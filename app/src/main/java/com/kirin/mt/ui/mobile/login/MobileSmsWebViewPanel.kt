package com.kirin.mt.ui.mobile.login

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kirin.mt.R
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val Tag = "BiliMT:SmsLogin"
private const val BiliLoginUrl = "https://passport.bilibili.com/login"
private const val CookiePollIntervalMs = 1000L

/**
 * 短信登录(移动端唯一登录方式):WebView 托管 B站 登录页,用户在 B站 自己的页面完成
 * 手机号 + 极验滑块 + 短信;轮询 CookieManager 抓 SESSDATA/bili_jct 存进 SessionStore 并
 * 刷新用户资料,成功自动返回。另提供"完成登录"手动兜底按钮(B站"登录"无自动返回时点它)。
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

  fun completeLogin(sessData: String, biliJct: String, buvid3: String?, buvid4: String?) {
    if (consumed) return
    consumed = true
    scope.launch {
      sessionStore.saveSession(sessData, biliJct)
      if (!buvid3.isNullOrBlank() || !buvid4.isNullOrBlank()) {
        sessionStore.saveDeviceCookies(buvid3, buvid4)
      }
      runCatching { authRepository.refreshUserProfile() }
      onSuccess()
    }
  }

  val webView = remember {
    WebView(context).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.javaScriptCanOpenWindowsAutomatically = true
      settings.userAgentString =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
      CookieManager.getInstance().setAcceptCookie(true)
      CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
      webViewClient = object : WebViewClient() {
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
          Log.e(Tag, "webview error: ${error?.description} (${request?.url})")
        }
      }
      webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
          val m = consoleMessage
          Log.d(Tag, "console[${m?.messageLevel()}]: ${m?.message()} @${m?.sourceId()}:${m?.lineNumber()}")
          return true
        }
      }
    }
  }

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

  // 自动轮询:出现 SESSDATA+bili_jct 即登录成功。
  LaunchedEffect(webView) {
    while (!consumed) {
      delay(CookiePollIntervalMs)
      if (consumed) break
      val cookies = readBiliCookies()
      val sessData = cookies["SESSDATA"]
      val biliJct = cookies["bili_jct"]
      if (!sessData.isNullOrBlank() && !biliJct.isNullOrBlank()) {
        completeLogin(sessData, biliJct, cookies["buvid3"], cookies["buvid4"])
      }
    }
  }

  // 手动兜底:B站"登录"无自动返回时,用户点"完成登录"读 cookie 完成。
  fun tryManualFinish() {
    if (consumed) return
    val cookies = readBiliCookies()
    val sessData = cookies["SESSDATA"]
    val biliJct = cookies["bili_jct"]
    if (!sessData.isNullOrBlank() && !biliJct.isNullOrBlank()) {
      completeLogin(sessData, biliJct, cookies["buvid3"], cookies["buvid4"])
    } else {
      Toast.makeText(context, context.getString(R.string.login_sms_not_detected), Toast.LENGTH_SHORT).show()
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    AndroidView(
      factory = { webView },
      modifier = Modifier.fillMaxSize(),
    )
    Row(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .fillMaxWidth()
        .background(Color(0xCC000000))
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      TextButton(onClick = { if (!consumed) onSuccess() }) {
        Text(text = stringResource(R.string.mobile_back), color = Color.White)
      }
      Text(
        text = stringResource(R.string.login_sms_hint_done),
        color = Color.White,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 8.dp),
      )
      TextButton(onClick = { tryManualFinish() }) {
        Text(text = stringResource(R.string.login_sms_done), color = Color.White)
      }
    }
  }
}

/** 读取 passport/www/m.bilibili.com 域 cookie 并解析成 map。 */
private fun readBiliCookies(): Map<String, String> {
  val cm = CookieManager.getInstance()
  val raw = buildString {
    cm.getCookie("https://passport.bilibili.com")?.let { append(it); if (!endsWith(";")) append(";") }
    cm.getCookie("https://www.bilibili.com")?.let { append(" "); append(it); if (!endsWith(";")) append(";") }
    cm.getCookie("https://m.bilibili.com")?.let { append(" "); append(it) }
  }
  if (raw.isBlank()) return emptyMap()
  return raw.split(";")
    .mapNotNull { entry ->
      val parts = entry.trim().split("=", limit = 2)
      if (parts.size == 2) parts[0] to parts[1] else null
    }
    .toMap()
}