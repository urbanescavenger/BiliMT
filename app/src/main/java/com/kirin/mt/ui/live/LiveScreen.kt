package com.kirin.mt.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.LiveRepository
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.common.VideoGridSkeleton
import com.kirin.mt.ui.common.focusRestoreKey
import com.kirin.mt.ui.common.resolveFocusIndex
import com.kirin.mt.ui.home.TvVideoGrid
import com.kirin.mt.ui.player.toVideoSummary
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val FirstPage = 1

/** 直播列表加载状态。Success 内联分页字段(单列表,无分区 map)。 */
internal sealed interface LiveState {
  data object Loading : LiveState
  data object Empty : LiveState
  data class Failed(val message: String) : LiveState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: String,
  ) : LiveState
}

@Stable
internal class LiveUiState {
  var state by mutableStateOf<LiveState>(LiveState.Loading)
  var loadRequest by mutableIntStateOf(0)
  var focusedIndex by mutableIntStateOf(0)
  var focusedKey by mutableStateOf("")
  var focusFirstItemKey by mutableIntStateOf(0)
  var handledManualRefreshKey by mutableIntStateOf(0)
}

/**
 * TV 直播列表(单一推荐流)。镜像 [com.kirin.mt.ui.home.RecommendScreen] 的结构但去掉分区 tab:
 * 复用 [TvVideoGrid] 的 D-pad 焦点/分页机制,卡片由 [LiveRoom.toVideoSummary] 映射而来,
 * 点击走 [onVideoSelected] → 壳层据 [VideoSummary.liveRoomId] 挂载 [com.kirin.mt.ui.player.LivePlayerScreen]。
 */
@Composable
internal fun LiveScreen(
  liveRepository: LiveRepository,
  uiState: LiveUiState,
  firstItemFocusRequester: FocusRequester,
  manualRefreshKey: Int,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  requestInitialFocus: Boolean,
  onInitialFocusRequested: () -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
) {
  val coroutineScope = rememberCoroutineScope()

  // 首次进入 / 手动刷新触发首页加载。
  LaunchedEffect(liveRepository) {
    if (uiState.loadRequest == 0) uiState.loadRequest = 1
  }
  LaunchedEffect(liveRepository, uiState.loadRequest) {
    if (uiState.loadRequest <= 0) return@LaunchedEffect
    uiState.state = LiveState.Loading
    uiState.focusedIndex = 0
    uiState.focusedKey = ""
    val nextState = try {
      val page = liveRepository.getLiveList(FirstPage)
      if (page.items.isEmpty()) {
        LiveState.Empty
      } else {
        LiveState.Success(
          videos = page.items.map { it.toVideoSummary() },
          nextPage = page.nextPage,
          loadingMore = false,
          endReached = !page.hasMore,
          loadMoreError = "",
        )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      LiveState.Failed(error.message.orEmpty())
    }
    uiState.state = nextState
  }

  // 侧边栏同 tab 二次点击 → 手动刷新。
  LaunchedEffect(manualRefreshKey) {
    if (manualRefreshKey <= 0 || manualRefreshKey == uiState.handledManualRefreshKey) return@LaunchedEffect
    uiState.handledManualRefreshKey = manualRefreshKey
    uiState.loadRequest += 1
  }

  fun loadNextPage() {
    val current = uiState.state as? LiveState.Success ?: return
    if (current.loadingMore || current.endReached) return
    val pageToLoad = current.nextPage
    uiState.state = current.copy(loadingMore = true, loadMoreError = "")
    coroutineScope.launch {
      val nextState = try {
        val page = liveRepository.getLiveList(pageToLoad)
        val known = current.videos.map { it.liveRoomId }.toMutableSet()
        val merged = current.videos + page.items
          .map { it.toVideoSummary() }
          .filter { it.liveRoomId > 0L && known.add(it.liveRoomId) }
        val latest = uiState.state as? LiveState.Success ?: return@launch
        latest.copy(
          videos = merged,
          nextPage = page.nextPage,
          loadingMore = false,
          endReached = !page.hasMore || merged.size == latest.videos.size,
          loadMoreError = "",
        )
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        val latest = uiState.state as? LiveState.Success ?: return@launch
        latest.copy(loadingMore = false, loadMoreError = error.message.orEmpty())
      }
      uiState.state = nextState
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    LiveHeader()
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = BiliSpacing.Xs),
    ) {
      when (val currentState = uiState.state) {
        LiveState.Loading -> VideoGridSkeleton()
        LiveState.Empty -> FeedStatusScreen(message = stringResource(R.string.live_empty))
        is LiveState.Failed -> FeedStatusScreen(
          message = stringResource(R.string.live_failed_with_message, currentState.message),
          actionLabel = stringResource(R.string.action_retry),
          onAction = { uiState.loadRequest += 1 },
        )
        is LiveState.Success -> {
          val restoredFocusIndex = currentState.videos.resolveFocusIndex(
            focusKey = uiState.focusedKey,
            fallbackIndex = uiState.focusedIndex,
          )
          TvVideoGrid(
            videos = currentState.videos,
            firstItemFocusRequester = firstItemFocusRequester,
            restoredFocusIndex = restoredFocusIndex,
            restoreFocusRequestKey = restoreFocusRequestKey,
            onRestoreFocusHandled = onRestoreFocusHandled,
            requestInitialFocus = requestInitialFocus,
            onInitialFocusRequested = onInitialFocusRequested,
            focusFirstItemKey = uiState.focusFirstItemKey,
            onFocusedIndexChange = { index, video ->
              uiState.focusedIndex = index
              uiState.focusedKey = video.focusRestoreKey()
            },
            onLoadMore = ::loadNextPage,
            onMoveLeftToNav = onMoveLeftToNav,
            onMoveUpFromFirstRow = { true },
            onVideoSelected = onVideoSelected,
            onOwnerSelected = { },
            onCardLongPress = { },
            keyFactory = { _, video -> video.liveRoomId },
            topPadding = BiliSizing.HomeVideoGridTopPadding + BiliSizing.HomeVideoGridTopBleed,
            topBleed = BiliSizing.HomeVideoGridTopBleed,
          )
        }
      }
    }
  }
}

@Composable
private fun LiveHeader() {
  // 单一推荐流,无分区 tab;留一个标题条与首页网格顶部对齐。
  Text(
    text = stringResource(R.string.nav_live),
    color = com.kirin.mt.ui.theme.BiliColors.TextPrimary,
    fontSize = com.kirin.mt.ui.theme.BiliTypography.SectionTitle,
    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = BiliSpacing.Lg, top = BiliSpacing.Md, bottom = BiliSpacing.Xs),
  )
}