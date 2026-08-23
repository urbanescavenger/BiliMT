package com.kirin.mt.core.player

/**
 * YouTube 播放路径优先级:主路径先走 SABR 还是 DASH 自合成兜底。
 *
 * 默认 [Sabr]——保持历史行为(NewPipe SABR 主路径 → DASH 兜底)。
 * [Dash] 用于慢源/卡顿场景:慢 SABR 首段(googlevideo 服务器 >10s 才送首段)会被 8s stall
 * 看门狗误判触发完整重建(见 `iptv-thumb-stall-watchdog-kills-slow-sources` 同类),切 Dash 让
 * DASH 自合成优先(NewPipe 已解密直链拼 MPD,实测能出 4K VP9,见 docs/youtube-hd-playback.md)。
 *
 * @see com.kirin.mt.core.settings.AppSettings.youtubeDeliveryPriority
 */
enum class YoutubeDeliveryPriority(val key: String, val label: String) {
  Sabr("sabr", "SABR 优先"),
  Dash("dash", "DASH 优先");

  companion object {
    /** 按 DataStore 存的 [key] 解码;null/未知回 [Sabr](对齐 [YoutubeDefaultQuality.fromKey] 模式)。 */
    fun fromKey(key: String?): YoutubeDeliveryPriority =
      entries.firstOrNull { it.key == key } ?: Sabr
  }
}
