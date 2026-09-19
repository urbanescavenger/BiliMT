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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

  /**
   * alpha.53:最近一次 harvest 的页面是否已加载完成(即 [WebViewClient.onPageFinished] 是否触发)。
   * 正常 watch 页 ~1-2s 触发;空白页(YouTube 风控/WebView 渲染进程崩)时**永不触发**且 title 空/body
   * NOBODY。据此 fail-fast 而非干等满 30s,给轮换工厂的重试留出窗口内(剩余 ~20s)时间。
   * 每次 [harvestImpl] 开始时清零([invalidateWebView] 也会清);单次并发由 registry rotationInFlight 保证,无需加锁。
   */
  @Volatile private var pageFinishedMs: Long = 0L

  /**
   * P11-125:最近一次 [WebViewClient.onPageFinished] 的 URL。空壳页判据只在**当前文档就是 watch 页**
   * 时才量主文档长度——[stopPlayback] 会把上一个文档硬停到 `about:blank`,它的 onPageFinished 可能
   * 落在新 harvest 的 loadUrl 之后(真机 09-19 21:15:44:load watch 与 `onPageFinished: about:blank`
   * 差 16ms),届时量到的是空白的 about:blank 文档,不设这道闸就会把正常重试误判成空壳。
   */
  @Volatile private var lastFinishedUrl: String? = null

  /**
   * P11-125:本次 harvest 期间页面层错误的累计([WebViewClient.onReceivedError] /
   * [WebViewClient.onReceivedHttpError])。真机 09-19 r1979 整场 harvest 0 捕获,但 PAGE diag 只有
   * `title= body=NOBODY player=false captures=0`,分不清「YouTube 返回了空文档」与「文档拿到了、
   * 子资源取不回来(连接被中间盒搞坏)」——两者修法完全不同。故 fail-fast 时把主文档错误 + 子资源
   * 错误一并打出来。每次 [harvestImpl] 开始清零。
   */
  @Volatile private var httpErrorCount: Int = 0
  @Volatile private var lastHttpError: String? = null
  @Volatile private var mainFrameError: String? = null

  /**
   * P11-126:本次 harvest 是否已 dump 过主文档请求头(每次 harvest 只打一次,免得刷屏)。
   * 判据见 [shouldInterceptRequest] 里的注释。
   */
  @Volatile private var mainDocHeadersLogged: Boolean = false

  /**
   * 长期存活 WebView(alpha.61:复用跨 harvest 积累真实浏览上下文——每次新建 fresh WebView 被
   * YouTube 风控成空白页 `body=NOBODY player=false`(alpha.60 真机),长期存活 + 先加载真实首页
   * 对齐 [YoutubeBrowserSession] 成功模式,让 WebView 建立真实 cookie/会话后再采集 watch 页 SABR POST)。
   *
   * P11-125 放宽:长期存活的前提是**这个实例还健康**。页面恒空且零 console(渲染进程没跑过任何 JS,
   * 真机 09-19)→ 由 [invalidateWebView] 丢弃重建,见该函数注释。
   */
  private var webView: WebView? = null

  /**
   * P11-126:串行化 WebView 创建。harvest 的并发前提原本由 registry `rotationInFlight` 保证单飞,
   * 但新增的启动预热([prewarm])会在播放之外并发进入 [ensureWebView] —— 两个协程同时走到
   * `webView == null` 就会各建一个 WebView 并互相顶掉(`loadUrl` 抢导航)。同 [InnerTubeClient]
   * 的 `sessionMutex` 用法。
   */
  private val webViewMutex = Mutex()

  /** 首次首页加载完成信号([ensureWebView] 用)。 */
  private var ready: CompletableDeferred<Unit>? = null

  /** 一次采集到的 googlevideo 请求(SABR POST 或 DASH GET;url 含已 transform 的 n + body 含 poToken/ustreamerConfig/formatIds)。 */
  data class SabrCapture(val url: String, val method: String, val bodyB64: String, val status: Int)

  /**
   * 加载 watch 页采集首个 SABR POST(alpha.49 起 watch 优先;embed Error 153 config 拒不可解,已删)。
   * 失败/超时返回 null(绝不抛,不阻塞主路径)。
   * @param timeoutMs 上限(默认 50s);首次调用含 ensureWebView 加载首页建立上下文(至多 HOMEPAGE_LOAD_MS=15s),
   *   之后 watch 页 SPA 播放器 init 通常 4-8s。50s = 15s 首页 + 30s watch 轮询(首次),后续复用 WebView 更短。
   */
  /**
   * alpha.48/49(轮换):支持 `startMs` 锚定——watch 页 URL 加 `&t=<秒>` 让浏览器播放器**从该位置起播**,
   * 其首发 SABR POST 锚定在 startMs。服务端会话窗口 = 锚点..锚点+60s(见 docs row 72,锚点服务端侧,
   * 由浏览器 POST 时播放位置定),故旋转到 mid-playhead 必须先让浏览器锚定在播放头,否则新会话窗口仍
   * 从 0 起算 → 请求 playhead>60s 被拒(alpha.47 session2 死因)。默认 startMs=0(从头播,原行为)。
   */
  suspend fun harvest(videoId: String, startMs: Long = 0L, timeoutMs: Long = 50_000L): SabrCapture? =
    withContext(Dispatchers.Main) {
      val result = runCatching { withTimeoutOrNull(timeoutMs) { harvestImpl(videoId, startMs) } }
        .onFailure { Log.w(Tag, "harvest failed: ${it.message ?: it::class.simpleName}") }
        .getOrNull()
      // P11-118:采集一结束就让采集 WebView **停声停播**。它长期存活且 watch 页会 autoplay,
      // 否则退出播放器后它仍在响(真机 09-16 17:04 AudioFocusDelegate 抢焦点),且与主播放器位置不一致。
      stopPlayback()
      result
    }

  /** P11-118:停掉采集 WebView 的媒体并暂停其渲染(下次 harvest 前 [resumeRendering] 恢复)。
   *  P11-118b:光 pause() 不够——真机 r1954 采集完成后它**继续按节奏发 SABR POST**(19:28:58/19:29:09/
   *  19:29:20,约 11s 一次),把同一个视频又下一遍,直接饿死主播放器(rn=3 首个媒体段 50s 没回来,
   *  用户「视频没加载出来」)。故这里加 `loadUrl("about:blank")` **硬停页面**——实例/ cookie jar /
   *  渲染进程都保留(不违反 alpha.61「长期存活 WebView」前提),只是把当前文档连同其媒体请求一起丢掉。 */
  private fun stopPlayback() {
    val view = webView ?: return
    runCatching {
      view.evaluateJavascript(
        "try{var ms=document.querySelectorAll('video,audio');for(var i=0;i<ms.length;i++){ms[i].pause();ms[i].muted=true;ms[i].volume=0;}}catch(e){};'ok'",
        null,
      )
    }
    runCatching { view.onPause() }
    runCatching { view.loadUrl("about:blank") }
  }

  private fun resumeRendering() {
    runCatching { webView?.onResume() }
  }

  /**
   * P11-126:后台预热——把 [ensureWebView] 那次冷启动(建 WebView + 加载 `https://www.youtube.com/`
   * 建立真实浏览上下文)挪到播放之外。
   *
   * 依据(真机 09-19 `logs_live_20260919_214431.log`):这次冷启实测吃掉 **10.9s**,而它整段都落在
   * 起播的 30s 预算里(PO token 铸造 9.3s + player js 4.4s 之后才轮到它)⇒ watch 页还没开始加载
   * 预算就到期,整条 launch 被取消。预热后 harvest 只剩「导航到 watch 页等捕获」那 1~2s。
   *
   * 结束时调 [stopPlayback] 硬停页面:首页加载完会留一个 autoplay 的页(静音由页内 HOOK 压住),
   * 不停掉就白耗流量,且下次 harvest 前的 about:blank 竞态会变复杂。
   *
   * **不发** [YoutubeLoadProgress]:它是全局单例,预热发生在播放器之外,emit 会留下一个 stale 的
   * 非 null step 显示在播放器加载叠层上;沿用 `BiliMT:Preload` 那套「只打日志」的做法。
   *
   * 并发安全:与 harvest 共享 [webViewMutex];若 WebView 已存在(已有 harvest 跑过 / 已被预热过)
   * 直接 no-op。由 `AppContainer.startYoutubeHarvestPrewarm` 在进程启动后延迟调用一次。
   *
   * @return 冷启动耗时 ms;未做任何事时返回 null。
   */
  suspend fun prewarm(): Long? = withContext(Dispatchers.Main) {
    if (webView != null) {
      Log.i(Tag, "prewarm: 采集 WebView 已存在(已预热或已采集过)→ 跳过")
      return@withContext null
    }
    val t0 = System.currentTimeMillis()
    val view = runCatching { ensureWebView() }
      .onFailure { Log.w(Tag, "prewarm: ensureWebView 失败: ${it.message}") }
      .getOrNull() ?: return@withContext null
    // 硬停页面(见 stopPlayback 注释:长期存活实例 + 只丢当前文档,实例/cookie jar/渲染进程都保留)。
    stopPlayback()
    val ms = System.currentTimeMillis() - t0
    Log.i(Tag, "prewarm: 采集 WebView 冷启完成 ${ms}ms(url=${view.url})→ 之后 harvest 只剩 watch 页导航")
    ms
  }

  /**
   * P11-125:丢弃当前采集 WebView,下次 [harvest] 走 [ensureWebView] 重建(重建时会重新加载
   * `https://www.youtube.com/` 建立真实上下文,即 alpha.61 那条已验证过的冷启动路径)。
   *
   * 依据(真机 09-19 r1979):整场 harvest 0 捕获,watch 页恒 `title= body=NOBODY player=false`,
   * **一条 YouTube 自己的 console 都没有**(09-17 正常时有 `LegacyDataMixin`/`PLAYERREQ`/`gv req`),
   * 同时 chromium 打了 48 次 `spdy_session.cc:2997 Received HEADERS for invalid stream`
   * (09-17 正常日志 0 次)。文档拿到了但页内 JS 一行没跑 ⇒ 要么该实例的渲染进程死了,要么它的网络
   * 请求(ytmainappweb 的 JS bundle)取不回来。这两者都只能靠**换实例**来复位。
   *
   * alpha.61 的「长期存活复用」前提是实例仍健康;空壳页继续复用只会让后续每次 harvest 都撞同一堵墙
   * (09-19 三次 harvest 全部 `no capture`,连冷启动重试也救不回来)。
   *
   * 诚实的边界:Chromium 的网络栈(含 HTTP/2 socket 池)是**进程级**共享的,`destroy()` 只回收本实例
   * 的渲染进程与文档,cookie jar / 会话由 WebView 框架持有(alpha.61 的会话前提不受影响)。若重建后
   * 仍空壳,日志里的 `webView=rebuilt` + 同样的空壳取证即可证明问题不在实例层,而在网络/风控层。
   */
  private fun invalidateWebView() {
    val old = webView ?: return
    Log.w(Tag, "harvest: 丢弃采集 WebView 实例(下次 harvest 重建;若重建后仍空壳=非实例问题)")
    runCatching { old.stopLoading() }
    runCatching { old.loadUrl("about:blank") }
    runCatching { old.destroy() }
    webView = null
    ready = null
    pageFinishedMs = 0L
    lastFinishedUrl = null
  }

  private suspend fun harvestImpl(videoId: String, startMs: Long = 0L): SabrCapture? {
    // alpha.61:复用长期存活 WebView(首次懒建并加载首页建立真实上下文),不再每次新建+销毁。
    // P11-125:把「本次是新建还是复用」打进日志——上轮空壳被 [invalidateWebView] 丢弃后,这次必是
    // 新建;若新建后**仍然**空壳,就说明问题不在这个实例(渲染进程/页面),而在更下层(网络/风控)。
    val reused = webView != null
    val view = ensureWebView()
    resumeRendering() // P11-118:上次采集后 onPause 过,这里恢复渲染/媒体
    // seed cookies:watch 播放器在 WebView 里发 /youtubei/v1/player 需要 VISITOR_INFO1_LIVE
    // 等会话 cookie(对齐 YoutubeJsExecutor.fetchViaWebView 的 CookieManager 写法)。
    // 每次 harvest 重播一遍(会话 cookie 可能变化),长期存活 WebView 已有的真实 cookie 会叠加。
    val cookies = innerTubeClient.currentSessionCookies()
    runCatching {
      CookieManager.getInstance().setCookie(YoutubeConstants.Origin, cookies)
      CookieManager.getInstance().flush()
    }.onFailure { Log.w(Tag, "seed cookies failed: ${it.message}") }
    // alpha.49(顺序反转):watch 页唯一稳定捕获源(embed Error 153 config 拒不可解,已删)。
    // 锚定用 `t=<秒>`(SPA 起播位置参数;startMs=0 首播不锚定)。
    val watchT = if (startMs > 0) "&t=${startMs / 1000}" else ""
    YoutubeLoadProgress.emit(YoutubeLoadStep.HarvestWatch)
    Log.i(Tag, "harvest: load watch videoId=$videoId startMs=$startMs${watchT} cookie=${cookies.length}B (webView=${if (reused) "reuse" else "rebuilt"})")
    // P11-125:清空页面层取证(上一轮的 onPageFinished/错误计数不能带进本轮)。
    pageFinishedMs = 0L
    lastFinishedUrl = null
    httpErrorCount = 0
    lastHttpError = null
    mainFrameError = null
    mainDocHeadersLogged = false
    view.loadUrl("https://www.youtube.com/watch?v=$videoId&autoplay=1&mute=1$watchT")
      // 轮询 window.__gvCaptures[0](对齐 BotGuard pollState 双解码:evaluateJavascript 对字符串
      // 结果做 JSON 编码,先解内层字符串再解析对象)。alpha.20 只截 SABR POST 致 25s 无捕获——
      // alpha.21 放宽到所有 googlevideo 请求(含 DASH GET),并加页面加载/console 诊断定位行为。
      // alpha.23:embed 报「错误 153」(config 拒不可解)→ 主源定为 watch 页(无 embed 权限闸)。
      val start = System.currentTimeMillis()
      val deadline = start + 30_000L
      var lastDiag = start
      var lastCaptureDump = start
      // P11-118:SABR POST 优先的兜底位。首页/预热阶段也会发 googlevideo GET(如 itag18 预载 403),
      // 旧判据「url 非空 && status>0」会把它当有效捕获**立刻返回**(真机 09-16 17:03:24:探针抓到首页
      // 403 GET 就收工,而真正的 SABR POST 在 1.4s 后才发)→ 探针白跑、也不该拿它当阶段 2 判据。
      var nonPostCapture: SabrCapture? = null
      // P11-125:空壳页的长度判据只量一次(见下方 DOC_LEN_JS 分支)。
      var docLenChecked = false
      while (System.currentTimeMillis() < deadline) {
        // alpha.53:空白页 fail-fast——onPageFinished 正常 ~1-2s 触发;超 [BLANK_PAGE_ABORT_MS] 仍没触发
        // = 页根本没渲染(风控/WebView 渲染崩,alpha.52 真机 w120:title 空/NOBODY/player=false 干等 30s)。
        // 立即放弃交轮换工厂重试,不耗满 30s(否则重试永远赶不上窗口耗尽)。正常页 1-2s 已过该闸,无副作用。
        // P11-125:补打页面层取证(主文档错误 + 子资源错误)——判「服务端返回空文档」还是「子资源取不回来」。
        if (pageFinishedMs == 0L && System.currentTimeMillis() - start > BLANK_PAGE_ABORT_MS) {
          Log.w(
            Tag,
            "harvest: onPageFinished not fired within ${BLANK_PAGE_ABORT_MS}ms (blank page — throttle/renderer died) → fail fast, let rotation retry" +
              " | mainFrameErr=${mainFrameError ?: "none"} httpErr=$httpErrorCount last=${lastHttpError ?: "none"}",
          )
          runForensicProbe(view, "onPageFinished 未触发")
          invalidateWebView()
          return null
        }
        // P11-125:onPageFinished **触发了**、页却是空文档——真机 09-19 21:15:23 watch 页 3s 就「完成」,
        // 但 title 空 / body NOBODY / player=false / **一条 YouTube 自己的 console 都没有**(09-17 正常时
        // 有 `LegacyDataMixin`/`PLAYERREQ`/`gv req`)。旧逻辑只在 onPageFinished **没触发**时 fail-fast,
        // 这种「完成了但空」的页面会干耗满 30s 轮询窗口,且复用的坏 WebView 让后续每次 harvest 都撞同一堵墙。
        // 判据:当前文档必须是 watch 页(挡掉 stopPlayback 留下的 about:blank 竞态,见 [lastFinishedUrl]),
        // 且主文档 outerHTML 长度低于 [EMPTY_DOC_ABORT_CHARS]——真实 watch 页恒 >100KB,空壳只有几十字节。
        if (pageFinishedMs != 0L && !docLenChecked &&
          System.currentTimeMillis() - pageFinishedMs > DOC_LEN_CHECK_DELAY_MS &&
          lastFinishedUrl?.contains("/watch?") == true
        ) {
          docLenChecked = true
          val dl = evalOn(view, DOC_LEN_JS)?.trim()?.toLongOrNull() ?: -1L
          if (dl in 0L until EMPTY_DOC_ABORT_CHARS) {
            Log.w(
              Tag,
              "harvest: watch 页是空壳文档(dl=${dl}B,onPageFinished 已触发)→ fail fast + 丢弃该 WebView" +
                " | mainFrameErr=${mainFrameError ?: "none"} httpErr=$httpErrorCount last=${lastHttpError ?: "none"}",
            )
            runForensicProbe(view, "空壳文档 dl=$dl")
            invalidateWebView()
            return null
          }
          Log.i(Tag, "harvest: 文档非空壳 dl=${dl}B → 继续等 SABR POST")
        }
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
              // P11-118:POST(SABR)优先——命中即返回;非 POST 只记兜底,继续等 POST 到 deadline。
              if (method.equals("POST", ignoreCase = true)) {
                Log.i(Tag, "harvest: captured SABR $method status=$status n=${n ?: "ABSENT"} url=$url bodyB64=${body?.length ?: 0}B")
                return SabrCapture(url, method, body ?: "", status)
              }
              if (nonPostCapture == null) {
                nonPostCapture = SabrCapture(url, method, body ?: "", status)
                Log.i(Tag, "harvest: non-SABR capture seen ($method status=$status n=${n ?: "ABSENT"}) → 继续等 SABR POST")
              }
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
      nonPostCapture?.let {
        Log.w(Tag, "harvest: no SABR POST before deadline; only ${it.method} status=${it.status} → return non-SABR(阶段 2 判据不算通过)")
        return it
      }
      Log.w(
        Tag,
        "harvest: timeout (30s 内 watch 无 googlevideo 请求)" +
          " | mainFrameErr=${mainFrameError ?: "none"} httpErr=$httpErrorCount last=${lastHttpError ?: "none"}",
      )
      runForensicProbe(view, "轮询到期无捕获")
      // 注意:不再 view.destroy()——WebView 长期存活复用(alpha.61),下次 harvest 直接导航到新 watch 页。
      return null
  }

  @SuppressLint("SetJavaScriptEnabled")
  /**
   * 复用长期存活 WebView(alpha.61):首次懒建 + 加载真实首页建立浏览上下文,之后跨 harvest 复用
   * (每次直接导航到新 watch 页)。镜像 [YoutubeBrowserSession] 成功模式——长期存活 + 真实页上下文
   * 让 WebView 不被 YouTube 风控成空白页。返回 WebView 前等待首页加载完成(或超时 HOMEPAGE_LOAD_MS)。
   */
  private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
    webViewMutex.withLock {
      webView?.let { return@withLock it }
      val deferred = CompletableDeferred<Unit>()
      ready = deferred
      val created = createWebView(deferred)
      webView = created
      withTimeoutOrNull(HOMEPAGE_LOAD_MS) { deferred.await() }
      Log.i(Tag, "harvest WebView initialized (long-lived): ${created.url}")
      created
    }
  }

  private fun createWebView(deferred: CompletableDeferred<Unit>): WebView = WebView(appContext).apply {
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
        // alpha.53:记录页面加载完成时刻——空白页 fail-fast 判定用(见 harvestImpl 轮询循环)。
        pageFinishedMs = System.currentTimeMillis()
        // P11-125:记完成 URL——空壳页判据只对 watch 文档生效(挡 about:blank 竞态)。
        lastFinishedUrl = url
        // alpha.61:首次首页加载完成 → 解除 ensureWebView 等待。
        if (deferred.isActive) deferred.complete(Unit)
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
        val url = request?.url?.toString()
        // P11-125:主文档错误**无条件**记(空白页第一判据)。旧实现只记 URL 含 youtube/googlevideo 的,
        // 主文档若走了别的 host(重定向/m.youtube.com)就被漏掉,而那正是「页为什么是空的」的关键证据。
        if (request?.isForMainFrame == true) {
          mainFrameError = "${error?.errorCode}/${error?.description} @${url?.take(80)}"
          Log.w(Tag, "harvest onReceivedError(main frame): $url ${error?.errorCode} ${error?.description}")
        }
        if (url?.contains("googlevideo") == true || url?.contains("youtube") == true) {
          httpErrorCount++
          lastHttpError = "${error?.errorCode}/${error?.description} @${url.take(80)}"
          Log.w(Tag, "harvest onReceivedError: $url ${error?.description}")
        }
      }

      override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
      ) {
        val url = request?.url?.toString()
        val code = errorResponse?.statusCode
        // P11-125:主文档非 2xx 是「空文档」的直接来源(YouTube 边缘 4xx/5xx 空体)→ 单独记;
        // 其余子资源错误累计计数,供 fail-fast 汇总(注意:正常 watch 页也会有几个子资源非 200,
        // 如 accounts.google.com/ServiceLogin,故计数本身不等于故障,只作对比取证)。
        if (request?.isForMainFrame == true) {
          mainFrameError = "HTTP $code @${url?.take(80)}"
        }
        httpErrorCount++
        lastHttpError = "HTTP $code @${url?.take(80)}"
        Log.w(Tag, "harvest onReceivedHttpError: $url code=$code reason=${errorResponse?.reasonPhrase} mainFrame=${request?.isForMainFrame}")
      }

      // alpha.43:shouldInterceptRequest 只读记录所有 googlevideo 请求的 itag/sabr(method+url),
      // return null 放行原生 Chromium 栈(真实 TLS/cookie/UA)——progressive GET 经 media stack 不经 fetch
      // hook(alpha.42 watch 页 6 次 itag=18 403 由 onReceivedHttpError 才看到),此层补全 200 的 progressive
      // 也结构化记录,确认 watch 页选 progressive(itag 18 sabr=false)而非 SABR POST。
      override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        // P11-126:主文档请求头**打一次**——查「UA 说桌面 Windows、Client Hints 说 Android WebView」
        // 这类身份自相矛盾(社区实测:Android WebView 即使覆盖 UA,仍会自动发
        // `Sec-CH-UA: "Android WebView"` / `Sec-CH-UA-Mobile: ?1` / `Sec-CH-UA-Platform: "Android"`,
        // 机器人检测据此判伪 → 回空壳;且 WebView 无任何 API 能覆盖/抑制这些头)。
        // 注意:此处 requestHeaders 不一定含全部客户端提示(部分由网络栈后置添加),
        // 权威判据是 FORENSIC_JS 里的 navigator.userAgentData,这里只是同一时刻的旁证。
        if (request.isForMainFrame && !mainDocHeadersLogged) {
          mainDocHeadersLogged = true
          val headers = request.requestHeaders.entries.joinToString("; ") { "${it.key}=${it.value.take(120)}" }
          Log.w(Tag, "harvest main-doc request headers: $headers")
        }
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
    // alpha.61:首次先加载真实首页建立浏览上下文(对齐 YoutubeBrowserSession 成功模式——
    // 长期存活 + 真实 cookie/会话,避免 fresh WebView 被风控成空白页)。onPageFinished 解除 ensureWebView 等待。
    loadUrl("https://www.youtube.com/")
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
   * P11-126:解 `evaluateJavascript` 回传值的外层 JSON 编码(字符串结果会被加引号并转义,见
   * [parseCaptureArray] 的双解码说明)。解不出就原样返回,保证日志永远有东西可看。
   */
  private fun decodeJsString(raw: String?): String {
    if (raw.isNullOrBlank()) return "null"
    return runCatching { json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull() ?: raw
  }

  /**
   * P11-126:空壳页取证(见 [FORENSIC_JS] 的判据说明)。**只落日志,不改任何行为**——候选修法
   * (如补 consent cookie、换 UA 让身份自洽)等这份证据出来再定。
   *
   * 三处出口共用:onPageFinished 未触发、已触发但文档空壳、30s 轮询到期。都在 `invalidateWebView()`
   * **之前**调用(之后实例已销毁,探针必然拿不到东西)。
   */
  private suspend fun runForensicProbe(view: WebView, reason: String) = withContext(NonCancellable) {
    val sync = runCatching { evalOn(view, FORENSIC_JS) }.getOrNull()
    Log.w(Tag, "harvest forensic($reason): ${decodeJsString(sync)}")
    // 同源 fetch 与高熵 Client Hints 都是异步的,给它们 ~1.5s 再回读。
    delay(1_500)
    val uad = runCatching { evalOn(view, "window.__forensicUad||'pending'") }.getOrNull()
    val fetched = runCatching { evalOn(view, "window.__forensicFetch||'pending'") }.getOrNull()
    Log.w(Tag, "harvest forensic($reason) async: uad=${decodeJsString(uad)} | fetch=${decodeJsString(fetched)}")
    // cookie jar:社区实测「补 SOCS/CONSENT 能拿回真页」,先确认这两个 cookie 在不在(本轮不注入)。
    val jar = runCatching { CookieManager.getInstance().getCookie(YoutubeConstants.Origin) }.getOrNull().orEmpty()
    Log.w(
      Tag,
      "harvest forensic($reason) cookie jar: len=${jar.length}B socs=${jar.contains("SOCS=")} " +
        "consent=${jar.contains("CONSENT=")} visitor=${jar.contains("VISITOR_INFO1_LIVE=")}",
    )
    Unit
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
     * alpha.53:空白页 fail-fast 阈值——watch 页 [onPageFinished] 正常 ~1-2s 触发;超过此值仍没触发
     * = 页未渲染(风控/渲染崩),立即放弃让轮换重试,不干等 30s。正常加载远快于 8s,不会误杀。
     */
    const val BLANK_PAGE_ABORT_MS = 8_000L

    /**
     * alpha.61:首次首页加载超时——ensureWebView 等首页 onPageFinished 建立真实上下文的兜底上限。
     * 超时仍返回 WebView(已创建,可能未完全加载),harvest 照常导航到 watch 页。
     */
    const val HOMEPAGE_LOAD_MS = 15_000L

    /**
     * P11-125:空壳页判据——onPageFinished 后延迟这么久才量主文档长度,给 SPA 首屏留出时间
     * (避免刚 finish 就读到尚未构建的 documentElement)。
     */
    const val DOC_LEN_CHECK_DELAY_MS = 1_000L

    /**
     * P11-125:空壳页阈值——真实 watch 页 documentElement.outerHTML 恒 >100KB(kevlar 骨架 + 内联数据),
     * 空文档/错误壳只有几十字节。取 20KB 留足余量:正常页 1s 内即远超,空壳绝无可能达到。
     */
    const val EMPTY_DOC_ABORT_CHARS = 20_000L

    /**
     * P11-125:主文档长度探针——同步返回 documentElement.outerHTML 长度(-1=读不到),同时经 console
     * 打 ytcfg/readyState/title 三个旁证:真实 watch 页 `ytcfg=true`(kevlar 已 boot),空壳恒 false。
     * 与 PAGE_DIAG_JS 的区别:PAGE_DIAG 走 console 是异步取证,evaluateJavascript 的回传值拿不到;
     * 这条要的是**回传值**(空壳判定必须同步拿到长度,不能等 console)。
     */
    @Suppress("MaxLineLength")
    const val DOC_LEN_JS = """try{var de=document.documentElement;var dl=(de&&de.outerHTML)?de.outerHTML.length:-1;console.log('DOCLEN dl='+dl+' ytcfg='+!!window.ytcfg+' rs='+document.readyState+' title='+document.title);dl;}catch(e){console.log('DOCLEN err '+e);-1;}"""

    /**
     * P11-126:空壳页取证探针——同源返回一段 JSON,另起两个异步结果到 `window.__forensicUad` /
     * `window.__forensicFetch`([runForensicProbe] 延迟回读)。
     *
     * 真机 09-19 的核心悬案:同一分钟 OkHttp 抓同一 watch 页得 1,437,481B 完整页,而采集 WebView
     * 只得到 39 字节空骨架。要判的四件事:
     *
     * 1. `dl`/`head` —— 那 39 字节到底是什么(39 恰好等于空骨架 `<html><head></head><body></body></html>`)。
     * 2. `enc`/`tr`/`dec`(`performance` 导航条目)—— **决定性**:`enc≈1.4MB` 而 `dl=39` ⇒ 服务端发了、
     *    文档是空的(解析/渲染层);`enc≈39/0` ⇒ 服务端/链路真没发内容。
     * 3. `ua` + `uadBrands`/`uadMobile`/`uadPlatform` + `__forensicUad` —— 核「UA 说桌面 Windows、
     *    Client Hints 说 Android WebView」这类身份自相矛盾(社区实测这是 WebView 被静默回空壳的常见原因,
     *    且 WebView **没有** API 能覆盖/抑制 Sec-CH-UA)。
     * 4. `__forensicFetch` —— 页内同源 `fetch('/robots.txt')`,判「导航路径坏」还是「WebView 网络整体坏」。
     *
     * 另有 Kotlin 侧的 cookie jar 检查(SOCS/CONSENT 在不在)见 [runForensicProbe]。
     * 注意:本轮**只取证不改行为**(consent cookie 注入等候选修法留到判读之后)。
     */
    @Suppress("MaxLineLength")
    const val FORENSIC_JS = """(function(){try{var de=document.documentElement;var html=de?de.outerHTML:'';var navs=(window.performance&&performance.getEntriesByType)?performance.getEntriesByType('navigation'):[];var nav=(navs&&navs[0])||{};var uad=navigator.userAgentData||null;var o={dl:html.length,head:html.slice(0,240),href:location.href,rs:document.readyState,enc:nav.encodedBodySize,tr:nav.transferSize,dec:nav.decodedBodySize,rstart:nav.responseStart,rend:nav.responseEnd,ua:navigator.userAgent,uadBrands:uad?JSON.stringify(uad.brands):'NONE',uadMobile:uad?String(uad.mobile):'NONE',uadPlatform:uad?String(uad.platform):'NONE'};window.__forensic=JSON.stringify(o);try{if(uad&&uad.getHighEntropyValues){uad.getHighEntropyValues(['platform','platformVersion','architecture','model','uaFullVersion']).then(function(v){window.__forensicUad=JSON.stringify(v);}).catch(function(e){window.__forensicUad='err='+e;});}else{window.__forensicUad='NO_API';}}catch(e){window.__forensicUad='threw='+e;}try{fetch('/robots.txt',{cache:'no-store'}).then(function(r){return r.text().then(function(t){window.__forensicFetch='status='+r.status+' len='+t.length+' head='+t.slice(0,60);});}).catch(function(e){window.__forensicFetch='err='+e;});}catch(e){window.__forensicFetch='threw='+e;}return window.__forensic;}catch(e){return 'FORENSIC_THREW='+e;}})()"""

    /**
     * 页面状态诊断脚本——dump player 元素 present/viewport 尺寸/<video> src/captures 条数,
     * 经 console.log 路由到 [onConsoleMessage]。onPageFinished 调一次 + harvest 轮询循环每 ~3s 调一次
     * (alpha.43:watch 页 SPA 播放器 init 在 onPageFinished 后数秒,周期性 dump 看状态演进 player false→true、
     * videoSrc 出现、captures 增长;videoSrc 看 progressive 经 media stack 实际选的格式)。
     */
    @Suppress("MaxLineLength")
    const val PAGE_DIAG_JS = """try{var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');var v=document.querySelector('video');var vs=(v&&(v.currentSrc||v.src))||'NONE';var gvc=(window.__gvCaptures&&window.__gvCaptures.length)||0;var de=document.documentElement;var dl=(de&&de.outerHTML)?de.outerHTML.length:-1;console.log('PAGE diag title='+document.title+' dl='+dl+' rs='+document.readyState+' ytcfg='+!!window.ytcfg+' body='+((document.body&&document.body.innerText)||'NOBODY').slice(0,60)+' player='+!!p+' vp='+(window.innerWidth+'x'+window.innerHeight)+' videoSrc='+vs.slice(0,120)+' captures='+gvc);}catch(e){console.log('PAGE diag err '+e);}"""

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
  // P11-118:强制静音。采集 WebView 里的 watch 页会 autoplay(mediaPlaybackRequiresUserGesture=false
  // 是为触发 SABR POST 必需的),而 URL 上的 `mute=1` 在 SPA 里不保证生效——真机 09-16 17:04/17:05
  // `AudioFocusDelegate` 两次抢到 AudioFocus,用户听到第二路音频(退出播放器后仍在响、且与主播放器
  // 位置不一致)。这里在**页内**永久压住:周期扫 + 捕获阶段拦 play 事件。
  // 整块包 try:onPageStarted 时机极早,hook 本体绝不能被这一段拖垮(否则采集全废)。
  try{
    function _muteAll(){ try{ var ms=document.querySelectorAll('video,audio'); for(var i=0;i<ms.length;i++){ ms[i].muted=true; ms[i].volume=0; } }catch(e){} }
    _muteAll(); setInterval(_muteAll, 300);
    document.addEventListener('play', function(e){ try{ e.target.muted=true; e.target.volume=0; }catch(_){ } }, true);
  }catch(e){}
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
