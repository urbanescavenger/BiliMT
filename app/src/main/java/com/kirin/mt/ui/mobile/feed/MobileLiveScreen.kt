package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.LiveRepository
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import com.kirin.mt.ui.player.toVideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FirstPage = 1

private sealed interface MobileLiveState {
  data object Loading : MobileLiveState
  data object Empty : MobileLiveState
  data class Failed(val message: String) : MobileLiveState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : MobileLiveState
}

@Stable
private class MobileLiveUiState {
  var state by mutableStateOf<MobileLiveState>(MobileLiveState.Loading)
  var loadRequest by mutableIntStateOf(0)
}

/**
 * 移动端直播列表(单一推荐流)。镜像 [com.kirin.mt.ui.mobile.home.MobileHomeScreen] 的单页结构
 * (PullToRefreshLayout + LazyVerticalGrid + 近底翻页),但无分区 tab。卡片复用 [MobileVideoCard],
 * 由 [com.kirin.mt.core.model.LiveRoom.toVideoSummary] 映射;点击走 [onVideoSelected] →
 * 壳层据 [VideoSummary.liveRoomId] 挂载 [com.kirin.mt.ui.player.LivePlayerScreen]。
 */
@Composable
fun MobileLiveScreen(
  liveRepository: LiveRepository,
  onVideoSelected: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState = remember { MobileLiveUiState() }
  val scope = rememberCoroutineScope()
  val gridState = rememberLazyGridState()

  LaunchedEffect(liveRepository) {
    if (uiState.loadRequest == 0) uiState.loadRequest = 1
  }
  LaunchedEffect(liveRepository, uiState.loadRequest) {
    if (uiState.loadRequest <= 0) return@LaunchedEffect
    uiState.state = MobileLiveState.Loading
    val nextState = try {
      val page = liveRepository.getLiveList(FirstPage)
      when {
        page.items.isEmpty() -> MobileLiveState.Empty
        else -> MobileLiveState.Success(
          videos = page.items.map { it.toVideoSummary() },
          nextPage = page.nextPage,
          loadingMore = false,
          endReached = !page.hasMore,
        )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      MobileLiveState.Failed(error.message.orEmpty().ifBlank { "加载失败" })
    }
    uiState.state = nextState
  }

  fun loadNextPage() {
    val current = uiState.state as? MobileLiveState.Success ?: return
    if (current.loadingMore || current.endReached) return
    uiState.state = current.copy(loadingMore = true)
    scope.launch {
      val next = try {
        val page = liveRepository.getLiveList(current.nextPage)
        val known = current.videos.map { it.liveRoomId }.toMutableSet()
        val merged = current.videos + page.items
          .map { it.toVideoSummary() }
          .filter { it.liveRoomId > 0L && known.add(it.liveRoomId) }
        current.copy(
          videos = merged,
          nextPage = page.nextPage,
          loadingMore = false,
          endReached = !page.hasMore || merged.size == current.videos.size,
        )
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        current.copy(loadingMore = false)
      }
      uiState.state = next
    }
  }

  // 近底自动加载下一页。
  LaunchedEffect(gridState) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) loadNextPage() }
  }

  PullToRefreshLayout(
    isRefreshing = uiState.state is MobileLiveState.Loading && uiState.loadRequest > 0,
    onRefresh = { uiState.loadRequest += 1 },
    modifier = modifier,
  ) {
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 160.dp),
      state = gridState,
      contentPadding = PaddingValues(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      when (val state = uiState.state) {
        MobileLiveState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
          Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
          ) { CircularProgressIndicator() }
        }
        MobileLiveState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
          Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
          ) { Text(stringResource(R.string.live_empty)) }
        }
        is MobileLiveState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
          Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              stringResource(R.string.live_failed_with_message, state.message),
              modifier = Modifier
                .fillMaxWidth()
                .clickable { uiState.loadRequest += 1 }
                .padding(8.dp),
            )
          }
        }
        is MobileLiveState.Success -> {
          items(state.videos, key = { it.liveRoomId }) { video ->
            MobileVideoCard(video = video, onClick = onVideoSelected, onOpenOwner = null)
          }
          if (state.loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
              ) { CircularProgressIndicator() }
            }
          }
        }
      }
    }
  }
}