package com.kirin.mt.ui.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kirin.mt.BiliTvApplication
import com.kirin.mt.R
import com.kirin.mt.core.storage.UserSession
import com.kirin.mt.ui.mobile.settings.FollowManageKind
import com.kirin.mt.ui.mobile.settings.MobileFollowManageScreen
import com.kirin.mt.ui.mobile.settings.MobileSettingsScreen
import com.kirin.mt.ui.theme.BiliTvTheme

class SettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    val appContainer = (application as BiliTvApplication).appContainer
    setContent {
      BiliTvTheme {
        Surface(modifier = Modifier.fillMaxSize().statusBarsPadding(), color = MaterialTheme.colorScheme.background) {
          val session by appContainer.sessionStore.session.collectAsState(initial = UserSession())
          var followScreen by remember { mutableStateOf<FollowManageKind?>(null) }
          Column(modifier = Modifier.fillMaxSize()) {
            val kind = followScreen
            if (kind == null) {
              SettingsTopBar(
                title = stringResource(R.string.mobile_settings_title),
                onBack = { finish() },
              )
              MobileSettingsScreen(
                appSettingsStore = appContainer.appSettingsStore,
                updateManager = appContainer.updateManager,
                apkInstaller = appContainer.apkInstaller,
                sessionStore = appContainer.sessionStore,
                authRepository = appContainer.authRepository,
                onOpenFollows = { followScreen = it },
                onLogin = { startActivity(android.content.Intent(this@SettingsActivity, LoginActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
              )
            } else {
              SettingsTopBar(
                title = when (kind) {
                  FollowManageKind.BiliFollows -> stringResource(R.string.mobile_follows_bili)
                  FollowManageKind.YoutubeFollows -> stringResource(R.string.mobile_follows_youtube)
                },
                onBack = { followScreen = null },
              )
              MobileFollowManageScreen(
                kind = kind,
                mid = session.mid ?: 0L,
                videoRepository = appContainer.videoRepository,
                youtubeChannelStore = appContainer.youtubeChannelStore,
                youtubeRepository = appContainer.youtubeRepository,
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
  Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
    TextButton(onClick = onBack, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart)) {
      Text(stringResource(R.string.mobile_back))
    }
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
    )
  }
}
