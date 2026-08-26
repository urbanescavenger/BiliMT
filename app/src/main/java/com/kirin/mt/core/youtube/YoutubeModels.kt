package com.kirin.mt.core.youtube

import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.UserSummary
import com.kirin.mt.core.model.VideoSummary

/**
 * YouTube 内容模型。对齐 FreeTube `parseLocalListVideo` 输出的字段结构（协议层面的
 * 形状），供 [YoutubeParsers] 填充、[com.kirin.mt.core.network.VideoRepository] 映射成
 * biliMT 统一的 [com.kirin.mt.core.model.VideoSummary]。
 */
data class YoutubeVideo(
  /** 视频 id（11 位，如 "dQw4w9WgXcQ"）。 */
  val videoId: String,
  val title: String,
  /** 频道名。 */
  val channelName: String,
  /** 频道 id（UC 开头）。 */
  val channelId: String,
  /** 频道头像 URL（yt3.ggpht.com）；无则空串。 */
  val channelAvatarUrl: String = "",
  /** 缩略图 URL（i.ytimg.com）。 */
  val thumbnailUrl: String,
  /** 观看数；未知为 null。 */
  val viewCount: Long?,
  /** 时长秒；直播/未知为 null。 */
  val durationSec: Int?,
  /** 发布时间（epoch 秒）；未知为 null。 */
  val publishedAt: Long?,
  val liveNow: Boolean,
  val isUpcoming: Boolean,
  /** 角标文案（如 "LIVE"/"New"/"4K"）；无则空串。 */
  val badge: String,
)

/** 一页 YouTube 内容（搜索/热门/频道），带续页 token。 */
data class YoutubeFeedPage(
  val items: List<YoutubeVideo>,
  /** 续页 token；null 表示没有下一页。 */
  val continuation: String?,
)

/** 一页首页订阅流（首屏或续页）。UI 负责跨页累积+去重+按 pubdate 排序。 */
data class YoutubeSubscriptionsPage(
  /** 本页新拉到的视频（每频道已 cap 到 perChannel，频道内已合并 RSS+InnerTube）。 */
  val videos: List<VideoSummary>,
  /** channelId -> 该频道下一 continuation token；null = 该频道到底。 */
  val perChannelContinuation: Map<String, String?>,
) {
  /** 所有频道都到底（无任一续页 token）即为全部加载完。 */
  val endReached: Boolean get() = perChannelContinuation.values.all { it == null }
}

/** YouTube 视频详情（简介 Tab）。由 /player 响应 videoDetails + microformat 填充。 */
data class YoutubeVideoDetail(
  val videoId: String,
  val title: String,
  /** 简介（shortDescription）。 */
  val description: String,
  /** 频道名。 */
  val channelName: String,
  /** 频道 id（UC 开头）。来自 videoDetails.channelId；无则空串。 */
  val channelId: String = "",
  /** 频道头像 URL；无则空串。 */
  val channelAvatarUrl: String,
  /** 观看数；未知为 null。 */
  val viewCount: Long?,
  /** 发布时间（epoch 秒）；未知为 null。 */
  val publishedAt: Long?,
  /** 点赞数（/next videoPrimaryInfoRenderer.videoActions 工具栏解析）；未知为 null。 */
  val likeCount: Long? = null,
)

/** 一条 YouTube 评论。字段对齐 LibreTube `Comment`（NewPipe CommentsInfoItem）。 */
data class YoutubeComment(
  val commentId: String,
  val authorName: String,
  val authorAvatarUrl: String,
  val content: String,
  val likeCount: Long?,
  /** 发布时间（epoch 秒，相对时间反推）；未知为 null。 */
  val publishedAt: Long?,
  /** 作者已认证（authorCommentBadge 存在）。 */
  val verified: Boolean = false,
  /** 置顶评论（pinnedCommentBadge 存在）。 */
  val pinned: Boolean = false,
  /** 作者点赞（actionButtons.commentActionButtonsRenderer.creatorHeart 存在）。 */
  val hearted: Boolean = false,
  /** 回复数（replyCount）。 */
  val replyCount: Int = 0,
  /** 楼中楼续页 token（replies.commentRepliesRenderer 内 continuation）；null 表示无回复。 */
  val repliesPage: String? = null,
  /** 作者是频道主（authorIsChannelOwner）。 */
  val channelOwner: Boolean = false,
  /** 作者回复过（replies.commentRepliesRenderer.viewRepliesCreatorThumbnail 存在）。 */
  val creatorReplied: Boolean = false,
)

/** 一页 YouTube 评论（/next 响应），带续页 token。 */
data class YoutubeCommentPage(
  val items: List<YoutubeComment>,
  /** 续页 token；null 表示没有下一页。 */
  val continuation: String?,
)

/** 搜索排序参数（InnerTube search params 串，对齐 FreeTube convertSearchFilters）。 */
object YoutubeSearchParams {
  /** 默认综合排序（无 params，youtubei.js 默认）。 */
  const val Relevance = ""

  /** 上传时间：本小时。 */
  const val Hour = "EgQQARgB"

  /** 上传时间：今天。 */
  const val Today = "EgQQARgC"

  /** 上传时间：本周。 */
  const val ThisWeek = "EgQQARgD"

  /** 上传时间：本月。 */
  const val ThisMonth = "EgQQARgE"

  /** 上传时间：今年。 */
  const val ThisYear = "EgQQARgF"

  /** 排序：观看次数。 */
  const val ViewCount = "CAMSAhAB"

  /** 排序：上传日期。 */
  const val UploadDate = "CAISAhoA"

  /** 排序：评分。 */
  const val Rating = "CAMSBBABGAE"

  /** 类型：视频。 */
  const val TypeVideo = "EgIQAQ%3D%3D"

  /** 类型：频道。 */
  const val TypeChannel = "EgIQAg%3D%3D"
}

/** 一条 YouTube 频道搜索结果（channelRenderer）。 */
data class YoutubeChannelSearchResult(
  /** 频道 id（UC 开头）。 */
  val channelId: String,
  val name: String,
  /** 频道头像 URL（yt3.ggpht.com）；无则空串。 */
  val avatarUrl: String = "",
  /** 订阅数；未知为 null。 */
  val subscriberCount: Long? = null,
  /** 视频数；未知为 null。 */
  val videoCount: Long? = null,
  /** 频道简介；无则空串。 */
  val description: String = "",
)

/** 一页 YouTube 频道搜索结果，带续页 token。 */
data class YoutubeChannelSearchPage(
  val items: List<YoutubeChannelSearchResult>,
  /** 续页 token；null 表示没有下一页。 */
  val continuation: String?,
)

/** 把 YouTube 频道搜索结果映射成统一的 [UserSummary]（mid 恒 0、level 恒 0，source=YouTube）。 */
fun YoutubeChannelSearchResult.toUserSummary(): UserSummary {
  return UserSummary(
    mid = 0L,
    channelId = channelId,
    name = name,
    face = avatarUrl,
    sign = description,
    fans = subscriberCount?.toInt() ?: 0,
    videos = videoCount?.toInt() ?: 0,
    level = 0,
    officialVerify = "",
    source = SourceYoutube,
  )
}
