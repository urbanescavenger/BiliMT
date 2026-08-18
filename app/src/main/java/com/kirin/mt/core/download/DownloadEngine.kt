package com.kirin.mt.core.download

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

    // 1) DASH init 段:必须占据文件开头 [0..b],需 initStart==0(标准 on-demand DASH 恒为 0)。
    var initDone = part.initDone
    if (hasDash && initStart == 0L) {
      if (file.length() < initEnd + 1) {
        val ok = downloadRange(part, headers, start = initStart, end = initEnd, file = file, append = false, onProgress = onProgress, shouldPause = shouldPause)
        if (!ok) return@withContext PartDownloadResult(completed = false, initDone = false, error = null)
        initDone = true
      } else {
        initDone = true
      }
    }

    // 2) 媒体段:续传偏移 = 目标起始 + 已下媒体字节。
    val fileLen = file.length()
    val mediaStart = part.mediaStartOffset + max(0L, fileLen - (initEnd + 1))
    val ok = downloadOpenRange(part, headers, start = mediaStart, file = file, onProgress = onProgress, shouldPause = shouldPause)
    val finalLen = file.length()
    val total = part.totalSize
    val completed = ok && (total <= 0L || finalLen >= total)
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
    onProgress: (DownloadProgress) -> Unit,
    shouldPause: () -> Boolean,
  ): Boolean = streamBody(part, headers, range = "bytes=$start-$end", file = file, append = append, onProgress = onProgress, shouldPause = shouldPause)

  /** 下载 [start] 起到 EOF 的开区间,追加到 [file] 末尾。返回 true=完整读完。 */
  private suspend fun downloadOpenRange(
    part: DownloadItemEntity,
    headers: Map<String, String>,
    start: Long,
    file: File,
    onProgress: (DownloadProgress) -> Unit,
    shouldPause: () -> Boolean,
  ): Boolean = streamBody(part, headers, range = "bytes=$start-", file = file, append = true, onProgress = onProgress, shouldPause = shouldPause)

  private suspend fun streamBody(
    part: DownloadItemEntity,
    headers: Map<String, String>,
    range: String,
    file: File,
    append: Boolean,
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
        file.outputStream(append).buffered().use { output ->
          body.byteStream().use { input ->
            val buffer = ByteArray(bufferSize)
            while (true) {
              if (shouldPause()) return false
              val read = input.read(buffer)
              if (read == -1) break
              output.write(buffer, 0, read)
              output.flush()
              onProgress(DownloadProgress(part.downloadId, part.id, file.length(), part.totalSize))
            }
          }
        }
        true
      }
    } catch (_: IOException) {
      false
    }
  }
}
