package com.kirin.mt.core.network

import com.kirin.mt.core.model.Comment
import com.kirin.mt.core.model.VideoSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object VideoSummaryMappers {

  /** 用于二次解析动态 live_rcmd.content 这类内嵌 JSON 字符串字段。宽松配置,失败由调用处兜底。 */
  private val nestedJson = Json { ignoreUnknownKeys = true; isLenient = true }
  fun fromArchive(json: JsonObject): VideoSummary {
    // dynamic/region 鐢?owner/pic锛況egion/feed/rcmd 鐢?author/cover锛屼簩鑰呭瓧娈靛悕涓嶅悓锛岀粺涓€鍏滃簳銆?
    val owner = json.obj("owner") ?: json.obj("author")
    val stat = json.obj("stat")
    return VideoSummary(
      bvid = json.string("bvid"),
      title = json.string("title"),
      pic = fixPicUrl(json.string("pic").ifBlank { json.string("cover") }),
      ownerName = owner?.string("name").orEmpty(),
      ownerFace = fixPicUrl(owner?.string("face").orEmpty()),
      ownerMid = owner?.long("mid") ?: 0L,
      view = BiliNumberParser.toInt(stat?.get("view")),
      danmaku = BiliNumberParser.toInt(stat?.get("danmaku")),
      duration = BiliNumberParser.parseDuration(json["duration"]),
      pubdate = json.long("pubdate"),
      badge = filterBadge(json.string("badge")),
    )
  }

  fun fromDynamicItem(json: JsonObject): VideoSummary? {
    if (json["visible"]?.jsonPrimitive?.booleanOrNull == false) {
      return null
    }

    val modules = json.obj("modules") ?: return null
    val dynamicModule = modules.obj("module_dynamic") ?: return null
    val major = dynamicModule.obj("major") ?: return null
    val author = modules.obj("module_author")

    // 按 major 类型分流:普通视频动态走 archive;直播推荐卡走 live_rcmd(原生带 live_status);
    // 其余类型(图文/PGC/专栏等)暂不收入。用 live_rcmd 字段存在性判定,避免依赖 type 字符串。
    return when {
      major.string("type") == "MAJOR_TYPE_ARCHIVE" -> fromArchiveDynamic(json, modules, major, author)
      major.obj("live_rcmd") != null -> fromLiveRcmdDynamic(json, major, author)
      else -> null
    }
  }

  private fun fromArchiveDynamic(
    json: JsonObject,
    modules: JsonObject,
    major: JsonObject,
    author: JsonObject?,
  ): VideoSummary? {
    val archive = major.obj("archive") ?: return null
    val stat = archive.obj("stat")
    // module_stat 是动态本身的社交计数(点赞/评论/转发),区别于 archive.stat 的播放/弹幕。
    val dynStat = modules.obj("module_stat")
    return VideoSummary(
      bvid = archive.string("bvid"),
      title = archive.string("title"),
      pic = fixPicUrl(archive.string("cover")),
      ownerName = author?.string("name").orEmpty(),
      ownerFace = fixPicUrl(author?.string("face").orEmpty()),
      ownerMid = author?.long("mid") ?: 0L,
      view = BiliNumberParser.toInt(stat?.get("play") ?: stat?.get("view")),
      danmaku = BiliNumberParser.toInt(stat?.get("danmaku")),
      duration = BiliNumberParser.parseDuration(archive["duration_text"]),
      pubdate = author?.long("pub_ts") ?: 0L,
      badge = filterBadge(archive.obj("badge")?.string("text").orEmpty()),
      dynId = json.string("id_str"),
      aid = archive.long("aid"),
      likeCount = dynStat?.obj("like")?.int("count") ?: 0,
      commentCount = dynStat?.obj("comment")?.int("count") ?: 0,
      forwardCount = dynStat?.obj("forward")?.int("count") ?: 0,
    )
  }

  /**
   * 动态直播推荐卡(MAJOR_TYPE_LIVE / live_rcmd):live_rcmd.content 是内嵌 JSON 字符串,
   * 二次解析出 live_play_info。仅 live_status==1(正直播)收入为 isLive 卡片,可点进直播间;
   * 解析失败或非直播静默 drop(null),不影响其余动态。
   * 字段依据:BV DynamicResponse.kt(live_rcmd.content)、Dynamic.kt(live_play_info: live_status/room_id/online/area_name)。
   */
  private fun fromLiveRcmdDynamic(
    json: JsonObject,
    major: JsonObject,
    author: JsonObject?,
  ): VideoSummary? {
    val contentStr = major.obj("live_rcmd")?.string("content").orEmpty()
    if (contentStr.isBlank()) return null
    val playInfo = runCatching {
      nestedJson.parseToJsonElement(contentStr).jsonObject.obj("live_play_info")
    }.getOrNull() ?: return null
    if (playInfo.int("live_status") != 1) return null
    return VideoSummary(
      bvid = "",
      title = playInfo.string("title"),
      pic = fixPicUrl(playInfo.string("cover")),
      ownerName = author?.string("name").orEmpty(),
      ownerFace = fixPicUrl(author?.string("face").orEmpty()),
      ownerMid = author?.long("mid") ?: 0L,
      view = playInfo.int("online"),
      danmaku = 0,
      duration = 0,
      pubdate = author?.long("pub_ts") ?: 0L,
      badge = "直播",
      isLive = true,
      liveRoomId = playInfo.long("room_id"),
      liveAreaName = playInfo.string("area_name"),
      dynId = json.string("id_str"),
    )
  }

  fun fromHistory(json: JsonObject): VideoSummary {
    val history = json.obj("history")
    val cover = json.string("cover").ifBlank { json.string("pic") }
    val badge = json.string("badge")
    val business = history?.string("business").orEmpty()
    val isLive = json.int("live_status") == 1 ||
      business == "live" ||
      badge.contains("\u76f4\u64ad") ||
      badge == "\u672a\u5f00\u64ad"

    return VideoSummary(
      bvid = history?.string("bvid").orEmpty(),
      title = json.string("title"),
      pic = fixPicUrl(cover),
      ownerName = json.string("author_name"),
      ownerFace = fixPicUrl(json.string("author_face")),
      ownerMid = json.long("author_mid"),
      view = BiliNumberParser.toInt(json.obj("stat")?.get("view")),
      danmaku = BiliNumberParser.toInt(json.obj("stat")?.get("danmaku")),
      duration = BiliNumberParser.parseDuration(json["duration"]),
      pubdate = json.long("pubdate"),
      badge = filterBadge(badge),
      progress = json.int("progress"),
      viewAt = json.long("view_at"),
      cid = history?.long("cid")?.takeIf { it != 0L } ?: (history?.long("oid") ?: 0L),
      historyPage = history?.int("page") ?: 0,
      historyPart = history?.string("part").orEmpty(),
      historyVideos = json.int("videos"),
      isLive = isLive,
    )
  }

  // 稍后再看列表项:/x/v2/history/toview 的 data.list[]。仅 UGC 条目(带 bvid/aid/owner)。
  // progress 为秒数(与历史一致,未观看为 -1),toPlaybackRequest() 自动续播。
  fun fromToViewItem(json: JsonObject): VideoSummary {
    val owner = json.obj("owner")
    return VideoSummary(
      bvid = json.string("bvid"),
      title = json.string("title"),
      pic = fixPicUrl(json.string("pic")),
      ownerName = owner?.string("name").orEmpty(),
      ownerFace = "",
      ownerMid = 0L,
      view = 0,
      danmaku = 0,
      duration = BiliNumberParser.parseDuration(json["duration"]),
      pubdate = 0L,
      badge = "",
      progress = json.int("progress"),
      cid = json.long("cid"),
      historyVideos = json.int("videos"),
      aid = json.long("aid"),
    )
  }

  fun fromSearch(json: JsonObject): VideoSummary {
    return VideoSummary(
      bvid = json.string("bvid"),
      title = stripHtmlTags(json.string("title")),
      pic = fixPicUrl(json.string("pic")),
      ownerName = json.string("author"),
      ownerFace = fixPicUrl(json.searchOwnerFace()),
      ownerMid = json.long("mid"),
      view = BiliNumberParser.toInt(json["play"]),
      danmaku = BiliNumberParser.toInt(json["danmaku"]),
      duration = BiliNumberParser.parseDuration(json["duration"]),
      pubdate = json.long("pubdate"),
      badge = filterBadge(json.string("badge")),
    )
  }

  fun fromSpace(json: JsonObject): VideoSummary {
    return VideoSummary(
      bvid = json.string("bvid"),
      title = json.string("title"),
      pic = fixPicUrl(json.string("pic")),
      ownerName = json.string("author"),
      ownerFace = "",
      ownerMid = json.long("mid"),
      view = BiliNumberParser.toInt(json["play"]),
      danmaku = BiliNumberParser.toInt(json["video_review"]),
      duration = BiliNumberParser.parseDuration(json["length"]),
      pubdate = json.long("created"),
      badge = filterBadge(json.string("badge")),
    )
  }

  fun fromFavoriteItem(json: JsonObject): VideoSummary {
    val upper = json.obj("upper")
    val cntInfo = json.obj("cnt_info")
    return VideoSummary(
      bvid = json.string("bvid"),
      title = json.string("title"),
      pic = fixPicUrl(json.string("cover")),
      ownerName = upper?.string("name").orEmpty(),
      ownerFace = fixPicUrl(upper?.string("face").orEmpty()),
      ownerMid = upper?.long("mid") ?: 0L,
      view = BiliNumberParser.toInt(cntInfo?.get("play")),
      danmaku = BiliNumberParser.toInt(cntInfo?.get("danmaku")),
      duration = BiliNumberParser.parseDuration(json["duration"]),
     pubdate = json.long("pubtime"),
     badge = filterBadge(json.string("badge")),
   )
 }

  fun fromFollowingSeason(json: JsonObject): FollowingSeason {
    val newEp = json.obj("new_ep")
    val firstEp = json.obj("first_ep_info")
    return FollowingSeason(
      seasonId = json.int("season_id"),
      title = json.string("title"),
      cover = fixPicUrl(json.string("cover")),
      badge = json.string("badge"),
      progress = json.string("progress"),
      newEpDesc = newEp?.string("index_show").orEmpty(),
      seasonTypeName = json.string("season_type_name"),
      firstEpId = firstEp?.int("id") ?: 0,
    )
  }

  fun fromComment(json: JsonObject): Comment {
    val member = json.obj("member")
    val content = json.obj("content")
    return Comment(
      id = json.long("rpid"),
      uname = member?.string("uname").orEmpty(),
      avatar = fixPicUrl(member?.string("avatar_url").orEmpty()),
      mid = member?.long("mid") ?: 0L,
      content = content?.string("message").orEmpty(),
      likeCount = json.int("like"),
      replyCount = json.int("reply_count"),
      ctime = json.long("ctime"),
    )
  }

  private fun JsonObject.searchOwnerFace(): String {
    return string("upic")
      .ifBlank { string("face") }
      .ifBlank { string("avatar") }
      .ifBlank { obj("owner")?.string("face").orEmpty() }
  }

  internal fun fixPicUrl(url: String): String {
    return when {
      url.startsWith("//") -> "https:$url"
      url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
      else -> url
    }
  }

  private fun stripHtmlTags(text: String): String {
    return text.replace(HtmlTagRegex, "")
  }

  private fun filterBadge(badge: String): String {
    return if (badge == "\u6295\u7a3f\u89c6\u9891" || badge == "\u6295\u7a3f") "" else badge
  }

  private val HtmlTagRegex = Regex("<[^>]*>")
}
