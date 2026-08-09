package com.kirin.mt.core.app

import android.content.Context
import android.util.Log
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.auth.TvLoginSigner
import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.cache.AppCacheManager
import com.kirin.mt.core.network.BiliApiClient
import com.kirin.mt.core.network.BiliApiEndpoints
import com.kirin.mt.core.network.BiliHttpClientFactory
import com.kirin.mt.core.network.LiveRepository
import com.kirin.mt.core.network.SpaceHttpSupport
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.youtube.InnerTubeClient
import com.kirin.mt.core.youtube.YoutubeBotGuard
import com.kirin.mt.core.youtube.YoutubeChannelStore
import com.kirin.mt.core.youtube.YoutubeFeedCacheStore
import com.kirin.mt.core.youtube.YoutubePlaylistStore
import com.kirin.mt.core.youtube.YoutubeJsExecutor
import com.kirin.mt.core.youtube.YoutubeNDecryptor
import com.kirin.mt.core.youtube.YoutubePlaybackResolver
import com.kirin.mt.core.youtube.YoutubeSabrHarvester
import com.kirin.mt.core.youtube.YoutubeSDecryptor
import com.kirin.mt.core.youtube.YoutubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import com.kirin.mt.core.player.CdnSelector
import com.kirin.mt.core.player.CdnSpeedTester
import com.kirin.mt.core.player.CodecCapabilityProbe
import com.kirin.mt.core.player.DanmakuSettingsStore
import com.kirin.mt.core.player.LiveQualityPreferenceStore
import com.kirin.mt.core.player.PlaybackProgressStore
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.storage.SearchHistoryStore
import com.kirin.mt.core.storage.SessionStore
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
  val youtubeNDecryptor: YoutubeNDecryptor = YoutubeNDecryptor(appContext, youtubeJsExecutor, youtubeHttpClient)
  val youtubeSDecryptor: YoutubeSDecryptor = YoutubeSDecryptor(youtubeJsExecutor, youtubeHttpClient)
  // SABR n-decrypt 的 WebView 嵌入采集器(plasma 兜底):独立 WebView,不复用 youtubeJsExecutor
  // 单例(导航会破坏其 bgutils 上下文)。每次 harvest 建新 WebView 用完销毁(alpha.20 MVP)。
  val youtubeSabrHarvester: YoutubeSabrHarvester = YoutubeSabrHarvester(appContext, youtubeInnerTubeClient)
  val youtubeRepository: YoutubeRepository = YoutubeRepository(
    client = youtubeInnerTubeClient,
  )
  val youtubePlaybackResolver: YoutubePlaybackResolver = YoutubePlaybackResolver(
    innerTubeClient = youtubeInnerTubeClient,
    botGuard = youtubeBotGuard,
    nDecryptor = youtubeNDecryptor,
    sDecryptor = youtubeSDecryptor,
    httpClient = youtubeHttpClient,
    sabrHarvester = youtubeSabrHarvester,
  )
  val videoRepository: VideoRepository = VideoRepository(
    apiClient = apiClient,
    wbiKeyRepository = wbiKeyRepository,
    wbiSigner = wbiSigner,
    sessionStore = sessionStore,
    youtubeRepository = youtubeRepository,
  )
  val liveRepository: LiveRepository = LiveRepository(
    apiClient = apiClient,
    wbiKeyRepository = wbiKeyRepository,
    wbiSigner = wbiSigner,
    sessionStore = sessionStore,
  )
  val playbackRepository: PlaybackRepository = PlaybackRepository(
    apiClient = apiClient,
    wbiKeyRepository = wbiKeyRepository,
    wbiSigner = wbiSigner,
    sessionStore = sessionStore,
    codecCapabilityProbe = codecCapabilityProbe,
    progressStore = PlaybackProgressStore(appContext),
    youtubePlaybackResolver = youtubePlaybackResolver,
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
    repository = webdavRepository,
    json = json,
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
    }
  }

  private companion object {
    const val LogTag = "BiliWarmup"
  }
}
