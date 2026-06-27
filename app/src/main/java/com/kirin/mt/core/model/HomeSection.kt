package com.kirin.mt.core.model

enum class HomeSection(
  val key: String,
  val regionTid: Int?,
  /** BV 新版分区 tid，用于 `region/feed/rcmd?from_region=`。主分区有此值时走 feed/rcmd（重载出新鲜推荐流），
   *  否则回退 `dynamic/region?rid=regionTid`。番剧/生活 BV 新体系无对应 UGC 主分类，留 null。 */
  val feedRcmdTid: Int? = null,
) {
  Recommend("recommend", null),
  Popular("popular", null),
  Anime("anime", 13),
  Movie("movie", 181, 1001),
  Game("game", 4, 1008),
  Knowledge("knowledge", 36, 1010),
  Tech("tech", 188, 1012),
  Music("music", 3, 1003),
  Dance("dance", 129, 1004),
  Life("life", 160),
  Food("food", 211, 1020),
  Douga("douga", 1, 1005);

  companion object {
    val DefaultOrder = entries.toList()

    fun fromKey(key: String): HomeSection? {
      return entries.firstOrNull { section -> section.key == key }
    }

    /** 交换 [list] 中 [i] 与 [j] 位置，返回新列表。越界时原样返回。 */
    fun swapped(list: List<HomeSection>, i: Int, j: Int): List<HomeSection> {
      if (i == j || i !in list.indices || j !in list.indices) return list
      val mutable = list.toMutableList()
      val tmp = mutable[i]
      mutable[i] = mutable[j]
      mutable[j] = tmp
      return mutable.toList()
    }
  }
}
