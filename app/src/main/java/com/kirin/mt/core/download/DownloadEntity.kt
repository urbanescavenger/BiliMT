package com.kirin.mt.core.download

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 下载任务父行(一个视频)。 */
@Entity(tableName = "download")
data class DownloadEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  /** [DownloadSource.key]。 */
  val source: String,
  /** B站 bvid / YouTube videoId。 */
  val videoId: String,
  val cid: Long = 0L,
  val title: String,
  val coverUrl: String = "",
  /** 封面本地路径(下载成功后填充,供离线列表渲染)。 */
  val coverPath: String? = null,
  /** [DownloadStatus.key]。 */
  val status: String,
  val durationMs: Long = 0L,
  /** 用户选择的清晰度显示名(如 "1080p"/"1080P 高清")。 */
  val qualityLabel: String = "",
  val videoMimeType: String = "",
  val videoCodecs: String = "",
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
  val audioMimeType: String = "",
  val audioCodecs: String = "",
  val errorMessage: String? = null,
  /** 下载所需 HTTP 头(Cookie/Referer/Origin/UA)的 JSON,供续传复用。 */
  val headersJson: String = "{}",
  val createdAtMs: Long,
)
