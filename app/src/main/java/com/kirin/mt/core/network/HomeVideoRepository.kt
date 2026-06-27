package com.kirin.mt.core.network

import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.SessionStore
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
    regionTidOverride: Int? = null,
  ): List<VideoSummary> {
    return when (section) {
      HomeSection.Recommend -> getRecommendVideos(idx)
      HomeSection.Popular -> getPopularVideos(page)
      else -> {
        val tid = regionTidOverride ?: section.regionTid ?: return emptyList()
        // 子分区 tid 用 BV 的 region/feed/rcmd 接口（dynamic/region 对子分区过滤不可靠，
        // 实测 rid=25 标“MMD·3D”却返回游戏区内容）。主分区仍走 dynamic/region。
        if (regionTidOverride != null) getRegionFeedRcmdVideos(tid, page)
        else getRegionVideos(tid, page)
      }
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

  private suspend fun getRegionVideos(tid: Int, page: Int): List<VideoSummary> {
    val root = apiClient.getJson(
      url = BiliApiEndpoints.Region,
      params = mapOf(
        "rid" to tid.toString(),
        "pn" to page.toString(),
        "ps" to "20",
      ),
    ).rootObject()
    root.requireBiliCodeOk("region")

    val archives = root.obj("data")?.get("archives") as? JsonArray ?: return emptyList()
    return archives
      .mapNotNull { it.asObjectOrNull() }
      .filter { it.string("bvid").isNotBlank() }
      .map(VideoSummaryMappers::fromArchive)
  }

  private suspend fun getRegionFeedRcmdVideos(tid: Int, page: Int): List<VideoSummary> {
    val sessData = sessionStore.sessData.first()
    // 未登录 feed/rcmd 会 -400，回退 dynamic/region 保证子分区仍出内容。
    if (sessData.isBlank()) return getRegionVideos(tid, page)
    return try {
      val root = apiClient.getJson(
        url = BiliApiEndpoints.RegionFeedRcmd,
        params = mapOf(
          "display_id" to page.toString(),
          "request_cnt" to "20",
          "from_region" to tid.toString(),
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
      // feed/rcmd 失败（鉴权波动/接口异常）回退 dynamic/region，不阻断子分区浏览。
      getRegionVideos(tid, page)
    }
  }
}
