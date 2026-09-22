package com.kirin.mt.core.app

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.auth.TvLoginSigner
import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.cache.AppCacheManager
import com.kirin.mt.core.download.DownloadDatabase
import com.kirin.mt.core.download.DownloadEngine
import com.kirin.mt.core.download.DownloadManager
import com.kirin.mt.core.download.DownloadStorage
import com.kirin.mt.core.download.DownloadUrlResolver
import com.kirin.mt.core.network.BiliApiClient
import com.kirin.mt.core.network.BiliApiEndpoints
import com.kirin.mt.core.network.BiliHttpClientFactory
import com.kirin.mt.core.network.HongguoRepository
import com.kirin.mt.core.network.IptvRepository
import com.kirin.mt.core.network.LiveRepository
import com.kirin.mt.core.network.SpaceHttpSupport
import com.kirin.mt.core.network.TvboxRepository
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.youtube.InnerTubeClient
import com.kirin.mt.core.youtube.YoutubeBotGuard
import com.kirin.mt.core.youtube.YoutubeBrowserSession
import com.kirin.mt.core.youtube.YoutubeChannelStore
import com.kirin.mt.core.youtube.YoutubeFeedCacheStore
import com.kirin.mt.core.youtube.YoutubeHistoryStore
import com.kirin.mt.core.youtube.YoutubePlaylistStore
import com.kirin.mt.core.youtube.YoutubeJsExecutor
import com.kirin.mt.core.youtube.YoutubeNDecryptor
import com.kirin.mt.core.youtube.YoutubeSolverDecipherer
import com.kirin.mt.core.youtube.YoutubePlaybackResolver
import com.kirin.mt.core.youtube.YoutubeSDecryptor
import com.kirin.mt.core.youtube.YoutubeRepository
import com.kirin.mt.core.youtube.YoutubeSabrHarvester
import com.kirin.mt.core.youtube.newpipe.NewPipePoTokenGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import com.kirin.mt.core.player.CdnSelector
import com.kirin.mt.core.player.CdnSpeedTester
import com.kirin.mt.core.player.CodecCapabilityProbe
import com.kirin.mt.core.player.DanmakuSettingsStore
import com.kirin.mt.core.player.IptvSourceProbeStore
import com.kirin.mt.core.player.IptvSourceProber
import com.kirin.mt.core.player.LiveQualityPreferenceStore
import com.kirin.mt.core.player.PlaybackProgressStore
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.YoutubeDeliveryPriority
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.storage.SearchHistoryStore
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.storage.WatchedStore
import com.kirin.mt.core.storage.WbiKeyStore
import com.kirin.mt.core.update.ApkInstaller
import com.kirin.mt.core.update.UpdateDownloader
import com.kirin.mt.core.update.UpdateManager
import com.kirin.mt.core.update.UpdateRepository
import com.kirin.mt.core.webdav.WebDavBackupService
import com.kirin.mt.core.webdav.WebDavConfigStore
import com.kirin.mt.core.webdav.WebDavRepository
import kotlinx.serialization.json.Json

class AppContainer(context: Context) {
  private val appContext = context.applicationContext

  /**
   * Application-scoped coroutine scope for fire-and-forget background work like API warmup.
   * SupervisorJob so one failure doesn't cancel siblings.
   */
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
  }

  val appSettingsStore: AppSettingsStore = AppSettingsStore(appContext)
  val appCacheManager: AppCacheManager = AppCacheManager(appContext)
  val searchHistoryStore: SearchHistoryStore = SearchHistoryStore(appContext)
  val sessionStore: SessionStore = SessionStore(appContext)
  val wbiKeyStore: WbiKeyStore = WbiKeyStore(appContext)
  val watchedStore: WatchedStore = WatchedStore(appContext)
  val httpClientFactory: BiliHttpClientFactory = BiliHttpClientFactory()
  val codecCapabilityProbe: CodecCapabilityProbe = CodecCapabilityProbe()
  val playbackHttpClient = httpClientFactory.createPlaybackClient()
  val downloadHttpClient = httpClientFactory.createDownloadClient()
  val cdnSpeedTester: CdnSpeedTester = CdnSpeedTester(playbackHttpClient)
  val cdnSelector: CdnSelector = CdnSelector(cdnSpeedTester)
  val apiClient: BiliApiClient = BiliApiClient(
    client = httpClientFactory.createApiClient(),
    json = json,
  )
  val wbiSigner: WbiSigner = WbiSigner()
  val wbiKeyRepository: WbiKeyRepository = WbiKeyRepository(
    apiClient = apiClient,
    keyStore = wbiKeyStore,
  )
  val youtubeChannelStore: YoutubeChannelStore = YoutubeChannelStore(appContext)
  val youtubePlaylistStore: YoutubePlaylistStore = YoutubePlaylistStore(appContext)
  val youtubeFeedCacheStore: YoutubeFeedCacheStore = YoutubeFeedCacheStore(appContext)
  val youtubeHistoryStore: YoutubeHistoryStore = YoutubeHistoryStore(appContext)
  // 共享同一个 YouTube OkHttpClient（InnerTube 数据 + /player + base.js/watch 抓取复用连接池）。
  val youtubeHttpClient = httpClientFactory.createYoutubeClient()
  val youtubeJsExecutor: YoutubeJsExecutor = YoutubeJsExecutor(appContext)
  // 真实浏览器会话 WebView（方案 A，对齐 FreeTubeAndroid 主 WebView）：长期存活加载真实 YouTube 页，
  // /player 走它 + 用它的真实 visitorData/cookie（根因修复：隐藏壳合成 fetch 被判"非真浏览器"）。
  val youtubeBrowserSession: YoutubeBrowserSession = YoutubeBrowserSession(appContext)
  // 共享同一个 InnerTubeClient：visitorData/realSessionData 必须跨 BotGuard(铸 token)与
  // PlaybackResolver(/player)一致，否则 token 绑定 A、/player 用 B → token 无效
  // → "The page needs to be reloaded"(alpha.26 实测：3 个独立实例各 fetch 不同 visitorData)。
  val youtubeInnerTubeClient = InnerTubeClient(
    httpClient = youtubeHttpClient,
    // WEB /player 走 WebView 原生网络栈(Chromium)时用同一 executor（对齐 FreeTubeAndroid 主 WebView）。
    jsExecutor = youtubeJsExecutor,
    // 方案 A：/player 优先走真实浏览器会话 WebView（真实页上下文 + 真实 cookie/TLS）。
    browserSession = youtubeBrowserSession,
  )
  val youtubeBotGuard: YoutubeBotGuard = YoutubeBotGuard(
    executor = youtubeJsExecutor,
    httpClient = youtubeHttpClient,
    innerTubeClient = youtubeInnerTubeClient,
  )
  // path C:NewPipeExtractor fork 的 PoTokenProvider,由移植的 NewPipe 原生 PoTokenGenerator 支撑。
  // 铸造的 poToken 缓存供 SABR init 复用(init==extraction 同 minter)。内容绑定(contentBinding)正确,
  // 修复 visionOS SABR RELOAD 死循环(§6.17/alpha.80)。旧 BiliTvPoTokenProvider/YoutubeBotGuard 保留,
  // SABR 不再用,但 /player 等仍走 BotGuard。
  /**
   * P11-154:arm A 的**铸造上下文**供给(显式标注类型,避免 lambda 类型推断歧义)。
   *
   * 把挑战源从 `[REQUEST_KEY]`→`/api/jnn/v1/Create`(文档里没有页面/ytcfg/EVENT_ID)换成
   * 「移动 watch 页自带的 ytAtN + 该页 `yt.config_`(EVENT_ID)」。依据:历史唯一拿到 `status=1` 的
   * token 都出自真 watch 页自铸(MWEB,3/3);而我们自铸的首笔必被判占位级(`status=2`,今天 7/7 场)。
   *
   * 关(实验关)→ null ⇒ 回落路径与改动前**逐字节一致**。开关见 [NewPipePoTokenGenerator.ARM_A_PAGE_CONTEXT]。
   */
  private val armAPageContextSupplier: (suspend (String) -> YoutubeBotGuard.PoTokenPageContext?)? =
    if (NewPipePoTokenGenerator.ARM_A_PAGE_CONTEXT) {
      { videoId ->
        youtubeBotGuard.fetchArmAPageContext(videoId, youtubeBrowserSession.readVisitorData())
      }
    } else {
      null
    }

  val biliTvPoTokenProvider: NewPipePoTokenGenerator = NewPipePoTokenGenerator(
    appContext = appContext,
    httpClient = youtubeHttpClient,
    pageContextSupplier = armAPageContextSupplier,
  )
  val youtubeNDecryptor: YoutubeNDecryptor = YoutubeNDecryptor(appContext, youtubeJsExecutor, youtubeHttpClient)
  val youtubeSDecryptor: YoutubeSDecryptor = YoutubeSDecryptor(youtubeJsExecutor, youtubeHttpClient)
  // P11-101:WebView 内 yt-dlp solver(n/s decipher,AST 结构匹配 + URL 类 transform)
  val youtubeSolverDecipherer: YoutubeSolverDecipherer = YoutubeSolverDecipherer(appContext, youtubeJsExecutor)
  val youtubeRepository: YoutubeRepository = YoutubeRepository(
    client = youtubeInnerTubeClient,
  )
  val pipedClient: com.kirin.mt.core.youtube.piped.PipedClient =
    com.kirin.mt.core.youtube.piped.PipedClient(httpClient = youtubeHttpClient, json = json)
  // P11-118 诊断(阶段 2 最小验证):复活 WebView harvest 采集器——真实桌面 WebView 加载 watch 页,
  // hook fetch/XHR 截获浏览器自己发的 SABR POST(浏览器 WASM 做 n-transform + 全 WEB 一致 attested body)。
  // 当前**只采集打日志、不接播放栈**(见 YoutubePlaybackResolver 的 harvest probe)。
  val youtubeSabrHarvester: YoutubeSabrHarvester =
    YoutubeSabrHarvester(appContext, youtubeInnerTubeClient)
  val youtubePlaybackResolver: YoutubePlaybackResolver = YoutubePlaybackResolver(
    innerTubeClient = youtubeInnerTubeClient,
    botGuard = youtubeBotGuard,
    nDecryptor = youtubeNDecryptor,
    sDecryptor = youtubeSDecryptor,
    solverDecipherer = youtubeSolverDecipherer,
    httpClient = youtubeHttpClient,
    biliTvPoTokenProvider = biliTvPoTokenProvider,
    pipedClient = pipedClient,
    appSettingsStore = appSettingsStore,
    sabrHarvester = youtubeSabrHarvester,
  )
  // 播放进度本地存储:VideoRepository 用它给普通卡片合入观看进度条,PlaybackRepository 用它续播。
  val playbackProgressStore: PlaybackProgressStore = PlaybackProgressStore(appContext)
  val tvboxRepository: TvboxRepository = TvboxRepository(
    client = downloadHttpClient,
    appSettingsStore = appSettingsStore,
  )
  val hongguoRepository: HongguoRepository = HongguoRepository(
    client = downloadHttpClient,
  )
  val videoRepository: VideoRepository = VideoRepository(
    apiClient = apiClient,
    wbiKeyRepository = wbiKeyRepository,
    wbiSigner = wbiSigner,
    sessionStore = sessionStore,
    youtubeRepository = youtubeRepository,
    youtubeChannelStore = youtubeChannelStore,
    progressStore = playbackProgressStore,
    tvboxRepository = tvboxRepository,
    hongguoRepository = hongguoRepository,
  )
  val liveRepository: LiveRepository = LiveRepository(
    apiClient = apiClient,
    wbiKeyRepository = wbiKeyRepository,
    wbiSigner = wbiSigner,
    sessionStore = sessionStore,
  )
  val iptvRepository: IptvRepository = IptvRepository(
    client = downloadHttpClient,
    appSettingsStore = appSettingsStore,
  )
  // IPTV 源判活(app 级,判活一次本次启动全程复用,见 docs/iptv-feasibility.md 三期):
  // store 由 TV 列表页/播放器/缩略图截帧共读共写;prober 只在启动扫一次。
  val iptvSourceProbeStore: IptvSourceProbeStore = IptvSourceProbeStore()
  private val iptvSourceProber = IptvSourceProber(
    repository = iptvRepository,
    store = iptvSourceProbeStore,
  )
  val playbackRepository: PlaybackRepository = PlaybackRepository(
    apiClient = apiClient,
    wbiKeyRepository = wbiKeyRepository,
    wbiSigner = wbiSigner,
    sessionStore = sessionStore,
    codecCapabilityProbe = codecCapabilityProbe,
    progressStore = playbackProgressStore,
    youtubePlaybackResolver = youtubePlaybackResolver,
    tvboxRepository = tvboxRepository,
  )
  val danmakuSettingsStore: DanmakuSettingsStore = DanmakuSettingsStore(appContext)
  val liveQualityPreferenceStore: LiveQualityPreferenceStore = LiveQualityPreferenceStore(appContext)
  val tvLoginSigner: TvLoginSigner = TvLoginSigner()
  val authRepository: AuthRepository = AuthRepository(
    apiClient = apiClient,
    tvLoginSigner = tvLoginSigner,
    sessionStore = sessionStore,
  )
  val appInfo: AppInfo = AppInfo(appContext)
  val updateRepository: UpdateRepository = UpdateRepository(
    apiClient = apiClient,
    repoOwner = "urbanescavenger",
    repoName = "BiliMT",
  )
  val updateDownloader: UpdateDownloader = UpdateDownloader(appContext, downloadHttpClient)
  val apkInstaller: ApkInstaller = ApkInstaller(appContext)
  val updateManager: UpdateManager = UpdateManager(
    appInfo = appInfo,
    repository = updateRepository,
    downloader = updateDownloader,
  )
  val webdavConfigStore: WebDavConfigStore = WebDavConfigStore(appContext)
  val webdavRepository: WebDavRepository = WebDavRepository(downloadHttpClient)
  val webdavBackupService: WebDavBackupService = WebDavBackupService(
    channelStore = youtubeChannelStore,
    watchedStore = watchedStore,
    repository = webdavRepository,
    json = json,
    settingsStore = appSettingsStore,
    sessionStore = sessionStore,
  )

  // ── 视频下载管理器(离线播放) ──────────────────────────────────────────────
  val downloadDatabase: DownloadDatabase = Room.databaseBuilder(
    appContext,
    DownloadDatabase::class.java,
    "download.db",
  ).addMigrations(DownloadDatabase.MIGRATION_2_1).build()
  val downloadStorage: DownloadStorage = DownloadStorage(appContext)
  val downloadEngine: DownloadEngine = DownloadEngine(downloadHttpClient)
  val downloadUrlResolver: DownloadUrlResolver = DownloadUrlResolver(
    playbackRepository = playbackRepository,
    youtubePlaybackResolver = youtubePlaybackResolver,
    appSettingsStore = appSettingsStore,
  )
  val downloadManager: DownloadManager = DownloadManager(
    appContext = appContext,
    dao = downloadDatabase.downloadDao(),
    storage = downloadStorage,
    urlResolver = downloadUrlResolver,
    engine = downloadEngine,
    thumbnailClient = downloadHttpClient,
    json = json,
    // 下载完成自动存档进「下载」播放列表(见 DownloadManager.finalizeGroup)。
    playlistStore = youtubePlaylistStore,
  )

  /**
   * 预热 api.bilibili.com 连接:启动后后台发一个轻量请求(BuvidSpi — 未登录可用,body 小,
   * 顺便预拉 buvid 种子),把 DNS+TCP+TLS 握手提前做完,连接进 OkHttp 连接池保留 ~5min。
   * 之后首开 UP 主主页等接口省掉冷建连的几百毫秒。HTTP/2 下同 host 后续请求复用此连接。
   * Fire-and-forget:失败静默,不影响 app 启动。
   *
   * 与视频流 CDN 优选不同 — 接口域名固定(api.bilibili.com),无多 CDN 候选可挑,
   * 这里只暖连接池,不选节点。
   */
  fun warmupApiConnection() {
    applicationScope.launch {
      val startedNs = System.nanoTime()
      runCatching { apiClient.getJson(BiliApiEndpoints.BuvidSpi) }
        .onSuccess {
          val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
          Log.i(LogTag, "api warmup ok: ${elapsedMs}ms (connection pooled for api.bilibili.com)")
        }
        .onFailure { error -> Log.w(LogTag, "api warmup failed: ${error.message}") }

      // 预热 buvid3/4:spi 拿 b_3/b_4 → activateBuvid 激活 → 存盘。这样用户首进 UP 主页前
      // buvid 已激活且服务端有秒级采信窗口,SpaceProfileRepository.fetchAccInfo 直接命中缓存,
      // 资料/粉丝数首进即加载,不再因 buvid 冷启动被判 452 空白。fire-and-forget,失败静默。
      runCatching { SpaceHttpSupport.ensureBuvidCookies(sessionStore, apiClient) }
        .onSuccess { (buvid3, buvid4) ->
          Log.i(LogTag, "buvid warmup ok: hasBuvid3=${!buvid3.isNullOrBlank()} hasBuvid4=${!buvid4.isNullOrBlank()}")
        }
        .onFailure { error -> Log.w(LogTag, "buvid warmup failed: ${error.message}") }

      // 预热 YouTube 真实会话(sw.js_data + 首页 cookie):feed 首屏 /browse 不再阻塞 ~3s 会话建立
      // (冷启动 RSS 全 404 时这段在动态关键路径上)。fire-and-forget,失败静默(下次请求仍会惰性建立)。
      runCatching { youtubeInnerTubeClient.warmupSession() }
        .onSuccess { Log.i(LogTag, "youtube session warmup ok") }
        .onFailure { error -> Log.w(LogTag, "youtube session warmup failed: ${error.message}") }
    }
  }

  /**
   * 启动后台 IPTV 源判活扫描(见 docs/iptv-feasibility.md 三期):延迟 15s 避开冷启动
   * 图片/接口流量高峰,然后对多源频道廉价 m3u8 探活(约 50 MB/千频道,KB 级 GET),
   * 结果写 [iptvSourceProbeStore] 供 TV 列表/播放器活源前置复用。fire-and-forget:
   * 未配置源时 getChannels 返回空自然退出;失败静默(列表/截帧路径不受影响)。
   */
  fun startIptvSourceProbe() {
    applicationScope.launch {
      delay(IptvProbeStartupDelayMs)
      runCatching { iptvSourceProber.sweepOnce() }
        .onFailure { error -> Log.w(LogTag, "iptv source probe failed: ${error.message}") }
    }
  }

  /**
   * P11-126:启动后台预热 harvest 采集 WebView(见 [YoutubeSabrHarvester.prewarm])。
   *
   * 真机 09-19:harvest WebView 的冷启(建实例 + 加载 youtube.com 首页建立浏览上下文)实测 **10.9s**,
   * 而它整段都落在起播预算里(PO token 铸造 9.3s + player js 4.4s 之后才轮到它)⇒ watch 页还没开始
   * 加载预算就到期,整条 launch 被取消,连已建好的 NewPipe 兜底会话也一起丢弃,用户黑屏 ~99s。
   * 把冷启挪到播放之外,harvest 就只剩「导航 watch 页等捕获」那 1~2s。
   *
   * **只在「WEB-SABR 优先」档做**:只有这一档 harvest 在起播关键路径上(该档 90s 预算就是为它给的);
   * SABR/DASH 档的 harvest 只做很晚的兜底,多等几秒无所谓 —— 不给不用它的用户白起一个 WebView +
   * 拉一次首页(~1.4MB)。用户切档后重启即生效。
   *
   * fire-and-forget,失败静默(与 [warmupApiConnection] / [startIptvSourceProbe] 同款)。
   */
  fun startYoutubeHarvestPrewarm() {
    applicationScope.launch {
      val priority = runCatching { appSettingsStore.settings.first().youtubeDeliveryPriority }.getOrNull()
      if (priority != YoutubeDeliveryPriority.WebSabr) {
        Log.i(LogTag, "youtube harvest prewarm skipped (priority=$priority,只有 WEB-SABR 优先档在关键路径上)")
        return@launch
      }
      delay(YoutubeHarvestPrewarmDelayMs)
      runCatching { youtubeSabrHarvester.prewarm() }
        .onSuccess { ms -> if (ms != null) Log.i(LogTag, "youtube harvest prewarm ok: ${ms}ms") }
        .onFailure { error -> Log.w(LogTag, "youtube harvest prewarm failed: ${error.message}") }
    }
  }

  /**
   * P11-154(只读取证,零行为影响):探一次**真实浏览会话的活文档**里有没有 `window.ytAtN` / `EVENT_ID`。
   *
   * 要回答的悬案:P11-103 记的「移动 UA 抓 watch 页拿不到 ytAtN」是从 **OkHttp 302 后的 HTML** 推的,
   * 而 MWEB 是 Polymer SPA——**启动后的活文档**与初始 HTML 不是一回事,从没测过。这条答案决定
   * P11-154 的下一轮该把页面上下文的**供给源**换成活文档还是别的。
   *
   * 真实浏览会话是懒加载的(首次 /player 才建),故这里等它起来:每 [PageContextProbeIntervalMs] 探一次,
   * 最多 [PageContextProbeAttempts] 次。只读、不导航、不建会话(见 [YoutubeBrowserSession.peekPageContext])。
   */
  fun startYoutubePageContextProbe() {
    applicationScope.launch {
      repeat(PageContextProbeAttempts) {
        delay(PageContextProbeIntervalMs)
        val result = runCatching { youtubeBrowserSession.peekPageContext() }.getOrNull()
        if (result != null) {
          Log.i(LogTag, "armA probe(live session): $result")
          return@launch
        }
      }
      Log.i(LogTag, "armA probe(live session): 无活会话/不在 youtube 域 → 本轮未取到")
    }
  }

  private companion object {
    const val LogTag = "BiliWarmup"
    /** IPTV 判活扫描的启动延迟:避开冷启动图片/接口流量高峰再动网络。 */
    const val IptvProbeStartupDelayMs = 15_000L
    /** P11-126:harvest 预热的启动延迟——比 IPTV 探活早,因为预热自己还要再花 4~11s 建 WebView + 载首页。 */
    const val YoutubeHarvestPrewarmDelayMs = 8_000L
    /** P11-154:活文档探针的尝试次数 × 间隔(等真实浏览会话起来;每次 5s,共 ~60s)。 */
    const val PageContextProbeAttempts = 12
    const val PageContextProbeIntervalMs = 5_000L
  }
}
