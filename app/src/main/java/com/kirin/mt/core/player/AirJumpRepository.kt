package com.kirin.mt.core.player

import android.util.Log
import com.kirin.mt.core.network.BiliApiClient
import com.kirin.mt.core.network.BiliApiEndpoints
import com.kirin.mt.core.network.BiliNetworkException
import com.kirin.mt.core.network.asObjectOrNull
import com.kirin.mt.core.network.string
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class AirJumpRepository(
  private val apiClient: BiliApiClient,
) {
  /**
   * 按 bvid 缓存拉到的段。仅成功结果（含「真无段」的 404/空数组）才缓存，
   * 网络失败不缓存、允许下次重试。作用：
   * 1) 二次进入播放器秒回（复用首次成功结果）；
   * 2) 冷连接首击即使慢/失败，靠 [AirJumpRetryAttempts] 重试兜住，不会整次播放无段。
   */
  private val segmentsCache = ConcurrentHashMap<String, List<AirJumpSegment>>()

  suspend fun getAirJumpSegments(bvid: String): List<AirJumpSegment> {
    if (bvid.isBlank()) return emptyList()
    segmentsCache[bvid]?.let { cached ->
      Log.d(LogTag, "hit cache: bvid=$bvid segments=${cached.size}")
      return cached
    }

    val totalAttempts = AirJumpRetryAttempts + 1
    repeat(totalAttempts) { attempt ->
      try {
        val segments = fetchSegments(bvid)
        // 成功（含 404 真无段）都缓存，避免同一视频反复请求冷连接。
        segmentsCache[bvid] = segments
        Log.i(LogTag, "loaded segments: bvid=$bvid segments=${segments.size} (attempt=${attempt + 1}/$totalAttempts)")
        return segments
      } catch (error: BiliNetworkException) {
        if (error.statusCode == 404) {
          // 该视频在 SponsorBlock 无段，缓存空列表，下次秒回不再请求。
          segmentsCache[bvid] = emptyList()
          Log.i(LogTag, "no segments (404): bvid=$bvid")
          return emptyList()
        }
        if (attempt < AirJumpRetryAttempts) {
          Log.w(LogTag, "fetch failed (attempt=${attempt + 1}/$totalAttempts) bvid=$bvid status=${error.statusCode}, retrying", error)
          delay(AirJumpRetryDelayMs)
        } else {
          Log.w(LogTag, "fetch failed after $totalAttempts attempts: bvid=$bvid status=${error.statusCode}", error)
          throw error
        }
      } catch (error: Throwable) {
        if (attempt < AirJumpRetryAttempts) {
          Log.w(LogTag, "fetch error (attempt=${attempt + 1}/$totalAttempts) bvid=$bvid, retrying", error)
          delay(AirJumpRetryDelayMs)
        } else {
          Log.w(LogTag, "fetch error after $totalAttempts attempts: bvid=$bvid", error)
          throw error
        }
      }
    }
    return emptyList()
  }

  private suspend fun fetchSegments(bvid: String): List<AirJumpSegment> {
    val url = BiliApiEndpoints.SponsorBlockSkipSegments.toHttpUrl().newBuilder()
      .addQueryParameter("videoID", bvid)
      .apply {
        AirJumpCategories.forEach { category ->
          addQueryParameter("category", category)
        }
      }
      .build()
      .toString()

    val root = apiClient.getJson(url = url)
    return (root as? JsonArray)
      ?.mapNotNull { element -> element.asObjectOrNull()?.toAirJumpSegment() }
      ?.filter { segment -> segment.durationMs > 0L }
      ?.sortedBy(AirJumpSegment::startMs)
      .orEmpty()
  }

  private fun JsonObject.toAirJumpSegment(): AirJumpSegment? {
    val segmentArray = this["segment"] as? JsonArray ?: return null
    val startSeconds = segmentArray.getOrNull(0)?.asString()?.toDoubleOrNull() ?: return null
    val endSeconds = segmentArray.getOrNull(1)?.asString()?.toDoubleOrNull() ?: return null
    val category = string("category").ifBlank { "unknown" }
    val startMs = (startSeconds * 1000.0).toLong().coerceAtLeast(0L)
    val endMs = (endSeconds * 1000.0).toLong().coerceAtLeast(0L)
    if (endMs <= startMs) return null
    return AirJumpSegment(
      id = string("UUID").ifBlank { "$category:$startMs:$endMs" },
      category = category,
      startMs = startMs,
      endMs = endMs,
    )
  }

  private fun JsonElement.asString(): String {
    return (this as? JsonPrimitive)?.contentOrNull ?: toString().trim('"')
  }

  private companion object {
    const val LogTag = "BiliMT:AirJump"
    val AirJumpCategories = listOf("sponsor", "intro", "outro", "interaction", "selfpromo")
    const val AirJumpRetryAttempts = 2
    const val AirJumpRetryDelayMs = 1_500L
  }
}
