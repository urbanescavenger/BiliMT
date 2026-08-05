package com.kirin.mt.core.youtube

import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 已映射成 [VideoSummary] 的一页 YouTube 内容，带续页 token。 */
data class YoutubeVideoPage(
  val items: List<VideoSummary>,
  val continuation: String?,
)

/** 订阅流逐频道拉取的并发上限。并发太高易触发 InnerTube 风控，4 是实测安全值。 */
const val YoutubeMaxConcurrentChannelFetches = 4

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
      val channelId = resolved?.first?.takeIf { it.isNotBlank() } ?: query
      val name = resolved?.second?.takeIf { it.isNotBlank() } ?: query
      YoutubeChannel(channelId = channelId, name = name)
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
    return client.postJson("/next", payload).let(YoutubeParsers::parseCommentPage)
  }

  /**
   * 动态页"YouTube 关注"流：遍历配置的频道取各自最新视频，按发布时间倒序合并。
   * 对齐 FreeTube `grabAllSubscriptions` 的"逐频道拉取+本地合并"思路（独立实现）。
   * 并发拉取（[YoutubeMaxConcurrentChannelFetches] 限并发，防 InnerTube 风控），
   * 关注多时总耗时≈批次×单批耗时，而非频道数×单频道耗时。
   */
  suspend fun getSubscriptionsFeed(
    channels: List<YoutubeChannel>,
    perChannel: Int = 8,
  ): List<VideoSummary> {
    if (channels.isEmpty()) {
      // 未配置频道时回退显示热门,避免动态 tab 空白(设置里可添加频道)。
      return getTrending(YoutubeConstants.TrendingTabs.values.first())
    }
    val semaphore = Semaphore(YoutubeMaxConcurrentChannelFetches)
    return coroutineScope {
      channels.map { channel ->
        async {
          semaphore.withPermit {
            runCatching {
              // lockupViewModel 不重复频道名/频道id,给空作者名与空频道id的视频补上所属频道,
              // 卡片作者行才有内容、点 UP 头像才能进本频道主页。
              getChannelVideos(channel.channelId).items.take(perChannel).map { video ->
                video.copy(
                  ownerName = if (video.ownerName.isBlank()) channel.name else video.ownerName,
                  channelId = if (video.channelId.isBlank()) channel.channelId else video.channelId,
                )
              }
            }.getOrDefault(emptyList())
          }
        }
      }.awaitAll().flatten().sortedByDescending { it.pubdate }
    }
  }

  /** 把 [YoutubeVideo] 映射成 biliMT 统一的 [VideoSummary] 卡片。 */
  fun toVideoSummary(video: YoutubeVideo): VideoSummary {
    return VideoSummary(
      bvid = video.videoId,
      title = video.title,
      pic = video.thumbnailUrl,
      ownerName = video.channelName,
      ownerFace = "",
      ownerMid = 0L,
      view = video.viewCount?.let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() } ?: 0,
      danmaku = 0,
      duration = video.durationSec ?: 0,
      pubdate = video.publishedAt ?: 0L,
      badge = video.badge,
      isLive = video.liveNow,
      source = SourceYoutube,
      channelId = video.channelId,
    )
  }
}
