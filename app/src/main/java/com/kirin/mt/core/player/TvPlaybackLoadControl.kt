package com.kirin.mt.core.player

import androidx.media3.exoplayer.DefaultLoadControl

fun createTvPlaybackLoadControl(): DefaultLoadControl {
  return DefaultLoadControl.Builder()
    .setBufferDurationsMs(
      MinBufferMs,
      MaxBufferMs,
      BufferForPlaybackMs,
      BufferForPlaybackAfterRebufferMs,
    )
    .setPrioritizeTimeOverSizeThresholds(true)
    .build()
}

private const val MinBufferMs = 30_000
/** alpha.45:90s→50s。原 90s > YouTube SABR 服务端 ~60s 缓冲上限,删掉 SabrStreamingDataSource 的
 * read() 内 Thread.sleep pacing 后,ExoPlayer 会 burst 拉到 90s 撞 60s 断崖。50s 是 Media3 默认值,
 * < 60s 让 LoadControl 在服务端上限前自动停拉,替代旧 pacer 的防断崖作用,且不阻塞 read 路径。 */
private const val MaxBufferMs = 50_000
private const val BufferForPlaybackMs = 1_500
private const val BufferForPlaybackAfterRebufferMs = 4_000
