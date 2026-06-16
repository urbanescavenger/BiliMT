package com.kirin.mt.core.player

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Selects the best CDN URL for a playback track.
 *
 * - For non-Auto preferences the existing [CdnRewriter] behavior is used.
 * - For [PlaybackCdnPreference.Auto] we measure the candidates (baseUrl +
 *   backupUrls) and return the fastest successful one. Results are cached in
 *   memory for [CacheTtlMs] to avoid repeated probes.
 */
class CdnSelector(
  private val speedTester: CdnSpeedTester,
) {

  private val cache = LinkedHashMap<String, CacheEntry>()

  suspend fun select(track: PlaybackTrack, preference: PlaybackCdnPreference): String {
    if (preference != PlaybackCdnPreference.Auto) {
      return CdnRewriter.rewrite(track.baseUrl, preference)
    }

    val candidates = (listOf(track.baseUrl) + track.backupUrls)
      .filter { it.startsWith("http://") || it.startsWith("https://") }
      .distinct()

    if (candidates.size <= 1) {
      return candidates.firstOrNull() ?: track.baseUrl
    }

    val baseHost = track.baseUrl.toHttpUrlOrNull()?.host ?: return track.baseUrl

    synchronized(cache) {
      val now = System.currentTimeMillis()
      cache.entries.removeAll { it.value.expiresAt <= now }
      val cachedHost = cache[baseHost]?.bestHost
      if (cachedHost != null) {
        val rewritten = replaceHost(track.baseUrl, cachedHost)
        if (rewritten != null) {
          Log.i(LogTag, "Using cached CDN host for $baseHost: $cachedHost")
          return rewritten
        }
      }
    }

    Log.i(LogTag, "Measuring ${candidates.size} CDN candidates for $baseHost")
    val measurements = speedTester.measure(candidates)
    val best = measurements.firstOrNull()
    if (best == null) {
      Log.w(LogTag, "All CDN measurements failed for $baseHost, falling back to baseUrl")
      return track.baseUrl
    }

    val bestHost = best.url.toHttpUrlOrNull()?.host ?: return track.baseUrl
    synchronized(cache) {
      cache[baseHost] = CacheEntry(
        bestHost = bestHost,
        expiresAt = System.currentTimeMillis() + CacheTtlMs,
      )
    }
    Log.i(LogTag, "Selected CDN host for $baseHost: $bestHost (${best.elapsedMs}ms, ${best.downloadedBytes} bytes)")
    return best.url
  }

  private fun replaceHost(url: String, newHost: String): String? {
    val httpUrl = url.toHttpUrlOrNull() ?: return null
    if (httpUrl.host == newHost) return url
    return httpUrl.newBuilder()
      .host(newHost)
      .build()
      .toString()
  }

  private data class CacheEntry(
    val bestHost: String,
    val expiresAt: Long,
  )

  private companion object {
    const val CacheTtlMs = 5 * 60 * 1000L
    const val LogTag = "BiliMT:CdnSelector"
  }
}
