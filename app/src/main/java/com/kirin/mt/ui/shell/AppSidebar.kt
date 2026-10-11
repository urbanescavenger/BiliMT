package com.kirin.mt.ui.shell

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.image.BiliImageSizing
import com.kirin.mt.core.image.buildOwnerAvatarRequest
import com.kirin.mt.core.storage.UserSession
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.focus.focusDiag
import com.kirin.mt.ui.glass.LocalLiquidGlassBackdrop
import com.kirin.mt.ui.glass.biliLiquidGlassSurface
import com.kirin.mt.ui.i18n.convertChineseText
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

private const val FocusLogTag = "BiliMT:Focus"

/** P11-191:能在侧栏内把焦点移向头像的方向键——只有这些键算「用户主动移过去」。 */
private val SidebarNavigationKeys = setOf(
  Key.DirectionUp,
  Key.DirectionDown,
  Key.DirectionLeft,
  Key.DirectionRight,
)

@Composable
internal fun AppSidebar(
  selectedDestination: AppDestination,
  accountSelected: Boolean,
  userSession: UserSession,
  autoConfirmOnFocus: Boolean,
  suppressAccountAutoConfirm: Boolean = false,
  /**
   * P11-191:只压头像的 autoConfirm(导航项不受影响)。用于「动态首屏合并 YouTube 关注流」
   * 这类会把网格换表、进而可能把焦点掉到头像的窗口——那段时间里落焦头像不该跳「我的」页,
   * 但用户主动按方向键切目的地仍然照常。
   */
  suppressAvatarAutoConfirm: Boolean = false,
  accountFocusRequester: FocusRequester,
  navFocusRequesters: Map<AppDestination, FocusRequester>,
  dynamicUnread: Int,
  onAccountSelected: () -> Unit,
  onDestinationSelected: (AppDestination) -> Unit,
  shouldAutoConfirmDestination: (AppDestination) -> Boolean,
  onMoveRight: (AppDestination) -> Boolean,
) {
  val homeColors = LocalHomeColors.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val cinematicVisualsEnabled = performancePolicy.cinematicVisualEffectsEnabled
  val liquidGlassBackdrop = LocalLiquidGlassBackdrop.current
  val liquidGlassEnabled = cinematicVisualsEnabled && performancePolicy.liquidGlassCardsEnabled && liquidGlassBackdrop != null
  val sidebarShape = RoundedCornerShape(
    topStart = BiliRadius.Sidebar,
    topEnd = BiliRadius.Sidebar,
    bottomEnd = BiliRadius.Sidebar,
    bottomStart = BiliRadius.Sidebar,
  )
  val sidebarBackground = if (cinematicVisualsEnabled) {
    Brush.horizontalGradient(
      colors = listOf(
        homeColors.sidebarSurface.copy(alpha = BiliFocus.HomeSidebarCinematicStartAlpha),
        homeColors.sidebarSurface.copy(alpha = BiliFocus.HomeSidebarCinematicMidAlpha),
        homeColors.sidebarSurface.copy(alpha = BiliFocus.HomeSidebarCinematicEndAlpha),
      ),
    )
  } else {
    Brush.horizontalGradient(
      colors = listOf(
        homeColors.sidebarSurface.copy(alpha = BiliFocus.HomeSidebarRefinedStartAlpha),
        homeColors.sidebarSurface.copy(alpha = BiliFocus.HomeSidebarRefinedMidAlpha),
        homeColors.sidebarSurface.copy(alpha = BiliFocus.HomeSidebarRefinedEndAlpha),
      ),
    )
  }
  val sidebarBorder = if (cinematicVisualsEnabled) {
    homeColors.textPrimary.copy(alpha = BiliFocus.HomeSidebarCinematicBorderAlpha)
  } else {
    homeColors.glassBorder
  }
  // 侧栏可聚焦项顺序:头像(0) + 导航项(1..n,按 AppDestination 枚举序,Settings 已排除)。
  // 用于循环导航:最上(头像)按上→最底,最底按下→最上。
  val sidebarFocusOrder = remember(accountFocusRequester, navFocusRequesters) {
    listOf(accountFocusRequester) + AppDestination.entries
      .filter { it != AppDestination.Settings }
      .map { navFocusRequesters.getValue(it) }
  }
  var focusedSidebarIndex by remember { mutableIntStateOf(-1) }
  // P11-191:头像 autoConfirm 只认「在侧栏里按出来的焦点移动」。
  // 焦点被动掉到头像(内容网格被外层换表销毁 → 焦点被清 → 落回布局里第一个可聚焦节点)时,
  // 若照样确认,用户就被凭空送进「我的」页(真机 logs_live_20261001_173435:17:34:24.748
  // 网格丢焦同一帧头像 openMyPage=true,全程无按键)。
  // 侧栏这层 onPreviewKeyEvent 只在焦点位于侧栏内时才收到按键 —— 内容网格里按的方向键到不了
  // 这里,所以「最近有没有侧栏按键」天然区分「按键移过来」与「被动掉过来」。
  var lastSidebarKeyMs by remember { mutableLongStateOf(0L) }
  Column(
    modifier = Modifier
      .width(BiliSizing.SidebarWidth)
      .fillMaxHeight()
      .focusDiag("sidebar")
      .clip(sidebarShape)
      .onPreviewKeyEvent { event ->
        if (
          event.type == KeyEventType.KeyDown &&
          event.key in SidebarNavigationKeys
        ) {
          lastSidebarKeyMs = SystemClock.uptimeMillis()
        }
        // 循环导航:仅在边界拦截(最上按上→最底,最底按下→最上),其余交给默认焦点遍历。
        if (event.type == KeyEventType.KeyDown && focusedSidebarIndex >= 0) {
          when (event.key) {
            Key.DirectionUp -> {
              if (focusedSidebarIndex == 0) {
                sidebarFocusOrder.last().requestFocus()
                true
              } else {
                false
              }
            }
            Key.DirectionDown -> {
              if (focusedSidebarIndex == sidebarFocusOrder.lastIndex) {
                sidebarFocusOrder.first().requestFocus()
                true
              } else {
                false
              }
            }
            else -> false
          }
        } else {
          false
        }
      }
      .then(
        if (liquidGlassEnabled) {
          Modifier.biliLiquidGlassSurface(
            enabled = true,
            shape = sidebarShape,
            surfaceColor = homeColors.sidebarSurface.copy(alpha = BiliFocus.HomeSidebarLiquidGlassSurfaceAlpha),
            borderColor = sidebarBorder,
            borderWidth = BiliFocus.RestingBorderWidth,
          )
        } else {
          Modifier
            .background(sidebarBackground)
            .border(BorderStroke(BiliFocus.RestingBorderWidth, sidebarBorder), sidebarShape)
        },
      )
      .padding(horizontal = BiliSpacing.Md, vertical = BiliSizing.ContentPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // 头像即「我的」入口(账号区 + 设置区合并页):登录/未登录都可点击进入。
    AccountNavItem(
      selected = accountSelected,
      userSession = userSession,
      autoConfirmOnFocus = autoConfirmOnFocus,
      suppressAutoConfirm = suppressAccountAutoConfirm || suppressAvatarAutoConfirm,
      isKeyDrivenFocus = {
        // 在回调里取值(不是组合期),避免每次按键都把侧栏重组一遍。
        SystemClock.uptimeMillis() - lastSidebarKeyMs <= BiliFocus.AutoConfirmKeyGraceMs
      },
      modifier = Modifier.focusRequester(accountFocusRequester),
      onClick = onAccountSelected,
      onMoveRight = {
        onMoveRight(selectedDestination)
      },
      onSidebarItemFocused = { focusedSidebarIndex = it },
    )
    Spacer(modifier = Modifier.height(BiliSizing.SidebarNavGroupTopPadding))
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(BiliSizing.SidebarNavGroupSpacing),
    ) {
      // 「设置」已并入头像「我的」入口,侧栏不再单独渲染设置导航项。
      AppDestination.entries.filter { it != AppDestination.Settings }.forEachIndexed { index, destination ->
        AppNavItem(
          destination = destination,
          selected = !accountSelected && selectedDestination == destination,
          autoConfirmOnFocus = shouldAutoConfirmDestination(destination),
          badge = if (destination == AppDestination.Dynamic) dynamicUnread else 0,
          modifier = Modifier.focusRequester(navFocusRequesters.getValue(destination)),
          onClick = {
            onDestinationSelected(destination)
          },
          onMoveRight = {
            onMoveRight(destination)
          },
          focusIndex = index + 1,
          onSidebarItemFocused = { focusedSidebarIndex = it },
          suppressAutoConfirm = suppressAccountAutoConfirm,
        )
      }
    }
    Spacer(modifier = Modifier.weight(1f))
  }
}

@Composable
private fun AccountNavItem(
  selected: Boolean,
  userSession: UserSession,
  autoConfirmOnFocus: Boolean,
  suppressAutoConfirm: Boolean = false,
  /** P11-191:这次落焦是不是「刚在侧栏里按了方向键」的结果;被动落焦返回 false。 */
  isKeyDrivenFocus: () -> Boolean = { true },
  modifier: Modifier,
  onClick: () -> Unit,
  onMoveRight: () -> Boolean,
  onSidebarItemFocused: (Int) -> Unit,
) {
  val performancePolicy = LocalBiliPerformancePolicy.current
  val homeColors = LocalHomeColors.current
  val cinematicVisualsEnabled = performancePolicy.cinematicVisualEffectsEnabled
  BiliFocusableSurface(
    shape = CircleShape,
    shadowOnFocus = !cinematicVisualsEnabled,
    focusedScale = if (cinematicVisualsEnabled) BiliFocus.CinematicNavScale else BiliFocus.CardScale,
    // 焦点环统一用白色(非默认粉色 accent),与选中态粉色左条区分。
    focusedBorderColor = homeColors.textPrimary.copy(alpha = BiliFocus.CinematicFocusedBorderAlpha),
    restingBorderColor = if (cinematicVisualsEnabled) {
      homeColors.textPrimary.copy(alpha = BiliFocus.CinematicRestingBorderAlpha)
    } else {
      null
    },
    focusedBorderWidth = if (cinematicVisualsEnabled) BiliFocus.RestingBorderWidth else BiliFocus.BorderWidth,
    focusedBackgroundColor = if (cinematicVisualsEnabled) {
      homeColors.textPrimary.copy(alpha = BiliFocus.CinematicFocusedBackgroundAlpha)
    } else {
      null
    },
    restingBackgroundColor = if (cinematicVisualsEnabled) {
      homeColors.textPrimary.copy(
        alpha = if (selected) {
          BiliFocus.CinematicSelectedBackgroundAlpha
        } else {
          BiliFocus.CinematicRestingBackgroundAlpha
        },
      )
    } else {
      null
    },
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.NavItemHeight)
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
          onMoveRight()
        } else {
          false
        }
      },
    enabled = !suppressAutoConfirm,
    onClick = onClick,
    onFocusChanged = { if (it) onSidebarItemFocused(0) },
    onFocused = {
      val keyDriven = isKeyDrivenFocus()
      val shouldOpen = autoConfirmOnFocus && !selected && !suppressAutoConfirm && keyDriven
      Log.d(
        FocusLogTag,
        "avatar focused: autoConfirm=$autoConfirmOnFocus suppress=$suppressAutoConfirm selected=$selected " +
          "keyDriven=$keyDriven -> openMyPage=$shouldOpen",
      )
      if (shouldOpen) {
        onClick()
      }
    },
  ) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      AccountAvatar(userSession = userSession)
    }
  }
}

@Composable
private fun AccountAvatar(userSession: UserSession) {
  val context = LocalContext.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val homeColors = LocalHomeColors.current
  val accountAvatarRequestSizePx = if (performancePolicy.lowSpecMode) {
    performancePolicy.ownerAvatarSizePx
  } else {
    BiliImageSizing.AccountAvatarSizePx
  }
  val fallbackPainter = ColorPainter(BiliColors.SurfaceElevated)
  val face = userSession.face.orEmpty()

  Box(
    modifier = Modifier.size(BiliSizing.AccountAvatarContainerSize),
    contentAlignment = Alignment.Center,
  ) {
    if (userSession.isLoggedIn && face.isNotBlank()) {
      val request = remember(
        context,
        face,
        accountAvatarRequestSizePx,
        performancePolicy.ownerAvatarRgb565Enabled,
        performancePolicy.imageMemoryCacheEnabled,
      ) {
        buildOwnerAvatarRequest(
          context = context,
          url = face,
          sizePx = accountAvatarRequestSizePx,
          allowRgb565 = performancePolicy.ownerAvatarRgb565Enabled,
          memoryCacheEnabled = performancePolicy.imageMemoryCacheEnabled,
        )
      }
      AsyncImage(
        model = request,
        contentDescription = userSession.uname?.let { name -> convertChineseText(name) } ?: stringResource(R.string.account_logged_in_default),
        contentScale = ContentScale.Crop,
        placeholder = fallbackPainter,
        error = fallbackPainter,
        modifier = Modifier
          .align(Alignment.Center)
          .size(BiliSizing.AccountAvatarSize)
          .clip(CircleShape)
          .background(BiliColors.SurfaceElevated),
      )
    } else {
      Icon(
        painter = painterResource(R.drawable.ic_nav_account),
        contentDescription = stringResource(R.string.nav_login),
        tint = if (userSession.isLoggedIn) homeColors.accent else homeColors.textSecondary,
        modifier = Modifier.size(BiliSizing.NavIconSize),
      )
    }

    if (userSession.isLoggedIn && userSession.isVip) {
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .offset(x = BiliSpacing.Xs, y = BiliSpacing.Xs)
          .size(BiliSizing.AccountVipBadgeSize)
          .clip(CircleShape)
          .background(BiliColors.BiliPink),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.account_vip_badge),
          color = BiliColors.TextPrimary,
          fontSize = BiliTypography.AccountVipBadge,
          lineHeight = BiliTypography.AccountVipBadgeLineHeight,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

@Composable
private fun AppNavItem(
  destination: AppDestination,
  selected: Boolean,
  autoConfirmOnFocus: Boolean,
  badge: Int,
  modifier: Modifier,
  onClick: () -> Unit,
  onMoveRight: () -> Boolean,
  focusIndex: Int,
  onSidebarItemFocused: (Int) -> Unit,
  suppressAutoConfirm: Boolean = false,
) {
  var focused by remember { mutableStateOf(false) }
  val homeColors = LocalHomeColors.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val cinematicVisualsEnabled = performancePolicy.cinematicVisualEffectsEnabled

  BiliFocusableSurface(
    shape = RoundedCornerShape(BiliRadius.Pill),
    shadowOnFocus = !cinematicVisualsEnabled,
    focusedScale = if (cinematicVisualsEnabled) BiliFocus.CinematicNavScale else BiliFocus.CardScale,
    // 焦点环统一用白色(非默认粉色 accent),与选中态粉色左条区分。
    focusedBorderColor = homeColors.textPrimary.copy(alpha = BiliFocus.CinematicFocusedBorderAlpha),
    restingBorderColor = if (cinematicVisualsEnabled) {
      homeColors.textPrimary.copy(alpha = BiliFocus.CinematicRestingBorderAlpha)
    } else {
      null
    },
    focusedBorderWidth = if (cinematicVisualsEnabled) BiliFocus.RestingBorderWidth else BiliFocus.BorderWidth,
    focusedBackgroundColor = if (cinematicVisualsEnabled) {
      homeColors.textPrimary.copy(alpha = BiliFocus.CinematicFocusedBackgroundAlpha)
    } else {
      null
    },
    restingBackgroundColor = if (cinematicVisualsEnabled) {
      homeColors.textPrimary.copy(
        alpha = if (selected) {
          BiliFocus.CinematicSelectedBackgroundAlpha
        } else {
          BiliFocus.CinematicRestingBackgroundAlpha
        },
      )
    } else {
      null
    },
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.NavItemHeight)
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
          onMoveRight()
        } else {
          false
        }
      },
    onFocusChanged = {
      focused = it
      if (it) onSidebarItemFocused(focusIndex)
    },
    enabled = !suppressAutoConfirm,
    onClick = onClick,
    onFocused = {
      if (autoConfirmOnFocus && !selected) {
        onClick()
      }
    },
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(BiliSpacing.Sm),
      contentAlignment = Alignment.Center,
    ) {
      if (selected) {
        Box(
          modifier = Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight(0.5f)
            .width(BiliSizing.SidebarNavIndicatorWidth)
            .clip(RoundedCornerShape(BiliRadius.Pill))
            .background(homeColors.accent),
        )
      }
      // 选中=粉色图标,焦点(非选中)=白色图标,其余=次级灰。
      // 与选中态粉色左条区分:焦点用白色系,选中用粉色系。
      Icon(
        painter = painterResource(destination.iconRes),
        contentDescription = stringResource(destination.titleRes),
        tint = when {
          selected -> homeColors.accent
          focused -> homeColors.textPrimary
          else -> homeColors.textSecondary
        },
        modifier = Modifier
          .width(BiliSizing.NavIconSize)
          .height(BiliSizing.NavIconSize),
      )
      if (badge > 0) {
        // 未读动态红点:叠在导航项右上角。
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .size(BiliSizing.NavUnreadDotSize)
            .clip(CircleShape)
            .background(BiliColors.BiliPink),
        )
      }
    }
  }
}
