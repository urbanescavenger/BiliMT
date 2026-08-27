package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.durationText
import com.kirin.mt.core.model.pubdateText
import com.kirin.mt.core.youtube.YoutubeParsers
import com.kirin.mt.core.youtube.YoutubePlaylistHeader
import com.kirin.mt.core.youtube.YoutubeRepository
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.formatCount
import com.kirin.mt.ui.mobile.home.rememberVideoCardRelativeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import android.util.Log

/**
 * YouTube 播放列表详情页(点频道页播放列表卡进入,对齐 LibreTube 先列视频再选播)。
 *
 * 布局(2026-08-27 改纵向列表,对齐 LibreTube PlaylistFragment):顶部全宽封面 + 标题 + 作者/视频数
 * + 「播放全部」+ 可展开简介,下方是带序号/封面的视频行列表(非网格)。
 *
 * 首屏拉 [getPlaylistVideos] 该播放列表视频 + 头部元数据(简介/作者/封面)+ 滚底 continuation 翻页;
 * 点某条视频时把 **已加载的整份列表**作为连播队列、从被点那条起播(onStartSelected)。点「播放全部」从第一条起播。
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
  var header by remember { mutableStateOf<YoutubePlaylistHeader?>(null) }
  var continuation by remember { mutableStateOf<String?>(null) }
  var loading by remember { mutableStateOf(true) }
  var loadingMore by remember { mutableStateOf(false) }
  var endReached by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf<String?>(null) }
  val listState = rememberLazyListState()
  val relativeText = rememberVideoCardRelativeText()

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
        header = page.playlistHeader
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
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      last to listState.layoutInfo.totalItemsCount
    }
      .distinctUntilChanged()
      .collect { (last, total) ->
        if (total > 0 && last >= total - 6) loadNext()
      }
  }

  val cover = header?.cover ?: playlist.thumbnail
  val countText = header?.videoCountText ?: playlist.videoCount
  val owner = header?.owner

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
      LazyColumn(
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        // ── 头部:封面 + 标题 + 作者/视频数 + 播放全部 + 简介 ──
        if (!loading || failed != null) {
          item {
            if (cover.isNotBlank()) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .aspectRatio(16f / 9f)
                  .clip(RoundedCornerShape(12.dp)),
              ) {
                AsyncImage(
                  model = cover,
                  contentDescription = playlist.title,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
              Spacer(modifier = Modifier.height(10.dp))
            }
            Text(
              text = playlist.title,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              if (owner != null) {
                Text(
                  text = owner,
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (countText.isNotBlank()) {
                Text(
                  text = if (owner != null) "· $countText" else countText,
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (items.isNotEmpty()) {
              OutlinedButton(onClick = { onStartSelected(items.first(), items) }) {
                Text("播放全部")
              }
            }
            val desc = header?.description
            if (!desc.isNullOrBlank()) {
              Spacer(modifier = Modifier.height(6.dp))
              var expanded by remember { mutableStateOf(false) }
              val shown = if (expanded) desc else desc.take(120)
              Text(
                text = shown,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { expanded = !expanded },
              )
            }
          }
        }

        when {
          failed != null -> item {
            Text(
              text = failed ?: "",
              color = MaterialTheme.colorScheme.error,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
          }
          loading -> item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
          items.isEmpty() -> item {
            Text(
              text = "暂无视频",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
          }
          else -> {
            itemsIndexed(items, key = { _, v -> v.bvid }) { index, video ->
              PlaylistVideoRow(
                video = video,
                index = index,
                relativeText = relativeText,
                onClick = { onStartSelected(video, items) },
                onLongPress = onLongPress,
              )
            }
            if (loadingMore) {
              item {
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

/** 播放列表一条视频的横向行:编号 + 封面(右下角时长) + 右侧标题/作者/播放量·时间。 */
@Composable
private fun PlaylistVideoRow(
  video: VideoSummary,
  index: Int,
  relativeText: com.kirin.mt.core.model.VideoCardRelativeText,
  onClick: () -> Unit,
  onLongPress: ((VideoSummary) -> Unit)?,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .combinedClickable(
        onClick = onClick,
        onLongClick = onLongPress?.let { { it(video) } },
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // 编号。
    Text(
      text = "${index + 1}",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(22.dp),
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.width(8.dp))
    // 封面 + 时长。
    Box(modifier = Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
      AsyncImage(
        model = video.pic,
        contentDescription = video.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      val durationText = video.durationText()
      if (durationText.isNotBlank() && !video.isLive) {
        Text(
          text = durationText,
          style = MaterialTheme.typography.labelSmall,
          fontSize = 11.sp,
          color = androidx.compose.ui.graphics.Color.White,
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        )
      }
    }
    Spacer(modifier = Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = video.title,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      if (video.ownerName.isNotBlank()) {
        Text(
          text = video.ownerName,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      val count = formatCount(if (video.view > 0) video.view else video.likeCount, androidx.compose.ui.platform.LocalContext.current.resources)
      val pubdate = video.pubdateText(relativeText)
      val meta = listOfNotNull(count.ifBlank { null }, pubdate.ifBlank { null }).joinToString(" · ")
      if (meta.isNotBlank()) {
        Text(
          text = meta,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
