package com.kirin.mt.core.update

import android.content.Context
import com.kirin.mt.core.network.BiliApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UpdateDownloader(
  context: Context,
  private val apiClient: BiliApiClient,
) {
  private val appContext: Context = context.applicationContext

  suspend fun download(asset: UpdateAsset, fileName: String = asset.name): File =
    withContext(Dispatchers.IO) {
      val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
      val target = File(dir, sanitizeFileName(fileName))
      val bytes = apiClient.getBytes(
        url = asset.downloadUrl,
        headers = mapOf("User-Agent" to "BiliMT-Android"),
      )
      target.outputStream().use { it.write(bytes) }
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
