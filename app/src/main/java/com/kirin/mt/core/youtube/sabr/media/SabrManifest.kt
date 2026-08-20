package com.kirin.mt.core.youtube.sabr.media

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
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
     * 从 [session] + [info] 建 manifest。video track = 全部视频轨(多 Representation,由 ExoPlayer 选轨);
     * audio track = 会话默认音频 itag。FormatId 从 [SabrSession] 取(按 track.id 查 videoFormat,
     * audio 用 session.audioFormatId)。
     *
     * alpha.81(复刻 LibreTube):video AdaptationSet 建全部视频 Representation(不再只建选中 itag 一条),
     * 由 ExoPlayer 默认选轨选最高 bitrate 档(绕开 itag313 RELOAD)。对齐 LibreTube `SabrManifest.kt`
     * 的 `videoStreams.groupBy { it.mimeType }` 多 Representation 语义。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun fromSession(session: SabrSession, info: PlaybackInfo): SabrManifest {
      // alpha.9X 决定性诊断:打印过滤后真视频轨数与 itag,分辨「旧缓存包(manifest 仍按 mime 拆组)」vs
      // 「MimeTypes.isVideo 过滤吞轨」。缺此日志无法判断部署包是否带 6062dab 单组修复。
      val diagVideoTracks = info.videoTracks.filter { MimeTypes.isVideo(it.mimeType) }
      android.util.Log.i(
        "YtSabrManifest",
        "fromSession: rawVideoTracks=${info.videoTracks.size} isVideo=${diagVideoTracks.size} " +
          "itags=[${diagVideoTracks.joinToString { it.id.toString() }}] mimes=[${diagVideoTracks.joinToString { it.mimeType }}]"
      )
      // alpha.82(对齐 LibreTube `SabrManifest.kt` groupBy):视频轨按 mimeType 分组,每个 mimeType 一个
      // video AdaptationSet,不混。即使音频 itag(如 itag248 Opus)混进 videoOnlyStreams,也被按音频
      // mimeType 分到单独的 AdaptationSet,并因 mimeType 非视频而在 Representation 里建成 audio Format,
      // 不会当视频轨被 ExoPlayer 选中 → 不再请求不存在的视频 itag → 不再 RELOAD 死循环。
      //
      // alpha.9X(修「Auto 起播低档后永不升档」):上述 groupBy 把视频拆成多个 AdaptationSet(如 H264 组 +
      // VP9 组),而 [SabrMediaPeriod.getGroupedAdaptationSetIndices] 每个 set 独立成 TrackGroup →
      // DefaultTrackSelector 只选中其中一个(实测选中了 VP9 单轨组 itag243)→ AdaptiveTrackSelection 的
      // trackSelection.length()==1,没有任何别的轨可切 → 带宽/缓冲再足也永不升档。这里改成**单个** video
      // AdaptationSet 装全部**真视频轨**(用 MimeTypes.isVideo 过滤掉音频误分类 itag,既保住 alpha.82 防
      // itag248 RELOAD 的意图,又让 ExoPlayer 拿到全部视频轨在一个组里自适应升档)。
      val videoAdaptationSets = listOf(
        AdaptationSet(
          C.TRACK_TYPE_VIDEO,
          info.videoTracks
            .filter { MimeTypes.isVideo(it.mimeType) }
            .map { track ->
              val fmtId = session.videoFormat(track.id) ?: session.videoFormatId
              Representation.fromTrack(track, fmtId, C.TRACK_TYPE_VIDEO)
            }
        )
      )
      val audioAdaptationSets = if (info.audioTracks.isEmpty()) emptyList() else {
        val audioTrack = info.audioTracks.first()
        val audioRep = Representation.fromTrack(audioTrack, session.audioFormatId, C.TRACK_TYPE_AUDIO)
        listOf(AdaptationSet(C.TRACK_TYPE_AUDIO, listOf(audioRep)))
      }
      val adaptationSets = videoAdaptationSets + audioAdaptationSets
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
