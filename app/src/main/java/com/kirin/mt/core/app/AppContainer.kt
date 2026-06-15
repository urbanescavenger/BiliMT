package com.kirin.mt.core.app

import android.content.Context
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.auth.TvLoginSigner
import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.cache.AppCacheManager
import com.kirin.mt.core.network.BiliApiClient
import com.kirin.mt.core.network.BiliHttpClientFactory
import com.kirin.mt.core.network.VideoRepository
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
  val updateDownloader: UpdateDownloader = UpdateDownloader(appContext, apiClient)
  val apkInstaller: ApkInstaller = ApkInstaller(appContext)
  val updateManager: UpdateManager = UpdateManager(
    appInfo = appInfo,
    repository = updateRepository,
    downloader = updateDownloader,
  )
}
