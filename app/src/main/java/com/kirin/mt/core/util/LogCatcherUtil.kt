package com.kirin.mt.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

object LogCatcherUtil {
  private val logger = KotlinLogging.logger("LogCatcher")
  private const val LOG_DIR = "crash_logs"
  private const val MANUAL_LOG_PREFIX = "logs_manual"
  private const val CRASH_LOG_PREFIX = "logs_crash"
  private const val LIVE_LOG_FILENAME = "logs_live.log"
  private const val MAX_LOG_COUNT = 10
  private const val MAX_PREVIEW_LINES = 500
  /** 实时日志大小上限：超过即裁掉最旧日志，保留较新部分（用户要求 10MB）。 */
  private const val MAX_LIVE_LOG_BYTES = 10L * 1024 * 1024
  /** 裁剪后保留的字节数（略小于上限，避免每写入几行就频繁裁剪）。 */
  private const val LIVE_TRIM_KEEP_BYTES = 9L * 1024 * 1024
  /** 每写入多少行检查一次大小（每行都 flush，但大小检查/裁剪每 N 行一次，减少 stat 开销）。 */
  private const val LIVE_SIZE_CHECK_LINES = 200
  /** 查看实时日志时只读尾部这么多字节，避免把 10MB 一次性塞进内存导致 TV 盒子 OOM。 */
  private const val LIVE_LOG_TAIL_BYTES = 2L * 1024 * 1024
  /** 播放器日志叠层每秒刷新用的轻量尾部读取量。 */
  private const val LIVE_OVERLAY_TAIL_BYTES = 256L * 1024

  enum class LogType { Manual, Crash, Live }

  data class LogFileInfo(
    val file: File,
    val type: LogType,
    val createdAt: Date,
  )

  private lateinit var appContext: Context

  var manualFiles: List<File> = emptyList()
    private set

  var crashFiles: List<File> = emptyList()
    private set

  private val recordingState = AtomicReference<RecordingState?>(null)

  val isRecording: Boolean
    get() = recordingState.get() != null

  private data class RecordingState(
    val process: Process,
    val writer: OutputStreamWriter,
    val file: File,
  )

  private val liveState = AtomicReference<LiveState?>(null)

  val isLiveLogging: Boolean
    get() = liveState.get() != null

  /** 实时日志文件（已存在时），用于在日志列表中展示和查看。 */
  val liveLogFile: File?
    get() = if (this::appContext.isInitialized) {
      val file = File(File(appContext.filesDir, LOG_DIR), LIVE_LOG_FILENAME)
      if (file.exists()) file else null
    } else {
      null
    }

  private data class LiveState(
    val process: Process,
    val file: File,
  )

  fun install(context: Context) {
    appContext = context.applicationContext
    runCatching {
      Runtime.getRuntime().exec("logcat -c")
      logger.info { "logcat cleared" }
    }

    val originHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
      logger.error(exception) { "======== UncaughtException ========" }
      logLogcat(manual = false)
      originHandler?.uncaughtException(thread, exception)
    }

    clearOldLogFiles()
    startLiveLogging()
  }

  fun logLogcat(manual: Boolean = false) {
    runCatching {
      val process = Runtime.getRuntime().exec("logcat -t 10000 -v threadtime")
      val reader = BufferedReader(InputStreamReader(process.inputStream))

      val logDir = File(appContext.filesDir, LOG_DIR)
      if (!logDir.exists()) logDir.mkdirs()

      val logFile = File(logDir, createFilename(manual))
      logFile.createNewFile()
      logger.info { "log file: $logFile" }

      logFile.writer().use { writer ->
        writer.writeDeviceInfo()
        writer.writeAppInfo()
        writer.appendLine("======== Logs ========")
        reader.useLines { lines ->
          lines.forEach { line ->
            writer.appendLine(line)
          }
        }
      }
    }.onFailure { error ->
      logger.error(error) { "write log to file failed" }
    }
  }

  fun startManualRecording(): Boolean {
    if (recordingState.get() != null) return false
    return runCatching {
      Runtime.getRuntime().exec("logcat -c")
      logger.info { "logcat cleared before recording" }

      val logDir = File(appContext.filesDir, LOG_DIR)
      if (!logDir.exists()) logDir.mkdirs()

      val logFile = File(logDir, createFilename(manual = true))
      logFile.createNewFile()
      logger.info { "recording log to: $logFile" }

      val writer = logFile.writer()
      writer.writeDeviceInfo()
      writer.writeAppInfo()
      writer.appendLine("======== Logs ========")
      writer.flush()

      val process = ProcessBuilder("logcat", "-v", "threadtime")
        .redirectErrorStream(true)
        .start()

      val previous = recordingState.getAndSet(RecordingState(process, writer, logFile))
      if (previous != null) {
        stopInternal(previous)
      }

      Thread {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
          var line: String?
          while (reader.readLine().also { line = it } != null) {
            val current = recordingState.get()
            if (current?.file != logFile) break
            current.writer.appendLine(line)
          }
        } catch (e: Exception) {
          logger.error(e) { "manual recording reader failed" }
        } finally {
          runCatching { reader.close() }
        }
      }.apply {
        name = "LogCatcher-recording"
        isDaemon = true
        start()
      }
      true
    }.getOrElse { error ->
      logger.error(error) { "start manual recording failed" }
      false
    }
  }

  fun stopManualRecording(): File? {
    val state = recordingState.getAndSet(null) ?: return null
    return stopInternal(state)
  }

  private fun stopInternal(state: RecordingState): File {
    runCatching {
      state.process.destroy()
      state.writer.flush()
      state.writer.close()
    }.onFailure { error ->
      logger.error(error) { "stop manual recording failed" }
    }
    updateLogFiles()
    return state.file
  }

  /**
   * 启动实时日志：常驻一个 logcat 进程，把输出持续追加到 [LIVE_LOG_FILENAME]，
   * 超过 [MAX_LIVE_LOG_BYTES] 时裁掉最旧部分（保留 [LIVE_TRIM_KEEP_BYTES]）。
   * 在 [install] 时自动启动，应用存活期间持续记录，跨重启累积——这样 PGC 黑屏等
   * 问题发生时日志已经在盘上，不用先手动开始录制。写者只在 reader 线程内持有，
   * 裁剪时同线程先关写者再改文件再重开，避免 append fd 与 rename 冲突。
   */
  fun startLiveLogging(): Boolean {
    if (liveState.get() != null) return false
    if (!this::appContext.isInitialized) return false
    return runCatching {
      val logDir = File(appContext.filesDir, LOG_DIR)
      if (!logDir.exists()) logDir.mkdirs()
      val logFile = File(logDir, LIVE_LOG_FILENAME)
      logFile.createNewFile()
      // 上次异常退出可能留下超限文件，启动时先裁一次。
      trimFileTail(logFile)

      val process = ProcessBuilder("logcat", "-v", "threadtime")
        .redirectErrorStream(true)
        .start()
      liveState.set(LiveState(process, logFile))

      Thread {
        var writer = OutputStreamWriter(FileOutputStream(logFile, true), Charsets.UTF_8)
        runCatching {
          if (logFile.length() == 0L) {
            writer.writeDeviceInfo()
            writer.writeAppInfo()
            writer.appendLine("======== Logs (live, rolling ${MAX_LIVE_LOG_BYTES / (1024 * 1024)}MB) ========")
          } else {
            writer.appendLine("======== resumed live logging ========")
          }
          writer.flush()
        }
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var linesSinceSizeCheck = 0
        try {
          while (true) {
            val line = reader.readLine() ?: break
            val current = liveState.get()
            if (current?.file != logFile) break
            writer.appendLine(line)
            // 每行 flush：Loading 卡死期间日志稀疏，攒够批量才 flush 会导致叠层读不到近期日志。
            runCatching { writer.flush() }
            linesSinceSizeCheck++
            if (linesSinceSizeCheck >= LIVE_SIZE_CHECK_LINES) {
              linesSinceSizeCheck = 0
              if (logFile.length() > MAX_LIVE_LOG_BYTES) {
                runCatching { writer.flush(); writer.close() }
                trimFileTail(logFile)
                writer = OutputStreamWriter(FileOutputStream(logFile, true), Charsets.UTF_8)
              }
            }
          }
        } catch (e: Exception) {
          logger.error(e) { "live recording reader failed" }
        } finally {
          runCatching { reader.close() }
          runCatching { writer.flush(); writer.close() }
        }
      }.apply {
        name = "LogCatcher-live"
        isDaemon = true
        start()
      }
      logger.info { "live logging started: $logFile" }
      true
    }.getOrElse { error ->
      logger.error(error) { "start live logging failed" }
      false
    }
  }

  fun stopLiveLogging() {
    val state = liveState.getAndSet(null) ?: return
    runCatching { state.process.destroy() }
    logger.info { "live logging stopped" }
  }

  /**
   * 裁剪日志文件：保留尾部 [LIVE_TRIM_KEEP_BYTES] 字节（跳到下一个换行按行对齐，
   * 丢弃首部不完整行），用临时文件替换原文件。调用方须保证此时无其它写者持有原文件 fd。
   */
  private fun trimFileTail(file: File) {
    runCatching {
      val len = file.length()
      if (len <= MAX_LIVE_LOG_BYTES) return@runCatching
      val keepFrom = (len - LIVE_TRIM_KEEP_BYTES).coerceAtLeast(0L)
      val tmp = File(file.parentFile, "${file.name}.trim.tmp")
      RandomAccessFile(file, "r").use { raf ->
        raf.seek(keepFrom)
        // 跳到下一个换行，避免把一行（可能正是 UTF-8 多字节字符的行）劈成两半
        var b = raf.read()
        while (b != -1 && b != '\n'.code) b = raf.read()
        FileOutputStream(tmp).use { out ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            val read = raf.read(buf)
            if (read <= 0) break
            out.write(buf, 0, read)
          }
        }
      }
      if (!file.delete()) logger.warn { "trim: delete original failed" }
      if (!tmp.renameTo(file)) logger.warn { "trim: rename tmp failed" }
    }.onFailure { logger.error(it) { "trim live log failed" } }
  }

  /**
   * 读取日志内容：小文件直接读全文；大文件（如实时日志）只读尾部 [LIVE_LOG_TAIL_BYTES]
   * 字节，避免把 10MB 一次性塞进内存导致 TV 盒子 OOM。
   */
  fun readLogContent(file: File): String {
    return runCatching {
      val len = file.length()
      if (len <= LIVE_LOG_TAIL_BYTES) return@runCatching file.readText()
      val sb = StringBuilder()
      FileInputStream(file).use { fis ->
        fis.channel.position(len - LIVE_LOG_TAIL_BYTES)
        BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { reader ->
          reader.readLine() // 丢弃首部可能不完整的一行
          while (true) {
            val line = reader.readLine() ?: break
            sb.appendLine(line)
          }
        }
      }
      sb.toString()
    }.getOrElse { "Read failed: ${it.message}" }
  }

  /**
   * 轻量版尾部读取：只读 [liveLogFile] 最后 256KB，返回最后 [maxLines] 行。
   * 给播放器日志叠层每秒调用用，避免每次读 2MB。文件不存在/读失败返回空表。
   */
  fun readLiveLogTailLines(maxLines: Int): List<String> {
    val file = liveLogFile ?: return emptyList()
    return runCatching {
      val len = file.length()
      if (len <= LIVE_OVERLAY_TAIL_BYTES) {
        file.readText().lines().takeLast(maxLines)
      } else {
        val sb = StringBuilder()
        FileInputStream(file).use { fis ->
          fis.channel.position(len - LIVE_OVERLAY_TAIL_BYTES)
          BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { reader ->
            reader.readLine() // 丢弃首部可能不完整的一行
            while (true) {
              val line = reader.readLine() ?: break
              sb.appendLine(line)
            }
          }
        }
        sb.toString().lines().takeLast(maxLines)
      }
    }.getOrElse { emptyList() }
  }

  private fun OutputStreamWriter.writeDeviceInfo() {
    val packageName = appContext.packageName
    val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      appContext.packageManager.getPackageInfo(
        packageName,
        android.content.pm.PackageManager.PackageInfoFlags.of(0),
      )
    } else {
      @Suppress("DEPRECATION")
      appContext.packageManager.getPackageInfo(packageName, 0)
    }
    val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
      packageInfo.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      packageInfo.versionCode.toLong()
    }

    appendLine("======== Device info ========")
    appendLine("App Version: ${packageInfo.versionName} ($versionCode)")
    appendLine("Package: $packageName")
    appendLine("Android Version: ${android.os.Build.VERSION.RELEASE} (${android.os.Build.VERSION.SDK_INT})")
    appendLine("Device: ${android.os.Build.DEVICE}")
    appendLine("Model: ${android.os.Build.MODEL}")
    appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
    appendLine("Brand: ${android.os.Build.BRAND}")
    appendLine("Product: ${android.os.Build.PRODUCT}")
    appendLine("Type: ${android.os.Build.TYPE}")
    appendLine("Hardware: ${android.os.Build.HARDWARE}")
    appendLine("ABIs: ${android.os.Build.SUPPORTED_ABIS?.joinToString() ?: "unknown"}")
  }

  private fun OutputStreamWriter.writeAppInfo() {
    appendLine("======== App info ========")
    appendLine("FilesDir: ${appContext.filesDir}")
    appendLine("CacheDir: ${appContext.cacheDir}")
  }

  private fun createFilename(manual: Boolean): String {
    val prefix = if (manual) MANUAL_LOG_PREFIX else CRASH_LOG_PREFIX
    val date = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.getDefault()).format(Date())
    return "${prefix}_$date.log"
  }

  fun allLogFiles(): List<LogFileInfo> {
    updateLogFiles()
    val live = liveLogFile?.let { LogFileInfo(it, LogType.Live, Date(it.lastModified())) }
    val manual = manualFiles.map { LogFileInfo(it, LogType.Manual, Date(it.lastModified())) }
    val crash = crashFiles.map { LogFileInfo(it, LogType.Crash, Date(it.lastModified())) }
    val all = (manual + crash) + (live?.let { listOf(it) } ?: emptyList())
    return all.sortedByDescending { it.createdAt.time }
  }

  fun readLogPreview(file: File, maxLines: Int = MAX_PREVIEW_LINES): String {
    return runCatching {
      file.bufferedReader().use { reader ->
        reader.lineSequence().take(maxLines).joinToString("\n")
      }
    }.getOrElse { "Read failed: ${it.message}" }
  }

  fun formatFileSize(bytes: Long): String {
    return when {
      bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
      bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
      else -> "$bytes B"
    }
  }

  fun shareLogFile(context: Context, file: File) {
    runCatching {
      val authority = "${context.packageName}.fileprovider"
      val uri = FileProvider.getUriForFile(context, authority, file)
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      val chooser = Intent.createChooser(intent, "Share log").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(chooser)
    }.onFailure { error ->
      logger.error(error) { "share log file failed: ${file.name}" }
    }
  }

  fun updateLogFiles() {
    val files = File(appContext.filesDir, LOG_DIR).listFiles()
    manualFiles = files
      ?.filter { it.name.startsWith(MANUAL_LOG_PREFIX) }
      ?.sortedBy { it.lastModified() }
      ?: emptyList()
    crashFiles = files
      ?.filter { it.name.startsWith(CRASH_LOG_PREFIX) }
      ?.sortedBy { it.lastModified() }
      ?: emptyList()
  }

  private fun clearOldLogFiles() {
    updateLogFiles()

    if (manualFiles.size > MAX_LOG_COUNT) {
      manualFiles.take(manualFiles.size - MAX_LOG_COUNT).forEach { it.delete() }
    }
    if (crashFiles.size > MAX_LOG_COUNT) {
      crashFiles.take(crashFiles.size - MAX_LOG_COUNT).forEach { it.delete() }
    }

    updateLogFiles()
  }
}
