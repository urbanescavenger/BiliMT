package com.kirin.mt.core.player

enum class PlaybackQualityPreference(
  val key: String,
  val requestedQualityId: Int,
) {
  Highest("highest", 127),
  Q1080("1080", 80),
  Q720("720", 64),
  Q480("480", 32);

  /**
   * 高度制清晰度源（红果短剧 ladder）的取流上限：同一个设置项在红果侧的值域映射。
   * B站 qn 是「请求档」语义、红果 ladder 是「≤上限取最优」语义，这里只换算档位高低，
   * 让设置页默认画质对红果同样生效（红果面板未手动选档时用它兜底）。
   */
  fun hongguoMaxQuality(): Int = when (this) {
    Highest, Q1080 -> 1080
    Q720 -> 720
    Q480 -> 480
  }

  companion object {
    fun fromKey(key: String?): PlaybackQualityPreference {
      return entries.firstOrNull { preference -> preference.key == key } ?: Highest
    }
  }
}
