package com.kirin.mt.core.player

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import com.kirin.mt.core.youtube.sabr.media.SabrTerminalException

/**
 * P11-223:SABR **终态**错误立即上抛,不再走 media3 Loader 的重试节拍。
 *
 * ── 为什么 ────────────────────────────────────────────────────────────────────────────────
 * SABR 会话被判死(InvalidPoToken / RELOAD_PLAYER / SABR_ERROR)后,fetcher 侧早已快失败
 * ([SabrMediaFetcher] 的 `fast-fail no-fetch`,一个请求都不再发),但错误在播放器眼里只是"又一个
 * chunk 加载失败"⇒ media3 Loader 按默认策略继续重试(实测每 ~1~5 秒一次、共 9 次),直到预算耗尽
 * 才上抛给 `error-retry` 链。真机三次实测的**判死→上抛**延迟(见 docs §40.7):
 *   `14:22` 3.05s / `13:21` 13.96s / `11:19` **31.87s** —— 这段时间用户就是黑着屏干等,
 * 而重载本身还要 7.8~20.7 秒。
 *
 * ── 怎么判 ────────────────────────────────────────────────────────────────────────────────
 * 只认**终态**这一类([SabrTerminalException],由 [SabrDataSource] 包成 IOException 上抛),
 * 其余错误(超时/断连/HTTP)一律交给 [DefaultLoadErrorHandlingPolicy] 原样处理 ——
 * 那条路是**瞬态**错误的重试,不能动。
 *
 * 注意 [getMinimumLoadableRetryCount] **保持默认**:把它设 0 会把"瞬态错误也只试一次",
 * 而瞬态重试是 SABR 拉流的正常兜底;真正要做的只是"终态别重试"。
 */
class SabrLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {

  override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long =
    if (isSabrTerminal(loadErrorInfo.exception)) {
      // C.TIME_UNSET = 该错误不可重试 ⇒ Loader 立即判致命并上抛(不再等退避节拍)。
      C.TIME_UNSET
    } else {
      super.getRetryDelayMsFor(loadErrorInfo)
    }

  /** 沿 cause 链找 [SabrTerminalException];兼容旧路径(未挂 cause 时按消息前缀识别)。 */
  private fun isSabrTerminal(t: Throwable?): Boolean {
    var cur = t
    var depth = 0
    while (cur != null && depth < MAX_CAUSE_DEPTH) {
      if (cur is SabrTerminalException) return true
      if (cur.message?.startsWith(TERMINAL_MESSAGE_PREFIX) == true) return true
      cur = cur.cause
      depth++
    }
    return false
  }

  private companion object {
    /** [SabrDataSource] 包装终态错误时用的消息前缀(旧路径无 cause,靠它兜底识别)。 */
    const val TERMINAL_MESSAGE_PREFIX = "SABR terminal:"
    const val MAX_CAUSE_DEPTH = 8
  }
}
