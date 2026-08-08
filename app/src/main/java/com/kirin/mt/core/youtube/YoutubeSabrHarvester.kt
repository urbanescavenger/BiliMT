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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * SABR n-decrypt 的 WebView 嵌入采集器(plasma 兜底方案)。
 *
 * 背景:plasma 播放器把 n/sig transform 移进 WASM,[YoutubeNDecryptor] 正则方案结构性
 * 失效(§6.7 row 42/43)→ SABR URL 带 `n` 未解 → googlevideo 403。逆向 WASM 不可行,
 * 故让 Android **WebView 浏览器引擎**(原生跑 WASM+WebCrypto)替我们做 n-transform:
 *  - 加载 YouTube watch 页 `https://www.youtube.com/watch?v=<id>`(alpha.23 起 watch 回退捕获;
 *    embed 页 Error 153 config 拒不可解,已删),watch 播放器(同 plasma base.js)会自己
 *    decipher(含 n-transform)并发起 SABR POST。
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
   * 加载 watch 页采集首个 SABR POST(alpha.49 起 watch 优先;embed Error 153 config 拒不可解,已删)。
   * 失败/超时返回 null(绝不抛,不阻塞主路径)。
   * @param timeoutMs 上限(默认 30s);watch 页 SPA 播放器 init 通常 4-8s。
   */
  /**
   * alpha.48/49(轮换):支持 `startMs` 锚定——watch 页 URL 加 `&t=<秒>` 让浏览器播放器**从该位置起播**,
   * 其首发 SABR POST 锚定在 startMs。服务端会话窗口 = 锚点..锚点+60s(见 docs row 72,锚点服务端侧,
   * 由浏览器 POST 时播放位置定),故旋转到 mid-playhead 必须先让浏览器锚定在播放头,否则新会话窗口仍
   * 从 0 起算 → 请求 playhead>60s 被拒(alpha.47 session2 死因)。默认 startMs=0(从头播,原行为)。
   */
  suspend fun harvest(videoId: String, startMs: Long = 0L, timeoutMs: Long = 30_000L): SabrCapture? =
    withContext(Dispatchers.Main) {
      runCatching { withTimeoutOrNull(timeoutMs) { harvestImpl(videoId, startMs) } }
        .onFailure { Log.w(Tag, "harvest failed: ${it.message ?: it::class.simpleName}") }
        .getOrNull()
    }

  private suspend fun harvestImpl(videoId: String, startMs: Long = 0L): SabrCapture? {
    val view = createWebView()
    try {
      // seed cookies:watch 播放器在 WebView 里发 /youtubei/v1/player 需要 VISITOR_INFO1_LIVE
      // 等会话 cookie(对齐 YoutubeJsExecutor.fetchViaWebView 的 CookieManager 写法)。
      val cookies = innerTubeClient.currentSessionCookies()
      runCatching {
        CookieManager.getInstance().setCookie(YoutubeConstants.Origin, cookies)
        CookieManager.getInstance().flush()
      }.onFailure { Log.w(Tag, "seed cookies failed: ${it.message}") }
      // alpha.49(顺序反转):watch 页唯一稳定捕获源(embed Error 153 config 拒不可解,已删)。
      // 锚定用 `t=<秒>`(SPA 起播位置参数;startMs=0 首播不锚定)。
      val watchT = if (startMs > 0) "&t=${startMs / 1000}" else ""
      YoutubeLoadProgress.emit(YoutubeLoadStep.HarvestWatch)
      Log.i(Tag, "harvest: load watch videoId=$videoId startMs=$startMs${watchT} cookie=${cookies.length}B")
      view.loadUrl("https://www.youtube.com/watch?v=$videoId&autoplay=1&mute=1$watchT")
      // 轮询 window.__gvCaptures[0](对齐 BotGuard pollState 双解码:evaluateJavascript 对字符串
      // 结果做 JSON 编码,先解内层字符串再解析对象)。alpha.20 只截 SABR POST 致 25s 无捕获——
      // alpha.21 放宽到所有 googlevideo 请求(含 DASH GET),并加页面加载/console 诊断定位行为。
      // alpha.23:embed 报「错误 153」(config 拒不可解)→ 主源定为 watch 页(无 embed 权限闸)。
      val start = System.currentTimeMillis()
      val deadline = start + 30_000L
      var lastDiag = start
      var lastCaptureDump = start
      while (System.currentTimeMillis() < deadline) {
        // alpha.47:读全数组并遍历,不再只读 [0]——首条无效(status=0/url 空)不再挡住后续 SABR POST。
        val raw = evalOn(view, "(window.__gvCaptures && window.__gvCaptures.length) ? JSON.stringify(window.__gvCaptures) : null")
        val arr = parseCaptureArray(raw)
        if (arr != null) {
          for (obj in arr) {
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
          // alpha.47 诊断:全数组无有效项——每 ~3s dump 看 hook 到底记了什么、status 是否全 0。
          if (System.currentTimeMillis() - lastCaptureDump > 3_000L) {
            lastCaptureDump = System.currentTimeMillis()
            val dump = arr.joinToString(" | ") { c ->
              val u = c["url"]?.jsonPrimitive?.contentOrNull
              val m = c["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
              val s = c["status"]?.jsonPrimitive?.contentOrNull ?: "?"
              val b = c["bodyB64"]?.jsonPrimitive?.contentOrNull?.length ?: 0
              "$m s=$s u=${u?.take(60) ?: "BLANK"} b=$b"
            }
            Log.w(Tag, "harvest: no valid capture (${arr.size}): $dump")
          }
        }
        // alpha.43:周期性 PAGE diag(每 ~3s)——watch 页 SPA 播放器 init 在 onPageFinished 后数秒,
        // 周期 dump 看 player false→true/videoSrc 出现/captures 增长演进。非 suspend evaluateJavascript
        // (经 console 路由),fire-and-forget 不阻塞 capture 轮询。
        if (System.currentTimeMillis() - lastDiag > 3_000L) {
          lastDiag = System.currentTimeMillis()
          view.evaluateJavascript(PAGE_DIAG_JS, null)
        }
        delay(200)
      }
      Log.w(Tag, "harvest: timeout (30s 内 watch 无 googlevideo 请求)")
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
    // 桌面 UA——alpha.22 真机 embed 报「错误 153 视频播放器配置错误」:播放器自己的 config
    // 请求被拒(mobile UA 嫌疑;FreeTube 桌面 Electron 能播)。harvester 是独立 WebView,UA 不影响
    // 我们 mobile /player 流程,故此处用桌面 UA 让 watch 页播放器正常 init。
    settings.userAgentString = YoutubeConstants.UserAgent
    webViewClient = object : WebViewClient() {
      // onPageStarted 在页面脚本(含播放器 base.js)加载前触发——SABR/GET 在播放器 init 后
      // (数秒)才发,故此处注入的 fetch/XHR wrapper 必先于首个 googlevideo 请求就位。
      // 同时 log 页面导航——alpha.20 真机 25s 无捕获,需确认页是否真加载/是否被 consent 拦。
      override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        Log.i(Tag, "harvest onPageStarted: $url")
        view?.evaluateJavascript(HOOK_JS, null)
      }

      override fun onPageFinished(view: WebView?, url: String?) {
        Log.i(Tag, "harvest onPageFinished: $url")
        // dump 页面内容——确认页真渲染了播放器(不是 consent 墙/error 壳)。title/body/player
        // 元素/video src/captures 经 console 路由(被 onConsoleMessage 捕获)。alpha.21 真机:onPageFinished
        // 触发但 25s 零 googlevideo 请求 + 零 console → 疑 detached WebView 0 尺寸致播放器 JS 不 init(本版补 measure+layout)。
        view?.evaluateJavascript(PAGE_DIAG_JS, null)
      }

      override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
      ) {
        if (request?.url?.toString()?.contains("googlevideo") == true ||
          request?.url?.toString()?.contains("youtube") == true
        ) {
          Log.w(Tag, "harvest onReceivedError: ${request?.url} ${error?.description}")
        }
      }

      override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
      ) {
        Log.w(Tag, "harvest onReceivedHttpError: ${request?.url} code=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase}")
      }

      // alpha.43:shouldInterceptRequest 只读记录所有 googlevideo 请求的 itag/sabr(method+url),
      // return null 放行原生 Chromium 栈(真实 TLS/cookie/UA)——progressive GET 经 media stack 不经 fetch
      // hook(alpha.42 watch 页 6 次 itag=18 403 由 onReceivedHttpError 才看到),此层补全 200 的 progressive
      // 也结构化记录,确认 watch 页选 progressive(itag 18 sabr=false)而非 SABR POST。
      override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        if (url.contains("googlevideo.com/videoplayback")) {
          val itag = extractQuery(url, "itag") ?: "?"
          val sabr = if (url.contains("sabr=")) "true" else "false"
          Log.i(Tag, "gv req method=${request.method} itag=$itag sabr=$sabr")
        }
        return null
      }
    }
    // 捕获 watch 播放器 console 输出——播放器 init 失败/autoplay 被拒会在 console 报错,
    // 是 alpha.20「无捕获」定位的关键信号。
    webChromeClient = object : WebChromeClient() {
      override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
        Log.i(Tag, "harvest console[${msg.messageLevel()}]: ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
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
   * 解析 evaluateJavascript 回传的 capture 数组——兼容两种 WebView 行为:
   *  1. 字符串结果被 JSON 编码(加引号):raw=`"[{\"url\":...}]"` → 剥一层引号再解数组。
   *  2. 直接回传数组:raw=`[{"url":...}]` → 直接解数组。
   * null/空/"null" 返回 null。
   */
  private fun parseCaptureArray(raw: String?): List<JsonObject>? {
    if (raw.isNullOrBlank() || raw == "null") return null
    // 路径 2:直解数组。
    runCatching {
      val el = json.parseToJsonElement(raw)
      if (el is JsonArray) return el.mapNotNull { it as? JsonObject }
    }.getOrNull()
    // 路径 1:剥引号后解数组。
    val inner = runCatching { json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull()
    if (inner.isNullOrBlank() || inner == "null") return null
    return runCatching {
      val el = json.parseToJsonElement(inner)
      if (el is JsonArray) el.mapNotNull { it as? JsonObject } else null
    }.getOrNull()
  }

  private companion object {
    const val Tag = "YtSabrHarvest"

    /**
     * 页面状态诊断脚本——dump player 元素 present/viewport 尺寸/<video> src/captures 条数,
     * 经 console.log 路由到 [onConsoleMessage]。onPageFinished 调一次 + harvest 轮询循环每 ~3s 调一次
     * (alpha.43:watch 页 SPA 播放器 init 在 onPageFinished 后数秒,周期性 dump 看状态演进 player false→true、
     * videoSrc 出现、captures 增长;videoSrc 看 progressive 经 media stack 实际选的格式)。
     */
    @Suppress("MaxLineLength")
    const val PAGE_DIAG_JS = """try{var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');var v=document.querySelector('video');var vs=(v&&(v.currentSrc||v.src))||'NONE';var gvc=(window.__gvCaptures&&window.__gvCaptures.length)||0;console.log('PAGE diag title='+document.title+' body='+((document.body&&document.body.innerText)||'NOBODY').slice(0,60)+' player='+!!p+' vp='+(window.innerWidth+'x'+window.innerHeight)+' videoSrc='+vs.slice(0,120)+' captures='+gvc);}catch(e){console.log('PAGE diag err '+e);}"""

    /**
     * fetch/XHR wrapper——截获**所有**发往 googlevideo.com/videoplayback 的请求(POST=SABR / GET=DASH 段),
     * 记录 {url, method, bodyB64, status} 到 window.__gvCaptures(最多 5 条防灌)。请求照常放行。
     * alpha.20 只截 SABR POST(`sabr=` + POST 过滤)致 25s 无捕获——放宽到全方法 + 全 googlevideo,
     * 定位 watch 到底用 SABR POST 还是 DASH GET(决定 alpha.21 是建 SabrSession 还是直接复用 GET url)。
     * body 可能是 ArrayBuffer/Uint8Array/Blob/string,统一转 base64;GET 段请求 body 空。
     * alpha.24:真机 SABR POST 捕获到 url+status+transformed-n(status=200 证浏览器 WASM n-transform
     * 被服务端接受)但 bodyB64=0B——播放器用 `fetch(new Request(url,{body}))` 形态,init.body 为空、
     * body 在 Request 对象里(ReadableStream 不可同步读)。改用 `input.clone().arrayBuffer()` 克隆
     * Request 读其 body(不消耗原请求),Promise.all(body,response) 后再 record(拿到 status)。
     */
    @Suppress("MaxLineLength")
    const val HOOK_JS = """
(function(){
  if(window.__gvHook) return; window.__gvHook=true; window.__gvCaptures=[];
  function b64(buf){ try{ if(!buf) return ''; var bytes=(buf instanceof Uint8Array)?buf:new Uint8Array(buf); var s=''; for(var i=0;i<bytes.length;i++) s+=String.fromCharCode(bytes[i]); return btoa(s); }catch(e){ return ''; } }
  function isGv(url){ return /googlevideo\.com\/videoplayback/.test(url||''); }
  function isPlayer(url){ return /youtubei\/v[0-9]+\/player/.test(url||''); }
  // 读请求 body 成字符串(youtubei /player body 是 JSON 文本)。兼容 Blob/ArrayBuffer/Request/string。
  function readBody(src){ try{ if(!src) return Promise.resolve(null); if(src instanceof Blob){ return src.text(); } if(typeof src==='string'){ return Promise.resolve(src); } if(src instanceof ArrayBuffer||(src&&typeof src.byteLength==='number'&&typeof src.getReader!=='function')){ return Promise.resolve(new TextDecoder().decode(new Uint8Array(src))); } if(src&&typeof src.clone==='function'&&typeof src.arrayBuffer==='function'){ return src.clone().arrayBuffer().then(function(ab){ return (ab&&ab.byteLength)?new TextDecoder().decode(new Uint8Array(ab)):null; }); } }catch(e){} return Promise.resolve(null); }
  function record(url,method,body,status){ if(window.__gvCaptures.length<5) window.__gvCaptures.push({url:url,method:method||'GET',bodyB64:b64(body),status:status}); }
  var _f=window.fetch;
  window.fetch=function(input,init){
    try{
      var url=(typeof input==='string')?input:((input&&input.url)||'');
      var method=(init&&init.method)||(input&&input.method)||'GET';
      if(isGv(url)){
        var bodySrc=(init&&init.body!=null)?init.body:input;
        var bp;
        if(bodySrc instanceof Blob){ bp=bodySrc.arrayBuffer().then(function(ab){return new Uint8Array(ab);}); }
        else if(bodySrc&&typeof bodySrc.clone==='function'&&typeof bodySrc.arrayBuffer==='function'){ bp=bodySrc.clone().arrayBuffer().then(function(ab){return (ab&&ab.byteLength)?new Uint8Array(ab):null;}).catch(function(){return null;}); }
        else if(bodySrc instanceof ArrayBuffer||(bodySrc&&typeof bodySrc.byteLength==='number'&&typeof bodySrc.getReader!=='function')){ bp=Promise.resolve(new Uint8Array(bodySrc)); }
        else { bp=Promise.resolve(null); }
        return _f.apply(this,arguments).then(function(r){ Promise.all([bp]).then(function(res){ record(url,method,res[0],r.status); }); return r; });
      }
      // alpha.43 诊断:截获 watch 页 /youtubei/v1/player 请求+响应——取证 watch 页为何走 progressive 而非 SABR。
      // 请求 body(JSON 文本)搜 poToken/serviceIntegrityDimensions → 判 watch 页是否铸了 PO token;
      // 响应 clone 读 json → dump streamingData keys/serverAbrStreamingUrl/adaptive0Url/ustreamerCfg/formats → 判服务端是否给 SABR 数据。
      else if(isPlayer(url)){
        var reqBp=(typeof input==='string') ? readBody(init&&init.body) : readBody(input);
        return _f.apply(this,arguments).then(function(r){
          Promise.all([reqBp]).then(function(res){ var bs=res[0]; console.log('PLAYERREQ poToken='+(bs&&bs.indexOf('poToken')>=0?'present':'absent')+' sid='+(bs&&bs.indexOf('serviceIntegrityDimensions')>=0?'present':'absent')); });
          r.clone().json().then(function(j){ try{ var ps=(j.playabilityStatus&&j.playabilityStatus.status)||'?'; var sd=j.streamingData||{}; var sdK=Object.keys(sd).join(','); var sabr=sd.serverAbrStreamingUrl?'present':'absent'; var af=sd.adaptiveFormats; var a0=(af&&af[0])?(af[0].url?'url':(af[0].signatureCipher?'cipher':'empty')):'none'; var ust=(j.playerConfig&&j.playerConfig.mediaCommonConfig&&j.playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig)?'present':'absent'; var fmts=sd.formats?sd.formats.length:0; console.log('PLAYERRESP status='+ps+' sdKeys='+sdK+' sabrUrl='+sabr+' adaptive0Url='+a0+' ustreamerCfg='+ust+' formats='+fmts); }catch(e){ console.log('PLAYERRESP err '+e); } }).catch(function(e){ console.log('PLAYERRESP json err '+e); });
          return r;
        });
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
