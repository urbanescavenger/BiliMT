package com.kirin.mt.ui.settings

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
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
