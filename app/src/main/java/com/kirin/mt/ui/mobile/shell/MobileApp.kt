package com.kirin.mt.ui.mobile.shell

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.update.ApkInstaller
import com.kirin.mt.core.update.UpdateManager
import com.kirin.mt.ui.mobile.common.DevelopingTipContent
import com.kirin.mt.ui.mobile.home.MobileHomeScreen
import com.kirin.mt.ui.shell.AppDestination

/**
 * 移动端应用壳:NavigationSuiteScaffold 自适应——窄屏底部 NavigationBar,宽屏侧边 NavigationRail。
 * 底栏项复用 [AppDestination](titleRes/iconRes)。Phase 1 仅首页(Recommend)可用,其余占位。
 */
@Composable
fun BiliMobileApp(
  videoRepository: VideoRepository,
  sessionStore: SessionStore,
  authRepository: AuthRepository,
  appSettingsStore: AppSettingsStore,
  updateManager: UpdateManager,
  apkInstaller: ApkInstaller,
) {
  val context = LocalContext.current
  var selected by rememberSaveable { mutableStateOf(AppDestination.Recommend) }
  val navType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
    currentWindowAdaptiveInfo()
  )

  val bottomNav = listOf(
    AppDestination.Recommend,
    AppDestination.Dynamic,
    AppDestination.Pgc,
    AppDestination.Settings,
  )

  NavigationSuiteScaffold(
    navigationSuite = {
      if (navType == NavigationSuiteType.NavigationBar) {
        NavigationBar {
          bottomNav.forEach { dest ->
            NavigationBarItem(
              selected = selected == dest,
              onClick = { selected = dest },
              icon = { Icon(painterResource(dest.iconRes), contentDescription = null) },
              label = { Text(stringResource(dest.titleRes)) },
            )
          }
        }
      } else {
        NavigationRail {
          bottomNav.forEach { dest ->
            NavigationRailItem(
              selected = selected == dest,
              onClick = { selected = dest },
              icon = { Icon(painterResource(dest.iconRes), contentDescription = null) },
              label = { Text(stringResource(dest.titleRes)) },
            )
          }
        }
      }
    },
  ) {
    when (selected) {
      AppDestination.Recommend -> MobileHomeScreen(
        videoRepository = videoRepository,
        enabledSections = HomeSection.DefaultOrder,
        onVideoSelected = { video ->
          // Phase 3 接触屏播放器,暂以 Toast 占位。
          Toast.makeText(context, context.getString(com.kirin.mt.R.string.mobile_player_pending), Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxSize(),
      )
      AppDestination.Dynamic -> DevelopingTipContent()
      AppDestination.Pgc -> DevelopingTipContent()
      AppDestination.Settings -> DevelopingTipContent()
      AppDestination.Search -> DevelopingTipContent()
    }
  }
}