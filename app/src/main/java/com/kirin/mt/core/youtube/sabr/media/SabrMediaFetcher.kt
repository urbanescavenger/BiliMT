package com.kirin.mt.core.youtube.sabr.media

import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.kirin.mt.core.youtube.sabr.ClientAbrStateInput
import com.kirin.mt.core.youtube.sabr.FormatId
import com.kirin.mt.core.youtube.sabr.SabrProto
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_FORMAT_INITIALIZATION_METADATA
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_MEDIA
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_MEDIA_END
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_MEDIA_HEADER
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_NEXT_REQUEST_POLICY
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_RELOAD_PLAYER_RESPONSE
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_CONTEXT_SENDING_POLICY
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_CONTEXT_UPDATE
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_ERROR
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_REDIRECT
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_STREAM_PROTECTION_STATUS
import com.kirin.mt.core.youtube.sabr.SabrRequestInput
import com.kirin.mt.core.youtube.sabr.SabrSession
import com.kirin.mt.core.youtube.sabr.SabrStreamRegistry
import com.kirin.mt.core.youtube.sabr.StreamerContextInput
import com.kirin.mt.core.youtube.sabr.UmpReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * alpha.64(端口 LibreTube `parser/SabrClient`):**单流多段** SABR fetcher。
 *
 * 取代 alpha.27-63 的双流 [com.kirin.mt.core.youtube.sabr.SabrClient](两路独立 POST,video bitfield=2 /
 * audio bitfield=1,对方格式 fake-full Int.MAX)。本 fetcher 对齐 LibreTube:
 *  - 一次 POST 同时请求 A+V([selectedFormatIds]=全部已初始化格式,[bitfield]=0)→ 服务端单响应推多段。
 *  - [bufferedRanges] 全部真实(每格式 buildBufferedRanges 按 downloadedSegments/bufferedSegments 算),
 *    **从不发 Int.MAX fake-full**——这是 alpha.63 证伪双流后锁定的 60s 断崖真因修复。
 *  - 多段缓存进 [initializedFormats](per itag)的 downloadedSegments,[getNextSegment] 逐段取。
 *
 * A/V 在 [SabrMediaPeriod] 层拆成两个 ChunkSampleStream 共享**一个**本 fetcher(单会话/单 clientNumber),
 * 服务端 60s-per-session 窗口在单流下靠 cookie 轮换无缝续推跨 60s,不再边界软拒。
 *
 * 终端结果处理保留(对齐我们 alpha.41-62 的稳健模型,优于 LibreTube 直接 throw):
 *  - Backoff → fetchStreamData 起始 sleep(封顶 2.5s 防 stall watchdog)+ [getNextSegment] 内重试同请求。
 *  - SABR_REDIRECT → [SabrSession.applyRedirect] 写回 sabrUrl,重试同请求。
 *  - RELOAD_PLAYER_RESPONSE / InvalidPoToken(status3)/ SABR_ERROR → 抛 [SabrTerminalException]
 *    → [SabrDataSource] evict 会话 → 播放器 error-retry 走新 harvest。
 *
 * 线程安全:[getNextSegment] 用 runBlocking{withContext(Dispatchers.IO.limitedParallelism(1)){...}}
 * 串行化(同 LibreTube),两个 ChunkSampleStream 的 loader 线程不会并发改 [initializedFormats]。
 *
 * 对齐 LibreTube `parser/SabrClient.kt`(MIT)。
 */
@OptIn(UnstableApi::class)
internal class SabrMediaFetcher(
  /**
   * alpha.66/67:整个会话 Entry(含 session/poTokenState/refreshPoToken)。改传 Entry 而非散参数,
   * 因 [poTokenState] 提升到会话级——切清晰度重建 fetcher 时新 fetcher 仍读已刷新的 token
   * (修 alpha.65 fetcher-instance currentPoToken 重建即丢的回归)。status=2 时在 [media] 的
   * readParts 后**同步**重铸 PO token(对齐 LibreTube,下个请求一定带新 token → status=3 不再出现)。
   */
  private val entry: SabrStreamRegistry.Entry,
  private val httpClient: OkHttpClient,
) {
  private val tag = "YtSabr"
  /** 派生自 [entry](会话级,切清晰度重建 fetcher 时复用已刷新的 token)。 */
  private val session = entry.session
  private val poTokenState = entry.poTokenState

  /** 已初始化格式(itag → InitializedFormat)。FORMAT_INITIALIZATION_METADATA part 建表。 */
  private val initializedFormats = mutableMapOf<Int, InitializedFormat>()
  /** 正在处理的 partial 段(headerId → Segment,MEDIA 累积/MEDIA_END 收尾)。 */
  private val partialSegments = mutableMapOf<Int, SabrSegment>()

  /** 当前选中的音频/视频 FormatId(由 [SabrMediaPeriod.selectTracks]→[selectFormat] 设)。 */
  private var audioFormat: FormatId? = null
  private var videoFormat: FormatId? = null

  /** 请求序号(每次 POST 追加 &rn=,AtomicInt 防 A/V 两 loader 并发)。 */
  private val requestNumber = AtomicInteger(0)
  /** 上次请求墙钟(epoch ms,0=首次),算 elapsedWallTimeMs。 */
  private val lastRequestMs = AtomicLong(0L)

  /** 服务端 NextRequestPolicy 回传的 backoff(ms),下次 fetchStreamData 起始 sleep。 */
  @Volatile private var backoffTime: Int? = null
  /** SABR_REDIRECT 给的新 url(写回 session.sabrUrl)。 */
  @Volatile private var redirectUrl: String? = null
  @Volatile private var invalidPo = false
  @Volatile private var fatalError: String? = null
  @Volatile private var reloadPlayerDump: String? = null
  /**
   * alpha.67(对齐 LibreTube status=2 同步刷新):processPart 里 status=2 置位,media() 的 readParts
   * 之后同步重铸 PO token(阻塞 loader 线程 ~1s)。下个请求一定带新 token → status=3 不再出现。
   * 异步(alpha.66 maybeRefreshPoToken)有竞态:刷新晚一拍,请求带旧 token 撞 status=3 → 全量重载。
   */
  @Volatile private var needsPoTokenRefresh = false

  /** selectTracks 时间戳(由 [SabrMediaPeriod] 写,ClientAbrState timeSinceLast* 用)。 */
  @Volatile var lastSeekMs: Long? = null
  @Volatile var lastManualFormatSelectionMs: Long? = null
  @Volatile var lastActionMs: Long? = null

  private val dispatcher = Dispatchers.IO.limitedParallelism(1)

  /**
   * 由 [SabrMediaPeriod] 选轨时调用,按 mimeType 分 audio/video(对齐 LibreTube selectFormat)。
   * 重复设同 Representation 直接返回(幂等)。
   */
  fun selectFormat(representation: Representation) {
    val mime = representation.format.containerMimeType
    if (MimeTypes.isAudio(mime)) {
      if (audioFormat != representation.formatId) audioFormat = representation.formatId
    } else if (MimeTypes.isVideo(mime)) {
      if (videoFormat != representation.formatId) videoFormat = representation.formatId
    }
  }

  /** 末段序号([SabrChunkSource] 判 endOfStream 用)。 */
  fun getEndSegmentNumber(formatItag: Int): Long? = initializedFormats[formatItag]?.endSegmentNumber

  /**
   * 取 [req] 指定段。若该格式未初始化或该段未下载 → 调 [media] POST 一批(单流多段),
   * 然后从 [initializedFormats] 取段。终端错误(RELOAD_PLAYER/InvalidPoToken/SABR_ERROR)抛
   * [SabrTerminalException];transient(backoff/redirect/段未到)在 [MAX_ATTEMPTS] 内重试。
   */
  fun getNextSegment(req: SabrSegmentRequest): SabrSegment {
    fatalError?.let { throw SabrTerminalException("SABR error: $it") }
    val itag = req.formatItag
    return runBlocking {
      withContext(dispatcher) {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
          attempt++
          // 同步 bufferedSegments:按 req.bufferedSegments 保留(清除已不缓冲的,防内存泄漏)
          initializedFormats[itag]?.bufferedSegments?.keys?.retainAll(req.bufferedSegments)

          var fmt = initializedFormats[itag]
          if (fmt == null || !fmt.hasSegment(req.segment)) {
            // 已下载但播放器不再要的段(seek 后)清掉,防泄漏
            fmt?.downloadedSegments?.clear()
            // 单流 POST——服务端推多段,缓存进 initializedFormats
            media(req)
          }
          // 终端检查(media 可能置位)
          reloadPlayerDump?.let { throw SabrTerminalException("RELOAD_PLAYER_RESPONSE: $it") }
          if (invalidPo) throw SabrTerminalException("InvalidPoToken (StreamProtectionStatus status=3)")
          fatalError?.let { throw SabrTerminalException("SABR error: $it") }

          fmt = initializedFormats[itag]
          val seg = fmt?.getSegment(req.segment)
          if (seg != null) return@withContext seg
          // transient:段未到(服务端只回了 context+backoff 或 redirect)。backoff 已在 media 起始 sleep,
          // redirect 已写回 session.sabrUrl。循环重试同请求。
          Log.i(tag, "getNextSegment: no seg ${req.segment} itag $itag after attempt $attempt (retry)")
        }
        throw SabrTerminalException("exhausted $MAX_ATTEMPTS attempts for seg ${req.segment} itag $itag")
      }
    }
  }

  /**
   * 发一次单流 POST + 处理 UMP 响应(对齐 LibreTube media + processUmpStream)。
   * backoff 起始 sleep(封顶 2.5s);redirect 写回 session;cookie/contexts 写回 session。
   */
  private suspend fun media(req: SabrSegmentRequest) {
    val data = fetchStreamData(req)
    val ump = UmpReader()
    ump.append(data)
    ump.readParts { type, payload -> processPart(type, payload) }
    // alpha.67(对齐 LibreTube processPart status==2 `poToken = generatePoToken()` 同步):status=2 在
    // 本响应里出现 → 此处(下个请求发出前)同步重铸 PO token,下个请求一定带新 token → status=3 不再出现。
    // 异步(alpha.66 maybeRefreshPoToken 后台 launch)有竞态:刷新需 ~550ms,下一段请求在此之前发出带旧 token
    // → 撞 status=3 → evict → onPlayerError 全量重载 → 重播前 60s + 音频先出现。看门狗已取消(改动 3),
    // 同步阻塞 loader 线程 ~1s 安全(前方 ~20s lookahead 缓冲兜底,LibreTube 同栈同做法)。
    if (needsPoTokenRefresh) {
      needsPoTokenRefresh = false
      val fresh = entry.refreshPoToken?.invoke()
      if (fresh != null && fresh.isNotEmpty()) {
        entry.poTokenState.currentPoToken = fresh
        Log.i(tag, "PO token refreshed on status=2: ${fresh.size}B (sync) → next request uses fresh token")
      } else {
        Log.w(tag, "PO token refresh null/empty on status=2 (refreshPoToken=${entry.refreshPoToken != null}) — keep stale")
      }
    }
  }

  /**
   * 构造 VideoPlaybackAbrRequest 单流请求体并 POST(对齐 LibreTube fetchStreamData)。
   * 关键差异(修 60s 断崖):bitfield=0(videoFormat 存在时 A+V)/ selectedFormatIds=全部已初始化格式 /
   * bufferedRanges=全部真实 buildBufferedRanges(无 Int.MAX)。
   */
  private suspend fun fetchStreamData(req: SabrSegmentRequest): ByteArray {
    backoffTime?.let { backoff ->
      val sleep = min(backoff.toLong(), MAX_BACKOFF_SLEEP_MS)
      Log.i(tag, "fetchStreamData: sleeping backoff $backoff ms (capped $sleep) before request")
      delay(sleep)
      backoffTime = null
    }

    val now = System.currentTimeMillis()
    val lastMs = lastRequestMs.get()
    val elapsed = if (lastMs > 0L) (now - lastMs).coerceAtLeast(0L) else 0L

    val playerTimeMs = req.segmentStartTimeMs
    val selected = initializedFormats.values.map { SabrProto.encodeFormatId(it.id.itag, it.id.lastModified, it.id.xtags) }
    val bufferedRanges = initializedFormats.values.flatMap { it.buildBufferedRanges() }
    val audioEnc = audioFormat?.let { SabrProto.encodeFormatId(it.itag, it.lastModified, it.xtags) }
    val videoEnc = videoFormat?.let { SabrProto.encodeFormatId(it.itag, it.lastModified, it.xtags) }
    val (activeCtxs, unsentCtxTypes) = session.prepareSabrContexts()
    val streamerContext = StreamerContextInput(
      clientInfo = session.clientInfo,
      poToken = poTokenState.currentPoToken,
      playbackCookie = session.playbackCookie,
      sabrContexts = activeCtxs,
      unsentSabrContexts = unsentCtxTypes,
    )
    val vHeight = videoFormat?.height ?: 0
    // alpha.16(对齐 LibreTube setAudioTrackId):当前选中音轨 id(audioTrack.id,如 "en.4")。
    // 按 itag 命中 session.audioTracks 里当前音频格式那条;单音轨视频 resolver 折叠成 "default"(audioTrackId
    // 为 null),对齐 LibreTube 发空串("" 而非 "default")——多音轨视频(如 jNl6YkkzKxw 5 音轨)发真实 id。
    val audioTrackId = session.audioTracks.firstOrNull { it.formatId.itag == audioFormat?.itag }?.id
      ?.takeIf { it != "default" } ?: ""
    val clientAbrState = ClientAbrStateInput(
      timeSinceLastManualFormatSelectionMs = lastManualFormatSelectionMs?.let { now - it } ?: 0L,
      lastManualSelectedResolution = max(vHeight, 360),
      clientViewportWidth = 640,
      clientViewportHeight = max(vHeight, 360),
      stickyResolution = max(vHeight, 360),
      clientViewportIsFlexible = false,
      bandwidthEstimate = 0L,
      // 请求体 playerTimeMs = 段起点(对齐 LibreTube setPlayerTimeMs(segmentStartTimeMs))
      playerTimeMs = playerTimeMs,
      timeSinceLastSeekMs = lastSeekMs?.let { now - it } ?: 0L,
      visibility = 1,
      playbackRate = req.playbackSpeed,
      elapsedWallTimeMs = elapsed,
      timeSinceLastActionMs = lastActionMs?.let { now - it } ?: 0L,
      // alpha.64 单流:bitfield=0(A+V,videoFormat 存在时)。alpha.63 双流用 2(VIDEO_ONLY)/1(AUDIO_ONLY)
      // 是 60s 断崖根因——服务端双流模型在 ~60s 边界软拒。单流 bitfield=0 对齐 LibreTube。
      enabledTrackTypesBitfield = if (videoFormat == null) 1 else 0,
      drcEnabled = false,
      enableVoiceBoost = false,
      audioTrackId = audioTrackId,
    )
    val input = SabrRequestInput(
      clientAbrState = clientAbrState,
      selectedFormatIds = selected,
      bufferedRanges = bufferedRanges,
      playerTimeMs = playerTimeMs,
      videoPlaybackUstreamerConfig = session.ustreamerConfig,
      preferredAudioFormatIds = listOfNotNull(audioEnc),
      preferredVideoFormatIds = listOfNotNull(videoEnc),
      preferredSubtitleFormatIds = emptyList(),
      streamerContext = streamerContext,
    )
    val body = SabrProto.encodeVideoPlaybackAbrRequest(input)
    val rn = requestNumber.getAndIncrement()
    lastRequestMs.set(now)
    val url = "${session.sabrUrl}&rn=$rn"
    Log.i(tag, "fetch rn=$rn itag=${req.formatItag} seg=${req.segment} playerTimeMs=$playerTimeMs bitfield=${if (videoFormat == null) 1 else 0} selectedFmts=${selected.size} bufferedRanges=${bufferedRanges.size} pot=${poTokenState.currentPoToken.size}B cookie=${session.playbackCookie != null && session.playbackCookie!!.isNotEmpty()} contexts=${activeCtxs.size}/${unsentCtxTypes.size} body=${body.size}B")

    val request = Request.Builder()
      .url(url)
      .post(body.toRequestBody("application/x-protobuf".toMediaType()))
      .header("accept-encoding", "identity")
      .header("accept", "application/vnd.yt-ump")
      .header("User-Agent", session.userAgent)
      // alpha.79:cookie/visitor 可空——空串=不带(对齐 LibreTube 无 HTTP cookie,靠 protobuf)。非空才带。
      .apply {
        if (session.cookieHeader.isNotBlank()) header("Cookie", session.cookieHeader)
        if (session.visitorData.isNotBlank()) header("X-Goog-Visitor-Id", session.visitorData)
      }
      .header("Origin", "https://www.youtube.com")
      .header("Referer", "https://www.youtube.com/")
      .build()
    return try {
      httpClient.newCall(request).execute().use { response ->
        val code = response.code
        if (code != 200) {
          val hdrs = response.headers.joinToString("; ") { "${it.first}=${it.second.take(80)}" }
          Log.w(tag, "fetch rn=$rn HTTP $code headers=[$hdrs] body=${response.body?.string()?.take(200)}")
          throw IOException("SABR HTTP $code")
        }
        response.body?.bytes() ?: throw IOException("SABR empty body")
      }
    } catch (e: SabrTerminalException) {
      throw e
    } catch (e: Exception) {
      Log.w(tag, "fetch rn=$rn exception: ${e.message}")
      throw e
    }
  }

  /** 处理一个 UMP part(对齐 LibreTube processPart)。 */
  private fun processPart(type: Int, payload: ByteArray) {
    when (type) {
      PART_MEDIA_HEADER -> {
        val mh = SabrProto.decodeMediaHeader(payload)
        if (mh == null) {
          Log.w(tag, "MEDIA_HEADER decode failed payloadLen=${payload.size}")
          return
        }
        if (mh.headerId == 0 && mh.itag == 0) {
          Log.w(tag, "MEDIA_HEADER empty (headerId=0 itag=0) payloadLen=${payload.size}")
          return
        }
        // alpha.71 广告防御层:跳过非白名单 itag 的 MEDIA_HEADER(广告段)。不入 partialSegments
        // → 其后续 MEDIA/MEDIA_END 按 headerId 找不到段自动丢弃。
        val hdrWhitelist = setOfNotNull(audioFormat?.itag, videoFormat?.itag)
        if (hdrWhitelist.isNotEmpty() && mh.itag !in hdrWhitelist) {
          Log.w(tag, "skip ad/unrequested MEDIA_HEADER itag=${mh.itag} headerId=${mh.headerId} (whitelist=$hdrWhitelist)")
          return
        }
        val duration = mh.durationMs
        if (partialSegments.containsKey(mh.headerId)) {
          Log.w(tag, "MEDIA_HEADER duplicate headerId=${mh.headerId} (overwrite)")
        }
        Log.i(tag, "MEDIA_HEADER headerId=${mh.headerId} itag=${mh.itag} seq=${mh.sequenceNumber} isInit=${mh.isInitSeg} startMs=${mh.startMs} dur=${duration}ms contentLen=${mh.contentLength}")
        partialSegments[mh.headerId] = SabrSegment(
          header = mh,
          sequenceNumber = mh.sequenceNumber.toLong(),
          data = mutableListOf(),
          duration = duration,
        )
      }
      PART_MEDIA -> {
        // payload = [headerId varint][media bytes]。headerId 是 UMP 自定义 varint(首字节<128 时单字节)。
        val (headerId, hdrLen) = readUmpVarint(payload, 0) ?: return
        val seg = partialSegments[headerId] ?: return
        seg.data.add(if (hdrLen == 0) payload else payload.copyOfRange(hdrLen, payload.size))
      }
      PART_MEDIA_END -> {
        val (headerId, _) = readUmpVarint(payload, 0) ?: return
        val seg = partialSegments.remove(headerId) ?: return
        val fmt = initializedFormats[seg.header.itag]
        if (fmt == null) {
          Log.w(tag, "MEDIA_END headerId=$headerId itag=${seg.header.itag} no InitializedFormat (dropped)")
          return
        }
        Log.i(tag, "MEDIA_END headerId=$headerId itag=${seg.header.itag} seq=${seg.sequenceNumber} chunks=${seg.data.size} bytes=${seg.length()}")
        fmt.downloadedSegments[seg.sequenceNumber] = seg
        if (seg.header.isInitSeg) fmt.initSegment = seg
      }
      PART_NEXT_REQUEST_POLICY -> {
        val policy = SabrProto.decodeNextRequestPolicy(payload)
        backoffTime = policy?.backoffTimeMs
        val cookieBytes = policy?.playbackCookieBytes
        val hasCookie = cookieBytes?.isNotEmpty() == true
        if (hasCookie) session.playbackCookie = cookieBytes
        Log.i(tag, "NEXT_REQUEST_POLICY backoff=${policy?.backoffTimeMs}ms cookie=$hasCookie maxTimeSinceReq=${policy?.maxTimeSinceLastRequestMs}")
      }
      PART_FORMAT_INITIALIZATION_METADATA -> {
        val fi = SabrProto.decodeFormatInitializationMetadata(payload)
        if (fi == null || fi.itag == 0) {
          Log.w(tag, "FORMAT_INITIALIZATION_METADATA decode failed/empty payloadLen=${payload.size}")
          return
        }
        if (initializedFormats.containsKey(fi.itag)) {
          Log.i(tag, "FORMAT_INITIALIZATION_METADATA itag=${fi.itag} already initialized (skip)")
          return
        }
        // alpha.71 广告防御层:只接受本会话请求的音视频 itag,跳过服务端注入的广告/未请求格式。
        // visionOS 客户端(path C)通常不注入广告,但作安全网:广告格式不入表 → 其 MEDIA 段
        // 在 partialSegments 找不到 headerId → MEDIA/MEDIA_END 自动丢弃 → 不喂解码器。
        // 白名单空(audioFormat/videoFormat 均未设)时不过滤,避免误杀。
        val whitelistedItags = setOfNotNull(audioFormat?.itag, videoFormat?.itag)
        if (whitelistedItags.isNotEmpty() && fi.itag !in whitelistedItags) {
          Log.w(tag, "skip ad/unrequested FORMAT_INIT itag=${fi.itag} (whitelist=$whitelistedItags)")
          return
        }
        Log.i(tag, "FORMAT_INITIALIZATION_METADATA itag=${fi.itag} endSegNum=${fi.endSegmentNumber} duration=${fi.endTimeMs}ms")
        initializedFormats[fi.itag] = InitializedFormat(
          id = FormatId(fi.itag, fi.lastModified, fi.xtags, 0),
          endSegmentNumber = fi.endSegmentNumber,
          duration = fi.endTimeMs,
        )
      }
      PART_SABR_REDIRECT -> {
        val url = SabrProto.decodeSabrRedirect(payload)
        redirectUrl = url
        if (url != null) session.applyRedirect(url)
        Log.i(tag, "SABR_REDIRECT -> ${url?.take(80)}")
      }
      PART_SABR_CONTEXT_UPDATE -> {
        val u = SabrProto.decodeSabrContextUpdate(payload)
        if (u != null && u.type != 0 && u.value.isNotEmpty()) {
          val keepExisting = u.writePolicy == 2 && session.sabrContexts.containsKey(u.type)
          if (!keepExisting) {
            session.sabrContexts[u.type] = u.value
            if (u.sendByDefault) session.activeSabrContextTypes.add(u.type)
          }
          Log.i(tag, "SABR_CONTEXT_UPDATE type=${u.type} valLen=${u.value.size} sendByDefault=${u.sendByDefault} writePolicy=${u.writePolicy} ctxs=${session.sabrContexts.size} active=${session.activeSabrContextTypes.size}")
        }
      }
      PART_SABR_CONTEXT_SENDING_POLICY -> {
        val p = SabrProto.decodeSabrContextSendingPolicy(payload)
        if (p != null) {
          p.start.forEach { session.activeSabrContextTypes.add(it) }
          p.stop.forEach { session.activeSabrContextTypes.remove(it) }
          p.discard.forEach { session.sabrContexts.remove(it) }
          Log.i(tag, "SABR_CONTEXT_SENDING_POLICY start=${p.start} stop=${p.stop} discard=${p.discard}")
        }
      }
      PART_STREAM_PROTECTION_STATUS -> {
        val status = SabrProto.decodeStreamProtectionStatus(payload)
        Log.w(tag, "STREAM_PROTECTION_STATUS status=$status")
        if (status == 3) invalidPo = true
        // alpha.67(对齐 LibreTube processPart status==2 `poToken = generatePoToken()` 同步):
        // status=2(Attestation pending)= 服务端预警 → media() 的 readParts 后同步重铸 PO token
        // (阻塞 loader 线程 ~1s,看门狗已取消无 8s cancel 风险)。下个请求一定带新 token → status=3
        // 不再出现。异步(alpha.66 maybeRefreshPoToken)有竞态:刷新晚一拍撞 status=3 → 全量重载。
        else if (status == 2) needsPoTokenRefresh = true
      }
      PART_RELOAD_PLAYER_RESPONSE -> {
        // Phase 1(diag):结构化解析。reloadToken = ReloadPlaybackParams.token(整串 base64),
        // 是服务端下发的 reload 凭证,Phase 2 需原样回传进新 /player 的 playbackContext.reloadPlaybackContext。
        // 不改播放行为。真机看 reloadToken 是否稳定/含 videoId,为 Phase 2 定回传逻辑。
        val info = SabrProto.decodeReloadPlayer(payload)
        reloadPlayerDump = "videoId=${info.videoId} reloadTokenLen=${info.reloadToken?.length} " +
          "reloadTokenDecodedHex=${info.reloadTokenDecodedHex} innerToken=\"${info.innerToken}\" " +
          "innerTokenDecodedHex=${info.innerTokenDecodedHex} field7Hex=${info.field7Hex} " +
          "potSent=${poTokenState.currentPoToken.size}B fields={${info.fieldsSummary}}"
        Log.w(tag, "RELOAD_PLAYER_RESPONSE $reloadPlayerDump")
        // Phase 2(取证):成功 decode 时把 reloadToken 停车到进程级 registry(独立于 sessions,evict 不清),
        // 供 resolve() 下次重进 consumeReloadToken 取走去重打 visionOS /player。仅当 token 非空且
        // videoId 可解出才存(成功 decode 恒带 videoId;decode 失败时 token 也 null,自然跳过)。
        val rt = info.reloadToken
        val vid = info.videoId
        if (vid != null && !rt.isNullOrBlank()) {
          SabrStreamRegistry.storeReloadToken(vid, rt)
        }
      }
      PART_SABR_ERROR -> {
        val err = SabrProto.decodeSabrError(payload)
        fatalError = "SABR Error type=${err?.type} code=${err?.code}"
        Log.w(tag, "SABR_ERROR type=${err?.type} code=${err?.code}")
      }
      else -> {
        Log.i(tag, "part type=$type payloadLen=${payload.size} (unhandled)")
      }
    }
  }

  /**
   * 读 UMP 自定义 varint(YouTube 格式,首字节高位判字节数,LE)。MEDIA/MEDIA_END part 的 headerId 用此编码。
   * 复用 [UmpReader] 同款规则;这里从 ByteArray offset 读。@return (value, byteLength) 或 null(字节不足)。
   */
  private fun readUmpVarint(data: ByteArray, offset: Int): Pair<Int, Int>? {
    if (offset >= data.size) return null
    val b0 = data[offset].toInt() and 0xFF
    val byteLength = when {
      b0 < 0x80 -> 1
      b0 < 0xC0 -> 2
      b0 < 0xE0 -> 3
      b0 < 0xF0 -> 4
      else -> 5
    }
    if (offset + byteLength > data.size) return null
    val value = when (byteLength) {
      1 -> b0
      2 -> (b0 and 0x3F) + 64 * (data[offset + 1].toInt() and 0xFF)
      3 -> (b0 and 0x1F) + 32 * (
        (data[offset + 1].toInt() and 0xFF) + 256 * (data[offset + 2].toInt() and 0xFF)
      )
      4 -> (b0 and 0x0F) + 16 * (
        (data[offset + 1].toInt() and 0xFF) + 256 * ((data[offset + 2].toInt() and 0xFF) + 256 * (data[offset + 3].toInt() and 0xFF))
      )
      else -> (data[offset + 1].toInt() and 0xFF) + 256 * (
        (data[offset + 2].toInt() and 0xFF) + 256 * ((data[offset + 3].toInt() and 0xFF) + 256 * (data[offset + 4].toInt() and 0xFF))
      )
    }
    return value to byteLength
  }

  private companion object {
    /** transient 重试上限(耗尽→SabrTerminalException→evict)。对齐旧 SabrDashDataSource BACKOFF_MAX_ATTEMPTS。 */
    const val MAX_ATTEMPTS = 6
    /** 单次 backoff 最大 sleep(ms,<8s stall watchdog)。对齐旧 MAX_BACKOFF_SLEEP_MS。 */
    const val MAX_BACKOFF_SLEEP_MS = 2_500L
  }
}

/**
 * 终端错误——RELOAD_PLAYER_RESPONSE / InvalidPoToken / SABR_ERROR / 重试耗尽。
 * [SabrDataSource.open] 捕获 → [com.kirin.mt.core.youtube.sabr.SabrStreamRegistry.evict] → 播放器 error-retry 重 harvest。
 */
internal class SabrTerminalException(message: String) : Exception(message)
