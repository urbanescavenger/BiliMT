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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
                setResponseHeader("Access-Control-Allow-Origin", "*")
                setResponseHeader(
                  "Access-Control-Allow-Methods",
                  "GET, HEAD, POST, PUT, DELETE, CONNECT, OPTIONS, TRACE, PATCH"
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

  private companion object {
    const val Tag = "YtJsExecutor"
    const val ShellReadyTimeoutMs = 3_000L
  }
}
