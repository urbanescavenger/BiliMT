package com.kirin.mt.core.model

/** PGC 季卡片（feed / index 结果统一条目）。 */
data class PgcSummary(
  val seasonId: Int,
  val episodeId: Int,
  val title: String,
  val subTitle: String,
  val cover: String,
  val rating: String,
  val seasonType: Int,
)
