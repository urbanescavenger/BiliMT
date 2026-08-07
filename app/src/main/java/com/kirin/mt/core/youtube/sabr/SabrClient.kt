package com.kirin.mt.core.youtube.sabr

import android.util.Base64
import android.util.Log
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_MEDIA
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_MEDIA_END
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_MEDIA_HEADER
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_NEXT_REQUEST_POLICY
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_CONTEXT_SENDING_POLICY
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_CONTEXT_UPDATE
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_ERROR
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_REDIRECT
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_STREAM_PROTECTION_STATUS
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * SABR 协议会话——一次 YouTube 视频播放对应一个 [SabrSession],持有跨请求不变的状态。
 *
 * 对齐 FreeTube `SabrData`(Watch.js createLocalSabrManifest):
 *  - [sabrUrl] = serverAbrStreamingUrl + 已附 `?alr=yes&cpn=<cpn>`(FreeTube L1619-1620)
 *  - [poToken]/[ustreamerConfig] = base64 decode 后的 bytes(分别填 StreamerContext.field2
 *    与 VideoPlaybackAbrRequest.field5)
 *  - [clientInfo] = StreamerContext.ClientInfo(对齐 InnerTube context)
 *  - [audioFormatId]/[videoFormatId] = adaptive 元数据的 itag/lastModified/xtags
 */
internal data class SabrSession(
  /**
   * SABR 基址 + 已附 `?alr=yes&cpn=<cpn>`(SabrClient.fetch 再追加 `&rn=<rn>`)。
   * `var`:SABR_REDIRECT 会换新 sabrUrl,[applyRedirect] 写回(重加 alr+cpn)。
   */
  var sabrUrl: String,
  val poToken: ByteArray,
  val ustreamerConfig: ByteArray,
  val clientInfo: ClientInfoInput,
  val audioFormatId: FormatId,
  val videoFormatId: FormatId,
  /** 会话传输头——与 /player 同会话(对齐 FreeTube 走浏览器/shaka fetch 自动带 cookie+UA+visitor)。 */
  val userAgent: String,
  val cookieHeader: String,
  val visitorData: String,
  /** 会话 cpn——Redirect 重写 sabrUrl 时需重新追加 `cpn=`(会话绑定)。 */
  val cpn: String,
) {
  /**
   * SABR_REDIRECT 给的新 sabrUrl 可能是 base(无 alr/cpn)→ 重加 alr+cpn 写回 [sabrUrl]。
   * 复用 [Companion.sabrUrlWithParams](对齐 fromSabrData 的拼接,只在无 `?`/无 alr 时补)。
   */
  fun applyRedirect(newBaseSabrUrl: String) {
    sabrUrl = sabrUrlWithParams(newBaseSabrUrl, cpn)
  }

  companion object {
    private val tag = "YtSabr"
    fun fromSabrData(
      sabrUrl: String,
      poTokenB64: String,
      ustreamerConfigB64: String,
      clientInfo: ClientInfoInput,
      audioFormatId: FormatId,
      videoFormatId: FormatId,
      userAgent: String,
      cookieHeader: String,
      visitorData: String,
      /** 会话 cpn——alpha.26 harvest 路径须传浏览器原 cpn(绑定 body 的 poToken/ustreamerConfig 会话);
       * null 时随机生成(classic /player 路径)。 */
      cpn: String? = null,
    ): SabrSession {
      // sabrUrl 加 alr=yes + cpn(对齐 FreeTube Watch.js L1619-1620 + SabrSchemePlugin 追加 rn)。cpn = 16 随机字节 base64url
      val usedCpn = cpn ?: randomCpn()
      val withParams = sabrUrlWithParams(sabrUrl, usedCpn)
      val po = Base64.decode(poTokenB64, Base64.DEFAULT)
      val ustreamer = Base64.decode(ustreamerConfigB64, Base64.DEFAULT)
      Log.i(tag, "SabrSession: sabrUrl=${withParams.take(200)}... poToken=${po.size}B ustreamerCfg=${ustreamer.size}B cpn=$usedCpn audio=$audioFormatId video=$videoFormatId ua=${userAgent.take(40)} cookie=${cookieHeader.length}B visitor=${visitorData.length}B")
      return SabrSession(withParams, po, ustreamer, clientInfo, audioFormatId, videoFormatId, userAgent, cookieHeader, visitorData, usedCpn)
    }

    /** 16 字节随机 → base64url 无 padding(对齐 youtubei.js generateRandomString 16 位 cpn)。 */
    private fun randomCpn(): String {
      val bytes = ByteArray(16)
      SecureRandom().nextBytes(bytes)
      return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun sabrUrlWithParams(url: String, cpn: String): String {
      val sep = if (url.contains("?")) "&" else "?"
      // alr=yes 让服务端返回 adaptive 重定向策略;cpn 绑定会话
      return "$url${sep}alr=yes&cpn=$cpn"
    }
  }
}

internal enum class SabrStreamType { AUDIO, VIDEO }

/** 一次 SABR 段请求(init 或 media)。 */
internal data class SabrFetchRequest(
  val isInit: Boolean,
  val sequenceNumber: Int = 0,
  val streamType: SabrStreamType,
)

internal sealed class SabrFetchResult {
  /** 段字节已收齐(MEDIA_END)+ 匹配到的 MediaHeader(含 contentLength/duration 等)。 */
  data class Success(val data: ByteArray, val mediaHeader: SabrProto.MediaHeader?) : SabrFetchResult()
  /** 服务端要求重定向到新 url(用新 sabrUrl 重试)。 */
  data class Redirect(val newSabrUrl: String, val sanitized: String) : SabrFetchResult()
  /** 服务端要求 backoff,重试同一请求。 */
  data class Backoff(val ms: Int) : SabrFetchResult()
  /** STREAM_PROTECTION_STATUS status==3 → PO token 无效。 */
  object InvalidPoToken : SabrFetchResult()
  data class Error(val message: String) : SabrFetchResult()
}

/**
 * SABR 协议引擎:POST VideoPlaybackAbrRequest → 流式解析 UMP 响应 → 提取匹配的 MEDIA 段字节。
 *
 * 对齐 FreeTube `SabrSchemePlugin.doRequest`(SabrSchemePlugin.js L285-614):每次段请求构造
 * `VideoPlaybackAbrRequest`(clientAbrState + preferred/selected formatIds + streamerContext + field5
 * ustreamerConfig),POST 到 `sabrUrl?rn=<requestNumber>`,headers 用
 * `content-type:application/x-protobuf / accept-encoding:identity / accept:application/vnd.yt-ump`,
 * 响应是 UMP 流容器,逐 part 处理(MEDIA_HEADER 匹配 formatId → MEDIA 收字节 → MEDIA_END 收尾)。
 */
internal class SabrClient(private val httpClient: OkHttpClient) {
  private val tag = "YtSabr"
  // alpha.27:SabrClient 现在在一个 SABR 播放会话内被 video+audio 两路 loader 线程并发调用
  //(MergingMediaSource 双 ProgressiveMediaSource),requestNumber 必须线程安全,否则 rn 重复
  //→服务端可能拒签。AtomicInt 保证每次 fetch 拿唯一 rn。
  private val requestNumber = AtomicInteger(0)

  /**
   * 发一次 SABR 段请求。[session] 的 sabrUrl 可能因 Redirect 变化(调用方把新 url 写回 session 再重试)。
   */
  suspend fun fetch(session: SabrSession, req: SabrFetchRequest): SabrFetchResult {
    val audioEnc = SabrProto.encodeFormatId(session.audioFormatId.itag, session.audioFormatId.lastModified, session.audioFormatId.xtags)
    val videoEnc = SabrProto.encodeFormatId(session.videoFormatId.itag, session.videoFormatId.lastModified, session.videoFormatId.xtags)
    val selected = if (req.isInit) emptyList() else listOf(audioEnc, videoEnc)
    val streamerContext = StreamerContextInput(
      clientInfo = session.clientInfo,
      poToken = session.poToken,
      sabrContexts = emptyList(),
      unsentSabrContexts = emptyList(),
    )
    val resolution = session.videoFormatId.height.takeIf { it > 0 }
    val clientAbrState = ClientAbrStateInput(
      timeSinceLastManualFormatSelectionMs = if (req.streamType == SabrStreamType.VIDEO) 0L else null,
      lastManualSelectedResolution = resolution,
      clientViewportWidth = 1920,
      clientViewportHeight = 1080,
      stickyResolution = resolution,
      clientViewportIsFlexible = false,
      bandwidthEstimate = 0L,
      playerTimeMs = 0L,
      playbackRate = 1.0f,
      // 对齐 googlevideo EnabledTrackTypes(AUDIO_ONLY=1 / VIDEO_ONLY=2 / VIDEO_AND_AUDIO=0)——
      // createVideoPlaybackAbrRequest 按 currentFormat.width 取 VIDEO_ONLY/AUDIO_ONLY。alpha.18 误用
      // 0(VIDEO_AND_AUDIO)致视频 init 请求声明要音视频双轨,虽非 403 主因但属语义错(§6.7 row 41)。
      enabledTrackTypesBitfield = if (req.streamType == SabrStreamType.AUDIO) 1 else 2,
      drcEnabled = false,
      enableVoiceBoost = false,
    )
    val input = SabrRequestInput(
      clientAbrState = clientAbrState,
      selectedFormatIds = selected,
      bufferedRanges = emptyList(),
      playerTimeMs = 0L,
      videoPlaybackUstreamerConfig = session.ustreamerConfig,
      preferredAudioFormatIds = listOf(audioEnc),
      preferredVideoFormatIds = listOf(videoEnc),
      preferredSubtitleFormatIds = emptyList(),
      streamerContext = streamerContext,
    )
    val body = SabrProto.encodeVideoPlaybackAbrRequest(input)
    val rn = requestNumber.getAndIncrement()
    val url = "${session.sabrUrl}&rn=$rn"
    Log.i(tag, "fetch rn=$rn isInit=${req.isInit} stream=${req.streamType} seq=${req.sequenceNumber} body=${body.size}B")

    return try {
      val request = Request.Builder()
        .url(url)
        .post(body.toRequestBody("application/x-protobuf".toMediaType()))
        .header("accept-encoding", "identity")
        .header("accept", "application/vnd.yt-ump")
        // 会话传输头(对齐 /player 同会话:UA + Cookie + X-Goog-Visitor-Id)——googlevideo SABR 端点
        // 拒绝无会话绑定的裸请求(alpha.17 实测 HTTP 403 空响应体)。
        .header("User-Agent", session.userAgent)
        .header("Cookie", session.cookieHeader)
        .header("X-Goog-Visitor-Id", session.visitorData)
        .header("Origin", "https://www.youtube.com")
        .header("Referer", "https://www.youtube.com/")
        .build()
      httpClient.newCall(request).execute().use { response ->
        val code = response.code
        if (code != 200) {
          // 全量响应头 + 体——googlevideo 403 空体时,header 里常带 x-gxxx 错误码定位真因。
          val hdrs = response.headers.joinToString("; ") { "${it.first}=${it.second.take(80)}" }
          Log.w(tag, "fetch rn=$rn HTTP $code headers=[$hdrs] body=${response.body?.string()?.take(200)}")
          return SabrFetchResult.Error("SABR HTTP $code")
        }
        processUmpStream(response, session, req)
      }
    } catch (e: Exception) {
      Log.w(tag, "fetch rn=$rn exception", e)
      SabrFetchResult.Error("SABR fetch exception: ${e.message}")
    }
  }

  /** 流式读响应体喂 [UmpReader],逐 part 处理,提取匹配的 MEDIA 段。 */
  private fun processUmpStream(
    response: okhttp3.Response,
    session: SabrSession,
    req: SabrFetchRequest,
  ): SabrFetchResult {
    val ump = UmpReader()
    var matchedHeaderId: Int? = null
    var matchedHeader: SabrProto.MediaHeader? = null
    val mediaChunks = ArrayList<ByteArray>()
    var finished = false
    var invalidPo = false
    var backoffMs: Int? = null
    var redirectUrl: String? = null
    var errorMsg: String? = null

    response.body?.byteStream()?.use { stream ->
      val buf = ByteArray(64 * 1024)
      while (!finished) {
        val n = stream.read(buf)
        if (n < 0) break
        if (n == 0) continue
        ump.append(if (n == buf.size) buf else buf.copyOfRange(0, n))
        ump.readParts { type, payload ->
          when (type) {
            PART_MEDIA_HEADER -> {
              // 即使不匹配也 dump——拿服务端真实 itag/lmt/xtags/isInit/seq,对比我们发出去的,
              // 定位 proto 字段号错位(§6.9)。首版不依赖匹配成功,关键是看服务端回了什么。
              val mh = SabrProto.decodeMediaHeader(payload)
              if (mh == null) {
                Log.w(tag, "MEDIA_HEADER decode failed payloadLen=${payload.size}")
              } else if (matchedHeaderId == null) {
                val matched = matchesFormat(mh, session, req)
                val wanted = if (req.streamType == SabrStreamType.AUDIO) session.audioFormatId else session.videoFormatId
                Log.i(tag, "MEDIA_HEADER headerId=${mh.headerId} itag=${mh.itag} lmt=${mh.lmt} xtags=${mh.xtags} isInit=${mh.isInitSeg} seq=${mh.sequenceNumber} contentLen=${mh.contentLength} dur=${mh.durationMs}ms | matched=$matched (wanted itag=${wanted.itag} lmt=${wanted.lastModified} xtags=${wanted.xtags})")
                if (matched) {
                  matchedHeaderId = mh.headerId
                  matchedHeader = mh
                }
              }
            }
            PART_MEDIA -> {
              if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == matchedHeaderId) {
                mediaChunks.add(payload.copyOfRange(1, payload.size))
              }
            }
            PART_MEDIA_END -> {
              if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == matchedHeaderId) {
                finished = true
                Log.i(tag, "MEDIA_END headerId=$matchedHeaderId chunks=${mediaChunks.size} bytes=${mediaChunks.sumOf { it.size }}")
              }
            }
            PART_STREAM_PROTECTION_STATUS -> {
              val status = SabrProto.decodeStreamProtectionStatus(payload)
              Log.w(tag, "STREAM_PROTECTION_STATUS status=$status")
              if (status == 3) invalidPo = true
            }
            PART_SABR_REDIRECT -> {
              redirectUrl = SabrProto.decodeSabrRedirect(payload)
              Log.i(tag, "SABR_REDIRECT -> ${redirectUrl?.take(80)}")
            }
            PART_NEXT_REQUEST_POLICY -> {
              val policy = SabrProto.decodeNextRequestPolicy(payload)
              backoffMs = policy?.backoffTimeMs
              Log.i(tag, "NEXT_REQUEST_POLICY backoff=${policy?.backoffTimeMs}ms cookie=${policy?.playbackCookie != null}")
            }
            PART_SABR_ERROR -> {
              val err = SabrProto.decodeSabrError(payload)
              errorMsg = "SABR Error type=${err?.type} code=${err?.code}"
              Log.w(tag, errorMsg!!)
            }
            PART_SABR_CONTEXT_UPDATE, PART_SABR_CONTEXT_SENDING_POLICY -> {
              // 首版不维护 SABR context(retry 时回传);log 计数
              Log.i(tag, "part type=$type(${partName(type)}) payloadLen=${payload.size} (context policy, ignored)")
            }
            else -> {
              // 其余 part type(LAWNMOWER/CACHE_LOAD/RELOAD_PLAYER/END_OF_TRACK 等)首版仅记录
              // —— RELOAD_PLAYER_RESPONSE(46)是要注意的:它要求重载 /player,这里只 log 不处理。
              Log.i(tag, "part type=$type(${partName(type)}) payloadLen=${payload.size} (unhandled)")
            }
          }
        }
      }
    }

    if (invalidPo) return SabrFetchResult.InvalidPoToken
    redirectUrl?.let { return SabrFetchResult.Redirect(it, it.take(80)) }
    errorMsg?.let { return SabrFetchResult.Error(it) }
    backoffMs?.let { if (it > 0) return SabrFetchResult.Backoff(it) }
    if (matchedHeaderId == null) {
      Log.w(tag, "no MEDIA_HEADER matched; got ${mediaChunks.size} chunks but no header")
      return SabrFetchResult.Error("no matching MEDIA_HEADER")
    }
    val data = mediaChunks.fold(ByteArray(0)) { acc, c -> acc + c }
    return SabrFetchResult.Success(data, matchedHeader)
  }

  /** 匹配 MEDIA_HEADER:formatId(itag/lastModified/xtags) 一致 + isInit/seq 对齐请求(对齐 SabrSchemePlugin L386-396)。 */
  private fun matchesFormat(mh: SabrProto.MediaHeader, session: SabrSession, req: SabrFetchRequest): Boolean {
    val wanted = if (req.streamType == SabrStreamType.AUDIO) session.audioFormatId else session.videoFormatId
    if (mh.itag != wanted.itag) return false
    if (mh.lmt != wanted.lastModified) return false
    // xtags 双方都可能 null(无 xtags 的常规 itag);null 与 "" 等价。
    if ((mh.xtags ?: "") != (wanted.xtags ?: "")) return false
    return if (req.isInit) mh.isInitSeg else mh.sequenceNumber == req.sequenceNumber
  }

  /** UMP part type 数值 → 可读名(仅诊断 log 用)。 */
  private fun partName(type: Int): String = when (type) {
    20 -> "MEDIA_HEADER"
    21 -> "MEDIA"
    22 -> "MEDIA_END"
    30 -> "CONFIG"
    35 -> "NEXT_REQUEST_POLICY"
    42 -> "FORMAT_INIT_METADATA"
    43 -> "SABR_REDIRECT"
    44 -> "SABR_ERROR"
    45 -> "SABR_SEEK"
    46 -> "RELOAD_PLAYER_RESPONSE"
    57 -> "SABR_CONTEXT_UPDATE"
    58 -> "STREAM_PROTECTION_STATUS"
    59 -> "SABR_CONTEXT_SENDING_POLICY"
    62 -> "END_OF_TRACK"
    else -> "?"
  }
}
