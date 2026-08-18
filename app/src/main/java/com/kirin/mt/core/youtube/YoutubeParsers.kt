package com.kirin.mt.core.youtube

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar

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
 * 续页 token 对齐 NewPipe YoutubeChannelTabExtractor：续页响应取
 * appendContinuationItemsAction.continuationItems 里的第一个；首屏定位视频网格
 * grid/richGrid 取第一个（不再全树扫+取最后一个，防止抓到 shorts/相关频道等其它 section 的 token，
 * 导致续页返回首屏相同视频而无法推进到更早视频）。
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

  /** 频道页 header 解析结果。 */
  data class ChannelInfo(val channelId: String, val name: String, val avatarUrl: String)

  /**
   * 从频道页 /browse 响应解析频道 info，返回 [ChannelInfo]（含头像）。
   *
   * YouTube 频道页 header 有三种形态，任一命中即用：
   *   1. header → c4TabbedHeaderRenderer → { channelId, title, avatar }
   *   2. metadata → channelMetadataRenderer → { externalId, title, avatar }
   *   3. microformat → microformatDataRenderer → { externalId, title }（无头像）
   *
   * @return 解析出的 [ChannelInfo]；解析不到时返回 null（由调用方回退输入串）。
   */
  fun parseChannelInfo(root: JsonObject): ChannelInfo? {
    val c4Header = root.obj("header")?.obj("c4TabbedHeaderRenderer")
    if (c4Header != null) {
      val id = c4Header.stringOrNull("channelId")
      val name = c4Header.stringOrNull("title")
      if (!id.isNullOrBlank()) {
        val avatar = c4Header.obj("avatar")?.array("thumbnails")?.let(::pickBestThumbnailUrl).orEmpty()
        return ChannelInfo(id, name ?: "", avatar)
      }
    }
    val channelMetadata = root.obj("metadata")?.obj("channelMetadataRenderer")
    if (channelMetadata != null) {
      val id = channelMetadata.stringOrNull("externalId")
      val name = channelMetadata.stringOrNull("title")
      if (!id.isNullOrBlank()) {
        val avatar = channelMetadata.obj("avatar")?.array("thumbnails")?.let(::pickBestThumbnailUrl).orEmpty()
        return ChannelInfo(id, name ?: "", avatar)
      }
    }
    val microformat = root.obj("microformat")?.obj("microformatDataRenderer")
    if (microformat != null) {
      val id = microformat.stringOrNull("externalId")
      val name = microformat.stringOrNull("title")
      if (!id.isNullOrBlank()) return ChannelInfo(id, name ?: "", "")
    }
    return null
  }

  /**
   * 从 /search 响应收集频道候选，返回 (channelId, name) 列表。
   *
   * 关键发现（实测）：`/browse` 只接受 `UC...` 频道 ID；`@handle` 做 browseId 会 400。
   * 所以 handle / 频道名必须走搜索，从 `channelRenderer` 里取 channelId + 名称。
   */
  fun parseChannelCandidates(root: JsonObject): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    collectByKey(root, KEY_CHANNEL_RENDERER) { node ->
      val id = node.stringOrNull("channelId")
      if (!id.isNullOrBlank()) {
        val title = runsText(node.obj("title")).ifBlank { simpleText(node.obj("title")) }
        result.add(id to title)
      }
    }
    return result
  }

  /**
   * 从 /player 响应解析视频详情（简介 Tab）。
   *
   * 取 `videoDetails`（title / author 频道名 / shortDescription 简介 / viewCount）+ `microformat`
   * （publishDate）。频道头像在 /player 里没有对应字段，留空由 UI 渲染占位。
   * 取不到 videoId/title 返回 null（调用方降级为「简介不可用」）。
   */
  fun parseVideoDetail(playerJson: JsonObject): YoutubeVideoDetail? {
    val vd = playerJson.obj("videoDetails") ?: return null
    val videoId = vd.stringOrNull("videoId") ?: return null
    val title = vd.stringOrNull("title").orEmpty()
    if (title.isBlank()) return null
    val description = vd.stringOrNull("shortDescription").orEmpty()
    val channelName = vd.stringOrNull("author").orEmpty()
    val viewCount = parseCount(vd.stringOrNull("viewCount"))
    val publishedAt = playerJson.obj("microformat")
      ?.obj("playerMicroformatRenderer")
      ?.stringOrNull("publishDate")
      ?.let { parsePublishDate(it) }
    return YoutubeVideoDetail(
      videoId = videoId,
      title = title,
      description = description,
      channelName = channelName,
      channelAvatarUrl = "",
      viewCount = viewCount,
      publishedAt = publishedAt,
    )
  }

  /**
   * 从 /next 响应解析一页评论 + 续页 token。
   *
   * 评论实体散落在 commentThreadRenderer → comment.commentRenderer 子树里，用 collectByKey 递归收集；
   * 续页 token 优先取评论 section 子树内的 continuation（避免把相关视频等其它 section 的 token 误当
   * 评论续页），取不到再回退到全局最后一个 continuation。
   */
  fun parseCommentPage(root: JsonObject): YoutubeCommentPage {
    val comments = mutableListOf<YoutubeComment>()
    var token: String? = null
    collectByKey(root, KEY_COMMENT_SECTION_RENDERER) { section ->
      collectByKey(section, KEY_COMMENT_RENDERER) { node ->
        parseCommentRenderer(node)?.let { comments.add(it) }
      }
      if (token == null) token = findContinuation(section)
    }
    // 防御：无 commentSectionRenderer 容器时回退全根收集（续页响应直接是 commentThreadRenderer）。
    if (comments.isEmpty() && token == null) {
      collectByKey(root, KEY_COMMENT_RENDERER) { node ->
        parseCommentRenderer(node)?.let { comments.add(it) }
      }
      token = findContinuation(root)
    }
    return YoutubeCommentPage(items = comments, continuation = token)
  }

  /**
   * 从首屏 /next 响应提取初始评论 continuation token（对齐 NewPipe YoutubeCommentsExtractor）。
   *
   * 首屏响应里评论不在 contents 主内容里，而在 `engagementPanels` 数组的
   * `engagementPanelSectionListRenderer`（panelIdentifier=engagement-panel-comments-section），
   * 只有 token 没有实际评论；必须带 token 再发一次 /next 才返回 commentThreadRenderer。
   * 兼容旧布局：contents.twoColumnWatchNextResults.results.results.contents 里
   * itemSectionRenderer.targetId=comments-section 的 continuationItemRenderer。
   *
   * @return 初始评论 token；取不到返回 null（评论禁用或结构未知）。
   */
  fun findInitialCommentsToken(root: JsonObject): String? {
    // 1. 新布局：engagementPanels 数组里的 comments panel。
    val panels = root["engagementPanels"] as? JsonArray
    if (panels != null) {
      for (panel in panels) {
        val section = (panel as? JsonObject)?.obj("engagementPanelSectionListRenderer") ?: continue
        if (section.stringOrNull("panelIdentifier") == "engagement-panel-comments-section") {
          // token 在排序菜单 subMenuItems[].serviceEndpoint.continuationCommand.token。
          val subMenu = section.obj("header")
            ?.obj("engagementPanelTitleHeaderRenderer")
            ?.obj("menu")
            ?.obj("sortFilterSubMenuRenderer")
          subMenu?.array("subMenuItems")?.forEach { item ->
            val candidate = (item as? JsonObject)?.obj("serviceEndpoint")
              ?.obj("continuationCommand")
              ?.stringOrNull("token")
            if (!candidate.isNullOrBlank()) return candidate
          }
          // 回退：content 里的 continuationItemRenderer。
          findContinuation(section)?.let { return it }
        }
      }
    }
    // 2. 旧布局：contents 主内容里 itemSectionRenderer.targetId=comments-section。
    val contents = root.obj("contents")
      ?.obj("twoColumnWatchNextResults")
      ?.obj("results")
      ?.obj("results")
      ?.array("contents")
    if (contents != null) {
      for (item in contents) {
        val section = (item as? JsonObject)?.obj("itemSectionRenderer") ?: continue
        if (section.stringOrNull("targetId") == "comments-section") {
          section.array("contents")?.let { arr ->
            firstContinuationToken(arr)?.let { return it }
          }
        }
      }
    }
    return null
  }

  /**
   * 从 /next 响应解析相关视频（对齐 LibreTube/NewPipe：secondaryResults 里的 compactVideoRenderer）。
   *
   * 相关视频 rail 在 `contents.twoColumnWatchNextResults.secondaryResults.secondaryResults.results[]`，
   * 每项是 compactVideoRenderer（或 compactPlaylistRenderer/compactRadioRenderer，跳过）；续页 token
   * 从该 section 内的 continuationItemRenderer 取。防御：无 secondaryResults 容器时回退全根收集。
   */
  fun parseRelatedVideos(root: JsonObject): YoutubeFeedPage {
    val videos = mutableListOf<YoutubeVideo>()
    var token: String? = null
    val secondary = root.obj("contents")
      ?.obj("twoColumnWatchNextResults")
      ?.obj("secondaryResults")
      ?.obj("secondaryResults")
    if (secondary != null) {
      collectByKey(secondary, KEY_COMPACT_VIDEO_RENDERER) { node ->
        parseVideoRenderer(node)?.let { videos.add(it) }
      }
      // 相关视频 rail 新格式 lockupViewModel(实测 2026-08-18:secondaryResults 每项是
      // {"lockupViewModel":{...}},非 compactVideoRenderer,与频道页新格式一致)。
      collectByKey(secondary, KEY_LOCKUP_VIEW_MODEL) { node ->
        parseLockupViewModel(node)?.let { videos.add(it) }
      }
      token = findContinuation(secondary)
    }
    // 防御：无 secondaryResults 容器时回退全根收集。
    if (videos.isEmpty() && token == null) {
      collectByKey(root, KEY_COMPACT_VIDEO_RENDERER) { node ->
        parseVideoRenderer(node)?.let { videos.add(it) }
      }
      collectByKey(root, KEY_LOCKUP_VIEW_MODEL) { node ->
        parseLockupViewModel(node)?.let { videos.add(it) }
      }
      token = findContinuation(root)
    }
    // 诊断：确认 /next 响应里相关视频 rail 的真实结构（真机相关视频区为空时定位）。
    val hasTwoCol = root.obj("contents")?.obj("twoColumnWatchNextResults") != null
    val hasSecondary = secondary != null
    val rootCompact = mutableListOf<JsonObject>()
    collectByKey(root, KEY_COMPACT_VIDEO_RENDERER) { rootCompact.add(it) }
    Log.i(
      "YtRelated",
      "parseRelatedVideos: twoCol=$hasTwoCol secondary=$hasSecondary " +
        "parsed=${videos.size} rootCompact=${rootCompact.size} token=${token != null} " +
        "keys=${root.keys.take(8)}"
    )
    // 结构诊断：打印 twoColumnWatchNextResults 键树 + secondary results 每项 renderer 类型。
    root.obj("contents")?.obj("twoColumnWatchNextResults")?.let { twoCol ->
      Log.i("YtRelated", "twoCol keys=${twoCol.keys}")
      twoCol.obj("secondaryResults")?.let { srOuter ->
        Log.i("YtRelated", "secondaryResults(outer) keys=${srOuter.keys}")
        srOuter.obj("secondaryResults")?.let { srInner ->
          Log.i("YtRelated", "secondaryResults(inner) keys=${srInner.keys}")
          srInner.array("results")?.forEachIndexed { i, item ->
            val rendererKey = (item as? JsonObject)?.keys?.firstOrNull { it.endsWith("Renderer") }
            Log.i("YtRelated", "secondary results[$i] renderer=$rendererKey")
          }
        }
      }
    }
    return YoutubeFeedPage(items = videos, continuation = token)
  }

  private fun parseCommentRenderer(node: JsonObject): YoutubeComment? {
    val commentId = node.stringOrNull("commentId") ?: return null
    val authorName = runsText(node.obj("authorText")).ifBlank { simpleText(node.obj("authorText")) }
    val avatarUrl = node.obj("authorThumbnail")
      ?.array("thumbnails")
      ?.let(::pickBestThumbnailUrl)
      .orEmpty()
    val content = runsText(node.obj("contentText")).ifBlank { simpleText(node.obj("contentText")) }
    val likeCount = parseCount(node.stringOrNull("likeCount"))
    val publishedAt = node.obj("publishedTimeText")?.let { pt ->
      parsePublished(simpleText(pt).ifBlank { runsText(pt) }, liveNow = false, isUpcoming = false)
    }
    // 对齐 LibreTube/NewPipe 的评论字段：认证/置顶/作者点赞/回复数/楼中楼/频道主/作者回复。
    val repliesSubtree = node.obj("replies")?.obj("commentRepliesRenderer")
    return YoutubeComment(
      commentId = commentId,
      authorName = authorName,
      authorAvatarUrl = avatarUrl,
      content = content,
      likeCount = likeCount,
      publishedAt = publishedAt,
      verified = node.obj("authorCommentBadge") != null,
      pinned = node.obj("pinnedCommentBadge") != null,
      hearted = node.obj("actionButtons")
        ?.obj("commentActionButtonsRenderer")
        ?.obj("creatorHeart") != null,
      replyCount = node.stringOrNull("replyCount")?.toIntOrNull() ?: 0,
      repliesPage = repliesSubtree?.let(::findContinuation),
      channelOwner = node.booleanOrNull("authorIsChannelOwner") ?: false,
      creatorReplied = repliesSubtree?.obj("viewRepliesCreatorThumbnail") != null,
    )
  }

  /** "YYYY-MM-DD" → epoch 秒；解析失败返回 null。 */
  private fun parsePublishDate(date: String): Long? {
    val parts = date.split('-').mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return null
    val cal = Calendar.getInstance().apply {
      clear()
      set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
    }
    return cal.timeInMillis / 1000L
  }

  /**
   * 新格式 lockupViewModel(频道页，实测为频道视频 tab 唯一格式)。
   *
   * 实测结构（2026-08）：
   *  - videoId 在顶层 `contentId`(字符串,不是对象!)
   *  - 标题 `metadata.lockupMetadataViewModel.title.content`
   *  - 封面 `contentImage.thumbnailViewModel.image.sources[].url`(取最后一张最大)
   *  - 时长/直播角标在 contentImage overlay 的 `thumbnailBadgeViewModel.text`(如 "13:09")
   *  - 播放量/发布时间在 `metadata.lockupMetadataViewModel.metadata.contentMetadataViewModel
   *    .metadataRows[].metadataParts[].text.content`(如 "56K views"、"4 days ago")
   *  - 无频道名(频道页视频不重复显示频道名)
   * 拿不到关键字段就跳过。防御式,不抛错。
   */
  private fun parseLockupViewModel(node: JsonObject): YoutubeVideo? {
    val videoId = node.stringOrNull("contentId")
    if (videoId.isNullOrBlank()) return null
    // 诊断:dump lockupViewModel 的 badges 数组 + 顶层键,定位会员专属视频真实结构。
    node.array("badges")?.let { b ->
      Log.d("YtBadge", "lockup videoId=$videoId badges=${b.toString().take(400)}")
    }
    // 会员专属视频(频道会员专属,非会员无法播放)直接过滤,不进 feed。
    if (isMembersOnly(node)) return null
    val title = node.obj("metadata")
      ?.obj("lockupMetadataViewModel")
      ?.obj("title")
      ?.stringOrNull("content")
      .orEmpty()
    if (title.isBlank()) return null

    val thumbnailUrl = node.obj("contentImage")
      ?.obj("thumbnailViewModel")?.obj("image")?.array("sources")
      ?.lastOrNull()?.let { (it as? JsonObject)?.stringOrNull("url") }
      ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    // 封面子树里的文本:时长 / LIVE / Premieres。
    val imageTexts = mutableListOf<String>()
    collectStrings(node.obj("contentImage"), imageTexts)
    val durationSec = imageTexts.firstNotNullOfOrNull { parseDuration(it) }
    val isUpcoming = imageTexts.any { it.contains("Premieres", ignoreCase = true) }
    val liveNow = imageTexts.any {
      it.contains("watching", ignoreCase = true) ||
        it.equals("LIVE", ignoreCase = true) ||
        isUpcoming
    }

    // metadata 子树里的文本:播放量 / 发布时间 / 角标。
    val metaTexts = mutableListOf<String>()
    collectStrings(node.obj("metadata"), metaTexts)
    val viewCount = metaTexts.firstNotNullOfOrNull { parseCount(it) }
    val publishedAt = metaTexts.firstNotNullOfOrNull { parsePublished(it, liveNow, isUpcoming) }
    val badge = metaTexts.firstOrNull {
      it.contains("LIVE", ignoreCase = true) ||
        it.contains("Premieres", ignoreCase = true)
    }.orEmpty()

    return YoutubeVideo(
      videoId = videoId,
      title = title,
      channelName = "",
      channelId = "",
      thumbnailUrl = thumbnailUrl,
      viewCount = viewCount,
      durationSec = durationSec,
      publishedAt = publishedAt,
      liveNow = liveNow,
      isUpcoming = isUpcoming,
      badge = badge,
    )
  }

  // ---- 单个 videoRenderer 解析 ----

  private fun parseVideoRenderer(node: JsonObject): YoutubeVideo? {
    val videoId = node.stringOrNull("videoId") ?: return null
    // 诊断:dump 每个视频的 badges 数组,定位会员专属视频真实结构。
    node.array("badges")?.let { b ->
      Log.d("YtBadge", "videoId=$videoId badges=${b.toString().take(400)}")
    }
    // 会员专属视频(频道会员专属,非会员无法播放)直接过滤,不进 feed。
    if (isMembersOnly(node)) return null

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

    // 频道头像:videoRenderer 在 channelThumbnail.thumbnails;部分客户端在
    // channelThumbnailSupportedRenderers.channelThumbnailWithAvatarRenderer.avatar.thumbnails。
    val channelAvatarUrl = node.obj("channelThumbnail")
      ?.array("thumbnails")
      ?.let(::pickBestThumbnailUrl)
      ?: node.obj("channelThumbnailSupportedRenderers")
        ?.obj("channelThumbnailWithAvatarRenderer")
        ?.obj("avatar")
        ?.array("thumbnails")
        ?.let(::pickBestThumbnailUrl)
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
      channelAvatarUrl = channelAvatarUrl,
      thumbnailUrl = thumbnailUrl,
      viewCount = viewCount,
      durationSec = if (liveNow) null else parseDuration(lengthText),
      publishedAt = publishedAt,
      liveNow = liveNow,
      isUpcoming = isUpcoming,
      badge = badge,
    )
  }

  /**
   * 会员专属视频(频道会员专属,非会员无法播放)。对齐 NewPipe `YoutubeStreamInfoItemExtractor`:
   * 检查 badges[] 里是否有 `metadataBadgeRenderer.style == BADGE_STYLE_TYPE_MEMBERS_ONLY`。
   * 命中即过滤,避免展示无法播放的视频。
   */
  private fun isMembersOnly(node: JsonObject): Boolean {
    return node.array("badges")
      ?.any { (it as? JsonObject)?.obj("metadataBadgeRenderer")?.stringOrNull("style") == "BADGE_STYLE_TYPE_MEMBERS_ONLY" }
      ?: false
  }

  // ---- 文本辅助 ----

  /** 从 thumbnails 数组选最高分辨率那张的 url(对齐 LibreTube `maxByOrNull { it.height }`)。 */
  private fun pickBestThumbnailUrl(thumbnails: JsonArray): String? {
    return thumbnails
      .mapNotNull { it as? JsonObject }
      .maxByOrNull { it.stringOrNull("height")?.toIntOrNull() ?: 0 }
      ?.stringOrNull("url")
  }

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
    // 只接受含冒号的真实时长格式 "MM:SS"/"HH:MM:SS"。单段纯数字(如 lockupViewModel 里被
    // collectStrings 收成字符串的缩略图 width/height/backgroundColor 等数值)不是时长,必须拒绝;
    // 否则首个匹配的固定数值会被当成时长,导致整个主页所有视频时长显示成同一个常量(实测 2:58)。
    if (!text.contains(':')) return null
    val parts = text.split(':').mapNotNull { it.toIntOrNull() }
    if (parts.size < 2 || parts.last() !in 0..59) return null
    return when (parts.size) {
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

  /** 递归收集子树里的所有字符串叶子(lockupViewModel 里时长/播放量/发布时间都藏在文本里)。 */
  private fun collectStrings(element: JsonElement?, out: MutableList<String>) {
    when (element) {
      is JsonObject -> {
        for ((_, value) in element) {
          collectStrings(value, out)
        }
      }
      is JsonArray -> {
        for (item in element) {
          collectStrings(item, out)
        }
      }
      is kotlinx.serialization.json.JsonPrimitive -> {
        element.contentOrNull?.let { out.add(it) }
      }
      else -> Unit
    }
  }

  /**
   * 提取续页 token（对齐 NewPipe YoutubeChannelTabExtractor，避免取到非视频网格的 token）：
   *   1. 续页响应：token 在 onResponseReceivedActions → appendContinuationItemsAction → continuationItems；
   *   2. 首屏：定位视频网格/richGrid 的 items 数组，取第一个 continuation；
   *   3. 回退（搜索等无网格容器）：全树第一个 continuationItemRenderer。
   * 之前是全树扫+取最后一个，频道视频 tab 响应里可能夹带 shorts/相关频道等其它 section 的 token，
   * 取错会导致续页返回首屏相同视频（去重后零新增 → ~50 到底 / 死循环）。
   */
  private fun findContinuation(root: JsonObject): String? {
    appendContinuationItems(root)?.let { return firstContinuationToken(it) }
    reloadContinuationItems(root)?.let { return firstContinuationToken(it) }
    videoGridItems(root)?.let { return firstContinuationToken(it) }
    return firstContinuationTokenRoot(root)
  }

  /** 续页响应容器：onResponseReceivedActions 里第一个 appendContinuationItemsAction.continuationItems。 */
  private fun appendContinuationItems(root: JsonObject): JsonArray? {
    val actions = root["onResponseReceivedActions"] as? JsonArray ?: return null
    for (action in actions) {
      val append = (action as? JsonObject)?.obj("appendContinuationItemsAction") ?: continue
      return append.array("continuationItems")
    }
    return null
  }

  /** 评论续页响应容器：onResponseReceivedEndpoints 里最后一个 reloadContinuationItemsCommand / appendContinuationItemsAction 的 continuationItems（对齐 NewPipe 取末尾）。 */
  private fun reloadContinuationItems(root: JsonObject): JsonArray? {
    val endpoints = root["onResponseReceivedEndpoints"] as? JsonArray ?: return null
    for (i in endpoints.indices.reversed()) {
      val obj = endpoints[i] as? JsonObject ?: continue
      obj.obj("reloadContinuationItemsCommand")?.array("continuationItems")?.let { return it }
      obj.obj("appendContinuationItemsAction")?.array("continuationItems")?.let { return it }
    }
    return null
  }

  /** 首屏：递归找第一个视频网格容器（gridRenderer.items 或 richGridRenderer.contents）。 */
  private fun videoGridItems(element: JsonElement): JsonArray? {
    when (element) {
      is JsonObject -> {
        element.obj("gridRenderer")?.array("items")?.let { return it }
        element.obj("richGridRenderer")?.array("contents")?.let { return it }
        for ((_, value) in element) {
          videoGridItems(value)?.let { return it }
        }
      }
      is JsonArray -> {
        for (item in element) {
          videoGridItems(item)?.let { return it }
        }
      }
      else -> Unit
    }
    return null
  }

  /** 在指定 items 数组里取第一个非空 continuationItemRenderer token（兼容 continuationEndpoint 与 button 两种结构）。 */
  private fun firstContinuationToken(items: JsonArray): String? {
    var token: String? = null
    collectByKey(items, KEY_CONTINUATION_ITEM_RENDERER) { node ->
      val candidate = node.obj("continuationEndpoint")
        ?.obj("continuationCommand")
        ?.stringOrNull("token")
        ?: node.obj("button")?.obj("buttonRenderer")?.obj("command")
          ?.obj("continuationCommand")?.stringOrNull("token")
      if (token == null && !candidate.isNullOrBlank()) token = candidate
    }
    return token
  }

  /** 全树第一个非空 continuationItemRenderer token（搜索等无网格容器回退用）。 */
  private fun firstContinuationTokenRoot(root: JsonElement): String? {
    var token: String? = null
    collectByKey(root, KEY_CONTINUATION_ITEM_RENDERER) { node ->
      if (token == null) {
        val candidate = node.obj("continuationEndpoint")
          ?.obj("continuationCommand")
          ?.stringOrNull("token")
          ?: node.obj("button")?.obj("buttonRenderer")?.obj("command")
            ?.obj("continuationCommand")?.stringOrNull("token")
        if (!candidate.isNullOrBlank()) token = candidate
      }
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
  private const val KEY_CHANNEL_RENDERER = "channelRenderer"
  private const val KEY_COMMENT_RENDERER = "commentRenderer"
  private const val KEY_COMMENT_SECTION_RENDERER = "commentSectionRenderer"
}
