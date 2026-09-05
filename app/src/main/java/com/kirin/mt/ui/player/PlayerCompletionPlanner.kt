package com.kirin.mt.ui.player

import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.PlaybackVideoMetadata

internal data class PlayerNextEpisodeCompletion(
  val request: PlaybackRequest,
  val title: String,
)

internal fun PlaybackRequest.nextEpisodeCompletion(
  metadata: PlaybackVideoMetadata?,
  selectedQualityId: Int?,
): PlayerNextEpisodeCompletion? {
  // 影视库:按集索引推进(分P 页号=选集索引,cid 恒 0 无从比对);selectedQualityId=线路档,连播不换线。
  if (isTvbox) {
    val episodes = tvboxCurrentLine?.episodes.orEmpty()
    val nextEpisode = episodes.getOrNull(tvboxEpisodeIndex + 1) ?: return null
    val nextRequest = copy(
      tvboxEpisodeIndex = tvboxEpisodeIndex + 1,
      startPositionMs = 0L,
      preferredQualityId = selectedQualityId,
      forceStartPosition = true,
    )
    return PlayerNextEpisodeCompletion(
      request = nextRequest,
      title = nextEpisode.title.ifBlank { nextRequest.title },
    )
  }
  val pages = metadata?.pages.orEmpty()
  val currentIndex = pages.indexOfFirst { episode ->
    episode.cid == cid || (historyPage > 0 && episode.page == historyPage)
  }
  val nextEpisode = pages.getOrNull(currentIndex + 1) ?: return null
  val nextRequest = copy(
    cid = nextEpisode.cid,
    startPositionMs = 0L,
    preferredQualityId = selectedQualityId,
    forceStartPosition = true,
    historyPage = nextEpisode.page,
    advanceToNextHistoryEpisode = false,
    epId = nextEpisode.epId,
  )
  return PlayerNextEpisodeCompletion(
    request = nextRequest,
    title = nextEpisode.title.ifBlank { nextRequest.title },
  )
}

internal fun List<VideoSummary>.firstCompletionRelatedVideo(currentBvid: String): VideoSummary? {
  return firstOrNull { video -> !video.bvid.equals(currentBvid, ignoreCase = true) }
}
