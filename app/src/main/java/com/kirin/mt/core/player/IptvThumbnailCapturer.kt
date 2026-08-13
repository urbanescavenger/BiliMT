package com.kirin.mt.core.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import kotlinx.coroutines.android.asCoroutineDispatcher
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
   *
   * media3 要求 Player 的所有方法(含 playbackState/videoSize 读取)必须在**带 Looper 的
   * 同一线程**上调用;IO 线程无 Looper 会抛 `Player is accessed on the wrong thread`。
   * 这里为每次截帧开一个专用 [HandlerThread],在其 looper dispatcher 里创建/访问/release
   * player,既不上主线程阻塞 UI,又满足同线程约束。
   */
  suspend fun capture(url: String): Bitmap? {
    val handlerThread = HandlerThread("IptvThumbCapture").apply { start() }
    val handler = Handler(handlerThread.looper)
    return withContext(handler.asCoroutineDispatcher()) {
      val imageReader = ImageReader.newInstance(CaptureWidth, CaptureHeight, PixelFormat.RGBA_8888, MaxImages)
      val player = ExoPlayer.Builder(context).setLooper(handlerThread.looper).build()
      try {
        player.setVideoSurface(imageReader.surface)
        // 静音:截帧只要画面不要声音。若出声,进 IPTV 列表/退出直播时每个缩略图截帧都会
        // 从扬声器放声(2 并发、最长 15s),用户会感觉"退出直播后还有音频在响"。
        player.setVolume(0f)
        // 与真实播放器(LivePlayerScreen)对齐:挂 LiveLoadErrorHandlingPolicy(重试 7 次 +
        // 指数退避)。IPTV 源首载常 403/404/断连,缺此策略则首载失败直接卡 BUFFERING。
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
          .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy())
          .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
        // 等 READY 且出画面(有视频尺寸),带超时——直播流可能几秒~十几秒才出画面。
        // 与 LivePlayerScreen 的 stall 看门狗同机制:某些源卡 BUFFERING 且进度不前进但**不报错**
        // (重试策略不触发),须主动重挂源拉活。被动等只会 15s 超时 → 缩略图回退台标
        // (日志 mobaibox.com / 223.110.x.x 截帧超时即此因)。
        val ready = withTimeoutOrNull(CaptureTimeoutMs) {
          var stallPosition = player.currentPosition
          var stallSince = 0L
          var reloads = 0
          while (player.playbackState != Player.STATE_READY || player.videoSize.width == 0) {
            val now = System.currentTimeMillis()
            val pos = player.currentPosition
            val stalled = player.playbackState == Player.STATE_BUFFERING && pos == stallPosition
            if (stalled) {
              if (stallSince == 0L) {
                stallSince = now
              } else if (now - stallSince >= StallReloadMs && reloads < MaxStallReloads) {
                reloads += 1
                Log.w(LogTag, "capture $url stall ${player.bufferedPercentage}%, reload #$reloads")
                player.clearMediaItems()
                player.setMediaSource(mediaSource)
                player.prepare()
                player.play()
                stallPosition = player.currentPosition
                stallSince = 0L
              }
            } else {
              stallPosition = pos
              stallSince = 0L
            }
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
    handlerThread.quitSafely()
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
    /** 卡 BUFFERING 且进度不前进超过此时长 → 判定 stall,重挂源拉活。 */
    const val StallReloadMs = 3_000L
    /** 单次截帧最多重挂次数,超过仍不 READY 则超时回退台标。 */
    const val MaxStallReloads = 3
    const val LogTag = "BiliMT:IptvThumb"
  }
}
