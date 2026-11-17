package com.kirin.mt.core.network

import android.content.Context
import android.util.Log
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.MemoryCookies
import com.hongguotv.core.PlayerDeviceStore
import com.hongguotv.core.Series
import com.kirin.mt.core.model.SourceHongguo
import com.kirin.mt.core.model.TvboxEpisode
import com.kirin.mt.core.model.TvboxLine
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.player.HongguoStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

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

  suspend fun detail(seriesId: String): VideoSummary? = withContext(Dispatchers.IO) {
    runCatching {
      val detail = content.detail(seriesId)
      toSummary(detail.series, detail.episodes)
    }.onFailure { Log.w(Tag, "详情失败 series=$seriesId: ${it.message}") }.getOrNull()
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
