package com.kirin.mt.core.youtube.sabr.media

import android.util.Pair
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.C.TrackType
import androidx.media3.common.Format
import androidx.media3.common.StreamKey
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.CompositeSequenceableLoaderFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SequenceableLoader
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.chunk.ChunkSampleStream
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.Arrays

/**
 * alpha.64(端口 LibreTube `SabrMediaPeriod`):SABR 流的 [MediaPeriod]。
 *
 * 从 [SabrManifest.adaptationSets] 建 [TrackGroupArray](单视频 + 单音频)→ [selectTracks] 时为每轨建
 * 一个 [ChunkSampleStream] 共享**一个** [SabrMediaFetcher](单流)。A/V 在此层 demux 成两路 sample stream,
 * 但底层共享同一 SABR 会话/fetcher——这是修 60s 断崖的核心(单流 vs 双流)。
 *
 * 对齐 LibreTube `player/SabrMediaPeriod.kt`(MIT)。适配:`fetcher` 替代 `sabrClient`;
 * selectFormat/lastManualFormatSelectionMs/lastActionMs 走 fetcher。
 */
@OptIn(UnstableApi::class)
internal class SabrMediaPeriod(
  private val manifest: SabrManifest,
  private val fetcher: SabrMediaFetcher,
  private val periodIndex: Int,
  private val chunkSourceFactory: SabrChunkSource.Factory,
  private val transferListener: TransferListener?,
  private val cmcdConfiguration: CmcdConfiguration?,
  private val drmSessionManager: DrmSessionManager,
  private val drmEventDispatcher: DrmSessionEventListener.EventDispatcher,
  private val loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
  private val mediaSourceEventDispatcher: MediaSourceEventListener.EventDispatcher,
  private val elapsedRealtimeOffsetMs: Long,
  private val allocator: Allocator,
  private val compositeSequenceableLoaderFactory: CompositeSequenceableLoaderFactory,
  private val playerId: PlayerId,
) : MediaPeriod, SequenceableLoader.Callback<ChunkSampleStream<SabrChunkSource>> {

  private val trackGroups: TrackGroupArray
  private val trackGroupInfos: Array<TrackGroupInfo>

  private var callback: MediaPeriod.Callback? = null
  private var sampleStreams: Array<ChunkSampleStream<SabrChunkSource?>> = emptyArray()
  private var compositeSequenceableLoader: SequenceableLoader = compositeSequenceableLoaderFactory.empty()
  private var canReportInitialDiscontinuity = true
  private var initialStartTimeUs: Long = 0

  init {
    val result = buildTrackGroups(drmSessionManager, chunkSourceFactory, manifest.adaptationSets)
    trackGroups = result.first
    trackGroupInfos = result.second as Array<TrackGroupInfo>
  }

  fun release() {
    sampleStreams.forEach { it.release(null) }
    callback = null
  }

  override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
    this.callback = callback
    callback.onPrepared(this)
  }

  override fun maybeThrowPrepareError() {}

  override fun getTrackGroups(): TrackGroupArray = trackGroups

  override fun getStreamKeys(trackSelections: MutableList<ExoTrackSelection>): List<StreamKey> {
    val manifestAdaptationSets = manifest.adaptationSets
    val streamKeys = mutableListOf<StreamKey>()
    for (trackSelection in trackSelections) {
      val trackGroupIndex = trackGroups.indexOf(trackSelection.trackGroup)
      val trackGroupInfo = trackGroupInfos[trackGroupIndex]
      val adaptationSetIndices = trackGroupInfo.adaptationSetIndices
      val trackIndices = IntArray(trackSelection.length())
      for (i in 0..<trackSelection.length()) trackIndices[i] = trackSelection.getIndexInTrackGroup(i)
      Arrays.sort(trackIndices)
      var currentAdaptationSetIndex = 0
      var totalTracksInPreviousAdaptationSets = 0
      var tracksInCurrentAdaptationSet = manifestAdaptationSets[adaptationSetIndices[0]].representations.size
      for (trackIndex in trackIndices) {
        while (trackIndex >= totalTracksInPreviousAdaptationSets + tracksInCurrentAdaptationSet) {
          currentAdaptationSetIndex++
          totalTracksInPreviousAdaptationSets += tracksInCurrentAdaptationSet
          tracksInCurrentAdaptationSet =
            manifestAdaptationSets[adaptationSetIndices[currentAdaptationSetIndex]].representations.size
        }
        streamKeys.add(
          StreamKey(periodIndex, adaptationSetIndices[currentAdaptationSetIndex], trackIndex - totalTracksInPreviousAdaptationSets)
        )
      }
    }
    return streamKeys
  }

  override fun selectTracks(
    selections: Array<ExoTrackSelection?>,
    mayRetainStreamFlags: BooleanArray,
    streams: Array<SampleStream?>,
    streamResetFlags: BooleanArray,
    positionUs: Long,
  ): Long {
    val streamIndexToTrackGroupIndex = getStreamIndexToTrackGroupIndex(selections)
    releaseDisabledStreams(selections, mayRetainStreamFlags, streams)
    selectNewStreams(selections, streams, streamResetFlags, positionUs, streamIndexToTrackGroupIndex)

    // 通知服务端格式已选(对齐 LibreTube lastManualFormatSelection/lastAction)
    val now = System.currentTimeMillis()
    fetcher.lastManualFormatSelectionMs = now
    fetcher.lastActionMs = now

    val sampleStreamList: MutableList<ChunkSampleStream<SabrChunkSource?>> = mutableListOf()
    for (sampleStream in streams) {
      if (sampleStream is ChunkSampleStream<*>) {
        @Suppress("UNCHECKED_CAST")
        sampleStreamList.add(sampleStream as ChunkSampleStream<SabrChunkSource?>)
      }
    }
    sampleStreams = sampleStreamList.toTypedArray()
    compositeSequenceableLoader = compositeSequenceableLoaderFactory.create(
      sampleStreams.toList(),
      sampleStreamList.map { listOf(it.primaryTrackType) },
    )
    if (canReportInitialDiscontinuity) {
      canReportInitialDiscontinuity = false
      initialStartTimeUs = positionUs
    }
    return positionUs
  }

  override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
    sampleStreams.map { it.discardBuffer(positionUs, toKeyframe) }
  }

  override fun reevaluateBuffer(positionUs: Long) {
    sampleStreams.filter { !it.isLoading }.forEach {
      val manifestDurationUs = Util.msToUs(manifest.durationMs)
      it.discardUpstreamSamplesForClippedDuration(manifestDurationUs)
    }
    compositeSequenceableLoader.reevaluateBuffer(positionUs)
  }

  override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
    compositeSequenceableLoader.continueLoading(loadingInfo)

  override fun isLoading(): Boolean = compositeSequenceableLoader.isLoading

  override fun getNextLoadPositionUs(): Long = compositeSequenceableLoader.nextLoadPositionUs

  override fun readDiscontinuity(): Long {
    for (sampleStream in sampleStreams) {
      if (sampleStream.consumeInitialDiscontinuity()) return initialStartTimeUs
    }
    return C.TIME_UNSET
  }

  override fun getBufferedPositionUs(): Long = compositeSequenceableLoader.bufferedPositionUs

  override fun seekToUs(positionUs: Long): Long {
    sampleStreams.forEach { it.seekToUs(positionUs) }
    return positionUs
  }

  override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
    for (sampleStream in sampleStreams) {
      if (sampleStream.primaryTrackType == C.TRACK_TYPE_VIDEO) {
        return sampleStream.getAdjustedSeekPositionUs(positionUs, seekParameters)
      }
    }
    return positionUs
  }

  override fun onContinueLoadingRequested(sampleStream: ChunkSampleStream<SabrChunkSource>) {
    callback!!.onContinueLoadingRequested(this)
  }

  private fun getStreamIndexToTrackGroupIndex(selections: Array<ExoTrackSelection?>): IntArray {
    val out = IntArray(selections.size)
    for (i in selections.indices) {
      out[i] = if (selections[i] != null) trackGroups.indexOf(selections[i]!!.trackGroup) else C.INDEX_UNSET
    }
    return out
  }

  private fun releaseDisabledStreams(
    selections: Array<ExoTrackSelection?>,
    mayRetainStreamFlags: BooleanArray,
    streams: Array<SampleStream?>,
  ) {
    for (i in selections.indices) {
      if (selections[i] == null || !mayRetainStreamFlags[i]) {
        if (streams[i] is ChunkSampleStream<*>) {
          @Suppress("UNCHECKED_CAST")
          (streams[i] as ChunkSampleStream<SabrChunkSource?>).release(null)
        }
        streams[i] = null
      }
    }
  }

  private fun selectNewStreams(
    selections: Array<ExoTrackSelection?>,
    streams: Array<SampleStream?>,
    streamResetFlags: BooleanArray,
    positionUs: Long,
    streamIndexToTrackGroupIndex: IntArray,
  ) {
    for (i in selections.indices) {
      val selection = selections[i] ?: continue
      val trackGroupIndex = streamIndexToTrackGroupIndex[i]
      val trackGroupInfo = trackGroupInfos[trackGroupIndex]
      val representation =
        manifest.adaptationSets[trackGroupInfo.adaptationSetIndices[0]].representations[selection.getIndexInTrackGroup(0)]
      fetcher.selectFormat(representation)
      if (streams[i] == null) {
        streamResetFlags[i] = true
        streams[i] = buildSampleStream(trackGroupInfo, selection, positionUs)
      } else if (streams[i] is ChunkSampleStream<*>) {
        @Suppress("UNCHECKED_CAST")
        (streams[i] as ChunkSampleStream<SabrChunkSource?>).getChunkSource().updateTrackSelection(selection)
      }
    }
  }

  private fun buildSampleStream(
    trackGroupInfo: TrackGroupInfo, selection: ExoTrackSelection, positionUs: Long,
  ): ChunkSampleStream<SabrChunkSource?> {
    val chunkSource = chunkSourceFactory.createSabrChunkSource(
      manifest,
      fetcher,
      trackGroupInfo.adaptationSetIndices,
      selection,
      trackGroupInfo.trackType,
      elapsedRealtimeOffsetMs,
      transferListener,
      playerId,
      cmcdConfiguration,
    )
    return ChunkSampleStream<SabrChunkSource?>(
      trackGroupInfo.trackType,
      null,
      null,
      chunkSource!!,
      this,
      allocator,
      positionUs,
      drmSessionManager,
      drmEventDispatcher,
      loadErrorHandlingPolicy,
      mediaSourceEventDispatcher,
      canReportInitialDiscontinuity,
      C.TIME_UNSET, // firstChunkStartTimeUs:1.10.0 新增(1.9.2 无此参),C.TIME_UNSET=未指定(对齐 LibreTube 1.9.2 不传此值的语义)
      null,
    )
  }

  private data class TrackGroupInfo(
    val trackType: @TrackType Int,
    val adaptationSetIndices: IntArray,
    val primaryTrackGroupIndex: Int,
  )

  companion object {
    private fun buildTrackGroups(
      drmSessionManager: DrmSessionManager,
      chunkSourceFactory: SabrChunkSource.Factory,
      adaptationSets: List<AdaptationSet>,
    ): Pair<TrackGroupArray, Array<TrackGroupInfo?>> {
      val groupedAdaptationSetIndices = getGroupedAdaptationSetIndices(adaptationSets)
      val primaryGroupCount = groupedAdaptationSetIndices.size
      val trackGroups = arrayOfNulls<TrackGroup>(primaryGroupCount)
      val trackGroupInfos = arrayOfNulls<TrackGroupInfo>(primaryGroupCount)
      buildPrimaryTrackGroupInfos(
        drmSessionManager, chunkSourceFactory, adaptationSets,
        groupedAdaptationSetIndices, primaryGroupCount, trackGroups, trackGroupInfos,
      )
      return Pair.create(
        TrackGroupArray(*trackGroups as Array<out TrackGroup>),
        trackGroupInfos,
      )
    }

    private fun getGroupedAdaptationSetIndices(adaptationSets: List<AdaptationSet>): Array<IntArray> =
      MutableList(adaptationSets.size) { mutableListOf(it) }.map { group -> group.sorted().toIntArray() }.toTypedArray()

    private fun buildPrimaryTrackGroupInfos(
      drmSessionManager: DrmSessionManager,
      chunkSourceFactory: SabrChunkSource.Factory,
      adaptationSets: List<AdaptationSet>,
      groupedAdaptationSetIndices: Array<IntArray>,
      primaryGroupCount: Int,
      trackGroups: Array<TrackGroup?>,
      trackGroupInfos: Array<TrackGroupInfo?>,
    ): Int {
      var trackGroupCount = 0
      for (i in 0..<primaryGroupCount) {
        val adaptationSetIndices = groupedAdaptationSetIndices[i]
        val representations: MutableList<Representation> = mutableListOf()
        for (adaptationSetIndex in adaptationSetIndices) {
          representations.addAll(adaptationSets[adaptationSetIndex].representations)
        }
        val formats = arrayOfNulls<Format>(representations.size)
        for (j in formats.indices) {
          val originalFormat = representations[j].format
          val updatedFormat = originalFormat.buildUpon().setCryptoType(drmSessionManager.getCryptoType(originalFormat))
          formats[j] = updatedFormat.build()
        }
        val firstAdaptationSet = adaptationSets[adaptationSetIndices[0]]
        val trackGroupId = "sabr:$i"
        val primaryTrackGroupIndex = trackGroupCount++
        maybeUpdateFormatsForParsedText(chunkSourceFactory, formats)
        trackGroups[primaryTrackGroupIndex] = TrackGroup(trackGroupId, *(formats.filterNotNull().toTypedArray()))
        trackGroupInfos[primaryTrackGroupIndex] =
          TrackGroupInfo(firstAdaptationSet.type, adaptationSetIndices, primaryTrackGroupIndex)
      }
      return trackGroupCount
    }

    private fun maybeUpdateFormatsForParsedText(
      chunkSourceFactory: SabrChunkSource.Factory, formats: Array<Format?>,
    ) {
      for (i in formats.indices) {
        formats[i] = chunkSourceFactory.getOutputTextFormat(formats[i]!!)
      }
    }
  }
}
