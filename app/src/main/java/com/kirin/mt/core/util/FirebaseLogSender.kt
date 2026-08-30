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
 * 把日志行逐行 `Crashlytics.log()` 注入环形缓冲(约 64KB,超限自动丢最旧;注入前先降噪压缩,
 * 见 [INJECT_BUDGET_BYTES]),
 * 再以非致命异常 `recordException` 触发一次上报,控制台该 issue 下即可看到附带日志。
 *
 * google-services.json 未就位时 FirebaseApp 不会初始化,所有入口 runCatching 兜底:
 * 上报失败返回 Result.failure 供 UI toast 提示,不影响其它功能。
 */
object FirebaseLogSender {

  private val logger = KotlinLogging.logger("FirebaseLogSender")

  /**
   * 单次上报注入的字节预算:Crashlytics 环形缓冲 ~64KB(按字节计,超出丢最旧)。
   * 2026-08-30 实测:r1700 里 1500 行注入云端只剩 82 行,且整段截在 HWUI「Image decoding
   * logging dropped」刷屏段,SABR/ABR 关键行一都没带上——瓶颈是字节不是行数。注入前先
   * [selectLinesForInjection] 降噪+压缩,再按本预算从尾往前装,留 8KB 余量给报告头/customKeys。
   */
  private const val INJECT_BUDGET_BYTES = 56_000

  /** 注入前扫描的原文尾部行数窗口(降噪压缩在窗口内做,原文更大时用一来省内存)。 */
  private const val SCAN_LINES = 4000

  /** 已知刷屏噪音 tag(整类丢弃,按 logcat 业务内容匹配):排查用不上,徒吃 64KB 预算。 */
  private val NOISE_TAGS = listOf("HWUI", "CCodecConfig", "CCodecBuffers", "CCodec ", "DisplayData")

  /** 高价值 tag/关键字:64KB 预算不够时无条件保留(没命中则按普通行装满即止)。 */
  private val PRIORITY_TAGS = listOf(
    "YtSabr", "YtResolver", "MobilePlayer", "FirebaseLogSender", "LogCatcher",
    "FATAL", "ANR", "AndroidRuntime",
  )

  /** logcat 行的元数据前缀(时间戳/pid/tid/级别),折叠连续重复行时按剥离后的业务内容判重。 */
  private val LOGCAT_META_PREFIX =
    Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+""")

  /**
   * 注入前的降噪压缩:①连续重复行折叠(HWUI/CCodec 刷屏一行顶几百);②已知噪音 tag 整类丢弃;
   * ③按 64KB 字节预算从尾往前装——高价值行无条件保留,普通行装满即止。返回时保持时间序
   * (旧→新),越新的行越后注入,环形缓冲里越靠尾部越不容易被残余截断丢掉。
   */
  private fun selectLinesForInjection(content: String): List<String> {
    val scanned = content.lines().takeLast(SCAN_LINES)

    // ① 连续重复行折叠:按去掉时间戳/pid/tid/级别的业务内容判重,同内容连续行只留首个
    val collapsed = ArrayList<String>(scanned.size)
    var i = 0
    while (i < scanned.size) {
      collapsed.add(scanned[i])
      var run = 1
      while (i + run < scanned.size && logcatBody(scanned[i + run]) == logcatBody(scanned[i]) &&
        logcatBody(scanned[i]).isNotBlank()
      ) run++
      i += run
    }

    // ② 噪音 tag 整类丢弃
    val filtered = collapsed.filter { line ->
      val body = logcatBody(line)
      NOISE_TAGS.none { body.startsWith(it) || body.contains(" $it ") }
    }

    // ③ 字节预算:从尾往前收,优先行超预算也可挤出空当(最多溢出单行),普通行装满即止;
    // addFirst 还原时间序(旧→新),越新越后注入、在环形缓冲里越靠尾部越稳
    val kept = ArrayDeque<String>()
    var used = 0
    for (idx in filtered.indices.reversed()) {
      val line = filtered[idx]
      val cost = line.length + 1
      val priority = PRIORITY_TAGS.any { line.contains(it) }
      if (used + cost <= INJECT_BUDGET_BYTES || (priority && used < INJECT_BUDGET_BYTES)) {
        kept.addFirst(line)
        used += cost
      }
    }
    return kept.toList()
  }

  private fun logcatBody(line: String): String =
    line.replaceFirst(LOGCAT_META_PREFIX, "")

  /** recordException 落盘后到提醒 SDK 送行的延迟(毫秒):太早会赶在持久化前 flush 扑空。 */
  private const val SEND_DELAY_MS = 2_000L

  private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
      // 采集开关恒关:上传全部走显式 sendUnsentReports(见 [bindAutoReport] 注释)。
      crashlytics.isCrashlyticsCollectionEnabled = false
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
   * 订阅设置流,同步「崩溃日志自动上报」开关。语义:
   * - **开**:启动时无条件 sendUnsentReports 一次,送掉积压的崩溃报告/上一会话
   *   recordException(崩溃报告由 Crashlytics 自带捕获链在崩溃时持久化,天然跨启动,
   *   启动送行即送达;无积压时调用无害)。
   * - **关**:不主动上报,仅手动「分享并上报」可用。
   *
   * 采集属性恒关(isCrashlyticsCollectionEnabled=false,两档都一样):SDK 自动采集
   * 开启时 sendUnsentReports 是故意的 no-op、真实上传只剩「下次启动/退后台」,连手动
   * 分享都被拖成延迟送达——恒关后所有上传都走显式送行,会话内无需开关杂耍,上报成功
   * 的感知也不依赖常驻轮询。开关只决定「启动送行 + 崩溃日志注入(attachCrashLog)」。
   */
  fun bindAutoReport(store: AppSettingsStore) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
      store.settings
        .map { it.crashLogAutoReportEnabled }
        .distinctUntilChanged()
        .collect { enabled ->
          crashAutoReportEnabled = enabled
          runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.isCrashlyticsCollectionEnabled = false
            if (enabled) {
              crashlytics.sendUnsentReports()
              logger.info { "startup: 自动上报开,启动送行已触发(送掉积压报告)" }
            }
          }.onFailure { error ->
            logger.debug { "auto report configure skipped: ${error.message}" }
          }
          logger.info { "crash auto report: $enabled (启动送行=${enabled})" }
        }
    }
  }

  /**
   * 未捕获崩溃发生后由 LogCatcherUtil 的全局处理器调用(此时崩溃报告已在本进程排队):
   * 若开关打开,把刚落盘的崩溃日志尾部注入 Crashlytics 日志缓冲,崩溃报告即携带这些
   * 日志行——采集恒关后它不会由 SDK 自动送行,而是由 [bindAutoReport] 的启动送行
   * (switch 开时每启动检查一次)在下次启动送达。开关关闭时是 no-op。
   */
  fun attachCrashLog(file: File) {
    if (!crashAutoReportEnabled) return
    runCatching {
      val crashlytics = FirebaseCrashlytics.getInstance()
      crashlytics.log("======== Crash log: ${file.name} (${LogCatcherUtil.formatFileSize(file.length())}) ========")
      selectLinesForInjection(LogCatcherUtil.readLogContent(file)).forEach { line ->
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
   *
   * 返回值只代表「入队」阶段成败(未配置/读文件异常)。注意 SDK 的 sendUnsentReports()
   * 返回 void、没有上传完成回调(试过 addOnSuccessListener/addOnCompleteListener 均不可
   * 解析),真实网络成败只能看 logcat 诊断日志(TRuntime.CctTransportBackend 的
   * Making request / Couldn't open connection)与控制台——UI 对入队成功只 toast
   * 「已入队:上传中」,不显示假的成功。
   */
  fun sendLogFile(context: Context, file: File): Result<Unit> {
    val appContext = context.applicationContext
    if (!isAvailable(appContext)) {
      logger.warn { "send: Firebase 未配置,拒绝上报 ${file.name}" }
      return Result.failure(IllegalStateException("Firebase 未配置(google-services.json 未就位)"))
    }
    return runCatching {
      val crashlytics = FirebaseCrashlytics.getInstance()
      logger.info { "send: 开始 file=${file.name} size=${file.length()}B autoReport=$crashAutoReportEnabled" }
      // 分隔头与录制时的 "======== Logs ========" 风格对齐,控制台里方便定位日志体从哪开始
      crashlytics.log("======== Shared log: ${file.name} (${LogCatcherUtil.formatFileSize(file.length())}) ========")
      // 先降噪压缩再进环形缓冲(见 INJECT_BUDGET_BYTES 注释):噪声/HWUI 刷屏行会把预算吃光,
      // 导致云端只截到一段无效行。注序列保持时间序,最新的行最后进缓冲最稳。
      val lines = selectLinesForInjection(LogCatcherUtil.readLogContent(file))
      lines.forEach { line ->
        crashlytics.log(line)
      }
      crashlytics.setCustomKey("log_file", file.name)
      crashlytics.setCustomKey("log_size", file.length())
      crashlytics.recordException(RuntimeException("Manual log share: ${file.name}"))
      logger.info { "send: 降噪后注入 ${lines.size} 行 + recordException 入队" }
      // 采集恒关(bindAutoReport/install 已设 false)后,sendUnsentReports 恒生效:
      // 显式即时上传,不再受「自动采集开启时为 no-op」的拖累,也无须临时切开关。
      // 注意 checkForUnsentReports 不能做门控(自动采集开启模式下恒 false,会整个
      // 跳过送行);这里无条件调用,重复调用无害。SDK 不给上传完成回调(返回 void,
      // Task 监听器不可用,见 2cc5f1c/dfc6e9c),真实成败看 logcat 的
      // TRuntime.CctTransportBackend 诊断行与云端。
      //
      // 送行延后 [SEND_DELAY_MS]:recordException 的持久化是异步的,即时送行会赶在
      // 报告落盘前发出→flush 扑空(报告滞留设备队列,云端无任何传输尝试;r1708 真机
      // 18:51/19:13 两次复现)。延后 2s 给落盘留时间;多次分享各自调度,重复调用无害。
      mainHandler.postDelayed({
        runCatching {
          crashlytics.sendUnsentReports()
          logger.info { "send: sendUnsentReports 已触发,上报在途(延迟 ${SEND_DELAY_MS}ms)" }
        }.onFailure { error ->
          logger.error(error) { "send: sendUnsentReports 调用失败" }
        }
      }, SEND_DELAY_MS)
      Unit
    }.onFailure { error ->
      logger.error(error) { "send: 上报异常 ${file.name}" }
    }
  }
}