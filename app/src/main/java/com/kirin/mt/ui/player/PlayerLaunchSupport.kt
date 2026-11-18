package com.kirin.mt.ui.player

import com.kirin.mt.core.model.LiveRoom
import com.kirin.mt.core.model.SourceHongguo
import com.kirin.mt.core.model.SourceIptv
import com.kirin.mt.core.model.SourceTvbox
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.isWatchCompleted
import com.kirin.mt.core.model.shouldAdvanceToNextHistoryEpisode
import com.kirin.mt.core.network.HongguoAppRepository
import com.kirin.mt.core.network.IptvChannel
import com.kirin.mt.core.player.PlaybackRequest

/**
 * 由 VideoSummary(首页/搜索/动态等卡片)构造播放请求。TV 与移动端共用。
 * 镜像 AppShell.kt 内的同名逻辑(后者为 BiliTvApp 局部函数,这里提供模块级 internal 版本)。
 */
internal fun VideoSummary.toPlaybackRequest(forceStartPosition: Boolean = false): PlaybackRequest {
  // IPTV 卡片:直链 m3u8,走直播播放器(LivePlayerScreen)的 IPTV 分支,带镜像源列表。
  if (source == SourceIptv) {
    return PlaybackRequest(
      bvid = "",
      cid = 0L,
      title = title,
      ownerName = ownerName,
      coverUrl = pic,
      source = SourceIptv,
      iptvUrls = iptvUrls,
    )
  }
  // TVBox(影视库)卡片:点播,VOD 播放器(PlayerScreen),线路表带每站完整分集(选集在线路内)。
  if (source == SourceTvbox) {
    return PlaybackRequest(
      bvid = "",
      cid = 0L,
      title = title,
      ownerName = ownerName,
      coverUrl = pic,
      source = SourceTvbox,
      tvboxLines = tvboxLines,
    )
  }
  // 红果短剧卡片(P11-83):点播,VOD 播放器,单线路分集=播放页 URL(播放时懒解析 MP4 直链)。
  if (source == SourceHongguo) {
    return PlaybackRequest(
      bvid = "",
      cid = 0L,
      title = title,
      ownerName = ownerName,
      coverUrl = pic,
      source = SourceHongguo,
      tvboxLines = tvboxLines,
      hongguoSeriesId = this.bvid.removePrefix(HongguoAppRepository.SeriesPrefix),
    )
  }
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
  // 番剧历史卡(epId>0,fromHistory business=pgc):续播该集,走 PGC playurl(ep_id)而非 UGC bvid。
  // bvid 此时是网格 key "ep{epId}" 非真实稿件号,必须置空;playurl 用 ep_id+cid,heartbeat 用 aid+epid+sid
  // (BV sendHeartbeat avid/bvid 二选一)。subType 未知(=0),播放器从季 metadata 回填。
  if (epId > 0L) {
    val advanceToNextEpisode = shouldAdvanceToNextHistoryEpisode()
    return PlaybackRequest(
      bvid = "",
      cid = cid,
      title = title,
      startPositionMs = progress
        .takeIf { it > 0 && !isWatchCompleted() && !advanceToNextEpisode }
        ?.times(1000L) ?: 0L,
      aid = aid,
      coverUrl = pic,
      viewCount = view,
      danmakuCount = danmaku,
      epId = epId,
      seasonId = seasonId.toLong(),
      forceStartPosition = forceStartPosition,
      historyPage = historyPage,
      advanceToNextHistoryEpisode = advanceToNextEpisode,
      source = source,
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
    source = source,
    channelId = channelId,
  )
}

/**
 * 红果卡片(可能携带本地历史续播字段)+ 已补拉的详情 → 播放请求(P11-341)。
 *
 * 详情的 `tvboxLines` 生效;续播集按卡片 [VideoSummary.historyEpisodeVid] 在详情分集表里
 * 匹配 `hongguo-app:<vid>`(剧集增删导致 vid 失配时回退 [VideoSummary.historyEpisodeIndex]
 * 兜底序号,再失配落首集);起点用卡片 progress,接近播完不续播从头开始。普通红果卡
 * (推荐/搜索/相关,无历史字段)退化为 [toPlaybackRequest] 原行为。
 */
internal fun VideoSummary.toHongguoPlaybackRequest(resolved: VideoSummary): PlaybackRequest {
  val episodes = resolved.tvboxLines.firstOrNull()?.episodes.orEmpty()
  val episodeIndex = when {
    historyEpisodeVid.isNotBlank() -> episodes
      .indexOfFirst { it.url == HongguoAppRepository.EpisodePrefix + historyEpisodeVid }
      .takeIf { it >= 0 } ?: historyEpisodeIndex.coerceAtLeast(0)
    else -> historyEpisodeIndex.coerceAtLeast(0)
  }
  val startSeconds = progress.takeIf { it > 0 && !isWatchCompleted() }
  return resolved.toPlaybackRequest().copy(
    tvboxEpisodeIndex = episodeIndex.coerceIn(0, episodes.lastIndex.coerceAtLeast(0)),
    startPositionMs = startSeconds?.times(1000L) ?: 0L,
    forceStartPosition = startSeconds != null,
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
 * IPTV 频道 → 视频卡片模型,复用 VideoCard + TvVideoGrid 展示与焦点机制。
 * [VideoSummary.source] 置为 [SourceIptv] + [VideoSummary.iptvUrls] 填充镜像源列表,
 * [toPlaybackRequest] 据此走直播播放器的 IPTV 分支。
 */
internal fun IptvChannel.toVideoSummary(): VideoSummary {
  return VideoSummary(
    bvid = "",
    title = name,
    pic = logo,
    ownerName = group,
    ownerFace = "",
    ownerMid = 0L,
    view = 0,
    danmaku = 0,
    duration = 0,
    pubdate = 0L,
    badge = "IPTV",
    source = SourceIptv,
    iptvUrls = urls,
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