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