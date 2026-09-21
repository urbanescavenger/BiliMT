package com.kirin.mt.core.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * YouTube PO token（Proof-of-Origin）生成，基于 **bgutils-js(MIT)** 打包进隐藏 WebView。
 *
 * 无 PO token 时 YouTube 会剥掉 adaptive 高清流 url（只剩 progressive 360p），
 * 这是高清(1080P/2K/4K)的唯一前置。流程(P11-101 Phase 2d + P11-103,对齐 FreeTube 9607/9637
 * /c42fee2c7 现行法)：
 *  0. **桌面 UA** 拉 watch 页 HTML(移动 UA 被 302 且页面无 ytAtN,r1932 实锤)→ 提取 ytcfg +
 *     ytAtN({R: bgChallenge, T: eacrToken};loose JSON,bgutils-js parseLooseJSON 同款解析);
 *     watch 页无效→整个会话回退主页(主页同样带 ytcfg+ytAtN,FreeTube 9637);注入 window.yt={config_}。
 *  1. challenge 优先级:页面内嵌 bgChallenge → `POST /youtubei/v1/att/get`(ENGAGEMENT_TYPE_UNBOUND
 *     + eacrToken,FreeTube botGuardScript 同款)→ Create 端点(旧法兜底)。
 *     `bgChallenge` { program, globalName, interpreterUrl },interpreter 单独 GET。
 *     ⚠️ 落到 create = attestation 链占位 → SABR 逐请求 status=2、60s 升级 status=3(r1932 实锤),
 *     页面挑战是唯一完整链源,create 只是残路。
 *  2. 隐藏 WebView eval interpreter JS → 定义 `window[globalName]`。
 *  3. `__runSnapshot`(bgutils BotGuardClient.create + snapshot,只传 webPoSignalOutput)→ botguardResponse。
 *  4. `POST Waa/GenerateIT`([requestKey, botguardResponse])→ integrityToken。
 *  5. `__mint`(bgutils WebPoMinter)→ 视频 ID 绑定的 PO token。
 *
 * 网络由 Kotlin 发（WebView 跨源 fetch 被 CORS 拦），WebView 只执行 interpreter JS + WASM。
 * 任一步失败返回 null，绝不阻塞"无 PO token 直连 /player"主路径。
 *
 * 脆弱点（需真机迭代）：interpreter JS/WASM 能否通过 BotGuard 运行时校验、snapshot 是否
 * 把 minter 填进 webPoSignalOutput（jnn Create 的 program 不产生，须用 /att/get）、GenerateIT 响应结构。
 */
class YoutubeBotGuard(
  private val executor: YoutubeJsExecutor,
  private val httpClient: OkHttpClient,
  private val innerTubeClient: InnerTubeClient,
) {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /**
   * 尝试生成一个视频 ID 绑定的 PO token。失败/不可用返回 null。
   */
  suspend fun generatePoToken(videoId: String): String? {
    return withContext(Dispatchers.IO) {
      runCatching { withTimeoutOrNull(OverallTimeoutMs) { mintPoToken(videoId) } }
        .onFailure { Log.w(Tag, "PO token failed: ${it.message ?: it::class.simpleName}") }
        .getOrNull()
    }
  }

  private suspend fun mintPoToken(videoId: String): String? {
    if (!executor.loadBgUtilsBundle()) {
      Log.w(Tag, "bgutils bundle load failed")
      return null
    }
    // P11-101 Phase 2d + P11-103(对齐 FreeTube 9607/9637/c42fee2c7 现行铸造法):
    // ① 桌面 UA 拉 watch 页 HTML 提取 ytcfg + ytAtN(R=页面内嵌 bgChallenge,T=eacrToken;
    //    loose JSON 解析;watch 页无效整个会话回退主页);
    // ② 注入 window.yt = {config_: ytConfig}(interpreter VM 读 EVENT_ID 等——首版缺它,
    //    铸出的 token attestation 链占位,60s re-attestation 被拒:status=2→refresh→status=3);
    // ③ challenge 优先级(P11-117 改):/att/get(**带完整 context,无 eacrToken**,FreeTube 同款)
    //    → 页面 bgChallenge → Create 端点(旧法兜底)。
    val pageData = fetchPageData(videoId)
    pageData?.ytConfig?.let { cfg ->
      // window.yt 注入必须先于 interpreter eval(BotGuard VM 启动时读 window.yt.config_)。
      // cfg 是合法 JSON 对象文本,原样 splice 进 eval 即为合法 JS 对象字面量。
      val inject = executor.eval("window.yt = { config_: ${cfg} }; typeof window.yt")
      Log.i(Tag, "page ytcfg injected: $inject (${cfg.toString().length}B)")
    }
    // 1) 拿 challenge 并 descramble。
    // P11-117:顺序改为 **/att/get 优先**(对齐 FreeTube——它以 /att/get 为唯一来源)。此前页面 bgChallenge
    // 优先:挑战来自 OkHttp 桌面 UA 抓的 watch 页,兑换却用移动身份,跨身份兑换是 13 轮的盲区。
    // 页面 bgChallenge / Create 端点保留为兜底。
    val challenge = attGetChallenge()
      ?: challengeFromPage(pageData)
      ?: fetchChallenge()
      ?: return null
    val interpreterJs = challenge.interpreterJavascript ?: return null
    val program = challenge.program ?: return null
    val globalName = challenge.globalName ?: return null
    Log.i(Tag, "challenge ok: source=${challenge.source} interpreter=${interpreterJs.length}B program=${program.length}B global=$globalName")

    // 2) 加载 interpreter JS 进 WebView（定义 window[globalName]）。
    // P11-110:优先 <script src> 标签加载(对齐 FreeTube botGuardScript.js 的 script 元素加载 +
    // FreeTubeAndroid bgwebview 同款)——两参照都不用 eval;BotGuard VM 对脚本执行上下文敏感
    // (c42fee2c7 专门把 fetch+new Function 改成 script 元素)。eval 文本路径保留作兜底。
    var interpreterLoaded = false
    challenge.interpreterUrl?.let { url ->
      interpreterLoaded = loadInterpreterViaScript(url)
      Log.i(Tag, "interpreter script-tag load: $interpreterLoaded")
    }
    if (!interpreterLoaded) {
      val interpreterJs = challenge.interpreterJavascript ?: return null
      // 用 try-catch 包裹捕获 interpreter JS 的运行时错误（evaluateJavascript 对抛错脚本返回 null）。
      val wrappedInterpreter = "try { $interpreterJs } catch(e) { window.__interpreterError = String(e && e.stack || e); }"
      val interpreterEval = executor.eval(wrappedInterpreter)
      Log.i(Tag, "interpreter eval result=${interpreterEval?.take(60)}")
    }
    val interpreterError = executor.eval("window.__interpreterError")
    Log.i(Tag, "interpreter error: $interpreterError")
    // 确认 window[globalName] 是否真的定义了。
    val globalCheck = executor.eval("typeof window.$globalName")
    Log.i(Tag, "global $globalName typeof=$globalCheck")
    if (interpreterError != null && interpreterError != "null" && interpreterError.isNotBlank()) {
      Log.w(Tag, "interpreter JS threw: $interpreterError")
      return null
    }

    // 3) snapshot → botguardResponse。
    val contentBinding = buildContentBinding(videoId)
    val snapshotState = runSnapshot(program, globalName, contentBinding) ?: return null
    val botguardResponse = snapshotState.stringOrNull("botguardResponse") ?: return null

    // 4) GenerateIT → integrityToken。
    val integrityToken = generateIntegrityToken(botguardResponse) ?: return null

    // 5) mint → PO token。
    return mintToken(integrityToken, videoId)
  }

  // ---- challenge 获取（对齐 FreeTube /att/get） ----

  private data class Challenge(
    val interpreterJavascript: String?,
    val program: String?,
    val globalName: String?,
    /** P11-101 Phase 2d:挑战来源诊断(page-bgChallenge / att-get / create)。 */
    val source: String = "create",
    /** P11-110:interpreter CDN URL——有则用 <script> 标签加载(两参照同款),无则 eval 兜底。 */
    val interpreterUrl: String? = null,
  )

  private suspend fun fetchChallenge(): Challenge? {
    val c = innerTubeClient.fetchBotGuardChallenge() ?: return null
    return Challenge(c.interpreterJavascript, c.program, c.globalName, source = "create")
  }

  // ---- P11-101 Phase 2d:watch 页 HTML 提取(ytcfg + ytAtN)+ 页面/att-get 挑战链 ----

  private data class PageData(
    val ytConfig: JsonObject?,
    /**
     * window.ytAtN({...}) 的数据:{ R: { bgChallenge: { program, globalName, interpreterUrl } }, T: eacrToken }。
     * 原始 R 是 hex 转义的 JSON 字符串,parseLooseJson 已递归解开成对象。
     */
    val attestationData: JsonObject?,
    /**
     * P11-106:桌面 watch 页 ytcfg 的 INNERTUBE_CONTEXT(桌面 WEB 会话身份:osName=Windows +
     * visitorData 等)——WEB-SABR 的 /player context + clientInfo 全链用它(FreeTube
     * buildSessionFromYtConfig 同款)。
     */
    val webContext: JsonObject?,
    /** P11-106:watch 页响应的 Set-Cookie(桌面页会话 cookie,SABR POST 身份用)。 */
    val webCookie: String?,
  )

  private var cachedPageData: Pair<String, PageData>? = null

  /**
   * P11-103(对齐 FreeTube 9637):watch 页一次抓不到有效数据(ytcfg/ytAtN 缺失=captcha/风控信号)
   * 就整个会话改用主页——主页同样带 ytcfg + ytAtN,且一次失败预示后续会持续失败。
   */
  private var homepageFallback = false

  private suspend fun fetchPageData(videoId: String): PageData? {
    cachedPageData?.let { (vid, data) -> if (vid == videoId) return data }
    if (homepageFallback) {
      val home = fetchHtmlPageData("https://www.youtube.com/", "home")
      if (home != null) cachedPageData = videoId to home
      return home
    }
    val watch = fetchHtmlPageData(
      "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1", "watch",
    )
    // P11-106 修复:watch 结果必须写缓存——webSessionIdentity() 从缓存取桌面会话身份,
    // 首版漏写导致桌面 override 恒 null(r1937 日志:page data ctx=true 但 /player 仍 ctxOs=Android/13)。
    // 顺带恢复原语义:同 videoId 的 mint 不重复抓页(每页 ~1.4MB)。
    if (watch != null) {
      cachedPageData = videoId to watch
      return watch
    }
    Log.w(Tag, "watch page unusable → homepage fallback for the rest of the session (FreeTube 9637)")
    homepageFallback = true
    val home = fetchHtmlPageData("https://www.youtube.com/", "home")
    if (home != null) cachedPageData = videoId to home
    return home
  }

  private suspend fun fetchHtmlPageData(url: String, kind: String): PageData? {
    // P11-103(对齐 FreeTube c42fee2c7 "Spoof desktop user agent to scrape the right version of
    // the watch page"):桌面 UA 才拿得到含 window.ytAtN 的桌面版页面——移动 UA 实测被 302,
    // 跟随重定向后的页面无 ytAtN(2026-09-15 日志:ytAtN=false → challenge 全落 create → token
    // attestation 链占位 → SABR status=2 逐请求拦截、60s 升级 status=3)。
    var pageCookie: String? = null
    val page = withContext(Dispatchers.IO) {
      runCatching {
        val req = Request.Builder()
          .url(url)
          .header("User-Agent", YoutubeConstants.UserAgent)
          .header("Accept-Language", "en-US")
          .build()
        httpClient.newCall(req).execute().use { resp ->
          // P11-106:捕获桌面页会话 cookie(Set-Cookie)供 SABR POST 身份配对。
          pageCookie = resp.headers("Set-Cookie").joinToString("; ") { it.substringBefore(';') }
            .takeIf { it.isNotBlank() }
          resp.body?.string().orEmpty()
        }
      }.getOrNull()
    }
    if (page.isNullOrBlank()) {
      Log.w(Tag, "$kind page fetch failed/blank")
      return null
    }
    // ⚠️ Java Pattern 与 JS 不同:`{` 不转义会被当量词解析 → "Syntax error near index 14"
    //(r1926 真机实锤,整轮铸造失败)。花括号一律转义。
    val ytcfgStr = Regex("""ytcfg\.set\((\{.+?\})\);""", RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1)
    val ytConfig = ytcfgStr?.let { s -> runCatching { json.parseToJsonElement(s).jsonObject }.getOrNull() }
    // P11-106:桌面 WEB 会话身份(桌面 ytcfg 的 INNERTUBE_CONTEXT,osName=Windows + visitorData)。
    val webContext = ytConfig?.obj("INNERTUBE_CONTEXT")
    val attestation = findYtAtN(page)?.let { parseLooseJson(it) }
    if (ytConfig == null) Log.w(Tag, "$kind page: ytcfg missing/parse failed")
    if (attestation == null) Log.w(Tag, "$kind page: ytAtN missing/parse failed")
    Log.i(Tag, "$kind page data: ytcfg=${ytConfig != null} ytAtN=${attestation != null} " +
      "ctx=${webContext != null} cookie=${pageCookie?.length ?: 0}B (page=${page.length}B)")
    // FreeTube 9637:ytcfg 与 challenge 都是 botguard 必需,缺任一视为本页无效。
    if (ytConfig == null || attestation == null) return null
    return PageData(ytConfig, attestation, webContext, pageCookie)
  }

  /**
   * P11-106:桌面 watch 页会话身份(WEB-SABR 全链一致桌面化的数据源)。
   * context = 桌面 ytcfg 的 INNERTUBE_CONTEXT(osName=Windows + visitorData);cookie = 页面 Set-Cookie。
   * FreeTube buildSessionFromYtConfig 的会话即由这套身份构造——/player、SABR clientInfo、UA 全对齐。
   * 无缓存(铸 token 前没抓过页)返回 null,调用方回退旧身份。
   */
  data class WebSessionIdentity(val context: JsonObject, val cookie: String?)

  fun webSessionIdentity(): WebSessionIdentity? {
    val cached = cachedPageData ?: return null
    val ctx = cached.second.webContext ?: return null
    return WebSessionIdentity(ctx, cached.second.webCookie)
  }

  // ---- P11-154:arm A 的「页面铸造上下文」(移动 MWEB 页:挑战 + 该挑战绑定的那份 ytcfg) ----

  /**
   * P11-154:arm A([PoTokenWebView],LibreTube 移植)的**页面铸造上下文**——挑战 **与该挑战所绑定的
   * 那份 ytcfg** 一并取自**同一张**移动 watch 页。
   *
   * **为什么必须成对**:调研(BgUtils #44 / PipePipeClient #86 / bgutil #243)指出 attestation challenge
   * 绑的是 `yt.config_.EVENT_ID`;只有挑战、文档里却没有同一份 EVENT_ID 是**构造上就不成立的错配**,
   * 单独测它分不清「页面挑战无效」与「我们配对错了」。故二者一并交付,任一缺失即整体作废。
   *
   * **为什么是移动页**:历史唯一拿到 `status=1` 的 token 都出自真 watch 页自铸,而那些页全是 MWEB
   * (`harvest ident host=m.youtube.com cfgName=MWEB cfgOs=Android`,真机 37/37)。桌面链
   * (`fetchPageData` + `/att/get`)已被 r2034 实测判死(`playability=UNPLAYABLE`,够不到 SABR 请求)。
   */
  data class PoTokenPageContext(
    /** 可直接喂 `po_token.html` 的 `runBotGuard(data)` 的挑战对象(已含 interpreter JS 文本)。 */
    val challengeJson: String,
    /** 该页 `ytcfg` 的 JSON 文本,注入 `window.yt = {config_: …}` 用。 */
    val ytcfgJson: String,
    /** `ytcfg.EVENT_ID` 前 8 位(日志判据;非空是发车的硬条件之一)。 */
    val eventId: String,
    /** 诊断字段(程序/interpreter/页面大小),供日志一行说清。 */
    val diag: String,
  )

  /** P11-154:per-videoId 缓存(含 null 结果——避免同一视频反复白拉 1MB 页)。 */
  private var cachedArmAPageContext: Pair<String, PoTokenPageContext?>? = null

  /**
   * P11-154:取 arm A 的页面上下文。**拿不到就返回 null**,调用方整体回落 Create(与改动前逐字节一致)。
   */
  suspend fun fetchArmAPageContext(videoId: String, visitorData: String? = null): PoTokenPageContext? =
    withTimeoutOrNull(ArmAPageContextTimeoutMs) {
      withContext(Dispatchers.IO) {
        cachedArmAPageContext?.let { (vid, ctx) -> if (vid == videoId) return@withContext ctx }
        val ctx = runCatching { buildArmAPageContext(videoId, visitorData) }
          .onFailure { Log.w(Tag, "armA page ctx failed: ${it.message ?: it::class.simpleName}") }
          .getOrNull()
        cachedArmAPageContext = videoId to ctx
        ctx
      }
    }

  private suspend fun buildArmAPageContext(videoId: String, visitorData: String?): PoTokenPageContext? {
    // 直取 m.youtube.com(不走 www + 302):确定性,且与历史上铸出被接受 token 的那张页同类。
    val url = "https://m.youtube.com/watch?v=$videoId"
    val started = System.currentTimeMillis()
    val page = withContext(Dispatchers.IO) {
      runCatching {
        val builder = Request.Builder().url(url)
          .header("User-Agent", YoutubeConstants.MobileUserAgent)
          .header("Accept-Language", "en-US")
        // 移动腿缺 VISITOR_INFO1_LIVE 会以空会话起(harvest 同款经验)——有则带上。
        if (!visitorData.isNullOrBlank()) builder.header("Cookie", "VISITOR_INFO1_LIVE=$visitorData")
        httpClient.newCall(builder.build()).execute().use { it.body?.string().orEmpty() }
      }.getOrNull()
    }
    val elapsed = System.currentTimeMillis() - started
    if (page.isNullOrBlank()) {
      Log.w(Tag, "armA page ctx: GET $url → blank (${elapsed}ms) → UNUSABLE → 整体回落 Create")
      return null
    }
    val ytcfg = findMergedYtcfg(page)
    val eventId = ytcfg?.stringOrNull("EVENT_ID")
    val attestation = findYtAtN(page)?.let { parseLooseJson(it) }
    val bg = attestation?.obj("R")?.obj("bgChallenge")
    val program = bg?.stringOrNull("program")
    val globalName = bg?.stringOrNull("globalName")
    val interpreterUrl = bg?.obj("interpreterUrl")
      ?.stringOrNull("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
      ?.takeIf { it.isNotBlank() }
      ?.let { if (it.startsWith("//")) "https:$it" else it }
    val interpreterJs = interpreterUrl?.let { fetchInterpreterJsText(it) }
    Log.i(
      Tag,
      "armA page ctx: GET $url → ${page.length}B (${elapsed}ms) ytcfg=${ytcfg != null}" +
        " eventId=${eventId?.take(8) ?: "NONE"} ytAtN=${attestation != null}" +
        " program=${program?.length ?: 0}B interpreter=${interpreterJs?.length ?: 0}B",
    )
    // 原子性:任一必需项缺失即**整体作废**(不交付半成品上下文)。
    if (ytcfg == null || eventId.isNullOrBlank() || program.isNullOrBlank() ||
      globalName.isNullOrBlank() || interpreterJs.isNullOrBlank()
    ) {
      Log.w(
        Tag,
        "armA page ctx: UNUSABLE (缺 ytcfg/EVENT_ID/program/globalName/interpreter)" +
          " → 整体回落 Create(行为与改动前一致)",
      )
      return null
    }
    // `po_token.html` 的 runBotGuard 只读这三个字段(interpreter 走 new Function 内联执行 ⇒
    // blockNetworkLoads=true 仍成立,CDN 取 JS 留在 Kotlin 侧)。
    val challengeObj = buildJsonObject {
      put(
        "interpreterJavascript",
        buildJsonObject {
          put("privateDoNotAccessOrElseSafeScriptWrappedValue", interpreterJs)
          put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", interpreterUrl)
        },
      )
      put("program", program)
      put("globalName", globalName)
      put("messageId", "")
      put("interpreterHash", "")
      put("clientExperimentsStateBlob", "")
    }
    return PoTokenPageContext(
      challengeJson = json.encodeToString(JsonObject.serializer(), challengeObj),
      ytcfgJson = json.encodeToString(JsonObject.serializer(), ytcfg),
      eventId = eventId.take(8),
      diag = "program=${program.length}B interpreter=${interpreterJs.length}B page=${page.length}B",
    )
  }

  /**
   * P11-103:ytAtN 数据提取。页面先有空调用 `window.ytAtN(); delete window.ytAtN;`(无参),
   * 实参数据在另一处 `window.ytAtN({'R': '\x7b\x22...' , ...})`。R 值是 hex 转义的 JSON 字符串,
   * 内含单/双引号——正则 `\{[\s\S]*?\}` 会因值内结构截断,须做引号感知的平衡括号扫描。
   */
  private fun findYtAtN(page: String): String? {
    var searchFrom = 0
    while (true) {
      val idx = page.indexOf("window.ytAtN(", searchFrom)
      if (idx < 0) return null
      scanBalancedObject(page, idx + "window.ytAtN(".length)?.let { return it }
      searchFrom = idx + 1
    }
  }

  /**
   * P11-154:自 [from] 起跳过空白,遇 `{` 则做**引号感知的平衡括号扫描**,返回该对象文本(含花括号)。
   * 从 [findYtAtN] 原实现原样提出——现在两处共用(ytAtN 与 [findMergedYtcfg]),避免再分叉一份扫描器:
   * 它的转义/引号处理是踩过坑的(值内含 `\xNN` 与引号,非引号感知的扫描会截断)。
   */
  private fun scanBalancedObject(page: String, from: Int): String? {
    var j = from
    while (j < page.length && page[j].isWhitespace()) j++
    if (j >= page.length || page[j] != '{') return null
    var depth = 0
    var k = j
    var inStr: Char? = null
    var closed = false
    while (k < page.length && !closed) {
      val c = page[k]
      if (inStr != null) {
        if (c == '\\') {
          k += 2
          continue
        }
        if (c == inStr) inStr = null
      } else {
        when (c) {
          '\'', '"' -> inStr = c
          '{' -> depth++
          '}' -> {
            depth--
            if (depth == 0) closed = true
          }
        }
      }
      k++
    }
    return if (closed) page.substring(j, k) else null
  }

  /**
   * P11-154:把页面里**所有** `ytcfg.set({...})` blob 合并为一个对象(后者覆盖同名顶层键)。
   *
   * 旧 [fetchHtmlPageData] 用 `Regex("ytcfg\\.set\\((\\{.+?\\})\\);", DOT_MATCHES_ALL)` 只取**第一个**,
   * 且非贪婪 `.+?` 在值内含 `});` 时会提前截断。而移动(MWEB)页会把 ytcfg 分多次 set(基础 cfg + 页面
   * 追加),`EVENT_ID` 与 `INNERTUBE_CONTEXT` 未必落在同一个 blob 里 ⇒ 必须全收 + 合并。
   */
  private fun findMergedYtcfg(page: String): JsonObject? {
    var searchFrom = 0
    var found = false
    val merged = LinkedHashMap<String, JsonElement>()
    while (true) {
      val idx = page.indexOf("ytcfg.set(", searchFrom)
      if (idx < 0) break
      val raw = scanBalancedObject(page, idx + "ytcfg.set(".length)
      if (raw != null) {
        // 严格 JSON 优先(ytcfg.set 通常就是严格 JSON);失败才退到宽松解析——[parseLooseJson] 的
        // 单引号处理会把双引号串里的撇号(`it's`)误当引号,故不能一上来就用它。
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
          ?: parseLooseJson(raw)
        parsed?.let { o ->
          merged.putAll(o)
          found = true
        }
      }
      searchFrom = idx + 1
    }
    return if (found) JsonObject(merged) else null
  }

  /** P11-154:取 interpreter JS 文本(页挑战链共用;移动身份,对齐全移动会话)。 */
  private suspend fun fetchInterpreterJsText(interpreterUrl: String): String? = withContext(Dispatchers.IO) {
    runCatching {
      // interpreter 多在 www.google.com/js/ CDN——对齐 FreeTubeAndroid WebView 拦截(带 Referer)。
      val req = Request.Builder().url(interpreterUrl)
        .header("User-Agent", YoutubeConstants.MobileUserAgent)
        .header("Referer", "https://www.youtube.com/")
        .build()
      httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
    }.getOrNull()?.takeIf { it.isNotBlank() }
  }

  /**
   * P11-103:bgutils-js `parseLooseJSON` 的 Kotlin 移植( helpers.ts L66-118)。ytAtN({...})
   * 不是严格 JSON:单引号 key/value、`\xNN` hex 转义、字符串值内嵌 JSON(需递归解)。
   * ① 去尾逗号;② 单引号字符串→JSON.stringify 等价的双引号串(仅解 \' 转义,反斜杠/双引号
   *   重新转义——\xNN 保持字面量留给第⑤步);③ 裸 key 加引号;④ JSON.parse;
   * ⑤ 递归 normalize:字符串解 \xNN,解出的内容以 { [ 开头再 JSON.parse 并继续 normalize。
   */
  private fun parseLooseJson(raw: String): JsonObject? {
    var text = raw.replace(Regex(""",\s*([}\]])""")) { it.groupValues[1] }
    val singleQuoted = Regex("""'((?:[^'\\]|\\[\s\S])*?)'""")
    runCatching {
      text = text.replace(singleQuoted) { m ->
        // JS 侧等价:innerStr.replace(/\\'/g, "'") 后 JSON.stringify。
        val inner = m.groupValues[1].replace("\\'", "'")
        jsonString(inner)
      }
    }.onFailure { Log.w(Tag, "parseLooseJson single-quote pass failed: ${it.message}") }
    text = text.replace(Regex("""([{,]\s*)([a-zA-Z0-9_${'$'}]+)\s*:""")) { m ->
      m.groupValues[1] + "\"" + m.groupValues[2] + "\":"
    }
    val root = runCatching { json.parseToJsonElement(text) }.getOrElse {
      Log.w(Tag, "parseLooseJson parse failed: ${it.message}")
      return null
    }
    return runCatching { normalizeJsonElement(root) as? JsonObject }.getOrNull()
  }

  /** parseLooseJSON normalizeValue 递归:解 \xNN;解出 JSON 文本再 parse。 */
  private fun normalizeJsonElement(el: JsonElement): JsonElement = when (el) {
    is JsonPrimitive -> if (el.isString) {
      val decoded = decodeHexEscapes(el.content)
      val trimmed = decoded.trim()
      if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        runCatching { normalizeJsonElement(json.parseToJsonElement(decoded)) }.getOrDefault(JsonPrimitive(decoded))
      } else {
        JsonPrimitive(decoded)
      }
    } else {
      el
    }
    is JsonArray -> JsonArray(el.map { normalizeJsonElement(it) })
    is JsonObject -> JsonObject(el.mapValues { normalizeJsonElement(it.value) })
  }

  private fun decodeHexEscapes(value: String): String =
    Regex("""\\x([0-9A-Fa-f]{2})""").replace(value) { m -> m.groupValues[1].toInt(16).toChar().toString() }

  /** 页面内嵌 bgChallenge(R.bgChallenge: program/globalName/interpreterUrl)→ 拉 interpreter。 */
  private suspend fun challengeFromPage(pageData: PageData?): Challenge? {
    val r = pageData?.attestationData?.obj("R") ?: return null
    val bg = r.obj("bgChallenge") ?: return null
    val program = bg.stringOrNull("program") ?: return null
    val globalName = bg.stringOrNull("globalName") ?: return null
    val interpreterUrl = bg.obj("interpreterUrl")
      ?.stringOrNull("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
      ?.takeIf { it.isNotBlank() }
      ?.let { if (it.startsWith("//")) "https:$it" else it }
      ?: return null
    val interpreterJs = withContext(Dispatchers.IO) {
      runCatching {
        // interpreter 多在 www.google.com/js/ CDN——对齐 FreeTubeAndroid WebView 拦截(带 Referer)。
        val req = Request.Builder().url(interpreterUrl)
          .header("User-Agent", YoutubeConstants.UserAgent)
          .header("Referer", "https://www.youtube.com/")
          .build()
        httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
      }.getOrNull()
    }
    if (interpreterJs.isNullOrBlank()) {
      Log.w(Tag, "page bgChallenge: interpreter fetch failed ($interpreterUrl)")
      return null
    }
    Log.i(Tag, "page bgChallenge ok: interpreter=${interpreterJs.length}B program=${program.length}B")
    return Challenge(interpreterJs, program, globalName, source = "page-bgChallenge", interpreterUrl = interpreterUrl)
  }

  /** /att/get fallback:页面未带 bgChallenge 时,用 eacrToken 换新 challenge(FreeTube botGuardScript 同款)。 */
  /**
   * P11-117(对齐 FreeTube `botGuardScript.js:14-30`):挑战从 `/att/get` 取——body 带**完整 context**、
   * **不带 eacrToken**,并带 `X-Goog-Visitor-Id` / `X-Youtube-Client-Version` / `X-Youtube-Client-Name:1`。
   *
   * 旧实现发 `{engagementType, eacrToken}`(eacrToken 来自 OkHttp 抓的桌面 watch 页)且用 postJson 的
   * 默认身份(合成移动 context + 移动 UA)→ **挑战按桌面身份拿、兑换按移动身份**,这条跨身份兑换是
   * 13 轮里从没被打开过的差异,而且该端点此前零日志。
   */
  private suspend fun attGetChallenge(): Challenge? {
    val identity = webSessionIdentity()
    if (identity == null) {
      Log.i(Tag, "att/get: no desktop identity yet(page not fetched)→ skip")
      return null
    }
    val ctx = identity.context
    val resp = runCatching {
      innerTubeClient.postJson(
        "/att/get",
        buildJsonObject {
          put("engagementType", "ENGAGEMENT_TYPE_UNBOUND")
          put("context", ctx)
        },
        client = InnerTubeClient.Client.WEB,
        contextOverride = ctx,
        visitorOverride = ctx.obj("client")?.stringOrNull("visitorData"),
        cookieOverride = identity.cookie,
        uaOverride = YoutubeConstants.UserAgent,
      )
    }.getOrElse {
      Log.w(Tag, "att/get failed: ${it.message}")
      return null
    }
    val bgChallenge = resp.obj("bgChallenge")
    val program = bgChallenge?.stringOrNull("program")
    val globalName = bgChallenge?.stringOrNull("globalName")
    if (program == null || globalName == null) {
      Log.w(Tag, "att/get: no bgChallenge in response")
      return null
    }
    val interpreterUrl = bgChallenge.obj("interpreterUrl")
      ?.stringOrNull("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
      ?.let { if (it.startsWith("//")) "https:$it" else it }
    val interpreterJs = interpreterUrl?.let { url ->
      withContext(Dispatchers.IO) {
        runCatching {
          val req = Request.Builder().url(url)
            .header("User-Agent", YoutubeConstants.UserAgent)
            .header("Referer", "https://www.youtube.com/")
            .build()
          httpClient.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrNull()
      }
    }
    if (interpreterJs.isNullOrBlank()) {
      Log.w(Tag, "att/get: interpreter fetch failed ($interpreterUrl)")
      return null
    }
    Log.i(Tag, "att-get challenge ok: interpreter=${interpreterJs.length}B program=${program.length}B")
    return Challenge(interpreterJs, program, globalName, source = "att-get", interpreterUrl = interpreterUrl)
  }

  // ---- WebView snapshot / mint ----

  /**
   * P11-110:interpreter 经 **<script src> 标签**加载进宿主页(对齐 FreeTube botGuardScript.js 的
   * script 元素加载 + FreeTubeAndroid bgwebview)。两参照均不以 eval 注入 interpreter;BotGuard VM
   * 对脚本执行上下文敏感(c42fee2c7 专门把 fetch+new Function 换成 script 元素)。轮询加载完成,
   * 超时/失败返回 false(调用方落 eval 兜底)。
   */
  private suspend fun loadInterpreterViaScript(url: String): Boolean {
    executor.eval(
      "(function(){ window.__interpLoad={status:'pending'}; var s=document.createElement('script'); " +
        "s.src=${jsonString(url)}; s.onload=function(){window.__interpLoad={status:'loaded'}}; " +
        "s.onerror=function(){window.__interpLoad={status:'error'}}; document.head.appendChild(s); })()"
    )
    val deadline = System.currentTimeMillis() + InterpreterLoadTimeoutMs
    while (System.currentTimeMillis() < deadline) {
      val raw = executor.eval("JSON.stringify(window.__interpLoad)")
      // evaluateJavascript 对字符串结果再包一层 JSON 编码(带引号)——先解外层再解对象(pollState 同款)。
      val inner = runCatching { json.parseToJsonElement(raw ?: "").jsonPrimitive.contentOrNull }.getOrNull() ?: raw
      when (runCatching { json.parseToJsonElement(inner ?: "").jsonObject.stringOrNull("status") }.getOrNull()) {
        "loaded" -> return true
        "error" -> return false
      }
      delay(100)
    }
    Log.w(Tag, "interpreter script-tag load timeout ($url)")
    return false
  }

  private suspend fun runSnapshot(program: String, globalName: String, contentBinding: JsonObject): JsonObject? {
    // 诊断:确认 js_shell.html 的桌面指纹 polyfill 在真机生效(VM 读到与 context 一致的桌面环境)。
    // 含实际 navigator.userAgent(非 polyfill 覆盖的只读属性,由 settings.userAgentString 决定)与
    // window.chrome 等强指纹信号——BotGuard VM 若探测到安卓 WebView 环境会铸出无效 token(§6.7 row 35)。
    val fp = executor.eval(
      "JSON.stringify({ua:navigator.userAgent, platform:navigator.platform, uaMobile:(navigator.userAgentData?navigator.userAgentData.mobile:'n/a'), " +
        "uaPlatform:(navigator.userAgentData?navigator.userAgentData.platform:'n/a'), plugins:navigator.plugins.length, " +
        "screenW:screen.width, screenH:screen.height, dpr:window.devicePixelRatio, " +
        "touch:navigator.maxTouchPoints, webdriver:navigator.webdriver, mem:navigator.deviceMemory, cores:navigator.hardwareConcurrency, " +
        "vendorSub:navigator.vendorSub, productSub:navigator.productSub, appCodeName:navigator.appCodeName, appName:navigator.appName, " +
        "chrome:typeof window.chrome, docURL:document.URL, baseURI:document.baseURI})"
    )
    Log.i(Tag, "VM fingerprint=$fp")
    val script = "try { window.__runSnapshot(${jsonString(program)}, ${jsonString(globalName)}, ${contentBinding.toString()}) } " +
      "catch(e) { window.__poToken = { status: 'error', token: null, error: String(e && e.stack || e) }; }"
    val evalResult = executor.eval(script)
    Log.i(Tag, "__runSnapshot eval result=${evalResult?.take(60)}")
    val state = pollState("snapshot-done")
    // 诊断:确认 minter 是否产生(UA 修正后期望 length>0 & isFunc=function)。
    val diag = executor.eval("window.__diag ? JSON.stringify(window.__diag) : 'n/a'")
    Log.i(Tag, "webPoSignalOutput diag=$diag")
    return state
  }

  private suspend fun mintToken(integrityToken: String, videoId: String): String? {
    executor.eval("window.__mint(${jsonString(integrityToken)}, ${jsonString(videoId)})")
    val state = pollState("done") ?: return null
    return state.stringOrNull("token")
  }

  /** 轮询 window.__poToken 直到目标 status 或 error/超时。 */
  private suspend fun pollState(target: String): JsonObject? {
    val deadline = System.currentTimeMillis() + PollTimeoutMs
    var pollCount = 0
    while (System.currentTimeMillis() < deadline) {
      val raw = executor.eval("JSON.stringify(window.__poToken)")
      if (raw == null) {
        Log.w(Tag, "pollState eval returned null (poll #$pollCount)")
        return null
      }
      // evaluateJavascript 对 JS 字符串结果做 JSON 编码(带引号+转义)，
      // 故 raw 形如 "{\"status\":...}" —— 先解析出内层字符串,再解析为 JsonObject。
      val inner = runCatching { json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull()
      if (inner.isNullOrBlank()) {
        Log.w(Tag, "pollState inner parse failed: $raw")
        return null
      }
      val state = runCatching { json.parseToJsonElement(inner).jsonObject }.getOrNull()
      if (state == null) {
        Log.w(Tag, "pollState state parse failed: $inner")
        return null
      }
      if (pollCount == 0) Log.i(Tag, "poll #0 state=$inner")
      pollCount++
      when (state.stringOrNull("status")) {
        target -> return state
        "error" -> {
          Log.w(Tag, "PO token JS error: ${state.stringOrNull("error")}")
          return null
        }
        else -> delay(100)
      }
    }
    Log.w(Tag, "PO token poll timeout waiting for $target")
    return null
  }

  // ---- GenerateIT ----

  private suspend fun generateIntegrityToken(botguardResponse: String): String? = withContext(Dispatchers.IO) {
    val body = "[\"$RequestKey\",${jsonString(botguardResponse)}]".toRequestBody(JsonProtobufMediaType)
    val request = Request.Builder()
      // 对齐 FreeTube botGuardScript.js 的 buildURL('GenerateIT', true) = www.youtube.com/api/jnn/v1/GenerateIT。
      // PR #6931 说明 youtube.com/api/jnn 是 jnn-pa.googleapis.com 的代理,但真机实测(§6.7 row 26)
      // 用 jnn-pa 直连 mint 出的 token 被判无效,先切到 YouTube 托管端点重测。
      .url("https://www.youtube.com/api/jnn/v1/GenerateIT")
      .post(body)
      .header("Content-Type", "application/json+protobuf")
      .header("x-goog-api-key", WaaApiKey)
      .header("x-user-agent", "grpc-web-javascript/0.1")
      .header("User-Agent", YoutubeConstants.UserAgent)
      // P11-105(裸发,对齐 FreeTube/FreeTubeAndroid):FreeTube botGuardScript.js 的 GenerateIT 只带
      // content-type/x-goog-api-key/x-user-agent 三头(且 token 铸造跑在 Electron cookie-less
      // partition session,§6.7 row 35 实证"alpha.10 加的 cookie 是多余且非根因");FreeTubeAndroid
      // bgwebview(全新 WebView 无会话 cookie)同样等效裸发。row 34 加的 Cookie+X-Goog-Visitor-Id
      // 把 integrityToken 绑到移动浏览器会话 visitor——而 P11-103 起挑战已来自桌面 watch 页
      // (ytAtN.R 自带页面 visitor),跨会话绑定正是 attestation 链断点:铸出的 token 被 SABR 服务端
      // 逐请求 status=2 nag、playerTimeMs≥60s 升 status=3(P11-104 请求形状对齐后 nag 仍在,
      // 排除请求体因素)。row 34 的 cookie 结论属旧 create 链时代,page-challenge 链对齐两参照裸发。
      .build()
    var status = 0
    val text = runCatching {
      httpClient.newCall(request).execute().use { resp ->
        status = resp.code
        if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
      }
    }.getOrNull()
    if (text.isNullOrBlank()) {
      Log.w(Tag, "GenerateIT failed/blank (status=$status)")
      return@withContext null
    }
    Log.i(Tag, "GenerateIT response (status=$status): ${text.take(400)}")
    // 实测响应形如 [null, <ttl>, null, "<integrityToken>"] —— token 在 index 3(纯字符串)。
    // 兼容其它形态：{ integrityToken: "..." } 或 [null, { integrityToken: "..." }]。
    // 注意:minter 真正产生后(webPoSignalOutput.length=1)响应变为 ["<token>", <ttl>, <n>] ——
    // token 在 index 0(对齐 FreeTube botGuardScript.js 的 response[0])。故 index 0 优先。
    val arr = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    // index 0 优先(新格式 ["<token>",ttl,n]);整条链包 runCatching,
    // 避免 arr[1] 是数字(43200)时 .jsonObject cast 抛异常而取不到 index 0/3。
    val token = runCatching {
      root?.stringOrNull("integrityToken")
        ?: arr?.getOrNull(0)?.jsonPrimitive?.contentOrNull
        ?: arr?.getOrNull(3)?.jsonPrimitive?.contentOrNull
        ?: arr?.getOrNull(1)?.jsonObject?.stringOrNull("integrityToken")
    }.onFailure { Log.w(Tag, "GenerateIT token parse failed: ${it.message}") }.getOrNull()
    if (token.isNullOrBlank()) Log.w(Tag, "GenerateIT response missing integrityToken")
    token
  }

  // ---- contentBinding ----

  /**
   * snapshot 的 contentBinding。`c` 值（含 b/hh 等）为 BotGuard 内容绑定上下文，
   * 需对照真实 player 响应/attestation 钉死；当前为占位，真机迭代时替换。
   */
  private fun buildContentBinding(videoId: String): JsonObject {
    return buildJsonObject {
      put("c", "a=6&a2=10&b=PLACEHOLDER&c=0&d=1&t=7200&c1a=1&c6a=1&c6b=1&hh=PLACEHOLDER")
      put("e", "ENGAGEMENT_TYPE_VIDEO_LIKE")
      put("encryptedVideoId", videoId)
    }
  }

  private fun jsonString(input: String): String {
    val escaped = input
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    return "\"$escaped\""
  }

  private fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

  private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

  private companion object {
    const val Tag = "YtBotGuard"
    const val RequestKey = "O43z0dpjhgX20SCx4KAo"
    const val WaaApiKey = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    /**
     * P11-162:`__runSnapshot` / `__mint` 的**轮询**上限。
     *
     * 由 6s 抬到 12s:**必须大于 bgutils 的 snapshot 超时**——那里现在是显式 **10_000**(此前漏传、
     * 走 bgutils 默认 3s)。若轮询仍停在 6s,会在 VM 还在跑时先放弃 ⇒ 白等一次铸造。
     * 整段仍由 [OverallTimeoutMs](20s) 兜住:snapshot ≤10s + GenerateIT + mint 都在预算内。
     */
    const val PollTimeoutMs = 12_000L
    // P11-110:interpreter <script> 标签加载超时(CDN 63KB,真机 ~1s;留裕量)。
    const val InterpreterLoadTimeoutMs = 10_000L
    // /att/get 的 program 更大(35KB>10KB),VM 加载/eval 更慢,8s 首尝试会 timeout,加到 20s。
    const val OverallTimeoutMs = 20_000L
    /**
     * P11-154:arm A 页面上下文的**整段上限**(含 1MB 页 GET + interpreter CDN GET)。
     * 它跑在被 await 的铸造窗口内(见 [YoutubePlaybackResolver.MINTER_WAIT_MS])⇒ 必须收敛,
     * 否则实验臂会被静默跳过、实验根本没跑。
     */
    const val ArmAPageContextTimeoutMs = 12_000L
    val JsonProtobufMediaType = "application/json+protobuf".toMediaType()
  }
}
