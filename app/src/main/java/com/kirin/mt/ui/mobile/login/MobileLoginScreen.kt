package com.kirin.mt.ui.mobile.login

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.storage.SessionStore

/**
 * 移动端登录:**只支持短信登录(WebView 托管 B站 登录页)**。
 * 二维码登录在移动端无效,已移除(TV 端 AccountScreen 的 QR 不受影响)。
 */
@Composable
fun MobileLoginScreen(
  authRepository: AuthRepository,
  sessionStore: SessionStore,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  MobileSmsWebViewPanel(
    sessionStore = sessionStore,
    authRepository = authRepository,
    onSuccess = onClose,
    modifier = modifier.fillMaxSize(),
  )
}