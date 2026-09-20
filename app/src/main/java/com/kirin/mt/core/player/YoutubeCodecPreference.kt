package com.kirin.mt.core.player

/**
 * YouTube 播放专用的解码器偏好(P11-133:从 [PlaybackCodecPreference] 独立出来)。
 *
 * **为什么不复用 B站 那套**:`PlaybackCodecPreference` 的值域是 B站 DASH 的编码族
 * (H.264 / H.265 / AV1),而 YouTube 的轨**大量是 VP9**(`315/308/303/302/247` …),
 * 且 VP9 在 B站 侧根本不存在。共用一份枚举的后果是「YouTube 解码器」那个菜单里**选不到 VP9**,
 * 而这恰恰是 YouTube 最常用的一族。
 *
 * [codecKey] 对应 `YoutubePlaybackResolver` 里既有的 codec 分类键(由 `codecs=` 前缀推出),
 * 两条 YouTube 路径都消费它:经典路 `pickVideo` 的 `codecRank`、SABR 路的代表轨挑选,
 * 以及 ABR 梯子的 codec 锚点([com.kirin.mt.core.youtube.sabr.media.HeightAwareAdaptiveTrackSelection])。
 *
 * **key 取值刻意沿用旧共享键的字符串**(`auto`/`h264`/`h265`/`av1`),只新增 `vp9`——
 * 这样从 `playback_codec_preference` 回退读取时,存量用户的 H.264/H.265/AV1 能**原样映射**过来,
 * 不会被 `fromKey` 认不出来而降级成 Auto。
 *
 * @see com.kirin.mt.core.settings.AppSettings.youtubePlaybackCodecPreference
 */
enum class YoutubeCodecPreference(val key: String, val codecKey: String?) {
  /** 自动:沿用历史行为——梯子锚在**全组顶档**的 codec(YouTube 通常是 VP9)。 */
  Auto("auto", null),
  Vp9("vp9", "vp9"),
  Av1("av1", "av01"),
  H264("h264", "avc"),
  H265("h265", "hevc");

  /**
   * 本机解不解得了这一族。VP9 恒真——平台层必有软解,且 YouTube 梯子顶档就是 VP9(见
   * `HeightAwareAdaptiveTrackSelection` 的顶档 codec 锚点),拒掉它等于把 1440p/2160p 全封死。
   */
  fun isSupportedBy(capability: CodecCapability): Boolean = when (codecKey) {
    null -> true // Auto:由梯子按顶档 codec 定,不在这里拦
    "vp9" -> true
    "av01" -> capability.supportsAv1
    "avc" -> capability.supportsH264
    "hevc" -> capability.supportsH265
    else -> true
  }

  companion object {
    /** 按 DataStore 存的 [key] 解码;null/未知回 [Auto](对齐 [YoutubeDeliveryPriority.fromKey] 模式)。 */
    fun fromKey(key: String?): YoutubeCodecPreference =
      entries.firstOrNull { it.key == key } ?: Auto
  }
}
