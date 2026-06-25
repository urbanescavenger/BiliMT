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
