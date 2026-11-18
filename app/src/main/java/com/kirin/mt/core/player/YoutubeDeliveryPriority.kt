package com.kirin.mt.core.player

/**
 * YouTube 播放路径优先级(P11-114,按 [YoutubePlaybackResolver.resolve] 的实际分层)。
 *
 * - [Sabr](默认,UI 显示为「NewPipe」):NewPipe 主路(visionOS `/player` + SABR pot-less)→
 *   WEB-SABR 兜底 → DASH 兜底,历史行为。[key] 保持 `sabr` 以兼容已持久化的设置。
 * - [Dash]:慢源/卡顿逃生——慢 SABR 首段(googlevideo 服务器 >10s 才送首段)会被 8s stall
 *   看门狗误判触发完整重建(见 `iptv-thumb-stall-watchdog-kills-slow-sources` 同类),切 Dash 让
 *   DASH 自合成优先(NewPipe 已解密直链拼 MPD,实测能出 4K VP9)。
 * - [WebSabr](UI 显示为「FreeTube」):强制先走 WEB attested 路径(桌面 ytcfg 身份 + 自铸
 *   poToken + cpn,请求形状逐字节对齐 FreeTube),适用门控视频/4K 场景;失败落回 SABR 主链。
 *   [key] 保持 `websabr` 以兼容已持久化的设置。
 * - [IosDirect](UI 显示为「IOS 直链优先」,P11-342):最优先直通 IOS client 匿名 /player 的
 *   全阶梯**已签名直链**(免 PO token/WebView/n 解密,合成 MPD),失败落默认 NewPipe 链;
 *   档位语义与 Dash/WebSabr 一致=「先走这条路」,不进兜底链。实测见
 *   docs/youtube-anonymous-client-probe.md。
 *
 * 注意:HLS 已在 P11-101 Step 0 整体移除(media3 createFallbackOptions FATAL + 媒体段门控 403),
 * 不是可选路径。
 *
 * @see com.kirin.mt.core.settings.AppSettings.youtubeDeliveryPriority
 */
enum class YoutubeDeliveryPriority(val key: String, val label: String) {
  Sabr("sabr", "NewPipe 优先"),
  Dash("dash", "DASH 优先"),
  WebSabr("websabr", "FreeTube 优先"),
  IosDirect("ios", "IOS 直链优先");

  companion object {
    /** 按 DataStore 存的 [key] 解码;null/未知回 [Sabr](对齐 [YoutubeDefaultQuality.fromKey] 模式)。 */
    fun fromKey(key: String?): YoutubeDeliveryPriority =
      entries.firstOrNull { it.key == key } ?: Sabr
  }
}