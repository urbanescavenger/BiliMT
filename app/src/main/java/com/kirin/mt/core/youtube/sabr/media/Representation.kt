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
      // 否则 NewPipe 把音频 itag(如 itag248 Opus)误分类进 videoOnlyStreams 时,音频会被强制建成
      // video Format,塞进 video AdaptationSet 被 ExoPlayer 当视频轨选 → 请求不存在的视频 itag → RELOAD 死循环。
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
