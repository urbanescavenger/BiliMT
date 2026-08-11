package com.kirin.mt.core.webdav

import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeChannelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * WebDAV 备份/还原编排:把 YouTube 关注频道列表序列化成单个 JSON 文件,
 * 上传到 WebDAV 服务器;还原时下载解析并写回 [YoutubeChannelStore]。
 *
 * 备份路径:`{config.url}/bilitv/youtube_channels.json`(先 mkcol 建 bilitv 目录)。
 * 序列化复用 [YoutubeChannelStore] 同款 `ListSerializer(YoutubeChannel.serializer())`。
 */
class WebDavBackupService(
  private val channelStore: YoutubeChannelStore,
  private val repository: WebDavRepository,
  private val json: Json,
) {
  private val serializer = ListSerializer(YoutubeChannel.serializer())

  /** 连通性探测(委托 repository),供保存配置前校验服务器可达。 */
  suspend fun ping(url: String, username: String, password: String): Boolean =
    repository.ping(url, username, password)

  /** 备份目录名(相对 WebDAV 根)。 */
  private val backupDir = "bilitv"
  private val backupFileName = "youtube_channels.json"
  /** 日志备份子目录。 */
  private val logsDir = "logs"

  /** 备份:序列化当前频道列表并上传,再把日志一起上传(同名覆盖),全部成功后删本地日志。 */
  suspend fun backup(config: WebDavConfig): Result<Unit> {
    if (!config.isConfigured) {
      return Result.failure(IllegalStateException("WebDAV 未配置"))
    }
    return runCatching {
      val channels = channelStore.channels.first()
      val body = json.encodeToString(serializer, channels).toByteArray()
      // 建目录(已存在时 mkcol 返回 405,内部视为成功)。
      repository.mkcol(dirUrl(config), config.username, config.password)
      val ok = repository.put(fileUrl(config), config.username, config.password, body)
      if (!ok) throw IOException("上传失败:服务器返回非 2xx")
      // 日志一起备份(覆盖),全部上传成功后才删本地日志,避免部分失败丢日志。
      backupLogs(config)
    }
  }

  /** 还原:下载并解析备份文件,写回频道列表。返回还原的频道数。 */
  suspend fun restore(config: WebDavConfig): Result<Int> {
    if (!config.isConfigured) {
      return Result.failure(IllegalStateException("WebDAV 未配置"))
    }
    return runCatching {
      val bytes = repository.get(fileUrl(config), config.username, config.password)
        ?: throw IOException("下载失败:文件不存在或服务器返回非 2xx")
      val channels = json.decodeFromString(serializer, bytes.decodeToString())
      channelStore.clear()
      channels.forEach { channelStore.add(it) }
      channels.size
    }
  }

  /**
   * 备份日志:把 crash_logs 目录下所有日志(手动/崩溃/实时)上传到 `{url}/bilitv/logs/`(同名覆盖)。
   * 全部上传成功后才删本地日志。无日志时直接返回。
   */
  private suspend fun backupLogs(config: WebDavConfig) {
    val logFiles = LogCatcherUtil.allLogFiles()
    if (logFiles.isEmpty()) return
    repository.mkcol(logsDirUrl(config), config.username, config.password)
    logFiles.forEach { info ->
      val bytes = withContext(Dispatchers.IO) { info.file.readBytes() }
      val ok = repository.put(logFileUrl(config, info.file.name), config.username, config.password, bytes)
      if (!ok) throw IOException("日志上传失败:${info.file.name}")
    }
    deleteBackedUpLogs(logFiles)
  }

  /** 删除已备份的本地日志。实时日志常驻写入,先停再删再重启,避免删掉后写者仍持有 fd。 */
  private fun deleteBackedUpLogs(logFiles: List<LogCatcherUtil.LogFileInfo>) {
    logFiles.forEach { info ->
      when (info.type) {
        LogCatcherUtil.LogType.Live -> {
          LogCatcherUtil.stopLiveLogging()
          info.file.delete()
          LogCatcherUtil.startLiveLogging()
        }
        else -> info.file.delete()
      }
    }
    LogCatcherUtil.updateLogFiles()
  }

  private fun dirUrl(config: WebDavConfig): String = "${trimTrailingSlash(config.url)}/$backupDir"

  private fun fileUrl(config: WebDavConfig): String = "${dirUrl(config)}/$backupFileName"

  private fun logsDirUrl(config: WebDavConfig): String = "${dirUrl(config)}/$logsDir"

  private fun logFileUrl(config: WebDavConfig, name: String): String = "${logsDirUrl(config)}/$name"

  private fun trimTrailingSlash(url: String): String = url.trimEnd('/')
}
