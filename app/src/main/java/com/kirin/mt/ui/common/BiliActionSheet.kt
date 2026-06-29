package com.kirin.mt.ui.common

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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * OK 确认并关闭,Back 关闭。首项自动获取焦点。点击蒙层外区域也关闭。
 */
@Composable
fun BiliActionSheet(
  title: String,
  items: List<BiliActionItem>,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val firstFocusRequester = remember { FocusRequester() }
  val shape = RoundedCornerShape(BiliRadius.Card)

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
    ),
  ) {
    Box(
      modifier = modifier
        .fillMaxSize()
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
            isFirst = index == 0,
            isLast = index == items.lastIndex,
            focusRequester = if (index == 0) firstFocusRequester else null,
            onDismiss = onDismiss,
          )
        }
      }
    }
  }

  LaunchedEffect(items) {
    if (items.isNotEmpty()) {
      runCatching { firstFocusRequester.requestFocus() }
    }
  }
}

@Composable
private fun BiliActionSheetItem(
  item: BiliActionItem,
  isFirst: Boolean,
  isLast: Boolean,
  focusRequester: FocusRequester?,
  onDismiss: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  var focused by remember { mutableStateOf(false) }
  val itemShape = RoundedCornerShape(BiliRadius.Pill)
  val baseModifier = Modifier
    .fillMaxWidth()
    .height(52.dp)
    .clip(itemShape)
    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
          // 在菜单边界消费上下方向,避免焦点逃逸到背后网格。
          Key.DirectionUp -> isFirst
          Key.DirectionDown -> isLast
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
