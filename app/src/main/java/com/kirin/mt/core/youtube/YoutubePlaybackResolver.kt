package com.kirin.mt.core.youtube

import android.util.Base64
import android.util.Log
import com.kirin.mt.core.download.ResolvedDownload
import com.kirin.mt.core.download.ResolvedPart
import com.kirin.mt.core.player.BiliPlaybackHeaders
import com.kirin.mt.core.player.CodecCapability
import com.kirin.mt.core.player.YoutubeCodecPreference
import com.kirin.mt.core.player.PlaybackAudioTrack
import com.kirin.mt.core.player.PlaybackInfo
import com.kirin.mt.core.player.PlaybackQuality
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.PlaybackSegmentBase
import com.kirin.mt.core.player.PlaybackTrack
import com.kirin.mt.core.player.YoutubeDefaultQuality
import com.kirin.mt.core.player.YoutubeDeliveryPriority
import com.kirin.mt.core.player.YoutubeStartQuality
import com.kirin.mt.core.youtube.sabr.FormatId as SabrFormatId
import com.kirin.mt.core.youtube.sabr.SabrAudioTrack
import com.kirin.mt.core.youtube.sabr.SabrClient
import com.kirin.mt.core.youtube.sabr.SabrFetchRequest
import com.kirin.mt.core.youtube.sabr.SabrFetchResult
import com.kirin.mt.core.youtube.sabr.SabrSession
import com.kirin.mt.core.youtube.sabr.SabrStreamRegistry
import com.kirin.mt.core.youtube.sabr.SabrStreamType
import com.kirin.mt.core.youtube.sabr.SabrProto
import com.kirin.mt.core.youtube.sabr.UmpReader
import com.kirin.mt.core.youtube.sabr.websafeBase64ToBytes
import android.net.Uri
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
  /** P11-101:WebView 内 yt-dlp solver(meriyah AST 结构匹配 + URL 类 transform),n/s decipher。 */
  private val solverDecipherer: YoutubeSolverDecipherer,
  private val httpClient: OkHttpClient,
  private val biliTvPoTokenProvider: NewPipePoTokenGenerator,
  /** Piped 后端客户端(可选,实验:对齐 LibreTube 默认 Piped 路径修 RELOAD)。null = 走 NewPipe(旧行为)。 */
  private val pipedClient: PipedClient? = null,
  /** 设置存储(读 youtubeUsePiped/pipedInstanceUrl/sabrForceSessionVideoItag)。null = 全 false/空(旧行为)。 */
  private val appSettingsStore: com.kirin.mt.core.settings.AppSettingsStore? = null,
  /**
   * P11-118d:WebView harvest 采集器(阶段 2 主材料来源)。null = 不启用(纯自造材料,旧行为)。
   * 它跑一次真实桌面 WebView 的 watch 页,截获浏览器自己发的 SABR POST;那份材料服务端才认。
   */
  private val sabrHarvester: YoutubeSabrHarvester? = null,
) {

  /** 从 player base.js 提取的 signatureTimestamp（对齐 youtubei.js Player.ts #getSignatureTimestamp）。 */
  private var signatureTimestamp: Int? = null

  /** 缓存的 base.js URL（避免 resolvePlayerJsUrl 重复拉 watch 页）。 */
  private var cachedPlayerJsUrl: String? = null

  /** P11-118 诊断:已跑过 harvest 的 videoId → 上次采集时刻(进程内防风控)。
   *  P11-118g:改**时间窗**去重(45s)——原先一次性去重导致「会话被看门狗/错误重载后不再 harvest,
   *  只能用已知会死的自造材料」(r1959 真机:harvest 会话被 stall 重载后,新会话用自造材料,
   *  60s 处 status=3 处决 → 又一轮重载)。 */
  private val harvestProbed = java.util.concurrent.ConcurrentHashMap<String, Long>()

  /**
   * 2026-09-20(补 P11-118c 判别实验):该视频**最近一次成功** harvest 到的浏览器原始捕获
   * (URL + bodyB64)。由 [harvestSessionMaterial] 在材料解得出时写入。
   *
   * 为什么需要它:此前 `cap` 只在「材料解不出」的失败分支里被 [replayHarvestCapture] 取证,
   * **成功那份直接丢掉**;于是真机 15:09-15:18 那种「会话建起来了、却在运行时被判死」的形态,
   * 手里没有任何可重放的材料,判别实验做不了。
   */
  private val lastHarvestCapture =
    java.util.concurrent.ConcurrentHashMap<String, YoutubeSabrHarvester.SabrCapture>()

  /**
   * 2026-09-20:已对该视频跑过「运行时判死 → 重放取证」的标记(**每视频一次**)。
   *
   * 为什么要限一次:重放是白烧一发真实 POST,而结论(材料好不好)不会因为多跑几发而不同;
   * 不做这个限制会变成每次 resolve 都打一发,反而把自己变成风控目标。
   */
  private val replayedAfterWebDeath = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  suspend fun resolve(
    request: PlaybackRequest,
    codecPreference: YoutubeCodecPreference,
    codecCapability: CodecCapability,
    youtubeDefaultQuality: YoutubeDefaultQuality = YoutubeDefaultQuality.Auto,
    youtubeStartQuality: YoutubeStartQuality = YoutubeStartQuality.Q480,
    // P11-126:调用方(起播)给的绝对 deadline(`System.currentTimeMillis()` 基准),0 = 不限。
    // 见 [YoutubeLaunchBudget]:WEB-SABR 优先链的固定开销(PO token ~9s + player js ~4s +
    // harvest WebView 冷启 4~11s)本身就吃掉 21-28s,而 harvest 自己那两层超时(40s/30s)原本与
    // 外层完全互不感知——真机 09-19 就是 harvest 烧到一半外层预算到期,整条 launch 被取消,
    // 连已建好的 NewPipe 兜底会话也一起丢弃。故这里按剩余预算决定「值不值得试」以及每层给多久。
    deadlineMs: Long = 0L,
  ): PlaybackInfo = withContext(Dispatchers.IO) {
    val videoId = request.bvid
    var lastError: String? = null
    var havePlayable = false

    // P11-126:剩余预算。deadlineMs<=0 视为不限(移动端与既有调用点不传 → 行为与今天完全一致)。
    fun remainingMs(): Long = if (deadlineMs <= 0L) Long.MAX_VALUE else (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)

    // 播放路径优先级(P11-114):Dash = DASH 自合成优先(慢 SABR 首段被 stall 看门狗误杀场景的逃生通道);
    // WebSabr = 强制 WEB attested 路径优先(门控视频/4K);Sabr(默认)= NewPipe SABR 主链。
    val deliveryPriority = appSettingsStore?.settings?.first()?.youtubeDeliveryPriority ?: YoutubeDeliveryPriority.Sabr
    val dashFirst = deliveryPriority == YoutubeDeliveryPriority.Dash
    val webSabrFirst = deliveryPriority == YoutubeDeliveryPriority.WebSabr

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
          val r = buildSabrSessionFromPiped(videoId, piped, youtubeDefaultQuality, request.preferredAudioTrackId)
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
              youtubeStartQuality = youtubeStartQuality,
              codecPreference = codecPreference,
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

    // ── P11-127(全移动):WEB-SABR 的 token 换**移动 minter** ────────────────────────────────
    // 此前 WEB-SABR 用上面那枚 `botGuard` token:它的挑战源是**桌面 watch 页**(OkHttp 桌面 UA 抓
    // ytcfg/`ytAtN` + `/att/get` 桌面 ctx)。移动 UA 下 watch 页 302 到 m.youtube.com 且**没有
    // `ytAtN`**(docs/youtube-web-sabr.md:91-93,P11-103),这条挑战链在「全移动」世界里结构性不可用;
    // 与它搭配的会话侧桌面身份又正是 P11-106 判为「身份生效但 nag 依旧」的那一半。
    // 替代物是**已经在用的**移动 minter:`PoTokenWebView`(移植 LibreTube,bgutils/BotGuard 在
    // cookie-less 隐藏 WebView 里铸,移动 UA),SABR 主链在 `getWebClientPoToken` / `refreshPoToken`
    // 处已用它,并已真机验证可铸。
    // 只在 WEB-SABR 真会出场时铸(用户选「WEB-SABR 优先」档,或 NewPipe 主链已 RELOAD / DASH 已失败
    // 的兜底场景),避免给默认 SABR 档白付铸造耗时;铸造失败回落原 botGuard token(不阻断)。
    // 2026-09-20(补 P11-118c 判别实验 —— 「把 WEB-SABR 走通」的分岔判据):上一次 WEB 会话在**运行时**
    // 被判死时(fetcher 撞 `InvalidPoToken status=3` → [SabrStreamRegistry.markWebSabrFailed]),把当时
    // 那份**浏览器亲手产生、服务端已回 200** 的材料原样重放一发,留一条 `status=?` 证据。
    //
    // **为什么必须放在 resolve 顶层**:判死标记会让下面两条 WEB 分支整体跳过(`buildWebSabrFallback`
    // 不再被调用),挂在 harvest 流程里就永远跑不到 —— 而"跑不到"正是这条实验自 09-16 之后再没出过
    // 证据的原因(P11-118c 只在"材料解不出"时才跑)。
    //
    // 判据(决定后续所有工作的方向):
    //   status=1    → 材料是好的,差异在我们**会话构造 / 传输** ⇒ 逐字段对齐可做,且那是唯一的活;
    //   status=2/3  → 服务端不看材料(§5.6 已出过一次此结论)⇒ 对齐字节没意义,换杠杆(会话轮换 / 身份)。
    val staleWebCapture = lastHarvestCapture[videoId]
    if (staleWebCapture != null && SabrStreamRegistry.isWebSabrFailed(videoId) &&
      replayedAfterWebDeath.add(videoId)
    ) {
      Log.w(
        Tag,
        "P11-118 harvest replay: $videoId WEB-SABR 运行时判死 → 重放上次浏览器材料取证(每视频一次)",
      )
      replayHarvestCapture(staleWebCapture)
    }

    val webSabrInPlay = webSabrFirst ||
      SabrStreamRegistry.reloadCount(videoId) > 0 ||
      SabrStreamRegistry.isDashFallbackFailed(videoId)
    val webSabrPoToken: String? = if (!webSabrInPlay) {
      poToken
    } else {
      runCatching { biliTvPoTokenProvider.ensureWebToken(videoId)?.streamingDataPoToken }
        .getOrNull()
        ?.also { Log.i(Tag, "WEB-SABR token: mobile minter (${it.length} chars)") }
        ?: poToken.also { Log.w(Tag, "WEB-SABR token: mobile minter 未产出 → 回落 botGuard token(${it?.length ?: 0} chars)") }
    }

    // 提取 signatureTimestamp（对齐 youtubei.js Player.ts #getSignatureTimestamp），注入 /player
    // 的 contentPlaybackContext。缺它 WEB /player 可能被判"非真浏览器" → "The page needs to be reloaded"。
    val signatureTimestamp = resolveSignatureTimestamp(videoId)

    // ── P11-114(用户设置「WEB-SABR 优先」):强制先走 WEB attested 路径(桌面身份+cpn+poToken)──
    // 适用门控视频/4K 强制场景;失败标记后落回 NewPipe SABR 主链(主链 RELOAD 时 ② 兜底段
    // 因 isWebSabrFailed 不再重复尝试,防循环)。
    // P11-126:先看剩余预算够不够——不够就不进 harvest(它最长会烧 40s+30s),直接落 NewPipe。
    // 真机 09-19:harvest 冷启把 30s 预算吃光,整条 launch 被取消,连兜底会话也白建;这条早退
    // 至少保证「兜底能落地、用户不必白等」。
    val webSabrFirstBudgetShort = webSabrFirst && poToken != null && remainingMs() < MinWebSabrFirstBudgetMs
    if (webSabrFirstBudgetShort) {
      Log.w(
        Tag,
        "WEB-SABR 优先:剩余预算 ${remainingMs()}ms < ${MinWebSabrFirstBudgetMs}ms" +
          "(harvest 冷启就要 4~11s,注定来不及)→ 跳过 WEB-SABR,直落 NewPipe 主链(不烧 WebView/solver)",
      )
    }
    // 2026-09-20(**运行时判死的标记必须在这条路上也被认**):此分支此前只查预算,不查 [isWebSabrFailed]。
    // 而运行时判死(fetcher 撞 `InvalidPoToken status=3`,真机 15:09-15:18 WEB 每会话第 2 笔即死)现在也会
    // 置标记 —— 不查它就会:建成功→clear→运行时死→**标记**→本次 resolve 仍重试 WEB-SABR→建成功→clear→…
    // 与修复前一样无限循环(下方 `webSabrDue` 那条分支本来就有这个守卫,只补 fetcher 侧会漏掉这里)。
    val webSabrFirstBlocked = webSabrFirst && SabrStreamRegistry.isWebSabrFailed(videoId)
    if (webSabrFirstBlocked) {
      Log.w(
        Tag,
        "WEB-SABR 优先:该视频 WEB-SABR 已判死(运行时 token 被服务端拒)→ 跳过," +
          "落 NewPipe 主链(pot-less SABR 实测可播)",
      )
    }
    if (webSabrFirst && poToken != null && !webSabrFirstBudgetShort && !webSabrFirstBlocked) {
      val webSabr = runCatching {
        buildWebSabrFallback(videoId, webSabrPoToken, signatureTimestamp, request, youtubeDefaultQuality, deadlineMs)
      }.onFailure {
        // P11-125:这条链整条包 runCatching——不落证就等于「失败且不知道为什么」。
        Log.w(Tag, "WEB-SABR(优先)链异常: ${it::class.simpleName}: ${it.message}", it)
      }.getOrNull()
      if (webSabr != null) {
        SabrStreamRegistry.clearWebSabrFailed(videoId)
        YoutubeLoadProgress.emit(YoutubeLoadStep.Connect)
        Log.i(Tag, "WEB-SABR 优先(用户设置) → playback ready: videoId=$videoId sid=${webSabr.second} → sabr:// DASH")
        return@withContext webSabr.first
      }
      SabrStreamRegistry.markWebSabrFailed(videoId)
      Log.w(Tag, "WEB-SABR 优先失败 → 落 NewPipe 主链(SABR→DASH 兜底)")
    }

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
    // P11-102(guard 放开):NewPipe SABR 仍不放(空 pot 重建必再 RELOAD),但 ② 兜底段在
    // reloadCount>0 且 pot 已铸出时直接走 WEB-SABR(pot>0 会话,r1927 真机证一把过),省掉注定
    // 403 的 DASH 一轮。
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
      // P11-119c:SABR 主链同样消费 preferredAudioTrackId(此前只有 WEB-SABR 消费 ⇒ 默认「SABR 优先」
      // 档位下点选音轨恒无效,会话永远落原声轨)。
      buildSabrSessionFromNewPipe(
        videoId, poToken, youtubeDefaultQuality, youtubeStartQuality,
        preferredAudioTrackId = request.preferredAudioTrackId,
      )
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
        youtubeStartQuality = youtubeStartQuality,
        codecPreference = codecPreference,
      )
    }
    // ② NewPipe 无 SABR → DASH/HLS 兜底(alpha.92 自合成 DASH 为主,次 dashMpdUrl[恒空]/HLS)。durationMs 传 0
    //    → buildDashFallbackFromNewPipe 内部用 info.duration 兜底。dashFirst 时 DASH 已先试过,跳过。
    if (!dashFirst) {
      // P11-101 Phase 2c(生产兜底):门控视频(ANDROID 直链 403 判死)→ WEB SABR 会话——
      // attested WEB /player(自铸 poToken)→ parseSabrData → solver n-decrypt(WebView 内
      // yt-dlp solver,08:15 r1921 真机全链通:transformed → init POST MEDIA ok)→
      // SabrSession(WEB ClientInfo + WEB poToken)+ registerByVideoId(status=2 刷新回调)。
      // 成功即返回 sabr:// PlaybackInfo(与普通视频同一播放链路,自适应);失败落 DASH 兜底。
      //
      // P11-102(guard 放开):RELOAD 过(reloadCount>0)且 pot 已铸出 → **直接** WEB-SABR(pot>0
      // 会话),不再先撞注定 403 的自合成 DASH 兜底(真机 09-15 13:14 KXXZbbnm9t0:guard 跳 NewPipe
      // SABR——空 pot 重建必再 RELOAD,不放——→ DASH 403 → 又烧一轮 auto-retry 才到 WEB-SABR,
      // ~60s 才恢复;而 WEB-SABR pot=128B 一把过)。WEB-SABR 失败标记 [markWebSabrFailed] 后落
      // DASH/HLS,原 isDashFallbackFailed 通道不变。
      val webSabrDue =
        !SabrStreamRegistry.isWebSabrFailed(videoId) &&
          remainingMs() >= MinWebSabrFirstBudgetMs &&
          (SabrStreamRegistry.isDashFallbackFailed(videoId) ||
            (SabrStreamRegistry.reloadCount(videoId) > 0 && poToken != null))
      if (webSabrDue) {
        val webSabr = runCatching {
          buildWebSabrFallback(videoId, webSabrPoToken, signatureTimestamp, request, youtubeDefaultQuality, deadlineMs)
        }.onFailure {
          // P11-125:同上——兜底段失败也必须留证。
          Log.w(Tag, "WEB-SABR(兜底)链异常: ${it::class.simpleName}: ${it.message}", it)
        }.getOrNull()
        if (webSabr != null) {
          SabrStreamRegistry.clearWebSabrFailed(videoId)
          YoutubeLoadProgress.emit(YoutubeLoadStep.Connect)
          Log.i(Tag, "NewPipe-first → WEB-SABR 兜底 playback ready: videoId=$videoId sid=${webSabr.second} → sabr:// DASH")
          return@withContext webSabr.first
        }
        SabrStreamRegistry.markWebSabrFailed(videoId)
        Log.w(Tag, "WEB-SABR 兜底未产出 → 落 DASH 兜底(探针取证随行)")
        runCatching { probeWebDashChain(videoId, poToken, signatureTimestamp) }
          .onFailure { Log.w(Tag, "P11-101 probe threw: ${it.message}") }
      }
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
              codecPreference = codecPreference,
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
              // P11-119c:判据必须含 **xtags**——多音轨视频的每条音轨共用同一 itag(真机 r1963:
              // en-US 配音与 zh-Hant 原声都是 itag140),只比 itag 会把切轨判成「无变化」直接跳过。
              if (request.preferredAudioTrackId != null) {
                val match = session.audioTracks.firstOrNull { it.id == request.preferredAudioTrackId }
                if (match != null &&
                  (match.formatId.itag != session.audioFormatId.itag ||
                    match.formatId.xtags != session.audioFormatId.xtags)
                ) {
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
              return@withContext buildSabrPlaybackInfo(
                request, videoId, durationMs, raws, session, cachedSid,
                codecPreference = codecPreference,
              )
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
          val npResult = buildSabrSessionFromNewPipe(
            videoId, poToken, youtubeDefaultQuality, youtubeStartQuality,
            preferredAudioTrackId = request.preferredAudioTrackId,
          )
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
              codecPreference = codecPreference,
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
    // P11-106:WEB-SABR 链传桌面 watch 页 ytcfg 的 INNERTUBE_CONTEXT(会话身份=桌面 WEB)。
    contextOverride: JsonObject? = null,
    // P11-115:桌面 watch 页 Set-Cookie / visitorData——/player 的 HTTP 身份与 body context 同源。
    cookieOverride: String? = null,
    visitorOverride: String? = null,
    // P11-117:UA 覆盖(见 InnerTubeClient.postJson)。WEB-SABR 传桌面 UA——此前该链**没有 UA 入口**,
    // 「桌面身份」只进了 body context。
    uaOverride: String? = null,
    // P11-117:强制走 OkHttp。WEB 默认走 browserSession WebView,而那条路的 UA 由移动
    // settings.userAgentString 决定、Cookie 头被丢(fetchViaWebView)——桌面四件套只有 OkHttp 能带上。
    forceOkHttp: Boolean = false,
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
    // P11-117:forceOkHttp 时跳过 WebView——桌面 UA/Cookie 只有 OkHttp 分支真能带上。
    val useWebView = !forceOkHttp &&
      (client == InnerTubeClient.Client.WEB || client == InnerTubeClient.Client.WEB_EMBEDDED || client == InnerTubeClient.Client.TVHTML5)
    // alpha.89:WebView fetch 安全网——若 browserSession 卡在错误页(origin=null)→ fetch CORS 失败抛错,
    // 此前直接冒泡致 resolve "no decodable formats" 全视频播不了(真机 alpha.88 "现在都不能播放")。
    // 捕获 viaWebView 异常 → 回退 OkHttp 直连(viaWebView=false)。OkHttp WEB /player 可能被判
    // "The page needs to be reloaded"(unplayable),但至少返回结构化响应而非硬崩;部分视频仍可取流。
    // P11-125:耗时 + 异常必须留证。真机 09-19 r1979 三次 WEB-SABR /player 在 `WEB-SABR identity`
    // 之后 **1ms** 内失败,而这里 `else throw e` 把异常原样抛出、调用方 runCatching{}.getOrNull()
    // 再吞一层,结果只剩一句 "WEB /player failed → abort",连 message 都没有。
    // 「1ms 内瞬时抛错」(会话数据/参数/WebView 状态)与「30s 网络超时」修法完全不同,故先落证再抛。
    val startedAt = System.currentTimeMillis()
    return runCatching {
      innerTubeClient.postJson("/player", payload, client = client, poToken = poToken, viaWebView = useWebView, contextOverride = contextOverride, cookieOverride = cookieOverride, visitorOverride = visitorOverride, uaOverride = uaOverride)
    }.getOrElse { e ->
      val costMs = System.currentTimeMillis() - startedAt
      if (useWebView) {
        Log.w(Tag, "postPlayer $client viaWebView failed after ${costMs}ms (${e::class.simpleName}: ${e.message}) → fallback OkHttp viaWebView=false", e)
        innerTubeClient.postJson("/player", payload, client = client, poToken = poToken, viaWebView = false, contextOverride = contextOverride, cookieOverride = cookieOverride, visitorOverride = visitorOverride, uaOverride = uaOverride)
      } else {
        Log.w(Tag, "postPlayer $client viaWebView=false failed after ${costMs}ms (${e::class.simpleName}: ${e.message}) → 抛给调用方", e)
        throw e
      }
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
      val resolved = raw?.takeIf { it.isNotBlank() }
        ?.replace("\\/", "/")
        ?.replace("\\u0026", "&")
        ?.let { if (it.startsWith("http")) it else "https://www.youtube.com$it" }
      // P11-117(诊断):打 jsUrl **内容**而非长度——此前 solver 失败时只知长度,判不出是不是拿错
      // player 版本(base.js 版本必须与签发 sabrUrl 里 n 的那个 player 同源)。UA 保持移动口径不动
      //(改 UA 会同时改 n 解密前提,变量不唯一),等这条日志拿出版本号再决定是否对齐桌面。
      Log.i(Tag, "resolvePlayerJsUrl(mobile UA) → ${resolved?.take(120)}")
      resolved
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

  /**
   * P11-120:把 NewPipe 给的字幕 URL 改写成 **WebVTT**(`fmt=vtt`)。
   *
   * 为什么必须改写:NewPipe fork 的 `StreamInfo.subtitles` 来自 `getSubtitlesDefault()` —— 拿的是
   * **默认格式(TTML/XML)** 的 URL(`…&fmt=ttml`)。播放端按 `text/vtt` 建 Format + `WebvttParser` 解析,
   * 于是每条字幕轨都在加载后抛 `ParserException: Expected WEBVTT. Got <?xml version="1.0" …>`,
   * media3 把这个错误当**轨级失败**处理(`Disabling track due to error`)→ 播放不受影响但字幕永不出现。
   * 2026-09-17 真机日志实锤(zh/en/th 三条轨同签名),用户视角就是「有选项没效果」。
   *
   * 改写安全性:与 NewPipe 内部 `getSubtitles(MediaFormat.VTT)` 完全同款做法 —— 同一个 baseUrl 只换
   * `fmt`。`fmt` **不在** `sparams=ip,ipbits,expire,v,ei,caps,opi,xoaf` 里,签名不受影响;
   * 本机 curl 复核过 ANDROID / visionOS 两条 /player 的签名 URL 加 `&fmt=vtt` 均 200 且是真 WEBVTT。
   *
   * 幂等:已经是 `fmt=vtt` 就原样保留;没有 `fmt` 参数则补一个。
   */
  private fun forceWebVttUrl(url: String): String {
    if (url.isEmpty()) return url
    if (SubtitleFmtParamRegex.containsMatchIn(url)) {
      return SubtitleFmtParamRegex.replace(url) { match -> match.groupValues[1] + "fmt=vtt" }
    }
    return url + if (url.contains('?')) "&fmt=vtt" else "?fmt=vtt"
  }

  /**
   * WEB-SABR 字幕数据源:从**已经拉下来的** WEB `/player` 响应里取字幕轨。
   *
   * 为什么需要这条:NewPipe 主链的字幕来自 `StreamInfo.subtitles`(见 [buildSabrSessionFromNewPipe]),
   * 而 WEB-SABR 路径**根本不调 getInfo**(它的材料是 harvest/自造 WEB `/player`)⇒ `subtitleTracks`
   * 恒空 ⇒ 用户视角「WEB-SABR 档下没有字幕选项」。数据其实就在手上那份 `player` 里:
   * `captions.playerCaptionsTracklistRenderer.captionTracks[]`。
   *
   * 字段映射(与 NewPipe 路径同形,播放端无须区分来源):
   * - `baseUrl` → [forceWebVttUrl] 改写 `fmt=vtt`(WEB 默认同样给 TTML/XML;不改写会被 WebvttParser
   *   判 ParserException 静默 disable 该轨,即 P11-120b 的那个坑);
   * - `languageCode` → languageCode;`name.simpleText`(或 `name.runs[0].text`)→ displayName;
   * - `kind=="asr"` 或 `vssId` 以 `a.` 开头 → isAutoGenerated(自动生成轨)。
   *
   * 取不到(视频无字幕/响应无 captions)返回空表,播放端不显示字幕项,零副作用。
   *
   * [poToken]:WEB 字幕 URL 带 `exp=xpe` 时需 `pot`(见 [withSubsPotToken]),传当前视频那枚
   * content-bound token;null/空则不加(该轨会回空 200 被静默 disable,不影响主源)。
   */
  private fun webPlayerSubtitleTracks(player: JsonObject, poToken: String?): List<PlaybackTrack> {
    val captionTracks = player.obj("captions")
      ?.obj("playerCaptionsTracklistRenderer")
      ?.array("captionTracks")
      ?: return emptyList()
    return captionTracks.mapNotNull { it as? JsonObject }
      .mapIndexedNotNull { index, track ->
        val rawUrl = track.stringOrNull("baseUrl") ?: return@mapIndexedNotNull null
        val name = track.obj("name")
        val vssId = track.stringOrNull("vssId")
        PlaybackTrack(
          id = index,
          baseUrl = forceWebVttUrl(withSubsPotToken(rawUrl, poToken)),
          backupUrls = emptyList(),
          bandwidth = 0,
          codecs = "",
          width = 0,
          height = 0,
          mimeType = "text/vtt",
          languageCode = track.stringOrNull("languageCode"),
          displayName = name?.stringOrNull("simpleText")
            ?: name?.array("runs")?.firstOrNull()?.let { (it as? JsonObject)?.stringOrNull("text") },
          isAutoGenerated = track.stringOrNull("kind") == "asr" || vssId?.startsWith("a.") == true,
        )
      }
  }

  /**
   * NewPipe 字幕轨 → 播放端轨(与 [webPlayerSubtitleTracks] 同形,播放端无须区分来源)。
   *
   * P11-120 / P11-120b 的既有结论在此集中一处:
   * - NewPipe fork 的 `StreamInfo.subtitles` 来自 `getSubtitlesDefault()`,给的是**默认格式(TTML/XML)**
   *   URL(`…&fmt=ttml`),必须 [forceWebVttUrl] 改写成 vtt,否则 WebvttParser 抛
   *   `Expected WEBVTT. Got <?xml …` 并把该轨**静默 disable**(真机 r1966 三条轨全部如此);
   * - `displayLanguageName` 供字幕面板显示;`isAutoGenerated`(asr)用于区分同语言的人工/自动双轨
   *   (role flags:人工 `ROLE_FLAG_CAPTION` / asr `ROLE_FLAG_SUPPLEMENTARY`)。
   *
   * P11-119d:抽成函数供 SABR 会话路径与 DASH 兜底路(见 [buildDashFallbackFromNewPipe])共用——
   * 两条路的 `info.subtitles` 同源,字幕行为必须一致,别再各写一份。
   */
  private fun newPipeSubtitleTracks(subtitles: List<SubtitlesStream>): List<PlaybackTrack> =
    subtitles.mapIndexed { index, subtitle ->
      PlaybackTrack(
        id = index,
        // 关键:NewPipe 给的是 **TTML/XML** URL,必须改写成 vtt(见上,真机实锤三条轨全中)。
        baseUrl = forceWebVttUrl(subtitle.url.orEmpty()),
        backupUrls = emptyList(),
        bandwidth = 0,
        codecs = "",
        width = 0,
        height = 0,
        mimeType = "text/vtt",
        languageCode = subtitle.languageTag,
        // NewPipe fork 的 getDisplayLanguageName() 理论上不抛,但它是解析产物,别让字幕显示名拖垮取流。
        displayName = runCatching { subtitle.displayLanguageName }.getOrNull(),
        isAutoGenerated = subtitle.isAutoGenerated,
      )
    }

  /**
   * WEB 客户端签发的字幕 URL **需要 PO token**:baseUrl 的 `exp` 里出现 `xpe`/`xpv` 即标记该视频在
   * PO token 灰度内,不带 `pot` 时 `/api/timedtext` 回 **空 200**(正文 0 字节)。
   *
   * 真机 r1968(2026-09-17 01:09)症状逐字对应:4 条 WEB 字幕轨全部挂载成功、点选后
   * `WebvttParser: Expected WEBVTT. Got null` → media3 `Disabling track due to error`,字幕永不出现。
   *
   * 参数形态对齐 yt-dlp [PR #13234](https://github.com/yt-dlp/yt-dlp/pull/13234)(issue #13075):
   * `pot=<token>`(**websafe base64 原样**,不解码)+ `potc=1` + `c=<clientName>`。token 绑定与
   * PLAYER 同款 = **videoId 内容绑定**,故 [buildWebSabrFallback] 手上那枚 poToken 直接复用
   * (同一枚已用于该视频的 /player 与 SABR 会话,无需另铸 subs 上下文 token)。
   *
   * 对照:ANDROID/visionOS 的 captionTracks 是**签名 URL**(`signature=` + `sparams=…`,无 `exp=xpe`),
   * 不追加 pot 也 200 真 WEBVTT —— 见 `docs/youtube-api-notes.md`(2026-09-17 复测),故这里按 `exp` 判据
   * 只在需要时才补参数,不动 NewPipe 那条已验证可用的路。
   */
  private fun withSubsPotToken(url: String, poToken: String?): String {
    if (poToken.isNullOrBlank()) return url
    val needsPot = url.split("&").any { part ->
      part.startsWith("exp=") && part.removePrefix("exp=").split(",").any { it == "xpe" || it == "xpv" }
    }
    if (!needsPot) return url
    val sep = if (url.contains('?')) "&" else "?"
    return "$url${sep}potc=1&pot=${Uri.encode(poToken)}&c=WEB"
  }

  /** 取 URL query 里 key 的值(如 `&cpn=<value>` → value);无则 null。 */
  private fun queryParam(url: String, key: String): String? {
    val qIdx = url.indexOf("?")
    if (qIdx < 0) return null
    return url.substring(qIdx + 1).split("&")
      .firstOrNull { it.startsWith("$key=") }?.substringAfter("=")
  }

  /** P11-113:16 字符随机 cpn(youtubei.js Utils.generateRandomString(16) 同源,FT Watch.js L659 同款)。 */
  private fun generateCpn(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(16) { append(alphabet.random()) } }
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
    youtubeStartQuality: YoutubeStartQuality = YoutubeStartQuality.Q480,
    /**
     * P11-119c:用户点选的音轨 id(PlaybackRequest.preferredAudioTrackId)。命中 [AudioStream.getAudioTrackId]
     * 即用该轨的 formatId 建会话(xtags 决定服务端发哪条音频),未命中/为空回落原声轨。
     * 此前这条主链**完全不看它**(签名里就没有 request)⇒ 「SABR 优先」档位下切轨恒无效。
     */
    preferredAudioTrackId: String? = null,
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
    // P11-97(09-14):绑定档改为**与自动选轨对齐**——绑自动选轨实际首轨(起播锁档的起始档),
    // 而非默认画质上限。理由:
    // ① 首次 fetch(唯一 RELOAD 暴露时刻)请求的就是起始档(seedBps 落点,最高 ≤ startHeight;
    //    起始画质=自动时最低档)——绑它,会话身份与首请求逐 itag 一致,无 alpha.77 式错位。
    // ② 起始画质枚举最高 720P(YoutubeStartQuality),恒在 alpha.14 实测安全区(≤1080p 会话可播;
    //    2160p 会话首 fetch 即 RELOAD,irrSuCb3BhI 00:56/23:58 两晚实锤)。
    // ③ videoFormatId 仅是请求兜底(请求体 preferredVideoFormatIds 按 itag 查 videoFormats 全表,
    //    alpha.29),ABR 爬档请求更高档走全表,不依赖绑定档=上限。
    // ④ 若对齐后 4K 视频仍 RELOAD,则证实 alpha.83 结论(「RELOAD 与 itag 选择无关,根因是
    //    NewPipe visionOS 未 attested 的 ustreamerConfig」——sabrUrl/ustreamerConfig 均为 player
    //    响应级、不随绑定档变),届时走 DASH-first 方案另修(P11-98)。
    // 默认画质上限仅保留在两处:ABR 爬档天花板 + 用户显式设了上限但起始画质=自动时的绑定兜底。
    val alignHeight = youtubeStartQuality.startHeight
    val defaultMaxHeight = youtubeDefaultQuality.maxHeight
    val defaultItag = when {
      alignHeight != null ->
        videoFormats.filter { it.height in 1..alignHeight }.maxByOrNull { it.height }?.itag
          ?: videoFormats.minByOrNull { it.height }?.itag // 全部超过上限 → 取最低档
      defaultMaxHeight != null ->
        videoFormats.filter { it.height in 1..defaultMaxHeight }.maxByOrNull { it.height }?.itag
          ?: videoFormats.minByOrNull { it.height }?.itag
      else -> videoFormats.minByOrNull { it.height }?.itag // 双自动:自动选轨从最低档起 → 绑最低档同起点
    }
    val firstVideo = defaultItag?.let { target ->
      videoStreams.firstOrNull { it.toSabrFormatId().itag == target }
    } ?: videoStreams.firstOrNull { it.height > 0 } ?: videoStreams.firstOrNull()
    Log.i(Tag, "NewPipe SABR harvest: videoFormats=${videoFormats.size} startHeight=$alignHeight defaultQualityMax=${youtubeDefaultQuality.maxHeight} defaultItag=$defaultItag firstVideo=itag${firstVideo?.itag}(${firstVideo?.height}p)")
    // alpha.83 诊断:dump 每个视频流 itag 的 codec。真机日志坐实 itag248=vp9(720p webm 视频),
    // **不是** opus 音频——此前「itag248=opus 误分类成视频轨」的记录是误读(已推翻,见 docs/youtube-hd-playback.md
    // 最新结论)。RELOAD 根因是 NewPipe visionOS 拿到未 attested 的 ustreamerConfig,与 itag 选择无关,
    // 故「按 codec 过滤音频 itag」前提本就不成立,过滤逻辑不再需要。
    Log.i(Tag, "NewPipe videoFormats dump: " + videoStreams
      .filter { it.height > 0 }
      .joinToString { "${it.itag}=${it.codec}" })
    // 优先原声轨(getAudioTrackType()==ORIGINAL,来自 xtags acont=original),跳过配音/翻译轨。
    // 多语言配音视频里同一 itag 会按语言重复出现,盲取第一条可能拿到配音轨。
    // P11-119c:先认用户点选的轨(preferredAudioTrackId)——与 WEB-SABR 路径同语义,否则切轨无效。
    val preferredAudio = preferredAudioTrackId?.let { id -> audioStreams.firstOrNull { it.getAudioTrackId() == id } }
    if (preferredAudioTrackId != null) {
      Log.i(
        Tag,
        if (preferredAudio != null) "NewPipe SABR audio switch: track=$preferredAudioTrackId → audio=itag${preferredAudio.itag}"
        else "NewPipe SABR audio switch: track=$preferredAudioTrackId 未命中 → 回落原声轨(可选=" +
          audioStreams.map { it.getAudioTrackId() ?: "null" }.distinct().joinToString(",") + ")",
      )
    }
    val firstAudio = preferredAudio
      ?: audioStreams.firstOrNull { it.audioTrackType == AudioTrackType.ORIGINAL }
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
    // P11-119c:与 WEB-SABR 路径同款音轨列表日志——此前这条主链只打会话里的单条 audio=itag,
    // 真机切轨失败时看不出「可选 id 有哪些/是否命中」,排查只能读代码。
    val distinctAudioTracks = sabrAudioTracks.distinctBy { it.id }
    if (distinctAudioTracks.size > 1) {
      Log.i(
        Tag,
        "NewPipe SABR audioTracks(${distinctAudioTracks.size}): " +
          distinctAudioTracks.joinToString { "${it.id}/${it.displayName ?: it.languageCode ?: "?"}${if (it.isDefault) "*orig" else ""}@itag${it.formatId.itag}" },
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
    // 字幕(WebVTT URL,不走 SABR 服务端):NewPipe SubtitleInfo 直接给可拉取的 WebVTT URL。
    // mimeType 固定 text/vtt。无字幕时为空列表。id 用索引(非 itag),供字幕轨去重/切换。
    //
    // 2026-08-31(P11-73):字幕曾整块下线——旧实现把每条 WebVTT 做成普通 ProgressiveMediaSource 并进
    // MergingMediaSource,媒体3 要等全部 child prepare 才开播,而 SubtitleExtractor 要读完整个文件才声明轨,
    // timedtext 被掐(响应头 81s 不回)时字幕 period 拖死主源整页转圈(00:25 真机)。
    // 2026-09-17(P11-120)回归:播放端改**懒加载**(prepare 期零读取 + 未选中不发请求 + 独立短超时 +
    // 失败即弃),见 [com.kirin.mt.core.player.SubtitleTracks];复测确认 timedtext 真实签名 URL 直连可用
    // (200/0.6~1.8s/真 WEBVTT,无需 poToken),P11-72 当时属偶发黑洞而非结构性封禁。
    // displayName/isAutoGenerated 供播放器字幕面板显示与「人工 vs 自动生成」同语言重轨区分。
    // P11-119d:构造逻辑抽到 [newPipeSubtitleTracks],与 DASH 兜底路共用(fmt=vtt 改写等细节只有一份)。
    val subtitleTracks = newPipeSubtitleTracks(info.subtitles)
    Log.i(
      Tag,
      "NewPipe SABR session: sabrUrl=${sabrUrl.take(80)}... poToken=${poTokenB64.length}B" +
        "(aligned-LibreTube-no-poToken) ustreamerCfg=${ustreamerCfgB64.length}B " +
        "cpn=${visionOsCpn ?: "random"} video=itag${vFmt.itag}(${vFmt.height}p) audio=itag${aFmt.itag} " +
        "videoFormats=${videoFormats.size} subtitles=${subtitleTracks.size}(fmt=vtt 已改写) dur=${durationMs}ms"
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
    /** P11-119c:同 [buildSabrSessionFromNewPipe]——消费用户点选的音轨 id。 */
    preferredAudioTrackId: String? = null,
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
    // P11-119c:先认用户点选的轨(preferredAudioTrackId),否则切轨无效。
    val preferredAudio = preferredAudioTrackId?.let { id -> audioStreams.firstOrNull { it.audioTrackId == id } }
    if (preferredAudioTrackId != null) {
      Log.i(
        Tag,
        if (preferredAudio != null) "Piped SABR audio switch: track=$preferredAudioTrackId → audio=itag${preferredAudio.itag}"
        else "Piped SABR audio switch: track=$preferredAudioTrackId 未命中 → 回落原声轨",
      )
    }
    val firstAudio = preferredAudio
      ?: audioStreams.firstOrNull { it.audioTrackType == "ORIGINAL" }
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
    val firstAudio = audioRaws.firstOrNull { isOriginalAudioRaw(it) } ?: audioRaws.firstOrNull()
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
  /**
   * P11-101 Phase 1 判别探针(仅诊断,不改播放行为)——门控视频(Fhyu9sqcF-o/irrSuCb3BhI 类,
   * SABR RELOAD + ANDROID 直链 403)验证「attested WEB /player → dashManifestUrl → WebView
   * decipher → +pot」链路(FreeTube 2026 生产架构同款,`decipherManifestUrl`)。四判据:
   * ① WEB /player 带自铸 poToken 是否 OK(非 LOGIN_REQUIRED);② dashManifestUrl 在否/带什么
   * 参数(s/sig/n);③ 现有 n/s 解密机制对该 URL 是否产出(URL 类闭包导出 + 结构正则——plasma
   * 时代可能 stale,verdict 就是取证);④ decipher 后 +pot 的 URL OkHttp GET 是否 200。
   */
  private suspend fun probeWebDashChain(videoId: String, poToken: String?, signatureTimestamp: Int?) {
    // ① attested WEB /player
    val player = runCatching {
      postPlayer(videoId, InnerTubeClient.Client.WEB, poToken, signatureTimestamp)
    }.getOrNull()
    if (player == null) {
      Log.w(Tag, "P11-101 probe ①: WEB /player request threw → 判据①否")
      return
    }
    val status = player.obj("playabilityStatus")?.stringOrNull("status")
    val streamingData = player.obj("streamingData")
    Log.i(
      Tag,
      "P11-101 probe ①: playability=$status poTokenArg=${poToken?.length ?: 0}B " +
        "streamingDataKeys=${streamingData?.keys?.toList() ?: "ABSENT"}",
    )
    // ② dashManifestUrl 在否/参数键
    val dashUrl = streamingData?.stringOrNull("dashManifestUrl")
    if (dashUrl.isNullOrBlank()) {
      // P11-101 Phase 2 修订:真机 r1916 实测 WEB /player 带token → dashManifestUrl ABSENT 且
      // adaptive=34 全无 url/cipher(SABR-only 门控签名),但 serverAbrStreamingUrl 在。
      // → 转测 WEB SABR 变体(= alpha.85b 机制 + 现在的 decipher):③' sabrUrl n-param + n-decrypt;
      // ④' 构 WEB SABR 会话 + POST init,verdict(MEDIA ok vs RELOAD vs 403)。
      val sabrData = parseSabrData(player)
      if (sabrData == null) {
        val combined = streamingData?.array("formats").orEmpty().mapNotNull { it as? JsonObject }
        val firstCombinedUrl = combined.firstOrNull()?.stringOrNull("url")
        Log.w(
          Tag,
          "P11-101 probe ③': parseSabrData ABSENT(无 sabrUrl/ustreamerCfg) " +
            "combined=${combined.size} firstCombinedUrl=${if (firstCombinedUrl.isNullOrBlank()) "ABSENT" else "present(${firstCombinedUrl.length}B)"}",
        )
        return
      }
      val sabrN = sabrData.sabrUrl.let { Uri.parse(it).getQueryParameter("n") }
      Log.i(
        Tag,
        "P11-101 probe ③': WEB-SABR sabrUrl=${sabrData.sabrUrl.length}B n-param=${if (sabrN.isNullOrBlank()) "ABSENT(n-free)" else "present(${sabrN.length}B)"} " +
          "ustreamerCfg=${sabrData.ustreamerCfgB64.length}B raws=${sabrData.raws.size}",
      )
      // combined/progressive 保底判据(itag18 不受 PO token 强制,yt-dlp #12363)
      val playerStreaming = player.obj("streamingData")
      val combinedFormats = playerStreaming?.array("formats").orEmpty().mapNotNull { it as? JsonObject }
      val firstCombinedUrl = combinedFormats.firstOrNull()?.stringOrNull("url")
      Log.i(
        Tag,
        "P11-101 probe ③': combined=${combinedFormats.size} " +
          "firstCombinedUrl=${if (firstCombinedUrl.isNullOrBlank()) "ABSENT" else "present(${firstCombinedUrl.length}B)"}",
      )
      // n-decrypt 尝试:① yt-dlp solver(P11-101,AST 结构匹配 + URL 类 transform,主选);
      // ② 旧 URL 类 config 法(NDecryptor,alpha.32 证伪,verdict 对照留取证)。
      val playerJsUrl2 = resolvePlayerJsUrl(videoId)
      var sabrUrlT = sabrData.sabrUrl
      if (!sabrN.isNullOrBlank() && playerJsUrl2 != null) {
        val solved = runCatching { solverDecipherer.solve(playerJsUrl2, listOf(sabrN), emptyList()) }.getOrNull()
        val solverN = solved?.let { solverDecipherer.transformedN(solved, sabrN) }
        Log.i(
          Tag,
          "P11-101 probe ③': solver n=${if (solverN != null && solverN != sabrN) "transformed($sabrN → $solverN)" else if (solverN == null) "FAILED" else "unchanged"}",
        )
        if (solverN != null && solverN != sabrN) {
          val withQ = sabrData.sabrUrl.replaceFirst("?n=${Uri.encode(sabrN)}", "?n=${Uri.encode(solverN)}")
          sabrUrlT = if (withQ != sabrData.sabrUrl) withQ
          else sabrData.sabrUrl.replaceFirst("&n=${Uri.encode(sabrN)}", "&n=${Uri.encode(solverN)}")
        } else {
          // solver 未产出 → 旧法对照(取证)
          sabrUrlT = nDecryptor.decrypt(sabrData.sabrUrl, playerJsUrl2)
        }
        val nAfter = Uri.parse(sabrUrlT).getQueryParameter("n")
        Log.i(
          Tag,
          "P11-101 probe ③': final n-decrypt=${if (sabrUrlT != sabrData.sabrUrl && nAfter != sabrN) "transformed" else "unchanged/failed"} (n $sabrN → $nAfter)",
        )
      }
      // ④' 全链终极判据:WEB SABR 会话 + init POST(对齐 buildSabrSessionFromReloadPlayer WEB 分支)
      val webPo = poToken
      if (webPo != null && playerJsUrl2 != null) {
        val raws = sabrData.raws
        val videoRaws = raws.filter { (it.intOrNull("height") ?: 0) > 0 }
        val audioRaws = raws.filter { (it.stringOrNull("mimeType") ?: "").startsWith("audio/") }
        val firstVideo = videoRaws.firstOrNull()
        val firstAudio = audioRaws.firstOrNull { isOriginalAudioRaw(it) } ?: audioRaws.firstOrNull()
        if (firstVideo != null && firstAudio != null) {
          val videoFormats = videoRaws.map { rawToSabrFormatId(it, it.intOrNull("height") ?: 0) }
          val session = SabrSession.fromSabrData(
            sabrUrlT, webPo, sabrData.ustreamerCfgB64,
            innerTubeClient.sabrClientInfo(),
            rawToSabrFormatId(firstAudio, 0), rawToSabrFormatId(firstVideo, firstVideo.intOrNull("height") ?: 0),
            userAgent = InnerTubeClient.Client.WEB.userAgent,
            cookieHeader = "", visitorData = "",
            cpn = queryParam(sabrData.sabrUrl, "cpn").orEmpty(),
            videoFormats = videoFormats,
          )
          val result = runCatching {
            SabrClient(httpClient).fetch(session, SabrFetchRequest(isInit = true, streamType = SabrStreamType.VIDEO, videoItag = session.videoFormatId.itag))
          }.getOrNull()
          when (result) {
            is SabrFetchResult.Success ->
              Log.i(Tag, "P11-101 probe ④': SABR init POST → MEDIA ok bytes=${result.data.size} mediaHeader=${result.mediaHeader != null} (全链通 → Phase 2 go)")
            is SabrFetchResult.Redirect ->
              Log.i(Tag, "P11-101 probe ④': SABR init POST → Redirect(newUrl=${result.newSabrUrl.length}B)")
            is SabrFetchResult.Backoff ->
              Log.i(Tag, "P11-101 probe ④': SABR init POST → Backoff(${result.ms}ms,会话被接受但缓发)")
            is SabrFetchResult.ReloadPlayer ->
              Log.w(Tag, "P11-101 probe ④': SABR init POST → RELOAD again(服务端拒会话) dump=${result.dump.take(80)}")
            SabrFetchResult.InvalidPoToken ->
              Log.w(Tag, "P11-101 probe ④': SABR init POST → InvalidPoToken(token 被拒)")
            is SabrFetchResult.Error ->
              Log.w(Tag, "P11-101 probe ④': SABR init POST → Error ${result.message.take(120)}")
            null -> Log.w(Tag, "P11-101 probe ④': SABR init POST threw(见上方异常日志)")
          }
        } else {
          Log.w(Tag, "P11-101 probe ④': raws 无可选轨(video=${firstVideo != null} audio=${firstAudio != null})")
        }
      } else {
        Log.w(Tag, "P11-101 probe ④': 跳过(poToken=${webPo != null} playerJsUrl=${playerJsUrl2 != null})")
      }
      return
    }
    val dashUri = Uri.parse(dashUrl)
    Log.i(
      Tag,
      "P11-101 probe ②: dashManifestUrl=${dashUrl.length}B host=${dashUri.host} " +
        "keys=${dashUri.queryParameterNames} s=${dashUri.getQueryParameter("s")?.length ?: 0}B " +
        "n=${dashUri.getQueryParameter("n")?.length ?: 0}B sig=${if (dashUri.getQueryParameter("sig") != null) "yes" else "no"}",
    )
    // ③ 现有 n/s 解密机制尝试(机制在、正则可能 stale——verdict 即结论)
    val playerJsUrl = resolvePlayerJsUrl(videoId)
    Log.i(Tag, "P11-101 probe ③: playerJsUrl=${playerJsUrl?.take(90) ?: "ABSENT"}")
    var current = dashUrl
    val sParam = dashUri.getQueryParameter("s")
    if (!sParam.isNullOrBlank() && playerJsUrl != null) {
      val spParam = dashUri.getQueryParameter("sp") ?: "signature"
      val decipheredS = runCatching { sDecryptor.decrypt(sParam, playerJsUrl) }.getOrNull()
      Log.i(Tag, "P11-101 probe ③: s-decrypt=${if (decipheredS.isNullOrBlank()) "FAILED" else "ok(${sParam.length}->${decipheredS.length})"}")
      if (!decipheredS.isNullOrBlank()) {
        current = current.replaceFirst("?s=${Uri.encode(sParam)}", "?$spParam=${Uri.encode(decipheredS)}")
      }
    }
    val nBefore = Uri.parse(current).getQueryParameter("n")
    if (playerJsUrl != null) {
      current = nDecryptor.decrypt(current, playerJsUrl)
    }
    val nAfter = Uri.parse(current).getQueryParameter("n")
    Log.i(
      Tag,
      "P11-101 probe ③: n-decrypt=${if (current != dashUrl && nAfter != nBefore) "transformed" else "unchanged/failed"} " +
        "(n $nBefore → $nAfter, urlLen=${current.length})",
    )
    // ④ 全链终极判据:decipher 后 +pot 的 manifest URL OkHttp GET 状态
    if (current != dashUrl) {
      val potUrl = current.let { u ->
        val sep = if (u.contains('?')) "&" else "?"
        "$u${sep}pot=${poToken ?: ""}"
      }
      val httpStatus = runCatching {
        val req = Request.Builder().url(potUrl).header("Range", "bytes=0-1023").build()
        httpClient.newCall(req).execute().use { it.code }
      }.getOrDefault(-1)
      Log.i(Tag, "P11-101 probe ④: manifest GET(+pot) → HTTP $httpStatus (200=全链通 → Phase 2 go)")
    } else {
      Log.w(Tag, "P11-101 probe ④: decipher 无产出 → 判据③④否")
    }
  }

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
    // P11-119d:字幕——DASH 兜底同样有 NewPipe `info.subtitles`(与 SABR 会话路径同源),此前这条路的
    // PlaybackInfo 没带 subtitleTracks ⇒ 「DASH 优先」档或 SABR 失败落下兜底时**没有字幕入口**
    // (P11-120 只接了 SABR 会话路径)。构造复用 [newPipeSubtitleTracks](fmt=vtt 改写等只有一份)。
    val subtitleTracks = newPipeSubtitleTracks(info.subtitles)
    if (subtitleTracks.isNotEmpty()) {
      Log.i(
        Tag,
        "兜底 subtitleTracks(${subtitleTracks.size}): " +
          subtitleTracks.joinToString { "${it.languageCode ?: "?"}/${it.displayName ?: "?"}${if (it.isAutoGenerated) "*asr" else ""}" },
      )
    }
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
    // alpha.9X(P11-99b 直链 403 诊断):只打 query 参数**键**与关键参数在否(pot=attestation 凭证,
    // n=n-decrypt 结果),不打完整 URL 值。判别「未 attested(pot 缺)」vs「n-decrypt 失效(n 原样)」。
    videoCandidates.firstOrNull()?.content?.let { u ->
      Log.i(
        Tag,
        "直链参数键 diag: keys=${Uri.parse(u).queryParameterNames} host=${Uri.parse(u).host}",
      )
    }
    val dashFallbackFailed = SabrStreamRegistry.isDashFallbackFailed(videoId)
    if (dashFallbackFailed) {
      Log.w(Tag, "自合成DASH: 直链 403 已判死(videoId=$videoId)→ 跳过自合成 DASH,直落 dashMpdUrl/HLS")
    }
    val synthAudio = audioCandidates.firstOrNull { it.audioTrackType == AudioTrackType.ORIGINAL }
      ?: audioCandidates.firstOrNull { it.audioTrackType != AudioTrackType.DUBBED }
      ?: audioCandidates.firstOrNull()
    if (!dashFallbackFailed && videoCandidates.isNotEmpty() && synthAudio != null) {
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
        subtitleTracks = subtitleTracks,
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
        subtitleTracks = subtitleTracks,
      )
    }
    // alpha.90:dashMpdUrl 空(android 无 manifest)→ 落 visionOS hlsUrl(Apple 平台原生 HLS 交付)。
    // P11-101 Step 0 停用:YouTube HLS 媒体段同 GVS attestation 网关门控(manifest/playlist 200 但
    // 段 403,09-15 真机),且 media3 1.10 HlsChunkSource.createFallbackOptions 在段加载错误处理
    // 路径有 redundantGroups/trackSelection 失配 → ArrayIndexOutOfBoundsException FATAL 闪退
    // (09-15 00:26 XQ-EC72 真机实锤)。撤掉 HLS 兜底,门控视频到此为止落 Failed(清晰报错),
    // 第三级兜底由 Phase 1/2 的 WEB-DASH 接替。
    Log.w(Tag, "HLS 兜底已停用(P11-101 Step 0,media3 createFallbackOptions 崩溃+媒体段门控 403)→ 返回 null")
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

  /**
   * P11-119:该音频 raw 是否为**原声轨**。
   * 先认字面量(NewPipe 侧历史上出现过明文 xtags),再解 base64(proto)——`/player` 的 xtags 是
   * base64,里面只有编码后的 "acont"/"original" 字节,**字面量子串恒不命中**;旧写法因此永远落到
   * audioRaws 第一条(r1962 真机:双音轨视频恒播英语配音轨)。
   */
  private fun isOriginalAudioRaw(raw: JsonObject): Boolean {
    val xtags = raw.stringOrNull("xtags") ?: return false
    if (xtags.contains("acont=original")) return true
    return SabrProto.parseFormatXtags(xtags)["acont"]?.equals("original", ignoreCase = true) == true
  }

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
    // 2026-08-30 声明口径修正:`bitrate` 是 VBR 峰值(比真平均高 ~60-75%),`averageBitrate`
    // (contentLength/approxDurationMs 自算,extractor 已解析进 ItagItem)才是真实平均消耗。
    // 任一未知(直播/老响应)→ 0,buildSabrTrack 回落 peak,行为不劣于旧口径。
    stream.itagItem?.let { item ->
      val clen = item.contentLength
      val durMs = item.approxDurationMs
      put("averageBitrate", if (clen > 0 && durMs > 0) (clen * 8 / durMs).toInt() else 0)
    } ?: put("averageBitrate", 0)
    put("fps", stream.fps)
  }

  /** 把 NewPipe 音频流包装成 /player adaptive 风格的 JsonObject。 */
  private fun newPipeAudioRaw(stream: AudioStream): JsonObject = buildJsonObject {
    put("itag", stream.itag.toLong())
    put("mimeType", stream.format?.mimeType ?: "")
    put("codec", stream.codec ?: "")
    put("bitrate", stream.bitrate)
    // 声明口径修正:同 newPipeVideoRaw(音频 contentLength/approxDurationMs 更精确)。
    stream.itagItem?.let { item ->
      val clen = item.contentLength
      val durMs = item.approxDurationMs
      put("averageBitrate", if (clen > 0 && durMs > 0) (clen * 8 / durMs).toInt() else 0)
    } ?: put("averageBitrate", 0)
  }

  /** 把 Piped 视频流包装成与 [newPipeVideoRaw] 同形的 JsonObject(供 [buildSabrTrack]/[buildSabrPlaybackInfo] 复用)。 */
  private fun pipedVideoRaw(stream: PipedStream): JsonObject = buildJsonObject {
    put("itag", (stream.itag ?: 0).toLong())
    put("height", stream.height ?: 0)
    put("width", stream.width ?: 0)
    put("mimeType", stream.mimeType ?: "")
    put("codec", stream.codec ?: "")
    put("bitrate", stream.bitrate ?: 0)
    // 声明口径修正:Piped API 无 average 字段 → 0,回落 peak(保持旧行为)。
    put("averageBitrate", 0)
    put("fps", stream.fps ?: 0)
  }

  /** 把 Piped 音频流包装成与 [newPipeAudioRaw] 同形的 JsonObject。 */
  private fun pipedAudioRaw(stream: PipedStream): JsonObject = buildJsonObject {
    put("itag", (stream.itag ?: 0).toLong())
    put("mimeType", stream.mimeType ?: "")
    put("codec", stream.codec ?: "")
    put("bitrate", stream.bitrate ?: 0)
    // 声明口径修正:Piped API 无 average 字段 → 0,回落 peak(保持旧行为)。
    put("averageBitrate", 0)
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
  /**
   * P11-101 Phase 2c(生产兜底):WEB SABR 会话——attested WEB /player(自铸 poToken)→
   * parseSabrData → yt-dlp solver n-decrypt → SabrSession(WEB ClientInfo + WEB poToken)
   * → registerByVideoId(status=2 刷新回调)。08:15 r1921 探针全链通(transformed → init POST
   * MEDIA ok)。返回 (PlaybackInfo, sid);任一步失败返回 null(上层落 DASH 兜底)。
   */
  /**
   * P11-118d:harvest 材料——浏览器亲手产出的 SABR 会话三元组(sabrUrl + poToken/ustreamerConfig + 原 cpn)。
   * 服务端只认这份材料(20:57 replay 实证 status=1),我们自造的 /player 材料恒被 nag。
   */
  private class HarvestMaterial(
    val baseSabrUrl: String,
    val cpn: String,
    val poTokenBytes: ByteArray,
    val ustreamerConfigBytes: ByteArray,
    /** body 里解出的选定档;解不出(YouTube proto 演进)时为 null → 用我们 /player 阶梯的默认档。 */
    val audioFormatId: SabrProto.FormatIdLite?,
    val videoFormatId: SabrProto.FormatIdLite?,
    /**
     * 2026-09-20(A2 形状对齐):材料 body 里 client_abr_state 的原始字节,透传进会话 →
     * 请求时作为本请求 f1 之前的一份发出(protobuf 合并 ⇒ 我们的实时值覆盖标量、材料独有的
     * ~11 个未建模字段保留)。
     */
    val clientAbrStateRaw: ByteArray?,
  )

  /**
   * 跑一次 harvest 并把捕获的 body 解成会话材料。任何一步失败返回 null(调用方回退自造材料)。
   * **时间窗去重(45s)**:窗口内不重复采集(防风控 + 防 auto-retry 立刻重打);窗口外允许重新采集——
   * 会话被看门狗/错误重载后必须能拿到新材料,否则只能退回已知会死的自造材料。
   * 拿到 POST 但解不出材料时顺带跑 [replayHarvestCapture] 留证据(那正是 20:57 判出 status=1 的手段)。
   */
  private suspend fun harvestSessionMaterial(videoId: String, startMs: Long, deadlineMs: Long = 0L): HarvestMaterial? {
    val harvester = sabrHarvester ?: return null
    val now = System.currentTimeMillis()
    harvestProbed[videoId]?.let { last ->
      if (now - last < HARVEST_RETRY_WINDOW_MS) {
        Log.i(Tag, "P11-118 harvest: $videoId 刚采集过(${now - last}ms 前,窗口 ${HARVEST_RETRY_WINDOW_MS}ms)→ 用自造材料")
        return null
      }
      Log.i(Tag, "P11-118 harvest: $videoId 上次采集已 ${now - last}ms(超窗口)→ 重新采集")
    }
    harvestProbed[videoId] = now
    val t0 = System.currentTimeMillis()
    // P11-126:把 harvest 的两层超时收敛进剩余预算——原来 40s+30s 是硬编码,与外层起播预算完全
    // 互不感知(真机 09-19:harvest 冷启烧到一半外层 30s 到期,整条 launch 被取消)。
    // 每次尝试都留出 [FallbackReserveMs] 给 NewPipe 兜底落地(真机实测兜底约 6s),不够 [MinHarvestAttemptMs]
    // 就干脆不发——发一次注定被砍的 harvest 只会白烧 WebView/solver。
    fun harvestBudgetMs(hardCapMs: Long): Long {
      if (deadlineMs <= 0L) return hardCapMs
      val remaining = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
      return minOf(hardCapMs, (remaining - FallbackReserveMs).coerceAtLeast(0L))
    }

    val firstBudget = harvestBudgetMs(HarvestColdCapMs)
    if (firstBudget < MinHarvestAttemptMs) {
      Log.w(Tag, "P11-118 harvest: 剩余预算只够 ${firstBudget}ms(< ${MinHarvestAttemptMs}ms)→ 放弃采集,直接自造材料(让兜底有时间落地)")
      harvestProbed.remove(videoId)
      return null
    }
    var cap = runCatching { harvester.harvest(videoId, startMs = startMs, timeoutMs = firstBudget) }.getOrNull()
    if (cap == null) {
      // P11-118d:首次(冷)harvest 要把 WebView 从零建起来 + 加载真实首页建立上下文,常常吃不进
      // 窗口(r1956 真机:第一次 `NO CAPTURE after 40009ms`,而紧接着的重试只花 1932ms)。这里就地补一次
      // 重试而不是让上层 auto-retry 兜——省掉一整轮播放失败。
      Log.w(Tag, "P11-118 harvest: cold attempt 无捕获(${System.currentTimeMillis() - t0}ms)→ 立即重试一次(WebView 已热)")
      val retryBudget = harvestBudgetMs(HarvestWarmCapMs)
      if (retryBudget < MinHarvestAttemptMs) {
        Log.w(Tag, "P11-118 harvest: 重试预算只够 ${retryBudget}ms(< ${MinHarvestAttemptMs}ms)→ 不重试")
      } else {
        cap = runCatching { harvester.harvest(videoId, startMs = startMs, timeoutMs = retryBudget) }.getOrNull()
      }
    }
    val ms = System.currentTimeMillis() - t0
    if (cap == null) {
      Log.w(Tag, "P11-118 harvest: NO CAPTURE after ${ms}ms(风控空白页/超时)→ 回退自造材料")
      harvestProbed.remove(videoId)
      return null
    }
    if (!cap.method.equals("POST", ignoreCase = true)) {
      Log.w(Tag, "P11-118 harvest: only ${cap.method} status=${cap.status} (no SABR POST) after ${ms}ms → 回退自造材料,允许重探")
      harvestProbed.remove(videoId)
      return null
    }
    Log.i(Tag, "P11-118 harvest: captured SABR POST status=${cap.status} bodyB64=${cap.bodyB64.length}B elapsed=${ms}ms")
    val body = runCatching { Base64.decode(cap.bodyB64, Base64.DEFAULT) }.getOrNull()?.takeIf { it.isNotEmpty() }
    // 2026-09-20(与 WEBREQDUMP **对称**的取证补齐):把**浏览器那份** body 也分片 dump 出来。
    // 为什么必须补:此前日志里只有 `bodyB64=<长度>`,**内容不在日志里** —— 于是「我们的 body vs 浏览器的
    // body 逐字段对比」结构上缺半边,拿日志什么都比不了(tmp/webreq_diff.py 按 base64 解必失败,实测)。
    // 分片规则同 WEBREQDUMP(logcat 单行上限),标记独立用 HARVBODY 便于 grep 与拼接。
    body?.let { b ->
      val hex = b.joinToString("") { "%02x".format(it) }
      val chunk = 1800
      val parts = (hex.length + chunk - 1) / chunk
      for (i in 0 until parts) {
        val seg = hex.substring(i * chunk, minOf((i + 1) * chunk, hex.length))
        Log.i(Tag, "HARVBODY part=${i + 1}/$parts bodyHex=$seg")
      }
    }
    val decoded = body?.let { runCatching { SabrProto.decodeVideoPlaybackAbrRequest(it) }.getOrNull() }
    val cpn = queryParam(cap.url, "cpn")
    if (decoded == null || cpn == null) {
      Log.w(
        Tag,
        "P11-118 harvest: decode/cpn 失败(body=${body?.size ?: 0}B cpn=$cpn) → 回退自造材料 + replay 取证; " +
          "body fields = ${body?.let { SabrProto.fieldHistogram(it) } ?: "N/A"}",
      )
      replayHarvestCapture(cap)
      return null
    }
    Log.i(
      Tag,
      "P11-118 harvest decoded: poToken=${decoded.poToken.size}B ustreamerCfg=${decoded.ustreamerConfig.size}B " +
        "audio=${decoded.audioFormatId} video=${decoded.videoFormatId} " +
        "bodyFields=${body?.let { SabrProto.fieldHistogram(it) } ?: "N/A"}",
    )
    // P11-118d:材料判据只看**会话三元组**(poToken + ustreamerConfig + 浏览器 cpn)——
    // formatId 解不出(YouTube proto 把 16/17 搬走了)不阻断会话:poToken 不绑 itag(P11-29),
    // 选定档用我们 /player 阶梯的默认档即可。真正不可替代的是那三个(服务端只认它们)。
    if (decoded.poToken.isEmpty() || decoded.ustreamerConfig.isEmpty()) {
      Log.w(Tag, "P11-118 harvest: poToken/ustreamerCfg 空 → 回退自造材料 + replay 取证")
      replayHarvestCapture(cap)
      return null
    }
    // 剥 alr/cpn/rn → fromSabrData 再加 alr=yes+cpn;cver 等浏览器参数保留(对齐 alpha.25/26 replay)。
    val base = cap.url.split("&")
      .filterNot { it.startsWith("alr=") || it.startsWith("cpn=") || it.startsWith("rn=") }
      .joinToString("&").let { if (it.startsWith("http")) it else "&$it" }
    Log.i(
      Tag,
      "P11-118 harvest material: poToken=${decoded.poToken.size}B ustreamerCfg=${decoded.ustreamerConfig.size}B " +
        "cpn=$cpn audio=${decoded.audioFormatId?.itag ?: "ladder-default"} video=${decoded.videoFormatId?.itag ?: "ladder-default"} " +
        "urlHasCver=${base.contains("cver=")}",
    )
    return HarvestMaterial(
      base, cpn, decoded.poToken, decoded.ustreamerConfig,
      decoded.audioFormatId, decoded.videoFormatId, decoded.clientAbrStateRaw,
    )
      // 2026-09-20(补 P11-118c 判别实验):材料**解得出**时也把这份原始捕获存下来。此前 `cap` 只在
      // 「解不出材料」的三个失败分支里被 replay 取证,成功那份直接丢掉 —— 于是「会话建起来了、却在
      // 运行时被判死」这种形态(真机 15:09-15:18)手里没有任何可比对的材料。见 [WebReplayOnce]。
      .also { lastHarvestCapture[videoId] = cap }
  }

  /**
   * P11-118c(阶段 2 决定性实验):把 harvest 捕获的浏览器请求**原样重放**。
   *
   * 为什么这是决定性的:材料(URL + body + 浏览器原 cpn)是**浏览器亲手产生、服务端已回 200** 的。
   * - 若我们的传输重放后拿到 `status=1` + MEDIA ⇒ 材料可用、传输无碍 ⇒ 后续接播放栈即可跑通;
   * - 若仍 `status=2` ⇒ 差异只在「请求从哪发出」(浏览器会话 vs OkHttp) ⇒ 原生路线到此为止。
   *
   * A/B 两发,对照同一份材料的两种传输形态:
   * ① **浏览器忠实形态**——FreeTube 的 SABR POST 只有 3 个头(content-type/accept-encoding/accept),
   *    无 Cookie、无 X-Goog-Visitor-Id(P11-107 已实证);
   * ② **我们原生路径形态**——补 Cookie + X-Goog-Visitor-Id + Origin/Referer。
   *
   * 重放规则沿 alpha.25 的实测结论:**保留浏览器 cpn、只剥 rn**(reset 为 0)——alpha.24 剥 cpn 时
   * 只回 105B `SABR_CONTEXT_UPDATE` 无媒体;保 cpn 后拿到完整媒体段。
   */
  private suspend fun replayHarvestCapture(capture: YoutubeSabrHarvester.SabrCapture) {
    val body = runCatching { Base64.decode(capture.bodyB64, Base64.DEFAULT) }.getOrNull()
    if (body == null || body.isEmpty()) {
      Log.w(Tag, "P11-118 harvest replay: body decode failed/empty (b64=${capture.bodyB64.length})")
      return
    }
    val stripped = capture.url.split("&").filterNot { it.startsWith("rn=") }.joinToString("&")
      .let { if (it.startsWith("http")) it else "&$it" }
    val replayUrl = "$stripped&rn=0"
    Log.i(
      Tag,
      "P11-118 harvest replay: start body=${body.size}B urlHasCpn=${replayUrl.contains("cpn=")} " +
        "urlHasCver=${replayUrl.contains("cver=")} url=${replayUrl.take(170)}",
    )
    suspend fun fire(label: String, withIdentityHeaders: Boolean) = withContext(Dispatchers.IO) {
      val rb = Request.Builder()
        .url(replayUrl)
        .post(body.toRequestBody("application/x-protobuf".toMediaType()))
        .header("accept-encoding", "identity")
        .header("accept", "application/vnd.yt-ump")
        .header("content-type", "application/x-protobuf")
        // P11-127:与最终传输形态一致(移动 UA)——桌面腿那轮 A/B replay 的结论已判读完
        // (P11-118c:材料可用/传输无碍),此处只是让取证与线上形态同源。
        .header("User-Agent", InnerTubeClient.Client.WEB.userAgent)
      if (withIdentityHeaders) {
        rb.header("Cookie", innerTubeClient.currentSessionCookies())
          .header("X-Goog-Visitor-Id", innerTubeClient.currentVisitorData())
          .header("Origin", "https://www.youtube.com")
          .header("Referer", "https://www.youtube.com/")
      }
      runCatching {
        httpClient.newCall(rb.build()).execute().use { r ->
          val ct = r.header("Content-Type")
          val bytes = r.body?.byteStream()?.use { it.readBytes() }
          Log.i(Tag, "P11-118 harvest replay[$label]: HTTP ${r.code} ct=$ct body=${bytes?.size ?: 0}B")
          if (bytes != null && ct?.contains("yt-ump") == true) {
            val ump = UmpReader()
            ump.append(bytes)
            ump.readParts { type, payload ->
              val detail = when (type) {
                SabrProto.PART_SABR_ERROR -> SabrProto.decodeSabrError(payload)?.let { "type=${it.type} code=${it.code}" }
                SabrProto.PART_SABR_REDIRECT -> "url=${SabrProto.decodeSabrRedirect(payload)?.take(100)}"
                SabrProto.PART_STREAM_PROTECTION_STATUS -> "status=${SabrProto.decodeStreamProtectionStatus(payload)}"
                SabrProto.PART_NEXT_REQUEST_POLICY -> SabrProto.decodeNextRequestPolicy(payload)?.let { "backoff=${it.backoffTimeMs}ms cookie=${it.playbackCookie != null}" }
                SabrProto.PART_MEDIA_HEADER -> SabrProto.decodeMediaHeader(payload)?.let { "headerId=${it.headerId} itag=${it.itag} isInit=${it.isInitSeg} seq=${it.sequenceNumber} contentLen=${it.contentLength} dur=${it.durationMs}ms" }
                else -> "payloadLen=${payload.size}"
              }
              Log.i(Tag, "P11-118 harvest replay[$label] UMP: type=$type(${replayPartName(type)}) $detail")
            }
          }
        }
      }.onFailure { Log.w(Tag, "P11-118 harvest replay[$label] failed: ${it.message}") }
    }
    fire("freetube-shape", withIdentityHeaders = false)
    fire("native-shape", withIdentityHeaders = true)
  }

  /** UMP part type → 可读名(诊断用,对齐 SabrClient 内部同名表)。 */
  private fun replayPartName(type: Int): String = when (type) {
    20 -> "MEDIA_HEADER"; 21 -> "MEDIA"; 22 -> "MEDIA_END"; 30 -> "CONFIG"
    35 -> "NEXT_REQUEST_POLICY"; 42 -> "FORMAT_INIT_METADATA"; 43 -> "SABR_REDIRECT"
    44 -> "SABR_ERROR"; 46 -> "RELOAD_PLAYER_RESPONSE"; 47 -> "PLAYBACK_START_POLICY"
    57 -> "SABR_CONTEXT_UPDATE"; 58 -> "STREAM_PROTECTION_STATUS"; 59 -> "SABR_CONTEXT_SENDING_POLICY"
    else -> "?"
  }

  private suspend fun buildWebSabrFallback(
    videoId: String,
    poToken: String?,
    signatureTimestamp: Int?,
    request: PlaybackRequest,
    youtubeDefaultQuality: YoutubeDefaultQuality = YoutubeDefaultQuality.Auto,
    // P11-126:起播给的绝对 deadline(0=不限),透传给 [harvestSessionMaterial] 收敛 harvest 超时。
    deadlineMs: Long = 0L,
  ): Pair<PlaybackInfo, String>? {
    if (poToken == null) {
      Log.w(Tag, "WEB-SABR: no poToken → abort")
      return null
    }
    // ── P11-118d:阶段 2 收尾——harvest 材料优先 ─────────────────────────────────
    // 20:57 真机 replay 实验实证:浏览器产出的材料(sabrUrl + body + 原 cpn)经**我们的 OkHttp**
    // 原样重放得到 `STREAM_PROTECTION_STATUS status=1` + 完整媒体段(761KB;itag251/396,含 init 与
    // seq=1..4),且 A(FreeTube 式 3 头)/ B(补 Cookie+visitor)两种传输形态结果**完全相同**
    // ⇒ 追了 15 轮的 nag 差异**只在材料**,不在身份/传输。故 WEB-SABR 会话优先用 harvest 材料;
    // 抓不到(风控空白页/超时/body 空)才回退我们自造的 /player 材料(见下方 material==null 分支)。
    val material = harvestSessionMaterial(videoId, request.startPositionMs, deadlineMs)
    if (material != null) {
      Log.i(
        Tag,
        "WEB-SABR: USING HARVEST MATERIAL po=${material.poTokenBytes.size}B " +
          "ust=${material.ustreamerConfigBytes.size}B cpn=${material.cpn} " +
          "audio=itag${material.audioFormatId?.itag ?: "ladder-default"} " +
          "video=itag${material.videoFormatId?.itag ?: "ladder-default"}",
      )
    }
    // ── P11-127(全移动):身份不再桌面化 ────────────────────────────────────────────────
    // 此前(P11-106/P11-117)这一段用「桌面 watch 页 ytcfg 的 INNERTUBE_CONTEXT + 该页 cookie +
    // 桌面 UA + forceOkHttp」四件套,动机是 FreeTube 桌面版能播。但真机判读的结论是
    // **「身份生效但 nag 依旧」**(P11-106 Done 注),桌面化从未通过它自己的成功判据;
    // 且这套身份与铸造 VM(Android 指纹)、采集页(UA 桌面 + Client Hints 移动)三处互相矛盾。
    // 现在四处统一为**原生 Android 移动**:`uaOverride=null` 让 `postJson` 落到
    // `Client.WEB.userAgent`(=MobileUserAgent)、`currentVisitorData()`、`currentSessionCookies()`、
    // `buildContext(WEB)`(osName 来自移动 `sw.js_data`=Android);`contextOverride=null` 同理。
    // 保留 `forceOkHttp=true`(单变量:它让 /player 走唯一真带 Cookie/UA 的传输,且不再经 WebView)。
    Log.i(
      Tag,
      "WEB-SABR identity: mobile=true osName=${innerTubeClient.sabrClientInfo().osName ?: "?"} " +
        "visitor=${innerTubeClient.currentVisitorData().take(24)} " +
        "cookie=${innerTubeClient.currentSessionCookies().length}B " +
        "ua=${InnerTubeClient.Client.WEB.userAgent.take(40)} forceOkHttp=true",
    )
    val player = runCatching {
      postPlayer(
        videoId, InnerTubeClient.Client.WEB, poToken, signatureTimestamp,
        // P11-127:四个 override 全撤(桌面身份下线),/player 用会话默认的移动身份。
        contextOverride = null,
        cookieOverride = null,
        visitorOverride = null,
        uaOverride = null,
        forceOkHttp = true,
      )
    }.getOrNull()
    if (player == null) {
      Log.w(Tag, "WEB-SABR: WEB /player failed → abort")
      return null
    }
    val status = player.obj("playabilityStatus")?.stringOrNull("status")
    if (status != "OK") {
      Log.w(Tag, "WEB-SABR: playability=$status → abort")
      return null
    }
    val sd = parseSabrData(player)
    if (sd == null) {
      Log.w(Tag, "WEB-SABR: parseSabrData ABSENT(无 sabrUrl/ustreamerCfg)→ abort")
      return null
    }
    // P11-112(诊断):/player 响应的 serverAbrStreamingUrl 参数键——与 FreeTube HAR 对比
    //(FT: alr,c=WEB,cpn,cps,keepalive,n,rqh,sabr,spc,svpuc,... 1000B)。服务端签发的参数集
    // 是它对会话信任度的可见信号;若我们缺 c/keepalive/sabr/svpuc 等键,差异在 /player 会话身份。
    runCatching {
      val parsed = Uri.parse(sd.sabrUrl)
      val keys = parsed.getQueryParameterNames()
      // P11-117:补 c=/cver=/n= 的存在性——「服务端签发形状」是它对我们会话信任度的可见信号
      //(cver 是 FreeTube 没有、youtubei.js decipher 才加的键,故只做观测不做对齐目标)。
      Log.i(
        Tag,
        "WEB-SABR sabrUrl params(${keys.size}): $keys len=${sd.sabrUrl.length} " +
          "c=${parsed.getQueryParameter("c")} cver=${parsed.getQueryParameter("cver")} " +
          "n=${parsed.getQueryParameter("n") != null}",
      )
    }
    // 选轨(对齐 reload harvest:startHeight 上限内最高档)
    val raws = sd.raws
    val videoRaws = raws.filter { (it.intOrNull("height") ?: 0) > 0 }
    val audioRaws = raws.filter { (it.stringOrNull("mimeType") ?: "").startsWith("audio/") }
    val maxHeight = youtubeDefaultQuality.maxHeight
    val defaultItag = maxHeight?.let { cap ->
      videoRaws.filter { (it.intOrNull("height") ?: 0) in 1..cap }.maxByOrNull { it.intOrNull("height") ?: 0 }
        ?.intOrNull("itag")
    } ?: videoRaws.maxByOrNull { it.intOrNull("height") ?: 0 }?.intOrNull("itag")
    val firstVideo = defaultItag?.let { t -> videoRaws.firstOrNull { (it.intOrNull("itag") ?: 0) == t } }
      ?: videoRaws.firstOrNull()
    // P11-119:默认音轨 = **原声轨**(isOriginalAudioRaw 解 xtags proto),不再用恒不命中的字面量判断。
    val firstAudio = audioRaws.firstOrNull { isOriginalAudioRaw(it) } ?: audioRaws.firstOrNull()
    if (firstVideo == null || firstAudio == null) {
      Log.w(Tag, "WEB-SABR: missing streams(video=${firstVideo != null} audio=${firstAudio != null})→ abort")
      return null
    }
    // n-decrypt(yt-dlp solver;无 n 参数则跳过;transform 失败即 abort——未 transform POST 必 403)
    // P11-118d:harvest 材料存在时**整段跳过**——那份 URL 的 n 已由浏览器 WASM transform 过,
    // 且 solver 失败会 abort 掉我们手上唯一可用的材料(必须避免)。
    var sabrUrl = sd.sabrUrl
    val sabrN = Uri.parse(sabrUrl).getQueryParameter("n")
    if (material == null && !sabrN.isNullOrBlank()) {
      val playerJsUrl = resolvePlayerJsUrl(videoId)
      if (playerJsUrl == null) {
        Log.w(Tag, "WEB-SABR: no playerJsUrl → abort")
        return null
      }
      val solved = runCatching { solverDecipherer.solve(playerJsUrl, listOf(sabrN), emptyList()) }
        // P11-114 诊断:r1947 真机 solver 静默失败(loaded→abort 之间零 solver 日志)——runCatching
        // 吞掉的异常必须现形才能定位(怀疑 WebView 主线程被 botGuard mint 并发占用/重建)。
        .onFailure { Log.w(Tag, "WEB-SABR: solver threw: ${it::class.simpleName}: ${it.message}") }
        .getOrNull()
      val solverN = solved?.let { solverDecipherer.transformedN(solved, sabrN) }
      if (solverN == null || solverN == sabrN) {
        Log.w(Tag, "WEB-SABR: n-decrypt unchanged/failed → abort(未 transform POST 必 403) playerJsUrl=${playerJsUrl.length}B sabrN=$sabrN")
        return null
      }
      val withQ = sabrUrl.replaceFirst("?n=${Uri.encode(sabrN)}", "?n=${Uri.encode(solverN)}")
      sabrUrl = if (withQ != sabrUrl) withQ
      else sabrUrl.replaceFirst("&n=${Uri.encode(sabrN)}", "&n=${Uri.encode(solverN)}")
      Log.i(Tag, "WEB-SABR: n transformed($sabrN → $solverN)")
    }
    // P11-113(对齐 FreeTube Watch.js L1739-1740):cpn 是**客户端生成**的播放 nonce——FT 用
    // `Utils.generateRandomString(16)` + `url.searchParams.set('cpn', videoInfo.cpn)` 注入 sabrUrl
    //(服务端签发的 URL 同样不带 cpn)。我们此前 queryParam("cpn") 落空(r1945 dump:34 键无 cpn)
    // → 会话 cpn 为空 → 服务端无法把请求与 playbackCookie/ustreamerConfig 会话配对 → status=2 nag。
    val cpnParam = queryParam(sd.sabrUrl, "cpn")
    val webCpn = cpnParam ?: generateCpn()
    // P11-118d:用 harvest 材料时 cpn 必须是**浏览器原 cpn**(材料三元组之一),不注入自造 cpn。
    if (material == null && cpnParam == null) {
      sabrUrl = if (sabrUrl.contains("?")) "$sabrUrl&cpn=$webCpn" else "$sabrUrl?cpn=$webCpn"
      Log.i(Tag, "WEB-SABR: cpn injected client-side($webCpn)——FreeTube Watch.js 同款")
    }
    // P11-119:WEB-SABR 也提供**音轨列表**。此前这条路的会话没传 audioTracks → `availableAudioTracks`
    // 恒空 ⇒ 移动端在 WEB-SABR 档下也看不到音轨菜单(NewPipe/Piped 路径是传了的)。数据源与 DASH 路径
    // 同源:/player `adaptiveFormats[].audioTrack{id,displayName,audioIsDefault}` + `language`
    // (解析器见 parseFormat)。单音轨视频多个 itag 的 id 都为 null → 折叠成一条 "default" 防误显示。
    val sabrAudioTracks = audioRaws.map { raw ->
      val at = raw.obj("audioTrack")
      val xt = SabrProto.parseFormatXtags(raw.stringOrNull("xtags"))
      SabrAudioTrack(
        id = at?.stringOrNull("id") ?: "default",
        languageCode = xt["lang"] ?: raw.stringOrNull("language"),
        displayName = at?.stringOrNull("displayName"),
        // 语义 = 「默认播这条」= 原声轨(与 NewPipe 路径 audioTrackType==ORIGINAL 同义)。
        // **不用** YouTube 的 audioIsDefault —— r1962 实锤它标的是英语**配音**轨,用了就会默认播配音。
        isDefault = isOriginalAudioRaw(raw),
        formatId = rawToSabrFormatId(raw, 0),
      )
    }.distinctBy { it.id }
    if (sabrAudioTracks.size > 1) {
      Log.i(
        Tag,
        "WEB-SABR audioTracks(${sabrAudioTracks.size}): " +
          sabrAudioTracks.joinToString { "${it.id}/${it.displayName ?: it.languageCode ?: "?"}${if (it.isDefault) "*orig" else ""}@itag${it.formatId.itag}" },
      )
    }
    // WEB 会话身份(P11-127:全移动)——`sabrClientInfo()` = clientName=1(WEB) + osName/osVersion
    // 取自移动 `sw.js_data`(=Android) + acceptLanguage/Region/screens/formFactor/timeZone;
    // UA 用 `Client.WEB.userAgent`(=MobileUserAgent)。此前这里是「有桌面身份就走
    // `webDesktopSabrClientInfo` + 桌面 UA」的二分,桌面那一半已被 P11-106 真机判为『身份生效但 nag
    // 依旧』,且与铸造 VM/采集页三处矛盾,现整段收敛(原 `webIdentity == null` 的兜底分支就是唯一路径)。
    // 注意 **clientName 仍为 1**:`SabrMediaFetcher` 的 `webShape = clientName == 1` 决定请求形状
    // (4 字段 clientInfo + 不发顶层 playerTimeMs),改 clientName 会同时翻动身份与请求形状两个变量。
    // P11-118g:会话默认档**一律走我们阶梯的默认档**,不用 harvest 材料里的选定档。
    // 依据(r1959 真机,`UrTJQIeUSiM`):材料选定档=itag399(AV1 1080p)时会话默认 399,而播放器选
    // 136/137(H264)→ 首帧出来 0.1s 后 `tracks changed` 从 3 条视频轨扩到 6 条 + `video size: 0x0` +
    // `video=null` → 重新 BUFFERING、pos 卡 0、duration 读成 Long.MIN → 判 ENDED → 看门狗 auto-retry
    // ⇒ **重载**。对照组(同日干净 visionOS 会话)从未出现这套 `0x0/tracks changed/ENDED` 模式。
    // 材料里真正不可替代的是 poToken/ustreamerConfig/cpn/URL,formatId 只是请求默认档(P11-29:token 不绑 itag)。
    val vFmt = rawToSabrFormatId(firstVideo, firstVideo.intOrNull("height") ?: 0)
    // P11-119:音轨切换——消费 preferredAudioTrackId。此前 WEB-SABR 路径**完全不消费**它
    // (只有「缓存会话复用」那条老路会换 audioFormatId),而这条路每次重建会话 ⇒ 切轨恒不生效
    // (r1962 真机:点选中文轨后 `audio switch` 一次都没打)。
    val preferredAudio = request.preferredAudioTrackId?.let { id -> sabrAudioTracks.firstOrNull { it.id == id } }
    if (preferredAudio != null) {
      Log.i(
        Tag,
        "WEB-SABR audio switch: track=${preferredAudio.id}(${preferredAudio.displayName ?: preferredAudio.languageCode}) " +
          "→ audio=itag${preferredAudio.formatId.itag}",
      )
    }
    val aFmt = preferredAudio?.formatId ?: rawToSabrFormatId(firstAudio, 0)
    val session = if (material != null) SabrSession.fromSabrBytes(
      material.baseSabrUrl,
      material.poTokenBytes,
      material.ustreamerConfigBytes,
      innerTubeClient.sabrClientInfo(),
      aFmt, vFmt,
      userAgent = InnerTubeClient.Client.WEB.userAgent,
      // SABR POST 不带 HTTP Cookie/X-Goog-Visitor-Id(P11-107 HAR 实锤,FreeTube 同款)。
      cookieHeader = "",
      visitorData = "",
      cpn = material.cpn,
      videoFormats = videoRaws.map { rawToSabrFormatId(it, it.intOrNull("height") ?: 0) },
      audioTracks = sabrAudioTracks,
      leadingClientAbrStateBytes = material.clientAbrStateRaw,
    ) else SabrSession.fromSabrData(
      // P11-117:恢复会话 poToken(P11-116 的 pot-less 是判别实验,已判读完毕:token 洗清——
      // 带/不带 token 的响应逐字节一致)。对齐 FreeTube:`createLocalSabrManifest(result, poToken, …)`
      // 把 content-bound(videoId 绑定)token 放进 sabrData,`SabrSchemePlugin` 再
      // `base64ToU8(sabrData.poToken)` 进 streamerContext.poToken。
      sabrUrl, poToken, sd.ustreamerCfgB64,
      innerTubeClient.sabrClientInfo(),
      aFmt, vFmt,
      userAgent = InnerTubeClient.Client.WEB.userAgent,
      // P11-107(HAR 实锤):FreeTube 的 SABR POST **不带 HTTP Cookie/X-Goog-Visitor-Id**——身份全在
      // protobuf body(playbackCookie/poToken/streamerContext),HTTP 层只有桌面 UA + Origin/Referer。
      // P11-101/r1924 时代"补 cookie/visitor"是旧 token 链(create 挑战+PoTokenWebView)时代的结论,
      // 现链(页面挑战+bare GenerateIT+桌面 clientInfo)对齐 FreeTube 全无 HTTP 身份头。
      cookieHeader = "",
      visitorData = "",
      cpn = webCpn,
      videoFormats = videoRaws.map { rawToSabrFormatId(it, it.intOrNull("height") ?: 0) },
      audioTracks = sabrAudioTracks,
    )
    val sid = SabrStreamRegistry.registerByVideoId(
      videoId, session, SabrClient(httpClient),
      // ── P11-127(Stage 2):重新启用 status=2 同步刷新 ─────────────────────────────────────
      // P11-117 当初**故意**传 null(对齐 FreeTube `SabrSchemePlugin.js:359-365`「只对 status===3
      // 反应」)。那是在**桌面会话 + 移动铸 token 错配**的年代做的决定:重铸出来的 token 与桌面会话
      // 不同源,刷了也没用,只能靠 status=3 时换整页/换会话。
      // 现在身份统一为移动(会话 `sabrClientInfo()`=Android + 移动 UA + 移动 minter),重铸的 token
      // 与 /player 同源 ⇒ 值得再试。真机 09-20 判读给出了直接动因:`hOv8` 会话 `status2Seen=5`(服务端
      // 从第 2 个响应起一路 nag)后仍在 ~35s 升 `status=3` 处决 → evict → ExoPlayer Source error →
      // auto-retry(用户体感「能播但 ~60s 重载一次」);而下一份**新材料**建的会话全程 `status=1`。
      // 即在 status=2 时就换上新鲜 token,有望把「处决+重载」变成「无感续播」。
      // 同步语义由 [SabrMediaFetcher] 保证(alpha.67 改回同步 + alpha.68 取消看门狗:status=2 在响应
      // 解析处同步重铸,下个请求必带新 token;异步化曾致竞态 status=3 60s 重启)。
      // single-flight(P11-102c)防多 fetcher 并发各铸一次互相踩。
      refreshPoToken = {
        biliTvPoTokenProvider.getWebClientPoToken(videoId)?.streamingDataPoToken?.toByteArray(Charsets.UTF_8)
      },
    )
    // WEB 会话是全新身份(探针 init POST 已被服务端接受)——清零 videoId 的 reload 计数,
    // 否则旧 visionOS 死会话留下的计数会触发 SabrDataSource fast-fail 误杀新会话
    //(08:45 r1922 真机:WEB-SABR playback ready 后所有 open 立即 reload-killed)。
    // 若 WEB 会话中途又收 RELOAD,计数重新累加 → fast-fail → 自动重试 → 重走本分支 = 自愈闭环。
    SabrStreamRegistry.resetReloadCount(videoId)
    Log.i(
      Tag,
      // P11-117:恢复真实 token 长度入日志——P11-116 把这里硬编码成 `poToken=0B`,实验结束后没回滚,
      // 导致 r1951 会话明明带 128B token 日志却显示 0B(判读陷阱)。字符串长度与解码后字节长度都给。
      "WEB-SABR playback ready: sid=$sid poTokenStr=${poToken.length}B poTokenBytes=${websafeBase64ToBytes(poToken).size}B " +
        "ustreamerCfg=${sd.ustreamerCfgB64.length}B " +
        "video=itag${vFmt.itag}(${vFmt.height}p) audio=itag${aFmt.itag} videoFormats=${videoRaws.size} dur=${sd.durationMs}ms"
    )
    // 字幕:WEB-SABR 不经 NewPipe getInfo,字幕只能取自手上这份 WEB /player 的 captions(见
    // [webPlayerSubtitleTracks])。此前这里不传 subtitleTracks ⇒ 该档下播放器没有字幕入口。
    // poToken 传入:WEB 的 captionTracks 带 exp=xpe 时需 pot(见 [withSubsPotToken]),日志里标 pot=true。
    val subtitleTracks = webPlayerSubtitleTracks(player, poToken)
    if (subtitleTracks.isNotEmpty()) {
      Log.i(
        Tag,
        "WEB-SABR subtitleTracks(${subtitleTracks.size}): " +
          subtitleTracks.joinToString {
            "${it.languageCode ?: "?"}/${it.displayName ?: "?"}${if (it.isAutoGenerated) "*asr" else ""}" +
              "${if (it.baseUrl.contains("pot=")) "*pot" else ""}"
          },
      )
    }
    return buildSabrPlaybackInfo(
      request, videoId, sd.durationMs, sd.raws, session, sid,
      subtitleTracks = subtitleTracks,
      youtubeDefaultQuality = youtubeDefaultQuality,
    ) to sid
  }

  private fun buildSabrPlaybackInfo(
    request: PlaybackRequest,
    videoId: String,
    durationMs: Long,
    raws: List<JsonObject>,
    sabrSession: SabrSession,
    sid: String,
    subtitleTracks: List<PlaybackTrack> = emptyList(),
    youtubeDefaultQuality: YoutubeDefaultQuality = YoutubeDefaultQuality.Auto,
    youtubeStartQuality: YoutubeStartQuality = YoutubeStartQuality.Q480,
    /** P11-129:解码器设置——决定「同分辨率多个 codec 变体」用哪一条(菜单只显示分辨率)。 */
    codecPreference: YoutubeCodecPreference = YoutubeCodecPreference.Auto,
  ): PlaybackInfo {
    val aItag = sabrSession.audioFormatId.itag
    // P11-119c:多条音轨共用同一 itag(靠 xtags 区分)时,只按 itag 取 raw 会拿到**别的**音轨的
    // 码率/codec 元数据 → 先按 xtags 精确匹配,再按 itag 兜底。
    val aXtags = sabrSession.audioFormatId.xtags
    val aRaw = (if (aXtags != null) raws.firstOrNull { it.stringOrNull("xtags") == aXtags } else null)
      ?: raws.firstOrNull { (it.longOrNull("itag")?.toInt() ?: 0) == aItag }
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

    // ── P11-129(对齐 B站 / LibreTube):清晰度菜单**只列分辨率** ────────────────────────────────
    // 此前按 itag 逐条列,而 SABR 阶梯同 height 有多条 codec/帧率变体(720p 5 条、1080p 3 条、1440p 2 条、
    // 2160p 2 条…)⇒ 菜单里「720p」重复出现。现在**同 height 合并成一条**,标签只留 `"${h}p"`;
    // 「用哪个变体」交给**「YouTube 解码器」设置**([YoutubeCodecPreference],P11-133 起是 YouTube 自己的值域)。
    // 代表轨的挑法与 DASH 分支的 [pickVideo] 同源:手动选中优先 → codec 偏好([codecRank] 越小越优)
    // → 码率高者。手动切换仍是「选中 itag 单轨锁定」,只是现在选中的是该分辨率的代表轨。
    fun codecKeyOfItag(itag: Int): String {
      val raw = raws.firstOrNull { (it.longOrNull("itag")?.toInt() ?: 0) == itag }
      return codecKey(
        extractCodecs(raw?.stringOrNull("mimeType") ?: "")
          .ifEmpty { raw?.stringOrNull("codec").orEmpty() },
      )
    }
    fun bitrateOfItag(itag: Int): Long =
      raws.firstOrNull { (it.longOrNull("itag")?.toInt() ?: 0) == itag }?.longOrNull("bitrate") ?: 0L

    // 全部视频 itag 作清晰度菜单;videoFormats 为空(classic 仅首条)则兜底默认 videoFormatId。
    val videoFmts = sabrSession.videoFormats.ifEmpty { listOf(sabrSession.videoFormatId) }
    /** height → 该分辨率的代表 itag(按解码器设置挑:手动选中 → codec 偏好 → 码率高者)。 */
    val repItagByHeight: Map<Int, Int> = videoFmts.groupBy { it.height }.mapValues { (_, sameHeight) ->
      (sameHeight.minWithOrNull(
        compareBy<SabrFormatId>(
          { if (it.itag == request.preferredQualityId) -1 else codecRank(codecKeyOfItag(it.itag), codecPreference) },
          { -bitrateOfItag(it.itag) },
        ),
      ) ?: sameHeight.first()).itag
    }
    val heightsDesc = repItagByHeight.keys.sortedDescending()
    val qualities = heightsDesc.map { h ->
      val itag = repItagByHeight.getValue(h)
      PlaybackQuality(id = itag, description = if (h > 0) "${h}p" else "itag $itag")
    }
    // 选档:preferredQualityId 命中该分辨率 → 用它的代表轨(换「解码器」设置后仍停在同一分辨率);
    // 否则按默认画质设置选:
    //  - maxHeight != null:height <= maxHeight 的最高档(全部超上限时取最低档保证可播);
    //  - Auto:最高可用(与 DASH 分支 pickVideo 的 Auto 语义一致——最大化分辨率)。
    //  同 sid 换 itag 即换清晰度(见上 alpha.29 注释),选非首条 itag 安全,无需重 harvest。
    val maxHeight = youtubeDefaultQuality.maxHeight
    val defaultItag = when {
      maxHeight != null ->
        heightsDesc.firstOrNull { it in 1..maxHeight }?.let { repItagByHeight.getValue(it) }
          ?: heightsDesc.lastOrNull()?.let { repItagByHeight.getValue(it) } // 全部超过上限 → 取最低档
      else -> heightsDesc.firstOrNull()?.let { repItagByHeight.getValue(it) } // Auto → 最高可用
    } ?: sabrSession.videoFormatId.itag
    val preferredHeight = request.preferredQualityId
      ?.let { pid -> videoFmts.firstOrNull { it.itag == pid }?.height }
    val selectedItag = preferredHeight?.let { repItagByHeight[it] } ?: defaultItag
    val selectedQuality = qualities.firstOrNull { it.id == selectedItag } ?: qualities.first()
    // alpha.81(复刻 LibreTube):manifest 塞全部视频轨,由 ExoPlayer 选轨。⚠️ 注意:AdaptiveTrackSelection
    // 按初始带宽估计(~1Mbps)起步,默认**不是**选最高 bitrate(旧注释误读),而是从低档起、带宽涨后爬档;
    // 且 SABR 单流 fetcher 下未下载轨 iterator=EMPTY,原生爬档被堵死。alpha.9X 已在 DefaultSabrChunkSource
    // 用「下一高码率档合成 iterator」解锁逐档升(见该文件 getNextChunk)。这里仍暴露全部轨供 ExoPlayer 选轨。
    // alpha.9X(修「SABR Auto 爬档超预设默认画质」):清单按 youtubeDefaultQuality 上限过滤——只放
    // height <= maxHeight 的档,ExoPlayer ABR 只能在预设上限内爬升,不会超过默认画质。qualities 菜单仍
    // 保留全量(手动切更高档不受限,见下方手动分支)。全部档都超上限时兜底最低档保证可播。
    // alpha.9X(修「Auto 起播直接顶最高」):轨道组按 height 升序——否则 NewPipe 的 videoOnlyStreams 是
    // **最高在前**(2160p 在 index 0),AdaptiveTrackSelection 初始选轨命中 index 0 → 首段直接 itag313(2160p),
    // 网络扛不住就 stall+auto-retry,ABR 再从顶砸回最低,不是"从低爬上去"。升序后 index 0 = 最低档 → 首段小、
    // 起播快,网络实测后由 DefaultSabrChunkSource 合成 iterator 逐档升。即使视频档>预设也兜底最低档可播。
    val sortedVideoFmts = if (maxHeight != null) {
      videoFmts.filter { it.height in 1..maxHeight }
        .ifEmpty { listOf(videoFmts.minByOrNull { it.height } ?: videoFmts.first()) }
    } else {
      videoFmts
    }.sortedBy { it.height }
    // ⚠️ 起步档已改由「带宽 seed」实现(YoutubeStartQuality.seedBps → PlayerScreen/MobilePlayerScreen 的
    // DefaultBandwidthMeter.setInitialBitrateEstimate),本块排序对 media3 1.10.0 初始选轨无效
    // (BaseTrackSelection 构造器内部强制按码率降序重排,AdaptiveTrackSelection 初始选轨=≤带宽估计×0.7的
    // 最高码率档,纯带宽驱动、不看 index0)。保留排序仅作轨道顺序呈现,勿再依赖它控制起播档。
    val startHeight = youtubeStartQuality.startHeight
    val cappedVideoFmts = if (startHeight == null) sortedVideoFmts
    else sortedVideoFmts.firstOrNull { it.height >= startHeight }
      ?.let { start -> listOf(start) + sortedVideoFmts.filterNot { it === start } }
      ?: sortedVideoFmts
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
      // 2026-08-30 修声明口径:bandwidth 改用 averageBitrate(真实平均)优先,peak 回落——
      // ABR 门槛/重锚据此判"供给 ≥ 实需";WEB /player 原生带 averageBitrate,NewPipe raws
      // 自算(见 newPipeVideoRaw),Piped 无该字段=0 回落 peak(旧行为)。
      bandwidth = raw?.intOrNull("averageBitrate")?.takeIf { it > 0 } ?: (raw?.intOrNull("bitrate") ?: 0),
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
    preference: YoutubeCodecPreference,
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

  /**
   * codec 偏好秩：偏好 codec 排最前，越靠前数字越小。
   *
   * P11-133:值域换成 [YoutubeCodecPreference]（多出 VP9）。Auto 沿用历史顺序
   * `avc > vp9 > av01 > hevc`；手动选中的族置顶，其余按该顺序兜底——这样任何一档在手时，
   * 同分辨率的变体都优先落在用户选的那族上（P11-129 的「同 height 多个 codec 变体用哪条」）。
   */
  private fun codecRank(codecKey: String, preference: YoutubeCodecPreference): Int {
    val autoOrder = listOf("avc", "vp9", "av01", "hevc", "other")
    val preferred = preference.codecKey
    val order = if (preferred == null) autoOrder else listOf(preferred) + autoOrder.filter { it != preferred }
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
      // 2026-08-30 修声明口径:peak → averageBitrate 优先(classic DASH 路径与 SABR 同口径,见 buildSabrTrack)。
      bitrate = node.intOrNull("averageBitrate")?.takeIf { it > 0 } ?: (node.intOrNull("bitrate") ?: 0),
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

    /**
     * P11-120:字幕 URL 的 `fmt` 参数(`&fmt=ttml` / `?fmt=srv3`)。匹配时保留前导 `?`/`&`,
     * 只用 [forceWebVttUrl] 替换取值,避免把 query 分隔符一起删掉。
     */
    val SubtitleFmtParamRegex = Regex("([?&])fmt=[^&]*")

    /** Piped 实例默认值(用户未填 pipedInstanceUrl 时用)。对齐 LibreTube 默认 kavin.rocks 公共实例。 */
    const val DEFAULT_PIPED_INSTANCE = "https://pipedapi.kavin.rocks"

    /** P11-118g:harvest 重新采集的时间窗——窗口内不重复采集(防风控/防 auto-retry 立刻重打),
     *  窗口外允许重采(会话被重载后必须能拿到新材料)。取值覆盖一次典型重载(错误/看门狗 → 重新 resolve)。 */
    private const val HARVEST_RETRY_WINDOW_MS = 45_000L

    /**
     * P11-126:进 WEB-SABR 优先/兜底前要求的最小**剩余**预算。低于它就跳过整条 WEB-SABR,
     * 直接落 NewPipe 主链。
     *
     * 依据(真机 09-19 `logs_live_20260919_214431.log`):WEB-SABR 优先链在真正开始 harvest 之前
     * 已花掉 ~13.7s(PO token 铸造 9.3s + player jsUrl/signatureTimestamp 4.4s);harvest **健康**时
     * watch 页 1~2s 就能出捕获(09-17 实测 1.4s),但**冷启**(建 WebView + 载首页)要 4~11s,不健康时
     * 更要烧满 40s+30s。取 45s = 「至少还够一次冷启 harvest + /player + solver + 建会话」。
     */
    private const val MinWebSabrFirstBudgetMs = 45_000L

    /**
     * P11-126:给 NewPipe 兜底预留的落地时间。每次 harvest 尝试的预算 = min(硬上限, 剩余 - 本值),
     * 保证 harvest 无论怎么烧,后面那条兜底(NewPipe SABR/DASH)仍有预算可用——真机 09-19 的教训
     * 就是 harvest 把预算吃光后,已经建好的兜底会话被整个丢弃。
     * 取值依据:真机兜底实测 21:42:34 → 21:42:40.04(约 6s),留 12s 余量。
     */
    private const val FallbackReserveMs = 12_000L

    /** P11-126:低于这个剩余预算就不发这次 harvest——发一次注定被砍的只会白烧 WebView/solver。 */
    private const val MinHarvestAttemptMs = 3_000L

    /** P11-126:harvest 冷启(建 WebView + 载首页)的硬上限,原 `timeoutMs = 40_000L`。 */
    private const val HarvestColdCapMs = 40_000L

    /** P11-126:harvest 热态重试的硬上限,原 `timeoutMs = 30_000L`。 */
    private const val HarvestWarmCapMs = 30_000L

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
