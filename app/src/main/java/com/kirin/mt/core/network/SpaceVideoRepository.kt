package com.kirin.mt.core.network

import android.util.Log
import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

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

    // P11-326 社区兜底(RSSHub commit 26c5fa2 / yt-dlp #9119):space/wbi/arc/search 被 412 风控时,
    // 改用 x/series/recArchivesByKeywords —— 不需要 WBI 签名、也不需要 dm_img 指纹,
    // 返回同一 UP 的投稿列表(仅按发布时间排序)。
    // 先单发一次：命中风控码就立刻切兜底，不让用户等满 2s/4s/8s/12s 的重试梯子；
    // 兜底也失败（或错误不是风控类）才回到原来的重试 / Recovery 链。
    val quickAttempt = runCatching {
      signAndFetchSpaceVideos(
        params = params,
        imgKey = keys?.imgKey,
        subKey = keys?.subKey,
        headers = headers,
        context = "space archives quick",
        retryDelaysMs = longArrayOf(),
      )
    }
    quickAttempt.getOrNull()?.let { return it }
    val quickError = quickAttempt.exceptionOrNull()
    // runCatching 会吞掉协程取消；取消不是风控，直接上抛，别再多打兜底/重试请求。
    if (quickError is kotlinx.coroutines.CancellationException) throw quickError
    if (quickError != null && SpaceHttpSupport.isRetryableFailure(quickError)) {
      val fallback = runCatching {
        fetchSpaceVideosFallback(mid = mid, page = page, sessData = sessData)
      }
      fallback.onFailure { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        logSpaceVideosFailure("archives fallback", error)
      }
      val fallbackVideos = fallback.getOrNull().orEmpty()
      if (fallbackVideos.isNotEmpty()) {
        Log.i(
          LogTag,
          "space videos archives fallback ok mid=$mid order=$order page=$page " +
            "videos=${fallbackVideos.size} (fallback is pubdate-only)",
        )
        return fallbackVideos
      }
    }

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
              retryDelaysMs = SpaceHttpSupport.RecoveryFallbackRetryDelaysMs,
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
          retryDelaysMs = SpaceHttpSupport.RecoveryFallbackRetryDelaysMs,
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
      if (!SpaceHttpSupport.isRetryableFailure(error)) {
        throw error
      }
      Log.w(
        LogTag,
        "space videos $context retryable failure attempt=${attempt + 1}: ${error.toSpaceVideoBrief()}",
      )
    }
    throw lastError ?: IllegalStateException("space videos $context failed")
  }

  /**
   * 412 风控兜底:走免签名的 `x/series/recArchivesByKeywords`。
   *
   * 社区做法(RSSHub `26c5fa2`、yt-dlp `#9119`)：space 页接口被风控时换这个接口拿投稿，
   * 它不校验 WBI 签名/dm_img 指纹，参数只有 mid + 分页 + orderby。
   * 限制：只按发布时间返回（click/stow 排序会退化成 senddate），且没有 owner 昵称/头像；
   * 页面头部资料来自 `acc/info`，卡片用不到这两个字段。
   */
  private suspend fun fetchSpaceVideosFallback(
    mid: Long,
    page: Int,
    sessData: String?,
  ): List<VideoSummary> {
    val headers = mapOf(
      "Cookie" to "SESSDATA=${sessData.orEmpty()};",
      "referer" to BiliHeaders.SpaceOrigin,
      "User-Agent" to SpaceUserAgent,
    )
    val root = apiClient.getJsonWithHeaders(
      url = BiliApiEndpoints.SpaceArchivesByKeywords,
      params = mapOf(
        "mid" to mid.toString(),
        "keywords" to "",
        "ps" to SpacePageSize.toString(),
        "pn" to page.toString(),
        "orderby" to "senddate",
      ),
      headers = headers,
    ).rootObject()
    root.requireBiliCodeOk("space archives fallback")

    val archives = root.obj("data")?.get("archives") as? JsonArray ?: return emptyList()
    return archives.mapNotNull { it.asObjectOrNull() }.mapNotNull(::seriesArchiveToSummary)
  }

  private fun seriesArchiveToSummary(json: JsonObject): VideoSummary? {
    val bvid = json.string("bvid")
    if (bvid.isBlank()) return null
    return VideoSummary(
      bvid = bvid,
      title = json.string("title"),
      pic = VideoSummaryMappers.fixPicUrl(json.string("pic")),
      ownerName = "",
      ownerFace = "",
      ownerMid = json.long("upMid"),
      view = BiliNumberParser.toInt(json.obj("stat")?.get("view")),
      danmaku = 0,
      duration = BiliNumberParser.parseDuration(json["duration"]),
      pubdate = json.long("pubdate"),
      badge = "",
      aid = json.long("aid"),
    )
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

  private val SpaceVideoRetryMode.retryDelaysMs: LongArray
    get() = when (this) {
      SpaceVideoRetryMode.Interactive -> SpaceHttpSupport.InteractiveRetryDelaysMs
      SpaceVideoRetryMode.Recovery -> SpaceHttpSupport.RecoveryRetryDelaysMs
    }

  private companion object {
    const val LogTag = "BiliVideoRepository"
    const val LogBodyPreviewLength = 160
    const val SpaceOrderPubdate = "pubdate"
    const val SpacePageSize = 30
    // base64("WebGL 1.0 (OpenGL ES 2.0 Chromium)") — 风控固定值
    const val DmImgStr = "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ"
    // Linux Chromium UA — 跟 dm_img_str (OpenGL ES Chromium) 指纹一致 (ref: BV BiliUserAgent)
    const val SpaceUserAgent =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
  }
}
