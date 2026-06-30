package com.kirin.mt.ui.feed

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.FollowingSeason
import com.kirin.mt.core.network.VideoRepository
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
}

@Stable
internal class DynamicFeedUiState {
  var nextOffset by mutableStateOf("")
  var state by mutableStateOf<UserFeedState>(UserFeedState.Loading)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
  var hasLoadedContent by mutableStateOf(false)
  var loadedOnce by mutableStateOf(false)
  var handledManualRefreshKey by mutableIntStateOf(0)
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

@Composable
internal fun UserFeedScreen(
  videoRepository: VideoRepository,
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
) {
  val coroutineScope = rememberCoroutineScope()
  val context = LocalContext.current
  val selectedTab = feedState.selectedTab
  var actionSheetVideo by remember { mutableStateOf<VideoSummary?>(null) }

  LaunchedEffect(videoRepository, isLoggedIn, autoRefreshOnSwitch, selectedTab) {
    if (!isLoggedIn) return@LaunchedEffect
    when (selectedTab) {
      UserFeedTab.DynamicVideo -> loadDynamicFirstPage(
        videoRepository,
        feedState.dynamicVideo,
        "video",
        forceRefresh = autoRefreshOnSwitch,
      )
      UserFeedTab.DynamicAll -> loadDynamicFirstPage(
        videoRepository,
        feedState.dynamicAll,
        "all",
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
    if (!isLoggedIn) return@LaunchedEffect
   val handledKey = when (selectedTab) {
     UserFeedTab.DynamicVideo -> feedState.dynamicVideo.handledManualRefreshKey
     UserFeedTab.DynamicAll -> feedState.dynamicAll.handledManualRefreshKey
     UserFeedTab.History -> feedState.history.handledManualRefreshKey
     UserFeedTab.Favorite -> feedState.favorite.handledManualRefreshKey
     UserFeedTab.Bangumi -> feedState.bangumi.handledManualRefreshKey
    }
    if (manualRefreshKey > 0 && manualRefreshKey != handledKey) {
      when (selectedTab) {
        UserFeedTab.DynamicVideo -> {
          feedState.dynamicVideo.handledManualRefreshKey = manualRefreshKey
          loadDynamicFirstPage(videoRepository, feedState.dynamicVideo, "video", forceRefresh = true)
        }
        UserFeedTab.DynamicAll -> {
          feedState.dynamicAll.handledManualRefreshKey = manualRefreshKey
          loadDynamicFirstPage(videoRepository, feedState.dynamicAll, "all", forceRefresh = true)
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
      onSelect = { tab -> if (tab != selectedTab) feedState.selectedTab = tab },
      tabFocusRequester = tabFocusRequester,
      onMoveLeftToNav = onMoveLeftToNav,
      onMoveDownToGrid = { runCatching { firstItemFocusRequester.requestFocus() }.isSuccess },
    )
    if (!isLoggedIn) {
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
      androidx.compose.runtime.key(selectedTab) {
        when (selectedTab) {
          UserFeedTab.DynamicVideo -> DynamicFeedContent(
            state = feedState.dynamicVideo,
            type = "video",
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
          UserFeedTab.History -> UserFeedContent(
            state = feedState.history.state,
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
          enabled = video.aid > 0L,
          onClick = { onCommentSelected(video) },
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
          enabled = video.ownerMid > 0L,
          onClick = { onOwnerSelected(video) },
        ),
      ),
      onDismiss = { actionSheetVideo = null },
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
  forceRefresh: Boolean,
) {
  if (!forceRefresh && state.loadedOnce) {
    return
  }

  state.state = UserFeedState.Loading
  state.focusedVideoIndex = 0
  state.focusedVideoKey = ""
  state.nextOffset = ""
  state.state = try {
    val page = videoRepository.getDynamicFeed(type = type)
    state.nextOffset = page.offset
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

  state.state = UserFeedState.Loading
  state.focusedVideoIndex = 0
  state.focusedVideoKey = ""
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

  state.state = UserFeedState.Loading
  state.focusedVideoIndex = 0
  state.focusedVideoKey = ""
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
  state.state = UserFeedState.Loading
  state.focusedVideoIndex = 0
  state.focusedVideoKey = ""
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
          loadDynamicFirstPage(videoRepository, state, type, forceRefresh = true)
        }
      },
      onLoadMore = { loadDynamicNextPage(videoRepository, coroutineScope, state, type) },
      onMoveUpFromFirstRow = { runCatching { tabFocusRequester.requestFocus() }.isSuccess },
      onMoveLeftToNav = onMoveLeftToNav,
      onVideoSelected = onVideoSelected,
      onOwnerSelected = onOwnerSelected,
      onCardLongPress = onCardLongPress,
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
            onMoveDownToGrid = { runCatching { firstItemFocusRequester.requestFocus() }.isSuccess },
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
) {
  when (state) {
    UserFeedState.Loading -> VideoGridSkeleton()
    UserFeedState.Empty -> FeedStatusScreen(message = emptyMessage)
    is UserFeedState.Failed -> FeedStatusScreen(
      message = failedMessage(state.message),
      actionLabel = stringResource(R.string.action_retry),
      onAction = onRetry,
    )
    is UserFeedState.Success -> UserFeedGrid(
      videos = state.videos,
      cardMode = cardMode,
      firstItemFocusRequester = firstItemFocusRequester,
      restoredFocusIndex = state.videos.resolveFocusIndex(
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
      footer = when {
        state.loadMoreError.isNotBlank() -> GridFooterState.Error(state.loadMoreError)
        state.loadingMore -> GridFooterState.Loading
        state.endReached -> GridFooterState.EndReached
        else -> GridFooterState.None
      },
    )
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
) {
  TvVideoGrid(
    videos = videos,
    cardMode = cardMode,
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
