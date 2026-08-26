package com.kirin.mt.core.youtube.sabr.media

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.LocalConfiguration
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Timeline
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.CompositeSequenceableLoaderFactory
import androidx.media3.exoplayer.source.DefaultCompositeSequenceableLoaderFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * alpha.64(端口 LibreTube `SabrMediaSource`):SABR 流的自定义 [MediaSource]。
 *
 * 不走 DashMediaSource/ProgressiveMediaSource——SABR 是服务端驱动分段协议,无 MPD/SIDX。
 * [SabrTimeline] 单 period(windowDurationUs=manifest.durationMs)→ [createPeriod] 建
 * [SabrMediaPeriod] → A/V 两 [androidx.media3.exoplayer.source.chunk.ChunkSampleStream] 共享
 * 一个 [SabrMediaFetcher](单流,修 60s 断崖)。
 *
 * 对齐 LibreTube `player/SabrMediaSource.kt`(MIT)。适配:Factory 取 [SabrManifest]+[SabrMediaFetcher]+
 * sessionId(为 [SabrDataSource] evict 用);DRM 走 [DefaultDrmSessionManagerProvider](无 DRM 时 no-op)。
 */
@OptIn(UnstableApi::class)
internal class SabrMediaSource(
  private var mediaItem: MediaItem,
  private val manifest: SabrManifest,
  private val fetcher: SabrMediaFetcher,
  private val sessionId: String,
  private val chunkSourceFactory: SabrChunkSource.Factory,
  private val compositeSequenceableLoaderFactory: CompositeSequenceableLoaderFactory,
  private val drmSessionManagerProvider: DrmSessionManagerProvider,
  private val loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
) : BaseMediaSource() {

  init {
    MediaLibraryInfo.registerModule("media3.exoplayer.sabr")
  }

  /** Factory for [SabrMediaSource]s。 */
  class Factory(
    private val manifest: SabrManifest,
    private val fetcher: SabrMediaFetcher,
    private val sessionId: String,
    private val bufferMaxMs: Long,
    private val bandwidthMeter: BandwidthMeter,
  ) : MediaSource.Factory {
    private val compositeSequenceableLoaderFactory = DefaultCompositeSequenceableLoaderFactory()
    private var drmSessionManagerProvider: DrmSessionManagerProvider = DefaultDrmSessionManagerProvider()
    private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy()

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): Factory =
      apply { this.drmSessionManagerProvider = provider }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): Factory =
      apply { this.loadErrorHandlingPolicy = policy }

    override fun createMediaSource(mediaItem: MediaItem): SabrMediaSource {
      Assertions.checkNotNull<LocalConfiguration>(mediaItem.localConfiguration)
      return SabrMediaSource(
        mediaItem,
        manifest,
        fetcher,
        sessionId,
        DefaultSabrChunkSource.Factory(
          SabrDataSource.Factory(fetcher, sessionId), bufferMaxMs, bandwidthMeter,
        ),
        compositeSequenceableLoaderFactory,
        drmSessionManagerProvider,
        loadErrorHandlingPolicy,
      )
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
  }

  private var mediaTransferListener: TransferListener? = null
  private var elapsedRealtimeOffsetMs: Long = C.TIME_UNSET
  private val drmSessionManager: DrmSessionManager by lazy { drmSessionManagerProvider.get(mediaItem) }

  @Synchronized
  override fun getMediaItem(): MediaItem = mediaItem

  override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
    val existing = getMediaItem()
    val existingCfg = Assertions.checkNotNull<LocalConfiguration>(existing.localConfiguration)
    val newCfg = mediaItem.localConfiguration
    return newCfg != null && newCfg.uri == existingCfg.uri &&
      newCfg.streamKeys == existingCfg.streamKeys &&
      newCfg.drmConfiguration == existingCfg.drmConfiguration &&
      existing.liveConfiguration == mediaItem.liveConfiguration
  }

  @Synchronized
  override fun updateMediaItem(mediaItem: MediaItem) {
    this.mediaItem = mediaItem
  }

  override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
    this.mediaTransferListener = mediaTransferListener
    drmSessionManager.setPlayer(Looper.myLooper()!!, getPlayerId())
    drmSessionManager.prepare()
    processManifest()
  }

  override fun maybeThrowSourceInfoRefreshError() {}

  override fun createPeriod(id: MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
    val periodIndex = id.periodUid as Int
    val periodEventDispatcher = createEventDispatcher(id)
    val drmEventDispatcher = createDrmEventDispatcher(id)
    return SabrMediaPeriod(
      manifest,
      fetcher,
      periodIndex,
      chunkSourceFactory,
      mediaTransferListener,
      null, // cmcdConfiguration——不用 CMCD
      drmSessionManager,
      drmEventDispatcher,
      loadErrorHandlingPolicy,
      periodEventDispatcher,
      elapsedRealtimeOffsetMs,
      allocator,
      compositeSequenceableLoaderFactory,
      getPlayerId(),
    )
  }

  override fun releasePeriod(mediaPeriod: MediaPeriod) {
    (mediaPeriod as SabrMediaPeriod).release()
  }

  override fun releaseSourceInternal() {
    elapsedRealtimeOffsetMs = C.TIME_UNSET
    drmSessionManager.release()
  }

  private fun processManifest() {
    val timeline = SabrTimeline(
      C.TIME_UNSET,
      C.TIME_UNSET,
      elapsedRealtimeOffsetMs,
      0,
      Util.msToUs(manifest.durationMs),
      0,
      manifest,
      mediaItem,
    )
    refreshSourceInfo(timeline)
  }

  private class SabrTimeline(
    private val presentationStartTimeMs: Long,
    private val windowStartTimeMs: Long,
    private val elapsedRealtimeEpochOffsetMs: Long,
    private val offsetInFirstPeriodUs: Long,
    private val windowDurationUs: Long,
    private val windowDefaultStartPositionUs: Long,
    private val manifest: SabrManifest,
    private val mediaItem: MediaItem?,
  ) : Timeline() {
    override fun getPeriodCount(): Int = 1

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
      Assertions.checkIndex(periodIndex, 0, periodCount)
      val uid: Any? = if (setIds) (0 + periodIndex) else null
      return period.set(
        null, uid, 0,
        Util.msToUs(manifest.durationMs),
        Util.msToUs(0) - offsetInFirstPeriodUs,
      )
    }

    override fun getWindowCount(): Int = 1

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
      Assertions.checkIndex(windowIndex, 0, 1)
      val defaultStartUs = windowDefaultStartPositionUs
      return window.set(
        Window.SINGLE_WINDOW_UID,
        mediaItem,
        manifest,
        presentationStartTimeMs,
        windowStartTimeMs,
        elapsedRealtimeEpochOffsetMs,
        true,
        false,
        null,
        defaultStartUs,
        windowDurationUs,
        0,
        periodCount - 1,
        offsetInFirstPeriodUs,
      )
    }

    override fun getIndexOfPeriod(uid: Any): Int =
      if (uid !is Int || uid < 0 || uid >= periodCount) C.INDEX_UNSET else uid

    override fun getUidOfPeriod(periodIndex: Int): Any {
      Assertions.checkIndex(periodIndex, 0, periodCount)
      return 0 + periodIndex
    }
  }

  private companion object {
    @Suppress("unused")
    private const val TAG = "SabrMediaSource"
  }
}
