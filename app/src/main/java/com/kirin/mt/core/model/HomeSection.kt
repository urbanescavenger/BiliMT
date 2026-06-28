package com.kirin.mt.core.model

/**
 * 首页分区。`Recommend`/`Popular` 为非 UGC 入口（无分区 tid）；其余 31 项对齐 BV `UgcTypeV2`
 * 的一级分区，`feedRcmdTid` 即 BV 新版分区 tid，用于 `region/feed/rcmd?from_region=`。
 * 顺序按 BV `UgcTopNavItem` 枚举声明顺序（= `channelId` 7→36、然后 44）。
 */
enum class HomeSection(
  val key: String,
  /** BV 新版分区 tid，用于 `region/feed/rcmd?from_region=`。Recommend/Popular 无此值。 */
  val feedRcmdTid: Int? = null,
) {
  Recommend("recommend"),
  Popular("popular"),
  Douga("douga", 1005),
  Game("game", 1008),
  Kichiku("kichiku", 1007),
  Music("music", 1003),
  Dance("dance", 1004),
  Cinephile("cinephile", 1001),
  Ent("ent", 1002),
  Knowledge("knowledge", 1010),
  Tech("tech", 1012),
  Information("information", 1009),
  Food("food", 1020),
  Shortplay("shortplay", 1021),
  Car("car", 1013),
  Fashion("fashion", 1014),
  Sports("sports", 1018),
  Animal("animal", 1024),
  Vlog("vlog", 1029),
  Painting("painting", 1006),
  Ai("ai", 1011),
  HomeDecor("home", 1015),
  Outdoors("outdoors", 1016),
  Gym("gym", 1017),
  Handmake("handmake", 1019),
  Travel("travel", 1022),
  Rural("rural", 1023),
  Parenting("parenting", 1025),
  Health("health", 1026),
  Emotion("emotion", 1027),
  LifeJoy("life_joy", 1030),
  LifeExperience("life_experience", 1031),
  Mysticism("mysticism", 1028);

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