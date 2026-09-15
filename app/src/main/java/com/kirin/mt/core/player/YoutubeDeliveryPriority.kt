package com.kirin.mt.core.player

/**
 * YouTube 播放路径优先级(P11-114,按 [YoutubePlaybackResolver.resolve] 的实际分层)。
 *
 * - [Sabr](默认):NewPipe SABR 主路(visionOS pot-less)→ WEB-SABR 兜底 → DASH 兜底,历史行为。
 * - [Dash]:慢源/卡顿逃生——慢 SABR 首段(googlevideo 服务器 >10s 才送首段)会被 8s stall
 *   看门狗误判触发完整重建(见 `iptv-thumb-stall-watchdog-kills-slow-sources` 同类),切 Dash 让
 *   DASH 自合成优先(NewPipe 已解密直链拼 MPD,实测能出 4K VP9)。
 * - [WebSabr]:强制先走 WEB attested 路径(桌面 ytcfg 身份 + 自铸 poToken + cpn),适用门控视频/
 *   4K 场景;失败落回 SABR 主链。
 *
 * 注意:HLS 已在 P11-101 Step 0 整体移除(media3 createFallbackOptions FATAL + 媒体段门控 403),
 * 不是可选路径。
 *
 * @see com.kirin.mt.core.settings.AppSettings.youtubeDeliveryPriority
 */
enum class YoutubeDeliveryPriority(val key: String, val label: String) {
  Sabr("sabr", "SABR 优先"),
  Dash("dash", "DASH 优先"),
  WebSabr("websabr", "WEB-SABR 优先");

  companion object {
    /** 按 DataStore 存的 [key] 解码;null/未知回 [Sabr](对齐 [YoutubeDefaultQuality.fromKey] 模式)。 */
    fun fromKey(key: String?): YoutubeDeliveryPriority =
      entries.firstOrNull { it.key == key } ?: Sabr
  }
}