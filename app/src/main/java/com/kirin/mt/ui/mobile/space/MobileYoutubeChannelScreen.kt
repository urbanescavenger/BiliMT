package com.kirin.mt.ui.mobile.space

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeChannelStore
import com.kirin.mt.core.youtube.YoutubeConstants
import com.kirin.mt.core.youtube.YoutubeParsers
import com.kirin.mt.core.youtube.YoutubeRepository
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import com.kirin.mt.ui.mobile.home.formatCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 移动端 YouTube 频道主页:频道名 + 关注按钮 + 视频网格(continuation 分页)。
 * 镜像 MobileUserSpaceScreen;关注写入 YoutubeChannelStore(免登录)。点视频起播,
 * 卡片 owner 点击留在本频道。头部信息为"基础"档(名称 + 关注),不做头像/签名。
 */
@Composable
internal fun MobileYoutubeChannelScreen(
  youtubeRepository: YoutubeRepository,
  youtubeChannelStore: YoutubeChannelStore,
  uiState: MobileYoutubeChannelUiState,
  channelId: String,
  channelName: String,
  onVideoSelected: (VideoSummary) -> Unit,
  onLongPress: ((VideoSummary) -> Unit)? = null,
  onStartPlaylist: (List<VideoSummary>) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val channels by youtubeChannelStore.channels.collectAsState(initial = emptyList())
  val followed = channels.any { it.channelId == channelId }
  val name = uiState.name
  val avatar = uiState.avatar
  val items = uiState.items
  val order = uiState.order
  val tab = uiState.tab
  val loading = uiState.loading
  val loadingMore = uiState.loadingMore
  val failed = uiState.failed
  val gridState = uiState.gridState

  // 当前内容的 /browse params:Videos Tab 用排序(最新/最热)params;Shorts/直播用服务端提供的
  // tab params(有则用,对齐 LibreTube 从 header 取;无则回退硬编码)。硬编码对部分频道/新布局失效。
  fun channelParams(): String {
    if (uiState.tab.hasSort) return uiState.order.params
    val keys = when (uiState.tab) {
      YoutubeConstants.ChannelContentTab.Videos -> listOf("videos")
      YoutubeConstants.ChannelContentTab.Shorts -> listOf("shorts")
      YoutubeConstants.ChannelContentTab.Live -> listOf("streams", "live")
      YoutubeConstants.ChannelContentTab.Playlists -> listOf("playlists")
    }
    return keys.firstNotNullOfOrNull { uiState.serverTabParams[it] } ?: uiState.tab.params
  }

  // Shorts/直播走系统生成播放列表 browseId(UUSH/UULV,布局无关、全频道可靠)。新布局频道页
  // 初始响应无这些 tab(懒加载),服务端 tab params 取不到时硬编码又失效,直接 browse 系统播放列表
  // 拿到对应内容(修「Shorts 显示热门视频/直播空」)。视频/播放列表仍用 channelId + params。
  fun channelBrowseId(): String? = when (uiState.tab) {
    YoutubeConstants.ChannelContentTab.Shorts ->
      YoutubeConstants.channelSystemBrowseId(channelId, YoutubeConstants.ChannelShortsSystemPlaylistPrefix)
    YoutubeConstants.ChannelContentTab.Live ->
      YoutubeConstants.channelSystemBrowseId(channelId, YoutubeConstants.ChannelLiveSystemPlaylistPrefix)
    else -> null
  }

  fun loadFirst() {
    // 同步清零翻页状态:切 Tab 后旧 continuation 必须立即作废,否则滚动触发的 loadNext 会用
    // 旧 tab 的 token + 当前 tab 的 browseId 发错配请求(UUSH browseId + videos continuation)。
    uiState.continuation = null
    uiState.playlistContinuation = null
    uiState.loadingMore = false
    uiState.endReached = false
    scope.launch {
      uiState.loading = true
      uiState.failed = null
      try {
        if (uiState.tab == YoutubeConstants.ChannelContentTab.Playlists) {
          // 播放列表 Tab:拉播放列表卡。
          val page = youtubeRepository.getChannelPlaylists(channelId, params = channelParams())
          uiState.playlists = page.items
          uiState.playlistContinuation = page.continuation
          uiState.endReached = page.continuation == null
        } else {
          // 视频/Shorts/直播:拉视频列表(Shorts/直播走系统播放列表 browseId)。
          val page = youtubeRepository.getChannelVideos(channelId, params = channelParams(), browseId = channelBrowseId())
          uiState.items = page.items.distinctBy { it.bvid }
          uiState.continuation = page.continuation
          uiState.endReached = page.continuation == null
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        uiState.failed = e.message.orEmpty().ifBlank { "加载失败" }
        uiState.items = emptyList()
        uiState.playlists = emptyList()
        uiState.continuation = null
        uiState.playlistContinuation = null
        uiState.endReached = true
      }
      uiState.loading = false
    }
  }

  fun loadNext() {
    Log.d(
      "YoutubeChannel",
      "loadNext called continuation=${uiState.continuation?.take(12) ?: "null"} " +
        "loadingMore=${uiState.loadingMore} endReached=${uiState.endReached}",
    )
    val tab = uiState.tab
    val isPlaylists = tab == YoutubeConstants.ChannelContentTab.Playlists
    val token = if (isPlaylists) uiState.playlistContinuation else uiState.continuation
    if (token == null) return
    if (uiState.loadingMore || uiState.endReached) return
    // 与 token 同步捕获当前 tab 的 params/browseId:协程执行时若 tab 已切换,用旧 tab 的
    // browseId(而非读新 tab)才不把旧列表内容混进新 tab;返回后再校验 tab 未变才合并。
    val params = channelParams()
    val browseId = channelBrowseId()
    uiState.loadingMore = true
    scope.launch {
      try {
        if (isPlaylists) {
          // 播放列表卡续页:先存旧列表再比较,避免 endReached 恒真只翻一页。
          val page = youtubeRepository.getChannelPlaylists(channelId, token, params = params)
          if (uiState.tab != tab || uiState.loading) {
            uiState.loadingMore = false
            return@launch
          }
          val old = uiState.playlists
          val merged = (old + page.items).distinctBy { it.id }
          uiState.playlists = merged
          uiState.playlistContinuation = page.continuation
          uiState.endReached = page.continuation == null || merged.size == old.size
        } else {
          val page = youtubeRepository.getChannelVideos(channelId, token, params = params, browseId = browseId)
          if (uiState.tab != tab || uiState.loading) {
            uiState.loadingMore = false
            return@launch
          }
          // 先存旧列表再比较:若先置 uiState.items=merged,merged.size==uiState.items.size 恒真,
          // endReached 永远变 true,续页只翻一页就停(对齐 TV 版 latest.videos 旧列表比较)。
          val oldItems = uiState.items
          val merged = (oldItems + page.items).distinctBy { it.bvid }
          uiState.items = merged
          uiState.continuation = page.continuation
          uiState.endReached = page.continuation == null || merged.size == oldItems.size
        }
        Log.d(
          "YoutubeChannel",
          "loadNext merged old=${uiState.items.size} new=${
            if (isPlaylists) uiState.playlists.size else uiState.items.size
          } endReached=${uiState.endReached}",
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 翻页失败保留已加载内容
      }
      uiState.loadingMore = false
    }
  }

  // 头部信息(订阅数/banner/简介/权威名+头像):仅 channelId 变化时解析,失败回退卡片名/空头像。
  LaunchedEffect(channelId) {
    uiState.name = channelName
    val header = runCatching { youtubeRepository.getChannelHeader(channelId) }.getOrNull()
    if (header != null) {
      uiState.name = header.name.ifBlank { channelName }
      uiState.avatar = header.avatarUrl
      uiState.subscriberCount = header.subscriberCount
      uiState.description = header.description
      uiState.bannerUrl = header.bannerUrl
      // 记录服务端内容 Tab params(小写标识 → params),切 Shorts/直播/播放列表用服务端 params。
      uiState.serverTabParams = header.tabs.map { it.name.lowercase() to it.params }.toMap()
    } else {
      val resolved = runCatching { youtubeRepository.resolveChannel(channelId) }.getOrNull()
      uiState.name = resolved?.name?.ifBlank { channelName } ?: channelName
      uiState.avatar = resolved?.avatar.orEmpty()
      uiState.subscriberCount = null
      uiState.description = ""
      uiState.bannerUrl = ""
      // 头部 info 解析失败也要拿 tab params,否则切 Shorts/直播回退硬编码(服务端不认)。
      uiState.serverTabParams = runCatching { youtubeRepository.getChannelTabs(channelId) }
        .getOrDefault(emptyMap())
    }
  }

  // 首屏/切排序/切 Tab:拉对应内容。已加载过同 channelId+order+tab 跳过(从播放器返回复用列表,
  // 仅清可能卡住的翻页 loading);切排序/切 Tab 强制重拉。
  LaunchedEffect(channelId, order, tab) {
    val changed = uiState.loadedChannelId != channelId || uiState.loadedOrder != order || uiState.loadedTab != tab
    Log.d("Ytabs", "effect fired tab=$tab order=$order loadedTab=${uiState.loadedTab} loadedOrder=${uiState.loadedOrder} changed=$changed")
    if (changed) {
      loadFirst()
      uiState.loadedChannelId = channelId
      uiState.loadedOrder = order
      uiState.loadedTab = tab
    } else {
      // 从播放器返回同 channelId+order+tab:清除可能卡住的翻页 loading 标志(scope 已随离开组合取消)。
      uiState.loadingMore = false
    }
  }

  // 滚到底自动翻页。发射 (last, total) 对而非布尔值:布尔去重会在首屏 loading 时把唯一的
  // true 消耗掉(此时 continuation 仍 null,loadNext 早退),且短列表近底时值不变不再发射,
  // 导致续页永不触发。对 pair 去重则任何滚动/加载导致 last 或 total 变化都会重新求值。
  LaunchedEffect(Unit) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      last to total
    }
      .distinctUntilChanged()
      .collect { (last, total) ->
        if (total > 0 && last >= total - 6) loadNext()
      }
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
      // 头部:返回 + 头像/频道名/订阅数/关注(镜像 B站 UP 页三列布局) + 排序(跨整行)
      item(span = { GridItemSpan(maxLineSpan) }) {
        Column(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedButton(onClick = onBack) { Text("‹") }
          }
          // 三列:左头像(跨两行) | 中(频道名 + 订阅数) | 右关注按钮
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            AsyncImage(
              model = avatar,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
              Text(
                text = name.ifBlank { channelName },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              val subs = uiState.subscriberCount
              if (subs != null) {
                Text(
                  text = stringResource(
                    R.string.youtube_channel_subscribers,
                    formatCount(subs.toInt(), context.resources),
                  ),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
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
          // 简介:整行放三列下方,2 行省略(可选)。
          if (uiState.description.isNotBlank()) {
            Text(
              text = uiState.description,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
          }
          // 内容 Tab 行:视频 / Shorts / 直播。
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            YoutubeConstants.ChannelContentTab.entries.forEach { t ->
              OutlinedButton(onClick = {
                Log.d("Ytabs", "tabClick from=${uiState.tab} to=$t")
                uiState.tab = t
              }) {
                Text(
                  text = when (t) {
                    YoutubeConstants.ChannelContentTab.Videos -> stringResource(R.string.youtube_channel_tab_videos)
                    YoutubeConstants.ChannelContentTab.Shorts -> stringResource(R.string.youtube_channel_tab_shorts)
                    YoutubeConstants.ChannelContentTab.Live -> stringResource(R.string.youtube_channel_tab_live)
                    YoutubeConstants.ChannelContentTab.Playlists -> stringResource(R.string.youtube_channel_tab_playlists)
                  },
                  color = if (uiState.tab == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
          // 排序栏:最新 / 最热(对齐 B站 UP 排行)。仅 Videos Tab 显示。
          if (uiState.tab.hasSort) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
              OutlinedButton(onClick = { uiState.order = YoutubeConstants.ChannelVideoOrder.Latest }) {
                Text(
                  stringResource(R.string.player_up_sort_latest),
                  color = if (uiState.order == YoutubeConstants.ChannelVideoOrder.Latest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
              }
              Spacer(Modifier.padding(start = 8.dp))
              OutlinedButton(onClick = { uiState.order = YoutubeConstants.ChannelVideoOrder.Popular }) {
                Text(
                  stringResource(R.string.player_up_sort_hot),
                  color = if (uiState.order == YoutubeConstants.ChannelVideoOrder.Popular) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }
      }

      val isPlaylists = uiState.tab == YoutubeConstants.ChannelContentTab.Playlists
      // 空判定依 Tab:播放列表 Tab 看 playlists,其余 Tab 看 displayItems(两者互斥)。
      val emptyItems = if (isPlaylists) uiState.playlists.isEmpty() else displayItems.isEmpty()
      // 各 Tab 空态文案对齐(没内容就明确显示空,不串其它内容)。
      val emptyText = when (uiState.tab) {
        YoutubeConstants.ChannelContentTab.Videos -> "暂无视频"
        YoutubeConstants.ChannelContentTab.Shorts -> "暂无短视频"
        YoutubeConstants.ChannelContentTab.Live -> "暂无直播"
        YoutubeConstants.ChannelContentTab.Playlists -> "暂无播放列表"
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
        emptyItems -> item(span = { GridItemSpan(maxLineSpan) }) {
          Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp),
          )
        }
        else -> {
          if (isPlaylists) {
            items(uiState.playlists, key = { it.id }) { playlist ->
              ChannelPlaylistCard(
                playlist = playlist,
                onClick = { openPlaylist(playlist, onStartPlaylist, scope, youtubeRepository) },
              )
            }
          } else {
            items(displayItems, key = { it.bvid }) { video ->
              MobileVideoCard(
                video = video,
                onClick = onVideoSelected,
                onOpenOwner = null,
                onLongPress = onLongPress,
                // 频道页卡片显示「上传时间 · 播放量」。
                showPubdate = true,
              )
            }
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

/** 点击播放列表卡:拉该播放列表首屏视频,作为连播队列起播(onStartPlaylist)。 */
private fun openPlaylist(playlist: YoutubeParsers.YoutubePlaylist, onStartPlaylist: (List<VideoSummary>) -> Unit, scope: kotlinx.coroutines.CoroutineScope, youtubeRepository: YoutubeRepository) {
  if (playlist.browseId.isBlank()) return
  scope.launch {
    runCatching { youtubeRepository.getPlaylistVideos(playlist.browseId) }
      .getOrNull()
      ?.items
      ?.takeIf { it.isNotEmpty() }
      ?.let { onStartPlaylist(it) }
  }
}

/** 频道播放列表卡:封面 + 标题(2行) + 视频数。点击打开该播放列表。 */
@Composable
private fun ChannelPlaylistCard(
  playlist: YoutubeParsers.YoutubePlaylist,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(12.dp)),
    ) {
      if (playlist.thumbnail.isNotBlank()) {
        AsyncImage(
          model = playlist.thumbnail,
          contentDescription = playlist.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center,
        ) {
          Text("▶", style = MaterialTheme.typography.headlineSmall)
        }
      }
    }
    Text(
      text = playlist.title,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 6.dp),
    )
    if (playlist.videoCount.isNotBlank()) {
      Text(
        text = playlist.videoCount,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}
