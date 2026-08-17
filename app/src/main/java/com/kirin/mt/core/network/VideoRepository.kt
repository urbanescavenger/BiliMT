package com.kirin.mt.core.network

import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.model.PgcFeedPage
import com.kirin.mt.core.model.PgcIndexFilters
import com.kirin.mt.core.model.PgcIndexPage
import com.kirin.mt.core.model.PgcIndexResult
import com.kirin.mt.core.model.PgcSeason
import com.kirin.mt.core.model.PgcType
import com.kirin.mt.core.model.SpaceUserProfile
import com.kirin.mt.core.model.UgcBannerItem
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeChannelStore
import com.kirin.mt.core.youtube.YoutubeCommentPage
import com.kirin.mt.core.youtube.YoutubeRepository
import com.kirin.mt.core.youtube.YoutubeSubscriptionsPage
import com.kirin.mt.core.youtube.YoutubeVideoDetail
import com.kirin.mt.core.youtube.YoutubeVideoPage
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray

/**
 * YouTube 关注流缓存的有效期(ms)。10 分钟内复用缓存秒出,超过则等网络刷新。
 *
 * alpha.98:删除 youtubeFeedTimeoutMs 外层全局超时。分批增量拉取([YoutubeRepository.getSubscriptionsFeed]
 * 按频道分块 + onChunkReady 逐批回调)下,单频道独立容错、不整批失败,几百频道也不需外层预算。
 */
const val YoutubeFeedCacheTtlMs = 10 * 60 * 1000L

/** 把 B 站动态与 YouTube 关注流按发布时间倒序合并成统一流。 */
fun mergeByPubdate(bili: List<VideoSummary>, youtube: List<VideoSummary>): List<VideoSummary> =
  (bili + youtube).sortedByDescending { it.pubdate }

class VideoRepository(
  private val apiClient: BiliApiClient,
  private val wbiKeyRepository: WbiKeyRepository,
  private val wbiSigner: WbiSigner,
  private val sessionStore: SessionStore,
  private val youtubeRepository: YoutubeRepository,
  private val youtubeChannelStore: YoutubeChannelStore,
) {
  private val spaceVideoRepository = SpaceVideoRepository(
    apiClient = apiClient,
    wbiKeyRepository = wbiKeyRepository,
    wbiSigner = wbiSigner,
    sessionStore = sessionStore,
  )
  private val searchVideoRepository = SearchVideoRepository(
    apiClient = apiClient,
    wbiKeyRepository = wbiKeyRepository,
    wbiSigner = wbiSigner,
    sessionStore = sessionStore,
  )
  private val homeVideoRepository = HomeVideoRepository(
    apiClient = apiClient,
    wbiKeyRepository = wbiKeyRepository,
    wbiSigner = wbiSigner,
    sessionStore = sessionStore,
  )
  private val userFeedRepository = UserFeedRepository(
    apiClient = apiClient,
    sessionStore = sessionStore,
  )
  private val spaceProfileRepository = SpaceProfileRepository(
    apiClient = apiClient,
    wbiKeyRepository = wbiKeyRepository,
    wbiSigner = wbiSigner,
    sessionStore = sessionStore,
  )
  private val pgcVideoRepository = PgcVideoRepository(
    apiClient = apiClient,
    sessionStore = sessionStore,
  )

  suspend fun getHomeSectionVideos(
    section: HomeSection,
    page: Int = 1,
    idx: Int = 0,
  ): List<VideoSummary> {
    // YouTube 热门 tab 改用"关注动态":拉关注频道的订阅流(RSS+InnerTube 合并),与移动端
    // 首页/动态一致。分页忽略(订阅流单页,滚动翻页 dedup 后自然到底);未关注/超时返回空。
    // alpha.98:去 withTimeoutOrNull 全局超时——getSubscriptionsFeed 已分批增量 + 单频道独立容错,
    // 慢频道只丢自身,不整批失败(几百频道不再因外层预算返回空)。
    if (section == HomeSection.YoutubeTrending) {
      val channels = youtubeChannelStore.channels.first()
      if (channels.isEmpty()) return emptyList()
      return youtubeSubscriptionsFeed(channels, onChannelAvatarResolved = { channel ->
        youtubeChannelStore.updateAvatar(channel.channelId, channel.avatar)
      })
    }
    return homeVideoRepository.getHomeSectionVideos(
      section = section,
      page = page,
      idx = idx,
    )
  }

  suspend fun getRegionBanner(tid: Int): List<UgcBannerItem> {
    return homeVideoRepository.getRegionBanner(tid)
  }

  suspend fun getRecommendVideos(idx: Int = 0): List<VideoSummary> {
    return homeVideoRepository.getRecommendVideos(idx)
  }

  suspend fun getRelatedVideos(bvid: String): List<VideoSummary> {
    return homeVideoRepository.getRelatedVideos(bvid)
  }

  suspend fun getPgcFeed(pgcType: PgcType, cursor: Int): PgcFeedPage {
    return pgcVideoRepository.getFeed(pgcType, cursor)
  }

  suspend fun getPgcSeasonInfo(seasonId: Int, epId: Int = 0): PgcSeason? {
    return pgcVideoRepository.getSeasonInfo(seasonId, epId)
  }

  suspend fun getPgcIndex(
    pgcType: PgcType,
    filters: PgcIndexFilters,
    page: PgcIndexPage,
  ): PgcIndexResult {
    return pgcVideoRepository.getPgcIndex(pgcType, filters, page)
  }

  suspend fun getSpaceVideos(
    mid: Long,
    page: Int = 1,
    order: String = SpaceOrderPubdate,
    retryMode: SpaceVideoRetryMode = SpaceVideoRetryMode.Interactive,
  ): List<VideoSummary> {
    return spaceVideoRepository.getSpaceVideos(
      mid = mid,
      page = page,
      order = order,
      retryMode = retryMode,
    )
  }

  suspend fun getSpaceUserProfile(mid: Long): SpaceUserProfile {
    return spaceProfileRepository.getSpaceUserProfile(mid)
  }

  suspend fun checkFollowStatus(mid: Long): Boolean {
    if (mid <= 0L) return false

    val sessData = sessionStore.sessData.first()
    if (sessData.isNullOrBlank()) return false

    val root = apiClient.getJson(
      url = BiliApiEndpoints.Relation,
      params = mapOf("fid" to mid.toString()),
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("relation")

    val attribute = root.obj("data")?.int("attribute") ?: 0
    return attribute == FollowAttribute || attribute == MutualFollowAttribute
  }

  suspend fun setFollowStatus(mid: Long, follow: Boolean): Boolean {
    if (mid <= 0L) return false

    val sessData = sessionStore.sessData.first()
    val biliJct = sessionStore.biliJct.first()
    if (sessData.isNullOrBlank() || biliJct.isNullOrBlank()) return false

    val root = apiClient.postFormJson(
      url = BiliApiEndpoints.RelationModify,
      params = mapOf(
        "fid" to mid.toString(),
        "act" to if (follow) FollowAction.toString() else UnfollowAction.toString(),
        "csrf" to biliJct,
      ),
      sessData = sessData,
      biliJct = biliJct,
    ).rootObject()
    root.requireBiliCodeOk("relation modify")
    return true
  }

  /** 拉取我关注的 B 站用户列表(分页)。镜像 getFollowingSeasons 模式。未登录返回空页。 */
  suspend fun getFollowingUsers(
    mid: Long,
    page: Int,
    pageSize: Int = FollowingUsersPageSize,
  ): FollowingUserPage {
    if (mid <= 0L) return FollowingUserPage(users = emptyList(), total = 0, hasMore = false)
    val sessData = sessionStore.sessData.first()
    if (sessData.isNullOrBlank()) {
      return FollowingUserPage(users = emptyList(), total = 0, hasMore = false)
    }

    val root = apiClient.getJson(
      url = BiliApiEndpoints.RelationFollowings,
      params = mapOf(
        "vmid" to mid.toString(),
        "pn" to page.toString(),
        "ps" to pageSize.toString(),
        "order" to "desc",
        "order_type" to "attention",
      ),
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("following users")

    val data = root.obj("data") ?: return FollowingUserPage(users = emptyList(), total = 0, hasMore = false)
    val list = data["list"] as? JsonArray ?: return FollowingUserPage(users = emptyList(), total = 0, hasMore = false)
    val users = list.mapNotNull { it.asObjectOrNull() }.map {
      FollowingUser(
        mid = it.long("mid"),
        uname = it.string("uname"),
        face = it.string("face"),
        sign = it.string("sign"),
      )
    }
    val total = data.int("total")
    val hasMore = page * pageSize < total && users.isNotEmpty()
    return FollowingUserPage(users = users, total = total, hasMore = hasMore)
  }

  suspend fun searchVideos(
    keyword: String,
    page: Int = 1,
    order: String = SearchOrderTotalRank,
  ): List<VideoSummary> {
    return searchVideoRepository.searchVideos(
      keyword = keyword,
      page = page,
      order = order,
    )
  }

  suspend fun getSearchSuggestions(keyword: String): List<String> {
    return searchVideoRepository.getSearchSuggestions(keyword)
  }

  // ---- YouTube（来源切换：搜索/热门/动态关注） ----

  suspend fun youtubeSearch(
    query: String,
    params: String = "",
    continuation: String? = null,
  ): YoutubeVideoPage {
    val feed = youtubeRepository.search(
      query = query,
      params = params,
      continuation = continuation,
    )
    return YoutubeVideoPage(
      items = feed.items.map(youtubeRepository::toVideoSummary),
      continuation = feed.continuation,
    )
  }

  suspend fun youtubeSubscriptionsFeed(
    channels: List<YoutubeChannel>,
    onChannelAvatarResolved: suspend (YoutubeChannel) -> Unit = {},
    onChunkReady: (List<VideoSummary>) -> Unit = {},
  ): List<VideoSummary> {
    return youtubeRepository.getSubscriptionsFeed(
      channels,
      onChannelAvatarResolved = onChannelAvatarResolved,
      onChunkReady = onChunkReady,
    )
  }

  /**
   * 首页订阅流分页：读取当前关注频道，previousContinuation=null 首屏，否则续页。
   * 返回带每频道续页 token 的页，UI 负责累积去重 + 按 pubdate 排序。
   * 头像回写 store 在此处理（本类持有 [youtubeChannelStore]），UI 无需感知。
   */
  suspend fun youtubeHomeFeedPage(
    previousContinuation: Map<String, String?>? = null,
    onChunkReady: (List<VideoSummary>) -> Unit = {},
  ): YoutubeSubscriptionsPage {
    val channels = youtubeChannelStore.channels.first()
    if (channels.isEmpty()) return YoutubeSubscriptionsPage(emptyList(), emptyMap())
    return youtubeRepository.getSubscriptionsPage(
      channels,
      previousContinuation = previousContinuation,
      onChannelAvatarResolved = { channel ->
        youtubeChannelStore.updateAvatar(channel.channelId, channel.avatar)
      },
      onChunkReady = onChunkReady,
    )
  }

  /** YouTube 视频详情（简介 Tab）。 */
  suspend fun getYoutubeVideoDetail(videoId: String): YoutubeVideoDetail? {
    return youtubeRepository.getVideoDetail(videoId)
  }

  /** YouTube 评论列表（/next + continuation 续页）。 */
  suspend fun getYoutubeComments(videoId: String, continuation: String? = null): YoutubeCommentPage {
    return youtubeRepository.getComments(videoId, continuation)
  }

  /** YouTube 相关视频（/next secondaryResults，对齐 LibreTube）。 */
  suspend fun getYoutubeRelatedVideos(videoId: String, continuation: String? = null): List<VideoSummary> {
    val page = youtubeRepository.getRelatedVideos(videoId, continuation)
    return page.items.map(youtubeRepository::toVideoSummary)
  }

  suspend fun getDynamicFeed(offset: String = "", type: String = "video"): DynamicFeedPage {
    return userFeedRepository.getDynamicFeed(offset = offset, type = type)
  }

  suspend fun getDynamicUnread(): Int {
    return userFeedRepository.getDynamicUnread()
  }

  suspend fun likeDynamic(dynId: String): Boolean {
    return userFeedRepository.likeDynamic(dynId)
  }

  suspend fun addToView(aid: Long): Boolean {
    return userFeedRepository.addToView(aid)
  }

  suspend fun likeVideoArchive(aid: Long): Boolean {
    return userFeedRepository.likeVideoArchive(aid)
  }

  suspend fun coinVideo(aid: Long, multiply: Int, selectLike: Boolean): Boolean {
    return userFeedRepository.coinVideo(aid, multiply, selectLike)
  }

  suspend fun dealFavorite(
    aid: Long,
    addMediaIds: List<Long>,
    delMediaIds: List<Long>,
  ): Boolean {
    return userFeedRepository.dealFavorite(aid, addMediaIds, delMediaIds)
  }

  suspend fun getComments(aid: Long, page: Int, sort: Int): CommentPage {
    return userFeedRepository.getComments(aid = aid, page = page, sort = sort)
  }

  suspend fun getHistoryPage(
    pageSize: Int = HistoryPageSize,
    viewAt: Long = 0L,
    max: Long = 0L,
  ): HistoryFeedPage {
    return userFeedRepository.getHistoryPage(
      pageSize = pageSize,
      viewAt = viewAt,
      max = max,
    )
  }

  suspend fun getFavoriteFolders(mid: Long): List<FavoriteFolder> {
    return userFeedRepository.getFavoriteFolders(mid)
  }

  suspend fun currentMid(): Long {
    return sessionStore.session.first().mid ?: 0L
  }

  suspend fun getFavoriteFolderVideos(
    mediaId: Long,
    page: Int,
    pageSize: Int = FavoriteFolderPageSize,
    order: String = "mtime",
  ): FavoriteFolderPage {
    return userFeedRepository.getFavoriteFolderVideos(
      mediaId = mediaId,
      page = page,
      pageSize = pageSize,
      order = order,
    )
  }

  suspend fun getFollowingSeasons(
    page: Int,
    pageSize: Int = FollowingSeasonPageSize,
    type: Int = 1,
    status: Int = 0,
  ): FollowingSeasonPage {
    val mid = sessionStore.session.first().mid ?: 0L
    return userFeedRepository.getFollowingSeasons(
      mid = mid,
      page = page,
      pageSize = pageSize,
      type = type,
      status = status,
    )
  }

  private companion object {
    const val SearchOrderTotalRank = "totalrank"
    const val HistoryPageSize = 30
    const val SpaceOrderPubdate = "pubdate"
    const val FollowAction = 1
    const val UnfollowAction = 2
    const val FollowAttribute = 2
    const val MutualFollowAttribute = 6
    const val FavoriteFolderPageSize = 20
    const val FollowingSeasonPageSize = 30
    const val FollowingUsersPageSize = 50
  }
}

/** B 站关注列表中的单个用户(镜像 FollowingSeason 放网络包层)。 */
data class FollowingUser(
  val mid: Long,
  val uname: String,
  val face: String,
  val sign: String,
)

data class FollowingUserPage(
  val users: List<FollowingUser>,
  val total: Int,
  val hasMore: Boolean,
)
