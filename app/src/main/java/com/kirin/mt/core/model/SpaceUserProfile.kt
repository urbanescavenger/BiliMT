package com.kirin.mt.core.model

data class SpaceUserProfile(
  val mid: Long,
  val name: String,
  val face: String,
  val topPhoto: String,
  val sign: String,
  val level: Int,
  val fans: Long,
  val following: Long,
  val isVip: Boolean,
  val vipLabel: String,
  val officialRole: Int,
  val officialTitle: String,
  val officialDesc: String,
  // 来自 x/space/acc/info 的 data.live_room:liveStatus 1=正直播、roomid 直播间短号、title/cover 直播间信息。
  // 无直播时 liveStatus=0 / liveRoomId=0。用于主页头像"直播"标记 + 点头像切直播间。
  val liveRoomId: Long = 0L,
  val liveStatus: Int = 0,
  val liveTitle: String = "",
  val liveCover: String = "",
) {
  companion object {
    val EMPTY = SpaceUserProfile(
      mid = 0L,
      name = "",
      face = "",
      topPhoto = "",
      sign = "",
      level = 0,
      fans = 0L,
      following = 0L,
      isVip = false,
      vipLabel = "",
      officialRole = 0,
      officialTitle = "",
      officialDesc = "",
    )
  }
}