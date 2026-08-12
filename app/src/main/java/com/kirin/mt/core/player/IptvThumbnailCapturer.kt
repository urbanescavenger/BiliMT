package com.kirin.mt.core.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.util.Log
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
 * 保证能连上源(否则截帧也会黑屏/失败)。media3 1.10 无 `getVideoFrameAtTime`,
 * 改用 [ImageReader] 作为 ExoPlayer 视频输出 Surface,播放到 READY 后
 * `acquireLatestImage()` 拿一帧转 Bitmap。
 */
@OptIn(UnstableApi::class)
class IptvThumbnailCapturer(private val context: Context) {
  private val dataSourceFactory = IptvDataSourceFactory().create()

  /**
   * 截取 [url] 直播流当前画面。成功返回 Bitmap,失败/超时返回 null(不抛)。
   * 每次调用新建一个 ExoPlayer + ImageReader,截完即 release(截帧是低频后台任务,不复用)。
   */
  suspend fun capture(url: String): Bitmap? = withContext(Dispatchers.IO) {
    val imageReader = ImageReader.newInstance(CaptureWidth, CaptureHeight, PixelFormat.RGBA_8888, MaxImages)
    val player = ExoPlayer.Builder(context).build()
    try {
      player.setVideoSurface(imageReader.surface)
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
      // 等一帧真正渲染到 ImageReader,避免取到黑帧。
      delay(FrameSettleDelayMs)
      val image = imageReader.acquireLatestImage()
        ?: run {
          Log.w(LogTag, "capture $url no image acquired")
          return@withContext null
        }
      val bitmap = image.toBitmap()
      image.close()
      Log.i(LogTag, "capture $url ok ${bitmap.width}x${bitmap.height}")
      bitmap
    } catch (error: Exception) {
      Log.w(LogTag, "capture $url failed: ${error.javaClass.simpleName}: ${error.message}")
      null
    } finally {
      player.release()
      imageReader.close()
    }
  }

  /** Image → ARGB_8888 Bitmap,处理 rowStride 行填充(ImageReader 的 rowStride 可能 > width*4)。 */
  private fun Image.toBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val padded = Bitmap.createBitmap(
      width + rowPadding / pixelStride,
      height,
      Bitmap.Config.ARGB_8888,
    )
    buffer.rewind()
    padded.copyPixelsFromBuffer(buffer)
    return if (rowPadding > 0) Bitmap.createBitmap(padded, 0, 0, width, height) else padded
  }

  private companion object {
    const val CaptureWidth = 640
    const val CaptureHeight = 360
    const val MaxImages = 2
    const val CaptureTimeoutMs = 15_000L
    const val FrameSettleDelayMs = 300L
    const val LogTag = "BiliMT:IptvThumb"
  }
}
