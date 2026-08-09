package com.kirin.mt.core.player

import com.kirin.mt.core.model.SourceBili
import com.kirin.mt.core.model.SourceYoutube

data class PlaybackRequest(
  val bvid: String,
  val cid: Long,
  val title: String,
  val startPositionMs: Long = 0L,
  val aid: Long = 0L,
  val ownerName: String = "",
  val ownerFace: String = "",
  val ownerMid: Long = 0L,
  /** 视频封面 URL,用于后台播放 MediaStyle 通知封面。 */
  val coverUrl: String = "",
  val viewCount: Int = 0,
  val danmakuCount: Int = 0,
  val pubdate: Long = 0L,
  val preferredQualityId: Int? = null,
  val forceStartPosition: Boolean = false,
  val historyPage: Int = 0,
  val advanceToNextHistoryEpisode: Boolean = false,
  /** PGC 剧集 id；>0 表示这是 PGC 播放请求，走 /pgc/player/web/playurl。 */
  val epId: Long = 0L,
  /** PGC 季 id；>0 时 getVideoMetadata 走 /pgc/view/web/season 取分集列表。 */
  val seasonId: Long = 0L,
  /** PGC 季副类型（season.type：1番剧/2电影/3纪录/4国创/5电视剧/7综艺），heartbeat type=4 时作 sub_type。 */
  val subType: Int = 0,
  /** 直播间 id;>0 表示这是直播播放请求,走 xlive/web-room/v2/index/getRoomPlayInfo,跳过 DASH playurl。 */
  val liveRoomId: Long = 0L,
  /** 内容来源：[SourceBili]（默认）/ [SourceYoutube]。YouTube 请求 bvid 字段承载 videoId。 */
  val source: String = SourceBili,
) {
  val isPgc: Boolean
    get() = epId > 0L || seasonId > 0L

  val isLive: Boolean
    get() = liveRoomId > 0L

  /** 这是 YouTube 播放请求：走 InnerTube /player 解析 progressive 直链，跳过 B 站 DASH playurl。 */
  val isYoutube: Boolean
    get() = source == SourceYoutube
}

/** 直播清晰度(qn + 描述,如 10000/原画)。 */
data class LiveQuality(
  val qn: Int,
  val description: String,
)

/**
 * 直播播放信息:一条可播流 URL + 可选清晰度列表。
 * 直播不走 DASH/合成 MPD,直接把 [streamUrl] 喂给 HlsMediaSource([isHls]=true)或
 * ProgressiveMediaSource([isHls]=false,FLV)。
 */
data class LivePlayInfo(
  val roomId: Long,
  val streamUrl: String,
  /** true=HLS(m3u8)用 HlsMediaSource;false=FLV 用 ProgressiveMediaSource。 */
  val isHls: Boolean,
  /** 当前实际下发清晰度(服务端可能因不可用而降级)。 */
  val currentQn: Int,
  /** 该直播间可选清晰度(已按 accept_qn 过滤)。 */
  val qualities: List<LiveQuality>,
  /** 播放流所需的 HTTP 头(Cookie/Referer/UA)。 */
  val headers: BiliPlaybackHeaders,
)

data class PlaybackInfo(
  val bvid: String,
  val cid: Long,
  val title: String,
  val durationMs: Long,
  val qualities: List<PlaybackQuality>,
  val selectedQuality: PlaybackQuality,
  val videoTracks: List<PlaybackTrack>,
  val audioTracks: List<PlaybackTrack>,
  val headers: BiliPlaybackHeaders,
)

data class PlaybackQuality(
  val id: Int,
  val description: String,
)

data class PlaybackVideoMetadata(
  val aid: Long,
  val bvid: String,
  val cid: Long,
  val title: String,
  val ownerName: String,
  val ownerFace: String,
  val ownerMid: Long,
  val viewCount: Int,
  val danmakuCount: Int,
  val pubdate: Long,
  val pages: List<PlaybackEpisode>,
  /** 视频简介(B 站 view 接口 data.desc / PGC season.desc),移动端播放页简介 Tab 展示。 */
  val desc: String = "",
  /** 互动计数(B 站 view 接口 data.stat)。PGC 无,默认 0。 */
  val likeCount: Int = 0,
  val coinCount: Int = 0,
  val favoriteCount: Int = 0,
  val shareCount: Int = 0,
  /** 当前用户对该视频的互动状态(B 站 view 接口 data.req_user,仅登录态返回)。 */
  val liked: Boolean = false,
  val coined: Boolean = false,
  val faved: Boolean = false,
)

data class PlaybackEpisode(
  val cid: Long,
  val page: Int,
  val title: String,
  val durationSeconds: Int,
  /** PGC 剧集 ep_id；UGC 多 P 为 0。 */
  val epId: Long = 0L,
)

data class PlaybackTrack(
  val id: Int,
  val baseUrl: String,
  val backupUrls: List<String>,
  val bandwidth: Int,
  val codecs: String,
  val width: Int,
  val height: Int,
  val mimeType: String,
  /**
   * DASH SegmentBase 信息（B 站 playurl 有；YouTube progressive 直链为 null）。
   * null 时播放器走 progressive [MergingMediaSource] 分支，不经 MPD 合成。
   */
  val segmentBase: PlaybackSegmentBase? = null,
  /**
   * alpha.59(Phase 2 DASH):SABR 合成 DASH 轨标记。SABR 无 indexRange/initRange(服务端按段发),
   * 播放器须走 DASH 分支 + MPD 用 SegmentTemplate(每段一个 sabr:// seg/init URL),而非 progressive
   * MergingMediaSource。segmentBase 仍为 null(无 indexRange),故 [isProgressive] 为 true——播放器分支
   * 判断须额外排除本标记(见 PlayerScreen/MobilePlayerScreen 的 `&& !isSabrDash`)。
   */
  val isSabrDash: Boolean = false,
) {
  /** 是否为 progressive 直链（无 DASH SegmentBase），如 YouTube 流。 */
  val isProgressive: Boolean
    get() = segmentBase == null

  val isH264: Boolean
    get() = codecs.contains("avc", ignoreCase = true)

  /**
   * 杜比视界（dvhe/dvh1/dav1 等，codecs 以 "dv" 开头）。设备普遍无 DV 解码器，
   * CodecCapabilityProbe 也不探测，故单独识别以便 [isPlayable] 将其排除。
   */
  val isDolbyVision: Boolean
    get() = codecs.startsWith("dv", ignoreCase = true)

  val isH265: Boolean
    get() = (codecs.contains("hev", ignoreCase = true) || codecs.contains("hvc", ignoreCase = true)) &&
      !isDolbyVision

  val isAv1: Boolean
    get() = codecs.contains("av01", ignoreCase = true) && !isDolbyVision
}

data class PlaybackSegmentBase(
  val initializationRange: String,
  val indexRange: String,
)
