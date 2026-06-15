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
 * Hosts that don't match any known B 站 CDN pattern are returned as-is when
 * the preference is `Auto`, or fall back to a default template otherwise.
 */
internal object CdnRewriter {
  private const val DefaultRegion = "default"

  private val BilibiliHostPatterns = listOf(
    // upos-hz-mirrorcos.bilivideo.com / upos-sz-mirrorali.alicloudccs.com
    Regex("""^upos-(?<region>[a-z]+)-(?:mirrorcos\.bilivideo\.com|mirrorali\.alicloudccs\.com|mirrorhw\.hwcloudbili\.com)$"""),
    // upos-hz-mirrorakam.akamaized.net
    Regex("""^upos-(?<region>[a-z]+)-mirrorakam\.akamaized\.net$"""),
    // xy[123]x*.aliyuncs.com — no extractable region
    Regex("""^xy[0-9]*x?\.aliyuncs\.com$"""),
  )

  fun rewrite(url: String, preference: PlaybackCdnPreference): String {
    if (preference == PlaybackCdnPreference.Auto) return url
    val original = url.toHttpUrlOrNull() ?: return url
    val rewritten = original.newBuilder()
      .host(mapHost(original.host, preference))
      .build()
    return rewritten.toString()
  }

  private fun mapHost(host: String, preference: PlaybackCdnPreference): String {
    val region = extractRegion(host) ?: return defaultHostFor(preference)
    return when (preference) {
      PlaybackCdnPreference.Official -> "$region.mirrorcos.bilivideo.com"
      PlaybackCdnPreference.Aliyun -> "$region.mirrorali.alicloudccs.com"
      PlaybackCdnPreference.Akamai -> "$region.mirrorakam.akamaized.net"
      PlaybackCdnPreference.Hw -> "$region.mirrorhw.hwcloudbili.com"
      PlaybackCdnPreference.Auto -> host
    }
  }

  private fun extractRegion(host: String): String? {
    val match = BilibiliHostPatterns.firstNotNullOfOrNull { regex -> regex.matchEntire(host) }
      ?: return null
    return match.groups["region"]?.value
  }

  private fun defaultHostFor(preference: PlaybackCdnPreference): String = when (preference) {
    PlaybackCdnPreference.Official -> "$DefaultRegion.mirrorcos.bilivideo.com"
    PlaybackCdnPreference.Aliyun -> "$DefaultRegion.mirrorali.alicloudccs.com"
    PlaybackCdnPreference.Akamai -> "$DefaultRegion.mirrorakam.akamaized.net"
    PlaybackCdnPreference.Hw -> "$DefaultRegion.mirrorhw.hwcloudbili.com"
    PlaybackCdnPreference.Auto -> ""
  }
}
