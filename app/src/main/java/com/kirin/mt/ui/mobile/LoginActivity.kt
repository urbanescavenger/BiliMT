package com.kirin.mt.ui.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import com.kirin.mt.BiliTvApplication
import com.kirin.mt.ui.mobile.login.MobileLoginScreen
import com.kirin.mt.ui.theme.BiliTvTheme

class LoginActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val appContainer = (application as BiliTvApplication).appContainer
    setContent {
      BiliTvTheme {
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