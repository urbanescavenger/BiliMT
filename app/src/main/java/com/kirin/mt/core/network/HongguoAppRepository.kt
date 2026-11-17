package com.kirin.mt.core.network

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.hongguotv.core.AppCommentPages
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.MemoryCookies
import com.hongguotv.core.PlayerDeviceStore
import com.hongguotv.core.PublicComments
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RecommendationFeed
import com.hongguotv.core.Series
import com.hongguotv.core.TimedComments
import com.kirin.mt.core.model.Comment
import com.kirin.mt.core.model.SourceHongguo
import com.kirin.mt.core.model.TvboxEpisode
import com.kirin.mt.core.model.TvboxLine
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.player.DanmakuEntry
import com.kirin.mt.core.player.DanmakuMode
import com.kirin.mt.core.player.HongguoStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 红果评论一页（游标分页；[nextCursor] 为空表示到底）。 */
data class HongguoCommentPage(
  val comments: List<Comment>,
  val hasMore: Boolean,
  val nextCursor: String,
  val total: Int,
)

/**
 * 红果 App 端数据源（GPL-3.0-only `:hongguo-core` 的应用侧适配）。
 *
 * 只负责三件事：
 * 1. 搜索：官方 App 搜索接口，拿剧集元数据；卡片不带分集，点击后再拉详情。
 * 2. 详情：网页 SSR 的 `vid_list` 是完整分集（网页只放开前 3 集播放，但分集 ID 全量都在）。
 * 3. 播放：`player/v1` 取流后返回 [HongguoStream]（远端 URL + CENC 密钥），由
 *    [com.kirin.mt.core.player.HongguoDataSource] 在 Media3 里边读边解，首帧不再等整集下载。
 *
 * 失败一律返回 null，调用方退回网页 3 集路径或报错，不在外层做无限重试。
 */
class HongguoAppRepository(
  private val context: Context,
) {
  // 独立 client：不继承 B 站 Cookie/Referer 拦截器（P11-83 实测 B 站 Referer 打红果 CDN 会 403），
  // 也不把 B 站账号 Cookie 带进红果请求。MemoryCookies 只服务红果站点自己的 ttwid 流转。
  private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .cookieJar(MemoryCookies())
    .build()

  private val content = ContentRepository(http)
  /** 官方推荐流游标跨页复用；page<=1 或失败时重建。 */
  private var recommendationFeed: RecommendationFeed? = null
  /** 定时弹幕按播放时间窗读取；内部有 60s 窗口缓存。 */
  private val timedCommentReader = TimedComments()
  private val appComments = AppCommentPages()
  private val publicComments = PublicComments(http)

  init {
    // 匿名设备身份持久化：换进程后不重复注册，减少上游注册频控。
    ContentRepository.deviceStore = SharedPreferencesDeviceStore(context)
  }

  suspend fun search(keyword: String): List<VideoSummary> = withContext(Dispatchers.IO) {
    // App 搜索、网页搜索各走一条链：App 结果通常更准但条数少，网页 searchAll 支持分页。
    // 两边都失败时由 HongguoRepository 再回退旧 __loader 网页搜索。
    val app = runCatching { content.search(keyword) }
      .onFailure { Log.w(Tag, "App 搜索失败 kw=$keyword: ${it.message}") }
      .getOrNull()
    // 不传 type：网页搜索跨真人/漫剧/AI 三类，genre=205 的漫剧也能搜到。
    // 传 ContentType.SHORT 时 searchAll 只留 genre=203/空，实测「新星」10 条全是 205，
    // 会被整页过滤成 0；这是“搜不到主流视频”的直接原因。
    val web1 = runCatching { content.searchAll(keyword, page = 1) }
      .onFailure { Log.w(Tag, "网页搜索失败 kw=$keyword: ${it.message}") }
      .getOrNull()
    val web2 = if (web1?.hasMore == true) {
      runCatching { content.searchAll(keyword, page = 2) }
        .onFailure { Log.w(Tag, "网页搜索第2页失败 kw=$keyword: ${it.message}") }
        .getOrNull()
    } else {
      null
    }
    val merged = (app?.items.orEmpty() + web1?.items.orEmpty() + web2?.items.orEmpty())
      // 详情/取流接口只认数字 series_id；book_id/搜索结果 id 混入的条目直接丢掉。
      .filter { it.id.matches(Regex("[0-9]{1,30}")) }
      .distinctBy { it.id }
      .map { toSummary(it, emptyList()) }
    Log.i(
      Tag,
      "搜索 kw=$keyword app=${app?.items?.size ?: -1} web1=${web1?.items?.size ?: -1} " +
        "web2=${web2?.items?.size ?: -1} merged=${merged.size}",
    )
    merged
  }

  /**
   * 红果推荐 Tab 数据。
   *
   * 主源用「综合热榜」：完整网格约 20 条/页、共 5 页，适合 TV/移动网格。
   * 官方 App 推荐流每页只回 2~3 条（竖屏轮播式），做网格会只剩几部，降为兜底；
   * 再失败才回退网页首页推荐。
   */
  suspend fun feed(page: Int = 1): List<VideoSummary> = withContext(Dispatchers.IO) {
    val ranked = runCatching {
      content.rankPage(RankBoard.ALL, page).items
        .filter { it.id.matches(Regex("[0-9]{1,30}")) }
        .distinctBy { it.id }
        .map { toSummary(it, emptyList()) }
    }.onFailure { Log.w(Tag, "榜单推荐失败 page=$page: ${it.message}") }.getOrNull()
    if (!ranked.isNullOrEmpty()) {
      Log.i(Tag, "推荐 page=$page count=${ranked.size} source=rank")
      return@withContext ranked
    }
    runCatching {
      if (page <= 1 || recommendationFeed == null) {
        recommendationFeed = content.createRecommendationFeed()
      }
      val catalog = recommendationFeed!!.page(page)
      val items = catalog.items
        .filter { it.id.matches(Regex("[0-9]{1,30}")) }
        .distinctBy { it.id }
        .map { toSummary(it, emptyList()) }
      if (items.isEmpty()) throw IOException("推荐为空")
      items
    }.onFailure {
      recommendationFeed = null
      Log.w(Tag, "推荐失败 page=$page: ${it.message}")
    }.getOrElse {
      runCatching {
        content.home(page).items
          .filter { it.id.matches(Regex("[0-9]{1,30}")) }
          .distinctBy { it.id }
          .map { toSummary(it, emptyList()) }
      }.onFailure { Log.w(Tag, "推荐回退失败 page=$page: ${it.message}") }
        .getOrDefault(emptyList())
    }.also { Log.i(Tag, "推荐 page=$page count=${it.size} source=feed/web") }
  }

  suspend fun detail(seriesId: String): VideoSummary? = withContext(Dispatchers.IO) {
    runCatching {
      val detail = content.detail(seriesId)
      toSummary(detail.series, detail.episodes)
    }.onFailure { Log.w(Tag, "详情失败 series=$seriesId: ${it.message}") }.getOrNull()
  }

  /**
   * 红果分集评论（匿名、只读）。主源官方 App 评论分页；失败时首屏回退网页公开评论/剧评。
   * 游标分页：首屏 cursor 传空串，翻页用上一页的 nextCursor。
   */
  suspend fun comments(
    seriesId: String,
    episodeId: String,
    cursor: String = "",
  ): HongguoCommentPage = withContext(Dispatchers.IO) {
    if (!seriesId.matches(Regex("[0-9]{6,30}")) || !episodeId.matches(Regex("[0-9]{6,30}"))) {
      return@withContext HongguoCommentPage(emptyList(), hasMore = false, nextCursor = "", total = 0)
    }
    val page = runCatching {
      parseAppComments(appComments.read(seriesId, episodeId, cursor))
    }.getOrElse { error ->
      Log.w(Tag, "评论失败 series=$seriesId vid=$episodeId cursor=${cursor.length}: ${error.message}")
      if (cursor.isBlank()) {
        runCatching { parsePublicComments(publicComments.read(seriesId, episodeId)) }
          .onFailure { Log.w(Tag, "公开评论回退失败 series=$seriesId vid=$episodeId: ${it.message}") }
          .getOrNull()
      } else {
        null
      }
    } ?: HongguoCommentPage(emptyList(), hasMore = false, nextCursor = "", total = 0)
    Log.i(Tag, "评论 series=$seriesId vid=$episodeId count=${page.comments.size} more=${page.hasMore}")
    page
  }

  private fun parseAppComments(result: org.json.JSONObject): HongguoCommentPage {
    val items = result.optJSONArray("items") ?: org.json.JSONArray()
    return HongguoCommentPage(
      comments = mapComments(items),
      hasMore = result.optBoolean("hasMore", false),
      nextCursor = result.optString("nextCursor"),
      total = result.optLong("total", items.length().toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
    )
  }

  private fun parsePublicComments(result: org.json.JSONObject): HongguoCommentPage {
    val block = result.optJSONObject("comments") ?: org.json.JSONObject()
    val items = block.optJSONArray("items") ?: org.json.JSONArray()
    return HongguoCommentPage(
      comments = mapComments(items),
      hasMore = false,
      nextCursor = "",
      total = block.optLong("total", items.length().toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
    )
  }

  private fun mapComments(items: org.json.JSONArray): List<Comment> =
    (0 until items.length()).mapNotNull { index ->
      val row = items.optJSONObject(index) ?: return@mapNotNull null
      val id = row.optString("id")
      val content = row.optString("content").trim()
      if (id.isBlank() || content.isBlank()) return@mapNotNull null
      Comment(
        id = id.toLongOrNull() ?: (id.hashCode().toLong() and 0xffffffffL),
        uname = row.optString("author").ifBlank { "红果用户" },
        avatar = row.optString("avatar"),
        mid = 0L,
        content = content,
        likeCount = row.optLong("likes", 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        replyCount = 0,
        ctime = row.optLong("created", 0L).coerceAtLeast(0L),
      )
    }.distinctBy { it.id }

  /**
   * 红果定时弹幕（匿名、只读）。接口按播放时间窗返回，单次只给一段；
   * 短剧预取前 5 分钟（最多 20 个窗口），避免一次拉几十段。
   */
  suspend fun timedDanmaku(
    seriesId: String,
    episodeId: String,
    durationMs: Long = 0L,
  ): List<DanmakuEntry> = withContext(Dispatchers.IO) {
    if (!seriesId.matches(Regex("[0-9]{6,30}")) || !episodeId.matches(Regex("[0-9]{6,30}"))) {
      return@withContext emptyList()
    }
    val duration = durationMs.coerceAtLeast(300_000L).coerceAtMost(600_000L)
    runCatching {
      val out = mutableListOf<DanmakuEntry>()
      var start = 0L
      var cursor = ""
      var windows = 0
      while (start < duration && windows++ < 20) {
        val page = timedCommentReader.read(seriesId, episodeId, start, duration, cursor)
        val rows = page.optJSONArray("items") ?: break
        for (i in 0 until rows.length()) {
          val row = rows.optJSONObject(i) ?: continue
          val text = row.optString("text").trim()
          val time = row.optLong("timeMS", -1L)
          if (text.isNotBlank() && time >= 0L) {
            out += DanmakuEntry(
              showAtMs = time,
              text = text,
              mode = DanmakuMode.Scroll,
              color = Color.WHITE,
            )
          }
        }
        val next = page.optLong("nextMS", start)
        cursor = page.optString("nextCursor")
        if (next <= start || next >= duration) break
        start = next
      }
      out.distinctBy { it.showAtMs to it.text }.sortedBy(DanmakuEntry::showAtMs)
        .also { Log.i(Tag, "弹幕 series=$seriesId vid=$episodeId count=${it.size} windows=$windows") }
    }.onFailure { Log.w(Tag, "弹幕失败 series=$seriesId vid=$episodeId: ${it.message}") }
      .getOrDefault(emptyList())
  }

  /**
   * 一集 → 流式播放描述（远端 URL + CENC 密钥）。
   * 由 [com.kirin.mt.core.player.HongguoDataSource.Factory] 在 Media3 读取时按 Range
   * 拉取并解密，不再整集落盘。
   */
  suspend fun resolveStream(episodeId: String, maxQuality: Int = 1080): HongguoStream? =
    withContext(Dispatchers.IO) {
      if (!episodeId.matches(Regex("[0-9]{1,30}"))) return@withContext null
      runCatching {
        val ladder = content.streamLadderWithCompatibility(episodeId)
        val rendition = ladder.select(maxQuality)
          ?: throw IOException("红果没有可用清晰度")
        HongguoStream(rendition.url, rendition.key, rendition.quality, http)
      }.onFailure { Log.w(Tag, "取流失败 episode=$episodeId: ${it.message}") }.getOrNull()
    }

  private fun toSummary(series: Series, episodes: List<String>): VideoSummary {
    val line = TvboxLine(
      name = "红果短剧",
      episodes = episodes.mapIndexed { index, vid ->
        TvboxEpisode("第${index + 1}集", "$EpisodePrefix$vid")
      },
    )
    return VideoSummary(
      bvid = "$SeriesPrefix${series.id}",
      title = series.title.ifBlank { series.id },
      pic = series.cover,
      ownerName = "红果短剧",
      ownerFace = "",
      ownerMid = 0L,
      view = 0,
      danmaku = 0,
      duration = 0,
      pubdate = 0L,
      badge = series.badge.ifBlank { if (episodes.isNotEmpty()) "全${episodes.size}集" else "短剧" },
      source = SourceHongguo,
      tvboxLines = if (episodes.isEmpty()) emptyList() else listOf(line),
    )
  }

  private class SharedPreferencesDeviceStore(context: Context) : PlayerDeviceStore {
    private val prefs = context.getSharedPreferences("hongguo_app_device", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(DeviceKey, null)

    override fun save(value: String?) {
      prefs.edit().apply {
        if (value == null) remove(DeviceKey) else putString(DeviceKey, value)
      }.apply()
    }

    private companion object {
      const val DeviceKey = "device"
    }
  }

  companion object {
    /** 搜索卡片 bvid 前缀：`hongguo:<series_id>`。 */
    const val SeriesPrefix = "hongguo:"

    /** 分集 URL 前缀：`hongguo-app:<vid>`，由 [com.kirin.mt.core.player.PlaybackRepository] 识别。 */
    const val EpisodePrefix = "hongguo-app:"

    private const val Tag = "HongguoApp"
  }
}
