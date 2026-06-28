package com.kirin.mt.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.kirin.mt.ui.glass.biliLiquidGlassSurface
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliMotion
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

/**
 * 统一的顶部 capsule tab 行(对齐 UGC `RecommendHeader` 样式):玻璃胶囊外层容器 +
 * 可横滚的 pill 行。UGC/PGC/动态 三页顶部 tab 共用此容器,保证视觉一致。
 */
@Composable
fun BiliCapsuleTabRow(
  itemCount: Int,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val capsuleShape = RoundedCornerShape(BiliRadius.Pill)
  val liquidGlassEnabled = performancePolicy.cinematicVisualEffectsEnabled && performancePolicy.liquidGlassCardsEnabled
  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.HomeSectionCapsuleHeight),
    contentAlignment = Alignment.Center,
  ) {
    val capsuleMaxWidth = maxWidth
    val capsuleMinWidth = capsuleMaxWidth * biliCapsuleTabMinWidthFraction(itemCount)
    val capsuleArrangement = if (itemCount <= BiliCapsuleTabSpreadMaxCount) {
      Arrangement.SpaceEvenly
    } else {
      Arrangement.spacedBy(BiliSizing.HomeSectionCapsuleItemSpacing)
    }
    Row(
      modifier = Modifier
        .align(Alignment.Center)
        .offset(y = -BiliSizing.HomeSectionCapsuleTopOffset)
        .widthIn(min = capsuleMinWidth, max = capsuleMaxWidth)
        .clip(capsuleShape)
        .biliLiquidGlassSurface(
          enabled = liquidGlassEnabled,
          shape = capsuleShape,
          surfaceColor = homeColors.glassSurface.copy(alpha = BiliFocus.HomeSectionCapsuleSurfaceAlpha),
          borderColor = homeColors.textPrimary.copy(alpha = BiliFocus.HomeSectionCapsuleBorderAlpha),
          borderWidth = BiliFocus.RestingBorderWidth,
        )
        .padding(
          horizontal = BiliSizing.HomeSectionCapsuleHorizontalPadding,
          vertical = BiliSizing.HomeSectionCapsuleVerticalPadding,
        )
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = capsuleArrangement,
      verticalAlignment = Alignment.CenterVertically,
      content = content,
    )
  }
}

/**
 * 统一的 pill tab 项(对齐 UGC `HomeSectionTab` 样式):透明底、focused=accent 边框 +
 * 微底色、3 层文字色(selected=accent/focused=textPrimary/resting=textSecondary)、
 * 19sp Bold when selected/focused。D-pad: Left/Up 逃逸回侧栏、Down 进内容、confirm 选中。
 * 各页按需传 onMoveLeftToNav(UGC 首项)/ onMoveUpToNav(动态·PGC) / onMoveDownToGrid。
 */
@Composable
fun BiliPillTab(
  text: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onMoveLeftToNav: (() -> Boolean)? = null,
  onMoveUpToNav: (() -> Boolean)? = null,
  onMoveDownToGrid: (() -> Boolean)? = null,
  onClick: () -> Unit = {},
  onFocused: (() -> Unit)? = null,
) {
  var focused by remember { mutableStateOf(false) }
  val performancePolicy = LocalBiliPerformancePolicy.current
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Pill)
  val targetBorderColor = if (focused) homeColors.accent else BiliColors.Transparent
  val targetTextColor = when {
    selected -> homeColors.accent
    focused -> homeColors.textPrimary
    else -> homeColors.textSecondary
  }
  val borderWidth = if (performancePolicy.motionEnabled) {
    animateDpAsState(
      targetValue = if (focused) BiliFocus.BorderWidth else BiliFocus.RestingBorderWidth,
      animationSpec = tween(BiliMotion.FocusMs, easing = BiliMotion.FocusEasing),
      label = "biliPillTabBorderWidth",
    ).value
  } else {
    if (focused) BiliFocus.BorderWidth else BiliFocus.RestingBorderWidth
  }
  val borderColor = if (performancePolicy.motionEnabled) {
    animateColorAsState(
      targetValue = targetBorderColor,
      animationSpec = tween(BiliMotion.FocusMs, easing = BiliMotion.FocusEasing),
      label = "biliPillTabBorder",
    ).value
  } else {
    targetBorderColor
  }
  val textColor = if (performancePolicy.motionEnabled) {
    animateColorAsState(
      targetValue = targetTextColor,
      animationSpec = tween(BiliMotion.FocusMs, easing = BiliMotion.FocusEasing),
      label = "biliPillTabText",
    ).value
  } else {
    targetTextColor
  }
  val interactionSource = remember { MutableInteractionSource() }

  Box(
    modifier = modifier
      .height(BiliSizing.HomeSectionTabHeight)
      .widthIn(min = BiliSizing.HomeSectionTabCompactMinWidth)
      .clip(shape)
      .background(
        if (focused) {
          homeColors.textPrimary.copy(alpha = BiliFocus.HomeSectionTabFocusedSurfaceAlpha)
        } else {
          BiliColors.Transparent
        },
      )
      .border(BorderStroke(borderWidth, borderColor), shape)
      .onFocusChanged { focusState ->
        focused = focusState.isFocused
        if (focusState.isFocused && !selected) {
          onFocused?.invoke()
        }
      }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown &&
            event.key == Key.DirectionLeft &&
            onMoveLeftToNav != null -> onMoveLeftToNav()
          event.type == KeyEventType.KeyDown &&
            event.key == Key.DirectionUp &&
            onMoveUpToNav != null -> onMoveUpToNav()
          event.type == KeyEventType.KeyDown &&
            event.key == Key.DirectionDown &&
            onMoveDownToGrid != null -> onMoveDownToGrid()
          event.type == KeyEventType.KeyUp && event.key.isConfirmKey() -> {
            onClick()
            true
          }
          else -> false
        }
      }
      .focusable(interactionSource = interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
      )
      .padding(horizontal = BiliSpacing.Sm),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = textColor,
      fontSize = BiliTypography.HomeSectionTab,
      lineHeight = BiliTypography.HomeSectionTabLineHeight,
      fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
      textAlign = TextAlign.Center,
      maxLines = 1,
      style = TextStyle(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    )
  }
}

private const val BiliCapsuleTabSpreadMaxCount = 6

private fun biliCapsuleTabMinWidthFraction(count: Int): Float = when (count) {
  0, 1 -> 0.24f
  2 -> 0.34f
  3 -> 0.44f
  4 -> 0.54f
  5 -> 0.60f
  6 -> 0.66f
  else -> 0f
}

private fun Key.isConfirmKey(): Boolean {
  return this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
}
