package com.kirin.mt.core.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

  /** 一次采集到的 googlevideo 请求(SABR POST 或 DASH GET;url 含已 transform 的 n + body 含 poToken/ustreamerConfig/formatIds)。 */
  data class SabrCapture(val url: String, val method: String, val bodyB64: String, val status: Int)

  /**
   * 加载 embed 页,采集首个 SABR POST。失败/超时返回 null(绝不抛,不阻塞主路径)。
   * @param timeoutMs 上限(默认 30s);embed 播放器 init 通常 3-8s,watch 页回退更慢。
   */
  suspend fun harvest(videoId: String, timeoutMs: Long = 30_000L): SabrCapture? =
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
      view.loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1&mute=1&playsinline=1&origin=https://www.youtube.com")
      // 轮询 window.__gvCaptures[0](对齐 BotGuard pollState 双解码:evaluateJavascript 对字符串
      // 结果做 JSON 编码,先解内层字符串再解析对象)。alpha.20 只截 SABR POST 致 25s 无捕获——
      // alpha.21 放宽到所有 googlevideo 请求(含 DASH GET),并加页面加载/console 诊断定位 embed 行为。
      // alpha.23:embed 报「错误 153」(config 拒)→ 10s 无捕获则回退 watch 页(无 embed 权限闸)。
      val start = System.currentTimeMillis()
      val deadline = start + 30_000L
      var triedWatch = false
      while (System.currentTimeMillis() < deadline) {
        val raw = evalOn(view, "(window.__gvCaptures && window.__gvCaptures[0]) ? JSON.stringify(window.__gvCaptures[0]) : null")
        val obj = parseCapture(raw)
        if (obj != null) {
          val url = obj["url"]?.jsonPrimitive?.contentOrNull
          val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
          val body = obj["bodyB64"]?.jsonPrimitive?.contentOrNull
          val status = obj["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
          if (!url.isNullOrBlank() && status > 0) {
            val n = extractQuery(url, "n")
            Log.i(Tag, "harvest: captured $method status=$status n=${n ?: "ABSENT"} url=$url bodyB64=${body?.length ?: 0}B")
            return SabrCapture(url, method, body ?: "", status)
          }
        }
        // 10s 内 embed 无捕获 → 回退 watch 页(同 videoId,无 embed 权限闸,播放器同 plasma base.js
        // 做 n-transform)。hook 在 onPageStarted 重新注入,idempotent,导航存活。
        if (!triedWatch && System.currentTimeMillis() - start > 10_000L) {
          Log.i(Tag, "harvest: embed 无捕获 10s → 回退 watch 页")
          view.loadUrl("https://www.youtube.com/watch?v=$videoId")
          triedWatch = true
        }
        delay(200)
      }
      Log.w(Tag, "harvest: timeout (30s 内 embed+watch 均无 googlevideo 请求)")
      return null
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
    // 桌面 UA——alpha.22 真机 embed 报「错误 153 视频播放器配置错误」:embed 播放器自己的 config
    // 请求被拒(mobile UA 嫌疑;FreeTube 桌面 Electron 能播)。harvester 是独立 WebView,UA 不影响
    // 我们 mobile /player 流程,故此处用桌面 UA 让 embed/watch 页播放器正常 init。
    settings.userAgentString = YoutubeConstants.UserAgent
    webViewClient = object : WebViewClient() {
      // onPageStarted 在页面脚本(含播放器 base.js)加载前触发——SABR/GET 在播放器 init 后
      // (数秒)才发,故此处注入的 fetch/XHR wrapper 必先于首个 googlevideo 请求就位。
      // 同时 log 页面导航——alpha.20 真机 25s 无捕获,需确认 embed 页是否真加载/是否被 consent 拦。
      override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        Log.i(Tag, "embed onPageStarted: $url")
        view?.evaluateJavascript(HOOK_JS, null)
      }

      override fun onPageFinished(view: WebView?, url: String?) {
        Log.i(Tag, "embed onPageFinished: $url")
        // dump 页面内容——确认 embed 页真渲染了播放器(不是 consent 墙/error 壳)。title/body/player
        // 元素经 console 路由(被 onConsoleMessage 捕获)。alpha.21 真机:onPageFinished 触发但 25s 零
        // googlevideo 请求 + 零 console → 疑 detached WebView 0 尺寸致播放器 JS 不 init(本版补 measure+layout)。
        view?.evaluateJavascript(
          "try{var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');console.log('PAGE diag title='+document.title+' body='+((document.body&&document.body.innerText)||'NOBODY').slice(0,150)+' player='+!!p+' vp='+(window.innerWidth+'x'+window.innerHeight));}catch(e){console.log('PAGE diag err '+e);}",
          null,
        )
      }

      override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
      ) {
        if (request?.url?.toString()?.contains("googlevideo") == true ||
          request?.url?.toString()?.contains("youtube") == true
        ) {
          Log.w(Tag, "embed onReceivedError: ${request?.url} ${error?.description}")
        }
      }

      override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
      ) {
        Log.w(Tag, "embed onReceivedHttpError: ${request?.url} code=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase}")
      }

      // 不覆盖 shouldInterceptRequest:让播放器的所有请求(/player、base.js、googlevideo SABR POST)
      // 走 WebView 原生 Chromium 栈(真实 TLS/cookie/UA)。采集发生在 JS fetch-hook 层,不拦截原生。
    }
    // 捕获 embed 播放器 console 输出——播放器 init 失败/autoplay 被拒会在 console 报错,
    // 是 alpha.20「无捕获」定位的关键信号。
    webChromeClient = object : WebChromeClient() {
      override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
        Log.i(Tag, "embed console[${msg.messageLevel()}]: ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
        return true
      }
    }
    clearCache(true)
    // 关键:detached WebView 默认 0 尺寸,YouTube 播放器 JS 检测元素/视口尺寸为 0 → 拒绝 init
    // (alpha.21 真机:onPageFinished 触发但零 console 零 googlevideo 请求,即此因)。measure+layout
    // 给真实内部尺寸,player 元素与 window.innerWidth/Height 非 0 → 播放器 init → 发 googlevideo 请求。
    val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
    val h = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
    measure(w, h)
    layout(0, 0, measuredWidth, measuredHeight)
  }

  /** 取 URL query 参数值(首段 ? 之后,不依赖正则)。 */
  private fun extractQuery(url: String, key: String): String? {
    val query = url.substringAfter("?", "")
    return query.split("&").firstNotNullOfOrNull { e ->
      val i = e.indexOf("=")
      if (i < 0) null else if (e.substring(0, i) == key) e.substring(i + 1) else null
    }
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
     * fetch/XHR wrapper——截获**所有**发往 googlevideo.com/videoplayback 的请求(POST=SABR / GET=DASH 段),
     * 记录 {url, method, bodyB64, status} 到 window.__gvCaptures(最多 5 条防灌)。请求照常放行。
     * alpha.20 只截 SABR POST(`sabr=` + POST 过滤)致 25s 无捕获——放宽到全方法 + 全 googlevideo,
     * 定位 embed 到底用 SABR POST 还是 DASH GET(决定 alpha.21 是建 SabrSession 还是直接复用 GET url)。
     * body 可能是 ArrayBuffer/Uint8Array/Blob/string,统一转 base64;GET 段请求 body 空。
     */
    @Suppress("MaxLineLength")
    const val HOOK_JS = """
(function(){
  if(window.__gvHook) return; window.__gvHook=true; window.__gvCaptures=[];
  function b64(buf){ try{ if(!buf) return ''; var bytes=(buf instanceof Uint8Array)?buf:new Uint8Array(buf); var s=''; for(var i=0;i<bytes.length;i++) s+=String.fromCharCode(bytes[i]); return btoa(s); }catch(e){ return ''; } }
  function isGv(url){ return /googlevideo\.com\/videoplayback/.test(url||''); }
  function record(url,method,body,status){ if(window.__gvCaptures.length<5) window.__gvCaptures.push({url:url,method:method||'GET',bodyB64:b64(body),status:status}); }
  var _f=window.fetch;
  window.fetch=function(input,init){
    try{
      var url=(typeof input==='string')?input:((input&&input.url)||'');
      var method=(init&&init.method)||(input&&input.method)||'GET';
      if(isGv(url)){
        var body=init&&init.body;
        var doFetch=function(b){ return _f.call(this,input,init).then(function(r){ record(url,method,b,r.status); return r; }); };
        if(body instanceof Blob){ return body.arrayBuffer().then(function(ab){ return doFetch(new Uint8Array(ab)); }); }
        return doFetch(body);
      }
    }catch(e){}
    return _f.apply(this,arguments);
  };
  var _open=XMLHttpRequest.prototype.open, _send=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(method,url){ this.__gvUrl=url; this.__gvMethod=method; return _open.apply(this,arguments); };
  XMLHttpRequest.prototype.send=function(body){
    if(this.__gvUrl && isGv(this.__gvUrl)){
      var u=this.__gvUrl,m=this.__gvMethod; this.addEventListener('load',function(){ record(u,m,body,this.status); });
    }
    return _send.apply(this,arguments);
  };
})();
"""
  }
}
