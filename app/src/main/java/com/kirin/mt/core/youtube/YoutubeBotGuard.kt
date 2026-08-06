package com.kirin.mt.core.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
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
 * YouTube PO token（Proof-of-Origin）生成，基于 **bgutils-js(MIT)** 打包进隐藏 WebView。
 *
 * 无 PO token 时 YouTube 会剥掉 adaptive 高清流 url（只剩 progressive 360p），
 * 这是高清(1080P/2K/4K)的唯一前置。流程（对齐 FreeTube botGuardScript.js + bgutils-js v4）：
 *  1. `POST /youtubei/v1/att/get`（ENGAGEMENT_TYPE_UNBOUND，对齐 FreeTube）→
 *     `challengeData.bgChallenge` { program, globalName, interpreterUrl }，interpreter 单独 GET。
 *  2. 隐藏 WebView eval interpreter JS → 定义 `window[globalName]`。
 *  3. `__runSnapshot`（bgutils BotGuardClient.create + snapshot，只传 webPoSignalOutput）→ botguardResponse。
 *  4. `POST Waa/GenerateIT`（[requestKey, botguardResponse]）→ integrityToken。
 *  5. `__mint`（bgutils WebPoMinter）→ 视频 ID 绑定的 PO token。
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
    // 1) 拿 challenge 并 descramble。
    val challenge = fetchChallenge() ?: return null
    val interpreterJs = challenge.interpreterJavascript ?: return null
    val program = challenge.program ?: return null
    val globalName = challenge.globalName ?: return null
    Log.i(Tag, "challenge ok: interpreter=${interpreterJs.length}B program=${program.length}B global=$globalName")

    // 2) 加载 interpreter JS 进 WebView（定义 window[globalName]）。
    // 用 try-catch 包裹捕获 interpreter JS 的运行时错误（evaluateJavascript 对抛错脚本返回 null）。
    val wrappedInterpreter = "try { $interpreterJs } catch(e) { window.__interpreterError = String(e && e.stack || e); }"
    val interpreterEval = executor.eval(wrappedInterpreter)
    Log.i(Tag, "interpreter eval result=${interpreterEval?.take(60)}")
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
  )

  private suspend fun fetchChallenge(): Challenge? {
    val c = innerTubeClient.fetchBotGuardChallenge() ?: return null
    return Challenge(c.interpreterJavascript, c.program, c.globalName)
  }

  // ---- WebView snapshot / mint ----

  private suspend fun runSnapshot(program: String, globalName: String, contentBinding: JsonObject): JsonObject? {
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
      .url("https://jnn-pa.googleapis.com/\$rpc/google.internal.waa.v1.Waa/GenerateIT")
      .post(body)
      .header("Content-Type", "application/json+protobuf")
      .header("x-goog-api-key", WaaApiKey)
      .header("x-user-agent", "grpc-web-javascript/0.1")
      .header("User-Agent", YoutubeConstants.UserAgent)
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

  private companion object {
    const val Tag = "YtBotGuard"
    const val RequestKey = "O43z0dpjhgX20SCx4KAo"
    const val WaaApiKey = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    const val PollTimeoutMs = 6_000L
    // /att/get 的 program 更大(35KB>10KB),VM 加载/eval 更慢,8s 首尝试会 timeout,加到 20s。
    const val OverallTimeoutMs = 20_000L
    val JsonProtobufMediaType = "application/json+protobuf".toMediaType()
  }
}
