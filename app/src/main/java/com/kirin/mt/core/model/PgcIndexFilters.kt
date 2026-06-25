package com.kirin.mt.core.model

/**
 * PGC 索引筛选条件（13 维度）。各维度原始 id/str，默认 -1/空 = 全部。
 * 不同 [PgcType] 可用维度子集不同，由 [PgcVideoRepository.getPgcIndex] 按 type 组装参数。
 */
data class PgcIndexFilters(
  val order: Int = 0,
  val sort: Int = 0,
  val seasonVersion: Int = -1,
  val spokenLanguage: Int = -1,
  val area: Int = -1,
  val isFinish: Int = -1,
  val copyright: Int = -1,
  val seasonStatus: Int = -1,
  val seasonMonth: Int = -1,
  val producer: Int = -1,
  val year: String = "",
  val releaseDate: String = "",
  val style: Int = 0,
) {
  companion object {
    val Default = PgcIndexFilters()
  }
}

/** 索引分页游标。 */
data class PgcIndexPage(
  val page: Int = 1,
  val pageSize: Int = 20,
  val hasNext: Boolean = false,
)

/** feed 分页结果。 */
data class PgcFeedPage(
  val items: List<PgcSummary>,
  val nextCursor: Int,
  val hasNext: Boolean,
)

/** 索引分页结果。 */
data class PgcIndexResult(
  val items: List<PgcSummary>,
  val nextPage: PgcIndexPage,
)
