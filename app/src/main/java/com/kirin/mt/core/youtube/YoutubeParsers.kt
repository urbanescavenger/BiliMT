package com.kirin.mt.core.youtube

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    // 频道页 Shorts tab:shortsLockupViewModel。⚠️ 实测(2026-08-27)它是 reel 风格,不是
    // lockupViewModel 形状:无 contentId,视频ID 在 onTap.innertubeCommand.reelWatchEndpoint.videoId,
    // 标题/播放量在顶层 accessibilityText("标题, X views - play Short")。走专属 parseShortsLockupViewModel。
    var shortsDumpDone = false
    collectByKey(root, KEY_SHORTS_LOCKUP_VIEW_MODEL) { node ->
      val v = parseShortsLockupViewModel(node)
      if (v != null) videos.add(v)
      else if (!shortsDumpDone) {
        // 诊断:parseShortsLockupViewModel 也失败时 dump 首个失败节点结构。
        shortsDumpDone = true
        Log.w("YtShorts", "shorts node keys=[${node.keys.joinToString(",")}] " +
          "contentId=${node.stringOrNull("contentId")} top=${node.toString().take(600)}")
      }
    }
    // 播放列表详情页(open playlist)条目 playlistVideoRenderer,与 videoRenderer 共享
    // videoId/thumbnail/title/lengthText 形状,parseVideoRenderer 可直接复用。
    collectByKey(root, KEY_PLAYLIST_VIDEO_RENDERER) { node ->
      parseVideoRenderer(node)?.let { videos.add(it) }
    }
    // 频道 Shorts tab 经典条目 reelItemRenderer(短剧网格)。shortsLockupViewModel 已在上方收集,
    // 部分频道 Shorts tab 用 reelItemRenderer,两者都收集保证不空。
    collectByKey(root, KEY_REEL_ITEM_RENDERER) { node ->
      parseReelItemRenderer(node)?.let { videos.add(it) }
    }
    val continuation = findContinuation(root)
    return YoutubeFeedPage(items = videos, continuation = continuation)
  }

  /**
   * 诊断:频道视频 /browse 返回 0 条时,抽根因提示。优先读 `alerts[].alertRenderer.text`(如
   * "This channel does not exist."),其次是顶层键缺失 contents 的事实。返回可打印字符串,无则为 null。
   */
  fun diagnosticEmptyReason(root: JsonObject): String? {
    val alerts = root["alerts"] as? JsonArray
    if (alerts != null) {
      for (alert in alerts) {
        val text = (alert as? JsonObject)?.obj("alertRenderer")?.obj("text")
        val simple = text?.stringOrNull("simpleText")
        if (!simple.isNullOrBlank()) return "alerts: $simple"
        val runs = text?.array("runs")
        if (runs != null) {
          val joined = runs.mapNotNull { (it as? JsonObject)?.stringOrNull("text") }.joinToString("")
          if (joined.isNotBlank()) return "alerts: $joined"
        }
      }
      return "alerts-present-but-no-text: ${alerts.toString().take(300)}"
    }
    if (root["contents"] == null) {
      return "no-contents topKeys=${root.keys.joinToString(",")}"
    }
    return null
  }

  /**
   * 播放列表 Tab 空响应诊断:报响应里出现的 lockupViewModel contentType 分布 + 顶层键。
   * 新布局频道播放列表卡可能是 lockupViewModel(contentType=PLAYLIST)而非旧 playlistRenderer,
   * 用它确认 parseChannelPlaylists 漏收集的 renderer 形状,再决定要不要加收集分支。
   */
  fun diagnosticPlaylistShape(root: JsonObject): String {
    val lockup = mutableListOf<String>()
    collectByKey(root, "lockupViewModel") { node ->
      lockup.add(node.stringOrNull("contentType") ?: "?")
    }
    val playlists = arrayOf("playlistRenderer", "playlistCardRenderer").count { k ->
      root.toString().contains("\"$k\"")
    }
    return "topKeys=${root.keys.joinToString(",")} " +
      "lockup=[${lockup.joinToString(",")}] playlistRenderers=$playlists"
  }

  /**
   * 诊断:频道视频(Shorts 等)/browse 返回 0 条时的响应形状。报顶层键、contents 首节点下的
   * renderer 键,以及各种已知 renderer 键出现的次数——确认短视频响应是「真无内容」还是
   * 「用了解析器漏收集的 renderer 形状」。
   */
  fun diagnosticFeedShape(root: JsonObject): String {
    val counts = LinkedHashMap<String, Int>()
    val known = arrayOf(
      "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "lockupViewModel",
      "shortsLockupViewModel", "reelItemRenderer", "playlistVideoRenderer", "richItemRenderer",
      "shelfRenderer", "radioRenderer", "playlistRenderer", "alertRenderer", "messageRenderer",
    )
    fun scan(e: JsonElement) {
      when (e) {
        is JsonObject -> {
          for (k in e.keys) {
            if (known.contains(k)) counts[k] = (counts[k] ?: 0) + 1
          }
          for ((_, v) in e) scan(v)
        }
        is JsonArray -> for (item in e) scan(item)
        else -> Unit
      }
    }
    scan(root)
    val contentKeys = (root["contents"] as? JsonObject)?.keys?.joinToString(",") ?: "null"
    val sections = counts.entries.filter { it.value > 0 }
      .joinToString(",") { "${it.key}=${it.value}" }
    return "top=${root.keys.joinToString(",")} contentsKeys=[$contentKeys] renderers=[${sections.ifBlank { "none" }}]"
  }

  /**
   * 频道页 header 解析结果。含订阅数/banner/简介/认证，供频道页头部展示
   *（对齐 LibreTube `ChannelResponse` 的 subscriberCount/banner/description/verified）。
   */
  data class ChannelInfo(
    val channelId: String,
    val name: String,
    val avatarUrl: String,
    /** 订阅数；未知为 null。 */
    val subscriberCount: Long? = null,
    /** banner 图 URL；无则空串。 */
    val bannerUrl: String = "",
    /** 频道简介；无则空串。 */
    val description: String = "",
    /** 认证频道（badges 含 VERIFIED）。 */
    val verified: Boolean = false,
    /**
     * 频道内容 Tab(视频/Shorts/直播)及其 /browse params。从响应解析(tabIdentifier + endpoint
     * 的 browseEndpoint.params),对齐 LibreTube/NewPipe 从 header 取 tab。硬编码 params 对部分
     * 频道/新布局失效,用服务端提供的 params 才能切到对应 Tab。
     */
    val tabs: List<ChannelTab> = emptyList(),
  )

  /**
   * 频道内容 Tab:稳定标识(如 Videos/Shorts/Streams,服务端 tabIdentifier)+ 对应的 /browse params。
   */
  data class ChannelTab(
    val name: String,
    val params: String,
  )

  /**
   * 频道"播放列表"Tab 的一张播放列表卡(playlistRenderer)。供频道页 Playlists Tab 展示;
   * 点击用 [browseId] 打开播放列表详情。
   */
  data class YoutubePlaylist(
    val id: String,
    val title: String,
    /** 封面图 URL;无则空串。 */
    val thumbnail: String,
    /** 视频数文案(如 "20 videos");无则空串。 */
    val videoCount: String,
    /** 打开播放列表的 browseId(playlistRenderer.navigationEndpoint.browseEndpoint.browseId,形如 VL...)。 */
    val browseId: String,
  )

  /**
   * 从频道页 /browse 响应解析频道 info，返回 [ChannelInfo]。
   *
   * YouTube 频道页 header 有三种形态，字段分散在不同形态，合并收集：
   *   1. header → c4TabbedHeaderRenderer → { channelId, title, avatar, subscriberCountText,
   *      banner, description, badges(VERIFIED) }
   *   2. metadata → channelMetadataRenderer → { externalId, title, avatar, description }
   *   3. microformat → microformatDataRenderer → { externalId, title, description }
   * 先命中 c4Header 的完整字段，缺的用 metadata/microformat 补齐。
   *
   * @return 解析出的 [ChannelInfo]；解析不到 channelId 时返回 null（由调用方回退输入串）。
   */
  fun parseChannelInfo(root: JsonObject): ChannelInfo? {
    var id: String? = null
    var name = ""
    var avatar = ""
    var subscriberCount: Long? = null
    var banner = ""
    var desc = ""
    var verified = false

    val c4Header = root.obj("header")?.obj("c4TabbedHeaderRenderer")
    if (c4Header != null) {
      val c4Id = c4Header.stringOrNull("channelId")
      if (!c4Id.isNullOrBlank()) {
        id = c4Id
        name = c4Header.stringOrNull("title").orEmpty()
        avatar = c4Header.obj("avatar")?.array("thumbnails")?.let(::pickBestThumbnailUrl).orEmpty()
        subscriberCount = parseCount(
          runsText(c4Header.obj("subscriberCountText")).ifBlank { simpleText(c4Header.obj("subscriberCountText")) },
        )
        banner = c4Header.obj("banner")?.array("thumbnails")?.let(::pickBestThumbnailUrl)
          ?: c4Header.obj("mobileBanner")?.array("thumbnails")?.let(::pickBestThumbnailUrl)
          .orEmpty()
        desc = c4Header.obj("description")?.let { runsText(it).ifBlank { simpleText(it) } }.orEmpty()
        verified = c4Header.array("badges")?.any {
          val badge = (it as? JsonObject)
          val renderer = badge?.obj("badgeRenderer") ?: badge?.obj("metadataBadgeRenderer")
          renderer?.stringOrNull("style")?.contains("VERIFIED", ignoreCase = true) == true ||
            renderer?.stringOrNull("styleId")?.contains("VERIFIED", ignoreCase = true) == true
        } == true
      }
    }

    val channelMetadata = root.obj("metadata")?.obj("channelMetadataRenderer")
    if (channelMetadata != null) {
      val metaId = channelMetadata.stringOrNull("externalId")
      if (!metaId.isNullOrBlank()) {
        if (id == null) id = metaId
        if (name.isBlank()) name = channelMetadata.stringOrNull("title").orEmpty()
        if (avatar.isBlank()) {
          avatar = channelMetadata.obj("avatar")?.array("thumbnails")?.let(::pickBestThumbnailUrl).orEmpty()
        }
        if (desc.isBlank()) desc = channelMetadata.stringOrNull("description").orEmpty()
      }
    }

    val microformat = root.obj("microformat")?.obj("microformatDataRenderer")
    if (microformat != null) {
      val mfId = microformat.stringOrNull("externalId")
      if (!mfId.isNullOrBlank()) {
        if (id == null) id = mfId
        if (name.isBlank()) name = microformat.stringOrNull("title").orEmpty()
        if (desc.isBlank()) desc = microformat.stringOrNull("description").orEmpty()
      }
    }

    if (id == null) return null
    return ChannelInfo(
      channelId = id,
      name = name,
      avatarUrl = avatar,
      subscriberCount = subscriberCount,
      bannerUrl = banner,
      description = desc,
      verified = verified,
      tabs = parseChannelTabs(root),
    )
  }

  /**
   * 从频道页 /browse 响应解析内容 Tab 栏(视频/Shorts/直播/播放列表等)。
   *
   * 频道布局依 client 版本/频道而变:部分返回旧 `tabRenderer`(真机 UCTu_hTa 有 featured/videos/
   * shorts/podcast/playlists/posts 6 个),部分新布局只给 `expandableTabRenderer`(搜索)。两种都递归
   * 收集。名字不靠 title(新格式 title.simpleText/content/tabIdentifier 常为空),而是从 params 解码
   * protobuf field1 取稳定标识(videos/shorts/streams/playlists/featured/podcast/posts)。取「有 params
   * 的 tab」按名去重组成 (name, params) 列表,供切 Tab 用服务端 params(而非硬编码)。
   */
  fun parseChannelTabs(root: JsonObject): List<ChannelTab> {
    val result = mutableListOf<ChannelTab>()
    val seen = HashSet<String>()
    collectByKey(root, KEY_TAB_RENDERER) { renderer -> collectTab(renderer, seen, result) }
    collectByKey(root, KEY_EXPANDABLE_TAB_RENDERER) { renderer -> collectTab(renderer, seen, result) }
    // 诊断:打印解析到的 tab 名字 + params 前缀,便于真机核验服务端 tab params 是否取到。
    Log.d("Ytabs", "parseChannelTabs found=${result.map { "${it.name}:${it.params.take(10)}" }}")
    return result
  }

  /**
   * 从单个 tab(旧 tabRenderer 或新 expandableTabRenderer)取 name + params。
   *
   * 名字优先从 params 解码的 protobuf field1 取(如 "videos"/"shorts"/"streams"/"playlists"/
   * "featured"/"posts"),因为新布局 tab 的 title 结构多变(simpleText/content/tabIdentifier 常为
   * 空,真机 SHAPE 全 text=null),而 params 里的 field1 字符串是稳定标识。取不到再回退 title。
   */
  private fun collectTab(renderer: JsonObject, seen: MutableSet<String>, out: MutableList<ChannelTab>) {
    val params = renderer.obj("endpoint")?.obj("browseEndpoint")?.stringOrNull("params")
    if (params.isNullOrBlank()) return
    val title = renderer.obj("title")
    val name = decodeTabName(params)
      ?: renderer.stringOrNull("tabIdentifier")
      ?: title?.stringOrNull("simpleText")
      ?: title?.stringOrNull("content").orEmpty()
    if (name.isBlank()) return
    if (seen.add(name.lowercase())) out.add(ChannelTab(name = name, params = params))
  }

  /**
   * 从 /browse tab 的 params(URL 编码 base64 的 protobuf)解析第一个 length-delimited 字符串字段,
   * 得到 tab 类型标识:视频=videos / 短视频=shorts / 直播=streams / 播放列表=playlists /
   * 精选=featured / 播客=podcast / 帖子=posts。InnerTube 的 tab 选择器在 field2(wire2,首字节
   * 0x12 而非 0x0A),所以不限定字段号,通用读首个 wire-type-2 字段。解析失败返回 null。
   */
  private fun decodeTabName(params: String): String? {
    val urlDecoded = try { java.net.URLDecoder.decode(params, "UTF-8") } catch (e: Exception) { params }
    val bytes = try { android.util.Base64.decode(urlDecoded, android.util.Base64.DEFAULT) }
    catch (e: Exception) { return null }
    if (bytes.size < 2) return null
    var idx = 0
    // 读 key varint(不限字段号,但要求 wire type 2 = 低3位是 010)。
    val wire = bytes[0].toInt() and 0x07
    if (wire != 2) return null
    do {
      idx++
      if (idx >= bytes.size) return null
    } while (bytes[idx - 1].toInt() and 0x80 != 0)
    // 读长度 varint。
    var len = 0
    var shift = 0
    while (idx < bytes.size) {
      val b = bytes[idx].toInt()
      len = len or ((b and 0x7F) shl shift)
      idx++
      if (b and 0x80 == 0) break
      shift += 7
      if (shift > 28) return null
    }
    if (len <= 0 || idx + len > bytes.size) return null
    return String(bytes, idx, len, Charsets.UTF_8)
  }

  /**
   * 从频道"播放列表"Tab 的 /browse 响应解析播放列表卡列表。兼容两种形状：
   *  - 旧布局:richItemRenderer.content.playlistRenderer(递归收集所有 playlistRenderer)
   *  - 新布局:lockupViewModel + contentType=LOCKUP_CONTENT_TYPE_PLAYLIST(实测 2026-08,
   *    纯 playlistRenderer 收集返回 0,lockup 才是新布局播放列表卡;视频 tab 的 lockup 是
   *    contentType=VIDEO,按 contentType 过滤互不串)。
   * 递归收集两种 renderer,按序追加。
   */
  fun parseChannelPlaylists(root: JsonObject): List<YoutubePlaylist> {
    val result = mutableListOf<YoutubePlaylist>()
    collectByKey(root, KEY_PLAYLIST_RENDERER) { node ->
      val id = node.stringOrNull("playlistId") ?: return@collectByKey
      val title = runsText(node.obj("title")).ifBlank { simpleText(node.obj("title")) }
      val thumbnail = node.obj("thumbnailRenderer")?.obj("thumbnail")
        ?.array("thumbnails")?.let(::pickBestThumbnailUrl)
        .orEmpty()
      val count = runsText(node.obj("videoCountText")).ifBlank { simpleText(node.obj("videoCountText")) }
      val browseId = node.obj("navigationEndpoint")?.obj("browseEndpoint")
        ?.stringOrNull("browseId").orEmpty()
      result.add(
        YoutubePlaylist(
          id = id,
          title = title,
          thumbnail = thumbnail,
          videoCount = count,
          browseId = browseId,
        ),
      )
    }
    // 新布局 lockupViewModel 播放列表卡。
    collectByKey(root, "lockupViewModel") { node ->
      val ct = node.stringOrNull("contentType")
      if (ct == null || !ct.contains("PLAYLIST")) return@collectByKey
      val id = node.stringOrNull("contentId") ?: return@collectByKey
      val title = node.obj("metadata")?.obj("lockupMetadataViewModel")?.obj("title")
        ?.stringOrNull("content")
        ?: return@collectByKey
      // 封面(实测 2026-08-27,频道页播放列表 tab):contentImage 是 collectionThumbnailViewModel
      // 包裹,真封面在 primaryThumbnail.thumbnailViewModel.image.sources(21/21 卡全此形状);
      // 旧直连 contentImage.thumbnailViewModel 仅作服务端回落兜底。直连路径下封面恒空 →
      // 频道页播放列表卡只剩 "▶" 占位块(alpha 期旧疾)。
      val thumbnailViewModel = node.obj("contentImage")?.obj("collectionThumbnailViewModel")
        ?.obj("primaryThumbnail")?.obj("thumbnailViewModel")
        ?: node.obj("contentImage")?.obj("thumbnailViewModel")
      val thumbnail = thumbnailViewModel?.obj("image")?.array("sources")
        ?.let(::pickBestThumbnailUrl).orEmpty()
      // 视频数(实测 2026-08-27):缩略图角标 badge("100 videos",
      // thumbnailOverlayBadgeViewModel.thumbnailBadges);metadataRows 现在只有
      // "Updated X ago"/"View full playlist",已不是视频数(旧注释的 metadataRows 路径失效),
      // 仅作 badge 缺失时兜底。
      val badgeTexts = mutableListOf<String>()
      thumbnailViewModel?.array("overlays")?.forEach { overlay ->
        (overlay as? JsonObject)?.obj("thumbnailOverlayBadgeViewModel")?.array("thumbnailBadges")
          ?.forEach { badge ->
            (badge as? JsonObject)?.obj("thumbnailBadgeViewModel")?.stringOrNull("text")
              ?.let { badgeTexts.add(it) }
          }
      }
      val countTexts = mutableListOf<String>()
      node.obj("metadata")?.obj("lockupMetadataViewModel")?.obj("metadata")
        ?.obj("contentMetadataViewModel")?.array("metadataRows")?.forEach { row ->
          (row as? JsonObject)?.array("metadataParts")?.forEach { part ->
            (part as? JsonObject)?.obj("text")?.stringOrNull("content")?.let { countTexts.add(it) }
          }
        }
      val count = badgeTexts.firstOrNull() ?: countTexts.firstOrNull().orEmpty()
      // 打开播放列表的 browseId:onTap/navigationEndpoint 的 browseEndpoint 优先,回退 contentId
      // (实测 2026-08-27 lockup 卡 onTap 为空 → 落到 contentId(PL...),normalizePlaylistBrowseId
      // 会补 VL 前缀)。
      val browseId = node.obj("onTap")?.obj("navigationEndpoint")?.obj("browseEndpoint")
        ?.stringOrNull("browseId")
        ?: node.obj("navigationEndpoint")?.obj("browseEndpoint")?.stringOrNull("browseId")
        ?: id
      result.add(YoutubePlaylist(id = id, title = title, thumbnail = thumbnail, videoCount = count, browseId = browseId))
    }
    return result
  }

  /**
   * 频道 Shorts tab 的 reelItemRenderer(短剧网格条目)。形状:videoId + headline(title, runs 或
   * simpleText)+ thumbnail.thumbnails。频道/UP 名等缺省由频道页注入。
   */
  private fun parseReelItemRenderer(node: JsonObject): YoutubeVideo? {
    val videoId = node.stringOrNull("videoId") ?: return null
    val title = runsText(node.obj("headline")).ifBlank { simpleText(node.obj("headline")) }
    val thumbnail = node.obj("thumbnail")?.array("thumbnails")?.let(::pickBestThumbnailUrl)
      ?: node.obj("thumbnailRenderer")?.obj("thumbnail")?.array("thumbnails")?.let(::pickBestThumbnailUrl)
      ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
    return YoutubeVideo(
      videoId = videoId,
      title = title,
      channelName = "",
      channelId = "",
      thumbnailUrl = thumbnail,
      viewCount = null,
      durationSec = null,
      publishedAt = null,
      liveNow = false,
      isUpcoming = false,
      badge = "Shorts",
    )
  }

  /**
   * 频道 Shorts tab 的 shortsLockupViewModel(reel 风格条目)。
   *
   * 实测结构(2026-08-27,真机日志 dump):
   *  - 无 contentId!视频ID 在 `onTap.innertubeCommand.reelWatchEndpoint.videoId`
   *  - 标题 + 播放量在顶层 `accessibilityText`(形如 "标题, 2.4 thousand views - play Short")
   *  - 封面 `thumbnailViewModel.image.sources[].url`(取最大),失败回退 mqdefault
   * 拿不到 videoId 就跳过。防御式,不抛错。
   */
  private fun parseShortsLockupViewModel(node: JsonObject): YoutubeVideo? {
    val videoId = node.obj("onTap")?.obj("innertubeCommand")
      ?.obj("reelWatchEndpoint")?.stringOrNull("videoId")
    if (videoId.isNullOrBlank()) return null

    // accessibilityText 形如 "标题, 2.4 thousand views - play Short"。标题取逗号前,剩余部分找播放量。
    // 播放量片段含 " - play Short" 后缀与英文单位词(parseCount 只认 K/M/B 后缀),先清洗成数字串。
    val a11y = node.stringOrNull("accessibilityText").orEmpty()
    val title = a11y.substringBefore(", ").trim().ifBlank { "Short" }
    val viewsText = a11y.substringAfter(", ", "")
      .substringBefore(" - play").substringBefore(" - view")
      .replace("thousand", "K", ignoreCase = true)
      .replace("million", "M", ignoreCase = true)
      .replace("billion", "B", ignoreCase = true)
    val viewCount = parseCount(viewsText)

    val thumbnailUrl = node.obj("thumbnailViewModel")?.obj("image")
      ?.array("sources")?.let(::pickBestThumbnailUrl)
      ?: node.obj("contentImage")?.obj("thumbnailViewModel")?.obj("image")
        ?.array("sources")?.let(::pickBestThumbnailUrl)
      ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    return YoutubeVideo(
      videoId = videoId,
      title = title,
      channelName = "",
      channelId = "",
      thumbnailUrl = thumbnailUrl,
      viewCount = viewCount,
      durationSec = null,
      publishedAt = null,
      liveNow = false,
      isUpcoming = false,
      badge = "Shorts",
    )
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
   * 解析一页频道搜索结果（/search + params=TypeChannel），返回频道列表 + 续页 token。
   * 复用 [parseChannelCandidates] 的 channelRenderer 收集，但提取更全字段（头像/订阅/视频数/简介）。
   */
  fun parseChannelSearchPage(root: JsonObject): YoutubeChannelSearchPage {
    val channels = mutableListOf<YoutubeChannelSearchResult>()
    collectByKey(root, KEY_CHANNEL_RENDERER) { node ->
      val id = node.stringOrNull("channelId")
      if (!id.isNullOrBlank()) {
        val title = runsText(node.obj("title")).ifBlank { simpleText(node.obj("title")) }
        val avatar = node.obj("thumbnail")?.array("thumbnails")?.let(::pickBestThumbnailUrl).orEmpty()
        val subscribers = parseCount(
          runsText(node.obj("subscriberCountText")).ifBlank { simpleText(node.obj("subscriberCountText")) },
        )
        val videos = parseCount(
          runsText(node.obj("videoCountText")).ifBlank { simpleText(node.obj("videoCountText")) },
        )
        val desc = runsText(node.obj("descriptionSnippet")).ifBlank { simpleText(node.obj("descriptionSnippet")) }
        channels.add(
          YoutubeChannelSearchResult(
            channelId = id,
            name = title,
            avatarUrl = avatar,
            subscriberCount = subscribers,
            videoCount = videos,
            description = desc,
          ),
        )
      }
    }
    return YoutubeChannelSearchPage(items = channels, continuation = findContinuation(root))
  }

  /**
   * 播放列表详情首屏头部信息。两条路径（真机实测 2026-08-27，见 YtPlaylist 诊断日志）：
   * - 新布局（当前实际返回）：`header.pageHeaderRenderer.content.pageHeaderViewModel` —— 简介在
   *   `description.descriptionPreviewViewModel.description.content`，作者在 `metadata` rows 的
   *   avatarStack text（"by xxx"），视频数在同 rows 的文本（"N videos"），封面在 `heroImage`。
   * - 旧布局（对齐 NewPipe `PlaylistInfo` / LibreTube `PlaylistFragment`）：`header.playlistHeaderRenderer`，
   *   descriptionText/description、ownerText、numVideosText、primaryThumbnail。保留兜底。
   * 字段可能 {runs} 或 {simpleText} 两种形状。取不到任一 header 返回 null。
   */
  fun parsePlaylistHeader(root: JsonObject): YoutubePlaylistHeader? {
    parseLegacyPlaylistHeader(root)?.let { return it }
    val vm = root.obj("header")?.obj("pageHeaderRenderer")
      ?.obj("content")?.obj("pageHeaderViewModel") ?: return null
    val description = vm.obj("description")?.obj("descriptionPreviewViewModel")?.obj("description")?.let { node ->
      node.stringOrNull("content")?.ifBlank { null }
        ?: runsText(node).ifBlank { simpleText(node) }.ifBlank { null }
    }
    // metadata rows:作者在 avatarStack 的 text，视频数/播放量在普通文本 parts。
    // 实测 2026-08-27（真机 YtPlaylist 日志 + 诊断 dump）：text 节点是 viewModel 形状
    // {"content": "...", "commandRuns": [...]}，**必须先读 content**——runsText 只认 "runs"、
    // simpleText 只认 "simpleText"，都不认 content，旧写法 avatarText 恒空串（owner=""）/
    // 行文本恒空（count=null）。另：avatarStack 文本带语言前缀——英文 "by xxx"、简中
    // "创建者：xxx"、繁中 "建立者：xxx"——剥前缀留名字；视频数=含数字且含 video/视频/影片/
    // 動画 词的文本（排除 "130,265 views" 纯播放量行）。
    var owner: String? = null
    var count: String? = null
    vm.obj("metadata")?.obj("contentMetadataViewModel")?.array("metadataRows")?.forEach { rowEl ->
      (rowEl as? JsonObject)?.array("metadataParts")?.forEach { partEl ->
        val part = partEl as? JsonObject ?: return@forEach
        fun viewModelText(node: JsonObject?): String? {
          val content = node?.stringOrNull("content")?.trim().orEmpty()
          if (content.isNotBlank()) return content
          return node?.let { runsText(it).ifBlank { simpleText(it) } }
        }
        val avatarText = part.obj("avatarStack")?.obj("avatarStackViewModel")?.obj("text")
          ?.let(::viewModelText)
        val text = avatarText ?: part.obj("text")?.let(::viewModelText)
        val t = text?.trim() ?: return@forEach
        if (avatarText != null) {
          val stripped = t
            .substringAfterLast("创建者：").substringAfterLast("创建者:")
            .substringAfterLast("建立者：").substringAfterLast("建立者:")
            .removePrefix("by ").trim()
          owner = stripped.ifBlank { t }
        } else if (count == null && t.any { it.isDigit() } &&
          listOf("video", "视频", "影片", "動画").any { t.contains(it, ignoreCase = true) }
        ) {
          count = t
        }
      }
    }
    val cover = vm.obj("heroImage")?.let { firstImageUrl(it) }
    return YoutubePlaylistHeader(description = description, owner = owner, videoCountText = count, cover = cover)
  }

  /** 旧布局 `header.playlistHeaderRenderer`（部分老响应仍有），取不到返回 null。 */
  private fun parseLegacyPlaylistHeader(root: JsonObject): YoutubePlaylistHeader? {
    val phr = root.obj("header")?.obj("playlistHeaderRenderer") ?: return null
    val descNode = phr.obj("descriptionText") ?: phr.obj("description")
    val description = if (descNode != null) {
      runsText(descNode).ifBlank { simpleText(descNode) }.ifBlank { null }
    } else null
    val owner = runsText(phr.obj("ownerText")).ifBlank { simpleText(phr.obj("ownerText")) }.ifBlank { null }
    val count = runsText(phr.obj("numVideosText")).ifBlank { simpleText(phr.obj("numVideosText")) }.ifBlank { null }
    val cover = phr.obj("primaryThumbnail")?.array("thumbnails")?.let(::pickBestThumbnailUrl)
      ?: phr.obj("thumbnail")?.array("thumbnails")?.let(::pickBestThumbnailUrl)
    return YoutubePlaylistHeader(description = description, owner = owner, videoCountText = count, cover = cover)
  }

  /** 在任意节点里递归找第一个 image.sources[].url（pageHeaderViewModel.heroImage 等 view-model 形状用）。 */
  private fun firstImageUrl(node: JsonObject): String? {
    node.array("sources")?.forEach { src ->
      (src as? JsonObject)?.stringOrNull("url")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    for ((_, value) in node) {
      when (value) {
        is JsonObject -> firstImageUrl(value)?.let { return it }
        is JsonArray -> value.forEach { el -> (el as? JsonObject)?.let { firstImageUrl(it)?.let { u -> return u } } }
        else -> Unit
      }
    }
    return null
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
    val channelId = vd.stringOrNull("channelId").orEmpty()
    val viewCount = parseCount(vd.stringOrNull("viewCount"))
    // publishDate / uploadDate 任一存在即用(实测 /player 部分客户端缺 publishDate 但给 uploadDate)。
    val mf = playerJson.obj("microformat")?.obj("playerMicroformatRenderer")
    val publishedAt = listOfNotNull(mf?.stringOrNull("publishDate"), mf?.stringOrNull("uploadDate"))
      .firstNotNullOfOrNull { parsePublishDate(it) }
    // 点赞数主源取 /player microformat 的 likeCount(实测该字段存在,形如 "123456" 原始数字串)。
    // 相比另发 /next 取 videoActions 工具栏(那路径真机常取不到,致简介区点赞行缺失)更快更稳;
    // 取不到保持 null,由调用方 getVideoDetail 的 /next 兜底回写。
    val likeCount = mf?.stringOrNull("likeCount")?.let(::parseCount)
    return YoutubeVideoDetail(
      videoId = videoId,
      title = title,
      description = description,
      channelName = channelName,
      channelId = channelId,
      channelAvatarUrl = "",
      viewCount = viewCount,
      publishedAt = publishedAt,
      likeCount = likeCount,
    )
  }

  /**
   * 从 /next 响应解析视频点赞数（对齐 NewPipe YoutubeStreamExtractor.getLikeCount）。
   *
   * 点赞数不在 /player 的 videoDetails，而在 /next 首屏的
   * `contents.twoColumnWatchNextResults.results.results.contents[]`（带 videoPrimaryInfoRenderer 的那项）
   * 的 `videoActions.menuRenderer.topLevelButtons`。优先新布局 segmentedLikeDislikeButtonViewModel
   * （buttonViewModel.accessibilityText），回退旧布局 segmentedLikeDislikeButtonRenderer
   * （toggleButtonRenderer 的 accessibilityData/accessibility/defaultText 三处 label）。
   * 两种都取不到返回 null（UI 不显示点赞行）。label/accessibilityText 形如 "1,234 likes" / "1.2M likes"。
   */
  fun parseLikeCount(root: JsonObject): Long? {
    val contents = root.obj("contents")
      ?.obj("twoColumnWatchNextResults")?.obj("results")?.obj("results")
      ?.array("contents")
      ?: return null
    val primary = contents.firstNotNullOfOrNull { (it as? JsonObject)?.obj("videoPrimaryInfoRenderer") }
      ?: return null
    val topButtons = primary.obj("videoActions")?.obj("menuRenderer")?.array("topLevelButtons")
      ?: return null
    for (element in topButtons) {
      val button = element as? JsonObject ?: continue
      // 新布局：segmentedLikeDislikeButtonViewModel 里 accessibilityText 带计数。
      val buttonViewModel = button.obj("segmentedLikeDislikeButtonViewModel")
        ?.obj("likeButtonViewModel")?.obj("likeButtonViewModel")
        ?.obj("toggleButtonViewModel")?.obj("toggleButtonViewModel")
        ?.obj("defaultButtonViewModel")?.obj("buttonViewModel")
      buttonViewModel?.stringOrNull("accessibilityText")?.let { parseCount(it)?.let { c -> if (c >= 0) return c } }
      // 旧布局：segmentedLikeDislikeButtonRenderer.likeButton.toggleButtonRenderer 三处 label 兜底。
      val likeToggle = button.obj("segmentedLikeDislikeButtonRenderer")
        ?.obj("likeButton")?.obj("toggleButtonRenderer") ?: continue
      val label = likeToggle.obj("accessibilityData")?.obj("accessibilityData")?.stringOrNull("label")
        ?: likeToggle.obj("accessibility")?.stringOrNull("label")
        ?: likeToggle.obj("defaultText")?.obj("accessibility")?.obj("accessibilityData")?.stringOrNull("label")
      if (!label.isNullOrBlank()) parseCount(label)?.let { c -> if (c >= 0) return c }
    }
    return null
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
    // 新布局(EUVM)：评论在 onResponseReceivedEndpoints[last].reloadContinuationItemsCommand/
    // appendContinuationItemsAction.continuationItems，每项 commentThreadRenderer → commentViewModel，
    // 实际数据在 frameworkUpdates.entityBatchUpdate.mutations（按 commentKey/toolbarStateKey 匹配 entityKey）。
    // 只从 continuationItems 解析（对齐 NewPipe），避免扫全树把 engagementPanels 等无 mutations 的
    // commentThreadRenderer 也收进来（那些会解析出空作者/内容）。
    val mutations = root.obj("frameworkUpdates")
      ?.obj("entityBatchUpdate")
      ?.array("mutations")
    val items = commentContinuationItems(root)
    if (items != null) {
      // 去掉末尾的 continuationItemRenderer（下一页 token）。
      val commentItems = if ((items.lastOrNull() as? JsonObject)?.obj("continuationItemRenderer") != null) {
        items.dropLast(1)
      } else {
        items
      }
      for (item in commentItems) {
        val thread = (item as? JsonObject)?.obj("commentThreadRenderer") ?: continue
        parseCommentThread(thread, mutations)?.let { comments.add(it) }
      }
    }
    // 旧布局：commentSectionRenderer → commentRenderer。
    if (comments.isEmpty()) {
      collectByKey(root, KEY_COMMENT_SECTION_RENDERER) { section ->
        collectByKey(section, KEY_COMMENT_RENDERER) { node ->
          parseCommentRenderer(node)?.let { comments.add(it) }
        }
      }
    }
    // 防御：无容器时回退全根收集。
    if (comments.isEmpty()) {
      collectByKey(root, KEY_COMMENT_RENDERER) { node ->
        parseCommentRenderer(node)?.let { comments.add(it) }
      }
    }
    // 续页 token：评论续页响应取 reloadContinuationItemsCommand.continuationItems 里最后一个
    // continuationItemRenderer（对齐 NewPipe 取末尾，避免取到楼中楼 replies 的 token）。
    val token = commentPageContinuation(root) ?: findContinuation(root)
    return YoutubeCommentPage(items = comments, continuation = token)
  }

  /** 评论续页响应容器：onResponseReceivedEndpoints 里最后一个 reloadContinuationItemsCommand / appendContinuationItemsAction 的 continuationItems。 */
  private fun commentContinuationItems(root: JsonObject): JsonArray? {
    val endpoints = root["onResponseReceivedEndpoints"] as? JsonArray ?: return null
    for (i in endpoints.indices.reversed()) {
      val obj = endpoints[i] as? JsonObject ?: continue
      obj.obj("reloadContinuationItemsCommand")?.array("continuationItems")?.let { return it }
      obj.obj("appendContinuationItemsAction")?.array("continuationItems")?.let { return it }
    }
    return null
  }

  /**
   * 解析一条新布局(EUVM)评论线程。commentThreadRenderer 里只有 commentViewModel(commentKey/
   * toolbarStateKey/commentId)，作者/内容/点赞等实体数据在 mutations 里按 entityKey 匹配。
   * 兼容旧布局：thread.comment.commentRenderer。
   */
  private fun parseCommentThread(thread: JsonObject, mutations: JsonArray?): YoutubeComment? {
    val vm = thread.obj("commentViewModel")?.obj("commentViewModel")
    if (vm != null) {
      val commentKey = vm.stringOrNull("commentKey")
      val toolbarStateKey = vm.stringOrNull("toolbarStateKey")
      // mutations 的 payload 是包装对象：评论实体包在 commentEntityPayload 里，
      // 工具栏状态包在 engagementToolbarStateEntityPayload 里，需先解包再取字段。
      val entity = mutations?.let { findMutationPayload(it, commentKey) }?.obj("commentEntityPayload")
      val toolbar = mutations?.let { findMutationPayload(it, toolbarStateKey) }?.obj("engagementToolbarStateEntityPayload")
      val author = entity?.obj("author")
      val properties = entity?.obj("properties")
      val toolbarObj = entity?.obj("toolbar")
      val replies = thread.obj("replies")?.obj("commentRepliesRenderer")
      val id = properties?.stringOrNull("commentId") ?: vm.stringOrNull("commentId") ?: return null
      return YoutubeComment(
        commentId = id,
        authorName = author?.stringOrNull("displayName").orEmpty(),
        authorAvatarUrl = author?.stringOrNull("avatarThumbnailUrl")
          ?: entity?.obj("avatar")?.obj("image")?.array("sources")?.let(::pickBestThumbnailUrl).orEmpty(),
        content = attributedText(properties?.get("content")),
        likeCount = parseCount(toolbarObj?.stringOrNull("likeCountNotliked")?.trim()),
        publishedAt = parsePublished(
          properties?.stringOrNull("publishedTime"),
          liveNow = false,
          isUpcoming = false,
        ),
        verified = author?.booleanOrNull("isVerified") == true || author?.booleanOrNull("isArtist") == true,
        pinned = vm.obj("pinnedText") != null,
        hearted = toolbar?.stringOrNull("heartState") == "TOOLBAR_HEART_STATE_HEARTED",
        replyCount = toolbarObj?.stringOrNull("replyCount")?.trim()?.toIntOrNull() ?: 0,
        repliesPage = replies?.array("contents")?.let { firstContinuationToken(it) },
        channelOwner = author?.booleanOrNull("isCreator") == true,
        creatorReplied = replies?.obj("viewRepliesCreatorThumbnail") != null,
      )
    }
    // 旧布局：comment.commentRenderer。
    return thread.obj("comment")?.obj(KEY_COMMENT_RENDERER)?.let { parseCommentRenderer(it) }
  }

  /** 诊断:dump 第一条评论的 entity payload 原始结构,确认 commentEntityPayload 字段。 */
  fun dumpCommentEntity(root: JsonObject) {
    val mutations = root.obj("frameworkUpdates")?.obj("entityBatchUpdate")?.array("mutations")
    Log.d("YoutubeComment", "dumpCommentEntity mutations=${mutations?.size}")
    mutations?.firstOrNull()?.let { m ->
      Log.d("YoutubeComment", "dumpCommentEntity firstMutation entityKey=${(m as? JsonObject)?.stringOrNull("entityKey")?.take(30)} payload=${(m as? JsonObject)?.obj("payload")?.toString()?.take(1200)}")
    }
    collectByKey(root, KEY_COMMENT_THREAD_RENDERER) { thread ->
      val vm = thread.obj("commentViewModel")?.obj("commentViewModel") ?: return@collectByKey
      val key = vm.stringOrNull("commentKey")
      val entity = mutations?.let { findMutationPayload(it, key) }
      Log.d("YoutubeComment", "dumpCommentEntity commentKey=${key?.take(20)} entity=${entity?.toString()?.take(1500)}")
      return@collectByKey
    }
  }

  /** 在 mutations 数组里按 entityKey 匹配，返回 payload。 */
  private fun findMutationPayload(mutations: JsonArray, key: String?): JsonObject? {
    if (key.isNullOrBlank()) return null
    for (m in mutations) {
      val obj = m as? JsonObject ?: continue
      if (obj.stringOrNull("entityKey") == key) return obj.obj("payload")
    }
    return null
  }

  /** 解析 attributed description(properties.content)：可能是 {content:"text"} 或 {runs:[...]} 或纯字符串。 */
  private fun attributedText(obj: JsonElement?): String {
    if (obj == null) return ""
    if (obj is JsonPrimitive) return obj.contentOrNull.orEmpty()
    val o = obj as? JsonObject ?: return ""
    o.stringOrNull("content")?.let { if (it.isNotBlank()) return it }
    runsText(o).let { if (it.isNotBlank()) return it }
    return o.stringOrNull("simpleText").orEmpty()
  }

  /** 评论续页 token：reloadContinuationItemsCommand.continuationItems 里最后一个 continuationItemRenderer（对齐 NewPipe 取末尾）。 */
  private fun commentPageContinuation(root: JsonObject): String? {
    val endpoints = root["onResponseReceivedEndpoints"] as? JsonArray ?: return null
    for (i in endpoints.indices.reversed()) {
      val obj = endpoints[i] as? JsonObject ?: continue
      val items = obj.obj("reloadContinuationItemsCommand")?.array("continuationItems")
        ?: obj.obj("appendContinuationItemsAction")?.array("continuationItems")
        ?: continue
      for (j in items.indices.reversed()) {
        val node = items[j] as? JsonObject ?: continue
        val token = node.obj("continuationItemRenderer")
          ?.obj("continuationEndpoint")?.obj("continuationCommand")?.stringOrNull("token")
          ?: node.obj("continuationItemRenderer")?.obj("button")?.obj("buttonRenderer")?.obj("command")
            ?.obj("continuationCommand")?.stringOrNull("token")
        if (!token.isNullOrBlank()) return token
      }
    }
    return null
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
    // YouTube publishDate/uploadDate 是 ISO-8601,如 "2023-01-15T00:00:00-08:00" 或 "2023-01-15"。
    // 只取日期部分(前 10 字符 yyyy-MM-dd)再切,否则 split('-') 会吃到 T 和冒号返回 null。
    val d = date.trim().take(10)
    val parts = d.split('-').mapNotNull { it.toIntOrNull() }
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
    // 诊断:dump lockupViewModel 的 metadataRows badges(会员角标真实位置),定位会员专属视频结构。
    val lockupBadges = node.obj("metadata")
      ?.obj("lockupMetadataViewModel")
      ?.obj("metadata")
      ?.obj("contentMetadataViewModel")
      ?.array("metadataRows")
    if (lockupBadges != null) {
      Log.d("YtBadge", "lockup videoId=$videoId metadataRows=${lockupBadges.toString().take(600)}")
    } else {
      Log.d("YtBadge", "lockup videoId=$videoId metadataRows=null topKeys=${node.keys.joinToString(",")}")
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

    // metadata 里的文本:播放量 / 发布时间 / 角标。只取 contentMetadataViewModel.metadataRows 里的
    // text.content(对齐注释里的真实结构),不再 collectStrings 全树扫——全树扫会把标题/缩略图尺寸/
    // trackingParams 里的数字也收进来,firstNotNullOfOrNull 可能误取成播放量(实测最热排序播放数被污染)。
    val metaTexts = mutableListOf<String>()
    lockupBadges?.forEach { row ->
      (row as? JsonObject)?.array("metadataParts")?.forEach { part ->
        (part as? JsonObject)?.obj("text")?.stringOrNull("content")?.let { metaTexts.add(it) }
      }
    }
    val viewCount = metaTexts.firstNotNullOfOrNull { parseCount(it) }
    val publishedAt = metaTexts.firstNotNullOfOrNull { parsePublished(it, liveNow, isUpcoming) }
    // 诊断:确认最热排序(英文 lockup)解析出的播放量/时间。
    Log.d("YtBadge", "lockup videoId=$videoId viewCount=$viewCount publishedAt=$publishedAt " +
      "metaTexts=$metaTexts")
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

    // 诊断:打印 videoRenderer 的 viewCountText/publishedTimeText 原始结构,定位"最热"排序下
    // 播放数/发布时间解析失败(返回 videoRenderer 而非 lockup 时,旧日志看不到这两字段结构)。
    val vct = node.obj("viewCountText")
    val ptt = node.obj("publishedTimeText")
    Log.d(
      "YtBadge",
      "videoRenderer videoId=$videoId viewCountText=${vct?.toString().orEmpty().take(200)} " +
        "publishedTimeText=${ptt?.toString().orEmpty().take(200)}",
    )

    val viewCount = parseCount(vct?.stringOrNull("simpleText"))

    val publishedAt = parsePublished(
      ptt?.stringOrNull("simpleText"),
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
   * 命中即过滤,避免展示无法播放的视频。两种 renderer 结构不同:
   *  - videoRenderer:顶层 `badges[].metadataBadgeRenderer.style == BADGE_STYLE_TYPE_MEMBERS_ONLY`
   *  - lockupViewModel(频道页新格式):角标嵌套在
   *    `metadata.lockupMetadataViewModel.metadata.contentMetadataViewModel.metadataRows[].badges[].badgeViewModel`,
   *    `badgeStyle == BADGE_MEMBERS_ONLY`(实测 2026-08,对齐 NewPipe PR #1503)。
   */
  private fun isMembersOnly(node: JsonObject): Boolean {
    // videoRenderer 旧格式:顶层 badges。
    if (node.array("badges")?.any {
        (it as? JsonObject)?.obj("metadataBadgeRenderer")?.stringOrNull("style") == "BADGE_STYLE_TYPE_MEMBERS_ONLY"
      } == true
    ) return true
    // lockupViewModel 新格式:metadataRows[].badges[].badgeViewModel.badgeStyle。
    return node.obj("metadata")
      ?.obj("lockupMetadataViewModel")
      ?.obj("metadata")
      ?.obj("contentMetadataViewModel")
      ?.array("metadataRows")
      ?.any { row ->
        (row as? JsonObject)?.array("badges")?.any { badge ->
          (badge as? JsonObject)?.obj("badgeViewModel")?.stringOrNull("badgeStyle") == "BADGE_MEMBERS_ONLY"
        } == true
      } == true
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

  /**
   * 解析播放数/观看数。英文 "1,234,567 views" / "1.2M views" / "No views" / "1.4K watching"，
   * 中文/繁体 "觀看次數：49萬次" / "观看次数:49万次" / "1.2亿次播放"（频道页 lockupViewModel
   * 实测返回繁体 zh-TW 文案）。
   */
  private fun parseCount(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    // 拒绝时间类文本:parseLockupViewModel 用 collectStrings 全树扫 metadata,发布时间
    // ("11 days ago"/"5 個月前"/"13:09")也会混进 metaTexts;不拦的话 "11 days ago" 会被
    // 误解析成 11 当作播放量。含 ago/前(相对时间)或冒号(时长)的一律不算播放量。
    if (text.contains(" ago", ignoreCase = true) || text.contains("前", ignoreCase = true) || text.contains(':')) {
      return null
    }
    // 先把说明性前缀/后缀与全角符号剥掉，只留"数字+单位"。
    val cleaned = text
      .replace(",", "")
      .replace(" views", "", ignoreCase = true)
      .replace(" likes", "", ignoreCase = true)
      .replace(" watching", "", ignoreCase = true)
      .replace("觀看次數", "", ignoreCase = true)
      .replace("观看次数", "", ignoreCase = true)
      .replace("次观看", "", ignoreCase = true)
      .replace("次播放", "", ignoreCase = true)
      .replace("次點讚", "", ignoreCase = true)
      .replace("次点赞", "", ignoreCase = true)
      .replace("次赞", "", ignoreCase = true)
      .replace("次", "", ignoreCase = true)
      .replace("：", "")
      .replace(":", "")
      .trim()
    if (cleaned.startsWith("No views", ignoreCase = true) || cleaned.equals("No", ignoreCase = true)) return 0L
    val multiplier = when {
      cleaned.contains("亿") -> 100_000_000L
      cleaned.contains("萬") || cleaned.contains("万") -> 10_000L
      cleaned.endsWith("M", ignoreCase = true) -> 1_000_000L
      cleaned.endsWith("K", ignoreCase = true) -> 1_000L
      cleaned.endsWith("B", ignoreCase = true) -> 1_000_000_000L
      else -> 1L
    }
    val numberPart = cleaned
      .replace("亿", "")
      .replace("萬", "")
      .replace("万", "")
      .substringBefore(' ')
      .trimEnd('M', 'm', 'K', 'k', 'B', 'b')
    val number = numberPart.toDoubleOrNull() ?: return null
    return (number * multiplier).toLong()
  }

  /**
   * 把相对时间文案转成 epoch 秒。InnerTube 返回 "3 hours ago"/"1 day ago"/"5 weeks ago"，
   * 频道页 lockupViewModel 返回繁体 "5 個月前"/"2 週前"/"1 年前"（实测 zh-TW）。无法精确定位，
   * 按当前时间反推近似值。live/upcoming 返回 null。
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

  /**
   * 相对时间解析,覆盖 [YoutubeContentRegion] 全部 hl(en/ja/zh-Hans/zh-Hant/ko/de):
   * en "5 weeks ago" / 简繁 "5 週前"/"3 小时前"/"1 个月前" / ja "3時間前"/"1か月前" /
   * ko "3분 전"/"3시간 전" / de "vor 2 Tagen"。
   *
   * 旧实现只认 `(\d+)\s*([秒分時时天周週月年]+)前`,zh-Hant 的 小時/分鐘/個月、ja 的
   * 時間/か月、ko/de 全系全解析失败 → publishedAt=null → 动态页 pubdate 回落当前时间,
   * 整页「1 分钟前」+ 排序全乱(真机 2026-09-05 日志实锤:HK 节点「3 個月前」「14 小時前」
   * 全 null,「1 年前」「2 週前」正常)。改为:提取「数字 + 其后非数字片段」,再按单位
   * 关键词归一到英文单位,contains 匹配对语言后缀(週間/Tagen/전等)天然免疫。
   */
  private fun parseRelative(text: String): Pair<Long, String>? {
    val m = Regex("""(\d+)\s*([^\d]+)""").find(text) ?: return null
    val amount = m.groupValues[1].toLongOrNull() ?: return null
    val blob = m.groupValues[2].lowercase()
    val unit = when {
      // second:en second(s) / 简繁 秒 / de Sekunde(n) / ko 초
      "second" in blob || "秒" in blob || "sekund" in blob || "초" in blob -> "second"
      // hour:en hour / 简繁 小时·小時 / ja 時間 / ko 시간 / de Stunde(n)
      "hour" in blob || "小時" in blob || "小时" in blob || "時間" in blob ||
        "时间" in blob || "시간" in blob || "stund" in blob -> "hour"
      // minute:en minute(s)·min / 简繁 分钟·分鐘 / ja 分 / ko 분
      "minut" in blob || "min" in blob || "分钟" in blob || "分鐘" in blob ||
        "分" in blob || "분" in blob -> "minute"
      // day:en day / 简繁 天 / ja 日 / ko 일 / de Tag(en)
      "day" in blob || "天" in blob || "日" in blob || "일" in blob || "tag" in blob -> "day"
      // week:en week / 简繁 周·週(含 週間) / ko 주(간) / de Woche(n)
      "week" in blob || "周" in blob || "週" in blob || "주" in blob || "woch" in blob -> "week"
      // month:en month / 简繁 个月·個月 / ja か月·ヶ月 / ko 개월 / de Monat(en)
      "month" in blob || "個月" in blob || "个月" in blob || "か月" in blob ||
        "ヶ月" in blob || "개월" in blob || "monat" in blob || "달" in blob ||
        "月" in blob -> "month"
      // year:en year / 简繁 年 / ko 년 / de Jahr(en)
      "year" in blob || "年" in blob || "년" in blob || "jahr" in blob -> "year"
      else -> return null
    }
    return amount to unit
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
  fun findContinuation(root: JsonObject): String? {
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
  private const val KEY_SHORTS_LOCKUP_VIEW_MODEL = "shortsLockupViewModel"
  private const val KEY_PLAYLIST_RENDERER = "playlistRenderer"
  private const val KEY_PLAYLIST_VIDEO_RENDERER = "playlistVideoRenderer"
  private const val KEY_REEL_ITEM_RENDERER = "reelItemRenderer"
  private const val KEY_CONTINUATION_ITEM_RENDERER = "continuationItemRenderer"
  private const val KEY_CHANNEL_RENDERER = "channelRenderer"
  private const val KEY_TAB_RENDERER = "tabRenderer"
  private const val KEY_EXPANDABLE_TAB_RENDERER = "expandableTabRenderer"
  private const val KEY_COMMENT_RENDERER = "commentRenderer"
  private const val KEY_COMMENT_SECTION_RENDERER = "commentSectionRenderer"
  private const val KEY_COMMENT_THREAD_RENDERER = "commentThreadRenderer"
}

/**
 * 播放列表详情首屏头部（[YoutubeParsers.parsePlaylistHeader]）的解析结果。字段缺省时用 null（UI 隐藏对应行）。
 * 顶层公开类（不在 internal 的 [YoutubeParsers] 内），供 public 的 [YoutubeRepository.YoutubeVideoPage] 引用。
 */
data class YoutubePlaylistHeader(
  val description: String?,
  /** ownerText，如 "@FollowCnRules"；无则 null。 */
  val owner: String?,
  /** numVideosText，如 "20 videos"；无则 null。 */
  val videoCountText: String?,
  /** 封面 URL；无则 null。 */
  val cover: String?,
)
