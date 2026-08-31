package com.kirin.mt.core.webdav

import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.storage.WatchedStore
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeChannelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException

/** Piped 配置备份载荷:启用开关 + 实例 URL(空串=默认实例 [com.kirin.mt.core.youtube.YoutubePlaybackResolver.DEFAULT_PIPED_INSTANCE])。 */
@Serializable
data class PipedBackupData(
  val youtubeUsePiped: Boolean = false,
  val pipedInstanceUrl: String = "",
)

/** 备份/还原可选项:YouTube 关注频道、Piped 配置、已看完列表、B站账号、IPTV 源配置、日志。还原不支持日志(诊断产物只上传不还原)。 */
enum class WebDavBackupItem { Channels, Piped, Watched, BiliAccount, Iptv, Logs }

/** IPTV 源配置备份载荷:m3u 地址 + Basic Auth 账号/密码(空串=未配置)。含源密码,还原即恢复 IPTV 源配置。 */
@Serializable
data class IptvBackupData(
  val sourceUrl: String = "",
  val username: String = "",
  val password: String = "",
)

/** B站账号登录态备份载荷:SESSDATA/bili_jct/buvid/mid 及资料(face/uname/isVip)。含登录凭证,还原即恢复登录态。 */
@Serializable
data class BiliSessionBackupData(
  val sessData: String? = null,
  val biliJct: String? = null,
  val buvid3: String? = null,
  val buvid4: String? = null,
  val mid: Long? = null,
  val face: String? = null,
  val uname: String? = null,
  val isVip: Boolean = false,
)

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
  private val sessionStore: SessionStore,
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
  /** B站账号登录态备份文件名。 */
  private val biliSessionFileName = "session.json"
  /** IPTV 源配置备份文件名。 */
  private val iptvConfigFileName = "iptv_config.json"
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
      // 入口快速连通校验:不通直接判失败(8s 内),避免逐项上传时才发现并产生部分上传。
      ensureReachable(config)
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
      if (WebDavBackupItem.BiliAccount in items) backupBiliAccount(config)
      if (WebDavBackupItem.Iptv in items) backupIptvConfig(config)
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
      // 入口快速连通校验:不通直接判失败,避免还原中途断连造成部分写回(频道清空后未恢复)。
      ensureReachable(config)
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
      if (WebDavBackupItem.BiliAccount in items) restoreBiliAccount(config)
      if (WebDavBackupItem.Iptv in items) restoreIptvConfig(config)
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

  /** 备份 B站账号登录态(sessdata/bili_jct/buvid/mid/资料)到 `{url}/bilitv/session.json`(覆盖)。未登录则序列化空账号。失败即抛,让整体备份失败。 */
  private suspend fun backupBiliAccount(config: WebDavConfig) {
    val session = sessionStore.session.first()
    val data = BiliSessionBackupData(
      sessData = session.sessData,
      biliJct = session.biliJct,
      buvid3 = session.buvid3,
      buvid4 = session.buvid4,
      mid = session.mid,
      face = session.face,
      uname = session.uname,
      isVip = session.isVip,
    )
    val body = json.encodeToString(BiliSessionBackupData.serializer(), data).toByteArray()
    val ok = repository.put(biliSessionUrl(config), config.username, config.password, body)
    if (!ok) throw IOException("B站账号上传失败:服务器返回非 2xx")
  }

  /** 还原 B站账号登录态。备份文件缺失或解析失败时跳过,不使整体还原失败。 */
  private suspend fun restoreBiliAccount(config: WebDavConfig) {
    val bytes = repository.get(biliSessionUrl(config), config.username, config.password) ?: return
    val data = runCatching {
      json.decodeFromString(BiliSessionBackupData.serializer(), bytes.decodeToString())
    }.getOrNull() ?: return
    sessionStore.saveSession(data.sessData, data.biliJct)
    sessionStore.saveDeviceCookies(data.buvid3, data.buvid4)
    sessionStore.saveUserProfile(data.mid, data.face, data.uname, data.isVip)
  }

  /** 备份 IPTV 源配置(m3u 地址 + Basic Auth 账号/密码)到 `{url}/bilitv/iptv_config.json`(覆盖)。失败即抛,让整体备份失败。 */
  private suspend fun backupIptvConfig(config: WebDavConfig) {
    val s = settingsStore.settings.first()
    val data = IptvBackupData(
      sourceUrl = s.iptvSourceUrl,
      username = s.iptvSourceUsername,
      password = s.iptvSourcePassword,
    )
    val body = json.encodeToString(IptvBackupData.serializer(), data).toByteArray()
    val ok = repository.put(iptvConfigUrl(config), config.username, config.password, body)
    if (!ok) throw IOException("IPTV 配置上传失败:服务器返回非 2xx")
  }

  /** 还原 IPTV 源配置。备份文件缺失(旧备份)或解析失败时跳过,不使整体还原失败。 */
  private suspend fun restoreIptvConfig(config: WebDavConfig) {
    val bytes = repository.get(iptvConfigUrl(config), config.username, config.password) ?: return
    val data = runCatching {
      json.decodeFromString(IptvBackupData.serializer(), bytes.decodeToString())
    }.getOrNull() ?: return
    settingsStore.setIptvSourceUrl(data.sourceUrl)
    settingsStore.setIptvSourceUsername(data.username)
    settingsStore.setIptvSourcePassword(data.password)
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
      ensureReachable(config)
      repository.mkcol(logsDirUrl(config), config.username, config.password)
      val bytes = withContext(Dispatchers.IO) { info.file.readBytes() }
      val ok = repository.put(logFileUrl(config, info.file.name), config.username, config.password, bytes)
      if (!ok) throw IOException("日志上传失败:${info.file.name}")
    }
  }

  /**
   * 入口快速连通校验:对配置根 URL 发短超时 GET,不通(网络异常或任何非 2xx,含 401/403)
   * 即抛 [IOException] 让整体操作立刻判失败,错误信息同时提示网络与账密两种可能。
   */
  private suspend fun ensureReachable(config: WebDavConfig) {
    val reachable = repository.ping(trimTrailingSlash(config.url), config.username, config.password)
    if (!reachable) {
      throw IOException("无法连接服务器(请检查网络、服务器地址或账号密码)")
    }
  }

  private fun dirUrl(config: WebDavConfig): String = "${trimTrailingSlash(config.url)}/$backupDir"

  private fun fileUrl(config: WebDavConfig): String = "${dirUrl(config)}/$backupFileName"

  private fun pipedConfigUrl(config: WebDavConfig): String = "${dirUrl(config)}/$pipedConfigFileName"

  private fun watchedUrl(config: WebDavConfig): String = "${dirUrl(config)}/$watchedFileName"

  private fun biliSessionUrl(config: WebDavConfig): String = "${dirUrl(config)}/$biliSessionFileName"

  private fun iptvConfigUrl(config: WebDavConfig): String = "${dirUrl(config)}/$iptvConfigFileName"

  private fun logsDirUrl(config: WebDavConfig): String = "${dirUrl(config)}/$logsDir"

  private fun logFileUrl(config: WebDavConfig, name: String): String = "${logsDirUrl(config)}/$name"

  private fun trimTrailingSlash(url: String): String = url.trimEnd('/')
}
