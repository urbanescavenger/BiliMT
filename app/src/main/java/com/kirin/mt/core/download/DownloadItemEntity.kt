package com.kirin.mt.core.download

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 下载分件行 = 一个独立本地文件。 */
@Entity(
  tableName = "downloadItem",
  foreignKeys = [
    ForeignKey(
      entity = DownloadEntity::class,
      parentColumns = ["id"],
      childColumns = ["downloadId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("downloadId")],
)
data class DownloadItemEntity(
  @PrimaryKey(autoGenerate = true) val id: Int = 0,
  val downloadId: Long,
  /** [PartKind.key]。 */
  val kind: String,
  /** 签名直链快照(下载时使用,存盘防过期后无法续传)。 */
  val url: String,
  val localPath: String,
  val mimeType: String = "",
  val codecs: String = "",
  /** DASH init 段 range `"a-b"`;progressive 为 null。 */
  val initRange: String? = null,
  /** DASH 媒体段起始偏移(indexRange 首值);progressive 为 0。 */
  val mediaStartOffset: Long = 0L,
  /** 总字节数(-1 未知,首探后填充)。 */
  val totalSize: Long = -1L,
  /** init 段是否已写入。 */
  val initDone: Boolean = false,
  /** [DownloadStatus.key]。 */
  val status: String,
  val error: String? = null,
)
