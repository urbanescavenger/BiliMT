package com.kirin.mt.core.youtube

import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 已映射成 [VideoSummary] 的一页 YouTube 内容，带续页 token。 */
data class YoutubeVideoPage(
  val items: List<VideoSummary>,
  val continuation: String?,
)

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
   * 动态页"YouTube 关注"流：遍历配置的频道取各自最新视频，按发布时间倒序合并。
   * 对齐 FreeTube `grabAllSubscriptions` 的"逐频道拉取+本地合并"思路（独立实现）。
   */
  suspend fun getSubscriptionsFeed(
    channels: List<YoutubeChannel>,
    perChannel: Int = 8,
  ): List<VideoSummary> {
    if (channels.isEmpty()) {
      // 未配置频道时回退显示热门,避免动态 tab 空白(设置里可添加频道)。
      return getTrending(YoutubeConstants.TrendingTabs.values.first())
    }
    val merged = channels.flatMap { channel ->
      runCatching {
        getChannelVideos(channel.channelId).items.take(perChannel)
      }.getOrDefault(emptyList())
    }
    return merged.sortedByDescending { it.pubdate }
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
    )
  }
}
