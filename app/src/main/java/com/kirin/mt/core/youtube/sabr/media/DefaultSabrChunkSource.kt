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
) : SabrChunkSource {

  /** [SabrChunkSource.Factory] for [DefaultSabrChunkSource]。 */
  class Factory(
    private val dataSourceFactory: DataSource.Factory,
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
      )
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format =
      chunkExtractorFactory.getOutputTextFormat(sourceFormat)
  }

  private val representationHolders: MutableList<RepresentationHolder>

  /**
   * 降档后记忆的升档上限 bitrate(初始 [Int.MAX_VALUE] = 不限制,允许爬到清单最高档)。
   * 一旦因带宽/stall 发生降档(selectedIndex 往低码率走),把上限收紧到「刚降到的档」——
   * 后续升档不再超过它,防止降档后缓冲回满又冲回最高档无限震荡。想上更高档需手动选。
   */
  private var ceilingBitrate: Int = Int.MAX_VALUE

  /** 上一轮 getNextChunk 的 selectedIndex 对应 bitrate,用于检测降档跳变。 */
  private var lastSelectedBitrate: Int = -1

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
      }]"
    )
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

    val selectedIndex = trackSelection.selectedIndex
    var representationHolder = representationHolders[selectedIndex]
    fetcher.selectFormat(representationHolder.representation)
    // alpha.9X(封顶内自适应,修「Auto 起播低档永不升档」+「爬最高被看门狗整段重载」):Auto 全轨自适应原把
    // 未下载轨的 iterator 全填 EMPTY → selectedIndex 冻结在低档永不升档。现在把**封顶内所有轨道**都喂「基于
    // 当前档 chunkIndex 的合成 iterator」(YouTube 同视频各 itag 段网格时间对齐,段时序可复用),让 ABR 在封顶内
    // 自由升/降;封顶 = 实测带宽 × 1.15(见下),**超封顶轨喂 EMPTY → AdaptiveTrackSelection 永远选不到 → 不回
    // 爬最高档**。已下载档保留真 iterator(可降回);切到未下载档后其 init 才按需加载。
    val currentBitrate =
      if (representationHolder.chunkIndex != null) trackSelection.getFormat(trackSelection.selectedIndex).bitrate else -1
    // alpha.9X(降档后记忆封顶):顶配档(如 itag313 2160p 17.8Mbps)常因网络/解码扛不住——升上去就 stall→
    // 降档→缓冲回满又升,无限震荡(真机 19:17 日志 sel 在 2160↔1080 间反复跳)。修法不在「从一开始就不升
    // 最高」,而是**降档后记忆**:初始允许爬到清单最高档;一旦检测到降档(selectedIndex 比上一轮往低码率走),
    // 把升档上限收紧到刚降到的档,后续升档不再超过它 → 停在网络能撑的档、不再冲回顶。想上更高档需手动选。
    if (currentBitrate > 0 && lastSelectedBitrate > 0 && currentBitrate < lastSelectedBitrate) {
      ceilingBitrate = currentBitrate // 降档:封顶到当前(降档后的)档
    }
    if (currentBitrate > 0) lastSelectedBitrate = currentBitrate
    // alpha.9X(带宽封顶,硬约束):实测带宽 × 1.15 与降档记忆封顶(ceilingBitrate)取小作 cap。**高于 cap 的轨
    // 一律不暴露 iterator 给 AdaptiveTrackSelection(含已加载轨,喂 EMPTY)——它选不到就死锁在最高档,杜绝
    // 「爬 2160p → 撑不起 → 看门狗整段重载 → 新会话又爬回」死循环。日志铁证:ceiling=271 却 sel=0 2160p,
    // 说明靠 synthetic 候选的 ceiling 挡不住已加载轨回爬,必须从 iterator 层面断掉。
    val bwBps = SabrMediaFetcher.sharedBandwidthBps
    val unprovenCap = if (trackSelection.length() == 0) 0 else {
      val target = (0..<trackSelection.length()).minByOrNull {
        val h = representationHolders[it].representation.format.height
        if (h > 0) Math.abs(h - 480) else Int.MAX_VALUE
      }
      if (target != null) trackSelection.getFormat(target).bitrate else 0
    }
    val capBps = if (bwBps > 0) ceilingBitrate.toLong().coerceAtMost((bwBps * 115) / 100).toInt()
      else ceilingBitrate.coerceAtMost(unprovenCap)
    val isWithinCap = { i: Int -> trackSelection.getFormat(i).bitrate in 1..capBps }
    val capIndex = (0..<trackSelection.length()).firstOrNull { trackSelection.getFormat(it).bitrate >= capBps }
    // alpha.9X(硬钳制取档,修「起播/回爬 2160p 超大段 → 慢段拖死 → 看门狗整段重载」):AdaptiveTrackSelection 按
    // bitrate 选轨(DefaultTrackSelector 内部按码率重排,EMPTY iterator 挡不住停在已选高码率轨)→ 每次会话直接从
    // 最高档起播。这里**钳制实际取档**:selectedIndex 超 cap 时强制取 cap 内最高档,并用该档 representation 作 chunk
    // trackFormat(见下方 chunk 构建,indexOf 可回查)。带宽未实测(首会话)用起步档(默认 480P)保守 seed——首段小、
    // 起播快,实测后逐档爬,杜绝一上来取 22MB 2160p 首段拖死。
    val fetchIndex = if (isWithinCap(selectedIndex)) selectedIndex
    else (0..<trackSelection.length()).filter { isWithinCap(it) }.maxByOrNull { trackSelection.getFormat(it).bitrate }
      ?: selectedIndex
    representationHolder = representationHolders[fetchIndex]
    fetcher.selectFormat(representationHolder.representation)
    val clampedBitrate = trackSelection.getFormat(fetchIndex).bitrate
    // 降档记忆在钳制档上重算:钳制后取档比上一轮低 → 封顶到当前档(后续升档不超过它,防降档后缓冲回满又冲回顶)。
    if (clampedBitrate > 0 && lastSelectedBitrate > 0 && clampedBitrate < lastSelectedBitrate) {
      ceilingBitrate = clampedBitrate
    }
    if (clampedBitrate > 0) lastSelectedBitrate = clampedBitrate
    Log.i(
      "YtSabrAbr",
      "sel=$fetchIndex(origin=${trackSelection.selectedIndex}) bitrate=$clampedBitrate bufS=${bufferedDurationUs / 1_000_000}.${
        bufferedDurationUs % 1_000_000 / 100_000
      } chunkIndex=${representationHolder.chunkIndex != null} bw=${bwBps / 1_000_000}.${bwBps % 1_000_000 / 100_000}Mbps " +
        "cap=${capIndex?.let { representationHolders[it].representation.formatId.itag } ?: "N"} fmts=${
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
          !isWithinCap(i) -> null                                  // 超带宽封顶:不暴露 → 永不选中(不回爬)
          holder.chunkIndex != null -> holder                       // ≤cap 已下载轨:真 iterator(当前档/可降回)
          representationHolder.chunkIndex != null -> representationHolder // ≤cap 未下载:合成(封顶内自适应)
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
        // 用 trackSelection.getFormat(fetchIndex) 而非 representation.format:后者可能与 selection 里的
        // Format 不相等,onChunkLoadCompleted 的 trackSelection.indexOf(trackFormat) 回查 -1 → representationHolders[-1]
        // IndexOutOfBounds 崩溃(真机坐实:每个会话首 chunk Source error,index -1 out of bounds for length 1)。
        trackSelection.getFormat(fetchIndex),
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
      // 同 init chunk:用 selection 内格式,保证 indexOf(trackFormat) 能回查到钳制档(repres格式可能不相等)。
      trackSelection.getFormat(fetchIndex),
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
