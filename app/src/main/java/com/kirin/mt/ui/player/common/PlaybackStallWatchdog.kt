package com.kirin.mt.ui.player.common

/**
 * TV / 移动端共用的播放看门狗状态机（P11-313，同源）：位置冻结、视频冻结、P11-305 慢滴让路。
 *
 * 为什么抽出来：这套判定此前在 TV `PlayerScreen` 与移动端 `MobilePlayerScreen` 各写一份，
 * 结果 TV 端补了 P11-305、移动端漏掉——同一场 4K 故障两端行为不一致（2026-11-10 真机：
 * 移动端 8 秒看门狗在数据落地前 0.6 秒重载，TV 端会先让路）。判定逻辑（阈值、计时器、
 * 让路窗口）必须同源；施加动作（重试记账、evict 会话、日志、retryKey）仍留在各自页面，
 * 因为两端的 UI 副作用不同。
 *
 * 纯逻辑，不依赖 Compose / Android；时间由调用方传入（`SystemClock.elapsedRealtime` 口径）。
 *
 * 两端统一后的行为：
 * - 首帧渲染时清零起播期计时器（P11-122）。
 * - 重载时同时清零 `bufferingSinceMs`（此前只有移动端这么做；避免重载后残留窗口让
 *   `nowMs - bufferingSinceMs` 一上来就 ≥ 视频冻结阈值，位置一动就误判冻结）。
 * - 位置恢复前进时清零慢滴让路窗口（P11-305）。
 * - TV 独占的「深度重试」通过 [Action.RETRY_EXHAUSTED] 暴露，由 TV 页面自行处理；移动端忽略。
 *
 * 所有时间参数单位均为毫秒。
 */
class PlaybackStallWatchdog(
  private val stallThresholdMs: Long = 8_000L,
  private val startupStallThresholdMs: Long = 25_000L,
  private val videoFreezeThresholdMs: Long = 12_000L,
  private val maxAutoRetry: Int = 2,
  private val dripDeferMaxEtaMs: Long = DEFAULT_DRIP_DEFER_MAX_ETA_MS,
  private val dripDeferMaxMs: Long = DEFAULT_DRIP_DEFER_MAX_MS,
) {
  companion object {
    const val DEFAULT_DRIP_DEFER_MAX_ETA_MS = 10_000L
    const val DEFAULT_DRIP_DEFER_MAX_MS = 12_000L
  }

  enum class Action {
    /** 无需动作（含「正在让路」）。 */
    NONE,

    /** BUFFERING 但位置仍在前进：视频渲染层挂死，音频时钟活着。 */
    VIDEO_FREEZE,

    /** 位置冻结超过阈值：整场重载续播。 */
    RELOAD,

    /** 重试预算耗尽。TV 端据此做深度重试；移动端暂无此层。 */
    RETRY_EXHAUSTED,
  }

  /**
   * @param resumePositionMs 重载续播点（VIDEO_FREEZE / RELOAD 有效）
   * @param frozenMs 冻结/缓冲窗口时长（日志用）
   * @param etaMs 在途视频段估算剩余时间（-1 = 无证据）
   * @param startup 本次判定是否发生在「真正起播」之前
   * @param deferStarted 本 tick 刚开始让路（调用方据此只打一次日志）
   * @param deferring 本 tick 正在让路（不重载）
   * @param nextAutoRetryCount 调用方应写回的重试计数
   */
  data class Decision(
    val action: Action,
    val resumePositionMs: Long = 0L,
    val frozenMs: Long = 0L,
    val etaMs: Long = -1L,
    val startup: Boolean = false,
    val deferStarted: Boolean = false,
    val deferring: Boolean = false,
    val nextAutoRetryCount: Int = 0,
  )

  private var stallBaselinePositionMs = 0L
  private var stallSinceMs = 0L
  private var bufferingSinceMs = 0L
  private var bufferingLastPosMs = 0L
  private var bufferingPosAdvanced = false
  private var stallDeferStartMs = 0L
  private var frameRenderedSeen = false

  fun onTick(
    nowMs: Long,
    positionMs: Long,
    isStallBuffering: Boolean,
    playbackStarted: Boolean,
    frameRendered: Boolean,
    seekGraceActive: Boolean,
    inFlightEtaMs: Long,
    autoRetryCount: Int,
  ): Decision {
    // P11-122：首帧渲染 = 起播真正成功，清零此前积累的起播期计时器；否则慢起播攒下的
    // 12~33s 会在首帧那一轮立刻越过阈值开枪。
    if (frameRendered != frameRenderedSeen) {
      frameRenderedSeen = frameRendered
      if (frameRendered) {
        stallSinceMs = 0L
        stallBaselinePositionMs = positionMs
        bufferingSinceMs = 0L
        bufferingPosAdvanced = false
      }
    }

    if (!isStallBuffering) {
      stallBaselinePositionMs = positionMs
      stallSinceMs = 0L
      stallDeferStartMs = 0L
      bufferingSinceMs = 0L
      return Decision(Action.NONE, resumePositionMs = positionMs)
    }

    // ① 视频冻结：BUFFERING 期间位置仍前进 = 音频时钟活着、视频渲染器层挂死。
    if (bufferingSinceMs == 0L) {
      bufferingSinceMs = nowMs
      bufferingLastPosMs = positionMs
      bufferingPosAdvanced = false
    } else {
      if (positionMs != bufferingLastPosMs) {
        bufferingPosAdvanced = true
        bufferingLastPosMs = positionMs
      }
      if (bufferingPosAdvanced &&
        nowMs - bufferingSinceMs >= videoFreezeThresholdMs &&
        autoRetryCount < maxAutoRetry
      ) {
        val frozenMs = nowMs - bufferingSinceMs
        bufferingSinceMs = 0L
        stallSinceMs = 0L
        stallBaselinePositionMs = 0L
        return Decision(
          action = Action.VIDEO_FREEZE,
          resumePositionMs = positionMs,
          frozenMs = frozenMs,
          startup = !playbackStarted,
          nextAutoRetryCount = autoRetryCount + 1,
        )
      }
    }

    // ② 位置冻结：BUFFERING 且位置连续不动。seek/换段后位置变化会清零基线。
    if (positionMs == stallBaselinePositionMs) {
      if (stallSinceMs == 0L) {
        stallSinceMs = nowMs
      } else {
        // P11-210：宽限档的判据是「真正起播过没有」（进过 READY），不是「出没出首帧」——
        // preroll 首帧之后播放器可能仍在 BUFFERING、位置钉在 seek 落点。
        val thresholdMs = if (!playbackStarted || seekGraceActive) {
          startupStallThresholdMs
        } else {
          stallThresholdMs
        }
        if (nowMs - stallSinceMs >= thresholdMs) {
          if (autoRetryCount < maxAutoRetry) {
            // P11-305：慢滴让路——在途视频段 ETA ≤ 阈值且最近仍在收字节时先不整场重载。
            // 依据（docs §70，319 份历史日志）：stall 时正有 ≥8s 视频段在 open 的 7 次里，
            // 该段全部在 stall 后 2~9s 落地；而整场重载要 10~22s 黑屏。
            // 让路总上限 [dripDeferMaxMs]；到期、无在途段或 ETA 失去意义时照常重载。
            val dripping = playbackStarted && frameRendered &&
              inFlightEtaMs in 0L..dripDeferMaxEtaMs
            val deferStarted: Boolean
            if (dripping) {
              deferStarted = stallDeferStartMs == 0L
              if (deferStarted) stallDeferStartMs = nowMs
            } else {
              stallDeferStartMs = 0L
              deferStarted = false
            }
            val deferring = dripping && nowMs - stallDeferStartMs < dripDeferMaxMs
            if (deferring) {
              return Decision(
                action = Action.NONE,
                resumePositionMs = positionMs,
                frozenMs = nowMs - stallSinceMs,
                etaMs = inFlightEtaMs,
                startup = !playbackStarted,
                deferStarted = deferStarted,
                deferring = true,
                nextAutoRetryCount = autoRetryCount,
              )
            }
            val frozenMs = nowMs - stallSinceMs
            stallDeferStartMs = 0L
            stallSinceMs = 0L
            stallBaselinePositionMs = 0L
            // 与移动端原行为对齐（见类注释）：重载后若立刻又 BUFFERING，残留的窗口起点
            // 会让 `nowMs - bufferingSinceMs` 一上来就 ≥12s，位置一动就误判「视频冻结」。
            bufferingSinceMs = 0L
            return Decision(
              action = Action.RELOAD,
              resumePositionMs = positionMs,
              frozenMs = frozenMs,
              etaMs = inFlightEtaMs,
              startup = !playbackStarted,
              nextAutoRetryCount = autoRetryCount + 1,
            )
          }
          return Decision(
            action = Action.RETRY_EXHAUSTED,
            resumePositionMs = positionMs,
            frozenMs = nowMs - stallSinceMs,
            etaMs = inFlightEtaMs,
            startup = !playbackStarted,
            nextAutoRetryCount = autoRetryCount,
          )
        }
      }
    } else {
      stallBaselinePositionMs = positionMs
      stallSinceMs = 0L
      stallDeferStartMs = 0L
    }
    return Decision(Action.NONE, resumePositionMs = positionMs)
  }
}
