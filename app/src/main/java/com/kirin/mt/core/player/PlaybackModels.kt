package com.kirin.mt.core.player

data class PlaybackRequest(
  val bvid: String,
  val cid: Long,
  val title: String,
  val startPositionMs: Long = 0L,
  val aid: Long = 0L,
  val ownerName: String = "",
  val ownerFace: String = "",
  val ownerMid: Long = 0L,
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
) {
  val isPgc: Boolean
    get() = epId > 0L || seasonId > 0L
}

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
  val segmentBase: PlaybackSegmentBase,
) {
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
