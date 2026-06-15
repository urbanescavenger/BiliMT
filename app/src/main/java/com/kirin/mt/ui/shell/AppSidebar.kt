package com.kirin.mt.ui.shell

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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

@Composable
internal fun AppSidebar(
  selectedDestination: AppDestination,
  accountSelected: Boolean,
  userSession: UserSession,
  autoConfirmOnFocus: Boolean,
  accountFocusRequester: FocusRequester,
  navFocusRequesters: Map<AppDestination, FocusRequester>,
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
  Column(
    modifier = Modifier
      .width(BiliSizing.SidebarWidth)
      .fillMaxHeight()
      .clip(sidebarShape)
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
    if (userSession.isLoggedIn) {
      AccountStatusAvatar(userSession = userSession)
    } else {
      AccountNavItem(
        selected = accountSelected,
        userSession = userSession,
        autoConfirmOnFocus = autoConfirmOnFocus,
        modifier = Modifier.focusRequester(accountFocusRequester),
        onClick = onAccountSelected,
        onMoveRight = {
          onMoveRight(selectedDestination)
        },
      )
    }
    Spacer(modifier = Modifier.height(BiliSizing.SidebarNavGroupTopPadding))
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(BiliSizing.SidebarNavGroupSpacing),
    ) {
      AppDestination.entries.forEach { destination ->
        AppNavItem(
          destination = destination,
          selected = !accountSelected && selectedDestination == destination,
          autoConfirmOnFocus = shouldAutoConfirmDestination(destination),
          modifier = Modifier.focusRequester(navFocusRequesters.getValue(destination)),
          onClick = {
            onDestinationSelected(destination)
          },
          onMoveRight = {
            onMoveRight(destination)
          },
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
  modifier: Modifier,
  onClick: () -> Unit,
  onMoveRight: () -> Boolean,
) {
  val performancePolicy = LocalBiliPerformancePolicy.current
  val homeColors = LocalHomeColors.current
  val cinematicVisualsEnabled = performancePolicy.cinematicVisualEffectsEnabled
  BiliFocusableSurface(
    shape = CircleShape,
    shadowOnFocus = !cinematicVisualsEnabled,
    focusedScale = if (cinematicVisualsEnabled) BiliFocus.CinematicNavScale else BiliFocus.CardScale,
    focusedBorderColor = if (cinematicVisualsEnabled) {
      homeColors.textPrimary.copy(alpha = BiliFocus.CinematicFocusedBorderAlpha)
    } else {
      null
    },
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
    onClick = onClick,
    onFocused = {
      if (autoConfirmOnFocus && !selected) {
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
private fun AccountStatusAvatar(userSession: UserSession) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.NavItemHeight)
      .focusProperties { canFocus = false },
    contentAlignment = Alignment.Center,
  ) {
    AccountAvatar(userSession = userSession)
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
  modifier: Modifier,
  onClick: () -> Unit,
  onMoveRight: () -> Boolean,
) {
  var focused by remember { mutableStateOf(false) }
  val homeColors = LocalHomeColors.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val cinematicVisualsEnabled = performancePolicy.cinematicVisualEffectsEnabled

  BiliFocusableSurface(
    shape = RoundedCornerShape(BiliRadius.Pill),
    shadowOnFocus = !cinematicVisualsEnabled,
    focusedScale = if (cinematicVisualsEnabled) BiliFocus.CinematicNavScale else BiliFocus.CardScale,
    focusedBorderColor = if (cinematicVisualsEnabled) {
      homeColors.textPrimary.copy(alpha = BiliFocus.CinematicFocusedBorderAlpha)
    } else {
      null
    },
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
    onFocusChanged = { focused = it },
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
      Icon(
        painter = painterResource(destination.iconRes),
        contentDescription = stringResource(destination.titleRes),
        tint = if (selected || focused) homeColors.accent else homeColors.textSecondary,
        modifier = Modifier
          .width(BiliSizing.NavIconSize)
          .height(BiliSizing.NavIconSize),
      )
    }
  }
}
