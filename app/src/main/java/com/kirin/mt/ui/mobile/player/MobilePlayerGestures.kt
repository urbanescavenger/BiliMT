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
 * 竖向拖拽的半屏侧:左半屏=亮度、右半屏=音量(通用手机播放器习惯)。
 */
internal enum class VerticalDragSide { Brightness, Volume }

/**
 * 移动端播放器统一手势检测:在单个 pointerInput 里互斥地识别
 *   - 单击(中央三分之二区域 / 左右各六分之一边缘)
 *   - 长按(越过 longPressTimeout 仍未越过 touchSlop)
 *   - 横向拖拽(越过水平 touchSlop,且水平位移不小于竖向)→ seek
 *   - 竖向拖拽(越过竖向 touchSlop,且竖向位移大于水平)→ 左半屏亮度 / 右半屏音量
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
  onVerticalDragStart: (side: VerticalDragSide) -> Unit = {},
  onVerticalDragDelta: (side: VerticalDragSide, deltaPx: Float) -> Unit = { _, _ -> },
  onVerticalDragEnd: () -> Unit = {},
  onVerticalDragCancel: () -> Unit = {},
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
    var verticalSide = VerticalDragSide.Brightness

    try {
      while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: continue

        if (mode == Mode.Tap) {
          val dt = change.uptimeMillis - downTime
          val dx = change.position.x - downPos.x
          val dy = change.position.y - downPos.y
          when {
            dt >= longPressTimeoutMs -> {
              mode = Mode.LongPress
              onLongPressStart()
            }
            // 横向 seek 与竖向亮度/音量在越过 slop 时按主方向互斥判定:
            // |dx|>=|dy| → 横拖 seek;否则竖拖,按 down 点在左/右半屏分亮度(左)/音量(右)。
            abs(dx) > slop && abs(dx) >= abs(dy) -> {
              mode = Mode.Drag
              onSeekStart()
            }
            abs(dy) > slop -> {
              verticalSide = if (downPos.x < width / 2f) VerticalDragSide.Brightness else VerticalDragSide.Volume
              mode = Mode.VerticalDrag
              onVerticalDragStart(verticalSide)
            }
          }
        }
        if (mode == Mode.Drag) {
          onSeekDelta(change.positionChange().x)
        }
        if (mode == Mode.VerticalDrag) {
          onVerticalDragDelta(verticalSide, change.positionChange().y)
        }

        if (change.changedToUp()) {
          when (mode) {
            Mode.LongPress -> onLongPressEnd()
            Mode.Drag -> onSeekEnd()
            Mode.VerticalDrag -> onVerticalDragEnd()
            Mode.Tap -> {
              // 中央 2/3 区域 → 暂停/播放;左右各 1/6 边缘 → 切换控件显隐。
              val edge = width / 6f
              if (downPos.x >= edge && downPos.x <= width - edge) {
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
        Mode.VerticalDrag -> onVerticalDragCancel()
        Mode.LongPress -> onLongPressEnd()
        Mode.Tap -> Unit
      }
    }
  }
}

private enum class Mode { Tap, LongPress, Drag, VerticalDrag }