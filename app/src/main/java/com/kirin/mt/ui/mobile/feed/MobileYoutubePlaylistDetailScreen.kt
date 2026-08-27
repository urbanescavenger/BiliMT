package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubeParsers
import com.kirin.mt.core.youtube.YoutubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import android.util.Log

/**
 * YouTube 播放列表详情页(点频道页播放列表卡进入,对齐 LibreTube 先列视频再选播)。
 *
 * 首屏拉 [getPlaylistVideos] 该播放列表视频 + 滚底 continuation 翻页;点某条视频时把
 * **已加载的整份列表**作为连播队列、从被点那条起播(onStartSelected)。点「播放全部」从第一条起播。
 * 播放列表卡的 browseId 来自 lockup 的 onTap.navigationEndpoint.browseEndpoint.browseId(形如 VL...)；
 * 播放列表视频条目是 playlistVideoRenderer,parseFeedPage 已收集。
 */
@Composable
internal fun MobileYoutubePlaylistDetailScreen(
  youtubeRepository: YoutubeRepository,
  playlist: YoutubeParsers.YoutubePlaylist,
  onStartSelected: (VideoSummary, List<VideoSummary>) -> Unit,
  onLongPress: ((VideoSummary) -> Unit)? = null,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  var items by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
  var description by remember { mutableStateOf<String?>(null) }
  var continuation by remember { mutableStateOf<String?>(null) }
  var loading by remember { mutableStateOf(true) }
  var loadingMore by remember { mutableStateOf(false) }
  var endReached by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf<String?>(null) }
  val gridState = rememberLazyGridState()

  fun loadFirst() {
    scope.launch {
      loading = true
      failed = null
      continuation = null
      loadingMore = false
      endReached = false
      try {
        val page = youtubeRepository.getPlaylistVideos(playlist.browseId)
        items = page.items.distinctBy { it.bvid }
        description = page.description
        continuation = page.continuation
        endReached = page.continuation == null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w("YtPlaylist", "loadFirst FAILED browseId=${playlist.browseId} err=${e.message}", e)
        failed = e.message.orEmpty().ifBlank { "加载失败" }
        items = emptyList()
        continuation = null
        endReached = true
      }
      loading = false
    }
  }

  fun loadNext() {
    val token = continuation
    if (token == null || loadingMore || endReached) return
    loadingMore = true
    scope.launch {
      try {
        val page = youtubeRepository.getPlaylistVideos(playlist.browseId, token)
        val old = items
        val merged = (old + page.items).distinctBy { it.bvid }
        items = merged
        continuation = page.continuation
        endReached = page.continuation == null || merged.size == old.size
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 翻页失败保留已加载内容
      }
      loadingMore = false
    }
  }

  LaunchedEffect(playlist.browseId) { loadFirst() }

  // 滚到底自动翻页(镜像频道页对 pair 去重,避免首屏 loading 消耗布尔 true)。
  LaunchedEffect(Unit) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      last to gridState.layoutInfo.totalItemsCount
    }
      .distinctUntilChanged()
      .collect { (last, total) ->
        if (total > 0 && last >= total - 6) loadNext()
      }
  }

  Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // 顶栏:返回 + 播放列表名。
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) { Text("‹") }
      Text(
        text = playlist.title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(start = 4.dp),
      )
    }
    PullToRefreshLayout(
      isRefreshing = loading,
      onRefresh = { loadFirst() },
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
        // 头部:视频数 + 「播放全部」。
        item(span = { GridItemSpan(maxLineSpan) }) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            if (playlist.videoCount.isNotBlank()) {
              Text(
                text = playlist.videoCount,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (items.isNotEmpty()) {
              OutlinedButton(onClick = { onStartSelected(items.first(), items) }) {
                Text("播放全部")
              }
            }
          }
        }
        // 播放列表简介(playlistHeaderRenderer.descriptionText,对齐 LibreTube)。可能较长,点开/收起。
        val desc = description
        if (!desc.isNullOrBlank()) {
          item(span = { GridItemSpan(maxLineSpan) }) {
            var expanded by remember { mutableStateOf(false) }
            val shown = if (expanded) desc else desc.take(120)
            Text(
              text = shown,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            )
          }
        }
        when {
          failed != null -> item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
              text = failed ?: "",
              color = MaterialTheme.colorScheme.error,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
          }
          loading -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
          items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
              text = "暂无视频",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
          }
          else -> {
            items(items, key = { it.bvid }) { video ->
              MobileVideoCard(
                video = video,
                onClick = { onStartSelected(video, items) },
                onOpenOwner = null,
                onLongPress = onLongPress,
                showPubdate = true,
              )
            }
            if (loadingMore) {
              item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                  CircularProgressIndicator()
                }
              }
            }
          }
        }
      }
    }
  }
}
