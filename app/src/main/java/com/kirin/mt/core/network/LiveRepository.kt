package com.kirin.mt.core.network

import com.kirin.mt.core.model.LiveListPage
import com.kirin.mt.core.model.LiveRoom
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 直播数据仓库:推荐直播列表。镜像 [HomeVideoRepository] 的结构
 * (apiClient + requireBiliCodeOk + JsonExt 手动映射)。
 *
 * 端点 [BiliApiEndpoints.LiveList](`xlive/web-interface/v1/second/getList`),
 * `parent_area_id=0` 跨全分区,按 `page` 分页,`sort_type=online` 按人气排序。
 * 异常不在此吞,向上抛给 UI 显示失败/重试。
 */
class LiveRepository(
  private val apiClient: BiliApiClient,
  private val sessionStore: SessionStore,
) {
  suspend fun getLiveList(page: Int = 1): LiveListPage {
    val session = sessionStore.session.first()
    // 直播接口靠 buvid cookie + live Referer 过风控,缺则 -352。
    val (buvid3, buvid4) = SpaceHttpSupport.ensureBuvidCookies(sessionStore, apiClient)
    val headers = SpaceHttpSupport.liveHeaders(
      roomId = null,
      sessData = session.sessData,
      biliJct = session.biliJct,
      dedeUserId = session.mid,
      buvid3 = buvid3,
      buvid4 = buvid4,
    )
    val root = apiClient.getJsonWithHeaders(
      url = BiliApiEndpoints.LiveList,
      params = mapOf(
        "platform" to "web",
        "parent_area_id" to "0",
        "area_id" to "0",
        "page" to page.toString(),
        "sort_type" to "online",
      ),
      headers = headers,
    ).rootObject()
    root.requireBiliCodeOk("live list")

    val data = root.obj("data")
    val list = data?.get("list") as? JsonArray ?: emptyList()
    val items = list
      .mapNotNull { it.asObjectOrNull() }
      .map(::fromLiveRoom)
      .filter { it.roomId > 0L }
    val hasMore = (data?.int("has_more") ?: 0) == 1
    return LiveListPage(
      items = items,
      nextPage = page + 1,
      hasMore = hasMore,
    )
  }

  private fun fromLiveRoom(json: JsonObject): LiveRoom {
    val cover = json.string("cover").ifBlank { json.string("keyframe") }
    val watched = json.obj("watched_show")
    val online = BiliNumberParser.toInt(watched?.get("num")).takeIf { it > 0 }
      ?: BiliNumberParser.toInt(json["online"])
    return LiveRoom(
      roomId = json.long("roomid"),
      uid = json.long("uid"),
      uname = json.string("uname"),
      title = json.string("title"),
      cover = VideoSummaryMappers.fixPicUrl(cover),
      face = VideoSummaryMappers.fixPicUrl(json.string("face")),
      online = online,
      areaName = json.string("area_name"),
      keyframe = VideoSummaryMappers.fixPicUrl(json.string("keyframe")),
      liveStatus = json.int("live_status"),
    )
  }
}