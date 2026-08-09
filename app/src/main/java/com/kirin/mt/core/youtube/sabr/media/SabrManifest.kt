package com.kirin.mt.core.youtube.sabr.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.kirin.mt.core.player.PlaybackInfo
import com.kirin.mt.core.youtube.sabr.SabrSession

/**
 * alpha.64(端口 LibreTube `manifest/SabrManifest`):SABR 流的元数据清单。
 * 由 [SabrSession](harvested 会话参数)+ [PlaybackInfo] 的 video/audio track(媒体元数据)构造。
 *
 * 与 LibreTube 不同:LibreTube 从 PipedAPI `Streams` 建;我们从已 resolve 的 [PlaybackInfo]
 * (track 元数据从 /player adaptiveFormats 取)+ [SabrSession](sabrUrl/ustreamerConfig/FormatId)建。
 *
 * 对齐 LibreTube `manifest/SabrManifest.kt`(MIT)。
 */
@UnstableApi
internal data class SabrManifest(
  /** 视频 ID(SABR 会话绑定)。 */
  val videoId: String,
  /** SABR 流服务端 URL(含 alr=yes&cpn,见 [SabrSession.sabrUrl])。 */
  val sabrUrl: String,
  /** VideoPlaybackUstreamerConfig 原始 bytes(opaque 透传进请求体 field5)。 */
  val ustreamerConfigBytes: ByteArray,
  /** 演示时长(ms,SabrTimeline windowDurationUs 用)。 */
  val durationMs: Long,
  /** adaptationSet 列表(单视频 + 单音频)。 */
  val adaptationSets: List<AdaptationSet>,
) {
  companion object {
    /**
     * 从 [session] + [info] 建 manifest。video track = 选中 itag 的 Representation;
     * audio track = 会话默认音频 itag。FormatId 从 [SabrSession] 取(按 track.id 查 videoFormat,
     * audio 用 session.audioFormatId)。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun fromSession(session: SabrSession, info: PlaybackInfo): SabrManifest {
      val videoTrack = info.videoTracks.first()
      val videoFmtId = session.videoFormat(videoTrack.id) ?: session.videoFormatId
      val videoRep = Representation.fromTrack(videoTrack, videoFmtId, C.TRACK_TYPE_VIDEO)
      val adaptationSets = if (info.audioTracks.isEmpty()) {
        listOf(AdaptationSet(C.TRACK_TYPE_VIDEO, listOf(videoRep)))
      } else {
        val audioTrack = info.audioTracks.first()
        val audioRep = Representation.fromTrack(audioTrack, session.audioFormatId, C.TRACK_TYPE_AUDIO)
        listOf(
          AdaptationSet(C.TRACK_TYPE_VIDEO, listOf(videoRep)),
          AdaptationSet(C.TRACK_TYPE_AUDIO, listOf(audioRep)),
        )
      }
      return SabrManifest(
        videoId = info.bvid,
        sabrUrl = session.sabrUrl,
        ustreamerConfigBytes = session.ustreamerConfig,
        durationMs = info.durationMs,
        adaptationSets = adaptationSets,
      )
    }
  }
}
