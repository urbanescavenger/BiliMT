package com.kirin.mt.core.youtube.sabr.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.kirin.mt.core.player.PlaybackTrack
import com.kirin.mt.core.youtube.sabr.FormatId

/**
 * alpha.64(端口 LibreTube `manifest/Representation`):一个 SABR 表示(一条 itag 流)。
 * 持有 media3 [format](选轨/解码用)+ 我们的 [formatId](SABR 请求体 FormatId 编码用)。
 *
 * 对齐 LibreTube `manifest/Representation.kt`(MIT),适配:不从 PipedStream 建表,从我们的
 * [PlaybackTrack] + [FormatId] 建(PlaybackTrack 已带 codecs/width/height/fps/bitrate/mimeType)。
 */
@UnstableApi
internal data class Representation(
  /** media3 Format(选轨/解码;含 containerMimeType/sampleMimeType/codecs/bitrate/width/height/frameRate)。 */
  val format: Format,
  /** SABR FormatId(请求体编码:itag/lastModified/xtags)。 */
  val formatId: FormatId,
) {
  companion object {
    /**
     * 从 [track] + [formatId] 建 Representation。track 提供媒体元数据,formatId 提供 SABR 协议字段。
     * [trackType] 决定按视频还是音频公式建 media3 Format。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun fromTrack(track: PlaybackTrack, formatId: FormatId, trackType: Int): Representation {
      val builder = Format.Builder()
        .setContainerMimeType(track.mimeType)
        .setCodecs(track.codecs)
        .setAverageBitrate(if (track.bandwidth > 0) track.bandwidth else -1)
      // alpha.82(对齐 LibreTube):建视频 Format 必须 trackType 为视频 **且** mimeType 真是视频。
      // 注意(已推翻,见 docs/youtube-hd-playback.md 最新结论):真机 dump 证实 itag248=vp9 720p 视频,
      // **不是** opus 音频——此前「itag248=opus 误分类进 videoOnlyStreams 致 RELOAD」的归因是误读。
      // RELOAD 根因是 NewPipe visionOS 未 attested 的 ustreamerConfig,与 itag 分类无关。本 guard 仅防
      // NewPipe 真把音频 itag 误标成 video/webm 的极端情况(不针对 itag248),保留无害。
      if (trackType == C.TRACK_TYPE_VIDEO && MimeTypes.isVideo(track.mimeType)) {
        builder.setSampleMimeType(MimeTypes.getVideoMediaMimeType(track.codecs))
          .setWidth(if (track.width > 0) track.width else -1)
          .setHeight(if (track.height > 0) track.height else -1)
          .setFrameRate(if (track.fps > 0) track.fps.toFloat() else -1f)
      } else {
        // alpha.74 修真机黑屏无声:音频 codecs 从 /player/NewPipe mimeType 提取时常为空
        // (itag139 的 mimeType 不带 codecs) → getAudioMediaMimeType("") 返回 null → sampleMimeType=null
        // → media3 无法把音频轨关联到 audio renderer → 音频轨不被 selectTracks 选中 → audioFormat=null
        // → SABR 只请求视频、白名单[137]挡掉服务端音频 itag251 → 整场无声。用 containerMimeType
        // (audio/mp4 / audio/webm)兜底 sampleMimeType,让音频轨可被选中(对齐媒体真实容器)。
        builder.setSampleMimeType(
          MimeTypes.getAudioMediaMimeType(track.codecs) ?: track.mimeType
        )
          .setChannelCount(2)
      }
      return Representation(builder.build(), formatId)
    }
  }
}
