package com.kirin.mt.core.image

import android.content.Context
import com.kirin.mt.core.network.BiliHeaders
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision

object BiliImageSizing {
  const val StandardVideoThumbnailWidthPx = 480
  const val StandardVideoThumbnailHeightPx = 270
  const val StandardOwnerAvatarSizePx = 72
  const val AccountAvatarSizePx = 96
  const val AccountProfileAvatarSizePx = 192
}

fun buildVideoThumbnailRequest(
  context: Context,
  url: String,
  widthPx: Int = BiliImageSizing.StandardVideoThumbnailWidthPx,
  heightPx: Int = BiliImageSizing.StandardVideoThumbnailHeightPx,
  allowRgb565: Boolean = false,
  memoryCacheEnabled: Boolean = true,
): ImageRequest {
  return ImageRequest.Builder(context)
    .data(url.biliCdnResizedImageUrl(widthPx, heightPx))
    .addBiliImageHeaders()
    .size(widthPx, heightPx)
    .precision(Precision.INEXACT)
    .allowRgb565(allowRgb565)
    .memoryCachePolicy(if (memoryCacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED)
    .crossfade(false)
    .build()
}

fun buildOwnerAvatarRequest(
  context: Context,
  url: String,
  sizePx: Int = BiliImageSizing.StandardOwnerAvatarSizePx,
  allowRgb565: Boolean = false,
  memoryCacheEnabled: Boolean = true,
): ImageRequest {
  // YouTube 头像(yt3.ggpht.com 等)走裸请求:不拼 B 站 CDN 尺寸后缀、不加 B 站请求头,
  // 否则 `@Nw.webp` 会破坏 yt3 URL、B 站 Referer 也会被 yt3 拒绝。
  if (url.isYoutubeImageUrl()) {
    return ImageRequest.Builder(context)
      .data(url)
      .size(sizePx, sizePx)
      .precision(Precision.INEXACT)
      .allowRgb565(allowRgb565)
      .memoryCachePolicy(if (memoryCacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED)
      .crossfade(false)
      .build()
  }
  return ImageRequest.Builder(context)
    .data(url.biliCdnResizedImageUrl(sizePx, sizePx))
    .addBiliImageHeaders()
    .size(sizePx, sizePx)
    .precision(Precision.INEXACT)
    .allowRgb565(allowRgb565)
    .memoryCachePolicy(if (memoryCacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED)
    .crossfade(false)
    .build()
}

/** YouTube 图片 URL(yt3.ggpht.com / yt3.googleusercontent.com / 含 ggpht)。 */
private fun String.isYoutubeImageUrl(): Boolean {
  val lower = lowercase()
  return lower.contains("yt3.ggpht.com") ||
    lower.contains("yt3.googleusercontent.com") ||
    lower.contains("ggpht")
}

fun String.biliCdnResizedImageUrl(
  widthPx: Int,
  heightPx: Int? = null,
): String {
  val normalized = normalizedBiliImageUrl()
  if (normalized.isBlank() || normalized.contains("@")) return normalized

  val suffix = if (heightPx != null) {
    "@${widthPx}w_${heightPx}h_1c.webp"
  } else {
    "@${widthPx}w.webp"
  }
  val queryStart = normalized.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: normalized.length
  return normalized.substring(0, queryStart) + suffix + normalized.substring(queryStart)
}

private fun String.normalizedBiliImageUrl(): String {
  val trimmed = trim()
  return when {
    trimmed.startsWith("//") -> "https:$trimmed"
    trimmed.startsWith("http://") -> "https://${trimmed.removePrefix("http://")}"
    else -> trimmed
  }
}

private fun ImageRequest.Builder.addBiliImageHeaders(): ImageRequest.Builder {
  return addHeader("User-Agent", BiliHeaders.UserAgent)
    .addHeader("Referer", BiliHeaders.Referer)
}
