package com.kirin.mt.core.model

/**
 * PGC（番剧/影视）分区类型。
 *
 * @param id 用于索引接口的 `st` / `season_type` 参数（注意 Tv=5 之后跳到 Variety=7，没有 6）
 * @param feedName 用于 feed 接口的 `name` 参数
 * @param usesV3Feed 番剧/国创走 v3 feed（带排行榜 cardStyle），其余走 v1 feed（扁平 items）
 */
enum class PgcType(val id: Int, val feedName: String, val usesV3Feed: Boolean) {
  Anime(id = 1, feedName = "anime", usesV3Feed = true),
  Movie(id = 2, feedName = "movie", usesV3Feed = false),
  Documentary(id = 3, feedName = "documentary", usesV3Feed = false),
  GuoChuang(id = 4, feedName = "guochuang", usesV3Feed = true),
  Tv(id = 5, feedName = "tv", usesV3Feed = false),
  Variety(id = 7, feedName = "variety", usesV3Feed = false);

  companion object {
    fun fromId(id: Int): PgcType? = entries.firstOrNull { it.id == id }
  }
}
