package com.kirin.mt.core.network

import com.kirin.mt.core.model.SourceTvbox
import com.kirin.mt.core.model.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * TVBox(影视库)数据源 spike(P11-77):内置 MacCMS 采集站白名单,零配置直接可用。
 *
 * 可行性研究结论(docs 内 P11-77 条目):真实 TVBox 配置里 70% 站点是 csp_jar、22% 是 drpy2 JS
 * (两者都要执行第三方代码,排除);type 0/1「纯 HTTP 采集站」是标准 MacCMS REST API
 * (`?ac=detail&wd=关键词` 一步直出含 `vod_play_url` 的完整结果),零代码执行可用。
 * config 本身只是地址簿——本源直接内置站点白名单,不解析任何 TVBox config、不执行任何 jar/js。
 *
 * 播放复用 IPTV 直链路径:[VideoSummary.source] 置 [SourceTvbox],线路 URL 走 `iptvUrls`
 * 字段(同名同年份跨站合并=多线路,容灾对齐 IPTV 镜像源心智)。spike 局限:合并卡取各站
 * 第 1 集,剧集选集(分集列表)留待后续阶段。
 */

/** 一个内置采集站(标准 MacCMS 采集接口,type 1 JSON)。 */
data class TvboxSite(
  val name: String,
  /** API 根地址,搜索时拼 `?ac=detail&wd=关键词`。 */
  val api: String,
)

/**
 * 内置白名单:取自真实 TVBox 配置(饭太硬/摸鱼儿/小马三份重合名单)里 https 直连可达
 * 且支持 `wd` 搜索的站点(2026-09-06 实测;索尼「暂不支持搜索」、飞速/四九/可可 https 不可达,弃)。
 * 全部 https——manifest 未开 cleartext,http 站点接不进来,是刻意取舍。
 */
val TvboxBuiltInSites = listOf(
  TvboxSite("极速资源", "https://jszyapi.com/api.php/provide/vod/"),
  TvboxSite("量子资源", "https://cj.lziapi.com/api.php/provide/vod/"),
  TvboxSite("非凡资源", "https://ffzy1.tv/api.php/provide/vod/"),
  TvboxSite("暴风资源", "https://bfzyapi.com/api.php/provide/vod/"),
  TvboxSite("百度资源", "https://api.apibdzy.com/api.php/provide/vod/"),
)

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
 * TVBox 聚合搜索仓库。对内置白名单全站并行扇出(单站独立容错,失败/超时静默丢弃——采集站
 * 死站是常态,不报错),同名同年份跨站合并成一张多线路卡。响应/站点质量差是常态:
 * 纯文本应答(如「暂不支持搜索」)、字段缺失/类型漂移、非法 JSON 全部按「该站无结果」处理。
 */
class TvboxRepository {

  /** 独立短超时 client:慢站自然掉队,不拖累别的站(对齐 probe-timeout 配置先行的教训)。 */
  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // 显式 null / 引号包数字(vod_id 字符串)等字段漂移全部容忍。
    coerceInputValues = true
  }

  /** 聚合搜索:全站扇出 → 合并。永不抛错(最差返回空列表,UI 落「无结果」)。 */
  suspend fun search(keyword: String): List<VideoSummary> = coroutineScope {
    val siteResults = TvboxBuiltInSites.map { site ->
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
            .filter { firstEpisodeUrl(it.vod) != null }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
    }

  /** 播放地址:取第一个播放组(`$$$` 分隔多源)的第一集(`#` 分隔,`集名$URL`)。 */
  private fun firstEpisodeUrl(vod: TvboxVod): String? {
    val firstGroup = vod.vod_play_url.split("$$$").firstOrNull().orEmpty()
    val firstEpisode = firstGroup.split("#").firstOrNull().orEmpty()
    val url = firstEpisode.substringAfter('$', "").trim()
    return url.takeIf { it.startsWith("http") }
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
      VideoSummary(
        bvid = "tvbox:${mergeKey(first.vod)}",
        title = first.vod.vod_name,
        pic = members.firstNotNullOfOrNull { it.vod.vod_pic.takeIf(String::isNotBlank) } ?: "",
        // 「UP主」位显示线路信息:单站显示站名,多站显示条数(站点明细进播放器「线路」面板)。
        ownerName = if (members.size == 1) first.site.name else "${members.size} 条线路",
        ownerFace = "",
        ownerMid = 0L,
        view = 0,
        danmaku = 0,
        duration = 0,
        pubdate = 0L,
        badge = members.firstNotNullOfOrNull { it.vod.vod_remarks.takeIf(String::isNotBlank) } ?: "",
        source = SourceTvbox,
        // 每站第 1 集 URL = 多线路(播放器按 selectedQn 索引切换,IPTV 同机制)。
        // spike 局限:剧集只能看第 1 集;选集列表留待后续阶段。
        iptvUrls = members.mapNotNull { firstEpisodeUrl(it.vod) }.distinct(),
      )
    }
  }

  private companion object {
    val WHITESPACE_REGEX = Regex("\\s+")
    const val MaxMergedResults = 60
  }
}