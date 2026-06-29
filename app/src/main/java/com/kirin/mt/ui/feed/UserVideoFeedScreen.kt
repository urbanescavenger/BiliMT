package com.kirin.mt.ui.feed

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.common.BiliCapsuleTabRow
import com.kirin.mt.ui.common.BiliPillTab
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.common.VideoGridSkeleton
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.home.TvVideoGrid
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

internal enum class UserFeedTab { Dynamic, History, Favorite }

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
  var state by mutableStateOf<UserFeedState>(UserFeedState.Loading)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
  var hasLoadedContent by mutableStateOf(false)
  var foldersLoaded by mutableStateOf(false)
  var handledManualRefreshKey by mutableIntStateOf(0)
}

@Stable
internal class UserFeedUiState {
  var selectedTab by mutableStateOf(UserFeedTab.Dynamic)
  val dynamic = DynamicFeedUiState()
  val history = HistoryFeedUiState()
  val favorite = FavoriteFeedUiState()
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
) {
  val coroutineScope = rememberCoroutineScope()
  val selectedTab = feedState.selectedTab

  LaunchedEffect(videoRepository, isLoggedIn, autoRefreshOnSwitch, selectedTab) {
    if (!isLoggedIn) return@LaunchedEffect
    when (selectedTab) {
      UserFeedTab.Dynamic -> loadDynamicFirstPage(
        videoRepository,
        feedState.dynamic,
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
    }

  LaunchedEffect(manualRefreshKey) {
    if (!isLoggedIn) return@LaunchedEffect
    val handledKey = when (selectedTab) {
      UserFeedTab.Dynamic -> feedState.dynamic.handledManualRefreshKey
      UserFeedTab.History -> feedState.history.handledManualRefreshKey
      UserFeedTab.Favorite -> feedState.favorite.handledManualRefreshKey
    }
    if (manualRefreshKey > 0 && manualRefreshKey != handledKey) {
      when (selectedTab) {
        UserFeedTab.Dynamic -> {
          feedState.dynamic.handledManualRefreshKey = manualRefreshKey
          loadDynamicFirstPage(videoRepository, feedState.dynamic, forceRefresh = true)
        }
        UserFeedTab.History -> {
          feedState.history.handledManualRefreshKey = manualRefreshKey
          loadHistoryFirstPage(videoRepository, feedState.history, forceRefresh = true)
        }
        UserFeedTab.Favorite -> {
          feedState.favorite.handledManualRefreshKey = manualRefreshKey
          loadFavoriteFolders(videoRepository, feedState.favorite, forceRefresh = true)
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
          UserFeedTab.Dynamic -> R.string.dynamic_signed_out
        },
      )
      FeedStatusScreen(message = message)
    } else {
      androidx.compose.runtime.key(selectedTab) {
        when (selectedTab) {
          UserFeedTab.Dynamic -> UserFeedContent(
            state = feedState.dynamic.state,
            loadingMessage = stringResource(R.string.dynamic_loading),
            emptyMessage = stringResource(R.string.dynamic_empty),
            failedMessage = { message -> stringResource(R.string.dynamic_failed_with_message, message) },
            cardMode = VideoCardMode.Dynamic,
            firstItemFocusRequester = firstItemFocusRequester,
            restoredFocusIndex = feedState.dynamic.focusedVideoIndex,
            restoredFocusKey = feedState.dynamic.focusedVideoKey,
            restoreFocusRequestKey = restoreFocusRequestKey,
            onRestoreFocusHandled = onRestoreFocusHandled,
            onFocusedIndexChange = { index, video ->
              feedState.dynamic.focusedVideoIndex = index
              feedState.dynamic.focusedVideoKey = video.focusRestoreKey()
            },
            onRetry = {
              coroutineScope.launch {
                loadDynamicFirstPage(videoRepository, feedState.dynamic, forceRefresh = true)
              }
            },
            onLoadMore = { loadDynamicNextPage(videoRepository, coroutineScope, feedState.dynamic) },
            onMoveUpFromFirstRow = { runCatching { tabFocusRequester.requestFocus() }.isSuccess },
            onMoveLeftToNav = onMoveLeftToNav,
            onVideoSelected = { video -> onVideoSelected(video, false) },
            onOwnerSelected = onOwnerSelected,
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
          )
        }
      }
    }
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
            UserFeedTab.Dynamic -> R.string.nav_dynamic
            UserFeedTab.History -> R.string.nav_history
            UserFeedTab.Favorite -> R.string.nav_favorite
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
    val page = videoRepository.getDynamicFeed()
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
) {
  val currentState = state.state as? UserFeedState.Success ?: return
  if (currentState.loadingMore || currentState.endReached) {
    return
  }

  val offsetToLoad = state.nextOffset
  state.state = currentState.copy(loadingMore = true, loadMoreError = "")
  coroutineScope.launch {
    state.state = try {
      val page = videoRepository.getDynamicFeed(offset = offsetToLoad)
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
    )
  }
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
    keyFactory = { index, video -> video.feedKey(index) },
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
    val videos: List<VideoSummary>,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: String,
  ) : UserFeedState
}
