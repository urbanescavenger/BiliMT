package com.kirin.mt.core.youtube.newpipe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import com.kirin.mt.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * 移植 LibreTube `api/poToken/PoTokenGenerator.kt`,实现 NewPipeExtractor 的 [PoTokenProvider]。
 *
 * 与 LibreTube 原版的差异(适配 BiliTV + 修 visionOS SABR RELOAD 根因):
 *  - 铸造内核用 BiliTV 移植的 [PoTokenWebView](+ [JavaScriptUtil]),网络走 BiliTV `httpClient`。
 *  - `LibreTubeApp.instance` → 构造传入 `context.applicationContext` + `httpClient`。
 *  - **新增 [cached]**:缓存最近一枚铸好的 [PoTokenResult],供
 *    `YoutubePlaybackResolver.buildSabrSessionFromNewPipe` 取用——保证 SABR init poToken 与
 *    getInfo() 期间 NewPipe 铸造的是**同一枚**(单一 minter,根除跨 minter status=3/RELOAD)。
 *  - **新增 [ensureWebToken]**:未缓存时强制铸一枚 WEB token 并缓存(resolver 取流前兜底)。
 *  - **`getIosClientPoToken` 不再返回 null**(LibreTube 原版返回 null):改为走 `getWebClientPoToken`
 *    同路径铸 WEB token 并缓存。**这是修复关键**——NewPipe visionOS getInfo 调 getIosClientPoToken,
 *    此前返回 null → provider 缓存恒空 → resolver 回退 resolve-minted PLACEHOLDER contentBinding token
 *    → visionOS SABR RELOAD 死循环(§6.17/alpha.79 定论)。现在 iOS 路径也填缓存,resolver 拿到 NewPipe
 *    原生铸的 contentBinding 正确的 WEB token。
 *  - `getWebEmbedClientPoToken`/`getAndroidClientPoToken` 仍 null(对齐 LibreTube;仅 WEB 走 SABR)。
 *
 * NewPipe 在 `StreamInfo.getInfo` 内**同步**调用此方法。BiliTV resolve() 在 Dispatchers.IO 调 getInfo,
 * 故 [runBlocking] 阻塞的是 IO 线程;[PoTokenWebView] 内部自管线程切换(WebView 走主线程)。
 */
class NewPipePoTokenGenerator(
    private val appContext: Context,
    private val httpClient: OkHttpClient,
) : PoTokenProvider {
    private val TAG = NewPipePoTokenGenerator::class.simpleName
    private val supportsWebView by lazy { runCatching { CookieManager.getInstance() }.isSuccess }

    private object WebPoTokenGenLock
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    private var poToken: PoTokenResult? = null

    /** resolver 取流时读:与 getInfo() 期间铸造的同一枚 poToken。 */
    fun cached(): PoTokenResult? = poToken

    /** 未缓存则强制铸一枚 WEB token 并缓存。resolver 在 SABR init 前调用,保证拿到 NewPipe 铸的合法 token。 */
    fun ensureWebToken(videoId: String): PoTokenResult? {
        cached()?.let { return it }
        return getWebClientPoToken(videoId)
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!supportsWebView) {
            return null
        }

        return getWebClientPoToken(videoId, false)
            .also { poToken = it }
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     * case the current [webPoTokenGenerator] threw an error last time
     * [NewPipePoTokenGenerator.getWebClientPoToken] was called
     */
    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val (poTokenGenerator, visitorData, hasBeenRecreated) =
            synchronized(WebPoTokenGenLock) {
                val shouldRecreate = webPoTokenGenerator == null || forceRecreate || webPoTokenGenerator!!.isExpired()

                if (shouldRecreate) {
                    val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
                    innertubeClientRequestInfo.clientInfo.clientVersion =
                        YoutubeParsingHelper.getClientVersion()

                    webPoTokenVisitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                        innertubeClientRequestInfo,
                        NewPipe.getPreferredLocalization(),
                        NewPipe.getPreferredContentCountry(),
                        YoutubeParsingHelper.getYouTubeHeaders(),
                        YoutubeParsingHelper.YOUTUBEI_V1_URL,
                        null,
                        false
                    )

                    runBlocking {
                        // close the current webPoTokenGenerator on the main thread
                        webPoTokenGenerator?.let { Handler(Looper.getMainLooper()).post { it.close() } }

                        // create a new webPoTokenGenerator
                        webPoTokenGenerator = PoTokenWebView
                            .newPoTokenGenerator(appContext, httpClient)
                    }
                }

                return@synchronized Triple(
                    webPoTokenGenerator!!,
                    webPoTokenVisitorData!!,
                    shouldRecreate
                )
            }

        val poToken = try {
            // Not using synchronized here, since poTokenGenerator would be able to generate
            // multiple poTokens in parallel if needed. The only important thing is for exactly one
            // visitorData/streaming poToken to be generated before anything else.
            runBlocking {
                poTokenGenerator.generatePoToken(videoId)
            }
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                // the poTokenGenerator has just been recreated (and possibly this is already the
                // second time we try), so there is likely nothing we can do
                throw throwable
            } else {
                // retry, this time recreating the [webPoTokenGenerator] from scratch;
                // this might happen for example if NewPipe goes in the background and the WebView
                // content is lost
                Log.e(TAG, "Failed to obtain poToken, retrying", throwable)
                return getWebClientPoToken(videoId = videoId, forceRecreate = true)
            }
        }


        if (BuildConfig.DEBUG) {
            Log.d(
                TAG, "poToken for $videoId: $poToken, visitor_data=$webPoTokenVisitorData"
            )
        }

        return PoTokenResult(webPoTokenVisitorData!!, poToken, poToken)
    }

    override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String?): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String?): PoTokenResult? =
        // alpha.91:回退 null,对齐 LibreTube PoTokenGenerator.getIosClientPoToken(返回 null)。
        // 此前委托 getWebClientPoToken 铸 WEB token 填缓存——但缓存 SABR init 不用(poTokenB64=""),
        // 只让 visionOS /player 带 WEB poToken → ustreamerConfig 被绑 WEB visitor → 与 visionOS SABR
        // 会话不匹配 → init 即 RELOAD(alpha.80 真根因,误诊为 visitor mismatch)。null → visionOS getInfo
        // 不带 poToken → ustreamerConfig visitor 不绑定 → SABR 空 poToken 首请求走 status=2 懒鉴权(对齐
        // LibreTube),缓存改由 resolver 的 ensureWebToken 按需铸。见 docs/youtube-dash-fallback-plan.md。
        null
}
