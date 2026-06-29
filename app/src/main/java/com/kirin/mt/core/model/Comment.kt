package com.kirin.mt.core.model

/**
 * 一条视频评论。来自 `/x/v2/reply` 的 `replies[]`。
 * 楼中楼回复(reply_count>0 时的二级评论)本期不展开,仅显示计数。
 */
data class Comment(
  val id: Long,
  val uname: String,
  val avatar: String,
  val mid: Long,
  val content: String,
  val likeCount: Int,
  val replyCount: Int,
  val ctime: Long,
)
