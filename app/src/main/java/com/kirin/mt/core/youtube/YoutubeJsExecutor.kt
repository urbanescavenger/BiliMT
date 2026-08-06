package com.kirin.mt.core.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * 隐藏 WebView 封装的 JS 执行引擎，供 YouTube 播放链路执行混淆 JS：
 *  - `n` 参数解密（提取/执行 base.js 里的 transform）
 *  - BotGuard PO token 生成（jnn WASM VM）
 *
 * 为什么用 WebView：Android 无内建 Node/QuickJS 等轻量 JS 引擎，BotGuard 的
 * jnn challenge 需要完整 JS + WebAssembly 运行时；项目不引入 C/C++ so，
 * 隐藏 WebView 是零新增依赖、且 SMS 登录已在用的现成方案。
 *
 * 约束（WebView 硬性要求）：
 *  - 必须创建于主线程（有 Looper），本类公开方法内部先 `withContext(Dispatchers.Main)`。
 *  - 单例生命周期由 [com.kirin.mt.core.app.AppContainer] 持有，懒创建、会话级复用，
 *    不随播放器生命周期销毁，避免反复建/拆重型 WebView。
 *  - BotGuard 宿主页用 loadDataWithBaseURL 加载为 youtube.com 同源基址（对齐 FreeTubeAndroid），
 *    VM 才能通过 origin/页内网络反爬校验产生 minter；页内网络经 shouldInterceptRequest 代理注入
 *    YouTube/Google 头 + CORS。challenge/interpreter/GenerateIT 主请求仍由 Kotlin 侧发。
 */
class YoutubeJsExecutor(context: Context) {

  private val appContext = context.applicationContext

  /** 已创建的隐藏 WebView；懒创建。 */
  private var webView: WebView? = null

  /** bgutils.js 是否已加载进 WebView（PO token 用）。 */
  private var bgUtilsLoaded = false

  /** js_shell.html 是否已加载完成（onPageFinished 置位）；eval 前必须等它，否则 evaluateJavascript 失败。 */
  private var shellReady: CompletableDeferred<Unit>? = null

  /** 解析 fetchViaWebView 响应（window.__webViewResp 双编码）。 */
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /**
   * 在隐藏 WebView 里同步执行一段 JS 表达式并取回结果文本。
   *
   * @param script JS 表达式；其结果会被 WebView 序列化（字符串带引号、对象为 null 或 {}）。
   * @return WebView 原始返回文本；脚本结果正常但为 null/undefined 时也返回 null；
   *         WebView 被销毁导致 `evaluateJavascript` 抛错时，重建 WebView 并重试一次。
   */
  suspend fun eval(script: String): String? = withContext(Dispatchers.Main) {
    // 先确保 WebView 创建（会设置 shellReady），再等 shell 加载完成，最后 eval。
    ensureWebView()
    awaitShellReady()
    val first = evalOn(webView!!, script)
    if (first.isSuccess) return@withContext first.getOrNull()
    // evaluateJavascript 抛错（多为 WebView 被 app 后台销毁）→ 重建后重试一次。
    Log.w(Tag, "eval threw: ${first.exceptionOrNull()?.message}; recreating WebView")
    webView?.destroy()
    webView = null
    bgUtilsLoaded = false
    ensureWebView()
    awaitShellReady()
    val retried = evalOn(webView!!, script)
    if (retried.isSuccess) retried.getOrNull() else {
      Log.w(Tag, "eval retry threw: ${retried.exceptionOrNull()?.message}")
      null
    }
  }

  /** 等 js_shell.html 加载完成（onPageFinished），否则 evaluateJavascript 对未就绪 WebView 失败。最多等 3s。 */
  private suspend fun awaitShellReady() {
    shellReady?.let { withTimeoutOrNull(ShellReadyTimeoutMs) { it.await() } }
  }

  private suspend fun evalOn(view: WebView, script: String): Result<String?> {
    return suspendCancellableCoroutine { cont ->
      runCatching {
        view.evaluateJavascript(script) { result ->
          if (cont.isActive) cont.resume(Result.success(result))
        }
      }.onFailure { e ->
        if (cont.isActive) cont.resume(Result.failure(e))
      }
    }
  }

  /**
   * 把打包好的 bgutils.js（PO token 生成，MIT）整体 eval 进隐藏 WebView，暴露
   * `window.__runSnapshot` / `window.__mint`。会话级只加载一次。
   * @return 是否加载成功。
   */
  suspend fun loadBgUtilsBundle(): Boolean {
    // 已加载过也验证 __runSnapshot 仍在：WebView 可能被重建（app 后台/生命周期）导致
    // window.__runSnapshot 丢失，此时需重置标志重新加载。
    if (bgUtilsLoaded) {
      val check = eval("typeof window.__runSnapshot")
      if (check?.contains("function") == true) return true
      Log.w(Tag, "bgutils __runSnapshot lost (webview recreated?); reload")
      bgUtilsLoaded = false
    }
    val js = withContext(Dispatchers.IO) {
      runCatching {
        appContext.assets.open("youtube/bgutils.js").bufferedReader().use { it.readText() }
      }.getOrNull()
    }
    if (js.isNullOrBlank()) return false
    val result = eval(js)
    val check = eval("typeof window.__runSnapshot")
    Log.i(Tag, "bgutils load result=${result?.take(30)} __runSnapshot typeof=$check")
    if (check?.contains("function") != true) {
      Log.w(Tag, "bgutils __runSnapshot not defined after load (result=$result)")
      return false
    }
    bgUtilsLoaded = true
    return true
  }

  /**
   * 在隐藏 WebView 里发起一个同源 fetch 并取回响应文本。
   *
   * 对齐 FreeTubeAndroid 主 WebView：/player 请求走 WebView 的**原生网络栈(Chromium)**，
   * 带真实浏览器头/cookie/TLS 指纹——这是 OkHttp 直连被拦("The page needs to be reloaded")、
   * FreeTubeAndroid 能过的根因。shouldInterceptRequest 对 /player 返回 null 放行原生。
   *
   * @param url     同源 URL（youtube.com，与宿主页基址同源，无 CORS）。
   * @param method  HTTP 方法（默认 POST）。
   * @param headers 请求头（InnerTube 认证头等；浏览器会叠加自己的 Origin/Sec-Fetch-*/UA）。
   * @param body    POST body（JSON 字符串）。
   * @return 响应 body 文本；网络错误/非 2xx/超时抛 [YoutubeApiException]。
   */
  suspend fun fetchViaWebView(
    url: String,
    method: String = "POST",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
  ): String = withContext(Dispatchers.Main) {
    ensureWebView()
    awaitShellReady()
    eval("window.__webViewResp = null")
    eval(buildFetchScript(url, method, headers, body))
    pollWebViewResponse()
  }

  /** 构造 fetch 脚本：响应存 window.__webViewResp，网络错误存 {status:0,error}。 */
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

  /** 轮询 window.__webViewResp 直到非 null（对齐 BotGuard pollState 双解码）。 */
  private suspend fun pollWebViewResponse(): String {
    val deadline = System.currentTimeMillis() + FetchTimeoutMs
    while (System.currentTimeMillis() < deadline) {
      val raw = eval("window.__webViewResp")
      if (raw != null && raw != "null") {
        // evaluateJavascript 对字符串结果做 JSON 编码(带引号+转义)，先解出内层字符串再解析。
        val inner = runCatching { json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull()
        if (inner.isNullOrBlank()) {
          Log.w(Tag, "fetchViaWebView inner parse failed: $raw")
          throw YoutubeApiException(0, "", "fetchViaWebView invalid response")
        }
        val obj = runCatching { json.parseToJsonElement(inner).jsonObject }.getOrNull()
        if (obj == null) {
          Log.w(Tag, "fetchViaWebView state parse failed: $inner")
          throw YoutubeApiException(0, "", "fetchViaWebView invalid response")
        }
        val status = obj["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val respBody = obj["body"]?.jsonPrimitive?.contentOrNull
        val error = obj["error"]?.jsonPrimitive?.contentOrNull
        if (status == 0) {
          Log.w(Tag, "fetchViaWebView network error: $error")
          throw YoutubeApiException(0, "", "fetchViaWebView network error: $error")
        }
        if (status !in 200..299) {
          Log.w(Tag, "fetchViaWebView HTTP $status body=${respBody?.take(200)}")
          throw YoutubeApiException(status, respBody ?: "", "fetchViaWebView HTTP $status")
        }
        Log.i(Tag, "fetchViaWebView ok status=$status body=${respBody?.length ?: 0}B")
        return respBody ?: ""
      }
      delay(FetchPollIntervalMs)
    }
    Log.w(Tag, "fetchViaWebView timeout")
    throw YoutubeApiException(0, "", "fetchViaWebView timeout")
  }

  private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
    webView?.let { return@withContext it }
    val created = createWebView()
    webView = created
    created
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebView(): WebView {
    val deferred = CompletableDeferred<Unit>()
    shellReady = deferred
    return WebView(appContext).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.allowFileAccess = true
      settings.allowContentAccess = true
      @Suppress("DEPRECATION")
      settings.allowUniversalAccessFromFileURLs = true
      // 关键:BotGuard 的 minter(webPoSignalOutput[0])被反爬环境检测门控。
      // alpha.19 已证仅设桌面 UA + skipPrivacyBuffer 无效——真正卡住的是 document 上下文。
      // FreeTubeAndroid 用 loadDataWithBaseURL("https://www.youtube.com/", …) 让宿主页的
      // document origin = youtube.com(真浏览器页面环境),VM 的 origin/页内网络探测才能通过 → 产生 minter。
      // 这里同样把宿主页从 file:// 换成 youtube.com 同源基址(仍只承载 evaluateJavascript,不显示内容)。
      settings.userAgentString = YoutubeConstants.UserAgent
      webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
          deferred.complete(Unit)
        }
        // 页内网络代理(对齐 FreeTube BotGuardWebView.shouldInterceptRequest):
        // VM 若在 snapshot 里做页内 fetch/XHR 探测,走这里注入 YouTube/Google 头 + CORS,
        // 避免因跨源被拦而把环境误判为"非真浏览器"。GET/HEAD 为主;POST 体由 Kotlin 侧发,不受影响。
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
          val urlStr = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
          if (urlStr.startsWith("data:") || urlStr.startsWith("file:")) {
            return super.shouldInterceptRequest(view, request)
          }
          // /player 走 WebView 原生网络栈(Chromium)，对齐 FreeTubeAndroid 主 WebView。
          // 返回 null 让 WebView 用真实浏览器上下文直接发请求(带真实头/cookie/TLS 指纹)，
          // 这是 OkHttp 直连被拦("The page needs to be reloaded")、FreeTubeAndroid 能过的根因。
          if (urlStr.startsWith("https://www.youtube.com/youtubei/v1/player")) {
            return null
          }
          return try {
            with(URL(urlStr).openConnection() as HttpURLConnection) {
              requestMethod = request.method
              request.requestHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
              when {
                urlStr.startsWith("https://www.youtube.com/youtubei/") -> {
                  setRequestProperty("Referer", "https://www.youtube.com/")
                  setRequestProperty("Origin", "https://www.youtube.com")
                  setRequestProperty("Sec-Fetch-Site", "same-origin")
                  setRequestProperty("Sec-Fetch-Mode", "same-origin")
                  setRequestProperty("X-Youtube-Bootstrap-Logged-In", "false")
                }
                urlStr.startsWith("https://www.google.com/js/") -> {
                  setRequestProperty("referer", "https://www.google.com/")
                  setRequestProperty("origin", "https://www.google.com")
                  setRequestProperty("Sec-Fetch-Dest", "script")
                  setRequestProperty("Sec-Fetch-Site", "cross-site")
                  setRequestProperty("Accept-Language", "*")
                }
              }
              WebResourceResponse(contentType, contentEncoding, inputStream).apply {
                setResponseHeaders(
                  mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, HEAD, POST, PUT, DELETE, CONNECT, OPTIONS, TRACE, PATCH"
                  )
                )
              }
            }
          } catch (e: Exception) {
            Log.w(Tag, "intercept failed: ${e.message}")
            super.shouldInterceptRequest(view, request)
          }
        }
      }
      clearCache(true)
      // 宿主页改为 youtube.com 同源基址(读 assets 里的 html,经 loadDataWithBaseURL 注入)。
      val shellHtml = runCatching {
        appContext.assets.open("youtube/js_shell.html").bufferedReader().use { it.readText() }
      }.getOrNull() ?: "<!DOCTYPE html><html><body></body></html>"
      loadDataWithBaseURL(YoutubeConstants.Origin, shellHtml, "text/html", "utf-8", null)
    }
  }

  /** JS 字符串字面量转义（嵌入 fetch 脚本用）。 */
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
    const val Tag = "YtJsExecutor"
    const val ShellReadyTimeoutMs = 3_000L
    const val FetchTimeoutMs = 20_000L
    const val FetchPollIntervalMs = 100L
  }
}
