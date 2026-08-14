package com.kirin.mt.ui.space

/**
 * YouTube 频道主页覆盖层请求。镜像 [UpSpaceRequest]，但主键是 YouTube channelId（UC 开头）
 * 而非 B 站 mid。由 AppShell 在 YouTube 视频卡片点 UP 主头像时按 source 分流置位。
 */
internal data class YoutubeChannelRequest(
  val channelId: String,
  val channelName: String,
  val avatar: String = "",
)