package com.kirin.mt.core.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoSummary(
  val bvid: String,
  val title: String,
  val pic: String,
  val ownerName: String,
  val ownerFace: String,
  val ownerMid: Long,
  val view: Int,
  val danmaku: Int,
  val duration: Int,
  val pubdate: Long,
  val badge: String,
  val progress: Int = ProgressUnset,
  val viewAt: Long = 0L,
  val cid: Long = 0L,
  val historyPage: Int = 0,
  val historyPart: String = "",
  val historyVideos: Int = 0,
  val isLive: Boolean = false,
  /** 直播间 id;>0 表示这张卡片是直播间(由 LiveRoom 映射而来),点击走直播播放。 */
  val liveRoomId: Long = 0L,
  /** 直播分区名(仅直播卡片填充,移动端卡片据此显示分区)。 */
  val liveAreaName: String = "",
  // 动态专属字段:仅 fromDynamicItem 填充,其它来源保持默认 0/空。
  // dynId 用于点赞等动态操作;aid 用于稍后再看;三个计数用于卡片展示动态本身的社交数据。
  val dynId: String = "",
  val aid: Long = 0L,
  val likeCount: Int = 0,
  val commentCount: Int = 0,
  val forwardCount: Int = 0,
  /** 内容来源：[SourceBili]（默认）/ [SourceYoutube] / [SourceIptv] / [SourceTvbox]。YouTube 卡片 bvid 字段承载 videoId。 */
  val source: String = SourceBili,
  /** YouTube 频道 id（UC 开头）。仅 [SourceYoutube] 卡片填充，用于进 UP 主页；B 站卡片为空串。 */
  val channelId: String = "",
  /** IPTV 频道镜像源 URL 列表（仅 [SourceIptv]/[SourceTvbox] 卡片填充）。同名频道合并成一个直播间，播放器里可切换源；TVBox 卡为跨站多线路。 */
  val iptvUrls: List<String> = emptyList(),
  /** TVBox(影视库)线路表（仅 [SourceTvbox] 卡片填充）：每线路=一个采集站的完整分集列表。选集在线路内换 index。 */
  val tvboxLines: List<TvboxLine> = emptyList(),
)

/** TVBox(影视库)线路：一个采集站 + 其分集列表(线路=清晰度面板档位,选集=线路内换 index)。 */
@Serializable
data class TvboxLine(
  /** 站点名(线路面板显示,如「极速资源」)。 */
  val name: String,
  /** 分集列表(播放页标题 + 可解析 URL;share/play 页 URL 播放时经 TvboxRepository.resolveLineUrl 解析)。 */
  val episodes: List<TvboxEpisode> = emptyList(),
)

/** TVBox(影视库)一集:播放页标题 + 可解析 URL。 */
@Serializable
data class TvboxEpisode(
  val title: String,
  val url: String,
)

const val ProgressUnset = -1

/** 内容来源常量。 */
const val SourceBili = "bili"
const val SourceYoutube = "youtube"
const val SourceIptv = "iptv"
/** TVBox(影视库)源:P11-77 spike,内置 MacCMS 采集站聚合搜索,直链 m3u8 复用 IPTV 播放路径。 */
const val SourceTvbox = "tvbox"
