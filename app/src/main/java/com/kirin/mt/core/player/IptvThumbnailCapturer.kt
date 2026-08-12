package com.kirin.mt.core.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * IPTV 频道缩略图截帧器:拉直播流,播放到 READY 出画面后截一帧转 Bitmap。
 *
 * 复用 [IptvDataSourceFactory](强制 IPv4 + 明文放行,见其注释),与播放器同栈,
 * 保证能连上源(否则截帧也会黑屏/失败)。media3 1.10 的 `getVideoFrameAtTime`
 * 返回 [androidx.media3.common.VideoFrame](非 Bitmap),需先
 * `setVideoFrameMetadataListener`(启用帧保留)+ `setVideoFrameOutputFormat(RGBA)`,
 * 再从 `frame.buffer`(ByteBuffer)拷像素到 ARGB_8888 Bitmap。
 */
@OptIn(UnstableApi::class)
class IptvThumbnailCapturer(private val context: Context) {
  private val dataSourceFactory = IptvDataSourceFactory().create()

  /**
   * 截取 [url] 直播流当前画面。成功返回 Bitmap,失败/超时返回 null(不抛)。
   * 每次调用新建一个 ExoPlayer,截完即 release(截帧是低频后台任务,不复用播放器)。
   */
  suspend fun capture(url: String): Bitmap? = withContext(Dispatchers.IO) {
    val player = ExoPlayer.Builder(context).build()
    try {
      // 空监听器即可启用帧保留,getVideoFrameAtTime 才能取到帧。
      player.setVideoFrameMetadataListener { _, _, _, _ -> }
      player.setVideoFrameOutputFormat(C.VIDEO_FRAME_FORMAT_RGBA)
      player.setMediaSource(
        HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(url)),
      )
      player.prepare()
      player.play()
      // 等 READY 且出画面(有视频尺寸),带超时——直播流可能几秒~十几秒才出画面。
      val ready = withTimeoutOrNull(CaptureTimeoutMs) {
        while (player.playbackState != Player.STATE_READY || player.videoSize.width == 0) {
          delay(100)
        }
      }
      if (ready == null) {
        Log.w(LogTag, "capture $url timeout (no ready frame)")
        return@withContext null
      }
      // 等一帧真正渲染,避免取到黑帧。
      delay(FrameSettleDelayMs)
      val frame = player.getVideoFrameAtTime(player.currentPosition, C.MODE_FASTEST)
        ?: run {
          Log.w(LogTag, "capture $url no frame at ${player.currentPosition}")
          return@withContext null
        }
      val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also {
        frame.buffer.rewind()
        it.copyPixelsFromBuffer(frame.buffer)
      }
      frame.release()
      Log.i(LogTag, "capture $url ok ${bitmap.width}x${bitmap.height}")
      bitmap
    } catch (error: Exception) {
      Log.w(LogTag, "capture $url failed: ${error.javaClass.simpleName}: ${error.message}")
      null
    } finally {
      player.release()
    }
  }

  private companion object {
    const val CaptureTimeoutMs = 15_000L
    const val FrameSettleDelayMs = 300L
    const val LogTag = "BiliMT:IptvThumb"
  }
}
