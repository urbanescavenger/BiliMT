package com.kirin.mt.core.youtube

import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
  private val client: OkHttpClient,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
  }

  /** guest 会话的 visitorData，首次使用时生成并复用（对齐 youtubei.js 单会话）。 */
  private var visitorData: String? = null

  /**
   * 发送一个 InnerTube JSON 请求。
   *
   * @param endpoint 以 / 开头的 API path，如 "/search"、"/browse"。
   * @param payload  业务字段（query/browseId/params/continuation…），context 会自动注入。
   * @return 响应根 JsonObject。
   */
  suspend fun postJson(
    endpoint: String,
    payload: JsonObject = buildJsonObject {},
  ): JsonObject = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
      // 业务字段在前，context 在后（youtubei.js 的 ...payload 后接 context）
      payload.forEach { (key, value) -> put(key, value) }
      put("context", buildContext())
    }

    val url = "${YoutubeConstants.InnerTubeBase}/${YoutubeConstants.ApiVersion}$endpoint" +
      "?key=${YoutubeConstants.ApiKey}&prettyPrint=false&alt=json"

    val request = Request.Builder()
      .url(url)
      .post(body.toString().toRequestBody(JsonMediaType))
      .header("Content-Type", "application/json")
      .header("User-Agent", YoutubeConstants.UserAgent)
      .header("Referer", YoutubeConstants.Referer)
      .header("X-Goog-Visitor-Id", currentVisitorData())
      .header("X-Youtube-Client-Version", YoutubeConstants.ClientVersion)
      .header("X-Youtube-Client-Name", YoutubeConstants.ClientNameId)
      .build()

    client.newCall(request).execute().use { response ->
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

  private fun buildContext(): JsonObject {
    return buildJsonObject {
      put(
        "client",
        buildJsonObject {
          put("clientName", YoutubeConstants.ClientName)
          put("clientVersion", YoutubeConstants.ClientVersion)
          put("hl", YoutubeConstants.Hl)
          put("gl", YoutubeConstants.Gl)
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
    }
  }

  private fun currentVisitorData(): String {
    val cached = visitorData
    if (cached != null) return cached
    val generated = encodeVisitorData(
      id = randomId(),
      timestamp = System.currentTimeMillis() / 1000L,
    )
    visitorData = generated
    return generated
  }

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
    val JsonMediaType = "application/json; charset=utf-8".toMediaType()
  }
}

class YoutubeApiException(
  val statusCode: Int,
  val responseBody: String,
  message: String,
) : Exception(message)
