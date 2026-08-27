package com.kirin.mt.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

/**
 * TV 通用「▶ 播放全部」chip(UP 空间/YouTube 频道页主页内容行居左,对齐移动端):
 * 粉字药丸(播放列表详情页「播放全部」同语言),OK 触发 [onActivate]。
 * 聚焦只高亮不触发任何选择;行首 Left 默认消费不移动(防焦点逃逸出覆盖层,同 tab 行行首)。
 */
@Composable
internal fun TvPlayAllChip(
  modifier: Modifier = Modifier,
  onActivate: () -> Unit,
  onMoveUp: () -> Boolean,
  onMoveDown: () -> Boolean,
  onMoveLeft: () -> Boolean = { true },
  onMoveRight: () -> Boolean = { true },
) {
  var focused by remember { mutableStateOf(false) }
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Pill)
  val interactionSource = remember { MutableInteractionSource() }
  Box(
    modifier = modifier
      .height(BiliSizing.HomeSectionTabHeight)
      .widthIn(min = BiliSizing.HomeSectionTabCompactMinWidth)
      .clip(shape)
      .border(BorderStroke(BiliFocus.BorderWidth, if (focused) homeColors.accent else BiliColors.Transparent), shape)
      .onFocusChanged { state -> focused = state.isFocused }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> onMoveUp()
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> onMoveDown()
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> onMoveLeft()
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> onMoveRight()
          event.type == KeyEventType.KeyUp && event.key.isConfirmKey() -> {
            onActivate()
            true
          }
          else -> false
        }
      }
      .focusable(interactionSource = interactionSource)
      .clickable(interactionSource = interactionSource, indication = null, onClick = onActivate)
      .padding(horizontal = BiliSpacing.Sm),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = "▶ 播放全部",
      color = BiliColors.BiliPink,
      fontSize = BiliTypography.HomeSectionTab,
      lineHeight = BiliTypography.HomeSectionTabLineHeight,
      fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
      textAlign = TextAlign.Center,
      maxLines = 1,
    )
  }
}

/**
 * TV 通用排序切换 chip:「≡ 」+ 当前档位文案,OK 在两档间翻转(对齐移动端「≡ 排序」单按钮,
 * 档位如 最新发布/最多播放)。聚焦只高亮不切档——切档会重拉数据,聚焦扫过不该触发。
 */
@Composable
internal fun TvSortToggleChip(
  label: String,
  modifier: Modifier = Modifier,
  onActivate: () -> Unit,
  onMoveUp: () -> Boolean,
  onMoveDown: () -> Boolean,
  onMoveLeft: () -> Boolean = { true },
  onMoveRight: () -> Boolean = { true },
) {
  var focused by remember { mutableStateOf(false) }
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Pill)
  val interactionSource = remember { MutableInteractionSource() }
  Box(
    modifier = modifier
      .height(BiliSizing.HomeSectionTabHeight)
      .widthIn(min = BiliSizing.HomeSectionTabCompactMinWidth)
      .clip(shape)
      .border(BorderStroke(BiliFocus.BorderWidth, if (focused) homeColors.accent else BiliColors.Transparent), shape)
      .onFocusChanged { state -> focused = state.isFocused }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> onMoveUp()
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> onMoveDown()
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> onMoveLeft()
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> onMoveRight()
          event.type == KeyEventType.KeyUp && event.key.isConfirmKey() -> {
            onActivate()
            true
          }
          else -> false
        }
      }
      .focusable(interactionSource = interactionSource)
      .clickable(interactionSource = interactionSource, indication = null, onClick = onActivate)
      .padding(horizontal = BiliSpacing.Sm),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      color = if (focused) homeColors.textPrimary else homeColors.textSecondary,
      fontSize = BiliTypography.HomeSectionTab,
      lineHeight = BiliTypography.HomeSectionTabLineHeight,
      fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
      textAlign = TextAlign.Center,
      maxLines = 1,
    )
  }
}

private fun Key.isConfirmKey(): Boolean {
  return this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
}