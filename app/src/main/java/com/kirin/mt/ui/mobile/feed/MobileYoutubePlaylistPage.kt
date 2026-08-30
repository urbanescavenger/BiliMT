package com.kirin.mt.ui.mobile.feed

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.DOWNLOAD_PLAYLIST_NAME
import com.kirin.mt.core.youtube.YoutubeHistoryStore
import com.kirin.mt.core.youtube.YoutubePlaylist
import com.kirin.mt.core.youtube.YoutubePlaylistStore
import com.kirin.mt.ui.mobile.home.CompletedBadge
import com.kirin.mt.ui.mobile.home.LocalWatchedIds
import com.kirin.mt.ui.mobile.home.YoutubeSnapshotWatchProgress
import com.kirin.mt.ui.mobile.home.formatCount
import com.kirin.mt.ui.mobile.home.rememberYoutubeWatchPositions
import com.kirin.mt.ui.theme.BiliColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 移动端"播放列表"子 tab:两层浏览。
 * 第一层列出全部命名播放列表(预置「默认」),点进第二层;
 * 第二层单列展示某列表视频,点击起播并把整列表作为连播队列,长按拖动排序,编辑模式移除。
 * 免登录,DataStore 持久化。
 */
@Composable
fun MobileYoutubePlaylistPage(
  youtubePlaylistStore: YoutubePlaylistStore,
  youtubeHistoryStore: YoutubeHistoryStore,
  onVideoSelected: (VideoSummary) -> Unit,
  onStartPlaylist: (List<VideoSummary>) -> Unit,
  modifier: Modifier = Modifier,
) {
  val playlists by youtubePlaylistStore.playlists.collectAsState(initial = emptyList())
  // rememberSaveable:HorizontalPager 切走本子 tab 再切回时 page 会重组,remember 会丢掉
  // 选中态掉回列表层;saveable 跨重组保留,停在当前打开的播放列表详情。
  var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
  var showCreateDialog by remember { mutableStateOf(false) }

  // 首次进入迁移旧版单扁平列表进「默认」。
  LaunchedEffect(Unit) { youtubePlaylistStore.migrateLegacyIfNeeded() }

  val selected = playlists.firstOrNull { it.name == selectedName }

  if (selected == null) {
    PlaylistListScreen(
      playlists = playlists,
      onCreate = { showCreateDialog = true },
      onSelect = { selectedName = it },
      modifier = modifier,
    )
  } else {
    PlaylistDetailScreen(
      playlist = selected,
      youtubePlaylistStore = youtubePlaylistStore,
      youtubeHistoryStore = youtubeHistoryStore,
      onVideoSelected = onVideoSelected,
      onStartPlaylist = onStartPlaylist,
      onBack = { selectedName = null },
      modifier = modifier,
    )
  }

  if (showCreateDialog) {
    CreatePlaylistDialog(
      youtubePlaylistStore = youtubePlaylistStore,
      onDismiss = { showCreateDialog = false },
      onCreated = { name ->
        showCreateDialog = false
        selectedName = name
      },
    )
  }
}

/** 第一层:播放列表列表(名 + 视频数),点进第二层。 */
@Composable
private fun PlaylistListScreen(
  playlists: List<YoutubePlaylist>,
  onCreate: () -> Unit,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.feed_tab_playlist),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f),
      )
      OutlinedButton(onClick = onCreate) {
        Text(stringResource(R.string.playlist_new_list))
      }
    }

    if (playlists.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = stringResource(R.string.playlist_empty),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(24.dp),
        )
      }
      return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
      items(playlists, key = { it.name }) { pl ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(pl.name) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            painter = painterResource(R.drawable.ic_player_playlist),
            contentDescription = null,
            tint = BiliColors.BiliPink,
            modifier = Modifier.size(22.dp),
          )
          Text(
            text = pl.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${pl.videos.size} 个",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = 4.dp),
          )
          Icon(
            painter = painterResource(R.drawable.ic_player_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
        }
        HorizontalDivider(
          modifier = Modifier.padding(horizontal = 12.dp),
          color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
      }
    }
  }
}

/**
 * 第二层:单列播放列表详情 + 长按拖动排序。
 * 拖动期间本地重排列表,结束后经 store.replaceVideos 持久化最终顺序。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDetailScreen(
  playlist: YoutubePlaylist,
  youtubePlaylistStore: YoutubePlaylistStore,
  youtubeHistoryStore: YoutubeHistoryStore,
  onVideoSelected: (VideoSummary) -> Unit,
  onStartPlaylist: (List<VideoSummary>) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  // 观看进度快照(videoId -> 历史条目):列表存的是加入时刻快照,真实进度渲染时查历史。
  val watchPositions = rememberYoutubeWatchPositions(youtubeHistoryStore)
  // 「下载」是下载自动存档列表:完全映射离线下载——不提供编辑/移除,但可长按拖动排序。
  val autoArchive = playlist.name == DOWNLOAD_PLAYLIST_NAME
  var editMode by remember { mutableStateOf(false) }
  // 批量勾选选中集(编辑模式下勾选的 bvid);「完成」或单点移除时清掉。
  var selectedBvids by remember { mutableStateOf<Set<String>>(emptySet()) }

  // 系统返回:编辑模式先退编辑(对齐相册多选惯例),否则回播放列表列表——
  // 本子 tab 的两层导航在组件内部,不拦返回键会一路冒泡直接退出应用。
  BackHandler {
    if (editMode) {
      editMode = false
      selectedBvids = emptySet()
    } else {
      onBack()
    }
  }
  // 批量移除二次确认弹窗。
  var showRemoveConfirm by remember { mutableStateOf(false) }
  // 本地可重排列表(拖动用);playlist.videos 变化(外部/持久化)时同步。
  var items by remember { mutableStateOf(playlist.videos) }
  LaunchedEffect(playlist.videos) { items = playlist.videos }

  // 拖动状态:拖动的视频 id + 其在 items 中的实时位置 + 初始 offset + 累计位移。
  // 对齐 Google LazyColumnDragAndDropDemo:拖拽项视觉位移 = initialOffset + delta - item.offset,
  // 自动补偿 item 重排后的 offset 变化,避免手动调整出错致重影。
  var draggingBvid by remember { mutableStateOf<String?>(null) }
  var draggingIndex by remember { mutableIntStateOf(-1) }
  var draggingInitialOffset by remember { mutableFloatStateOf(0f) }
  var draggingDelta by remember { mutableFloatStateOf(0f) }

  fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) { Text("‹") }
      Text(
        text = playlist.name,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f).padding(start = 4.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (autoArchive) {
        // 下载自动存档列表:只读镜像,不提供编辑/移除。
        Text(
          text = stringResource(R.string.playlist_auto_managed),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelMedium,
        )
      } else {
        OutlinedButton(onClick = {
          editMode = !editMode
          selectedBvids = emptySet()
        }) {
          Text(stringResource(if (editMode) R.string.playlist_done else R.string.playlist_edit))
        }
      }
    }

    if (items.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = stringResource(R.string.playlist_empty),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(24.dp),
        )
      }
      return@Column
    }

    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
      itemsIndexed(items, key = { _, v -> v.bvid }) { index, video ->
        val isDragging = video.bvid == draggingBvid
        // 拖拽项禁用 animateItem:交换后布局位置立即跳变,由 graphicsLayer 的 translationY 补偿,
        // 视觉连续跟手;若拖拽项也走 animateItem,交换后位置动画与 translationY 叠加会漂移出重影。
        val itemModifier = if (isDragging) Modifier else Modifier.animateItem()
        Row(
          modifier = itemModifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
              if (isDragging) {
                // 拖拽项视觉位移 = 初始 offset + 累计 delta - item 当前 offset。
                // item 重排后 offset 变化被自动减掉,拖拽项始终钉在手指下,不重影。
                val info = listState.layoutInfo.visibleItemsInfo
                  .firstOrNull { it.index == draggingIndex }
                translationY = if (info != null) draggingInitialOffset + draggingDelta - info.offset else 0f
              }
            }
            .background(
              if (isDragging) Color(0xFF2A2A32) else Color.Transparent,
              RoundedCornerShape(10.dp),
            )
            .clickable {
              if (editMode) {
                // 编辑模式:点卡勾选/取消(批量移除),不触发播放/拖动。
                selectedBvids = if (video.bvid in selectedBvids) selectedBvids - video.bvid
                else selectedBvids + video.bvid
              } else {
                // 先起播(外层 onVideoSelected 清连播队列),再设置队列快照,保证播放器用该列表连播。
                onVideoSelected(video)
                onStartPlaylist(items)
              }
            }
            .pointerInput(video.bvid, editMode, autoArchive) {
              // 「下载」列表允许长按拖动排序(编辑/移除仍禁用);其它列表编辑模式下禁拖。
              if (editMode) return@pointerInput
              // 边缘 auto-scroll 任务,复用单个 job 防滚动堆叠。
              var overscrollJob: Job? = null
              detectDragGesturesAfterLongPress(
                onDragStart = {
                  draggingBvid = video.bvid
                  draggingIndex = index
                  draggingInitialOffset = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == index }?.offset?.toFloat() ?: 0f
                  draggingDelta = 0f
                },
                onDrag = { change, dragAmount ->
                  change.consume()
                  draggingDelta += dragAmount.y
                  val from = draggingIndex
                  if (from < 0) return@detectDragGesturesAfterLongPress
                  val layoutInfo = listState.layoutInfo
                  val draggingInfo = layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == from }
                    ?: return@detectDragGesturesAfterLongPress
                  // 拖拽带上下沿(viewport 固定坐标 = 初始 offset + 累计位移,不依赖 item 当前 offset)。
                  val startOffset = draggingInitialOffset + draggingDelta
                  val endOffset = startOffset + draggingInfo.size
                  val draggedCenter = startOffset + draggingInfo.size / 2f
                  // 目标卡选择:只取拖拽方向的相邻槽位(from±1),拖过其中心才换一格。
                  // 不用最近中心/双向重叠——顶部拖拽时手指还没离开原槽,最近中心会把目标钉回原槽,
                  // 致最上面视频刚换一格就被弹回拖不下去;方向邻位判定单调、反向不弹回,顶/底都能逐格拖。
                  val targetIndex = when {
                    draggingDelta > 0f && from + 1 < items.size -> from + 1
                    draggingDelta < 0f && from - 1 >= 0 -> from - 1
                    else -> null
                  }
                  val target = targetIndex?.let { ti ->
                    layoutInfo.visibleItemsInfo.firstOrNull { it.index == ti }?.takeIf { neighbor ->
                      if (draggingDelta > 0f) draggedCenter > neighbor.offset + neighbor.size / 2f
                      else draggedCenter < neighbor.offset + neighbor.size / 2f
                    }
                  }
                  if (target != null) {
                    val newItems = items.toMutableList()
                    val item = newItems.removeAt(from)
                    newItems.add(target.index, item)
                    items = newItems
                    draggingIndex = target.index
                  }
                  // 边缘 auto-scroll:拖拽带越出视口顶/底 → scrollBy,越界量自纠回到 0 即停;
                  // scrollBy 自带首/末项边界 clamp。列表在拖拽项下滚动,拖拽项钉在手指下。
                  val overscroll = when {
                    draggingDelta < 0f ->
                      (startOffset - layoutInfo.viewportStartOffset).takeIf { it < 0f } ?: 0f
                    draggingDelta > 0f ->
                      (endOffset - layoutInfo.viewportEndOffset).takeIf { it > 0f } ?: 0f
                    else -> 0f
                  }
                  if (overscroll != 0f) {
                    if (overscrollJob?.isActive != true) {
                      overscrollJob = scope.launch { listState.scrollBy(overscroll) }
                    }
                  } else {
                    overscrollJob?.cancel()
                    overscrollJob = null
                  }
                },
                onDragEnd = {
                  overscrollJob?.cancel()
                  overscrollJob = null
                  draggingBvid = null
                  draggingIndex = -1
                  draggingDelta = 0f
                  scope.launch { youtubePlaylistStore.replaceVideos(playlist.name, items) }
                },
                onDragCancel = {
                  overscrollJob?.cancel()
                  overscrollJob = null
                  draggingBvid = null
                  draggingIndex = -1
                  draggingDelta = 0f
                },
              )
            },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // 编辑模式:行首复选框勾选/取消;点卡也能切,复选框自身消费点击不触发父行。
          if (editMode) {
            Checkbox(
              checked = video.bvid in selectedBvids,
              onCheckedChange = { checked ->
                selectedBvids = if (checked) selectedBvids + video.bvid else selectedBvids - video.bvid
              },
            )
          }
          Box(
            modifier = Modifier
              .width(110.dp)
              .height(62.dp)
              .clip(RoundedCornerShape(8.dp)),
          ) {
            AsyncImage(
              model = video.pic,
              contentDescription = video.title,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
            // 播放进度细条:未看完且历史里有上次播放位置时贴缩略图底(已看完交给角标)。
            if (video.bvid !in LocalWatchedIds.current) {
              val entry = watchPositions[video.bvid]
              YoutubeSnapshotWatchProgress(
                positionMs = entry?.positionMs ?: 0L,
                durationMs = entry?.durationMs ?: 0L,
                modifier = Modifier.align(Alignment.BottomStart),
              )
            }
            if (editMode) {
              Text(
                text = "✕ ${stringResource(R.string.playlist_remove)}",
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                  .align(Alignment.TopEnd)
                  .padding(2.dp)
                  .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
                  .padding(horizontal = 4.dp, vertical = 1.dp),
              )
            }
            // 已看完角标:命中本地 watched 集合(bvid/videoId)时贴缩略图右下。
            if (video.bvid in LocalWatchedIds.current) {
              CompletedBadge(modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
            }
          }
          Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
              text = video.title,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
              text = buildString {
                if (video.ownerName.isNotBlank()) append(video.ownerName)
                if (video.view > 0) {
                  if (isNotEmpty()) append(" · ")
                  append(formatCount(video.view, context.resources))
                }
              },
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (editMode) {
            TextButton(onClick = {
              scope.launch {
                youtubePlaylistStore.removeVideo(playlist.name, video.bvid)
                selectedBvids = selectedBvids - video.bvid
                toast(context.getString(R.string.playlist_removed))
              }
            }) {
              Text(stringResource(R.string.playlist_remove), color = MaterialTheme.colorScheme.error)
            }
          }
        }
        HorizontalDivider(
          modifier = Modifier.padding(horizontal = 12.dp),
          color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
      }
    }

    // 编辑模式:底部固定全局批量操作栏(已选 N/全选/删除所选),对齐下载批量删除。
    if (editMode) {
      val allSelected = items.isNotEmpty() && items.all { it.bvid in selectedBvids }
      PlaylistBatchBar(
        selectedCount = selectedBvids.size,
        onToggleAll = {
          selectedBvids = if (allSelected) emptySet() else items.map { it.bvid }.toSet()
        },
        onDelete = { showRemoveConfirm = true },
      )
    }

    // 批量移除二次确认。
    if (showRemoveConfirm) {
      AlertDialog(
        onDismissRequest = { showRemoveConfirm = false },
        title = { Text(stringResource(R.string.playlist_batch_confirm_title)) },
        text = {
          Text(
            stringResource(R.string.playlist_batch_confirm_message, selectedBvids.size),
            style = MaterialTheme.typography.bodyMedium,
          )
        },
        confirmButton = {
          TextButton(onClick = {
            scope.launch {
              youtubePlaylistStore.removeVideos(playlist.name, selectedBvids)
              toast(context.getString(R.string.playlist_removed))
              selectedBvids = emptySet()
            }
            showRemoveConfirm = false
          }) { Text(stringResource(R.string.playlist_batch_delete)) }
        },
        dismissButton = {
          TextButton(onClick = { showRemoveConfirm = false }) {
            Text(stringResource(R.string.playlist_cancel))
          }
        },
      )
    }
  }
}

/** 编辑模式底部批量操作栏:已选计数 + 全选切换 + 删除所选。样式对齐下载批量栏。 */
@Composable
private fun PlaylistBatchBar(
  selectedCount: Int,
  onToggleAll: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(R.string.playlist_batch_selected, selectedCount),
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = onToggleAll) {
      Text(stringResource(R.string.playlist_batch_select_all))
    }
    TextButton(
      onClick = onDelete,
      enabled = selectedCount > 0,
    ) {
      Text(
        text = stringResource(R.string.playlist_batch_delete),
        color = if (selectedCount > 0) BiliColors.BiliPink else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
