package com.kirin.mt.core.youtube

import android.util.Log
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 已映射成 [VideoSummary] 的一页 YouTube 内容，带续页 token。 */
data class YoutubeVideoPage(
  val items: List<VideoSummary>,
  val continuation: String?,
)

/** 订阅流逐频道拉取的并发上限。并发太高易触发 InnerTube 风控，4 是实测安全值。 */
const val YoutubeMaxConcurrentChannelFetches = 4

/** RSS 订阅流并发上限。RSS 是轻量 GET、无 InnerTube 风控，可放宽到 8。 */
const val YoutubeMaxConcurrentRssFetches = 8

/**
 * YouTube 内容门面，供 [com.kirin.mt.core.network.VideoRepository] 转发。
 * 只暴露"搜索 / 热门 / 频道视频"元数据接口；播放流解析（InnerTube /player + PO token）
 * 属 Phase 2，另行实现。
 */
class YoutubeRepository(
  private val client: InnerTubeClient,
) {

  /** 搜索，返回原始模型。@param params 排序/筛选参数串，见 [YoutubeSearchParams]。 */
  suspend fun search(
    query: String,
    params: String = YoutubeSearchParams.Relevance,
    continuation: String? = null,
  ): YoutubeFeedPage {
    val payload = buildJsonObject {
      if (continuation != null) {
        put("continuation", continuation)
      } else {
        put("query", query)
        if (params.isNotBlank()) put("params", params)
      }
    }
    return client.postJson("/search", payload).let(YoutubeParsers::parseFeedPage)
  }

  /** 热门(趋势)，返回映射后的卡片。 */
  suspend fun getTrending(tab: YoutubeConstants.TrendingTab): List<VideoSummary> {
    val payload = buildJsonObject {
      put("browseId", tab.browseId)
      tab.params?.let { put("params", it) }
    }
    val feed = client.postJson("/browse", payload).let(YoutubeParsers::parseFeedPage)
    return feed.items.map(::toVideoSummary)
  }

  /**
   * 把用户输入解析成可持久化的 [YoutubeChannel]。接受 `UC...` 频道 ID、`@handle`、频道名或完整 URL。
   *
   * 实测关键：`/browse` 只接受 `UC...` 频道 ID；`@handle` 做 browseId 会 400。
   * 所以：UC ID 走 `/browse`；handle / 频道名走 `/search` 收集 `channelRenderer`。
   * 解析失败（非频道页/无匹配频道/网络异常）抛 [YoutubeApiException]。
   */
  suspend fun resolveChannel(input: String): YoutubeChannel {
    val query = normalizeChannelInput(input)
    if (query.isBlank()) {
      throw YoutubeApiException(statusCode = 0, responseBody = "", message = "empty channel input")
    }
    return if (query.matches(ChannelIdRegex)) {
      // UC... 频道 ID：/browse 直接拉频道页。
      val payload = buildJsonObject { put("browseId", query) }
      val root = client.postJson("/browse", payload)
      val resolved = YoutubeParsers.parseChannelInfo(root)
      val channelId = resolved?.channelId?.takeIf { it.isNotBlank() } ?: query
      val name = resolved?.name?.takeIf { it.isNotBlank() } ?: query
      val avatar = resolved?.avatarUrl.orEmpty()
      YoutubeChannel(channelId = channelId, name = name, avatar = avatar)
    } else {
      // handle / 频道名：/search 找 channelRenderer。
      val payload = buildJsonObject { put("query", query) }
      val root = client.postJson("/search", payload)
      val candidates = YoutubeParsers.parseChannelCandidates(root)
      val match = pickChannelCandidate(candidates, query)
        ?: throw YoutubeApiException(statusCode = 0, responseBody = "", message = "channel not found: $query")
      YoutubeChannel(channelId = match.first, name = match.second.ifBlank { query })
    }
  }

  /**
   * 从搜索候选里挑最佳匹配：优先名称精确匹配(忽略大小写)，
   * 其次第一个含 query 的候选，最后退回到首个候选。实测对 `@handle` 搜索首条即目标频道。
   */
  private fun pickChannelCandidate(
    candidates: List<Pair<String, String>>,
    query: String,
  ): Pair<String, String>? {
    if (candidates.isEmpty()) return null
    val lower = query.lowercase()
    candidates.firstOrNull { (_, name) -> name.equals(query, ignoreCase = true) }?.let { return it }
    candidates.firstOrNull { (_, name) -> name.lowercase().contains(lower) }?.let { return it }
    return candidates.first()
  }

  /** 归一化频道输入：去掉 URL 前缀(/channel/ / @handle)、尾部斜杠、头部 @。 */
  private fun normalizeChannelInput(input: String): String {
    var value = input.trim()
    for (prefix in listOf(
      "https://www.youtube.com/channel/", "http://www.youtube.com/channel/", "www.youtube.com/channel/", "youtube.com/channel/",
      "https://www.youtube.com/", "http://www.youtube.com/", "https://youtube.com/", "www.youtube.com/", "youtube.com/",
    )) {
      if (value.startsWith(prefix)) {
        value = value.removePrefix(prefix)
        break
      }
    }
    value = value.trimEnd('/')
    return value.removePrefix("@")
  }

  private companion object {
    val ChannelIdRegex = Regex("""UC[0-9A-Za-z_-]{22}""")

    /** 分批并发拉取：每批频道数（对齐 LibreTube CHANNEL_CHUNK_SIZE=5）。 */
    const val ChunkSize = 5

    /** 防节流：每累计这么多个频道暂停一次（对齐 LibreTube CHANNEL_BATCH_SIZE=50）。 */
    const val BatchSize = 50

    /** 防节流随机暂停范围(ms)（对齐 LibreTube CHANNEL_BATCH_DELAY=500..1500）。 */
    val BatchDelayMs = 500L..1500L
  }

  /** 频道"视频"tab 的最新视频，返回映射后的卡片 + 续页 token。 */
  suspend fun getChannelVideos(
    channelId: String,
    continuation: String? = null,
  ): YoutubeVideoPage {
    val payload = buildJsonObject {
      if (continuation != null) {
        put("continuation", continuation)
      } else {
        put("browseId", channelId)
        put("params", YoutubeConstants.ChannelVideosParams)
      }
    }
    val feed = client.postJson("/browse", payload).let(YoutubeParsers::parseFeedPage)
    return YoutubeVideoPage(
      items = feed.items.map(::toVideoSummary),
      continuation = feed.continuation,
    )
  }

  /**
   * 视频详情（简介 Tab）：POST /player 取 videoDetails（title/author/shortDescription/viewCount）
   * + microformat（publishDate）。受限视频可能无 videoDetails，此时返回 null（UI 显示重试）。
   */
  suspend fun getVideoDetail(videoId: String): YoutubeVideoDetail? {
    if (videoId.isBlank()) return null
    return runCatching {
      val payload = buildJsonObject {
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
      }
      client.postJson("/player", payload).let(YoutubeParsers::parseVideoDetail)
    }.getOrNull()
  }

  /**
   * 评论列表（/next）：首屏 payload 只带 videoId；续页带 continuation token。
   * 返回一页 [YoutubeComment] + 续页 token（null 表示到底）。
   */
  suspend fun getComments(
    videoId: String,
    continuation: String? = null,
  ): YoutubeCommentPage {
    val payload = buildJsonObject {
      put("videoId", videoId)
      if (!continuation.isNullOrBlank()) put("continuation", continuation)
    }
    Log.d("YoutubeComment", "getComments videoId=$videoId continuation=${continuation?.take(16) ?: "null"}")
    val page = client.postJson("/next", payload).let(YoutubeParsers::parseCommentPage)
    Log.d(
      "YoutubeComment",
      "getComments videoId=$videoId items=${page.items.size} " +
        "continuation=${page.continuation?.take(16) ?: "null"}",
    )
    return page
  }

  /**
   * 相关视频（/next）：与评论同端点，取 secondaryResults 里的 compactVideoRenderer（对齐 LibreTube）。
   * 首屏 payload 只带 videoId；续页带 continuation token。返回一页 [YoutubeVideo] + 续页 token。
   */
  suspend fun getRelatedVideos(
    videoId: String,
    continuation: String? = null,
  ): YoutubeFeedPage {
    val payload = buildJsonObject {
      put("videoId", videoId)
      if (!continuation.isNullOrBlank()) put("continuation", continuation)
    }
    return client.postJson("/next", payload).let(YoutubeParsers::parseRelatedVideos)
  }

  /**
   * 动态页"YouTube 关注"流：遍历配置的频道取各自最新视频，按发布时间倒序合并。
   * 对齐 LibreTube `LocalFeedRepository.refreshFeed` 的**分批增量**模型（独立实现）。
   *
   * **RSS + InnerTube 并行拉取后按 videoId 合并**：每频道并发发轻量 RSS GET
   * （[YoutubeMaxConcurrentRssFetches] 限并发，无 InnerTube 风控、不计配额）与 InnerTube
   * `/browse`（[YoutubeMaxConcurrentChannelFetches] 限并发防风控）。RSS 提供精确 `publishedAt`，
   * InnerTube 补全 `duration`/`liveNow`/`isUpcoming`/`badge` 及 RSS 未覆盖的 Shorts/直播/首映。
   * 任何一路失败都降级用另一路，不影响整体。
   *
   * **分批增量(几百频道可扩展)**：频道按 [ChunkSize] 分批并发拉取，每批就绪立即回调
   * [onChunkReady]，调用方可"拉到一批显示一批"而非等全部；每累计 [BatchSize] 个频道
   * `delay` 一次防节流（对齐 LibreTube `CHANNEL_BATCH_DELAY`）。单频道失败只丢自身，
   * **无需外层全局超时**（这是几百频道下旧一次性 `awaitAll` + 外层预算必超时的根因）。
   * 函数仍返回全量 List（按 pubdate 倒序），供调用方一次性缓存写。
   *
   * @param onChunkReady 每批就绪回调（增量 merge / 增量写缓存的入口）。
   */
  suspend fun getSubscriptionsFeed(
    channels: List<YoutubeChannel>,
    perChannel: Int = 15,
    onChannelAvatarResolved: suspend (YoutubeChannel) -> Unit = {},
    onChunkReady: (List<VideoSummary>) -> Unit = {},
  ): List<VideoSummary> {
    if (channels.isEmpty()) {
      // 未配置频道时回退显示热门,避免动态 tab 空白(设置里可添加频道)。
      return getTrending(YoutubeConstants.TrendingTabs.values.first())
    }
    val rssSemaphore = Semaphore(YoutubeMaxConcurrentRssFetches)
    val innerTubeSemaphore = Semaphore(YoutubeMaxConcurrentChannelFetches)
    val accumulator = mutableListOf<VideoSummary>()
    var processed = 0
    for (batch in channels.chunked(ChunkSize)) {
      val batchResult = coroutineScope {
        batch.map { channel ->
          async {
            // 旧频道(无头像)懒解析一次并回写 store,供本次填充与后续复用,避免每次刷新重复 /browse。
            var channelAvatar = channel.avatar
            if (channelAvatar.isBlank()) {
              val resolvedAvatar = innerTubeSemaphore.withPermit {
                runCatching {
                  val payload = buildJsonObject { put("browseId", channel.channelId) }
                  YoutubeParsers.parseChannelInfo(client.postJson("/browse", payload))?.avatarUrl.orEmpty()
                }.getOrDefault("")
              }
              if (resolvedAvatar.isNotBlank()) {
                channelAvatar = resolvedAvatar
                onChannelAvatarResolved(channel.copy(avatar = resolvedAvatar))
              }
            }
            // RSS 与 InnerTube 并行拉取。RSS 提供精确发布时间,InnerTube 补全 duration/live 等字段。
            val rssDeferred = async {
              rssSemaphore.withPermit {
                runCatching { getChannelRss(channel.channelId) }
                  .onFailure { Log.w("YoutubeFeed", "RSS failed for ${channel.channelId}", it) }
                  .getOrDefault(emptyList())
              }
            }
            val innerTubeDeferred = async {
              innerTubeSemaphore.withPermit {
                runCatching { getChannelVideosRaw(channel.channelId) }
                  .onFailure { Log.w("YoutubeFeed", "InnerTube failed for ${channel.channelId}", it) }
                  .getOrDefault(emptyList())
              }
            }
            val rssVideos = rssDeferred.await()
            val innerTubeVideos = innerTubeDeferred.await()
            val merged = mergeByVideoId(rssVideos, innerTubeVideos)
            Log.d(
              "YoutubeFeed",
              "${channel.channelId}: RSS ${rssVideos.size} + InnerTube ${innerTubeVideos.size} → merged ${merged.size}",
            )
            val resolved: List<VideoSummary> = merged.take(perChannel).map(::toVideoSummary)
            // lockupViewModel 不重复频道名/频道id,给空作者名与空频道id的视频补上所属频道,
            // 卡片作者行才有内容、点 UP 头像才能进本频道主页;头像同理补上所属频道头像。
            resolved.map { video ->
              video.copy(
                ownerName = if (video.ownerName.isBlank()) channel.name else video.ownerName,
                channelId = if (video.channelId.isBlank()) channel.channelId else video.channelId,
                ownerFace = if (video.ownerFace.isBlank()) channelAvatar else video.ownerFace,
              )
            }
          }
        }.awaitAll().flatten()
      }
      // 每批就绪即回调(增量 merge / 增量写缓存),不等待全部频道。
      onChunkReady(batchResult)
      accumulator += batchResult
      processed += batch.size
      // 防节流(对齐 LibreTube CHANNEL_BATCH_DELAY):每累计 BatchSize 频道暂停随机 500-1500ms。
      if (processed % BatchSize == 0) {
        delay(Random.nextLong(BatchDelayMs.first, BatchDelayMs.last + 1))
      }
    }
    return accumulator.sortedByDescending { it.pubdate }
  }

  /**
   * 拉取单频道"视频"tab 的原始 InnerTube 视频列表(未映射成卡片),供订阅流与 RSS 合并。
   * 失败抛异常,由调用方降级。
   */
  private suspend fun getChannelVideosRaw(channelId: String): List<YoutubeVideo> {
    val payload = buildJsonObject {
      put("browseId", channelId)
      put("params", YoutubeConstants.ChannelVideosParams)
    }
    return client.postJson("/browse", payload).let(YoutubeParsers::parseFeedPage).items
  }

  /**
   * 按 videoId 合并 RSS 与 InnerTube 两路视频:以 RSS 为基底,用 InnerTube 补全 RSS 缺失的字段。
   * RSS 提供精确 ISO 8601 发布时间,InnerTube 相对时间反推是近似值 → 时间优先 RSS;
   * RSS 不提供 duration/live/upcoming/badge/头像 → 这些优先 InnerTube;viewCount 用更准确的 InnerTube。
   */
  private fun mergeByVideoId(
    rssVideos: List<YoutubeVideo>,
    innerTubeVideos: List<YoutubeVideo>,
  ): List<YoutubeVideo> {
    val byId = LinkedHashMap<String, YoutubeVideo>()
    for (v in rssVideos) byId[v.videoId] = v
    for (it in innerTubeVideos) {
      val existing = byId[it.videoId]
      byId[it.videoId] = if (existing == null) {
        // 仅 InnerTube 有(Shorts/直播/首映/RSS 未覆盖),直接保留。
        it
      } else {
        existing.copy(
          publishedAt = existing.publishedAt ?: it.publishedAt,
          durationSec = it.durationSec ?: existing.durationSec,
          liveNow = existing.liveNow || it.liveNow,
          isUpcoming = existing.isUpcoming || it.isUpcoming,
          badge = existing.badge.ifBlank { it.badge },
          viewCount = it.viewCount ?: existing.viewCount,
          channelAvatarUrl = existing.channelAvatarUrl.ifBlank { it.channelAvatarUrl },
        )
      }
    }
    return byId.values.toList()
  }

  /** 拉取单频道 RSS 订阅流并解析成 [YoutubeVideo]。失败抛异常，由调用方回退。 */
  private suspend fun getChannelRss(channelId: String): List<YoutubeVideo> {
    val xml = client.getText("${YoutubeConstants.RssFeedBase}?channel_id=$channelId")
    return YoutubeRssParser.parse(xml)
  }

  /** 把 [YoutubeVideo] 映射成 biliMT 统一的 [VideoSummary] 卡片。 */
  fun toVideoSummary(video: YoutubeVideo): VideoSummary {
    return VideoSummary(
      bvid = video.videoId,
      title = video.title,
      pic = video.thumbnailUrl,
      ownerName = video.channelName,
      ownerFace = video.channelAvatarUrl,
      ownerMid = 0L,
      view = video.viewCount?.let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() } ?: 0,
      danmaku = 0,
      duration = video.durationSec ?: 0,
      pubdate = video.publishedAt ?: (System.currentTimeMillis() / 1000L),
      badge = video.badge,
      isLive = video.liveNow,
      source = SourceYoutube,
      channelId = video.channelId,
    )
  }
}
