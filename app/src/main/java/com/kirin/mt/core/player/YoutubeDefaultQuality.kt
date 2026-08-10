package com.kirin.mt.core.player

/**
 * YouTube 默认画质(设置项)。YouTube 画质按分辨率(高度)分档,故用 [maxHeight] 作上限:
 * 解析时选 height <= maxHeight 的最高档。null = 自动(最大化分辨率)。
 */
enum class YoutubeDefaultQuality(val key: String, val label: String, val maxHeight: Int?) {
  Auto("auto", "自动（最高）", null),
  Q2160("2160", "4K", 2160),
  Q1440("1440", "2K", 1440),
  Q1080("1080", "1080P", 1080),
  Q720("720", "720P", 720),
  Q480("480", "480P", 480);

  companion object {
    fun fromKey(key: String?): YoutubeDefaultQuality {
      return entries.firstOrNull { quality -> quality.key == key } ?: Auto
    }
  }
}
