package com.kirin.mt.core.network

import com.kirin.mt.core.model.SourceHongguo
import com.kirin.mt.core.model.TvboxEpisode
import com.kirin.mt.core.model.TvboxLine
import com.kirin.mt.core.model.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 红果短剧数据源(P11-83):hongguoduanju.com 网页端解析,零配置零签名。
 *
 * spike 实锤(2026-09):第三方解析接口生态已全灭(cenguigui api-v2 无 A 记录、mov 域返回占位
 * 文本),而官方网页端全链路免签名——
 * ①搜索 `/search/{kw}?__loader=search_(keyword)/page` 返回纯 JSON(edenux 数据路由,比 SSR HTML
 *   轻 16 倍),`searchList[].video_data` 直出 series_id/标题/封面/角标/vid_list;固定 10 条/次,
 *   无可用分页参数(全量穷举无一命中),单发全量。
 * ②**网页匿名端每部剧只放开前 3 集**(实测 20/20 部剧 `accessible_episode_cnt=3`,与搜索 loader
 *   的 vid_list=3 一致;详情页 vid_list 虽有全量集,第 4 集起播放页全部打不开)。所以搜索 loader
 *   的 vid_list 就是「可播集全集」,直接用,不做详情补全(300KB/部,纯浪费);角标按可看集数改写。
 * ③播放 `/player/{series}/{vid}` SSR 内嵌 `main_url` 明文 MP4(qznovelvod CDN,裸请求 200 +
 *   Accept-Ranges),由 [TvboxRepository.resolveHongguoMainUrl] 懒解析,走 ProgressiveMediaSource。
 *
 * 卡片复用 TVBox 线路心智:单线路 [TvboxLine],分集 URL=播放页地址(播放时懒解析直链)。
 * 失败静默(网页改版/网络抖动按「无结果」落地,UI 落空态)。
 */
class HongguoRepository(private val client: OkHttpClient) {

  /** 搜索放宽到 10s/15s(单请求链路,SSR 数据路由 ~15KB 响应)。 */
  private val httpClient = client.newBuilder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  /**
   * 红果搜索:关键词 URL 拼路径段。URLEncoder 是表单编码(空格→`+`),路径段里 `+` 会被当
   * 字面加号,统一改 `%20`。永不抛错(最差空列表)。
   */
  suspend fun search(keyword: String): List<VideoSummary> = withContext(Dispatchers.IO) {
    try {
      val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
      val url = "$Site/search/$encoded?__loader=$SearchLoaderId"
      val request = Request.Builder().url(url).build()
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@withContext emptyList()
        val body = response.body?.string().orEmpty()
        // 纯文本/HTML 应答(改版、风控页)直接弃,只收 JSON。
        if (!body.trimStart().startsWith("{")) return@withContext emptyList()
        val page = json.decodeFromString(HongguoSearchPage.serializer(), body)
        page.searchList
          .filter { it.doc_type == DocTypeSeries }
          .mapNotNull { it.video_data }
          .filter { it.series_id.isNotBlank() && it.vid_list.isNotEmpty() }
          .map(::toVideoSummary)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptyList()
    }
  }

  /** 一部剧 → 一张卡:单线路「红果短剧」,分集=播放页 URL(播放时懒解析 main_url 直链)。 */
  private fun toVideoSummary(series: HongguoVideoData): VideoSummary {
    val episodes = series.vid_list.mapIndexed { index, vid ->
      TvboxEpisode(
        title = "第${index + 1}集",
        url = "$Site/player/${series.series_id}/$vid",
      )
    }
    // 角标:网页端只放开部分集时,「全66集」误导(选集面板只有前 3 集可播),改标「可看3集」;
    // 全放开(vid_list ≥ episode_cnt)或 cnt 未知时用官方角标文案,再兜底「短剧」。
    val badge = when {
      series.episode_cnt <= 0 || series.vid_list.size >= series.episode_cnt ->
        series.episode_right_text.ifBlank { BadgeFallback }
      else -> "可看${series.vid_list.size}集"
    }
    return VideoSummary(
      bvid = "hongguo:${series.series_id}",
      title = series.series_title.ifBlank { series.series_id },
      pic = series.series_cover,
      ownerName = OwnerName,
      ownerFace = "",
      ownerMid = 0L,
      view = 0,
      danmaku = 0,
      duration = 0,
      pubdate = 0L,
      badge = badge,
      source = SourceHongguo,
      tvboxLines = listOf(TvboxLine(name = LineName, episodes = episodes)),
    )
  }

  private companion object {
    const val Site = "https://hongguoduanju.com"
    /** edenx 数据路由 id:`?__loader=` 直出 loader JSON(免 250KB+ SSR HTML)。 */
    const val SearchLoaderId = "search_(keyword)/page"
    /** 搜索条目 doc_type=23 为剧集(其余为用户/话题等,弃)。 */
    const val DocTypeSeries = 23
    const val OwnerName = "红果短剧"
    const val LineName = "红果短剧"
    const val BadgeFallback = "短剧"
  }
}

/** 红果搜索 loader 数据(只取需要的字段,其余 ignoreUnknownKeys 丢弃;totalCount 等不消费)。 */
@Serializable
private data class HongguoSearchPage(val searchList: List<HongguoSearchItem> = emptyList())

/** 搜索条目:剧集(doc_type=23)带 video_data,其它类型 video_data 缺省。 */
@Serializable
private data class HongguoSearchItem(
  val doc_type: Int = 0,
  val video_data: HongguoVideoData? = null,
)

/** 一部剧的字段(类型漂移由 isLenient 容忍:字符串包数字等)。 */
@Serializable
private data class HongguoVideoData(
  val series_id: String = "",
  val series_title: String = "",
  val series_cover: String = "",
  val series_intro: String = "",
  val episode_cnt: Int = 0,
  /** 官方角标文案(如「全90集」),仅全放开时直接展示。 */
  val episode_right_text: String = "",
  /** 可播分集 vid 列表(网页匿名端=可播集全集,通常 3 集,见类注释②)。 */
  val vid_list: List<String> = emptyList(),
)