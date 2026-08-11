package com.kirin.mt.core.network

import com.kirin.mt.core.settings.AppSettingsStore
import kotlinx.coroutines.flow.first
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * IPTV 频道模型。一个频道 = 频道名 + logo + 分组 + 镜像源 URL 列表。
 * 同名频道（多个镜像 URL）合并成一个频道，播放器里可切换源。
 */
data class IptvChannel(
  val name: String,
  val logo: String,
  val group: String,
  val urls: List<String>,
)

/**
 * IPTV 数据仓库：拉取配置的 m3u 播放列表 URL，解析成频道列表。
 * 与 [LiveRepository]（B站 API）完全独立 —— 无 B站 API、无 WBI 签名、无 cookie，
 * 只是对远程 m3u 文本做一次 GET + 解析。
 *
 * 源地址来自 [AppSettingsStore.iptvSourceUrl]（设置页配置）。
 */
class IptvRepository(
  private val client: OkHttpClient,
  private val appSettingsStore: AppSettingsStore,
) {
  suspend fun getChannels(): List<IptvChannel> {
    val settings = appSettingsStore.settings.first()
    val url = settings.iptvSourceUrl
    if (url.isBlank()) return emptyList()
    val body = try {
      val requestBuilder = Request.Builder()
        .url(url)
        .header("User-Agent", BiliHeaders.UserAgent)
      // 配置了账号则带 Basic Auth(密码可选)。
      if (settings.iptvSourceUsername.isNotBlank()) {
        requestBuilder.header(
          "Authorization",
          Credentials.basic(settings.iptvSourceUsername, settings.iptvSourcePassword),
        )
      }
      client.newCall(requestBuilder.build()).execute().use { response ->
        if (!response.isSuccessful) return emptyList()
        response.body?.string() ?: return emptyList()
      }
    } catch (error: Exception) {
      return emptyList()
    }
    return parseM3u(body)
  }

  /**
   * 校验 IPTV 源连通性(设置保存时调用):先 HEAD 轻量探测,HEAD 失败/超时/非 2xx 都回退 GET。
   * 返回 true=可达(2xx),false=不可达/网络错误/未配置。
   *
   * 注意:有些源服务器(如 cf.19961226.xyz/iptv/)对 HEAD 直接挂起超时、只响应 GET。
   * 因此 HEAD 的任何异常都不能当作连接失败,必须回退 GET 再判定。
   */
  suspend fun checkSourceReachable(url: String, username: String, password: String): Boolean {
    if (url.isBlank()) return false
    val requestBuilder = Request.Builder()
      .url(url)
      .header("User-Agent", BiliHeaders.UserAgent)
    if (username.isNotBlank()) {
      requestBuilder.header("Authorization", Credentials.basic(username, password))
    }
    // HEAD 轻量探测:异常/超时/非 2xx 均视为"不支持 HEAD",不致命,继续走 GET。
    val headOk = runCatching {
      client.newCall(requestBuilder.head().build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)
    if (headOk) return true
    // GET 回退:真实连通性判定(带 Basic Auth)。
    return runCatching {
      client.newCall(requestBuilder.get().build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)
  }

  /**
   * 解析 m3u 播放列表文本。处理样例（tmp/result.m3u）实测的坑：
   * 1. 同名频道多个镜像 URL → 合并成一个频道（urls 列表），非去重丢弃。
   * 2. 伪频道（名是时间戳，如 "2026-08-11 03:13:08"）→ 跳过。
   * 3. rtmp:// 流（ExoPlayer 不支持）→ 过滤。
   * 4. URL 的 query 串（?zzhed 等取流参数）→ 原样保留。
   */
  internal fun parseM3u(content: String): List<IptvChannel> {
    val channels = LinkedHashMap<String, IptvChannel>()
    var pendingName = ""
    var pendingLogo = ""
    var pendingGroup = ""
    var pendingUrl: String? = null

    for (rawLine in content.lineSequence()) {
      val line = rawLine.trim()
      when {
        line.startsWith("#EXTINF") -> {
          pendingLogo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
          pendingGroup = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
          pendingName = line.substringAfterLast(",").trim()
          pendingUrl = null
        }
        line.startsWith("#") -> Unit // 其它指令（#EXTM3U 等）忽略
        line.isNotBlank() -> {
          // 这是 URL 行，归属上一条 #EXTINF。
          val url = line
          if (pendingUrl == null) {
            pendingUrl = url
            // 过滤 rtmp（ExoPlayer 不支持）与伪频道（名是时间戳）。
            if (!url.startsWith("rtmp://") && !isPseudoChannel(pendingName)) {
              val existing = channels[pendingName]
              if (existing != null) {
                channels[pendingName] = existing.copy(urls = existing.urls + url)
              } else {
                channels[pendingName] = IptvChannel(
                  name = pendingName,
                  logo = pendingLogo,
                  group = pendingGroup,
                  urls = listOf(url),
                )
              }
            }
          }
        }
      }
    }
    return channels.values.toList()
  }

  /** 伪频道：名是时间戳（如 "2026-08-11 03:13:08"），是源里的元数据行非真频道。 */
  private fun isPseudoChannel(name: String): Boolean =
    name.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*"))
}
