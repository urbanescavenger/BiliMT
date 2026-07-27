package com.kirin.mt.ui.player

import com.kirin.mt.core.model.LiveRoom
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.isWatchCompleted
import com.kirin.mt.core.model.shouldAdvanceToNextHistoryEpisode
import com.kirin.mt.core.player.PlaybackRequest

/**
 * 由 VideoSummary(首页/搜索/动态等卡片)构造播放请求。TV 与移动端共用。
 * 镜像 AppShell.kt 内的同名逻辑(后者为 BiliTvApp 局部函数,这里提供模块级 internal 版本)。
 */
internal fun VideoSummary.toPlaybackRequest(forceStartPosition: Boolean = false): PlaybackRequest {
  // 直播卡片:走直播播放(独立 LivePlayerScreen),不带点播字段。
  if (liveRoomId > 0L) {
    return PlaybackRequest(
      bvid = "",
      cid = 0L,
      title = title,
      ownerName = ownerName,
      ownerFace = ownerFace,
      ownerMid = ownerMid,
      coverUrl = pic,
      liveRoomId = liveRoomId,
    )
  }
  val advanceToNextEpisode = shouldAdvanceToNextHistoryEpisode()
  return PlaybackRequest(
    bvid = bvid,
    cid = cid,
    title = title,
    startPositionMs = progress
      .takeIf { it > 0 && !isWatchCompleted() && !advanceToNextEpisode }
      ?.times(1000L) ?: 0L,
    ownerName = ownerName,
    ownerFace = ownerFace,
    ownerMid = ownerMid,
    coverUrl = pic,
    viewCount = view,
    danmakuCount = danmaku,
    pubdate = pubdate,
    forceStartPosition = forceStartPosition,
    historyPage = historyPage,
    advanceToNextHistoryEpisode = advanceToNextEpisode,
  )
}

/**
 * 直播间摘要 → 视频卡片模型,复用 VideoCard + TvVideoGrid 展示与焦点/分页机制。
 * [VideoSummary.liveRoomId] 置位后,[toPlaybackRequest] 会自动走直播播放路径。
 */
internal fun LiveRoom.toVideoSummary(): VideoSummary {
  return VideoSummary(
    bvid = "",
    title = title,
    pic = cover,
    ownerName = uname,
    ownerFace = face,
    ownerMid = uid,
    view = online,
    danmaku = 0,
    duration = 0,
    pubdate = 0L,
    badge = "直播",
    isLive = true,
    liveRoomId = roomId,
    liveAreaName = areaName,
  )
}

/**
 * 由 [LiveRoom](直播间卡片)构造直播播放请求。TV 与移动端共用。
 * 仅填 [PlaybackRequest.liveRoomId] 及展示字段;[PlaybackRequest.isLive] 据此为 true,
 * 壳层据此挂载 [LivePlayerScreen] 而非点播 PlayerScreen。
 */
internal fun LiveRoom.toLivePlaybackRequest(): PlaybackRequest {
  return PlaybackRequest(
    bvid = "",
    cid = 0L,
    title = title,
    ownerName = uname,
    ownerFace = face,
    ownerMid = uid,
    coverUrl = cover,
    liveRoomId = roomId,
  )
}