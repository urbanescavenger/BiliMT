package com.kirin.mt.ui.mobile.shell

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.kirin.mt.R
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.CdnSelector
import com.kirin.mt.core.player.DanmakuSettingsStore
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.storage.SearchHistoryStore
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.storage.UserSession
import com.kirin.mt.core.update.ApkInstaller
import com.kirin.mt.core.update.UpdateManager
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.ui.mobile.LoginActivity
import com.kirin.mt.ui.mobile.SettingsActivity
import com.kirin.mt.ui.mobile.common.DevelopingTipContent
import com.kirin.mt.ui.mobile.feed.MobileDynamicScreen
import com.kirin.mt.ui.mobile.home.MobileHomeScreen
import com.kirin.mt.ui.mobile.player.MobilePlayerScreen
import com.kirin.mt.ui.mobile.search.MobileSearchScreen
import com.kirin.mt.ui.player.toPlaybackRequest
import com.kirin.mt.ui.shell.AppDestination
import okhttp3.OkHttpClient

/**
 * 移动端应用壳:NavigationSuiteScaffold 自适应——窄屏底部 NavigationBar,宽屏侧边 NavigationRail,
 * 由 NavigationSuiteScaffold 根据 WindowAdaptiveInfo 自动决定。底栏项复用 [AppDestination]。
 * 点首页视频 → 全屏触屏播放器(MobilePlayerScreen)覆盖在上层。
 */
@Composable
fun BiliMobileApp(
  videoRepository: VideoRepository,
  playbackRepository: PlaybackRepository,
  danmakuSettingsStore: DanmakuSettingsStore,
  playbackHttpClient: OkHttpClient,
  cdnSelector: CdnSelector,
  authRepository: AuthRepository,
  appSettingsStore: AppSettingsStore,
  sessionStore: SessionStore,
  searchHistoryStore: SearchHistoryStore,
  updateManager: UpdateManager,
  apkInstaller: ApkInstaller,
) {
  val context = LocalContext.current
  var selected by rememberSaveable { mutableStateOf(AppDestination.Recommend) }
  var recommendRefreshKey by rememberSaveable { mutableStateOf(0) }
  val settings by appSettingsStore.settings.collectAsState(initial = AppSettings())
  val session by sessionStore.session.collectAsState(initial = UserSession())
  var playbackRequest by remember { mutableStateOf<PlaybackRequest?>(null) }

  val effectiveCodecPreference =
    if (settings.lowSpecMode) PlaybackCodecPreference.H264 else settings.playbackCodecPreference

  val bottomNav = listOf(
    AppDestination.Recommend,
    AppDestination.Dynamic,
    AppDestination.Search,
    AppDestination.Settings,
  )

  Box(modifier = Modifier.fillMaxSize()) {
    NavigationSuiteScaffold(
      modifier = Modifier.statusBarsPadding(),
      navigationSuiteItems = {
        bottomNav.forEach { dest ->
          item(
            selected = selected == dest,
            onClick = {
              if (dest == AppDestination.Settings) {
                context.startActivity(Intent(context, SettingsActivity::class.java))
              } else {
                // 重复点击当前已选中的"推荐"tab -> 触发首页刷新(滚顶 + 重载)
                if (dest == AppDestination.Recommend && selected == dest) {
                  recommendRefreshKey++
                }
                selected = dest
              }
            },
            icon = { Icon(painterResource(dest.iconRes), contentDescription = null) },
            label = { Text(stringResource(dest.titleRes)) },
          )
        }
      },
    ) {
      when (selected) {
        AppDestination.Recommend -> MobileHomeScreen(
          videoRepository = videoRepository,
          enabledSections = HomeSection.DefaultOrder,
          refreshKey = recommendRefreshKey,
          onVideoSelected = { video ->
            playbackRequest = video.toPlaybackRequest()
          },
          modifier = Modifier.fillMaxSize(),
        )
        AppDestination.Dynamic -> MobileDynamicScreen(
          videoRepository = videoRepository,
          isLoggedIn = session.isLoggedIn,
          onVideoSelected = { video -> playbackRequest = video.toPlaybackRequest() },
          onLogin = { context.startActivity(Intent(context, LoginActivity::class.java)) },
          modifier = Modifier.fillMaxSize(),
        )
        AppDestination.Pgc -> DevelopingTipContent()
        AppDestination.Search -> MobileSearchScreen(
          videoRepository = videoRepository,
          searchHistoryStore = searchHistoryStore,
          onVideoSelected = { video ->
            playbackRequest = video.toPlaybackRequest()
          },
          modifier = Modifier.fillMaxSize(),
        )
        AppDestination.Settings -> DevelopingTipContent()
      }
    }

    val request = playbackRequest
    if (request != null) {
      // 组合在 NavigationSuiteScaffold 内容(含搜索页 BackHandler)之后,
      // OnBackPressedDispatcher 栈中更靠顶,系统返回优先关播放器而非退 app / 回搜索输入态。
      BackHandler { playbackRequest = null }
      Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        MobilePlayerScreen(
          request = request,
          playbackRepository = playbackRepository,
          danmakuSettingsStore = danmakuSettingsStore,
          playbackHttpClient = playbackHttpClient,
          cdnSelector = cdnSelector,
          playbackCodecPreference = effectiveCodecPreference,
          playbackQualityPreference = settings.playbackQualityPreference,
          playbackCdnPreference = settings.playbackCdnPreference,
          onBack = { playbackRequest = null },
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}