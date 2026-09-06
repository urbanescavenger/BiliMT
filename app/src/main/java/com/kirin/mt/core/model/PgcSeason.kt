package com.kirin.mt.core.model

/** PGC 季详情。 */
data class PgcSeason(
  val seasonId: Int,
  val title: String,
  val cover: String,
  val evaluate: String,
  val type: Int,
  val typeName: String,
  val newEpDesc: String,
  val episodes: List<PgcEpisode>,
  val sections: List<PgcSection>,
  val seasons: List<PgcSeasonRef>,
  val progress: PgcPlayProgress?,
  /** 季级角标(接口 badge,如「会员」);空=无。 */
  val badge: String = "",
  // 统计(stat):移动端详情页数据行展示,对齐官方 播放/追番/弹幕。
  val viewCount: Long = 0,
  val danmakuCount: Long = 0,
  val followCount: Long = 0,
)

/** 同系列其它季。 */
data class PgcSeasonRef(
  val seasonId: Int,
  val seasonTitle: String,
  val cover: String,
)

/** 上次播放进度。 */
data class PgcPlayProgress(
  val lastEpId: Int,
  val lastEpIndex: String,
  /** 秒 */
  val lastTime: Int,
)
