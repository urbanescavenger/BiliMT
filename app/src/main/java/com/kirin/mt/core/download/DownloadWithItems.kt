package com.kirin.mt.core.download

import androidx.room.Embedded
import androidx.room.Relation

/** 下载任务 + 分件列表(供 UI / 播放读取)。 */
data class DownloadWithItems(
  @Embedded val download: DownloadEntity,
  @Relation(parentColumn = "id", entityColumn = "downloadId") val items: List<DownloadItemEntity>,
) {
  val status: DownloadStatus
    get() = DownloadStatus.fromKey(download.status)

  val source: DownloadSource
    get() = DownloadSource.fromKey(download.source)

  val videoPart: DownloadItemEntity?
    get() = items.firstOrNull { PartKind.fromKey(it.kind) == PartKind.VIDEO }

  val audioPart: DownloadItemEntity?
    get() = items.firstOrNull { PartKind.fromKey(it.kind) == PartKind.AUDIO }

  val muxedPart: DownloadItemEntity?
    get() = items.firstOrNull { PartKind.fromKey(it.kind) == PartKind.MUXED }

  /** 已下载字节(各分件文件长度和;含封面则也计入——封面小,忽略即可)。 */
  val totalDownloadedBytes: Long
    get() = items.sumOf { item ->
      val f = java.io.File(item.localPath)
      if (f.exists()) f.length() else 0L
    }

  val totalSize: Long
    get() = if (items.any { it.totalSize > 0L }) items.sumOf { it.totalSize } else -1L

  val fraction: Float
    get() = if (totalSize > 0L) (totalDownloadedBytes.toFloat() / totalSize).coerceIn(0f, 1f) else 0f

  /** 是否具备离线可播条件(媒体分件全部 COMPLETED)。 */
  val isPlayable: Boolean
    get() {
      val media = items.filter { it.kind != PartKind.THUMB.key }
      return media.isNotEmpty() && media.all { DownloadStatus.fromKey(it.status) == DownloadStatus.COMPLETED }
    }
}
