package com.kirin.mt.core.network

import com.kirin.mt.core.model.SourceTvbox
import com.kirin.mt.core.model.TvboxEpisode
import com.kirin.mt.core.model.TvboxLine
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.settings.AppSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TVBox(影视库)数据源(P11-77 spike → P11-81 配置化)。
 *
 * 站点来源:用户在设置里填写 TVBox 配置 URL,[TvboxRepository] 拉取 config 解析 `sites` 数组,
 * 只留 type 0/1「纯 HTTP 采集站」(标准 MacCMS REST API,`?ac=detail&wd=关键词` 一步直出含
 * `vod_play_url` 的完整结果,零代码执行可用);type 3(csp_jar / drpy2 JS,要执行第三方代码)与
 * searchable=0 的站静默过滤。config 是 JSONC(`//` 注释、尾逗号),用 kotlinx 1.9.0
 * `allowComments` 原生解析——严禁按行截断剥注释(URL 里的 `//` 会被误剥)。
 *
 * 播放复用 IPTV 直链路径:[VideoSummary.source] 置 [SourceTvbox],线路=站点(跨站同名同年份
 * 合并成一张多线路卡,线路=清晰度面板档位)。单站搜索独立容错,失败/超时/纯文本应答按
 * 「该站无结果」静默丢弃(采集站死站是常态)。
 */

/** 一个采集站(标准 MacCMS 采集接口,type 0/1 JSON)。 */
data class TvboxSite(
  val name: String,
  /** API 根地址,搜索时拼 `?ac=detail&wd=关键词`。 */
  val api: String,
)

/** TVBox config 里一个 site 条目(只取过滤需要的字段,其余 ignoreUnknownKeys 丢弃)。 */
@Serializable
private data class TvboxConfigSite(
  val name: String = "",
  /** 站点类型:0/1=MacCMS JSON/XML 采集站(收),3=csp/drpy 蜘蛛(丢)。缺省按 0。 */
  val type: Int = 0,
  val api: String = "",
  /** TVBox 惯例:0=不进搜索。缺省按 1。 */
  val searchable: Int = 1,
)

@Serializable
private data class TvboxConfigRoot(val sites: List<TvboxConfigSite> = emptyList())

/** 影视库源状态(搜索空态引导用)。 */
sealed interface TvboxSourceStatus {
  /** 未配置 TVBox 配置 URL。 */
  data object NotConfigured : TvboxSourceStatus
  /** 配置拉取/解析失败(附原因)。 */
  data class Failed(val message: String) : TvboxSourceStatus
  /** 配置就绪(含可用站数,可能为 0)。 */
  data class Ready(val siteCount: Int) : TvboxSourceStatus
}

/** 单站搜索响应(MacCMS `ac=detail` 返回的 list 自带播放地址,无需二次详情请求)。 */
@Serializable
private data class TvboxSearchResponse(val list: List<TvboxVod> = emptyList())

/** MacCMS 影片条目(只取搜索/合并需要的字段;各站字段类型不稳,vod_id/vod_year 可能是字符串)。 */
@Serializable
private data class TvboxVod(
  val vod_id: Long = 0L,
  val vod_name: String = "",
  val vod_pic: String = "",
  val vod_year: String = "",
  val vod_remarks: String = "",
  val vod_play_url: String = "",
)

/** 站内一条解析结果:站点(线路名)+ 影片。 */
private data class TvboxSiteVod(val site: TvboxSite, val vod: TvboxVod)

/**
 * TVBox 聚合搜索仓库。站点来自用户配置的 TVBox config URL(懒加载 + TTL 缓存),搜索时对
 * 全部站点并行扇出(单站独立容错,失败/超时静默丢弃——采集站死站是常态,不报错),同名同年份
 * 跨站合并成一张多线路卡。响应/站点质量差是常态:纯文本应答(如「暂不支持搜索」)、字段缺失/
 * 类型漂移、非法 JSON 全部按「该站无结果」处理。
 */
class TvboxRepository(
  private val client: OkHttpClient,
  private val appSettingsStore: AppSettingsStore,
) {

  /** 独立短超时 client:慢站自然掉队,不拖累别的站(对齐 probe-timeout 配置先行的教训)。 */
  private val httpClient = client.newBuilder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()

  /** config 拉取放宽到 15s(远程 gist/网盘慢);复用注入 client 的拦截器栈。 */
  private val configClient = client.newBuilder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

  /** m3u8 存活预检 client:5s 连/6s 读(比线路请求更紧,预检失败只是顺延不是终点)。 */
  private val probeClient = httpClient.newBuilder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .build()

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // 显式 null / 引号包数字(vod_id 字符串)等字段漂移全部容忍。
    coerceInputValues = true
  }

  /**
   * config 专用解析器:TVBox config 是 JSONC(大量 `//` 注释、尾逗号),allowComments 原生
   * 吃掉——严禁按行截断剥注释(URL 值里的 `//` 会被误剥成 `"http:`)。
   */
  private val configJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    allowComments = true
    allowTrailingComma = true
  }

  /** 线路解析缓存:原始 share/play URL → 可播 m3u8。切线路/失败重试不重复拉页。 */
  private val resolveCache = ConcurrentHashMap<String, String>()

  /** m3u8 死链缓存:url → 死判定到期时间戳(5 分钟)。自动重试/重进不再重复探测死链。 */
  private val deadUntilCache = ConcurrentHashMap<String, Long>()

  private val sitesMutex = Mutex()
  private val statusFlow = MutableStateFlow<TvboxSourceStatus>(TvboxSourceStatus.NotConfigured)

  /** 影视库源状态(搜索空态引导、设置保存校验共用)。 */
  val sourceStatus: StateFlow<TvboxSourceStatus> = statusFlow.asStateFlow()

  /** 缓存键=config URL;URL 变更即失效(换配置立查新站),TTL 内不重拉。 */
  private var cachedUrl: String? = null
  private var cachedSites: List<TvboxSite> = emptyList()
  private var cachedAtMs: Long = 0L

  /**
   * 当前可用站点(懒加载):读 settings 的配置 URL → 空返 NotConfigured;同 URL 且未过 TTL 用
   * 缓存;否则拉 config 解析。失败/未配置返回空列表(search 按无结果落地,状态流另行上报)。
   * 失败不缓存负结果——下次搜索自动重试。
   */
  private suspend fun currentSites(): List<TvboxSite> {
    val url = appSettingsStore.settings.first().tvboxConfigUrl.trim()
    if (url.isEmpty()) {
      statusFlow.value = TvboxSourceStatus.NotConfigured
      return emptyList()
    }
    sitesMutex.withLock {
      val cacheFresh = cachedUrl == url && System.currentTimeMillis() - cachedAtMs < ConfigTtlMs
      if (!cacheFresh) {
        val (sites, error) = fetchAndParseConfig(url)
        if (sites == null) {
          statusFlow.value = TvboxSourceStatus.Failed(error ?: "加载失败")
          return emptyList()
        }
        cachedUrl = url
        cachedSites = sites
        cachedAtMs = System.currentTimeMillis()
      }
      statusFlow.value = TvboxSourceStatus.Ready(cachedSites.size)
      return cachedSites
    }
  }

  /**
   * 拉取 + 解析 + 过滤一个 config。成功返回可用站点(type 0/1、searchable≠0、api 合法、
   * 按 api 去重防重复扇出),失败返回 null+原因。永不抛错。
   */
  private suspend fun fetchAndParseConfig(url: String): Pair<List<TvboxSite>?, String?> =
    withContext(Dispatchers.IO) {
      try {
        val request = Request.Builder().url(url).build()
        configClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            return@withContext null to "HTTP ${response.code}"
          }
          val body = response.body?.string().orEmpty()
          val root = configJson.decodeFromString(TvboxConfigRoot.serializer(), body)
          val sites = root.sites
            .asSequence()
            .filter { it.type in 0..1 }
            .filter { it.searchable != 0 }
            .filter { it.name.isNotBlank() && it.api.startsWith("http") }
            .map { TvboxSite(name = it.name.trim(), api = it.api.trim().trimEnd('/')) }
            .distinctBy { it.api }
            .toList()
          sites to null
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null to (e.message ?: e.javaClass.simpleName)
      }
    }

  /**
   * 设置页保存校验:拉取指定 URL 并解析,返回可用站数(失败 -1)。URL 与当前配置一致时
   * 预热缓存,保存后首次搜索不重拉。
   */
  suspend fun validateConfig(url: String): Int {
    val trimmed = url.trim()
    val (sites, _) = fetchAndParseConfig(trimmed)
    val count = sites?.size ?: return -1
    val currentUrl = appSettingsStore.settings.first().tvboxConfigUrl.trim()
    if (currentUrl == trimmed) {
      sitesMutex.withLock {
        cachedUrl = trimmed
        cachedSites = sites
        cachedAtMs = System.currentTimeMillis()
      }
      statusFlow.value = TvboxSourceStatus.Ready(count)
    }
    return count
  }

  /** 聚合搜索:全站扇出 → 合并。永不抛错(最差返回空列表,UI 落「无结果」)。 */
  suspend fun search(keyword: String): List<VideoSummary> = coroutineScope {
    val sites = currentSites()
    val siteResults = sites.map { site ->
      async { searchSite(site, keyword) }
    }.awaitAll()
    mergeResults(siteResults.filterNotNull().flatten())
  }

  /** 单站搜索,失败返回 null(扇出层静默丢弃)。取消(CancellationException)照抛,防吞取消竞态。 */
  private suspend fun searchSite(site: TvboxSite, keyword: String): List<TvboxSiteVod>? =
    withContext(Dispatchers.IO) {
      try {
        val request = Request.Builder()
          .url("${site.api}?ac=detail&wd=${URLEncoder.encode(keyword, "UTF-8")}")
          .build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) return@withContext null
          val body = response.body?.string().orEmpty()
          // 纯文本应答(如「暂不支持搜索」)直接弃,只收 JSON。
          if (!body.trimStart().startsWith("{")) return@withContext null
          val parsed = json.decodeFromString(TvboxSearchResponse.serializer(), body)
          parsed.list
            .map { TvboxSiteVod(site, it) }
            .filter { parseEpisodeList(it.vod).isNotEmpty() }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
    }

  /** 合并键:去空白小写片名 + 年份,同名同年份跨站视为同一部(多线路)。 */
  private fun mergeKey(vod: TvboxVod): String =
    "${vod.vod_name.replace(WHITESPACE_REGEX, "").lowercase()}|${vod.vod_year.trim()}"

  private fun mergeResults(entries: List<TvboxSiteVod>): List<VideoSummary> {
    val groups = LinkedHashMap<String, MutableList<TvboxSiteVod>>()
    entries.forEach { entry ->
      groups.getOrPut(mergeKey(entry.vod)) { mutableListOf() }.add(entry)
    }
    return groups.values.take(MaxMergedResults).map { members ->
      val first = members.first()
      // 每站一条线路 = 完整分集列表(选集在线路内换 index;线路=清晰度面板档位)。
      val lines = members.map { TvboxLine(name = it.site.name, episodes = parseEpisodeList(it.vod)) }
        .filter { it.episodes.isNotEmpty() }
      VideoSummary(
        bvid = "tvbox:${mergeKey(first.vod)}",
        title = first.vod.vod_name,
        pic = members.firstNotNullOfOrNull { it.vod.vod_pic.takeIf(String::isNotBlank) } ?: "",
        // 「UP主」位显示线路信息:单站显示站名,多站显示条数(站点明细进播放器「线路」面板)。
        ownerName = if (members.size == 1) first.site.name else "${lines.size} 条线路",
        ownerFace = "",
        ownerMid = 0L,
        view = 0,
        danmaku = 0,
        duration = 0,
        pubdate = 0L,
        badge = members.firstNotNullOfOrNull { it.vod.vod_remarks.takeIf(String::isNotBlank) } ?: "",
        source = SourceTvbox,
        tvboxLines = lines,
      )
    }
  }

  /**
   * 全分集解析:取一个播放组(`$$$` 分隔多源组,直链 m3u8 组优先——share/play 页组混着会
   * 把同一批集解析成两份),`#` 分集、`集名$URL` 逐条提取。空集名回落「正片」(电影组常无标题)。
   * 官方播放页(v.qq.com 等,JS 播放器无直链可提取)在集级剔除——整线剔空后该线路自动消失
   * (P11-81c:真机日志实测「tvbox line #0 episode#0 resolve failed: v.qq.com/x/cover/...」
   * 单线路影片无得顺延,直接播放失败;源头剔除后选集面板不再出现点了必失败的集)。
   */
  private fun parseEpisodeList(vod: TvboxVod): List<TvboxEpisode> {
    val groups = vod.vod_play_url.split("$$$")
      .map { group ->
        group.split("#").mapNotNull { segment ->
          val title = segment.substringBefore('$', "").trim()
          val url = segment.substringAfter('$', "").trim()
          if (url.startsWith("http") && !isOfficialPlayerPage(url)) {
            TvboxEpisode(title = title.ifBlank { "正片" }, url = url)
          } else {
            null
          }
        }
      }
      .filter { it.isNotEmpty() }
    return (groups.firstOrNull { parsed -> parsed.any { it.url.substringBefore('?').endsWith(".m3u8") } }
      ?: groups.firstOrNull())
      .orEmpty()
      .take(MaxEpisodesPerLine)
  }

  private companion object {
    val WHITESPACE_REGEX = Regex("\\s+")
    const val MaxMergedResults = 60
    const val MaxEpisodesPerLine = 500
    /** config 缓存 TTL:站点列表变化不频繁,5 分钟内复用,换 URL 即失效。 */
    const val ConfigTtlMs = 5 * 60 * 1000L
    /** m3u8 死链判定缓存 TTL:5 分钟后重新探测(CDN 可能回源补文件)。 */
    const val DeadProbeTtlMs = 5 * 60 * 1000L

    /**
     * 官方视频站播放页特征(`://host/` 片段,contains 匹配即可覆盖 http/https 与移动版子域):
     * 腾讯/爱奇艺/优酷/芒果/B站/搜狐/1905/PPTV/乐视。这些页面是 JS 播放器(部分带 DRM),
     * 正则三级提取拿不到直链,选集解析期直接剔除;采集站 share 页(非凡/量子)不含这些特征,不受影响。
     */
    val OFFICIAL_PLAYER_HOSTS = listOf(
      "://v.qq.com/",
      "://m.v.qq.com/",
      ".iqiyi.com/",
      ".youku.com/",
      ".mgtv.com/",
      ".bilibili.com/",
      ".sohu.com/",
      ".1905.com/",
      ".pptv.com/",
      ".le.com/",
      ".letv.com/",
    )
    /** share/play 页内嵌播放地址:非凡 `const url = "…index.m3u8?sign=…"`;其余站点兜底取页内首个 m3u8。 */
    val CONST_URL_REGEX = Regex("""const url\s*=\s*"([^"]+)"""")
    val M3U8_URL_REGEX = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")
    val RELATIVE_M3U8_REGEX = Regex("""["'](/[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""")
  }

  /**
   * 把一条线路 URL 解析成可直接喂 [androidx.media3.exoplayer.hls.HlsMediaSource] 的 m3u8 地址。
   * 直链 m3u8 原样返回;share/play HTML 页拉一次页面,按优先级提取:
   * ①非凡 share 页内嵌 `const url = "…"`(相对路径相对页面 URL 解析);
   * ②页内首个绝对 m3u8(极速 play 页含 `/play/<id>/index.m3u8`);
   * ③页内首个相对 m3u8。解析结果按原始 URL 缓存(切线路/重试不重复拉页)。
   * 失败返回 null(调用方按「该线路死」处理,顺延下一线路)。
   */
  suspend fun resolveLineUrl(rawUrl: String): String? {
    if (rawUrl.substringBefore('?').endsWith(".m3u8")) return rawUrl
    resolveCache[rawUrl]?.let { return it }
    return withContext(Dispatchers.IO) {
      try {
        val pageUrl = rawUrl.toHttpUrlOrNull() ?: return@withContext null
        val request = Request.Builder().url(pageUrl).build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) return@withContext null
          val html = response.body?.string().orEmpty()
          val extracted = extractM3u8(html)
            ?: return@withContext null
          val resolved = if (extracted.startsWith("http")) {
            extracted
          } else {
            // 相对路径(非凡 `/20230218/…/index.m3u8?sign=…`):RFC 相对引用直接对页面 URL 解析,含查询串。
            pageUrl.resolve(extracted)?.toString() ?: return@withContext null
          }
          resolveCache[rawUrl] = resolved
          resolved
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
    }
  }

  /** 页面 HTML → m3u8 地址(绝对或相对)。三级兜底见 [resolveLineUrl] 注释。 */
  private fun extractM3u8(html: String): String? {
    CONST_URL_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
      ?.let { return it }
    M3U8_URL_REGEX.find(html)?.value?.let { return it }
    RELATIVE_M3U8_REGEX.find(html)?.groupValues?.get(1)?.let { return it }
    return null
  }

  /**
   * m3u8 存活预检(P11-81d):解析「成功」≠ 文件还在——采集站老资源 CDN 404 是常态
   * (真机实测量子 vip1.lz-cdn.com 2022 年旧片 index.m3u8 返回 404,顺延逻辑不知情,
   * 播放器 Source error 后自动重试原样打死链)。用 Range 0-0 轻量探测(借 awesome-zhuiju-free
   * check-availability 手法):200/206 = 活;404/410 = 明确死,调用方顺延下一线路;其余
   * (403 防盗链/5xx/超时)保守按活处理,不误杀慢源,让播放器自己兜。死判定缓存 5 分钟,
   * 自动重试/重进不重复探测。裸头(无 Referer/Origin),与播放头一致。
   */
  suspend fun probePlayable(url: String): Boolean {
    deadUntilCache[url]?.let { until -> if (System.currentTimeMillis() < until) return false }
    return withContext(Dispatchers.IO) {
      try {
        val request = Request.Builder()
          .url(url)
          .header("Range", "bytes=0-0")
          .build()
        probeClient.newCall(request).execute().use { response ->
          when (response.code) {
            200, 206 -> true
            404, 410 -> {
              deadUntilCache[url] = System.currentTimeMillis() + DeadProbeTtlMs
              false
            }
            else -> true
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 探测网络失败按活处理——探测只是辅助,别因为探测网络抖动把好线路杀掉。
        true
      }
    }
  }

  /** 官方视频站播放页判定:命中 [OFFICIAL_PLAYER_HOSTS] 任一特征即不可懒解析,选集期剔除。 */
  private fun isOfficialPlayerPage(url: String): Boolean =
    OFFICIAL_PLAYER_HOSTS.any { url.contains(it) }
}