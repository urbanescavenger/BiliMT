package com.kirin.mt.ui.pgc

/** 打开 PGC 季详情的请求。 */
internal data class PgcSeasonRequest(
  val seasonId: Int,
  val epId: Int = 0,
)
