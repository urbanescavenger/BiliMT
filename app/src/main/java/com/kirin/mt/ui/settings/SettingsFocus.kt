package com.kirin.mt.ui.settings

import android.util.Log
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.kirin.mt.ui.theme.BiliSpacing
import kotlin.math.abs
import kotlinx.coroutines.delay

/** 设置页诊断日志。设置页此前零日志,D-pad 焦点回归(行不可达/跳顶/丢焦点)只能靠它证伪。 */
internal const val SettingsLogTag = "BiliMT:Settings"

/**
 * P11-148:等目标行进入布局的帧数上限(≈1s)。
 * [LazyListState.scrollItemIntoComfortableView] 已把目标行滚进视口,正常 1~2 帧即可见;
 * 留大预算只为弱机上首帧组合迟缓(此时宁可多等,也别在节点没挂载时白打 requestFocus)。
 */
internal const val SettingsFocusWaitLayoutFrames = 60

/** 抢焦点重试预算:20 次 × 50ms ≈ 1s(与 RecommendScreen 的初始焦点重试同量级)。 */
private const val SettingsFocusRetryAttempts = 20
private const val SettingsFocusRetryDelayMs = 50L

/**
 * P11-148:有界重试的抢焦点。
 *
 * 离屏行(LazyColumn 未组合的行)与 Dialog 的首帧,`FocusRequester` 都还没有挂载节点——此时
 * `requestFocus()` 落空(节点缺失时本 Compose 版本直接抛 `IllegalStateException`,见
 * SettingsScreen.kt 折叠组的注释),**一次调用失败就永久无焦点**:
 * 焦点树里没有任何节点,D-pad 全部按键无响应(表现为「设置项上下选择丢焦点」,列表深处的
 * WebDAV 备份行因此按不到)。按帧重试到成功或用尽预算,与 TvVideoGrid / RecommendScreen
 * 的焦点恢复路径同构。
 *
 * @param label 用尽预算时的日志上下文(项 id / lazy 下标 / 弹窗名)。
 * @return 是否抢到焦点;false 时已打 [SettingsLogTag] 警告。
 */
internal suspend fun FocusRequester.requestFocusWithRetry(
  label: String,
  attempts: Int = SettingsFocusRetryAttempts,
): Boolean {
  repeat(attempts) {
    withFrameNanos { }
    if (runCatching { requestFocus() }.getOrDefault(false)) {
      return true
    }
    delay(SettingsFocusRetryDelayMs)
  }
  Log.w(SettingsLogTag, "focus retry exhausted: $label")
  return false
}

internal val SettingsBringIntoViewSpec = object : BringIntoViewSpec {
  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    val childEnd = offset + size
    return when {
      offset < 0f && childEnd > containerSize -> 0f
      offset < 0f -> offset
      childEnd > containerSize -> childEnd - containerSize
      else -> 0f
    }
  }
}

@Composable
internal fun SettingsEntryFocusTarget(
  focusRequester: FocusRequester,
  onFocused: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(BiliSpacing.Xs)
      .focusRequester(focusRequester)
      .onFocusChanged { focusState ->
        if (focusState.isFocused) {
          onFocused()
        }
      }
      .focusTarget(),
  )
}

internal fun Modifier.settingsBoundaryKeys(
  itemIndex: Int,
  onMoveSettingFocus: (Int, Int) -> Boolean,
  onMoveLeftToNav: () -> Boolean,
): Modifier {
  return onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) {
      return@onPreviewKeyEvent false
    }
    when (event.key) {
      Key.DirectionUp -> onMoveSettingFocus(itemIndex, -1)
      Key.DirectionDown -> onMoveSettingFocus(itemIndex, 1)
      Key.DirectionLeft -> onMoveLeftToNav()
      else -> false
    }
  }
}

internal suspend fun LazyListState.scrollItemIntoComfortableView(
  index: Int,
  direction: Int,
  fallbackItemHeightPx: Int,
  edgeInsetPx: Int,
) {
  val totalItems = layoutInfo.totalItemsCount
  if (totalItems <= 0) {
    return
  }

  val safeIndex = index.coerceIn(0, totalItems - 1)
  val layout = layoutInfo
  val viewportTop = layout.viewportStartOffset + edgeInsetPx
  val viewportBottom = layout.viewportEndOffset - edgeInsetPx
  val focusedItem = layout.visibleItemsInfo.firstOrNull { item -> item.index == safeIndex }

  if (focusedItem != null) {
    val itemTop = focusedItem.offset
    val itemBottom = itemTop + focusedItem.size
    val scrollDelta = when {
      itemTop < viewportTop -> itemTop - viewportTop
      itemBottom > viewportBottom -> itemBottom - viewportBottom
      else -> 0
    }
    if (abs(scrollDelta) <= 1) {
      return
    }
    scroll {
      scrollBy(scrollDelta.toFloat())
    }
    return
  }

  val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
  val itemHeightPx = layout.visibleItemsInfo.firstOrNull()?.size ?: fallbackItemHeightPx
  val maxTop = (viewportHeight - itemHeightPx - edgeInsetPx).coerceAtLeast(edgeInsetPx)
  val desiredTop = when {
    direction > 0 -> maxTop
    direction < 0 -> edgeInsetPx
    else -> ((viewportHeight - itemHeightPx) / 2).coerceIn(edgeInsetPx, maxTop)
  }
  scrollToItem(safeIndex, scrollOffset = -desiredTop)
}
