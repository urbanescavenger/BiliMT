package com.kirin.mt.core.model

/**
 * 直播分区(二级分区)。来自 xlive/web-interface/v1/index/getWebAreaList
 * data[].list[]。
 */
data class LiveArea(
  val id: Int,
  val parentId: Int,
  val name: String,
  /** 分区图标 URL(可能为空)。 */
  val icon: String = "",
)

/**
 * 直播分区组(一级分区及其子分区)。来自 xlive/web-interface/v1/index/getWebAreaList
 * data[]。
 */
data class LiveAreaGroup(
  val id: Int,
  val name: String,
  val areas: List<LiveArea>,
)

/**
 * 直播间摘要(推荐列表项)。来自 xlive/web-interface/v1/second/getList 的 data.list[]。
 */
data class LiveRoom(
  val roomId: Long,
  val uid: Long,
  val uname: String,
  val title: String,
  /** 封面;为空时回退到 keyframe(直播间实时截图)。 */
  val cover: String,
  val face: String,
  /** 在线人数。 */
  val online: Int,
  val areaName: String,
  val keyframe: String,
  /** 1=正在直播。 */
  val liveStatus: Int,
  /** 二级分区 ID。 */
  val areaId: Int = 0,
  /** 一级分区 ID。 */
  val parentAreaId: Int = 0,
)

/**
 * 直播推荐列表分页结果。
 * @param items 当前页直播间
 * @param nextPage 下一页页号(从 1 起;hasMore=false 时无意义)
 * @param hasMore 是否还有更多
 */
data class LiveListPage(
  val items: List<LiveRoom>,
  val nextPage: Int,
  val hasMore: Boolean,
)