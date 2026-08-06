package com.kirin.mt.core.webdav

import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeChannelStore
import kotlinx.coroutines.flow.first
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

  /** 备份目录名(相对 WebDAV 根)。 */
  private val backupDir = "bilitv"
  private val backupFileName = "youtube_channels.json"

  /** 备份:序列化当前频道列表并上传。返回失败原因或成功。 */
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

  private fun dirUrl(config: WebDavConfig): String = "${trimTrailingSlash(config.url)}/$backupDir"

  private fun fileUrl(config: WebDavConfig): String = "${dirUrl(config)}/$backupFileName"

  private fun trimTrailingSlash(url: String): String = url.trimEnd('/')
}
