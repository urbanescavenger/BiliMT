package com.kirin.mt.core.player

/**
 * YouTube SABR 自适应起播档(设置项)。media3 1.10.0 的 AdaptiveTrackSelection 初始选轨 = 「≤ 带宽估计×0.7
 * 的最高码率档」(BaseTrackSelection 构造器内部强制按码率降序重排,resolver 挪 index0 的顺序对选轨无效),
 * 故用 [seedBps] 给带宽计 seed 一个「目标档码率/0.7」的初始估计,让首段落在目标档,带宽实测后由
 * DefaultSabrChunkSource 合成 iterator 逐档爬升到 [YoutubeDefaultQuality] 上限。144/240 等更低档仍留在
 * ABR 降档链(网络崩可降)。
 */
enum class YoutubeStartQuality(val key: String, val label: String, val startHeight: Int?) {
  /** seed 到最低码率 → 最低档起播(最省带宽、起播最快)。 */
  Auto("auto", "自动（最低档）", null),
  Q144("144", "144P", 144),
  Q240("240", "240P", 240),
  Q360("360", "360P", 360),
  Q480("480", "480P", 480),
  Q720("720", "720P", 720);

  /** 初始带宽估计 seed(bps),让 AdaptiveTrackSelection 首段落在目标档。 */
  fun seedBps(): Long {
    // 初始选轨 = 「≤ seed×0.7 的最高码率档」。把 seed 设成「目标档典型码率 / 0.7」,首段命中目标档;
    // 实际档码率随 codec/内容浮动,近似值偏差±一档可接受(起播后带宽实测自然爬升/收敛)。
    val effectiveBps = when (this) {
      Auto, Q144 -> 250_000L    // ~144~240p
      Q240       -> 500_000L    // ~240p
      Q360       -> 900_000L    // ~360p
      Q480       -> 1_800_000L  // ~480p
      Q720       -> 3_500_000L  // ~720p
    }
    return (effectiveBps / 0.7).toLong()
  }

  companion object {
    fun fromKey(key: String?): YoutubeStartQuality {
      return entries.firstOrNull { quality -> quality.key == key } ?: Q480
    }
  }
}
