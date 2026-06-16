package com.kirin.mt.core.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class UpdateDownloader(
  context: Context,
  private val client: OkHttpClient,
) {
  private val appContext: Context = context.applicationContext

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

    val request = Request.Builder()
      .url(asset.downloadUrl)
      .header("User-Agent", "BiliMT-Android")
      .header("Accept", "application/octet-stream")
      .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("Download failed: HTTP ${response.code}")
      }

      val total = response.body?.contentLength() ?: asset.size
      temp.parentFile?.mkdirs()
      temp.outputStream().use { output ->
        response.body?.byteStream()?.use { input ->
          val buffer = ByteArray(8192)
          var downloaded = 0L
          var read: Int
          while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            downloaded += read
            onProgress(downloaded, total)
          }
        } ?: throw IOException("Empty response body")
      }
    }

    if (!temp.exists() || temp.length() != asset.size) {
      throw IOException("Download incomplete: ${temp.length()}/${asset.size} bytes")
    }

    if (target.exists() && !target.delete()) {
      throw IOException("Cannot replace existing file: $target")
    }

    if (!temp.renameTo(target)) {
      throw IOException("Cannot rename temp file to $target")
    }

    target
  }

  fun isDownloaded(fileName: String): Boolean {
    val f = File(File(appContext.cacheDir, "updates"), sanitizeFileName(fileName))
    return f.exists() && f.length() > 0
  }

  fun downloadedFile(fileName: String): File =
    File(File(appContext.cacheDir, "updates"), sanitizeFileName(fileName))

  private fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[^\w.\-]"""), "_")
}
