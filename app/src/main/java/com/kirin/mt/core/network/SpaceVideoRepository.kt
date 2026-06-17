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
    val biliJct = session.biliJct
    val (buvid3, buvid4) = SpaceHttpSupport.ensureBuvidCookies(sessionStore, apiClient)
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
      "web_location" to SpaceHttpSupport.SpaceWebLocation,
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
      headers = SpaceHttpSupport.headers(
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
    val SpaceInteractiveRetryDelaysMs = longArrayOf(600L)
    val SpaceRecoveryRetryDelaysMs = longArrayOf(1_200L, 2_400L)
    val SpaceRecoveryFallbackRetryDelaysMs = longArrayOf(1_200L)
    val SpaceRetryableHttpCodes = setOf(412, 429, 500, 502, 503, 504)
  }
}
