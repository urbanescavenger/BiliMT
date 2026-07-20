package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.kirin.mt.core.network.FavoriteFolder
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FavoritePageSize = 20

private sealed interface FavoriteContent {
  data object Loading : FavoriteContent
  data object Empty : FavoriteContent
  data class Failed(val message: String) : FavoriteContent
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : FavoriteContent
}

/**
 * 移动端"收藏"子 tab:收藏夹切换 + 收藏夹视频网格 + page 分页。进入时 currentMid() ->
 * getFavoriteFolders(mid),默认选首个收藏夹(全部),顶部 LazyRow 收藏夹 chip 切换。
 * 复用 getFavoriteFolderVideos 与 MobileVideoCard。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileFavoritePage(
  videoRepository: VideoRepository,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  var folders by remember { mutableStateOf<List<FavoriteFolder>>(emptyList()) }
  var foldersLoading by remember { mutableStateOf(true) }
  var currentMediaId by remember { mutableStateOf(0L) }
  var content by remember { mutableStateOf<FavoriteContent>(FavoriteContent.Loading) }

  suspend fun loadFirstVideos(mediaId: Long) {
    content = FavoriteContent.Loading
    content = try {
      val page = videoRepository.getFavoriteFolderVideos(mediaId = mediaId, page = 1, pageSize = FavoritePageSize)
      if (page.videos.isEmpty()) {
        FavoriteContent.Empty
      } else {
        FavoriteContent.Success(
          videos = page.videos,
          nextPage = 2,
          loadingMore = false,
          endReached = !page.hasMore,
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      FavoriteContent.Failed(e.message.orEmpty().ifBlank { "加载失败" })
    }
  }

  // 首次进入:拉收藏夹列表,默认选首个并加载其视频。
  LaunchedEffect(Unit) {
    foldersLoading = true
    val list = try {
      val mid = videoRepository.currentMid()
      if (mid > 0L) videoRepository.getFavoriteFolders(mid) else emptyList()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptyList()
    }
    folders = list
    foldersLoading = false
    if (list.isEmpty()) {
      content = FavoriteContent.Empty
    } else {
      currentMediaId = list.first().mediaId
      loadFirstVideos(currentMediaId)
    }
  }

  fun selectFolder(mediaId: Long) {
    if (mediaId == currentMediaId) return
    currentMediaId = mediaId
    scope.launch { loadFirstVideos(mediaId) }
  }

  fun reloadFirst() {
    if (currentMediaId > 0L) scope.launch { loadFirstVideos(currentMediaId) }
  }

  val gridState = rememberLazyGridState()

  fun loadNextPage() {
    val current = content as? FavoriteContent.Success ?: return
    if (current.loadingMore || current.endReached || currentMediaId <= 0L) return
    val pageToLoad = current.nextPage
    content = current.copy(loadingMore = true)
    scope.launch {
      val next = try {
        val page = videoRepository.getFavoriteFolderVideos(
          mediaId = currentMediaId,
          page = pageToLoad,
          pageSize = FavoritePageSize,
        )
        val merged = (current.videos + page.videos).distinctBy { it.bvid }
        current.copy(
          videos = merged,
          nextPage = pageToLoad + 1,
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

  LaunchedEffect(currentMediaId) {
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
      isRefreshing = content is FavoriteContent.Loading,
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
        // 收藏夹切换行:全宽 header item,随内容滚动。
        if (folders.isNotEmpty()) {
          item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              contentPadding = PaddingValues(bottom = 4.dp),
            ) {
              lazyItems(folders, key = { it.mediaId }) { folder ->
                FilterChip(
                  selected = folder.mediaId == currentMediaId,
                  onClick = { selectFolder(folder.mediaId) },
                  label = { Text(folder.title) },
                  colors = FilterChipDefaults.filterChipColors(),
                )
              }
            }
          }
        }

        when (val s = content) {
          FavoriteContent.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
          }
          FavoriteContent.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.favorite_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
          is FavoriteContent.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.favorite_failed_with_message, s.message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
              )
            }
          }
          is FavoriteContent.Success -> {
            if (s.videos.isEmpty()) {
              item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                  modifier = Modifier.fillMaxWidth().padding(32.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    text = stringResource(R.string.favorite_empty),
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