@file:OptIn(UnstableApi::class)

package com.kirin.mt.core.youtube.sabr.media

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.kirin.mt.core.player.PlaybackInfo

/**
 * alpha.97(修「Auto 永不升过 1080p」诊断):对 SABR 会话每个视频轨实测 media3 渲染器硬解能力判定,
 * 结果打 I 级日志。判定链(MediaCodecVideoRenderer.supportsFormat → MediaCodecInfo.isFormatSupported
 * → PerformancePoint 覆盖 / areSizeAndRateSupported)全部是 D 级或静默,live 日志默认收不到——
 * DefaultTrackSelector 只凭 FORMAT_HANDLED 才给 ADAPTIVE 资格(否则 FIXED,1440p60/2160p60 永不进
 * 自适应组),这里把「每个 itag 到底被判成什么、被哪个解码器、硬件标记如何」在 I 级显式曝光:
 *  - support=FORMAT_EXCEEDS_CAPABILITIES → 能力查询否掉(performance point 不覆盖 / sizeAndRate 不支持);
 *  - b=0 → resolver 没回填 bandwidth,DefaultTrackSelector 的 ADAPTIVE 资格还要求 bitrate 有值,同样否;
 *  - primary/hw 与解码器名 → 区分硬解(c2.qti.*)与软解(c2.android.*)。
 */
object SabrCodecDiagnostics {
  private const val TAG = "YtSabrCodec"

  /** 对 [info] 全部视频轨逐条打硬解能力判定日志。仅 SABR 会话调用(每视频一次,开销可忽略)。 */
  fun logVideoCodecSupport(context: Context, info: PlaybackInfo) {
    info.videoTracks.forEach { track ->
      // 注意:PlaybackTrack.mimeType 是容器 mime(video/webm/video/mp4),而解码器查询要**编解码 mime**
      // (video/x-vnd.on2.vp9 / video/avc)——首版误传容器 mime,getDecoderInfos 恒空 → 全线
      // FORMAT_UNSUPPORTED_SUBTYPE,是诊断工具自身假象(真机渲染器路径经 sampleMime 转换无此问题)。
      // 对齐 Representation.fromTrack 的 sampleMime 语义:优先 MimeTypes.getMediaMimeType(codecs)。
      val sampleMime = MimeTypes.getMediaMimeType(track.codecs.ifBlank { null })
        ?: track.mimeType.ifBlank { null }
      val format = Format.Builder()
        .setSampleMimeType(sampleMime)
        .setCodecs(track.codecs.ifBlank { null })
        .setWidth(track.width)
        .setHeight(track.height)
        .setFrameRate(if (track.fps > 0) track.fps.toFloat() else Format.NO_VALUE.toFloat())
        .setAverageBitrate(if (track.bandwidth > 0) track.bandwidth else Format.NO_VALUE)
        .build()
      var supportLabel: String
      var decoderLabel: String
      try {
        val capabilities =
          MediaCodecVideoRenderer.supportsFormat(context, MediaCodecSelector.DEFAULT, format)
        val formatSupport = RendererCapabilities.getFormatSupport(capabilities)
        supportLabel = when (formatSupport) {
          C.FORMAT_HANDLED -> "FORMAT_HANDLED"
          C.FORMAT_EXCEEDS_CAPABILITIES -> "FORMAT_EXCEEDS_CAPABILITIES"
          C.FORMAT_UNSUPPORTED_DRM -> "FORMAT_UNSUPPORTED_DRM"
          C.FORMAT_UNSUPPORTED_SUBTYPE -> "FORMAT_UNSUPPORTED_SUBTYPE"
          C.FORMAT_UNSUPPORTED_TYPE -> "FORMAT_UNSUPPORTED_TYPE"
          else -> "FORMAT_?$formatSupport"
        }
        decoderLabel = try {
          val decoders =
            MediaCodecUtil.getDecoderInfos(format.sampleMimeType ?: "", false, false)
          val primary = decoders.firstOrNull()
          primary?.let { "${it.name}(hw=${it.hardwareAccelerated})" } ?: "none"
        } catch (e: MediaCodecUtil.DecoderQueryException) {
          "query-error:${e.message}"
        }
      } catch (e: Exception) {
        supportLabel = "crash:${e.javaClass.simpleName}"
        decoderLabel = "-"
      }
      Log.i(
        TAG,
        "codecSupport itag=${track.id} mime=${track.mimeType} codecs=${track.codecs} " +
          "${track.width}x${track.height}@${track.fps} b=${track.bandwidth} " +
          "primary=$decoderLabel support=$supportLabel",
      )
    }
  }
}