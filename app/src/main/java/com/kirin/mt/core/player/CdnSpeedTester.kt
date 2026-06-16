package com.kirin.mt.core.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.system.measureTimeMillis

/**
 * Measures real-world download speed for a list of candidate CDN URLs.
 *
 * Each candidate is requested with `Range: bytes=0-65535` so we only download
 * up to 64 KiB. The fastest successful candidate is returned first.
 */
class CdnSpeedTester(
  private val client: OkHttpClient,
) {

  suspend fun measure(urls: List<String>): List<Measurement> = withContext(Dispatchers.IO) {
    val uniqueUrls = urls.filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
    if (uniqueUrls.isEmpty()) {
      return@withContext emptyList()
    }

    val deferreds = uniqueUrls.map { url ->
      async {
        runCatching {
          val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${MeasureRangeBytes - 1}")
            .get()
            .build()

          var downloadedBytes = 0L
          val elapsedMs = measureTimeMillis {
            client.newCall(request).execute().use { response ->
              if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("HTTP ${response.code}")
              }
              response.body?.source()?.use { source ->
                val buffer = okio.Buffer()
                while (downloadedBytes < MeasureRangeBytes) {
                  val read = source.read(buffer, MeasureChunkBytes)
                  if (read == -1L) break
                  downloadedBytes += read
                  buffer.clear()
                }
              }
            }
          }
          Measurement(url = url, elapsedMs = elapsedMs, downloadedBytes = downloadedBytes)
        }.getOrNull()
      }
    }

    deferreds
      .mapNotNull { it.await() }
      .filter { it.downloadedBytes > 0 }
      .sortedBy { it.elapsedMs }
  }

  data class Measurement(
    val url: String,
    val elapsedMs: Long,
    val downloadedBytes: Long,
  )

  private companion object {
    const val MeasureRangeBytes = 64 * 1024L
    const val MeasureChunkBytes = 8 * 1024L
  }
}
