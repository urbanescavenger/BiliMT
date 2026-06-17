package com.kirin.mt.ui.common

import com.kirin.mt.core.model.VideoSummary

/**
 * Shared helpers for paginated video grids keyed by bvid (search + UP 主主页).
 * UserFeed (dynamic/history) keeps its own richer helpers (index/viewAt-aware dedup) and is
 * intentionally not migrated here.
 */

/** Appends [nextVideos], dropping any whose bvid already appears in this list. */
internal fun List<VideoSummary>.appendUniqueByBvid(nextVideos: List<VideoSummary>): List<VideoSummary> {
  if (nextVideos.isEmpty()) {
    return this
  }
  val knownBvids = mapTo(mutableSetOf()) { video -> video.bvid }
  return this + nextVideos.filter { video -> knownBvids.add(video.bvid) }
}

/** Resolves the focus-restore index from a [focusKey] (or falls back to [fallbackIndex]). */
internal fun List<VideoSummary>.resolveFocusIndex(focusKey: String, fallbackIndex: Int): Int {
  val keyIndex = focusKey
    .takeIf { key -> key.isNotBlank() }
    ?.let { key -> indexOfFirst { video -> video.focusRestoreKey() == key } }
    ?.takeIf { index -> index >= 0 }
  return keyIndex ?: fallbackIndex.coerceIn(0, lastIndex)
}

/** Stable key for a video used to restore focus after paging/back. */
internal fun VideoSummary.focusRestoreKey(): String {
  return bvid.ifBlank {
    when {
      cid > 0L -> "cid-$cid"
      historyPage > 0 -> "p-$historyPage"
      else -> ""
    }
  }
}