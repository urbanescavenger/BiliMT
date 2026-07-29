package com.kirin.mt.core.network

import com.kirin.mt.core.auth.WbiKeyRepository
import com.kirin.mt.core.auth.WbiSigner
import com.kirin.mt.core.model.LiveArea
import com.kirin.mt.core.model.LiveAreaGroup
import com.kirin.mt.core.model.LiveListPage
import com.kirin.mt.core.model.LiveRoom
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private const val LiveAreaPageSize = 20

/**
 * 直播数据仓库:推荐直播列表 + 直播分区列表 + 按分区拉直播间。
 * 镜像 [HomeVideoRepository] 的结构(apiClient + WBI 签名 + requireBiliCodeOk + JsonExt 手动映射)。
 *
 * 端点 [BiliApiEndpoints.LiveList](`xlive/web-interface/v1/index/getList`)是 WBI 端点
 * (首页聚合,无分页),需 WBI 签名(`w_rid`+`wts`)+ `web_location`,文档核实不需要 Cookie。
 * 之前误用 `second/getList`(非 WBI 端点)却给它 WBI 签名 → -352;换成 `index/getList` 即对。
 *
 * 风控:WBI 签名是过 -352 的钥匙(同推荐接口那套);buvid cookie + live Referer 叠加更稳。
 * 分区相关接口(`getWebAreaList`/`second/getList`)不是 WBI 端点,裸发+live headers即可。
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
    // index/getList:recommend_room_list 是精选高亮(~5),大头在 room_list 模块数组里
    // (每个模块含自己的 list 房间数组,字段同 recommend_room_list)。合并 + 按 roomid 去重。
    val rooms = mutableListOf<JsonObject>()
    (data?.get("recommend_room_list") as? JsonArray)?.forEach { room ->
      room.asObjectOrNull()?.let(rooms::add)
    }
    (data?.get("room_list") as? JsonArray)?.forEach { modEl ->
      val mod = modEl.asObjectOrNull() ?: return@forEach
      val inner = (mod.get("list") as? JsonArray)
        ?: (mod.get("room_list") as? JsonArray)
        ?: (mod.get("rooms") as? JsonArray)
      inner?.forEach { room -> room.asObjectOrNull()?.let(rooms::add) }
    }
    val seen = mutableSetOf<Long>()
    val items = rooms
      .map(::fromLiveRoom)
      .filter { it.roomId > 0L && seen.add(it.roomId) }
    // index/getList 是首页聚合,无分页 → 单批。
    return LiveListPage(
      items = items,
      nextPage = page + 1,
      hasMore = false,
    )
  }

  /**
   * 获取直播分区树。公开 GET,无需 WBI 签名,但需 live headers 过基础风控。
   */
  suspend fun getAreaList(): List<LiveAreaGroup> {
    val session = sessionStore.session.first()
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
      url = BiliApiEndpoints.LiveAreaList,
      params = mapOf("source_id" to "2"),
      headers = headers,
    ).rootObject()
    root.requireBiliCodeOk("live area list")

    // getWebAreaList 实测返回 {"code":0,"data":{"data":[...]}}:父分区数组在 data.data(data 是
    // 对象,内嵌 data 数组),不是顶层 data。旧文档曾写 data 直接为数组,双兼容防上游漂移。
    val dataElem = root["data"]
    val areaArray = (dataElem as? JsonArray)
      ?: (dataElem?.asObjectOrNull()?.get("data") as? JsonArray)
      ?: return emptyList()
    return areaArray
      .mapNotNull { it.asObjectOrNull() }
      .map { parent ->
        val parentId = parent.int("id")
        LiveAreaGroup(
          id = parentId,
          name = parent.string("name"),
          areas = (parent.get("list") as? JsonArray)
            ?.mapNotNull { it.asObjectOrNull() }
            ?.map { child ->
              LiveArea(
                id = child.int("id"),
                parentId = child.int("parent_id").takeIf { it > 0 } ?: parentId,
                name = child.string("name"),
                icon = VideoSummaryMappers.fixPicUrl(child.string("pic")),
              )
            }
            ?: emptyList(),
        )
      }
  }

  /**
   * 按分区拉直播间列表。改用旧版公开接口 /room/v1/Area/getRoomList，无需 WBI 签名，
   * 也不用 session cookies，只需基础 web headers。
   */
  suspend fun getLiveListByArea(
    parentAreaId: Int,
    areaId: Int,
    page: Int = 1,
  ): LiveListPage {
    // 父分区模式:areaId=0 + parentAreaId>0(拉该大类下所有房间,移动端 tab 用);
    // 叶子分区模式:areaId>0(可选带 parentAreaId)。两者都 0 无过滤,兜底空。
    if (parentAreaId <= 0 && areaId <= 0) {
      return LiveListPage(items = emptyList(), nextPage = page, hasMore = false)
    }
    val params = mutableMapOf(
      "area_id" to areaId.toString(),
      "page" to page.toString(),
      "page_size" to LiveAreaPageSize.toString(),
    )
    if (parentAreaId > 0) {
      params["parent_area_id"] = parentAreaId.toString()
    }
    val root = apiClient.getJsonWithHeaders(
      url = BiliApiEndpoints.LiveAreaRoomList,
      params = params,
      headers = SpaceHttpSupport.liveHeaders(
        roomId = null,
        sessData = null,
        biliJct = null,
        dedeUserId = null,
        buvid3 = null,
        buvid4 = null,
      ),
    ).rootObject()
    root.requireBiliCodeOk("live area rooms")

    val rooms = (root.get("data") as? JsonArray)
      ?.mapNotNull { it.asObjectOrNull() }
      ?: emptyList()
    val seen = mutableSetOf<Long>()
    val items = rooms
      .map(::fromLiveRoom)
      .filter { it.roomId > 0L && seen.add(it.roomId) }
    return LiveListPage(
      items = items,
      nextPage = page + 1,
      hasMore = rooms.size >= LiveAreaPageSize,
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
      areaId = json.int("area_v2_id").takeIf { it > 0 } ?: json.int("area_id"),
      parentAreaId = json.int("parent_area_v2_id").takeIf { it > 0 }
        ?: json.int("parent_area_id"),
    )
  }
}