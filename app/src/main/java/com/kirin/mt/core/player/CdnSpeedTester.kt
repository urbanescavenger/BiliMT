package com.kirin.mt.core.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

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

  suspend fun measure(
    urls: List<String>,
    options: MeasureOptions = MeasureOptions.Dialog,
    /**
     * When true, return as soon as the first viable candidate completes
     * (instead of waiting for every probe). Used on the live playback path so a
     * dead/slow backupUrl cannot block first-frame for ~1s while the winner
     * was already back in tens of ms. Any other candidates that already
     * finished are drained as fallbacks; the rest are cancelled by structured
     * concurrency. The settings dialog keeps earlyReturn=false to show the
     * full ranked list.
     */
    earlyReturn: Boolean = false,
  ): List<Measurement> = withContext(Dispatchers.IO) {
    val uniqueUrls = urls
      .filter { it.startsWith("http://") || it.startsWith("https://") }
      .distinct()
      .take(MaxCandidates)
    if (uniqueUrls.isEmpty()) {
      return@withContext emptyList()
    }

    val probeClient = client.newBuilder()
      .connectTimeout(options.connectMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      .readTimeout(options.readMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      .writeTimeout(WriteTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      .build()

    if (earlyReturn) {
      measureEarly(probeClient, uniqueUrls, options)
    } else {
      measureAll(probeClient, uniqueUrls, options)
    }
  }

  /** Await every probe and return the full ranked list (dialog behavior). */
  private suspend fun measureAll(
    probeClient: OkHttpClient,
    uniqueUrls: List<String>,
    options: MeasureOptions,
  ): List<Measurement> {
    val deferreds = uniqueUrls.map { url ->
      async {
        runCatching { probeUrl(probeClient, url) }.getOrNull()
      }
    }
    return try {
      withTimeout(options.totalMs) {
        deferreds
          .mapNotNull { it.await() }
          .filter { it.downloadedBytes > MinDownloadedBytes }
          .sortedByDescending { it.score }
      }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
      emptyList()
    }
  }

  /**
   * Return as soon as the first viable candidate completes, draining any
   * already-finished probes as fallbacks and cancelling the rest.
   */
  private suspend fun measureEarly(
    probeClient: OkHttpClient,
    uniqueUrls: List<String>,
    options: MeasureOptions,
  ): List<Measurement> = coroutineScope {
    val channel = Channel<Measurement>(uniqueUrls.size)
    val jobs = uniqueUrls.map { url ->
      launch {
        val measurement = runCatching { probeUrl(probeClient, url) }.getOrNull()
        if (measurement != null && measurement.downloadedBytes > MinDownloadedBytes) {
          // trySend is non-throwing; after close() it silently drops, so a late
          // probe completing during teardown won't crash this coroutine.
          channel.trySend(measurement)
        }
      }
    }
    val collected = mutableListOf<Measurement>()
    try {
      withTimeoutOrNull(options.totalMs) {
        // Block until the first viable candidate arrives.
        collected.add(channel.receive())
        // Drain any others that already finished, without waiting.
        while (true) {
          val m = channel.tryReceive().getOrNull() ?: break
          collected.add(m)
        }
      }
    } finally {
      // Don't keep waiting for the remaining probes — cancel them so the
      // winner is returned promptly. probeUrl is cancellable (Call.cancel() on
      // cancellation), so stuck connect-phase probes abort immediately.
      coroutineContext[Job]?.cancelChildren()
      channel.close()
    }
    collected.sortedByDescending { it.score }
  }

  /**
   * Per-call timeout budget for [measure].
   *
   * - [Dialog] is used by the settings speed test: tolerant, since the user is
   *   explicitly waiting for a ranked result.
   * - [Open] is used on the live playback path: tight, so a dead CDN cannot
   *   block first-frame for seconds. A failed/empty measurement safely falls
   *   back to baseUrl + backupUrls in [CdnSelector].
   */
  data class MeasureOptions(
    val connectMs: Long,
    val readMs: Long,
    val totalMs: Long,
  ) {
    companion object {
      val Dialog = MeasureOptions(
        connectMs = ConnectTimeoutMs,
        readMs = ProbeReadTimeoutMs,
        totalMs = TotalTimeoutMs,
      )
      val Open = MeasureOptions(
        connectMs = OpenConnectTimeoutMs,
        readMs = OpenReadTimeoutMs,
        totalMs = OpenTotalTimeoutMs,
      )
    }
  }

  private suspend fun probeUrl(client: OkHttpClient, url: String): Measurement {
    val request = Request.Builder()
      .url(url)
      .header("Range", "bytes=0-${MeasureRangeBytes - 1}")
      .get()
      .build()

    val startNs = System.nanoTime()
    // Use OkHttp's async enqueue wrapped in a cancellable continuation so that
    // cancelling this coroutine (e.g. when an early-return winner is already
    // available) actually aborts in-flight probes via Call.cancel(). A blocking
    // execute() would ignore coroutine cancellation and keep occupying an IO
    // thread until its own timeout, defeating early return.
    val response = suspendCancellableCoroutine { cont ->
      val call = client.newCall(request)
      cont.invokeOnCancellation { runCatching { call.cancel() } }
      call.enqueue(object : Callback {
        override fun onResponse(call: Call, resp: Response) {
          if (!resp.isSuccessful && resp.code != 206) {
            resp.close()
            cont.resumeWithException(IllegalStateException("HTTP ${resp.code}"))
          } else {
            cont.resume(resp)
          }
        }

        override fun onFailure(call: Call, e: IOException) {
          cont.resumeWithException(e)
        }
      })
    }

    response.use {
      val firstByteMs = (it.receivedResponseAtMillis - it.sentRequestAtMillis).coerceAtLeast(0L)
      var downloadedBytes = 0L
      it.body?.source()?.use { source ->
        val buffer = okio.Buffer()
        while (downloadedBytes < MeasureRangeBytes) {
          val read = source.read(buffer, MeasureChunkBytes)
          if (read == -1L) break
          downloadedBytes += read
          buffer.clear()
        }
      }
      val totalMs = (System.nanoTime() - startNs) / 1_000_000L
      Measurement(
        url = url,
        firstByteMs = firstByteMs,
        totalMs = totalMs,
        downloadedBytes = downloadedBytes,
      )
    }
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
    const val OpenConnectTimeoutMs = 1000L
    const val OpenReadTimeoutMs = 1000L
    const val OpenTotalTimeoutMs = 1500L
    const val TtfbWeight = 2.0
    const val TotalWeight = 1.0
  }
}
