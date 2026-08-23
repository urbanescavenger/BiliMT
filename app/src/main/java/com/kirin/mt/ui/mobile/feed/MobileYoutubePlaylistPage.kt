package com.kirin.mt.ui.mobile.feed

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import com.kirin.mt.core.youtube.DEFAULT_PLAYLIST_NAME
import com.kirin.mt.core.youtube.YoutubePlaylist
import com.kirin.mt.core.youtube.YoutubePlaylistStore
import com.kirin.mt.ui.mobile.home.formatCount
import com.kirin.mt.ui.theme.BiliColors
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
  onVideoSelected: (VideoSummary) -> Unit,
  onStartPlaylist: (List<VideoSummary>) -> Unit,
  modifier: Modifier = Modifier,
) {
  val playlists by youtubePlaylistStore.playlists.collectAsState(initial = emptyList())
  var selectedName by remember { mutableStateOf<String?>(null) }
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
  onVideoSelected: (VideoSummary) -> Unit,
  onStartPlaylist: (List<VideoSummary>) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  // 「默认」是下载自动存档列表:完全映射离线下载,只读——不提供编辑/移除/拖动,只能播放。
  val autoArchive = playlist.name == DEFAULT_PLAYLIST_NAME
  var editMode by remember { mutableStateOf(false) }
  // 批量勾选选中集(编辑模式下勾选的 bvid);「完成」或单点移除时清掉。
  var selectedBvids by remember { mutableStateOf<Set<String>>(emptySet()) }
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
              if (editMode || autoArchive) return@pointerInput
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
                  // 拖拽项中心 = 初始 offset + 累计 delta(固定,不依赖 item 当前 offset,不漂移)。
                  val draggedCenter = draggingInitialOffset + draggingInfo.size / 2f + draggingDelta
                  val target = layoutInfo.visibleItemsInfo
                    .filter { it.index != from }
                    .minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - draggedCenter) }
                  if (target != null && target.index != from) {
                    val newItems = items.toMutableList()
                    val item = newItems.removeAt(from)
                    newItems.add(target.index, item)
                    items = newItems
                    draggingIndex = target.index
                  }
                },
                onDragEnd = {
                  draggingBvid = null
                  draggingIndex = -1
                  draggingDelta = 0f
                  scope.launch { youtubePlaylistStore.replaceVideos(playlist.name, items) }
                },
                onDragCancel = {
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
          Box {
            AsyncImage(
              model = video.pic,
              contentDescription = video.title,
              contentScale = ContentScale.Crop,
              modifier = Modifier
                .width(110.dp)
                .height(62.dp)
                .clip(RoundedCornerShape(8.dp)),
            )
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
                  append(formatCount(video.view))
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
