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
        val feedTid = section.feedRcmdTid
        if (regionTidOverride == null && feedTid != null) {
          // 主分区走 BV 的 feed/rcmd（新父 tid），重载出新鲜推荐流；失败/未登录回退 dynamic/region 旧 tid。
          getRegionFeedRcmdVideos(feedTid, page, fallbackTid = tid)
        } else {
          // 子分区（旧子 tid，各子分区返回不同内容）或无 feedRcmdTid 的主分区（番剧/生活）走 dynamic/region。
          getRegionVideos(tid, page)
        }
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

  private suspend fun getRegionFeedRcmdVideos(
    feedRegionTid: Int,
    page: Int,
    fallbackTid: Int,
  ): List<VideoSummary> {
    val sessData = sessionStore.sessData.first()
    // 未登录 feed/rcmd 会 -400，回退 dynamic/region 保底。
    if (sessData.isNullOrBlank()) return getRegionVideos(fallbackTid, page)
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
      val videos = archives
        .mapNotNull { it.asObjectOrNull() }
        .filter { it.string("bvid").isNotBlank() }
        .map(VideoSummaryMappers::fromArchive)
      // feed/rcmd 返回空（冷门分区或异常）也回退，避免主分区空白。
      if (videos.isEmpty()) getRegionVideos(fallbackTid, page) else videos
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      // feed/rcmd 失败（鉴权波动/接口异常）回退 dynamic/region，不阻断主分区浏览。
      getRegionVideos(fallbackTid, page)
    }
  }
}
