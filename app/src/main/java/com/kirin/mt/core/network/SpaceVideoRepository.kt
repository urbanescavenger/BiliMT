package com.kirin.mt.core.network

import android.util.Log
import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray

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
    val keys = wbiKeyRepository.ensureKeys(sessData)
    Log.i(
      LogTag,
      "space videos start mid=$mid order=$order page=$page hasSession=${!sessData.isNullOrBlank()} " +
        "hasWbiKeys=${keys != null} retryMode=$retryMode",
    )
    // Params match BV getWebUserSpaceVideos exactly — extra params trigger 风控.
    val params = mapOf(
      "mid" to mid.toString(),
      "pn" to page.toString(),
      "ps" to SpacePageSize.toString(),
      "order" to order,
      "tid" to "0",
      // 风控参数 — 缺少会被 -352 拦截 (ref: BV getWebUserSpaceVideos)
      "dm_img_list" to "[]",
      "dm_img_str" to DmImgStr,
      "dm_cover_img_str" to DmImgStr,
    )
    // BV sends only SESSDATA in Cookie — buvid/biliJct/DedeUserID trigger 风控.
    // User-Agent 必须跟 dm_img_str (OpenGL ES Chromium) 一致，否则指纹矛盾触发 412.
    val headers = mapOf(
      "Cookie" to "SESSDATA=${sessData.orEmpty()};",
      "referer" to BiliHeaders.SpaceOrigin,
      "User-Agent" to SpaceUserAgent,
    )

    return runCatching {
      signAndFetchSpaceVideos(
        params = params,
        imgKey = keys?.imgKey,
        subKey = keys?.subKey,
        headers = headers,
        context = "space archives",
        retryDelaysMs = retryMode.retryDelaysMs,
      )
    }.getOrElse { signedError ->
      logSpaceVideosFailure("signed", signedError)
      if (retryMode == SpaceVideoRetryMode.Interactive) {
        throw signedError
      }
      // Recovery mode: refresh wbi keys and retry
      val refreshedVideos = if (keys != null) {
        val refreshedKeys = wbiKeyRepository.refreshKeys(sessData)
        if (refreshedKeys != null) {
          runCatching {
            signAndFetchSpaceVideos(
              params = params,
              imgKey = refreshedKeys.imgKey,
              subKey = refreshedKeys.subKey,
              headers = headers,
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
      refreshedVideos ?: runCatching {
        signAndFetchSpaceVideos(
          params = params,
          imgKey = null,
          subKey = null,
          headers = headers,
          context = "space archives fallback",
          retryDelaysMs = SpaceRecoveryFallbackRetryDelaysMs,
        )
      }.onFailure { fallbackError ->
        logSpaceVideosFailure("unsigned fallback", fallbackError)
      }.getOrDefault(emptyList())
    }
  }

  /**
   * Signs params (fresh wts + w_rid per attempt) and fetches with retries.
   *
   * Signing inside the loop ensures every retry gets a new timestamp,
   * which matters when the server rejects stale wts or applies transient 风控 (-352).
   */
  private suspend fun signAndFetchSpaceVideos(
    params: Map<String, String>,
    imgKey: String?,
    subKey: String?,
    headers: Map<String, String>,
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
      // Fresh wts + w_rid on every attempt
      val signedParams = if (imgKey != null && subKey != null) {
        wbiSigner.sign(params, imgKey, subKey)
      } else {
        params
      }
      val result = runCatching {
        getSpaceVideosWithParams(signedParams, headers, context)
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
    headers: Map<String, String>,
    context: String,
  ): List<VideoSummary> {
    val mid = params["mid"].orEmpty()
    val order = params["order"].orEmpty()
    val page = params["pn"].orEmpty()
    val root = apiClient.getJsonWithHeaders(
      url = BiliApiEndpoints.SpaceArcSearch,
      params = params,
      headers = headers,
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

  private fun Throwable.toSpaceVideoBrief(): String {
    return when (this) {
      is BiliApiCodeException -> "code=$code message=$biliMessage"
      is BiliNetworkException -> "http=$statusCode body=${responseBody.take(LogBodyPreviewLength)}"
      else -> "${javaClass.simpleName}: ${message.orEmpty()}"
    }
  }

  private fun Throwable.isRetryableSpaceFailure(): Boolean {
    if (this is BiliNetworkException && statusCode in SpaceRetryableHttpCodes) return true
    if (this is BiliApiCodeException && code == RiskControlCode) return true
    return false
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
    // base64("WebGL 1.0 (OpenGL ES 2.0 Chromium)") — 风控固定值
    const val DmImgStr = "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ"
    // Linux Chromium UA — 跟 dm_img_str (OpenGL ES Chromium) 指纹一致 (ref: BV BiliUserAgent)
    const val SpaceUserAgent =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val RiskControlCode = -352
    val SpaceInteractiveRetryDelaysMs = longArrayOf(2_000L, 4_000L)
    val SpaceRecoveryRetryDelaysMs = longArrayOf(1_200L, 2_400L)
    val SpaceRecoveryFallbackRetryDelaysMs = longArrayOf(1_200L)
    val SpaceRetryableHttpCodes = setOf(412, 429, 500, 502, 503, 504)
  }
}
