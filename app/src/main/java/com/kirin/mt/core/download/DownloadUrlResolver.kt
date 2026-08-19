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
    val qn = choice.biliQn
    return runCatching {
      val settings = appSettingsStore.settings.first()
      val codecPref = settings.playbackCodecPreference
      val qualityPref = settings.playbackQualityPreference
      val targetHeight = biliQualityHeight(choice.biliQualityLabel)
      // 下载不强制用户所选 qn 去请求 playurl：B站对「fourk=1 + 低 qn」的不一致组合会返回
      // code -400(请求错误),而 qn=127(Highest,与播放路径一致)实测稳定返回全部轨。故用最高 qn
      // 取全量轨,再按用户所选清晰度的高度挑「不超过目标高度」的带宽最高轨,既绕过 -400 又尊重选择。
      val resolved = playbackRepository.getPlaybackInfo(
        request.copy(preferredQualityId = PlaybackQualityPreference.Highest.requestedQualityId),
        codecPref,
        qualityPref,
      )
      val video = if (targetHeight != null) {
        resolved.videoTracks
          .filter { it.height > 0 && it.height <= targetHeight }
          .maxByOrNull { it.bandwidth }
          ?: resolved.videoTracks.maxByOrNull { it.bandwidth }
      } else {
        resolved.videoTracks.maxByOrNull { it.bandwidth }
      } ?: error("B站 DASH 无视频轨")
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
    }.onFailure { Log.w(Tag, "resolveBili 失败(bvid=${request.bvid} cid=${request.cid} qn=$qn): ${it.message}") }
  }

  /** 从清晰度描述解析目标高度(px):"1080P 高清"→1080、"4K 超清"→2160;解析不到返回 null(取最高轨)。 */
  private fun biliQualityHeight(label: String): Int? {
    Regex("""(\d+)\s*P""", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    if (Regex("""4K""", RegexOption.IGNORE_CASE).containsMatchIn(label)) return 2160
    return null
  }

  private suspend fun resolveYoutube(request: PlaybackRequest, choice: DownloadQualityChoice): Result<ResolvedDownload> {
    return runCatching {
      youtubePlaybackResolver.resolveForDownload(
        request = request,
        preferMuxed = choice.youTubePreferMuxed,
        maxHeight = choice.youTubeMaxHeight,
      ) ?: error("YouTube 解析失败(无可用直链)")
    }.onFailure { Log.w(Tag, "resolveYoutube 失败: ${it.javaClass.simpleName}: ${it.message}", it) }
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
