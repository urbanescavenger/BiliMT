package com.kirin.mt.core.util

import android.content.Context
import android.os.Build
import com.kirin.mt.core.app.AppInfo
import com.kirin.mt.core.settings.AppSettingsStore
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

  /** 崩溃自动上报是否开启(设置项默认关;由 [bindAutoReport] 订阅设置流持续同步)。 */
  @Volatile
  var crashAutoReportEnabled: Boolean = false
    private set

  fun install(context: Context) {
    val appContext = context.applicationContext
    runCatching {
      // JSON 就位时 FirebaseInitProvider 已初始化默认实例;未就位时 getInstance 抛
      // IllegalStateException,在这里捕获后只记 debug 日志,应用照常启动。
      val crashlytics = FirebaseCrashlytics.getInstance()
      crashlytics.isCrashlyticsCollectionEnabled = true
      crashlytics.setCustomKey("app_version", AppInfo(appContext).current().versionName)
      crashlytics.setCustomKey("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
    }.onFailure { error ->
      logger.debug { "install skipped (Firebase 未初始化): ${error.message}" }
    }
  }

  /** FirebaseApp 是否已初始化(JSON 未就位时为 false)。 */
  fun isAvailable(context: Context): Boolean {
    return runCatching { FirebaseApp.getInstance() }.isSuccess
  }

  /**
   * 订阅设置流,把「崩溃日志自动上报」开关同步到 Crashlytics 数据采集开关:
   * 关(默认)时崩溃数据不自动发往 Firebase,仅分享时手动「分享并上报」仍可用
   * (手动上报走 recordException + sendUnsentReports 主动放行,不依赖采集开关)。
   * 启动时收集一次前 crashAutoReportEnabled=false,走默认关闭语义,无需等待。
   */
  fun bindAutoReport(store: AppSettingsStore) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
      store.settings
        .map { it.crashLogAutoReportEnabled }
        .distinctUntilChanged()
        .collect { enabled ->
          crashAutoReportEnabled = enabled
          runCatching {
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
          }.onFailure { error ->
            logger.debug { "auto report configure skipped: ${error.message}" }
          }
          logger.info { "crash auto report: $enabled" }
        }
    }
  }

  /**
   * 未捕获崩溃发生后由 LogCatcherUtil 的全局处理器调用(此时崩溃报告已在本进程排队):
   * 若开关打开,把刚落盘的崩溃日志尾部注入 Crashlytics 日志缓冲,自动崩溃报告
   * (Crashlytics 自带捕获链,下次启动发送)即携带这些日志行。开关关闭时是 no-op。
   */
  fun attachCrashLog(file: File) {
    if (!crashAutoReportEnabled) return
    runCatching {
      val crashlytics = FirebaseCrashlytics.getInstance()
      crashlytics.log("======== Crash log: ${file.name} (${LogCatcherUtil.formatFileSize(file.length())}) ========")
      LogCatcherUtil.readLogContent(file).lines().takeLast(MAX_SEND_LINES).forEach { line ->
        crashlytics.log(line)
      }
      crashlytics.setCustomKey("crash_log_file", file.name)
      crashlytics.setCustomKey("log_size", file.length())
    }.onFailure { error ->
      logger.error(error) { "attach crash log failed: ${file.name}" }
    }
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
      if (!crashAutoReportEnabled) {
        // 采集开关关闭时 recordException 只入队不发,手动上报是用户明确动作,立即放行
        crashlytics.sendUnsentReports()
      }
    }
  }
}