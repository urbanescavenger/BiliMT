package com.kirin.mt.core.youtube.newpipe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.MainThread
import com.kirin.mt.BuildConfig
import com.kirin.mt.core.youtube.YoutubeBotGuard
import com.kirin.mt.core.youtube.YoutubeConstants
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 移植 LibreTube `api/poToken/PoTokenWebView.kt`,作为 [NewPipePoTokenGenerator] 的 BotGuard 铸造内核。
 *
 * 与 LibreTube 原版的差异(适配 BiliTV):
 *  - 网络层:LibreTube 走 `RetrofitInstance.externalApi.botguardRequest`(纯 OkHttp、无 cookie jar 桥接);
 *    这里换 BiliTV 共享的 `httpClient` 直发 OkHttp POST,头模式与 LibreTube `ExternalApi.botguardRequest`
 *    **完全一致**(UA / Accept / Content-Type json+protobuf / x-goog-api-key / x-user-agent),**不额外带
 *    Cookie / X-Goog-Visitor-Id**(实证:LibreTube 无 cookie jar,GenerateIT 照样铸出 contentBinding 正确的 token)。
 *  - UA:LibreTube 常量 `USER_AGENT`(桌面 Chrome)→ BiliTV `YoutubeConstants.MobileUserAgent`(移动 Chrome,
 *    与 BiliTV 现有 BotGuard WebView 指纹一致)。
 *  - `LibreTubeApp.instance` → 构造传入 `context.applicationContext` + `httpClient`。
 *  - `com.github.libretube.BuildConfig` → `com.kirin.mt.BuildConfig`。
 *  - 保留全部 JS interface 名(`downloadAndRunBotguard`/`onRunBotguardResult`/`onObtainPoTokenResult`),
 *    与 `po_token.html` 里 BotGuard JS 的调用一致。
 *
 * 流程:WebView 加载 po_token.html → `downloadAndRunBotguard` → POST jnn/v1/Create 拿 challenge →
 * eval runBotGuard(challenge) 铸 contentBinding 正确的 botguardResponse → POST GenerateIT → integrityToken →
 * `generatePoToken(videoId)` → JS obtainPoToken → websafe poToken。
 */
class PoTokenWebView private constructor(
    context: Context,
    private val httpClient: OkHttpClient,
    private val generatorContinuation: Continuation<PoTokenWebView>,
    /** P11-154:铸造上下文取自该视频的移动 watch 页(`downloadAndRunBotguard` 里用)。 */
    private val videoId: String,
    /** P11-154:页面上下文供给。**null ⇒ 与改动前逐字节一致的 Create 路径**。 */
    private val pageContextSupplier: (suspend (String) -> YoutubeBotGuard.PoTokenPageContext?)? = null,
) {
    private val webView = WebView(context)
    private val poTokenContinuations = mutableMapOf<String, Continuation<String>>()
    private val exceptionHandler = CoroutineExceptionHandler { context, exception ->
        onInitializationError(exception)
    }
    private lateinit var expirationInstant: Instant

    /**
     * P11-154:本实例是否走了「页面上下文」铸造分支。上层在建失败时据此把后续 mint 整体回落到 Create
     * ——避免一次页面上下文故障让 arm A 永久不可用。
     */
    @Volatile
    var usedPageContext = false
        private set

    /** P11-154:本实例的铸造上下文一行摘要(供 resolver 的 `token 形态` 日志带上,作判据)。 */
    @Volatile
    var mintContext = "unknown"
        private set

    //region Initialization
    init {
        webView.settings.apply {
            //noinspection SetJavaScriptEnabled we want to use JavaScript!
            javaScriptEnabled = true
            safeBrowsingEnabled = false
            userAgentString = YoutubeConstants.MobileUserAgent
            blockNetworkLoads = true // the WebView does not need internet access
        }

        // so that we can run async functions and get back the result
        webView.addJavascriptInterface(this, JS_INTERFACE)
    }

    /**
     * Must be called right after instantiating [PoTokenWebView] to perform the actual
     * initialization. This will asynchronously go through all the steps needed to load BotGuard,
     * run it, and obtain an `integrityToken`.
     */
    private fun loadHtmlAndObtainBotguard(context: Context) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "loadHtmlAndObtainBotguard() called")
        }

        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            try {
                val html = context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                withContext(Dispatchers.Main) {
                    webView.loadDataWithBaseURL(
                        "https://www.youtube.com",
                        html.replaceFirst(
                            "</script>",
                            // calls downloadAndRunBotguard() when the page has finished loading
                            "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
                        ),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            } catch (e: Exception) {
                onInitializationError(e)
            }
        }
    }

    /**
     * Called during initialization by the JavaScript snippet appended to the HTML page content in
     * [loadHtmlAndObtainBotguard] after the WebView content has been loaded.
     */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "downloadAndRunBotguard() called")
        }

        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            // ── P11-154:优先用「真页面上下文」铸造(挑战 + 该挑战所绑定的那份 ytcfg) ──────────────
            // 依据:历史唯一拿到 `status=1` 的 token 都出自真 watch 页自铸(r1992 ×10 / r2002 ×100 /
            // r2030 材料臂 ×19),而那些页全是 MWEB;本类此前的挑战是 `[REQUEST_KEY]`→
            // `/api/jnn/v1/Create`,文档里**没有页面、没有 ytcfg、没有 EVENT_ID** ⇒ 服务端每场首笔就
            // 判占位级(`status=2`,今天 7/7 场)。调研(BgUtils #44 / PipePipeClient #86 / bgutil #243)
            // 指出 challenge 绑的是 `yt.config_.EVENT_ID` ⇒ 挑战与那份 ytcfg 必须**成对且同页同源**。
            // 取不到 ⇒ **整体回落 Create**(与改动前逐字节一致,不产生半成品状态)。
            val pageCtx = runCatching { pageContextSupplier?.invoke(videoId) }.getOrNull()
            val challengeData = if (pageCtx != null) {
                usedPageContext = true
                mintContext = "page-bgChallenge ytcfg=page eventId=${pageCtx.eventId} ${pageCtx.diag}"
                Log.i(TAG, "armA 铸造上下文(P11-154): challenge=$mintContext")
                pageCtx.challengeJson
            } else {
                mintContext = if (pageContextSupplier == null) {
                    "create ytcfg=none eventId=NONE (未启用页面上下文)"
                } else {
                    "create ytcfg=none eventId=NONE (页面上下文不可用→整体回落)"
                }
                Log.i(TAG, "armA 铸造上下文(P11-154): challenge=$mintContext")
                val responseBody = makeBotguardServiceRequest(
                    "https://www.youtube.com/api/jnn/v1/Create",
                    listOf(REQUEST_KEY)
                )
                parseChallengeData(responseBody)
            }
            // 有页面上下文时,`window.yt = {config_: …}` 必须**先于** interpreter eval——BotGuard VM 在
            // 启动时读 `window.yt.config_`(同 [YoutubeBotGuard.mintPoToken] 的既有做法;缺它则铸出的
            // token attestation 链占位)。两条语句同一次 eval ⇒ 顺序确定。
            val ytInject = pageCtx?.let { "window.yt = {config_: ${it.ytcfgJson}};\n" } ?: ""
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    """try {
                             ${ytInject}data = $challengeData
                             runBotGuard(data).then(function (result) {
                                 this.webPoSignalOutput = result.webPoSignalOutput
                                 $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                             }, function (error) {
                                 $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                             })
                         } catch (error) {
                             $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                         }""",
                    null
                )
            }
        }
    }

    /**
     * Called during initialization by the JavaScript snippets from either
     * [downloadAndRunBotguard] or [onRunBotguardResult].
     */
    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "Initialization error from JavaScript: $error")
        }
        onInitializationError(PoTokenException(error))
    }

    /**
     * Called during initialization by the JavaScript snippet from [downloadAndRunBotguard] after
     * obtaining the BotGuard execution output [botguardResponse].
     */
    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            val response = makeBotguardServiceRequest(
                "https://www.youtube.com/api/jnn/v1/GenerateIT",
                listOf(REQUEST_KEY, botguardResponse)
            )
            val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(response)

            // leave 10 minutes of margin just to be sure
            expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600)

            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    "this.integrityToken = $integrityToken"
                ) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "initialization finished, expiration=${expirationTimeInSeconds}s")
                    }
                    generatorContinuation.resume(this@PoTokenWebView)
                }
            }
        }
    }
    //endregion

    //region Obtaining poTokens
    suspend fun generatePoToken(identifier: String): String {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "generatePoToken() called with identifier $identifier")
        }
        return suspendCancellableCoroutine { continuation ->
            poTokenContinuations[identifier] = continuation
            val u8Identifier = stringToU8(identifier)

            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript(
                    """try {
                        identifier = "$identifier"
                        u8Identifier = $u8Identifier
                        poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                    }""",
                ) {}
            }
        }
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] when an error occurs in calling the
     * JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "obtainPoToken error from JavaScript: $error")
        }
        poTokenContinuations.remove(identifier)?.resumeWithException(PoTokenException(error))
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] with the original identifier and the
     * result of the JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Generated poToken (before decoding): identifier=$identifier poTokenU8=$poTokenU8")
        }
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            poTokenContinuations.remove(identifier)?.resumeWithException(t)
            return
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Generated poToken: identifier=$identifier poToken=$poToken")
        }
        poTokenContinuations.remove(identifier)?.resume(poToken)
    }

    fun isExpired(): Boolean {
        return Instant.now().isAfter(expirationInstant)
    }
    //endregion

    //region Utils
    /**
     * Makes a POST request to [url] with the given [data] by setting the correct headers.
     * This is supposed to be used only during initialization. Returns the response body
     * as a String if the response is successful.
     */
    private suspend fun makeBotguardServiceRequest(url: String, data: List<String>): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            // 序列化为 JSON 数组,如 Create=["O43z0dpjhgX20SCx4KAo"]、GenerateIT=["O43z0...","<botguardResponse>"]。
            .post(json.encodeToString(data).toRequestBody(JsonProtobufMediaType))
            // 对齐 LibreTube ExternalApi.botguardRequest 的 @Headers(UA/Accept/Content-Type/x-goog-api-key/x-user-agent)。
            .header("User-Agent", YoutubeConstants.MobileUserAgent)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json+protobuf")
            .header("x-goog-api-key", WaaApiKey)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .build()
        val resp = httpClient.newCall(request).execute()
        resp.use { response ->
            if (response.isSuccessful) response.body?.string().orEmpty() else throw PoTokenException("HTTP ${response.code} from $url")
        }
    }

    /**
     * Handles any error happening during initialization, releasing resources and sending the error
     * to [generatorContinuation].
     */
    private fun onInitializationError(error: Throwable) {
        CoroutineScope(Dispatchers.Main).launch {
            close()
            generatorContinuation.resumeWithException(error)
        }
    }

    /**
     * Releases all [webView] resources.
     */
    @MainThread
    fun close() = with(webView) {
        clearHistory()
        // clears RAM cache and disk cache (globally for all WebViews)
        clearCache(true)

        // ensures that the WebView isn't doing anything when destroying it
        loadUrl("about:blank")

        onPause()
        removeAllViews()
        destroy()
    }
    //endregion

    companion object {
        private val TAG = PoTokenWebView::class.simpleName
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private val JS_INTERFACE = PoTokenWebView::class.simpleName!!
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val JsonProtobufMediaType = "application/json+protobuf".toMediaType()
        const val WaaApiKey = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

        suspend fun newPoTokenGenerator(
            context: Context,
            httpClient: OkHttpClient,
            videoId: String,
            pageContextSupplier: (suspend (String) -> YoutubeBotGuard.PoTokenPageContext?)? = null,
        ): PoTokenWebView {
            return suspendCancellableCoroutine { continuation ->
                Handler(Looper.getMainLooper()).post {
                    val poTokenWebView =
                        PoTokenWebView(context, httpClient, continuation, videoId, pageContextSupplier)
                    poTokenWebView.loadHtmlAndObtainBotguard(context)
                }
            }
        }
    }
}

class PoTokenException(message: String) : Exception(message)
