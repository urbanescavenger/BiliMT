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
 * 端点 [BiliApiEndpoints.LiveList](`xlive/web-interface/v1/index/getList`)是 WBI 端点
 * (首页聚合,无分页),需 WBI 签名(`w_rid`+`wts`)+ `web_location`,文档核实不需要 Cookie。
 * 之前误用 `second/getList`(非 WBI 端点)却给它 WBI 签名 → -352;换成 `index/getList` 即对。
 *
 * 风控:WBI 签名是过 -352 的钥匙(同推荐接口那套);buvid cookie + live Referer 叠加更稳。
 */
class LiveRepository(
  private val apiClient: BiliApiClient,
  private val wbiKeyRepository: WbiKeyRepository,
  private val wbiSigner: WbiSigner,
  private val sessionStore: SessionStore,
) {
  suspend fun getLiveList(page: Int = 1): LiveListPage {
    val session = sessionStore.session.first()
    // buvid cookie + live Referer 过基础风控(WBI 端点文档说不需要 Cookie,但带上无害更稳)。
    val (buvid3, buvid4) = SpaceHttpSupport.ensureBuvidCookies(sessionStore, apiClient)
    val headers = SpaceHttpSupport.liveHeaders(
      roomId = null,
      sessData = session.sessData,
      biliJct = session.biliJct,
      dedeUserId = session.mid,
      buvid3 = buvid3,
      buvid4 = buvid4,
    )
    // WBI 签名(同推荐接口)+ web_location 过 -352。keys 为空(取键失败)则裸发。
    val params = mutableMapOf(
      "platform" to "web",
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
    // index/getList 返回 data.recommend_room_list(回退 data.list 兼容旧端点)。
    val list = (data?.get("recommend_room_list") as? JsonArray)
      ?: (data?.get("list") as? JsonArray)
      ?: emptyList()
    val items = list
      .mapNotNull { it.asObjectOrNull() }
      .map(::fromLiveRoom)
      .filter { it.roomId > 0L }
    // index/getList 是首页聚合,无分页 → 单批。
    return LiveListPage(
      items = items,
      nextPage = page + 1,
      hasMore = false,
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
      // index/getList 用 area_v2_name(回退 area_name 兼容 second/getList)。
      areaName = json.string("area_v2_name").ifBlank { json.string("area_name") },
      keyframe = VideoSummaryMappers.fixPicUrl(json.string("keyframe")),
      // recommend_room_list 都是正在直播,无 live_status 字段 → 缺省 1。
      liveStatus = json.int("live_status").takeIf { it > 0 } ?: 1,
    )
  }
}