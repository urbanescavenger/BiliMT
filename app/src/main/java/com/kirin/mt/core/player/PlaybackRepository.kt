package com.kirin.mt.core.player

import android.util.Log
import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.network.BiliApiClient
import com.kirin.mt.core.network.BiliApiEndpoints
import com.kirin.mt.core.network.BiliNumberParser
import com.kirin.mt.core.network.PgcMappers
import com.kirin.mt.core.network.asObjectOrNull
import com.kirin.mt.core.network.boolean
import com.kirin.mt.core.network.int
import com.kirin.mt.core.network.long
import com.kirin.mt.core.network.obj
import com.kirin.mt.core.network.requireBiliCodeOk
import com.kirin.mt.core.network.rootObject
import com.kirin.mt.core.network.string
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class PlaybackRepository(
  private val apiClient: BiliApiClient,
  private val wbiKeyRepository: WbiKeyRepository,
  private val wbiSigner: WbiSigner,
  private val sessionStore: SessionStore,
  private val codecCapabilityProbe: CodecCapabilityProbe,
  private val progressStore: PlaybackProgressStore,
) {
  private val videoshotRepository = VideoshotRepository(
    apiClient = apiClient,
    sessionStore = sessionStore,
  )
  private val danmakuRepository = DanmakuRepository(
    apiClient = apiClient,
    sessionStore = sessionStore,
  )
  private val airJumpRepository = AirJumpRepository(apiClient)

  private val playbackCache = LinkedHashMap<PlaybackCacheKey, CachedPlaybackInfo>()

  suspend fun getPlaybackInfo(
    request: PlaybackRequest,
    codecPreference: PlaybackCodecPreference,
    qualityPreference: PlaybackQualityPreference,
  ): PlaybackInfo {
    val requestedQualityId = request.preferredQualityId ?: qualityPreference.requestedQualityId
    val cacheKey = PlaybackCacheKey(
      bvid = request.bvid,
      cid = request.cid,
      codecPreference = codecPreference,
      requestedQualityId = requestedQualityId,
    )
    cachedPlaybackInfo(cacheKey)?.let {
      Log.i(PlaybackLogTag, "playurl cache hit for bvid=${request.bvid} cid=${request.cid}")
      return it
    }

    val session = sessionStore.session.first()
    val sessData = session.sessData
    val biliJct = session.biliJct
    val mid = session.mid
    val codecCapability = codecCapabilityProbe.probe()
    val effectiveCodecPreference = when {
      codecPreference == PlaybackCodecPreference.H264 -> PlaybackCodecPreference.H264
      codecCapability.supports(codecPreference) -> codecPreference
      else -> PlaybackCodecPreference.Auto
    }
    val fnval = buildFnval(
      codecPreference = effectiveCodecPreference,
      codecCapability = codecCapability,
    )
    Log.i(
      PlaybackLogTag,
      "playurl codec requested=${codecPreference.key} effective=${effectiveCodecPreference.key} fnval=$fnval qn=$requestedQualityId",
    )
    val params = if (request.isPgc) {
      // PGC 复用与 UGC 相同的 SDR fnval（buildFnval：DASH+H265+AV1），不请求 HDR/杜比视界/8K/杜比音轨。
      // 原因：CodecCapabilityProbe 只探测 avc/hevc/av01，不探测 DV/HDR profile；fnval=4048 会让服务端给
      // 大会员返回顶部 HDR/DV 清晰度，设备声称支持 H.265 却渲染不了 HDR/DV → 黑屏无 onPlayerError。
      // 不声明 from_client / support_multi_audio，避免服务端返回 Web DRM 流或当前播放器无法处理的格式。
      mutableMapOf(
        "cid" to request.cid.toString(),
        "qn" to requestedQualityId.toString(),
        "fnval" to fnval.toString(),
        "fnver" to "0",
        "fourk" to "1",
      ).apply {
        if (request.epId > 0L) put("ep_id", request.epId.toString())
        if (request.bvid.isNotBlank()) put("bvid", request.bvid)
        if (request.aid > 0L) put("avid", request.aid.toString())
      }
    } else {
      mutableMapOf(
        "bvid" to request.bvid,
        "cid" to request.cid.toString(),
        "qn" to requestedQualityId.toString(),
        "fnval" to fnval.toString(),
        "fourk" to "1",
      )
    }
    val keys = wbiKeyRepository.ensureKeys(sessData)
    val signedParams = when {
      request.isPgc -> params
      keys != null -> wbiSigner.sign(params, keys.imgKey, keys.subKey)
      else -> params
    }

    val headers = BiliPlaybackHeaders(sessData = sessData, biliJct = biliJct, mid = mid)
    val url = if (request.isPgc) BiliApiEndpoints.PgcPlayUrl else BiliApiEndpoints.PlayUrl
    val root = apiClient.getJsonWithHeaders(
      url = url,
      params = signedParams,
      headers = headers.asMap(),
    ).rootObject()
    root.requireBiliCodeOk("playurl")
    // 对齐 BV 的 BiliResponse.getResponseData()：UGC /x/player/playurl 的 payload 在 data 下，
    // 而 PGC /pgc/player/web/playurl v1 把整个 payload 包在根级 result 对象下（其内层 result 才是 "suee"）。
    // PGC 响应没有 data 字段，必须回退到 result，否则 dash 永远为空、PGC 起播黑屏。
    val data = root.obj("data") ?: root.obj("result") ?: JsonObject(emptyMap())
    if (request.isPgc) logPgcPlayUrlResponse(request, data)
    val info = parsePlaybackInfo(
      request = request,
      headers = headers,
      data = data,
      requestedQualityId = requestedQualityId,
      codecPreference = effectiveCodecPreference,
      codecCapability = codecCapability,
    )
    storeCachedPlaybackInfo(cacheKey, info)
    return info
  }

  /**
   * Short-TTL in-memory cache for resolved playurl. The signed media URLs
   * returned by B 站 stay valid far longer than [PlaybackCacheTtlMs], so a
   * ~90s cache is safe and lets reopening a video (or flipping between
   * episodes) skip the 1–3s api.bilibili.com round-trip. codecCapability is
   * device-stable, so keying on the requested codec preference is sufficient.
   */
  private fun cachedPlaybackInfo(key: PlaybackCacheKey): PlaybackInfo? {
    synchronized(playbackCache) {
      val now = System.currentTimeMillis()
      playbackCache.entries.removeAll { it.value.expiresAt <= now }
      return playbackCache[key]?.info
    }
  }

  private fun storeCachedPlaybackInfo(key: PlaybackCacheKey, info: PlaybackInfo) {
    synchronized(playbackCache) {
      playbackCache[key] = CachedPlaybackInfo(
        info = info,
        expiresAt = System.currentTimeMillis() + PlaybackCacheTtlMs,
      )
    }
  }

  private data class PlaybackCacheKey(
    val bvid: String,
    val cid: Long,
    val codecPreference: PlaybackCodecPreference,
    val requestedQualityId: Int,
  )

  private data class CachedPlaybackInfo(
    val info: PlaybackInfo,
    val expiresAt: Long,
  )

  suspend fun resolveCid(bvid: String): Long {
    if (bvid.isBlank()) {
      return 0L
    }
    val root = apiClient.getJson(
      url = BiliApiEndpoints.View,
      params = mapOf("bvid" to bvid),
    ).rootObject()
    root.requireBiliCodeOk("view")
    val data = root.obj("data") ?: return 0L
    return data.long("cid").takeIf { it > 0L }
      ?: ((data["pages"] as? JsonArray)
        ?.firstOrNull()
        ?.asObjectOrNull()
        ?.long("cid") ?: 0L)
  }

  suspend fun getVideoMetadata(request: PlaybackRequest): PlaybackVideoMetadata {
    if (request.seasonId > 0L) {
      return getPgcVideoMetadata(request)
    }

    val root = apiClient.getJson(
      url = BiliApiEndpoints.View,
      params = mapOf("bvid" to request.bvid),
    ).rootObject()
    root.requireBiliCodeOk("view metadata")

    val data = root.obj("data") ?: JsonObject(emptyMap())
    val owner = data.obj("owner")
    val stat = data.obj("stat")
    val pages = (data["pages"] as? JsonArray)
      ?.mapNotNull { element ->
        val page = element.asObjectOrNull() ?: return@mapNotNull null
        PlaybackEpisode(
          cid = page.long("cid"),
          page = page.int("page"),
          title = page.string("part").ifBlank { page.string("page_part") },
          durationSeconds = BiliNumberParser.parseDuration(page["duration"]),
        )
      }
      ?.filter { episode -> episode.cid > 0L }
      .orEmpty()

    return PlaybackVideoMetadata(
      aid = data.long("aid"),
      bvid = data.string("bvid").ifBlank { request.bvid },
      cid = request.cid.takeIf { it > 0L }
        ?: data.long("cid").takeIf { it > 0L }
        ?: pages.firstOrNull()?.cid
        ?: 0L,
      title = data.string("title").ifBlank { request.title },
      ownerName = owner?.string("name").orEmpty(),
      ownerFace = owner?.string("face").orEmpty(),
      ownerMid = owner?.long("mid") ?: 0L,
      viewCount = BiliNumberParser.toInt(stat?.get("view")),
      danmakuCount = BiliNumberParser.toInt(stat?.get("danmaku")),
      pubdate = data.long("pubdate"),
      pages = pages,
    )
  }

  private suspend fun getPgcVideoMetadata(request: PlaybackRequest): PlaybackVideoMetadata {
    val sessData = sessionStore.sessData.first()
    val root = apiClient.getJson(
      url = BiliApiEndpoints.PgcSeasonView,
      params = mapOf("season_id" to request.seasonId.toString()),
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("pgc season metadata")

    val data = root.obj("data") ?: JsonObject(emptyMap())
    val season = PgcMappers.fromSeasonData(data)
    val pages = season.episodes.mapIndexed { index, ep ->
      PlaybackEpisode(
        cid = ep.cid,
        page = index + 1,
        title = ep.longTitle.ifBlank { ep.title },
        durationSeconds = ep.duration,
        epId = ep.id.toLong(),
      )
    }.filter { it.cid > 0L }

    return PlaybackVideoMetadata(
      aid = request.aid,
      bvid = request.bvid,
      cid = request.cid.takeIf { it > 0L }
        ?: pages.firstOrNull()?.cid
        ?: 0L,
      title = season.title.ifBlank { request.title },
      ownerName = "",
      ownerFace = "",
      ownerMid = 0L,
      viewCount = 0,
      danmakuCount = 0,
      pubdate = 0L,
      pages = pages,
    )
  }

  suspend fun getOnlineCount(aid: Long, cid: Long): String? {
    if (aid <= 0L || cid <= 0L) {
      return null
    }
    val sessData = sessionStore.sessData.first()
    val root = apiClient.getJson(
      url = BiliApiEndpoints.PlayerOnlineTotal,
      params = mapOf(
        "aid" to aid.toString(),
        "cid" to cid.toString(),
      ),
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("player online total")

    val data = root.obj("data") ?: return null
    return data.string("total")
      .ifBlank { data.string("count") }
      .takeIf { count -> count.isNotBlank() }
  }

  suspend fun getVideoshot(bvid: String, cid: Long): VideoshotData? {
    return videoshotRepository.getVideoshot(bvid = bvid, cid = cid)
  }

  suspend fun getVideoshotImageBytes(url: String): ByteArray? {
    return videoshotRepository.getVideoshotImageBytes(url)
  }

  suspend fun getDanmaku(cid: Long): List<DanmakuEntry> {
    return danmakuRepository.getDanmaku(cid)
  }

  suspend fun getAirJumpSegments(bvid: String): List<AirJumpSegment> {
    return airJumpRepository.getAirJumpSegments(bvid)
  }

  suspend fun getSavedProgress(bvid: String, cid: Long): PlaybackProgress? {
    return progressStore.getProgress(bvid = bvid, cid = cid)
  }

  suspend fun getLatestSavedProgress(bvid: String): PlaybackProgress? {
    return progressStore.getLatestProgress(bvid = bvid)
  }

  suspend fun saveProgress(
    bvid: String,
    cid: Long,
    positionMs: Long,
    durationMs: Long,
  ) {
    progressStore.saveProgress(
      bvid = bvid,
      cid = cid,
      positionMs = positionMs,
      durationMs = durationMs,
    )
  }

  suspend fun reportProgress(
    bvid: String,
    cid: Long,
    progressSeconds: Int,
  ): Boolean {
    if (bvid.isBlank() || cid <= 0L) return false
    val sessData = sessionStore.sessData.first()
    val biliJct = sessionStore.biliJct.first()
    if (sessData.isNullOrBlank() || biliJct.isNullOrBlank()) return false

    return runCatching {
      val root = apiClient.postJson(
        url = BiliApiEndpoints.PlayerHeartbeat,
        params = mapOf(
          "bvid" to bvid,
          "cid" to cid.toString(),
          "played_time" to progressSeconds.toString(),
          "real_played_time" to progressSeconds.toString(),
          "start_ts" to (System.currentTimeMillis() / 1000L).toString(),
          "csrf" to biliJct,
        ),
        sessData = sessData,
        biliJct = biliJct,
      ).rootObject()
      root.requireBiliCodeOk("player heartbeat")
      true
    }.getOrDefault(false)
  }

  private fun logPgcPlayUrlResponse(request: PlaybackRequest, data: JsonObject) {
    val type = data.string("type")
    val result = data.string("result")
    val isDrm = data.boolean("is_drm")
    val hasPaid = data.boolean("has_paid")
    val isPreview = data.int("is_preview") == 1
    val quality = data.int("quality")
    val dash = data.obj("dash")
    val videoCount = (dash?.get("video") as? JsonArray)?.size ?: 0
    val audioCount = (dash?.get("audio") as? JsonArray)?.size ?: 0
    Log.i(
      PlaybackLogTag,
      "pgc playurl epId=${request.epId} cid=${request.cid} type=$type result=$result " +
        "quality=$quality drm=$isDrm paid=$hasPaid preview=$isPreview " +
        "videos=$videoCount audios=$audioCount",
    )
  }

  private fun parsePlaybackInfo(
    request: PlaybackRequest,
    headers: BiliPlaybackHeaders,
    data: JsonObject,
    requestedQualityId: Int,
    codecPreference: PlaybackCodecPreference,
    codecCapability: CodecCapability,
  ): PlaybackInfo {
    if (request.isPgc) {
      val type = data.string("type")
      val result = data.string("result")
      val isDrm = data.boolean("is_drm")
      val hasPaid = data.boolean("has_paid")
      val isPreview = data.int("is_preview") == 1
      if (type.isNotBlank() && type != "DASH") {
        throw BiliPlaybackException("PGC playurl returned type=$type, only DASH is supported")
      }
      if (isDrm) {
        throw BiliPlaybackException("PGC content requires DRM, which is not supported yet")
      }
      if (isPreview) {
        throw BiliPlaybackException("PGC content requires purchase/preview only")
      }
      if (result.isNotBlank() && result !in PgcSuccessResults) {
        throw BiliPlaybackException("PGC playurl business result=$result")
      }
    }
    val dash = data.obj("dash")
    val videoTracks = (dash?.get("video") as? JsonArray)
      ?.mapNotNull { it.asObjectOrNull()?.toPlaybackTrack() }
      .orEmpty()
      .filter { track -> track.baseUrl.isNotBlank() }
    val audioTracks = (dash?.get("audio") as? JsonArray)
      ?.mapNotNull { it.asObjectOrNull()?.toPlaybackTrack() }
      .orEmpty()
      .filter { track -> track.baseUrl.isNotBlank() }
    val qualities = parseQualities(data)
    val selectedQuality = qualities.firstOrNull { quality -> quality.id == data.int("quality") }
      ?: qualities.firstOrNull()
      ?: PlaybackQuality(id = data.int("quality"), description = data.int("quality").toString())
    val acceptedVideoTracks = videoTracks.filter { track ->
      track.isPlayable(codecPreference = codecPreference, capability = codecCapability)
    }.ifEmpty {
      videoTracks.filter { track -> track.isPlayable(codecPreference = PlaybackCodecPreference.Auto, capability = codecCapability) }
    }.ifEmpty {
      videoTracks
    }
    val selectedQualityTracks = acceptedVideoTracks.filter { track -> track.id == selectedQuality.id }
      .ifEmpty { acceptedVideoTracks }
      .sortedWith(videoTrackComparator(codecPreference))
    val selectedVideo = selectedQualityTracks.firstOrNull()
    Log.i(
      PlaybackLogTag,
      "playurl ${if (request.isPgc) "pgc" else "ugc"} qn requested=$requestedQualityId returned=${selectedQuality.id} " +
        "selected=${selectedVideo?.codecs ?: "none"} mime=${selectedVideo?.mimeType.orEmpty()} " +
        "${selectedVideo?.width ?: 0}x${selectedVideo?.height ?: 0} " +
        "(accepted=${acceptedVideoTracks.size}/${videoTracks.size}) " +
        "tracks=${selectedQualityTracks.joinToString { "${it.id}:${it.width}x${it.height}:${it.codecs}" }}",
    )

    return PlaybackInfo(
      bvid = request.bvid,
      cid = request.cid,
      title = request.title,
      durationMs = (dash?.long("duration") ?: 0L) * 1000L,
      qualities = qualities,
      selectedQuality = selectedQuality,
      videoTracks = selectedQualityTracks,
      audioTracks = audioTracks.sortedByDescending(PlaybackTrack::bandwidth),
      headers = headers,
    )
  }

  private fun JsonObject.toPlaybackTrack(): PlaybackTrack {
    return PlaybackTrack(
      id = int("id"),
      baseUrl = string("baseUrl").ifBlank { string("base_url") },
      backupUrls = ((this["backupUrl"] ?: this["backup_url"]) as? JsonArray)
        ?.mapNotNull { element -> element.toString().trim('"').takeIf(String::isNotBlank) }
        .orEmpty(),
      bandwidth = int("bandwidth"),
      codecs = string("codecs"),
      width = int("width"),
      height = int("height"),
      mimeType = string("mimeType").ifBlank { string("mime_type") },
      segmentBase = PlaybackSegmentBase(
        initializationRange = obj("SegmentBase")?.rangeString("Initialization")
          ?: obj("segment_base")?.rangeString("initialization")
          ?: "0-0",
        indexRange = obj("SegmentBase")?.string("indexRange")
          ?: obj("segment_base")?.string("index_range")
          ?: "0-0",
      ),
    )
  }

  private fun JsonObject.rangeString(name: String): String? {
    return string(name).ifBlank {
      obj(name)?.string("range").orEmpty()
    }.takeIf { value -> value.isNotBlank() }
  }

  private fun parseQualities(data: JsonObject): List<PlaybackQuality> {
    val ids = (data["accept_quality"] as? JsonArray)
      ?.mapNotNull { element -> element.toString().toIntOrNull() }
      .orEmpty()
    val descriptions = (data["accept_description"] as? JsonArray)
      ?.map { element -> element.asString() }
      .orEmpty()

    return ids.mapIndexed { index, id ->
      PlaybackQuality(
        id = id,
        description = descriptions.getOrNull(index).orEmpty().ifBlank { id.toString() },
      )
    }
  }

  private fun JsonElement.asString(): String {
    return (this as? JsonPrimitive)?.contentOrNull ?: toString().trim('"')
  }

  private fun PlaybackTrack.isPlayable(
    codecPreference: PlaybackCodecPreference,
    capability: CodecCapability,
  ): Boolean {
    when (codecPreference) {
      PlaybackCodecPreference.H264 -> return isH264
      PlaybackCodecPreference.H265 -> return isH265
      PlaybackCodecPreference.Av1 -> return isAv1
      PlaybackCodecPreference.Auto -> Unit
    }
    return when {
      isAv1 -> capability.supportsAv1
      isH265 -> capability.supportsH265
      isH264 -> capability.supportsH264
      // 未知 codec（含杜比视界 dvhe/dvh1 等已落到此分支）一律判不可播，
      // 避免 fnval 万一仍带回高级流时把不可解轨道选进 manifest 导致黑屏。
      else -> false
    }
  }

  private fun videoTrackComparator(codecPreference: PlaybackCodecPreference): Comparator<PlaybackTrack> {
    return compareByDescending<PlaybackTrack> { track ->
      track.codecPriority(codecPreference)
    }.thenByDescending { track ->
      track.height
    }.thenByDescending { track ->
      track.bandwidth
    }
  }

  private fun PlaybackTrack.codecPriority(codecPreference: PlaybackCodecPreference): Int {
    return when (codecPreference) {
      PlaybackCodecPreference.H264 -> if (isH264) 1 else 0
      PlaybackCodecPreference.H265 -> if (isH265) 1 else 0
      PlaybackCodecPreference.Av1 -> if (isAv1) 1 else 0
      PlaybackCodecPreference.Auto -> when {
        isAv1 -> 3
        isH265 -> 2
        isH264 -> 1
        else -> 0
      }
    }
  }

  private fun PlaybackTrack.codecLabel(): String {
    return when {
      isAv1 -> "AV1"
      isH265 -> "H.265"
      isH264 -> "H.264"
      else -> codecs
    }
  }

  private fun buildFnval(codecPreference: PlaybackCodecPreference, codecCapability: CodecCapability): Int {
    var fnval = FnvalDash
    if (codecPreference != PlaybackCodecPreference.H264 && codecCapability.supportsH265) {
      fnval = fnval or FnvalH265
    }
    if (codecPreference == PlaybackCodecPreference.Auto && codecCapability.supportsAv1 ||
      codecPreference == PlaybackCodecPreference.Av1 && codecCapability.supportsAv1
    ) {
      fnval = fnval or FnvalAv1
    }
    return fnval
  }

  private companion object {
    const val FnvalDash = 16
    const val FnvalH265 = 64
    const val FnvalAv1 = 1024
    /**
     * 弃用：原 PGC 固定 fnval=4048（DASH+H265+HDR+4K+DolbyAudio+DolbyVision+8K）。
     * 现已改为 PGC 复用 buildFnval（仅 SDR DASH+H265+AV1），避免服务端返回设备渲染不了的
     * HDR/杜比视界流导致黑屏。保留此常量供未来在 CodecCapabilityProbe 加 DV/HDR 能力探测后重新启用。
     */
    const val PgcFnval = 4048
    /**
     * PGC playurl 业务成功标识。BV 只靠 code==0 + isPreview/is_drm 判定，不校验该字符串；
     * 这里宽松接受 b 站历史出现过的两种取值，避免误杀导致起播失败。
     */
    val PgcSuccessResults = setOf("suee", "success")
    const val PlaybackLogTag = "BiliMT:Playback"
    const val PlaybackCacheTtlMs = 90_000L
  }
}

class BiliPlaybackException(message: String) : Exception(message)
