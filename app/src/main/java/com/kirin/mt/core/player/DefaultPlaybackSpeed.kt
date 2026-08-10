package com.kirin.mt.core.player

/**
 * 默认播放倍速(设置项)。档位对齐播放器倍速选项 [PlayerOverlay.PlayerSpeedOptions](0.5~2.0)。
 * 起播时按 [value] 初始化播放器 playbackSpeed(默认 1.0x)。
 */
enum class DefaultPlaybackSpeed(val key: String, val label: String, val value: Float) {
  X05("0.5", "0.5x", 0.5f),
  X075("0.75", "0.75x", 0.75f),
  X100("1.0", "1.0x", 1.0f),
  X125("1.25", "1.25x", 1.25f),
  X15("1.5", "1.5x", 1.5f),
  X20("2.0", "2.0x", 2.0f);

  companion object {
    fun fromKey(key: String?): DefaultPlaybackSpeed {
      return entries.firstOrNull { speed -> speed.key == key } ?: X100
    }
  }
}
