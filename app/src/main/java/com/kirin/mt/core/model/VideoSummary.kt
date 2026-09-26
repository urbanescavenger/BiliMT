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
  /** PGC 季 id;>0 表示这张卡片是番剧/影视季(由番剧搜索映射而来),点击进 PGC 季详情而非播放器。 */
  val seasonId: Int = 0,
  /**
   * PGC 剧集 id;>0 表示这是番剧历史卡(仅 fromHistory business=pgc 条目填充,kid=季 id、
   * oid=该集 avid 也一并填),点击经 toPlaybackRequest 走 PGC playurl(ep_id)续播该集,
   * 不像搜索季卡(seasonId>0 且 epId=0)那样进季详情。bvid 承载 "ep{epId}" 作网格 key。
   */
  val epId: Long = 0L,
  /** 直播分区名(仅直播卡片填充,移动端卡片据此显示分区)。 */
  val liveAreaName: String = "",
  // 动态专属字段:仅 fromDynamicItem 填充,其它来源保持默认 0/空。
  // dynId 用于点赞等动态操作;aid 用于稍后再看;三个计数用于卡片展示动态本身的社交数据。
  val dynId: String = "",
  val aid: Long = 0L,
  val likeCount: Int = 0,
  val commentCount: Int = 0,
  val forwardCount: Int = 0,
  // 图文/纯文字动态专属(仅 fromDynamicItem 的 DRAW/WORD 分支填充,其余来源保持空)。
  // 图文**没有 bvid**,所以它一旦进入列表,key/去重/翻页判据都要回退到 dynId。
  val dynamicKind: String = DynamicKindNone,
  val dynamicText: String = "",
  val dynamicImages: List<DynamicImage> = emptyList(),
  /** 正文是否被服务端截断(opus.summary.has_more),决定卡片要不要给「展开」。 */
  val dynamicTextHasMore: Boolean = false,
  /** 内容来源：[SourceBili]（默认）/ [SourceYoutube] / [SourceIptv] / [SourceTvbox] / [SourceHongguo]。YouTube 卡片 bvid 字段承载 videoId。 */
  val source: String = SourceBili,
  /** YouTube 频道 id（UC 开头）。仅 [SourceYoutube] 卡片填充，用于进 UP 主页；B 站卡片为空串。 */
  val channelId: String = "",
  /** IPTV 频道镜像源 URL 列表（仅 [SourceIptv]/[SourceTvbox] 卡片填充）。同名频道合并成一个直播间，播放器里可切换源；TVBox 卡为跨站多线路。 */
  val iptvUrls: List<String> = emptyList(),
  /** TVBox(影视库)线路表（仅 [SourceTvbox] 卡片填充）：每线路=一个采集站的完整分集列表。选集在线路内换 index。 */
  val tvboxLines: List<TvboxLine> = emptyList(),
)

/** 动态图文单张图片。宽高来自接口:`major.draw.items` 是字符串、`major.opus.pics` 是数字,解析层已统一成 Int。 */
@Serializable
data class DynamicImage(
  val url: String,
  val width: Int = 0,
  val height: Int = 0,
)

/** [VideoSummary.dynamicKind] 取值:非图文动态 / 图文 / 纯文字。 */
const val DynamicKindNone = ""
const val DynamicKindDraw = "draw"
const val DynamicKindWord = "word"

/**
 * 列表 key / 去重用的稳定标识。图文动态**没有 bvid**,必须回退到 dynId,否则整列 key 都为空串
 * (Compose 抛 key 冲突、`distinctBy` 会把所有图文当成同一条)。
 */
val VideoSummary.feedKey: String get() = dynId.ifBlank { bvid }

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
/**
 * 红果短剧源(P11-83):hongguoduanju.com 网页端解析,零配置零签名。
 * 搜索一步直出剧集列表(含 vid_list),播放=播放页 SSR 内嵌 main_url 明文 MP4。
 * 播放复用 TVBox 线路心智(单线路 TvboxLine,分集 URL=播放页地址,懒解析取直链)。
 */
const val SourceHongguo = "hongguo"
