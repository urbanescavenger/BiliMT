package com.kirin.mt.core.network

import com.kirin.mt.core.model.Comment
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray

internal class UserFeedRepository(
  private val apiClient: BiliApiClient,
  private val sessionStore: SessionStore,
) {
  suspend fun getDynamicFeed(offset: String = "", type: String = "video"): DynamicFeedPage {
    val sessData = sessionStore.sessData.first()
    if (sessData.isNullOrBlank()) {
      return DynamicFeedPage(videos = emptyList(), offset = "", hasMore = false)
    }

    val root = apiClient.getJson(
      url = BiliApiEndpoints.DynamicFeed,
      params = buildMap {
        put("type", type)
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

  suspend fun getDynamicUnread(): Int {
    val sessData = sessionStore.sessData.first()
    if (sessData.isNullOrBlank()) return 0

    val root = apiClient.getJson(
      url = BiliApiEndpoints.DynamicUnread,
      sessData = sessData,
    ).rootObject()
    if (root.int("code") != 0) return 0
    val data = root.obj("data") ?: return 0
    // web 接口未读数在 data.new_default(关注动态)或 data.new;两者都取较大值兜底。
    val default = data.int("new_default")
    val newCount = data.int("new")
    return maxOf(default, newCount)
  }

  suspend fun likeDynamic(dynId: String): Boolean {
    if (dynId.isBlank()) return false
    val sessData = sessionStore.sessData.first()
    val biliJct = sessionStore.biliJct.first()
    if (sessData.isNullOrBlank() || biliJct.isNullOrBlank()) return false

    val root = apiClient.postFormJson(
      url = BiliApiEndpoints.DynamicLike,
      params = mapOf(
        "dyn_id" to dynId,
        "csrf" to biliJct,
      ),
      sessData = sessData,
      biliJct = biliJct,
    ).rootObject()
    root.requireBiliCodeOk("dynamic like")
    return true
  }

  suspend fun addToView(aid: Long): Boolean {
    if (aid <= 0L) return false
    val sessData = sessionStore.sessData.first()
    val biliJct = sessionStore.biliJct.first()
    if (sessData.isNullOrBlank() || biliJct.isNullOrBlank()) return false

    val root = apiClient.postFormJson(
      url = BiliApiEndpoints.ToviewAdd,
      params = mapOf(
        "aid" to aid.toString(),
        "csrf" to biliJct,
      ),
      sessData = sessData,
      biliJct = biliJct,
    ).rootObject()
    root.requireBiliCodeOk("toview add")
    return true
  }

  suspend fun getComments(
    aid: Long,
    page: Int,
    sort: Int,
    pageSize: Int = 20,
  ): CommentPage {
    if (aid <= 0L) return CommentPage(comments = emptyList(), currentPage = page, hasMore = false)
    val sessData = sessionStore.sessData.first()

    val root = apiClient.getJson(
      url = BiliApiEndpoints.CommentReply,
      params = mapOf(
        "oid" to aid.toString(),
        "type" to "1",
        "pn" to page.toString(),
        "ps" to pageSize.toString(),
        "sort" to sort.toString(),
      ),
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("comments")

    val data = root.obj("data") ?: return CommentPage(comments = emptyList(), currentPage = page, hasMore = false)
    val replies = data["replies"] as? JsonArray
      ?: return CommentPage(comments = emptyList(), currentPage = page, hasMore = false)
    val comments = replies
      .mapNotNull { it.asObjectOrNull() }
      .map(VideoSummaryMappers::fromComment)
    val count = data.obj("page")?.int("count") ?: comments.size
    val hasMore = page * pageSize < count && comments.isNotEmpty()
    return CommentPage(comments = comments, currentPage = page, hasMore = hasMore)
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

  suspend fun getFavoriteFolders(mid: Long): List<FavoriteFolder> {
    if (mid <= 0L) return emptyList()
    val sessData = sessionStore.sessData.first()
    if (sessData.isNullOrBlank()) return emptyList()

    val root = apiClient.getJson(
      url = BiliApiEndpoints.FavoriteFolderListAll,
      params = mapOf(
        "up_mid" to mid.toString(),
        "type" to "0",
      ),
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("favorite folders")

    val data = root.obj("data") ?: return emptyList()
    val list = data["list"] as? JsonArray ?: return emptyList()
    return list
      .mapNotNull { it.asObjectOrNull() }
      .map { folder ->
        FavoriteFolder(
          mediaId = folder.long("id"),
          title = folder.string("title"),
          mediaCount = folder.int("media_count"),
        )
      }
      .filter { it.mediaId > 0L }
  }

  suspend fun getFavoriteFolderVideos(
    mediaId: Long,
    page: Int,
    pageSize: Int = 20,
    order: String = "mtime",
  ): FavoriteFolderPage {
    if (mediaId <= 0L) {
      return FavoriteFolderPage(videos = emptyList(), hasMore = false)
    }
    val sessData = sessionStore.sessData.first()
    if (sessData.isNullOrBlank()) {
      return FavoriteFolderPage(videos = emptyList(), hasMore = false)
    }

    val root = apiClient.getJson(
      url = BiliApiEndpoints.FavoriteResourceList,
      params = mapOf(
        "media_id" to mediaId.toString(),
        "pn" to page.toString(),
        "ps" to pageSize.toString(),
        "order" to order,
        "type" to "0",
        "tid" to "0",
      ),
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("favorite folder videos")

    val data = root.obj("data") ?: return FavoriteFolderPage(videos = emptyList(), hasMore = false)
    val medias = data["medias"] as? JsonArray ?: return FavoriteFolderPage(videos = emptyList(), hasMore = false)
    val videos = medias
      .mapNotNull { it.asObjectOrNull() }
      .map(VideoSummaryMappers::fromFavoriteItem)
      .filter { it.bvid.isNotBlank() }

    return FavoriteFolderPage(
      videos = videos,
      hasMore = data.boolean("has_more") && videos.isNotEmpty(),
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

data class FavoriteFolder(
  val mediaId: Long,
  val title: String,
  val mediaCount: Int,
)

data class FavoriteFolderPage(
  val videos: List<VideoSummary>,
  val hasMore: Boolean,
)

data class CommentPage(
  val comments: List<Comment>,
  val currentPage: Int,
  val hasMore: Boolean,
)
