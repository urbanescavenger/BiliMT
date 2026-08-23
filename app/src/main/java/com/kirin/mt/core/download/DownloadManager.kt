package com.kirin.mt.core.download

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.kirin.mt.core.model.SourceBili
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.youtube.DOWNLOAD_PLAYLIST_NAME
import com.kirin.mt.core.youtube.YoutubePlaylistStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 下载管理器(公开 API)。Room 为唯一事实源;前台 [DownloadService] 负责实际字节传输与通知,
 * 经 [reportProgress] 回吐实时进度到 [progress]。
 */
class DownloadManager(
  private val appContext: Context,
  private val dao: DownloadDao,
  private val storage: DownloadStorage,
  private val urlResolver: DownloadUrlResolver,
  private val engine: DownloadEngine,
  private val thumbnailClient: OkHttpClient,
  private val json: Json,
  private val playlistStore: YoutubePlaylistStore,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val logTag = "DownloadMgr"

  /** 全部下载任务(Room 驱动,进程死/界面重建后仍准确)。 */
  val downloads: Flow<List<DownloadWithItems>> = dao.observeAll()

  /** 实时字节进度(服务回吐)。 */
  val progress = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64)

  /** 当前正在下载的任务 id(无则 null)。 */
  val activeDownloadId = MutableStateFlow<Long?>(null)

  /** 暂停/取消标志(内存级,供引擎 shouldPause 快速判断)。 */
  private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()

  suspend fun enqueue(request: PlaybackRequest, choice: DownloadQualityChoice): Result<Long> {
    // 网络解析(NewPipe getInfo / B站 playurl)必须离开主线程:MobilePlayer 的 enqueue 调用方跑在
    // Main,而 NewPipe 的 StreamInfo.getInfo 内部同步走 PoTokenProvider(WebView),设计要求在
    // Dispatchers.IO 调——主线程上抛的异常 message 为 null → UI 报「无直链」。
    return withContext(Dispatchers.IO) {
      enqueueOnIo(request, choice)
    }
  }

  private suspend fun enqueueOnIo(request: PlaybackRequest, choice: DownloadQualityChoice): Result<Long> {
    if (dao.existsActive(request.bvid, choice.source.key)) {
      return Result.failure(IllegalStateException("该视频已在下载队列中"))
    }
    val resolved = urlResolver.resolve(request, choice).getOrElse { return Result.failure(it) }
    if (!resolved.hasMedia) return Result.failure(IllegalStateException("无可下载的媒体流"))

    val id = dao.insertDownload(
      DownloadEntity(
        source = choice.source.key,
        videoId = resolved.videoId,
        cid = resolved.cid,
        title = resolved.title,
        coverUrl = resolved.coverUrl,
        status = DownloadStatus.QUEUED.key,
        durationMs = resolved.durationMs,
        qualityLabel = resolved.qualityLabel,
        videoMimeType = (resolved.video ?: resolved.muxed)?.mimeType ?: "",
        videoCodecs = (resolved.video ?: resolved.muxed)?.codecs ?: "",
        videoWidth = (resolved.video ?: resolved.muxed)?.width ?: 0,
        videoHeight = (resolved.video ?: resolved.muxed)?.height ?: 0,
        audioMimeType = resolved.audio?.mimeType ?: "",
        audioCodecs = resolved.audio?.codecs ?: "",
        headersJson = json.encodeToString(resolved.headers),
        createdAtMs = System.currentTimeMillis(),
      ),
    )
    if (resolved.muxed != null) {
      insertPart(id, PartKind.MUXED, resolved.muxed, storage.videoFile(id))
    } else {
      resolved.video?.let { insertPart(id, PartKind.VIDEO, it, storage.videoFile(id)) }
      resolved.audio?.let { insertPart(id, PartKind.AUDIO, it, storage.audioFile(id)) }
    }
    // 封面后台下载(不阻塞入队)。
    scope.launch { downloadThumb(id, resolved.coverUrl) }
    startService(DownloadService.ACTION_ENQUEUE, id)
    return Result.success(id)
  }

  private suspend fun insertPart(downloadId: Long, kind: PartKind, part: ResolvedPart, file: File) {
    dao.insertItem(
      DownloadItemEntity(
        downloadId = downloadId,
        kind = kind.key,
        url = part.url,
        localPath = file.path,
        mimeType = part.mimeType,
        codecs = part.codecs,
        initRange = part.initRange,
        mediaStartOffset = part.mediaStartOffset,
        totalSize = -1L,
        status = DownloadStatus.QUEUED.key,
      ),
    )
  }

  suspend fun pause(id: Long) {
    pauseFlags[id]?.set(true)
    dao.updateStatus(id, DownloadStatus.PAUSED.key)
  }

  suspend fun resume(id: Long) {
    pauseFlags.remove(id)
    dao.updateStatus(id, DownloadStatus.RUNNING.key)
    // 未完成分件归队。
    dao.itemsFor(id).forEach { item ->
      if (DownloadStatus.fromKey(item.status) != DownloadStatus.COMPLETED) {
        dao.updateItemStatus(item.id, DownloadStatus.QUEUED.key)
      }
    }
    startService(DownloadService.ACTION_RESUME, id)
  }

  suspend fun cancel(id: Long) {
    pauseFlags[id]?.set(true)
    dao.itemsFor(id).forEach { item ->
      if (DownloadStatus.fromKey(item.status) != DownloadStatus.COMPLETED) {
        dao.updateItemStatus(item.id, DownloadStatus.CANCELLED.key)
      }
    }
    dao.updateStatus(id, DownloadStatus.CANCELLED.key)
  }

  /** 删除任务:取消 + 删文件 + 删库行(CASCADE 删分件);并从「下载」存档播放列表移除该视频。 */
  suspend fun delete(id: Long) {
    val videoId = dao.getById(id)?.download?.videoId
    cancel(id)
    storage.deleteAll(id)
    dao.delete(id)
    // 离线列表删下载 → 同步移除「下载」播放列表里的存档,避免残留孤卡。
    if (videoId != null) {
      runCatching { playlistStore.removeVideo(DOWNLOAD_PLAYLIST_NAME, videoId) }
        .onFailure { Log.w(logTag, "删除下载后从下载播放列表移除失败: ${it.message}") }
    }
  }

  /** 返回可播文件(video/muxed,audio 可选)。未完成返回对应 null。 */
  fun playbackFiles(id: Long): Pair<File?, File?> {
    val media = storage.videoFile(id).takeIf { it.exists() && it.length() > 0L }
    val audio = storage.audioFile(id).takeIf { it.exists() && it.length() > 0L }
    return media to audio
  }

  // ── 服务协作 ────────────────────────────────────────────────────────────

  fun reportProgress(p: DownloadProgress) {
    progress.tryEmit(p)
  }

  /** 是否还有活动下载(供服务决定是否停前台)。 */
  suspend fun hasActiveDownloads(): Boolean {
    return dao.observeAll().first().any { DownloadStatus.isActive(it.status) }
  }

  /** 处理整个队列,最多 [MAX_CONCURRENT] 个分件并发。返回是否有剩余活动下载。 */
  suspend fun runQueue(onProgress: (DownloadProgress) -> Unit): Boolean {
    val all = dao.observeAll().first()
    val active = all.filter { DownloadStatus.isActive(it.status) }
    if (active.isEmpty()) {
      activeDownloadId.value = null
      return false
    }
    activeDownloadId.value = active.first().download.id
    // 下载实际开始即把父行置 RUNNING:此前父行只在 finalizeGroup(全部 join 完成后)才从 QUEUED
    // 转 RUNNING,导致 UI 整段下载期读 group.status 一直显示「排队中」——内容/日志在更新但界面不刷。
    active.forEach { group ->
      if (DownloadStatus.fromKey(group.download.status) == DownloadStatus.QUEUED) {
        dao.updateStatus(group.download.id, DownloadStatus.RUNNING.key)
      }
    }
    val parts = active.flatMap { it.items }
      .filter { item ->
        val s = DownloadStatus.fromKey(item.status)
        s == DownloadStatus.QUEUED || s == DownloadStatus.RUNNING
      }
    if (parts.isNotEmpty()) {
      val semaphore = Semaphore(MAX_CONCURRENT)
      val jobs = parts.map { part ->
        scope.launch {
          semaphore.withPermit {
            downloadOne(part, onProgress)
          }
        }
      }
      jobs.forEach { it.join() }
    }

    // 收尾:把每个活动下载的父行状态定到终态/静息态。
    active.forEach { group -> finalizeGroup(group) }
    activeDownloadId.value = null
    return hasActiveDownloads()
  }

  /**
   * 依据分件状态把父行定到终态/静息态:
   * - 全部媒体分件 COMPLETED → COMPLETED(清暂停标志)。
   * - 全部媒体分件 CANCELLED → CANCELLED。
   * - 任一媒体分件 FAILED → FAILED(终态,用户 resume 重试)。
   * - 其余(部分 QUEUED/PAUSED)→ 保持 RUNNING(待续传)。
   */
  private suspend fun finalizeGroup(group: DownloadWithItems) {
    val media = group.items.filter { it.kind != PartKind.THUMB.key }
    if (media.isEmpty()) return
    val statuses = media.map { DownloadStatus.fromKey(it.status) }
    when {
      statuses.all { it == DownloadStatus.COMPLETED } -> {
        dao.updateStatus(group.download.id, DownloadStatus.COMPLETED.key)
        pauseFlags.remove(group.download.id)
        // 下载完成自动存档进「下载」播放列表,方便离线统一回看。
        // addVideo 按 bvid(videoId) 去重,重复下载/续传不会重复入列。
        runCatching { playlistStore.addVideo(DOWNLOAD_PLAYLIST_NAME, group.download.toVideoSummary()) }
          .onFailure { Log.w(logTag, "下载完成写入下载播放列表失败: ${it.message}") }
      }
      statuses.all { it == DownloadStatus.CANCELLED } -> {
        dao.updateStatus(group.download.id, DownloadStatus.CANCELLED.key)
        pauseFlags.remove(group.download.id)
      }
      statuses.any { it == DownloadStatus.FAILED } -> {
        dao.updateStatus(group.download.id, DownloadStatus.FAILED.key)
        pauseFlags.remove(group.download.id)
      }
      else -> {
        // 部分 QUEUED/PAUSED → 保持 RUNNING(待续传)。
        if (DownloadStatus.fromKey(group.download.status) == DownloadStatus.QUEUED) {
          dao.updateStatus(group.download.id, DownloadStatus.RUNNING.key)
        }
      }
    }
  }

  private suspend fun downloadOne(item: DownloadItemEntity, onProgress: (DownloadProgress) -> Unit) {
    val paused = pauseFlags[item.downloadId] ?: AtomicBoolean(false).also { pauseFlags[item.downloadId] = it }
    // 已取消/暂停的分件跳过。
    if (paused.get()) return
    val parent = dao.getById(item.downloadId)
    val parentStatus = parent?.status
    if (parentStatus != null && parentStatus != DownloadStatus.RUNNING && parentStatus != DownloadStatus.QUEUED) return
    dao.updateItemStatus(item.id, DownloadStatus.RUNNING.key)
    val headers = headersFor(parent?.download)
    val file = File(item.localPath)

    // 首次探测总长(progressive 或未探)。
    var effective = item
    if (effective.totalSize <= 0L) {
      val total = engine.probeLength(effective.url, headers)
      if (total > 0L) {
        dao.updateItemTotalSize(effective.id, total)
        effective = effective.copy(totalSize = total)
      }
    }

    val result = engine.downloadPart(
      part = effective,
      file = file,
      headers = headers,
      onProgress = { p -> onProgress(p) },
      shouldPause = { paused.get() },
    )
    val currentParent = dao.getById(item.downloadId)?.status
    when {
      result.completed -> {
        dao.updateItemStatus(item.id, DownloadStatus.COMPLETED.key, initDone = true)
      }
      // 父行暂停 → 分件标记 PAUSED。
      currentParent == DownloadStatus.PAUSED -> {
        dao.updateItemStatus(item.id, DownloadStatus.PAUSED.key, initDone = result.initDone)
      }
      // 父行取消 → 分件 CANCELLED。
      currentParent == DownloadStatus.CANCELLED -> {
        dao.updateItemStatus(item.id, DownloadStatus.CANCELLED.key, initDone = result.initDone)
      }
      else -> {
        // 中断/失败 → FAILED,保留文件供续传。
        dao.updateItemStatus(item.id, DownloadStatus.FAILED.key, error = result.error, initDone = result.initDone)
      }
    }
  }

  private fun headersFor(download: DownloadEntity?): Map<String, String> {
    if (download == null) return emptyMap()
    return runCatching { json.decodeFromString<Map<String, String>>(download.headersJson) }.getOrDefault(emptyMap())
  }

  /**
   * 下载父行 → 播放列表卡片摘要。B 站 videoId=bvid、YouTube videoId=videoId(bvid 字段承载);
   * ownerName/view 下载时不存,置空/0 → 播放列表详情卡不渲染作者行,仅标题 + 封面(coverUrl)。
   */
  private fun DownloadEntity.toVideoSummary(): VideoSummary = VideoSummary(
    bvid = videoId,
    title = title,
    pic = coverUrl,
    // 下载时不存作者/计数,显式给空值/0(编译器不支持跳过中间默认参数)。
    ownerName = "",
    ownerFace = "",
    ownerMid = 0L,
    view = 0,
    danmaku = 0,
    duration = (durationMs / 1000L).toInt(),
    pubdate = 0L,
    badge = qualityLabel,
    cid = cid,
    source = if (source == DownloadSource.YOUTUBE.key) SourceYoutube else SourceBili,
  )

  private suspend fun downloadThumb(downloadId: Long, coverUrl: String) {
    if (coverUrl.isBlank()) return
    try {
      val file = storage.thumbFile(downloadId)
      val request = Request.Builder().url(coverUrl).header("User-Agent", "BiliMT-Android").build()
      thumbnailClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return
        file.parentFile?.mkdirs()
        file.outputStream().use { out -> response.body?.byteStream()?.use { it.copyTo(out) } }
        dao.updateCoverPath(downloadId, file.path)
      }
    } catch (e: Exception) {
      Log.w(logTag, "缩略图下载失败: ${e.message}")
    }
  }

  private fun startService(action: String, id: Long) {
    ContextCompat.startForegroundService(
      appContext,
      Intent(appContext, DownloadService::class.java).setAction(action).putExtra(DownloadService.EXTRA_ID, id),
    )
  }

  private companion object {
    // 对齐 LibreTube MAX_CONCURRENT_DOWNLOADS=6:多分件/多任务并发堆带宽。单视频仅视频+音频两分件,
    // 但多任务排队时能并行下,避免串行。
    const val MAX_CONCURRENT = 6
  }
}
