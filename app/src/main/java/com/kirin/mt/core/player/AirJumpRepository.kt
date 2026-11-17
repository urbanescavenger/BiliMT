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
   * 按 bvid(+cid) 缓存拉到的段。仅成功结果（含「真无段」的 404/空数组）才缓存，
   * 网络失败不缓存、允许下次重试。作用：
   * 1) 二次进入播放器秒回（复用首次成功结果）；
   * 2) 冷连接首击即使慢/失败，靠多节点回退 + 重试兜住，不会整次播放无段。
   */
  private val segmentsCache = ConcurrentHashMap<String, List<AirJumpSegment>>()

  suspend fun getAirJumpSegments(bvid: String, cid: Long = 0L): List<AirJumpSegment> {
    if (bvid.isBlank()) return emptyList()
    // 多分P视频的段按 cid 归属：只按 bvid 缓存会把别的分P的段套到当前分P上。
    val cacheKey = if (cid > 0L) "$bvid+$cid" else bvid
    segmentsCache[cacheKey]?.let { cached ->
      Log.d(LogTag, "hit cache: bvid=$bvid cid=$cid segments=${cached.size}")
      return cached
    }

    val totalAttempts = AirJumpRetryAttempts + 1
    var lastError: Throwable? = null
    repeat(totalAttempts) { attempt ->
      // 每次尝试换下一个镜像节点：官方 primary → alias → IP mirror，再循环。
      // 原实现 3 次全打同一个 bsbsb.top，节点抖动/超时（真机日志 09-24、09-30、10-31、11-13
      // 均有 15s timeout ×3）时整场无段。
      val server = AirJumpServers[attempt % AirJumpServers.size]
      try {
        val segments = fetchSegments(server, bvid = bvid, cid = cid)
        // 成功（含 404 真无段）都缓存，避免同一视频反复请求冷连接。
        segmentsCache[cacheKey] = segments
        Log.i(
          LogTag,
          "loaded segments: bvid=$bvid cid=$cid server=${server.host} segments=${segments.size} " +
            "(attempt=${attempt + 1}/$totalAttempts)",
        )
        return segments
      } catch (error: kotlinx.coroutines.CancellationException) {
        // 协程被取消(Compose 效果重启/scope 离开)不是网络错误,重抛让它立刻死,
        // 别当可重试错误吞掉——retry 的 delay 会立刻再抛,徒增 "scope left" 日志噪音。
        throw error
      } catch (error: BiliNetworkException) {
        lastError = error
        if (error.statusCode == 404) {
          // 该视频在 SponsorBlock 无段，缓存空列表，下次秒回不再请求。
          segmentsCache[cacheKey] = emptyList()
          Log.i(LogTag, "no segments (404): bvid=$bvid cid=$cid server=${server.host}")
          return emptyList()
        }
        if (attempt < AirJumpRetryAttempts) {
          Log.w(
            LogTag,
            "fetch failed (attempt=${attempt + 1}/$totalAttempts) bvid=$bvid cid=$cid " +
              "server=${server.host} status=${error.statusCode}, retrying next node",
            error,
          )
          delay(AirJumpRetryDelayMs)
        }
      } catch (error: Throwable) {
        lastError = error
        if (attempt < AirJumpRetryAttempts) {
          Log.w(
            LogTag,
            "fetch error (attempt=${attempt + 1}/$totalAttempts) bvid=$bvid cid=$cid " +
              "server=${server.host}, retrying next node",
            error,
          )
          delay(AirJumpRetryDelayMs)
        }
      }
    }
    Log.w(
      LogTag,
      "fetch failed after $totalAttempts attempts across ${AirJumpServers.size} nodes: " +
        "bvid=$bvid cid=$cid",
      lastError,
    )
    throw lastError ?: IllegalStateException("air jump fetch failed: bvid=$bvid cid=$cid")
  }

  private suspend fun fetchSegments(server: AirJumpServer, bvid: String, cid: Long): List<AirJumpSegment> {
    val url = server.url.toHttpUrl().newBuilder()
      .addQueryParameter("videoID", bvid)
      .apply {
        // 分P视频必须带 cid：不带时镜像会返回所有分P的段，别的分P的广告时间会被套到当前分P上
        // （2026-11-13 真机 BV1pg4x61Ek4 拿到 970s 与 1016s 两段，前者属于另一个 cid）。
        if (cid > 0L) addQueryParameter("cid", cid.toString())
        AirJumpCategories.forEach { category ->
          addQueryParameter("category", category)
        }
      }
      .build()
      .toString()

    val root = apiClient.getJson(url = url, callTimeoutMs = AirJumpRequestTimeoutMs)
    return (root as? JsonArray)
      ?.mapNotNull { element -> element.asObjectOrNull()?.toAirJumpSegment(expectedCid = cid) }
      ?.filter { segment -> segment.durationMs > 0L }
      ?.sortedBy(AirJumpSegment::startMs)
      .orEmpty()
  }

  private fun JsonObject.toAirJumpSegment(expectedCid: Long): AirJumpSegment? {
    val segmentArray = this["segment"] as? JsonArray ?: return null
    // 双保险：请求已带 cid，但部分镜像/缓存可能忽略该参数；响应自带 cid 时客户端再过滤一次。
    // 响应没有 cid 字段（老接口）时保留，宁可多跳也不因字段缺失整段丢弃。
    val segmentCid = string("cid")
    if (expectedCid > 0L && segmentCid.isNotBlank() && segmentCid != expectedCid.toString()) return null
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

  private data class AirJumpServer(val url: String) {
    val host: String
      get() = runCatching { url.toHttpUrl().host }.getOrDefault(url)
  }

  private companion object {
    const val LogTag = "BiliMT:AirJump"
    val AirJumpCategories = listOf("sponsor", "intro", "outro", "interaction", "selfpromo")

    // 与 BilibiliSponsorBlock 扩展 ota/servers.json 的官方节点顺序一致：
    // primary → alias → IP mirror。单节点挂掉时仍有可用源。
    val AirJumpServers = listOf(
      AirJumpServer(BiliApiEndpoints.SponsorBlockSkipSegments),
      AirJumpServer("https://bsbsb.cc/api/skipSegments"),
      AirJumpServer("http://103.236.70.57:9876/api/skipSegments"),
    )
    // 扩展对分段请求用 6s 超时（serverConfig.hashRequestTimeoutMs）；共享客户端 15s 太长，
    // 一个坏节点就要卡满 15s 才轮到下一个。
    const val AirJumpRequestTimeoutMs = 6_000L
    const val AirJumpRetryAttempts = 2
    const val AirJumpRetryDelayMs = 1_500L
  }
}
