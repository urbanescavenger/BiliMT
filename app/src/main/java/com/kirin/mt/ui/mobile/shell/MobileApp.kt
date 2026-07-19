package com.kirin.mt.ui.mobile.shell

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.kirin.mt.R
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.update.ApkInstaller
import com.kirin.mt.core.update.UpdateManager
import com.kirin.mt.ui.mobile.SettingsActivity
import com.kirin.mt.ui.mobile.common.DevelopingTipContent
import com.kirin.mt.ui.mobile.home.MobileHomeScreen
import com.kirin.mt.ui.shell.AppDestination

/**
 * 移动端应用壳:NavigationSuiteScaffold 自适应——窄屏底部 NavigationBar,宽屏侧边 NavigationRail,
 * 由 NavigationSuiteScaffold 根据 WindowAdaptiveInfo 自动决定。底栏项复用 [AppDestination]
 * (titleRes/iconRes)。Phase 1 仅首页(Recommend)可用,其余占位。
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

  val bottomNav = listOf(
    AppDestination.Recommend,
    AppDestination.Dynamic,
    AppDestination.Pgc,
    AppDestination.Settings,
  )

  NavigationSuiteScaffold(
    navigationSuiteItems = {
      bottomNav.forEach { dest ->
        item(
          selected = selected == dest,
          onClick = {
            if (dest == AppDestination.Settings) {
              // 设置走独立 Activity,不切走当前内容。
              context.startActivity(Intent(context, SettingsActivity::class.java))
            } else {
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
        onVideoSelected = { video ->
          // Phase 3 接触屏播放器,暂以 Toast 占位。
          Toast.makeText(context, context.getString(R.string.mobile_player_pending), Toast.LENGTH_SHORT).show()
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