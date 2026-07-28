package com.kirin.mt.core.player

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 直播流专用加载错误重试策略。
 *
 * 直播 CDN URL 偶尔 403/404/断连,ExoPlayer 默认策略很快放弃并抛致命错误。
 * 这里对可恢复的网络/HTTP 错误延长重试窗口、指数退避;对解码器等非网络错误
 * 直接放弃,避免无效死循环。
 */
class LiveLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
  override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
    val cause = loadErrorInfo.exception
    return if (isRetryableError(cause)) {
      // 指数退避:1s -> 2s -> 4s -> 8s -> 封顶 10s
      val shift = loadErrorInfo.errorCount.coerceAtMost(MAX_RETRY_SHIFT)
      val delay = 1000L shl shift
      delay.coerceAtMost(MAX_RETRY_DELAY_MS)
    } else {
      C.TIME_UNSET // 非可恢复错误,不内部重试,让上层走重载/提示逻辑
    }
  }

  override fun getMinimumLoadableRetryCount(dataType: Int): Int = MIN_RETRY_COUNT

  private fun isRetryableError(cause: Throwable?): Boolean {
    return when {
      cause == null -> true
      cause is SocketTimeoutException -> true
      cause is UnknownHostException -> true
      cause is IOException -> true
      cause is HttpDataSource.HttpDataSourceException -> true
      cause.cause != null -> isRetryableError(cause.cause)
      else -> false
    }
  }

  private companion object {
    const val MIN_RETRY_COUNT = 7
    const val MAX_RETRY_SHIFT = 6
    const val MAX_RETRY_DELAY_MS = 10_000L
  }
}
