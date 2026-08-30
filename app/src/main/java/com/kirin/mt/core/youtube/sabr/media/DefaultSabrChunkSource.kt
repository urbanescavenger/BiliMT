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
    (bandwidthMeter as? SabrBandwidthMeter)?.setReseedBandwidthProvider { bitrateBps ->
      fetcher.reseedActiveWindow(bitrateBps)
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
    this.trackSelection = trackSelection
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
    return trackSelection.shouldCancelChunkLoad(playbackPositionUs, loadingChunk, queue)
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
    if (trackType == C.TRACK_TYPE_VIDEO) {
      fetcher.noteBufferedAheadMs(Util.usToMs(bufferedDurationUs))
    }

    val representationHolder = representationHolders[trackSelection.selectedIndex]
    fetcher.selectFormat(representationHolder.representation)
    // 增量升/降档(alpha.9X,修「Auto 起播低档后永不升档」):Auto 全轨自适应原把所有未下载轨的 iterator
    // 填 MediaChunkIterator.EMPTY,AdaptiveTrackSelection 看不到任何备选数据 → selectedIndex 冻结在低档
    // (默认带宽估计 ~1Mbps 起步如 itag244=480p),带宽涨了也无新轨可切。这里只给「当前档的下一高码率档」
    // (upgrade,升档)与「下一低码率档」(downgrade,网络崩时降档自救)各喂一个**基于当前档 chunkIndex 的
    // 合成 iterator**——YouTube 同视频各 itag 段网格时间对齐,段时序可复用,让 ABR 判定该档可切。
    // 切到该档后其 init 才按需加载(chunkIndex 变真),下一轮再给再下一档喂合成 iterator;已下载档保留真
    // iterator(可降回)。其余档仍 EMPTY(不参与切轨)→ 一次只升降一档、不越级、不预拉多轨 init。
    val currentBitrate =
      if (representationHolder.chunkIndex != null) trackSelection.getFormat(trackSelection.selectedIndex).bitrate else -1
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
      } chunkIndex=${representationHolder.chunkIndex != null} bw=${bandwidthMeter.getBitrateEstimate() / 1000}K " +
        "sus=${(bandwidthMeter as? SabrBandwidthMeter)?.getSustainedBitrateEstimate()?.div(1000) ?: -1L}K " +
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
          i == upgradeCandidateIndex -> representationHolder   // 下一高码率档:合成(升档)
          i == downgradeCandidateIndex -> representationHolder // 下一低码率档:合成(网络崩时降档)
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
      out.endOfStream = true
      return
    }

    val lastAvailableSegmentNum = representationHolder.getLastAvailableSegmentNum()
    val segmentNum = previousChunk?.nextChunkIndex ?: Util.constrainValue(
      representationHolder.getSegmentNum(loadPositionUs), 0, lastAvailableSegmentNum
    )

    if (segmentNum > lastAvailableSegmentNum) {
      out.endOfStream = true
      return
    }
    if (representationHolder.getSegmentStartTimeUs(segmentNum) >= representationHolder.periodDurationUs) {
      out.endOfStream = true
      return
    }

    val seekTimeUs = if (queue.isEmpty()) loadPositionUs else C.TIME_UNSET
    val startTimeUs = representationHolder.getSegmentStartTimeUs(segmentNum)
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
      0,
      representationHolder.chunkExtractor!!,
    )
  }

  override fun onChunkLoadCompleted(chunk: Chunk) {
    if (chunk is InitializationChunk) {
      val trackIndex = trackSelection.indexOf(chunk.trackFormat)
      val representationHolder = representationHolders[trackIndex]
      if (representationHolder.chunkIndex == null) {
        representationHolder.chunkExtractor?.chunkIndex?.let {
          representationHolders[trackIndex].chunkIndex = it
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
