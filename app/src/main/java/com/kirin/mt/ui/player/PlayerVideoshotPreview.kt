package com.kirin.mt.ui.player

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.kirin.mt.core.player.VideoshotData

internal fun VideoshotData?.alignPreviewTarget(
  targetMs: Long,
  currentPreviewMs: Long?,
  deltaMs: Long,
  maxDurationMs: Long,
  enabled: Boolean,
): Long {
  val videoshot = this ?: return targetMs
  if (!enabled) return targetMs
  var aligned = videoshot.closestTimestampMs(targetMs).coerceIn(0L, maxDurationMs)
  if (currentPreviewMs != null && deltaMs > 0 && aligned <= currentPreviewMs && currentPreviewMs < maxDurationMs) {
    aligned = videoshot.closestTimestampMs((currentPreviewMs + 1_000L).coerceIn(0L, maxDurationMs))
      .coerceIn(0L, maxDurationMs)
  }
  if (currentPreviewMs != null && deltaMs < 0 && aligned >= currentPreviewMs && currentPreviewMs > 0L) {
    aligned = videoshot.closestTimestampMs((currentPreviewMs - 1_000L).coerceIn(0L, maxDurationMs))
      .coerceIn(0L, maxDurationMs)
  }
  return aligned
}

internal fun VideoshotData.previewSpriteUrls(
  previewPositionMs: Long?,
  playbackPositionMs: Long,
  durationMs: Long,
  cachedUrls: Set<String>,
): List<String> {
  return buildList {
    frameAt(previewPositionMs ?: playbackPositionMs, durationMs)?.imageUrl?.let(::add)
    images.take(VideoshotPreloadImageCount).forEach(::add)
  }
    .filter { url -> url.isNotBlank() && url !in cachedUrls }
    .distinct()
}

internal fun ByteArray.decodeImageBitmapOrNull(): ImageBitmap? {
  return BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()
}

internal fun Map<String, ImageBitmap>.withBoundedSprite(
  key: String,
  image: ImageBitmap,
): Map<String, ImageBitmap> {
  val nextEntries = (this - key).toMutableMap()
  nextEntries[key] = image
  return nextEntries.entries
    .toList()
    .takeLast(MaxVideoshotSpriteCacheEntries)
    .associate { entry -> entry.key to entry.value }
}

private const val VideoshotPreloadImageCount = 2
private const val MaxVideoshotSpriteCacheEntries = 6
