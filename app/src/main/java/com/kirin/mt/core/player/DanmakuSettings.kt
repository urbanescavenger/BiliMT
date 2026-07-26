package com.kirin.mt.core.player

import androidx.annotation.StringRes
import com.kirin.mt.R

data class DanmakuSettings(
  val enabled: Boolean = true,
  val opacity: Float = 0.8f,
  val fontSize: Int = 28,
  val area: Float = 0.5f,
  val speed: Int = 5,
  val allowTop: Boolean = true,
  val allowBottom: Boolean = true,
  /** 弹幕容量/性能档:决定显示数量上限 + 同屏行数 + buffer。默认标准(5000)。 */
  val capacity: DanmakuCapacity = DanmakuCapacity.Standard,
)

/**
 * 弹幕容量档位:用户在设置里选,按 TV 盒子性能放宽弹幕显示数量 + 同屏密度。
 *
 * - [maxEntries] toTextData 限流(自己发的 isMine 必保留,不参与此限流)。
 * - [lineCountMax] 滚动弹幕同屏行数上限;[fixedLineCountMax] 顶/底固定弹幕行数上限。
 * - [scrollBuffer]/[fixedBuffer] Bytedance DanmakuView 各层 bufferSize。
 *
 * Unlimited 档 maxEntries=Int.MAX_VALUE,实际受 DanmakuRepository parse 上限(20000)约束。
 */
enum class DanmakuCapacity(
  val key: String,
  val maxEntries: Int,
  val lineCountMax: Int,
  val fixedLineCountMax: Int,
  val scrollBuffer: Int,
  val fixedBuffer: Int,
  @StringRes val labelRes: Int,
) {
  Standard("standard", 5000, 10, 3, 8, 4, R.string.player_danmaku_capacity_standard),
  High("high", 8000, 12, 4, 10, 5, R.string.player_danmaku_capacity_high),
  Ultra("ultra", 12000, 15, 5, 12, 6, R.string.player_danmaku_capacity_ultra),
  Unlimited("unlimited", Int.MAX_VALUE, 15, 5, 12, 6, R.string.player_danmaku_capacity_unlimited);

  companion object {
    fun fromKey(key: String?): DanmakuCapacity = entries.firstOrNull { it.key == key } ?: Standard
  }
}