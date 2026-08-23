package com.kirin.mt.core.youtube

import android.util.Log
import com.kirin.mt.core.download.ResolvedDownload
import com.kirin.mt.core.download.ResolvedPart
import com.kirin.mt.core.player.BiliPlaybackHeaders
import com.kirin.mt.core.player.CodecCapability
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackAudioTrack
import com.kirin.mt.core.player.PlaybackInfo
import com.kirin.mt.core.player.PlaybackQuality
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.PlaybackSegmentBase
import com.kirin.mt.core.player.PlaybackTrack
import com.kirin.mt.core.player.YoutubeDefaultQuality
import com.kirin.mt.core.player.YoutubeDeliveryPriority
import com.kirin.mt.core.youtube.sabr.FormatId as SabrFormatId
import com.kirin.mt.core.youtube.sabr.SabrAudioTrack
import com.kirin.mt.core.youtube.sabr.SabrClient
import com.kirin.mt.core.youtube.sabr.SabrFetchResult
import com.kirin.mt.core.youtube.sabr.SabrSession
import com.kirin.mt.core.youtube.sabr.SabrStreamRegistry
import com.kirin.mt.core.youtube.newpipe.NewPipePoTokenGenerator
import com.kirin.mt.core.youtube.piped.PipedClient
import com.kirin.mt.core.youtube.piped.PipedStreams
import com.kirin.mt.core.youtube.piped.PipedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
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
  private val biliTvPoTokenProvider: NewPipePoTokenGenerator,
  /** Piped 后端客户端(可选,实验:对齐 LibreTube 默认 Piped 路径修 RELOAD)。null = 走 NewPipe(旧行为)。 */
  private val pipedClient: PipedClient? = null,
  /** 设置存储(读 youtubeUsePiped/pipedInstanceUrl/sabrForceSessionVideoItag)。null = 全 false/空(旧行为)。 */
  private val appSettingsStore: com.kirin.mt.core.settings.AppSettingsStore? = null,
) {

  /** 从 player base.js 提取的 signatureTimestamp（对齐 youtubei.js Player.ts #getSignatureTimestamp）。 */
  private var signatureTimestamp: Int? = null

  /** 缓存的 base.js URL（避免 resolvePlayerJsUrl 重复拉 watch 页）。 */
  private var cachedPlayerJsUrl: String? = null

  suspend fun resolve(
    request: PlaybackRequest,
    codecPreference: PlaybackCodecPreference,
    codecCapability: CodecCapability,
    youtubeDefaultQuality: YoutubeDefaultQuality = YoutubeDefaultQuality.Auto,
  ): PlaybackInfo = withContext(Dispatchers.IO) {
    val videoId = request.bvid
    var lastError: String? = null
    var havePlayable = false

    // 播放路径优先级:true = DASH 自合成优先(慢 SABR 首段被 stall 看门狗误杀场景的逃生通道,见 youtube-hd-playback.md)。
    val dashFirst = appSettingsStore?.settings?.first()?.youtubeDeliveryPriority == YoutubeDeliveryPriority.Dash

    // ── Piped 后端 opt-in（实验:对齐 LibreTube 默认 Piped 路径,修 RELOAD_PLAYER_RESPONSE 死循环）──
    // 用户在设置开 youtubeUsePiped 后,先走 Piped `/streams/{videoId}`。Piped 实例自带 poToken 请求
    // YouTube,回**已 attested 的 WEB-bound** ustreamerConfig(见 [docs/youtube-hd-playback.md]
    // 「alpha.83 更正」段)——首请求发空 poToken,服务端回 status=2 时再铸 WEB poToken 续命,绕过
    // NewPipe visionOS 路径拿未 attested config 致 RELOAD 的问题。Piped 失败/无 SABR 数据自动回退 NewPipe。
    if (pipedClient != null && appSettingsStore != null && !dashFirst) {
      val cfg = appSettingsStore.settings.first()
      if (cfg.youtubeUsePiped) {
        val instance = cfg.pipedInstanceUrl.ifBlank { DEFAULT_PIPED_INSTANCE }
        val piped = runCatching { pipedClient.fetchStreams(videoId, instance) }.getOrNull()
        if (piped != null &&
          !piped.serverAbrStreamingUrl.isNullOrBlank() &&
          !piped.videoPlaybackUstreamerConfig.isNullOrBlank()
        ) {
          val r = buildSabrSessionFromPiped(videoId, piped, youtubeDefaultQuality)
          if (r != null) {
            YoutubeLoadProgress.emit(YoutubeLoadStep.BuildSession)
            val sabrClient = SabrClient(httpClient)
            val sid = SabrStreamRegistry.registerByVideoId(
              videoId,
              r.session,
              sabrClient,
              refreshPoToken = { biliTvPoTokenProvider.getWebClientPoToken(videoId)?.streamingDataPoToken?.toByteArray(Charsets.UTF_8) },
              forceSessionVideoItag = cfg.sabrForceSessionVideoItag,
            )
            YoutubeLoadProgress.emit(YoutubeLoadStep.Connect)
            Log.i(
              Tag,
              "SABR playback ready: sid=$sid source=Piped instance=$instance " +
                "video=itag${r.session.videoFormatId.itag} audio=itag${r.session.audioFormatId.itag}"
            )
            return@withContext buildSabrPlaybackInfo(
              request,
              videoId,
              r.durationMs,
              r.raws,
              r.session,
              sid,
              subtitleTracks = r.subtitleTracks,
              youtubeDefaultQuality = youtubeDefaultQuality,
            )
          }
          Log.w(Tag, "Piped path: buildSabrSessionFromPiped returned null (instance=$instance) → fall back to NewPipe")
        } else {
          Log.w(Tag, "Piped path: no SABR data from $instance (sabrUrl/ustreamerCfg absent) → fall back to NewPipe")
        }
      }
    }

    // 生成视频 ID 绑定的 PO token（best-effort）。无 PO token 时 YouTube 剥掉 adaptive 高清 url
    // （只剩 progressive 360p）；有 token 才能拿高清直链。失败降级为无 token 直连。
    YoutubeLoadProgress.emit(YoutubeLoadStep.FetchPlayer)
    val poToken = botGuard.generatePoToken(videoId)
    if (poToken != null) Log.i(Tag, "PO token minted (${poToken.length} chars)") else Log.w(Tag, "PO token unavailable; degrade to no-token")
    YoutubeLoadProgress.emit(YoutubeLoadStep.MintToken)

    // 提取 signatureTimestamp（对齐 youtubei.js Player.ts #getSignatureTimestamp），注入 /player
    // 的 contentPlaybackContext。缺它 WEB /player 可能被判"非真浏览器" → "The page needs to be reloaded"。
    val signatureTimestamp = resolveSignatureTimestamp(videoId)

    // ── NewPipe-first 主路径(alpha.93):对齐 LibreTube 直调 NewPipe getInfo,不依赖 WEB /player WebView harvest ──
    // alpha.89 WebView harvest 坏(卡 m.youtube.com 错误页 27s)→ 先走自包含的 NewPipe(visonOS SABR → DASH 兜底)。
    // ① visionOS NewPipe SABR(alpha.91 Fix A register+refreshPoToken 在此生效;Fix B 已使 getInfo 不带 WEB visitor
    //    → status=2 懒鉴权,空 poToken 首请求)。buildSabrSessionFromNewPipe 内部 L806 poTokenB64="" 不用外部 poToken。
    //
    // alpha.9X:SABR 死循环守卫。attestation 视频(4K/HD)首次 RELOAD 后 reloadCount>0,重建空 poToken 会话
    // 必再 RELOAD(alpha.93 NewPipe-first 早退使下方 consumeReloadTokenSlot/MAX_RELOADS 闸门不可达,曾 reloadCount
    // 17→24 无界爬升直至 evict→Source error;对齐 LibreTube 对 RELOAD 直接失败不循环)。RELOAD 后跳过 SABR,
    // 直接落 ② 的 DASH/HLS 兜底。注意:DASH 自合成兜底(NewPipe 已解密直链拼 MPD)不走 SABR attestation,
    // 实测能出 4K(2160p VP9,见 docs youtube-hd-playback.md「alpha.9X」),不是只 ≤1080p。
    //
    // youtubeDeliveryPriority=Dash(用户设「DASH 优先」):先走 DASH 自合成兜底,成功直接返回;失败才落 SABR。
    // 逃生通道——慢 SABR 首段(googlevideo 服务器 >10s 才送首段)会被 8s stall 看门狗误判完整重建。
    if (dashFirst) {
      val dashFirstRes = runCatching { buildDashFallbackFromNewPipe(videoId, 0L, request, youtubeDefaultQuality) }.getOrNull()
      if (dashFirstRes != null) {
        YoutubeLoadProgress.emit(YoutubeLoadStep.Connect)
        Log.i(Tag, "NewPipe-first(DASH 优先) → DASH/HLS playback ready: videoId=$videoId → ${if (dashFirstRes.remoteHlsManifestUrl != null) "HLS" else "DASH"}")
        return@withContext dashFirstRes
      }
    }
    val np = if (SabrStreamRegistry.reloadCount(videoId) > 0) {
      Log.w(Tag, "SABR dead-loop guard: videoId=$videoId 已 RELOAD(reloadCount=${SabrStreamRegistry.reloadCount(videoId)})→ 跳过重建,直接 DASH/HLS 兜底")
      null
    } else {
      buildSabrSessionFromNewPipe(videoId, poToken, youtubeDefaultQuality)
    }
    if (np != null) {
      YoutubeLoadProgress.emit(YoutubeLoadStep.BuildSession)
      val sabrClient = SabrClient(httpClient)
      // alpha.65:注入 PO token 刷新回调——SABR status=2(Attestation pending)时用 PoTokenWebView 重铸
      // streamingDataPoToken(alpha.91 Fix A:统一 minter,替 YoutubeBotGuard PLACEHOLDER),对齐 LibreTube。
      val sid = SabrStreamRegistry.registerByVideoId(
        videoId, np.session, sabrClient,
        refreshPoToken = { biliTvPoTokenProvider.getWebClientPoToken(videoId)?.streamingDataPoToken?.toByteArray(Charsets.UTF_8) },
      )
      YoutubeLoadProgress.emit(YoutubeLoadStep.Connect)
      Log.i(
        Tag,
        "SABR playback ready: sid=$sid source=NewPipe(primary) " +
          "video=itag${np.session.videoFormatId.itag}(${np.session.videoFormatId.height}p) " +
          "audio=itag${np.session.audioFormatId.itag} → sabr:// DASH"
      )
      return@withContext buildSabrPlaybackInfo(
        request, videoId, np.durationMs, np.raws, np.session, sid,
        subtitleTracks = np.subtitleTracks.orEmpty(),
        youtubeDefaultQuality = youtubeDefaultQuality,
      )
    }
    // ② NewPipe 无 SABR → DASH/HLS 兜底(alpha.92 自合成 DASH 为主,次 dashMpdUrl[恒空]/HLS)。durationMs 传 0
    //    → buildDashFallbackFromNewPipe 内部用 info.duration 兜底。dashFirst 时 DASH 已先试过,跳过。
    if (!dashFirst) {
      val npDash = runCatching { buildDashFallbackFromNewPipe(videoId, 0L, request, youtubeDefaultQuality) }.getOrNull()
      if (npDash != null) {
        YoutubeLoadProgress.emit(YoutubeLoadStep.Connect)
        Log.i(Tag, "NewPipe-first → DASH/HLS 兜底 playback ready: videoId=$videoId → ${if (npDash.remoteHlsManifestUrl != null) "HLS" else "DASH"}")
        return@withContext npDash
      }
    }
    Log.w(Tag, "NewPipe-first 全空(SABR+DASH 兜底均失败)→ 落 WEB /player last resort(classic WEB SABR reload-closure / classic DASH)")

    // 收集 playable 客户端的 streamingData 候选。对齐 FreeTubeAndroid:主用 WEB(带 token,拿 SABR),
    // 仅年龄限制回退 WEB_EMBEDDED;ANDROID 从 /player 链移除(FreeTubeAndroid 不用 ANDROID 客户端)。
    val allAdaptive = mutableListOf<ParsedFormat>()
    val allCombined = mutableListOf<ParsedFormat>()
    var durationMs = 0L
    YoutubeLoadProgress.emit(YoutubeLoadStep.ResolvePlayer)
    // 对齐 FreeTubeAndroid getLocalVideoInfo(§6.7 row 65):每视频只发 1 次 WEB /player(带 token)。
    //  - LOGIN_REQUIRED(bot 检测)→ 立即短路,不回退(FreeTubeAndroid L517-519 直接 return)。
    //    继续发 WEB_EMBEDDED/ANDROID 只会放大 bot 特征(日志:~36 次 /player 后服务端开始拒)。
    //  - 年龄限制(reason='Sign in to confirm your age')→ 追加 WEB_EMBEDDED 回退(FreeTubeAndroid L476)。
    //  - ANDROID 从 /player 链移除(FreeTubeAndroid 不用 ANDROID 客户端)。
    // TV 端试验 TVHTML5 client(对齐 YouTube 官方 TV 端),失败/被拦自动回退 WEB 走现有 SABR。
    // 移动端 preferredYoutubeClient=null → 默认 WEB(行为不变)。
    val clients = mutableListOf<InnerTubeClient.Client>()
    if (request.preferredYoutubeClient == InnerTubeClient.Client.TVHTML5) {
      clients += InnerTubeClient.Client.TVHTML5
      clients += InnerTubeClient.Client.WEB  // 兜底:TVHTML5 被拦/无 url 时回退 WEB 走 SABR
    } else {
      clients += (request.preferredYoutubeClient ?: InnerTubeClient.Client.WEB)
    }
    Log.i(Tag, "resolve clients=${clients.map { it.name }} preferred=${request.preferredYoutubeClient?.name ?: "null(default WEB)"} videoId=$videoId")
    var clientIdx = 0
    while (clientIdx < clients.size) {
      val client = clients[clientIdx]
      clientIdx++
      val player = runCatching { postPlayer(videoId, client = client, poToken = poToken, signatureTimestamp = signatureTimestamp) }.getOrNull()
      if (!player.isPlayable()) {
        val status = player?.obj("playabilityStatus")?.stringOrNull("status")
        val reason = player?.playabilityReason()
        lastError = reason ?: lastError
        // LOGIN_REQUIRED(bot 检测)→ 立即短路,不回退(对齐 FreeTubeAndroid L517-519)。
        if (status == "LOGIN_REQUIRED") {
          Log.w(Tag, "player $client LOGIN_REQUIRED (bot) → short-circuit, no fallback (videoId=$videoId reason=$reason)")
          throw YoutubeApiException(0, "", "YouTube playback blocked: $reason")
        }
        // 年龄限制 → 追加 WEB_EMBEDDED 回退(对齐 FreeTubeAndroid L476 只认 'Sign in to confirm your age')。
        if (client == InnerTubeClient.Client.WEB && reason == "Sign in to confirm your age") {
          Log.i(Tag, "player WEB age-restricted → fall back to WEB_EMBEDDED (videoId=$videoId)")
          clients += InnerTubeClient.Client.WEB_EMBEDDED
        }
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
      if ((client == InnerTubeClient.Client.WEB || client == InnerTubeClient.Client.TVHTML5) && !sabrUrl.isNullOrBlank() && !ustreamerCfgStr.isNullOrBlank() && poToken != null) {
        // Phase 2 取证:RELOAD 回传。上次会话收到 RELOAD_PLAYER_RESPONSE 时 [SabrMediaFetcher.processPart]
        // 已把 reloadToken 停车进 [SabrStreamRegistry](独立于 sessions,evict 不清);evict→播放器错误重试重进
        // resolve。这里 consume 取走 token → 重打 visionOS /player(回传 reloadPlaybackContext)换新会话;
        // 成功即试用(证明 Phase 2 成立);失败落常规 NewPipe harvest。reloadCount 超 MAX 只跳过 reload 尝试
        // (整体 loop 已由播放器错误重试预算 MaxStallAutoRetry 兜底,本轮诊断不接完整 DASH 闭环)。
        val reloadToken = SabrStreamRegistry.consumeReloadTokenSlot()
        if (reloadToken != null) {
          // alpha.8 教训:storeReloadToken 对每次 RELOAD part(rn=0..7,单次尝试约 8 个)都 +1,且从不重置,
          // 到 resolve 重跑时 count 已远超 MAX → 诊断期的 reload 尝试永远被跳过、Phase 2 一次都没发过。
          // consume 已是原子 one-shot-per-token(取走即不再重放),累积 count 对兜底 loop 冗余,
          // 故这里每次 consume 到 token 就把 count 归零 → 每个 resolve 周期恰好尝试一次 reload。
          SabrStreamRegistry.resetReloadCount(videoId)
          val reloadCount = SabrStreamRegistry.reloadCount(videoId)
          Log.i(Tag, "SABR reload path: videoId=$videoId reload#$reloadCount tokenLen=${reloadToken.length}")
          val rp = runCatching { buildSabrSessionFromReloadPlayer(videoId, reloadToken, poToken, youtubeDefaultQuality) }.getOrNull()
          if (rp != null) {
            YoutubeLoadProgress.emit(YoutubeLoadStep.BuildSession)
            val sabrClient = SabrClient(httpClient)
            val sid = SabrStreamRegistry.registerByVideoId(
              videoId, rp.session, sabrClient,
              refreshPoToken = { biliTvPoTokenProvider.getWebClientPoToken(videoId)?.streamingDataPoToken?.toByteArray(Charsets.UTF_8) },
            )
            YoutubeLoadProgress.emit(YoutubeLoadStep.Connect)
            Log.i(
              Tag,
              "SABR reload playback ready: sid=$sid reload#$reloadCount source=reload-closure " +
                "video=itag${rp.session.videoFormatId.itag}(${rp.session.videoFormatId.height}p) " +
                "audio=itag${rp.session.audioFormatId.itag} → sabr:// DASH"
            )
            return@withContext buildSabrPlaybackInfo(
              request, videoId, rp.durationMs, rp.raws, rp.session, sid,
              subtitleTracks = rp.subtitleTracks,
              youtubeDefaultQuality = youtubeDefaultQuality,
            )
          }
          Log.w(Tag, "SABR reload 闭环未回 SABR(WEB/visionOS reload 均无 sabrUrl)→ 试 DASH/HLS 兜底")
          // alpha.88:RELOAD 闭环兜底(对齐 LibreTube SABR RELOAD 崩后落 streams.dash)——用 NewPipe getInfo
          // 的 dashMpdUrl(android streamingData manifest)直喂 DashMediaSource,≤1080p。
          // alpha.90:Phase 0 取证 dashMpdUrl 恒空 → 实际走 hlsUrl(visionOS Apple 平台原生 HLS)次选兜底。
          val dashInfo = runCatching { buildDashFallbackFromNewPipe(videoId, durationMs, request, youtubeDefaultQuality) }.getOrNull()
          if (dashInfo != null) {
            YoutubeLoadProgress.emit(YoutubeLoadStep.Connect)
            val kind = if (dashInfo.remoteHlsManifestUrl != null) "HLS" else "DASH"
            val len = (dashInfo.remoteDashManifestUrl ?: dashInfo.remoteHlsManifestUrl)?.length
            Log.i(Tag, "SABR reload → $kind 兜底 playback ready: videoId=$videoId manifest=${len}B → 远程 $kind")
            return@withContext dashInfo
          }
          Log.w(Tag, "DASH/HLS 兜底均空(dashMpdUrl+hlsUrl 无 manifest)→ 落常规 NewPipe harvest(会 RELOAD,但已无其它出口)")
        }
        val raws = rawAdaptive.mapNotNull { it as? JsonObject }
        val firstVideo = raws.firstOrNull { (it.intOrNull("height") ?: 0) > 0 }
        val firstAudio = raws.firstOrNull { (it.stringOrNull("mimeType") ?: "").startsWith("audio/") }
        if (firstVideo != null && firstAudio != null) {
          // alpha.29:切清晰度/seek 重跑 resolve 时,若同 videoId 已有会话,直接复用——poToken/ustreamerConfig/
          // cpn 会话级可复用 ~6h(FreeTube 证实)。alpha.59(Phase 2 DASH):DASH 按需逐段拉,会话服务整段视频
          // (无 60s 窗口/轮换),故不再按窗口判断,直接 getByVideoId 复用。无缓存 → 下方 harvest 建新会话。
          val cachedSid = SabrStreamRegistry.getByVideoId(videoId)
          if (cachedSid != null) {
            val cachedEntry = SabrStreamRegistry.getEntryByVideoId(videoId)
            if (cachedEntry != null) {
              var session = cachedEntry.session
              // 音轨切换:preferredAudioTrackId 命中且与当前 audioFormatId 不同 → copy 换 audioFormatId 并重注册
              // (更新缓存 entry,下个音频段请求用新 itag)。poToken 会话级不绑 itag,无需重 harvest。
              if (request.preferredAudioTrackId != null) {
                val match = session.audioTracks.firstOrNull { it.id == request.preferredAudioTrackId }
                if (match != null && match.formatId.itag != session.audioFormatId.itag) {
                  session = session.copy(audioFormatId = match.formatId)
                  SabrStreamRegistry.registerByVideoId(
                    videoId, session, cachedEntry.client,
                    windowStartMs = cachedEntry.windowStartMs,
                    refreshPoToken = cachedEntry.refreshPoToken,
                  )
                  Log.i(Tag, "SABR audio switch: videoId=$videoId track=${request.preferredAudioTrackId} → audio=itag${match.formatId.itag}")
                }
              }
              Log.i(Tag, "SABR session reuse: videoId=$videoId sid=$cachedSid → reuse (skip harvest/decipher), preferredQuality=${request.preferredQualityId}")
              return@withContext buildSabrPlaybackInfo(request, videoId, durationMs, raws, session, cachedSid)
            }
          }
          // alpha.86:退回 NewPipe visionOS 作 SABR 主路径(对齐 LibreTube 完全本地模式)。alpha.85 试 attested
          // WEB /player 经典路径作主路径修 4K RELOAD——RELOAD 确实消失(attested config 不再被协议层拒),但 WEB
          // /player 的 serverAbrStreamingUrl **带 n-param**(与 fork visionOS sabrUrl 无 n 不同——docs §6.7 row 75
          // 只适用 fork visionOS),plasma WASM 致 n-decrypt 结构性失效 [[youtube-plasma-wasm-n-decrypt]],OkHttp
          // POST 未 transform 的 sabrUrl → 所有 fetch HTTP 403(Server=gvs 空体,§6.18 alpha.18 同症状),**连
          // ≤1080p 都回归 403**。且 alpha.80 真机已证 visionOS config + WEB-visitor poToken(首请求带 token 尝试
          // attestation)→ RELOAD(visitor 不匹配,§6.18)——故 4K 两条 OkHttp 路全堵(visionOS+WEB-poToken→RELOAD /
          // WEB+WEB-poToken→403n),4K 唯一曾跑通的是 alpha.20-25 WebView harvest(浏览器 WASM 做 n-transform +
          // 浏览器全 WEB 一致 attested body),alpha.71 退役(疑跨 minter 60s,但 [[sabr-status2-sync-refresh]]
          // alpha.68 已同步修 60s 竞态,跨 minter 是否真致命待重测)。4K 另议,此处先修 ≤1080p 回归。
          // alpha.71(path C):NewPipeExtractor fork 作 SABR 取流唯一路径(visionOS 客户端,干净 /player,
          // 无浏览器会话绑定,网关 URL 无 n-param 需 decipher)。彻底退役 alpha.20-70 的 WebView harvest
          // 兜底(harvest 抓回的 serverAbrStreamingUrl+ustreamerConfig 绑浏览器会话 → 跨 minter status=3 /
          // alpha.70 纯 backoff 无 cookie)。NewPipe 自铸 poToken(getInfo 期间经 [BiliTvPoTokenProvider]),
          // 复用缓存供 SABR init → init==extraction 同 minter,根除 60s 重启。
          // alpha.76:classic n-decrypt 兜底已退役——plasma player.js 把 n/sig 移进 WASM 致 n-decrypt 结构性
          // 失效,且 classic 用 resolve() 顶部 poToken 跨 minter → status=3 60s 卡死。NewPipe 无 SABR 数据直接落 DASH。
          var sabrSession: SabrSession? = null
          var sabrRaws: List<JsonObject> = raws
          var sabrDuration = durationMs
          val npResult = buildSabrSessionFromNewPipe(videoId, poToken, youtubeDefaultQuality)
          if (npResult != null) {
            sabrSession = npResult.session
            sabrRaws = npResult.raws
            sabrDuration = npResult.durationMs
          } else {
            Log.w(Tag, "SABR: NewPipe 无 SABR 数据 → 试 DASH/HLS 兜底(classic n-decrypt 已退役:plasma 失效 + 跨 minter 卡 60s)")
            // alpha.90:NewPipe 无 SABR 数据时,getInfo 仍可能给 hlsUrl(visionOS Apple 平台原生 HLS 交付)→ 走
            // HLS 兜底,而非落已死的 classic n-decrypt(plasma WASM 致 n-decrypt 结构性失效)。复用 [buildDashFallbackFromNewPipe]
            //(内部再 getInfo 取 dashMpdUrl/hlsUrl,优先 DASH、次选 HLS)。两路均空才落 L399 classic。
            val noSabrFallback = runCatching { buildDashFallbackFromNewPipe(videoId, durationMs, request, youtubeDefaultQuality) }.getOrNull()
            if (noSabrFallback != null) {
              YoutubeLoadProgress.emit(YoutubeLoadStep.Connect)
              val kind = if (noSabrFallback.remoteHlsManifestUrl != null) "HLS" else "DASH"
              val len = (noSabrFallback.remoteDashManifestUrl ?: noSabrFallback.remoteHlsManifestUrl)?.length
              Log.i(Tag, "无 SABR → $kind 兜底 playback ready: videoId=$videoId manifest=${len}B → 远程 $kind")
              return@withContext noSabrFallback
            }
          }
          if (sabrSession != null) {
            YoutubeLoadProgress.emit(YoutubeLoadStep.BuildSession)
            val sabrClient = SabrClient(httpClient)
            // alpha.59(Phase 2 DASH):注册会话(无窗口锚点——DASH 会话服务整段视频,无 60s 轮换)。
            // alpha.65:注入 PO token 刷新回调——SABR status=2(Attestation pending)时重铸 streamingDataPoToken,
            // 对齐 LibreTube SabrClient.generatePoToken。botGuard 是 AppContainer 进程级单例,lambda 长生命周期安全。
            val sid = SabrStreamRegistry.registerByVideoId(
              videoId, sabrSession, sabrClient,
              refreshPoToken = { biliTvPoTokenProvider.getWebClientPoToken(videoId)?.streamingDataPoToken?.toByteArray(Charsets.UTF_8) },
            )
            YoutubeLoadProgress.emit(YoutubeLoadStep.Connect)
            Log.i(
              Tag,
              "SABR playback ready: sid=$sid source=NewPipe " +
                "video=itag${sabrSession.videoFormatId.itag}(${sabrSession.videoFormatId.height}p) " +
                "audio=itag${sabrSession.audioFormatId.itag} → sabr:// DASH tracks"
            )
            return@withContext buildSabrPlaybackInfo(
              request, videoId, sabrDuration, sabrRaws, sabrSession, sid,
              subtitleTracks = npResult?.subtitleTracks.orEmpty(),
              youtubeDefaultQuality = youtubeDefaultQuality,
            )
          }
        } else {
          Log.w(Tag, "SABR init probe skipped: video=${firstVideo != null} audio=${firstAudio != null}")
        }
      }
    }
    // 对齐 FreeTubeAndroid:不再发无 token 诊断探针(§6.7 row 65)——纯诊断,poToken != null 时必发,
    // 直接翻倍 /player 请求量,放大 bot 特征。删掉后每视频只发 1 次 WEB /player(带 token)。
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
    val adaptiveVideo = pickVideo(decodableVideos, codecPreference, request.preferredQualityId, youtubeDefaultQuality.maxHeight)
    if (adaptiveVideo != null) {
      val audio = pickAudio(audioCandidates, request.preferredAudioTrackId)
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
            allAudioTracks = audioCandidates,
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
    allAudioTracks: List<ParsedFormat> = emptyList(),
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
    // 多语言配音:全部可选音轨(供播放器音轨切换菜单)。按 audioTrack.id 去重——单音轨视频
    // 多个 itag(251/140)audioTrackId 均为 null → 折叠成一条,避免误显示多音轨菜单。
    val availableAudioTracks = allAudioTracks
      .map {
        PlaybackAudioTrack(
          id = it.audioTrackId ?: "default",
          languageCode = it.languageCode,
          displayName = it.audioDisplayName ?: it.languageCode,
          isDefault = it.audioIsDefault,
        )
      }
      .distinctBy { it.id }
    Log.i(
      Tag,
      "resolve ok: $kindLabel itag=${videoFmt.itag}; nDecrypt=${if (videoUrl.contains("n=")) "left" else "applied"}; " +
        "dash=${if (videoFmt.toSegmentBase() != null) "yes" else "no"}; qualities=${qualities.size}; audioTracks=${availableAudioTracks.size}",
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
      availableAudioTracks = availableAudioTracks,
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
    // ANDROID 保持 OkHttp 直连(作为回退)。TVHTML5 也走 WebView(TV client OkHttp 直连大概率被拦)。
    val useWebView = client == InnerTubeClient.Client.WEB || client == InnerTubeClient.Client.WEB_EMBEDDED || client == InnerTubeClient.Client.TVHTML5
    // alpha.89:WebView fetch 安全网——若 browserSession 卡在错误页(origin=null)→ fetch CORS 失败抛错,
    // 此前直接冒泡致 resolve "no decodable formats" 全视频播不了(真机 alpha.88 "现在都不能播放")。
    // 捕获 viaWebView 异常 → 回退 OkHttp 直连(viaWebView=false)。OkHttp WEB /player 可能被判
    // "The page needs to be reloaded"(unplayable),但至少返回结构化响应而非硬崩;部分视频仍可取流。
    return runCatching {
      innerTubeClient.postJson("/player", payload, client = client, poToken = poToken, viaWebView = useWebView)
    }.getOrElse { e ->
      if (useWebView) {
        Log.w(Tag, "postPlayer $client viaWebView failed (${e.message}) → fallback OkHttp viaWebView=false")
        innerTubeClient.postJson("/player", payload, client = client, poToken = poToken, viaWebView = false)
      } else throw e
    }
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

  private fun replaceParam(url: String, key: String, value: String): String {
    val start = url.indexOf("$key=")
    if (start < 0) return url
    val valueStart = start + key.length + 1
    val end = url.indexOf('&', valueStart).let { if (it < 0) url.length else it }
    return url.substring(0, valueStart) + value + url.substring(end)
  }

  /** 从 URL query 中移除指定 key 的参数(用于 strip NewPipe 追加的 &cpn=)。 */
  private fun stripQueryParam(url: String, key: String): String {
    val qIdx = url.indexOf("?")
    if (qIdx < 0) return url
    val base = url.substring(0, qIdx)
    val query = url.substring(qIdx + 1)
    val kept = query.split("&").filterNot { it == key || it.startsWith("$key=") }
    return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
  }

  /** 取 URL query 里 key 的值(如 `&cpn=<value>` → value);无则 null。 */
  private fun queryParam(url: String, key: String): String? {
    val qIdx = url.indexOf("?")
    if (qIdx < 0) return null
    return url.substring(qIdx + 1).split("&")
      .firstOrNull { it.startsWith("$key=") }?.substringAfter("=")
  }

  /** path C:[StreamInfo.getInfo] 的结果包装——session + 供 buildSabrPlaybackInfo 的 raws + 时长 + 字幕。 */
  private data class NewPipeSabrResult(
    val session: SabrSession,
    val raws: List<JsonObject>,
    val durationMs: Long,
    val subtitleTracks: List<PlaybackTrack>,
  )

  /**
   * alpha.71(path C):用 NewPipeExtractor fork(`libre-tube`)取 SABR 会话。`StreamInfo.getInfo` 内部
   * 走 visionOS 客户端(不带 poToken)发干净 /player,拿到未绑定任何浏览器会话的
   * [StreamInfo.getServerAbrStreamingUrl](SABR 网关端点,无 n-param 需 decipher)+ [StreamInfo.getUstreamerConfig]。
   *
   * alpha.14:visionOS SABR 请求**不带 poToken**(对齐 LibreTube,§6.18 定论)。上一轮 alpha.80 移植
   * NewPipe 原生 PoTokenGenerator 后仍 RELOAD——真机日志坐实 LibreTube SabrClient 的
   * getCachedWebClientPoToken() 在 getInfo 期间恒空(getIosClientPoToken 返回 null),首请求 setPoToken(empty)
   * 不带 poToken 且能播;BiliTV 带 120B WEB-visitor 铸的 poToken 被服务端按会话 visitor 不匹配 RELOAD 全拒。
   * 故 SABR 会话不再带 poToken;getInfo 仍走 NewPipe 铸 token(供 iOS /player 请求),但 SABR 不依赖它。
   *
   * 返回 null = 视频无 SABR / getInfo 失败 → 上层 classic(n-decrypt)或 DASH 兜底。
   */
  private suspend fun buildSabrSessionFromNewPipe(
    videoId: String,
    poToken: String?,
    youtubeDefaultQuality: YoutubeDefaultQuality = YoutubeDefaultQuality.Auto,
  ): NewPipeSabrResult? {
    val info = runCatching { StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId") }
      .getOrElse {
        Log.w(Tag, "NewPipe getInfo failed: ${it.message}")
        return null
      }
    val sabrUrlRaw = info.serverAbrStreamingUrl
    val ustreamerCfgB64 = info.ustreamerConfig
    if (sabrUrlRaw.isNullOrBlank() || ustreamerCfgB64.isNullOrBlank()) {
      Log.w(Tag, "NewPipe: no serverAbrStreamingUrl/ustreamerConfig (video lacks SABR) → fallback")
      return null
    }
    // Phase 0 诊断(DASH 兜底,见 docs/youtube-dash-fallback-plan.md):NewPipe 是否给 dashMpdUrl/hlsUrl。
    // 不改播放行为,仅取证判断 DASH 兜底可走哪条(直拉 manifest vs 合成 MPD)。
    Log.i(Tag, "NewPipe DASH diag: dashMpdUrl=${info.dashMpdUrl?.take(80)} hlsUrl=${info.hlsUrl?.take(60)}")
    // alpha.14:NewPipe 给网关 URL 追加了 &cpn=<visionOsCpn>,而 ustreamerConfig 绑定该 cpn。
    // 必须用 visionOsCpn(对齐 LibreTube 直接用原始 URL),不能用随机 cpn——否则服务端按 cpn 不匹配
    // 拒会话 → RELOAD_PLAYER_RESPONSE 死循环(alpha.14 真机:jNl6YkkzKxw 2160p 首 fetch 即 RELOAD,
    // 3wO2mW3eylw 1080p 能播;LibreTube 能播 jNl6YkkzKxw 因它保留 visionOsCpn)。strip 掉避免重复参数。
    val visionOsCpn = queryParam(sabrUrlRaw, "cpn")
    val sabrUrl = stripQueryParam(sabrUrlRaw, "cpn")
    val videoStreams = info.videoOnlyStreams
    val audioStreams = info.audioStreams
    val videoFormats = videoStreams.filter { it.height > 0 }.map { it.toSabrFormatId() }
    // alpha.77:harvest 选轨必须与 buildSabrPlaybackInfo 的选档一致——否则会话绑定的 videoFormatId
    // 与播放器实际请求的 itag 不一致(harvest 盲取最高分辨率首条 vs 播放按默认画质上限选档)
    // → 服务端 RELOAD_PLAYER_RESPONSE 死循环(alpha.77 真机:itag313 会话 + itag136 请求)。
    // 用 youtubeDefaultQuality 的 maxHeight 从全部视频流里选同一档,而非盲取最高分辨率首条。
    val maxHeight = youtubeDefaultQuality.maxHeight
    val defaultItag = when {
      maxHeight != null ->
        videoFormats.filter { it.height in 1..maxHeight }.maxByOrNull { it.height }?.itag
          ?: videoFormats.minByOrNull { it.height }?.itag // 全部超过上限 → 取最低档
      else -> videoFormats.maxByOrNull { it.height }?.itag // Auto → 最高可用
    }
    val firstVideo = defaultItag?.let { target ->
      videoStreams.firstOrNull { it.toSabrFormatId().itag == target }
    } ?: videoStreams.firstOrNull { it.height > 0 } ?: videoStreams.firstOrNull()
    Log.i(Tag, "NewPipe SABR harvest: videoFormats=${videoFormats.size} maxHeight=$maxHeight defaultItag=$defaultItag firstVideo=itag${firstVideo?.itag}(${firstVideo?.height}p)")
    // alpha.83 诊断:dump 每个视频流 itag 的 codec。真机日志坐实 itag248=vp9(720p webm 视频),
    // **不是** opus 音频——此前「itag248=opus 误分类成视频轨」的记录是误读(已推翻,见 docs/youtube-hd-playback.md
    // 最新结论)。RELOAD 根因是 NewPipe visionOS 拿到未 attested 的 ustreamerConfig,与 itag 选择无关,
    // 故「按 codec 过滤音频 itag」前提本就不成立,过滤逻辑不再需要。
    Log.i(Tag, "NewPipe videoFormats dump: " + videoStreams
      .filter { it.height > 0 }
      .joinToString { "${it.itag}=${it.codec}" })
    // 优先原声轨(getAudioTrackType()==ORIGINAL,来自 xtags acont=original),跳过配音/翻译轨。
    // 多语言配音视频里同一 itag 会按语言重复出现,盲取第一条可能拿到配音轨。
    val firstAudio = audioStreams.firstOrNull { it.audioTrackType == AudioTrackType.ORIGINAL }
      ?: audioStreams.firstOrNull { it.audioTrackType != AudioTrackType.DUBBED }
      ?: audioStreams.firstOrNull()
    if (firstVideo == null || firstAudio == null) {
      Log.w(Tag, "NewPipe: missing streams (video=${firstVideo != null} audio=${firstAudio != null})")
      return null
    }
    val vFmt = firstVideo.toSabrFormatId()
    val aFmt = firstAudio.toSabrFormatId()
    // Phase 0 诊断(DASH 兜底,见 docs/youtube-dash-fallback-plan.md):首视频/音频流的已解密 URL
    // (NewPipe 内部 n-decrypt)+ deliveryMethod + itagItem 字段(toString 暴露 init/index range 若有,
    // 不假设字段名)。仅取证,不改播放行为。
    Log.i(Tag, "NewPipe DASH diag: firstVideo itag=${firstVideo.itag} delivery=${firstVideo.deliveryMethod} url=${firstVideo.content?.take(90)} itagItem=${firstVideo.itagItem?.toString()?.take(140)}")
    Log.i(Tag, "NewPipe DASH diag: firstAudio itag=${firstAudio.itag} delivery=${firstAudio.deliveryMethod} url=${firstAudio.content?.take(90)} itagItem=${firstAudio.itagItem?.toString()?.take(140)}")
    // 全部可选音轨(供播放器音轨切换菜单)。id 用 getAudioTrackId()(audioTrack.id,如 "en.4");
    // 单音轨视频多个 itag 的 audioTrackId 均为 null → 折叠成 "default" 一条,避免误显示多音轨菜单。
    val sabrAudioTracks = audioStreams.map {
      SabrAudioTrack(
        id = it.getAudioTrackId() ?: "default",
        languageCode = it.getAudioLocale()?.language,
        displayName = it.getAudioTrackName() ?: it.getAudioLocale()?.getDisplayName(),
        isDefault = it.audioTrackType == AudioTrackType.ORIGINAL,
        formatId = it.toSabrFormatId(),
      )
    }
    // alpha.14:对齐 LibreTube——visionOS SABR 请求**不带 poToken**。
    // 上一轮(alpha.80)移植 NewPipe 原生 PoTokenGenerator 后仍 RELOAD,真机日志坐实根因:
    // LibreTube SabrClient 的 getCachedWebClientPoToken() 在 getInfo 期间恒空(getIosClientPoToken 返回 null),
    // 首请求 setPoToken(empty) 不带 poToken 且能播;BiliTV 带 120B WEB-visitor 铸的 poToken 被 visionOS
    // 服务端按会话 visitor 不匹配 RELOAD 全拒(§6.18/alpha.14 定论)。故 SABR 会话不再带 poToken。
    // getInfo 仍走 NewPipe 铸 token(供 iOS /player 请求),但 SABR 请求不依赖它——poToken 空也不回退。
    val poTokenB64 = "" // 对齐 LibreTube:不带 poToken
    val durationMs = info.duration * 1000L
    val raws = videoStreams.map { newPipeVideoRaw(it) } + audioStreams.map { newPipeAudioRaw(it) }
    val session = SabrSession.fromSabrData(
      sabrUrl,
      poTokenB64,
      ustreamerCfgB64,
      // alpha.75:NewPipe getInfo 是 visionOS /player,其 ustreamerConfig 绑 visionOS 客户端。这里必须
      // 用 visionOS client info(对齐 LibreTube),用 WEB client info 会被服务端 RELOAD_PLAYER 全拒。
      innerTubeClient.visionOsSabrClientInfo(),
      aFmt,
      vFmt,
      // alpha.78:HTTP UA 必须与 clientInfo/ustreamerConfig 同为 visionOS。此前用 WEB UA(clientName=101
      // visionOS 的 protobuf + Mozilla/5.0 Pixel 7 的 HTTP 头)被服务端判客户端不一致 → RELOAD_PLAYER 全拒
      // (真机 2026-08-16:NewPipe 新会话首 fetch 即 RELOAD,死循环)。对齐 LibreTube SabrClient 全 visionOS。
      userAgent = InnerTubeClient.Client.VISION_OS.userAgent,
      // alpha.79:UA 对齐 visionOS 后仍 RELOAD(真机 alpha.78 UA 修复仍死循环)——因为 HTTP Cookie/
      // X-Goog-Visitor-Id 仍带 WEB(Android Chrome)会话头,与 visionOS protobuf 不一致。彻底对齐
      // LibreTube:完全不带 HTTP cookie/visitor,会话身份全靠 protobuf(poToken/ustreamerConfig/playbackCookie)。
      cookieHeader = "",
      visitorData = "",
      // alpha.14:cpn 用 visionOsCpn(NewPipe /player 的 cpn,绑定 ustreamerConfig),不用随机 cpn。
      cpn = visionOsCpn,
      videoFormats = videoFormats,
      audioTracks = sabrAudioTracks,
    )
    // 字幕(WebVTT URL 直拉,不走 SABR 服务端):NewPipe SubtitleInfo 直接给可拉取的 WebVTT URL。
    // mimeType 固定 text/vtt,Media3 SubtitleExtractor 转 MEDIA3_CUES 由 PlayerView 内置 SubtitleView 渲染。
    // 无字幕时为空列表。id 用索引(非 itag),供字幕轨去重/切换。
    val subtitleTracks = info.subtitles.mapIndexed { index, subtitle: SubtitlesStream ->
      PlaybackTrack(
        id = index,
        baseUrl = subtitle.url.orEmpty(),
        backupUrls = emptyList(),
        bandwidth = 0,
        codecs = "",
        width = 0,
        height = 0,
        mimeType = "text/vtt",
        languageCode = subtitle.languageTag,
      )
    }
    Log.i(
      Tag,
      "NewPipe SABR session: sabrUrl=${sabrUrl.take(80)}... poToken=${poTokenB64.length}B" +
        "(aligned-LibreTube-no-poToken) ustreamerCfg=${ustreamerCfgB64.length}B " +
        "cpn=${visionOsCpn ?: "random"} video=itag${vFmt.itag}(${vFmt.height}p) audio=itag${aFmt.itag} " +
        "videoFormats=${videoFormats.size} subtitles=${subtitleTracks.size} dur=${durationMs}ms"
    )
    return NewPipeSabrResult(session, raws, durationMs, subtitleTracks)
  }

  /**
   * alpha.84(对齐 LibreTube 默认 Piped 路径):用 Piped 后端 `/streams/{videoId}` 取 SABR 会话。
   *
   * 区别于 [buildSabrSessionFromNewPipe](NewPipe fork 的 getInfo 走 visionOS /player)——这里走 Piped
   * 实例,Piped 自带 poToken 请求 YouTube,回**已 attested 的 WEB-bound** `serverAbrStreamingUrl` +
   * `videoPlaybackUstreamerConfig`(见 [docs/youtube-hd-playback.md]「alpha.83 更正」段:NewPipe
   * visionOS 拿未 attested 的 visionOS-bound config → 对需 attestation 的视频服务端直接 RELOAD 不可续命;
   * Piped config 已 attested,首请求发空 poToken,服务端回 status=2 时才铸 WEB poToken 续命)。
   *
   * **复用下游**:返回与 NewPipe 路径同形的 [NewPipeSabrResult](session + raws + 时长 + 字幕),resolve
   * 的 Piped 分支直接复用既有 `registerByVideoId` + [buildSabrPlaybackInfo],不另造 PlaybackInfo 构造。
   * raws 由 [pipedVideoRaw]/[pipedAudioRaw] 把 [PipedStream] 转成与 [newPipeVideoRaw]/[newPipeAudioRaw]
   * 同形的 JsonObject,使 [buildSabrTrack]/[buildSabrPlaybackInfo] 无需感知数据源差异。
   *
   * ClientInfo/UA 仍用 visionOS([innerTubeClient.visionOsSabrClientInfo] + VISION_OS UA)——对齐
   * LibreTube `SabrClient`(Piped/NewPipe 通用硬编码 visionOS ClientInfo),差异只在 ustreamerConfig 来源。
   * poToken 留空(对齐 LibreTube Piped 首请求,服务端 status=2 时由注入的 refreshPoToken 铸 WEB poToken)。
   *
   * 返回 null = Piped 无 SABR 数据(sabrUrl/ustreamerConfig 缺) / 缺音视频流 → resolve 回退 NewPipe。
   */
  private suspend fun buildSabrSessionFromPiped(
    videoId: String,
    piped: PipedStreams,
    youtubeDefaultQuality: YoutubeDefaultQuality = YoutubeDefaultQuality.Auto,
  ): NewPipeSabrResult? {
    val sabrUrlRaw = piped.serverAbrStreamingUrl
    val ustreamerCfgB64 = piped.videoPlaybackUstreamerConfig
    if (sabrUrlRaw.isNullOrBlank() || ustreamerCfgB64.isNullOrBlank()) {
      Log.w(Tag, "Piped: no serverAbrStreamingUrl/ustreamerConfig (video lacks SABR) → fallback")
      return null
    }
    // 同 NewPipe 路径:cpn 用 Piped URL 自带的 cpn(绑定 ustreamerConfig),strip 掉避免重复参数。
    val cpn = queryParam(sabrUrlRaw, "cpn")
    val sabrUrl = stripQueryParam(sabrUrlRaw, "cpn")
    val videoStreams = piped.videoStreams.filter { (it.height ?: 0) > 0 }
    val audioStreams = piped.audioStreams
    val videoFormats = videoStreams.map { it.toSabrFormatId() }
    // 同 NewPipe 路径:用 youtubeDefaultQuality.maxHeight 选档,与 buildSabrPlaybackInfo 选档一致。
    val maxHeight = youtubeDefaultQuality.maxHeight
    val defaultItag = when {
      maxHeight != null ->
        videoFormats.filter { it.height in 1..maxHeight }.maxByOrNull { it.height }?.itag
          ?: videoFormats.minByOrNull { it.height }?.itag
      else -> videoFormats.maxByOrNull { it.height }?.itag
    }
    val firstVideo = defaultItag?.let { target ->
      videoStreams.firstOrNull { it.itag == target }
    } ?: videoStreams.firstOrNull()
    Log.i(Tag, "Piped SABR harvest: videoFormats=${videoFormats.size} maxHeight=$maxHeight defaultItag=$defaultItag firstVideo=itag${firstVideo?.itag}(${firstVideo?.height}p)")
    // 优先原声轨(audioTrackType=="ORIGINAL",对齐 NewPipe AudioTrackType.ORIGINAL),跳过配音/翻译轨。
    val firstAudio = audioStreams.firstOrNull { it.audioTrackType == "ORIGINAL" }
      ?: audioStreams.firstOrNull { it.audioTrackType != "DUBBED" }
      ?: audioStreams.firstOrNull()
    if (firstVideo == null || firstAudio == null) {
      Log.w(Tag, "Piped: missing streams (video=${firstVideo != null} audio=${firstAudio != null})")
      return null
    }
    val vFmt = firstVideo.toSabrFormatId()
    val aFmt = firstAudio.toSabrFormatId()
    // 全部可选音轨(供播放器音轨切换菜单)。id 用 audioTrackId;单音轨视频 audioTrackId 为 null → 折叠 "default"。
    val sabrAudioTracks = audioStreams.map {
      SabrAudioTrack(
        id = it.audioTrackId ?: "default",
        languageCode = it.audioTrackLocale,
        displayName = it.audioTrackName,
        isDefault = it.audioTrackType == "ORIGINAL",
        formatId = it.toSabrFormatId(),
      )
    }
    val poTokenB64 = "" // 对齐 LibreTube Piped 首请求:不带 poToken(status=2 时才铸)
    val durationMs = piped.duration * 1000L
    val raws = videoStreams.map { pipedVideoRaw(it) } + audioStreams.map { pipedAudioRaw(it) }
    val session = SabrSession.fromSabrData(
      sabrUrl,
      poTokenB64,
      ustreamerCfgB64,
      // 同 NewPipe 路径:visionOS ClientInfo(LibreTube Piped/NewPipe 通用)。
      innerTubeClient.visionOsSabrClientInfo(),
      aFmt,
      vFmt,
      userAgent = InnerTubeClient.Client.VISION_OS.userAgent,
      // 同 NewPipe 路径:完全不带 HTTP cookie/visitor,会话身份全靠 protobuf。
      cookieHeader = "",
      visitorData = "",
      cpn = cpn,
      videoFormats = videoFormats,
      audioTracks = sabrAudioTracks,
    )
    Log.i(
      Tag,
      "Piped SABR session: sabrUrl=${sabrUrl.take(80)}... poToken=0B(aligned-LibreTube-no-poToken) " +
        "ustreamerCfg=${ustreamerCfgB64.length}B cpn=${cpn ?: "random"} video=itag${vFmt.itag}(${vFmt.height}p) " +
        "audio=itag${aFmt.itag} videoFormats=${videoFormats.size} dur=${durationMs}ms"
    )
    // Piped 暂不取字幕(模型未含;NewPipe 路径有,字幕非关键,留空)。
    return NewPipeSabrResult(session, raws, durationMs, emptyList())
  }

  /** Phase 2 取证:从 visionOS reload /player 响应提取的 SABR 数据。 */
  private data class ReloadSabrData(
    val sabrUrl: String,
    val ustreamerCfgB64: String,
    val raws: List<JsonObject>,
    val durationMs: Long,
  )

  /** 从 InnerTube /player 响应提取 SABR 数据(camelCase key,同 WEB diag L161-177)。缺 sabrUrl/ustreamerConfig 返回 null。 */
  private fun parseSabrData(player: JsonObject): ReloadSabrData? {
    val streamingData = player.obj("streamingData") ?: return null
    val sabrUrlRaw = streamingData.stringOrNull("serverAbrStreamingUrl")
    if (sabrUrlRaw.isNullOrBlank()) return null
    val ustreamerCfgB64 = player
      .obj("playerConfig")
      ?.obj("mediaCommonConfig")
      ?.obj("mediaUstreamerRequestConfig")
      ?.stringOrNull("videoPlaybackUstreamerConfig")
    if (ustreamerCfgB64.isNullOrBlank()) return null
    val raws = streamingData.array("adaptiveFormats")?.mapNotNull { it as? JsonObject } ?: emptyList()
    val durationMs = (player.obj("videoDetails")?.stringOrNull("lengthSeconds")?.toLongOrNull() ?: 0L) * 1000L
    return ReloadSabrData(sabrUrlRaw, ustreamerCfgB64, raws, durationMs)
  }

  /**
   * alpha.87:RELOAD 重载闭环——服务端 RELOAD_PLAYER_RESPONSE(part 46)下发 reloadToken 后,
   * 用 **WEB client + PoTokenWebView 铸的 WEB poToken** 重打 /player([InnerTubeClient.postWebPlayerReload]),
   * 换 attested sabrUrl + ustreamerConfig,重建 **WEB 一致** SABR session(WEB ClientInfo + WEB UA + WEB poToken)重试。
   *
   * 背景定论:
   *  - alpha.86 用 visionOS 重打(无 attestation)→ 受保护视频返回空 sabrUrl/ustreamerConfig → 死循环。
   *  - LibreTube 对受保护视频同样在 RELOAD 崩(L583 throw)后落 DASH——证明"对齐 LibreTube poToken"修不了,
   *    唯一出路是真正消费 RELOAD:换能 attest 的 WEB client 重打。
   *  - WEB-reload 数据绑 WEB client → SABR StreamerContext.ClientInfo 必须 WEB([sabrClientInfo] clientName=1)、
   *    UA 必须 WEB、poToken 注入 StreamerContext.field2(对齐 LibreTube status=2 重铸 poToken 重试),否则
   *    visionOS/WEB 错配 → 再 RELOAD(alpha.80 旧疾)。
   *
   * **已接受风险(待真机日志验证)**:WEB /player 回的 sabrUrl 可能带 `n` 参数 → SABR 端点 HTTP 403
   * (alpha.85/86;n-decrypt 已被 plasma WASM 废)。带 reloadToken 的重打**可能**返回 n-free sabrUrl。
   * 若 403,SabrClient 打 `fetch rn=$rn HTTP 403`,据此再定方向。
   *
   * WEB 失败 / 返回空 → visionOS reload 兜底(对受保护视频大概率也空,但保留作诊断对照)。
   * 返回 null = 两路 reload 均未回 SABR 数据 → resolve 落常规 NewPipe harvest。
   */
  private suspend fun buildSabrSessionFromReloadPlayer(
    videoId: String,
    reloadToken: String,
    poToken: String?,
    youtubeDefaultQuality: YoutubeDefaultQuality = YoutubeDefaultQuality.Auto,
  ): NewPipeSabrResult? {
    // 1) 铸 WEB poToken(PoTokenWebView)。ensureWebToken:有缓存用缓存,否则现场铸一枚。
    val webPoToken: String? = runCatching { biliTvPoTokenProvider.ensureWebToken(videoId)?.streamingDataPoToken }
      .getOrElse {
        Log.w(Tag, "RELOAD reload: ensureWebToken failed: ${it.message} → visionOS fallback")
        null
      }
    Log.i(Tag, "RELOAD reload: videoId=$videoId webPoToken=${webPoToken?.length ?: 0}B reloadTokenLen=${reloadToken.length}")

    // 2) 优先 WEB attested reload。WEB /player 走 viaWebView 原生浏览器栈 + WEB poToken。
    var sd: ReloadSabrData? = null
    var usedWeb = false
    if (webPoToken != null) {
      val webPlayer = runCatching { innerTubeClient.postWebPlayerReload(videoId, reloadToken, webPoToken) }
        .getOrElse {
          Log.w(Tag, "WEB player reload failed: ${it.message} → visionOS fallback")
          null
        }
      if (webPlayer != null) {
        sd = parseSabrData(webPlayer)
        if (sd != null) {
          usedWeb = true
          // 诊断:WEB-reload 的 sabrUrl 是否带 n 参数(决定会不会 SABR 端点 403)。
          val sabrN = queryParam(sd!!.sabrUrl, "n")
          Log.i(
            Tag,
            "WEB reload OK: sabrUrl=YES(${sd!!.sabrUrl.length}B) n-param=${if (sabrN.isNullOrBlank()) "ABSENT(n-free)" else "present(${sabrN.length}B)"} " +
              "ustreamerCfg=${sd!!.ustreamerCfgB64.length}B raws=${sd!!.raws.size}"
          )
        } else {
          Log.w(Tag, "WEB reload: no serverAbrStreamingUrl/ustreamerConfig → visionOS fallback")
        }
      }
    }

    // 3) WEB 失败/空 → visionOS reload 兜底(诊断对照;对受保护视频大概率也空)。
    if (sd == null) {
      val vp = runCatching { innerTubeClient.postVisionOsPlayerReload(videoId, reloadToken) }
        .getOrElse {
          Log.w(Tag, "visionOS player reload failed: ${it.message} → fallback")
          return null
        }
      sd = parseSabrData(vp) ?: run {
        Log.w(Tag, "visionOS reload: no serverAbrStreamingUrl/ustreamerConfig → fallback")
        return null
      }
      Log.i(Tag, "visionOS reload OK(兜底): sabrUrl=${sd!!.sabrUrl.length}B ustreamerCfg=${sd!!.ustreamerCfgB64.length}B raws=${sd!!.raws.size}")
    }

    // 4) 选轨(同 NewPipe 路径——harvest 选轨与 buildSabrPlaybackInfo 选档一致,避免 itag 错配 RELOAD)。
    val sabrData = sd!! // 走到这里 sd 必非空(WEB/visionOS 两路均空已 return null)
    val cpn = queryParam(sabrData.sabrUrl, "cpn")
    val sabrUrl = stripQueryParam(sabrData.sabrUrl, "cpn")
    val raws = sabrData.raws
    val videoRaws = raws.filter { (it.intOrNull("height") ?: 0) > 0 }
    val audioRaws = raws.filter { (it.stringOrNull("mimeType") ?: "").startsWith("audio/") }
    val videoFormats = videoRaws.map { rawToSabrFormatId(it, it.intOrNull("height") ?: 0) }
    val maxHeight = youtubeDefaultQuality.maxHeight
    val defaultItag = when {
      maxHeight != null ->
        videoFormats.filter { it.height in 1..maxHeight }.maxByOrNull { it.height }?.itag
          ?: videoFormats.minByOrNull { it.height }?.itag
      else -> videoFormats.maxByOrNull { it.height }?.itag
    }
    val firstVideo = defaultItag?.let { target ->
      videoRaws.firstOrNull { (it.intOrNull("itag") ?: 0) == target }
    } ?: videoRaws.firstOrNull()
    Log.i(Tag, "reload harvest: source=${if (usedWeb) "WEB" else "visionOS"} videoFormats=${videoFormats.size} maxHeight=$maxHeight defaultItag=$defaultItag firstVideo=itag${firstVideo?.intOrNull("itag")}(${firstVideo?.intOrNull("height")}p)")
    // 优先原声轨(xtags 含 acont=original);否则取第一条音频。
    val firstAudio = audioRaws.firstOrNull { (it.stringOrNull("xtags") ?: "").contains("acont=original") }
      ?: audioRaws.firstOrNull()
    if (firstVideo == null || firstAudio == null) {
      Log.w(Tag, "reload: missing streams (video=${firstVideo != null} audio=${firstAudio != null}) → fallback")
      return null
    }
    val vFmt = rawToSabrFormatId(firstVideo, firstVideo.intOrNull("height") ?: 0)
    val aFmt = rawToSabrFormatId(firstAudio, 0)

    // 5) 建 session:WEB attested → 全 WEB 一致;visionOS 兜底 → 沿用 alpha.86 visionOS session(空 poToken)。
    val webPo = webPoToken // usedWeb=true 仅在 webPoToken!=null 时置位,该分支内 webPo 非空
    val session = if (usedWeb && webPo != null) {
      // WEB-reload 数据绑 WEB client → WEB ClientInfo + WEB UA + WEB poToken(注入 StreamerContext.field2)。
      // 不带 HTTP cookie/visitor(对齐 alpha.79 无 HTTP cookie 靠 protobuf;WEB 若需 visitor 待真机日志验证)。
      SabrSession.fromSabrData(
        sabrUrl,
        webPo,
        sabrData.ustreamerCfgB64,
        innerTubeClient.sabrClientInfo(),
        aFmt,
        vFmt,
        userAgent = InnerTubeClient.Client.WEB.userAgent,
        cookieHeader = "",
        visitorData = "",
        cpn = cpn,
        videoFormats = videoFormats,
      )
    } else {
      // visionOS 兜底:对齐 LibreTube,不带 poToken;ustreamerConfig 绑 visionOS → visionOS ClientInfo + UA。
      SabrSession.fromSabrData(
        sabrUrl,
        "",
        sabrData.ustreamerCfgB64,
        innerTubeClient.visionOsSabrClientInfo(),
        aFmt,
        vFmt,
        userAgent = InnerTubeClient.Client.VISION_OS.userAgent,
        cookieHeader = "",
        visitorData = "",
        cpn = cpn,
        videoFormats = videoFormats,
      )
    }
    Log.i(
      Tag,
      "SABR reload session: source=${if (usedWeb) "WEB-attested" else "visionOS"} sabrUrl=YES(${sabrUrl.take(60)}...) " +
        "poToken=${if (usedWeb && webPo != null) webPo.length else 0}B ustreamerCfg=${sabrData.ustreamerCfgB64.length}B " +
        "video=itag${vFmt.itag}(${vFmt.height}p) audio=itag${aFmt.itag} videoFormats=${videoFormats.size} " +
        "dur=${sabrData.durationMs}ms reloadTokenLen=${reloadToken.length}"
    )
    return NewPipeSabrResult(session, raws, sabrData.durationMs, emptyList())
  }

  /**
   * alpha.88:RELOAD 闭环兜底——用 NewPipe [StreamInfo.getInfo] 的 **dashMpdUrl**(android streamingData
   * manifest)直喂 [PlaybackInfo.remoteDashManifestUrl],播放器 [buildDashMediaItem] 拿到非空 remote URL
   * 即走 DashMediaSource 拉远程 MPD + 分段(ExoPlayer 自管 A/V 轨/SegmentList),≤1080p。对齐 LibreTube
   * SABR RELOAD 崩后落 `streams.dash`([NewPipeMediaServiceRepository.kt:312] `dash = resp.dashMpdUrl`)。
   *
   * **alpha.90:HLS 次选**——Phase 0 真机取证坐实 visionOS getInfo 的 **dashMpdUrl 恒空**(fork getDashMpdUrl
   * 仅读 android streamingData,android 无 poToken 对受保护视频取不到 manifest),故 alpha.88 DASH 分支实际
   * 从不触发。同次取证发现 visionOS /player 的 **hlsUrl 非空**(`hls_variant` manifest)——visionOS 是 Apple
   * 平台,YouTube 给 Apple 平台的 hlsUrl 是 AVPlayer 级原生 HLS 交付(非 web attestation 路径),作 dashMpdUrl
   * 空时的次选兜底:填 [PlaybackInfo.remoteHlsManifestUrl],播放器走 [HlsMediaSource] 分支(HLS playlist 自带
   * 多码率 + A/V,无需 init/index range 拼接,可原生 seek)。对齐 LibreTube `setStreamSource` 的 HLS last-resort 分支。
   *
   * 优先级:dashMpdUrl 非空 → DASH;否则 hlsUrl 非空 → HLS;否则 null(上层落常规 NewPipe harvest,会 RELOAD
   * 但已无其它出口)。返回的 PlaybackInfo 仅一条 dummy 视频轨(audioTracks 空——manifest 自带 A/V 轨),
   * 路由由 [PlaybackInfo.isHlsManifest]/[PlaybackInfo.hasRemoteManifest] 判定,非 dummy 轨字段。
   */
  private suspend fun buildDashFallbackFromNewPipe(
    videoId: String,
    durationMs: Long,
    request: PlaybackRequest,
    youtubeDefaultQuality: YoutubeDefaultQuality = YoutubeDefaultQuality.Auto,
  ): PlaybackInfo? {
    val info = runCatching { StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId") }
      .getOrElse {
        Log.w(Tag, "兜底: NewPipe getInfo failed: ${it.message}")
        return null
      }
    val resolvedDuration = if (durationMs > 0) durationMs else info.duration * 1000L
    // Phase 2 自合成 DASH(对齐 LibreTube `createDashSource`):NewPipe 流顶层暴露已解密 URL(content)
    // + initStart/initEnd/indexStart/indexEnd(同一 fork 738c3d4,LibreTube `toPipedStream` 直读)。
    // 用这些拼 <SegmentBase> 合成 MPD,提为主兜底(优先于 dashMpdUrl[已知恒空]/hlsUrl)。
    // range 守卫:任一轨 content/initStart/indexStart 为空则不自合成,落回 dashMpdUrl → hlsUrl(零回归)。
    // 诊断:打印候选流 range,真机确认 visionOS 流是否带 init/index range(验证可行性)。
    val videoCandidates = info.videoOnlyStreams
      .filter { !it.content.isNullOrBlank() && it.indexStart > 0 && it.height > 0 }
    val audioCandidates = info.audioStreams
      .filter { !it.content.isNullOrBlank() && it.indexStart > 0 }
    Log.i(Tag, "自合成DASH diag: videoCandidates=${videoCandidates.size} audioCandidates=${audioCandidates.size} (全视频=${info.videoOnlyStreams.size} 全音频=${info.audioStreams.size})")
    videoCandidates.take(3).forEach { v ->
      Log.i(Tag, "自合成DASH diag: v itag=${v.itag} ${v.height}p url=${v.content?.length}B init=[${v.initStart}-${v.initEnd}] index=[${v.indexStart}-${v.indexEnd}] codec=${v.codec}")
    }
    audioCandidates.take(2).forEach { a ->
      Log.i(Tag, "自合成DASH diag: a itag=${a.itag} url=${a.content?.length}B init=[${a.initStart}-${a.initEnd}] index=[${a.indexStart}-${a.indexEnd}] codec=${a.codec}")
    }
    val synthAudio = audioCandidates.firstOrNull { it.audioTrackType == AudioTrackType.ORIGINAL }
      ?: audioCandidates.firstOrNull { it.audioTrackType != AudioTrackType.DUBBED }
      ?: audioCandidates.firstOrNull()
    if (videoCandidates.isNotEmpty() && synthAudio != null) {
      // alpha.9X:DASH 自合成支持多档清晰度——全部带 range 的视频流各构一条 PlaybackTrack + 一档 quality,
      // 复用 alpha.81 多 Representation 机制(buildDashManifest 每条 track 生成一个 <Representation>,
      // 塞进同一 <AdaptationSet>),ExoPlayer 自动选轨/手动选档,与 SABR allVideoTracks 同构。
      // 每条流各自独立 URL + 独立 init/index range,本就是合法 DASH 多 Representation 结构。
      val sortedVideos = videoCandidates.sortedByDescending { it.height }
      // alpha.9X:对齐 LibreTube——清晰度菜单按分辨率去重(每个 height 一档,纯 "${height}p",不带 codec),
      // codec 由 ExoPlayer 自动选(多 codec 变体保留在 videoTracks)。LibreTube getAvailableResolutions
      // 从 currentTracks.groups 读 height 用 toSortedSet 去重,故 720p 只有一个选项(VP9/H264 不重复列)。
      val distinctVideos = sortedVideos.distinctBy { it.height }
      val qualities = distinctVideos.map { PlaybackQuality(id = it.itag, description = "${it.height}p") }
      // 选档:preferredQualityId 命中菜单用之(手动切清晰度);否则按默认画质设置(与 SABR defaultItag 同语义)。
      val maxHeight = youtubeDefaultQuality.maxHeight
      val defaultVideo = when {
        maxHeight != null -> distinctVideos.filter { it.height in 1..maxHeight }.maxByOrNull { it.height }
          ?: distinctVideos.minByOrNull { it.height }
        else -> distinctVideos.maxByOrNull { it.height }
      }
      val selectedVideo = request.preferredQualityId
        ?.takeIf { pid -> distinctVideos.any { it.itag == pid } }
        ?.let { pid -> distinctVideos.first { it.itag == pid } }
        ?: defaultVideo ?: return null
      val selectedQuality = qualities.firstOrNull { it.id == selectedVideo.itag } ?: qualities.first()
      fun buildVideoTrack(v: VideoStream): PlaybackTrack = PlaybackTrack(
        // alpha.9X:多 codec 变体同分辨率时,id 必须唯一(用 itag,对齐 SABR 路径),否则 MPD 里多个
        // <Representation id="0_0"> 重复 ID → ExoPlayer 切轨时加载错 init 段 → EOFException。
        id = v.itag,
        baseUrl = v.content!!, // filter 已保证 content 非空
        backupUrls = emptyList(),
        bandwidth = v.bitrate,
        codecs = v.codec ?: "",
        width = v.width,
        height = v.height,
        mimeType = v.format?.mimeType ?: "video/mp4",
        segmentBase = PlaybackSegmentBase("${v.initStart}-${v.initEnd}", "${v.indexStart}-${v.indexEnd}"),
      )
      val aTrack = PlaybackTrack(
        id = 0,
        baseUrl = synthAudio.content!!, // filter 已保证 content 非空
        backupUrls = emptyList(),
        bandwidth = synthAudio.bitrate,
        codecs = synthAudio.codec ?: "",
        width = 0,
        height = 0,
        mimeType = synthAudio.format?.mimeType ?: "audio/mp4",
        segmentBase = PlaybackSegmentBase("${synthAudio.initStart}-${synthAudio.initEnd}", "${synthAudio.indexStart}-${synthAudio.indexEnd}"),
      )
      // 手动选档与默认/Auto 统一:只回 selectedVideo(手动档或 Auto 默认/最高档)所在分辨率的全部
      // codec 变体(ExoPlayer 在该分辨率内自动选 codec,对齐 LibreTube),不再全轨喂 ExoPlayer 自适应。
      // 自合成 DASH 是 on-demand(SegmentBase),ExoPlayer 自适应按初始带宽(~1Mbps)从 360/480p 起步
      // 且不爬升(真机卡 360p/480p,显示却标 1080/2160);确定性从请求档起播与手动选档行为一致。
      val videoTracks = sortedVideos.filter { it.height == selectedVideo.height }.map { buildVideoTrack(it) }
      Log.i(Tag, "兜底: 自合成 DASH from NewPipe(video itag${selectedVideo.itag} ${selectedVideo.height}p [${videoTracks.size}/${sortedVideos.size}轨 ${qualities.size}档] + audio itag${synthAudio.itag}) dur=${resolvedDuration}ms → buildDashManifest 合成 MPD DashMediaSource")
      return PlaybackInfo(
        bvid = videoId,
        cid = 0L,
        title = request.title,
        durationMs = resolvedDuration,
        qualities = qualities,
        selectedQuality = selectedQuality,
        videoTracks = videoTracks,
        audioTracks = listOf(aTrack),
        headers = YoutubePlaybackHeaders,
      )
    }
    Log.i(Tag, "自合成DASH: 无 range 有效流(video=${videoCandidates.isNotEmpty()} audio=${synthAudio != null})→ 落 dashMpdUrl/HLS")
    val dashMpdUrl = info.dashMpdUrl
    if (!dashMpdUrl.isNullOrBlank()) {
      Log.i(Tag, "兜底: dashMpdUrl=${dashMpdUrl.length}B dur=${resolvedDuration}ms → 远程 MPD DashMediaSource")
      // dummy 视频轨:segmentBase 非 null → isProgressive=false → 路由 DashMediaSource 分支(真实轨由远程 MPD 定义)。
      val dummyTrack = PlaybackTrack(
        id = 0,
        baseUrl = dashMpdUrl,
        backupUrls = emptyList(),
        bandwidth = 0,
        codecs = "video/mp4",
        width = 0,
        height = 480,
        mimeType = "video/mp4",
        segmentBase = PlaybackSegmentBase("0-0", "0-0"),
      )
      val quality = PlaybackQuality(0, "DASH 兜底")
      return PlaybackInfo(
        bvid = videoId,
        cid = 0L,
        title = request.title,
        durationMs = resolvedDuration,
        qualities = listOf(quality),
        selectedQuality = quality,
        videoTracks = listOf(dummyTrack),
        audioTracks = emptyList(),
        headers = YoutubePlaybackHeaders,
        remoteDashManifestUrl = dashMpdUrl,
      )
    }
    // alpha.90:dashMpdUrl 空(android 无 manifest)→ 落 visionOS hlsUrl(Apple 平台原生 HLS 交付)。
    val hlsUrl = info.hlsUrl
    if (!hlsUrl.isNullOrBlank()) {
      Log.i(Tag, "兜底: dashMpdUrl 空 → hlsUrl=${hlsUrl.length}B dur=${resolvedDuration}ms → 远程 HLS HlsMediaSource")
      // dummy 视频轨:路由由 isHlsManifest()(remoteHlsManifestUrl!=null)判定,非轨字段;audioTracks 空(HLS playlist 自带 A/V)。
      val dummyTrack = PlaybackTrack(
        id = 0,
        baseUrl = hlsUrl,
        backupUrls = emptyList(),
        bandwidth = 0,
        codecs = "video/mp4",
        width = 0,
        height = 480,
        mimeType = "video/mp4",
        segmentBase = PlaybackSegmentBase("0-0", "0-0"),
      )
      val quality = PlaybackQuality(0, "HLS 兜底")
      return PlaybackInfo(
        bvid = videoId,
        cid = 0L,
        title = request.title,
        durationMs = resolvedDuration,
        qualities = listOf(quality),
        selectedQuality = quality,
        videoTracks = listOf(dummyTrack),
        audioTracks = emptyList(),
        headers = YoutubePlaybackHeaders,
        remoteHlsManifestUrl = hlsUrl,
      )
    }
    Log.w(Tag, "兜底: dashMpdUrl 与 hlsUrl 均空 → 返回 null(上层落常规 NewPipe harvest,会 RELOAD 但已无其它出口)")
    return null
  }

  /**
   * 下载专用解析:用 NewPipe [StreamInfo.getInfo] 取**已解密直链**(非 SABR `sabr://`),供离线下载。
   *
   * 播放 [resolve] 优先 SABR,产出的轨是 `sabr://` 协议不可直接下载字节;这里独立走 NewPipe 直链路径
   * (content 已 n/s 解密,同 [buildDashFallbackFromNewPipe])。
   *
   * @param preferMuxed true=音视频一体 progressive 单文件(≤720p itag 18/22);false=video-only + audio 分文件。
   * @param maxHeight 视频最大高度(null=最高)。video-only 高清常见 VP9/AV1,设备需支持硬解。
   */
  suspend fun resolveForDownload(
    request: PlaybackRequest,
    preferMuxed: Boolean,
    maxHeight: Int?,
    audioOnly: Boolean = false,
  ): ResolvedDownload? {
    val videoId = request.bvid
    val info = runCatching { StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId") }
      .getOrElse {
        // 只打 message 会漏(某些异常 message 为 null,曾被误报成「无直链」)——带上异常类 + 首个堆栈帧。
        Log.w(Tag, "resolveForDownload: NewPipe getInfo failed: ${it.javaClass.simpleName}: ${it.message}", it)
        return null
      }
    val headers = BiliPlaybackHeaders(
      sessData = null,
      biliJct = null,
      mid = null,
      referer = "https://www.youtube.com",
      origin = "https://www.youtube.com",
    ).asMap()

    // 音频-only:只下音频轨,video=null 使 enqueueOnIo 只插 AUDIO 分件。挑最佳(最大)音频流。
    if (audioOnly) {
      val audioCandidates = info.audioStreams.filter { !it.content.isNullOrBlank() && it.indexStart > 0 }
      val audio = audioCandidates.firstOrNull { it.audioTrackType == AudioTrackType.ORIGINAL }
        ?: audioCandidates.firstOrNull { it.audioTrackType != AudioTrackType.DUBBED }
        ?: audioCandidates.firstOrNull()
        ?: return null
      return ResolvedDownload(
        videoId = videoId,
        cid = 0L,
        title = request.title,
        coverUrl = request.coverUrl,
        durationMs = info.duration * 1000L,
        qualityLabel = "音频",
        video = null,
        audio = ResolvedPart(
          url = audio.content!!,
          mimeType = audio.format?.mimeType ?: "audio/mp4",
          codecs = audio.codec ?: "",
          width = 0,
          height = 0,
          initRange = "${audio.initStart}-${audio.initEnd}",
          mediaStartOffset = audio.indexStart.toLong(),
        ),
        headers = headers,
      )
    }

    // 简单下载:优先 progressive muxed 单文件;现代 YouTube 基本无 progressive 流(仅 video-only+audio),
    // 无则退化为 video-only+audio 两文件(≤720p,对齐 LibreTube 始终两文件 mux 的下载模型)。
    if (preferMuxed) {
      val muxed = (info.videoStreams)
        .filter { !it.content.isNullOrBlank() && it.height > 0 }
        .let { list ->
          list.filter { maxHeight == null || it.height <= maxHeight }.maxByOrNull { it.height }
            ?: list.minByOrNull { it.height }
        }
      if (muxed != null) {
        return ResolvedDownload(
          videoId = videoId,
          cid = 0L,
          title = request.title,
          coverUrl = request.coverUrl,
          durationMs = info.duration * 1000L,
          qualityLabel = "${muxed.height}p",
          muxed = ResolvedPart(
            url = muxed.content!!,
            mimeType = muxed.format?.mimeType ?: "video/mp4",
            codecs = muxed.codec ?: "",
            width = muxed.width,
            height = muxed.height,
            initRange = null,
            mediaStartOffset = 0L,
          ),
          headers = headers,
        )
      }
      Log.w(Tag, "resolveForDownload: 无 progressive muxed 流,退化为 video-only+audio(≤720p)")
      return buildSeparateParts(info, videoId, request, headers, maxHeight = maxHeight ?: 720)
    }

    return buildSeparateParts(info, videoId, request, headers, maxHeight)
  }

  /** 分文件下载:video-only(带 range) + audio,两文件由离线播放器现场 mux(对齐 LibreTube)。 */
  private fun buildSeparateParts(
    info: StreamInfo,
    videoId: String,
    request: PlaybackRequest,
    headers: Map<String, String>,
    maxHeight: Int?,
  ): ResolvedDownload? {
    val videoCandidates = info.videoOnlyStreams
      .filter { !it.content.isNullOrBlank() && it.indexStart > 0 && it.height > 0 }
    val video = videoCandidates
      .let { list -> list.filter { maxHeight == null || it.height <= maxHeight }.maxByOrNull { it.height } ?: list.maxByOrNull { it.height } }
    val audioCandidates = info.audioStreams.filter { !it.content.isNullOrBlank() && it.indexStart > 0 }
    val audio = audioCandidates.firstOrNull { it.audioTrackType == AudioTrackType.ORIGINAL }
      ?: audioCandidates.firstOrNull { it.audioTrackType != AudioTrackType.DUBBED }
      ?: audioCandidates.firstOrNull()
    if (video == null || audio == null) {
      Log.w(Tag, "resolveForDownload: 无 video-only/audio 分件(video=${video != null} audio=${audio != null})")
      return null
    }
    return ResolvedDownload(
      videoId = videoId,
      cid = 0L,
      title = request.title,
      coverUrl = request.coverUrl,
      durationMs = info.duration * 1000L,
      qualityLabel = "${video.height}p",
      video = ResolvedPart(
        url = video.content!!,
        mimeType = video.format?.mimeType ?: "video/mp4",
        codecs = video.codec ?: "",
        width = video.width,
        height = video.height,
        initRange = "${video.initStart}-${video.initEnd}",
        mediaStartOffset = video.indexStart.toLong(),
      ),
      audio = ResolvedPart(
        url = audio.content!!,
        mimeType = audio.format?.mimeType ?: "audio/mp4",
        codecs = audio.codec ?: "",
        width = 0,
        height = 0,
        initRange = "${audio.initStart}-${audio.initEnd}",
        mediaStartOffset = audio.indexStart.toLong(),
      ),
      headers = headers,
    )
  }

  /** raw adaptive JSON → SABR [SabrFormatId](itag/lastModified/xtags 来自 raw 字段,height 显式传)。 */
  private fun rawToSabrFormatId(raw: JsonObject, height: Int): SabrFormatId = SabrFormatId(
    raw.intOrNull("itag") ?: 0,
    raw.longOrNull("lastModified") ?: 0L,
    raw.stringOrNull("xtags"),
    height,
  )

  /** NewPipe [VideoStream] → SABR [SabrFormatId](itag/lastModified/xtags 来自 ItagItem,height 来自流)。 */
  private fun VideoStream.toSabrFormatId(): SabrFormatId = SabrFormatId(
    itag,
    itagItem?.lastModified ?: 0L,
    itagItem?.xtags,
    height,
  )

  /** NewPipe [AudioStream] → SABR [SabrFormatId](音频无 height)。 */
  private fun AudioStream.toSabrFormatId(): SabrFormatId = SabrFormatId(
    itag,
    itagItem?.lastModified ?: 0L,
    itagItem?.xtags,
    0,
  )

  /** Piped [PipedStream] → SABR [SabrFormatId](itag/lastModified/xtags 取 PipedStream,height 取流,音频 0)。 */
  private fun PipedStream.toSabrFormatId(): SabrFormatId = SabrFormatId(
    itag ?: 0,
    lastModified ?: 0L,
    xtags,
    height ?: 0,
  )

  /** 把 NewPipe 视频流包装成 /player adaptive 风格的 JsonObject(供 buildSabrPlaybackInfo 取 codec/height/fps)。 */
  private fun newPipeVideoRaw(stream: VideoStream): JsonObject = buildJsonObject {
    put("itag", stream.itag.toLong())
    put("height", stream.height)
    put("width", stream.width)
    put("mimeType", stream.format?.mimeType ?: "")
    put("codec", stream.codec ?: "")
    put("bitrate", stream.bitrate)
    put("fps", stream.fps)
  }

  /** 把 NewPipe 音频流包装成 /player adaptive 风格的 JsonObject。 */
  private fun newPipeAudioRaw(stream: AudioStream): JsonObject = buildJsonObject {
    put("itag", stream.itag.toLong())
    put("mimeType", stream.format?.mimeType ?: "")
    put("codec", stream.codec ?: "")
    put("bitrate", stream.bitrate)
  }

  /** 把 Piped 视频流包装成与 [newPipeVideoRaw] 同形的 JsonObject(供 [buildSabrTrack]/[buildSabrPlaybackInfo] 复用)。 */
  private fun pipedVideoRaw(stream: PipedStream): JsonObject = buildJsonObject {
    put("itag", (stream.itag ?: 0).toLong())
    put("height", stream.height ?: 0)
    put("width", stream.width ?: 0)
    put("mimeType", stream.mimeType ?: "")
    put("codec", stream.codec ?: "")
    put("bitrate", stream.bitrate ?: 0)
    put("fps", stream.fps ?: 0)
  }

  /** 把 Piped 音频流包装成与 [newPipeAudioRaw] 同形的 JsonObject。 */
  private fun pipedAudioRaw(stream: PipedStream): JsonObject = buildJsonObject {
    put("itag", (stream.itag ?: 0).toLong())
    put("mimeType", stream.mimeType ?: "")
    put("codec", stream.codec ?: "")
    put("bitrate", stream.bitrate ?: 0)
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
    subtitleTracks: List<PlaybackTrack> = emptyList(),
    youtubeDefaultQuality: YoutubeDefaultQuality = YoutubeDefaultQuality.Auto,
  ): PlaybackInfo {
    val aItag = sabrSession.audioFormatId.itag
    val aRaw = raws.firstOrNull { (it.longOrNull("itag")?.toInt() ?: 0) == aItag }
    val audioTrack = buildSabrTrack(aItag, aRaw, "audio", sid, videoId)
    // 多语言配音:全部可选音轨(供播放器音轨切换菜单)。按 id 去重——单音轨会话多个 itag 折叠成一条。
    val availableAudioTracks = sabrSession.audioTracks
      .map {
        PlaybackAudioTrack(
          id = it.id,
          languageCode = it.languageCode,
          displayName = it.displayName,
          isDefault = it.isDefault,
        )
      }
      .distinctBy { it.id }

    // 全部视频 itag 作清晰度菜单;videoFormats 为空(classic 仅首条)则兜底默认 videoFormatId。
    val videoFmts = sabrSession.videoFormats.ifEmpty { listOf(sabrSession.videoFormatId) }
    val qualities = videoFmts.sortedByDescending { it.height }.map { fmt ->
      val raw = raws.firstOrNull { (it.longOrNull("itag")?.toInt() ?: 0) == fmt.itag }
      val h = raw?.intOrNull("height") ?: fmt.height
      // alpha.78:codec 兜底读 "codec" key(对齐 buildSabrTrack)——NewPipe 路径 mimeType 是纯
      // "video/mp4" 不含 codecs=,但 newPipeVideoRaw 已把 stream.codec 写进 "codec" key;不兜底则
      // 新建会话的画质菜单丢 codec(显示裸 "1440p"),复用会话(WEB raws 带 codecs=)却显示 "1440p VP9"。
      val codec = shortCodec(
        extractCodecs(raw?.stringOrNull("mimeType") ?: "")
          .ifEmpty { raw?.stringOrNull("codec").orEmpty() }
      )
      PlaybackQuality(
        id = fmt.itag,
        description = (if (h > 0) "${h}p" else "itag ${fmt.itag}") + (if (codec.isNotEmpty()) " $codec" else ""),
      )
    }
    // 选档:preferredQualityId 命中菜单用之(播放中手动切清晰度优先);否则按默认画质设置选:
    //  - maxHeight != null:height <= maxHeight 的最高 itag(全部超上限时取最低档保证可播);
    //  - Auto:maxBy height(与 DASH 分支 pickVideo 的 Auto 语义一致——最大化分辨率;
    //    现状用会话首条,NewPipe 顺序不保证降序,可能并非最高)。
    //  同 sid 换 itag 即换清晰度(见上 alpha.29 注释),选非首条 itag 安全,无需重 harvest。
    val maxHeight = youtubeDefaultQuality.maxHeight
    val defaultItag = when {
      maxHeight != null ->
        videoFmts.filter { it.height in 1..maxHeight }.maxByOrNull { it.height }?.itag
          ?: videoFmts.minByOrNull { it.height }?.itag // 全部超过上限 → 取最低档
      else -> videoFmts.maxByOrNull { it.height }?.itag // Auto → 最高可用
    } ?: sabrSession.videoFormatId.itag
    val selectedItag = request.preferredQualityId
      ?.takeIf { pid -> videoFmts.any { it.itag == pid } }
      ?: defaultItag
    val selectedQuality = qualities.firstOrNull { it.id == selectedItag } ?: qualities.first()
    // alpha.81(复刻 LibreTube):manifest 塞全部视频轨,由 ExoPlayer 选轨。⚠️ 注意:AdaptiveTrackSelection
    // 按初始带宽估计(~1Mbps)起步,默认**不是**选最高 bitrate(旧注释误读),而是从低档起、带宽涨后爬档;
    // 且 SABR 单流 fetcher 下未下载轨 iterator=EMPTY,原生爬档被堵死。alpha.9X 已在 DefaultSabrChunkSource
    // 用「下一高码率档合成 iterator」解锁逐档升(见该文件 getNextChunk)。这里仍暴露全部轨供 ExoPlayer 选轨。
    // alpha.9X(修「SABR Auto 爬档超预设默认画质」):清单按 youtubeDefaultQuality 上限过滤——只放
    // height <= maxHeight 的档,ExoPlayer ABR 只能在预设上限内爬升,不会超过默认画质。qualities 菜单仍
    // 保留全量(手动切更高档不受限,见下方手动分支)。全部档都超上限时兜底最低档保证可播。
    val cappedVideoFmts = if (maxHeight != null) {
      videoFmts.filter { it.height in 1..maxHeight }
        .ifEmpty { listOf(videoFmts.minByOrNull { it.height } ?: videoFmts.first()) }
    } else {
      videoFmts
    }
    val allVideoTracks = cappedVideoFmts.map { fmt ->
      val raw = raws.firstOrNull { (it.longOrNull("itag")?.toInt() ?: 0) == fmt.itag }
      buildSabrTrack(fmt.itag, raw, "video", sid, videoId)
    }
    // alpha.9X(恢复清晰度选择):手动选档(preferredQualityId != null)时 videoTracks 只建选中 itag 单条,
    // ExoPlayer 只播该档(清晰度真正生效,锁单轨是刻意设计)。否则(默认/Auto)保持 alpha.81 全部轨 → 由
    // ExoPlayer 自适应选轨(默认带宽估计从低档起步,经 DefaultSabrChunkSource 合成 iterator 逐档爬升)。
    // 手动选 4K(itag313)→ SABR 单轨 RELOAD → 兜底提前落 DASH 出 4K;选 ≤1080p → SABR 直接播该档(避开 RELOAD)。
    val videoTracks = if (request.preferredQualityId != null) {
      val raw = raws.firstOrNull { (it.longOrNull("itag")?.toInt() ?: 0) == selectedItag }
      listOf(buildSabrTrack(selectedItag, raw, "video", sid, videoId))
    } else {
      allVideoTracks
    }
    Log.i(
      Tag,
      "SABR PlaybackInfo: sid=$sid sessionVideo=itag${sabrSession.videoFormatId.itag}(${sabrSession.videoFormatId.height}p) " +
        "qualities=${qualities.size} selected=itag$selectedItag(${selectedQuality.description}) " +
        "videoTracks=${videoTracks.size}(all=${allVideoTracks.size}) audio=itag$aItag(${audioTrack.codecs}) duration=${durationMs}ms → sabr:// DASH"
    )
    return PlaybackInfo(
      bvid = videoId,
      cid = 0L,
      title = request.title,
      durationMs = durationMs,
      qualities = qualities,
      selectedQuality = selectedQuality,
      videoTracks = videoTracks,
      audioTracks = listOf(audioTrack),
      headers = YoutubePlaybackHeaders,
      availableAudioTracks = availableAudioTracks,
      subtitleTracks = subtitleTracks,
    )
  }

  /** 构造单条 SABR track。元数据从 /player adaptive 原始 JSON 取,缺则用合理默认。
   *  alpha.29:视频流 baseUrl 带 `&itag=<itag>`(同 sid 换 itag 即换清晰度);audio 不带(用会话默认)。
   *  alpha.64(单流移植):isSabrSingle=true → 播放器走自定义 SabrMediaSource 分支(取代 alpha.59 合成 DASH)。 */
  private fun buildSabrTrack(itag: Int, raw: JsonObject?, stream: String, sid: String, videoId: String): PlaybackTrack {
    val rawMime = raw?.stringOrNull("mimeType")
    val mime = (rawMime ?: if (stream == "video") "video/mp4" else "audio/mp4").substringBefore(";").trim()
    // alpha.75 修真机无声:音频 codec 从 mimeType 抠为空(NewPipe MediaFormat.mimeType 是纯 "audio/mp4" 不含
    // codecs=),而 NewPipe Stream 自带 codec 字段(newPipeAudioRaw 已写入 "codec" key)→ 优先读它。reuse/WEB
    // 路径无 "codec" key 且 aRaw 可能为 null → 音频再按 itag 兜底(等价 Piped 的 codec 字段)。
    val codecs = extractCodecs(rawMime ?: "")
      .ifEmpty { raw?.stringOrNull("codec").orEmpty() }
      .ifEmpty { if (stream == "audio") audioCodecForItag(itag) else "" }
    val isVideo = stream == "video"
    // alpha.64:baseUrl 仍带 sid(供 isSabrSingle 扩展判断 scheme;SabrMediaSource 不用此 URL,用 manifest.sabrUrl)。
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
      // alpha.64:fps(/player adaptiveFormats 的 fps 字段,SabrManifest Representation 建表用)。
      fps = if (isVideo) (raw?.intOrNull("fps") ?: 0) else 0,
      // null → 播放器 progressive 分支(MergingMediaSource),SabrStreamingDataSource 接管 sabr://。
      segmentBase = null,
      // alpha.64(单流移植):走自定义 SabrMediaSource(单流,修 60s 断崖)。isSabrDash 合成 DASH 双流退役(保留死代码)。
      isSabrDash = false,
      isSabrSingle = true,
    )
  }

  /** alpha.75:音频 codec 的最后兜底——NewPipe/WEB 都取不到时按 itag 定(等价 Piped 的 codec 字段)。
   *  仅 mimeType 与 "codec" key 都无 codec 时触发(如 reuse/WEB 路径 aRaw=null)。 */
  private fun audioCodecForItag(itag: Int): String = when (itag) {
    139, 140, 141, 142 -> "mp4a.40.2"   // m4a / AAC-LC
    249, 250, 251      -> "opus"        // webm / Opus
    171, 172           -> "vorbis"      // webm / Vorbis
    338, 774           -> "flac"        // FLAC
    else               -> ""
  }

  /** alpha.29:清晰度描述用的简短 codec 标签(区分同高度多 codec,如 1080p H264 vs VP9 vs AV1)。 */
  private fun shortCodec(codecs: String): String = when {
    codecs.contains("av01", true) -> "AV1"
    codecs.contains("vp09", true) || codecs.contains("vp9", true) -> "VP9"
    codecs.contains("avc1", true) || codecs.contains("avc3", true) -> "H264"
    codecs.contains("hevc", true) || codecs.contains("hvc1", true) -> "HEVC"
    else -> ""
  }

  // ---- 格式挑选 ----

  private fun pickVideo(
    candidates: List<ParsedFormat>,
    preference: PlaybackCodecPreference,
    preferredItag: Int?,
    preferredMaxHeight: Int?,
  ): ParsedFormat? {
    // 用户在清晰度面板选中具体 itag（如 1080p/2K/4K）时，优先命中该档。
    if (preferredItag != null) {
      candidates.firstOrNull { it.itag == preferredItag }?.let { return it }
    }
    // 默认画质上限(设置里 YouTube 默认画质):选 height <= 上限的最高档。null=自动(最大化分辨率)。
    val pool = if (preferredMaxHeight != null) {
      candidates.filter { it.height <= preferredMaxHeight }
    } else {
      candidates
    }
    // 最大化分辨率，codec 偏好仅在同分辨率下打破平局。避免旧逻辑「avc 优先」压过更高的 vp9/av01。
    return pool.maxWithOrNull(
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

  private fun pickAudio(candidates: List<ParsedFormat>, preferredAudioTrackId: String?): ParsedFormat? {
    // 用户显式选了音轨(audioTrack.id)时优先命中。
    if (preferredAudioTrackId != null) {
      candidates.firstOrNull { it.audioTrackId == preferredAudioTrackId }?.let { return it }
    }
    // 优先原声/默认轨(audioTrack.audioIsDefault=true),跳过配音/翻译轨。
    // 多语言配音视频里同一 itag(如 251)会按语言重复出现,盲取第一条可能拿到配音轨。
    val original = candidates.firstOrNull { it.audioIsDefault }
    if (original != null) return original
    // 兜底(非多音轨视频):按 opus(251)/m4a(140)/最高码率。
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
    // 多语言配音(multi-audio)元数据:同一 itag 会按语言重复出现,audioTrack.audioIsDefault=true 才是原声轨。
    val audioTrack = node.obj("audioTrack")
    val audioIsDefault = audioTrack?.get("audioIsDefault")?.jsonPrimitive?.booleanOrNull ?: false
    val audioTrackId = audioTrack?.stringOrNull("id")
    val audioDisplayName = audioTrack?.stringOrNull("displayName")
    val languageCode = node.stringOrNull("language")
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
      audioIsDefault = audioIsDefault,
      audioTrackId = audioTrackId,
      audioDisplayName = audioDisplayName,
      languageCode = languageCode,
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
    /** 是否默认/原声轨(audioTrack.audioIsDefault=true)。多语言配音视频里同一 itag 会按语言重复出现。 */
    val audioIsDefault: Boolean = false,
    /** 音轨 id(audioTrack.id,如 "en.4",非 itag)。多语言配音视频用它区分各语言轨。 */
    val audioTrackId: String? = null,
    /** 音轨显示名(audioTrack.displayName,如 "English (Original)"/"中文")。 */
    val audioDisplayName: String? = null,
    /** 语言代码(顶层 language 字段,如 "en"/"zh-Hans")。 */
    val languageCode: String? = null,
    val kind: Kind = if (mimeType.startsWith("video/")) Kind.Video else Kind.Audio,
  )

  private companion object {
    const val Tag = "YtResolver"

    /** Piped 实例默认值(用户未填 pipedInstanceUrl 时用)。对齐 LibreTube 默认 kavin.rocks 公共实例。 */
    const val DEFAULT_PIPED_INSTANCE = "https://pipedapi.kavin.rocks"

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
