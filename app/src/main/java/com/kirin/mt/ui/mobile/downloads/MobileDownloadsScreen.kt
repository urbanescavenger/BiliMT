package com.kirin.mt.ui.mobile.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.download.DownloadManager
import com.kirin.mt.core.download.DownloadSource
import com.kirin.mt.core.download.DownloadStatus
import com.kirin.mt.core.download.DownloadWithItems
import com.kirin.mt.core.model.SourceBili
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubePlaylistStore
import com.kirin.mt.ui.mobile.feed.MobilePlaylistPickerDialog
import com.kirin.mt.ui.theme.BiliColors
import kotlinx.coroutines.launch

/**
 * 下载管理库:列出全部下载任务(Room 事实源),卡片显示封面/标题/状态/进度,
 * 按状态给 play/pause/resume/cancel/delete。进度条由实时 [DownloadManager.progress] 驱动。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileDownloadsScreen(
  downloadManager: DownloadManager,
  youtubePlaylistStore: YoutubePlaylistStore,
  onPlayDownload: (Long) -> Unit,
  batchMode: Boolean = false,
  selectedIds: Set<Long> = emptySet(),
  onToggleSelection: (Long) -> Unit = {},
  onSetSelection: (Set<Long>) -> Unit = {},
  onDeleteSelected: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val downloads by downloadManager.downloads.collectAsState(initial = emptyList())
  // 长按卡片弹出的操作弹窗目标;再点「加入播放列表」切到列表选择弹窗。
  var longPressGroup by remember { mutableStateOf<DownloadWithItems?>(null) }
  var showPlaylistPicker by remember { mutableStateOf(false) }
  // 批量删除二次确认弹窗。
  var showDeleteConfirm by remember { mutableStateOf(false) }
  // 实时字节进度:downloadId → fraction。Download 的 totalDownloadedBytes 是文件长度,服务回吐更及时。
  var liveProgress by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
  // 实时速率按分件记:(downloadId, partId) → bytesPerSecond;组级显示需对这些并发分件求和,
  // 否则同一下载的视频+音频两分件各自报速度,只取最后一个会「速度不对」。
  var liveSpeedParts by remember { mutableStateOf<Map<Pair<Long, Int>, Long>>(emptyMap()) }
  // 实时已下载字节:(downloadId, partId) → downloadedBytes，用于进度汇报实时累加。
  var livePartBytes by remember { mutableStateOf<Map<Pair<Long, Int>, Long>>(emptyMap()) }
  // 实时分件总量:(downloadId, partId) → totalBytes(由 progress 报告探测后的真实大小)。
  var livePartTotal by remember { mutableStateOf<Map<Pair<Long, Int>, Long>>(emptyMap()) }
  LaunchedEffect(Unit) {
    downloadManager.progress.collect { p ->
      liveProgress = liveProgress + (p.downloadId to p.fraction)
      if (p.bytesPerSecond > 0L) liveSpeedParts = liveSpeedParts + ((p.downloadId to p.partId) to p.bytesPerSecond)
      livePartBytes = livePartBytes + ((p.downloadId to p.partId) to p.downloadedBytes)
      if (p.totalBytes > 0L) livePartTotal = livePartTotal + ((p.downloadId to p.partId) to p.totalBytes)
    }
  }

  Column(modifier = modifier.fillMaxSize()) {
    if (downloads.isEmpty()) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
          text = stringResource(R.string.downloads_empty),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(downloads, key = { it.download.id }) { group ->
          // 组级剩余待下载:Σ(各分件已知总量 - 实时已下载字节)。
          // 总量取 progress 流实时 totalBytes(探测后)优先、Room 快照兜底——避免分件 totalSize
          // 在 DB 里仍为 -1 时被 filter 排除导致「速度在涨但剩余不减」。
          val did = group.download.id
          var groupTotal = 0L
          var totalKnown = false
          for (item in group.items) {
            // 优先用 progress 流实时 reportTotal(DASH 折算后的实际字节,已 emit 即准确);
            // 未开始下载的分件无 progress 时才 fallback Room item.totalSize。
            val t = livePartTotal[did to item.id] ?: item.totalSize
            if (t > 0L) {
              groupTotal += t
              totalKnown = true
            }
          }
          val downloaded = group.items.sumOf { livePartBytes[did to it.id] ?: 0L }
          val remainingBytes = if (totalKnown) (groupTotal - downloaded).coerceAtLeast(0L) else null
          // 组级总速度 = 还在下载(未下完)的并发分件瞬时速度之和。
          // 已完成分件不再 emit,但其瞬时速度残留在 liveSpeedParts——必须排除,
          // 否则视频已下完(残留 7M)+ 音频慢速(65k)会错误显示 7M。
          val totalSpeed = liveSpeedParts.entries
            .filter { it.key.first == did }
            .filter { (key, _) ->
              val total = livePartTotal[key]
              val done = livePartBytes[key] ?: 0L
              // 总量未知或还没下完 → 仍在活跃下载,计入;已下完(done>=total) → 排除残留。
              total == null || total <= 0L || done < total
            }
            .sumOf { it.value }
          DownloadCard(
            group = group,
            liveFraction = liveProgress[group.download.id],
            liveSpeedBps = totalSpeed.takeIf { it > 0L },
            remainingBytes = remainingBytes,
            batchMode = batchMode,
            selected = group.download.id in selectedIds,
            onSelect = { onToggleSelection(group.download.id) },
            onPlay = { onPlayDownload(group.download.id) },
            onPause = { scope.launch { downloadManager.pause(group.download.id) } },
            onResume = { scope.launch { downloadManager.resume(group.download.id) } },
            onCancel = { scope.launch { downloadManager.cancel(group.download.id) } },
            onLongPress = {
              longPressGroup = group
              showPlaylistPicker = false
            },
          )
        }
      }
      if (batchMode) {
        val allSelected = downloads.all { it.download.id in selectedIds }
        DownloadBatchBar(
          selectedCount = selectedIds.size,
          onToggleAll = {
            onSetSelection(if (allSelected) emptySet() else downloads.map { it.download.id }.toSet())
          },
          onDelete = { showDeleteConfirm = true },
        )
      }
    }
  }

  // 长按卡片:底部操作菜单(加入播放列表 → 列表选择/删除)。
  longPressGroup?.let { group ->
    if (showPlaylistPicker) {
      MobilePlaylistPickerDialog(
        video = group.toVideoSummary(),
        youtubePlaylistStore = youtubePlaylistStore,
        onDismiss = {
          showPlaylistPicker = false
          longPressGroup = null
        },
      )
    } else {
      DownloadActionsSheet(
        group = group,
        onPickPlaylist = { showPlaylistPicker = true },
        onDelete = {
          scope.launch {
            downloadManager.delete(group.download.id)
            longPressGroup = null
          }
        },
        onDismiss = {
          showPlaylistPicker = false
          longPressGroup = null
        },
      )
    }
  }

  // 批量删除二次确认。
  if (showDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text(stringResource(R.string.downloads_batch_confirm_title)) },
      text = {
        Text(
          stringResource(R.string.downloads_batch_confirm_message, selectedIds.size),
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        TextButton(onClick = {
          showDeleteConfirm = false
          onDeleteSelected()
        }) { Text(stringResource(R.string.downloads_action_delete)) }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirm = false }) {
          Text(stringResource(R.string.playlist_cancel))
        }
      },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadCard(
  group: DownloadWithItems,
  liveFraction: Float?,
  liveSpeedBps: Long?,
  remainingBytes: Long?,
  batchMode: Boolean = false,
  selected: Boolean = false,
  onSelect: () -> Unit = {},
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit,
  onLongPress: () -> Unit,
) {
  val d = group.download
  val status = group.status
  val fraction = liveFraction ?: group.fraction
  val coverModel = d.coverPath ?: d.coverUrl

  // 批量模式:点卡片=勾选/取消,关闭长按;普通模式:单击播放(可播时),长按弹操作菜单。
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .combinedClickable(
        onClick = {
          if (batchMode) onSelect() else if (group.isPlayable) onPlay()
        },
        onLongClick = if (batchMode) null else onLongPress,
      )
      .padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (batchMode) {
      Checkbox(
        checked = selected,
        onCheckedChange = { onSelect() },
      )
    }
    AsyncImage(
      model = coverModel,
      contentDescription = d.title,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .size(width = 96.dp, height = 60.dp)
        .clip(RoundedCornerShape(8.dp)),
    )
    Column(
      modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
    ) {
      Text(
        text = d.title,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = statusLabel(status),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status == DownloadStatus.RUNNING && liveSpeedBps != null && liveSpeedBps > 0L) {
          Text(
            text = "  ·  ${formatSpeed(liveSpeedBps)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        // 剩余待下载大小:totalSize - 实时已下载字节(总量未知时为 null 跳过)。
        if ((status == DownloadStatus.RUNNING || status == DownloadStatus.QUEUED) && remainingBytes != null) {
          Text(
            text = "  ·  剩余 ${formatBytes(remainingBytes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (status == DownloadStatus.RUNNING || status == DownloadStatus.QUEUED) {
        LinearProgressIndicator(
          progress = { fraction },
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
      }
    }
    // 右侧按钮列只保留下载控制;播放走单击,删除走长按菜单。
    Column(horizontalAlignment = Alignment.End) {
      when (status) {
        DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
          TextButton(onClick = onPause) { Text(stringResource(R.string.downloads_action_pause)) }
          TextButton(onClick = onCancel) { Text(stringResource(R.string.downloads_action_cancel)) }
        }
        DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
          TextButton(onClick = onResume) { Text(stringResource(R.string.downloads_action_resume)) }
        }
        DownloadStatus.COMPLETED, DownloadStatus.CANCELLED -> {
          // 无按钮:单击播放 / 长按删除
        }
      }
    }
  }
}

/** 格式化下载速率:≥1MB/s 显示 MB/s,否则 KB/s。 */
private fun formatSpeed(bytesPerSecond: Long): String {
  return if (bytesPerSecond >= 1024 * 1024) {
    String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
  } else {
    "${bytesPerSecond / 1024} KB/s"
  }
}

/** 格式化剩余大小:≥1MB 显示 MB(1 位小数),否则 KB。 */
private fun formatBytes(bytes: Long): String {
  return if (bytes >= 1024 * 1024) {
    String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
  } else {
    "${bytes / 1024} KB"
  }
}

@Composable
private fun statusLabel(status: DownloadStatus): String = when (status) {
  DownloadStatus.QUEUED -> stringResource(R.string.downloads_status_queued)
  DownloadStatus.RUNNING -> stringResource(R.string.downloads_status_running)
  DownloadStatus.PAUSED -> stringResource(R.string.downloads_status_paused)
  DownloadStatus.COMPLETED -> stringResource(R.string.downloads_status_completed)
  DownloadStatus.FAILED -> stringResource(R.string.downloads_status_failed)
  DownloadStatus.CANCELLED -> stringResource(R.string.downloads_status_cancelled)
}

/** 批量管理模式底部操作栏:已选计数 + 全选切换 + 删除所选。 */
@Composable
private fun DownloadBatchBar(
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
      text = stringResource(R.string.downloads_batch_selected, selectedCount),
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = onToggleAll) {
      Text(stringResource(R.string.downloads_batch_select_all))
    }
    TextButton(
      onClick = onDelete,
      enabled = selectedCount > 0,
    ) {
      Text(
        text = stringResource(R.string.downloads_batch_delete),
        color = if (selectedCount > 0) BiliColors.BiliPink else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** 长按下载卡片弹出的底部操作菜单:加入播放列表(仅 YouTube)+ 删除。样式对齐 MobileYoutubeLongPressSheet。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadActionsSheet(
  group: DownloadWithItems,
  onPickPlaylist: () -> Unit,
  onDelete: () -> Unit,
  onDismiss: () -> Unit,
) {
  val d = group.download
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFF1A1A20),
  ) {
    MaterialTheme(colorScheme = darkColorScheme()) {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
          text = d.title,
          color = Color.White,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.padding(top = 8.dp))
        if (group.source == DownloadSource.YOUTUBE) {
          SheetActionRow(
            icon = painterResource(R.drawable.ic_player_playlist),
            text = stringResource(R.string.add_to_playlist),
            onClick = onPickPlaylist,
          )
        }
        SheetActionRow(
          icon = rememberVectorPainter(Icons.Filled.Delete),
          text = stringResource(R.string.downloads_action_delete),
          onClick = onDelete,
        )
        Spacer(Modifier.padding(bottom = 8.dp))
      }
    }
  }
}

/** 底部操作菜单的一行:图标 + 文字。 */
@Composable
private fun SheetActionRow(icon: androidx.compose.ui.graphics.painter.Painter, text: String, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painter = icon,
      contentDescription = null,
      tint = BiliColors.BiliPink,
      modifier = Modifier.size(24.dp),
    )
    Spacer(Modifier.width(12.dp))
    Text(text, color = Color.White)
  }
}

/** 下载任务 → 播放列表可用的 VideoSummary(仿离线播放器相关视频构造;仅 metadata,不落盘)。 */
private fun DownloadWithItems.toVideoSummary(): VideoSummary {
  val d = download
  return VideoSummary(
    bvid = d.videoId,
    title = d.title,
    pic = d.coverUrl,
    ownerName = "",
    ownerFace = "",
    ownerMid = 0L,
    view = 0,
    danmaku = 0,
    duration = (d.durationMs / 1000).toInt(),
    pubdate = 0L,
    badge = "",
    cid = d.cid,
    source = if (source == DownloadSource.YOUTUBE) SourceYoutube else SourceBili,
  )
}
