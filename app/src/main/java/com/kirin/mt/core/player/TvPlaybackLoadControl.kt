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

private const val MinBufferMs = 10_000
/** alpha.58(Phase 1 paced 验证):50s→10s。50s 缓冲边沿 = playhead+50s,playhead≈10s 时 cumulative 已到
 * 60s 撞服务端断崖(alpha.54 日志 cumulative=60001 即此)。10s 让 ProgressiveMediaSource 每 ~6s 播放才拉
 * 一段(缓冲耗尽才续拉),请求节奏与墙钟同步 → paced,对齐 FreeTube 的按需取段,验证服务端能否不靠轮换
 * 持续发段跨过 60s。Phase 2 换 DashMediaSource 后沿用此小缓冲目标,让 playerTimeMs 贴近墙钟。 */
private const val MaxBufferMs = 10_000
private const val BufferForPlaybackMs = 1_500
private const val BufferForPlaybackAfterRebufferMs = 4_000
