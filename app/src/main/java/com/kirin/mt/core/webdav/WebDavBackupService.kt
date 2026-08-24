package com.kirin.mt.core.webdav

import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.storage.WatchedStore
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeChannelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/** Piped 配置备份载荷:启用开关 + 实例 URL(空串=默认实例 [com.kirin.mt.core.youtube.YoutubePlaybackResolver.DEFAULT_PIPED_INSTANCE])。 */
@Serializable
data class PipedBackupData(
  val youtubeUsePiped: Boolean = false,
  val pipedInstanceUrl: String = "",
)

/** 备份/还原可选项:YouTube 关注频道、Piped 配置、已看完列表、日志。还原不支持日志(诊断产物只上传不还原)。 */
enum class WebDavBackupItem { Channels, Piped, Watched, Logs }

/**
 * WebDAV 备份/还原编排:把 YouTube 关注频道列表序列化成单个 JSON 文件,
 * 上传到 WebDAV 服务器;还原时下载解析并写回 [YoutubeChannelStore]。
 * 同时备份/还原 Piped 配置(见 [PipedBackupData])。
 *
 * 备份路径:`{config.url}/bilitv/youtube_channels.json` + `bilitv/piped_config.json`
 * (先 mkcol 建 bilitv 目录)。序列化复用 [YoutubeChannelStore] 同款
 * `ListSerializer(YoutubeChannel.serializer())`。
 */
class WebDavBackupService(
  private val channelStore: YoutubeChannelStore,
  private val watchedStore: WatchedStore,
  private val repository: WebDavRepository,
  private val json: Json,
  private val settingsStore: AppSettingsStore,
) {
  private val serializer = ListSerializer(YoutubeChannel.serializer())
  private val watchedSerializer = ListSerializer(String.serializer())

  /** 连通性探测(委托 repo,供保存配置前校验服务器可达)。 */
  suspend fun ping(url: String, username: String, password: String): Boolean =
    repository.ping(url, username, password)

  /** 备份目录名(相对 WebDAV 根)。 */
  private val backupDir = "bilitv"
  private val backupFileName = "youtube_channels.json"
  /** Piped 配置备份文件名。 */
  private val pipedConfigFileName = "piped_config.json"
  /** 已看完列表备份文件名。 */
  private val watchedFileName = "watched.json"
  /** 日志备份子目录。 */
  private val logsDir = "logs"

  /** 备份:按所选项目子集上传(频道/Piped 配置/已看完列表/日志,同名覆盖),全部成功后删对应项目对应的本地日志。 */
  suspend fun backup(
    config: WebDavConfig,
    items: Set<WebDavBackupItem> = WebDavBackupItem.entries.toSet(),
  ): Result<Unit> {
    if (!config.isConfigured) {
      return Result.failure(IllegalStateException("WebDAV 未配置"))
    }
    return runCatching {
      // 建目录(已存在时 mkcol 返回 405,内部视为成功)。
      repository.mkcol(dirUrl(config), config.username, config.password)
      if (WebDavBackupItem.Channels in items) {
        val channels = channelStore.channels.first()
        val body = json.encodeToString(serializer, channels).toByteArray()
        val ok = repository.put(fileUrl(config), config.username, config.password, body)
        if (!ok) throw IOException("上传失败:服务器返回非 2xx")
      }
      if (WebDavBackupItem.Piped in items) backupPipedConfig(config)
      if (WebDavBackupItem.Watched in items) backupWatched(config)
      if (WebDavBackupItem.Logs in items) backupLogs(config)
    }
  }

  /** 还原:按 [items] 子集下载解析并写回(频道 + Piped 配置 + 已看完列表)。日志不还原。返回还原的频道数(未选频道则 0)。 */
  suspend fun restore(
    config: WebDavConfig,
    items: Set<WebDavBackupItem> = WebDavBackupItem.entries.toSet(),
  ): Result<Int> {
    if (!config.isConfigured) {
      return Result.failure(IllegalStateException("WebDAV 未配置"))
    }
    return runCatching {
      var count = 0
      if (WebDavBackupItem.Channels in items) {
        val bytes = repository.get(fileUrl(config), config.username, config.password)
          ?: throw IOException("下载失败:文件不存在或服务器返回非 2xx")
        val channels = json.decodeFromString(serializer, bytes.decodeToString())
        channelStore.clear()
        channels.forEach { channelStore.add(it) }
        count = channels.size
      }
      if (WebDavBackupItem.Piped in items) restorePipedConfig(config)
      if (WebDavBackupItem.Watched in items) restoreWatched(config)
      count
    }
  }

  /** 备份 Piped 配置(启用开关 + 实例 URL)到 `{url}/bilitv/piped_config.json`(覆盖)。失败即抛,让整体备份失败。 */
  private suspend fun backupPipedConfig(config: WebDavConfig) {
    val s = settingsStore.settings.first()
    val data = PipedBackupData(
      youtubeUsePiped = s.youtubeUsePiped,
      pipedInstanceUrl = s.pipedInstanceUrl,
    )
    val body = json.encodeToString(PipedBackupData.serializer(), data).toByteArray()
    val ok = repository.put(pipedConfigUrl(config), config.username, config.password, body)
    if (!ok) throw IOException("Piped 配置上传失败:服务器返回非 2xx")
  }

  /** 还原 Piped 配置。备份文件缺失(旧备份)或解析失败时跳过,不使整体还原失败。 */
  private suspend fun restorePipedConfig(config: WebDavConfig) {
    val bytes = repository.get(pipedConfigUrl(config), config.username, config.password) ?: return
    val data = runCatching {
      json.decodeFromString(PipedBackupData.serializer(), bytes.decodeToString())
    }.getOrNull() ?: return
    settingsStore.setYoutubeUsePiped(data.youtubeUsePiped)
    settingsStore.setPipedInstanceUrl(data.pipedInstanceUrl)
  }

  /** 备份「已看完列表」到 `{url}/bilitv/watched.json`(覆盖)。失败即抛,让整体备份失败。 */
  private suspend fun backupWatched(config: WebDavConfig) {
    val ids = watchedStore.all()
    val body = json.encodeToString(watchedSerializer, ids).toByteArray()
    val ok = repository.put(watchedUrl(config), config.username, config.password, body)
    if (!ok) throw IOException("已看完列表上传失败:服务器返回非 2xx")
  }

  /** 还原「已看完列表」。备份文件缺失(旧备份)或解析失败时跳过,不使整体还原失败。 */
  private suspend fun restoreWatched(config: WebDavConfig) {
    val bytes = repository.get(watchedUrl(config), config.username, config.password) ?: return
    val ids = runCatching {
      json.decodeFromString(watchedSerializer, bytes.decodeToString())
    }.getOrNull() ?: return
    watchedStore.replaceAll(ids)
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

  /**
   * 备份单个日志文件到 `{url}/bilitv/logs/<name>`(同名覆盖)。日志分享面板「备份」按钮用:
   * 只上传不删本地(区别于全量备份 [backup] 的备份后删除),便于逐条备份某个 crash/manual 日志。
   */
  suspend fun backupLogFile(config: WebDavConfig, info: LogCatcherUtil.LogFileInfo): Result<Unit> {
    if (!config.isConfigured) {
      return Result.failure(IllegalStateException("WebDAV 未配置"))
    }
    return runCatching {
      repository.mkcol(logsDirUrl(config), config.username, config.password)
      val bytes = withContext(Dispatchers.IO) { info.file.readBytes() }
      val ok = repository.put(logFileUrl(config, info.file.name), config.username, config.password, bytes)
      if (!ok) throw IOException("日志上传失败:${info.file.name}")
    }
  }

  private fun dirUrl(config: WebDavConfig): String = "${trimTrailingSlash(config.url)}/$backupDir"

  private fun fileUrl(config: WebDavConfig): String = "${dirUrl(config)}/$backupFileName"

  private fun pipedConfigUrl(config: WebDavConfig): String = "${dirUrl(config)}/$pipedConfigFileName"

  private fun watchedUrl(config: WebDavConfig): String = "${dirUrl(config)}/$watchedFileName"

  private fun logsDirUrl(config: WebDavConfig): String = "${dirUrl(config)}/$logsDir"

  private fun logFileUrl(config: WebDavConfig, name: String): String = "${logsDirUrl(config)}/$name"

  private fun trimTrailingSlash(url: String): String = url.trimEnd('/')
}
