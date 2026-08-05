package com.kirin.mt.core.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 *  - JS 里跨源 fetch 会被 CORS 拦，因此网络请求一律由 Kotlin 侧发，
 *    WebView 只做"加载脚本 + eval + 跑 WASM"，不做 HTTP。
 */
class YoutubeJsExecutor(context: Context) {

  private val appContext = context.applicationContext

  /** 已创建的隐藏 WebView；懒创建。 */
  private var webView: WebView? = null

  /**
   * 在隐藏 WebView 里同步执行一段 JS 表达式并取回结果文本。
   *
   * @param script JS 表达式；其结果会被 WebView 序列化（字符串带引号、对象为 null 或 {}）。
   * @return WebView 原始返回文本；执行失败/WebView 不可用时返回 null。
   */
  suspend fun eval(script: String): String? = withContext(Dispatchers.Main) {
    val view = ensureWebView()
    suspendCancellableCoroutine { cont ->
      runCatching {
        view.evaluateJavascript(script) { result ->
          if (cont.isActive) cont.resume(result)
        }
      }.onFailure {
        if (cont.isActive) cont.resume(null)
      }
    }
  }

  private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
    webView?.let { return@withContext it }
    val created = createWebView()
    webView = created
    created
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebView(): WebView {
    return WebView(appContext).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.allowFileAccess = true
      settings.allowContentAccess = true
      // 只做单向 evaluateJavascript，不暴露任何 JavascriptInterface 桥，降低暴露面。
      webViewClient = WebViewClient()
      loadUrl("file:///android_asset/youtube/js_shell.html")
    }
  }
}
