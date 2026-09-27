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

  /**
   * P11-178(2026-09-23 TV 真机 `logs_live_20260923_233549`):P11-173 的「stall 到达档」冷却**独占一格**,
   * 不再与上面 [trialFailedHeight] 共用。
   *
   * 真机证据:23:34:51.9 stall 写进 2160p(90s, remain 64s),23:35:17.9 新会话第一次评估
   * `downgrade fail cooldown: 720p excluded 90s (remain 64s,…)` —— 即启动锁那次降档调
   * [noteTrialFail] 把**同一个槽**覆盖成 720p ⇒ stall 冷却只活了 26 秒就被清掉。两个机制目的不同
   * (试探/降档失败 vs 跨重载不再爬回同一堵墙),共用一格必然互相踩。
   */
  @Volatile
  private var stallReachedHeight = -1

  @Volatile
  private var stallReachedUntilWallMs = 0L

  /**
   * P11-188:上一次 stall 冷却的「目标档 / 连击数 / 所属视频 / 发生墙钟」(跨重载)。
   * 同档 + 同视频 + 在 [STALL_REPEAT_RESET_MS] 窗口内 ⇒ 连击 +1,冷却按 [stallRepeatCooldownMs] 逐级加重。
   */
  @Volatile
  private var stallRepeatHeight = -1

  @Volatile
  private var stallRepeatCount = 0

  @Volatile
  private var stallRepeatVideoId: String? = null

  @Volatile
  private var lastStallCooldownWallMs = 0L

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
   *
   * ── P11-188(2026-09-27 真机 `logs_live_20260927_201351`):**只管降档/试探失败那一格** ─────────
   * [stallReachedHeight] 那格**不由本方法解除**(旧实现顺手一起清,是 P11-178 拆格时漏掉的半边)。
   * 真机:19:42:38 stall 写 2160p 冷却 90s → 19:42:53(重载后 4.6s)`cooldown cleared early: 2160p
   * (bufS=24s est=23861K ≥ declared=16278K×1.1, remain=75s → 0)` → ABR 随即爬回 2160p → 69s 后
   * 再 stall;全场 9 次重载**每一次**都紧跟一条 early-clear,90s 冷却实际寿命只有 4~5s。
   *
   * 原因是早解判据在**重载后天然成立**,两条证据都失效:
   * ①`bufferedDurationUs` 是低档回填的证据,不是高档可持续的证据 —— 重载由起播档(720p,1.5Mbps)
   *   起步,链路 23Mbps ⇒ **4 秒回填到 24s**,而这 24s 对 2160p(16.3Mbps)零信息量;
   * ②`est` 同源虚高 —— 同一时刻 `meas=1414K` 而 `est=23861K`,是低档段 bulk 下载推出的窗口值。
   *
   * 与 P11-178 是同一教训(两机制共用一格必然互相踩),这次共用的不是格而是一个 clear 入口。
   * stall 冷却的语义是「实测已饿死」,不该被「低档回填」翻案 —— 它的解除只走自然到期
   * (基数 90s 起,反复撞同一档时逐级加重,见 [stallRepeatCooldownMs])。
   */
  fun clearTrialFail(height: Int) {
    if (trialFailedHeight == height) {
      trialFailedHeight = -1
      trialFailedUntilWallMs = 0L
    }
  }

  /** 该 height 是否处于降档失败冷却中(跨重载有效)。P11-178:stall 到达档冷却走独立一格,一并算。 */
  fun isTrialFailBlocked(height: Int, nowWallMs: Long = System.currentTimeMillis()): Boolean =
    (height == trialFailedHeight && nowWallMs < trialFailedUntilWallMs) ||
      (height == stallReachedHeight && nowWallMs < stallReachedUntilWallMs)

  /**
   * P11-188:该 height 是否处于**降档/试探失败**那一格的冷却中(不含 stall 到达档)。
   *
   * [clearTrialFail] 的早解路径只该判这一格。拿 [isTrialFailBlocked] 去判会出两件事:
   * ①只有 stall 格在冷却时条件恒真(它俩都 true),于是**每次评估都打一行** `cooldown cleared early`;
   * ②那行还写着 `remain=Ns → 0` —— 其实根本清不动 stall 格,**日志撒谎**。
   * 真机 `logs_live_20260927_210358`:2160p 的 180s stall 冷却期间刷了 **89 条**这种行。
   */
  fun isDowngradeFailBlocked(height: Int, nowWallMs: Long = System.currentTimeMillis()): Boolean =
    height == trialFailedHeight && nowWallMs < trialFailedUntilWallMs

  /**
   * P11-188:[isDowngradeFailBlocked] 那一格的剩余秒数(诊断日志用);不在冷却中返回 0。
   * 与 [trialFailBlockedRemainSec](两格取大)分开,是为了让 `cooldown cleared early` 那行打印的
   * 是**真正被解的那一格**的剩余 —— 早解路径只动降档格,拿两格最大值去打印会显示 stall 格的数字。
   */
  fun downgradeFailBlockedRemainSec(height: Int, nowWallMs: Long = System.currentTimeMillis()): Int =
    if (height == trialFailedHeight && nowWallMs < trialFailedUntilWallMs)
      ((trialFailedUntilWallMs - nowWallMs) / 1000L).toInt()
    else 0

  /** 冷却剩余秒数(诊断日志用);不在冷却中返回 0。两格取大。 */
  fun trialFailBlockedRemainSec(nowWallMs: Long = System.currentTimeMillis()): Int {
    val trial = if (trialFailedHeight >= 0 && nowWallMs < trialFailedUntilWallMs)
      ((trialFailedUntilWallMs - nowWallMs) / 1000L).toInt() else 0
    val stall = if (stallReachedHeight >= 0 && nowWallMs < stallReachedUntilWallMs)
      ((stallReachedUntilWallMs - nowWallMs) / 1000L).toInt() else 0
    return maxOf(trial, stall)
  }

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

  // ── P11-173:跨重载「到达档」冷却(治「重载 → 重爬同一档 → 再饿死」循环)────────────────────
  //
  // 真机 logs_live_20260922_224548(BRAVIA,8yVhEAPMJ-E)两场同签名:
  //   场1 22:29:53 冷启梯子升 1080p → 22:30:08 `rn=2 6586130B/14951ms→3Mbps` → rn=3 **零字节挂死
  //   18s** → 22:30:20 `stall detected #1 @24941ms buffered=2%` → 整场重载;
  //   场2 22:30:48/50 `upshift reseed → 1080p/1440p` → rn=5/6 突发 20~27Mbps 让 ABR 钉 1440p →
  //   22:31:50/22:32:13 `rn=8 20MB/22s→7Mbps`、`rn=9 11.9MB/23.1s→4Mbps` 链路塌方 →
  //   22:32:14 `stall #1 @109574ms` → 再重载(第三次连 harvest 都没采到,落 NewPipe 兜底)。
  // 即:**重载把带宽窗口清零 → 新会话又从突发估计起步 → 40~90s 内爬回同一档 → 同一堵墙**。
  // 现状只有顶档(≥2160)有跨重载记忆([noteStartupStall]/[isTopTierStartupBlocked]),到过 1440p
  // 的场次裸奔。故:stall 重载时把**本场实际升上去过的最高档**记进 [isTrialFailBlocked] 的冷却
  // (跨重载存活、到期自动解除;**P11-188 起不再被 [clearTrialFail] 的「缓冲健康 + 带宽达标」提前放行**),
  // 新实例的升档候选循环(HeightAwareAdaptiveTrackSelection 内 `isTrialFailBlocked(f.height)`)
  // 自然跳过它 —— 逐级爬约束下,跳过该档即等于把梯子封在这一档以下。

  /** 本场(跨重载)ABR 实际升上去过的最高视频档高度;0=未知。 */
  @Volatile
  private var reachedHeight = 0

  /**
   * P11-173 冷却时长:与既有 `downgrade fail cooldown`(90s)同量级——真机两场都在重载后 40~90s 内爬回。
   * P11-188 起它是 [STALL_REPEAT_COOLDOWNS_MS] 的**基数**(第一次撞墙 90s,连击逐级加)。
   */
  const val STALL_REACHED_HEIGHT_COOLDOWN_MS = 90_000L

  /**
   * P11-173 只冷却「够高的档」:低于此高度不冷却。理由:起播档由带宽 seed 决定(起播画质最高 720P),
   * 把 720p 及以下冷却掉只会让画面更低、并不解决供给问题;真机会饿死的档从 1080p 起。
   */
  const val STALL_REACHED_MIN_HEIGHT = 1080

  /**
   * P11-188:同一档**反复** stall 时的连击冷却序列(ms)。基数沿用 P11-173 的 90s,连击逐级加到 300s 封顶。
   *
   * 依据(2026-09-27 真机,同 `logs_live_20260927_201351`):9 次 stall 的间隔 81~94s 几乎等于 90s 冷却
   * —— 说明冷却一到期 ABR 就爬回同一档再饿死。把 early-clear 那条泄漏堵上(见 [clearTrialFail])后
   * 单靠 90s 也只是把循环周期从 84s 拉到 ~150s,反复撞同一堵墙的档必须逐次加重惩罚,而不是每次
   * 都重新给 90 秒。
   */
  private val STALL_REPEAT_COOLDOWNS_MS = longArrayOf(
    STALL_REACHED_HEIGHT_COOLDOWN_MS,
    180_000L,
    300_000L,
  )

  /**
   * P11-188:连击复位窗口(ms)——距上次 stall 超过这段时间(或换了视频、换了档),视为「已经稳过一段」,
   * 连击从 1 重算。10min 取「一次成功播放明显长于一个误批-回填周期(~55~85s)」的量级。
   */
  const val STALL_REPEAT_RESET_MS = 600_000L

  /** P11-188:第 [repeat] 次连击对应的冷却时长(1 起算,超过序列长度取封顶值)。 */
  private fun stallRepeatCooldownMs(repeat: Int): Long =
    STALL_REPEAT_COOLDOWNS_MS[minOf(repeat - 1, STALL_REPEAT_COOLDOWNS_MS.lastIndex)]

  /** P11-173:ABR 每次升档成功后调用(单调取最大,调用廉价;只记「爬上去过」的档,不记 seed 起始档)。 */
  fun noteReachedHeight(height: Int) {
    if (height > reachedHeight) reachedHeight = height
  }

  /**
   * P11-173:看门狗 stall 重载时调用(替代裸 [onStallReload]) —— 先走既有「试探在身 → 转失败冷却」,
   * 再把「本场到达档」(≥ [STALL_REACHED_MIN_HEIGHT])冷却 90s 起(连击递增,见 [STALL_REPEAT_COOLDOWNS_MS])。
   * 只处理档位记忆本身,起播顶档记忆仍由 [noteStartupStall] 负责。
   *
   * ── P11-178(2026-09-23 TV 真机 `logs_live_20260923_233549`):目标档改取**饿死瞬间正在播的档** ──────
   * 那场 stall 记的是 `reachedHeight=2160p`(23:30:12 那次 reseed 曾爬到 itag401),可**真正漏光的是
   * 1440p**(itag400,6.04M,rn=27/28 挂死),而 2160p 当时本来就被 `trial refused (over-capacity)`
   * (25384K > floor 8747K)挡着 ⇒ 这条 90s 冷却当场是空操作,新会话照样能爬回 1440p 那堵墙。
   * 语义纠正:到达档只是「爬过」,饿死档才是「这堵墙」;且逐级爬约束下封住饿死档即封住它以上所有档,
   * 故目标档优先取 [starvedHeight],仅当它低于 [STALL_REACHED_MIN_HEIGHT](把 720p 及以下冷却掉只会
   * 让画面更低,口径不变)时退回 [reachedHeight]。
   *
   * ── P11-188(2026-09-27):冷却时长改为**连击递增** ────────────────────────────────────────
   * [videoId] 用于连击归属:换了视频就重算(否则上一个视频攒下的连击会让新视频第一次 stall 就吃 300s)。
   * 判据与效果见 [STALL_REPEAT_COOLDOWNS_MS]。
   */
  fun onStallReloadWithReachedHeight(
    starvedHeight: Int = 0,
    nowWallMs: Long = System.currentTimeMillis(),
    videoId: String? = null,
    log: (String) -> Unit = {},
  ) {
    onStallReload()
    val reached = reachedHeight
    reachedHeight = 0
    val starved = if (starvedHeight > 0) starvedHeight else 0
    val target = when {
      starved >= STALL_REACHED_MIN_HEIGHT -> starved
      reached >= STALL_REACHED_MIN_HEIGHT -> reached
      else -> -1
    }
    if (target < 0) {
      log(
        "stall-reached cooldown skipped: starved=${starved}p reached=${reached}p " +
          "均 < ${STALL_REACHED_MIN_HEIGHT}p(冷却低档只会让画面更低)",
      )
      return
    }
    // P11-188:同档 + 同视频 + 在复位窗口内 ⇒ 连击 +1,冷却逐级加重;否则从 1 重算。
    val repeat = if (target == stallRepeatHeight &&
      videoId == stallRepeatVideoId &&
      nowWallMs - lastStallCooldownWallMs <= STALL_REPEAT_RESET_MS
    ) {
      stallRepeatCount + 1
    } else {
      1
    }
    stallRepeatHeight = target
    stallRepeatCount = repeat
    stallRepeatVideoId = videoId
    lastStallCooldownWallMs = nowWallMs
    val cooldownMs = stallRepeatCooldownMs(repeat)
    // P11-178:写独立一格,不被后续 markDowngradeFromTrial(试探/降档失败)覆盖。
    // P11-188:也不被 clearTrialFail 的早解路径解除(见该函数注释)。
    stallReachedHeight = target
    stallReachedUntilWallMs = nowWallMs + cooldownMs
    log(
      "stall-reached cooldown: ${target}p excluded ${cooldownMs / 1000}s" +
        (if (repeat > 1) " (repeat #$repeat)" else "") +
        " (starved=${starved}p reached=${reached}p; survives reload; 重载后新 ABR 不再爬回同一档,P11-173/178/188)",
    )
  }
}