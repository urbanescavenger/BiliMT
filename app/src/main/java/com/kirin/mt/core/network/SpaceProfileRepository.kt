package com.kirin.mt.core.network

import android.util.Log
import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.model.SpaceUserProfile
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * Fetches a UP主's profile: core info via `x/space/acc/info` (wbi-signed) and fan/following counts
 * via `x/relation/stat` (best-effort, `vmid` param). Mirrors [SpaceVideoRepository]'s wbi sign +
 * refresh fallback + buvid cookie handling (via [SpaceHttpSupport]).
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
      val accInfoDeferred = async { fetchAccInfo(mid, sessData, biliJct, session.mid, buvid3, buvid4) }
      val statDeferred = async {
        runCatching { fetchRelationStat(mid, sessData, biliJct, session.mid, buvid3, buvid4) }
          .onFailure { error -> Log.w(LogTag, "relation/stat failed: ${error.toSpaceProfileBrief()}") }
          .getOrNull()
      }
      val accInfo = accInfoDeferred.await()
      SpaceProfileMappers.mergeRelationStat(accInfo, statDeferred.await())
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
    val signedParams = if (keys != null) {
      wbiSigner.sign(params, keys.imgKey, keys.subKey)
    } else {
      params
    }
    val headers = SpaceHttpSupport.headers(
      mid = mid.toString(),
      sessData = sessData,
      biliJct = biliJct,
      dedeUserId = dedeUserId,
      buvid3 = buvid3,
      buvid4 = buvid4,
    )
    return runCatching {
      val root = apiClient.getJsonWithHeaders(
        url = BiliApiEndpoints.SpaceAccInfo,
        params = signedParams,
        headers = headers,
      ).rootObject()
      root.requireBiliCodeOk("space acc/info")
      SpaceProfileMappers.fromAccInfo(root.obj("data") ?: JsonObject(emptyMap()), mid)
    }.getOrElse { signedError ->
      logAccInfoFailure("signed", signedError)
      val refreshedKeys = wbiKeyRepository.refreshKeys(sessData)
      if (refreshedKeys != null) {
        val refreshedSignedParams = wbiSigner.sign(params, refreshedKeys.imgKey, refreshedKeys.subKey)
        runCatching {
          val root = apiClient.getJsonWithHeaders(
            url = BiliApiEndpoints.SpaceAccInfo,
            params = refreshedSignedParams,
            headers = headers,
          ).rootObject()
          root.requireBiliCodeOk("space acc/info refreshed")
          SpaceProfileMappers.fromAccInfo(root.obj("data") ?: JsonObject(emptyMap()), mid)
        }.onFailure { refreshedError -> logAccInfoFailure("refreshed", refreshedError) }
          .getOrThrow()
      } else if (signedParams !== params) {
        runCatching {
          val root = apiClient.getJsonWithHeaders(
            url = BiliApiEndpoints.SpaceAccInfo,
            params = params,
            headers = headers,
          ).rootObject()
          root.requireBiliCodeOk("space acc/info fallback")
          SpaceProfileMappers.fromAccInfo(root.obj("data") ?: JsonObject(emptyMap()), mid)
        }.onFailure { fallbackError -> logAccInfoFailure("unsigned fallback", fallbackError) }
          .getOrNull() ?: throw signedError
      } else {
        throw signedError
      }
    }
  }

  private suspend fun fetchRelationStat(
    mid: Long,
    sessData: String?,
    biliJct: String?,
    dedeUserId: Long?,
    buvid3: String?,
    buvid4: String?,
  ): JsonObject {
    val root = apiClient.getJsonWithHeaders(
      url = BiliApiEndpoints.RelationStat,
      params = mapOf("vmid" to mid.toString()),
      headers = SpaceHttpSupport.headers(
        mid = mid.toString(),
        sessData = sessData,
        biliJct = biliJct,
        dedeUserId = dedeUserId,
        buvid3 = buvid3,
        buvid4 = buvid4,
      ),
    ).rootObject()
    root.requireBiliCodeOk("relation stat")
    return root.obj("data") ?: JsonObject(emptyMap())
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