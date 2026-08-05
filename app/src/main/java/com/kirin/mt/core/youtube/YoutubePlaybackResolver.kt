package com.kirin.mt.core.youtube

import android.util.Log
import com.kirin.mt.core.player.BiliPlaybackHeaders
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackInfo
import com.kirin.mt.core.player.PlaybackQuality
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.PlaybackTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * YouTube 播放流解析器：`POST /youtubei/v1/player`（videoId + context，可带 PO token）
 * → 解析 `streamingData.adaptiveFormats` → 按 codec 偏好挑视频/音频 → 解密 `n`/`s`
 * → 产出 [PlaybackInfo]（progressive 直链，segmentBase=null）。
 *
 * 策略：
 *  - 先 WEB 客户端直连（无 PO token）；`playabilityStatus != OK`（被风控拦截）时
 *    回退 ANDROID 客户端（guest 取流更宽容）再试一次。
 *  - PO token（jnn）尚未跑通，[YoutubeBotGuard] 返回 null 时走直连；对多数视频仍可播。
 *  - 挑出的格式 URL 带 `n` 时走 [YoutubeNDecryptor] 解密（base.js 从 watch 页取）。
 */
class YoutubePlaybackResolver(
  private val innerTubeClient: InnerTubeClient,
  private val botGuard: YoutubeBotGuard,
  private val nDecryptor: YoutubeNDecryptor,
  private val httpClient: OkHttpClient,
) {

  suspend fun resolve(
    request: PlaybackRequest,
    codecPreference: PlaybackCodecPreference,
  ): PlaybackInfo = withContext(Dispatchers.IO) {
    val videoId = request.bvid
    var lastError: String? = null

    // 1) 尝试 WEB（直连，无 PO token）。
    var player = runCatching { postPlayer(videoId, client = InnerTubeClient.Client.WEB, poToken = null) }
      .getOrNull()
    if (!player.isPlayable()) {
      lastError = player.playabilityReason()
      Log.w(Tag, "player WEB not playable ($lastError); retry ANDROID")
      player = runCatching { postPlayer(videoId, client = InnerTubeClient.Client.ANDROID, poToken = null) }
        .getOrNull()
      if (!player.isPlayable()) {
        lastError = player?.playabilityReason() ?: lastError
        throw YoutubeApiException(
          statusCode = 0,
          responseBody = "",
          message = "YouTube playback blocked: ${lastError ?: "no streamingData"}",
        )
      }
    }

    val streamingData = player.obj("streamingData")
      ?: throw YoutubeApiException(0, "", "YouTube /player missing streamingData")
    // adaptiveFormats = 分离的纯视频/纯音频；formats = 单个合并的 progressive 流(音视频一体)。
    // 实测：未带 PO token 时 YouTube 常剥离 adaptiveFormats 的 url，仅保留 formats(progressive) 的 url。
    val adaptive = (streamingData.array("adaptiveFormats") ?: emptyList())
      .mapNotNull { it as? JsonObject }.mapNotNull(::parseFormat)
    val progressive = (streamingData.array("formats") ?: emptyList())
      .mapNotNull { it as? JsonObject }.mapNotNull(::parseFormat)

    val videoCandidates = adaptive.filter { it.kind == Kind.Video && !it.combined }
    val audioCandidates = adaptive.filter { it.kind == Kind.Audio }
    val combinedCandidates = (adaptive + progressive).filter { it.kind == Kind.Video && it.combined }

    // 取 base.js 用于 `n` 解密（仅当存在 `n` 参数时拉取）。
    val playerJsUrl = resolvePlayerJsUrl(videoId)
    val durationMs = (player.obj("videoDetails")?.stringOrNull("lengthSeconds")?.toLongOrNull() ?: 0L) * 1000L

    // Case B（优先）：单个合并 progressive 流(如 itag 18/22)是真实 mp4，ProgressiveMediaSource 可正确播放。
    // 实测 YouTube adaptive 流多为 fMP4 分片，喂 ProgressiveMediaSource 会解析失败(音频接口报错)，故不用它做首选项。
    val combined = combinedCandidates.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
    if (combined != null) {
      val combinedUrl = resolveStreamUrl(combined, playerJsUrl)
      if (combinedUrl.isNotBlank()) {
        return@withContext buildInfo(request, videoId, durationMs, combined, null, combinedUrl, "")
      }
    }
    // Case A（last resort）：adaptive 视频+音频双轨。fMP4 分片经 ProgressiveMediaSource 可能解不了，仅作兜底。
    val video = pickVideo(videoCandidates, codecPreference)
    val audio = pickAudio(audioCandidates)
    if (video != null && audio != null) {
      val videoUrl = resolveStreamUrl(video, playerJsUrl)
      val audioUrl = resolveStreamUrl(audio, playerJsUrl)
      if (videoUrl.isNotBlank() && audioUrl.isNotBlank()) {
        return@withContext buildInfo(request, videoId, durationMs, video, audio, videoUrl, audioUrl)
      }
    }
    throw YoutubeApiException(0, "", "YouTube no decodable video/audio formats")
  }

  private fun buildInfo(
    request: PlaybackRequest,
    videoId: String,
    durationMs: Long,
    videoFmt: ParsedFormat,
    audioFmt: ParsedFormat?,
    videoUrl: String,
    audioUrl: String,
  ): PlaybackInfo {
    val quality = PlaybackQuality(id = videoFmt.itag, description = videoFmt.qualityLabel)
    val videoTrack = PlaybackTrack(
      id = videoFmt.itag,
      baseUrl = videoUrl,
      backupUrls = emptyList(),
      bandwidth = videoFmt.bitrate,
      codecs = videoFmt.codecs,
      width = videoFmt.width,
      height = videoFmt.height,
      mimeType = videoFmt.mimeType,
    )
    val audioTracks = if (audioFmt != null && audioUrl.isNotBlank()) {
      listOf(
        PlaybackTrack(
          id = audioFmt.itag,
          baseUrl = audioUrl,
          backupUrls = emptyList(),
          bandwidth = audioFmt.bitrate,
          codecs = audioFmt.codecs,
          width = 0,
          height = 0,
          mimeType = audioFmt.mimeType,
        ),
      )
    } else {
      emptyList()
    }
    val kindLabel = if (audioFmt != null) "${videoFmt.qualityLabel} + audio" else "${videoFmt.qualityLabel} (progressive)"
    Log.i(
      Tag,
      "resolve ok: $kindLabel itag=${videoFmt.itag}; nDecrypt=${if (videoUrl.contains("n=")) "left" else "applied"}",
    )
    return PlaybackInfo(
      bvid = videoId,
      cid = 0L,
      title = request.title,
      durationMs = durationMs,
      qualities = listOf(quality),
      selectedQuality = quality,
      videoTracks = listOf(videoTrack),
      audioTracks = audioTracks,
      headers = YoutubePlaybackHeaders,
    )
  }

  // ---- /player 请求与解析 ----

  private suspend fun postPlayer(videoId: String, client: InnerTubeClient.Client, poToken: String?): JsonObject {
    val payload = buildJsonObject {
      put("videoId", videoId)
      put("contentCheckOk", true)
      put("racyCheckOk", true)
      put("playbackContext", buildJsonObject {
        put("contentPlaybackContext", buildJsonObject { put("html5Preference", "HTML5_PREF_WANTS") })
      })
    }
    return innerTubeClient.postJson("/player", payload, client = client, poToken = poToken)
  }

  /** 从 watch 页 HTML 提取 base.js URL（用于 n 解密）。失败返回 null。 */
  private suspend fun resolvePlayerJsUrl(videoId: String): String? = withContext(Dispatchers.IO) {
    val page = runCatching {
      val req = Request.Builder()
        .url("https://www.youtube.com/watch?v=$videoId")
        .header("User-Agent", YoutubeConstants.UserAgent)
        .build()
      httpClient.newCall(req).execute().use { it.body?.string().orEmpty() }
    }.getOrNull()
    if (page.isNullOrBlank()) return@withContext null
    val m = Regex("""\"jsUrl\":\"([^\"]+base\.js)\"""").find(page)
      ?: Regex("""\"jsUrl\":\"([^\"]+)\"""").find(page)
    val raw = m?.groupValues?.get(1)
    raw?.takeIf { it.isNotBlank() }
      ?.replace("\\/", "/")
      ?.replace("\\u0026", "&")
      ?.let { if (it.startsWith("http")) it else "https://www.youtube.com$it" }
  }

  private suspend fun resolveStreamUrl(format: ParsedFormat, playerJsUrl: String?): String {
    var url = format.url
    // signatureCipher 形态：url 缺失，需解 s + sp 并回填（signature 解密当前 best-effort，缺 base.js 时用原始 url 兜底）。
    if (url.isBlank()) {
      url = signatureCipherUrl(format.signatureCipher) ?: return ""
    }
    // 解密 `n`（base.js 不可用/解密失败时保留原 url，多半 403 由播放器报错暴露）。
    if (url.contains("n=") && playerJsUrl != null) {
      url = nDecryptor.decrypt(url, playerJsUrl)
    }
    return url
  }

  /** signatureCipher "s=..&sp=..&url=.." 的解析；s 解密当前未实现，仅回填原始 url。 */
  private fun signatureCipherUrl(cipher: String?): String? {
    if (cipher.isNullOrBlank()) return null
    val parts = cipher.split("&").associate { entry ->
      val idx = entry.indexOf('=')
      if (idx < 0) entry to "" else entry.substring(0, idx) to entry.substring(idx + 1)
    }
    return parts["url"]
  }

  // ---- 格式挑选 ----

  private fun pickVideo(candidates: List<ParsedFormat>, preference: PlaybackCodecPreference): ParsedFormat? {
    val priority = when (preference) {
      PlaybackCodecPreference.H264 -> listOf("avc")
      PlaybackCodecPreference.H265 -> listOf("hevc")
      PlaybackCodecPreference.Av1 -> listOf("av01")
      PlaybackCodecPreference.Auto -> listOf("avc", "vp9", "av01", "hevc")
    }
    for (codecKey in priority) {
      val group = candidates.filter { it.codecKey == codecKey }
      if (group.isNotEmpty()) {
        return group.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
      }
    }
    // 偏好 codec 全无 → 退化为最高分辨率任意 codec。
    return candidates.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
  }

  private fun pickAudio(candidates: List<ParsedFormat>): ParsedFormat? {
    // 优先 opus(251)，其次 m4a(140)，否则最高 bitrate。
    val opus = candidates.firstOrNull { it.itag == 251 }
    if (opus != null) return opus
    val m4a = candidates.firstOrNull { it.itag == 140 }
    if (m4a != null) return m4a
    return candidates.maxByOrNull { it.bitrate }
  }

  // ---- format 解析 ----

  private fun parseFormat(node: JsonObject): ParsedFormat? {
    val itag = node.longOrNull("itag")?.toInt() ?: return null
    val mimeType = node.stringOrNull("mimeType").orEmpty()
    val codecs = extractCodecs(mimeType)
    val kind = when {
      mimeType.startsWith("video/") -> Kind.Video
      mimeType.startsWith("audio/") -> Kind.Audio
      else -> return null
    }
    if (kind == Kind.Video && (node.intOrNull("height") ?: 0) <= 0) return null
    val cipher = node.stringOrNull("signatureCipher")
    val url = node.stringOrNull("url") ?: if (cipher != null) "" else null
    if (url == null && cipher == null) return null
    return ParsedFormat(
      itag = itag,
      mimeType = mimeType,
      codecs = codecs,
      codecKey = codecKey(codecs),
      width = node.intOrNull("width") ?: 0,
      height = node.intOrNull("height") ?: 0,
      bitrate = node.intOrNull("bitrate") ?: 0,
      qualityLabel = node.stringOrNull("qualityLabel") ?: "${node.intOrNull("height") ?: 0}p",
      url = url.orEmpty(),
      signatureCipher = cipher,
      // 合并流(音视频一体，如 progressive itag 18)的 mimeType 里含音频 codec(mp4a/opus)。
      combined = mimeType.contains("mp4a", ignoreCase = true) || mimeType.contains("opus", ignoreCase = true),
    )
  }

  private fun extractCodecs(mimeType: String): String {
    val m = Regex("""codecs="([^"]+)"""").find(mimeType) ?: return ""
    return m.groupValues[1].trim().split(",").firstOrNull()?.trim().orEmpty()
  }

  private fun codecKey(codecs: String): String {
    val c = codecs.lowercase(Locale.ROOT)
    return when {
      c.startsWith("avc") -> "avc"
      c.startsWith("hev") || c.startsWith("hvc") -> "hevc"
      c.startsWith("av01") -> "av01"
      c.startsWith("vp9") || c.startsWith("vp8") -> "vp9"
      c.startsWith("opus") -> "opus"
      c.startsWith("mp4a") -> "m4a"
      else -> "other"
    }
  }

  // ---- Json 辅助 ----

  // 全部用可空 receiver，兼容 runCatching.getOrNull() 可能为 null 的 /player 响应。
  private fun JsonObject?.obj(name: String): JsonObject? = this?.get(name) as? JsonObject
  private fun JsonObject?.array(name: String): JsonArray? = this?.get(name) as? JsonArray
  private fun JsonObject?.stringOrNull(name: String): String? = this?.get(name)?.jsonPrimitive?.contentOrNull
  private fun JsonObject?.intOrNull(name: String): Int? = this?.get(name)?.jsonPrimitive?.content?.toIntOrNull()
  private fun JsonObject?.longOrNull(name: String): Long? = this?.get(name)?.jsonPrimitive?.content?.toLongOrNull()

  private fun JsonObject?.isPlayable(): Boolean {
    return obj("playabilityStatus")?.stringOrNull("status")?.let { it == "OK" } == true ||
      obj("streamingData") != null
  }

  private fun JsonObject?.playabilityReason(): String {
    return obj("playabilityStatus")?.stringOrNull("reason")
      ?: obj("playabilityStatus")?.stringOrNull("status")
      ?: "unknown"
  }

  private enum class Kind { Video, Audio }

  private data class ParsedFormat(
    val itag: Int,
    val mimeType: String,
    val codecs: String,
    val codecKey: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val qualityLabel: String,
    val url: String,
    val signatureCipher: String?,
    /** 是否合并流(音视频一体，progressive itag 18 等)。 */
    val combined: Boolean,
    val kind: Kind = if (mimeType.startsWith("video/")) Kind.Video else Kind.Audio,
  )

  private companion object {
    const val Tag = "YtResolver"

    /** googlevideo 直链无需 B 站 Cookie；仅带 youtube Referer/Origin。 */
    val YoutubePlaybackHeaders = BiliPlaybackHeaders(
      sessData = null,
      biliJct = null,
      mid = null,
      referer = "https://www.youtube.com",
      origin = "https://www.youtube.com",
    )
  }
}
