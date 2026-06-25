package com.kirin.mt.core.model

/** PGC 花絮/番外板块。aid 为 0 的跳转项已在 mapper 过滤。 */
data class PgcSection(
  val id: Long,
  val title: String,
  val episodes: List<PgcEpisode>,
)
