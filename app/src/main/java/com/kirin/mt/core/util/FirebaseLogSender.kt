package com.kirin.mt.core.util

import android.content.Context
import android.os.Build
import com.kirin.mt.core.app.AppInfo
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Crashlytics 日志手动上报。Crashlytics 没有直接上传日志文件的 API,采用标准 workaround:
 * 把日志行逐行 `Crashlytics.log()` 注入环形缓冲(约 64KB,超限自动丢最旧,这里取结尾 1500 行),
 * 再以非致命异常 `recordException` 触发一次上报,控制台该 issue 下即可看到附带日志。
 *
 * google-services.json 未就位时 FirebaseApp 不会初始化,所有入口 runCatching 兜底:
 * 上报失败返回 Result.failure 供 UI toast 提示,不影响其它功能。
 */
object FirebaseLogSender {

  private val logger = KotlinLogging.logger("FirebaseLogSender")

  /** 单次上报注入的行数上限:Crashlytics 缓冲 ~64KB,1500 行足够且不会把报告撑爆。 */
  private const val MAX_SEND_LINES = 1500

  fun install(context: Context) {
    val appContext = context.applicationContext
    runCatching {
      // JSON 就位时 FirebaseInitProvider 已初始化默认实例;未就位时 getInstance 抛
      // IllegalStateException,在这里捕获后只记 debug 日志,应用照常启动。
      val crashlytics = FirebaseCrashlytics.getInstance()
      crashlytics.isCrashlyticsCollectionEnabled = true
      crashlytics.setCustomKey("app_version", AppInfo(appContext).current().versionName)
      crashlytics.setCustomKey("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
      crashlytics.debug = false
    }.onFailure { error ->
      logger.debug { "install skipped (Firebase 未初始化): ${error.message}" }
    }
  }

  /** FirebaseApp 是否已初始化(JSON 未就位时为 false)。 */
  fun isAvailable(context: Context): Boolean {
    return runCatching { FirebaseApp.getInstance() }.isSuccess
  }

  /**
   * 把 [file] 的日志尾部(最多 [MAX_SEND_LINES] 行)注入 Crashlytics 并以非致命异常上报。
   * 失败(未配置)时返回 [Result.failure] 供 UI toast。上报是异步排队的:
   * 方法成功返回只代表已入队,控制台通常几分钟内出现。
   */
  fun sendLogFile(context: Context, file: File): Result<Unit> {
    val appContext = context.applicationContext
    if (!isAvailable(appContext)) {
      return Result.failure(IllegalStateException("Firebase 未配置(google-services.json 未就位)"))
    }
    return runCatching {
      val crashlytics = FirebaseCrashlytics.getInstance()
      // 分隔头与录制时的 "======== Logs ========" 风格对齐,控制台里方便定位日志体从哪开始
      crashlytics.log("======== Shared log: ${file.name} (${LogCatcherUtil.formatFileSize(file.length())}) ========")
      val content = LogCatcherUtil.readLogContent(file)
      // 只取尾部 MAX_SEND_LINES 行:超长日志(LIVE 滚动 10MB)也只喂最新部分进环形缓冲
      content.lines().takeLast(MAX_SEND_LINES).forEach { line ->
        crashlytics.log(line)
      }
      crashlytics.setCustomKey("log_file", file.name)
      crashlytics.setCustomKey("log_size", file.length())
      crashlytics.recordException(RuntimeException("Manual log share: ${file.name}"))
    }
  }
}