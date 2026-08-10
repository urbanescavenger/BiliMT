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
private const val MaxBufferMs = 15_000 // alpha.11(4K黑屏):50s×26Mbps itag315≈162MB 撑爆 ~170MB 默认堆致 GC 阻塞→stall→ENDED 黑屏;降 15s≈48MB 默认堆放得下,配合 AndroidManifest largeHeap 兜底。alpha.68 同步刷新 status=2 已解 60s 重启,不再需 alpha.64 的 50s 大缓冲规避断崖。
/** alpha.67(对齐 LibreTube PlayerHelper.getLoadControl):用 media3 默认值 2500ms(原 1500)。
 * 起播前多攒 1s 缓冲,给 AV1 第一帧解码留提前量,缩小首播"音频先出现"(opus 输出延迟 0 立刻出声、
 * AV1 输出延迟 17-24 帧需先解码)。LibreTube 用默认 2500,未为 AV1 专门加大;重载冷启动那层由
 * status=2 同步刷新消除 60s 重启解决,此处只兜首播残留差。代价:首播起播延迟 1.5s→2.5s。 */
private const val BufferForPlaybackMs = 2_500
/** alpha.67(对齐 LibreTube / media3 默认):4000→5000ms。 */
private const val BufferForPlaybackAfterRebufferMs = 5_000
