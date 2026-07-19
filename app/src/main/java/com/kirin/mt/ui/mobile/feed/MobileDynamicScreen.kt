package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
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
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private sealed interface DynamicState {
  data object Loading : DynamicState
  data object Empty : DynamicState
  data class Failed(val message: String) : DynamicState
  data class Success(
    val videos: List<VideoSummary>,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : DynamicState
}

/**
 * 移动端动态 tab:关注动态视频网格 + offset 分页。复用 VideoRepository.getDynamicFeed
 * 与 MobileVideoCard。未登录时显示登录入口。
 */
@Composable
fun MobileDynamicScreen(
  videoRepository: VideoRepository,
  isLoggedIn: Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onLogin: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!isLoggedIn) {
    Column(
      modifier = modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.mobile_account_signed_out),
        style = MaterialTheme.typography.titleMedium,
      )
      Button(onClick = onLogin, modifier = Modifier.padding(top = 16.dp)) {
        Text(stringResource(R.string.mobile_login))
      }
    }
    return
  }

  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<DynamicState>(DynamicState.Loading) }
  var nextOffset by remember { mutableStateOf("") }

  LaunchedEffect(isLoggedIn) {
    if (!isLoggedIn) return@LaunchedEffect
    state = DynamicState.Loading
    nextOffset = ""
    state = try {
      val page = videoRepository.getDynamicFeed(type = "video")
      nextOffset = page.offset
      if (page.videos.isEmpty()) {
        DynamicState.Empty
      } else {
        DynamicState.Success(
          videos = page.videos,
          loadingMore = false,
          endReached = !page.hasMore,
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      DynamicState.Failed(e.message.orEmpty().ifBlank { "加载失败" })
    }
  }

  val gridState = rememberLazyGridState()

  fun loadNextPage() {
    val current = state as? DynamicState.Success ?: return
    if (current.loadingMore || current.endReached) return
    val offsetToLoad = nextOffset
    state = current.copy(loadingMore = true)
    scope.launch {
      val next = try {
        val page = videoRepository.getDynamicFeed(offset = offsetToLoad, type = "video")
        nextOffset = page.offset
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

  LaunchedEffect(isLoggedIn) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) loadNextPage() }
  }

  Box(modifier = modifier.fillMaxSize()) {
    when (val s = state) {
      DynamicState.Loading -> CircularProgressIndicator(
        modifier = Modifier.align(Alignment.Center),
      )
      DynamicState.Empty -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.mobile_dynamic_empty),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
      is DynamicState.Failed -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = s.message,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.error,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(24.dp),
        )
      }
      is DynamicState.Success -> LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(s.videos, key = { it.bvid }) { video ->
          MobileVideoCard(video = video, onClick = onVideoSelected)
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