package com.kirin.mt.core.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * IPTV 频道缩略图截帧器(方案一:SurfaceTexture + EGL 离屏 glReadPixels)。
 *
 * 备选实现,替代 [IptvThumbnailCapturer] 的 ImageReader 方案。两者唯一区别在"怎么把解码器
 * 输出的一帧变成 Bitmap":
 *   - ImageReader:把 Surface 直接给 ImageReader,`acquireLatestImage()` 拿 Image 转 Bitmap。
 *     坑:codec 输出 buffer 与 ImageReader 请求的像素格式(RGBA_8888)可能不匹配,某些
 *     源/设备上拿到的是 YUV 或格式错位,转出的 Bitmap 花屏或全黑(且格式问题不抛错,难排查)。
 *   - 本方案(SurfaceTexture+EGL):把 Surface 交给 SurfaceTexture,解码器写进来的仍是原始
 *     buffer,由 EGL 通过 GL 管线把 OES 外部纹理绘制到离屏 pbuffer surface,再 `glReadPixels`
 *     读回 RGBA。这是 media3 播放器截帧的"正统真离屏"路径,不依赖 ImageReader 的格式协商,
 *     兼容性更稳。
 *
 * 其余全部复用 [IptvThumbnailCapturer] 的逻辑:强制 IPv4 明文 [IptvDataSourceFactory]、
 * [LiveLoadErrorHandlingPolicy] 重试、stall 看门狗启动宽限期、ImageReader 帧可用信号
 * 改成 SurfaceTexture [OnFrameAvailableListener]。
 *
 * media3 要求 Player 的所有方法(含 playbackState)必须在带 Looper 的同一线程调用,GL 也要
 * 同一线程(EGL context 线程绑定)。这里每次截帧开一个专用 [HandlerThread],在其 looper
 * dispatcher 里创建/访问/release player + EGL,既不上主线程阻塞 UI,又满足双线程约束。
 */
@OptIn(UnstableApi::class)
class IptvThumbnailCapturerEgl(private val context: Context) {
  private val dataSourceFactory = IptvDataSourceFactory().create()

  /**
   * 截取 [url] 直播流当前画面。成功返回 Bitmap,失败/超时返回 null(不抛)。
   * 每次调用新建 ExoPlayer + EGL 离屏 surface,截完即 release(截帧是低频后台任务,不复用)。
   */
  suspend fun capture(url: String): Bitmap? {
    val handlerThread = HandlerThread("IptvThumbEgl").apply { start() }
    val handler = Handler(handlerThread.looper)
    return withContext(handler.asCoroutineDispatcher()) {
      val egl = EglCapture()
      try {
        val player = ExoPlayer.Builder(context).setLooper(handlerThread.looper).build()
        try {
          player.setVideoSurface(egl.surface)
          // 静音:截帧只要画面不要声音(3 并发、最长 22s,出声会打扰用户)。
          player.setVolume(0f)
          // 帧可用信号:SurfaceTexture 真收到一帧时置 true。ready 判断用它而非
          // player.videoSize(某些源 videoSize 报 0,绑死它会让已 READY 的截帧一直空转到超时)。
          val frameReady = AtomicBoolean(false)
          egl.surfaceTexture.setOnFrameAvailableListener({ frameReady.set(true) }, handler)
          // 与真实播放器对齐:挂 LiveLoadErrorHandlingPolicy(重试 7 次 + 指数退避),
          // 否则首载失败直接卡 BUFFERING。
          val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy())
            .createMediaSource(MediaItem.fromUri(url))
          player.setMediaSource(mediaSource)
          player.prepare()
          player.play()
          // 等 READY 且真出了帧,带超时。慢源从请求到首帧要几秒~十几秒,是正常启动不是死源:
          // 启动宽限期 [StartupGraceMs] 内绝不判 stall、绝不重挂——否则慢源还在连就被一次次
          // reload 打断,永远 READY 不了。宽限期后仍卡 BUFFERING 且进度不动才是真 stall,才重挂。
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
          // 等一帧真正渲染到 SurfaceTexture,避免读到黑帧。
          delay(FrameSettleDelayMs)
          // 离屏渲染 + glReadPixels 读回 RGBA 转 Bitmap。
          val bitmap = egl.readFrame()
          if (bitmap != null) {
            Log.i(LogTag, "capture $url ok ${bitmap.width}x${bitmap.height}")
          } else {
            Log.w(LogTag, "capture $url no frame read (egl/gl failed)")
          }
          bitmap
        } finally {
          player.release()
        }
      } catch (error: Exception) {
        Log.w(LogTag, "capture $url failed: ${error.javaClass.simpleName}: ${error.message}")
        null
      } finally {
        egl.release()
      }
    }
    handlerThread.quitSafely()
  }

  /**
   * 封装一次截帧的离屏 GL 环境:EGL display/config/context + 离屏 pbuffer surface +
   * OES 外部纹理 + SurfaceTexture/Surface(给 ExoPlayer)。在构造线程(即 HandlerThread
   * looper dispatcher)上创建并绑定 EGL context。
   */
  private class EglCapture {
    val surfaceTexture: SurfaceTexture
    val surface: Surface

    private val display: EGLDisplay
    private val context: EGLContext
    private val pbuffer: EGLSurface
    // 纹理 ID 自己保存:SurfaceTexture.getTextureId() 是 @hide API,SDK 编译期不可见,
    // 不能调 surfaceTexture.textureId(Unresolved reference)。用创建时生成的 texIds[0]。
    private val textureId: Int

    private var program = 0

    init {
      // --- EGL 初始化 ---
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
      val version = IntArray(2)
      check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

      val configAttribs = intArrayOf(
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_NONE,
      )
      val configs = arrayOfNulls<EGLConfig>(1)
      val numConfig = IntArray(1)
      check(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfig, 0) &&
        numConfig[0] >= 1) { "eglChooseConfig failed" }
      val config = configs[0]!!

      // EGL ES2 context
      val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
      context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
      check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

      // 离屏 pbuffer surface(目标,不占屏)
      val surfaceAttribs = intArrayOf(
        EGL14.EGL_WIDTH, CaptureWidth,
        EGL14.EGL_HEIGHT, CaptureHeight,
        EGL14.EGL_NONE,
      )
      pbuffer = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
      check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
      check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }

      // --- GL 外部纹理 + SurfaceTexture(给播放器写帧) ---
      val texIds = IntArray(1)
      GLES20.glGenTextures(1, texIds, 0)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[0])
      // 不设 min/mag filter 直接 draw 可能取到脏纹理,给足默认采样参数
      GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
      )
      GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
      )
      GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
      )
      GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
      )
      surfaceTexture = SurfaceTexture(texIds[0])
      surface = Surface(surfaceTexture)
      surfaceTexture.setDefaultBufferSize(CaptureWidth, CaptureHeight)
      textureId = texIds[0]

      program = buildProgram()
    }

    /** 把 SurfaceTexture 当前帧绘制到 pbuffer 并 glReadPixels 读回 RGBA Bitmap。 */
    fun readFrame(): Bitmap? {
      val buf = ByteBuffer.allocateDirect(CaptureWidth * CaptureHeight * 4)
        .order(ByteOrder.nativeOrder())
      GLES20.glViewport(0, 0, CaptureWidth, CaptureHeight)
      GLES20.glUseProgram(program)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
      surfaceTexture.updateTexImage()
      drawQuad()
      GLES20.glFinish()
      GLES20.glReadPixels(
        0, 0, CaptureWidth, CaptureHeight,
        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
      )
      if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
      buf.rewind()
      val bmp = Bitmap.createBitmap(CaptureWidth, CaptureHeight, Bitmap.Config.ARGB_8888)
      bmp.copyPixelsFromBuffer(buf)
      // glReadPixels 是 bottom-up 行序,垂直翻转成正常方向
      return Bitmap.createBitmap(
        bmp, 0, 0, CaptureWidth, CaptureHeight,
        Matrix().apply { setScale(1f, -1f) }, true,
      )
    }

    fun release() {
      try {
        surface.release()
        surfaceTexture.release()
        if (program != 0) GLES20.glDeleteProgram(program)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglMakeCurrent(
          display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
        )
        EGL14.eglTerminate(display)
      } catch (_: Exception) {
        // release 尽力而为
      }
    }

    private fun drawQuad() {
      // 全屏 quad(三角形带):位置 -1..1,纹理坐标 V 翻转(SurfaceTexture 行序 top-down)
      val vertexBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
      vertexBuffer.put(
        floatArrayOf(
          // x, y, u, v
          -1f, -1f, 0f, 1f,
          1f, -1f, 1f, 1f,
          -1f, 1f, 0f, 0f,
          1f, 1f, 1f, 0f,
        ),
      )
      vertexBuffer.rewind()
      val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
      val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
      GLES20.glEnableVertexAttribArray(aPosition)
      GLES20.glEnableVertexAttribArray(aTexCoord)
      GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
      vertexBuffer.position(2)
      GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      GLES20.glDisableVertexAttribArray(aPosition)
      GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private fun buildProgram(): Int {
      val vertexShader = compileShader(
        GLES20.GL_VERTEX_SHADER,
        """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
          gl_Position = aPosition;
          vTexCoord = aTexCoord;
        }
        """.trimIndent(),
      )
      val fragmentShader = compileShader(
        GLES20.GL_FRAGMENT_SHADER,
        """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        varying vec2 vTexCoord;
        void main() {
          gl_FragColor = texture2D(uTexture, vTexCoord);
        }
        """.trimIndent(),
      )
      val program = GLES20.glCreateProgram()
      GLES20.glAttachShader(program, vertexShader)
      GLES20.glAttachShader(program, fragmentShader)
      GLES20.glLinkProgram(program)
      GLES20.glDeleteShader(vertexShader)
      GLES20.glDeleteShader(fragmentShader)
      return program
    }

    private fun compileShader(type: Int, source: String): Int {
      val shader = GLES20.glCreateShader(type)
      GLES20.glShaderSource(shader, source)
      GLES20.glCompileShader(shader)
      return shader
    }
  }

  private companion object {
    const val CaptureWidth = 640
    const val CaptureHeight = 360
    /** 单次截帧总超时。慢源首帧要几秒~十几秒,给足启动时间。 */
    const val CaptureTimeoutMs = 22_000L
    const val FrameSettleDelayMs = 300L
    /** 启动宽限期:前 6s 不判 stall、不重挂——慢源正常启动期,reload 只会打断它。 */
    const val StartupGraceMs = 6_000L
    /** 宽限期后卡 BUFFERING 且进度不前进超过此时长 → 判定真 stall,重挂源拉活。 */
    const val StallReloadMs = 4_000L
    /** 单次截帧最多重挂次数,超过仍不 READY 则超时回退台标。 */
    const val MaxStallReloads = 2
    const val LogTag = "BiliMT:IptvThumbEgl"
  }
}
