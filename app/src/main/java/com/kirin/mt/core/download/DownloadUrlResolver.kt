package com.kirin.mt.core.download

import android.util.Log
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.PlaybackTrack
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.youtube.YoutubePlaybackResolver
import kotlinx.coroutines.flow.first

/**
 * 下载 URL 解析:统一 B站(DASH)与 YouTube(NewPipe 直链)的可下载直链解析。
 *
 * B站复用 [PlaybackRepository.getPlaybackInfo] 的 DASH video/audio 轨(带 baseUrl + segmentBase + headers)。
 * YouTube 走 [YoutubePlaybackResolver.resolveForDownload](NewPipe 已解密直链,避免不可下载的 sabr://)。
 */
class DownloadUrlResolver(
  private val playbackRepository: PlaybackRepository,
  private val youtubePlaybackResolver: YoutubePlaybackResolver,
  private val appSettingsStore: AppSettingsStore,
) {
  suspend fun resolve(
    request: PlaybackRequest,
    choice: DownloadQualityChoice,
  ): Result<ResolvedDownload> {
    return when (choice.source) {
      DownloadSource.BILI -> resolveBili(request, choice)
      DownloadSource.YOUTUBE -> resolveYoutube(request, choice)
    }
  }

  private suspend fun resolveBili(request: PlaybackRequest, choice: DownloadQualityChoice): Result<ResolvedDownload> {
    return runCatching {
      val settings = appSettingsStore.settings.first()
      val codecPref = settings.playbackCodecPreference
      val qualityPref = settings.playbackQualityPreference
      val qn = choice.biliQn
      val resolved = if (qn != null) {
        playbackRepository.getPlaybackInfo(
          request.copy(preferredQualityId = qn),
          codecPref,
          qualityPref,
        )
      } else {
        playbackRepository.getPlaybackInfo(request, codecPref, qualityPref)
      }
      val video = resolved.videoTracks.maxByOrNull { it.bandwidth }
        ?: error("B站 DASH 无视频轨")
      val audio = resolved.audioTracks.maxByOrNull { it.bandwidth }
        ?: error("B站 DASH 无音频轨")
      ResolvedDownload(
        videoId = request.bvid,
        cid = request.cid,
        title = request.title,
        coverUrl = request.coverUrl,
        durationMs = resolved.durationMs,
        qualityLabel = choice.biliQualityLabel.ifBlank { "${video.height}p" },
        video = video.toResolvedPart(),
        audio = audio.toResolvedPart(),
        headers = resolved.headers.asMap(),
      )
    }.onFailure { Log.w(Tag, "resolveBili 失败: ${it.message}") }
  }

  private suspend fun resolveYoutube(request: PlaybackRequest, choice: DownloadQualityChoice): Result<ResolvedDownload> {
    return runCatching {
      youtubePlaybackResolver.resolveForDownload(
        request = request,
        preferMuxed = choice.youTubePreferMuxed,
        maxHeight = choice.youTubeMaxHeight,
      ) ?: error("YouTube 解析失败(无可用直链)")
    }.onFailure { Log.w(Tag, "resolveYoutube 失败: ${it.message}") }
  }

  private companion object {
    const val Tag = "DownloadUrlResolver"
  }

  private fun PlaybackTrack.toResolvedPart(): ResolvedPart = ResolvedPart(
    url = baseUrl,
    mimeType = mimeType,
    codecs = codecs,
    width = width,
    height = height,
    initRange = segmentBase?.initializationRange,
    mediaStartOffset = segmentBase?.indexRange?.substringBefore("-")?.trim()?.toLongOrNull() ?: 0L,
  )
}
