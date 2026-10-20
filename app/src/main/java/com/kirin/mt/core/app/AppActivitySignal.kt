package com.kirin.mt.core.app

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * P11-254:「UI 是否已上屏 / 用户此刻是否在动 / 是否有视频在播」的极简信号,专供**启动后台任务**
 * 挑开跑时机用(目前只有 harvest 采集 WebView 预热一个消费者)。
 *
 * **起因(真机 2026-10-12,BRAVIA AE2,debug 包 dev.r2197,`logs_live_20261012_232242.log`)**:
 * 「WEB-SABR 优先」档的预热过去是「进程启动后延迟 8s 再跑」,而这块盒子启动后 10~15s 恰好是自家
 * 首页 / 动态 / YouTube 各路首屏最挤的时候 —— 主线程连跳 246~287 帧(4~5s),再叠上 harvest 的
 * 主线程冷启(实测 7029ms)⇒ 用户一按遥控器就吃**输入分发超时 ANR**(日志 `Wrote stack traces to
 * tombstoned`)→ 应用被关掉 → 重启 → 又冻,40 秒内连崩三次(PID 3663 → 5113 → 5792)。反证:唯一把
 * 预热**跑完**的那个进程(5792)活了下来。旧注释担心的「首次起播自己付冷启」是真的,但那不该用
 * 「撞启动高峰」来换。
 *
 * 所以这类「主线程要占几秒」的后台活儿必须满足两条才开跑:
 * 1. **启动已稳定**:UI 真的上屏([markUiReady])之后再等一个 settle 窗口(启动那波首屏请求落地);
 * 2. **用户此刻没在动**:最近 quietMs 内没有按键、且没有视频在播(播放器是主线程第一优先消费者,
 *    P11-126 的本意也是把冷启挪到播放之外)。
 *
 * 只读轻量状态(volatile + 一个 `CompletableDeferred`),不进 Compose 状态、不引新依赖。
 * 写入方:`MainActivity.onWindowFocusChanged` 调 [markUiReady];`AppShell` 根节点记按键与播放态。
 */
object AppActivitySignal {
  private val uiReadySignal = CompletableDeferred<Unit>()

  @Volatile private var uiReadyAtMs: Long = 0L
  @Volatile private var lastKeyAtMs: Long = 0L

  /** 播放器是否可见(前台)。由 AppShell 随 `visiblePlaybackRequest` 同步。 */
  @Volatile var playbackActive: Boolean = false

  /** 首次窗口获得焦点 = UI 真的画出来了。重复调用只刷新基准时间,不重复 complete。 */
  fun markUiReady(nowMs: Long = SystemClock.uptimeMillis()) {
    uiReadyAtMs = nowMs
    if (!uiReadySignal.isCompleted) {
      uiReadySignal.complete(Unit)
    }
  }

  /** 任意按键。只记时间、不改任何行为(返回 false 让事件照常下发)。 */
  fun markKey(nowMs: Long = SystemClock.uptimeMillis()) {
    lastKeyAtMs = nowMs
  }

  /**
   * 等「UI 已就绪 + 距上屏满 settleMs」。UI 还没上屏就挂起等(首个窗口焦点);已就绪则只等剩余时间。
   * 进程没起过 UI(比如纯后台)时永远不会返回 —— 调用方是 fire-and-forget 的后台协程,无害。
   */
  suspend fun awaitStartupSettled(settleMs: Long) {
    uiReadySignal.await()
    val elapsed = SystemClock.uptimeMillis() - uiReadyAtMs
    if (elapsed < settleMs) {
      delay(settleMs - elapsed)
    }
  }

  /** 现在是不是一个「用户没在动」的空档:距上次按键 ≥ quietMs 且没有视频在播。 */
  fun isIdle(quietMs: Long, nowMs: Long = SystemClock.uptimeMillis()): Boolean {
    return !playbackActive && nowMs - lastKeyAtMs >= quietMs
  }
}
