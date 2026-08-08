package com.kirin.mt.core.youtube

import android.util.Base64
import android.util.Log
import com.kirin.mt.core.player.BiliPlaybackHeaders
import com.kirin.mt.core.player.CodecCapability
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackInfo
import com.kirin.mt.core.player.PlaybackQuality
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.PlaybackSegmentBase
import com.kirin.mt.core.player.PlaybackTrack
import com.kirin.mt.core.youtube.sabr.FormatId as SabrFormatId
import com.kirin.mt.core.youtube.sabr.SabrClient
import com.kirin.mt.core.youtube.sabr.SabrFetchRequest
import com.kirin.mt.core.youtube.sabr.SabrFetchResult
import com.kirin.mt.core.youtube.sabr.SabrProto
import com.kirin.mt.core.youtube.sabr.SabrSession
import com.kirin.mt.core.youtube.sabr.SabrStreamRegistry
import com.kirin.mt.core.youtube.sabr.SabrStreamType
import com.kirin.mt.core.youtube.sabr.UmpReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

/**
 * YouTube 播放流解析器：`POST /youtubei/v1/player`（videoId + context，可带 PO token）
 * → 解析 `streamingData.adaptiveFormats` → 按 codec 偏好挑视频/音频 → 解密 `n`/`s`
 * → 产出 [PlaybackInfo]（adaptive DASH 优先，progressive 兜底）。
 *
 * 策略：
 *  - 合并 WEB + ANDROID 两个客户端（均 guest 直连，无 PO token）的 streamingData 候选。
 *    无 PO token 时 WEB 常剥离 adaptiveFormats 的 url（只剩 progressive itag 18/22=360p），
 *    ANDROID 客户端对多数视频直接返回带 url 的高清 adaptive（NewPipe 同款），故合并后取高清。
 *  - PO token（jnn）尚未跑通，[YoutubeBotGuard] 返回 null 时走直连；对多数视频仍可播。
 *  - 优先 adaptive 高清视频+音频（1080P/2K/4K，走 DASH 合成 MPD）；仅当 adaptive 取不到
 *    可播直链时回退单个合并 progressive 流（itag 18/22）。用 [CodecCapability] 过滤设备
 *    解不了的轨道（4K VP9/AV1 无硬解时回退）。
 *  - 格式 URL 带 `n` 走 [YoutubeNDecryptor]、带 `s` 走 [YoutubeSDecryptor] 解密（base.js 从 watch 页取）。
 */
class YoutubePlaybackResolver(
  private val innerTubeClient: InnerTubeClient,
  private val botGuard: YoutubeBotGuard,
  private val nDecryptor: YoutubeNDecryptor,
  private val sDecryptor: YoutubeSDecryptor,
  private val httpClient: OkHttpClient,
  private val sabrHarvester: YoutubeSabrHarvester,
) {

  /** 从 player base.js 提取的 signatureTimestamp（对齐 youtubei.js Player.ts #getSignatureTimestamp）。 */
  private var signatureTimestamp: Int? = null

  /** 缓存的 base.js URL（避免 resolvePlayerJsUrl 重复拉 watch 页）。 */
  private var cachedPlayerJsUrl: String? = null

  suspend fun resolve(
    request: PlaybackRequest,
    codecPreference: PlaybackCodecPreference,
    codecCapability: CodecCapability,
  ): PlaybackInfo = withContext(Dispatchers.IO) {
    val videoId = request.bvid
    var lastError: String? = null
    var havePlayable = false

    // 生成视频 ID 绑定的 PO token（best-effort）。无 PO token 时 YouTube 剥掉 adaptive 高清 url
    // （只剩 progressive 360p）；有 token 才能拿高清直链。失败降级为无 token 直连。
    val poToken = botGuard.generatePoToken(videoId)
    if (poToken != null) Log.i(Tag, "PO token minted (${poToken.length} chars)") else Log.w(Tag, "PO token unavailable; degrade to no-token")

    // 提取 signatureTimestamp（对齐 youtubei.js Player.ts #getSignatureTimestamp），注入 /player
    // 的 contentPlaybackContext。缺它 WEB /player 可能被判"非真浏览器" → "The page needs to be reloaded"。
    val signatureTimestamp = resolveSignatureTimestamp(videoId)

    // 收集 playable 客户端(WEB → WEB_EMBEDDED → ANDROID)的 streamingData 合并候选。
    // 实测(§6.5):无有效 PO token 时各客户端都会剥光 adaptiveFormats 的 url(只剩 progressive 360p)。
    // PO token 只能铸成 WEB 绑定(att/get 是 WEB challenge 通道,ANDROID att/get 不返回 bgChallenge)。
    // FreeTubeAndroid 对 WEB 失败时回退 WEB_EMBEDDED(复用 WEB 的 visitorData + 同一个 WEB 绑定 token)，
    // 该嵌入式客户端对 PO token 校验可能更宽容。故合并三客户端候选,统一选最高 adaptive,progressive 仅兜底。
    val allAdaptive = mutableListOf<ParsedFormat>()
    val allCombined = mutableListOf<ParsedFormat>()
    var durationMs = 0L
    for (client in listOf(InnerTubeClient.Client.WEB, InnerTubeClient.Client.WEB_EMBEDDED, InnerTubeClient.Client.ANDROID)) {
      val player = runCatching { postPlayer(videoId, client = client, poToken = poToken, signatureTimestamp = signatureTimestamp) }.getOrNull()
      if (!player.isPlayable()) {
        lastError = player?.playabilityReason() ?: lastError
        // 诊断:dump 完整 playabilityStatus(status/reason/errorScreen),定位 WEB "Video unavailable" 真因。
        Log.w(
          Tag,
          "player $client not playable (videoId=$videoId status=${player?.obj("playabilityStatus")?.stringOrNull("status")} " +
            "reason=${player?.playabilityReason()} ps=${player?.obj("playabilityStatus").toString().take(400)}); next client"
        )
        continue
      }
      havePlayable = true
      val streamingData = player.obj("streamingData") ?: continue
      if (durationMs <= 0L) {
        durationMs = (player.obj("videoDetails")?.stringOrNull("lengthSeconds")?.toLongOrNull() ?: 0L) * 1000L
      }
      // adaptiveFormats = 分离的纯视频/纯音频；formats = 单个合并的 progressive 流(音视频一体)。
      val adaptive = (streamingData.array("adaptiveFormats") ?: emptyList())
        .mapNotNull { it as? JsonObject }.mapNotNull(::parseFormat)
      val progressive = (streamingData.array("formats") ?: emptyList())
        .mapNotNull { it as? JsonObject }.mapNotNull(::parseFormat)
      allAdaptive += adaptive
      allCombined += (adaptive + progressive).filter { it.kind == Kind.Video && it.combined }
      Log.i(Tag, "$client formats: adaptive=${adaptive.size} progressive=${progressive.size}")
      // 诊断:dump streamingData 原始结构,定位 adaptive=0 是「token 没被应用(有 adaptive 但 url 空)」
      // 还是「guest 不给 adaptive(无 adaptiveFormats)」。§6.7 row 25。
      val rawAdaptive = streamingData.array("adaptiveFormats") ?: emptyList()
      val firstFmt = rawAdaptive.firstOrNull() as? JsonObject
      val firstUrl = firstFmt?.stringOrNull("url")
      val firstCipher = firstFmt?.stringOrNull("signatureCipher")
      // 决定性诊断:FreeTube 用 SABR(server_abr_streaming_url)而非 legacy DASH 直链(§6.7 row 36)。
      // 若 /player 有 server_abr_streaming_url + adaptive 元数据(无 url),说明 YouTube 期望客户端走 SABR,
      // url 空不是 token 无效,而是拿流机制变了——我们该切 SABR 而非死磕 legacy DASH。
      //
      // 注意 raw InnerTube JSON 用 camelCase(serverAbrStreamingUrl / playerConfig / mediaCommonConfig /
      // mediaUstreamerRequestConfig / videoPlaybackUstreamerConfig)。FreeTube 代码里的 snake_case
      // (server_abr_streaming_url 等)是 youtubei.js 库端 camelCase→snake_case 转换后的形态,不是
      // raw 响应里的 key——alpha.13 的 sabrUrl=ABSENT/ustreamerCfg=ABSENT 是查错 key 导致的假阴性
      // (§6.7 row 38 定位)。
      val sabrUrl = streamingData.stringOrNull("serverAbrStreamingUrl")
      // SABR 路径第二道闸:FreeTube 决策逻辑(Watch.js)要求 server_abr_streaming_url 与
      // player_config.media_common_config.media_ustreamer_request_config.video_playback_ustreamer_config
      // 同时 present 才走 SABR(§6.7 row 36)。只 dump sabrUrl 不够,两道闸都要确认。
      val playerCfg = player.obj("playerConfig")
      // SABR 第二道闸拆两级:FreeTube Watch.js 决策(L884)只查父层 media_ustreamer_request_config
      // (camelCase=mediaUstreamerRequestConfig),createLocalSabrManifest 才读子层 videoPlaybackUstreamerConfig。
      // alpha.14 真机只 dump 了子层报 ABSENT,但 mediaCommonConfig 在 keys 里——需补查父层才能定 gate 2。
      val ustreamerReqCfg = playerCfg
        ?.obj("mediaCommonConfig")
        ?.obj("mediaUstreamerRequestConfig")
      val ustreamerCfg = ustreamerReqCfg
        ?.obj("videoPlaybackUstreamerConfig")
      // videoPlaybackUstreamerConfig 在 proto 里是 bytes(googlevideo field 5),JSON 里是 base64 字符串,
      // .obj() 必返回 null(alpha.15 报 ABSENT 疑似类型不符假阴性)。补:父层 keys + 子层按 string 读,
      // 坐实它是 base64 串并拿确切长度(SABR 移植要透传这串 bytes)。
      val ustreamerCfgStr = ustreamerReqCfg?.stringOrNull("videoPlaybackUstreamerConfig")
      // 原始 key 全量 dump:彻底坐实「camelCase 假阴性」理论。若 streamingData.keys 里有
      // serverAbrStreamingUrl 而 snake_case 读不到,根因即定。同时 dump 第一条 adaptive 的全部
      // key,确认 url/signatureCipher 是否真无(而非换成了别的拿流字段名)。
      Log.i(Tag, "$client streamingData keys=${streamingData.keys.toList()}")
      Log.i(Tag, "$client playerConfig keys=${playerCfg?.keys?.toList() ?: "NO playerConfig"}")
      Log.i(Tag, "$client ustreamerReqCfg keys=${ustreamerReqCfg?.keys?.toList() ?: "NO ustreamerReqCfg"}")
      Log.i(
        Tag,
        "$client diag: playable=${player.obj("playabilityStatus")?.stringOrNull("status")} " +
          "rawAdaptive=${rawAdaptive.size} parsedAdaptive=${adaptive.size} " +
          "firstUrl=${if (firstUrl.isNullOrBlank()) "EMPTY" else "present(${firstUrl.length}B)"} " +
          "firstCipher=${if (firstCipher.isNullOrBlank()) "none" else "present"} " +
          "sabrUrl=${if (sabrUrl.isNullOrBlank()) "ABSENT" else "present(${sabrUrl.length}B)"} " +
          "ustreamerReqCfg=${if (ustreamerReqCfg == null) "ABSENT" else "present(${ustreamerReqCfg.toString().length}B)"} " +
          "ustreamerCfg=${if (ustreamerCfg == null) "ABSENT(obj)" else "present(obj ${ustreamerCfg.toString().length}B)"} " +
          "ustreamerCfgStr=${if (ustreamerCfgStr.isNullOrBlank()) "ABSENT(str)" else "present(str ${ustreamerCfgStr.length}B)"} " +
          "progressiveRaw=${(streamingData.array("formats") ?: emptyList()).size}"
      )
      // 决定性诊断:dump 第一条 adaptive 完整字段 + 全表扫描任何 url 类字段。若 YouTube 给的是
      // `pot`/`sabr`/其它拿流字段而非 url,说明拿流机制变了,url 空不代表真没有(§6.7 row 32)。
      val firstRawJson = firstFmt?.toString()?.take(600)
      val urlishKeys = rawAdaptive.mapNotNull { it as? JsonObject }
        .flatMap { it.keys }.filter { it.contains("url", true) || it.contains("cipher", true) || it.contains("sabr", true) || it == "pot" }.distinct()
      val firstHasAny = firstFmt?.keys?.any { it.contains("url", true) || it.contains("cipher", true) || it.contains("sabr", true) || it == "pot" } == true
      Log.i(Tag, "$client rawAdaptive keys(all adaptive url/cipher/pot-ish)=${if (urlishKeys.isEmpty()) "NONE" else urlishKeys} firstHasAny=$firstHasAny")
      Log.i(Tag, "$client rawAdaptive first format json=$firstRawJson")

      // SABR 协议往返探针(§6.9):WEB 数据齐全时发一次 init 段请求,验证 encode→POST→UMP→MEDIA 全链。
      // 首版仅诊断——拿回字节即证明协议层通,再接 Media3 播放(Phase 2b)。
      if (client == InnerTubeClient.Client.WEB && !sabrUrl.isNullOrBlank() && !ustreamerCfgStr.isNullOrBlank() && poToken != null) {
        val raws = rawAdaptive.mapNotNull { it as? JsonObject }
        val firstVideo = raws.firstOrNull { (it.intOrNull("height") ?: 0) > 0 }
        val firstAudio = raws.firstOrNull { (it.stringOrNull("mimeType") ?: "").startsWith("audio/") }
        if (firstVideo != null && firstAudio != null) {
          // alpha.29:多清晰度——切清晰度重跑 resolve 时,若该 videoId 已有缓存 SABR 会话
          // (poToken/ustreamerConfig/cpn 会话级可复用 ~6h,核对 FreeTube Watch.js 证实同 token 跨多 itag),
          // 跳过 harvest/decipher 直接复用,用 preferredQualityId 选 itag 建 PlaybackInfo。
          val cachedSid = SabrStreamRegistry.getByVideoId(videoId)
          if (cachedSid != null) {
            val cached = SabrStreamRegistry.get(cachedSid)
            if (cached != null) {
              Log.i(Tag, "SABR cache hit: videoId=$videoId sid=$cachedSid → reuse session (skip harvest/decipher), preferredQuality=${request.preferredQualityId}")
              return@withContext buildSabrPlaybackInfo(request, videoId, durationMs, raws, cached.session, cachedSid)
            }
          }
          // alpha.29:同会话所有可播视频 itag 的 FormatId(从 /player adaptiveFormats 全收)。
          // poToken 会话级不绑 itag → 多清晰度 = 请求体 preferredVideoFormatIds 填哪个 itag(见 SabrClient.fetch)。
          val videoFormats = raws.filter { (it.intOrNull("height") ?: 0) > 0 }.map {
            SabrFormatId(
              it.longOrNull("itag")?.toInt() ?: 0,
              it.longOrNull("lastModified") ?: 0L,
              it.stringOrNull("xtags"),
              it.intOrNull("height") ?: 0,
            )
          }
          // SABR URL 需 decipher(n-param transform)——对齐 googlevideo 示例
          // `innertube.session.player.decipher(serverAbrStreamingUrl)`。googlevideo URL 带 `n` 签名参数,
          // 未用 base.js transform 解出真值则返回 403 空体(alpha.18 实测 Server=gvs 1.0
          // Content-Length=0,§6.7 row 41)。resolvePlayerJsUrl 内部缓存,此处与下游 resolveStreamUrl
          // 共用同一份 playerJsUrl,不重复拉 watch 页。
          val sabrUrlDeciphered = decipherSabrUrl(sabrUrl, resolvePlayerJsUrl(videoId))
          val nTransformed = sabrUrlDeciphered != sabrUrl
          val vFmt = SabrFormatId(
            firstVideo.longOrNull("itag")?.toInt() ?: 0,
            firstVideo.longOrNull("lastModified") ?: 0L,
            firstVideo.stringOrNull("xtags"),
            firstVideo.intOrNull("height") ?: 0,
          )
          val aFmt = SabrFormatId(
            firstAudio.longOrNull("itag")?.toInt() ?: 0,
            firstAudio.longOrNull("lastModified") ?: 0L,
            firstAudio.stringOrNull("xtags"),
          )
          // alpha.27:SABR 数据齐全 → 建 SabrSession 注册进 SabrStreamRegistry,返回 sabr://
          // progressive track(播放器走 MergingMediaSource(ProgressiveMediaSource×2),SabrStreamingDataSource
          // 把 read() 翻译成 SabrClient.fetch(init/seg))。nTransformed(classic)用 /player 数据直接建;
          // NO-CHANGE(plasma WASM)走 WebView harvest 取 transformed-n + body(poToken/ustreamerConfig/formatIds)。
          val sabrSession = if (nTransformed) {
            // classic 播放器:n-decrypt 成功 → 用 /player 数据(poToken+ustreamerCfgStr+firstVideo/firstAudio formatIds)建会话。
            SabrSession.fromSabrData(
              sabrUrlDeciphered, poToken, ustreamerCfgStr, innerTubeClient.sabrClientInfo(), aFmt, vFmt,
              userAgent = client.userAgent,
              cookieHeader = innerTubeClient.currentSessionCookies(),
              visitorData = innerTubeClient.currentVisitorData(),
              videoFormats = videoFormats,
            )
          } else {
            // plasma 播放器:n-transform 在 WASM 里,[YoutubeNDecryptor] 正则结构性失效(§6.7 row 43)
            // → sabrUrl 带 n 未解 → googlevideo 403。改让 Android WebView 浏览器引擎(原生跑 WASM)
            // 加载 watch 页替我们做 n-transform,采集其 SABR POST(url 已 transform + body 含
            // poToken/ustreamerConfig/formatIds)。alpha.26 已证:用 harvested body 解码出的会话参数
            // + 浏览器 cpn,SabrClient 自构 body 驱 init+seg 全 Success(§6.7 row 50)。
            Log.i(Tag, "SABR n-decrypt NO-CHANGE (plasma WASM) → harvest watch WebView")
            val capture = sabrHarvester.harvest(videoId)
            if (capture == null) {
              Log.w(Tag, "harvest: no capture (watch 页 30s 内未发 googlevideo 请求 — 页没加载/autoplay 被拦;查 YtSabrHarvest 日志)")
              null
            } else {
              val harvestedN = extractParam(capture.url, "n")
              val rawN = extractParam(sabrUrl, "n")
              val isPost = capture.method.equals("POST", ignoreCase = true)
              Log.i(
                Tag,
                "harvest: ${capture.method} status=${capture.status} " +
                  "n(harvested=${harvestedN ?: "ABSENT"} raw=${rawN ?: "ABSENT"} same=${harvestedN == rawN}) " +
                  "url=${capture.url} bodyB64=${capture.bodyB64.length}B " +
                  "→ ${if (isPost) "POST(build SabrSession from harvested body)" else "GET(SABR 需 POST body,skip)"}"
              )
              if (isPost) buildSabrSessionFromCapture(capture, client, videoFormats) else null
            }
          }
          if (sabrSession != null) {
            val sabrClient = SabrClient(httpClient)
            val sid = SabrStreamRegistry.registerByVideoId(videoId, sabrSession, sabrClient)
            // alpha.57(轮换):装填会话轮换工厂。服务端对每会话 ~60s 服务量上限,到点主动开新 harvest
            // 建新会话(锚定当前播放头),[requestRotation] 后台执行 → registerByVideoId 复用同 sid 覆盖
            // entry → DataSource 检测切换。捕获本 resolve 的 client/videoFormats(harvest 建会话要它们)。
            SabrStreamRegistry.rotationFactory = { v, startMs ->
              Log.i(Tag, "rotation: start harvest videoId=$v startMs=$startMs")
              val cap = sabrHarvester.harvest(v, startMs)
              if (cap == null) {
                Log.w(Tag, "rotation: harvest failed videoId=$v startMs=$startMs (keep old session)")
              } else if (!cap.method.equals("POST", ignoreCase = true)) {
                Log.w(Tag, "rotation: captured ${cap.method} not POST → skip (need SABR POST body)")
              } else {
                val newSession = buildSabrSessionFromCapture(cap, client, videoFormats)
                if (newSession == null) {
                  Log.w(Tag, "rotation: buildSabrSessionFromCapture failed videoId=$v (keep old session)")
                } else {
                  val newSid = SabrStreamRegistry.registerByVideoId(v, newSession, SabrClient(httpClient))
                  Log.i(Tag, "rotation: registered new session videoId=$v startMs=$startMs sid=$newSid (switched)")
                }
              }
            }
            Log.i(
              Tag,
              "SABR playback ready: sid=$sid nTransformed=$nTransformed " +
                "video=itag${sabrSession.videoFormatId.itag}(${sabrSession.videoFormatId.height}p) " +
                "audio=itag${sabrSession.audioFormatId.itag} → sabr:// progressive tracks"
            )
            return@withContext buildSabrPlaybackInfo(request, videoId, durationMs, raws, sabrSession, sid)
          }
        } else {
          Log.w(Tag, "SABR init probe skipped: video=${firstVideo != null} audio=${firstAudio != null}")
        }
      }
    }
    // 诊断:带/不带 token 对比——同一 videoId 再发一次无 token 的 WEB /player,对比 adaptive 条数/首条 url,
    // 判断 token 是否真的起作用(§6.7 row 28)。若带/不带 token 响应完全一样(都剥空)→ token 无效;
    // 若有差异 → token 在起作用但不够。走 WebView 原生栈与 with-token WEB 同路径,公平对比。
    if (poToken != null) {
      val noTokenPlayer = runCatching {
        postPlayer(videoId, client = InnerTubeClient.Client.WEB, poToken = null, signatureTimestamp = signatureTimestamp)
      }.getOrNull()
      val noTokenSd = noTokenPlayer?.obj("streamingData")
      val noTokenRaw = noTokenSd?.array("adaptiveFormats")
      val noTokenFirst = noTokenRaw?.firstOrNull() as? JsonObject
      val noTokenFirstUrl = noTokenFirst?.stringOrNull("url")
      val noTokenSabr = noTokenSd?.stringOrNull("serverAbrStreamingUrl")
      val noTokenUstreamerReq = noTokenPlayer?.obj("playerConfig")
        ?.obj("mediaCommonConfig")
        ?.obj("mediaUstreamerRequestConfig")
      val noTokenUstreamer = noTokenUstreamerReq?.obj("videoPlaybackUstreamerConfig")
      Log.i(
        Tag,
        "diag no-token WEB: playable=${noTokenPlayer?.obj("playabilityStatus")?.stringOrNull("status")} " +
          "rawAdaptive=${noTokenRaw?.size ?: 0} " +
          "firstUrl=${if (noTokenFirstUrl.isNullOrBlank()) "EMPTY" else "present(${noTokenFirstUrl.length}B)"} " +
          "sabrUrl=${if (noTokenSabr.isNullOrBlank()) "ABSENT" else "present(${noTokenSabr.length}B)"} " +
          "ustreamerReqCfg=${if (noTokenUstreamerReq == null) "ABSENT" else "present(${noTokenUstreamerReq.toString().length}B)"} " +
          "ustreamerCfg=${if (noTokenUstreamer == null) "ABSENT" else "present(${noTokenUstreamer.toString().length}B)"} " +
          "streamingData keys=${noTokenSd?.keys?.toList() ?: "NONE"} " +
          "(对比 with-token WEB 见上方 diag)"
      )
    }
    if (!havePlayable) {
      throw YoutubeApiException(0, "", "YouTube playback blocked: ${lastError ?: "no streamingData"}")
    }

    val videoCandidates = allAdaptive.filter { it.kind == Kind.Video && !it.combined }
    val audioCandidates = allAdaptive.filter { it.kind == Kind.Audio }

    // 取 base.js 用于 `n`/`s` 解密（仅当存在对应参数时拉取）。
    val playerJsUrl = resolvePlayerJsUrl(videoId)

    // 硬件能力过滤：不选 TV 解不了的 4K VP9/AV1（HEVC/AV1 无硬解时回退，避免黑屏/卡顿）。
    val decodableVideos = videoCandidates.filter { codecKeySupported(it.codecKey, codecCapability) }

    // 优先：adaptive 高清视频+音频双轨。fMP4 分片喂 ProgressiveMediaSource 会解析失败，
    // 故走 DASH 分支（segmentBase 由 initRange/indexRange 填充），由合成 MPD 播放。
    val adaptiveVideo = pickVideo(decodableVideos, codecPreference, request.preferredQualityId)
    if (adaptiveVideo != null) {
      val audio = pickAudio(audioCandidates)
      val videoUrl = resolveStreamUrl(adaptiveVideo, playerJsUrl)
      if (videoUrl.isNotBlank() && audio != null) {
        val audioUrl = resolveStreamUrl(audio, playerJsUrl)
        if (audioUrl.isNotBlank()) {
          return@withContext buildInfo(
            request = request,
            videoId = videoId,
            durationMs = durationMs,
            videoFmt = adaptiveVideo,
            audioFmt = audio,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            allQualities = buildQualityList(decodableVideos),
          )
        }
      }
    }
    // 兜底：单个合并 progressive 流(如 itag 18/22)是真实 mp4，ProgressiveMediaSource 可正确播放。
    val combined = allCombined.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
    if (combined != null) {
      val combinedUrl = resolveStreamUrl(combined, playerJsUrl)
      if (combinedUrl.isNotBlank()) {
        return@withContext buildInfo(
          request = request,
          videoId = videoId,
          durationMs = durationMs,
          videoFmt = combined,
          audioFmt = null,
          videoUrl = combinedUrl,
          audioUrl = "",
          allQualities = emptyList(),
        )
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
    allQualities: List<PlaybackQuality>,
  ): PlaybackInfo {
    val selectedQuality = PlaybackQuality(id = videoFmt.itag, description = videoFmt.qualityLabel)
    // 清晰度面板列出全部可播(已硬件过滤)的 adaptive 档位；progressive 兜底只一项。
    val qualities = allQualities.ifEmpty { listOf(selectedQuality) }
    val videoTrack = PlaybackTrack(
      id = videoFmt.itag,
      baseUrl = videoUrl,
      backupUrls = emptyList(),
      bandwidth = videoFmt.bitrate,
      codecs = videoFmt.codecs,
      width = videoFmt.width,
      height = videoFmt.height,
      mimeType = videoFmt.mimeType,
      segmentBase = videoFmt.toSegmentBase(),
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
          segmentBase = audioFmt.toSegmentBase(),
        ),
      )
    } else {
      emptyList()
    }
    val kindLabel = if (audioFmt != null) "${videoFmt.qualityLabel} + audio" else "${videoFmt.qualityLabel} (progressive)"
    Log.i(
      Tag,
      "resolve ok: $kindLabel itag=${videoFmt.itag}; nDecrypt=${if (videoUrl.contains("n=")) "left" else "applied"}; " +
        "dash=${if (videoFmt.toSegmentBase() != null) "yes" else "no"}; qualities=${qualities.size}",
    )
    return PlaybackInfo(
      bvid = videoId,
      cid = 0L,
      title = request.title,
      durationMs = durationMs,
      qualities = qualities,
      selectedQuality = selectedQuality,
      videoTracks = listOf(videoTrack),
      audioTracks = audioTracks,
      headers = YoutubePlaybackHeaders,
    )
  }

  /** 把全部可播 adaptive 视频档按分辨率/带宽降序整理成清晰度列表（去重 itag）。 */
  private fun buildQualityList(videos: List<ParsedFormat>): List<PlaybackQuality> {
    return videos
      .sortedWith(compareByDescending<ParsedFormat> { it.height }.thenByDescending { it.bitrate })
      .map { PlaybackQuality(id = it.itag, description = it.qualityLabel) }
      .distinctBy { it.id }
  }

  // ---- /player 请求与解析 ----

  private suspend fun postPlayer(
    videoId: String,
    client: InnerTubeClient.Client,
    poToken: String?,
    signatureTimestamp: Int?,
  ): JsonObject {
    val payload = buildJsonObject {
      put("videoId", videoId)
      put("contentCheckOk", true)
      put("racyCheckOk", true)
      put("playbackContext", buildJsonObject {
        put("contentPlaybackContext", buildJsonObject {
          // 对齐 youtubei.js getInfo 的 contentPlaybackContext(vis/splay/lactMilliseconds/signatureTimestamp)。
          // signatureTimestamp 从 player base.js 提取(§6.8.4 待补项),缺它 WEB /player 可能被判"非真浏览器"。
          put("vis", 0)
          put("splay", false)
          put("lactMilliseconds", "-1")
          if (signatureTimestamp != null) put("signatureTimestamp", signatureTimestamp)
          put("html5Preference", "HTML5_PREF_WANTS")
        })
      })
    }
    // WEB/WEB_EMBEDDED /player 走 WebView 原生网络栈(Chromium)，对齐 FreeTubeAndroid 主 WebView；
    // ANDROID 保持 OkHttp 直连(作为回退)。
    return innerTubeClient.postJson(
      "/player", payload, client = client, poToken = poToken,
      viaWebView = client == InnerTubeClient.Client.WEB || client == InnerTubeClient.Client.WEB_EMBEDDED,
    )
  }

  /** 从 watch 页 HTML 提取 base.js URL（用于 n/s 解密）。失败返回 null。结果缓存复用。 */
  private suspend fun resolvePlayerJsUrl(videoId: String): String? {
    cachedPlayerJsUrl?.let { return it }
    val url = withContext(Dispatchers.IO) {
      val page = runCatching {
        val req = Request.Builder()
          .url("https://www.youtube.com/watch?v=$videoId")
          .header("User-Agent", YoutubeConstants.MobileUserAgent)
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
    cachedPlayerJsUrl = url
    return url
  }

  /**
   * 从 player base.js 提取 signatureTimestamp（对齐 youtubei.js Player.ts #getSignatureTimestamp）。
   * 结果缓存复用。失败返回 null（不阻塞 /player，仅少一个反爬字段）。
   */
  private suspend fun resolveSignatureTimestamp(videoId: String): Int? {
    signatureTimestamp?.let { return it }
    val playerJsUrl = resolvePlayerJsUrl(videoId) ?: return null
    val js = runCatching {
      val req = Request.Builder()
        .url(playerJsUrl)
        .header("User-Agent", YoutubeConstants.MobileUserAgent)
        .build()
      httpClient.newCall(req).execute().use { it.body?.string().orEmpty() }
    }.getOrNull()
    if (js.isNullOrBlank()) {
      Log.w(Tag, "signatureTimestamp: base.js fetch failed/blank")
      return null
    }
    val ts = Regex("""signatureTimestamp:(\d+)""").find(js)?.groupValues?.get(1)?.toIntOrNull()
    if (ts != null) {
      signatureTimestamp = ts
      Log.i(Tag, "signatureTimestamp=$ts")
    } else {
      Log.w(Tag, "signatureTimestamp not found in base.js")
    }
    return ts
  }

  private suspend fun resolveStreamUrl(format: ParsedFormat, playerJsUrl: String?): String {
    var url = format.url
    // signatureCipher 形态：url 缺失，需解 s + sp 并回填（best-effort，缺 base.js 时用原始 url 兜底 → 多半 403）。
    if (url.isBlank() && format.signatureCipher != null) {
      url = signatureCipherUrl(format.signatureCipher, playerJsUrl)
    }
    if (url.isBlank()) return ""
    // 解密 `n`（base.js 不可用/解密失败时保留原 url，多半 403 由播放器报错暴露）。
    if (url.contains("n=") && playerJsUrl != null) {
      url = nDecryptor.decrypt(url, playerJsUrl)
    }
    return url
  }

  /** signatureCipher "s=..&sp=..&url=.." 的解析 + `s` 解密；失败回填原始 url。 */
  private suspend fun signatureCipherUrl(cipher: String?, playerJsUrl: String?): String {
    if (cipher.isNullOrBlank()) return ""
    val parts = cipher.split("&").associate { entry ->
      val idx = entry.indexOf('=')
      if (idx < 0) entry to "" else entry.substring(0, idx) to entry.substring(idx + 1)
    }
    val baseUrl = parts["url"] ?: return ""
    val s = parts["s"] ?: return baseUrl
    val sp = parts["sp"] ?: "signature"
    if (playerJsUrl != null) {
      val deciphered = sDecryptor.decrypt(s, playerJsUrl)
      if (deciphered != null) {
        return replaceParam(baseUrl, sp, deciphered)
      }
    }
    return baseUrl
  }

  /**
   * SABR `server_abr_streaming_url` 的 decipher——对齐 googlevideo 示例
   * `innertube.session.player.decipher(serverAbrStreamingUrl)`。googlevideo URL 带 `n` 签名参数,
   * 未用 base.js transform 解出真值则返回 403 空体(alpha.18 实测 Server=gvs 1.0 Content-Length=0)。
   * 同时 dump 全量 param key + n/sig/s/pot 存在性——alpha.18 仅截 200 字看不到 n 是否在 URL 里,
   * 此日志在 403 持续时定位是「n 未解」还是「poToken 绑定错」还是「URL 本无 n」。
   * 无 `n` 或无 base.js 时原样返回(best-effort)。
   */
  private suspend fun decipherSabrUrl(url: String, playerJsUrl: String?): String {
    val query = url.substringAfter("?", "")
    val params = query.split("&").mapNotNull { e ->
      val i = e.indexOf("=")
      if (i < 0) e to "" else e.substring(0, i) to e.substring(i + 1)
    }.toMap()
    val hasN = params.containsKey("n")
    val hasSig = params.containsKey("sig")
    val hasS = params.containsKey("s")
    val hasPot = params.containsKey("pot")
    Log.i(Tag, "sabrUrl params: keys=${params.keys.toList()} n=$hasN sig=$hasSig s=$hasS pot=$hasPot playerJs=${playerJsUrl != null}")
    if (!hasN || playerJsUrl == null) return url
    val transformed = nDecryptor.decrypt(url, playerJsUrl)
    Log.i(Tag, "sabrUrl n-decrypt: ${if (transformed == url) "NO-CHANGE(transform fail/no-op)" else "applied"}")
    return transformed
  }

  private fun replaceParam(url: String, key: String, value: String): String {
    val start = url.indexOf("$key=")
    if (start < 0) return url
    val valueStart = start + key.length + 1
    val end = url.indexOf('&', valueStart).let { if (it < 0) url.length else it }
    return url.substring(0, valueStart) + value + url.substring(end)
  }

  /** 取 URL query 参数值(首段 ? 之后,不依赖正则)。 */
  private fun extractParam(url: String, key: String): String? {
    val query = url.substringAfter("?", "")
    return query.split("&").firstNotNullOfOrNull { e ->
      val i = e.indexOf("=")
      if (i < 0) null else if (e.substring(0, i) == key) e.substring(i + 1) else null
    }
  }

  /**
   * alpha.27:从 harvested POST(url+body)构造 [SabrSession](plasma 路径)。
   * 解码 body 取 poToken/ustreamerConfig/formatIds(浏览器会话绑定值),用浏览器 cpn 绑会话,
   * 剥 alr/cpn/rn 得 base sabrUrl(fromSabrData 再加 alr=yes+cpn)。alpha.26 已证此会话 +
   * SabrClient 自构 body 驱 init+seg 全 Success(§6.7 row 50)。返回 null 表示 body/formatIds/cpn 缺失
   * → 上层回退普通 adaptive/progressive 路径。
   */
  private suspend fun buildSabrSessionFromCapture(capture: YoutubeSabrHarvester.SabrCapture, client: InnerTubeClient.Client, videoFormats: List<SabrFormatId>): SabrSession? {
    val body = runCatching { Base64.decode(capture.bodyB64, Base64.DEFAULT) }.getOrNull()
    if (body == null || body.isEmpty()) {
      Log.w(Tag, "SABR session: body empty (len=${capture.bodyB64.length}) — hook 没捕到 body?")
      return null
    }
    val decoded = SabrProto.decodeVideoPlaybackAbrRequest(body)
    val browserCpn = extractParam(capture.url, "cpn")
    // 剥 alr/cpn/rn → base sabrUrl(fromSabrData 再加 alr=yes+cpn=browserCpn;SabrClient.fetch 加 rn)。
    // cver 等浏览器参数保留(与 alpha.25/26 replay 一致)。
    val baseSabrUrl = capture.url.split("&").filterNot {
      it.startsWith("alr=") || it.startsWith("cpn=") || it.startsWith("rn=")
    }.joinToString("&").let { if (it.startsWith("http")) it else "&$it" }
    val audioFmt = decoded.audioFormatId
    val videoFmt = decoded.videoFormatId
    Log.i(
      Tag,
      "SABR session: decoded poToken=${decoded.poToken.size}B ustreamerCfg=${decoded.ustreamerConfig.size}B " +
        "audio=$audioFmt video=$videoFmt cpn=$browserCpn base=${baseSabrUrl.take(120)}..."
    )
    if (audioFmt == null || videoFmt == null || browserCpn == null) {
      Log.w(Tag, "SABR session: missing formatIds/cpn (audio=$audioFmt video=$videoFmt cpn=$browserCpn)")
      return null
    }
    return SabrSession.fromSabrData(
      baseSabrUrl,
      Base64.encodeToString(decoded.poToken, Base64.NO_WRAP),
      Base64.encodeToString(decoded.ustreamerConfig, Base64.NO_WRAP),
      innerTubeClient.sabrClientInfo(),
      SabrFormatId(audioFmt.itag, audioFmt.lastModified, audioFmt.xtags, 0),
      SabrFormatId(videoFmt.itag, videoFmt.lastModified, videoFmt.xtags, 0),
      userAgent = client.userAgent,
      cookieHeader = innerTubeClient.currentSessionCookies(),
      visitorData = innerTubeClient.currentVisitorData(),
      cpn = browserCpn,
      videoFormats = videoFormats,
    )
  }

  /**
   * alpha.27:把 SABR 会话包成 [PlaybackInfo]——两条 `sabr://youtube/<sid>?stream=video|audio`
   * progressive track(segmentBase=null → 播放器走 MergingMediaSource(ProgressiveMediaSource×2),
   * SabrStreamingDataSource 把 read() 翻译成 SabrClient.fetch(init/seg))。track 元数据
   * (codecs/width/height)按会话 formatId 的 itag 从 /player adaptive 原始 JSON 取,确保与 SABR
   * 实际服务的格式一致。
   *
   * alpha.29:多清晰度——`qualities` = 会话全部视频 itag(从 videoFormats,按 height 降序),
   * `videoTracks` = 仅选中 itag 的一条(progressive 分支只播 first(),见 PlayerScreen MergingMediaSource
   * 构建),`selectedQuality` = `preferredQualityId` 命中菜单则用之,否则默认 videoFormatId(harvested)。
   * 切清晰度:播放器用 preferredQualityId 重跑 resolve → 缓存命中跳过 harvest → 用新 itag 建 PlaybackInfo
   * → 重建 MediaSource(新 `sabr://...&itag=N` → SabrStreamingDataSource 按新 itag 请求)。poToken 会话级
   * 不绑 itag(FreeTube 证实),同 sid 换 itag 即换清晰度,无需重 harvest。
   */
  private fun buildSabrPlaybackInfo(
    request: PlaybackRequest,
    videoId: String,
    durationMs: Long,
    raws: List<JsonObject>,
    sabrSession: SabrSession,
    sid: String,
  ): PlaybackInfo {
    val aItag = sabrSession.audioFormatId.itag
    val aRaw = raws.firstOrNull { (it.longOrNull("itag")?.toInt() ?: 0) == aItag }
    val audioTrack = buildSabrTrack(aItag, aRaw, "audio", sid, videoId)

    // 全部视频 itag 作清晰度菜单;videoFormats 为空(classic 仅首条)则兜底默认 videoFormatId。
    val videoFmts = sabrSession.videoFormats.ifEmpty { listOf(sabrSession.videoFormatId) }
    val qualities = videoFmts.sortedByDescending { it.height }.map { fmt ->
      val raw = raws.firstOrNull { (it.longOrNull("itag")?.toInt() ?: 0) == fmt.itag }
      val h = raw?.intOrNull("height") ?: fmt.height
      val codec = shortCodec(extractCodecs(raw?.stringOrNull("mimeType") ?: ""))
      PlaybackQuality(
        id = fmt.itag,
        description = (if (h > 0) "${h}p" else "itag ${fmt.itag}") + (if (codec.isNotEmpty()) " $codec" else ""),
      )
    }
    // 选档:preferredQualityId 命中菜单用之;否则默认 videoFormatId(harvested/首条,服务端已验过)。
    val selectedItag = request.preferredQualityId
      ?.takeIf { pid -> videoFmts.any { it.itag == pid } }
      ?: sabrSession.videoFormatId.itag
    val selectedQuality = qualities.firstOrNull { it.id == selectedItag } ?: qualities.first()
    val vRaw = raws.firstOrNull { (it.longOrNull("itag")?.toInt() ?: 0) == selectedItag }
    val videoTrack = buildSabrTrack(selectedItag, vRaw, "video", sid, videoId)
    Log.i(
      Tag,
      "SABR PlaybackInfo: sid=$sid qualities=${qualities.size} selected=itag$selectedItag(${videoTrack.height}p ${videoTrack.codecs}) " +
        "audio=itag$aItag(${audioTrack.codecs}) duration=${durationMs}ms → sabr:// progressive"
    )
    return PlaybackInfo(
      bvid = videoId,
      cid = 0L,
      title = request.title,
      durationMs = durationMs,
      qualities = qualities,
      selectedQuality = selectedQuality,
      videoTracks = listOf(videoTrack),
      audioTracks = listOf(audioTrack),
      headers = YoutubePlaybackHeaders,
    )
  }

  /** 构造单条 SABR progressive track。元数据从 /player adaptive 原始 JSON 取,缺则用合理默认。
   *  alpha.29:视频流 baseUrl 带 `&itag=<itag>`(同 sid 换 itag 即换清晰度);audio 不带(用会话默认)。 */
  private fun buildSabrTrack(itag: Int, raw: JsonObject?, stream: String, sid: String, videoId: String): PlaybackTrack {
    val rawMime = raw?.stringOrNull("mimeType")
    val mime = (rawMime ?: if (stream == "video") "video/mp4" else "audio/mp4").substringBefore(";").trim()
    val codecs = extractCodecs(rawMime ?: "")
    val isVideo = stream == "video"
    // alpha.57(轮换):URI 带 `&videoId=`——DataSource 主动旋转到 ~45s 时需知道 videoId 触发
    // [SabrStreamRegistry.requestRotation](工厂按 videoId 建新会话)。轮换复用同 sid,URI 不变。
    val baseUrl = if (isVideo) "sabr://youtube/$sid?stream=video&itag=$itag&videoId=$videoId"
      else "sabr://youtube/$sid?stream=audio&videoId=$videoId"
    return PlaybackTrack(
      id = itag,
      baseUrl = baseUrl,
      backupUrls = emptyList(),
      bandwidth = raw?.intOrNull("bitrate") ?: 0,
      codecs = codecs,
      width = if (isVideo) (raw?.intOrNull("width") ?: 0) else 0,
      height = if (isVideo) (raw?.intOrNull("height") ?: 0) else 0,
      mimeType = mime,
      // null → 播放器 progressive 分支(MergingMediaSource),SabrStreamingDataSource 接管 sabr://。
      segmentBase = null,
    )
  }

  /** alpha.29:清晰度描述用的简短 codec 标签(区分同高度多 codec,如 1080p H264 vs VP9 vs AV1)。 */
  private fun shortCodec(codecs: String): String = when {
    codecs.contains("av01", true) -> "AV1"
    codecs.contains("vp09", true) || codecs.contains("vp9", true) -> "VP9"
    codecs.contains("avc1", true) || codecs.contains("avc3", true) -> "H264"
    codecs.contains("hevc", true) || codecs.contains("hvc1", true) -> "HEVC"
    else -> ""
  }

  private suspend fun replaySabrCapture(capture: YoutubeSabrHarvester.SabrCapture, client: InnerTubeClient.Client) {
    val body = runCatching { Base64.decode(capture.bodyB64, Base64.DEFAULT) }.getOrNull()
    if (body == null || body.isEmpty()) {
      Log.w(Tag, "SABR replay: body decode failed/empty (len=${capture.bodyB64.length}) — hook 仍没捕到 body?")
      return
    }
    // alpha.24 真机:空 body 重放得 `sabr.malformed_config`;捕到真 body(4632B)后重放得 105B UMP
    // (非 malformed_config)但仍无 MEDIA。推测:alpha.24 剥了 cpn → URL 失会话绑定 → 服务端只回
    // context update 不发媒体。**保留浏览器 cpn**(它绑定 body 的 poToken/ustreamerConfig 会话),
    // 只剥 rn(重置为 0 做 re-init),让 cpn+body+transport 三者会话一致。
    val strippedRn = capture.url.split("&").filterNot { it.startsWith("rn=") }
      .joinToString("&").let { if (it.startsWith("http")) it else "&$it" }
    val replayUrl = if (strippedRn.contains("rn=")) strippedRn else "${strippedRn}&rn=0"
    val hasCpn = replayUrl.contains("cpn=")
    val request = Request.Builder()
      .url(replayUrl)
      .post(body.toRequestBody("application/x-protobuf".toMediaType()))
      .header("accept-encoding", "identity")
      .header("accept", "application/vnd.yt-ump")
      .header("User-Agent", client.userAgent)
      .header("Cookie", innerTubeClient.currentSessionCookies())
      .header("X-Goog-Visitor-Id", innerTubeClient.currentVisitorData())
      .header("Origin", "https://www.youtube.com")
      .header("Referer", "https://www.youtube.com/")
      .build()
    runCatching {
      httpClient.newCall(request).execute().use { r ->
        val ct = r.header("Content-Type")
        val bodyBytes = r.body?.byteStream()?.use { it.readBytes() }
        Log.i(
          Tag,
          "SABR replay: HTTP ${r.code} Content-Type=$ct body=${bodyBytes?.size ?: 0}B " +
            "cpn=$hasCpn → ${bodyBytes?.take(60)?.joinToString("") { "%02x".format(it) } ?: ""}"
        )
        // alpha.25:用 UmpReader + SabrProto 解析响应 UMP,逐 part log 类型+内容——
        // 定位 105B 是 SABR_ERROR(会话/poToken 不匹配?) / SABR_REDIRECT(换节点?) /
        // NEXT_REQUEST_POLICY(backoff?) / SABR_CONTEXT_UPDATE(无媒体) / MEDIA_HEADER(成功?)。
        if (bodyBytes != null && (ct?.contains("yt-ump") == true)) {
          val ump = UmpReader()
          ump.append(bodyBytes)
          ump.readParts { type, payload ->
            val name = partName(type)
            val detail = when (type) {
              SabrProto.PART_SABR_ERROR -> SabrProto.decodeSabrError(payload)?.let { "type=${it.type} code=${it.code}" }
              SabrProto.PART_SABR_REDIRECT -> "url=${SabrProto.decodeSabrRedirect(payload)?.take(120)}"
              SabrProto.PART_STREAM_PROTECTION_STATUS -> "status=${SabrProto.decodeStreamProtectionStatus(payload)}"
              SabrProto.PART_NEXT_REQUEST_POLICY -> SabrProto.decodeNextRequestPolicy(payload)?.let { "backoff=${it.backoffTimeMs}ms cookie=${it.playbackCookie != null} vid=${it.videoId}" }
              SabrProto.PART_MEDIA_HEADER -> SabrProto.decodeMediaHeader(payload)?.let { "headerId=${it.headerId} itag=${it.itag} lmt=${it.lmt} isInit=${it.isInitSeg} seq=${it.sequenceNumber} contentLen=${it.contentLength} dur=${it.durationMs}ms" }
              else -> "payloadLen=${payload.size}"
            }
            Log.i(Tag, "SABR replay UMP part: type=$type($name) $detail")
          }
        }
      }
    }.onFailure { Log.w(Tag, "SABR replay failed: ${it.message}") }
  }

  /** UMP part type 数值 → 可读名(诊断用,对齐 SabrClient.partName)。 */
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

  // ---- 格式挑选 ----

  private fun pickVideo(
    candidates: List<ParsedFormat>,
    preference: PlaybackCodecPreference,
    preferredItag: Int?,
  ): ParsedFormat? {
    // 用户在清晰度面板选中具体 itag（如 1080p/2K/4K）时，优先命中该档。
    if (preferredItag != null) {
      candidates.firstOrNull { it.itag == preferredItag }?.let { return it }
    }
    // 最大化分辨率，codec 偏好仅在同分辨率下打破平局。避免旧逻辑「avc 优先」压过更高的 vp9/av01。
    return candidates.maxWithOrNull(
      compareBy<ParsedFormat> { it.height }
        .thenByDescending { codecRank(it.codecKey, preference) }
        .thenBy { it.bitrate },
    )
  }

  /** codec 偏好秩：偏好 codec 排最前，越靠前数字越小。 */
  private fun codecRank(codecKey: String, preference: PlaybackCodecPreference): Int {
    val order = when (preference) {
      PlaybackCodecPreference.H264 -> listOf("avc", "vp9", "av01", "hevc", "other")
      PlaybackCodecPreference.H265 -> listOf("hevc", "avc", "vp9", "av01", "other")
      PlaybackCodecPreference.Av1 -> listOf("av01", "vp9", "avc", "hevc", "other")
      PlaybackCodecPreference.Auto -> listOf("avc", "vp9", "av01", "hevc", "other")
    }
    return order.indexOf(codecKey).let { if (it < 0) order.size else it }
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
    val rawMimeType = node.stringOrNull("mimeType").orEmpty()
    val codecs = extractCodecs(rawMimeType)
    val kind = when {
      rawMimeType.startsWith("video/") -> Kind.Video
      rawMimeType.startsWith("audio/") -> Kind.Audio
      else -> return null
    }
    if (kind == Kind.Video && (node.intOrNull("height") ?: 0) <= 0) return null
    val cipher = node.stringOrNull("signatureCipher")
    val url = node.stringOrNull("url") ?: if (cipher != null) "" else null
    if (url == null && cipher == null) return null
    // 净化 MIME：去掉 "; codecs=..." 尾缀，只留 "video/mp4"/"audio/mp4"/"video/webm"，
    // 否则 buildDashManifest 会把完整串写进 <AdaptationSet mimeType> 破坏 MPD 解析。
    val cleanMimeType = rawMimeType.substringBefore(";").trim()
    return ParsedFormat(
      itag = itag,
      mimeType = cleanMimeType,
      codecs = codecs,
      codecKey = codecKey(codecs),
      width = node.intOrNull("width") ?: 0,
      height = node.intOrNull("height") ?: 0,
      bitrate = node.intOrNull("bitrate") ?: 0,
      qualityLabel = node.stringOrNull("qualityLabel") ?: "${node.intOrNull("height") ?: 0}p",
      url = url.orEmpty(),
      signatureCipher = cipher,
      // on-demand fMP4 的 DASH SegmentBase（adaptive 有；progressive 无）。喂合成 MPD 用。
      initRange = node.rangeString("initRange"),
      indexRange = node.rangeString("indexRange"),
      // 合并流(音视频一体，如 progressive itag 18)的 mimeType codecs 列表里含音频 codec(mp4a/opus)。
      // 注意 extractCodecs 只留第一个(视频)codec，故用原始 rawMimeType 判定。
      combined = rawMimeType.contains("mp4a", ignoreCase = true) || rawMimeType.contains("opus", ignoreCase = true),
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

  /** 视频 codec 是否设备可解。VP9/VP8 广泛支持且探测未单列，放行；HEVC/AV1 以探测结果为准。 */
  private fun codecKeySupported(codecKey: String, capability: CodecCapability): Boolean {
    return when (codecKey) {
      "avc" -> capability.supportsH264
      "hevc" -> capability.supportsH265
      "av01" -> capability.supportsAv1
      "vp9", "vp8", "other" -> true
      else -> true
    }
  }

  /** 由 YouTube JSON 的 `initRange`/`indexRange` 构造 [PlaybackSegmentBase]（无则 null → progressive）。 */
  private fun ParsedFormat.toSegmentBase(): PlaybackSegmentBase? {
    return if (initRange.isNotBlank() && indexRange.isNotBlank()) {
      PlaybackSegmentBase(initializationRange = initRange, indexRange = indexRange)
    } else {
      null
    }
  }

  // ---- Json 辅助 ----

  // 全部用可空 receiver，兼容 runCatching.getOrNull() 可能为 null 的 /player 响应。
  private fun JsonObject?.obj(name: String): JsonObject? = this?.get(name) as? JsonObject
  private fun JsonObject?.array(name: String): JsonArray? = this?.get(name) as? JsonArray
  private fun JsonObject?.stringOrNull(name: String): String? = this?.get(name)?.jsonPrimitive?.contentOrNull
  private fun JsonObject?.intOrNull(name: String): Int? = this?.get(name)?.jsonPrimitive?.content?.toIntOrNull()
  private fun JsonObject?.longOrNull(name: String): Long? = this?.get(name)?.jsonPrimitive?.content?.toLongOrNull()

  /** YouTube `initRange`/`indexRange` 形如 `{ "start":"0", "end":"794" }` → 拼成 "0-794"。 */
  private fun JsonObject.rangeString(name: String): String {
    val range = obj(name) ?: return ""
    val start = range.longOrNull("start")
    val end = range.longOrNull("end")
    return if (start != null && end != null) "$start-$end" else ""
  }

  /** SABR 探针结果摘要(§6.9)。 */
  private fun summarizeSabrResult(r: SabrFetchResult): String = when (r) {
    is SabrFetchResult.Success ->
      "Success bytes=${r.data.size}B headerId=${r.mediaHeader?.headerId} itag=${r.mediaHeader?.itag} isInit=${r.mediaHeader?.isInitSeg} contentLen=${r.mediaHeader?.contentLength} dur=${r.mediaHeader?.durationMs}ms"
    is SabrFetchResult.Redirect -> "Redirect -> ${r.sanitized}"
    is SabrFetchResult.Backoff -> "Backoff ${r.ms}ms"
    is SabrFetchResult.ReloadPlayer -> "ReloadPlayer (part 46, terminal) ${r.dump.take(120)}"
    SabrFetchResult.InvalidPoToken -> "InvalidPoToken (STREAM_PROTECTION_STATUS=3)"
    is SabrFetchResult.Error -> "Error: ${r.message}"
  }

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
    /** DASH SegmentBase range（on-demand fMP4），如 "0-794"。无则 null 走 progressive。 */
    val initRange: String,
    /** DASH SegmentBase indexRange，如 "795-1438"。 */
    val indexRange: String,
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
