package com.kirin.mt.core.youtube.sabr

import android.util.Base64
import android.util.Log
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
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
  /**
   * alpha.29:同一会话下所有可播视频 itag 的 FormatId(从 /player adaptiveFormats 全收)。
   * poToken 是**会话级**不绑 itag(核对 FreeTube Watch.js createLocalSabrManifest 证实:一个 poToken
   * 给整个 SABR 会话,播放器用同一 token 请求不同 itag)→ 多清晰度 = 请求体 preferredVideoFormatIds
   * 填哪个 itag,服务端发对应流。`videoFormatId` 是默认(harvested/首条)兜底,本表查不到时回退它。
   */
  val videoFormats: List<FormatId> = emptyList(),
  /**
   * 多语言配音:全部可选音频轨(供播放器音轨切换菜单)。id 为 audioTrack.id(如 "en.4",非 itag)。
   * 音轨切换 = 按 id 命中本表 → copy(audioFormatId = 对应 formatId) 换会话音频轨。
   */
  val audioTracks: List<SabrAudioTrack> = emptyList(),
  val userAgent: String,
  val cookieHeader: String,
  val visitorData: String,
  /** 会话 cpn——Redirect 重写 sabrUrl 时需重新追加 `cpn=`(会话绑定)。 */
  val cpn: String,
  /**
   * alpha.30:服务端 NextRequestPolicy 回传的 playbackCookie(原始 bytes),下个请求 opaque 透传进
   * StreamerContext.field3。FreeTube 源码确认必须回传——不回传则服务端 ~6 段后丢失会话连续性
   * → 只回 policy 不回 media → premature EOF(我们 alpha.28 seq7 黑屏的根因)。
   *
   * 会话级(PlaybackCookie 含双格式 resolution,服务端对同一会话发同一 cookie)→ 两 loader
   * (audio/video)共享安全。@Volatile:两 loader 线程并发写,保证引用可见性。
   * processUmpStream 捕获写回;fetch 读它填 StreamerContext。null=首请求/尚无 cookie。
   */
  @Volatile var playbackCookie: ByteArray? = null,
  /**
   * alpha.31:SABR 上下文握手状态机——服务端用 SABR_CONTEXT_UPDATE(part 57)下发上下文,要求客户端
   * 把 {type, value} 回传进下次请求 streamerContext.sabr_contexts(field5)+ unsent_sabr_contexts(field6)。
   * **不回传 → 服务端判定握手未完成 → 只回 context+backoff 不发 media → 8 次重试后 EOF → 视频打不开
   * (alpha.29/30 一直加载的根因)。** 对齐 FreeTube SabrSchemePlugin.js `prepareSabrContexts`(L223-239)。
   *
   * 会话级(与 [playbackCookie] 同理:服务端对同一会话发同一组 context,log 证实 audio/video 两 loader
   * 都收同一 92B context)→ 两 loader 共享安全。用 ConcurrentHashMap/newKeySet 因两 loader 线程并发写。
   * - [sabrContexts]:type → value(服务端给的原始 opaque bytes,不解码/重组),active+非 active 都存这。
   * - [activeSabrContextTypes]:type 集合,标记 [sabrContexts] 中哪些该完整回传(其余只回 type id)。
   * processUmpStream 捕获写回(part 57/59);fetch 读它经 [prepareSabrContexts] 填 StreamerContext。
   */
  val sabrContexts: MutableMap<Int, ByteArray> = ConcurrentHashMap(),
  val activeSabrContextTypes: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
) {
  /** alpha.29:按 itag 查多清晰度 FormatId;查不到回退默认 [videoFormatId](同 itag 时)。 */
  /** alpha.29:按 itag 查多清晰度 FormatId;查不到回退默认 [videoFormatId](同 itag 时)。 */
  fun videoFormat(itag: Int): FormatId? =
    videoFormats.firstOrNull { it.itag == itag } ?: videoFormatId.takeIf { it.itag == itag }

  /**
   * alpha.31:对齐 FreeTube `prepareSabrContexts`(SabrSchemePlugin.js L223-239)——每次请求(含重试)
   * 重算回传集:[sabrContexts] 中 type 在 [activeSabrContextTypes] 的 → 完整 `SabrContext{type, value}`
   * (field5);非 active 的 → 只 type id(field6)。value 用服务端给的原始 bytes,opaque 回传。
   */
  fun prepareSabrContexts(): Pair<List<SabrContext>, List<Int>> {
    val active = ArrayList<SabrContext>()
    val unsent = ArrayList<Int>()
    for ((type, value) in sabrContexts) {
      if (type in activeSabrContextTypes) active.add(SabrContext(type, value))
      else unsent.add(type)
    }
    return active to unsent
  }

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
      /**
       * alpha.29:同会话所有可播视频 itag 的 FormatId(从 /player adaptiveFormats 全收)。poToken 会话级
       * 不绑 itag(FreeTube 证实)→ 多清晰度 = 请求体填哪个 itag。默认空(仅 [videoFormatId] 一档)。
       */
      videoFormats: List<FormatId> = emptyList(),
      /** 多语言配音:全部可选音频轨(供播放器音轨切换菜单)。默认空(单音轨)。 */
      audioTracks: List<SabrAudioTrack> = emptyList(),
    ): SabrSession {
      // sabrUrl 加 alr=yes + cpn(对齐 FreeTube Watch.js L1619-1620 + SabrSchemePlugin 追加 rn)。cpn = 16 随机字节 base64url
      val usedCpn = cpn ?: randomCpn()
      val withParams = sabrUrlWithParams(sabrUrl, usedCpn)
      val po = Base64.decode(poTokenB64, Base64.DEFAULT)
      // ustreamerConfig 是 YouTube 的 URL-safe base64(含 -/_),DEFAULT 解码会丢弃非法字符→损坏字节
      // → 服务端判 sabr.malformed_config(alpha.72 真机全黑)。对齐 LibreTube SabrManifest URL_SAFE 解码。
      val ustreamer = Base64.decode(ustreamerConfigB64, Base64.URL_SAFE)
      Log.i(tag, "SabrSession: sabrUrl=${withParams.take(200)}... poToken=${po.size}B ustreamerCfg=${ustreamer.size}B cpn=$usedCpn audio=$audioFormatId video=$videoFormatId videoFormats=${videoFormats.size} audioTracks=${audioTracks.size} ua=${userAgent.take(40)} cookie=${cookieHeader.length}B visitor=${visitorData.length}B")
      return SabrSession(withParams, po, ustreamer, clientInfo, audioFormatId, videoFormatId, videoFormats, audioTracks, userAgent, cookieHeader, visitorData, usedCpn)
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

/**
 * 一条可选音频轨(多语言配音)。id 为 audioTrack.id(如 "en.4",非 itag)。
 * 音轨切换 = 按 id 命中本表 → copy(audioFormatId = [formatId]) 换会话音频轨。
 */
internal data class SabrAudioTrack(
  val id: String,
  val languageCode: String?,
  val displayName: String?,
  val isDefault: Boolean,
  val formatId: FormatId,
)

internal enum class SabrStreamType { AUDIO, VIDEO }

/** 一次 SABR 段请求(init 或 media)。 */
internal data class SabrFetchRequest(
  val isInit: Boolean,
  val sequenceNumber: Int = 0,
  val streamType: SabrStreamType,
  /** alpha.29:本次请求要播的视频 itag(null=用会话默认 videoFormatId,即 harvested/首条)。
   * poToken 会话级不绑 itag → 换 itag 即换清晰度,无需重 harvest。audio 流忽略本字段。 */
  val videoItag: Int? = null,
  /** alpha.28:SABR 服务端驱动——服务端按 playerTimeMs + bufferedRanges 决定发哪段。
   * alpha.27 硬死 playerTimeMs=0/无 buffer → 服务端只发初始 ~4 段(segs 1-4,~26s lookahead)
   * 就不再发新段 → seq5 拿不到 → premature EOF(黑屏)。改:每段请求带 cumulativeDurationMs
   * (已缓冲到的位置)+ bufferedRange(0..cumulative)让服务端 lookahead 窗口前移持续发新段。 */
  val playerTimeMs: Long = 0L,
  val bufferedRange: BufferedRangeInput? = null,
  /** alpha.51(Track A 移动播放点):本段起始呈现时间(ms),镜像 FreeTube 段 URL 的 `startTimeMs`。
   * 仅用于拼段请求 URL(`&startTimeMs=<T>&sq=<seq>`),让每次段请求**自锚定**到目标呈现时间——
   * FreeTube 靠此绕开服务端会话锚定窗口的 ~60s 服务量上限(见 Mp4SegmentIndexParser.js)。
   * body 的 [playerTimeMs] 仍保持 playhead(alpha.44 防 60s 断崖结论,不随 URL 改)。 */
  val startTimeMs: Long = 0L,
  // alpha.30:playbackCookie 存 [SabrSession.playbackCookie](会话级——cookie 含双格式 resolution,
  // 服务端对同一会话发同一 cookie,两 loader 共享安全),不在 req 里——Backoff 重试同 req 时
  // session 已更新 cookie,fetch 自动读到,无需 req 重建。
)

internal sealed class SabrFetchResult {
  /** 段字节已收齐(MEDIA_END)+ 匹配到的 MediaHeader + alpha.30:本响应的 playbackCookie(诊断用)。
   * cookie 实际存 [SabrSession.playbackCookie](processUmpStream 写回),下个请求 fetch 自动读。 */
  data class Success(
    val data: ByteArray,
    val mediaHeader: SabrProto.MediaHeader?,
    /** alpha.30:本响应 NextRequestPolicy 的 playbackCookie(诊断 log 用);null=无 policy/无 cookie。 */
    val playbackCookie: ByteArray?,
  ) : SabrFetchResult()
  /** 服务端要求重定向到新 url(用新 sabrUrl 重试)。 */
  data class Redirect(val newSabrUrl: String, val sanitized: String) : SabrFetchResult()
  /** 服务端要求 backoff,重试同一请求。 */
  data class Backoff(val ms: Int) : SabrFetchResult()
  /**
   * alpha.41:RELOAD_PLAYER_RESPONSE(part 46)——服务端明令重载 player response(取新 formats/poToken/
   * sabrUrl),**非** backoff。FreeTube 语义「whole video cannot be played → reload /player」。
   * terminal:不再当 Backoff 死循环重发同一过期请求,交由 [SabrStreamingDataSource] evict 会话
   * → 播放器 stall/error-retry 走新 harvest。携带 [dump] 取证(顶层字段 + hex)。
   */
  data class ReloadPlayer(val dump: String) : SabrFetchResult()
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
   * alpha.63(对齐 LibreTube setElapsedWallTimeMs):上次请求墙钟(epoch ms,0=首次)。
   * 两路 loader 共享一个 SabrClient,elapsedWallTimeMs=now-lastRequestMs(A/V 任一路都算「上次请求」,
   * 对齐 LibreTube 单 SabrClient 共享 lastRequestMs)。AtomicLong 线程安全。
   */
  private val lastRequestMs = java.util.concurrent.atomic.AtomicLong(0L)

  /**
   * 发一次 SABR 段请求。[session] 的 sabrUrl 可能因 Redirect 变化(调用方把新 url 写回 session 再重试)。
   */
  suspend fun fetch(session: SabrSession, req: SabrFetchRequest): SabrFetchResult {
    val audioEnc = SabrProto.encodeFormatId(session.audioFormatId.itag, session.audioFormatId.lastModified, session.audioFormatId.xtags)
    // alpha.29:按请求 itag 选视频 FormatId(poToken 会话级不绑 itag → 换 itag 换清晰度);查不到回退默认。
    val videoFmt = req.videoItag?.let { session.videoFormat(it) } ?: session.videoFormatId
    val videoEnc = SabrProto.encodeFormatId(videoFmt.itag, videoFmt.lastModified, videoFmt.xtags)
    val selected = if (req.isInit) emptyList() else listOf(audioEnc, videoEnc)
    // alpha.30:回传 playbackCookie(StreamerContext.field3)——服务端 NextRequestPolicy 要求客户端
    // 把 cookie 塞进下个请求,否则 ~6 段后丢失会话连续性 → premature EOF(FreeTube 源码确认)。
    // cookie 存 session.playbackCookie(processUmpStream 捕获写回),会话级共享。
    // alpha.55:init 请求**不带** playbackCookie——会话级共享 cookie 在轮换时会被另一路 loader 先写入
    // (AUDIO 轮换后先跑、写 session.playbackCookie),导致后轮换的 VIDEO 其 init 复用 AUDIO 派生的
    // cookie → STREAM_PROTECTION_STATUS status=3(InvalidPoToken) → init 失败 → 视频卡死在 ~1min
    // (alpha.54 真机:VIDEO 轮换 init cookie=true 失败,而所有成功 init 皆 cookie=false)。init 是全新
    // 会话首请求,不该携带前一会话的 cookie;MEDIA(段)请求仍回传(alpha.30 结论,服务端要求)。
    // alpha.31:同时回传 SABR 上下文握手(field5 sabrContexts / field6 unsentSabrContexts)——
    // 不回传则服务端只回 context+backoff 不发 media → 8 次后 EOF(alpha.29/30 打不开视频根因)。
    // session.prepareSabrContexts 按 active 集合分派(processUmpStream 捕获 part 57/59 写回)。
    // alpha.57:init 请求**不带** SABR 上下文(field5/field6)——与 alpha.55 的 playbackCookie 同理:会话级
    // 共享上下文在轮换时会被另一路 loader 先写入(AUDIO 轮换后先跑、写 session.sabrContexts),导致后轮换的
    // VIDEO 其 init 复用 AUDIO 派生的上下文 → STREAM_PROTECTION_STATUS status=3(InvalidPoToken) → init 失败
    // → 视频卡死在 ~1min(alpha.56 真机:VIDEO 轮换 init cookie=false 但 contexts=1/0 仍 status=3,而 AUDIO
    // 轮换 init contexts=0/0 成功)。init 是全新会话首请求,不该携带前一会话的上下文;MEDIA(段)请求仍回传
    // (alpha.31 结论,服务端要求)。对齐 FreeTube:init 首请求天然无上下文。
    val (activeCtxs, unsentCtxTypes) =
      if (req.isInit) emptyList<SabrContext>() to emptyList<Int>() else session.prepareSabrContexts()
    val streamerContext = StreamerContextInput(
      clientInfo = session.clientInfo,
      poToken = session.poToken,
      playbackCookie = if (req.isInit) null else session.playbackCookie,
      sabrContexts = activeCtxs,
      unsentSabrContexts = unsentCtxTypes,
    )
    val resolution = videoFmt.height.takeIf { it > 0 }
    // alpha.63(对齐 LibreTube fetchStreamData):补 ClientAbrState 字段 29/34/36/39。
    // elapsedWallTimeMs=距上次请求墙钟(首请求 0);visibility=1(可见);seek/action 首播发 0。
    val now = System.currentTimeMillis()
    val lastMs = lastRequestMs.get()
    val elapsed = if (lastMs > 0L) (now - lastMs).coerceAtLeast(0L) else 0L
    lastRequestMs.set(now)
    // alpha.16(对齐 LibreTube setAudioTrackId):当前选中音轨 id(audioTrack.id,如 "en.4")。
    // 按 itag 命中 session.audioTracks 里当前音频格式那条;单音轨视频 resolver 折叠成 "default"(audioTrackId
    // 为 null),对齐 LibreTube 发空串("" 而非 "default")——多音轨视频(如 jNl6YkkzKxw 5 音轨)发真实 id。
    val audioTrackId = session.audioTracks.firstOrNull { it.formatId.itag == session.audioFormatId.itag }?.id
      ?.takeIf { it != "default" } ?: ""
    val clientAbrState = ClientAbrStateInput(
      timeSinceLastManualFormatSelectionMs = if (req.streamType == SabrStreamType.VIDEO) 0L else null,
      lastManualSelectedResolution = resolution,
      clientViewportWidth = 1920,
      clientViewportHeight = 1080,
      stickyResolution = resolution,
      clientViewportIsFlexible = false,
      bandwidthEstimate = 0L,
      // alpha.28:playerTimeMs 推进(已缓冲到的位置),否则服务端 lookahead 窗口停在 0 → premature EOF。
      playerTimeMs = req.playerTimeMs,
      timeSinceLastSeekMs = 0L,
      visibility = 1,
      playbackRate = 1.0f,
      elapsedWallTimeMs = elapsed,
      timeSinceLastActionMs = 0L,
      audioTrackId = audioTrackId,
      // alpha.40:回退 alpha.39 的 video=0(VIDEO_AND_AUDIO)。alpha.39 真机证伪——bitfield=0 让视频流
      // 请求声明「要音视频双轨」→ 服务端向 VIDEO 流灌 itag=251(audio) MEDIA_HEADER(matched=false,
      // wanted itag=video);itag401 靠 headerId=2 侥幸拿到 video 头,itag399 则几十次 backoff=0
      // 拿不到任何 video 头 → seq0 init 即 EOF(alpha.39 logs_live.log:97/635/1091-1340)。
      // video=0 对齐 FreeTube `streamIsAudio?1:0` 仅在 shaka 单路按段拉时成立;我们是 audio/video
      // 双 ProgressiveMediaSource 分流,video 流必须 VIDEO_ONLY(=2,alpha.18→38 原值,语义正确)。
      // googlevideo EnabledTrackTypes:AUDIO_ONLY=1 / VIDEO_ONLY=2 / VIDEO_AND_AUDIO=0。
      enabledTrackTypesBitfield = if (req.streamType == SabrStreamType.AUDIO) 1 else 2,
      drcEnabled = false,
      enableVoiceBoost = false,
    )
    // alpha.30:bufferedRanges 对齐 FreeTube fillBufferedRanges——own 格式报真实已缓冲段(让服务端
    // 发下一段不重发)+ **对方格式标「满缓冲」**(createFullBufferRange,durationMs/seg=Int.MAX)告诉
    // 服务端「这格式别发,我只要 own 这条」。不标则服务端每流试图发双格式(audio itag 251 header 混进
    // video 响应)→ 会话状态混乱。init 请求不发 bufferedRanges(对齐 FreeTube:isInit 不 fillBufferedRanges)。
    val bufferedRanges = if (req.isInit) emptyList() else {
      val own = req.bufferedRange
      // 对方格式 = 本流不播的那条(audio 流→video,video 流→audio)。
      val otherFmt = if (req.streamType == SabrStreamType.AUDIO) videoFmt else session.audioFormatId
      val otherFull = createFullBufferRange(otherFmt)
      listOfNotNull(otherFull, own)
    }
    val input = SabrRequestInput(
      clientAbrState = clientAbrState,
      selectedFormatIds = selected,
      // alpha.30:own 格式报已缓冲 0..cumulative + 对方格式标满缓冲(见上),让服务端只发 own 下一段。
      bufferedRanges = bufferedRanges,
      playerTimeMs = req.playerTimeMs,
      videoPlaybackUstreamerConfig = session.ustreamerConfig,
      preferredAudioFormatIds = listOf(audioEnc),
      preferredVideoFormatIds = listOf(videoEnc),
      preferredSubtitleFormatIds = emptyList(),
      streamerContext = streamerContext,
    )
    val body = SabrProto.encodeVideoPlaybackAbrRequest(input)
    val rn = requestNumber.getAndIncrement()
    // alpha.54:段 URL 只加 `rn`(对齐 FreeTube SabrStreamingAdapter,后者段 URL 仅 rn、时间只在 body
    // playerTimeMs)。删掉 alpha.51 Track A 的 `&startTimeMs=&sq=`——真机证伪该诊断:轮换新会话(harvest
    // 自 &t=60)URL startTimeMs=cumulative=60000 未封顶直接触发服务端 maxTimeSinceReq 软拒 → 6 backoff →
    // EOF(alpha.52 死因)。init/段统一只拼 rn。
    val url = "${session.sabrUrl}&rn=$rn"
    // alpha.38 诊断:打**发出**的 cookie 哈希(sentCookieHash)。对比同流上一个 PolicyDiag 收到的
    // cookieHash——若两者不等,说明两 loader 并发期间 cookie 被对方覆盖(§8 clobber 风险的铁证);
    // 若始终相等,cookie 共享安全,clobber 排除。结合 PolicyDiag 逐流读。
    val sentCookieHash = session.playbackCookie?.let { java.util.Arrays.hashCode(it) }
    Log.i(tag, "fetch rn=$rn isInit=${req.isInit} stream=${req.streamType} seq=${req.sequenceNumber} startTimeMs=${req.startTimeMs} body=${body.size}B cookie=${session.playbackCookie != null && session.playbackCookie!!.isNotEmpty()} sentCookieHash=$sentCookieHash contexts=${activeCtxs.size}/${unsentCtxTypes.size}")

    return try {
      val request = Request.Builder()
        .url(url)
        .post(body.toRequestBody("application/x-protobuf".toMediaType()))
        .header("accept-encoding", "identity")
        .header("accept", "application/vnd.yt-ump")
        // 会话传输头(对齐 /player 同会话:UA + Cookie + X-Goog-Visitor-Id)——googlevideo SABR 端点
        // 拒绝无会话绑定的裸请求(alpha.17 实测 HTTP 403 空响应体)。
        .header("User-Agent", session.userAgent)
        // alpha.79:cookie/visitor 可空——空串=不带(对齐 LibreTube 无 HTTP cookie,靠 protobuf)。非空才带。
        .apply {
          if (session.cookieHeader.isNotBlank()) header("Cookie", session.cookieHeader)
          if (session.visitorData.isNotBlank()) header("X-Goog-Visitor-Id", session.visitorData)
        }
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
    // alpha.41:RELOAD_PLAYER_RESPONSE(part 46)取证 dump——非空则 terminal,优先于 Backoff 返回 ReloadPlayer。
    var reloadPlayerDump: String? = null
    // alpha.30:NextRequestPolicy 出现标志 + 原始 playbackCookie bytes(回传进下个请求 StreamerContext.field3)。
    var sawPolicy = false
    var cookieBytes: ByteArray? = null

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
                val wanted = if (req.streamType == SabrStreamType.AUDIO) session.audioFormatId
                  else req.videoItag?.let { session.videoFormat(it) } ?: session.videoFormatId
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
              // alpha.30:捕获原始 playbackCookie bytes 写回 session.playbackCookie(opaque 透传,
              // 下个请求 fetch 读它填 StreamerContext.field3)。会话级共享,两 loader 并发写安全
              // (cookie 含双格式 resolution,服务端对同一会话发同一值)。
              sawPolicy = true
              cookieBytes = policy?.playbackCookieBytes
              // ByteArray? 无 isNullOrEmpty() 扩展;且 cookieBytes 是 var 跨 when 臂赋值,
              // smart cast 不生效——用 ?.isNotEmpty() == true 不依赖 smart cast。
              val hasCookie = cookieBytes?.isNotEmpty() == true
              if (hasCookie) session.playbackCookie = cookieBytes
              Log.i(tag, "NEXT_REQUEST_POLICY backoff=${policy?.backoffTimeMs}ms cookie=$hasCookie")
              // alpha.38 诊断叠层:逐流逐段打 cookie 指纹 + 解码后的 PlaybackCookie 双格式 +
              // 服务端 readahead 流控字段。一次真机即可判定:① audio/video 收到的 cookie bytes 是否
              // 同值(cookieHash 比)—同值→共享安全,排除 §8 clobber 风险;异值→需改 per-stream cookie。
              // ② 60s 软拒是否我们超前缓冲触发(target_*_readahead vs 实际 lead)或请求间隔超
              // max_time_since_last_request_ms。纯诊断,不改请求构造(对齐「拿证据再改」)。
              val cookieHash = cookieBytes?.let { java.util.Arrays.hashCode(it) }
              val pc = policy?.playbackCookie
              Log.i(tag, "PolicyDiag stream=${req.streamType} seq=${req.sequenceNumber} backoff=${policy?.backoffTimeMs}ms cookieLen=${cookieBytes?.size ?: 0} cookieHash=$cookieHash cookieRes=${pc?.resolution} vFmt=${pc?.videoFmt} aFmt=${pc?.audioFmt} readahead targetA=${policy?.targetAudioReadaheadMs} targetV=${policy?.targetVideoReadaheadMs} maxTimeSinceReq=${policy?.maxTimeSinceLastRequestMs} minA=${policy?.minAudioReadaheadMs} minV=${policy?.minVideoReadaheadMs}")
            }
            PART_SABR_ERROR -> {
              val err = SabrProto.decodeSabrError(payload)
              errorMsg = "SABR Error type=${err?.type} code=${err?.code}"
              Log.w(tag, errorMsg!!)
            }
            PART_SABR_CONTEXT_UPDATE -> {
              // alpha.31:捕获服务端下发的上下文,回传进下次请求 streamerContext.sabr_contexts(field5)
              // / unsent_sabr_contexts(field6)。不回传 → 服务端只回 context+backoff 不发 media → EOF
              // (alpha.29/30 打不开视频根因)。对齐 FreeTube SabrSchemePlugin.js L430-447。
              val u = SabrProto.decodeSabrContextUpdate(payload)
              if (u != null && u.type != 0 && u.value.isNotEmpty()) {
                // writePolicy KEEP_EXISTING(2) 且已存 → 跳过(不覆盖、不加 active);否则覆盖存。
                val keepExisting = u.writePolicy == 2 && session.sabrContexts.containsKey(u.type)
                if (!keepExisting) {
                  session.sabrContexts[u.type] = u.value
                  if (u.sendByDefault) session.activeSabrContextTypes.add(u.type)
                }
                Log.i(tag, "SABR_CONTEXT_UPDATE type=${u.type} valLen=${u.value.size} sendByDefault=${u.sendByDefault} writePolicy=${u.writePolicy} keepExisting=$keepExisting ctxs=${session.sabrContexts.size} active=${session.activeSabrContextTypes.size}")
              } else {
                Log.i(tag, "part type=$type(SABR_CONTEXT_UPDATE) payloadLen=${payload.size} (no type/value, ignored)")
              }
            }
            PART_SABR_CONTEXT_SENDING_POLICY -> {
              // alpha.31:服务端动态控制 active 集合(start 发/stop 停/discard 丢)。对齐 FreeTube L450-470。
              val p = SabrProto.decodeSabrContextSendingPolicy(payload)
              if (p != null) {
                p.start.forEach { session.activeSabrContextTypes.add(it) }
                p.stop.forEach { session.activeSabrContextTypes.remove(it) }
                p.discard.forEach { session.sabrContexts.remove(it) }
                Log.i(tag, "SABR_CONTEXT_SENDING_POLICY start=${p.start} stop=${p.stop} discard=${p.discard} ctxs=${session.sabrContexts.size} active=${session.activeSabrContextTypes.size}")
              } else {
                Log.i(tag, "part type=$type(SABR_CONTEXT_SENDING_POLICY) payloadLen=${payload.size} (decode failed, ignored)")
              }
            }
            PART_RELOAD_PLAYER_RESPONSE -> {
              // alpha.41:服务端明令重载 player response——非 backoff,不可重发同一过期请求(否则 6× 死循环
              // → EOF,见 alpha.40 logs)。解码 144B payload 取证(顶层字段 + hex):是否带新 sabrUrl /
              // format 列表 / 原因码,区分根因 A(video itag formatId 过期)vs B(session 已初始化)。
              // terminal:置 dump,循环外优先返回 ReloadPlayer(交 DataSource evict → 播放器重 harvest)。
              val diag = SabrProto.decodeReloadPlayerResponse(payload)
              reloadPlayerDump = "fields={${diag.fieldsSummary}} hex=${diag.hexDump}"
              Log.w(tag, "RELOAD_PLAYER_RESPONSE payloadLen=${payload.size} $reloadPlayerDump")
            }
            else -> {
              // 其余 part type(LAWNMOWER/CACHE_LOAD/END_OF_TRACK 等)首版仅记录。
              Log.i(tag, "part type=$type(${partName(type)}) payloadLen=${payload.size} (unhandled)")
            }
          }
        }
      }
    }

    if (invalidPo) return SabrFetchResult.InvalidPoToken
    // alpha.41:RELOAD_PLAYER_RESPONSE 优先级高于 Redirect/Error/Backoff——服务端明令重载 player
    // response,重发同一 req 无意义,terminal 交 DataSource evict → 播放器重 harvest。
    reloadPlayerDump?.let { return SabrFetchResult.ReloadPlayer(it) }
    redirectUrl?.let { return SabrFetchResult.Redirect(it, it.take(80)) }
    errorMsg?.let { return SabrFetchResult.Error(it) }
    if (matchedHeaderId != null) {
      val data = mediaChunks.fold(ByteArray(0)) { acc, c -> acc + c }
      // alpha.30:把本响应的 playbackCookie 透传给调用方,塞进下个请求 StreamerContext.field3 回传。
      return SabrFetchResult.Success(data, matchedHeader, cookieBytes)
    }
    // alpha.30:无 MEDIA_HEADER 匹配的两种情况(对齐 FreeTube SabrSchemePlugin doRequest 重试语义):
    // ① 有 NEXT_REQUEST_POLICY(sawPolicy)→「软空响应」,服务端在等 cookie/会话推进 → Backoff 重试
    //   同 seq(回传 cookie 重发),不当永久 EOF。这是 alpha.28 seq7 黑屏的直接触发点(我们原来当 Error→EOF)。
    // ② 无 policy → 真「无 media 无原因」,Error→EOF。
    if (sawPolicy) {
      Log.i(tag, "no MEDIA_HEADER but NEXT_REQUEST_POLICY present → Backoff ${backoffMs ?: 0}ms (retry with cookie, not EOF)")
      return SabrFetchResult.Backoff(backoffMs ?: 0)
    }
    Log.w(tag, "no MEDIA_HEADER matched; got ${mediaChunks.size} chunks but no header")
    return SabrFetchResult.Error("no matching MEDIA_HEADER")
  }

  /** 匹配 MEDIA_HEADER:formatId(itag/lastModified/xtags) 一致 + isInit/seq 对齐请求(对齐 SabrSchemePlugin L386-396)。 */
  private fun matchesFormat(mh: SabrProto.MediaHeader, session: SabrSession, req: SabrFetchRequest): Boolean {
    // alpha.29:视频流按请求 itag 匹配(poToken 会话级不绑 itag,服务端按 preferredVideoFormatIds 发对应流)。
    val wanted = if (req.streamType == SabrStreamType.AUDIO) session.audioFormatId
      else req.videoItag?.let { session.videoFormat(it) } ?: session.videoFormatId
    if (mh.itag != wanted.itag) return false
    if (mh.lmt != wanted.lastModified) return false
    // xtags 双方都可能 null(无 xtags 的常规 itag);null 与 "" 等价。
    if ((mh.xtags ?: "") != (wanted.xtags ?: "")) return false
    // alpha.44:续播/切清晰度时 client 用 playerTimeMs=startMs 让服务端从续播点发段,返回的段 seq 可能
    // > 请求 seq(nextSeq 从 1 起算,而续播点落在更高 seq);接受 `mh.sequenceNumber >= req.sequenceNumber`
    // 避免死磕低 seq → matched=false → 6 backoff → EOF(alpha.43 真机:reuse session req seq=1,服务端按
    // playerTimeMs=7194 给 seq=2+,严格相等判 false 死磕 → evict)。拒绝 `<`(服务端重发回退段)避免
    // alpha.36 5s 断崖复发(req=2 收 seq=1 仍 reject)。同 itag/lmt/xtags 已挡对方 itag 混入 header
    // (docs §6.9 row:audio itag 251 混进 video 响应),seq 检查只针对同 itag,`>=` 安全。
    return if (req.isInit) mh.isInitSeg else mh.sequenceNumber >= req.sequenceNumber
  }

  /**
   * alpha.30:构造「满缓冲」条目——告诉服务端「这格式别发,我只要 own 那条」。
   * 对齐 FreeTube `createFullBufferRange`(SabrSchemePlugin.js L85-97):
   * durationMs=Int.MAX / startSegmentIndex=Int.MAX / endSegmentIndex=Int.MAX / startTimeMs=0
   * + timeRange(durationTicks=Int.MAX, startTicks=0, timescale=1000)。
   * encodeBufferedRange 已支持这些字段(BufferedRangeInput 全填即可,无需新编码逻辑)。
   */
  private fun createFullBufferRange(fmt: FormatId): BufferedRangeInput = BufferedRangeInput(
    itag = fmt.itag,
    lastModified = fmt.lastModified,
    xtags = fmt.xtags,
    startTimeMs = 0L,
    durationMs = Int.MAX_VALUE.toLong(),
    startSegmentIndex = Int.MAX_VALUE,
    endSegmentIndex = Int.MAX_VALUE,
    timeRange = TimeRangeInput(startTicks = 0L, durationTicks = Int.MAX_VALUE.toLong(), timescale = 1000),
  )

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
    67 -> "SNACKBAR_MESSAGE"
    else -> "?"
  }
}
