package com.kirin.mt.core.player

import com.kirin.mt.core.network.BiliApiClient
import com.kirin.mt.core.network.BiliApiEndpoints
import com.kirin.mt.core.network.BiliNetworkException
import com.kirin.mt.core.network.asObjectOrNull
import com.kirin.mt.core.network.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class AirJumpRepository(
  private val apiClient: BiliApiClient,
) {
  suspend fun getAirJumpSegments(bvid: String): List<AirJumpSegment> {
    if (bvid.isBlank()) return emptyList()
    val url = BiliApiEndpoints.SponsorBlockSkipSegments.toHttpUrl().newBuilder()
      .addQueryParameter("videoID", bvid)
      .apply {
        AirJumpCategories.forEach { category ->
          addQueryParameter("category", category)
        }
      }
      .build()
      .toString()

    val root = try {
      apiClient.getJson(url = url)
    } catch (error: BiliNetworkException) {
      if (error.statusCode == 404) return emptyList()
      throw error
    }
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
    val AirJumpCategories = listOf("sponsor", "intro", "outro", "interaction", "selfpromo")
  }
}
