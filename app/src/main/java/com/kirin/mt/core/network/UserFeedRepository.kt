package com.kirin.mt.core.network

import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray

internal class UserFeedRepository(
  private val apiClient: BiliApiClient,
  private val sessionStore: SessionStore,
) {
  suspend fun getDynamicFeed(offset: String = ""): DynamicFeedPage {
    val sessData = sessionStore.sessData.first()
    if (sessData.isNullOrBlank()) {
      return DynamicFeedPage(videos = emptyList(), offset = "", hasMore = false)
    }

    val root = apiClient.getJson(
      url = BiliApiEndpoints.DynamicFeed,
      params = buildMap {
        put("type", "all")
        if (offset.isNotBlank()) {
          put("offset", offset)
        }
      },
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("dynamic feed")

    val data = root.obj("data") ?: return DynamicFeedPage(videos = emptyList(), offset = "", hasMore = false)
    val items = data["items"] as? JsonArray ?: return DynamicFeedPage(videos = emptyList(), offset = "", hasMore = false)
    val videos = items
      .mapNotNull { it.asObjectOrNull() }
      .mapNotNull(VideoSummaryMappers::fromDynamicItem)
      .filter { it.bvid.isNotBlank() }

    return DynamicFeedPage(
      videos = videos,
      offset = data.string("offset"),
      hasMore = data.boolean("has_more"),
    )
  }

  suspend fun getHistoryPage(
    pageSize: Int,
    viewAt: Long = 0L,
    max: Long = 0L,
  ): HistoryFeedPage {
    val sessData = sessionStore.sessData.first()
    if (sessData.isNullOrBlank()) {
      return HistoryFeedPage(videos = emptyList(), nextViewAt = 0L, nextMax = 0L, hasMore = false)
    }

    val root = apiClient.getJson(
      url = BiliApiEndpoints.HistoryCursor,
      params = buildMap {
        put("ps", pageSize.toString())
        if (viewAt > 0L) {
          put("view_at", viewAt.toString())
        }
        if (max > 0L) {
          put("max", max.toString())
        }
      },
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("history")

    val data = root.obj("data") ?: return HistoryFeedPage(videos = emptyList(), nextViewAt = 0L, nextMax = 0L, hasMore = false)
    val list = data["list"] as? JsonArray ?: return HistoryFeedPage(videos = emptyList(), nextViewAt = 0L, nextMax = 0L, hasMore = false)
    val videos = list
      .mapNotNull { it.asObjectOrNull() }
      .map(VideoSummaryMappers::fromHistory)
      .filter { it.bvid.isNotBlank() || it.isLive }

    val cursor = data.obj("cursor")
    return HistoryFeedPage(
      videos = videos,
      nextViewAt = cursor?.long("view_at") ?: 0L,
      nextMax = cursor?.long("max") ?: 0L,
      hasMore = videos.isNotEmpty() && cursor != null,
    )
  }
}

data class DynamicFeedPage(
  val videos: List<VideoSummary>,
  val offset: String,
  val hasMore: Boolean,
)

data class HistoryFeedPage(
  val videos: List<VideoSummary>,
  val nextViewAt: Long,
  val nextMax: Long,
  val hasMore: Boolean,
)
