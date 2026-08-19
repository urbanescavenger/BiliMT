package com.kirin.mt.core.download

import android.os.SystemClock
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 单个分件下载的结果。 */
data class PartDownloadResult(
  /** true=已完整下载(可播);false=未完成(暂停/中断/失败,可续传)。 */
  val completed: Boolean,
  /** init 段是否已写入(供调用方落库 initDone)。 */
  val initDone: Boolean,
  val error: String?,
)

/**
 * 纯单分件下载器(无状态,不写库)。用 [OkHttpClient] 流式写本地文件。
 *
 * 支持 DASH(init 段 + media 段 Range 拼接成完整 on-demand fMP4)与 progressive(整文件连续流)。
 * 每请求带调用方传入的 headers(UA/Referer/Origin/Cookie,对齐 [com.kirin.mt.core.player.BiliMediaDataSourceFactory])。
 */
class DownloadEngine(
  private val client: OkHttpClient,
) {
  private val bufferSize = 64 * 1024

  /** 进度上报最小间隔(ms)。对齐 LibreTube 非逐 buffer 上报:逐块回调会让调用方(前台通知 +
   * Room 查询)占满共享 IO 线程池,饿死实际网络读——本地测试下载远慢于播放即此因。 */
  private val emitIntervalMs = 120L

  /** 探测 URL 总字节数:`Range: bytes=0-0`,解析 `Content-Range: bytes 0-0/<TOTAL>`。失败返回 -1。 */
  suspend fun probeLength(url: String, headers: Map<String, String>): Long = withContext(Dispatchers.IO) {
    val request = Request.Builder()
      .url(url)
      .apply { headers.forEach { (k, v) -> header(k, v) } }
      .header("Range", "bytes=0-0")
      .header("Accept-Encoding", "identity")
      .build()
    try {
      client.newCall(request).execute().use { response ->
        if (response.code != 200 && response.code != 206) return@withContext -1L
        val contentRange = response.header("Content-Range") ?: return@withContext -1L
        contentRange.substringAfter("/").trim().toLongOrNull() ?: -1L
      }
    } catch (_: IOException) {
      -1L
    }
  }

  /**
   * 下载一个分件到 [file]。续传逻辑:
   * - DASH([part.initRange] 非空):先确保 init 段写入(不足则整段重下,init 小),再媒体段从
   *   `mediaStartOffset + (已下媒体字节)` 起流到 EOF。
   * - Progressive:从 `file.length()` 起流到 EOF。
   *
   * 进度经 [onProgress] 回调(带真实 partId/total)。未完成时:暂停(经 [shouldPause])→ error=null;
   * 中断/失败 → error 非空。
   */
  suspend fun downloadPart(
    part: DownloadItemEntity,
    file: File,
    headers: Map<String, String>,
    onProgress: (DownloadProgress) -> Unit,
    shouldPause: () -> Boolean,
  ): PartDownloadResult = withContext(Dispatchers.IO) {
    file.parentFile?.mkdirs()
    val initStart = part.initRange?.substringBefore("-")?.toLongOrNull()
    val initEnd = part.initRange?.substringAfter("-")?.toLongOrNull()
    val hasDash = initStart != null && initEnd != null
    // hasDash 保证 initStart/initEnd 非空;Kotlin 不透过 hasDash 智能转换,这里显式断言。
    val dashInitStart = initStart ?: 0L
    val dashInitEnd = initEnd ?: 0L

    // 分件下载完成后的目标文件长度(对齐 LibreTube 的 fileSize>=downloadSize 口径)。
    // DASH:init 段(0..initEnd)+ 媒体段(mediaStartOffset..EOF)拼成一个文件;progressive:整资源。
    // probe 的 part.totalSize 是整资源 Content-Length,DASH 只下其中一部分,需折算成实际应下文件长度,
    // 否则 completed 判定 finalLen>=total 永远不满足、进度条剩余显示「减不完」的残值。
    val targetTotal = if (hasDash && part.totalSize > 0L && part.totalSize > part.mediaStartOffset) {
      (dashInitEnd + 1L) + (part.totalSize - part.mediaStartOffset)
    } else {
      part.totalSize
    }

    // 1) DASH init 段:必须占据文件开头 [0, b],需 init==0(标准 on-demand DASH 恒为 0)。
    var initDone = part.initDone
    if (hasDash && dashInitStart == 0L) {
      if (file.length() < dashInitEnd + 1) {
        val ok = downloadRange(part, headers, start = dashInitStart, end = dashInitEnd, file = file, append = false, reportTotal = targetTotal, onProgress = onProgress, shouldPause = shouldPause)
        if (!ok) return@withContext PartDownloadResult(completed = false, initDone = false, error = null)
        initDone = true
      } else {
        initDone = true
      }
    }

    // 2) 媒体段:续传偏移 = 目标起始 + 已下媒体字节。
    val fileLen = file.length()
    val mediaStart = part.mediaStartOffset + max(0L, fileLen - (dashInitEnd + 1))
    val ok = downloadOpenRange(part, headers, start = mediaStart, file = file, reportTotal = targetTotal, onProgress = onProgress, shouldPause = shouldPause)
    val finalLen = file.length()
    val completed = ok && (targetTotal <= 0L || finalLen >= targetTotal)
    PartDownloadResult(completed = completed, initDone = initDone, error = if (ok) null else "interrupted")
  }

  /** 下载闭区间 [start,end] 到 [file]。append=false 清空文件(用于 init)。返回 true=完整写完。 */
  private suspend fun downloadRange(
    part: DownloadItemEntity,
    headers: Map<String, String>,
    start: Long,
    end: Long,
    file: File,
    append: Boolean,
    reportTotal: Long,
    onProgress: (DownloadProgress) -> Unit,
    shouldPause: () -> Boolean,
  ): Boolean = streamBody(part, headers, range = "bytes=$start-$end", file = file, append = append, reportTotal = reportTotal, onProgress = onProgress, shouldPause = shouldPause)

  /** 下载 [start] 起到 EOF 的开区间,追加到 [file] 末尾。返回 true=完整读完。 */
  private suspend fun downloadOpenRange(
    part: DownloadItemEntity,
    headers: Map<String, String>,
    start: Long,
    file: File,
    reportTotal: Long,
    onProgress: (DownloadProgress) -> Unit,
    shouldPause: () -> Boolean,
  ): Boolean = streamBody(part, headers, range = "bytes=$start-", file = file, append = true, reportTotal = reportTotal, onProgress = onProgress, shouldPause = shouldPause)

  private suspend fun streamBody(
    part: DownloadItemEntity,
    headers: Map<String, String>,
    range: String,
    file: File,
    append: Boolean,
    reportTotal: Long,
    onProgress: (DownloadProgress) -> Unit,
    shouldPause: () -> Boolean,
  ): Boolean {
    val request = Request.Builder()
      .url(part.url)
      .apply { headers.forEach { (k, v) -> header(k, v) } }
      .header("Range", range)
      .header("Accept-Encoding", "identity")
      .build()
    return try {
      client.newCall(request).execute().use { response ->
        if (response.code != 200 && response.code != 206) return false
        val body = response.body ?: return false
        java.io.FileOutputStream(file, append).buffered().use { output ->
          body.byteStream().use { input ->
            val buffer = ByteArray(bufferSize)
            // 本地字节计数累加,避免逐块调 file.length()(每次 stat 系统调用)。
            // 进度/速率按 emitIntervalMs 聚合上报,而非逐 64KB——高频 onProgress 会带起调用方
            // 的 Room 查询 + 前台通知 binder 调用,占满共享 IO 线程池饿死实际网络读(对齐 LibreTube)。
            var written = file.length()
            var lastEmitBytes = written
            var lastEmit = SystemClock.elapsedRealtime()
            while (true) {
              if (shouldPause()) return false
              val read = input.read(buffer)
              if (read == -1) break
              output.write(buffer, 0, read)
              output.flush()
              written += read
              val now = SystemClock.elapsedRealtime()
              val dt = now - lastEmit
              if (dt >= emitIntervalMs) {
                val speed = if (dt > 0) ((written - lastEmitBytes).toDouble() / dt * 1000).toLong().coerceAtLeast(0L) else 0L
                lastEmitBytes = written
                lastEmit = now
                onProgress(DownloadProgress(part.downloadId, part.id, written, reportTotal, speed))
              }
            }
            // 流结束补发一次,保证末尾字节/速率也上报(进度条到 100%)。
            val endNow = SystemClock.elapsedRealtime()
            val endDt = endNow - lastEmit
            val endSpeed = if (endDt > 0) ((written - lastEmitBytes).toDouble() / endDt * 1000).toLong().coerceAtLeast(0L) else 0L
            onProgress(DownloadProgress(part.downloadId, part.id, written, reportTotal, endSpeed))
          }
        }
        true
      }
    } catch (_: IOException) {
      false
    }
  }
}
