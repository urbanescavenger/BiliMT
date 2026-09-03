package com.kirin.mt.ui.mobile.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import kotlin.math.roundToInt

/**
 * 移动端播放器竖滑亮度/音量支持(在线 MobilePlayerScreen / 离线 MobileOfflinePlayerScreen 共用):
 *   - [PlayerVerticalGestureController]:左半屏竖滑调亮度(Window.screenBrightness)、右半屏竖滑调音量
 *     (AudioManager STREAM_MUSIC)。起始值锚定手势开始时刻,delta 按「位移/手势区高度」比例换算,
 *     整屏滑动=满量程,上滑增、下滑减。亮度 clamp 0.01~1(最暗留一点可见度,不用系统自动亮度)。
 *   - [VerticalGestureBubble]:手势气泡 overlay(图标+百分比),由调用方在松手 800ms 后隐藏。
 */

internal class PlayerVerticalGestureController(private val activity: Activity) {

  private val audioManager =
    activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private var startBrightness = 0.5f
  private var startVolume = 0

  /** 亮度手势开始:锚定当前亮度。系统自动亮度(screenBrightness=-1)时按 0.5 起步。 */
  fun onBrightnessStart(): Float {
    startBrightness = activity.window.attributes.screenBrightness
      .takeIf { it >= 0f && it <= 1f } ?: 0.5f
    return startBrightness
  }

  /** 亮度增量:dyPx 下滑为正 → 变暗。返回新亮度(0.01~1)。 */
  fun onBrightnessDelta(dyPx: Float, heightPx: Float): Float {
    val newBrightness = (startBrightness - dyPx / heightPx.coerceAtLeast(1f))
      .coerceIn(BrightnessMin, 1f)
    val attrs = activity.window.attributes
    attrs.screenBrightness = newBrightness
    activity.window.attributes = attrs
    return newBrightness
  }

  /** 音量手势开始:锚定当前媒体音量。返回比例(0~1)供气泡显示。 */
  fun onVolumeStart(): Float {
    startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return if (max > 0) startVolume.toFloat() / max else 0f
  }

  /** 音量增量:dyPx 下滑为正 → 减小。flags=0 不弹系统音量条(气泡由播放器自己画)。 */
  fun onVolumeDelta(dyPx: Float, heightPx: Float): Float {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return 0f
    val target = (startVolume - dyPx / heightPx.coerceAtLeast(1f) * max)
      .roundToInt().coerceIn(0, max)
    if (target != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
      runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }
    return target.toFloat() / max
  }

  private companion object {
    /** 亮度下限:最暗留一点可见度(能看见画面与气泡),不归 0 全黑。 */
    const val BrightnessMin = 0.01f
  }
}

/** 竖滑手势气泡:图标 + 百分比,半透明圆角底,由调用方放在对应半屏中央。 */
@Composable
internal fun VerticalGestureBubble(
  side: VerticalDragSide,
  fraction: Float,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(16.dp))
      .background(Color(0x99000000))
      .padding(horizontal = 16.dp, vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      painter = painterResource(
        if (side == VerticalDragSide.Brightness) R.drawable.ic_player_brightness else R.drawable.ic_player_volume,
      ),
      contentDescription = null,
      tint = Color.White,
      modifier = Modifier.size(28.dp),
    )
    Spacer(Modifier.height(4.dp))
    Text(text = "${(fraction * 100).roundToInt()}%", color = Color.White)
  }
}