package com.kirin.mt.ui.common

import com.kirin.mt.core.model.UserSummary
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

/** Appends [nextUsers], dropping any whose mid/channelId already appears in this list. */
internal fun List<UserSummary>.appendUniqueByMid(nextUsers: List<UserSummary>): List<UserSummary> {
  if (nextUsers.isEmpty()) {
    return this
  }
  val knownKeys = mapTo(mutableSetOf()) { user -> user.dedupKey() }
  return this + nextUsers.filter { user -> knownKeys.add(user.dedupKey()) }
}

/** 用户去重键：B站用 mid，YouTube 用 channelId。 */
internal fun UserSummary.dedupKey(): String {
  return if (channelId.isNotBlank()) "yt-$channelId" else "bili-$mid"
}

/** 用户列表的焦点恢复键（与去重键一致）。 */
internal fun UserSummary.focusRestoreKey(): String = dedupKey()

/** 从 [focusKey] 解析用户列表的焦点恢复索引（或回退 [fallbackIndex]）。 */
internal fun List<UserSummary>.resolveFocusIndex(focusKey: String, fallbackIndex: Int): Int {
  val keyIndex = focusKey
    .takeIf { key -> key.isNotBlank() }
    ?.let { key -> indexOfFirst { user -> user.dedupKey() == key } }
    ?.takeIf { index -> index >= 0 }
  return keyIndex ?: fallbackIndex.coerceIn(0, lastIndex)
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
      liveRoomId > 0L -> "live-$liveRoomId"
      cid > 0L -> "cid-$cid"
      historyPage > 0 -> "p-$historyPage"
      else -> ""
    }
  }
}