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
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.flow.first

class VideoRepository(
  private val apiClient: BiliApiClient,
  private val wbiKeyRepository: WbiKeyRepository,
  private val wbiSigner: WbiSigner,
  private val sessionStore: SessionStore,
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
    return homeVideoRepository.getHomeSectionVideos(
      section = section,
      page = page,
      idx = idx,
    )
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

  suspend fun getDynamicFeed(offset: String = ""): DynamicFeedPage {
    return userFeedRepository.getDynamicFeed(offset)
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

  private companion object {
    const val SearchOrderTotalRank = "totalrank"
    const val HistoryPageSize = 30
    const val SpaceOrderPubdate = "pubdate"
    const val FollowAction = 1
    const val UnfollowAction = 2
    const val FollowAttribute = 2
    const val MutualFollowAttribute = 6
  }
}
