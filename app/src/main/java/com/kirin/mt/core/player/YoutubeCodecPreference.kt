package com.kirin.mt.core.player

/**
 * YouTube 播放专用的解码器偏好(P11-133:从 [PlaybackCodecPreference] 独立出来)。
 *
 * **为什么不复用 B站 那套**:`PlaybackCodecPreference` 的值域是 B站 DASH 的编码族
 * (H.264 / H.265 / AV1),而 YouTube 的轨**大量是 VP9**(`315/308/303/302/247` …),
 * 且 VP9 在 B站 侧根本不存在。共用一份枚举的后果是「YouTube 解码器」那个菜单里**选不到 VP9**,
 * 而这恰恰是 YouTube 最常用的一族。
 *
 * 值域**只列实测真的拿得到的族**:全部真机日志里 YouTube 梯子只有 `avc1.*` / `vp9` / `av01.*`
 * 三类,**从未出现过 HEVC**,故不设 H.265 项(详见枚举尾部注释)。
 *
 * [codecKey] 对应 `YoutubePlaybackResolver` 里既有的 codec 分类键(由 `codecs=` 前缀推出),
 * 两条 YouTube 路径都消费它:经典路 `pickVideo` 的 `codecRank`、SABR 路的代表轨挑选,
 * 以及 ABR 梯子的 codec 锚点([com.kirin.mt.core.youtube.sabr.media.HeightAwareAdaptiveTrackSelection])。
 *
 * **key 取值刻意沿用旧共享键的字符串**(`auto`/`h264`/`av1`),只新增 `vp9`——
 * 这样从 `playback_codec_preference` 回退读取时,存量用户选的 H.264/AV1 能**原样映射**过来,
 * 不会被 `fromKey` 认不出来而降级成 Auto。唯一的例外是旧键里的 `h265`:本枚举已无此项,
 * 它按 `fromKey` 的「未知即 Auto」落回自动——这是移除 H.265 时的**有意**取舍,不是兼容性事故。
 *
 * @see com.kirin.mt.core.settings.AppSettings.youtubePlaybackCodecPreference
 */
enum class YoutubeCodecPreference(val key: String, val codecKey: String?) {
  /** 自动:沿用历史行为——梯子锚在**全组顶档**的 codec(YouTube 通常是 VP9)。 */
  Auto("auto", null),
  Vp9("vp9", "vp9"),
  Av1("av1", "av01"),
  H264("h264", "avc");
  // 曾经还有 H265("h265","hevc"),已移除:本 app 走 WEB/SABR + NewPipe(web 系) 身份,抓到的
  // **全部**真机日志里一条 HEVC 轨都没有(codecs 串只有 avc1.* / vp9 / av01.*;容易误认的
  // itag 330~337 实为 vp9.2 HDR,不是 H.265)。留着它等于菜单里挂一个几乎永远无效的项——
  // 选了会被梯子的 hasPreferredFamily 守卫退回 Auto。存量选过 h265 的用户由 fromKey 落回 Auto。

  /**
   * 本机解不解得了这一族。VP9 恒真——平台层必有软解,且 YouTube 梯子顶档就是 VP9(见
   * `HeightAwareAdaptiveTrackSelection` 的顶档 codec 锚点),拒掉它等于把 1440p/2160p 全封死。
   */
  fun isSupportedBy(capability: CodecCapability): Boolean = when (codecKey) {
    null -> true // Auto:由梯子按顶档 codec 定,不在这里拦
    "vp9" -> true
    "av01" -> capability.supportsAv1
    "avc" -> capability.supportsH264
    else -> true
  }

  companion object {
    /** 按 DataStore 存的 [key] 解码;null/未知回 [Auto](对齐 [YoutubeDeliveryPriority.fromKey] 模式)。 */
    fun fromKey(key: String?): YoutubeCodecPreference =
      entries.firstOrNull { it.key == key } ?: Auto
  }
}
