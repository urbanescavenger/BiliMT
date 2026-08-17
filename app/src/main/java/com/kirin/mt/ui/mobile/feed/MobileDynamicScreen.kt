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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.network.YoutubeFeedCacheTtlMs
import com.kirin.mt.core.network.mergeByPubdate
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeChannelStore
import com.kirin.mt.core.youtube.YoutubeFeedCacheStore
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileDynamicScreen(
  videoRepository: VideoRepository,
  youtubeFeedCacheStore: YoutubeFeedCacheStore,
  youtubeChannelStore: YoutubeChannelStore,
  isLoggedIn: Boolean,
  dynamicRefreshKey: Int = 0,
  youtubeChannels: List<YoutubeChannel>,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  onLogin: () -> Unit,
  modifier: Modifier = Modifier,
  onLongPress: ((VideoSummary) -> Unit)? = null,
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
  // YouTube 关注拉取超时/失败提示:true 时网格顶部显示提示条(区别于静默空)。
  var youtubeTimeoutNotice by remember { mutableStateOf(false) }
  // 请求去重:已有拉取在进行则跳过,避免重复点击底栏/下拉并发重拉全量频道。
  var feedJob by remember { mutableStateOf<Job?>(null) }
  // 保留旧数据刷新时驱动下拉指示器(区别于初始 Loading 的网格内 spinner)。
  var isRefreshing by remember { mutableStateOf(false) }

  /** 把 YouTube 流合并进当前 B 站动态(先去掉旧 YouTube 部分再合并,保证缓存秒出+网络刷新不重复)。 */
  fun mergeYoutube(yt: List<VideoSummary>) {
    when (val cur = state) {
      is DynamicState.Success -> {
        val biliOnly = cur.videos.filterNot { it.source == SourceYoutube }
        state = cur.copy(videos = mergeByPubdate(biliOnly, yt))
      }
      is DynamicState.Empty -> state = DynamicState.Success(yt, loadingMore = false, endReached = true)
      else -> {} // Failed / Loading 保持原样
    }
  }

  suspend fun loadFirstBody() {
    // 保留旧数据后台刷新:有旧 Success 时不闪 Loading,由 isRefreshing 驱动下拉指示器;
    // 无旧数据(首次进入)才显示网格内 Loading spinner。
    val prev = state as? DynamicState.Success
    if (prev == null) {
      state = DynamicState.Loading
    } else {
      isRefreshing = true
    }
    nextOffset = ""
    youtubeTimeoutNotice = false
    try {
      state = try {
        val page = videoRepository.getDynamicFeed(type = "video")
        nextOffset = page.offset
        when {
          page.videos.isEmpty() -> prev ?: DynamicState.Empty
          else -> DynamicState.Success(
            videos = page.videos,
            loadingMore = false,
            endReached = !page.hasMore,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 失败保留旧数据兜底,避免刷新失败清空列表。
        prev ?: DynamicState.Failed(e.message.orEmpty().ifBlank { "加载失败" })
      }
    } finally {
      isRefreshing = false
    }

    // 合并 YouTube 关注:先读缓存秒出(10min 内),后台分批增量拉网络刷新并每批写缓存;
    // 全部频道拉完仍空才用缓存兜底(即使过期)。无频道时不产生额外加载。
    if (youtubeChannels.isNotEmpty()) {
      val currentIds = youtubeChannels.map { it.channelId }
      val cached = youtubeFeedCacheStore.read()
      val cacheValid = cached != null && cached.channelIds == currentIds
      val cacheFresh = cacheValid && System.currentTimeMillis() - cached.fetchedAt <= YoutubeFeedCacheTtlMs
      if (cacheFresh && cached.videos.isNotEmpty()) {
        mergeYoutube(cached.videos) // 秒出
      }
      scope.launch {
        val accumulator = mutableListOf<VideoSummary>()
        try {
          val result = videoRepository.youtubeSubscriptionsFeed(
            youtubeChannels,
            onChannelAvatarResolved = { channel ->
              youtubeChannelStore.updateAvatar(channel.channelId, channel.avatar)
            },
            onChunkReady = { chunk ->
              // 增量:先累积再 merge 全量 accumulator——mergeYoutube 内部会 filterNot 掉 state 里所有旧
              // YouTube 再合并传入列表,若传单批 chunk 则后批覆盖前批(二次覆盖 bug)。传累积全量才不丢。
              // 缓存写是后台 IO(onChunkReady 是非挂起回调,不能直接 suspend;writeChannel 内部走 Room,
              // 用独立协程异步写,不阻塞主线程 merge)。
              if (chunk.isNotEmpty()) {
                accumulator += chunk
                mergeYoutube(accumulator)
                chunk.groupBy { it.channelId }.forEach { (channelId, videos) ->
                  scope.launch { youtubeFeedCacheStore.writeChannel(channelId, videos) }
                }
              }
            },
          )
          if (accumulator.isEmpty()) {
            // 全部频道拉完仍无内容 → 缓存兜底(即使过期)。
            if (cacheValid && cached.videos.isNotEmpty()) mergeYoutube(cached.videos)
          } else {
            youtubeTimeoutNotice = false
            // 每批已 writeChannel,收尾再写一次全量(保证读缓存时 channelIds 集合与当前列表一致,
            // 且覆盖批次间去重后的最终列表)。
            youtubeFeedCacheStore.write(currentIds, accumulator)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // 意外失败:提示 + 缓存兜底(即使过期)。
          youtubeTimeoutNotice = true
          if (cacheValid && cached.videos.isNotEmpty()) mergeYoutube(cached.videos)
        }
      }
    }
  }

  /** 去重入口:feedJob 活跃则跳过,否则启动一次完整刷新。 */
  fun refreshFeed() {
    if (feedJob?.isActive == true) return
    feedJob = scope.launch { loadFirstBody() }
  }

  // 首次进入 + 每次点击底栏"动态"tab(dynamicRefreshKey 自增)都刷新 B 站 + YouTube 关注。
  LaunchedEffect(isLoggedIn, dynamicRefreshKey) {
    if (!isLoggedIn) return@LaunchedEffect
    refreshFeed()
  }

  fun reloadFirst() {
    refreshFeed()
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
    // PullToRefreshLayout 提到 when 外,isRefreshing 顶层求值真值;刷新时 state→Loading 不再卸载容器,
    // 列表滚动位置与指示器保留,各状态内联为 grid item(照 MobileUserSpaceScreen 范式)。
    PullToRefreshLayout(
      isRefreshing = isRefreshing,
      onRefresh = { reloadFirst() },
      modifier = Modifier.fillMaxSize(),
    ) {
      LazyVerticalGrid(
        // 动态 feed 单列:卡片占满整行,配 feedLayout 的 B 站动态样式(顶行作者块+缩略图+标题)。
        columns = GridCells.Fixed(1),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        when (val s = state) {
          DynamicState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
          }
          DynamicState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.mobile_dynamic_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
          is DynamicState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
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
          }
          is DynamicState.Success -> {
            if (youtubeTimeoutNotice) {
              item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                  text = "YouTube 关注加载超时",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
              }
            }
            items(s.videos, key = { it.bvid }) { video ->
              MobileVideoCard(
                video = video,
                onClick = onVideoSelected,
                onOpenOwner = onOpenOwner,
                onLongPress = onLongPress,
                showYoutubeBorder = true,
                feedLayout = true,
              )
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