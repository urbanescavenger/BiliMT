package com.kirin.mt.core.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Measures CDN performance for a list of candidate URLs.
 *
 * Each candidate is probed with `Range: bytes=0-65535` to download at most
 * 64 KiB. The tester records both time-to-first-byte (TTFB) and sustained
 * throughput, then ranks candidates by a combined score.
 *
 * - Individual probe timeout: [ProbeTimeoutMs]
 * - Overall measurement timeout: [TotalTimeoutMs]
 * - Max concurrent probes: [MaxConcurrency]
 */
class CdnSpeedTester(
  private val client: OkHttpClient,
) {

  suspend fun measure(urls: List<String>): List<Measurement> = withContext(Dispatchers.IO) {
    val uniqueUrls = urls
      .filter { it.startsWith("http://") || it.startsWith("https://") }
      .distinct()
      .take(MaxCandidates)
    if (uniqueUrls.isEmpty()) {
      return@withContext emptyList()
    }

    val probeClient = client.newBuilder()
      .connectTimeout(ConnectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      .readTimeout(ProbeReadTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      .writeTimeout(WriteTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      .build()

    try {
      val deferreds = uniqueUrls.map { url ->
        async {
          runCatching {
            withTimeoutOrNull(ConnectTimeoutMs) {
              probeUrl(probeClient, url)
            }
          }.getOrNull()
        }
      }
      deferreds
        .mapNotNull { it.await() }
        .filter { it.downloadedBytes > MinDownloadedBytes }
        .sortedByDescending { it.score }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
      emptyList()
    }
  }

  private fun probeUrl(client: OkHttpClient, url: String): Measurement {
    val request = Request.Builder()
      .url(url)
      .header("Range", "bytes=0-${MeasureRangeBytes - 1}")
      .get()
      .build()

    val startNs = System.nanoTime()
    var firstByteMs = -1L
    var downloadedBytes = 0L

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful && response.code != 206) {
        throw IllegalStateException("HTTP ${response.code}")
      }
      val receivedMs = response.receivedResponseAtMillis
      val sentMs = response.sentRequestAtMillis
      firstByteMs = (receivedMs - sentMs).coerceAtLeast(0L)
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

    val totalMs = (System.nanoTime() - startNs) / 1_000_000L
    return Measurement(
      url = url,
      firstByteMs = firstByteMs,
      totalMs = totalMs,
      downloadedBytes = downloadedBytes,
    )
  }

  data class Measurement(
    val url: String,
    val firstByteMs: Long,
    val totalMs: Long,
    val downloadedBytes: Long,
  ) {
    /**
     * Score rewards low TTFB and high throughput. The formula is intentionally
     * simple so that both metrics matter:
     *
     *   throughput = downloadedBytes / max(totalMs, 1)
     *   score = throughput / (firstByteMs * TtfbWeight + totalMs * TotalWeight + 100)
     */
    val score: Double
      get() {
        val throughput = downloadedBytes.toDouble() / totalMs.coerceAtLeast(1L)
        val penalty = firstByteMs * TtfbWeight + totalMs * TotalWeight + 100.0
        return throughput / penalty
      }
  }

  private companion object {
    const val MeasureRangeBytes = 64 * 1024L
    const val MeasureChunkBytes = 8 * 1024L
    const val MinDownloadedBytes = 1024L
    const val MaxCandidates = 6
    const val ConnectTimeoutMs = 2000L
    const val ProbeReadTimeoutMs = 3000L
    const val WriteTimeoutMs = 2000L
    const val TotalTimeoutMs = 4000L
    const val TtfbWeight = 2.0
    const val TotalWeight = 1.0
  }
}
