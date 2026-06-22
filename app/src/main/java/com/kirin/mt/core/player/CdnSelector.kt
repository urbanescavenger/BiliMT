package com.kirin.mt.core.player

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Selects the best CDN URL for a playback track.
 *
 * - For non-Auto preferences the existing [CdnRewriter] behavior is used.
 * - For [PlaybackCdnPreference.Auto] we measure the candidates (baseUrl +
 *   backupUrls) and return the fastest successful one as the primary URL, with
 *   the remaining successful candidates as fallbacks.
 *
 * Results are cached in memory for [CacheTtlMs] keyed by the full original
 * base URL. Caching the full URL (instead of just the host) avoids applying a
 * signed URL from one video onto another video's base URL, which was causing
 * playback to freeze on the first frame.
 */
class CdnSelector(
  private val speedTester: CdnSpeedTester,
) {

  private val cache = LinkedHashMap<String, CacheEntry>()

  /**
   * Returns a primary URL and an ordered list of fallback URLs for [track].
   */
  suspend fun select(track: PlaybackTrack, preference: PlaybackCdnPreference): CdnSelection {
    if (preference != PlaybackCdnPreference.Auto) {
      val primary = CdnRewriter.rewrite(track.baseUrl, preference)
      val fallbacks = track.backupUrls
        .asSequence()
        .map { CdnRewriter.rewrite(it, preference) }
        .filter { it != primary }
        .filter { isEligibleCandidate(it) }
        .distinct()
        .toList()
      return CdnSelection(primary, fallbacks)
    }

    val baseUrl = track.baseUrl
    val candidates = candidatesFor(track, preference)

    if (candidates.isEmpty()) {
      return CdnSelection(baseUrl, emptyList())
    }

    val cached = cachedSelection(baseUrl)
    if (cached != null) {
      Log.i(LogTag, "Using cached CDN selection for $baseUrl")
      return cached
    }

    if (candidates.size == 1) {
      val selection = CdnSelection(candidates.first(), emptyList())
      cacheSelection(baseUrl, selection)
      return selection
    }

    Log.i(LogTag, "Measuring ${candidates.size} CDN candidates for $baseUrl")
    val measurements = speedTester.measure(candidates, CdnSpeedTester.MeasureOptions.Open)
    val successfulUrls = measurements.map { it.url }

    if (successfulUrls.isEmpty()) {
      Log.w(LogTag, "All CDN measurements failed for $baseUrl, falling back to baseUrl + backups")
      val fallbacks = track.backupUrls
        .filter { it != baseUrl && isEligibleCandidate(it) }
        .distinct()
      val selection = CdnSelection(baseUrl, fallbacks)
      cacheSelection(baseUrl, selection)
      return selection
    }

    val primary = successfulUrls.first()
    val fallbacks = successfulUrls.drop(1)
    val selection = CdnSelection(primary, fallbacks)
    cacheSelection(baseUrl, selection)

    val best = measurements.firstOrNull()
    if (best != null) {
      Log.i(
        LogTag,
        "Selected CDN for $baseUrl: ${best.url} (ttfb=${best.firstByteMs}ms, total=${best.totalMs}ms, ${best.downloadedBytes} bytes, score=${"%.2f".format(best.score)})",
      )
    }
    return selection
  }

  /**
   * The candidate URLs the player would actually consider for [track] under
   * [preference] — i.e. the same set [select] measures for Auto, and the
   * rewritten hosts it would force for a non-Auto preference. Exposed so the
   * settings speed test measures exactly what the player uses, instead of the
   * raw baseUrl + backupUrls (which for non-Auto include hosts the player
   * rewrites away and never contacts).
   */
  fun candidatesFor(track: PlaybackTrack, preference: PlaybackCdnPreference): List<String> {
    val raw = listOf(track.baseUrl) + track.backupUrls
    return if (preference != PlaybackCdnPreference.Auto) {
      raw
        .map { CdnRewriter.rewrite(it, preference) }
        .filter { isEligibleCandidate(it) }
        .distinct()
    } else {
      raw
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .distinct()
        .filter { isEligibleCandidate(it) }
    }
  }

  /**
   * Builds and caches a [CdnSelection] for [track] from already-measured
   * [measurements] (no network). Lets the settings speed test pre-warm the
   * cache so the next open of the same video hits `Using cached CDN selection`
   * instead of re-measuring on the live path.
   *
   * [measurements] is assumed best-first (as returned by [CdnSpeedTester.measure]);
   * only entries whose URL is among [candidatesFor] are considered.
   */
  fun applyMeasurements(
    track: PlaybackTrack,
    preference: PlaybackCdnPreference,
    measurements: List<CdnSpeedTester.Measurement>,
  ): CdnSelection {
    val baseUrl = track.baseUrl
    val candidates = candidatesFor(track, preference)
    if (candidates.isEmpty()) {
      return CdnSelection(baseUrl, emptyList())
    }
    val candidateSet = candidates.toHashSet()
    val successful = measurements.filter { it.url in candidateSet }
    val selection = if (successful.isEmpty()) {
      val fallbacks = track.backupUrls
        .filter { it != baseUrl && isEligibleCandidate(it) }
        .distinct()
      CdnSelection(baseUrl, fallbacks)
    } else {
      CdnSelection(successful.first().url, successful.drop(1).map { it.url })
    }
    cacheSelection(baseUrl, selection)
    return selection
  }

  private fun isEligibleCandidate(url: String): Boolean {
    if (url.contains(".mcdn.bilivideo.")) return false
    if (url.contains(".szbdyd.com")) return false
    val host = url.toHttpUrlOrNull()?.host ?: return false
    if (host.matches(BareIpPattern)) return false
    return true
  }

  private fun cachedSelection(baseUrl: String): CdnSelection? {
    synchronized(cache) {
      val now = System.currentTimeMillis()
      cache.entries.removeAll { it.value.expiresAt <= now }
      return cache[baseUrl]?.selection
    }
  }

  private fun cacheSelection(baseUrl: String, selection: CdnSelection) {
    synchronized(cache) {
      cache[baseUrl] = CacheEntry(
        selection = selection,
        expiresAt = System.currentTimeMillis() + CacheTtlMs,
      )
    }
  }

  private data class CacheEntry(
    val selection: CdnSelection,
    val expiresAt: Long,
  )

  private companion object {
    const val CacheTtlMs = 5 * 60 * 1000L
    const val LogTag = "BiliMT:CdnSelector"

    val BareIpPattern = Regex("""^(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?$""")
  }
}

data class CdnSelection(
  val primaryUrl: String,
  val fallbackUrls: List<String>,
)
