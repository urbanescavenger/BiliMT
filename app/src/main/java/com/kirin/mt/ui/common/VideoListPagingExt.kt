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

/** Resolves the focus-restore index from a [focusKey] (or falls back to [fallbackIndex]). */
internal fun List<VideoSummary>.resolveFocusIndex(focusKey: String, fallbackIndex: Int): Int {
  // 空表守卫:下面 coerceIn(0, lastIndex) 在 lastIndex = -1 时会抛 IllegalArgumentException。
  if (isEmpty()) {
    return 0
  }
  val keyIndex = focusKey
    .takeIf { key -> key.isNotBlank() }
    ?.let { key -> indexOfFirst { video -> video.focusRestoreKey() == key } }
    ?.takeIf { index -> index >= 0 }
  return keyIndex ?: fallbackIndex.coerceIn(0, lastIndex)
}

/**
 * Stable key for a video used to restore focus after paging/back.
 *
 * P11-202 起这是**全工程唯一**的身份实现(推荐页 / 动态页各自的私有副本已删除)。分支顺序即身份强度,
 * 缺任何一个分支都会让某类卡片退化成空串 = 永远无法被锚定:
 *  - `bvid`:YouTube 卡片的 `videoId` 也存在这里(`VideoSummary.source` 注释),不是 B 站专属;
 *  - `live-`:直播卡(在 `cid-` 之前,直播间 cid 可能非 0);
 *  - `dyn-`:图文/纯文字动态,`bvid`/`cid`/`viewAt` 全空,不补这个分支就永远锚定不了;
 *  - `view-`:YouTube 本地历史里 `videoId` 可能为空串,此时 `viewAt`(播放时刻)是唯一身份。
 *
 * 注意与 `VideoSummary.feedKey`(属性,`dynId.ifBlank { bvid }`)区分:那个是去重键,不参与焦点恢复。
 */
internal fun VideoSummary.focusRestoreKey(): String {
  return bvid.ifBlank {
    when {
      liveRoomId > 0L -> "live-$liveRoomId"
      dynId.isNotBlank() -> "dyn-$dynId"
      cid > 0L -> "cid-$cid"
      historyPage > 0 -> "p-$historyPage"
      viewAt > 0L -> "view-$viewAt"
      else -> ""
    }
  }
}