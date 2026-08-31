package com.kirin.mt.core.youtube.sabr.media

/**
 * 2026-08-31(stall 重载记忆,修「起播 4K 死循环」):真机 20:04 复盘——ABR 冷启动被 2 个 720p 段的
 * 爆发速率(33-58M)骗过顶档门槛,起播(pos=0,首帧前)直跳 itag315(2160p)→ 切轨后 loader 停发
 * chunk → 播放器永不 READY → stall 看门狗 auto-retry 整链路重启 → 重启后 720p 播 2 秒
 * 「recovered, counter reset」→ ABR 状态全新、同样误判再跳 4K → ~16s 一轮无限循环。
 *
 * 看门狗重载把 ABR/带宽窗口/excludeTrack 冷却全部清零(每次都是 auto-retry #1,永不升级),失败
 * 不被记忆。本对象是**进程级跨重载记忆**:播放器侧在起播期(pos < [STARTUP_STALL_POS_MAX_MS])stall
 * 重载时记一笔,重载后新建的 [HeightAwareAdaptiveTrackSelection] 据此把顶档(≥2160)冷却
 * [TOP_TIER_STARTUP_STALL_COOLDOWN_MS]——期间低档升降照常,冷却自然到期或手动选档兜底。
 * 单例进程级(非按 videoId):重载后立刻重进同一视频正是主场景,按视频反而要穿层层接线;
 * 误伤面 = 起播 stall 后 3 分钟内换看其它 YouTube 视频不自动上 4K,可接受。
 */
object SabrAbrMemory {

  /** 最近一次「起播期 stall」的墙钟时间(epoch ms),0=从未。 */
  @Volatile
  private var lastStartupStallWallMs = 0L

  /** 起播期判定阈值:pos 在此之内 stall 视为起播 stall(冷启动误跳期,sustained 证据 <15s 尚未成熟)。 */
  const val STARTUP_STALL_POS_MAX_MS = 30_000L

  /** 顶档冷却时长(对齐 HeightAware 既有 TOP_TIER_BUFFER_CRITICAL_COOLDOWN_MS=3min)。 */
  const val TOP_TIER_STARTUP_STALL_COOLDOWN_MS = 180_000L

  /** 播放器侧在起播期 stall 重载时调用:重载后的新 ABR 实例将跳过顶档直到冷却到期。 */
  fun noteStartupStall() {
    lastStartupStallWallMs = System.currentTimeMillis()
  }

  /** 顶档(≥2160)是否仍处于起播 stall 冷却中——HeightAware 升档候选循环据此跳过顶档。 */
  fun isTopTierStartupBlocked(nowWallMs: Long = System.currentTimeMillis()): Boolean =
    lastStartupStallWallMs > 0L &&
      nowWallMs - lastStartupStallWallMs < TOP_TIER_STARTUP_STALL_COOLDOWN_MS

  /** 冷却剩余秒数(诊断日志用);不在冷却中返回 0。 */
  fun topTierStartupBlockedRemainSec(nowWallMs: Long = System.currentTimeMillis()): Int =
    if (isTopTierStartupBlocked(nowWallMs))
      ((lastStartupStallWallMs + TOP_TIER_STARTUP_STALL_COOLDOWN_MS - nowWallMs) / 1000L).toInt()
    else 0
}