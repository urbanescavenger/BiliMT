package com.kirin.mt.core.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * SABR n-decrypt 的 WebView 嵌入采集器(plasma 兜底方案)。
 *
 * 背景:plasma 播放器把 n/sig transform 移进 WASM,[YoutubeNDecryptor] 正则方案结构性
 * 失效(§6.7 row 42/43)→ SABR URL 带 `n` 未解 → googlevideo 403。逆向 WASM 不可行,
 * 故让 Android **WebView 浏览器引擎**(原生跑 WASM+WebCrypto)替我们做 n-transform:
 *  - 加载 YouTube embed 页 `https://www.youtube.com/embed/<id>`,embed 播放器(同 plasma base.js)
 *    会自己 decipher(含 n-transform)并发起 SABR POST。
 *  - 在 [onPageStarted] 注入 fetch/XHR wrapper,截获首个发往 `googlevideo.com/videoplayback` 的
 *    POST(URL 含 `sabr`),采集 {url(已 transform 的 n), bodyB64, status}。
 *  - 让请求照常放行(返回真实 Response),播放器拿到 200 即证「浏览器 transform 的 n 被服务端接受」。
 *
 * 独立 WebView 实例(不复用 [YoutubeJsExecutor] 单例——导航会破坏其 bgutils 上下文,影响
 * BotGuard/N-S 解密/InnerTube /player)。本类每次 [harvest] 建一个新 WebView,用完销毁。
 *
 * 采集到的 (URL+body) 后续(alpha.21)用于构建 [com.kirin.mt.core.youtube.sabr.SabrSession],
 * 由 [com.kirin.mt.core.youtube.sabr.SabrClient] 驱动 init/segment 请求,接 Media3。
 */
class YoutubeSabrHarvester(
  context: Context,
  private val innerTubeClient: InnerTubeClient,
) {
  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /** 一次采集到的 SABR POST(url 含已 transform 的 n + body 含 poToken/ustreamerConfig/formatIds)。 */
  data class SabrCapture(val url: String, val bodyB64: String, val status: Int)

  /**
   * 加载 embed 页,采集首个 SABR POST。失败/超时返回 null(绝不抛,不阻塞主路径)。
   * @param timeoutMs 上限(默认 25s);embed 播放器 init 通常 3-8s 内发首个 SABR POST。
   */
  suspend fun harvest(videoId: String, timeoutMs: Long = 25_000L): SabrCapture? =
    withContext(Dispatchers.Main) {
      runCatching { withTimeoutOrNull(timeoutMs) { harvestImpl(videoId) } }
        .onFailure { Log.w(Tag, "harvest failed: ${it.message ?: it::class.simpleName}") }
        .getOrNull()
    }

  private suspend fun harvestImpl(videoId: String): SabrCapture? {
    val view = createWebView()
    try {
      // seed cookies:embed 播放器在 WebView 里发 /youtubei/v1/player 需要 VISITOR_INFO1_LIVE
      // 等会话 cookie(对齐 YoutubeJsExecutor.fetchViaWebView 的 CookieManager 写法)。
      val cookies = innerTubeClient.currentSessionCookies()
      runCatching {
        CookieManager.getInstance().setCookie(YoutubeConstants.Origin, cookies)
        CookieManager.getInstance().flush()
      }.onFailure { Log.w(Tag, "seed cookies failed: ${it.message}") }
      Log.i(Tag, "harvest: load embed videoId=$videoId cookie=${cookies.length}B")
      view.loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1&mute=1&playsinline=1")
      // 轮询 window.__sabrCaptures[0](对齐 BotGuard pollState 双解码:evaluateJavascript 对字符串
      // 结果做 JSON 编码,先解内层字符串再解析对象)。
      val deadline = System.currentTimeMillis() + 25_000L
      while (System.currentTimeMillis() < deadline) {
        val raw = evalOn(view, "(window.__sabrCaptures && window.__sabrCaptures[0]) ? JSON.stringify(window.__sabrCaptures[0]) : null")
        // evaluateJavascript 对 JS 字符串结果做 JSON 编码(加引号)→ 双解码;但部分 WebView 版本可能
        // 直返对象。两种都兼容:先试当对象直解,失败再按「引号字符串」剥一层。
        val obj = parseCapture(raw)
        if (obj != null) {
          val url = obj["url"]?.jsonPrimitive?.contentOrNull
          val body = obj["bodyB64"]?.jsonPrimitive?.contentOrNull
          val status = obj["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
          if (!url.isNullOrBlank() && status > 0) {
            Log.i(Tag, "harvest: captured status=$status url=${url.take(160)}... bodyB64=${body?.length ?: 0}B")
            return SabrCapture(url, body ?: "", status)
          }
        }
        delay(200)
      }
      Log.w(Tag, "harvest: timeout (no SABR POST captured in 25s)")
      null
    } finally {
      view.destroy()
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebView(): WebView = WebView(appContext).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = true
    settings.allowContentAccess = true
    @Suppress("DEPRECATION")
    settings.allowUniversalAccessFromFileURLs = true
    settings.mediaPlaybackRequiresUserGesture = false // 允许 muted autoplay,触发播放器 → SABR POST
    settings.userAgentString = YoutubeConstants.MobileUserAgent
    webViewClient = object : WebViewClient() {
      // onPageStarted 在页面脚本(含播放器 base.js)加载前触发——SABR POST 在播放器 init 后
      // (数秒)才发,故此处注入的 fetch/XHR wrapper 必先于首个 SABR POST 就位。evaluateJavascript
      // 跑在页面主世界,wrap window.fetch 会真正截获播放器的 fetch 调用。
      override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        view?.evaluateJavascript(HOOK_JS, null)
      }
      // 不覆盖 shouldInterceptRequest:让播放器的所有请求(/player、base.js、googlevideo SABR POST)
      // 走 WebView 原生 Chromium 栈(真实 TLS/cookie/UA)。采集发生在 JS fetch-hook 层,不拦截原生。
    }
    clearCache(true)
  }

  private suspend fun evalOn(view: WebView, script: String): String? =
    suspendCancellableCoroutine { cont ->
      runCatching {
        view.evaluateJavascript(script) { result -> if (cont.isActive) cont.resume(result) }
      }.onFailure { e -> if (cont.isActive) cont.resume(null) }
    }

  /**
   * 解析 evaluateJavascript 回传的 capture 对象——兼容两种 WebView 行为:
   *  1. 字符串结果被 JSON 编码(加引号):raw=`"{\"url\":...}"` → 剥一层引号再解对象。
   *  2. 直接回传对象:raw=`{"url":...}` → 直接解对象。
   * null/空/"null" 返回 null。
   */
  private fun parseCapture(raw: String?): JsonObject? {
    if (raw.isNullOrBlank() || raw == "null") return null
    // 路径 2:直解对象。
    runCatching { return json.parseToJsonElement(raw).jsonObject }.getOrNull()
    // 路径 1:剥引号。
    val inner = runCatching { json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull()
    if (inner.isNullOrBlank() || inner == "null") return null
    return runCatching { json.parseToJsonElement(inner).jsonObject }.getOrNull()
  }

  private companion object {
    const val Tag = "YtSabrHarvest"

    /**
     * fetch/XHR wrapper——截获发往 googlevideo 的 SABR POST,记录 {url, bodyB64, status} 到
     * window.__sabrCaptures(最多 3 条防灌)。请求照常放行(返回真实 Response),播放器正常播放。
     * body 可能是 ArrayBuffer/Uint8Array/Blob/string,统一转 base64;ReadableStream 则记空体
     * (仍得 url+status,足够证明浏览器 transform 的 n 被接受)。
     */
    @Suppress("MaxLineLength")
    const val HOOK_JS = """
(function(){
  if(window.__sabrHook) return; window.__sabrHook=true; window.__sabrCaptures=[];
  function b64(buf){ try{ var bytes=(buf instanceof Uint8Array)?buf:new Uint8Array(buf); var s=''; for(var i=0;i<bytes.length;i++) s+=String.fromCharCode(bytes[i]); return btoa(s); }catch(e){ return ''; } }
  function isSabr(url,method){ return /googlevideo\.com\/videoplayback/.test(url) && /post/i.test(method||'') && /(^|[?&])sabr=/.test(url); }
  function record(url,method,body,status){ if(window.__sabrCaptures.length<3) window.__sabrCaptures.push({url:url,method:method,bodyB64:body?b64(body):'',status:status}); }
  var _f=window.fetch;
  window.fetch=function(input,init){
    try{
      var url=(typeof input==='string')?input:((input&&input.url)||'');
      var method=(init&&init.method)||(input&&input.method)||'GET';
      if(isSabr(url,method)){
        var body=init&&init.body;
        var doFetch=function(b){ return _f.call(this,input,init).then(function(r){ record(url,method,b,r.status); return r; }); };
        if(body instanceof Blob){ return body.arrayBuffer().then(function(ab){ return doFetch(new Uint8Array(ab)); }); }
        return doFetch(body);
      }
    }catch(e){}
    return _f.apply(this,arguments);
  };
  var _open=XMLHttpRequest.prototype.open, _send=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(method,url){ this.__sUrl=url; this.__sMethod=method; return _open.apply(this,arguments); };
  XMLHttpRequest.prototype.send=function(body){
    if(this.__sUrl && isSabr(this.__sUrl,this.__sMethod)){
      var u=this.__sUrl,m=this.__sMethod; this.addEventListener('load',function(){ record(u,m,body,this.status); });
    }
    return _send.apply(this,arguments);
  };
})();
"""
  }
}
