package com.kirin.mt.ui.feed

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubeHistoryEntry
import com.kirin.mt.core.youtube.resolveChannelAvatarUrl
import com.kirin.mt.core.youtube.resolveThumbnailUrl
import com.kirin.mt.core.network.FollowingSeason
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.network.mergeByPubdate
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.ui.common.BiliActionItem
import com.kirin.mt.ui.common.BiliActionSheet
import com.kirin.mt.ui.common.BiliCapsuleTabRow
import com.kirin.mt.ui.common.BiliPillTab
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.common.VideoGridSkeleton
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.home.TvVideoGrid
import com.kirin.mt.ui.home.GridFooterState
import com.kirin.mt.ui.home.VideoCard
import com.kirin.mt.ui.home.VideoCardMode
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliMotion
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class UserFeedTab { DynamicVideo, DynamicAll, History, Favorite, Bangumi }

internal enum class BangumiFollowType(val id: Int, val labelRes: Int) {
  Bangumi(id = 1, labelRes = R.string.bangumi_type_bangumi),
  Cinema(id = 2, labelRes = R.string.bangumi_type_cinema),
}

internal enum class BangumiFollowStatus(val id: Int, val labelRes: Int) {
  All(id = 0, labelRes = R.string.bangumi_status_all),
  Want(id = 1, labelRes = R.string.bangumi_status_want),
  Watching(id = 2, labelRes = R.string.bangumi_status_watching),
  Watched(id = 3, labelRes = R.string.bangumi_status_watched),
}

@Stable
internal class BangumiFollowUiState {
  var selectedType by mutableStateOf(BangumiFollowType.Bangumi)
  var selectedStatus by mutableStateOf(BangumiFollowStatus.All)
  var currentPage by mutableIntStateOf(0)
  var state by mutableStateOf<UserFeedState>(UserFeedState.Loading)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
  var hasLoadedContent by mutableStateOf(false)
  var loadedOnce by mutableStateOf(false)
  var handledManualRefreshKey by mutableIntStateOf(0)
  var focusRestoredItemKey by mutableIntStateOf(0)
}

@Stable
internal class DynamicFeedUiState {
  var nextOffset by mutableStateOf("")
  var state by mutableStateOf<UserFeedState>(UserFeedState.Loading)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
  var hasLoadedContent by mutableStateOf(false)
  var loadedOnce by mutableStateOf(false)
  // 首次进入时 youtubeChannels 还是 emptyList(store 未发),首载只拉 B 站并置 loadedOnce=true;
  // 等频道发出来后再重启 LaunchedEffect 时,loadedOnce 会挡住 YouTube 合并 → 初始空、手动刷新才出。
  // 用该标志区分「频道首次就绪需补拉 YouTube」与「后续头像回填等频道变化(不应重载)」。
  var youtubeMerged by mutableStateOf(false)
  var handledManualRefreshKey by mutableIntStateOf(0)
  var focusRestoredItemKey by mutableIntStateOf(0)
}

@Stable
internal class HistoryFeedUiState {
  var nextViewAt by mutableStateOf(0L)
  var nextMax by mutableStateOf(0L)
  var state by mutableStateOf<UserFeedState>(UserFeedState.Loading)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
  var hasLoadedContent by mutableStateOf(false)
  var loadedOnce by mutableStateOf(false)
  var handledManualRefreshKey by mutableIntStateOf(0)
  var focusRestoredItemKey by mutableIntStateOf(0)
}

@Stable
internal class FavoriteFeedUiState {
  var folders by mutableStateOf<List<com.kirin.mt.core.network.FavoriteFolder>>(emptyList())
  var currentFolderMediaId by mutableStateOf(0L)
  var currentPage by mutableIntStateOf(0)
  var currentOrder by mutableStateOf("mtime")
  var state by mutableStateOf<UserFeedState>(UserFeedState.Loading)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
  var hasLoadedContent by mutableStateOf(false)
  var foldersLoaded by mutableStateOf(false)
  var handledManualRefreshKey by mutableIntStateOf(0)
  var focusRestoredItemKey by mutableIntStateOf(0)
}

@Stable
internal class UserFeedUiState {
  var selectedTab by mutableStateOf(UserFeedTab.DynamicVideo)
  val dynamicVideo = DynamicFeedUiState()
  val dynamicAll = DynamicFeedUiState()
  val history = HistoryFeedUiState()
  val favorite = FavoriteFeedUiState()
  val bangumi = BangumiFollowUiState()
}

// 把指定子 tab 的聚焦位置清回顶部。切子 tab / 侧栏切回动态页时调用,
// 让重组后的网格从第 0 行起始、按 Down 进列表落第一行(不再停在旧位置 / 跳版面)。
private fun resetFeedTabFocus(feedState: UserFeedUiState, tab: UserFeedTab) {
  when (tab) {
    UserFeedTab.DynamicVideo -> {
      feedState.dynamicVideo.focusedVideoIndex = 0
      feedState.dynamicVideo.focusedVideoKey = ""
    }
    UserFeedTab.DynamicAll -> {
      feedState.dynamicAll.focusedVideoIndex = 0
      feedState.dynamicAll.focusedVideoKey = ""
    }
    UserFeedTab.History -> {
      feedState.history.focusedVideoIndex = 0
      feedState.history.focusedVideoKey = ""
    }
    UserFeedTab.Favorite -> {
      feedState.favorite.focusedVideoIndex = 0
      feedState.favorite.focusedVideoKey = ""
    }
    UserFeedTab.Bangumi -> {
      feedState.bangumi.focusedVideoIndex = 0
      feedState.bangumi.focusedVideoKey = ""
    }
  }
}

@Composable
internal fun UserFeedScreen(
  videoRepository: VideoRepository,
  youtubeChannelStore: com.kirin.mt.core.youtube.YoutubeChannelStore,
  youtubeHistoryStore: com.kirin.mt.core.youtube.YoutubeHistoryStore,
  isLoggedIn: Boolean,
  feedState: UserFeedUiState,
  autoRefreshOnSwitch: Boolean,
  manualRefreshKey: Int,
  firstItemFocusRequester: FocusRequester,
  tabFocusRequester: FocusRequester,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary, Boolean) -> Unit,
 onOwnerSelected: (VideoSummary) -> Unit = {},
 onCommentSelected: (VideoSummary) -> Unit = {},
 onSeasonSelected: (com.kirin.mt.core.network.FollowingSeason) -> Unit = {},
 onActionSheetDismissed: () -> Unit = {},
) {
  val coroutineScope = rememberCoroutineScope()
  val context = LocalContext.current
  val selectedTab = feedState.selectedTab
  var actionSheetVideo by remember { mutableStateOf<VideoSummary?>(null) }
  // 长按菜单内选中了会跳转的项(评论 / 去 UP 主主页)时置 true,onDismiss 据此跳过网格焦点恢复,
  // 避免焦点被抢到菜单底下隐藏的网格上;仅 Back / 点赞 / 稍后再看(留在网格)才恢复卡片焦点。
  var actionSheetNavigating by remember { mutableStateOf(false) }
  val youtubeChannels by youtubeChannelStore.channels.collectAsState(initial = emptyList())
  // 本地 YouTube 播放历史(免登录):合并进 History tab,与 B 站历史按播放时间倒序混合。
  val youtubeHistory by youtubeHistoryStore.history.collectAsState(initial = emptyList())
  // 频道头像回退:旧历史条目 channelAvatarUrl 为空,按 channelId 从 YoutubeChannelStore 补(动态 feed 已缓存)。
  val avatarByChannelId = remember(youtubeChannels) {
    youtubeChannels.associate { it.channelId to it.avatar }
  }
  val youtubeHistoryVideos = youtubeHistory.map {
    it.toVideoSummary(avatarFallback = avatarByChannelId[it.channelId].orEmpty())
  }

  // 侧栏切回动态页时 screen 重组但未切子 tab → onSelect 不触发,当前子 tab 的旧
  // focusedVideoIndex 仍在,从 tab 按 Down 走 focusRestoredItemKey 会跳到旧深位置。
  // 进入时(非视频恢复,restoreFocusRequestKey <= 0)把当前子 tab 重置回顶部。
  // 视频退出返回时 restoreFocusRequestKey > 0 → 不重置,沿用既有恢复。
  LaunchedEffect(Unit) {
    if (restoreFocusRequestKey <= 0) {
      resetFeedTabFocus(feedState, selectedTab)
    }
  }

  // key 用稳定 channelId 列表而非 youtubeChannels 全量:YouTube 拉取中 updateAvatar 回填会改
  // youtubeChannels(头像字段),若 key 用全量会重启 LaunchedEffect 取消在途拉取 → 无限重启循环。
  // channelId 不变则 key 稳定,头像回填不触发重启。
  val youtubeChannelIds = youtubeChannels.map { it.channelId }
  LaunchedEffect(videoRepository, isLoggedIn, autoRefreshOnSwitch, selectedTab, youtubeChannelIds) {
    // 统一动态「动态(视频)」合并了 YouTube 关注(免登录,手动配置频道);其余 tab 需要登录。
    if (!isLoggedIn && !(selectedTab == UserFeedTab.DynamicVideo && youtubeChannels.isNotEmpty())) {
      return@LaunchedEffect
    }
    when (selectedTab) {
      UserFeedTab.DynamicVideo -> loadDynamicFirstPage(
        videoRepository,
        feedState.dynamicVideo,
        "video",
        youtubeChannels,
        youtubeChannelStore,
        // 频道首次就绪(非空且尚未合并 YouTube)时强制补拉,把 YouTube 关注并进首载;
        // 之后频道变化(头像回填)不再强制,靠 loadedOnce 去重。
        forceRefresh = autoRefreshOnSwitch ||
          (youtubeChannels.isNotEmpty() && !feedState.dynamicVideo.youtubeMerged),
      )
      UserFeedTab.DynamicAll -> loadDynamicFirstPage(
        videoRepository,
        feedState.dynamicAll,
        "all",
        emptyList(),
        youtubeChannelStore,
        forceRefresh = autoRefreshOnSwitch,
      )
      UserFeedTab.History -> loadHistoryFirstPage(
        videoRepository,
        feedState.history,
        forceRefresh = autoRefreshOnSwitch,
      )
     UserFeedTab.Favorite -> loadFavoriteFolders(
       videoRepository,
       feedState.favorite,
      forceRefresh = autoRefreshOnSwitch,
     )
     UserFeedTab.Bangumi -> loadBangumiFirstPage(
       videoRepository,
       feedState.bangumi,
       forceRefresh = autoRefreshOnSwitch,
     )
    }
  }

  LaunchedEffect(manualRefreshKey) {
   if (!isLoggedIn && !(selectedTab == UserFeedTab.DynamicVideo && youtubeChannels.isNotEmpty())) {
     return@LaunchedEffect
   }
   val handledKey = when (selectedTab) {
     UserFeedTab.DynamicVideo -> feedState.dynamicVideo.handledManualRefreshKey
     UserFeedTab.DynamicAll -> feedState.dynamicAll.handledManualRefreshKey
     UserFeedTab.History -> feedState.history.handledManualRefreshKey
     UserFeedTab.Favorite -> feedState.favorite.handledManualRefreshKey
     UserFeedTab.Bangumi -> feedState.bangumi.handledManualRefreshKey
    }
    if (manualRefreshKey > 0 && manualRefreshKey != handledKey) {
      // 侧栏重点击"动态"(当前目的地)= 显式刷新当前子 tab。重置当前子 tab 焦点回顶,
      // 配合下方 key(selectedTab, manualRefreshKey) 重建网格(initialFirstVisibleItemIndex=0)
      // → 视口回顶;之后 Down(focusRestoredItemKey)落第 0 行、视频返回也回顶。
      resetFeedTabFocus(feedState, selectedTab)
      when (selectedTab) {
        UserFeedTab.DynamicVideo -> {
          feedState.dynamicVideo.handledManualRefreshKey = manualRefreshKey
          loadDynamicFirstPage(videoRepository, feedState.dynamicVideo, "video", youtubeChannels, youtubeChannelStore, forceRefresh = true)
        }
        UserFeedTab.DynamicAll -> {
          feedState.dynamicAll.handledManualRefreshKey = manualRefreshKey
          loadDynamicFirstPage(videoRepository, feedState.dynamicAll, "all", emptyList(), youtubeChannelStore, forceRefresh = true)
        }
        UserFeedTab.History -> {
          feedState.history.handledManualRefreshKey = manualRefreshKey
          loadHistoryFirstPage(videoRepository, feedState.history, forceRefresh = true)
        }
       UserFeedTab.Favorite -> {
         feedState.favorite.handledManualRefreshKey = manualRefreshKey
         loadFavoriteFolders(videoRepository, feedState.favorite, forceRefresh = true)
       }
       UserFeedTab.Bangumi -> {
         feedState.bangumi.handledManualRefreshKey = manualRefreshKey
         loadBangumiFirstPage(videoRepository, feedState.bangumi, forceRefresh = true)
       }
      }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    UserFeedTabRow(
      selectedTab = selectedTab,
      onSelect = { tab ->
        if (tab != selectedTab) {
          resetFeedTabFocus(feedState, tab)
          feedState.selectedTab = tab
        }
      },
      tabFocusRequester = tabFocusRequester,
      onMoveLeftToNav = onMoveLeftToNav,
      onMoveDownToGrid = {
        when (selectedTab) {
          UserFeedTab.DynamicVideo -> feedState.dynamicVideo.focusRestoredItemKey += 1
          UserFeedTab.DynamicAll -> feedState.dynamicAll.focusRestoredItemKey += 1
          UserFeedTab.History -> feedState.history.focusRestoredItemKey += 1
          UserFeedTab.Favorite -> feedState.favorite.focusRestoredItemKey += 1
          UserFeedTab.Bangumi -> feedState.bangumi.focusRestoredItemKey += 1
        }
        true
      },
    )
    // History tab 未登录也放行:本地 YouTube 历史免登录即可展示(与移动端一致);B 站历史仍要求登录。
    val signedOutHistoryAllowed = selectedTab == UserFeedTab.History
    if (!isLoggedIn && !signedOutHistoryAllowed && !(selectedTab == UserFeedTab.DynamicVideo && youtubeChannels.isNotEmpty())) {
      val message = stringResource(
       when (selectedTab) {
         UserFeedTab.History -> R.string.history_signed_out
         UserFeedTab.Favorite -> R.string.favorite_signed_out
         UserFeedTab.DynamicVideo -> R.string.dynamic_signed_out
         UserFeedTab.DynamicAll -> R.string.dynamic_signed_out
         UserFeedTab.Bangumi -> R.string.bangumi_signed_out
       },
      )
      FeedStatusScreen(message = message)
    } else {
      // key 纳入 manualRefreshKey:侧栏重点击"动态"刷新时 manualRefreshKey 变 →
      // 网格重建 → rememberLazyListState 重新初始化(restoreFocusRequestKey<=0 →
      // initialFirstVisibleItemIndex=0)→ 视口回顶。子 tab 切换行为不变(selectedTab 部分仍变)。
      androidx.compose.runtime.key(selectedTab, manualRefreshKey) {
        when (selectedTab) {
          UserFeedTab.DynamicVideo -> DynamicFeedContent(
            state = feedState.dynamicVideo,
            type = "video",
            youtubeChannels = youtubeChannels,
            youtubeChannelStore = youtubeChannelStore,
            cardMode = VideoCardMode.Dynamic,
            firstItemFocusRequester = firstItemFocusRequester,
            tabFocusRequester = tabFocusRequester,
            restoreFocusRequestKey = restoreFocusRequestKey,
            onRestoreFocusHandled = onRestoreFocusHandled,
            coroutineScope = coroutineScope,
            videoRepository = videoRepository,
            onMoveLeftToNav = onMoveLeftToNav,
            onVideoSelected = { video -> onVideoSelected(video, false) },
            onOwnerSelected = onOwnerSelected,
            onCardLongPress = { video -> actionSheetVideo = video },
          )
          UserFeedTab.DynamicAll -> DynamicFeedContent(
            state = feedState.dynamicAll,
            type = "all",
            youtubeChannels = emptyList(),
            youtubeChannelStore = youtubeChannelStore,
            cardMode = VideoCardMode.Dynamic,
            firstItemFocusRequester = firstItemFocusRequester,
            tabFocusRequester = tabFocusRequester,
            restoreFocusRequestKey = restoreFocusRequestKey,
            onRestoreFocusHandled = onRestoreFocusHandled,
            coroutineScope = coroutineScope,
            videoRepository = videoRepository,
            onMoveLeftToNav = onMoveLeftToNav,
            onVideoSelected = { video -> onVideoSelected(video, false) },
            onOwnerSelected = onOwnerSelected,
            onCardLongPress = { video -> actionSheetVideo = video },
          )
          UserFeedTab.History -> HistoryFeedContent(
            state = feedState.history.state,
            youtubeVideos = youtubeHistoryVideos,
            isLoggedIn = isLoggedIn,
            loadingMessage = stringResource(R.string.history_loading),
            emptyMessage = stringResource(R.string.history_empty),
            failedMessage = { message -> stringResource(R.string.history_failed_with_message, message) },
            cardMode = VideoCardMode.History,
            firstItemFocusRequester = firstItemFocusRequester,
            restoredFocusIndex = feedState.history.focusedVideoIndex,
            restoredFocusKey = feedState.history.focusedVideoKey,
            restoreFocusRequestKey = restoreFocusRequestKey,
            onRestoreFocusHandled = onRestoreFocusHandled,
            onFocusedIndexChange = { index, video ->
              feedState.history.focusedVideoIndex = index
              feedState.history.focusedVideoKey = video.focusRestoreKey()
            },
            onRetry = {
              coroutineScope.launch {
                loadHistoryFirstPage(videoRepository, feedState.history, forceRefresh = true)
              }
            },
            onLoadMore = { loadHistoryNextPage(videoRepository, coroutineScope, feedState.history) },
            onMoveUpFromFirstRow = { runCatching { tabFocusRequester.requestFocus() }.isSuccess },
            onMoveLeftToNav = onMoveLeftToNav,
           onVideoSelected = { video -> onVideoSelected(video, true) },
           onOwnerSelected = onOwnerSelected,
           onCardLongPress = { video -> onOwnerSelected(video) },
           focusRestoredItemKey = feedState.history.focusRestoredItemKey,
          )
          UserFeedTab.Favorite -> FavoriteFeedContent(
            state = feedState.favorite,
            cardMode = VideoCardMode.Dynamic,
            firstItemFocusRequester = firstItemFocusRequester,
            restoreFocusRequestKey = restoreFocusRequestKey,
            onRestoreFocusHandled = onRestoreFocusHandled,
            tabFocusRequester = tabFocusRequester,
            coroutineScope = coroutineScope,
            videoRepository = videoRepository,
            onMoveLeftToNav = onMoveLeftToNav,
           onVideoSelected = { video -> onVideoSelected(video, false) },
           onOwnerSelected = onOwnerSelected,
          onCardLongPress = { video -> onOwnerSelected(video) },
         )
         UserFeedTab.Bangumi -> BangumiFollowContent(
           state = feedState.bangumi,
           firstItemFocusRequester = firstItemFocusRequester,
           restoreFocusRequestKey = restoreFocusRequestKey,
           onRestoreFocusHandled = onRestoreFocusHandled,
           tabFocusRequester = tabFocusRequester,
           coroutineScope = coroutineScope,
           videoRepository = videoRepository,
           onMoveLeftToNav = onMoveLeftToNav,
           onSeasonSelected = onSeasonSelected,
         )
       }
      }
    }
  }

  actionSheetVideo?.let { video ->
    val likeLabel = stringResource(R.string.feed_action_like)
    val toviewLabel = stringResource(R.string.feed_action_toview)
    val upspaceLabel = stringResource(R.string.feed_action_upspace)
    val commentLabel = stringResource(R.string.feed_action_comment)
    val likeDone = stringResource(R.string.feed_action_like_done)
    val likeFailed = stringResource(R.string.feed_action_like_failed)
    val toviewDone = stringResource(R.string.feed_action_toview_done)
    val toviewFailed = stringResource(R.string.feed_action_toview_failed)

    BiliActionSheet(
      title = stringResource(R.string.feed_action_sheet_title),
      items = listOf(
        BiliActionItem(
          label = commentLabel,
          enabled = video.aid > 0L || video.source == SourceYoutube,
          onClick = {
            actionSheetNavigating = true
            onCommentSelected(video)
          },
        ),
        BiliActionItem(
          label = likeLabel,
          enabled = video.dynId.isNotBlank(),
          onClick = {
            coroutineScope.launch {
              val ok = runCatching { videoRepository.likeDynamic(video.dynId) }
                .getOrDefault(false)
              Toast.makeText(
                context,
                if (ok) likeDone else likeFailed,
                Toast.LENGTH_SHORT,
              ).show()
            }
          },
        ),
        BiliActionItem(
          label = toviewLabel,
          enabled = video.aid > 0L,
          onClick = {
            coroutineScope.launch {
              val ok = runCatching { videoRepository.addToView(video.aid) }
                .getOrDefault(false)
              Toast.makeText(
                context,
                if (ok) toviewDone else toviewFailed,
                Toast.LENGTH_SHORT,
              ).show()
            }
          },
        ),
        BiliActionItem(
          label = upspaceLabel,
          enabled = video.ownerMid > 0L ||
            (video.source == SourceYoutube && video.channelId.isNotBlank()),
          onClick = {
            actionSheetNavigating = true
            onOwnerSelected(video)
          },
        ),
      ),
      onDismiss = {
        if (!actionSheetNavigating) {
          // BiliActionSheet 是屏内覆盖层(非 Dialog),关闭时其聚焦节点被移除,Compose 不会自动把
          // 焦点还给底下网格 → 焦点会落到侧栏头像并 autoConfirm 打开「我的」页(焦点丢失)。
          // 走 AppShell 的 contentFocusRestore 机制:置 contentFocusRestoreDestination 抑制头像
          // autoConfirm,并让网格 restore effect 把焦点拉回刚长按的那张卡片。
          onActionSheetDismissed()
        }
        actionSheetNavigating = false
        actionSheetVideo = null
      },
    )
  }
}

@Composable
private fun UserFeedTabRow(
  selectedTab: UserFeedTab,
  onSelect: (UserFeedTab) -> Unit,
  tabFocusRequester: FocusRequester,
  onMoveLeftToNav: () -> Boolean,
  onMoveDownToGrid: () -> Boolean,
) {
  BiliCapsuleTabRow(itemCount = UserFeedTab.entries.size) {
    UserFeedTab.entries.forEach { tab ->
      val selected = tab == selectedTab
      BiliPillTab(
        text = stringResource(
          when (tab) {
           UserFeedTab.DynamicVideo -> R.string.nav_dynamic_video
           UserFeedTab.DynamicAll -> R.string.nav_dynamic_all
           UserFeedTab.History -> R.string.nav_history
           UserFeedTab.Favorite -> R.string.nav_favorite
           UserFeedTab.Bangumi -> R.string.nav_bangumi
         },
        ),
        selected = selected,
        modifier = if (selected) Modifier.focusRequester(tabFocusRequester) else Modifier,
        onMoveUpToNav = onMoveLeftToNav,
        onMoveDownToGrid = onMoveDownToGrid,
        onClick = { onSelect(tab) },
      )
    }
  }
}

private suspend fun loadDynamicFirstPage(
  videoRepository: VideoRepository,
  state: DynamicFeedUiState,
  type: String,
  youtubeChannels: List<YoutubeChannel>,
  youtubeChannelStore: com.kirin.mt.core.youtube.YoutubeChannelStore,
  forceRefresh: Boolean,
) {
  if (!forceRefresh && state.loadedOnce) {
    return
  }

  // force-refresh 且已有内容时保留旧 videos 与焦点,不切骨架、不清焦点,
  // 避免网格销毁重建后跳到第一个视频(对齐推荐页刷新策略)。
  if (state.state !is UserFeedState.Success) {
    state.state = UserFeedState.Loading
    state.focusedVideoIndex = 0
    state.focusedVideoKey = ""
  }
  state.nextOffset = ""
  // 1. 拉 B 站动态
  var biliEndReached = true
  var biliError: String? = null
  val biliVideos = try {
    val page = videoRepository.getDynamicFeed(type = type)
    state.nextOffset = page.offset
    state.loadedOnce = true
    biliEndReached = !page.hasMore
    page.videos
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    state.loadedOnce = true
    biliError = error.message.orEmpty()
    null
  }

  // 2. 拉 YouTube 关注(仅「动态(视频)」,等查完再一次性合并,不再分批增量叠加)
  val youtubeVideos = if (type == "video" && youtubeChannels.isNotEmpty()) {
    fetchYoutubeAll(videoRepository, youtubeChannels, youtubeChannelStore)
  } else {
    emptyList()
  }

  // 3. 合并一次
  val merged = mergeByPubdate(biliVideos.orEmpty(), youtubeVideos)
  // 频道非空即视为已尝试合并 YouTube(含拉取失败返回空),置位后不再因频道变化强制补拉。
  if (type == "video" && youtubeChannels.isNotEmpty()) {
    state.youtubeMerged = true
  }
  state.state = when {
    merged.isEmpty() && biliError != null -> UserFeedState.Failed(biliError)
    merged.isEmpty() -> UserFeedState.Empty
    else -> {
      state.hasLoadedContent = true
      UserFeedState.Success(
        videos = merged,
        loadingMore = false,
        endReached = biliEndReached,
        loadMoreError = "",
      )
    }
  }
}

/** 全量拉取 YouTube 关注流(等全部查完),失败返回空(单频道已容错)。 */
private suspend fun fetchYoutubeAll(
  videoRepository: VideoRepository,
  channels: List<YoutubeChannel>,
  youtubeChannelStore: com.kirin.mt.core.youtube.YoutubeChannelStore,
): List<VideoSummary> {
  return try {
    videoRepository.youtubeSubscriptionsFeed(
      channels,
      onChannelAvatarResolved = { channel ->
        youtubeChannelStore.updateAvatar(channel.channelId, channel.avatar)
      },
    )
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    emptyList()
  }
}

private fun loadDynamicNextPage(
  videoRepository: VideoRepository,
  coroutineScope: CoroutineScope,
  state: DynamicFeedUiState,
  type: String,
) {
  val currentState = state.state as? UserFeedState.Success ?: return
  if (currentState.loadingMore || currentState.endReached) {
    return
  }

  val offsetToLoad = state.nextOffset
  state.state = currentState.copy(loadingMore = true, loadMoreError = "")
  coroutineScope.launch {
    state.state = try {
      val page = videoRepository.getDynamicFeed(
        offset = offsetToLoad,
        type = type,
      )
      state.nextOffset = page.offset
      val latestState = state.state as? UserFeedState.Success ?: return@launch
      val mergedVideos = latestState.videos.appendUnique(nextVideos = page.videos)
      if (mergedVideos.isNotEmpty()) {
        state.hasLoadedContent = true
      }
      latestState.copy(
        videos = mergedVideos,
        loadingMore = false,
        endReached = !page.hasMore ||
          page.videos.isEmpty() ||
          mergedVideos.size == latestState.videos.size,
        loadMoreError = "",
      )
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      val latestState = state.state as? UserFeedState.Success ?: return@launch
      latestState.copy(loadingMore = false, loadMoreError = error.message.orEmpty())
    }
  }
}

private suspend fun loadHistoryFirstPage(
  videoRepository: VideoRepository,
  state: HistoryFeedUiState,
  forceRefresh: Boolean,
) {
  if (!forceRefresh && state.loadedOnce) {
    return
  }

  // force-refresh 且已有内容时保留旧 videos 与焦点,不切骨架、不清焦点,
  // 避免网格销毁重建后跳到第一个视频(对齐推荐页刷新策略)。
  if (state.state !is UserFeedState.Success) {
    state.state = UserFeedState.Loading
    state.focusedVideoIndex = 0
    state.focusedVideoKey = ""
  }
  state.nextViewAt = 0L
  state.nextMax = 0L
  state.state = try {
    val page = videoRepository.getHistoryPage()
    state.nextViewAt = page.nextViewAt
    state.nextMax = page.nextMax
    state.loadedOnce = true
    if (page.videos.isEmpty()) {
      UserFeedState.Empty
    } else {
      state.hasLoadedContent = true
      UserFeedState.Success(
        videos = page.videos,
        loadingMore = false,
        endReached = !page.hasMore,
        loadMoreError = "",
      )
    }
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    state.loadedOnce = true
    UserFeedState.Failed(error.message.orEmpty())
  }
}

private fun loadHistoryNextPage(
  videoRepository: VideoRepository,
  coroutineScope: CoroutineScope,
  state: HistoryFeedUiState,
) {
  val currentState = state.state as? UserFeedState.Success ?: return
  if (currentState.loadingMore || currentState.endReached) {
    return
  }

  val viewAtToLoad = state.nextViewAt
  val maxToLoad = state.nextMax
  state.state = currentState.copy(loadingMore = true, loadMoreError = "")
  coroutineScope.launch {
    state.state = try {
      val page = videoRepository.getHistoryPage(
        viewAt = viewAtToLoad,
        max = maxToLoad,
      )
      state.nextViewAt = page.nextViewAt
      state.nextMax = page.nextMax
      val latestState = state.state as? UserFeedState.Success ?: return@launch
      val mergedVideos = latestState.videos.appendUnique(nextVideos = page.videos)
      if (mergedVideos.isNotEmpty()) {
        state.hasLoadedContent = true
      }
      latestState.copy(
        videos = mergedVideos,
        loadingMore = false,
        endReached = !page.hasMore ||
          page.videos.isEmpty() ||
          mergedVideos.size == latestState.videos.size,
        loadMoreError = "",
      )
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      val latestState = state.state as? UserFeedState.Success ?: return@launch
      latestState.copy(loadingMore = false, loadMoreError = error.message.orEmpty())
    }
 }
}

private suspend fun loadFavoriteFolders(
  videoRepository: VideoRepository,
  state: FavoriteFeedUiState,
  forceRefresh: Boolean,
) {
  if (!forceRefresh && state.foldersLoaded) {
    return
  }

  // force-refresh 且已有内容时保留旧 videos 与焦点,不切骨架、不清焦点,
  // 避免网格销毁重建后跳到第一个视频(对齐推荐页刷新策略)。
  if (state.state !is UserFeedState.Success) {
    state.state = UserFeedState.Loading
    state.focusedVideoIndex = 0
    state.focusedVideoKey = ""
  }
  state.currentPage = 0
  state.state = try {
    val mid = videoRepository.currentMid()
    val folders = if (mid > 0L) videoRepository.getFavoriteFolders(mid) else emptyList()
    state.folders = folders
    state.foldersLoaded = true
    if (folders.isEmpty()) {
      UserFeedState.Empty
    } else {
      state.currentFolderMediaId = folders.first().mediaId
      val page = videoRepository.getFavoriteFolderVideos(
        mediaId = state.currentFolderMediaId,
        page = 1,
        order = state.currentOrder,
      )
      state.currentPage = 1
      state.hasLoadedContent = page.videos.isNotEmpty()
      if (page.videos.isEmpty()) {
        UserFeedState.Empty
      } else {
        UserFeedState.Success(
          videos = page.videos,
          loadingMore = false,
          endReached = !page.hasMore,
          loadMoreError = "",
        )
      }
    }
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    state.foldersLoaded = true
    UserFeedState.Failed(error.message.orEmpty())
  }
}

private suspend fun loadFavoriteFirstPage(
  videoRepository: VideoRepository,
  state: FavoriteFeedUiState,
) {
  state.state = UserFeedState.Loading
  state.focusedVideoIndex = 0
  state.focusedVideoKey = ""
  state.currentPage = 0
  state.state = try {
    if (state.currentFolderMediaId <= 0L) {
      UserFeedState.Empty
    } else {
      val page = videoRepository.getFavoriteFolderVideos(
        mediaId = state.currentFolderMediaId,
        page = 1,
        order = state.currentOrder,
      )
      state.currentPage = 1
      state.hasLoadedContent = page.videos.isNotEmpty()
      if (page.videos.isEmpty()) {
        UserFeedState.Empty
      } else {
        UserFeedState.Success(
          videos = page.videos,
          loadingMore = false,
          endReached = !page.hasMore,
          loadMoreError = "",
        )
      }
    }
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    UserFeedState.Failed(error.message.orEmpty())
  }
}

private fun loadFavoriteNextPage(
  videoRepository: VideoRepository,
  coroutineScope: CoroutineScope,
  state: FavoriteFeedUiState,
) {
  val currentState = state.state as? UserFeedState.Success ?: return
  if (currentState.loadingMore || currentState.endReached) {
    return
  }

  val nextPage = state.currentPage + 1
  val mediaIdToLoad = state.currentFolderMediaId
  state.state = currentState.copy(loadingMore = true, loadMoreError = "")
  coroutineScope.launch {
    state.state = try {
      val page = videoRepository.getFavoriteFolderVideos(
        mediaId = mediaIdToLoad,
        page = nextPage,
        order = state.currentOrder,
      )
      state.currentPage = nextPage
      val latestState = state.state as? UserFeedState.Success ?: return@launch
      val mergedVideos = latestState.videos.appendUnique(nextVideos = page.videos)
      if (mergedVideos.isNotEmpty()) {
        state.hasLoadedContent = true
      }
      latestState.copy(
        videos = mergedVideos,
        loadingMore = false,
        endReached = !page.hasMore ||
          page.videos.isEmpty() ||
          mergedVideos.size == latestState.videos.size,
        loadMoreError = "",
      )
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      val latestState = state.state as? UserFeedState.Success ?: return@launch
      latestState.copy(loadingMore = false, loadMoreError = error.message.orEmpty())
    }
  }
}

private suspend fun loadBangumiFirstPage(
  videoRepository: VideoRepository,
  state: BangumiFollowUiState,
  forceRefresh: Boolean,
) {
  if (!forceRefresh && state.loadedOnce) return
  // force-refresh 且已有内容时保留旧 videos 与焦点,不切骨架、不清焦点,
  // 避免网格销毁重建后跳到第一个视频(对齐推荐页刷新策略)。
  if (state.state !is UserFeedState.Success) {
    state.state = UserFeedState.Loading
    state.focusedVideoIndex = 0
    state.focusedVideoKey = ""
  }
  state.currentPage = 0
  state.state = try {
    val page = videoRepository.getFollowingSeasons(
      page = 1,
      type = state.selectedType.id,
      status = state.selectedStatus.id,
    )
    state.currentPage = 1
    state.loadedOnce = true
    state.hasLoadedContent = page.seasons.isNotEmpty()
    if (page.seasons.isEmpty()) {
      UserFeedState.Empty
    } else {
      UserFeedState.Success(
        seasons = page.seasons,
        loadingMore = false,
        endReached = !page.hasMore,
        loadMoreError = "",
      )
    }
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    state.loadedOnce = true
    UserFeedState.Failed(error.message.orEmpty())
  }
}

private fun loadBangumiNextPage(
  videoRepository: VideoRepository,
  coroutineScope: CoroutineScope,
  state: BangumiFollowUiState,
) {
  val currentState = state.state as? UserFeedState.Success ?: return
  if (currentState.loadingMore || currentState.endReached) return
  val nextPage = state.currentPage + 1
  val typeToLoad = state.selectedType.id
  val statusToLoad = state.selectedStatus.id
  state.state = currentState.copy(loadingMore = true, loadMoreError = "")
  coroutineScope.launch {
    state.state = try {
      val page = videoRepository.getFollowingSeasons(
        page = nextPage,
        type = typeToLoad,
        status = statusToLoad,
      )
      state.currentPage = nextPage
      val latestState = state.state as? UserFeedState.Success ?: return@launch
      val merged = latestState.seasons.appendSeasonsUnique(page.seasons)
      if (merged.isNotEmpty()) state.hasLoadedContent = true
      latestState.copy(
        seasons = merged,
        loadingMore = false,
        endReached = !page.hasMore ||
          page.seasons.isEmpty() ||
          merged.size == latestState.seasons.size,
        loadMoreError = "",
      )
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      val latestState = state.state as? UserFeedState.Success ?: return@launch
      latestState.copy(loadingMore = false, loadMoreError = error.message.orEmpty())
    }
  }
}

@Composable
private fun DynamicFeedContent(
  state: DynamicFeedUiState,
  type: String,
  youtubeChannels: List<YoutubeChannel>,
  youtubeChannelStore: com.kirin.mt.core.youtube.YoutubeChannelStore,
  cardMode: VideoCardMode,
  firstItemFocusRequester: FocusRequester,
  tabFocusRequester: FocusRequester,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  coroutineScope: CoroutineScope,
  videoRepository: VideoRepository,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit = {},
  onCardLongPress: (VideoSummary) -> Unit = {},
) {
  // type（video / all）由上层 tab 决定，这里不再有独立的类型过滤行；网格 Up 直接回 tab 行。
  Column(modifier = Modifier.fillMaxSize()) {
    UserFeedContent(
      state = state.state,
      loadingMessage = stringResource(R.string.dynamic_loading),
      emptyMessage = stringResource(R.string.dynamic_empty),
      failedMessage = { message -> stringResource(R.string.dynamic_failed_with_message, message) },
      cardMode = cardMode,
      firstItemFocusRequester = firstItemFocusRequester,
      restoredFocusIndex = state.focusedVideoIndex,
      restoredFocusKey = state.focusedVideoKey,
      restoreFocusRequestKey = restoreFocusRequestKey,
      onRestoreFocusHandled = onRestoreFocusHandled,
      onFocusedIndexChange = { index, video ->
        state.focusedVideoIndex = index
        state.focusedVideoKey = video.focusRestoreKey()
      },
      onRetry = {
        coroutineScope.launch {
          loadDynamicFirstPage(videoRepository, state, type, youtubeChannels, youtubeChannelStore, forceRefresh = true)
        }
      },
      onLoadMore = { loadDynamicNextPage(videoRepository, coroutineScope, state, type) },
      onMoveUpFromFirstRow = { runCatching { tabFocusRequester.requestFocus() }.isSuccess },
      onMoveLeftToNav = onMoveLeftToNav,
      onVideoSelected = onVideoSelected,
      onOwnerSelected = onOwnerSelected,
      onCardLongPress = onCardLongPress,
      focusRestoredItemKey = state.focusRestoredItemKey,
    )
  }
}

@Composable
private fun FavoriteFeedContent(
  state: FavoriteFeedUiState,
  cardMode: VideoCardMode,
  firstItemFocusRequester: FocusRequester,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  tabFocusRequester: FocusRequester,
  coroutineScope: CoroutineScope,
  videoRepository: VideoRepository,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit = {},
  onCardLongPress: (VideoSummary) -> Unit = {},
) {
 val folderFocusRequester = remember { FocusRequester() }
  val hasMultipleFolders = state.folders.size > 1

  LaunchedEffect(state.folders) {
    if (state.folders.isNotEmpty() && hasMultipleFolders) {
      runCatching { folderFocusRequester.requestFocus() }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    if (hasMultipleFolders) {
      BiliCapsuleTabRow(
        itemCount = state.folders.size,
        modifier = Modifier,
      ) {
        state.folders.forEachIndexed { index, folder ->
          val selected = folder.mediaId == state.currentFolderMediaId
          BiliPillTab(
            text = folder.title,
            selected = selected,
            modifier = if (selected) Modifier.focusRequester(folderFocusRequester) else Modifier,
            onMoveUpToNav = onMoveLeftToNav,
            onMoveDownToGrid = { state.focusRestoredItemKey += 1; true },
            onClick = {
              if (!selected) {
                state.currentFolderMediaId = folder.mediaId
                coroutineScope.launch {
                  loadFavoriteFirstPage(videoRepository, state)
                }
              }
            },
          )
        }
      }
    }
    UserFeedContent(
      state = state.state,
      loadingMessage = stringResource(R.string.favorite_loading),
      emptyMessage = stringResource(R.string.favorite_empty),
      failedMessage = { message -> stringResource(R.string.favorite_failed_with_message, message) },
      cardMode = cardMode,
      firstItemFocusRequester = firstItemFocusRequester,
      restoredFocusIndex = state.focusedVideoIndex,
      restoredFocusKey = state.focusedVideoKey,
      restoreFocusRequestKey = restoreFocusRequestKey,
      onRestoreFocusHandled = onRestoreFocusHandled,
      onFocusedIndexChange = { index, video ->
        state.focusedVideoIndex = index
        state.focusedVideoKey = video.focusRestoreKey()
      },
      onRetry = {
        coroutineScope.launch {
          if (state.folders.isEmpty()) {
            loadFavoriteFolders(videoRepository, state, forceRefresh = true)
          } else {
            loadFavoriteFirstPage(videoRepository, state)
          }
        }
      },
      onLoadMore = { loadFavoriteNextPage(videoRepository, coroutineScope, state) },
      onMoveUpFromFirstRow = {
        if (state.folders.size > 1) {
          runCatching { folderFocusRequester.requestFocus() }.isSuccess
        } else {
          runCatching { tabFocusRequester.requestFocus() }.isSuccess
        }
      },
      onMoveLeftToNav = onMoveLeftToNav,
      onVideoSelected = onVideoSelected,
      onOwnerSelected = onOwnerSelected,
      onCardLongPress = onCardLongPress,
      focusRestoredItemKey = state.focusRestoredItemKey,
    )
  }
}

@Composable
private fun BangumiFollowContent(
  state: BangumiFollowUiState,
  firstItemFocusRequester: FocusRequester,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  tabFocusRequester: FocusRequester,
  coroutineScope: CoroutineScope,
  videoRepository: VideoRepository,
  onMoveLeftToNav: () -> Boolean,
  onSeasonSelected: (com.kirin.mt.core.network.FollowingSeason) -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    val feedState = state.state
    when (feedState) {
      UserFeedState.Loading -> VideoGridSkeleton()
      UserFeedState.Empty -> FeedStatusScreen(message = stringResource(R.string.bangumi_empty))
      is UserFeedState.Failed -> FeedStatusScreen(
        message = stringResource(R.string.bangumi_failed_with_message, feedState.message),
        actionLabel = stringResource(R.string.action_retry),
        onAction = {
          coroutineScope.launch {
            loadBangumiFirstPage(videoRepository, state, forceRefresh = true)
          }
        },
      )
      is UserFeedState.Success -> {
        val seasons = feedState.seasons
       if (seasons.isEmpty()) {
         FeedStatusScreen(message = stringResource(R.string.bangumi_empty))
       } else {
         BangumiGrid(
           seasons = seasons,
           firstItemFocusRequester = firstItemFocusRequester,
           restoredFocusIndex = seasons.resolveSeasonFocusIndex(
             focusKey = state.focusedVideoKey,
             fallbackIndex = state.focusedVideoIndex,
           ),
           restoreFocusRequestKey = restoreFocusRequestKey,
           onRestoreFocusHandled = onRestoreFocusHandled,
           onFocusedIndexChange = { index, season ->
             state.focusedVideoIndex = index
             state.focusedVideoKey = season.seasonFocusKey()
           },
           onLoadMore = { loadBangumiNextPage(videoRepository, coroutineScope, state) },
           onMoveUpFromFirstRow = { runCatching { tabFocusRequester.requestFocus() }.isSuccess },
           onMoveLeftToNav = onMoveLeftToNav,
           onSeasonSelected = onSeasonSelected,
           footer = when {
             feedState.loadMoreError.isNotBlank() -> GridFooterState.Error(feedState.loadMoreError)
             feedState.loadingMore -> GridFooterState.Loading
             feedState.endReached -> GridFooterState.EndReached
             else -> GridFooterState.None
           },
           focusRestoredItemKey = state.focusRestoredItemKey,
         )
       }
      }
    }
  }
}

private fun com.kirin.mt.core.network.FollowingSeason.toVideoSummary(): VideoSummary {
  return VideoSummary(
    bvid = "season-$seasonId",
    title = title,
    pic = cover,
    ownerName = seasonTypeName,
    ownerFace = "",
    ownerMid = 0L,
    view = 0,
    danmaku = 0,
    duration = 0,
    pubdate = 0L,
    badge = badge,
  )
}

private fun com.kirin.mt.core.network.FollowingSeason.seasonFocusKey(): String {
  return "season-$seasonId"
}

private fun List<com.kirin.mt.core.network.FollowingSeason>.resolveSeasonFocusIndex(
  focusKey: String,
  fallbackIndex: Int,
): Int {
  if (isEmpty()) return 0
  val lastIndex = lastIndex
  if (focusKey.isBlank()) return fallbackIndex.coerceIn(0, lastIndex)
  val keyIndex = indexOfFirst { it.seasonFocusKey() == focusKey }
  return keyIndex.coerceAtLeast(0).coerceAtMost(lastIndex)
}

/**
 * 历史 tab 内容:本地 YouTube 历史(免登录)+ B 站观看历史按播放时间倒序混合。
 * 登录时 [state] 承载 B 站历史(含分页),未登录时只渲染 [youtubeVideos] 并附登录提示。
 */
@Composable
private fun HistoryFeedContent(
  state: UserFeedState,
  youtubeVideos: List<VideoSummary>,
  isLoggedIn: Boolean,
  loadingMessage: String,
  emptyMessage: String,
  failedMessage: @Composable (String) -> String,
  cardMode: VideoCardMode,
  firstItemFocusRequester: FocusRequester,
  restoredFocusIndex: Int,
  restoredFocusKey: String,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onFocusedIndexChange: (Int, VideoSummary) -> Unit,
  onRetry: () -> Unit,
  onLoadMore: () -> Unit,
  onMoveUpFromFirstRow: () -> Boolean,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit = {},
  onCardLongPress: (VideoSummary) -> Unit = {},
  focusRestoredItemKey: Int = 0,
) {
  if (!isLoggedIn) {
    // 未登录:跳过 B 站网络请求,只显示本地 YouTube 历史。
    if (youtubeVideos.isEmpty()) {
      FeedStatusScreen(message = stringResource(R.string.history_signed_out))
      return
    }
    Column(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.weight(1f)) {
        UserFeedContent(
          state = UserFeedState.Success(
            videos = emptyList(),
            loadingMore = false,
            endReached = true,
            loadMoreError = "",
          ),
          extraVideos = youtubeVideos,
          loadingMessage = loadingMessage,
          emptyMessage = emptyMessage,
          failedMessage = failedMessage,
          cardMode = cardMode,
          firstItemFocusRequester = firstItemFocusRequester,
          restoredFocusIndex = restoredFocusIndex,
          restoredFocusKey = restoredFocusKey,
          restoreFocusRequestKey = restoreFocusRequestKey,
          onRestoreFocusHandled = onRestoreFocusHandled,
          onFocusedIndexChange = onFocusedIndexChange,
          onRetry = onRetry,
          onLoadMore = {},
          onMoveUpFromFirstRow = onMoveUpFromFirstRow,
          onMoveLeftToNav = onMoveLeftToNav,
          onVideoSelected = onVideoSelected,
          onOwnerSelected = onOwnerSelected,
          onCardLongPress = onCardLongPress,
          focusRestoredItemKey = focusRestoredItemKey,
        )
      }
      Text(
        text = stringResource(R.string.history_signed_out),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
      )
    }
    return
  }
  UserFeedContent(
    state = state,
    extraVideos = youtubeVideos,
    loadingMessage = loadingMessage,
    emptyMessage = emptyMessage,
    failedMessage = failedMessage,
    cardMode = cardMode,
    firstItemFocusRequester = firstItemFocusRequester,
    restoredFocusIndex = restoredFocusIndex,
    restoredFocusKey = restoredFocusKey,
    restoreFocusRequestKey = restoreFocusRequestKey,
    onRestoreFocusHandled = onRestoreFocusHandled,
    onFocusedIndexChange = onFocusedIndexChange,
    onRetry = onRetry,
    onLoadMore = onLoadMore,
    onMoveUpFromFirstRow = onMoveUpFromFirstRow,
    onMoveLeftToNav = onMoveLeftToNav,
    onVideoSelected = onVideoSelected,
    onOwnerSelected = onOwnerSelected,
    onCardLongPress = onCardLongPress,
    focusRestoredItemKey = focusRestoredItemKey,
  )
}

@Composable
private fun UserFeedContent(
  state: UserFeedState,
  loadingMessage: String,
  emptyMessage: String,
  failedMessage: @Composable (String) -> String,
  cardMode: VideoCardMode,
  firstItemFocusRequester: FocusRequester,
  restoredFocusIndex: Int,
  restoredFocusKey: String,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onFocusedIndexChange: (Int, VideoSummary) -> Unit,
  onRetry: () -> Unit,
  onLoadMore: () -> Unit,
  onMoveUpFromFirstRow: () -> Boolean,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit = {},
  onCardLongPress: (VideoSummary) -> Unit = {},
  focusRestoredItemKey: Int = 0,
  /** 额外的本地条目(如 YouTube 历史),与 state.videos 按播放时间倒序合并展示。空则原样渲染。 */
  extraVideos: List<VideoSummary> = emptyList(),
) {
  when (state) {
    UserFeedState.Loading -> VideoGridSkeleton()
    UserFeedState.Empty -> FeedStatusScreen(message = emptyMessage)
    is UserFeedState.Failed -> FeedStatusScreen(
      message = failedMessage(state.message),
      actionLabel = stringResource(R.string.action_retry),
      onAction = onRetry,
    )
    is UserFeedState.Success -> {
      val displayVideos = mergeExtraVideos(state.videos, extraVideos)
      UserFeedGrid(
        videos = displayVideos,
        cardMode = cardMode,
        firstItemFocusRequester = firstItemFocusRequester,
        restoredFocusIndex = displayVideos.resolveFocusIndex(
          focusKey = restoredFocusKey,
          fallbackIndex = restoredFocusIndex,
        ),
        restoreFocusRequestKey = restoreFocusRequestKey,
        onRestoreFocusHandled = onRestoreFocusHandled,
        onFocusedIndexChange = onFocusedIndexChange,
        onLoadMore = onLoadMore,
        onMoveUpFromFirstRow = onMoveUpFromFirstRow,
        onMoveLeftToNav = onMoveLeftToNav,
        onVideoSelected = onVideoSelected,
        onOwnerSelected = onOwnerSelected,
        onCardLongPress = onCardLongPress,
        focusRestoredItemKey = focusRestoredItemKey,
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

@Composable
private fun UserFeedGrid(
  videos: List<VideoSummary>,
  cardMode: VideoCardMode,
  firstItemFocusRequester: FocusRequester,
  restoredFocusIndex: Int,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onFocusedIndexChange: (Int, VideoSummary) -> Unit,
  onLoadMore: () -> Unit,
  onMoveUpFromFirstRow: () -> Boolean,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit = {},
  onCardLongPress: (VideoSummary) -> Unit = {},
  footer: GridFooterState = GridFooterState.None,
  focusRestoredItemKey: Int = 0,
) {
  TvVideoGrid(
    videos = videos,
    debugLabel = "dynamic-grid",
    firstItemFocusRequester = firstItemFocusRequester,
    restoredFocusIndex = restoredFocusIndex,
    restoreFocusRequestKey = restoreFocusRequestKey,
    onRestoreFocusHandled = onRestoreFocusHandled,
    onFocusedIndexChange = onFocusedIndexChange,
    onLoadMore = onLoadMore,
    onMoveUpFromFirstRow = onMoveUpFromFirstRow,
    onMoveLeftToNav = onMoveLeftToNav,
    onVideoSelected = onVideoSelected,
    onOwnerSelected = onOwnerSelected,
    onCardLongPress = onCardLongPress,
    footer = footer,
    focusRestoredItemKey = focusRestoredItemKey,
    keyFactory = { index, video -> video.feedKey(index) },
  )
}

@Composable
private fun BangumiGrid(
  seasons: List<FollowingSeason>,
  firstItemFocusRequester: FocusRequester,
  restoredFocusIndex: Int,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onFocusedIndexChange: (Int, FollowingSeason) -> Unit,
  onLoadMore: () -> Unit,
  onMoveUpFromFirstRow: () -> Boolean,
  onMoveLeftToNav: () -> Boolean,
  onSeasonSelected: (FollowingSeason) -> Unit,
  footer: GridFooterState = GridFooterState.None,
  focusRestoredItemKey: Int = 0,
) {
  val videos = remember(seasons) { seasons.map { it.toVideoSummary() } }
  val seasonByKey = remember(seasons) { seasons.associateBy { it.seasonFocusKey() } }
  TvVideoGrid(
    videos = videos,
    cardMode = VideoCardMode.Bangumi,
    firstItemFocusRequester = firstItemFocusRequester,
    restoredFocusIndex = restoredFocusIndex,
    restoreFocusRequestKey = restoreFocusRequestKey,
    onRestoreFocusHandled = onRestoreFocusHandled,
    onFocusedIndexChange = { index, video ->
      seasonByKey[video.bvid]?.let { onFocusedIndexChange(index, it) }
    },
    onLoadMore = onLoadMore,
    onMoveUpFromFirstRow = onMoveUpFromFirstRow,
    onMoveLeftToNav = onMoveLeftToNav,
    onVideoSelected = { video -> seasonByKey[video.bvid]?.let(onSeasonSelected) },
    keyFactory = { _, video -> video.bvid },
    footer = footer,
    focusRestoredItemKey = focusRestoredItemKey,
  )
}

private suspend fun LazyGridState.scrollItemIntoStablePosition(
  index: Int,
  totalItems: Int,
  fallbackItemHeightPx: Int,
  scrollInsetPx: Int,
  focusedRowTopPaddingPx: Int,
  focusScale: Float,
  smoothScroll: Boolean,
) {
  val layout = layoutInfo
  val columns = layout.estimatedColumnCount()
  val row = index / columns
  val lastRow = (totalItems - 1) / columns
  val rowStartIndex = row * columns
  val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
  val itemHeightPx = layout.visibleItemsInfo.firstOrNull { item -> item.index == index }?.size?.height
    ?: layout.visibleItemsInfo.firstOrNull()?.size?.height
    ?: fallbackItemHeightPx
  val focusOverflowPx = ((itemHeightPx * (focusScale - 1f)) / 2f).roundToInt()
  val edgeInsetPx = scrollInsetPx + focusOverflowPx
  val focusedItem = layout.visibleItemsInfo.firstOrNull { item -> item.index == index }
  if (focusedItem != null) {
    val itemTop = focusedItem.offset.y
    val viewportTop = layout.viewportStartOffset
    val viewportBottom = layout.viewportEndOffset - edgeInsetPx
    val targetTop = (layout.viewportStartOffset + focusedRowTopPaddingPx.coerceAtLeast(edgeInsetPx))
      .coerceAtMost(viewportBottom - focusedItem.size.height)
      .coerceAtLeast(viewportTop + edgeInsetPx)
    val scrollDelta = itemTop - targetTop
    if (kotlin.math.abs(scrollDelta) <= BiliMotion.FocusScrollMinDeltaPx) {
      return
    }
    if (smoothScroll) {
      animateScrollBy(scrollDelta.toFloat())
    } else {
      scroll {
        scrollBy(scrollDelta.toFloat())
      }
    }
    return
  }
  val maxTop = (viewportHeight - itemHeightPx - edgeInsetPx).coerceAtLeast(edgeInsetPx)
  val desiredTop = when (row) {
    0 -> edgeInsetPx
    lastRow -> maxTop
    else -> {
      ((viewportHeight - itemHeightPx) / 2).coerceIn(edgeInsetPx, maxTop)
    }
  }

  if (smoothScroll) {
    animateScrollToItem(index = rowStartIndex, scrollOffset = -focusedRowTopPaddingPx)
  } else {
    scrollToItem(index = rowStartIndex, scrollOffset = -focusedRowTopPaddingPx)
  }
}

private fun LazyGridState.targetIndexForDirection(
  fromIndex: Int,
  totalItems: Int,
  direction: Key,
): Int? {
  val columns = layoutInfo.estimatedColumnCount()
  val currentRow = fromIndex / columns
  val currentColumn = fromIndex % columns
  val lastIndex = totalItems - 1
  val lastRow = lastIndex / columns

  return when (direction) {
    Key.DirectionUp -> {
      if (currentRow == 0) {
        null
      } else {
        ((currentRow - 1) * columns + currentColumn).coerceAtMost(lastIndex)
      }
    }
    Key.DirectionDown -> {
      if (currentRow >= lastRow) {
        null
      } else {
        ((currentRow + 1) * columns + currentColumn).coerceAtMost(lastIndex)
      }
    }
    Key.DirectionLeft -> {
      if (currentColumn == 0) null else fromIndex - 1
    }
    Key.DirectionRight -> {
      val nextIndex = fromIndex + 1
      if (nextIndex > lastIndex || nextIndex / columns != currentRow) null else nextIndex
    }
    else -> null
  }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo.estimatedColumnCount(): Int {
  return visibleItemsInfo
    .map(LazyGridItemInfo::columnAnchor)
    .distinct()
    .count()
    .coerceAtLeast(1)
}

private val LazyGridItemInfo.columnAnchor: Int
  get() = offset.x

private fun List<VideoSummary>.appendUnique(nextVideos: List<VideoSummary>): List<VideoSummary> {
  if (nextVideos.isEmpty()) {
    return this
  }
  val knownKeys = mapIndexedTo(mutableSetOf()) { index, video -> video.feedKey(index) }
  return this + nextVideos.filterIndexed { index, video -> knownKeys.add(video.feedKey(index)) }
}

/** 把额外的本地条目(YouTube 历史)与 [base](B 站历史)按播放时间倒序混合。extra 为空时原样返回 base。 */
private fun mergeExtraVideos(base: List<VideoSummary>, extra: List<VideoSummary>): List<VideoSummary> {
  if (extra.isEmpty()) return base
  // 两者 viewAt 都是秒:YouTube=lastPlayedAtMs/1000,B站历史=viewAt(秒)。倒序排列,B站分页恒更旧,不重排已显示内容。
  return (extra + base).sortedByDescending { it.viewAt }
}

/** YouTube 历史条目 → 卡片模型。progress 填秒数供续播;viewAt 填播放时间(秒)与 B 站历史同单位供混合排序。 */
private fun YoutubeHistoryEntry.toVideoSummary(avatarFallback: String = ""): VideoSummary {
  return VideoSummary(
    bvid = videoId,
    title = title,
    pic = resolveThumbnailUrl(),
    ownerName = channelName,
    ownerFace = resolveChannelAvatarUrl(avatarFallback),
    ownerMid = 0L,
    view = 0,
    danmaku = 0,
    duration = (durationMs / 1000L).toInt(),
    pubdate = 0L,
    badge = "",
    progress = (positionMs / 1000L).toInt(),
    viewAt = lastPlayedAtMs / 1000L,
    source = SourceYoutube,
    channelId = channelId,
  )
}

private fun List<VideoSummary>.resolveFocusIndex(focusKey: String, fallbackIndex: Int): Int {
  val keyIndex = focusKey
    .takeIf { key -> key.isNotBlank() }
    ?.let { key -> indexOfFirst { video -> video.focusRestoreKey() == key } }
    ?.takeIf { index -> index >= 0 }
  return keyIndex ?: fallbackIndex.coerceIn(0, lastIndex)
}

private fun List<com.kirin.mt.core.network.FollowingSeason>.appendSeasonsUnique(
  nextSeasons: List<com.kirin.mt.core.network.FollowingSeason>,
): List<com.kirin.mt.core.network.FollowingSeason> {
  if (nextSeasons.isEmpty()) return this
  val knownIds = map { it.seasonId }.toMutableSet()
  return this + nextSeasons.filter { knownIds.add(it.seasonId) }
}

private fun VideoSummary.focusRestoreKey(): String {
  return bvid.ifBlank {
    when {
      cid > 0L -> "cid-$cid"
      historyPage > 0 -> "p-$historyPage"
      viewAt > 0L -> "view-$viewAt"
      else -> ""
    }
  }
}

private fun VideoSummary.feedKey(index: Int): String {
  return bvid.ifBlank {
    "cid-$cid-view-$viewAt-$index"
  }
}

private fun Int.shouldLoadMore(totalItems: Int, threshold: Int): Boolean {
  return totalItems - this <= threshold
}

private const val RestoreFocusRetryCount = 8

internal sealed interface UserFeedState {
  data object Loading : UserFeedState
  data object Empty : UserFeedState
  data class Failed(val message: String) : UserFeedState
 data class Success(
   val videos: List<VideoSummary> = emptyList(),
   val loadingMore: Boolean,
   val endReached: Boolean,
   val loadMoreError: String,
   val seasons: List<FollowingSeason> = emptyList(),
 ) : UserFeedState
}
