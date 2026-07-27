package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
private const val SkeletonCount = 6

private sealed interface MobileLiveState {
  data object Loading : MobileLiveState
  data object Empty : MobileLiveState
  data class Failed(val message: String) : MobileLiveState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: String? = null,
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
 *
 * 顶部"直播"标题条;加载态骨架屏;失败态带"重试"按钮;翻页失败底部提示可点重试;
 * 卡片头像/UP 名可点进 UP 空间([onOpenOwner])。
 */
@Composable
fun MobileLiveScreen(
  liveRepository: LiveRepository,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
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
    uiState.state = current.copy(loadingMore = true, loadMoreError = null)
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
          loadMoreError = null,
        )
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        current.copy(loadingMore = false, loadMoreError = error.message.orEmpty().ifBlank { "加载更多失败" })
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
      // 顶部"直播"标题条(失败全屏态除外,失败态独占居中)。
      if (uiState.state !is MobileLiveState.Failed) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { LiveHeader() }
      }

      when (val state = uiState.state) {
        MobileLiveState.Loading -> {
          items(SkeletonCount) { LiveSkeletonCard() }
        }
        MobileLiveState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
          Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
          ) { Text(stringResource(R.string.live_empty)) }
        }
        is MobileLiveState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = stringResource(R.string.live_failed_with_message, state.message),
              color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { uiState.loadRequest += 1 }) {
              Text(stringResource(R.string.live_retry))
            }
          }
        }
        is MobileLiveState.Success -> {
          items(state.videos, key = { it.liveRoomId }) { video ->
            MobileVideoCard(video = video, onClick = onVideoSelected, onOpenOwner = onOpenOwner)
          }
          if (state.loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
              ) { CircularProgressIndicator() }
            }
          } else if (state.loadMoreError != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { loadNextPage() }
                  .padding(16.dp),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = stringResource(R.string.live_load_more_failed),
                  color = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LiveHeader() {
  Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
    Text(
      text = stringResource(R.string.live_title),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = stringResource(R.string.live_subtitle),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 骨架占位卡片:圆角封面占位 + 两行文字占位。 */
@Composable
private fun LiveSkeletonCard() {
  val placeholder = MaterialTheme.colorScheme.surfaceVariant
  Column(modifier = Modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 10f)
        .clip(RoundedCornerShape(12.dp))
        .background(placeholder),
    )
    Spacer(modifier = Modifier.height(6.dp))
    Box(
      modifier = Modifier
        .fillMaxWidth(0.9f)
        .height(14.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(placeholder),
    )
    Spacer(modifier = Modifier.height(4.dp))
    Box(
      modifier = Modifier
        .fillMaxWidth(0.5f)
        .height(12.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(placeholder),
    )
  }
}