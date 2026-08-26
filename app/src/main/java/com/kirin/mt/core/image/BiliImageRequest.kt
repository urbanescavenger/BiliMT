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
  // YouTube 缩略图(i.ytimg.com)走裸请求:不拼 B 站 CDN 尺寸后缀、不加 B 站请求头,
  // 否则 `@480w_270h_1c.webp` 会破坏 i.ytimg URL、B 站 Referer 也会被 YouTube 拒绝。
  if (url.isYoutubeImageUrl()) {
    return buildYoutubeImageRequest(context, url, widthPx, heightPx, allowRgb565, memoryCacheEnabled)
  }
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
    return buildYoutubeImageRequest(context, url, sizePx, sizePx, allowRgb565, memoryCacheEnabled)
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

/** YouTube/Google 图片 URL(yt3.ggpht.com / yt3.googleusercontent.com / lh3.googleusercontent.com 头像、
 *  i.ytimg.com 缩略图 / 含 ggpht)。lh3.googleusercontent.com 是 Google 头像迁移后的常见宿主
 *  (搜索/播放器里部分频道头像用它,parseChannelInfo 仍给 yt3.ggpht.com),不识别会被当 B 站图拼
 *  `@Nw.webp` 后缀破坏 → 头像加载失败(频道主页能显、搜索/播放器空白),故一并走裸请求。 */
private fun String.isYoutubeImageUrl(): Boolean {
  val lower = lowercase()
  return lower.contains("googleusercontent.com") ||
    lower.contains("yt3.ggpht.com") ||
    lower.contains("ggpht") ||
    lower.contains("ytimg.com")
}

/**
 * YouTube 图片裸请求:不加 B 站 CDN 后缀与请求头,仅做协议归一化(`//` → `https:`),
 * 避免 i.ytimg/yt3 无协议头 URL 被 coil 拒绝。供缩略图与头像的 YouTube 分支共用。
 *
 * 不设 `.size()` / `Precision` / `allowRgb565`:对齐移动端裸 `AsyncImage(model=url)`(能正常显示),
 * 让 Coil 按布局尺寸解码。曾强加 `.size(480,270)` + `Precision.INEXACT` + `allowRgb565`,在部分
 * Amlogic 盒子上对 YouTube 原始尺寸/格式图解码失败 → 历史/动态卡片缩略图一片空白(B站走 CDN 强制
 * `@480w_270h_1c.webp` 不受影响),故回退为裸请求。
 */
private fun buildYoutubeImageRequest(
  context: Context,
  url: String,
  widthPx: Int,
  heightPx: Int,
  allowRgb565: Boolean,
  memoryCacheEnabled: Boolean,
): ImageRequest {
  return ImageRequest.Builder(context)
    .data(url.normalizedBiliImageUrl())
    .memoryCachePolicy(if (memoryCacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED)
    .crossfade(false)
    .build()
}

/**
 * 外部图源裸请求:不拼 B 站 CDN 尺寸后缀、不加 B 站请求头,仅做协议归一化(`//` → `https:`、
 * `http://` → `https://`)。供 YouTube 之外的第三方图源使用——如 IPTV 台标(tvg-logo),
 * 其域名任意、不认识 B 站 `@Nw_Nh_1c.webp` 后缀、也会拒 B 站 Referer;若走 [buildVideoThumbnailRequest]
 * 的 B 站分支会把台标 URL 拼坏并因防盗链加载失败(移动端裸 AsyncImage 无此问题)。
 */
fun buildExternalImageRequest(
  context: Context,
  url: String,
  widthPx: Int,
  heightPx: Int,
  allowRgb565: Boolean = false,
  memoryCacheEnabled: Boolean = true,
): ImageRequest {
  return ImageRequest.Builder(context)
    .data(url.normalizedBiliImageUrl())
    .size(widthPx, heightPx)
    .precision(Precision.INEXACT)
    .allowRgb565(allowRgb565)
    .memoryCachePolicy(if (memoryCacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED)
    .crossfade(false)
    .build()
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
