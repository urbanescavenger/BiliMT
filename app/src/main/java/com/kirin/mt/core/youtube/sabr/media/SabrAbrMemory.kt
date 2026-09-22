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
 *
 * 2026-09-01(trial 失败冷却跨重载,修「重载洗掉冷却 → 塌方期反复冲高档」):21:0X 真机复盘
 * (§24 alpha.5 首验)——①试探失败冷却(180s)是 selection 实例内字段,看门狗重载即丢 → 新实例
 * 立即重新试探;②21:13:23 案例重载发生在**试探期**(降档路径从未跑,冷却根本没记)→ 新实例再试
 * 同一档。修:试探失败/试探在身改记墙钟态([noteTrialFail]/[noteTrialActive]/[onStallReload]),
 * 新 ABR 实例读 [isTrialFailBlocked] 跳过冷却中的档;看门狗 stall 重载时若 activeTrial 在身
 * (试探被重载打死)→ [onStallReload] 转记为 fail 冷却。
 */
object SabrAbrMemory {

  /** 最近一次「起播期 stall」的墙钟时间(epoch ms),0=从未。 */
  @Volatile
  private var lastStartupStallWallMs = 0L

  /** 2026-09-01:试探失败的 height 与解禁墙钟(epoch ms)。-1=从未。 */
  @Volatile
  private var trialFailedHeight = -1

  @Volatile
  private var trialFailedUntilWallMs = 0L

  /** 2026-09-01:当前正在试探的 height(-1=无)——看门狗重载时若在身则转记失败冷却。 */
  @Volatile
  private var activeTrialHeight = -1

  /** 起播期判定阈值:pos 在此之内 stall 视为起播 stall(冷启动误跳期,sustained 证据尚未成熟)。 */
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

  /**
   * 2026-09-01:试探升档进入某档时调用(看门狗重载路径据此把「试探期被打死」转记失败冷却)。
   */
  fun noteTrialActive(height: Int) {
    activeTrialHeight = height
  }

  /** 试探降档离开该档(降档路径正常闭环)时调用:清 active、记 180s 失败冷却。 */
  fun noteTrialFail(height: Int, cooldownMs: Long, nowWallMs: Long = System.currentTimeMillis()) {
    if (activeTrialHeight == height) activeTrialHeight = -1
    trialFailedHeight = height
    trialFailedUntilWallMs = nowWallMs + cooldownMs
  }

  /**
   * P11-151(a,2026-09-20 真机「降档钉死 144p」):**最近一次「操作事件」**(seek / 手动选档)的墙钟时间。
   *
   * 依据(`logs_live_20260920_220043.log`):22:00:09 打出
   * `downgrade fail cooldown: 720p excluded 180s (gated+trial blocked, survives reload)` ——
   * 而**「降档即记冷却」不区分原因**(见 [HeightAwareAdaptiveTrackSelection.markDowngradeFromTrial] 注释):
   * 一次降档就把源档锁 180 秒,期间任何升档路径都碰不到它 ⇒ 画面钉在 144p,而缓冲一直有 25~28 秒。
   * 但 seek / 手动选档引起的降档**不是「该档不可持续」的证据**,是操作开销。
   * 故:操作事件后 [OPERATION_EVENT_GRACE_MS] 内的降档只记 [OPERATION_EVENT_COOLDOWN_MS] 的短冷却。
   */
  @Volatile
  private var lastOperationEventWallMs = 0L

  /** 操作事件后这段时间内的降档不按「供给不足」惩罚。 */
  const val OPERATION_EVENT_GRACE_MS = 20_000L

  /** 操作事件专用短冷却(它不代表该档不可持续,只是给窗口重建留时间)。 */
  const val OPERATION_EVENT_COOLDOWN_MS = 20_000L

  /** seek / 手动选档发生时调用(由 [SabrMediaFetcher.recordFetchGap] 的操作分支触发)。 */
  fun noteOperationEvent(nowWallMs: Long = System.currentTimeMillis()) {
    lastOperationEventWallMs = nowWallMs
  }

  /** 最近 [withinMs] 内是否发生过操作事件(降档冷却据此选时长)。 */
  fun recentOperationEvent(
    withinMs: Long = OPERATION_EVENT_GRACE_MS,
    nowWallMs: Long = System.currentTimeMillis(),
  ): Boolean =
    lastOperationEventWallMs > 0L && nowWallMs - lastOperationEventWallMs < withinMs

  /**
   * P11-151(b):**提前解除**某档的降档冷却。给「缓冲健康 + 带宽已达标」的早解条件用 ——
   * 冷却的本意是「该档扛不住」,而当缓冲已重建到健康水位、且实测带宽已超过该档声明码率,
   * 死等 90 秒只会把画面按在最低档。
   */
  fun clearTrialFail(height: Int) {
    if (trialFailedHeight == height) {
      trialFailedHeight = -1
      trialFailedUntilWallMs = 0L
    }
  }

  /** 该 height 是否处于试探失败冷却中(跨重载有效)。 */
  fun isTrialFailBlocked(height: Int, nowWallMs: Long = System.currentTimeMillis()): Boolean =
    height == trialFailedHeight && nowWallMs < trialFailedUntilWallMs

  /** 冷却剩余秒数(诊断日志用);不在冷却中返回 0。 */
  fun trialFailBlockedRemainSec(nowWallMs: Long = System.currentTimeMillis()): Int =
    if (trialFailedHeight >= 0 && nowWallMs < trialFailedUntilWallMs)
      ((trialFailedUntilWallMs - nowWallMs) / 1000L).toInt()
    else 0

  /**
   * 看门狗 stall 重载时调用(播放器侧 auto-retry 路径):若正有试探在身(试探期直接被重载打死,
   * 降档路径从未跑),转记为失败冷却——新实例不再立即重试同一堵墙。只处理试探态,起播顶档记忆
   * 仍由 [noteStartupStall](播放器侧按 pos 条件调用)负责。
   */
  fun onStallReload() {
    val trial = activeTrialHeight
    if (trial >= 0) {
      activeTrialHeight = -1
      trialFailedHeight = trial
      trialFailedUntilWallMs = System.currentTimeMillis() + TOP_TIER_STARTUP_STALL_COOLDOWN_MS
    }
  }
}