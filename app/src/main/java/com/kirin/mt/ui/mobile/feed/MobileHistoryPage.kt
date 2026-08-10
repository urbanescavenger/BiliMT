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
import androidx.compose.runtime.collectAsState
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
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.youtube.YoutubeHistoryEntry
import com.kirin.mt.core.youtube.YoutubeHistoryStore
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
 * 移动端"历史"子 tab:本地 YouTube 历史(绿框)置顶 + B 站观看历史(双游标 viewAt/max 分页)。
 * YouTube 历史来自 [YoutubeHistoryStore](DataStore,免登录),未登录时也显示;B 站历史走
 * [VideoRepository.getHistoryPage] 需登录,未登录时跳过网络请求并显示登录提示。
 * 历史项 VideoSummary 已带 cid/progress/historyPage,toPlaybackRequest() 自动用 progress 续播。
 */
@Composable
fun MobileHistoryPage(
  videoRepository: VideoRepository,
  youtubeHistoryStore: YoutubeHistoryStore,
  isLoggedIn: Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  onLogin: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<HistoryState>(HistoryState.Loading) }
  var nextViewAt by remember { mutableStateOf(0L) }
  var nextMax by remember { mutableStateOf(0L) }
  val youtubeHistory by youtubeHistoryStore.history.collectAsState(initial = emptyList())
  val youtubeVideos = youtubeHistory.map { it.toVideoSummary() }

  suspend fun loadFirstBody() {
    state = HistoryState.Loading
    nextViewAt = 0L
    nextMax = 0L
    // 未登录:跳过 B 站网络请求(未授权会失败),只显示本地 YouTube 历史。
    if (!isLoggedIn) {
      state = HistoryState.Empty
      return
    }
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

  // key 用 isLoggedIn:登录态变化(未登录→登录)时重载 B 站历史。
  LaunchedEffect(isLoggedIn) { loadFirstBody() }

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
        // 本地 YouTube 历史置顶,绿框区分。
        items(youtubeVideos, key = { it.bvid }) { video ->
          MobileVideoCard(
            video = video,
            onClick = onVideoSelected,
            onOpenOwner = onOpenOwner,
            showYoutubeBorder = true,
          )
        }
        if (!isLoggedIn) {
          // 未登录:只显示本地 YouTube 历史 + 登录提示。
          item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                text = stringResource(R.string.history_signed_out),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
              Button(onClick = onLogin, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.mobile_login))
              }
            }
          }
        } else {
          when (val s = state) {
            HistoryState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
              Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
              ) { CircularProgressIndicator() }
            }
            HistoryState.Empty -> if (youtubeVideos.isEmpty()) {
              item(span = { GridItemSpan(maxLineSpan) }) {
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
}

/** 历史条目 → 卡片模型。progress 填秒数,toPlaybackRequest 据此续播;接近播完自动视为看完不续播。 */
private fun YoutubeHistoryEntry.toVideoSummary(): VideoSummary {
  return VideoSummary(
    bvid = videoId,
    title = title,
    pic = thumbnailUrl,
    ownerName = channelName,
    ownerFace = "",
    ownerMid = 0L,
    view = 0,
    danmaku = 0,
    duration = (durationMs / 1000L).toInt(),
    pubdate = 0L,
    badge = "",
    progress = (positionMs / 1000L).toInt(),
    source = SourceYoutube,
    channelId = channelId,
  )
}
