package com.kirin.mt.ui.mobile.feed

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val HistoryPageSize = 30

private sealed interface HistoryState {
  data object Loading : HistoryState
  data object Empty : HistoryState
  data class Failed(val message: String) : HistoryState
  data class Success(
    val videos: List<VideoSummary>,
    val nextViewAt: Long,
    val nextMax: Long,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : HistoryState
}

/**
 * 移动端"历史"子 tab:观看历史视频网格 + 双游标(viewAt/max)分页。复用
 * VideoRepository.getHistoryPage 与 MobileVideoCard。历史项 VideoSummary 已带 cid/progress/
 * historyPage,toPlaybackRequest() 自动用 progress 作 startPositionMs 续播。
 */
@Composable
fun MobileHistoryPage(
  videoRepository: VideoRepository,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<HistoryState>(HistoryState.Loading) }
  var nextViewAt by remember { mutableStateOf(0L) }
  var nextMax by remember { mutableStateOf(0L) }

  suspend fun loadFirstBody() {
    state = HistoryState.Loading
    nextViewAt = 0L
    nextMax = 0L
    state = try {
      val page = videoRepository.getHistoryPage(pageSize = HistoryPageSize)
      nextViewAt = page.nextViewAt
      nextMax = page.nextMax
      if (page.videos.isEmpty()) {
        HistoryState.Empty
      } else {
        HistoryState.Success(
          videos = page.videos,
          nextViewAt = page.nextViewAt,
          nextMax = page.nextMax,
          loadingMore = false,
          endReached = !page.hasMore,
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      HistoryState.Failed(e.message.orEmpty().ifBlank { "加载失败" })
    }
  }

  LaunchedEffect(Unit) { loadFirstBody() }

  fun reloadFirst() {
    scope.launch { loadFirstBody() }
  }

  val gridState = rememberLazyGridState()

  fun loadNextPage() {
    val current = state as? HistoryState.Success ?: return
    if (current.loadingMore || current.endReached) return
    val viewAt = nextViewAt
    val max = nextMax
    state = current.copy(loadingMore = true)
    scope.launch {
      val next = try {
        val page = videoRepository.getHistoryPage(pageSize = HistoryPageSize, viewAt = viewAt, max = max)
        nextViewAt = page.nextViewAt
        nextMax = page.nextMax
        val merged = (current.videos + page.videos).distinctBy { it.bvid }
        current.copy(
          videos = merged,
          loadingMore = false,
          endReached = !page.hasMore || merged.size == current.videos.size,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        current.copy(loadingMore = false)
      }
      state = next
    }
  }

  LaunchedEffect(Unit) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) loadNextPage() }
  }

  Box(modifier = modifier.fillMaxSize()) {
    PullToRefreshLayout(
      isRefreshing = state is HistoryState.Loading,
      onRefresh = { reloadFirst() },
      modifier = Modifier.fillMaxSize(),
    ) {
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        when (val s = state) {
          HistoryState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
          }
          HistoryState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
          is HistoryState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.history_failed_with_message, s.message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
              )
            }
          }
          is HistoryState.Success -> {
            items(s.videos, key = { it.bvid }) { video ->
              MobileVideoCard(video = video, onClick = onVideoSelected, onOpenOwner = onOpenOwner)
            }
            if (s.loadingMore) {
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
}