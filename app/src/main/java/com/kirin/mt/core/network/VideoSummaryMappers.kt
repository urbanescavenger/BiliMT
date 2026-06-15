package com.kirin.mt.core.network

import com.kirin.mt.core.model.VideoSummary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object VideoSummaryMappers {
  fun fromArchive(json: JsonObject): VideoSummary {
    val owner = json.obj("owner")
    val stat = json.obj("stat")
    return VideoSummary(
      bvid = json.string("bvid"),
      title = json.string("title"),
      pic = fixPicUrl(json.string("pic")),
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
    if (major.string("type") != "MAJOR_TYPE_ARCHIVE") {
      return null
    }

    val archive = major.obj("archive") ?: return null
    val author = modules.obj("module_author")
    val stat = archive.obj("stat")
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

  private fun JsonObject.searchOwnerFace(): String {
    return string("upic")
      .ifBlank { string("face") }
      .ifBlank { string("avatar") }
      .ifBlank { obj("owner")?.string("face").orEmpty() }
  }

  private fun fixPicUrl(url: String): String {
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
