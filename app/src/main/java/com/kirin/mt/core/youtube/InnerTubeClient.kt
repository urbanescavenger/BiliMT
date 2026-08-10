package com.kirin.mt.core.youtube

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * InnerTube 私有 API 的 HTTP 客户端。
 *
 * 发送 `POST https://www.youtube.com/youtubei/v1/{endpoint}?key={API_KEY}`，
 * 把 [YoutubeConstants] 里的 client context 注入 body，并带 guest 认证所需 headers。
 *
 * 协议来源：YouTube.js/FreeTube（见 [YoutubeConstants] 头注）。仅复用请求形态，独立实现。
 */
class InnerTubeClient(
  private val httpClient: OkHttpClient,
  /** 可选：WEB /player 走 WebView 原生网络栈(Chromium)时注入（对齐 FreeTubeAndroid 主 WebView）。 */
  private val jsExecutor: YoutubeJsExecutor? = null,
  /** 可选：真实浏览器会话 WebView（方案 A，对齐 FreeTubeAndroid 主 WebView）。/player 走它 + 用它的真实 visitorData/cookie。 */
  private val browserSession: YoutubeBrowserSession? = null,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
  }

  /** guest 会话的 visitorData，首次使用时生成并复用（对齐 youtubei.js 单会话）。 */
  private var visitorData: String? = null

  /** 从 sw.js_data 拉取的真实会话数据（visitorData + 当前 WEB client version）。 */
  private var realSessionData: RealSessionData? = null

  /** 保证 sw.js_data 只 fetch 一次（铸 token 与 /player 并发调用时用同一真实 visitorData）。 */
  private val sessionMutex = Mutex()

  /**
   * 发送一个 InnerTube JSON 请求。
   *
   * @param endpoint 以 / 开头的 API path，如 "/search"、"/browse"。
   * @param payload  业务字段（query/browseId/params/continuation…），context 会自动注入。
   * @param client   客户端（WEB 默认；ANDROID 供 /player 回退用，guest 取流更宽容）。
   * @param poToken  可选 PO token；非空时注入 context.serviceIntegrityDimensions。
   * @return 响应根 JsonObject。
   */
  suspend fun postJson(
    endpoint: String,
    payload: JsonObject = buildJsonObject {},
    client: Client = Client.WEB,
    poToken: String? = null,
    viaWebView: Boolean = false,
  ): JsonObject = withContext(Dispatchers.IO) {
    // 先拉真实 visitorData（WEB /player 用合成 visitorData 会被拦，见 ensureRealSessionData）。
    ensureRealSessionData()
    val body = buildJsonObject {
      // 业务字段在前，context 在后（youtubei.js 的 ...payload 后接 context）
      payload.forEach { (key, value) -> put(key, value) }
      // PO token 必须放请求【顶层】serviceIntegrityDimensions（对齐 youtubei.js Innertube.ts
      // getInfo 的 extra_payload.serviceIntegrityDimensions），不是 context 里。
      // 放错位置 → token 不被应用 → WEB /player 仍 "The page needs to be reloaded"(alpha.27 实测)。
      if (!poToken.isNullOrBlank()) {
        put("serviceIntegrityDimensions", buildJsonObject { put("poToken", poToken) })
      }
      put("context", buildContext(client = client))
    }

    // 诊断:dump /player 请求体,确认 contentPoToken 真的在顶层 serviceIntegrityDimensions 里
    // (§6.7 row 26 真机 adaptive=0 定位:排除「token 没注入请求」分支)。
    if (endpoint == "/player") {
      val sid = body["serviceIntegrityDimensions"]?.jsonObject
      val sidToken = sid?.stringOrNull("poToken")
      val ctxClient = body["context"]?.jsonObject?.obj("client")
      Log.i(
        Tag,
        "postJson /player client=$client viaWebView=$viaWebView " +
          "poTokenArg=${if (poToken.isNullOrBlank()) "null" else "${poToken.length}B"} " +
          "bodySID=${if (sid == null) "ABSENT" else "present"} " +
          "bodySIDToken=${if (sidToken.isNullOrBlank()) "EMPTY" else "${sidToken.length}B"} " +
          "cookieV1L=${currentVisitorData().take(24)} " +
          "ctxOs=${ctxClient?.stringOrNull("osName")}/${ctxClient?.stringOrNull("osVersion")} " +
          "ctxBrowser=${ctxClient?.stringOrNull("browserName")}/${ctxClient?.stringOrNull("browserVersion")} " +
          "ctxMem=${ctxClient?.stringOrNull("memoryTotalKbytes")} " +
          "bodyLen=${body.toString().length}B"
      )
    }

    val url = "${YoutubeConstants.InnerTubeBase}/${YoutubeConstants.ApiVersion}$endpoint" +
      "?key=${YoutubeConstants.ApiKey}&prettyPrint=false&alt=json"

    // WEB/WEB_EMBEDDED /player 走 WebView 原生网络栈(Chromium)，对齐 FreeTubeAndroid 主 WebView。
    // OkHttp 直连被拦("The page needs to be reloaded")、FreeTubeAndroid 能过的根因是请求没走
    // 真实浏览器网络栈。ANDROID 客户端保持 OkHttp 直连(作为回退)。
    // 方案 A：优先走真实浏览器会话 WebView（真实页上下文 + 真实 cookie/TLS），否则回退 jsExecutor 壳。
    if (viaWebView && (client == Client.WEB || client == Client.WEB_EMBEDDED || client == Client.TVHTML5)) {
      val text = if (browserSession != null) {
        browserSession.fetchViaWebView(url, "POST", buildWebViewHeaders(client), body.toString())
      } else {
        jsExecutor?.fetchViaWebView(url, "POST", buildWebViewHeaders(client), body.toString())
          ?: throw YoutubeApiException(0, "", "InnerTube $endpoint: no WebView available for viaWebView")
      }
      return@withContext runCatching { json.parseToJsonElement(text).jsonObject }
        .getOrElse { throw YoutubeApiException(0, text, "InnerTube $endpoint returned invalid JSON") }
    }

    val requestBuilder = Request.Builder()
      .url(url)
      .post(body.toString().toRequestBody(JsonMediaType))
      .header("Content-Type", "application/json")
      .header("User-Agent", client.userAgent)
      .header("Referer", YoutubeConstants.Referer)
      .header("X-Goog-Visitor-Id", currentVisitorData())
      // PO token 生效前提：请求必须带与 visitorData 配对的 VISITOR_INFO1_LIVE cookie
      // （对齐 youtubei.js Session，cookie 值 == visitorData proto）。只有 visitorData、无配对
      // cookie → YouTube 无法把 token 绑定到真实会话 → adaptive URL 被剥空（§6.7 row 31）。
      .header("Cookie", currentSessionCookies())
    when (client) {
      Client.WEB, Client.WEB_EMBEDDED -> requestBuilder
        .header("X-Youtube-Client-Version", if (client == Client.WEB) currentClientVersion() else YoutubeConstants.WebEmbeddedClientVersion)
        .header("X-Youtube-Client-Name", if (client == Client.WEB) YoutubeConstants.ClientNameId else YoutubeConstants.WebEmbeddedClientNameId)
        .header("Origin", YoutubeConstants.Referer)
        .header("Accept", "*/*")
        .header("Accept-Language", "*")
        // 对齐 FreeTubeAndroid shouldInterceptRequest 对 youtubei/ 注入的浏览器指纹头
        // (§6.8.1)。缺这些 WEB /player 更易被判"非真浏览器" → "The page needs to be reloaded"。
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-Mode", "cors")
        .header("X-Youtube-Bootstrap-Logged-In", "false")
      Client.TVHTML5 -> requestBuilder
        .header("X-Youtube-Client-Version", YoutubeConstants.TvHtml5ClientVersion)
        .header("X-Youtube-Client-Name", YoutubeConstants.TvHtml5ClientNameId)
        .header("Origin", YoutubeConstants.Referer)
        .header("Accept", "*/*")
        .header("Accept-Language", "*")
        .header("Sec-Fetch-Site", "same-origin")
        .header("Sec-Fetch-Mode", "cors")
        .header("X-Youtube-Bootstrap-Logged-In", "false")
      Client.ANDROID -> requestBuilder
        .header("X-Goog-API-Format-Version", YoutubeConstants.AndroidGoogApiFormatVersion)
        .header("X-Youtube-Client-Version", YoutubeConstants.AndroidClientVersion)
        .header("X-Youtube-Client-Name", "30")
    }

    httpClient.newCall(requestBuilder.build()).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw YoutubeApiException(
          statusCode = response.code,
          responseBody = text,
          message = "InnerTube $endpoint failed with status ${response.code}",
        )
      }
      runCatching { json.parseToJsonElement(text).jsonObject }
        .getOrElse { throw YoutubeApiException(response.code, text, "InnerTube $endpoint returned invalid JSON") }
    }
  }

  /**
   * 普通 GET,返回 body 字符串。供非 InnerTube 的轻量抓取复用共享连接池
   * (如频道 RSS `/feeds/videos.xml`)。非 2xx 抛 [YoutubeApiException]。
   */
  suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", YoutubeConstants.MobileUserAgent)
      .header("Referer", YoutubeConstants.Referer)
      .build()
    httpClient.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw YoutubeApiException(
          statusCode = response.code,
          responseBody = text,
          message = "GET $url failed with status ${response.code}",
        )
      }
      text
    }
  }

  /**
   * 拉取 BotGuard challenge（对齐 FreeTube botGuardScript.js）：
   * `POST /youtubei/v1/att/get`（ENGAGEMENT_TYPE_UNBOUND），从 `challengeData.bgChallenge`
   * 取 program/globalName/interpreterUrl，再单独 GET interpreter JS。
   *
   * 对齐后 VM 才会把 minter 填进 `webPoSignalOutput`（jnn Create 的 program 不产生 minter，
   * 真机曾报 `BgError: PMD:Undefined`）。
   *
   * 必须用 WEB context：实测(alpha.22)ANDROID context 的 /att/get 不返回 bgChallenge
   * （FreeTube 的 botGuardScript.js 也硬编码 X-Youtube-Client-Name:'1'，att/get 是 WEB 客户端的
   * challenge 通道）。故 PO token 只能铸成 WEB 绑定的；能否用于播放取决于 /player 用 WEB 还是 ANDROID。
   */
  suspend fun fetchBotGuardChallenge(): BotGuardChallenge? = withContext(Dispatchers.IO) {
    // 先拉真实 visitorData，保证铸 token 与 /player 用同一真实 visitorData（token 绑定前提）。
    ensureRealSessionData()
    val ctx = buildContext(Client.WEB)
    val ctxClient = ctx.obj("client")
    Log.i(
      Tag,
      "challenge context: os=${ctxClient?.stringOrNull("osName")}/${ctxClient?.stringOrNull("osVersion")} " +
        "browser=${ctxClient?.stringOrNull("browserName")}/${ctxClient?.stringOrNull("browserVersion")} " +
        "device=${ctxClient?.stringOrNull("deviceMake")}/${ctxClient?.stringOrNull("deviceModel")} " +
        "mem=${ctxClient?.stringOrNull("memoryTotalKbytes")} tz=${ctxClient?.stringOrNull("timeZone")}"
    )
    val body = buildJsonObject {
      put("engagementType", "ENGAGEMENT_TYPE_UNBOUND")
      put("context", ctx)
    }
    val url = "${YoutubeConstants.InnerTubeBase}/${YoutubeConstants.ApiVersion}/att/get?prettyPrint=false&alt=json"
    val request = Request.Builder()
      .url(url)
      .post(body.toString().toRequestBody(JsonMediaType))
      .header("Accept", "*/*")
      .header("Content-Type", "application/json")
      .header("X-Goog-Visitor-Id", currentVisitorData())
      .header("X-Youtube-Client-Version", currentClientVersion())
      .header("X-Youtube-Client-Name", YoutubeConstants.ClientNameId)
      .header("User-Agent", YoutubeConstants.MobileUserAgent)
      .header("Referer", YoutubeConstants.Referer)
      .header("Cookie", currentSessionCookies())
      .build()
    val text = runCatching {
      httpClient.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
    }.getOrNull()
    if (text.isNullOrBlank()) {
      Log.w(Tag, "att/get challenge fetch failed/blank")
      return@withContext null
    }
    val bg = runCatching {
      json.parseToJsonElement(text).jsonObject.obj("bgChallenge")
    }.getOrNull()
    if (bg == null) {
      Log.w(Tag, "att/get response missing bgChallenge: ${text.take(300)}")
      return@withContext null
    }
    val program = bg.stringOrNull("program")
    val globalName = bg.stringOrNull("globalName")
    val interpreterUrl = bg.obj("interpreterUrl")?.stringOrNull("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
      ?: bg.stringOrNull("interpreterUrl")
    if (program.isNullOrBlank() || globalName.isNullOrBlank() || interpreterUrl.isNullOrBlank()) {
      Log.w(Tag, "att/get challenge missing fields: program=${program?.length} global=$globalName url=$interpreterUrl")
      return@withContext null
    }
    val resolvedUrl = if (interpreterUrl.startsWith("//")) "https:$interpreterUrl" else interpreterUrl
    val interpreter = getText(resolvedUrl)
    if (interpreter.isNullOrBlank()) {
      Log.w(Tag, "att/get interpreter fetch failed/blank: $resolvedUrl")
      return@withContext null
    }
    BotGuardChallenge(interpreter, program, globalName)
  }

  private fun JsonObject.obj(name: String): JsonObject? = this[name]?.jsonObject

  private fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

  /** 从 JSPB device_info 数组按索引取字符串（越界/非字符串返回 null）。 */
  private fun JsonArray?.str(i: Int): String? = this?.getOrNull(i)?.jsonPrimitive?.contentOrNull

  /** InnerTube 客户端类型。 */
  enum class Client {
    WEB,
    WEB_EMBEDDED,
    ANDROID,
    TVHTML5;

    val userAgent: String
      get() = when (this) {
        // WEB/WEB_EMBEDDED 用移动 Chrome UA(对齐 FreeTubeAndroid,§6.7 row 37)。
        WEB, WEB_EMBEDDED -> YoutubeConstants.MobileUserAgent
        ANDROID -> YoutubeConstants.AndroidUserAgent
        // TVHTML5 用 Cobalt TV UA(对齐 YouTube 官方 TV 端,试验)。
        TVHTML5 -> YoutubeConstants.TvHtml5UserAgent
      }
  }

  private fun buildContext(client: Client = Client.WEB): JsonObject {
    return buildJsonObject {
      put(
        "client",
        buildJsonObject {
          when (client) {
            Client.WEB, Client.WEB_EMBEDDED -> {
              put("clientName", if (client == Client.WEB) YoutubeConstants.ClientName else YoutubeConstants.WebEmbeddedClientName)
              put("clientVersion", if (client == Client.WEB) currentClientVersion() else YoutubeConstants.WebEmbeddedClientVersion)
              put("hl", YoutubeConstants.Hl)
              put("gl", YoutubeConstants.Gl)
              // 对齐 youtubei.js 的 WEB context(反爬关键字段)。缺这些 WEB /player 会被判
              // "非真浏览器" → "The page needs to be reloaded"(playerErrorMessageRenderer)。
              put("platform", YoutubeConstants.WebPlatform)
              put("clientFormFactor", YoutubeConstants.WebClientFormFactor)
              put("userInterfaceTheme", YoutubeConstants.WebUserInterfaceTheme)
              put("originalUrl", YoutubeConstants.WebOriginalUrl)
              put("userAgent", YoutubeConstants.MobileUserAgent)
              put("screenWidthPoints", 1920)
              put("screenHeightPoints", 1080)
              put("screenPixelDensity", 1)
              put("screenDensityFloat", 1)
              put("utcOffsetMinutes", 0)
              put("timeZone", realSessionData?.timeZone ?: "Asia/Shanghai")
              put("clientScreen", "WATCH")
              // 浏览器指纹字段（对齐 youtubei.js #buildContext，challenge context 关键）。
              // 缺这些 BotGuard VM 产出的 token 被判无效 → adaptive URL 被剥（§6.7 row 29）。
              realSessionData?.osName?.let { put("osName", it) }
              realSessionData?.osVersion?.let { put("osVersion", it) }
              realSessionData?.browserName?.let { put("browserName", it) }
              realSessionData?.browserVersion?.let { put("browserVersion", it) }
              realSessionData?.deviceMake?.let { put("deviceMake", it) }
              realSessionData?.deviceModel?.let { put("deviceModel", it) }
              realSessionData?.deviceExperimentId?.let { put("deviceExperimentId", it) }
              realSessionData?.rolloutToken?.let { put("rolloutToken", it) }
              put("memoryTotalKbytes", "8000000")
              // mainAppWebInfo：youtubei.js #buildContext 对所有客户端无条件设置（反爬关键字段）。
              // WEB 和 WEB_EMBEDDED 都带。
              put(
                "mainAppWebInfo",
                buildJsonObject {
                  YoutubeConstants.WebMainAppWebInfo.forEach { (k, v) -> put(k, v) }
                },
              )
            }
            Client.ANDROID -> {
              put("clientName", "ANDROID")
              put("clientVersion", YoutubeConstants.AndroidClientVersion)
              put("androidSdkVersion", YoutubeConstants.AndroidSdkVersion)
              put("hl", YoutubeConstants.Hl)
              put("gl", YoutubeConstants.Gl)
            }
            // TVHTML5:YouTube 官方 TV 端 client(Cobalt 平台)。试验 TV 端取流,
            // 对齐 yt-dlp clientName=TVHTML5/clientNameId=7。context 比 WEB 简单(无浏览器指纹/
            // mainAppWebInfo/clientScreen,TV 原生 client 不带这些)。visitorData 复用 WEB session
            // (先试,真机看是否因 client version 7.x vs WEB 2.x 不配对被拒)。
            Client.TVHTML5 -> {
              put("clientName", YoutubeConstants.TvHtml5ClientName)
              put("clientVersion", YoutubeConstants.TvHtml5ClientVersion)
              put("hl", YoutubeConstants.Hl)
              put("gl", YoutubeConstants.Gl)
              put("platform", "TV")
              put("clientFormFactor", "UNKNOWN_FORM_FACTOR")
              put("userInterfaceTheme", YoutubeConstants.WebUserInterfaceTheme)
              put("userAgent", YoutubeConstants.TvHtml5UserAgent)
              put("screenWidthPoints", 1920)
              put("screenHeightPoints", 1080)
              put("screenPixelDensity", 1)
              put("screenDensityFloat", 1)
              put("utcOffsetMinutes", 0)
              put("timeZone", realSessionData?.timeZone ?: "Asia/Shanghai")
            }
          }
          put("visitorData", currentVisitorData())
        },
      )
      put(
        "user",
        buildJsonObject {
          put("enableSafetyMode", false)
          put("lockedSafetyMode", false)
        },
      )
      put(
        "request",
        buildJsonObject {
          put("useSsl", true)
          put("internalExperimentFlags", kotlinx.serialization.json.buildJsonArray {})
        },
      )
      // WEB 客户端 /player 必须带 thirdParty.embedUrl，否则返回 "Video unavailable"/
      // "The page needs to be reloaded"(实测 alpha.22,即使带有效 PO token 也被拦)。
      // WEB_EMBEDDED 是嵌入式播放器也带 embedUrl；ANDROID 客户端不需要此字段。
      if (client == Client.WEB || client == Client.WEB_EMBEDDED) {
        put(
          "thirdParty",
          buildJsonObject { put("embedUrl", YoutubeConstants.EmbedUrl) },
        )
      }
      // 注意：serviceIntegrityDimensions.poToken 已移到请求顶层（见 postJson），
      // 不再放 context 里（对齐 youtubei.js）。
    }
  }

  /** WEB/WEB_EMBEDDED /player 走 WebView 时的请求头（对齐 OkHttp WEB 分支 + 会话配对 Cookie）。 */
  private fun buildWebViewHeaders(client: Client = Client.WEB): Map<String, String> {
    val clientNameId = when (client) {
      Client.WEB -> YoutubeConstants.ClientNameId
      Client.WEB_EMBEDDED -> YoutubeConstants.WebEmbeddedClientNameId
      Client.ANDROID -> "30"
      Client.TVHTML5 -> YoutubeConstants.TvHtml5ClientNameId
    }
    val clientVersion = when (client) {
      Client.WEB -> currentClientVersion()
      Client.WEB_EMBEDDED -> YoutubeConstants.WebEmbeddedClientVersion
      Client.ANDROID -> YoutubeConstants.AndroidClientVersion
      Client.TVHTML5 -> YoutubeConstants.TvHtml5ClientVersion
    }
    val headers = mutableMapOf(
      "Content-Type" to "application/json",
      "User-Agent" to client.userAgent,
      "Referer" to YoutubeConstants.Referer,
      "X-Goog-Visitor-Id" to currentVisitorData(),
      "X-Youtube-Client-Version" to clientVersion,
      "X-Youtube-Client-Name" to clientNameId,
      "Origin" to YoutubeConstants.Referer,
      "Accept" to "*/*",
      "Accept-Language" to "*",
      "Sec-Fetch-Site" to "same-origin",
      "Sec-Fetch-Mode" to "cors",
      "X-Youtube-Bootstrap-Logged-In" to "false",
      // PO token 生效前提：/player 必须带与 visitorData 配对的 VISITOR_INFO1_LIVE cookie
      // （对齐 youtubei.js Session，cookie 值 == visitorData proto）。WebView cookie store 里是
      // botguard VM 页自己的不配对 cookie，须显式覆盖为会话 cookie（§6.7 row 31/32）。
      "Cookie" to currentSessionCookies(),
    )
    return headers
  }

  /** 当前 visitorData（真实会话优先，否则合成）。GenerateIT 需带它把 integrityToken 绑定到会话。 */
  fun currentVisitorData(): String {
    val cached = visitorData
    if (cached != null) return cached
    val generated = encodeVisitorData(
      id = randomId(),
      timestamp = System.currentTimeMillis() / 1000L,
    )
    visitorData = generated
    return generated
  }

  /** 当前 WEB client version：优先用 sw.js_data 拉到的真实版本，否则回退硬编码。 */
  private fun currentClientVersion(): String {
    return realSessionData?.clientVersion ?: YoutubeConstants.ClientVersion
  }

  /** 当前完整会话 cookie（CONSENT/SOCS/VISITOR_INFO1_LIVE/PREF）；未捕获到则降级仅 VISITOR_INFO1_LIVE=V。 */
  fun currentSessionCookies(): String {
    return realSessionData?.sessionCookies
      ?: "VISITOR_INFO1_LIVE=${currentVisitorData()}; PREF=tz=Asia.Shanghai"
  }

  /**
   * SABR StreamerContext.ClientInfo（对齐 googlevideo `streamer_context.proto` ClientInfo）。
   * 用 sw.js_data 拉到的真实浏览器指纹字段（WEB 绑定，对齐 poToken 是 WEB challenge 铸的）。
   * clientName 用 X-Youtube-Client-Name 的数值（WEB=1）；clientFormFactor=UNKNOWN_FORM_FACTOR(0)。
   */
  internal fun sabrClientInfo(): com.kirin.mt.core.youtube.sabr.ClientInfoInput {
    val d = realSessionData
    return com.kirin.mt.core.youtube.sabr.ClientInfoInput(
      deviceMake = d?.deviceMake,
      deviceModel = d?.deviceModel,
      clientName = YoutubeConstants.ClientNameId.toIntOrNull(),
      clientVersion = d?.clientVersion ?: YoutubeConstants.ClientVersion,
      osName = d?.osName,
      osVersion = d?.osVersion,
      acceptLanguage = "${YoutubeConstants.Hl}-${YoutubeConstants.Gl}",
      acceptRegion = YoutubeConstants.Gl,
      screenWidthPoints = 1920,
      screenHeightPoints = 1080,
      screenPixelDensity = 1,
      clientFormFactor = 0,
      timeZone = d?.timeZone,
    )
  }

  /**
   * path C(NewPipe visionOS 取流):SABR StreamerContext 的 ClientInfo **必须用 visionOS 客户端**——
   * NewPipe getInfo 走的 visionOS /player 返回的 ustreamerConfig 绑定 visionOS 客户端,配 WEB client
   * info(clientName=1)会被服务端 RELOAD_PLAYER 整体拒(alpha.75 真机:所有 init 全拒)。逐字对齐
   * LibreTube `SabrClient.kt:398-405`(clientName=101/clientVersion=1.02/Apple/RealityDevice14,1/visionOS)。
   */
  internal fun visionOsSabrClientInfo(): com.kirin.mt.core.youtube.sabr.ClientInfoInput =
    com.kirin.mt.core.youtube.sabr.ClientInfoInput(
      deviceMake = "Apple",
      deviceModel = "RealityDevice14,1",
      clientName = 101,
      clientVersion = "1.02",
      osName = "visionOS",
      osVersion = "25.6.0.23O471",
    )


  /**
   * 拉取真实 visitorData（对齐 youtubei.js `generateSessionLocally:false` 的 #getSessionData）。
   *
   * 实测(alpha.24)：WEB /player 用**合成** visitorData 会被判"非真浏览器" → 返回
   * "The page needs to be reloaded"(playerErrorMessageRenderer)，即使带有效 PO token 也被拦。
   * FreeTubeAndroid 用 `createInnertube({ generateSessionLocally:false })` 从
   * `https://www.youtube.com/sw.js_data` 取真实 visitorData + 当前 client version。
   * 失败回退合成 visitorData（不阻塞主路径）。
   */
  private suspend fun ensureRealSessionData() {
    // 双检锁：铸 token(BotGuard 线程)与 /player 并发调用时，保证只 fetch 一次，
    // 否则各自 fetch 到不同 visitorData → token 绑定 A、/player 用 B → token 无效
    // → "The page needs to be reloaded"(alpha.25 实测)。
    if (realSessionData != null) return
    sessionMutex.withLock {
      if (realSessionData != null) return
      // 方案 A：优先用真实浏览器会话的 visitorData + cookie（对齐 FreeTubeAndroid 主 WebView）。
      // 先确保真实 YouTube 页面加载，读它的 VISITOR_INFO1_LIVE cookie 作 visitorData（cookie 值 ==
      // visitorData proto，§6.7 row 31 确认）。失败回退 sw.js_data 的 visitorData。
      val browserVisitor = browserSession?.let {
        runCatching { it.ensureLoaded() }.getOrNull()
        it.readVisitorData()
      }
      val data = runCatching { fetchRealSessionData() }.getOrNull()
      if (data != null) {
        val effectiveVisitor = browserVisitor ?: data.visitorData
        val browserCookies = browserSession?.readCookies()
        val effectiveCookies = if (browserCookies.isNullOrBlank()) data.sessionCookies else browserCookies
        realSessionData = data.copy(visitorData = effectiveVisitor, sessionCookies = effectiveCookies)
        visitorData = effectiveVisitor
        Log.i(
          Tag,
          "real session data: visitorData=${effectiveVisitor.take(24)}... " +
            "(browser=${browserVisitor != null}) clientVersion=${data.clientVersion}"
        )
      } else {
        Log.w(Tag, "sw.js_data fetch failed; fallback to synthetic visitorData")
      }
    }
  }

  private suspend fun fetchRealSessionData(): RealSessionData? = withContext(Dispatchers.IO) {
    // 不注入随机 VISITOR_INFO1_LIVE：响应 body 的 device_info[13] 才是 YouTube 为本会话生成的真实
    // visitorData，用它本身作 /player 的配对 cookie（见 buildWebViewHeaders / postJson Cookie 头）。
    val request = Request.Builder()
      .url("https://www.youtube.com/sw.js_data")
      .header("Accept-Language", "en-US")
      // 移动 UA 请求 → sw.js_data 报 Android context(osName/browserName 等 device_info),
      // 让铸 token 的 context 与真实设备一致(对齐 FreeTubeAndroid,§6.7 row 37)。
      .header("User-Agent", YoutubeConstants.MobileUserAgent)
      .header("Accept", "*/*")
      .header("Referer", "https://www.youtube.com/sw.js")
      .header("Cookie", "PREF=tz=Asia.Shanghai")
      .build()
    val text = runCatching {
      httpClient.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
    }.getOrNull()
    if (text.isNullOrBlank() || !text.startsWith(")]}'")) return@withContext null
    val root = runCatching { json.parseToJsonElement(text.removePrefix(")]}'").trim()).jsonArray }.getOrNull()
      ?: return@withContext null
    // JSPB 结构：data[0][2]=ytcfg；ytcfg[0][0]=device_info。
    // device_info 索引对齐 youtubei.js getSessionData：13=visitorData，16=clientVersion，
    // 11/12=deviceMake/Model，17/18=osName/Version，79=timeZone，86/87=browserName/Version，
    // 103=deviceExperimentId，107=rolloutToken。这些浏览器指纹字段是 challenge context 的关键
    // （缺了 BotGuard VM 产出的 token 被判无效 → adaptive URL 被剥，§6.7 row 29）。
    val ytcfg = root.getOrNull(0)?.jsonArray?.getOrNull(2)?.jsonArray ?: return@withContext null
    val deviceInfo = ytcfg.getOrNull(0)?.jsonArray?.getOrNull(0)?.jsonArray ?: return@withContext null
    val visitor = deviceInfo.str(13)
    if (visitor.isNullOrBlank()) return@withContext null
    val version = deviceInfo.str(16)
    // 完整会话 cookie：抓真实首页拿 CONSENT/SOCS 等（YouTube 对无 consent cookie 的会话判
    // "未同意浏览"，常只回 progressive 不给 adaptive，§6.7 row 32）。失败降级仅 VISITOR_INFO1_LIVE。
    val sessionCookies = captureSessionCookies(visitor)
    Log.i(Tag, "session cookies: ${sessionCookies?.take(160) ?: "NONE (fallback VISITOR_INFO1_LIVE only)"}")
    RealSessionData(
      visitorData = visitor,
      clientVersion = version ?: YoutubeConstants.ClientVersion,
      osName = deviceInfo.str(17),
      osVersion = deviceInfo.str(18),
      browserName = deviceInfo.str(86),
      browserVersion = deviceInfo.str(87),
      deviceMake = deviceInfo.str(11),
      deviceModel = deviceInfo.str(12),
      timeZone = deviceInfo.str(79),
      deviceExperimentId = deviceInfo.str(103),
      rolloutToken = deviceInfo.str(107),
      sessionCookies = sessionCookies,
    )
  }

  /**
   * 抓真实首页 `https://www.youtube.com/` 捕获完整会话 cookie（CONSENT/SOCS/VISITOR_INFO1_LIVE/PREF），
   * 对齐 youtubei.js Session（首页加载时 Set-Cookie 一整套）。失败返回 null（调用方降级仅 VISITOR_INFO1_LIVE）。
   */
  private suspend fun captureSessionCookies(visitorId: String): String? = withContext(Dispatchers.IO) {
    val request = Request.Builder()
      .url("https://www.youtube.com/")
      .header("User-Agent", YoutubeConstants.MobileUserAgent)
      .header("Accept-Language", "en-US")
      .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
      .header("Cookie", "VISITOR_INFO1_LIVE=$visitorId; PREF=tz=Asia.Shanghai")
      .build()
    val captured = linkedMapOf<String, String>()
    val ok = runCatching {
      httpClient.newCall(request).execute().use { resp ->
        resp.headers("Set-Cookie").forEach { sc ->
          val name = sc.substringBefore("=").trim()
          val value = sc.substringAfter("=").substringBefore(";").trim()
          if (name in setOf("CONSENT", "SOCS", "VISITOR_INFO1_LIVE", "PREF", "YSC")) captured[name] = value
        }
        resp.body?.close()
      }
      true
    }.getOrDefault(false)
    if (!ok || captured.isEmpty()) return@withContext null
    // 显式保证 VISITOR_INFO1_LIVE = visitorData（token 会话配对前提）。
    captured["VISITOR_INFO1_LIVE"] = visitorId
    captured.entries.joinToString("; ") { (k, v) -> "$k=$v" }
  }

  /** sw.js_data 拉取的真实会话数据（含浏览器指纹字段，供 challenge context 用）。 */
  private data class RealSessionData(
    val visitorData: String,
    val clientVersion: String,
    val osName: String?,
    val osVersion: String?,
    val browserName: String?,
    val browserVersion: String?,
    val deviceMake: String?,
    val deviceModel: String?,
    val timeZone: String?,
    val deviceExperimentId: String?,
    val rolloutToken: String?,
    val sessionCookies: String?,
  )

  // ---- visitorData 编码（对齐 youtubei.js ProtoUtils.encodeVisitorData） ----

  private fun encodeVisitorData(id: String, timestamp: Long): String {
    val idBytes = id.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream()
    // field 1 (tag 0x0A): id — length-delimited string
    out.write(0x0A)
    writeVarint(idBytes.size.toLong(), out)
    out.write(idBytes)
    // field 5 (tag 0x28): timestamp — varint int32
    out.write(0x28)
    writeVarint(timestamp, out)

    val base64url = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
      .replace('+', '-')
      .replace('/', '_')
    return uriComponent(base64url)
  }

  private fun writeVarint(value: Long, out: ByteArrayOutputStream) {
    var v = value
    while (true) {
      val b = (v and 0x7F).toInt()
      v = v ushr 7
      if (v == 0L) {
        out.write(b)
        return
      }
      out.write(b or 0x80)
    }
  }

  /** JS encodeURIComponent 等价：仅保留 [A-Za-z0-9-_.~]，其余百分号编码。 */
  private fun uriComponent(input: String): String {
    val sb = StringBuilder()
    for (b in input.toByteArray(Charsets.UTF_8)) {
      val c = b.toInt() and 0xFF
      val ch = c.toChar()
      if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
        ch == '-' || ch == '_' || ch == '.' || ch == '~'
      ) {
        sb.append(ch)
      } else {
        sb.append('%').append("%02X".format(c))
      }
    }
    return sb.toString()
  }

  private fun randomId(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    val sb = StringBuilder(11)
    repeat(11) {
      sb.append(chars[(Math.random() * chars.length).toInt()])
    }
    return sb.toString()
  }

  private companion object {
    const val Tag = "YtBotGuard"
    val JsonMediaType = "application/json; charset=utf-8".toMediaType()
  }
}

class YoutubeApiException(
  val statusCode: Int,
  val responseBody: String,
  message: String,
) : Exception(message)

/** BotGuard challenge（对齐 FreeTube `challengeData.bgChallenge`）。 */
data class BotGuardChallenge(
  val interpreterJavascript: String?,
  val program: String?,
  val globalName: String?,
)
