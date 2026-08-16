package com.kirin.mt.core.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * 真实浏览器会话 WebView(对齐 FreeTubeAndroid 主 WebView)。
 *
 * FreeTubeAndroid 的 /player 走主 WebView——真实可见、全屏、长期存活的浏览器页面,一直在浏览
 * YouTube、累积真实 cookie、建立真实 TLS 会话。我们此前用隐藏壳(空白 js_shell.html)发合成 fetch,
 * 被 BotGuard 判"非真浏览器"累积怀疑分 → 打 ~36 次 /player 后服务端开始 LOGIN_REQUIRED(§6.7 row 65)。
 *
 * 本类:一个长期存活的 WebView 加载真实 `https://www.youtube.com/` 页面,提供:
 *  - [fetchViaWebView]:在真实页面里 eval 同源 fetch(走真实浏览器上下文/cookie/TLS)。
 *  - [readVisitorData]:读真实页面的 VISITOR_INFO1_LIVE cookie 作 visitorData(方案 A:全链路用
 *    真实会话的 visitorData 铸 token + 发 /player,对齐 FreeTubeAndroid)。
 *  - [readCookies]:读真实页面的完整 cookie 集(作 /player 与 SABR 的会话 cookie)。
 *
 * 与 [YoutubeJsExecutor](BotGuard 铸 token 的隐藏壳)分离——FreeTubeAndroid 也是 BotGuardWebView
 * (隐藏壳)与主 WebView(真实页)分开。本类只承载 /player 的真实浏览会话,不复用 jsExecutor 单例
 * (导航会破坏其 bgutils 上下文)。
 */
class YoutubeBrowserSession(context: Context) {
  private val appContext = context.applicationContext
  private var webView: WebView? = null
  private var ready: CompletableDeferred<Unit>? = null
  /** alpha.89:主帧加载失败标记(onReceivedError / 错误页 onPageFinished 置位)。 */
  @Volatile private var loadFailed = false
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /** 确保真实 YouTube 页面已加载(懒创建,长期存活)。 */
  suspend fun ensureLoaded() = withContext(Dispatchers.Main) {
    // alpha.89:WebView 是长期单例,但 youtube.com 加载失败时会停在 chrome-error://chromewebdata
    // (origin=null)——onPageFinished 对错误页也触发,旧实现据此判"已加载"→ fetch 从 origin=null 发
    // → CORS 预检被拒 → 所有 YouTube 视频都播不了(真机 alpha.88 "现在都不能播放")。此处每次校验
    // 当前 url 确在 youtube.com;不在则销毁重建(破除卡死单例),最多重试 [LoadRetries] 次。
    if (webView != null && isOnYoutube(webView?.url)) return@withContext
    if (webView != null) {
      Log.w(Tag, "browser session not on youtube.com (url=${webView?.url}) → destroy + recreate (stuck-on-error-page recovery)")
      webView?.destroy()
      webView = null
    }
    repeat(LoadRetries) { attempt ->
      val deferred = CompletableDeferred<Unit>()
      ready = deferred
      loadFailed = false
      val created = createWebView(deferred)
      webView = created
      withTimeoutOrNull(LoadTimeoutMs) { deferred.await() }
      val loadedUrl = webView?.url
      Log.i(Tag, "browser session load attempt=${attempt + 1}/$LoadRetries url=$loadedUrl failed=$loadFailed")
      if (isOnYoutube(loadedUrl)) return@withContext
      // 仍在错误页/超时 → 销毁重建重试(非最后一次)
      if (attempt < LoadRetries - 1) {
        webView?.destroy()
        webView = null
      }
    }
    // 全部重试后仍不在 youtube.com → 留下最后一个 webView(可能停在错误页),fetchViaWebView 会拒绝
    // 从 origin=null 发 fetch 并抛错,触发上层 postPlayer 回退 OkHttp(安全网)。
  }

  /** 当前 WebView 是否停在真实 youtube.com 页(非错误页/空页)。 */
  private fun isOnYoutube(url: String?): Boolean =
    url != null && url.startsWith("https://www.youtube.com")

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebView(deferred: CompletableDeferred<Unit>): WebView {
    return WebView(appContext).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.allowFileAccess = true
      settings.allowContentAccess = true
      @Suppress("DEPRECATION")
      settings.allowUniversalAccessFromFileURLs = true
      settings.userAgentString = YoutubeConstants.MobileUserAgent
      webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
          // alpha.89:错误页(chrome-error://chromewebdata)也会触发 onPageFinished,但此时 origin=null
          // → 同源 fetch 变跨源 → CORS 预检被拒。只在真正落到 youtube.com 时完成 deferred;否则标记
          // loadFailed,让 ensureLoaded 超时后销毁重建重试。
          if (!isOnYoutube(url)) {
            loadFailed = true
            return
          }
          if (deferred.isActive) deferred.complete(Unit)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
          // 主帧加载失败(youtube.com 拉不下来)→ 标记,不完成 deferred(让 ensureLoaded 重建重试)。
          if (request?.isForMainFrame == true) {
            loadFailed = true
            Log.w(Tag, "browser session main-frame load error: ${error?.errorCode} ${error?.description}")
          }
        }
      }
      // 真实 YouTube 页面 → 真实文档上下文 + 真实 cookie + 真实 TLS 会话(对齐 FreeTubeAndroid 主 WebView)。
      loadUrl("https://www.youtube.com/")
    }
  }

  /** 读真实页面的 VISITOR_INFO1_LIVE cookie 作 visitorData(方案 A)。 */
  fun readVisitorData(): String? {
    val cookies = runCatching { CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull()
    val v = cookies?.split(";")?.map { it.trim() }
      ?.firstOrNull { it.startsWith("VISITOR_INFO1_LIVE=") }
      ?.substringAfter("=")
    Log.i(Tag, "browser session visitorData=${v?.take(24) ?: "NONE"}")
    return v
  }

  /** 读真实页面的完整 cookie 集(作 /player 与 SABR 的会话 cookie)。 */
  fun readCookies(): String? {
    val cookies = runCatching { CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull()
    Log.i(Tag, "browser session cookies=${cookies?.take(120) ?: "NONE"}")
    return cookies
  }

  /** 在真实页面里发同源 fetch 并取回响应文本(对齐 FreeTubeAndroid 主 WebView 的 /player)。 */
  suspend fun fetchViaWebView(
    url: String,
    method: String = "POST",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
  ): String = withContext(Dispatchers.Main) {
    ensureLoaded()
    // alpha.89:fetch 必须从 https://www.youtube.com 同源页发(InnerTubeBase=www.youtube.com/youtubei)。
    // 若 WebView 仍停在错误页(origin=null),fetch 会 CORS 失败——直接抛错让上层 postPlayer 回退 OkHttp,
    // 不要白等 20s fetch 超时。
    val curUrl = webView?.url
    if (!isOnYoutube(curUrl)) {
      Log.w(Tag, "fetchViaWebView: not on youtube.com (url=$curUrl) → refuse fetch (would CORS-fail) → caller falls back to OkHttp")
      throw YoutubeApiException(0, "", "browser session not on youtube.com (url=$curUrl); fetch would CORS-fail")
    }
    // Cookie 是 fetch 的 forbidden header,浏览器静默忽略 → 真实页面的 cookie 由原生网络栈自动携带
    // (对齐 FreeTubeAndroid 主 WebView 的真实浏览器 cookie)。显式 Cookie 头剥掉。
    val fetchHeaders = headers - "Cookie"
    eval("window.__webViewResp = null")
    eval(buildFetchScript(url, method, fetchHeaders, body))
    pollWebViewResponse()
  }

  private suspend fun eval(script: String): String? = withContext(Dispatchers.Main) {
    val view = webView ?: return@withContext null
    suspendCancellableCoroutine { cont ->
      runCatching {
        view.evaluateJavascript(script) { result ->
          if (cont.isActive) cont.resume(Result.success(result))
        }
      }.onFailure { e ->
        if (cont.isActive) cont.resume(Result.failure(e))
      }
    }.getOrNull()
  }

  private fun buildFetchScript(url: String, method: String, headers: Map<String, String>, body: String?): String {
    val headersJs = headers.entries.joinToString(",") { (k, v) -> "${jsonString(k)}:${jsonString(v)}" }
    val bodyJs = body?.let { jsonString(it) } ?: "null"
    return "window.__webViewResp = null; (async () => { try { " +
      "const resp = await fetch(${jsonString(url)}, { method: ${jsonString(method)}, " +
      "headers: { $headersJs }, body: $bodyJs }); " +
      "const text = await resp.text(); " +
      "window.__webViewResp = JSON.stringify({ status: resp.status, ok: resp.ok, body: text }); " +
      "} catch (e) { " +
      "window.__webViewResp = JSON.stringify({ status: 0, ok: false, error: String(e && e.stack || e) }); " +
      "} })();"
  }

  private suspend fun pollWebViewResponse(): String {
    val deadline = System.currentTimeMillis() + FetchTimeoutMs
    while (System.currentTimeMillis() < deadline) {
      val raw = eval("window.__webViewResp")
      if (raw != null && raw != "null") {
        val inner = runCatching { json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull()
        if (inner.isNullOrBlank()) {
          Log.w(Tag, "browser fetch inner parse failed: $raw")
          throw YoutubeApiException(0, "", "browser fetch invalid response")
        }
        val obj = runCatching { json.parseToJsonElement(inner).jsonObject }.getOrNull()
        if (obj == null) {
          Log.w(Tag, "browser fetch state parse failed: $inner")
          throw YoutubeApiException(0, "", "browser fetch invalid response")
        }
        val status = obj["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val respBody = obj["body"]?.jsonPrimitive?.contentOrNull
        val error = obj["error"]?.jsonPrimitive?.contentOrNull
        if (status == 0) {
          Log.w(Tag, "browser fetch network error: $error")
          throw YoutubeApiException(0, "", "browser fetch network error: $error")
        }
        if (status !in 200..299) {
          Log.w(Tag, "browser fetch HTTP $status body=${respBody?.take(200)}")
          throw YoutubeApiException(status, respBody ?: "", "browser fetch HTTP $status")
        }
        Log.i(Tag, "browser fetch ok status=$status body=${respBody?.length ?: 0}B")
        return respBody ?: ""
      }
      delay(FetchPollIntervalMs)
    }
    Log.w(Tag, "browser fetch timeout")
    throw YoutubeApiException(0, "", "browser fetch timeout")
  }

  private fun jsonString(input: String): String {
    val escaped = input
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    return "\"$escaped\""
  }

  private companion object {
    const val Tag = "YtBrowserSession"
    const val LoadTimeoutMs = 15_000L
    const val LoadRetries = 2
    const val FetchTimeoutMs = 20_000L
    const val FetchPollIntervalMs = 100L
  }
}
