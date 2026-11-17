package com.kirin.mt.core.network

import android.content.Context
import android.util.Log
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.ContentType
import com.hongguotv.core.MemoryCookies
import com.hongguotv.core.PlayerDeviceStore
import com.hongguotv.core.Rendition
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.Series
import com.hongguotv.core.StreamInfo
import com.kirin.mt.core.model.SourceHongguo
import com.kirin.mt.core.model.TvboxEpisode
import com.kirin.mt.core.model.TvboxLine
import com.kirin.mt.core.model.VideoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 红果 App 端数据源（GPL-3.0-only `:hongguo-core` 的应用侧适配）。
 *
 * 只负责三件事：
 * 1. 搜索：官方 App 搜索接口，拿剧集元数据；卡片不带分集，点击后再拉详情。
 * 2. 详情：网页 SSR 的 `vid_list` 是完整分集（网页只放开前 3 集播放，但分集 ID 全量都在）。
 * 3. 播放：`player/v1` 取流后按需下载并用 CENC 密钥解成明文 MP4，落到应用缓存，
 *    再交给现有 ProgressiveMediaSource 播放（避免改动两个播放器的数据源管线）。
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
  private val downloadMutex = Mutex()

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
    val web1 = runCatching { content.searchAll(keyword, page = 1, type = ContentType.SHORT) }
      .onFailure { Log.w(Tag, "网页搜索失败 kw=$keyword: ${it.message}") }
      .getOrNull()
    val web2 = if (web1?.hasMore == true) {
      runCatching { content.searchAll(keyword, page = 2, type = ContentType.SHORT) }
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
   * 一集 → 本地明文 MP4 的 `file://` URL。
   * 已缓存则直接复用；未缓存时先走 v5 取流，再按 CENC 密钥边读边解写到 `.part`，
   * 完整写完才 rename，避免半成品被播放器读到。
   */
  suspend fun resolveEpisode(episodeId: String, maxQuality: Int = 1080): String? =
    withContext(Dispatchers.IO) {
      if (!episodeId.matches(Regex("[0-9]{1,30}"))) return@withContext null
      val target = File(cacheDir(), "$episodeId.mp4")
      if (target.length() > 0L) return@withContext fileUrl(target)
      downloadMutex.withLock {
        if (target.length() > 0L) return@withLock fileUrl(target)
        runCatching {
          val ladder = content.streamLadderWithCompatibility(episodeId)
          val rendition = ladder.select(maxQuality)
            ?: throw IOException("红果没有可用清晰度")
          download(rendition, target)
          fileUrl(target)
        }.onFailure { Log.w(Tag, "取流失败 episode=$episodeId: ${it.message}") }.getOrNull()
      }
    }

  private fun download(rendition: Rendition, target: File) {
    val video = RemoteVideo(
      http,
      StreamInfo(rendition.url, rendition.key, "${rendition.quality}P"),
    ).prepare()
    video.use {
      val part = File(target.parentFile, target.name + ".part")
      FileOutputStream(part).use { out ->
        var offset = 0L
        while (offset < it.total) {
          val want = minOf(64 * 1024L, it.total - offset).toInt()
          val bytes = it.read(offset, want)
          if (bytes.isEmpty()) break
          out.write(bytes)
          offset += bytes.size
        }
        out.fd.sync()
      }
      if (part.length() != it.total) {
        part.delete()
        throw IOException("红果视频不完整 ${part.length()}/${it.total}")
      }
      if (target.exists()) target.delete()
      if (!part.renameTo(target)) {
        part.delete()
        throw IOException("红果视频落盘失败")
      }
    }
  }

  private fun cacheDir(): File = File(context.cacheDir, "hongguo").apply { mkdirs() }

  private fun fileUrl(file: File): String = "file://${file.absolutePath}"

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
