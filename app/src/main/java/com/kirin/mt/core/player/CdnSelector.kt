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
   * 因播放卡顿被临时拉黑的 CDN 主机（host → 解禁时间戳）。
   *
   * B站直连 DASH 没有 SABR 那套换节点能力：卡顿重载时若只重放同一个 playurl，
   * 缓存（[CacheTtlMs]）会把选择钉在同一个 host 上，整场都换不了节点。
   * 播放器在 stall 重载前调用 [avoidHosts] 把当前主用 host 拉黑一段时间，
   * 下一次 [select] 的缓存直接 miss、候选里也过滤掉它，从而真正换到备用 CDN。
   */
  private val avoidedHosts = HashMap<String, Long>()

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
    val allCandidates = candidatesFor(track, preference)
    // 被 [avoidHosts] 拉黑的主机优先剔除；全被拉黑时退回完整候选（宁可重试坏节点也不能无 URL 可播）。
    val candidates = allCandidates
      .filterNot { isAvoided(it) }
      .ifEmpty { allCandidates }

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
    val measurements = speedTester.measure(
      candidates,
      CdnSpeedTester.MeasureOptions.Open,
      earlyReturn = true,
    )
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
   * [measurements] is assumed best-first (as returned by [CdnSpeedTester.measure]).
   * Matching is by host (not full URL): the dialog measures one representative
   * URL per host, but each track has its own signed URLs on that host — so we
   * pick this track's own candidate URL on the best-measured host it shares,
   * never handing track B a URL signed for track A.
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
    // This track's own candidate URL per host (first wins).
    val hostToCandidate = LinkedHashMap<String, String>()
    for (c in candidates) {
      val host = c.toHttpUrlOrNull()?.host ?: continue
      hostToCandidate.putIfAbsent(host, c)
    }
    // Best-first measured hosts that this track can serve.
    val matchedHosts = measurements
      .mapNotNull { it.url.toHttpUrlOrNull()?.host }
      .filter { it in hostToCandidate }
      .distinct()
    val selection = if (matchedHosts.isEmpty()) {
      val fallbacks = track.backupUrls
        .filter { it != baseUrl && isEligibleCandidate(it) }
        .distinct()
      CdnSelection(baseUrl, fallbacks)
    } else {
      val primary = hostToCandidate.getValue(matchedHosts.first())
      val fallbacks = matchedHosts.drop(1).map { hostToCandidate.getValue(it) }
      CdnSelection(primary, fallbacks)
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
      val entry = cache[baseUrl] ?: return null
      // 缓存里的主用 host 正被拉黑（stall 换节点）⇒ 当 miss 处理，重新测量候选。
      if (isAvoided(entry.selection.primaryUrl)) {
        cache.remove(baseUrl)
        return null
      }
      return entry.selection
    }
  }

  /**
   * 把 [urls] 里出现的主机临时拉黑 [durationMs]（默认 [AvoidHostMs]），
   * 用于 stall 重载时强制换 CDN。只影响 [PlaybackCdnPreference.Auto] 的自动选择，
   * 手动指定 CDN 的设置优先，不会被覆盖。
   */
  fun avoidHosts(urls: Collection<String>, durationMs: Long = AvoidHostMs) {
    val now = System.currentTimeMillis()
    synchronized(cache) {
      avoidedHosts.entries.removeAll { it.value <= now }
      urls.forEach { url ->
        val host = url.toHttpUrlOrNull()?.host ?: return@forEach
        avoidedHosts[host] = now + durationMs
        Log.w(LogTag, "avoid CDN host $host for ${durationMs / 1000}s (stall)")
      }
    }
  }

  private fun isAvoided(url: String): Boolean {
    val host = url.toHttpUrlOrNull()?.host ?: return false
    synchronized(cache) {
      val until = avoidedHosts[host] ?: return false
      if (until <= System.currentTimeMillis()) {
        avoidedHosts.remove(host)
        return false
      }
      return true
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
    /** stall 换节点拉黑时长：短于 [CacheTtlMs]，避免一场播放把好节点也永久排掉。 */
    const val AvoidHostMs = 3 * 60 * 1000L
    const val LogTag = "BiliMT:CdnSelector"

    val BareIpPattern = Regex("""^(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?$""")
  }
}

data class CdnSelection(
  val primaryUrl: String,
  val fallbackUrls: List<String>,
)
