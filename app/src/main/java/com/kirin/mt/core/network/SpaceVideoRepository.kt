package com.kirin.mt.core.network

import android.util.Base64
import android.util.Log
import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.SessionStore
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import org.json.JSONObject

enum class SpaceVideoRetryMode {
  Interactive,
  Recovery,
}

internal class SpaceVideoRepository(
  private val apiClient: BiliApiClient,
  private val wbiKeyRepository: WbiKeyRepository,
  private val wbiSigner: WbiSigner,
  private val sessionStore: SessionStore,
) {
  suspend fun getSpaceVideos(
    mid: Long,
    page: Int = 1,
    order: String = SpaceOrderPubdate,
    retryMode: SpaceVideoRetryMode = SpaceVideoRetryMode.Interactive,
  ): List<VideoSummary> {
    if (mid <= 0L) {
      Log.w(LogTag, "space videos skipped: invalid mid=$mid order=$order page=$page")
      return emptyList()
    }

    val session = sessionStore.session.first()
    val sessData = session.sessData
    val biliJct = session.biliJct
    val (buvid3, buvid4) = ensureSpaceBuvidCookies()
    val keys = wbiKeyRepository.ensureKeys(sessData)
    Log.i(
      LogTag,
      "space videos start mid=$mid order=$order page=$page hasSession=${!sessData.isNullOrBlank()} " +
        "hasWbiKeys=${keys != null} hasBuvid3=${!buvid3.isNullOrBlank()} retryMode=$retryMode",
    )
    val params = mutableMapOf(
      "mid" to mid.toString(),
      "pn" to page.toString(),
      "ps" to SpacePageSize.toString(),
      "order" to order,
      "index" to "1",
      "order_avoided" to "true",
      "platform" to "web",
      "web_location" to SpaceWebLocation,
    )
    val signedParams = if (keys != null) {
      wbiSigner.sign(params, keys.imgKey, keys.subKey)
    } else {
      params
    }

    return runCatching {
      getSpaceVideosWithParamsWithRetry(
        params = signedParams,
        sessData = sessData,
        biliJct = biliJct,
        dedeUserId = session.mid,
        buvid3 = buvid3,
        buvid4 = buvid4,
        context = "space archives",
        retryDelaysMs = retryMode.retryDelaysMs,
      )
    }.getOrElse { signedError ->
      logSpaceVideosFailure("signed", signedError)
      if (retryMode == SpaceVideoRetryMode.Interactive) {
        throw signedError
      }
      val refreshedVideos = if (keys != null) {
        val refreshedKeys = wbiKeyRepository.refreshKeys(sessData)
        if (refreshedKeys != null) {
          val refreshedSignedParams = wbiSigner.sign(params, refreshedKeys.imgKey, refreshedKeys.subKey)
          runCatching {
            getSpaceVideosWithParamsWithRetry(
              params = refreshedSignedParams,
              sessData = sessData,
              biliJct = biliJct,
              dedeUserId = session.mid,
              buvid3 = buvid3,
              buvid4 = buvid4,
              context = "space archives refreshed",
              retryDelaysMs = SpaceRecoveryFallbackRetryDelaysMs,
            )
          }.onFailure { refreshedError ->
            logSpaceVideosFailure("refreshed", refreshedError)
          }.getOrNull()
        } else {
          null
        }
      } else {
        null
      }
      refreshedVideos ?: if (signedParams == params) {
        emptyList()
      } else {
        runCatching {
          getSpaceVideosWithParamsWithRetry(
            params = params,
            sessData = sessData,
            biliJct = biliJct,
            dedeUserId = session.mid,
            buvid3 = buvid3,
            buvid4 = buvid4,
            context = "space archives fallback",
            retryDelaysMs = SpaceRecoveryFallbackRetryDelaysMs,
          )
        }.onFailure { fallbackError ->
          logSpaceVideosFailure("unsigned fallback", fallbackError)
        }.getOrDefault(emptyList())
      }
    }
  }

  private suspend fun getSpaceVideosWithParamsWithRetry(
    params: Map<String, String>,
    sessData: String?,
    biliJct: String?,
    dedeUserId: Long?,
    buvid3: String?,
    buvid4: String?,
    context: String,
    retryDelaysMs: LongArray,
  ): List<VideoSummary> {
    var lastError: Throwable? = null
    repeat(retryDelaysMs.size + 1) { attempt ->
      if (attempt > 0) {
        val delayMs = retryDelaysMs[attempt - 1]
        Log.i(
          LogTag,
          "space videos $context retry attempt=${attempt + 1} delayMs=$delayMs mid=${params["mid"].orEmpty()} " +
            "order=${params["order"].orEmpty()}",
        )
        delay(delayMs)
      }
      val result = runCatching {
        getSpaceVideosWithParams(params, sessData, biliJct, dedeUserId, buvid3, buvid4, context)
      }
      result.onSuccess { return it }
      val error = result.exceptionOrNull() ?: return emptyList()
      lastError = error
      if (!error.isRetryableSpaceFailure()) {
        throw error
      }
      Log.w(
        LogTag,
        "space videos $context retryable failure attempt=${attempt + 1}: ${error.toSpaceVideoBrief()}",
      )
    }
    throw lastError ?: IllegalStateException("space videos $context failed")
  }

  private suspend fun getSpaceVideosWithParams(
    params: Map<String, String>,
    sessData: String?,
    biliJct: String?,
    dedeUserId: Long?,
    buvid3: String?,
    buvid4: String?,
    context: String,
  ): List<VideoSummary> {
    val mid = params["mid"].orEmpty()
    val order = params["order"].orEmpty()
    val page = params["pn"].orEmpty()
    val root = apiClient.getJsonWithHeaders(
      url = BiliApiEndpoints.SpaceArcSearch,
      params = params,
      headers = spaceHeaders(
        mid = mid,
        sessData = sessData,
        biliJct = biliJct,
        dedeUserId = dedeUserId,
        buvid3 = buvid3,
        buvid4 = buvid4,
      ),
    ).rootObject()
    root.requireBiliCodeOk(context)

    val data = root.obj("data")
    val listObject = data?.obj("list")
    val list = listObject?.get("vlist") as? JsonArray
    if (list == null) {
      Log.w(
        LogTag,
        "space videos $context missing vlist mid=$mid order=$order page=$page hasData=${data != null} hasList=${listObject != null}",
      )
      return emptyList()
    }
    val videos = list
      .mapNotNull { it.asObjectOrNull() }
      .filter { it.string("bvid").isNotBlank() }
      .map(VideoSummaryMappers::fromSpace)
    Log.i(LogTag, "space videos $context ok mid=$mid order=$order page=$page raw=${list.size} videos=${videos.size}")
    return videos
  }

  private fun logSpaceVideosFailure(stage: String, error: Throwable) {
    Log.w(LogTag, "space videos $stage failed: ${error.toSpaceVideoBrief()}")
  }

  private suspend fun ensureSpaceBuvidCookies(): Pair<String?, String?> {
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
      runCatching { activateBuvid(buvid3) }.onFailure { error ->
        Log.w(LogTag, "buvid activate failed: ${error.toSpaceVideoBrief()}")
      }
      Log.i(LogTag, "buvid ready source=spi hasBuvid3=${buvid3.isNotBlank()} hasBuvid4=${!buvid4.isNullOrBlank()}")
      buvid3 to buvid4
    }.getOrElse { error ->
      val fallbackBuvid3 = "${UUID.randomUUID()}infoc"
      sessionStore.saveDeviceCookies(buvid3 = fallbackBuvid3, buvid4 = null)
      Log.w(LogTag, "buvid spi failed, generated fallback: ${error.toSpaceVideoBrief()}")
      fallbackBuvid3 to null
    }
  }

  private suspend fun activateBuvid(buvid3: String) {
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

  private fun spaceHeaders(
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

  private fun Throwable.toSpaceVideoBrief(): String {
    return when (this) {
      is BiliApiCodeException -> "code=$code message=$biliMessage"
      is BiliNetworkException -> "http=$statusCode body=${responseBody.take(LogBodyPreviewLength)}"
      else -> "${javaClass.simpleName}: ${message.orEmpty()}"
    }
  }

  private fun Throwable.isRetryableSpaceFailure(): Boolean {
    return this is BiliNetworkException && statusCode in SpaceRetryableHttpCodes
  }

  private val SpaceVideoRetryMode.retryDelaysMs: LongArray
    get() = when (this) {
      SpaceVideoRetryMode.Interactive -> SpaceInteractiveRetryDelaysMs
      SpaceVideoRetryMode.Recovery -> SpaceRecoveryRetryDelaysMs
    }

  private companion object {
    const val LogTag = "BiliVideoRepository"
    const val LogBodyPreviewLength = 160
    const val SpaceOrderPubdate = "pubdate"
    const val SpacePageSize = 25
    const val SpaceWebLocation = "333.1387"
    val SpaceInteractiveRetryDelaysMs = longArrayOf(600L)
    val SpaceRecoveryRetryDelaysMs = longArrayOf(1_200L, 2_400L)
    val SpaceRecoveryFallbackRetryDelaysMs = longArrayOf(1_200L)
    val SpaceRetryableHttpCodes = setOf(412, 429, 500, 502, 503, 504)
  }
}
