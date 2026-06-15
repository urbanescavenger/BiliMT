package com.kirin.mt.ui.player

import android.util.Log
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.SpaceVideoRetryMode
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.PlaybackVideoMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun CoroutineScope.launchVideoListPanelLoad(
  panel: PlayerPanel,
  loadToken: Long,
  defaultFocusedIndex: Int,
  loader: suspend () -> List<VideoSummary>,
  isCurrentLoad: (Long, PlayerPanel) -> Boolean,
  applyResult: (videos: List<VideoSummary>, focusedIndex: Int) -> Unit,
  showControls: () -> Unit,
): Job = launch {
  val videos = runCatching { loader() }.getOrDefault(emptyList())
  if (!isCurrentLoad(loadToken, panel)) {
    return@launch
  }
  applyResult(videos, if (videos.isNotEmpty()) defaultFocusedIndex else 0)
  showControls()
}

internal fun CoroutineScope.launchUpVideosPanelLoad(
  loadToken: Long,
  order: String,
  initialRequest: PlaybackRequest,
  videoRepository: VideoRepository,
  resolveDisplayMetadata: suspend () -> PlaybackVideoMetadata?,
  currentRequest: () -> PlaybackRequest,
  isCurrentUpVideosLoad: (Long) -> Boolean,
  currentLoadDescription: () -> String,
  readCachedVideos: (String) -> List<VideoSummary>,
  cacheVideos: (String, List<VideoSummary>) -> Unit,
  applyResolvedCachedVideos: (List<VideoSummary>) -> Unit,
  applyLoadedVideos: (List<VideoSummary>) -> Unit,
  applyFollowed: (Boolean) -> Unit,
  showControls: () -> Unit,
): Job = launch {
  val resolvedMetadata = resolveDisplayMetadata()
  val resolvedRequest = currentRequest()
  val ownerMid = resolvedRequest.ownerMid.takeIf { it > 0L } ?: resolvedMetadata?.ownerMid ?: 0L
  val cacheKey = upVideoCacheKey(ownerMid, order)
  val resolvedCachedVideos = readCachedVideos(cacheKey)
    .withoutCurrentVideo(resolvedRequest)
  Log.i(
    PlayerUpVideosLogTag,
    "resolved token=$loadToken bvid=${initialRequest.bvid} order=$order ownerMid=$ownerMid " +
      "metadataMid=${resolvedMetadata?.ownerMid ?: 0L} cache=${resolvedCachedVideos.size}",
  )
  if (isCurrentUpVideosLoad(loadToken) && resolvedCachedVideos.isNotEmpty()) {
    applyResolvedCachedVideos(resolvedCachedVideos)
    showControls()
  }

  val networkResult = if (ownerMid <= 0L) {
    Log.w(PlayerUpVideosLogTag, "skip network token=$loadToken bvid=${initialRequest.bvid} order=$order: ownerMid=0")
    Result.success(emptyList())
  } else {
    runCatching {
      videoRepository.getSpaceVideos(
        mid = ownerMid,
        order = order,
        retryMode = SpaceVideoRetryMode.Interactive,
      )
    }
  }
  val videos = networkResult.onFailure { error ->
    Log.w(
      PlayerUpVideosLogTag,
      "network failed token=$loadToken mid=$ownerMid order=$order bvid=${initialRequest.bvid}: ${error.toLogBrief()}",
    )
  }.getOrDefault(emptyList())
  if (!isCurrentUpVideosLoad(loadToken)) {
    Log.i(
      PlayerUpVideosLogTag,
      "discard token=$loadToken ${currentLoadDescription()} network=${videos.size}",
    )
    return@launch
  }

  val nextVideos = videos.ifEmpty { readCachedVideos(cacheKey) }
    .withoutCurrentVideo(currentRequest())
  if (videos.isNotEmpty()) {
    cacheVideos(cacheKey, videos)
  }
  Log.i(
    PlayerUpVideosLogTag,
    "apply token=$loadToken mid=$ownerMid order=$order network=${videos.size} next=${nextVideos.size} " +
      "usedCacheFallback=${videos.isEmpty() && nextVideos.isNotEmpty()}",
  )
  applyLoadedVideos(nextVideos)
  showControls()

  if (ownerMid > 0L && videos.isEmpty() && networkResult.isFailure) {
    launch {
      delay(UpVideosRecoveryRetryDelayMs)
      if (!isCurrentUpVideosLoad(loadToken)) {
        return@launch
      }
      val recoveryVideos = runCatching {
        videoRepository.getSpaceVideos(
          mid = ownerMid,
          order = order,
          retryMode = SpaceVideoRetryMode.Recovery,
        )
      }.onFailure { error ->
        Log.w(
          PlayerUpVideosLogTag,
          "background retry failed token=$loadToken mid=$ownerMid order=$order bvid=${initialRequest.bvid}: ${error.toLogBrief()}",
        )
      }.getOrDefault(emptyList())
      if (recoveryVideos.isEmpty() || !isCurrentUpVideosLoad(loadToken)) {
        return@launch
      }
      val recoveredVideos = recoveryVideos.withoutCurrentVideo(currentRequest())
      if (recoveredVideos.isEmpty()) {
        return@launch
      }
      cacheVideos(cacheKey, recoveryVideos)
      Log.i(
        PlayerUpVideosLogTag,
        "background apply token=$loadToken mid=$ownerMid order=$order network=${recoveryVideos.size} " +
          "next=${recoveredVideos.size}",
      )
      applyLoadedVideos(recoveredVideos)
      showControls()
    }
  }

  val followed = runCatching {
    videoRepository.checkFollowStatus(ownerMid)
  }.onFailure { error ->
    Log.w(PlayerUpVideosLogTag, "follow check failed token=$loadToken mid=$ownerMid: ${error.toLogBrief()}")
  }.getOrDefault(false)
  if (!isCurrentUpVideosLoad(loadToken)) {
    return@launch
  }
  applyFollowed(followed)
  showControls()
}

internal fun List<VideoSummary>.withoutCurrentVideo(request: PlaybackRequest): List<VideoSummary> {
  if (request.bvid.isBlank()) return this
  return filterNot { video -> video.bvid.equals(request.bvid, ignoreCase = true) }
}

internal fun upVideoCacheKey(ownerMid: Long, order: String): String {
  return "$ownerMid:$order"
}

internal fun Map<String, List<VideoSummary>>.withBoundedUpVideoEntry(
  key: String,
  videos: List<VideoSummary>,
): Map<String, List<VideoSummary>> {
  val nextEntries = (this - key).toMutableMap()
  nextEntries[key] = videos.take(MaxUpVideoCacheVideosPerKey)
  return nextEntries.entries
    .toList()
    .takeLast(MaxUpVideoCacheKeys)
    .associate { entry -> entry.key to entry.value }
}

internal fun Throwable.toLogBrief(): String {
  return "${javaClass.simpleName}: ${message.orEmpty()}"
}

private const val MaxUpVideoCacheKeys = 4
private const val MaxUpVideoCacheVideosPerKey = 50
private const val UpVideosRecoveryRetryDelayMs = 1_200L
internal const val PlayerUpVideosLogTag = "BiliMT:UpVideos"
