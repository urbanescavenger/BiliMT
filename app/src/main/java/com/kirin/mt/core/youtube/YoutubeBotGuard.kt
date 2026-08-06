package com.kirin.mt.core.youtube

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * 这是高清(1080P/2K/4K)的唯一前置。流程（跟随 bgutils-js v4）：
 *  1. `POST /api/jnn/v1/Create`（requestKey=`O43z0dpjhgX20SCx4KAo`）拿 challenge。
 *  2. descramble（base64 解码 + 每字节 +97）→ `{ interpreterJavascript, program, globalName }`。
 *  3. 隐藏 WebView eval interpreter JS → 定义 `window[globalName]`。
 *  4. `__runSnapshot`（bgutils BotGuardClient.create + snapshot）→ botguardResponse。
 *  5. `POST Waa/GenerateIT`（[requestKey, botguardResponse]）→ integrityToken。
 *  6. `__mint`（bgutils WebPoMinter）→ 视频 ID 绑定的 PO token。
 *
 * 网络由 Kotlin 发（WebView 跨源 fetch 被 CORS 拦），WebView 只执行 interpreter JS + WASM。
 * 任一步失败返回 null，绝不阻塞"无 PO token 直连 /player"主路径。
 *
 * 脆弱点（需真机迭代）：interpreter JS/WASM 能否通过 BotGuard 运行时校验、snapshot 的
 * contentBinding `c` 值格式（当前为占位，需对照真实 player 响应钉死）、GenerateIT 响应结构。
 */
class YoutubeBotGuard(
  private val executor: YoutubeJsExecutor,
  private val httpClient: OkHttpClient,
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
    if (executor.eval(interpreterJs) == null) {
      Log.w(Tag, "interpreter eval failed")
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

  // ---- challenge 获取与 descramble ----

  private data class Challenge(
    val interpreterJavascript: String?,
    val program: String?,
    val globalName: String?,
  )

  private suspend fun fetchChallenge(): Challenge? = withContext(Dispatchers.IO) {
    val body = "[\"$RequestKey\"]".toRequestBody(JsonProtobufMediaType)
    val request = Request.Builder()
      .url("https://www.youtube.com/api/jnn/v1/Create")
      .post(body)
      .header("Content-Type", "application/json+protobuf")
      .header("x-goog-api-key", WaaApiKey)
      .header("x-user-agent", "grpc-web-javascript/0.1")
      .header("User-Agent", YoutubeConstants.UserAgent)
      .header("Referer", YoutubeConstants.Referer)
      .build()
    val text = runCatching {
      httpClient.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
    }.getOrNull()
    if (text.isNullOrBlank()) {
      Log.w(Tag, "challenge fetch failed/blank")
      return@withContext null
    }
    // 响应形如 [null, "<scrambled>"]。
    val scrambled = runCatching {
      json.parseToJsonElement(text).jsonArray.getOrNull(1)?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    if (scrambled.isNullOrBlank()) {
      Log.w(Tag, "challenge response missing scrambled data")
      return@withContext null
    }
    parseChallenge(descramble(scrambled))
  }

  /** base64 解码 + 每字节 +97 → UTF-8 JSON 串（对齐 bgutils-js descrambleChallenge）。 */
  private fun descramble(scrambled: String): String? {
    val bytes = runCatching { Base64.decode(scrambled, Base64.DEFAULT) }.getOrNull() ?: return null
    return runCatching { bytes.map { (it + 97).toByte() }.toByteArray().toString(Charsets.UTF_8) }.getOrNull()
  }

  /** 解析 descramble 后的 JSON 数组 → [messageId, wrappedScript, wrappedUrl, hash, program, globalName, ...]。 */
  private fun parseChallenge(descrambled: String?): Challenge? {
    if (descrambled.isNullOrBlank()) return null
    val arr = runCatching { json.parseToJsonElement(descrambled).jsonArray }.getOrNull() ?: return null
    val wrappedScript = arr.getOrNull(1) as? JsonArray
    val interpreterJavascript = wrappedScript?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
    val program = (arr.getOrNull(4) as? JsonPrimitive)?.contentOrNull
    val globalName = (arr.getOrNull(5) as? JsonPrimitive)?.contentOrNull
    return Challenge(interpreterJavascript, program, globalName)
  }

  // ---- WebView snapshot / mint ----

  private suspend fun runSnapshot(program: String, globalName: String, contentBinding: JsonObject): JsonObject? {
    executor.eval("window.__runSnapshot(${jsonString(program)}, ${jsonString(globalName)}, ${contentBinding.toString()})")
    return pollState("snapshot-done")
  }

  private suspend fun mintToken(integrityToken: String, videoId: String): String? {
    executor.eval("window.__mint(${jsonString(integrityToken)}, ${jsonString(videoId)})")
    val state = pollState("done") ?: return null
    return state.stringOrNull("token")
  }

  /** 轮询 window.__poToken 直到目标 status 或 error/超时。 */
  private suspend fun pollState(target: String): JsonObject? {
    val deadline = System.currentTimeMillis() + PollTimeoutMs
    while (System.currentTimeMillis() < deadline) {
      val raw = executor.eval("JSON.stringify(window.__poToken)") ?: return null
      val state = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
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
    val text = runCatching {
      httpClient.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
    }.getOrNull()
    if (text.isNullOrBlank()) {
      Log.w(Tag, "GenerateIT failed/blank")
      return@withContext null
    }
    // 响应形如 [null, { integrityToken: "..." }] 或 { integrityToken: "..." }。
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    val token = root?.stringOrNull("integrityToken")
      ?: runCatching { json.parseToJsonElement(text).jsonArray.getOrNull(1)?.jsonObject?.stringOrNull("integrityToken") }.getOrNull()
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
    const val OverallTimeoutMs = 8_000L
    val JsonProtobufMediaType = "application/json+protobuf".toMediaType()
  }
}
