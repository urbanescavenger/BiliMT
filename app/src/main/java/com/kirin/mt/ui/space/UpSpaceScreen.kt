package com.kirin.mt.ui.space

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
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
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.image.BiliImageSizing
import com.kirin.mt.core.image.buildOwnerAvatarRequest
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.common.VideoGridSkeleton
import com.kirin.mt.ui.common.appendUniqueByBvid
import com.kirin.mt.ui.common.focusRestoreKey
import com.kirin.mt.ui.common.resolveFocusIndex
import com.kirin.mt.ui.home.TvVideoGrid
import com.kirin.mt.ui.i18n.convertChineseText
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class UpSpaceOrder(val key: String, val titleRes: Int)

private val UpSpaceOrders = listOf(
  UpSpaceOrder(UpSpaceOrderLatest, R.string.player_up_sort_latest),
  UpSpaceOrder(UpSpaceOrderHot, R.string.player_up_sort_hot),
)

private const val FirstPage = 1

@Composable
internal fun UpSpaceScreen(
  request: UpSpaceRequest,
  videoRepository: VideoRepository,
  isLoggedIn: Boolean,
  uiState: UpSpaceUiState,
  firstItemFocusRequester: FocusRequester,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onBack: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
) {
  val coroutineScope = rememberCoroutineScope()
  val mid = request.mid

  val sortFocusRequesters = remember {
    UpSpaceOrders.associate { option -> option.key to FocusRequester() }
  }
  val followFocusRequester = remember { FocusRequester() }

  BackHandler { onBack() }

  // Profile + follow status: reload only when mid or retryKey changes (order does not affect profile).
  LaunchedEffect(mid, uiState.retryKey) {
    if (uiState.profileLoadedMid == mid && uiState.profileLoadedRetryKey == uiState.retryKey &&
      uiState.profileState !is SpaceProfileState.Loading
    ) {
      return@LaunchedEffect
    }
    uiState.profileState = SpaceProfileState.Loading
    uiState.followed = false
    uiState.profileState = try {
      SpaceProfileState.Loaded(videoRepository.getSpaceUserProfile(mid))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      SpaceProfileState.Failed(e.message.orEmpty())
    }
    if (isLoggedIn) {
      uiState.followed = runCatching { videoRepository.checkFollowStatus(mid) }.getOrDefault(false)
    }
    uiState.profileLoadedMid = mid
    uiState.profileLoadedRetryKey = uiState.retryKey
  }

  // Video first page: reload when mid/order/retryKey changes.
  LaunchedEffect(mid, uiState.order, uiState.retryKey) {
    if (uiState.videoLoadedMid == mid && uiState.videoLoadedOrder == uiState.order &&
      uiState.videoLoadedRetryKey == uiState.retryKey &&
      uiState.videoState !is SpaceVideoState.Loading
    ) {
      return@LaunchedEffect
    }
    uiState.videoState = SpaceVideoState.Loading
    uiState.focusedVideoIndex = 0
    uiState.focusedVideoKey = ""
    uiState.videoState = try {
      val videos = videoRepository.getSpaceVideos(mid, page = FirstPage, order = uiState.order)
      if (videos.isEmpty()) {
        SpaceVideoState.Empty
      } else {
        SpaceVideoState.Success(
          videos = videos,
          nextPage = FirstPage + 1,
          loadingMore = false,
          endReached = videos.size < UpSpacePageSize,
          loadMoreError = "",
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      SpaceVideoState.Failed(e.message.orEmpty())
    }
    uiState.videoLoadedMid = mid
    uiState.videoLoadedOrder = uiState.order
    uiState.videoLoadedRetryKey = uiState.retryKey
  }

  // Auto-focus the first video card when the first page arrives (initial open only).
  val videos = (uiState.videoState as? SpaceVideoState.Success)?.videos.orEmpty()
  LaunchedEffect(uiState.videoState, uiState.focusFirstVideo) {
    val success = uiState.videoState as? SpaceVideoState.Success
    if (success != null && uiState.focusFirstVideo && success.videos.isNotEmpty()) {
      withFrameNanos { }
      runCatching { firstItemFocusRequester.requestFocus() }
      uiState.focusFirstVideo = false
    }
  }

  fun loadNextPage() {
    val current = uiState.videoState as? SpaceVideoState.Success ?: return
    if (current.loadingMore || current.endReached) return
    val pageToLoad = current.nextPage
    val orderToLoad = uiState.order
    uiState.videoState = current.copy(loadingMore = true, loadMoreError = "")
    coroutineScope.launch {
      uiState.videoState = try {
        val nextVideos = videoRepository.getSpaceVideos(mid, page = pageToLoad, order = orderToLoad)
        val latest = uiState.videoState as? SpaceVideoState.Success ?: return@launch
        val merged = latest.videos.appendUniqueByBvid(nextVideos)
        latest.copy(
          videos = merged,
          nextPage = pageToLoad + 1,
          loadingMore = false,
          endReached = nextVideos.size < UpSpacePageSize || merged.size == latest.videos.size,
          loadMoreError = "",
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val latest = uiState.videoState as? SpaceVideoState.Success ?: return@launch
        latest.copy(loadingMore = false, loadMoreError = e.message.orEmpty())
      }
    }
  }

  fun toggleFollow() {
    if (!isLoggedIn || uiState.followLoading) return
    if (uiState.followed) {
      uiState.showUnfollowConfirm = true
      uiState.unfollowConfirmFocusedConfirm = false
    } else {
      setFollow(true)
    }
  }

  fun setFollow(follow: Boolean) {
    if (mid <= 0L || uiState.followLoading) return
    uiState.followLoading = true
    coroutineScope.launch {
      val success = runCatching { videoRepository.setFollowStatus(mid, follow) }.getOrDefault(false)
      if (success) {
        uiState.followed = follow
      }
      uiState.followLoading = false
      uiState.showUnfollowConfirm = false
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(BiliColors.VideoBlack),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      UpSpaceHeader(
        request = request,
        profileState = uiState.profileState,
        isLoggedIn = isLoggedIn,
        followed = uiState.followed,
        followLoading = uiState.followLoading,
        order = uiState.order,
        sortFocusRequesters = sortFocusRequesters,
        followFocusRequester = followFocusRequester,
        firstItemFocusRequester = firstItemFocusRequester,
        onOrderSelected = { uiState.selectOrder(it) },
        onFollowClicked = ::toggleFollow,
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = BiliSpacing.Lg),
      ) {
        when (val state = uiState.videoState) {
          SpaceVideoState.Loading -> VideoGridSkeleton()
          SpaceVideoState.Empty -> FeedStatusScreen(message = stringResource(R.string.up_space_empty))
          is SpaceVideoState.Failed -> FeedStatusScreen(
            message = stringResource(R.string.up_space_videos_failed, state.message),
            actionLabel = stringResource(R.string.action_retry),
            onAction = { uiState.retryKey += 1 },
          )
          is SpaceVideoState.Success -> TvVideoGrid(
            videos = state.videos,
            firstItemFocusRequester = firstItemFocusRequester,
            restoredFocusIndex = state.videos.resolveFocusIndex(uiState.focusedVideoKey, uiState.focusedVideoIndex),
            restoreFocusRequestKey = restoreFocusRequestKey,
            onRestoreFocusHandled = onRestoreFocusHandled,
            onFocusedIndexChange = { index, video ->
              uiState.focusedVideoIndex = index
              uiState.focusedVideoKey = video.focusRestoreKey()
            },
            onLoadMore = ::loadNextPage,
            onMoveLeftToNav = { true },
            onMoveUpFromFirstRow = {
              runCatching { sortFocusRequesters.getValue(uiState.order).requestFocus() }.isSuccess
            },
            onBackKey = { onBack() },
            onVideoSelected = onVideoSelected,
          )
        }
      }
    }

    if (uiState.showUnfollowConfirm) {
      UpSpaceUnfollowConfirmDialog(
        onConfirm = { setFollow(false) },
        onCancel = { uiState.showUnfollowConfirm = false },
      )
    }
  }
}

@Composable
private fun UpSpaceHeader(
  request: UpSpaceRequest,
  profileState: SpaceProfileState,
  isLoggedIn: Boolean,
  followed: Boolean,
  followLoading: Boolean,
  order: String,
  sortFocusRequesters: Map<String, FocusRequester>,
  followFocusRequester: FocusRequester,
  firstItemFocusRequester: FocusRequester,
  onOrderSelected: (String) -> Unit,
  onFollowClicked: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val profile = (profileState as? SpaceProfileState.Loaded)?.profile
  val displayName = profile?.name?.takeIf { it.isNotBlank() } ?: request.ownerName
  val displayFace = profile?.face?.takeIf { it.isNotBlank() } ?: request.ownerFace
  val sign = profile?.sign.orEmpty()
  val level = profile?.level ?: 0
  val fans = profile?.fans ?: 0L
  val followingCount = profile?.following ?: 0L
  val isVip = profile?.isVip == true
  val officialTitle = profile?.officialTitle.orEmpty()

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = BiliSizing.VideoGridHorizontalPadding, vertical = BiliSpacing.Lg),
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Xl),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      UpSpaceAvatar(face = displayFace, name = displayName)
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm)) {
          Text(
            text = convertChineseText(displayName).ifBlank { stringResource(R.string.player_panel_unknown_up) },
            color = BiliColors.TextPrimary,
            fontSize = BiliTypography.ScreenTitle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (isVip) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(BiliRadius.Pill))
                .background(BiliColors.BiliPink)
                .padding(horizontal = BiliSpacing.Sm, vertical = BiliSpacing.Xs),
            ) {
              Text(
                text = stringResource(R.string.account_vip_badge),
                color = BiliColors.TextPrimary,
                fontSize = BiliTypography.AccountProfileVipBadge,
                fontWeight = FontWeight.Bold,
              )
            }
          }
          if (level > 0) {
            Text(
              text = stringResource(R.string.up_space_level, level),
              color = BiliColors.BiliPink,
              fontSize = BiliTypography.BodySmall,
              fontWeight = FontWeight.Bold,
            )
          }
        }
        if (officialTitle.isNotBlank()) {
          Text(
            text = convertChineseText(officialTitle),
            color = BiliColors.TextSecondary,
            fontSize = BiliTypography.BodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg)) {
          Text(
            text = stringResource(R.string.up_space_fans, formatSpaceCount(fans)),
            color = BiliColors.TextSecondary,
            fontSize = BiliTypography.Body,
          )
          Text(
            text = stringResource(R.string.up_space_following, formatSpaceCount(followingCount)),
            color = BiliColors.TextSecondary,
            fontSize = BiliTypography.Body,
          )
        }
        val signText = sign.ifBlank { stringResource(R.string.up_space_sign_empty) }
        Text(
          text = convertChineseText(signText),
          color = BiliColors.TextTertiary,
          fontSize = BiliTypography.BodySmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    LazyRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
    ) {
      items(UpSpaceOrders, key = { it.key }) { option ->
        UpSpaceChip(
          text = stringResource(option.titleRes),
          selected = order == option.key,
          modifier = Modifier.focusRequester(sortFocusRequesters.getValue(option.key)),
          onActivate = { onOrderSelected(option.key) },
          onFocused = { if (order != option.key) onOrderSelected(option.key) },
          onMoveDown = {
            runCatching { firstItemFocusRequester.requestFocus() }.isSuccess
          },
        )
      }
      item {
        UpSpaceFollowChip(
          isLoggedIn = isLoggedIn,
          followed = followed,
          followLoading = followLoading,
          modifier = Modifier.focusRequester(followFocusRequester),
          onActivate = onFollowClicked,
          onMoveDown = {
            runCatching { firstItemFocusRequester.requestFocus() }.isSuccess
          },
          onMoveLeft = {
            runCatching { sortFocusRequesters.getValue(UpSpaceOrderHot).requestFocus() }.isSuccess
          },
        )
      }
    }
  }
}

@Composable
private fun UpSpaceAvatar(face: String, name: String) {
  val context = LocalContext.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val requestSizePx = if (performancePolicy.lowSpecMode) {
    BiliImageSizing.AccountAvatarSizePx
  } else {
    BiliImageSizing.AccountProfileAvatarSizePx
  }
  val fallbackPainter = ColorPainter(BiliColors.Surface)

  Box(
    modifier = Modifier
      .size(BiliSizing.AccountProfileAvatarSize)
      .clip(CircleShape)
      .background(BiliColors.Surface),
    contentAlignment = Alignment.Center,
  ) {
    if (face.isNotBlank()) {
      val request = remember(
        context,
        face,
        requestSizePx,
        performancePolicy.ownerAvatarRgb565Enabled,
        performancePolicy.imageMemoryCacheEnabled,
      ) {
        buildOwnerAvatarRequest(
          context = context,
          url = face,
          sizePx = requestSizePx,
          allowRgb565 = performancePolicy.ownerAvatarRgb565Enabled,
          memoryCacheEnabled = performancePolicy.imageMemoryCacheEnabled,
        )
      }
      AsyncImage(
        model = request,
        contentDescription = name,
        contentScale = ContentScale.Crop,
        placeholder = fallbackPainter,
        error = fallbackPainter,
        modifier = Modifier
          .size(BiliSizing.AccountProfileAvatarSize)
          .clip(CircleShape)
          .background(BiliColors.Surface),
      )
    } else {
      Icon(
        painter = painterResource(R.drawable.ic_nav_account),
        contentDescription = name,
        colorFilter = ColorFilter.tint(BiliColors.BiliPink),
        modifier = Modifier.size(BiliSizing.AccountAvatarSize),
      )
    }
  }
}

@Composable
private fun UpSpaceChip(
  text: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onActivate: () -> Unit,
  onFocused: () -> Unit,
  onMoveDown: () -> Boolean,
) {
  var focused by remember { mutableStateOf(false) }
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Pill)
  val borderColor = if (focused) homeColors.accent else BiliColors.Transparent
  val textColor = when {
    selected -> homeColors.accent
    focused -> homeColors.textPrimary
    else -> homeColors.textSecondary
  }
  val interactionSource = remember { MutableInteractionSource() }
  Box(
    modifier = modifier
      .height(BiliSizing.HomeSectionTabHeight)
      .widthIn(min = BiliSizing.HomeSectionTabCompactMinWidth)
      .clip(shape)
      .border(BorderStroke(BiliFocus.BorderWidth, borderColor), shape)
      .onFocusChanged { state ->
        focused = state.isFocused
        if (state.isFocused) onFocused()
      }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> onMoveDown()
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
      text = text,
      color = textColor,
      fontSize = BiliTypography.HomeSectionTab,
      lineHeight = BiliTypography.HomeSectionTabLineHeight,
      fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
      textAlign = TextAlign.Center,
      maxLines = 1,
    )
  }
}

@Composable
private fun UpSpaceFollowChip(
  isLoggedIn: Boolean,
  followed: Boolean,
  followLoading: Boolean,
  modifier: Modifier = Modifier,
  onActivate: () -> Unit,
  onMoveDown: () -> Boolean,
  onMoveLeft: () -> Boolean,
) {
  var focused by remember { mutableStateOf(false) }
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Pill)
  val text = when {
    !isLoggedIn -> stringResource(R.string.up_space_not_logged_in_follow)
    followLoading -> stringResource(R.string.player_up_follow_loading)
    followed -> stringResource(R.string.player_up_followed)
    else -> stringResource(R.string.player_up_follow)
  }
  val borderColor = if (focused) homeColors.accent else BiliColors.Transparent
  val textColor = when {
    followed -> homeColors.accent
    focused -> homeColors.textPrimary
    else -> homeColors.textSecondary
  }
  val interactionSource = remember { MutableInteractionSource() }
  Box(
    modifier = modifier
      .height(BiliSizing.HomeSectionTabHeight)
      .widthIn(min = BiliSizing.HomeSectionTabCompactMinWidth)
      .clip(shape)
      .border(BorderStroke(BiliFocus.BorderWidth, borderColor), shape)
      .onFocusChanged { state -> focused = state.isFocused }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> onMoveDown()
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> onMoveLeft()
          event.type == KeyEventType.KeyUp && event.key.isConfirmKey() && isLoggedIn -> {
            onActivate()
            true
          }
          else -> false
        }
      }
      .focusable(interactionSource = interactionSource)
      .clickable(interactionSource = interactionSource, indication = null, onClick = { if (isLoggedIn) onActivate() })
      .padding(horizontal = BiliSpacing.Sm),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = textColor,
      fontSize = BiliTypography.HomeSectionTab,
      lineHeight = BiliTypography.HomeSectionTabLineHeight,
      fontWeight = if (followed || focused) FontWeight.Bold else FontWeight.Medium,
      textAlign = TextAlign.Center,
      maxLines = 1,
    )
  }
}

@Composable
private fun UpSpaceUnfollowConfirmDialog(
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
) {
  val cancelFocusRequester = remember { FocusRequester() }
  val confirmFocusRequester = remember { FocusRequester() }

  LaunchedEffect(Unit) {
    withFrameNanos { }
    runCatching { cancelFocusRequester.requestFocus() }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(BiliColors.VideoBlack.copy(alpha = 0.6f))
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
          onCancel()
          true
        } else {
          false
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .width(BiliSizing.PlayerUnfollowDialogWidth)
        .clip(RoundedCornerShape(BiliRadius.Panel))
        .background(BiliColors.SurfaceElevated)
        .padding(BiliSpacing.Xl),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
    ) {
      Text(
        text = stringResource(R.string.player_unfollow_confirm_title),
        color = BiliColors.TextPrimary,
        fontSize = BiliTypography.PlayerPanelTitle,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
      Text(
        text = stringResource(R.string.player_unfollow_confirm_message),
        color = BiliColors.TextSecondary,
        fontSize = BiliTypography.BodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      ) {
        UpSpaceConfirmButton(
          text = stringResource(R.string.player_unfollow_confirm_cancel),
          modifier = Modifier
            .weight(1f)
            .focusRequester(cancelFocusRequester),
          onActivate = onCancel,
          onMoveRight = {
            runCatching { confirmFocusRequester.requestFocus() }.isSuccess
          },
        )
        UpSpaceConfirmButton(
          text = stringResource(R.string.player_unfollow_confirm_action),
          destructive = true,
          modifier = Modifier
            .weight(1f)
            .focusRequester(confirmFocusRequester),
          onActivate = onConfirm,
          onMoveLeft = {
            runCatching { cancelFocusRequester.requestFocus() }.isSuccess
          },
        )
      }
    }
  }
}

@Composable
private fun UpSpaceConfirmButton(
  text: String,
  modifier: Modifier = Modifier,
  destructive: Boolean = false,
  onActivate: () -> Unit,
  onMoveLeft: () -> Boolean = { false },
  onMoveRight: () -> Boolean = { false },
) {
  var focused by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(BiliRadius.Card)
  val surfaceColor = when {
    focused && destructive -> BiliColors.BiliPink
    focused -> BiliColors.PlayerPanelFocused
    else -> BiliColors.PlayerControlIdle
  }
  val textColor = if (focused) BiliColors.TextPrimary else BiliColors.TextSecondary
  Box(
    modifier = modifier
      .height(BiliSizing.PlayerUnfollowDialogButtonHeight)
      .clip(shape)
      .background(surfaceColor)
      .onFocusChanged { state -> focused = state.isFocused }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> onMoveLeft()
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> onMoveRight()
          event.type == KeyEventType.KeyUp && event.key.isConfirmKey() -> {
            onActivate()
            true
          }
          else -> false
        }
      }
      .focusable()
      .padding(horizontal = BiliSpacing.Md),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = textColor,
      fontSize = BiliTypography.Body,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

private fun Key.isConfirmKey(): Boolean {
  return this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
}

private fun formatSpaceCount(count: Long): String {
  return when {
    count >= 100_000_000L -> "%.1f亿".format(count / 100_000_000.0)
    count >= 10_000L -> "%.1f万".format(count / 10_000.0)
    else -> count.toString()
  }
}