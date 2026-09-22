package com.kirin.mt.core.cache

import android.content.Context
import android.util.Log
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
      // P11-96:清缓存全程打日志——用户反馈「清除缓存才能正常播放」(09-13),但清除动作从不
      // 落日志,时间线上与装包/重启混在一起无法归因。以后每次清除有据可查:何时、清了多少、
      // cacheDir 里有什么(与播放故障的因果链靠这个判断)。
      val cacheDir = appContext.cacheDir
      val entries = cacheDir.listFiles()?.map { "${it.name}(${if (it.isDirectory) "d" else "${it.length()}B"})" }.orEmpty()
      val beforeBytes = cacheDir.sizeBytes() + logDir.sizeBytes()
      Log.i(TAG, "clearCache start before=${beforeBytes / 1024}KB entries=${entries.size} [${entries.take(20).joinToString(",")}]")

      appContext.imageLoader.memoryCache?.clear()
      appContext.imageLoader.diskCache?.clear()
      Log.i(TAG, "clearCache coil memory+disk cache cleared")
      cacheDir.listFiles()?.forEach { child -> child.deleteRecursively() }
      Log.i(TAG, "clearCache cacheDir wiped dir=${cacheDir.path}")

      // 停止日志写入后再清理日志文件，避免 logcat 进程持有 fd 导致残留。
      LogCatcherUtil.stopLiveLogging()
      LogCatcherUtil.stopManualRecording()
      logDir.listFiles()?.forEach { child -> child.deleteRecursively() }
      // 重新启动实时日志，保证后续崩溃/问题仍有日志可循。
      LogCatcherUtil.startLiveLogging()
      LogCatcherUtil.updateLogFiles()

      Log.i(TAG, "clearCache done cleared=${beforeBytes / 1024}KB (logs wiped & live logging restarted)")
      CacheClearResult(clearedBytes = beforeBytes)
    }
  }
}

data class CacheClearResult(
  val clearedBytes: Long,
)

private const val TAG = "BiliMT:AppCache"

private const val LOG_DIR_NAME = "crash_logs"

private fun File.sizeBytes(): Long {
  if (!exists()) return 0L
  if (isFile) return length()
  return listFiles()?.sumOf { child -> child.sizeBytes() } ?: 0L
}
