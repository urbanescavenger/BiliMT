package com.kirin.mt.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogCatcherUtil {
  private val logger = KotlinLogging.logger("LogCatcher")
  private const val LOG_DIR = "crash_logs"
  private const val MANUAL_LOG_PREFIX = "logs_manual"
  private const val CRASH_LOG_PREFIX = "logs_crash"
  private const val MAX_LOG_COUNT = 10
  private const val MAX_PREVIEW_LINES = 500

  enum class LogType { Manual, Crash }

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
    val manual = manualFiles.map { LogFileInfo(it, LogType.Manual, Date(it.lastModified())) }
    val crash = crashFiles.map { LogFileInfo(it, LogType.Crash, Date(it.lastModified())) }
    return (manual + crash).sortedByDescending { it.createdAt.time }
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
