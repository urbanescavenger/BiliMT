package com.kirin.mt.core.player

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Rewrites the host portion of a Bilibili media URL according to a user-chosen
 * CDN preference. The path, query, and fragment are left untouched so signed
 * tokens (e.g. `?xyz=` m4s query params) remain valid.
 *
 * `Auto` is a no-op — the original URL is returned unchanged so B 站's
 * default CDN choice is preserved.
 *
 * Unknown / non-rewritable hosts (mcdn, p2p, bare IPs, szbdyd, etc.) are
 * returned as-is for every preference to avoid breaking playback.
 */
internal object CdnRewriter {

  /**
   * Matches known Bilibili media CDN hosts.
   * Captures the region (e.g. `hz`, `sz`) and the full CDN identifier
   * (e.g. `mirrorcos.bilivideo.com`, `mirrorakam.akamaized.net`).
   */
  private val UposHostPattern = Regex(
    """^upos-(?<region>[a-z0-9]+)-(?<cdn>mirror[a-z0-9]+\.(?:bilivideo\.com|akamaized\.net))$"""
  )

  /**
   * Raw Aliyun CDN host sometimes returned by B 站 (e.g. `xy123x1.aliyuncs.com`).
   * These have no region, so we conservatively route them to the Shenzhen Aliyun
   * mirror (`sz`) which is what the upstream BV reference implementation uses.
   */
  private val RawAliyunPattern = Regex("""^xy[0-9]+x?[0-9]*\.aliyuncs\.com$""")

  fun rewrite(url: String, preference: PlaybackCdnPreference): String {
    if (preference == PlaybackCdnPreference.Auto) return url

    val original = url.toHttpUrlOrNull() ?: return url
    val host = original.host

    val rewrittenHost = when {
      host.endsWith(".mcdn.bilivideo.com") -> host
      host.endsWith(".szbdyd.com") -> host
      host == "upos-sz-mirrorali.bilivideo.com" && preference == PlaybackCdnPreference.Aliyun -> host
      UposHostPattern.matches(host) -> rewriteUposHost(host, preference)
      RawAliyunPattern.matches(host) -> "upos-sz-mirrorali.bilivideo.com"
      else -> host
    }

    return if (rewrittenHost == host) {
      url
    } else {
      original.newBuilder()
        .host(rewrittenHost)
        .build()
        .toString()
    }
  }

  private fun rewriteUposHost(host: String, preference: PlaybackCdnPreference): String {
    val match = UposHostPattern.matchEntire(host) ?: return host
    val region = match.groups["region"]?.value ?: return host

    val targetCdn = when (preference) {
      PlaybackCdnPreference.Official -> "mirrorcos.bilivideo.com"
      PlaybackCdnPreference.Aliyun -> "mirrorali.bilivideo.com"
      PlaybackCdnPreference.Akamai -> "mirrorakam.akamaized.net"
      PlaybackCdnPreference.Hw -> "mirrorhw.bilivideo.com"
      PlaybackCdnPreference.Auto -> match.groups["cdn"]?.value ?: return host
    }

    return "upos-$region-$targetCdn"
  }
}
