package com.kirin.mt.core.player

/**
 * 播放缓冲时长上限(maxBuffer,设置项)。对应 LoadControl 的 MaxBufferMs——缓冲池多大,
 * 网络波动(如 4K 单轨锁档)时缓冲耗尽前能顶住多久不卡。默认 [Standard] 50s 对齐 LibreTube
 * (LibreTube min 10s / max 默认 50s)。注意:MaxBufferMs 不得低于 MinBuffer(10s)。
 */
enum class PlaybackBufferMax(val key: String, val label: String, val ms: Int) {
  Moderate("30", "30s", 30_000),
  Standard("50", "50s", 50_000),
  High("75", "75s", 75_000),
  Maximum("100", "100s", 100_000);

  companion object {
    fun fromKey(key: String?): PlaybackBufferMax {
      return entries.firstOrNull { buffer -> buffer.key == key } ?: Standard
    }
  }
}
