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

private const val ToViewPageSize = 30

private sealed interface ToViewContent {
  data object Loading : ToViewContent
  data object Empty : ToViewContent
  data class Failed(val message: String) : ToViewContent
  data class Success(
    val videos: List<VideoSummary>,
    val nextViewAt: Long,
    val nextMax: Long,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : ToViewContent
}

/**
 * 移动端"稍后再看"子 tab:B 站稍后再看列表 + viewAt/max 双游标分页(与历史一致)。
 * 需登录;未登录时外层 MobileFeedScreen 拦截并显示登录入口。复用 getToViewPage 与 MobileVideoCard。
 */
@Composable
fun MobileToViewPage(
  videoRepository: VideoRepository,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  var content by remember { mutableStateOf<ToViewContent>(ToViewContent.Loading) }

  suspend fun loadFirstPage() {
    content = ToViewContent.Loading
    content = try {
      val page = videoRepository.getToViewPage(pageSize = ToViewPageSize)
      if (page.videos.isEmpty()) {
        ToViewContent.Empty
      } else {
        ToViewContent.Success(
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
      ToViewContent.Failed(e.message.orEmpty().ifBlank { "加载失败" })
    }
  }

  LaunchedEffect(Unit) {
    loadFirstPage()
  }

  fun reloadFirst() {
    scope.launch { loadFirstPage() }
  }

  val gridState = rememberLazyGridState()

  fun loadNextPage() {
    val current = content as? ToViewContent.Success ?: return
    if (current.loadingMore || current.endReached) return
    val viewAtToLoad = current.nextViewAt
    val maxToLoad = current.nextMax
    content = current.copy(loadingMore = true)
    scope.launch {
      val next = try {
        val page = videoRepository.getToViewPage(
          pageSize = ToViewPageSize,
          viewAt = viewAtToLoad,
          max = maxToLoad,
        )
        val merged = (current.videos + page.videos).distinctBy { it.bvid }
        current.copy(
          videos = merged,
          nextViewAt = page.nextViewAt,
          nextMax = page.nextMax,
          loadingMore = false,
          endReached = !page.hasMore || merged.size == current.videos.size,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        current.copy(loadingMore = false)
      }
      content = next
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
      isRefreshing = content is ToViewContent.Loading,
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
        when (val s = content) {
          ToViewContent.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
          }
          ToViewContent.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.toview_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
          is ToViewContent.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.toview_failed_with_message, s.message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
              )
            }
          }
          is ToViewContent.Success -> {
            if (s.videos.isEmpty()) {
              item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                  modifier = Modifier.fillMaxWidth().padding(32.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    text = stringResource(R.string.toview_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                  )
                }
              }
            } else {
              items(s.videos, key = { it.bvid }) { video ->
                MobileVideoCard(video = video, onClick = onVideoSelected, onOpenOwner = onOpenOwner)
              }
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
