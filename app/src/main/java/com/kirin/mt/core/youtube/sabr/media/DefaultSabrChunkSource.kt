package com.kirin.mt.core.youtube.sabr.media

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.C.TrackType
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.chunk.BaseMediaChunkIterator
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor
import androidx.media3.exoplayer.source.chunk.Chunk
import androidx.media3.exoplayer.source.chunk.ChunkExtractor
import androidx.media3.exoplayer.source.chunk.ChunkHolder
import androidx.media3.exoplayer.source.chunk.ContainerMediaChunk
import androidx.media3.exoplayer.source.chunk.InitializationChunk
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions
import androidx.media3.extractor.ChunkIndex
import java.time.Instant

/**
 * alpha.64(端口 LibreTube `DefaultSabrChunkSource`):默认 [SabrChunkSource] 实现。
 *
 * [getNextChunk] 构造 [SabrSegmentRequest] 塞进 [DataSpec.customData]:chunkIndex==null 时发 init 请求
 * ([InitializationChunk]),否则发 media 段请求([ContainerMediaChunk] + [BundledChunkExtractor])。
 * [onChunkLoadCompleted] 把 init 段解出的 [ChunkIndex] 存进 [RepresentationHolder](后续段定位用)。
 *
 * 对齐 LibreTube `player/DefaultSabrChunkSource.kt`(MIT)。适配:[SabrMediaFetcher] 替代 `SabrClient`;
 * [Representation.formatId] 替代 `representation.formatId()`(已是我们的 FormatId,无需 build);
 * [SabrSegmentRequest] 替代 `PlaybackRequest`(传 itag 而非 FormatId 对象)。
 */
@OptIn(UnstableApi::class)
internal class DefaultSabrChunkSource(
  chunkExtractorFactory: ChunkExtractor.Factory,
  private val manifest: SabrManifest,
  private val fetcher: SabrMediaFetcher,
  private val adaptationSetIndices: IntArray,
  private var trackSelection: ExoTrackSelection,
  private val trackType: @TrackType Int,
  private val dataSource: DataSource,
  private val playerId: PlayerId,
  private val bufferMaxMs: Long,
  private val bandwidthMeter: BandwidthMeter,
) : SabrChunkSource {

  /** [SabrChunkSource.Factory] for [DefaultSabrChunkSource]。 */
  class Factory(
    private val dataSourceFactory: DataSource.Factory,
    private val bufferMaxMs: Long,
    private val bandwidthMeter: BandwidthMeter,
  ) : SabrChunkSource.Factory {
    private val chunkExtractorFactory = BundledChunkExtractor.Factory()

    override fun createSabrChunkSource(
      manifest: SabrManifest,
      fetcher: SabrMediaFetcher,
      adaptationSetIndices: IntArray,
      trackSelection: ExoTrackSelection,
      trackType: @TrackType Int,
      elapsedRealtimeOffsetMs: Long,
      transferListener: TransferListener?,
      playerId: PlayerId,
      cmcdConfiguration: CmcdConfiguration?,
    ): SabrChunkSource {
      val dataSource = dataSourceFactory.createDataSource()
      transferListener?.let { dataSource.addTransferListener(it) }
      return DefaultSabrChunkSource(
        chunkExtractorFactory,
        manifest,
        fetcher,
        adaptationSetIndices,
        trackSelection,
        trackType,
        dataSource,
        playerId,
        bufferMaxMs,
        bandwidthMeter,
      )
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format =
      chunkExtractorFactory.getOutputTextFormat(sourceFormat)
  }

  private val representationHolders: MutableList<RepresentationHolder>

  /** P11-130:已上报预取过的档(itag)——同一档只预取一次,避免持续白吃带宽。 */
  private val prefetchedTiers = mutableSetOf<Int>()

  init {
    val representations =
      adaptationSetIndices.flatMap { manifest.adaptationSets[it].representations }.toList()
    representationHolders = (0..<trackSelection.length()).map {
      val representation = representations[trackSelection.getIndexInTrackGroup(it)]
      RepresentationHolder(
        Util.msToUs(manifest.durationMs),
        representation,
        chunkExtractorFactory.createProgressiveMediaExtractor(
          trackType,
          representation.format,
          false,
          emptyList(),
          null,
          playerId,
        ),
      )
    }.toMutableList()
    // alpha.9X 决定性诊断:打印 manifest adaptation set 结构(每 set 的 itag 数)+ trackSelection.length()。
    // 判定「旧缓存包(manifest 仍按 mime 拆组 → 视频被拆成多个单轨组)」还是「单组 5 轨但 DefaultTrackSelector
    // 仍只选 1 轨」。fmts= 一行即可见:若视频组是 [243] 一个,是旧包;若 [243 244 …] 多个但仍 sel=1,是选轨器问题。
    // alpha.97(修「Auto 永不升过 1080p」诊断):补 excluded= —— TrackGroup 全量 itag 减去 selection 已吸收的,
    // 直接分辨 1440p/2160p 等「进了组没被选」vs「根本没进组(ADAPTIVE 资格被否)」。
    val selectedIndices = (0..<trackSelection.length()).map { trackSelection.getIndexInTrackGroup(it) }
    val excludedItags = representations.indices
      .filterNot { it in selectedIndices }
      .map { representations[it].formatId.itag }
    Log.i(
      "YtSabrChunk",
      "init trackType=$trackType trackSelLen=${trackSelection.length()} sets=${
        manifest.adaptationSets.mapIndexed { si, set ->
          "set$si[${set.type}]=" + set.representations.joinToString { it.formatId.itag.toString() }
        }
      } selected=[${
        (0..<trackSelection.length()).joinToString { i ->
          val idx = trackSelection.getIndexInTrackGroup(i)
          "${trackSelection.getFormat(i).id}($idx)b=${trackSelection.getFormat(i).bitrate}"
        }
      }] excluded=[$excludedItags]"
    )
  }

  init {
    // alpha.9X(带宽驱动选档,替代 exclude/force 补丁):把真实带宽来源注入 [SabrBandwidthMeter]——
    // media3 带宽计被 SabrDataSource 喂的内存瞬时读样本污染(bw= 1M↔437M 跳变),effectiveBitrate 不可信;
    // 改为向带宽计返回 SabrMediaFetcher 实测真实带宽(中位数)。AdaptiveTrackSelection 据此原生 ABR 选档,
    // 不再需要 ceiling/force-climb 排除补丁。见 SabrBandwidthMeter / SabrMediaFetcher。
    (bandwidthMeter as? SabrBandwidthMeter)?.setRealBandwidthProvider { fetcher.getRealBitrateEstimate() }
    (bandwidthMeter as? SabrBandwidthMeter)?.setSustainedBandwidthProvider { fetcher.getSustainedBitrateEstimate() }
    (bandwidthMeter as? SabrBandwidthMeter)?.setRefillCapacityProvider { fetcher.getRefillCapacityBps() }
    (bandwidthMeter as? SabrBandwidthMeter)?.setReseedBandwidthProvider { bitrateBps ->
      fetcher.reseedActiveWindow(bitrateBps)
    }
    (bandwidthMeter as? SabrBandwidthMeter)?.setMeasuredBitrateProvider { itag ->
      fetcher.getMeasuredBitrateBps(itag)
    }
    (bandwidthMeter as? SabrBandwidthMeter)?.setMeasuredSegCountProvider { itag ->
      fetcher.getMeasuredSegmentCount(itag)
    }
  }

  override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
    fetcher.lastSeekMs = Instant.now().toEpochMilli()
    for (representationHolder in representationHolders) {
      if (representationHolder.chunkIndex != null) {
        val segmentCount = representationHolder.segmentCount
        if (segmentCount == 0L) continue
        val segmentNum = representationHolder.getSegmentNum(positionUs)
        val firstSyncUs = representationHolder.getSegmentStartTimeUs(segmentNum)
        val secondSyncUs = if (firstSyncUs < positionUs && segmentNum < segmentCount - 1)
          representationHolder.getSegmentStartTimeUs(segmentNum + 1) else firstSyncUs
        return seekParameters.resolveSeekPositionUs(positionUs, firstSyncUs, secondSyncUs)
      }
    }
    return positionUs
  }

  override fun updateTrackSelection(trackSelection: ExoTrackSelection) {
    // 2026-08-31 切轨停发诊断(log add,修「起播 4K→重载」取证):切轨时刻打点——旧/新选中档与
    // chunkIndex 状态。真机 20:04 复盘:720p→315(2160p)切轨后 getNextChunk 再未被调用(6s 空窗→
    // 看门狗重载),此日志区分「chunk source 拒发」vs「media3 loader 根本没来要」。
    val old = this.trackSelection
    Log.i(
      "YtSabrChunk",
      "updateTrackSelection: sel ${old.selectedIndex}(${old.selectedFormat?.height}p)" +
        " → ${trackSelection.selectedIndex}(${trackSelection.selectedFormat?.height}p) " +
        "len=${trackSelection.length()} newIndexHasChunkIndex=" +
        "${representationHolders.getOrNull(trackSelection.selectedIndex)?.chunkIndex != null}"
    )
    this.trackSelection = trackSelection
  }

  /**
   * P11-130(升档预加载):判断「下一档是否值得预取」并上报给 fetcher。
   *
   * 条件(全部满足才报,宁缺勿滥):①视频轨;②缓冲水位 ≥ [PREFETCH_MIN_BUFFERED_US](预取会占用串行
   * fetcher,缓冲薄时不许抢);③存在比当前档**分辨率更高**的下一档(阶梯是码率降序,index 越小越高档,
   * 故从 sel-1 往上找第一条 height 更大的);④该档声明码率 ≤ 实测带宽 × [PREFETCH_BW_MARGIN_PERMILLE]
   * (够不着就别预取);⑤同一档只报一次(避免持续白吃带宽)。
   *
   * 2026-09-20:②**是持续条件不是一次性检查**——生效窗口期内缓冲跌破
   * [PREFETCH_REVOKE_BUFFERED_US] 立即撤销(否则缓冲塌了还继续抢响应预算,把正在播的格式饿死)。
   */
  private fun maybePrefetchNextTier(bufferedDurationUs: Long) {
    // 2026-09-20:窗口是**持续条件**——缓冲跌破撤销线立即撤销(见 PREFETCH_REVOKE_BUFFERED_US)。
    // 放在最前:撤销要能抢在"又一次请求带上预取档"之前生效。
    if (bufferedDurationUs < PREFETCH_REVOKE_BUFFERED_US) {
      fetcher.cancelPrefetch("缓冲 ${bufferedDurationUs / 1_000_000}s 跌破撤销线")
      return
    }
    if (bufferedDurationUs < PREFETCH_MIN_BUFFERED_US) return
    val sel = trackSelection.selectedIndex
    val curHeight = representationHolders.getOrNull(sel)?.representation?.format?.height ?: return
    val nextIdx = (sel - 1 downTo 0).firstOrNull {
      representationHolders[it].representation.format.height > curHeight
    } ?: return
    val holder = representationHolders[nextIdx]
    val nextItag = holder.representation.formatId.itag
    if (!prefetchedTiers.add(nextItag)) return
    val nextBitrate = holder.representation.format.bitrate
    val bw = bandwidthMeter.getBitrateEstimate()
    if (bw <= 0L || nextBitrate > bw * PREFETCH_BW_MARGIN_PERMILLE / 1000L) return
    fetcher.prefetchFormat(nextItag)
  }

  override fun maybeThrowError() {
    // fatalError 由 fetcher.getNextSegment 抛 SabrTerminalException → DataSource open → chunk load error 通路,
    // 这里不重复 throw(对齐 LibreTube fatalError 走 getNextSegment throw)。
  }

  override fun getPreferredQueueSize(playbackPositionUs: Long, queue: MutableList<out MediaChunk>): Int {
    if (trackSelection.length() < 2) return queue.size
    return trackSelection.evaluateQueueSize(playbackPositionUs, queue)
  }

  override fun shouldCancelLoad(
    playbackPositionUs: Long,
    loadingChunk: Chunk,
    queue: MutableList<out MediaChunk>,
  ): Boolean {
    val cancel = trackSelection.shouldCancelChunkLoad(playbackPositionUs, loadingChunk, queue)
    // 2026-08-31 切轨停发诊断:取消在途 chunk 是「切轨后 loader 卡住」的候选链路之一(取消后
    // sample stream 重建,若不再来要 chunk 即坐实 loader 侧)。
    if (cancel) {
      Log.i(
        "YtSabrChunk",
        "shouldCancelLoad=true: canceling itag=${(loadingChunk.dataSpec.customData as? SabrSegmentRequest)?.formatItag}" +
          " type=${if (loadingChunk is InitializationChunk) "init" else "media"} posMs=${Util.usToMs(playbackPositionUs)}"
      )
    }
    return cancel
  }

  override fun getNextChunk(
    loadingInfo: LoadingInfo,
    loadPositionUs: Long,
    queue: List<MediaChunk>,
    out: ChunkHolder,
  ) {
    val playbackPositionUs = loadingInfo.playbackPositionUs
    val bufferedDurationUs = loadPositionUs - playbackPositionUs
    val previousChunk = queue.lastOrNull()

    // alpha.9Z:视频轨每次取 chunk 把「播放位置前方缓冲水位」喂给 fetcher,gap 计时据此扣减滑行量
    // (仅视频轨喂:音频轨缓冲远超需求,会污染判定)。见 SabrMediaFetcher.recordFetchGap。
    // 2026-09-20(修 r2018 实测:位置锚没生效):**播放位置必须两条轨都喂** —— 位置与轨道无关,
    // 而**本场第一个请求是音频轨的 init**(r2018 日志 `fetch rn=0 itag=140 seg=0 shape=ft`):
    // 原来只在视频轨分支里喂,音频轨先跑 ⇒ 那一笔请求时位置注入口还是 -1 ⇒ 锚为 null ⇒ 位置锚 0 次命中,
    // 服务端照旧从 seg 0 起推。缓冲水位(noteBufferedAheadMs)保持**仅视频轨**(音频缓冲远超需求会污染
    // 滑行量判定,见上),两者语义不同、不能一起搬出去。
    fetcher.notePlaybackPositionMs(Util.usToMs(playbackPositionUs))
    // 2026-09-20(C1):把「服务端实际推来的视频 itag」同步给选档 —— 材料会话里服务端只服务它那场会话
    // 绑定的格式(r2019:只推 251/396),我们选的档不被初始化 ⇒ no seg 死循环;选档收在这个集合内才通。
    (trackSelection as? HeightAwareAdaptiveTrackSelection)?.noteServerServedItags(fetcher.serverServedVideoItags())
    if (trackType == C.TRACK_TYPE_VIDEO) {
      fetcher.noteBufferedAheadMs(Util.usToMs(bufferedDurationUs))
      // P11-130(升档预加载):下一档可负担 + 缓冲健康 → 让 fetcher 提前把它的 init(+段)取回来,
      // 切档那刻直接命中缓存(否则每次升档现拉 2.5–7.2s,视频轨断流而音频照播)。
      maybePrefetchNextTier(bufferedDurationUs)
    }

    // 2026-08-31 B2:决策前快照——升/降档候选与合成 iterator 都基于「当前档」构(ABR 看到的是切换前
    // 状态,语义不变);staging 的 holder 与 fetcher 格式改到 updateSelectedTrack 之后重读(见下)。
    val preSelectionHolder = representationHolders[trackSelection.selectedIndex]
    // 增量升/降档(alpha.9X,修「Auto 起播低档后永不升档」):Auto 全轨自适应原把所有未下载轨的 iterator
    // 填 MediaChunkIterator.EMPTY,AdaptiveTrackSelection 看不到任何备选数据 → selectedIndex 冻结在低档
    // (默认带宽估计 ~1Mbps 起步如 itag244=480p),带宽涨了也无新轨可切。这里只给「当前档的下一高码率档」
    // (upgrade,升档)与「下一低码率档」(downgrade,网络崩时降档自救)各喂一个**基于当前档 chunkIndex 的
    // 合成 iterator**——YouTube 同视频各 itag 段网格时间对齐,段时序可复用,让 ABR 判定该档可切。
    // 切到该档后其 init 才按需加载(chunkIndex 变真),下一轮再给再下一档喂合成 iterator;已下载档保留真
    // iterator(可降回)。其余档仍 EMPTY(不参与切轨)→ 一次只升降一档、不越级、不预拉多轨 init。
    val currentBitrate =
      if (preSelectionHolder.chunkIndex != null) trackSelection.getFormat(trackSelection.selectedIndex).bitrate else -1
    var upgradeCandidateIndex = if (currentBitrate > 0) {
      (0..<trackSelection.length())
        .filter { trackSelection.getFormat(it).bitrate > currentBitrate }
        .minByOrNull { trackSelection.getFormat(it).bitrate }
    } else null
    val downgradeCandidateIndex = if (currentBitrate > 0) {
      (0..<trackSelection.length())
        .filter { trackSelection.getFormat(it).bitrate in 1 until currentBitrate }
        .maxByOrNull { trackSelection.getFormat(it).bitrate }
    } else null
    // alpha.9X 诊断:ABR 候选与带宽,定位「Auto 起播低档后不升档」。bitrate=-1(Format 未设)则 currentBitrate<=0
    // → up/down 恒 null → 未下载轨全 EMPTY → 退化成老 bug(钉死起始档)。bw= 现为 SabrBandwidthMeter 返回的
    // 真实带宽(中位数);对照 YtSabr fetch 实际吞吐(8-13M),确认带宽计已可信、media3 原生 ABR 按它选档。
    Log.i(
      "YtSabrAbr",
      "sel=${trackSelection.selectedIndex} bitrate=$currentBitrate bufS=${bufferedDurationUs / 1_000_000}.${
        bufferedDurationUs % 1_000_000 / 100_000
      } chunkIndex=${preSelectionHolder.chunkIndex != null} bw=${bandwidthMeter.getBitrateEstimate() / 1000}K " +
        // 2026-08-31 显示修正:sus 原值 -1(证据不足)曾被 -1/1000 整除打成 0K,取证时把「无证据」
        // 误读成「证据为零」;负值原样显示。
        "sus=${
          (bandwidthMeter as? SabrBandwidthMeter)?.getSustainedBitrateEstimateRaw()
            ?.let { if (it >= 0) "${it / 1000}K" else "-1" } ?: "-1"
        } " +
        // 2026-09-01 重填容量取证:近 N 笔成功请求瞬时吞吐中位数(免疫墙钟空转),升档判据用它与 bw 取大。
        "cap=${
          (bandwidthMeter as? SabrBandwidthMeter)?.getRefillCapacityEstimate()
            ?.let { if (it >= 0) "${it / 1000}K" else "-1" } ?: "-1"
        } " +
        "meas=${
          fetcher.getMeasuredBitrateBps(preSelectionHolder.representation.formatId.itag).div(1000)
        }K " +
        "up=$upgradeCandidateIndex down=$downgradeCandidateIndex fmts=${
          (0..<trackSelection.length()).joinToString { i ->
            val f = trackSelection.getFormat(i)
            "${representationHolders[i].representation.formatId.itag}[${if (f.bitrate > 0) f.bitrate else "NA"}]"
          }
        }"
    )
    trackSelection.updateSelectedTrack(
      playbackPositionUs,
      bufferedDurationUs,
      C.TIME_UNSET,
      queue,
      Array(representationHolders.size) { i ->
        val holder = representationHolders[i]
        val timing = when {
          holder.chunkIndex != null -> holder                 // 已下载轨:真 iterator(含当前档,可降回)
          i == upgradeCandidateIndex -> preSelectionHolder     // 下一高码率档:合成(升档)
          i == downgradeCandidateIndex -> preSelectionHolder  // 下一低码率档:合成(网络崩时降档)
          else -> null
        }
        if (timing == null) MediaChunkIterator.EMPTY
        else {
          val lastAvailableSegmentNum = timing.getLastAvailableSegmentNum()
          val segmentNum = previousChunk?.nextChunkIndex ?: Util.constrainValue(
            timing.getSegmentNum(loadPositionUs), 0, lastAvailableSegmentNum
          )
          RepresentationSegmentIterator(timing, segmentNum, lastAvailableSegmentNum)
        }
      },
    )

    // 2026-08-31 B2(对齐上游 media3 DashChunkSource:先 updateSelectedTrack 再读 selectedIndex):
    // 原实现在决策前取 holder 并喂 fetcher.selectFormat → 切档决策对同一次调用 staged 的段无效
    // (23:24:48.052 水位急救 315→308,2ms 后 staged 的请求仍载 315 段),每次切档(含急救/升档)
    // 白吃一个循环的生效延迟。决策后重读:急救/切档当次 staged 的段立即按新档发出。
    val representationHolder = representationHolders[trackSelection.selectedIndex]
    fetcher.selectFormat(representationHolder.representation)

    if (representationHolder.chunkExtractor != null && representationHolder.chunkIndex == null) {
      // 新格式首请求:init 段(服务端按 isInitSeg 发 fMP4 init,ChunkExtractor 从中解 ChunkIndex)
      val dataSpec = DataSpec.Builder()
        .setUri(manifest.sabrUrl)
        .setCustomData(
          SabrSegmentRequest.initRequest(
            representationHolder.representation.formatId.itag,
            Util.usToMs(playbackPositionUs),
            loadingInfo.playbackSpeed,
          )
        )
        .build()
      out.chunk = InitializationChunk(
        dataSource,
        dataSpec,
        trackSelection.selectedFormat,
        trackSelection.selectionReason,
        trackSelection.selectionData,
        representationHolder.chunkExtractor,
      )
      return
    }

    if (representationHolder.segmentCount == 0L) {
      // 2026-08-31 切轨停发诊断:endOfStream 回执(与「根本没来要」区分;chunkIndex 为 null 的
      // segmentCount==0 = 新轨 init 尚未解出段表却走到 media 分支,异常路径)。
      Log.w(
        "YtSabrChunk",
        "getNextChunk → endOfStream(segmentCount=0, chunkIndex=${representationHolder.chunkIndex != null})"
      )
      out.endOfStream = true
      return
    }

    val lastAvailableSegmentNum = representationHolder.getLastAvailableSegmentNum()
    val segmentNum = previousChunk?.nextChunkIndex ?: Util.constrainValue(
      representationHolder.getSegmentNum(loadPositionUs), 0, lastAvailableSegmentNum
    )

    if (segmentNum > lastAvailableSegmentNum) {
      Log.i(
        "YtSabrChunk",
        "getNextChunk → endOfStream(segmentNum $segmentNum > last $lastAvailableSegmentNum)"
      )
      out.endOfStream = true
      return
    }
    if (representationHolder.getSegmentStartTimeUs(segmentNum) >= representationHolder.periodDurationUs) {
      Log.i("YtSabrChunk", "getNextChunk → endOfStream(startTimeUs >= periodDurationUs)")
      out.endOfStream = true
      return
    }

    val seekTimeUs = if (queue.isEmpty()) loadPositionUs else C.TIME_UNSET
    val startTimeUs = representationHolder.getSegmentStartTimeUs(segmentNum)
    // P11-85 诊断:续播黑屏(位置冻结、数据就位、永不 READY)取证——每轨首 media chunk 打点:
    // loadPosition(裁剪起点)与段号/段起点的映射关系,下轮复盘首段裁剪是否吃掉关键帧。
    if (queue.isEmpty()) {
      Log.i(
        "YtSabrChunk",
        "first media chunk: trackType=$trackType loadPositionMs=${Util.usToMs(loadPositionUs)}" +
          " segmentNum=$segmentNum startMs=${Util.usToMs(startTimeUs)} clipMs=${Util.usToMs(seekTimeUs)}" +
          " itag=${representationHolder.representation.formatId.itag}",
      )
    }
    val bufferedSegments = queue.mapNotNull { (it.dataSpec.customData as SabrSegmentRequest?)?.segment }
    val dataSpec = DataSpec.Builder()
      .setUri(manifest.sabrUrl)
      .setCustomData(
        SabrSegmentRequest(
          representationHolder.representation.formatId.itag,
          Util.usToMs(playbackPositionUs),
          loadingInfo.playbackSpeed,
          // chunk index 不把 init 段算作 seg 0,所以 media 段号 = segmentNum + 1
          segmentNum + 1,
          Util.usToMs(startTimeUs),
          bufferedSegments,
        )
      )
      .build()

    out.chunk = ContainerMediaChunk(
      dataSource,
      dataSpec,
      trackSelection.selectedFormat,
      trackSelection.selectionReason,
      trackSelection.selectionData,
      startTimeUs,
      representationHolder.getSegmentEndTimeUs(segmentNum),
      seekTimeUs,
      representationHolder.periodDurationUs,
      segmentNum,
      1,
      // P11-90/91:mp4 段的 tfdt 已被 SabrDataSource 相对化(首值→0),样本时间整体平移到段
      // 网格起点——复刻 media3 <1.10 老 ChunkExtractorWrapper 的「首样本==startTimeUs」自校准。
      // P11-91 修了 tfdt 采集遍历 bug(此前补丁全程 no-op,offset 叠在原始绝对 tfdt 上时间轴
      // 翻倍,「播3秒跳10s」)。webm(VP9/AV1)轨无 tfdt,MatroskaExtractor 的 cluster 时间戳
      // 是绝对值(与网格一致),offset 必须保持 0,否则同样翻倍——按容器分流。
      if (trackSelection.selectedFormat.containerMimeType?.endsWith("webm") == true) 0L else startTimeUs,
      representationHolder.chunkExtractor!!,
    )
  }

  override fun onChunkLoadCompleted(chunk: Chunk) {
    // 2026-08-31 切轨停发诊断(log add):chunk 交付回执——真机 20:04 里 315 的 init+2 段全部
    // 交付后 loader 再无 getNextChunk(6s 空窗→看门狗),此日志界定「数据到位 vs media3 不再来要」。
    Log.i(
      "YtSabrChunk",
      "chunk completed: ${if (chunk is InitializationChunk) "init" else "media"}" +
        " itag=${(chunk.dataSpec.customData as? SabrSegmentRequest)?.formatItag}" +
        " bytes=${chunk.bytesLoaded()} sel=${trackSelection.selectedIndex}(${trackSelection.selectedFormat?.height}p)"
    )
    if (chunk is InitializationChunk) {
      val trackIndex = trackSelection.indexOf(chunk.trackFormat)
      val representationHolder = representationHolders[trackIndex]
      if (representationHolder.chunkIndex == null) {
        representationHolder.chunkExtractor?.chunkIndex?.let {
          representationHolders[trackIndex].chunkIndex = it
          // P11-85:段表(绝对时间网格)回喂 fetcher——buildBufferedRanges 上报真实
          // startTimeMs/durationMs 用(header.startMs/durationMs 服务端恒回 0,续播场景
          // 垃圾 ranges 会把服务端回落判定带偏 → 位置冻结黑屏,详见 SabrSegment)。
          val itag = representationHolder.representation.formatId.itag
          val timesMs = LongArray(it.length) { i -> Util.usToMs(it.timesUs[i]) }
          fetcher.registerSegmentGrid(itag, timesMs)
        }
      }
    }
  }

  override fun onChunkLoadError(
    chunk: Chunk,
    cancelable: Boolean,
    loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
  ): Boolean {
    // 2026-08-31 切轨停发诊断:错误也打点(静默 fallback 会表现为「不请求了」)。
    Log.w(
      "YtSabrChunk",
      "chunk load error: itag=${(chunk.dataSpec.customData as? SabrSegmentRequest)?.formatItag}" +
        " type=${if (chunk is InitializationChunk) "init" else "media"}" +
        " cancelable=$cancelable error=${loadErrorInfo.exception.javaClass.simpleName}" +
        ": ${loadErrorInfo.exception.message?.take(120)}"
    )
    if (!cancelable) return false
    // alpha.64:SabrDataSource 在 SabrTerminalException 时已 evict 会话;这里走默认 fallback 逻辑。
    // 404 末段兜底(对齐 LibreTube missingLastSegment 处理)。
    if (chunk is MediaChunk && loadErrorInfo.exception is HttpDataSource.InvalidResponseCodeException) {
      val code = (loadErrorInfo.exception as HttpDataSource.InvalidResponseCodeException).responseCode
      if (code == 404) {
        val representationHolder = representationHolders[trackSelection.indexOf(chunk.trackFormat)]
        val segmentCount = representationHolder.segmentCount
        if (segmentCount != 0L && chunk.nextChunkIndex > segmentCount - 1) {
          return true
        }
      }
    }
    val fallbackOptions = createFallbackOptions(trackSelection)
    if (!fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK) &&
      !fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)
    ) {
      return false
    }
    val fallbackSelection = loadErrorHandlingPolicy.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    if (fallbackSelection == null || !fallbackOptions.isFallbackAvailable(fallbackSelection.type)) {
      return false
    }
    var cancelLoad = false
    if (fallbackSelection.type == LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK) {
      cancelLoad = trackSelection.excludeTrack(
        trackSelection.indexOf(chunk.trackFormat), fallbackSelection.exclusionDurationMs
      )
    }
    return cancelLoad
  }

  override fun release() {
    for (representationHolder in representationHolders) {
      representationHolder.chunkExtractor?.release()
    }
  }

  private fun createFallbackOptions(trackSelection: ExoTrackSelection): FallbackOptions {
    val nowMs = SystemClock.elapsedRealtime()
    val numberOfTracks = trackSelection.length()
    var numberOfExcludedTracks = 0
    for (i in 0..<numberOfTracks) {
      if (trackSelection.isTrackExcluded(i, nowMs)) numberOfExcludedTracks++
    }
    return FallbackOptions(0, 0, numberOfTracks, numberOfExcludedTracks)
  }

  /** [MediaChunkIterator] wrapping a [RepresentationHolder]。 */
  internal class RepresentationSegmentIterator(
    private val representationHolder: RepresentationHolder,
    firstAvailableSegmentNum: Long,
    lastAvailableSegmentNum: Long,
  ) : BaseMediaChunkIterator(firstAvailableSegmentNum, lastAvailableSegmentNum) {
    override fun getDataSpec(): DataSpec {
      checkInBounds()
      return DataSpec.Builder().setUri("sabr://unused").build()
    }

    override fun getChunkStartTimeUs(): Long {
      checkInBounds()
      return representationHolder.getSegmentStartTimeUs(currentIndex)
    }

    override fun getChunkEndTimeUs(): Long {
      checkInBounds()
      return representationHolder.getSegmentEndTimeUs(currentIndex)
    }
  }

  /** 单 [Representation] 快照 + 从 init 段解出的 [ChunkIndex](段定位用)。 */
  internal data class RepresentationHolder(
    val periodDurationUs: Long,
    val representation: Representation,
    val chunkExtractor: ChunkExtractor?,
  ) {
    var chunkIndex: ChunkIndex? = null

    val segmentCount: Long
      get() = chunkIndex?.length?.toLong() ?: 0

    fun getSegmentStartTimeUs(segmentNum: Long): Long = chunkIndex!!.timesUs[segmentNum.toInt()]
    fun getSegmentEndTimeUs(segmentNum: Long): Long =
      getSegmentStartTimeUs(segmentNum) + chunkIndex!!.durationsUs[segmentNum.toInt()]
    fun getSegmentNum(positionUs: Long): Long = chunkIndex!!.getChunkIndex(positionUs).toLong()
    fun getLastAvailableSegmentNum(): Long = chunkIndex!!.length.toLong() - 1
  }
}

/**
 * P11-130(升档预加载)阈值。
 *
 * [PREFETCH_MIN_BUFFERED_US]:缓冲水位门槛——预取占用**串行** fetcher,缓冲薄时不许抢(与"满缓冲试探"
 * 同源的思路:有富余才做额外动作)。
 * [PREFETCH_BW_MARGIN_PERMILLE]:下一档声明码率相对实测带宽的上限(0.8×)——够不着就别预取,白吃带宽。
 *
 * 2026-09-20([PREFETCH_REVOKE_BUFFERED_US],修「预取窗口期内抢响应预算 → 正在播的格式饿死」):
 * 上面两条此前**只在进入时检查一次**,此后 30s 窗口(SabrMediaFetcher 的 PREFETCH_WINDOW_MS)一路照问不误
 * ——真机两场(`logs_live_20260920_135925.log` / `logs_live_20260920_142332.log`,视频 UblCOS7McLg):
 * 播 720p 时预取候选 = 1080p itag335,服务端**照办**(每个响应带 `FORMAT_INIT itag=335` + 5 个 335 段
 * ≈7.87MB),而白名单只认 `[140,698]` → 整段丢弃(且 `initializedFormats[335]` 连建都不建,见
 * SabrMediaFetcher 两处白名单);更要命的是**响应预算被 335 占满,当次请求的 698 段根本不来** →
 * `no seg` → 重试 6 次 → `terminal → evict` → 新会话(窗口还在)再问一次 335。两场各 12/13 次,
 * 「零可用数据 32.8s / 25s」窗口**与预取窗口逐帧吻合**(13:57:35.977 宣布 → 13:58:05.98 到期 →
 * 13:58:08.843 窗口到期后第一笔立刻拿到 698 seg 12/13/14;14:22:29.994 宣布 → 14:22:59.99 到期 →
 * 14:23:01.206 第一笔立刻拿到 698 段)。
 *
 * 修法:**窗口是持续条件,不是进入时的一次性检查**——缓冲跌破 [PREFETCH_REVOKE_BUFFERED_US] 就立即撤销。
 * 撤销线比进入线低 5s 作滞回带,防缓冲在 20s 上下自然抖动时被一次轻微回落永久关掉。
 * 撤销是**单向**的(该档仍留在 chunk source 的 `prefetchedTiers`,`prefetchedTiers` 的原意「同一档只报
 * 一次、避免持续白吃带宽」不变):本会话不再重试该档。
 */
private const val PREFETCH_MIN_BUFFERED_US = 20_000_000L
private const val PREFETCH_REVOKE_BUFFERED_US = 15_000_000L
private const val PREFETCH_BW_MARGIN_PERMILLE = 800L
