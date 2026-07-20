package com.kirin.mt.ui.mobile.shell

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
  var spaceRequest by remember { mutableStateOf<com.kirin.mt.ui.space.UpSpaceRequest?>(null) }
  // 空间是否压在播放器之上:true=刚从播放器进空间(空间在上、播放器藏后),
  // false=从空间起了播(播放器在上)。配合空间叠层显示门控与 BackHandler enabled,
  // 让"空间→视频→返回→空间"成立(不销毁 spaceRequest),镜像 TV AppShell spacePlaybackBehind。
  var spacePlaybackBehind by remember { mutableStateOf(false) }

  // Android 13+ 需运行时请求 POST_NOTIFICATIONS,否则后台播放通知(及控件)不显示。
  val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

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
          onOpenOwner = { video ->
            spaceRequest = com.kirin.mt.ui.space.UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
            spacePlaybackBehind = false
          },
          modifier = Modifier.fillMaxSize(),
        )
        AppDestination.Dynamic -> MobileDynamicScreen(
          videoRepository = videoRepository,
          isLoggedIn = session.isLoggedIn,
          onVideoSelected = { video -> playbackRequest = video.toPlaybackRequest() },
          onOpenOwner = { video ->
            spaceRequest = com.kirin.mt.ui.space.UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
            spacePlaybackBehind = false
          },
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
          onOpenOwner = { video ->
            spaceRequest = com.kirin.mt.ui.space.UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
            spacePlaybackBehind = false
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
      // 空间压在播放器之上时(spacePlaybackBehind),播放器让出返回键由空间响应。
      BackHandler(enabled = !(spaceRequest != null && spacePlaybackBehind)) {
        playbackRequest = null
      }
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
          airJumpAssistantEnabled = settings.airJumpAssistantEnabled,
          videoRepository = videoRepository,
          onPlayVideo = { video -> playbackRequest = video.toPlaybackRequest() },
          onBack = { playbackRequest = null },
          onOpenUpSpace = { mid, name, face ->
            spaceRequest = com.kirin.mt.ui.space.UpSpaceRequest(mid, name, face)
            spacePlaybackBehind = true
          },
          modifier = Modifier.fillMaxSize(),
        )
      }
    }

    val space = spaceRequest
    if (space != null) {
      // 空间在顶层(无播放器或刚从播放器进空间)时才接管返回键;播放器在上时让播放器响应。
      BackHandler(enabled = playbackRequest == null || spacePlaybackBehind) {
        spaceRequest = null
        spacePlaybackBehind = false
      }
      // 显示门控:仅在空间处于顶层时渲染,避免从空间起播后空间仍盖在播放器之上。
      if (playbackRequest == null || spacePlaybackBehind) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        ) {
          com.kirin.mt.ui.mobile.space.MobileUserSpaceScreen(
            videoRepository = videoRepository,
            mid = space.mid,
            ownerName = space.ownerName,
            ownerFace = space.ownerFace,
            // 不销毁 spaceRequest:播放器返回时由门控重新露出空间,而非落回首页。
            onVideoSelected = { video ->
              spacePlaybackBehind = false
              playbackRequest = video.toPlaybackRequest()
            },
            // 点空间内卡片的 UP 头像 -> 切到该 UP 空间(LaunchedEffect(mid) 自动重载)。
            // 不动 spacePlaybackBehind:保留来源栈(从播放器进来的返回回播放器,从 tab 进来的返回回 tab)。
            onOpenOwner = { video ->
              spaceRequest = com.kirin.mt.ui.space.UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
            },
            onBack = {
              spaceRequest = null
              spacePlaybackBehind = false
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
  }
}