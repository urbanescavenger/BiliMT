package com.kirin.mt.ui.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.kirin.mt.BiliTvApplication
import com.kirin.mt.core.i18n.ChineseTextConverters
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.ui.i18n.LocalChineseTextConverter
import com.kirin.mt.ui.i18n.localizedContext
import com.kirin.mt.ui.mobile.login.MobileLoginScreen
import com.kirin.mt.ui.theme.BiliTvTheme

class LoginActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    val appContainer = (application as BiliTvApplication).appContainer
    setContent {
      BiliTvTheme {
        val context = LocalContext.current
        val appSettings by appContainer.appSettingsStore.settings.collectAsState(initial = AppSettings())
        val localizedContext = remember(context, appSettings.chineseTextVariant) {
          context.localizedContext(appSettings.chineseTextVariant)
        }
        val textConverter = remember(appSettings.chineseTextVariant) {
          ChineseTextConverters.forVariant(appSettings.chineseTextVariant)
        }
        CompositionLocalProvider(
          LocalContext provides localizedContext,
          LocalResources provides localizedContext.resources,
          LocalChineseTextConverter provides textConverter,
        ) {
          MobileLoginScreen(
            authRepository = appContainer.authRepository,
            sessionStore = appContainer.sessionStore,
            onClose = { finish() },
            modifier = Modifier.statusBarsPadding(),
          )
        }
      }
    }
  }
}