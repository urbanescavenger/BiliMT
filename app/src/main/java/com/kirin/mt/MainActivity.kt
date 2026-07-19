package com.kirin.mt

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kirin.mt.ui.mobile.isTvUi
import com.kirin.mt.ui.mobile.shell.BiliMobileApp
import com.kirin.mt.ui.shell.BiliTvApp
import com.kirin.mt.ui.theme.BiliTvTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val appContainer = (application as BiliTvApplication).appContainer
    val tvUi = isTvUi(this)
    // 仅移动端启用 edge-to-edge:深色 app 用 SystemBarStyle.dark 强制浅色(白)图标 +
    // 透明系统栏背景,状态栏透出 app 深色内容、白时钟/电量清晰可见。TV 无状态栏,不动。
    if (!tvUi) enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    setContent {
      BiliTvTheme {
        if (tvUi) {
          BiliTvApp(
            videoRepository = appContainer.videoRepository,
            playbackRepository = appContainer.playbackRepository,
            danmakuSettingsStore = appContainer.danmakuSettingsStore,
            playbackHttpClient = appContainer.playbackHttpClient,
            codecCapabilityProbe = appContainer.codecCapabilityProbe,
            cdnSelector = appContainer.cdnSelector,
            authRepository = appContainer.authRepository,
            appSettingsStore = appContainer.appSettingsStore,
            appCacheManager = appContainer.appCacheManager,
            searchHistoryStore = appContainer.searchHistoryStore,
            sessionStore = appContainer.sessionStore,
            updateManager = appContainer.updateManager,
            apkInstaller = appContainer.apkInstaller,
          )
        } else {
          BiliMobileApp(
            videoRepository = appContainer.videoRepository,
            playbackRepository = appContainer.playbackRepository,
            danmakuSettingsStore = appContainer.danmakuSettingsStore,
            playbackHttpClient = appContainer.playbackHttpClient,
            cdnSelector = appContainer.cdnSelector,
            authRepository = appContainer.authRepository,
            appSettingsStore = appContainer.appSettingsStore,
            sessionStore = appContainer.sessionStore,
            searchHistoryStore = appContainer.searchHistoryStore,
            updateManager = appContainer.updateManager,
            apkInstaller = appContainer.apkInstaller,
          )
        }
      }
    }
  }
}