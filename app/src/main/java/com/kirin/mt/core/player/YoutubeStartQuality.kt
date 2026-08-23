package com.kirin.mt.core.player

/**
 * YouTube SABR 自适应起播档(设置项)。Auto 自适应时 ExoPlayer 初始选轨命中 AdaptationSet index0,
 * 由 [startHeight] 决定把哪个档挪到 index0 作起播档:网络实测后再由 DefaultSabrChunkSource 合成 iterator
 * 逐档爬升到 [YoutubeDefaultQuality] 上限。144/240 等更低档仍留在 ABR 降档链(网络崩可降)。
 */
enum class YoutubeStartQuality(val key: String, val label: String, val startHeight: Int?) {
  /** 纯升序,index0 = 最低档起播(最省带宽、起播最快)。 */
  Auto("auto", "自动（最低档）", null),
  Q144("144", "144P", 144),
  Q240("240", "240P", 240),
  Q360("360", "360P", 360),
  Q480("480", "480P", 480),
  Q720("720", "720P", 720);

  companion object {
    fun fromKey(key: String?): YoutubeStartQuality {
      return entries.firstOrNull { quality -> quality.key == key } ?: Q480
    }
  }
}
