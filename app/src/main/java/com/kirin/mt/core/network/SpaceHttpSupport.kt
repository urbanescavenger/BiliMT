package com.kirin.mt.core.network

import android.util.Base64
import android.util.Log
import com.kirin.mt.core.storage.SessionStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Shared HTTP helpers for Bilibili space endpoints (UP主空间).
 *
 * Both [SpaceVideoRepository] (投稿列表) and [SpaceProfileRepository] (资料/粉丝数) need the
 * same buvid cookie provisioning and the same space request headers (Referer/Origin/sec-ch-ua +
 * buvid cookies) to avoid 412 / 风控 responses. Centralizing them keeps a single source of truth.
 */
internal object SpaceHttpSupport {
  private const val LogTag = "BiliSpaceHttp"

  /** web_location param value used by space web endpoints. */
  const val SpaceWebLocation = "333.1387"

  /**
   * Ensures buvid3/buvid4 cookies exist (fetches via spi + activates, caching into the session
   * store). Returns the cookies to attach to subsequent space requests.
   */
  suspend fun ensureBuvidCookies(
    sessionStore: SessionStore,
    apiClient: BiliApiClient,
  ): Pair<String?, String?> {
    val cachedBuvid3 = sessionStore.buvid3.first()
    val cachedBuvid4 = sessionStore.buvid4.first()
    if (!cachedBuvid3.isNullOrBlank()) {
      return cachedBuvid3 to cachedBuvid4
    }

    return runCatching {
      val root = apiClient.getJson(url = BiliApiEndpoints.BuvidSpi).rootObject()
      root.requireBiliCodeOk("buvid spi")
      val data = root.obj("data")
      val buvid3 = data?.string("b_3").orEmpty().ifBlank { "${UUID.randomUUID()}infoc" }
      val buvid4 = data?.string("b_4").orEmpty().takeIf { it.isNotBlank() }
      sessionStore.saveDeviceCookies(buvid3 = buvid3, buvid4 = buvid4)
      runCatching { activateBuvid(apiClient, buvid3) }.onFailure { error ->
        Log.w(LogTag, "buvid activate failed: ${error.toSpaceHttpBrief()}")
      }
      Log.i(LogTag, "buvid ready source=spi hasBuvid3=${buvid3.isNotBlank()} hasBuvid4=${!buvid4.isNullOrBlank()}")
      buvid3 to buvid4
    }.getOrElse { error ->
      val fallbackBuvid3 = "${UUID.randomUUID()}infoc"
      sessionStore.saveDeviceCookies(buvid3 = fallbackBuvid3, buvid4 = null)
      Log.w(LogTag, "buvid spi failed, generated fallback: ${error.toSpaceHttpBrief()}")
      fallbackBuvid3 to null
    }
  }

  /** Request headers for a space endpoint targeting [mid] (used as the Referer path). */
  fun headers(
    mid: String,
    sessData: String?,
    biliJct: String?,
    dedeUserId: Long?,
    buvid3: String?,
    buvid4: String?,
  ): Map<String, String> {
    return buildMap {
      put("User-Agent", BiliHeaders.UserAgent)
      put("Accept", "*/*")
      put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,ja;q=0.7,zh-TW;q=0.6")
      put("Origin", BiliHeaders.SpaceOrigin)
      put("Referer", "https://space.bilibili.com/$mid")
      put("Priority", "u=1, i")
      put("sec-ch-ua", "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"")
      put("sec-ch-ua-mobile", "?0")
      put("sec-ch-ua-platform", "\"Windows\"")
      put("Sec-Fetch-Dest", "empty")
      put("Sec-Fetch-Mode", "cors")
      put("Sec-Fetch-Site", "same-site")
      BiliHeaders.cookie(
        sessData = sessData,
        biliJct = biliJct,
        buvid3 = buvid3,
        buvid4 = buvid4,
        dedeUserId = dedeUserId,
      )?.let { cookie -> put("Cookie", cookie) }
    }
  }

  private suspend fun activateBuvid(apiClient: BiliApiClient, buvid3: String) {
    val random = java.util.Random()
    val randomBytes = ByteArray(32).also { random.nextBytes(it) }
    val tailBytes = byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68) + ByteArray(4).also { random.nextBytes(it) }
    val encodedTail = Base64.encodeToString(randomBytes + tailBytes, Base64.NO_WRAP)
    val payload = JSONObject().apply {
      put("3064", 1)
      put("39c8", "333.999.fp.risk")
      put(
        "3c43",
        JSONObject().apply {
          put("adca", "Windows")
          put("bfe9", encodedTail.takeLast(50))
        },
      )
    }.toString()
    val root = apiClient.postFormJson(
      url = BiliApiEndpoints.BuvidActivate,
      params = mapOf("payload" to payload),
      headers = buildMap {
        put("Origin", BiliHeaders.Origin)
        BiliHeaders.cookie(sessData = null, buvid3 = buvid3)?.let { cookie -> put("Cookie", cookie) }
      },
    ).rootObject()
    val code = root.int("code")
    if (code != 0) {
      throw BiliApiCodeException(
        context = "buvid activate",
        code = code,
        biliMessage = root.string("message"),
      )
    }
  }

  private fun Throwable.toSpaceHttpBrief(): String {
    return when (this) {
      is BiliApiCodeException -> "code=$code message=$biliMessage"
      is BiliNetworkException -> "http=$statusCode body=${responseBody.take(160)}"
      else -> "${javaClass.simpleName}: ${message.orEmpty()}"
    }
  }
}