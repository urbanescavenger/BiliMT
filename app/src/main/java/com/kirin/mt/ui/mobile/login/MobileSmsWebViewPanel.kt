package com.kirin.mt.ui.mobile.login

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BiliLoginUrl = "https://passport.bilibili.com/login"
private const val CookiePollIntervalMs = 1000L

/**
 * 短信登录:WebView 托管 B站 登录页(用户在 B站 自己的页面完成 手机号+极验滑块+短信),
 * 登录成功后从 CookieManager 抓 SESSDATA/bili_jct 存进 SessionStore 并刷新用户资料。
 * 不引入极验 SDK、不逆向 B站 sms/captcha API。
 */
@Composable
fun MobileSmsWebViewPanel(
  sessionStore: SessionStore,
  authRepository: AuthRepository,
  onSuccess: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var consumed by remember { mutableStateOf(false) }

  val webView = remember {
    WebView(context).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.userAgentString =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
      CookieManager.getInstance().setAcceptCookie(true)
      webViewClient = WebViewClient()
    }
  }

  // 启动:清掉旧 cookie(便于检测本次新登录的 SESSDATA),再加载 B站 登录页。
  LaunchedEffect(webView) {
    CookieManager.getInstance().removeAllCookies { }
    CookieManager.getInstance().flush()
    webView.loadUrl(BiliLoginUrl)
  }

  DisposableEffect(webView) {
    onDispose {
      webView.destroy()
      CookieManager.getInstance().flush()
    }
  }

  // 轮询 cookie,出现 SESSDATA+bili_jct 即登录成功。
  LaunchedEffect(webView) {
    while (!consumed) {
      delay(CookiePollIntervalMs)
      if (consumed) break
      val cookies = readBiliCookies()
      val sessData = cookies["SESSDATA"]
      val biliJct = cookies["bili_jct"]
      if (!sessData.isNullOrBlank() && !biliJct.isNullOrBlank()) {
        consumed = true
        val buvid3 = cookies["buvid3"]
        val buvid4 = cookies["buvid4"]
        scope.launch {
          sessionStore.saveSession(sessData, biliJct)
          if (!buvid3.isNullOrBlank() || !buvid4.isNullOrBlank()) {
            sessionStore.saveDeviceCookies(buvid3, buvid4)
          }
          runCatching { authRepository.refreshUserProfile() }
          onSuccess()
        }
      }
    }
  }

  AndroidView(
    factory = { webView },
    modifier = modifier.fillMaxSize(),
  )
}

/** 读取 .bilibili.com 与 passport.bilibili.com 两域 cookie 并解析成 map。 */
private fun readBiliCookies(): Map<String, String> {
  val cm = CookieManager.getInstance()
  val raw = buildString {
    cm.getCookie("https://passport.bilibili.com")?.let { append(it); if (!endsWith(";")) append(";") }
    cm.getCookie("https://.bilibili.com")?.let { append(" "); append(it) }
  }
  if (raw.isBlank()) return emptyMap()
  return raw.split(";")
    .mapNotNull { entry ->
      val parts = entry.trim().split("=", limit = 2)
      if (parts.size == 2) parts[0] to parts[1] else null
    }
    .toMap()
}