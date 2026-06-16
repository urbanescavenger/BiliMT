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
 *
 * The cache key is the region prefix of the base URL (e.g. `upos-sz`) so that
 * measurements made for one video can be reused for other videos served from the
 * same region.
 */
class CdnSelector(
  private val speedTester: CdnSpeedTester,
) {

  private val cache = LinkedHashMap<String, CacheEntry>()

  suspend fun select(track: PlaybackTrack, preference: PlaybackCdnPreference): String {
    if (preference != PlaybackCdnPreference.Auto) {
      return CdnRewriter.rewrite(track.baseUrl, preference)
    }

    val baseUrl = track.baseUrl
    val candidates = (listOf(baseUrl) + track.backupUrls)
      .filter { it.startsWith("http://") || it.startsWith("https://") }
      .distinct()
      .filter { isEligibleCandidate(it) }

    if (candidates.isEmpty()) {
      return baseUrl
    }
    if (candidates.size == 1) {
      return candidates.first()
    }

    val regionKey = regionKey(baseUrl) ?: return baseUrl
    val cachedBest = bestHostForRegion(regionKey)
    if (cachedBest != null) {
      val rewritten = replaceHost(baseUrl, cachedBest)
      if (rewritten != null) {
        Log.i(LogTag, "Using cached CDN host for $regionKey: $cachedBest")
        return rewritten
      }
    }

    Log.i(LogTag, "Measuring ${candidates.size} CDN candidates for $regionKey")
    val measurements = speedTester.measure(candidates)
    val best = measurements.firstOrNull()
    if (best == null) {
      Log.w(LogTag, "All CDN measurements failed for $regionKey, falling back to baseUrl")
      return baseUrl
    }

    val bestHost = best.url.toHttpUrlOrNull()?.host ?: return baseUrl
    synchronized(cache) {
      cache[regionKey] = CacheEntry(
        bestHost = bestHost,
        expiresAt = System.currentTimeMillis() + CacheTtlMs,
      )
    }
    Log.i(
      LogTag,
      "Selected CDN host for $regionKey: $bestHost (ttfb=${best.firstByteMs}ms, total=${best.totalMs}ms, ${best.downloadedBytes} bytes, score=${"%.2f".format(best.score)})",
    )
    return best.url
  }

  private fun isEligibleCandidate(url: String): Boolean {
    if (url.contains(".mcdn.bilivideo.")) return false
    if (url.contains(".szbdyd.com")) return false
    val host = url.toHttpUrlOrNull()?.host ?: return false
    if (host.matches(BareIpPattern)) return false
    return true
  }

  private fun regionKey(url: String): String? {
    val host = url.toHttpUrlOrNull()?.host ?: return null
    return UposRegionPattern.matchEntire(host)?.groupValues?.getOrNull(0) ?: host
  }

  private fun bestHostForRegion(regionKey: String): String? {
    synchronized(cache) {
      val now = System.currentTimeMillis()
      cache.entries.removeAll { it.value.expiresAt <= now }
      return cache[regionKey]?.bestHost
    }
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

    val UposRegionPattern = Regex("""^upos-[a-z0-9]+""")
    val BareIpPattern = Regex("""^(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?$""")
  }
}
