package com.kirin.mt.core.network

import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.model.LiveListPage
import com.kirin.mt.core.model.LiveRoom
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 直播数据仓库:推荐直播列表。镜像 [HomeVideoRepository] 的结构
 * (apiClient + WBI 签名 + requireBiliCodeOk + JsonExt 手动映射)。
 *
 * 端点 [BiliApiEndpoints.LiveList](`xlive/web-interface/v1/second/getList`),
 * `parent_area_id=0` 跨全分区,按 `page` 分页,`sort_type=online` 按人气排序。
 *
 * 风控:直播接口要过 -352 必须 WBI 签名(`w_rid`+`wts`,和推荐接口同一套)+ `web_location`,
 * 再加 buvid cookie + live Referer。缺 WBI 签名只加 buvid 仍 -352。
 */
class LiveRepository(
  private val apiClient: BiliApiClient,
  private val wbiKeyRepository: WbiKeyRepository,
  private val wbiSigner: WbiSigner,
  private val sessionStore: SessionStore,
) {
  suspend fun getLiveList(page: Int = 1): LiveListPage {
    val session = sessionStore.session.first()
    // buvid cookie + live Referer 过基础风控。
    val (buvid3, buvid4) = SpaceHttpSupport.ensureBuvidCookies(sessionStore, apiClient)
    val headers = SpaceHttpSupport.liveHeaders(
      roomId = null,
      sessData = session.sessData,
      biliJct = session.biliJct,
      dedeUserId = session.mid,
      buvid3 = buvid3,
      buvid4 = buvid4,
    )
    // WBI 签名(同推荐接口)+ web_location 过 -352。keys 为空(未登录/取键失败)则裸发,退化为只靠 buvid。
    val params = mutableMapOf(
      "platform" to "web",
      "parent_area_id" to "0",
      "area_id" to "0",
      "page" to page.toString(),
      "sort_type" to "online",
      "web_location" to "444.7",
    )
    val keys = wbiKeyRepository.ensureKeys(session.sessData)
    val signedParams = if (keys != null) wbiSigner.sign(params, keys.imgKey, keys.subKey) else params
    val root = apiClient.getJsonWithHeaders(
      url = BiliApiEndpoints.LiveList,
      params = signedParams,
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