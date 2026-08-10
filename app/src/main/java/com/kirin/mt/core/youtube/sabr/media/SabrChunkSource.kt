package com.kirin.mt.core.youtube.sabr.media

import androidx.media3.common.C.TrackType
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.chunk.ChunkSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.CmcdConfiguration

/**
 * alpha.64(端口 LibreTube `SabrChunkSource`):SABR 流的 [ChunkSource] 接口 + Factory。
 *
 * 对齐 LibreTube `player/SabrChunkSource.kt`(MIT)。适配:[SabrMediaFetcher] 替代 LibreTube `SabrClient`;
 * 我们不用 CMCD(CmcdConfiguration 留 null)。getOutputTextFormat 默认透传(无字幕转码)。
 */
@UnstableApi
internal interface SabrChunkSource : ChunkSource {
  /** Factory for [SabrChunkSource]s。 */
  interface Factory {
    fun createSabrChunkSource(
      manifest: SabrManifest,
      fetcher: SabrMediaFetcher,
      adaptationSetIndices: IntArray,
      trackSelection: ExoTrackSelection,
      trackType: @TrackType Int,
      elapsedRealtimeOffsetMs: Long,
      transferListener: TransferListener?,
      playerId: PlayerId,
      cmcdConfiguration: CmcdConfiguration?,
    ): SabrChunkSource

    /** 字幕源格式透传(无转码)。 */
    fun getOutputTextFormat(sourceFormat: Format): Format = sourceFormat
  }

  /** 更新轨选(adaptive 切清晰度时)。 */
  fun updateTrackSelection(trackSelection: ExoTrackSelection)
}
