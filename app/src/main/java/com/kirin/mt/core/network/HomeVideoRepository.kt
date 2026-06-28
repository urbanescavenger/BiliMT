package com.kirin.mt.core.network

import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.model.UgcBannerItem
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.util.BvId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray

internal class HomeVideoRepository(
  private val apiClient: BiliApiClient,
  private val wbiKeyRepository: WbiKeyRepository,
  private val wbiSigner: WbiSigner,
  private val sessionStore: SessionStore,
) {
  suspend fun getHomeSectionVideos(
    section: HomeSection,
    page: Int = 1,
    idx: Int = 0,
  ): List<VideoSummary> {
    return when (section) {
      HomeSection.Recommend -> getRecommendVideos(idx)
      HomeSection.Popular -> getPopularVideos(page)
      else -> section.feedRcmdTid?.let { getRegionFeedRcmdVideos(it, page) } ?: emptyList()
    }
  }

  suspend fun getRegionBanner(tid: Int): List<UgcBannerItem> {
    val root = apiClient.getJson(
      url = BiliApiEndpoints.RegionBanner,
      params = mapOf("region_id" to tid.toString()),
    ).rootObject()
    root.requireBiliCodeOk("region banner")

    val list = root.obj("data")?.get("region_banner_list") as? JsonArray ?: return emptyList()
    return list.mapNotNull { it.asObjectOrNull() }
      .mapNotNull { item ->
        val bvid = BvId.bvidFromUrl(item.string("url")) ?: return@mapNotNull null
        UgcBannerItem(
          bvid = bvid,
          title = item.string("title"),
          cover = item.string("image"),
        )
      }
  }

  suspend fun getRecommendVideos(idx: Int = 0): List<VideoSummary> {
    val sessData = sessionStore.sessData.first()
    val keys = wbiKeyRepository.ensureKeys(sessData)

    val params = mutableMapOf(
      "fresh_idx" to idx.toString(),
      "fresh_type" to "4",
      "ps" to "20",
    )

    val signedParams = if (keys != null) {
      wbiSigner.sign(params, keys.imgKey, keys.subKey)
    } else {
      params
    }

    val root = apiClient.getJson(
      url = BiliApiEndpoints.Recommend,
      params = signedParams,
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("recommend")

    val item = root.obj("data")?.get("item") as? JsonArray ?: return emptyList()
    return item
      .mapNotNull { it.asObjectOrNull() }
      .filter { it.string("bvid").isNotBlank() }
      .map(VideoSummaryMappers::fromArchive)
  }

  suspend fun getRelatedVideos(bvid: String): List<VideoSummary> {
    if (bvid.isBlank()) return emptyList()

    val sessData = sessionStore.sessData.first()
    val root = apiClient.getJson(
      url = BiliApiEndpoints.ArchiveRelated,
      params = mapOf("bvid" to bvid),
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("archive related")

    val list = root["data"] as? JsonArray ?: return emptyList()
    return list
      .mapNotNull { it.asObjectOrNull() }
      .filter { it.string("bvid").isNotBlank() }
      .map(VideoSummaryMappers::fromArchive)
  }

  private suspend fun getPopularVideos(page: Int): List<VideoSummary> {
    val root = apiClient.getJson(
      url = BiliApiEndpoints.Popular,
      params = mapOf(
        "pn" to page.toString(),
        "ps" to "20",
      ),
    ).rootObject()
    root.requireBiliCodeOk("popular")

    val list = root.obj("data")?.get("list") as? JsonArray ?: return emptyList()
    return list
      .mapNotNull { it.asObjectOrNull() }
      .filter { it.string("bvid").isNotBlank() }
      .map(VideoSummaryMappers::fromArchive)
  }

  private suspend fun getRegionFeedRcmdVideos(
    feedRegionTid: Int,
    page: Int,
  ): List<VideoSummary> {
    val sessData = sessionStore.sessData.first()
    // 未登录 feed/rcmd 会 -400；BV UGC 仅走此接口，未登录返回空（app 登录门控）。
    if (sessData.isNullOrBlank()) return emptyList()
    return try {
      val root = apiClient.getJson(
        url = BiliApiEndpoints.RegionFeedRcmd,
        params = mapOf(
          "display_id" to page.toString(),
          "request_cnt" to "20",
          "from_region" to feedRegionTid.toString(),
          "device" to "web",
          "plat" to "30",
        ),
        sessData = sessData,
      ).rootObject()
      root.requireBiliCodeOk("region feed rcmd")

      val archives = root.obj("data")?.get("archives") as? JsonArray ?: emptyList()
      archives
        .mapNotNull { it.asObjectOrNull() }
        .filter { it.string("bvid").isNotBlank() }
        .map(VideoSummaryMappers::fromArchive)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      emptyList()
    }
  }
}
