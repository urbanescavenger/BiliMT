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
import java.util.concurrent.atomic.AtomicBoolean
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
        // 从扬声器放声(3 并发、最长 22s),用户会感觉"退出直播后还有音频在响"。
        player.setVolume(0f)
        // 帧可用信号:ImageReader 真的收到一帧时置 true。ready 判断用它而非
        // player.videoSize(某些源 videoSize 报 0,绑死它会让已 READY 的截帧一直空转到超时)。
        val frameReady = AtomicBoolean(false)
        imageReader.setOnImageAvailableListener({ frameReady.set(true) }, handler)
        // 与真实播放器(LivePlayerScreen)对齐:挂 LiveLoadErrorHandlingPolicy(重试 7 次 +
        // 指数退避)。IPTV 源首载常 403/404/断连,缺此策略则首载失败直接卡 BUFFERING。
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
          .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy())
          .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
        // 等 READY 且真出了帧,带超时。慢源(尤其 mobaibox 这类)从请求到首帧常要
        // 几秒~十几秒,是正常启动不是死源:启动宽限期 [StartupGraceMs] 内绝不判 stall、
        // 绝不重挂——否则慢源还在连就一次次被 reload 打断,永远 READY 不了
        // (日志整批 `stall 0% reload #1/2/3` → timeout 即此因,真机播放器同一源 1s 就 READY)。
        // 宽限期后仍卡 BUFFERING 且进度不动,才是真 stall,才重挂源拉活。
        val ready = withTimeoutOrNull(CaptureTimeoutMs) {
          var stallPosition = player.currentPosition
          var stallSince = 0L
          var reloads = 0
          val startTime = System.currentTimeMillis()
          while (player.playbackState != Player.STATE_READY || !frameReady.get()) {
            val now = System.currentTimeMillis()
            val pos = player.currentPosition
            val stalled = player.playbackState == Player.STATE_BUFFERING &&
              pos == stallPosition && now - startTime >= StartupGraceMs
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
    /** 单次截帧总超时。慢源首帧要几秒~十几秒,15s 太紧,拉长给足启动时间。 */
    const val CaptureTimeoutMs = 22_000L
    const val FrameSettleDelayMs = 300L
    /** 启动宽限期:前 6s 不判 stall、不重挂——慢源正常启动期,reload 只会打断它。 */
    const val StartupGraceMs = 6_000L
    /** 宽限期后卡 BUFFERING 且进度不前进超过此时长 → 判定真 stall,重挂源拉活。 */
    const val StallReloadMs = 4_000L
    /** 单次截帧最多重挂次数,超过仍不 READY 则超时回退台标。 */
    const val MaxStallReloads = 2
    const val LogTag = "BiliMT:IptvThumb"
  }
}
