package com.kirin.mt.ui.space

import android.util.Log
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.image.buildOwnerAvatarRequest
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeChannelStore
import com.kirin.mt.core.youtube.YoutubeRepository
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.common.VideoGridSkeleton
import com.kirin.mt.ui.common.appendUniqueByBvid
import com.kirin.mt.ui.common.focusRestoreKey
import com.kirin.mt.ui.common.resolveFocusIndex
import com.kirin.mt.ui.home.TvVideoGrid
import com.kirin.mt.ui.home.VideoCardMode
import com.kirin.mt.ui.home.GridFooterState
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

/**
 * TV 版 YouTube 频道主页。镜像 [UpSpaceScreen] 的覆盖层 + D-pad 焦点恢复范式，
 * 数据层复用 [YoutubeRepository]（resolveChannel + getChannelVideos continuation 分页），
 * 关注写入 [YoutubeChannelStore]（免登录）。点视频起播，卡片 owner 点击留在本频道。
 *
 * 头部为"基础"档（头像 + 频道名 + 关注 Chip），无 B 站空间的排序/签名/粉丝/等级/直播。
 */
@Composable
internal fun YoutubeChannelScreen(
  request: YoutubeChannelRequest,
  youtubeRepository: YoutubeRepository,
  youtubeChannelStore: YoutubeChannelStore,
  uiState: YoutubeChannelUiState,
  firstItemFocusRequester: FocusRequester,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onBack: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
) {
  val coroutineScope = rememberCoroutineScope()
  val channelId = request.channelId
  val channels by youtubeChannelStore.channels.collectAsState(initial = emptyList())
  val followed = channels.any { it.channelId == channelId }
  val followFocusRequester = remember { FocusRequester() }

  BackHandler { onBack() }

  // 首屏：解析权威频道名 + 头像（失败回退 request 值）+ 拉第一页。
  // 同 channelId + retryKey 已加载过则跳过（从播放器返回复用列表，仅清可能卡住的 loadingMore）。
  LaunchedEffect(channelId, uiState.retryKey) {
    if (uiState.loadedChannelId == channelId && uiState.loadedRetryKey == uiState.retryKey &&
      uiState.videoState !is ChannelVideoState.Loading
    ) {
      val success = uiState.videoState as? ChannelVideoState.Success
      if (success != null) uiState.videoState = success.copy(loadingMore = false)
      return@LaunchedEffect
    }
    uiState.name = request.channelName
    uiState.avatar = request.avatar
    val resolved = runCatching { youtubeRepository.resolveChannel(channelId) }.getOrNull()
    uiState.name = resolved?.name?.takeIf { it.isNotBlank() } ?: request.channelName
    uiState.avatar = resolved?.avatar?.takeIf { it.isNotBlank() } ?: request.avatar
    // 诊断:确认频道页实际用的 channelId + resolveChannel 是否成功(成功→header 来自权威解析,
    // 失败→header 回退 request 值,可据此判断 channelId 是否合法)。
    Log.d(
      "YoutubeChannel",
      "channel open channelId=[$channelId] reqName=[${request.channelName}] " +
        "resolved=${resolved?.let { "ok name=${it.name}" } ?: "FAILED"}",
    )
    uiState.focusedVideoIndex = 0
    uiState.focusedVideoKey = ""
    uiState.videoState = ChannelVideoState.Loading
    uiState.videoState = try {
      val page = youtubeRepository.getChannelVideos(channelId)
      if (page.items.isEmpty()) {
        ChannelVideoState.Empty
      } else {
        ChannelVideoState.Success(
          videos = page.items.distinctBy { it.bvid },
          continuation = page.continuation,
          loadingMore = false,
          endReached = page.continuation == null,
          loadMoreError = "",
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ChannelVideoState.Failed(e.message.orEmpty().ifBlank { "加载失败" })
    }
    uiState.loadedChannelId = channelId
    uiState.loadedRetryKey = uiState.retryKey
  }

  // 首屏到达后自动聚焦第一个视频卡片（仅初次打开）。
  LaunchedEffect(uiState.videoState, uiState.focusFirstVideo) {
    val success = uiState.videoState as? ChannelVideoState.Success
    if (success != null && uiState.focusFirstVideo && success.videos.isNotEmpty()) {
      withFrameNanos { }
      runCatching { firstItemFocusRequester.requestFocus() }
      uiState.focusFirstVideo = false
    }
  }

  fun loadMore() {
    val current = uiState.videoState as? ChannelVideoState.Success ?: return
    if (current.loadingMore || current.endReached) return
    val token = current.continuation ?: return
    uiState.videoState = current.copy(loadingMore = true, loadMoreError = "")
    coroutineScope.launch {
      uiState.videoState = try {
        val page = youtubeRepository.getChannelVideos(channelId, token)
        val latest = uiState.videoState as? ChannelVideoState.Success ?: return@launch
        val merged = latest.videos.appendUniqueByBvid(page.items)
        latest.copy(
          videos = merged,
          continuation = page.continuation,
          loadingMore = false,
          endReached = page.continuation == null || merged.size == latest.videos.size,
          loadMoreError = "",
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val latest = uiState.videoState as? ChannelVideoState.Success ?: return@launch
        latest.copy(loadingMore = false, loadMoreError = e.message.orEmpty())
      }
    }
  }

  fun toggleFollow() {
    if (uiState.followLoading) return
    uiState.followLoading = true
    coroutineScope.launch {
      if (followed) {
        youtubeChannelStore.remove(channelId)
      } else {
        youtubeChannelStore.add(YoutubeChannel(channelId = channelId, name = uiState.name, avatar = uiState.avatar))
      }
      uiState.followLoading = false
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(BiliColors.VideoBlack),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      YoutubeChannelHeader(
        channelId = channelId,
        name = uiState.name.ifBlank { request.channelName },
        avatar = uiState.avatar,
        followed = followed,
        followLoading = uiState.followLoading,
        followFocusRequester = followFocusRequester,
        firstItemFocusRequester = firstItemFocusRequester,
        onFollowClicked = ::toggleFollow,
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = BiliSpacing.Lg),
      ) {
        when (val state = uiState.videoState) {
          ChannelVideoState.Loading -> VideoGridSkeleton()
          ChannelVideoState.Empty -> FeedStatusScreen(message = stringResource(R.string.youtube_channel_no_videos))
          is ChannelVideoState.Failed -> FeedStatusScreen(
            message = stringResource(R.string.youtube_channel_videos_failed, state.message),
            actionLabel = stringResource(R.string.action_retry),
            onAction = { uiState.retryKey += 1 },
          )
          is ChannelVideoState.Success -> {
            // 频道页视频（channelId/owner 为空）统一注入本频道 id + 名 + 头像，保证卡片 owner
            // 点击留在本频道、头像显示本频道头像（lockupViewModel 不带 channelAvatarUrl）。
            val displayItems = state.videos.map { video ->
              video.copy(
                channelId = if (video.channelId.isBlank()) channelId else video.channelId,
                ownerName = if (video.ownerName.isBlank()) uiState.name else video.ownerName,
                ownerFace = if (video.ownerFace.isBlank()) uiState.avatar else video.ownerFace,
              )
            }
            TvVideoGrid(
              videos = displayItems,
              debugLabel = "channel-grid",
              firstItemFocusRequester = firstItemFocusRequester,
              restoredFocusIndex = displayItems.resolveFocusIndex(uiState.focusedVideoKey, uiState.focusedVideoIndex),
              restoreFocusRequestKey = restoreFocusRequestKey,
              onRestoreFocusHandled = onRestoreFocusHandled,
              onFocusedIndexChange = { index, video ->
                uiState.focusedVideoIndex = index
                uiState.focusedVideoKey = video.focusRestoreKey()
              },
              onLoadMore = ::loadMore,
              onMoveLeftToNav = { true },
              onMoveUpFromFirstRow = {
                runCatching { followFocusRequester.requestFocus() }.isSuccess
              },
              onBackKey = { onBack() },
              onVideoSelected = onVideoSelected,
              onOwnerSelected = { }, // 留在本频道：卡片 owner 点击不跳转
              cardMode = VideoCardMode.Standard,
              footer = when {
                state.loadMoreError.isNotBlank() -> GridFooterState.Error(state.loadMoreError)
                state.loadingMore -> GridFooterState.Loading
                state.endReached -> GridFooterState.EndReached
                else -> GridFooterState.None
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun YoutubeChannelHeader(
  channelId: String,
  name: String,
  avatar: String,
  followed: Boolean,
  followLoading: Boolean,
  followFocusRequester: FocusRequester,
  firstItemFocusRequester: FocusRequester,
  onFollowClicked: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val context = LocalContext.current
  val performancePolicy = LocalBiliPerformancePolicy.current
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
      AsyncImage(
        model = buildOwnerAvatarRequest(
          context = context,
          url = avatar,
          allowRgb565 = performancePolicy.ownerAvatarRgb565Enabled,
          memoryCacheEnabled = performancePolicy.imageMemoryCacheEnabled,
        ),
        contentDescription = name,
        modifier = Modifier
          .size(64.dp)
          .clip(CircleShape),
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(BiliColors.SurfaceElevated),
        error = ColorPainter(BiliColors.SurfaceElevated),
      )
      Text(
        text = name.ifBlank { channelId },
        color = BiliColors.TextPrimary,
        fontSize = BiliTypography.ScreenTitle,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      YoutubeChannelFollowChip(
        followed = followed,
        followLoading = followLoading,
        modifier = Modifier.focusRequester(followFocusRequester),
        onActivate = onFollowClicked,
        onMoveDown = {
          runCatching { firstItemFocusRequester.requestFocus() }.isSuccess
        },
        onMoveLeft = { false },
      )
    }
  }
}

@Composable
private fun YoutubeChannelFollowChip(
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
    followLoading -> stringResource(R.string.youtube_channel_resolving)
    followed -> stringResource(R.string.youtube_channel_following)
    else -> stringResource(R.string.youtube_channel_follow)
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
      fontWeight = if (followed || focused) FontWeight.Bold else FontWeight.Medium,
      textAlign = TextAlign.Center,
      maxLines = 1,
    )
  }
}

private fun Key.isConfirmKey(): Boolean {
  return this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
}