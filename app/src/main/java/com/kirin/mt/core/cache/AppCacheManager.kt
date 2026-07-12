package com.kirin.mt.core.cache

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.kirin.mt.core.util.LogCatcherUtil
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppCacheManager(context: Context) {
  private val appContext = context.applicationContext
  private val logDir: File
    get() = File(appContext.filesDir, LOG_DIR_NAME)

  suspend fun cacheSizeBytes(): Long {
    return withContext(Dispatchers.IO) {
      appContext.cacheDir.sizeBytes() + logDir.sizeBytes()
    }
  }

  @OptIn(ExperimentalCoilApi::class)
  suspend fun clearCache(): CacheClearResult {
    return withContext(Dispatchers.IO) {
      val cacheDir = appContext.cacheDir
      val beforeBytes = cacheDir.sizeBytes() + logDir.sizeBytes()

      appContext.imageLoader.memoryCache?.clear()
      appContext.imageLoader.diskCache?.clear()
      cacheDir.listFiles()?.forEach { child -> child.deleteRecursively() }

      // 停止日志写入后再清理日志文件，避免 logcat 进程持有 fd 导致残留。
      LogCatcherUtil.stopLiveLogging()
      LogCatcherUtil.stopManualRecording()
      logDir.listFiles()?.forEach { child -> child.deleteRecursively() }
      // 重新启动实时日志，保证后续崩溃/问题仍有日志可循。
      LogCatcherUtil.startLiveLogging()
      LogCatcherUtil.updateLogFiles()

      CacheClearResult(clearedBytes = beforeBytes)
    }
  }
}

data class CacheClearResult(
  val clearedBytes: Long,
)

private const val LOG_DIR_NAME = "crash_logs"

private fun File.sizeBytes(): Long {
  if (!exists()) return 0L
  if (isFile) return length()
  return listFiles()?.sumOf { child -> child.sizeBytes() } ?: 0L
}
