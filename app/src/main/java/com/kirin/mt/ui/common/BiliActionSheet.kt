package com.kirin.mt.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.ui.focus.focusDiag
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

/**
 * 长按动态卡片弹出的操作菜单项。
 */
data class BiliActionItem(
  val label: String,
  val enabled: Boolean = true,
  val onClick: () -> Unit,
)

/**
 * TV 遥控器友好的模态操作菜单:居中卡片 + 半透蒙层。D-pad 上下在菜单项间移动,
 * OK 确认并关闭,Back 关闭。首项自动获取焦点。
 *
 * 屏内覆盖层(非 Dialog 独立窗口):TV 上 Dialog 窗口焦点不切过去,D-pad 会被背后
 * 网格拦截,导致焦点卡在首项无法移动。对齐 SpeedTestDialog 等屏内覆盖层模式。
 *
 * 焦点陷阱:每项各自持有 FocusRequester,方向键全部消费并手动在启用项间移焦,
 * 不依赖 Compose 默认焦点遍历——否则焦点会逃逸到背后网格,上下键一按就丢焦点。
 */
@Composable
fun BiliActionSheet(
  title: String,
  items: List<BiliActionItem>,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Card)
  val focusRequesters = remember(items.size) {
    List(items.size) { FocusRequester() }
  }

  BackHandler(onBack = onDismiss)

  Box(
    modifier = modifier
      .fillMaxSize()
      .focusDiag("action-sheet")
      .background(BiliColors.OverlayScrim.copy(alpha = 0.6f)),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 420.dp)
        .clip(shape)
        .background(homeColors.cardSurface)
        .border(
          width = 1.dp,
          color = homeColors.textPrimary.copy(alpha = 0.15f),
          shape = shape,
        )
        .padding(BiliSpacing.Lg),
    ) {
      Text(
        text = title,
        color = homeColors.textPrimary,
        fontSize = BiliTypography.CardTitle,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(modifier = Modifier.height(BiliSpacing.Md))
      items.forEachIndexed { index, item ->
        BiliActionSheetItem(
          item = item,
          focusRequester = focusRequesters[index],
          onDismiss = onDismiss,
          onMoveFocus = { direction ->
            moveSheetFocus(items, index, direction, focusRequesters)
          },
        )
      }
    }
  }

  LaunchedEffect(items) {
    if (items.isNotEmpty()) {
      val firstEnabled = items.indexOfFirst { it.enabled }
      if (firstEnabled >= 0) {
        runCatching { focusRequesters[firstEnabled].requestFocus() }
      }
    }
  }
}

/**
 * 在菜单内按方向键手动移动焦点,只落到可用的(enabled)项,越过禁用项。
 * 全部返回 true 消费按键,杜绝 Compose 默认焦点遍历把焦点逃逸到背后网格。
 */
private fun moveSheetFocus(
  items: List<BiliActionItem>,
  fromIndex: Int,
  direction: Int, // -1 = 上, +1 = 下
  focusRequesters: List<FocusRequester>,
) {
  var next = fromIndex
  repeat(items.size) {
    next += direction
    if (next !in items.indices) return // 菜单边界
    if (items[next].enabled) {
      runCatching { focusRequesters[next].requestFocus() }
      return
    }
  }
}

@Composable
private fun BiliActionSheetItem(
  item: BiliActionItem,
  focusRequester: FocusRequester,
  onDismiss: () -> Unit,
  onMoveFocus: (Int) -> Unit,
) {
  val homeColors = LocalHomeColors.current
  var focused by remember { mutableStateOf(false) }
  val itemShape = RoundedCornerShape(BiliRadius.Pill)
  val baseModifier = Modifier
    .fillMaxWidth()
    .height(52.dp)
    .clip(itemShape)
    .focusRequester(focusRequester)
    .onFocusChanged { focused = it.isFocused }
    .onPreviewKeyEvent { event ->
      if (event.type != KeyEventType.KeyDown) {
        // KeyUp of a confirm key triggers the action; let it fall through to the branch below.
        if (event.type == KeyEventType.KeyUp && event.key.isActionConfirmKey()) {
          if (item.enabled) {
            item.onClick()
            onDismiss()
          }
          true
        } else {
          false
        }
      } else {
        when (event.key) {
          // 方向键全部消费并手动移焦:默认遍历会逃逸到背后网格,导致焦点丢失。
          // Back 交给外层 BackHandler 统一处理。
          Key.DirectionUp -> {
            onMoveFocus(-1)
            true
          }
          Key.DirectionDown -> {
            onMoveFocus(+1)
            true
          }
          Key.DirectionLeft, Key.DirectionRight -> true
          else -> false
        }
      }
    }
    .background(if (focused) homeColors.textPrimary.copy(alpha = 0.12f) else Color.Transparent)
    .focusable(enabled = item.enabled)

  Row(
    modifier = baseModifier.padding(horizontal = BiliSpacing.Md),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    Text(
      text = item.label,
      color = if (item.enabled) {
        if (focused) homeColors.textPrimary else homeColors.textSecondary
      } else {
        homeColors.textTertiary
      },
      fontSize = BiliTypography.CardMeta,
      fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun Key.isActionConfirmKey(): Boolean {
  return this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
}
