package com.kirin.mt.core.youtube.sabr.media

/**
 * alpha.64(端口 LibreTube `PlaybackRequest`):一次 SABR 段请求的参数,塞进 [androidx.media3.datasource.DataSpec.customData]
 * 由 [SabrDataSource] 取出交给 [SabrMediaFetcher.getNextSegment]。一次请求只针对**一个格式**(itag),
 * 但 fetcher 内部一次 POST 会拉**所有已初始化格式**的多段(单流),再按 itag 取出本请求段。
 *
 * 对齐 LibreTube `parser/SabrClient.kt` 的 `PlaybackRequest`(MIT)。
 *
 * @param formatItag 本请求要取的格式 itag(从 [Representation.formatId].itag)。
 * @param playerPositionMs 播放器当前位置(ms,ClientAbrState 诊断用;请求体 playerTimeMs 用 [segmentStartTimeMs])。
 * @param playbackSpeed 播放速率(1.0)。
 * @param segment 段序号(init=0;media 段从 1 起,= ChunkIndex segmentNum + 1)。
 * @param segmentStartTimeMs 本段起始呈现时间(ms,**请求体 playerTimeMs** 用此值,对齐 LibreTube)。
 * @param bufferedSegments 播放器已缓冲段号队列(同步 fetcher 的 bufferedSegments 缓存,防泄漏内存)。
 */
internal data class SabrSegmentRequest(
  val formatItag: Int,
  val playerPositionMs: Long,
  val playbackSpeed: Float,
  val segment: Long,
  val segmentStartTimeMs: Long,
  val bufferedSegments: List<Long>,
) {
  companion object {
    /** init 段请求(segment=0, segmentStartTimeMs=0, 无 bufferedSegments)。 */
    fun initRequest(formatItag: Int, playerPositionMs: Long, playbackSpeed: Float): SabrSegmentRequest =
      SabrSegmentRequest(formatItag, playerPositionMs, playbackSpeed, 0L, 0L, emptyList())
  }
}
