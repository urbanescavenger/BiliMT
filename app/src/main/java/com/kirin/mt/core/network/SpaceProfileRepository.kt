package com.kirin.mt.core.network

import android.util.Log
import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.model.SpaceUserProfile
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * Fetches a UP主's profile: core info via `x/space/acc/info` (wbi-signed) and fan/following counts
 * via `x/relation/stat` (best-effort, `vmid` param). Mirrors [SpaceVideoRepository]'s wbi sign +
 * refresh fallback + buvid cookie handling (via [SpaceHttpSupport]).
 *
 * 两条请求都走 [SpaceHttpSupport] 的退避重试:进一次 UP 页会在同一秒发出 acc/info + relation/stat
 * + wbi/arc/search,足以撞上 B站 对 space 接口的频控(-799)/风控(412),退避 2s 后即恢复。
 * acc/info 最终失败时不再连坐丢弃已经成功的 relation/stat —— 页面用视频卡带的昵称头像兜底,
 * 统计行仍给出真实数字(P11-179)。
 */
internal class SpaceProfileRepository(
  private val apiClient: BiliApiClient,
  private val wbiKeyRepository: WbiKeyRepository,
  private val wbiSigner: WbiSigner,
  private val sessionStore: SessionStore,
) {
  suspend fun getSpaceUserProfile(mid: Long): SpaceUserProfile {
    require(mid > 0L) { "invalid mid=$mid" }

    val session = sessionStore.session.first()
    val sessData = session.sessData
    val biliJct = session.biliJct
    val (buvid3, buvid4) = SpaceHttpSupport.ensureBuvidCookies(sessionStore, apiClient)

    return coroutineScope {
      val accInfoDeferred = async {
        runCatching { fetchAccInfo(mid, sessData, biliJct, session.mid, buvid3, buvid4) }
      }
      val statDeferred = async {
        runCatching { fetchRelationStat(mid, sessData, biliJct, session.mid, buvid3, buvid4) }
          .onFailure { error -> Log.w(LogTag, "relation/stat failed: ${error.toSpaceProfileBrief()}") }
          .getOrNull()
      }
      val accInfoResult = accInfoDeferred.await()
      val stat = statDeferred.await()
      val accInfo = accInfoResult.getOrNull()
      if (accInfo == null) {
        // acc/info 最终失败但统计成功:返回「空资料 + 真统计」的降级结果,而不是让统计也变 0。
        val accInfoError = accInfoResult.exceptionOrNull()
        if (stat == null) throw accInfoError ?: IllegalStateException("space acc/info failed")
        Log.w(LogTag, "acc/info failed, keep relation/stat only: ${accInfoError?.toSpaceProfileBrief().orEmpty()}")
        val statsOnly = SpaceProfileMappers.mergeRelationStat(
          SpaceProfileMappers.fromAccInfo(JsonObject(emptyMap()), mid),
          stat,
        )
        Log.i(LogTag, "profile mid=$mid partial fans=${statsOnly.fans} following=${statsOnly.following}")
        return@coroutineScope statsOnly
      }
      val merged = SpaceProfileMappers.mergeRelationStat(accInfo, stat)
      Log.i(LogTag, "profile mid=$mid ok hasStats=${stat != null} fans=${merged.fans} following=${merged.following}")
      merged
    }
  }

  private suspend fun fetchAccInfo(
    mid: Long,
    sessData: String?,
    biliJct: String?,
    dedeUserId: Long?,
    buvid3: String?,
    buvid4: String?,
  ): SpaceUserProfile {
    val keys = wbiKeyRepository.ensureKeys(sessData)
    val params = mutableMapOf(
      "mid" to mid.toString(),
      "platform" to "web",
      "web_location" to SpaceHttpSupport.SpaceWebLocation,
    )
    val headers = SpaceHttpSupport.headers(
      mid = mid.toString(),
      sessData = sessData,
      biliJct = biliJct,
      dedeUserId = dedeUserId,
      buvid3 = buvid3,
      buvid4 = buvid4,
    )
    return runCatching {
      fetchAccInfoWithRetry(
        mid = mid,
        params = params,
        imgKey = keys?.imgKey,
        subKey = keys?.subKey,
        headers = headers,
        context = "signed",
        retryDelaysMs = SpaceHttpSupport.InteractiveRetryDelaysMs,
      )
    }.getOrElse { signedError ->
      logAccInfoFailure("signed", signedError)
      val refreshedKeys = wbiKeyRepository.refreshKeys(sessData)
      if (refreshedKeys != null) {
        runCatching {
          fetchAccInfoWithRetry(
            mid = mid,
            params = params,
            imgKey = refreshedKeys.imgKey,
            subKey = refreshedKeys.subKey,
            headers = headers,
            context = "refreshed",
            retryDelaysMs = SpaceHttpSupport.RecoveryRetryDelaysMs,
          )
        }.onFailure { refreshedError -> logAccInfoFailure("refreshed", refreshedError) }
          .getOrThrow()
      } else if (keys != null) {
        runCatching {
          fetchAccInfoWithRetry(
            mid = mid,
            params = params,
            imgKey = null,
            subKey = null,
            headers = headers,
            context = "unsigned fallback",
            retryDelaysMs = SpaceHttpSupport.RecoveryFallbackRetryDelaysMs,
          )
        }.onFailure { fallbackError -> logAccInfoFailure("unsigned fallback", fallbackError) }
          .getOrNull() ?: throw signedError
      } else {
        throw signedError
      }
    }
  }

  /**
   * Signs with a fresh `wts`/`w_rid` per attempt and retries the transient 风控/频控 codes
   * ([SpaceHttpSupport.isRetryableFailure]) with backoff — same ladder as [SpaceVideoRepository].
   */
  private suspend fun fetchAccInfoWithRetry(
    mid: Long,
    params: Map<String, String>,
    imgKey: String?,
    subKey: String?,
    headers: Map<String, String>,
    context: String,
    retryDelaysMs: LongArray,
  ): SpaceUserProfile {
    var lastError: Throwable? = null
    repeat(retryDelaysMs.size + 1) { attempt ->
      if (attempt > 0) {
        val delayMs = retryDelaysMs[attempt - 1]
        Log.i(LogTag, "space acc/info $context retry attempt=${attempt + 1} delayMs=$delayMs mid=$mid")
        delay(delayMs)
      }
      val signedParams = if (imgKey != null && subKey != null) {
        wbiSigner.sign(params, imgKey, subKey)
      } else {
        params
      }
      val result = runCatching {
        val root = apiClient.getJsonWithHeaders(
          url = BiliApiEndpoints.SpaceAccInfo,
          params = signedParams,
          headers = headers,
        ).rootObject()
        root.requireBiliCodeOk("space acc/info $context")
        SpaceProfileMappers.fromAccInfo(root.obj("data") ?: JsonObject(emptyMap()), mid)
      }
      result.onSuccess { return it }
      val error = result.exceptionOrNull() ?: return@repeat
      lastError = error
      if (!SpaceHttpSupport.isRetryableFailure(error)) throw error
      Log.w(
        LogTag,
        "space acc/info $context retryable failure attempt=${attempt + 1}: ${error.toSpaceProfileBrief()}",
      )
    }
    throw lastError ?: IllegalStateException("space acc/info $context failed")
  }

  private suspend fun fetchRelationStat(
    mid: Long,
    sessData: String?,
    biliJct: String?,
    dedeUserId: Long?,
    buvid3: String?,
    buvid4: String?,
  ): JsonObject {
    val headers = SpaceHttpSupport.headers(
      mid = mid.toString(),
      sessData = sessData,
      biliJct = biliJct,
      dedeUserId = dedeUserId,
      buvid3 = buvid3,
      buvid4 = buvid4,
    )
    val retryDelaysMs = SpaceHttpSupport.InteractiveRetryDelaysMs
    var lastError: Throwable? = null
    repeat(retryDelaysMs.size + 1) { attempt ->
      if (attempt > 0) {
        val delayMs = retryDelaysMs[attempt - 1]
        Log.i(LogTag, "space relation/stat retry attempt=${attempt + 1} delayMs=$delayMs mid=$mid")
        delay(delayMs)
      }
      val result = runCatching {
        val root = apiClient.getJsonWithHeaders(
          url = BiliApiEndpoints.RelationStat,
          params = mapOf("vmid" to mid.toString()),
          headers = headers,
        ).rootObject()
        root.requireBiliCodeOk("relation stat")
        root.obj("data") ?: JsonObject(emptyMap())
      }
      result.onSuccess { return it }
      val error = result.exceptionOrNull() ?: return@repeat
      lastError = error
      if (!SpaceHttpSupport.isRetryableFailure(error)) throw error
      Log.w(LogTag, "space relation/stat retryable failure attempt=${attempt + 1}: ${error.toSpaceProfileBrief()}")
    }
    throw lastError ?: IllegalStateException("relation stat failed")
  }

  private fun logAccInfoFailure(stage: String, error: Throwable) {
    Log.w(LogTag, "space acc/info $stage failed: ${error.toSpaceProfileBrief()}")
  }

  private fun Throwable.toSpaceProfileBrief(): String {
    return when (this) {
      is BiliApiCodeException -> "code=$code message=$biliMessage"
      is BiliNetworkException -> "http=$statusCode body=${responseBody.take(LogBodyPreviewLength)}"
      else -> "${javaClass.simpleName}: ${message.orEmpty()}"
    }
  }

  private companion object {
    const val LogTag = "BiliSpaceProfile"
    const val LogBodyPreviewLength = 160
  }
}