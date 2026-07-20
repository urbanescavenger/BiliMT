package com.kirin.mt.ui.mobile.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 移动端播放器统一手势检测:在单个 pointerInput 里互斥地识别
 *   - 单击(中央三分之一区域 / 左右边缘)
 *   - 长按(越过 longPressTimeout 仍未越过 touchSlop)
 *   - 横向拖拽(越过水平 touchSlop)
 *
 * 之所以手写 awaitPointerEventScope 循环、而不是叠 detectTapGestures + detectHorizontalDragGestures
 * 两个 pointerInput:两者会抢 down 事件、行为不确定。单循环按「超时→长按、越过 slop→拖拽、抬起→单击」
 * 分支判定,互斥且确定。
 */
internal suspend fun PointerInputScope.detectPlayerGestures(
  onCenterTap: () -> Unit,
  onEdgeTap: () -> Unit,
  onLongPressStart: () -> Unit,
  onLongPressEnd: () -> Unit,
  onSeekStart: () -> Unit,
  onSeekDelta: (deltaPx: Float) -> Unit,
  onSeekEnd: () -> Unit,
  onSeekCancel: () -> Unit,
) {
  // 用 Compose 默认值(与 DefaultViewConfiguration 一致),避免不同 Compose 版本
  // ViewConfiguration 成员名差异(touchSlop/longPressTimeout vs *Millis/pointerSlop)。
  // PointerInputScope 即 Density,8.dp.toPx() 直接可用。
  val slop = 8.dp.toPx()
  val longPressTimeoutMs = 500L

  // 外层 while 保证多次手势连续识别(awaitEachGesture 单次语义在不同版本里有差异)
  while (true) awaitEachGesture {
    // 每次手势现读宽度:pointerInput(Unit) 块只启动一次,若在顶部缓存 width,
    // 竖屏→全屏切换后布局变宽但 width 仍是旧值,中央/边缘判定会失真(全屏点击判为边缘、不暂停)。
    val width = size.width.toFloat()
    val down = awaitFirstDown()
    val pointerId = down.id
    val downPos = down.position
    val downTime = down.uptimeMillis
    var mode = Mode.Tap

    try {
      while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: continue

        if (mode == Mode.Tap) {
          val dt = change.uptimeMillis - downTime
          when {
            dt >= longPressTimeoutMs -> {
              mode = Mode.LongPress
              onLongPressStart()
            }
            abs(change.position.x - downPos.x) > slop -> {
              mode = Mode.Drag
              onSeekStart()
            }
          }
        }
        if (mode == Mode.Drag) {
          onSeekDelta(change.positionChange().x)
        }

        if (change.changedToUp()) {
          when (mode) {
            Mode.LongPress -> onLongPressEnd()
            Mode.Drag -> onSeekEnd()
            Mode.Tap -> {
              val third = width / 3f
              if (downPos.x >= third && downPos.x <= width - third) {
                onCenterTap()
              } else {
                onEdgeTap()
              }
            }
          }
          // 标记已收尾,finally 不再补发清理回调
          mode = Mode.Tap
          break
        }
      }
    } finally {
      // 协程被取消(系统拦截/多指抢断)时,按当前模式补发清理,避免倍速/拖拽状态卡死
      when (mode) {
        Mode.Drag -> onSeekCancel()
        Mode.LongPress -> onLongPressEnd()
        Mode.Tap -> Unit
      }
    }
  }
}

private enum class Mode { Tap, LongPress, Drag }