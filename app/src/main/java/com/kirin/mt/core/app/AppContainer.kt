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
import com.kirin.mt.core.network.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import com.kirin.mt.core.player.CdnSelector
import com.kirin.mt.core.player.CdnSpeedTester
import com.kirin.mt.core.player.CodecCapabilityProbe
import com.kirin.mt.core.player.DanmakuSettingsStore
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
  val videoRepository: VideoRepository = VideoRepository(
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
  )
  val danmakuSettingsStore: DanmakuSettingsStore = DanmakuSettingsStore(appContext)
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
    }
  }

  private companion object {
    const val LogTag = "BiliWarmup"
  }
}
