package com.kirin.mt.core.download

/** 下载来源。 */
enum class DownloadSource(val key: String) {
  BILI("bili"),
  YOUTUBE("youtube");

  companion object {
    fun fromKey(key: String?): DownloadSource = entries.firstOrNull { it.key == key } ?: BILI
  }
}

/** 一个下载任务的整体状态。 */
enum class DownloadStatus(val key: String) {
  QUEUED("queued"),
  RUNNING("running"),
  PAUSED("paused"),
  COMPLETED("completed"),
  FAILED("failed"),
  CANCELLED("cancelled");

  companion object {
    fun fromKey(key: String?): DownloadStatus = entries.firstOrNull { it.key == key } ?: QUEUED

    /** 活动态(需前台服务处理)。 */
    fun isActive(status: DownloadStatus): Boolean = status == QUEUED || status == RUNNING
  }
}

/** 下载分件类型:每个分件 = 一个独立本地文件。 */
enum class PartKind(val key: String) {
  VIDEO("video"),
  AUDIO("audio"),
  MUXED("muxed"),
  THUMB("thumb");

  companion object {
    fun fromKey(key: String?): PartKind = entries.firstOrNull { it.key == key } ?: MUXED
  }
}

/**
 * 单个分件的下载进度。UI 用它驱动进度条与状态。
 *
 * @param partId 对应 [DownloadItemEntity.id];thumb 无分件行时 -1。
 */
data class DownloadProgress(
  val downloadId: Long,
  val partId: Int,
  val downloadedBytes: Long,
  val totalBytes: Long,
  /** 近端瞬时下载速率(字节/秒),由 [DownloadEngine] 在逐块读循环里估算;未测到/暂停为 0。 */
  val bytesPerSecond: Long = 0L,
) {
  val fraction: Float
    get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * 用户选择的下载清晰度。由 UI 质量对话框构造,传给 [DownloadManager.enqueue]。
 *
 * @param biliQn B站目标 qn(PlaybackQuality.id);非 B站为 null。
 * @param biliQualityLabel B站清晰度显示名,存父行 qualityLabel。
 * @param youTubePreferMuxed YouTube:true=音视频一体单文件(≤720p);false=视频+音频分文件。
 * @param youTubeMaxHeight YouTube:视频最大高度(null=最高)。
 */
data class DownloadQualityChoice(
  val source: DownloadSource,
  val biliQn: Int? = null,
  val biliQualityLabel: String = "",
  val youTubePreferMuxed: Boolean = false,
  val youTubeMaxHeight: Int? = null,
)

/** 一个可下载分件(已解析出直链 + DASH range 信息)。 */
data class ResolvedPart(
  val url: String,
  val mimeType: String,
  val codecs: String,
  val width: Int,
  val height: Int,
  /** DASH init 段 `"a-b"`;progressive 为 null。 */
  val initRange: String?,
  /** DASH 媒体段起始偏移(indexRange 首值);progressive 为 0。 */
  val mediaStartOffset: Long,
)

/** 一个视频的下载解析结果(直链 + 各分件)。 */
data class ResolvedDownload(
  val videoId: String,
  val cid: Long,
  val title: String,
  val coverUrl: String,
  val durationMs: Long,
  val qualityLabel: String,
  /** 视频轨(video-only,DASH)。 */
  val video: ResolvedPart? = null,
  /** 音频轨(audio-only,DASH)。 */
  val audio: ResolvedPart? = null,
  /** 合并轨(progressive 单文件)。 */
  val muxed: ResolvedPart? = null,
  /** 下载所需 HTTP 头(UA/Referer/Origin/Cookie)。 */
  val headers: Map<String, String>,
) {
  /** 是否至少有一个可下载媒体分件。 */
  val hasMedia: Boolean
    get() = video != null || audio != null || muxed != null
}

