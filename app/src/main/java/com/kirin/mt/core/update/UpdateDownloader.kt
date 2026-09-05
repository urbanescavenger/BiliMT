package com.kirin.mt.core.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateDownloader(
  context: Context,
  private val client: OkHttpClient,
) {
  private val appContext: Context = context.applicationContext

  /**
   * 下载 APK,失败自动重试 + 断点续传:
   * - 每次尝试从 .tmp 已下字节发 Range 续传;服务端不认 Range 回 200 时整包重下;
   * - 断流/网络抖动(readTimeout 抛错、HTTP 5xx 等)指数退避重试,不再让 UI 一次失败
   *   就整包从头来或干等 5 分钟 readTimeout;
   * - callTimeout 按包大小动态给(每次下载 newBuilder() 覆盖):readTimeout 按每次 read
   *   重置,慢滴服务器只要偶尔挤进 1 字节就永不超时,必须整调用上限兜底。
   */
  suspend fun download(
    asset: UpdateAsset,
    fileName: String = asset.name,
    onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
  ): File = withContext(Dispatchers.IO) {
    val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
    val safeName = sanitizeFileName(fileName)
    val target = File(dir, safeName)
    val temp = File(dir, "$safeName.tmp")

    if (target.exists() && target.length() == asset.size) {
      return@withContext target
    }

    // callTimeout 按包大小给:按 32KB/s 慢速底线估算 + 2min 余量,夹在 [10min, 30min]。
    val callClient = client.newBuilder()
      .callTimeout(
        (asset.size / 32_000L + 120_000L).coerceIn(600_000L, 1_800_000L),
        TimeUnit.SECONDS,
      )
      .build()

    var lastError: IOException? = null
    for (attempt in 1..MAX_ATTEMPTS) {
      if (attempt > 1) delay(RETRY_BASE_DELAY_MS * (attempt - 1))
      try {
        downloadOnce(callClient, asset, temp, onProgress)
        if (!temp.exists() || temp.length() != asset.size) {
          throw IOException("Download incomplete: ${temp.length()}/${asset.size} bytes")
        }
        if (target.exists() && !target.delete()) {
          throw IOException("Cannot replace existing file: $target")
        }
        if (!temp.renameTo(target)) {
          throw IOException("Cannot rename temp file to $target")
        }
        return@withContext target
      } catch (e: IOException) {
        // temp 留着供下轮从已下字节续传
        lastError = e
      }
    }
    throw lastError ?: IOException("Download failed after $MAX_ATTEMPTS attempts")
  }

  /** 单次尝试:temp 已有部分字节则发 Range 续传(200=服务端不认 Range,回退整包)。 */
  private fun downloadOnce(
    client: OkHttpClient,
    asset: UpdateAsset,
    temp: File,
    onProgress: (downloaded: Long, total: Long) -> Unit,
  ) {
    val base = if (temp.exists() && temp.length() in 1..asset.size) temp.length() else 0L
    if (base == 0L) temp.delete()

    val request = Request.Builder()
      .url(asset.downloadUrl)
      .header("User-Agent", "BiliMT-Android")
      .header("Accept", "application/octet-stream")
      .apply { if (base > 0) header("Range", "bytes=$base-") }
      .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("Download failed: HTTP ${response.code}")
      }
      val resumed = response.code == 206 && base > 0
      val start = if (resumed) base else 0L
      temp.parentFile?.mkdirs()
      FileOutputStream(temp, resumed).use { output ->
        response.body?.byteStream()?.use { input ->
          val buffer = ByteArray(8192)
          var downloaded = start
          var read: Int
          while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            downloaded += read
            onProgress(downloaded, asset.size)
          }
        } ?: throw IOException("Empty response body")
      }
    }
  }

  fun isDownloaded(fileName: String): Boolean {
    val f = File(File(appContext.cacheDir, "updates"), sanitizeFileName(fileName))
    return f.exists() && f.length() > 0
  }

  /**
   * 缓存文件大小，不存在返回 -1。
   * 用于 refresh() 时校验缓存是否与远端 asset 一致：debug/release 都复用固定 asset 名
   * （如 BiliMT-debug.apk），旧包残留在 cache 里会让 isDownloaded() 恒真，导致每次都
   * 安装旧缓存 APK 而不重新下载新包（更新后版本号不变）。只有大小匹配才视为已是最新包。
   */
  fun downloadedFileSize(fileName: String): Long {
    val f = File(File(appContext.cacheDir, "updates"), sanitizeFileName(fileName))
    return if (f.exists()) f.length() else -1L
  }

  fun downloadedFile(fileName: String): File =
    File(File(appContext.cacheDir, "updates"), sanitizeFileName(fileName))

  private fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[^\w.\-]"""), "_")

  private companion object {
    /** 失败重试上限:指数退避 1s/2s/3s/4s,5 次足够跨过短抖动,彻底失败仍回 UI toast。 */
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_BASE_DELAY_MS = 1_000L
  }
}