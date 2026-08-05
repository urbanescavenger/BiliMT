package com.kirin.mt.ui.mobile.space

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeChannelStore
import com.kirin.mt.core.youtube.YoutubeRepository
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 移动端 YouTube 频道主页:频道名 + 关注按钮 + 视频网格(continuation 分页)。
 * 镜像 MobileUserSpaceScreen;关注写入 YoutubeChannelStore(免登录)。点视频起播,
 * 卡片 owner 点击留在本频道。头部信息为"基础"档(名称 + 关注),不做头像/签名。
 */
@Composable
fun MobileYoutubeChannelScreen(
  youtubeRepository: YoutubeRepository,
  youtubeChannelStore: YoutubeChannelStore,
  channelId: String,
  channelName: String,
  onVideoSelected: (VideoSummary) -> Unit,
  onLongPress: ((VideoSummary) -> Unit)? = null,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val channels by youtubeChannelStore.channels.collectAsState(initial = emptyList())
  val followed = channels.any { it.channelId == channelId }
  var name by remember { mutableStateOf(channelName) }
  var followLoading by remember { mutableStateOf(false) }

  // 频道视频分页状态
  var items by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
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
      try {
        val page = youtubeRepository.getChannelVideos(channelId)
        items = page.items
        continuation = page.continuation
        endReached = page.continuation == null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failed = e.message.orEmpty().ifBlank { "加载失败" }
        items = emptyList()
        continuation = null
        endReached = true
      }
      loading = false
    }
  }

  fun loadNext() {
    val token = continuation ?: return
    if (loadingMore || endReached) return
    loadingMore = true
    scope.launch {
      try {
        val page = youtubeRepository.getChannelVideos(channelId, token)
        val merged = (items + page.items).distinctBy { it.bvid }
        items = merged
        continuation = page.continuation
        endReached = page.continuation == null || merged.size == items.size
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 翻页失败保留已加载内容
      }
      loadingMore = false
    }
  }

  // 首屏:解析权威频道名(失败回退卡片名) + 拉第一页
  LaunchedEffect(channelId) {
    name = runCatching { youtubeRepository.resolveChannel(channelId).name }
      .getOrDefault(channelName).ifBlank { channelName }
    loadFirst()
  }

  // 滚到底自动翻页
  LaunchedEffect(Unit) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) loadNext() }
  }

  // 频道页视频(channelId 为空)统一注入本频道 id + 名,保证卡片 owner 点击留在本频道。
  val displayItems = items.map { video ->
    video.copy(
      channelId = if (video.channelId.isBlank()) channelId else video.channelId,
      ownerName = if (video.ownerName.isBlank()) name else video.ownerName,
    )
  }

  PullToRefreshLayout(
    isRefreshing = loading,
    onRefresh = { loadFirst() },
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 160.dp),
      state = gridState,
      contentPadding = PaddingValues(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      // 头部:返回 + 频道名 + 关注按钮(跨整行)
      item(span = { GridItemSpan(maxLineSpan) }) {
        Column(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedButton(onClick = onBack) { Text("‹") }
            Text(
              text = name.ifBlank { channelName },
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.padding(start = 12.dp).weight(1f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Button(
              onClick = {
                if (followLoading) return@Button
                followLoading = true
                scope.launch {
                  if (followed) {
                    youtubeChannelStore.remove(channelId)
                  } else {
                    youtubeChannelStore.add(YoutubeChannel(channelId = channelId, name = name))
                  }
                  followLoading = false
                }
              },
              enabled = !followLoading,
            ) {
              Text(stringResource(if (followed) R.string.youtube_channel_following else R.string.youtube_channel_follow))
            }
          }
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
        displayItems.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
          Text(
            text = "暂无视频",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp),
          )
        }
        else -> {
          items(displayItems, key = { it.bvid }) { video ->
            MobileVideoCard(
              video = video,
              onClick = onVideoSelected,
              onOpenOwner = null,
              onLongPress = onLongPress,
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
