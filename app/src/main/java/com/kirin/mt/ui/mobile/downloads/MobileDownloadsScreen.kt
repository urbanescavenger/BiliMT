package com.kirin.mt.ui.mobile.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.download.DownloadManager
import com.kirin.mt.core.download.DownloadStatus
import com.kirin.mt.core.download.DownloadWithItems
import kotlinx.coroutines.launch

/**
 * 下载管理库:列出全部下载任务(Room 事实源),卡片显示封面/标题/状态/进度,
 * 按状态给 play/pause/resume/cancel/delete。进度条由实时 [DownloadManager.progress] 驱动。
 */
@Composable
fun MobileDownloadsScreen(
  downloadManager: DownloadManager,
  onPlayDownload: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val downloads by downloadManager.downloads.collectAsState(initial = emptyList())
  // 实时字节进度:downloadId → fraction。Room 的 totalDownloadedBytes 是文件长度,服务回吐更及时。
  var liveProgress by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
  // 实时速率:downloadId → bytesPerSecond(由 DownloadEngine 逐块读估算)。
  var liveSpeed by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
  // 实时已下载字节:(downloadId, partId) → downloadedBytes，用于组级剩余大小实时累加。
  var livePartBytes by remember { mutableStateOf<Map<Pair<Long, Int>, Long>>(emptyMap()) }
  // 实时分件总量:(downloadId, partId) → totalBytes(由 progress 流回传探测后的真实大小)。
  var livePartTotal by remember { mutableStateOf<Map<Pair<Long, Int>, Long>>(emptyMap()) }
  LaunchedEffect(Unit) {
    downloadManager.progress.collect { p ->
      liveProgress = liveProgress + (p.downloadId to p.fraction)
      if (p.bytesPerSecond > 0L) liveSpeed = liveSpeed + (p.downloadId to p.bytesPerSecond)
      livePartBytes = livePartBytes + ((p.downloadId to p.partId) to p.downloadedBytes)
      if (p.totalBytes > 0L) livePartTotal = livePartTotal + ((p.downloadId to p.partId) to p.totalBytes)
    }
  }

  if (downloads.isEmpty()) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        text = stringResource(R.string.downloads_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
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
        val t = maxOf(livePartTotal[did to item.id] ?: -1L, item.totalSize)
        if (t > 0L) {
          groupTotal += t
          totalKnown = true
        }
      }
      val downloaded = group.items.sumOf { livePartBytes[did to it.id] ?: 0L }
      val remainingBytes = if (totalKnown) (groupTotal - downloaded).coerceAtLeast(0L) else null
      DownloadCard(
        group = group,
        liveFraction = liveProgress[group.download.id],
        liveSpeedBps = liveSpeed[group.download.id],
        remainingBytes = remainingBytes,
        onPlay = { onPlayDownload(group.download.id) },
        onPause = { scope.launch { downloadManager.pause(group.download.id) } },
        onResume = { scope.launch { downloadManager.resume(group.download.id) } },
        onCancel = { scope.launch { downloadManager.cancel(group.download.id) } },
        onDelete = { scope.launch { downloadManager.delete(group.download.id) } },
      )
    }
  }
}

@Composable
private fun DownloadCard(
  group: DownloadWithItems,
  liveFraction: Float?,
  liveSpeedBps: Long?,
  remainingBytes: Long?,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit,
  onDelete: () -> Unit,
) {
  val d = group.download
  val status = group.status
  val fraction = liveFraction ?: group.fraction
  val coverModel = d.coverPath ?: d.coverUrl

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
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
    Column(horizontalAlignment = Alignment.End) {
      if (group.isPlayable) {
        TextButton(onClick = onPlay) { Text(stringResource(R.string.downloads_action_play)) }
      }
      when (status) {
        DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
          TextButton(onClick = onPause) { Text(stringResource(R.string.downloads_action_pause)) }
          TextButton(onClick = onCancel) { Text(stringResource(R.string.downloads_action_cancel)) }
        }
        DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
          TextButton(onClick = onResume) { Text(stringResource(R.string.downloads_action_resume)) }
          TextButton(onClick = onDelete) { Text(stringResource(R.string.downloads_action_delete)) }
        }
        DownloadStatus.COMPLETED -> {
          TextButton(onClick = onDelete) { Text(stringResource(R.string.downloads_action_delete)) }
        }
        DownloadStatus.CANCELLED -> {
          TextButton(onClick = onDelete) { Text(stringResource(R.string.downloads_action_delete)) }
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
