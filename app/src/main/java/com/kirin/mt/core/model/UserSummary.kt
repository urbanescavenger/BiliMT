package com.kirin.mt.core.model

import kotlinx.serialization.Serializable

/**
 * 搜索结果里的用户/UP主/频道条目。一个模型同时承载 B站 UP 与 YouTube 频道：
 * - B站：mid / name / face / sign / fans / videos / level / officialVerify 填充，channelId 恒空。
 * - YouTube：channelId / name / face / sign(简介) / fans(订阅) / videos 填充，mid 恒 0、level 恒 0。
 * 按 [source] 分发到不同空间页（B站 UP 空间 / YouTube 频道主页）。
 */
@Serializable
data class UserSummary(
  /** B站 mid；YouTube 恒 0。 */
  val mid: Long,
  /** YouTube 频道 id（UC 开头）；B站恒空串。 */
  val channelId: String,
  val name: String,
  val face: String,
  /** B站签名；YouTube 频道简介。 */
  val sign: String,
  /** B站粉丝数；YouTube 订阅数。 */
  val fans: Int,
  /** B站投稿数；YouTube 视频数。 */
  val videos: Int,
  /** B站等级；YouTube 恒 0。 */
  val level: Int,
  /** B站认证信息；YouTube 恒空串。 */
  val officialVerify: String,
  /** 内容来源：[SourceBili] / [SourceYoutube]。 */
  val source: String,
)
