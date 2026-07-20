package com.kirin.mt.ui.player

import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.isWatchCompleted
import com.kirin.mt.core.model.shouldAdvanceToNextHistoryEpisode
import com.kirin.mt.core.player.PlaybackRequest

/**
 * 由 VideoSummary(首页/搜索/动态等卡片)构造播放请求。TV 与移动端共用。
 * 镜像 AppShell.kt 内的同名逻辑(后者为 BiliTvApp 局部函数,这里提供模块级 internal 版本)。
 */
internal fun VideoSummary.toPlaybackRequest(forceStartPosition: Boolean = false): PlaybackRequest {
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