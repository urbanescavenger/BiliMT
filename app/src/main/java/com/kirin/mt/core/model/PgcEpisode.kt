package com.kirin.mt.core.model

/** PGC 单集（正片或花絮）。 */
data class PgcEpisode(
  val id: Int,
  val aid: Long,
  val bvid: String,
  val cid: Long,
  val title: String,
  val longTitle: String,
  val cover: String,
  /** 秒 */
  val duration: Int,
)
