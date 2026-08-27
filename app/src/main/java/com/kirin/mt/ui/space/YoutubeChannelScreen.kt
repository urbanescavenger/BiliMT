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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.kirin.mt.core.youtube.YoutubeConstants
import com.kirin.mt.core.youtube.YoutubeParsers
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
 * 数据层复用 [YoutubeRepository]（getChannelHeader + getChannelVideos/getChannelPlaylists
 * continuation 分页），关注写入 [YoutubeChannelStore]（免登录）。点视频起播，卡片 owner 点击留在本频道。
 *
 * 2026-08-27 对齐移动端加内容 tab（主页/Shorts/直播/播放列表,tab chip 聚焦只高亮、OK 才切换）；
 * 视频 tab(TV 刻意)保留网格 + 「▶ 播放全部」+ 最新发布/最多播放排序(仅主页 tab 显示);播放列表 tab 走
 * [ChannelPlaylistGrid] 焦点卡片网格,OK 进 [YoutubePlaylistDetailScreen](AppShell 覆盖层)。
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
  onOpenPlaylist: (YoutubeParsers.YoutubePlaylist) -> Unit = {},
  onPlayAll: (List<VideoSummary>) -> Unit = {},
) {
  val coroutineScope = rememberCoroutineScope()
  val channelId = request.channelId
  val channels by youtubeChannelStore.channels.collectAsState(initial = emptyList())
  val followed = channels.any { it.channelId == channelId }
  val followFocusRequester = remember { FocusRequester() }
  val sortFocusRequesters = remember {
    YoutubeConstants.ChannelVideoOrder.entries.associateWith { FocusRequester() }
  }
  val tabFocusRequesters = remember {
    YoutubeConstants.ChannelContentTab.entries.associateWith { FocusRequester() }
  }
  val playlistFirstItemFocusRequester = remember { FocusRequester() }

  BackHandler { onBack() }

  // 当前 tab 的内容 /browse params:主页用排序(最新发布/最多播放)params;Shorts/直播/播放列表
  // 优先用服务端 tab params(对齐移动端 channelParams,硬编码兜底)。
  fun channelParams(): String {
    if (uiState.tab.hasSort) return uiState.order.params
    val keys = when (uiState.tab) {
      YoutubeConstants.ChannelContentTab.Videos -> listOf("videos")
      YoutubeConstants.ChannelContentTab.Shorts -> listOf("shorts")
      YoutubeConstants.ChannelContentTab.Live -> listOf("streams", "live")
      YoutubeConstants.ChannelContentTab.Playlists -> listOf("playlists")
    }
    return keys.firstNotNullOfOrNull { uiState.serverTabParams[it] } ?: uiState.tab.params
  }

  fun videoStateFor(tab: YoutubeConstants.ChannelContentTab): ChannelVideoState = when (tab) {
    YoutubeConstants.ChannelContentTab.Shorts -> uiState.shortsState
    YoutubeConstants.ChannelContentTab.Live -> uiState.liveState
    else -> uiState.videoState
  }

  fun setVideoStateFor(tab: YoutubeConstants.ChannelContentTab, state: ChannelVideoState) {
    when (tab) {
      YoutubeConstants.ChannelContentTab.Shorts -> uiState.shortsState = state
      YoutubeConstants.ChannelContentTab.Live -> uiState.liveState = state
      else -> uiState.videoState = state
    }
  }

  // 频道权威信息(名/头像/订阅数/简介/banner/服务端 tab params):仅 channelId 变化时解析一次,
  // 失败回退 request 值 + resolveChannel(对齐移动端两段式)。
  LaunchedEffect(channelId) {
    uiState.name = request.channelName
    uiState.avatar = request.avatar
    val header = runCatching { youtubeRepository.getChannelHeader(channelId) }.getOrNull()
    if (header != null) {
      uiState.name = header.name.ifBlank { request.channelName }
      uiState.avatar = header.avatarUrl.ifBlank { request.avatar }
      // 记录服务端内容 tab params,切 Shorts/直播/播放列表用(硬编码 params 对部分频道失效)。
      uiState.serverTabParams = header.tabs.map { it.name.lowercase() to it.params }.toMap()
      Log.d(
        "YoutubeChannel",
        "channel open channelId=[$channelId] header=ok name=${header.name} " +
          "tabs=${uiState.serverTabParams.keys.joinToString(",")}",
      )
    } else {
      val resolved = runCatching { youtubeRepository.resolveChannel(channelId) }.getOrNull()
      uiState.name = resolved?.name?.takeIf { it.isNotBlank() } ?: request.channelName
      uiState.avatar = resolved?.avatar?.takeIf { it.isNotBlank() } ?: request.avatar
      uiState.serverTabParams = runCatching { youtubeRepository.getChannelTabs(channelId) }
        .getOrDefault(emptyMap())
      // 诊断:header 解析失败走 resolveChannel 回退,tab params 可能拿不到(回退硬编码 params)。
      Log.d(
        "YoutubeChannel",
        "channel open channelId=[$channelId] header=FAILED " +
          "resolved=${resolved?.let { "ok name=${it.name}" } ?: "FAILED"} " +
          "tabs=${uiState.serverTabParams.keys.joinToString(",")}",
      )
    }
  }

  // 首屏/切排序/切 tab:拉对应内容。同 channelId+retryKey+排序+tab 已加载过则跳过(从播放器
  // 返回复用列表,仅清可能卡住的 loadingMore);切排序/切 tab 强制重拉。
  LaunchedEffect(channelId, uiState.order, uiState.tab, uiState.retryKey) {
    if (uiState.loadedChannelId == channelId && uiState.loadedRetryKey == uiState.retryKey &&
      uiState.loadedOrder == uiState.order && uiState.loadedTab == uiState.tab
    ) {
      when (val state = videoStateFor(uiState.tab)) {
        is ChannelVideoState.Success -> setVideoStateFor(uiState.tab, state.copy(loadingMore = false))
        else -> Unit
      }
      val playlistSuccess = uiState.playlistState as? ChannelPlaylistState.Success
      if (playlistSuccess != null && uiState.tab == YoutubeConstants.ChannelContentTab.Playlists) {
        uiState.playlistState = playlistSuccess.copy(loadingMore = false)
      }
      return@LaunchedEffect
    }
    uiState.focusedVideoIndex = 0
    uiState.focusedVideoKey = ""
    uiState.focusedPlaylistIndex = 0
    uiState.focusedPlaylistKey = ""
    val tab = uiState.tab
    if (tab == YoutubeConstants.ChannelContentTab.Playlists) {
      uiState.playlistState = ChannelPlaylistState.Loading
      uiState.playlistState = try {
        val page = youtubeRepository.getChannelPlaylists(channelId, params = channelParams())
        if (page.items.isEmpty()) {
          ChannelPlaylistState.Empty
        } else {
          ChannelPlaylistState.Success(
            playlists = page.items.distinctBy { it.id },
            continuation = page.continuation,
            loadingMore = false,
            endReached = page.continuation == null,
            loadMoreError = "",
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        ChannelPlaylistState.Failed(e.message.orEmpty().ifBlank { "加载失败" })
      }
      uiState.focusFirstVideo = true
    } else {
      setVideoStateFor(tab, ChannelVideoState.Loading)
      val state = try {
        val page = youtubeRepository.getChannelVideos(channelId, params = channelParams())
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
      setVideoStateFor(tab, state)
      uiState.focusFirstVideo = true
    }
    uiState.loadedChannelId = channelId
    uiState.loadedRetryKey = uiState.retryKey
    uiState.loadedOrder = uiState.order
    uiState.loadedTab = uiState.tab
  }

  // 首屏到达后自动聚焦第一个卡片(仅初次打开;播放列表 tab 聚焦第一张卡)。
  LaunchedEffect(uiState.videoState, uiState.shortsState, uiState.liveState, uiState.playlistState, uiState.focusFirstVideo) {
    if (!uiState.focusFirstVideo) return@LaunchedEffect
    when (uiState.tab) {
      YoutubeConstants.ChannelContentTab.Playlists -> {
        val success = uiState.playlistState as? ChannelPlaylistState.Success
        if (success != null && success.playlists.isNotEmpty()) {
          withFrameNanos { }
          runCatching { playlistFirstItemFocusRequester.requestFocus() }
          uiState.focusFirstVideo = false
        }
      }
      else -> {
        val success = videoStateFor(uiState.tab) as? ChannelVideoState.Success
        if (success != null && success.videos.isNotEmpty()) {
          withFrameNanos { }
          runCatching { firstItemFocusRequester.requestFocus() }
          uiState.focusFirstVideo = false
        }
      }
    }
  }

  fun loadMore() {
    val tab = uiState.tab
    if (tab == YoutubeConstants.ChannelContentTab.Playlists) {
      val current = uiState.playlistState as? ChannelPlaylistState.Success ?: return
      if (current.loadingMore || current.endReached) return
      val token = current.continuation ?: return
      val params = channelParams()
      uiState.playlistState = current.copy(loadingMore = true, loadMoreError = "")
      coroutineScope.launch {
        uiState.playlistState = try {
          val page = youtubeRepository.getChannelPlaylists(channelId, token, params = params)
          val latest = uiState.playlistState as? ChannelPlaylistState.Success ?: return@launch
          val merged = (latest.playlists + page.items).distinctBy { it.id }
          latest.copy(
            playlists = merged,
            continuation = page.continuation,
            loadingMore = false,
            endReached = page.continuation == null || merged.size == latest.playlists.size,
            loadMoreError = "",
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          val latest = uiState.playlistState as? ChannelPlaylistState.Success ?: return@launch
          latest.copy(loadingMore = false, loadMoreError = e.message.orEmpty())
        }
      }
      return
    }
    val current = videoStateFor(tab) as? ChannelVideoState.Success ?: return
    if (current.loadingMore || current.endReached) return
    val token = current.continuation ?: return
    val params = channelParams()
    setVideoStateFor(tab, current.copy(loadingMore = true, loadMoreError = ""))
    coroutineScope.launch {
      val nextState = try {
        val page = youtubeRepository.getChannelVideos(channelId, token, params = params)
        val latest = videoStateFor(tab) as? ChannelVideoState.Success ?: return@launch
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
        val latest = videoStateFor(tab) as? ChannelVideoState.Success ?: return@launch
        latest.copy(loadingMore = false, loadMoreError = e.message.orEmpty())
      }
      setVideoStateFor(tab, nextState)
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

  /** 频道页视频（channelId/owner 为空）统一注入本频道 id + 名 + 头像，卡片 owner 点击留在本频道。 */
  fun displayItemsFor(state: ChannelVideoState.Success): List<VideoSummary> = state.videos.map { video ->
    video.copy(
      channelId = if (video.channelId.isBlank()) channelId else video.channelId,
      ownerName = if (video.ownerName.isBlank()) uiState.name else video.ownerName,
      ownerFace = if (video.ownerFace.isBlank()) uiState.avatar else video.ownerFace,
    )
  }

  // 「▶ 播放全部」(仅主页 tab):整份已加载视频作连播队列,第一条起播(对齐移动端主页 tab)。
  fun playAllCurrent() {
    val state = videoStateFor(uiState.tab) as? ChannelVideoState.Success ?: return
    if (state.videos.isEmpty()) return
    onPlayAll(displayItemsFor(state))
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
        playlistFirstItemFocusRequester = playlistFirstItemFocusRequester,
        tab = uiState.tab,
        tabFocusRequesters = tabFocusRequesters,
        onTabSelected = { uiState.tab = it },
        order = uiState.order,
        sortFocusRequesters = sortFocusRequesters,
        onOrderSelected = { uiState.order = it },
        onPlayAll = ::playAllCurrent,
        onFollowClicked = ::toggleFollow,
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = BiliSpacing.Lg),
      ) {
        if (uiState.tab == YoutubeConstants.ChannelContentTab.Playlists) {
          // 播放列表 tab:焦点网格卡片(封面+标题+视频数),OK 打开播放列表详情页。
          when (val state = uiState.playlistState) {
            ChannelPlaylistState.Loading -> VideoGridSkeleton()
            ChannelPlaylistState.Empty -> FeedStatusScreen(message = "暂无播放列表")
            is ChannelPlaylistState.Failed -> FeedStatusScreen(
              message = state.message,
              actionLabel = stringResource(R.string.action_retry),
              onAction = { uiState.retryKey += 1 },
            )
            is ChannelPlaylistState.Success -> ChannelPlaylistGrid(
              playlists = state.playlists,
              loadingMore = state.loadingMore,
              endReached = state.endReached,
              loadMoreError = state.loadMoreError,
              firstItemFocusRequester = playlistFirstItemFocusRequester,
              restoreFocusRequestKey = restoreFocusRequestKey,
              onRestoreFocusHandled = onRestoreFocusHandled,
              focusedIndex = uiState.focusedPlaylistIndex,
              focusedKey = uiState.focusedPlaylistKey,
              onFocusedIndexChange = { index, playlist ->
                uiState.focusedPlaylistIndex = index
                uiState.focusedPlaylistKey = playlist.id
              },
              onLoadMore = ::loadMore,
              onBackKey = { onBack() },
              onPlaylistSelected = onOpenPlaylist,
            )
          }
        } else {
          // 主页/Shorts/直播 tab:视频保持网格(TV 端刻意保留,对齐用户要求)。
          when (val state = videoStateFor(uiState.tab)) {
            ChannelVideoState.Loading -> VideoGridSkeleton()
            ChannelVideoState.Empty -> FeedStatusScreen(message = stringResource(R.string.youtube_channel_no_videos))
            is ChannelVideoState.Failed -> FeedStatusScreen(
              message = stringResource(R.string.youtube_channel_videos_failed, state.message),
              actionLabel = stringResource(R.string.action_retry),
              onAction = { uiState.retryKey += 1 },
            )
            is ChannelVideoState.Success -> {
              // 频道页视频统一注入本频道信息(卡片 owner 点击留在本频道、头像显示本频道头像)。
              val displayItems = displayItemsFor(state)
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
                  if (uiState.tab.hasSort) {
                    runCatching { sortFocusRequesters.getValue(uiState.order).requestFocus() }.isSuccess
                  } else {
                    runCatching { tabFocusRequesters.getValue(uiState.tab).requestFocus() }.isSuccess
                  }
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
  playlistFirstItemFocusRequester: FocusRequester,
  tab: YoutubeConstants.ChannelContentTab,
  tabFocusRequesters: Map<YoutubeConstants.ChannelContentTab, FocusRequester>,
  onTabSelected: (YoutubeConstants.ChannelContentTab) -> Unit,
  order: YoutubeConstants.ChannelVideoOrder,
  sortFocusRequesters: Map<YoutubeConstants.ChannelVideoOrder, FocusRequester>,
  onOrderSelected: (YoutubeConstants.ChannelVideoOrder) -> Unit,
  onPlayAll: () -> Unit,
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
          // 下移进内容 tab 行(2026-08-27 对齐移动端加 tab,排序行挂在 tab 之下)。
          runCatching { tabFocusRequesters.getValue(tab).requestFocus() }.isSuccess
        },
        onMoveLeft = { false },
      )
    }
    // 内容 tab 行:主页 / Shorts / 直播 / 播放列表(对齐移动端;聚焦只高亮,OK 才切换,避免
    // 焦点扫过时每个 tab 都触发重拉)。
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
    ) {
      YoutubeConstants.ChannelContentTab.entries.forEachIndexed { tabIndex, option ->
        val selected = tab == option
        YoutubeChannelTabChip(
          text = stringResource(
            when (option) {
              YoutubeConstants.ChannelContentTab.Videos -> R.string.youtube_channel_tab_videos
              YoutubeConstants.ChannelContentTab.Shorts -> R.string.youtube_channel_tab_shorts
              YoutubeConstants.ChannelContentTab.Live -> R.string.youtube_channel_tab_live
              YoutubeConstants.ChannelContentTab.Playlists -> R.string.youtube_channel_tab_playlists
            },
          ),
          selected = selected,
          modifier = Modifier.focusRequester(tabFocusRequesters.getValue(option)),
          onActivate = { onTabSelected(option) },
          onMoveUp = {
            runCatching { followFocusRequester.requestFocus() }.isSuccess
          },
          onMoveDown = {
            when (option) {
              YoutubeConstants.ChannelContentTab.Videos ->
                runCatching { sortFocusRequesters.getValue(order).requestFocus() }.isSuccess
              YoutubeConstants.ChannelContentTab.Playlists ->
                runCatching { playlistFirstItemFocusRequester.requestFocus() }.isSuccess
              else -> runCatching { firstItemFocusRequester.requestFocus() }.isSuccess
            }
          },
          // 左右也显式 requestFocus(实测默认焦点搜索在 tab 行丢失);行首/行尾消费按键不移动,
          // 防止焦点逃逸出频道页(曾跳到 AppShell 侧栏)。
          onMoveLeft = {
            val prev = YoutubeConstants.ChannelContentTab.entries.getOrNull(tabIndex - 1)
            if (prev != null) {
              runCatching { tabFocusRequesters.getValue(prev).requestFocus() }.isSuccess
            } else {
              true
            }
          },
          onMoveRight = {
            val next = YoutubeConstants.ChannelContentTab.entries.getOrNull(tabIndex + 1)
            if (next != null) {
              runCatching { tabFocusRequesters.getValue(next).requestFocus() }.isSuccess
            } else {
              true
            }
          },
        )
      }
    }
    // 排序栏:「▶ 播放全部」+ 最新发布 / 最多播放(对齐移动端主页 tab 头部行「播放全部+排序」)。
    // 排序聚焦选中即切换,切排序重新拉取;仅主页 tab 显示(Shorts/直播/播放列表无排序)。
    if (tab.hasSort) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
      ) {
        YoutubeChannelPlayAllChip(
          onActivate = onPlayAll,
          onMoveUp = {
            runCatching { tabFocusRequesters.getValue(tab).requestFocus() }.isSuccess
          },
          onMoveDown = {
            runCatching { firstItemFocusRequester.requestFocus() }.isSuccess
          },
        )
        YoutubeConstants.ChannelVideoOrder.entries.forEach { option ->
          val selected = order == option
          val titleRes = when (option) {
            YoutubeConstants.ChannelVideoOrder.Latest -> R.string.player_up_sort_latest
            YoutubeConstants.ChannelVideoOrder.Popular -> R.string.player_up_sort_hot
          }
          YoutubeChannelSortChip(
            text = stringResource(titleRes),
            selected = selected,
            modifier = Modifier.focusRequester(sortFocusRequesters.getValue(option)),
            onActivate = { onOrderSelected(option) },
            onFocused = { if (!selected) onOrderSelected(option) },
            onMoveUp = {
              // 上移回内容 tab 行当前 tab(不处理 Up 会交给默认焦点搜索,实测丢失焦点)。
              runCatching { tabFocusRequesters.getValue(tab).requestFocus() }.isSuccess
            },
            onMoveDown = {
              runCatching { firstItemFocusRequester.requestFocus() }.isSuccess
            },
          )
        }
      }
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

@Composable
private fun YoutubeChannelSortChip(
  text: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onActivate: () -> Unit,
  onFocused: () -> Unit,
  onMoveUp: () -> Boolean,
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
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> onMoveUp()
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

/**
 * 「▶ 播放全部」chip(主页 tab 排序行最前,对齐移动端):OK 把已加载视频整份作连播队列起播。
 * 聚焦不触发任何选择(区别于排序 chip 的聚焦即切换);行首 Left 消费不移动防焦点逃逸出频道页。
 */
@Composable
private fun YoutubeChannelPlayAllChip(
  modifier: Modifier = Modifier,
  onActivate: () -> Unit,
  onMoveUp: () -> Boolean,
  onMoveDown: () -> Boolean,
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
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> true
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
 * 内容 tab chip(主页/Shorts/直播/播放列表):聚焦只高亮不切换(避免焦点扫过逐个重拉),
 * OK 键才切换 tab。上下键接头部/内容焦点链。
 */
@Composable
private fun YoutubeChannelTabChip(
  text: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onActivate: () -> Unit,
  onMoveUp: () -> Boolean,
  onMoveDown: () -> Boolean,
  onMoveLeft: () -> Boolean = { false },
  onMoveRight: () -> Boolean = { false },
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

/** 频道"播放列表" tab 的卡片网格:封面 + 标题(2行) + 视频数,D-pad 焦点 + 近底自动翻页。 */
@Composable
private fun ChannelPlaylistGrid(
  playlists: List<YoutubeParsers.YoutubePlaylist>,
  loadingMore: Boolean,
  endReached: Boolean,
  loadMoreError: String,
  firstItemFocusRequester: FocusRequester,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  focusedIndex: Int,
  focusedKey: String,
  onFocusedIndexChange: (Int, YoutubeParsers.YoutubePlaylist) -> Unit,
  onLoadMore: () -> Unit,
  onBackKey: () -> Boolean,
  onPlaylistSelected: (YoutubeParsers.YoutubePlaylist) -> Unit,
) {
  val restoreFocusRequester = remember { FocusRequester() }
  // 从播放列表详情页/播放器返回时按记录索引恢复焦点(镜像 TvVideoGrid 的 restore 范式)。
  LaunchedEffect(restoreFocusRequestKey) {
    if (restoreFocusRequestKey != 0 && playlists.isNotEmpty()) {
      withFrameNanos { }
      runCatching { restoreFocusRequester.requestFocus() }
      onRestoreFocusHandled(restoreFocusRequestKey)
    }
  }
  LazyVerticalGrid(
    columns = GridCells.Fixed(4),
    contentPadding = PaddingValues(
      horizontal = BiliSizing.VideoGridHorizontalPadding,
      vertical = BiliSpacing.Lg,
    ),
    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
    modifier = Modifier.fillMaxSize(),
  ) {
    itemsIndexed(playlists) { index, playlist ->
      val requester = when {
        restoreFocusRequestKey != 0 && (playlist.id == focusedKey || index == focusedIndex) -> restoreFocusRequester
        index == 0 -> firstItemFocusRequester
        else -> null
      }
      ChannelPlaylistCard(
        playlist = playlist,
        modifier = (if (requester != null) Modifier.focusRequester(requester) else Modifier),
        onFocused = {
          onFocusedIndexChange(index, playlist)
          // 聚焦到近底卡片即触发续页(对齐视频网格 onLoadMore 行为)。
          if (index >= playlists.size - 4) onLoadMore()
        },
        onActivate = { onPlaylistSelected(playlist) },
      )
    }
    if (loadingMore) {
      item {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(BiliSpacing.Lg),
          contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
      }
    }
    if (loadMoreError.isNotBlank()) {
      item {
        Text(
          text = loadMoreError,
          color = BiliColors.TextSecondary,
          fontSize = BiliTypography.CardMeta,
          modifier = Modifier.padding(BiliSpacing.Lg),
        )
      }
    }
    if (endReached && playlists.isNotEmpty() && !loadingMore && loadMoreError.isBlank()) {
      item {
        Text(
          text = "没有更多了",
          color = BiliColors.TextSecondary,
          fontSize = BiliTypography.CardMeta,
          textAlign = TextAlign.Center,
          modifier = Modifier
            .fillMaxWidth()
            .padding(BiliSpacing.Lg),
        )
      }
    }
  }
}

/**
 * 播放列表网格卡:封面(16:9) + 标题(2行) + 视频数文案(解析自缩略图角标,如 "141 个视频")。
 * 封面取自 parseChannelPlaylists(含 collectionThumbnailViewModel 新路径)。
 */
@Composable
private fun ChannelPlaylistCard(
  playlist: YoutubeParsers.YoutubePlaylist,
  modifier: Modifier = Modifier,
  onFocused: () -> Unit,
  onActivate: () -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(8.dp)
  Column(
    modifier = modifier
      .clip(shape)
      .border(
        BorderStroke(BiliFocus.BorderWidth, if (focused) homeColors.accent else BiliColors.Transparent),
        shape,
      )
      .onFocusChanged { state ->
        focused = state.isFocused
        if (state.isFocused) onFocused()
      }
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp && event.key.isConfirmKey()) {
          onActivate()
          true
        } else {
          false
        }
      }
      .focusable()
      .padding(BiliSpacing.Xs),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .clip(shape)
        .background(BiliColors.SurfaceElevated),
    ) {
      if (playlist.thumbnail.isNotBlank()) {
        AsyncImage(
          model = playlist.thumbnail,
          contentDescription = playlist.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Text("▶", color = BiliColors.TextSecondary, fontSize = BiliTypography.ScreenTitle)
        }
      }
    }
    Text(
      text = playlist.title,
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.CardTitle,
      lineHeight = BiliTypography.CardTitleLineHeight,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = BiliSpacing.Xs),
    )
    if (playlist.videoCount.isNotBlank()) {
      Text(
        text = playlist.videoCount,
        color = BiliColors.TextSecondary,
        fontSize = BiliTypography.CardMeta,
        maxLines = 1,
        modifier = Modifier.padding(top = BiliSpacing.Xs),
      )
    }
  }
}

private fun Key.isConfirmKey(): Boolean {
  return this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
}