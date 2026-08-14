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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
  uiState: MobileYoutubeChannelUiState,
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
  val name = uiState.name
  val avatar = uiState.avatar
  val items = uiState.items
  val loading = uiState.loading
  val loadingMore = uiState.loadingMore
  val failed = uiState.failed
  val gridState = uiState.gridState

  fun loadFirst() {
    scope.launch {
      uiState.loading = true
      uiState.failed = null
      try {
        val page = youtubeRepository.getChannelVideos(channelId)
        uiState.items = page.items.distinctBy { it.bvid }
        uiState.continuation = page.continuation
        uiState.endReached = page.continuation == null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        uiState.failed = e.message.orEmpty().ifBlank { "加载失败" }
        uiState.items = emptyList()
        uiState.continuation = null
        uiState.endReached = true
      }
      uiState.loading = false
    }
  }

  fun loadNext() {
    val token = uiState.continuation ?: return
    if (uiState.loadingMore || uiState.endReached) return
    uiState.loadingMore = true
    scope.launch {
      try {
        val page = youtubeRepository.getChannelVideos(channelId, token)
        val merged = (uiState.items + page.items).distinctBy { it.bvid }
        uiState.items = merged
        uiState.continuation = page.continuation
        uiState.endReached = page.continuation == null || merged.size == uiState.items.size
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 翻页失败保留已加载内容
      }
      uiState.loadingMore = false
    }
  }

  // 首屏:解析权威频道名 + 头像(失败回退卡片名/空头像) + 拉第一页(已加载过同 channelId 则跳过)。
  LaunchedEffect(channelId) {
    if (uiState.loadedChannelId != channelId) {
      uiState.name = channelName
      val resolved = runCatching { youtubeRepository.resolveChannel(channelId) }.getOrNull()
      uiState.name = resolved?.name?.ifBlank { channelName } ?: channelName
      uiState.avatar = resolved?.avatar.orEmpty()
      loadFirst()
      uiState.loadedChannelId = channelId
    } else {
      // 从播放器返回同 channelId:清除可能卡住的翻页 loading 标志(scope 已随离开组合取消)。
      uiState.loadingMore = false
    }
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

  // 频道页视频(channelId 为空)统一注入本频道 id + 名 + 头像,保证卡片 owner 点击留在本频道、
  // 头像显示本频道头像(lockupViewModel 不带 channelAvatarUrl,需从解析出的频道信息补)。
  val displayItems = items.map { video ->
    video.copy(
      channelId = if (video.channelId.isBlank()) channelId else video.channelId,
      ownerName = if (video.ownerName.isBlank()) name else video.ownerName,
      ownerFace = if (video.ownerFace.isBlank()) avatar else video.ownerFace,
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
                if (uiState.followLoading) return@Button
                uiState.followLoading = true
                scope.launch {
                  if (followed) {
                    youtubeChannelStore.remove(channelId)
                  } else {
                    youtubeChannelStore.add(YoutubeChannel(channelId = channelId, name = name))
                  }
                  uiState.followLoading = false
                }
              },
              enabled = !uiState.followLoading,
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
