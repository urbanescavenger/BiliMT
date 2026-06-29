package com.kirin.mt.core.model

data class VideoSummary(
  val bvid: String,
  val title: String,
  val pic: String,
  val ownerName: String,
  val ownerFace: String,
  val ownerMid: Long,
  val view: Int,
  val danmaku: Int,
  val duration: Int,
  val pubdate: Long,
  val badge: String,
  val progress: Int = ProgressUnset,
  val viewAt: Long = 0L,
  val cid: Long = 0L,
  val historyPage: Int = 0,
  val historyPart: String = "",
  val historyVideos: Int = 0,
  val isLive: Boolean = false,
  // 动态专属字段:仅 fromDynamicItem 填充,其它来源保持默认 0/空。
  // dynId 用于点赞等动态操作;aid 用于稍后再看;三个计数用于卡片展示动态本身的社交数据。
  val dynId: String = "",
  val aid: Long = 0L,
  val likeCount: Int = 0,
  val commentCount: Int = 0,
  val forwardCount: Int = 0,
)

const val ProgressUnset = -1
