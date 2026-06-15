package com.kirin.mt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kirin.mt.ui.shell.BiliTvApp
import com.kirin.mt.ui.theme.BiliTvTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val appContainer = (application as BiliTvApplication).appContainer
    setContent {
        BiliTvTheme {
          BiliTvApp(
            videoRepository = appContainer.videoRepository,
            playbackRepository = appContainer.playbackRepository,
            danmakuSettingsStore = appContainer.danmakuSettingsStore,
            playbackHttpClient = appContainer.playbackHttpClient,
            codecCapabilityProbe = appContainer.codecCapabilityProbe,
            authRepository = appContainer.authRepository,
            appSettingsStore = appContainer.appSettingsStore,
            appCacheManager = appContainer.appCacheManager,
            searchHistoryStore = appContainer.searchHistoryStore,
            sessionStore = appContainer.sessionStore,
            updateManager = appContainer.updateManager,
            apkInstaller = appContainer.apkInstaller,
          )
      }
    }
  }
}
