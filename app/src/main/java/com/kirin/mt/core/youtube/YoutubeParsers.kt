package com.kirin.mt.core.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * InnerTube JSON → [YoutubeVideo] 解析器。独立实现，仅复用响应"形状"。
 *
 * 兼容三种响应容器（文档顺序一致）：
 *  - 搜索：contents → twoColumnSearchResultsRenderer → primaryContents → sectionListRenderer
 *    → itemSectionRenderer → videoRenderer
 *  - 热门/频道：contents → twoColumnBrowseResultsRenderer → tabs → richGridRenderer /
 *    sectionListRenderer → richItemRenderer → videoRenderer
 *  - 续页：onResponseReceivedActions → appendContinuationItemsAction → continuationItems →
 *    richItemRenderer → videoRenderer
 *
 * 采用"递归收集所有 videoRenderer 子对象"的方式统一处理，天然兼容以上所有形态；
 * 续页 token 从最后一个 continuationItemRenderer 里取。
 */
internal object YoutubeParsers {

  /**
   * 解析一页响应，返回视频列表 + 续页 token。
   */
  fun parseFeedPage(root: JsonObject): YoutubeFeedPage {
    val videos = mutableListOf<YoutubeVideo>()
    // 不同端点/客户端返回不同 renderer,字段结构一致:
    //  WEB 搜索 videoRenderer / topic 热门 gridVideoRenderer / ANDROID 搜索 compactVideoRenderer
    collectByKey(root, KEY_VIDEO_RENDERER) { node ->
      parseVideoRenderer(node)?.let { videos.add(it) }
    }
    collectByKey(root, KEY_GRID_VIDEO_RENDERER) { node ->
      parseVideoRenderer(node)?.let { videos.add(it) }
    }
    collectByKey(root, KEY_COMPACT_VIDEO_RENDERER) { node ->
      parseVideoRenderer(node)?.let { videos.add(it) }
    }
    // 频道页新格式 lockupViewModel(richItemRenderer → content)
    collectByKey(root, KEY_LOCKUP_VIEW_MODEL) { node ->
      parseLockupViewModel(node)?.let { videos.add(it) }
    }
    val continuation = findContinuation(root)
    return YoutubeFeedPage(items = videos, continuation = continuation)
  }

  /**
   * 新格式 lockupViewModel(频道页)。videoId/title 嵌在嵌套结构里,解析不稳定;
   * 拿不到就跳过(动态 tab 空频道会回退热门,不依赖此解析)。防御式,不抛错。
   */
  private fun parseLockupViewModel(node: JsonObject): YoutubeVideo? {
    val videoId = node.obj("contentId")?.stringOrNull("videoId")
      ?: node.obj("contentMetadataViewModel")?.obj("contentId")?.stringOrNull("videoId")
    if (videoId.isNullOrBlank()) return null
    val title = node.obj("contentMetadataViewModel")
      ?.obj("title")
      ?.stringOrNull("content")
      .orEmpty()
    val thumbnailUrl = node.obj("contentImage")
      ?.obj("image")?.array("sources")
      ?.firstNotNullOfOrNull { (it as? JsonObject)?.stringOrNull("url") }
      ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
    return YoutubeVideo(
      videoId = videoId,
      title = title,
      channelName = "",
      channelId = "",
      thumbnailUrl = thumbnailUrl,
      viewCount = null,
      durationSec = null,
      publishedAt = null,
      liveNow = false,
      isUpcoming = false,
      badge = "",
    )
  }

  // ---- 单个 videoRenderer 解析 ----

  private fun parseVideoRenderer(node: JsonObject): YoutubeVideo? {
    val videoId = node.stringOrNull("videoId") ?: return null

    val title = runsText(node.obj("title")).ifBlank { simpleText(node.obj("title")) }

    // ownerText 或 longBylineText 提供频道名 + id
    val owner = node.obj("ownerText") ?: node.obj("longBylineText")
    val ownerRuns = owner?.array("runs")
    val channelName = ownerRuns
      ?.firstOrNull { it is JsonObject }
      ?.let { (it as JsonObject).stringOrNull("text") }
      .orEmpty()
    val channelId = ownerRuns
      ?.firstOrNull { it is JsonObject }
      ?.let { (it as JsonObject).obj("navigationEndpoint")?.obj("browseEndpoint")?.stringOrNull("browseId") }
      .orEmpty()

    val thumbnailUrl = node.obj("thumbnail")
      ?.array("thumbnails")
      ?.mapNotNull { (it as? JsonObject)?.stringOrNull("url") }
      ?.lastOrNull()
      ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    // 时长:videoRenderer 在 lengthText;gridVideoRenderer 在 thumbnailOverlayTimeStatusRenderer。
    val lengthText = node.obj("lengthText")?.stringOrNull("simpleText")
      ?: node.obj("thumbnailOverlayTimeStatusRenderer")?.obj("text")?.stringOrNull("simpleText")
      ?: ""
    val explicitLive = node.booleanOrNull("isLiveNow") ?: false
    val watchingText = node.obj("viewCountText")?.stringOrNull("simpleText")
      ?.contains("watching", ignoreCase = true) == true
    val liveNow = explicitLive || lengthText.equals("LIVE", ignoreCase = true) || watchingText

    val isUpcoming = node.obj("upcomingEventData") != null ||
      lengthText.startsWith("Premiere", ignoreCase = true)

    val badge = node.array("badges")
      ?.firstNotNullOfOrNull { (it as? JsonObject)?.obj("metadataBadgeRenderer")?.stringOrNull("label") }
      .orEmpty()

    val viewCount = parseCount(
      node.obj("viewCountText")?.stringOrNull("simpleText"),
    )

    val publishedAt = parsePublished(
      node.obj("publishedTimeText")?.stringOrNull("simpleText"),
      liveNow,
      isUpcoming,
    )

    return YoutubeVideo(
      videoId = videoId,
      title = title,
      channelName = channelName,
      channelId = channelId,
      thumbnailUrl = thumbnailUrl,
      viewCount = viewCount,
      durationSec = if (liveNow) null else parseDuration(lengthText),
      publishedAt = publishedAt,
      liveNow = liveNow,
      isUpcoming = isUpcoming,
      badge = badge,
    )
  }

  // ---- 文本辅助 ----

  private fun runsText(obj: JsonObject?): String {
    if (obj == null) return ""
    return obj.array("runs")
      ?.mapNotNull { (it as? JsonObject)?.stringOrNull("text") }
      ?.joinToString("")
      .orEmpty()
  }

  private fun simpleText(obj: JsonObject?): String {
    return obj?.stringOrNull("simpleText").orEmpty()
  }

  private fun parseDuration(text: String): Int? {
    if (text.isBlank()) return null
    val parts = text.split(':').mapNotNull { it.toIntOrNull() }
    if (parts.isEmpty()) return null
    return when (parts.size) {
      1 -> parts[0]
      2 -> parts[0] * 60 + parts[1]
      3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
      else -> null
    }
  }

  /** 解析 "1,234,567 views" / "1.2M views" / "No views" / "1.4K watching"。 */
  private fun parseCount(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    if (text.startsWith("No views", ignoreCase = true)) return 0L
    val cleaned = text
      .replace(",", "")
      .replace(" views", "", ignoreCase = true)
      .replace(" watching", "", ignoreCase = true)
      .trim()
    val multiplier = when {
      cleaned.endsWith("M", ignoreCase = true) -> 1_000_000L
      cleaned.endsWith("K", ignoreCase = true) -> 1_000L
      cleaned.endsWith("B", ignoreCase = true) -> 1_000_000_000L
      else -> 1L
    }
    val numberPart = cleaned
      .substringBefore(' ')
      .trimEnd('M', 'm', 'K', 'k', 'B', 'b')
    val number = numberPart.toDoubleOrNull() ?: return null
    return (number * multiplier).toLong()
  }

  /**
   * 把相对时间文案转成 epoch 秒。InnerTube 返回 "3 hours ago"/"1 day ago"/"5 weeks ago" 等，
   * 无法精确定位，按当前时间反推近似值。live/upcoming 返回 null。
   */
  private fun parsePublished(text: String?, liveNow: Boolean, isUpcoming: Boolean): Long? {
    if (text.isNullOrBlank() || liveNow || isUpcoming) return null
    val nowSec = System.currentTimeMillis() / 1000L
    val lower = text.lowercase()
    val (amount, unit) = parseRelative(lower) ?: return null
    val seconds = when {
      unit.contains("second") -> amount
      unit.contains("minute") -> amount * 60L
      unit.contains("hour") -> amount * 3600L
      unit.contains("day") -> amount * 86400L
      unit.contains("week") -> amount * 604800L
      unit.contains("month") -> amount * 2592000L
      unit.contains("year") -> amount * 31536000L
      else -> return null
    }
    return nowSec - seconds
  }

  private fun parseRelative(lower: String): Pair<Long, String>? {
    val match = Regex("""(\d+)\s+([a-z]+)""").find(lower) ?: return null
    val amount = match.groupValues[1].toLongOrNull() ?: return null
    return amount to match.groupValues[2]
  }

  // ---- 递归收集 videoRenderer / continuation ----

  private fun collectByKey(element: JsonElement, key: String, visit: (JsonObject) -> Unit) {
    when (element) {
      is JsonObject -> {
        (element[key] as? JsonObject)?.let(visit)
        for ((_, value) in element) {
          collectByKey(value, key, visit)
        }
      }
      is JsonArray -> {
        for (item in element) {
          collectByKey(item, key, visit)
        }
      }
      else -> Unit
    }
  }

  /** 找最后一个 continuationItemRenderer 里的 token。 */
  private fun findContinuation(root: JsonObject): String? {
    var token: String? = null
    collectByKey(root, KEY_CONTINUATION_ITEM_RENDERER) { node ->
      val candidate = node.obj("continuationEndpoint")
        ?.obj("continuationCommand")
        ?.stringOrNull("token")
      if (!candidate.isNullOrBlank()) token = candidate
    }
    return token
  }

  // ---- JsonObject 辅助 ----

  private fun JsonObject.stringOrNull(name: String): String? {
    return this[name]?.jsonPrimitive?.contentOrNull
  }

  private fun JsonObject.booleanOrNull(name: String): Boolean? {
    return this[name]?.jsonPrimitive?.let { it.content.toBooleanStrictOrNull() }
  }

  private fun JsonObject.obj(name: String): JsonObject? {
    return this[name] as? JsonObject
  }

  private fun JsonObject.array(name: String): JsonArray? {
    return this[name] as? JsonArray
  }

  private const val KEY_VIDEO_RENDERER = "videoRenderer"
  private const val KEY_GRID_VIDEO_RENDERER = "gridVideoRenderer"
  private const val KEY_COMPACT_VIDEO_RENDERER = "compactVideoRenderer"
  private const val KEY_LOCKUP_VIEW_MODEL = "lockupViewModel"
  private const val KEY_CONTINUATION_ITEM_RENDERER = "continuationItemRenderer"
}
