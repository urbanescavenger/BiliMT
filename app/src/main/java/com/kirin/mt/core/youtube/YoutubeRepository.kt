package com.kirin.mt.core.youtube

import android.os.SystemClock
import android.util.Log
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random
import org.schabi.newpipe.extractor.stream.StreamInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 已映射成 [VideoSummary] 的一页 YouTube 内容，带续页 token。 */
data class YoutubeVideoPage(
  val items: List<VideoSummary>,
  val continuation: String?,
  /** 播放列表详情首屏头部(playlistHeaderRenderer 的简介/作者/视频数/封面)；普通视频 feed 恒 null。 */
  val playlistHeader: YoutubePlaylistHeader? = null,
)

/** 频道"播放列表"Tab 的一页播放列表卡，带续页 token。internal 因含 [YoutubeParsers.YoutubePlaylist]。 */
internal data class YoutubePlaylistsPage(
  val items: List<YoutubeParsers.YoutubePlaylist>,
  val continuation: String?,
)

/** 订阅流逐频道拉取的并发上限。对齐 LibreTube CHANNEL_CHUNK_SIZE=5：批次串行、每批 ≤5 频道，
 * 并发 5 让整批 1 轮 RTT 跑完（旧 4 会让每批 5 个频道空转一轮排队）。峰值并发仍由 ChunkSize 限到 5。 */
const val YoutubeMaxConcurrentChannelFetches = 5

/** RSS 订阅流并发上限。RSS 是轻量 GET、无 InnerTube 风控，可放宽到 8。 */
const val YoutubeMaxConcurrentRssFetches = 8

/**
 * YouTube 内容门面，供 [com.kirin.mt.core.network.VideoRepository] 转发。
 * 只暴露"搜索 / 热门 / 频道视频"元数据接口；播放流解析（InnerTube /player + PO token）
 * 属 Phase 2，另行实现。
 */
class YoutubeRepository(
  private val client: InnerTubeClient,
) {

  /** P11-201 频道页 TV 画质角标缓存(按 channelId),见 [tvQualityBadges]。 */
  private val tvQualityCaches = LinkedHashMap<String, TvQualityCache>()

  /** 同一时刻只让一条频道页去翻 TV 页(翻页是串行的,并发进来只会白翻)。 */
  private val tvQualityGate = Semaphore(1)

  /**
   * 频道排序 chip token 缓存(channelId → 铸造时间 + 各排序 token),见 [getChannelVideosOrdered]。
   * 命中即省掉「为摘 token 而多拉一次最新页」那条请求。
   */
  private val channelOrderTokenCaches =
    LinkedHashMap<String, Pair<Long, Map<YoutubeConstants.ChannelVideoOrder, String>>>()

  /** 搜索，返回原始模型。@param params 排序/筛选参数串，见 [YoutubeSearchParams]。 */
  suspend fun search(
    query: String,
    params: String = YoutubeSearchParams.Relevance,
    continuation: String? = null,
  ): YoutubeFeedPage {
    val payload = buildJsonObject {
      if (continuation != null) {
        put("continuation", continuation)
      } else {
        put("query", query)
        if (params.isNotBlank()) put("params", params)
      }
    }
    val root = client.postJson("/search", payload)
    val feed = YoutubeParsers.parseFeedPage(root)
    // 诊断(搜索续页静默无结果时定位):首屏/续页都在这一条日志里 —— items 数、是否带 token、
    // 用的哪种排序 params。两端搜索都走这里(TV + 移动端)。
    Log.i(
      "YtSearch",
      "video search continuation=${continuation != null} params=${params.take(16)} " +
        "items=${feed.items.size} nextToken=${if (feed.continuation != null) "yes" else "null"}",
    )
    return feed
  }

  /** 频道搜索（params=TypeChannel），返回原始频道模型 + 续页 token。 */
  suspend fun searchChannels(
    query: String,
    continuation: String? = null,
  ): YoutubeChannelSearchPage {
    val payload = buildJsonObject {
      if (continuation != null) {
        put("continuation", continuation)
      } else {
        put("query", query)
        put("params", YoutubeSearchParams.TypeChannel)
      }
    }
    val page = client.postJson("/search", payload).let(YoutubeParsers::parseChannelSearchPage)
    Log.i(
      "YtSearch",
      "channel search continuation=${continuation != null} items=${page.items.size} " +
        "nextToken=${if (page.continuation != null) "yes" else "null"}",
    )
    return page
  }

  /** 热门(趋势)，返回映射后的卡片。 */
  suspend fun getTrending(tab: YoutubeConstants.TrendingTab): List<VideoSummary> {
    val payload = buildJsonObject {
      put("browseId", tab.browseId)
      tab.params?.let { put("params", it) }
    }
    val feed = client.postJson("/browse", payload).let(YoutubeParsers::parseFeedPage)
    return feed.items.map(::toVideoSummary)
  }

  /**
   * 把用户输入解析成可持久化的 [YoutubeChannel]。接受 `UC...` 频道 ID、`@handle`、频道名或完整 URL。
   *
   * 实测关键：`/browse` 只接受 `UC...` 频道 ID；`@handle` 做 browseId 会 400。
   * 所以：UC ID 走 `/browse`；handle / 频道名走 `/search` 收集 `channelRenderer`。
   * 解析失败（非频道页/无匹配频道/网络异常）抛 [YoutubeApiException]。
   */
  suspend fun resolveChannel(input: String): YoutubeChannel {
    val query = normalizeChannelInput(input)
    if (query.isBlank()) {
      throw YoutubeApiException(statusCode = 0, responseBody = "", message = "empty channel input")
    }
    return if (query.matches(ChannelIdRegex)) {
      // UC... 频道 ID：/browse 直接拉频道页。
      val payload = buildJsonObject { put("browseId", query) }
      val root = client.postJson("/browse", payload)
      val resolved = YoutubeParsers.parseChannelInfo(root)
      val channelId = resolved?.channelId?.takeIf { it.isNotBlank() } ?: query
      val name = resolved?.name?.takeIf { it.isNotBlank() } ?: query
      val avatar = resolved?.avatarUrl.orEmpty()
      YoutubeChannel(channelId = channelId, name = name, avatar = avatar)
    } else {
      // handle / 频道名：/search 找 channelRenderer。
      val payload = buildJsonObject { put("query", query) }
      val root = client.postJson("/search", payload)
      val candidates = YoutubeParsers.parseChannelCandidates(root)
      val match = pickChannelCandidate(candidates, query)
        ?: throw YoutubeApiException(statusCode = 0, responseBody = "", message = "channel not found: $query")
      YoutubeChannel(channelId = match.first, name = match.second.ifBlank { query })
    }
  }

  /**
   * 频道页头部全量信息（名称/头像/订阅数/banner/简介/认证）。UC 频道 ID 走 /browse，
   * 返回 [YoutubeParsers.ChannelInfo]；解析失败或非 UC ID 返回 null（调用方回退 request 值）。
   */
  suspend internal fun getChannelHeader(channelId: String): YoutubeParsers.ChannelInfo? {
    if (!channelId.matches(ChannelIdRegex)) return null
    return runCatching {
      val payload = buildJsonObject { put("browseId", channelId) }
      YoutubeParsers.parseChannelInfo(client.postJson("/browse", payload))
    }.getOrNull()
  }

  /**
   * 频道内容 Tab 的服务端 params（小写标识 → params）。与 [getChannelHeader] 独立：
   * 即使头部 info 解析失败（getChannelHeader 返 null），仍要拿到 tab params 才能切
   * Shorts/直播/播放列表。UC 频道 ID 走 /browse；失败或非 UC ID 返回空 map。
   */
  suspend internal fun getChannelTabs(channelId: String): Map<String, String> {
    if (!channelId.matches(ChannelIdRegex)) return emptyMap()
    return runCatching {
      val payload = buildJsonObject { put("browseId", channelId) }
      YoutubeParsers.parseChannelTabs(client.postJson("/browse", payload))
        .map { it.name.lowercase() to it.params }
        .toMap()
    }.getOrDefault(emptyMap())
  }

  /**
   * 从搜索候选里挑最佳匹配：优先名称精确匹配(忽略大小写)，
   * 其次第一个含 query 的候选，最后退回到首个候选。实测对 `@handle` 搜索首条即目标频道。
   */
  private fun pickChannelCandidate(
    candidates: List<Pair<String, String>>,
    query: String,
  ): Pair<String, String>? {
    if (candidates.isEmpty()) return null
    val lower = query.lowercase()
    candidates.firstOrNull { (_, name) -> name.equals(query, ignoreCase = true) }?.let { return it }
    candidates.firstOrNull { (_, name) -> name.lowercase().contains(lower) }?.let { return it }
    return candidates.first()
  }

  /** 归一化频道输入：去掉 URL 前缀(/channel/ / @handle)、尾部斜杠、头部 @。 */
  private fun normalizeChannelInput(input: String): String {
    var value = input.trim()
    for (prefix in listOf(
      "https://www.youtube.com/channel/", "http://www.youtube.com/channel/", "www.youtube.com/channel/", "youtube.com/channel/",
      "https://www.youtube.com/", "http://www.youtube.com/", "https://youtube.com/", "www.youtube.com/", "youtube.com/",
    )) {
      if (value.startsWith(prefix)) {
        value = value.removePrefix(prefix)
        break
      }
    }
    value = value.trimEnd('/')
    return value.removePrefix("@")
  }

  private companion object {
    val ChannelIdRegex = Regex("""UC[0-9A-Za-z_-]{22}""")

    /** 分批并发拉取：每批频道数（对齐 LibreTube CHANNEL_CHUNK_SIZE=5）。 */
    const val ChunkSize = 5

    /** 防节流：每累计这么多个频道暂停一次（对齐 LibreTube CHANNEL_BATCH_SIZE=50）。 */
    const val BatchSize = 50

    /** 防节流随机暂停范围(ms)（对齐 LibreTube CHANNEL_BATCH_DELAY=500..1500）。 */
    val BatchDelayMs = 500L..1500L

    /** 频道"视频"tab 的首屏 params——只有走这条的才是视频 tab、才补拉 TV 画质角标
     *  (排序不由 params 表达,三档共用这一个首屏 params,见 [YoutubeConstants.ChannelVideoOrder])。 */
    val ChannelVideoTabParams = setOf(YoutubeConstants.ChannelVideosParams)

    /** TV 画质角标缓存有效期:过期重拉基页(分页 token 会失效,不能长期存)。 */
    const val TvQualityCacheTtlMs = 10 * 60 * 1000L

    /** 单次调用最多翻几页 TV(防御:token 异常时别空转刷请求)。 */
    const val TvQualityMaxPagesPerCall = 4

    /**
     * 基页退化重取的覆盖率下限:在显示的视频被覆盖的比例低于它,就判定这次基页不完整
     * (实测退化场只覆盖 6/30),再取一次基页。正常场基页覆盖 26/30 以上 ⇒ 不触发。
     */
    const val TvQualityCoverageFloor = 0.5

    /** 最多缓存几个频道的 TV 角标(超出按最久未更新淘汰)。 */
    const val TvQualityCacheMaxChannels = 4

    /** 排序 chip token 缓存有效期(同 TV 角标:token 里有随机 targetId,别长期存)。 */
    const val ChannelOrderTokenTtlMs = 10 * 60 * 1000L

    /** 最多缓存几个频道的排序 chip token。 */
    const val ChannelOrderTokenMaxChannels = 8
  }

  /**
   * 频道"视频"tab 的一页视频，返回映射后的卡片 + 续页 token。
   * [params] 只选**哪个 tab**（视频 tab 恒为 [YoutubeConstants.ChannelVideosParams]），**不再表达排序**
   * ——排序（最新/最热/最早）由 [getChannelVideosOrdered] 摘服务端 chip token 再做，翻页 continuation
   * 与排序无关（排序已烘进 token）。
   * [browseId] 非空时覆盖 [channelId] 作为 browseId 且不发 params。Shorts/直播统一用
   * channelId + 服务端 tab params（系统播放列表 UUSH/UULV 实测 /browse 返回 400，已废弃）。
   */
  suspend fun getChannelVideos(
    channelId: String,
    continuation: String? = null,
    params: String = YoutubeConstants.ChannelVideosParams,
    browseId: String? = null,
    // 是否补拉 TV 客户端那一路画质角标(见下方注释)。频道页传 true;播放器 UP 面板等
    // 播放路径传 false——那条路径刻意不加请求,也不让面板等一个更大的响应。
    withQualityBadges: Boolean = true,
  ): YoutubeVideoPage = coroutineScope {
    val payload = buildJsonObject {
      if (continuation != null) {
        put("continuation", continuation)
      } else {
        put("browseId", browseId ?: channelId)
        if (browseId == null) put("params", params)
      }
    }
    // 画质角标(4K/8K)只在 TV 客户端的数据里,频道页 WEB 响应没有 → 额外拉一路 TVHTML5 频道页,
    // 按 videoId 并回(见 YoutubeParsers.parseChannelTilePage)。只在「视频 tab」发这一路
    // (Shorts/直播/播放列表不需要);TV 客户端无视排序 params,最新/最热/最早返回同一份频道页,故三档
    // 排序共用。首屏这一路基页与 WEB 请求**并行**;更早的视频靠沿 TV 上传列表继续翻页补
    // ([tvQualityBadges],缓存里按需翻)。失败静默降级为无角标(不影响列表)。
    // 显式标泛型:`if (…) async {…} else null` 会让 Kotlin 从 null 那一支把类型定成 Nothing?。
    val qualityVideoTab = withQualityBadges && browseId == null && params in ChannelVideoTabParams
    val tvBaseDeferred: Deferred<YoutubeParsers.TvTilePage?>? =
      if (qualityVideoTab && continuation == null) {
        async { fetchTvTilePage(channelId, continuation = null) }
      } else {
        null
      }
    val root = client.postJson("/browse", payload)
    val feed = YoutubeParsers.parseFeedPage(root)
    // 诊断:频道视频 0 条时,打印 channelId + 空响应根因(alert / 缺 contents)。排除「频道不存在」/
    // 风控空响应 vs 真实无视频 两分支,定位 TV 头像进频道全空问题。
    if (feed.items.isEmpty() && continuation == null) {
      val reason = YoutubeParsers.diagnosticEmptyReason(root)
      Log.w(
        "YoutubeChannel",
        "getChannelVideos EMPTY channelId=[$channelId] ${if (continuation == null) "first" else "next"} " +
          "reason=${reason ?: "no-reason(parse ok,真无视频)"} " +
          "shape=${YoutubeParsers.diagnosticFeedShape(root)}",
      )
    } else {
      Log.d(
        "YoutubeChannel",
        "getChannelVideos channelId=$channelId ${if (continuation == null) "first" else "next"} " +
          "params=${if (continuation == null) params else "continuation"} " +
          "browseId=${browseId ?: "channel"} items=${feed.items.size} next=${feed.continuation?.take(12) ?: "null"}",
      )
    }
    val qualityBadges = if (qualityVideoTab) {
      tvQualityBadges(
        channelId = channelId,
        webVideoIds = feed.items.map { it.videoId },
        basePage = tvBaseDeferred,
      )
    } else {
      emptyMap()
    }
    val items = if (qualityBadges.isEmpty()) {
      feed.items
    } else {
      // 已有角标(会员/直播/首映)优先保留,画质角标只补空位——会员专属是功能性标记,不能被 4K 顶掉。
      feed.items.map { video ->
        val quality = qualityBadges[video.videoId]
        if (quality.isNullOrBlank() || video.badge.isNotBlank()) video
        else video.copy(badge = quality)
      }
    }
    if (qualityVideoTab) {
      Log.d(
        "YoutubeChannel",
        "getChannelVideos qualityBadges channelId=$channelId tiled=${qualityBadges.size} " +
          "matched=${items.count { qualityBadges.containsKey(it.videoId) }} page=${feed.items.size}",
      )
    }
    YoutubeVideoPage(
      items = items.map(::toVideoSummary),
      continuation = feed.continuation,
    )
  }

  // ---- 频道页排序(最新/最热/最早):排序 chip token 路线 ----

  /**
   * 频道「视频」tab 按排序取一页。**最新** = 一条请求;「最热」/「最早」= 先取最新页摘服务端铸造的
   * 排序 chip token([YoutubeParsers.parseChannelOrderTokens]),再用该 token 发一条 continuation。
   *
   * 为什么不是「换个 params」:2026-10-10 实测 `params="EgZwb3B1bGFy"`(与 rustypipe 本地铸造的
   * `order_ctoken` 一样)**都不再有效** —— 前者服务端直接回 Home tab(列表不是按播放量排的),
   * 后者作为 continuation 一律 400;按解出的结构静态造 token(伪造 chipBar targetId)三种排序全部
   * 0 条。唯一可用的路是响应里那条 chip token,且必须原样发回(自带 `%3D%3D`,别再编码)。
   * 失败/缺 token 一律**降级为最新页**,不抛给调用方。
   *
   * 只管**首屏**:翻页继续走 [getChannelVideos] 的 continuation(排序已烘进 token,续页与排序无关)。
   */
  suspend fun getChannelVideosOrdered(
    channelId: String,
    order: YoutubeConstants.ChannelVideoOrder,
    withQualityBadges: Boolean = true,
  ): YoutubeVideoPage {
    if (order == YoutubeConstants.ChannelVideoOrder.Latest) {
      // 最新 = Videos tab 首屏本身,免去摘 token 的那次往返。
      return getChannelVideos(channelId, withQualityBadges = withQualityBadges)
    }
    val token = channelOrderToken(channelId, order)
    if (token == null) {
      Log.w(
        "YoutubeChannel",
        "getChannelVideosOrdered channelId=$channelId order=${order.name}: 排序 chip token 缺失 → 降级最新",
      )
      return getChannelVideos(
        channelId,
        params = YoutubeConstants.ChannelVideosParams,
        withQualityBadges = withQualityBadges,
      )
    }
    return getChannelVideos(channelId, continuation = token, withQualityBadges = withQualityBadges)
  }

  /**
   * 排序 chip token:先查缓存(TTL [ChannelOrderTokenTtlMs]),未命中就拉一次最新页从响应里摘。
   *
   * 摘 token 的这一路**不补画质角标**(`withQualityBadges = false` 那套语义):它只为拿 token,
   * 页面列表随后由 continuation 那条路取,角标也由那条路按 videoId 匹配。
   */
  private suspend fun channelOrderToken(
    channelId: String,
    order: YoutubeConstants.ChannelVideoOrder,
  ): String? {
    val now = System.currentTimeMillis()
    val cachedTokens = synchronized(channelOrderTokenCaches) {
      channelOrderTokenCaches[channelId]?.takeIf { now - it.first <= ChannelOrderTokenTtlMs }?.second
    }
    cachedTokens?.get(order)?.let { return it }
    val payload = buildJsonObject {
      put("browseId", channelId)
      put("params", YoutubeConstants.ChannelVideosParams)
    }
    val root = feedCatching<JsonObject?>(null, "ChannelOrderTokens", channelId) {
      client.postJson("/browse", payload)
    } ?: return null
    val tokens = YoutubeParsers.parseChannelOrderTokens(root)
    Log.d(
      "YoutubeChannel",
      "channelOrderTokens channelId=$channelId got=[${tokens.keys.joinToString(",") { it.name }}]",
    )
    if (tokens.isEmpty()) return null
    synchronized(channelOrderTokenCaches) {
      channelOrderTokenCaches[channelId] = now to tokens
      while (channelOrderTokenCaches.size > ChannelOrderTokenMaxChannels) {
        channelOrderTokenCaches.remove(channelOrderTokenCaches.keys.first())
      }
    }
    return tokens[order]
  }

  // ---- P11-201 频道页画质角标:TV 客户端补充路(基页 + 沿上传列表翻页) ----

  /**
   * 单频道 TV 画质角标缓存:累积的角标 + 沿上传列表继续翻页的游标 + 「在显示的视频」集合。
   *
   * 翻页判据是**覆盖**,所以缓存记的是 UI 已收到的 WEB 视频 id([wanted]),而不是 P11-201
   * 初版那个「已交给 UI 的条数」代理 —— 代理在基页与 WEB 列表错位时会判错(见 [tvQualityBadges])。
   */
  private class TvQualityCache {
    val badges = LinkedHashMap<String, String>()

    /** UI 已收到的 WEB 视频 id(跨页累积)。角标够不够,只看它被覆盖了多少。 */
    val wanted = LinkedHashSet<String>()

    var nextToken: String? = null

    /** 基页挑中的 shelf 目标(UC…);翻页时优先认同一个,免得在续页响应里挑错 shelf。 */
    var shelfTarget: String? = null

    // 基页形状(base* 三项只作诊断与退化重取判据,不参与翻页决策)。
    var baseTiles = 0
    var baseShelves = 0
    var baseOverlap = 0

    var baseLoaded = false
    var baseRetried = false
    var walkedPages = 0

    var updatedAtMs = 0L

    /** 在显示的视频里还差多少条没有角标。 */
    fun missingCount(): Int = wanted.count { !badges.containsKey(it) }
  }

  /**
   * 取该频道的 TV 画质角标(videoId → "4K"),不够深就沿 TV 上传列表继续翻页。
   *
   * 为什么是「翻页」而不是「换客户端」:频道页 WEB 列表分页 30 条/页,而 TV 频道页首屏的
   * 「Videos」shelf 只给最新 24 条 —— 只拉首屏的话,越往后越没有角标(P11-201 真机反馈
   * 「只有最新的一部分有角标」)。该 shelf 带旧格式分页 token,逐页 24 条翻下去能一直翻到更早的
   * 视频,结果整个会话内缓存复用(每多翻一页 ≈ 多覆盖 24 条)。
   *
   * **继续翻页的判据是覆盖,不是条数**(P11-228):缓存累积 UI 已收到的 WEB 视频 id
   * ([TvQualityCache.wanted]),只在「还没覆盖到的在显示视频」确实被填上时才算推进。初版用的是
   * 「已翻条数 ≥ 已交给 UI 条数」这个计数代理,基页形状与 WEB 列表一错位就判错 —— 真机
   * 2026-10-09 那场(tiled 23→47→71→95→132 一路涨,首屏 30 条却始终只覆盖 6 条)就是基页没含
   * 上传列表、翻页顺着别的 shelf 走:条数每页 +24 看着「够深了」,覆盖纹丝不动,于是首屏那些视频
   * **永远**没有角标(用户报「首屏没有 4K,翻页后才有」)。
   *
   * 基页还会做一次**退化重取**:覆盖不到在显示的视频时(不足 [TvQualityCoverageFloor])再取一次
   * 基页并并集 —— 同频道同一形状的请求实测会返回不同的 shelf 组合(同一会话里既有 23 条的退化页,
   * 也有 71 条的正常页),重取一次能整屏救回角标。每个缓存代只重取一次(最多 +1 请求),正常情况
   * (基页覆盖 26/30)不触发。
   *
   * [basePage] 是调用方与 WEB 请求并行发出的基页(首屏才有);无缓存且拿不到基页时自己补一次。
   * 任何失败都只降级为「角标少一点」,不抛给调用方。
   */
  private suspend fun tvQualityBadges(
    channelId: String,
    webVideoIds: List<String>,
    basePage: Deferred<YoutubeParsers.TvTilePage?>?,
  ): Map<String, String> = tvQualityGate.withPermit {
    val now = System.currentTimeMillis()
    val cached = tvQualityCaches[channelId]?.takeIf { now - it.updatedAtMs <= TvQualityCacheTtlMs }
    val cache: TvQualityCache
    if (cached != null) {
      cache = cached
    } else {
      cache = TvQualityCache()
      tvQualityCaches.remove(channelId)
      tvQualityCaches[channelId] = cache
      while (tvQualityCaches.size > TvQualityCacheMaxChannels) {
        val oldest = tvQualityCaches.minByOrNull { it.value.updatedAtMs }?.key ?: break
        tvQualityCaches.remove(oldest)
      }
    }
    cache.wanted.addAll(webVideoIds)

    // ①基页:一个缓存代只取一次(与 WEB 请求并行的 deferred 优先,拿不到才自己补)。
    if (!cache.baseLoaded) {
      cache.baseLoaded = true
      val page = basePage?.let { deferred -> runCatching { deferred.await() }.getOrNull() }
        ?: feedCatching(null, "TVQualityBadges", channelId) { fetchTvTilePage(channelId, null) }
      page?.let { absorbTilePage(cache, it, preferTarget = null, isBase = true) }
    }
    // ②按覆盖翻页:填不动就收手(walkForCoverage 里判),错 shelf 最多花一页请求。
    walkForCoverage(channelId, cache)
    // ③基页退化 ⇒ 重取一次;真救回覆盖才继续翻(否则白翻)。
    val coveredNow = cache.wanted.size - cache.missingCount()
    if (!cache.baseRetried && cache.wanted.isNotEmpty() &&
      coveredNow < cache.wanted.size * TvQualityCoverageFloor
    ) {
      cache.baseRetried = true
      val page = feedCatching(null, "TVQualityBadgesRetry", channelId) { fetchTvTilePage(channelId, null) }
      if (page != null) {
        absorbTilePage(cache, page, preferTarget = cache.shelfTarget, isBase = false)
        if (cache.wanted.size - cache.missingCount() > coveredNow) walkForCoverage(channelId, cache)
      }
    }
    cache.updatedAtMs = System.currentTimeMillis()
    // 诊断:P11-201 那行的 tiled/matched 只说「并回了几条」,基页退化时看不出是谁的锅 ——
    // 这里把 wanted/covered 与基页形状(baseTiles/baseShelves/baseOverlap)一并打出来,
    // 下一次退化场只看日志就能判「基页没含上传列表」还是「翻页挑错 shelf」。
    Log.d(
      "YoutubeChannel",
      "qualityBadgesDetail channelId=$channelId wanted=${cache.wanted.size} " +
        "covered=${cache.wanted.size - cache.missingCount()} baseTiles=${cache.baseTiles} " +
        "baseShelves=${cache.baseShelves} baseOverlap=${cache.baseOverlap} " +
        "walkedPages=${cache.walkedPages} baseRetry=${cache.baseRetried} tiled=${cache.badges.size}",
    )
    // 返回快照:缓存随时可能被下一次翻页继续写,别把可变 map 递出去。
    LinkedHashMap(cache.badges)
  }

  /**
   * 顺上传列表往下翻:只在**还没覆盖到的在显示视频**被填上时继续,一页填不动就收手 ——
   * 基页与 WEB 列表错位时挑错的 shelf 最多只花一页请求就会被判「没用」。
   */
  private suspend fun walkForCoverage(channelId: String, cache: TvQualityCache) {
    var pages = 0
    while (pages < TvQualityMaxPagesPerCall) {
      val missing = cache.missingCount()
      if (missing == 0) break
      val token = cache.nextToken ?: break
      pages++
      val page = feedCatching(null, "TVQualityBadgesNext", channelId) {
        fetchTvTilePage(channelId, continuation = token)
      } ?: break
      absorbTilePage(cache, page, preferTarget = cache.shelfTarget, isBase = false)
      if (cache.missingCount() >= missing) break
    }
    cache.walkedPages += pages
  }

  /**
   * 把一页 TV 解析结果并进缓存:角标只补空位(会员/直播等已有角标由调用方优先保留);
   * 带 token 的 shelf 里挑「与在显示视频重叠最多」的那条当下一跳游标。
   *
   * [isBase]=true 时顺带记基页形状(诊断 + 退化重取判据)。
   */
  private fun absorbTilePage(
    cache: TvQualityCache,
    page: YoutubeParsers.TvTilePage,
    preferTarget: String?,
    isBase: Boolean,
  ) {
    page.qualityBadges.forEach { (videoId, label) -> cache.badges.putIfAbsent(videoId, label) }
    if (isBase) {
      cache.baseTiles = page.videoIds.size
      cache.baseShelves = page.shelves.size
      cache.baseOverlap = page.shelves.maxOfOrNull { shelf ->
        shelf.videoIds.count { it in cache.wanted }
      } ?: 0
    }
    val shelf = pickUploadShelf(page.shelves, cache.wanted.toList(), preferTarget)
    cache.nextToken = shelf?.nextToken ?: page.fallbackNextToken
    if (shelf != null) cache.shelfTarget = shelf.targetBrowseId
  }

  /**
   * 从 TV 页里的 shelf 中挑「上传列表」那条:先认同一个目标([preferTarget],翻页时用),
   * 否则在**带分页 token** 的候选里按与在显示视频的重叠度取最大(播放列表/Shorts shelf 几乎零重叠);
   * 全零时取第一条(TV 布局里 Videos shelf 排在 For You 之后、Shorts 与各播放列表之前)。
   *
   * 候选**不再要求目标是频道(UC…)**(P11-228):上传列表 shelf 的目标实测可能是 UC、也可能缺省,
   * 卡 UC 前缀会把真正能翻到更早视频的那条 shelf 直接排除在外。
   */
  private fun pickUploadShelf(
    shelves: List<YoutubeParsers.TvTileShelf>,
    webVideoIds: List<String>,
    preferTarget: String? = null,
  ): YoutubeParsers.TvTileShelf? {
    if (!preferTarget.isNullOrBlank()) {
      shelves.firstOrNull { it.nextToken != null && it.targetBrowseId == preferTarget }?.let { return it }
    }
    val candidates = shelves.filter { it.nextToken != null }
    if (candidates.isEmpty()) return null
    val wanted = webVideoIds.toHashSet()
    val best = candidates.maxByOrNull { shelf -> shelf.videoIds.count { it in wanted } } ?: return null
    return best.takeIf { shelf -> shelf.videoIds.any { it in wanted } } ?: candidates.first()
  }

  /** 拉一页 TV 客户端(TVHTML5)数据并解析:首屏发 browseId+params,翻页发 continuation。失败抛异常。 */
  private suspend fun fetchTvTilePage(
    channelId: String,
    continuation: String?,
  ): YoutubeParsers.TvTilePage {
    val payload = buildJsonObject {
      if (continuation != null) {
        put("continuation", continuation)
      } else {
        put("browseId", channelId)
        put("params", YoutubeConstants.ChannelVideosParams)
      }
    }
    val root = client.postJson("/browse", payload, client = InnerTubeClient.Client.TVHTML5)
    return YoutubeParsers.parseChannelTilePage(root)
  }

  /**
   * 频道"播放列表"Tab 的播放列表卡列表。首屏发 browseId+params,续页发 continuation
   *（对齐 [getChannelVideos]）。[params] 优先用服务端提供的 playlists tab params。
   */
  suspend internal fun getChannelPlaylists(
    channelId: String,
    continuation: String? = null,
    params: String = YoutubeConstants.ChannelPlaylistsParams,
  ): YoutubePlaylistsPage {
    val payload = buildJsonObject {
      if (continuation != null) {
        put("continuation", continuation)
      } else {
        put("browseId", channelId)
        put("params", params)
      }
    }
    val root = client.postJson("/browse", payload)
    val items = YoutubeParsers.parseChannelPlaylists(root)
    // 诊断:确认播放列表 Tab 是否真的请求到 playlistRenderer 内容(空=服务端可能无视 params 回落成普通视频)。
    Log.d("Ytabs", "getChannelPlaylists channelId=$channelId continuation=${continuation != null} " +
      "params=${if (continuation == null) params.take(8) else "cont"} items=${items.size} " +
      "shape=${if (items.isEmpty()) YoutubeParsers.diagnosticPlaylistShape(root) else "ok"}")
    return YoutubePlaylistsPage(
      items = items,
      continuation = YoutubeParsers.findContinuation(root),
    )
  }

  /**
   * 打开一个播放列表:按播放列表 browseId（形如 VL...，来自 [YoutubeParsers.YoutubePlaylist.browseId]）
   * 拉首屏视频列表，翻页发 continuation。对齐 [getChannelVideos] 解析视频。
   */
  suspend fun getPlaylistVideos(
    playlistBrowseId: String,
    continuation: String? = null,
  ): YoutubeVideoPage {
    val payload = buildJsonObject {
      if (continuation != null) put("continuation", continuation)
      else put("browseId", normalizePlaylistBrowseId(playlistBrowseId))
    }
    val root = client.postJson("/browse", payload)
    val feed = YoutubeParsers.parseFeedPage(root)
    // 简介/作者/封面只在首屏 header(playlistHeaderRenderer 或 pageHeaderRenderer)有；续页是纯 continuation 无 header。
    val playlistHeader = if (continuation == null) YoutubeParsers.parsePlaylistHeader(root) else null
    // 诊断：确认首屏 header 解析结果(desc/owner/count/cover),取不到时 dump header 顶层键定位。
    if (continuation == null) {
      val header = root["header"]
      Log.i(
        "YtPlaylist",
        "getPlaylistVideos browseId=$playlistBrowseId " +
          "desc=${playlistHeader?.description?.take(60) ?: "null"} " +
          "owner=${playlistHeader?.owner ?: "null"} count=${playlistHeader?.videoCountText ?: "null"} " +
          "cover=${if (playlistHeader?.cover != null) "ok" else "null"} " +
          "headerKeys=${(header as? JsonObject)?.keys?.take(10) ?: "N/A"} " +
          "items=${feed.items.size}",
      )
    }
    return YoutubeVideoPage(
      items = feed.items.map(::toVideoSummary),
      continuation = feed.continuation,
      playlistHeader = playlistHeader,
    )
  }

  /** 打开播放列表的 /browse 需要 `VL` + 播放列表 id(如 VLPL...)。lockupViewModel 卡片提取的
   *  browseId 可能是裸 PL... 或已 VL 前缀;非 VL 前缀一律补 VL,否则 /browse 返回 400。 */
  private fun normalizePlaylistBrowseId(id: String): String =
    if (id.startsWith("VL")) id else "VL$id"

  /**
   * 视频详情（简介 Tab）：POST /player 取 videoDetails（title/author/shortDescription/viewCount）
   * + microformat（publishDate）。受限视频可能无 videoDetails，此时返回 null（UI 显示重试）。
   */
  suspend fun getVideoDetail(videoId: String): YoutubeVideoDetail? {
    if (videoId.isBlank()) return null
    val detail = runCatching {
      val payload = buildJsonObject {
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
      }
      val playerJson = client.postJson("/player", payload)
      // 诊断:直接看 microformat.playerMicroformatRenderer 到底有哪些日期字段(确认 publishDate
      // 是否真缺,或只是 parser 读了错字段)。不改播放行为。
      runCatching {
        val mf = playerJson["microformat"]?.jsonObject?.get("playerMicroformatRenderer")?.jsonObject
        val pub = mf?.get("publishDate")?.jsonPrimitive?.contentOrNull
        val up = mf?.get("uploadDate")?.jsonPrimitive?.contentOrNull
        Log.i("YoutubeDetail", "getVideoDetail mf videoId=$videoId renderer=${mf != null} keys=${mf?.keys ?: "N/A"} publishDate=$pub uploadDate=$up")
      }
      YoutubeParsers.parseVideoDetail(playerJson)
    }.getOrNull() ?: return null
    // 点赞数不在 /player 的 videoDetails,在 /next 首屏 videoPrimaryInfoRenderer.videoActions 工具栏
    // (对齐 NewPipe getLikeCount)。发一次 /next 取点赞并回写;失败保持 null(UI 不显示点赞行)。
    val withLikes = runCatching {
      val nextPayload = buildJsonObject { put("videoId", videoId) }
      val likeCount = YoutubeParsers.parseLikeCount(client.postJson("/next", nextPayload))
      Log.i("YoutubeDetail", "getVideoDetail likes videoId=$videoId likeCount=$likeCount")
      if (likeCount != null) detail.copy(likeCount = likeCount) else detail
    }.getOrElse {
      Log.w("YoutubeDetail", "getVideoDetail likes failed videoId=$videoId: ${it::class.simpleName}: ${it.message}")
      detail
    }
    // /player 的 microformat.publishDate 实测恒 null。对齐 LibreTube:缺省时用 NewPipe getInfo 的
    // uploadDate(与入口路径无关)兜底,保证简介 Tab 恒有发布时间(历史/播放列表/相关视频统一)。
    // 频道头像同源:parseVideoDetail 的 /player videoDetails 无作者头像字段(channelAvatarUrl 恒空),
    // 不补则历史条目/简介 Tab 频道行头像一片空白。复用同一次 getInfo 的 uploaderAvatars 提权威头像
    // (对齐 LibreTube Streams.uploaderAvatar = uploaderAvatars.maxBy { height }),零额外网络往返。
    if (withLikes.publishedAt == null || withLikes.channelAvatarUrl.isBlank()) {
      val np = runCatching {
        // NewPipe getInfo 是同步阻塞网络调用,必须在 IO 线程(对齐 LibreTube getStreams 的
        // withContext(Dispatchers.IO));直接在主线程跑抛 NetworkOnMainThreadException → 头像恒空。
        withContext(Dispatchers.IO) {
          val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
          val d = info.uploadDate
          // 诊断:NewPipe 兜底源与值(确认 getInfo 是否成功、uploadDate 是否非空、头像数)。
          Log.i("YoutubeDetail", "getVideoDetail newpipe videoId=$videoId uploadDateClass=${d?.javaClass?.simpleName ?: "null"} uploadDate=$d avatars=${info.uploaderAvatars.size}")
          d?.offsetDateTime()?.toEpochSecond()?.takeIf { it > 0L } to
            info.uploaderAvatars.maxByOrNull { it.height }?.url.orEmpty()
        }
      }.getOrElse {
        Log.w("YoutubeDetail", "getVideoDetail newpipe failed videoId=$videoId: ${it::class.simpleName}: ${it.message}\n${it.stackTraceToString().take(1200)}")
        null
      }
      if (np != null) {
        val (npUpload, npAvatar) = np
        return withLikes.copy(
          publishedAt = withLikes.publishedAt ?: npUpload,
          channelAvatarUrl = withLikes.channelAvatarUrl.ifBlank { npAvatar },
        )
      }
    }
    return withLikes
  }

  /**
   * 评论列表（/next）：首屏 payload 只带 videoId；续页带 continuation token。
   * 返回一页 [YoutubeComment] + 续页 token（null 表示到底）。
   */
  suspend fun getComments(
    videoId: String,
    continuation: String? = null,
  ): YoutubeCommentPage {
    val payload = buildJsonObject {
      put("videoId", videoId)
      if (!continuation.isNullOrBlank()) put("continuation", continuation)
    }
    Log.d("YoutubeComment", "getComments videoId=$videoId continuation=${continuation?.take(16) ?: "null"}")
    var response = client.postJson("/next", payload)
    // 首屏:先拿初始评论 token,再发第二次 /next 拉真评论(对齐 NewPipe 两步)。
    // 首屏 /next 响应里评论在 engagementPanels 数组(panelIdentifier=engagement-panel-comments-section),
    // 只有 token 没有实际评论;必须带 token 再发一次才返回 commentThreadRenderer。
    if (continuation.isNullOrBlank()) {
      val initialToken = YoutubeParsers.findInitialCommentsToken(response)
      if (initialToken != null) {
        Log.d("YoutubeComment", "getComments videoId=$videoId initialToken=${initialToken.take(16)}")
        val secondPayload = buildJsonObject {
          put("videoId", videoId)
          put("continuation", initialToken)
        }
        response = client.postJson("/next", secondPayload)
      }
    }
    val page = YoutubeParsers.parseCommentPage(response)
    Log.d(
      "YoutubeComment",
      "getComments videoId=$videoId items=${page.items.size} " +
        "continuation=${page.continuation?.take(16) ?: "null"}",
    )
    // 诊断:dump 第一条评论字段,确认 EUVM 是否提取到作者/内容。
    page.items.firstOrNull()?.let { c ->
      Log.d(
        "YoutubeComment",
        "getComments firstComment id=${c.commentId.take(16)} author=${c.authorName.take(20)} " +
          "content=${c.content.take(40)} likes=${c.likeCount} replies=${c.replyCount}",
      )
    }
    YoutubeParsers.dumpCommentEntity(response)
    return page
  }

  /**
   * 相关视频（/next）：与评论同端点，取 secondaryResults 里的 compactVideoRenderer（对齐 LibreTube）。
   * 首屏 payload 只带 videoId；续页带 continuation token。返回一页 [YoutubeVideo] + 续页 token。
   */
  suspend fun getRelatedVideos(
    videoId: String,
    continuation: String? = null,
  ): YoutubeFeedPage {
    val payload = buildJsonObject {
      put("videoId", videoId)
      if (!continuation.isNullOrBlank()) put("continuation", continuation)
    }
    return client.postJson("/next", payload).let(YoutubeParsers::parseRelatedVideos)
  }

  /**
   * 订阅流单频道拉取的容错包装：网络/解析失败记日志并降级用 [default]（丢该频道自身），
   * **但必须透传 [CancellationException]**——`runCatching` 会吞掉取消异常（含
   * `LeftCompositionCancellationException`：composition scope 离开组合时抛的取消），
   * 把"协程被取消"误判成"网络失败"→ 整批返回空 → 首页卡片全 ERR。取消必须向上传播，
   * 让外层 `LaunchedEffect` 感知到并允许重试，而不是当成一次真实的失败降级。
   */
  private suspend fun <T> feedCatching(
    default: T,
    failKind: String,
    channelId: String,
    block: suspend () -> T,
  ): T = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.w("YoutubeFeed", "$failKind failed for $channelId", e)
    default
  }

  /**
   * 动态页"YouTube 关注"流：遍历配置的频道取各自最新视频，按发布时间倒序合并。
   * 对齐 LibreTube `LocalFeedRepository.refreshFeed` 的**分批增量**模型（独立实现）。
   *
   * **RSS + InnerTube 并行拉取后按 videoId 合并**：每频道并发发轻量 RSS GET
   * （[YoutubeMaxConcurrentRssFetches] 限并发，无 InnerTube 风控、不计配额）与 InnerTube
   * `/browse`（[YoutubeMaxConcurrentChannelFetches] 限并发防风控）。RSS 提供精确 `publishedAt`，
   * InnerTube 补全 `duration`/`liveNow`/`isUpcoming`/`badge` 及 RSS 未覆盖的 Shorts/直播/首映。
   * 任何一路失败都降级用另一路，不影响整体。
   *
   * **分批增量(几百频道可扩展)**：频道按 [ChunkSize] 分批并发拉取，每批就绪立即回调
   * [onChunkReady]，调用方可"拉到一批显示一批"而非等全部；每累计 [BatchSize] 个频道
   * `delay` 一次防节流（对齐 LibreTube `CHANNEL_BATCH_DELAY`）。单频道失败只丢自身，
   * **无需外层全局超时**（这是几百频道下旧一次性 `awaitAll` + 外层预算必超时的根因）。
   * 函数仍返回全量 List（按 pubdate 倒序），供调用方一次性缓存写。
   *
   * @param onChunkReady 每批就绪回调（增量 merge / 增量写缓存的入口）。
   */
  suspend fun getSubscriptionsFeed(
    channels: List<YoutubeChannel>,
    perChannel: Int = 15,
    onChannelAvatarResolved: suspend (YoutubeChannel) -> Unit = {},
    onChunkReady: (List<VideoSummary>) -> Unit = {},
    cachedLatestByChannel: Map<String, Long> = emptyMap(),
  ): List<VideoSummary> {
    if (channels.isEmpty()) {
      // 未配置频道时回退显示热门,避免动态 tab 空白(设置里可添加频道)。
      return getTrending(YoutubeConstants.TrendingTabs.values.first())
    }
    val startMs = SystemClock.elapsedRealtime()
    val rssSemaphore = Semaphore(YoutubeMaxConcurrentRssFetches)
    val innerTubeSemaphore = Semaphore(YoutubeMaxConcurrentChannelFetches)
    val accumulator = mutableListOf<VideoSummary>()
    var processed = 0
    for (batch in channels.chunked(ChunkSize)) {
      val batchResult = coroutineScope {
        batch.map { channel ->
          async {
            // 旧频道(无头像)懒解析一次并回写 store,供本次填充与后续复用,避免每次刷新重复 /browse。
            var channelAvatar = channel.avatar
            if (channelAvatar.isBlank()) {
              val resolvedAvatar = innerTubeSemaphore.withPermit {
                feedCatching("", "avatar", channel.channelId) {
                  val payload = buildJsonObject { put("browseId", channel.channelId) }
                  YoutubeParsers.parseChannelInfo(client.postJson("/browse", payload))?.avatarUrl.orEmpty()
                }
              }
              if (resolvedAvatar.isNotBlank()) {
                channelAvatar = resolvedAvatar
                onChannelAvatarResolved(channel.copy(avatar = resolvedAvatar))
              }
            }
            // RSS 与 InnerTube 拉取。RSS 提供精确发布时间,InnerTube 补全 duration/live 等字段。
            // 门控(对齐 LibreTube hasNewerUploads):有缓存时先拉 RSS,若 RSS 最新视频 ≤ 缓存最新
            // 则跳过 InnerTube 只付 RSS 成本;无缓存(首次)则 RSS+InnerTube 并行拉全保正确性。
            val cachedLatest = cachedLatestByChannel[channel.channelId]
            val rssDeferred = async {
              rssSemaphore.withPermit {
                feedCatching(emptyList(), "RSS", channel.channelId) { getChannelRss(channel.channelId) }
              }
            }
            val innerTubeDeferred: Deferred<List<YoutubeVideo>>? = if (cachedLatest == null) {
              async {
                innerTubeSemaphore.withPermit {
                  feedCatching(emptyList(), "InnerTube", channel.channelId) { getChannelVideosRaw(channel.channelId) }
                }
              }
            } else {
              null
            }
            val rssVideos = rssDeferred.await()
            val innerTubeVideos = if (innerTubeDeferred != null) {
              innerTubeDeferred.await()
            } else {
              val rssLatest = rssVideos.maxOfOrNull { it.publishedAt ?: 0L } ?: 0L
              // innerTubeDeferred==null ⟹ cachedLatest!=null,!! 安全。
              // RSS 空/失败(rssLatest=0,如 YouTube RSS 404)时不能门控跳过——404 不代表无新内容,
              // 必须拉 InnerTube 兜底(否则动态页全空);仅 RSS 正常返回且最新≤缓存最新才跳过。
              if (rssVideos.isEmpty() || rssLatest > cachedLatest!!) {
                innerTubeSemaphore.withPermit {
                  feedCatching(emptyList(), "InnerTube", channel.channelId) { getChannelVideosRaw(channel.channelId) }
                }
              } else {
                emptyList()
              }
            }
            val merged = mergeByVideoId(rssVideos, innerTubeVideos)
            Log.d(
              "YoutubeFeed",
              "${channel.channelId}: RSS ${rssVideos.size} + InnerTube ${innerTubeVideos.size} → merged ${merged.size}",
            )
            val resolved: List<VideoSummary> = merged.take(perChannel).map(::toVideoSummary)
            // lockupViewModel 不重复频道名/频道id,给空作者名与空频道id的视频补上所属频道,
            // 卡片作者行才有内容、点 UP 头像才能进本频道主页;头像同理补上所属频道头像。
            resolved.map { video ->
              video.copy(
                ownerName = if (video.ownerName.isBlank()) channel.name else video.ownerName,
                channelId = if (video.channelId.isBlank()) channel.channelId else video.channelId,
                ownerFace = if (video.ownerFace.isBlank()) channelAvatar else video.ownerFace,
              )
            }
          }
        }.awaitAll().flatten()
      }
      // 每批就绪即回调(增量 merge / 增量写缓存),不等待全部频道。
      onChunkReady(batchResult)
      accumulator += batchResult
      processed += batch.size
      // 防节流(对齐 LibreTube CHANNEL_BATCH_DELAY):每累计 BatchSize 频道暂停随机 500-1500ms。
      // 用 >= 而非 % == 0:ChunkSize 不整除 BatchSize 时 % 永不触发(如 ChunkSize=4 时 50 不整除)。
      if (processed >= BatchSize) {
        delay(Random.nextLong(BatchDelayMs.first, BatchDelayMs.last + 1))
        processed = 0
      }
    }
    val elapsedMs = SystemClock.elapsedRealtime() - startMs
    Log.i(
      "YoutubeFeed",
      "getSubscriptionsFeed done: channels=${channels.size} total=${accumulator.size} elapsed=${elapsedMs}ms",
    )
    return accumulator.sortedByDescending { it.pubdate }
  }

  /**
   * 首页订阅流分页(首屏或续页),返回每频道独立续页 token。
   *
   * - **首屏(previousContinuation == null)**:每频道 RSS + InnerTube 第一页并行拉取合并([mergeByVideoId]),
   *   `take(perChannel)` 映射成卡片,同时记录该频道 InnerTube 第一页的 continuation。
   * - **续页**:只对 `previousContinuation` 中 token 非 null 的频道,调 [getChannelVideosRawPage] 拉更早一页
   *   (RSS 无续页概念,续页仅走 InnerTube),`take(perChannel)` 映射,记录该频道下一 token。
   *
   * 分批并发 / 防节流 / 单频道失败降级均沿用 [getSubscriptionsFeed] 骨架。UI 负责跨页累积去重后按 pubdate 排序。
   *
   * @param perChannel 每频道每页最多取条数(默认 15)。
   * @param previousContinuation null 表示首屏;否则 channelId -> 上一页留下的下一 token。
   */
  suspend fun getSubscriptionsPage(
    channels: List<YoutubeChannel>,
    perChannel: Int = 15,
    previousContinuation: Map<String, String?>? = null,
    onChannelAvatarResolved: suspend (YoutubeChannel) -> Unit = {},
    onChunkReady: (List<VideoSummary>) -> Unit = {},
  ): YoutubeSubscriptionsPage {
    if (channels.isEmpty()) {
      // 未配置频道时回退显示热门,避免首页空白(设置里可添加频道)。
      return YoutubeSubscriptionsPage(getTrending(YoutubeConstants.TrendingTabs.values.first()), emptyMap())
    }
    val startMs = SystemClock.elapsedRealtime()
    val rssSemaphore = Semaphore(YoutubeMaxConcurrentRssFetches)
    val innerTubeSemaphore = Semaphore(YoutubeMaxConcurrentChannelFetches)
    // 首屏：全部频道；续页：仅 token 非 null 的频道。
    val activeChannels = if (previousContinuation == null) {
      channels
    } else {
      channels.filter { (previousContinuation[it.channelId] ?: return@filter false) != null }
    }
    val accumulator = mutableListOf<VideoSummary>()
    val continuationAccumulator = mutableMapOf<String, String?>()
    var processed = 0
    for (batch in activeChannels.chunked(ChunkSize)) {
      val (batchVideos, batchContinuations) = coroutineScope {
        batch.map { channel ->
          async {
            // 旧频道(无头像)懒解析一次并回写 store,供本次填充与后续复用,避免每次刷新重复 /browse。
            var channelAvatar = channel.avatar
            if (channelAvatar.isBlank()) {
              val resolvedAvatar = innerTubeSemaphore.withPermit {
                feedCatching("", "avatar", channel.channelId) {
                  val payload = buildJsonObject { put("browseId", channel.channelId) }
                  YoutubeParsers.parseChannelInfo(client.postJson("/browse", payload))?.avatarUrl.orEmpty()
                }
              }
              if (resolvedAvatar.isNotBlank()) {
                channelAvatar = resolvedAvatar
                onChannelAvatarResolved(channel.copy(avatar = resolvedAvatar))
              }
            }
            if (previousContinuation == null) {
              // 首屏：RSS 与 InnerTube 第一页并行拉取合并，并记录 InnerTube 第一页的续页 token。
              val rssDeferred = async {
                rssSemaphore.withPermit {
                  feedCatching(emptyList(), "RSS", channel.channelId) { getChannelRss(channel.channelId) }
                }
              }
              val innerTubeDeferred = async {
                innerTubeSemaphore.withPermit {
                  feedCatching(
                    YoutubeFeedPage(emptyList(), null),
                    "InnerTube",
                    channel.channelId,
                  ) { getChannelVideosRawPage(channel.channelId) }
                }
              }
              val rssVideos = rssDeferred.await()
              val innerTubePage = innerTubeDeferred.await()
              val merged = mergeByVideoId(rssVideos, innerTubePage.items)
              Log.d(
                "YoutubeFeed",
                "${channel.channelId}: RSS ${rssVideos.size} + InnerTube ${innerTubePage.items.size} → merged ${merged.size} " +
                  "firstToken=${innerTubePage.continuation?.take(12) ?: "null"}",
              )
              val resolved: List<VideoSummary> = merged.take(perChannel).map(::toVideoSummary)
                .map { fillChannelInfo(it, channel, channelAvatar) }
              Triple(resolved, channel.channelId, innerTubePage.continuation)
            } else {
              // 续页：只拉 InnerTube 更早一页（RSS 无续页），取该频道下一 token。
              val token = previousContinuation[channel.channelId]
              val innerTubeDeferred = async {
                innerTubeSemaphore.withPermit {
                  feedCatching(
                    YoutubeFeedPage(emptyList(), null),
                    "InnerTube next",
                    channel.channelId,
                  ) { getChannelVideosRawPage(channel.channelId, token) }
                }
              }
              val innerTubePage = innerTubeDeferred.await()
              Log.d(
                "YoutubeFeed",
                "${channel.channelId}: next page → ${innerTubePage.items.size} (next=${innerTubePage.continuation?.take(12) ?: "null"})",
              )
              val resolved: List<VideoSummary> = innerTubePage.items.take(perChannel).map(::toVideoSummary)
                .map { fillChannelInfo(it, channel, channelAvatar) }
              Triple(resolved, channel.channelId, innerTubePage.continuation)
            }
          }
        }.awaitAll()
      }.let { results ->
        val batchVideos = mutableListOf<VideoSummary>()
        val batchContinuations = mutableMapOf<String, String?>()
        for (r in results) {
          if (r != null) {
            batchVideos += r.first
            batchContinuations[r.second] = r.third
          }
        }
        batchVideos to batchContinuations
      }
      // 每批就绪即回调(增量 merge / 增量写缓存),不等待全部频道。
      onChunkReady(batchVideos)
      accumulator += batchVideos
      // 首屏未拉到的频道(如头像解析失败)不在此批,续页 map 只含本批频道,其余继承 previousContinuation。
      if (previousContinuation != null) continuationAccumulator.putAll(previousContinuation)
      continuationAccumulator.putAll(batchContinuations)
      processed += batch.size
      // 防节流(对齐 LibreTube CHANNEL_BATCH_DELAY):每累计 BatchSize 频道暂停随机 500-1500ms。
      // 用 >= 而非 % == 0:ChunkSize 不整除 BatchSize 时 % 永不触发(如 ChunkSize=4 时 50 不整除)。
      if (processed >= BatchSize) {
        delay(Random.nextLong(BatchDelayMs.first, BatchDelayMs.last + 1))
        processed = 0
      }
    }
    val elapsedMs = SystemClock.elapsedRealtime() - startMs
    Log.i(
      "YoutubeFeed",
      "getSubscriptionsPage ${if (previousContinuation == null) "first" else "next"} done: " +
        "channels=${activeChannels.size} total=${accumulator.size} " +
        "channelsWithToken=${continuationAccumulator.values.count { it != null }} elapsed=${elapsedMs}ms",
    )
    return YoutubeSubscriptionsPage(
      videos = accumulator.sortedByDescending { it.pubdate },
      perChannelContinuation = continuationAccumulator,
    )
  }

  /** 给订阅流卡片补上所属频道名/频道 id/头像(lockupViewModel 恒空,见 youtube-api-notes)。 */
  private fun fillChannelInfo(
    video: VideoSummary,
    channel: YoutubeChannel,
    channelAvatar: String,
  ): VideoSummary {
    return video.copy(
      ownerName = if (video.ownerName.isBlank()) channel.name else video.ownerName,
      channelId = if (video.channelId.isBlank()) channel.channelId else video.channelId,
      ownerFace = if (video.ownerFace.isBlank()) channelAvatar else video.ownerFace,
    )
  }

  /**
   * 拉取单频道"视频"tab 一页的原始 InnerTube 内容,带续页 token。
   * 首屏发 browseId+params,续页发 continuation(对齐 [getChannelVideos])。
   * 失败抛异常,由调用方降级。
   */
  private suspend fun getChannelVideosRawPage(
    channelId: String,
    continuation: String? = null,
  ): YoutubeFeedPage {
    val payload = buildJsonObject {
      if (continuation != null) {
        put("continuation", continuation)
      } else {
        put("browseId", channelId)
        put("params", YoutubeConstants.ChannelVideosParams)
      }
    }
    return client.postJson("/browse", payload).let(YoutubeParsers::parseFeedPage)
  }

  /**
   * 拉取单频道"视频"tab 的原始 InnerTube 视频列表(未映射成卡片),供订阅流与 RSS 合并。
   * 仅取第一页,续页 token 丢弃(单次拉最新一批场景)。失败抛异常,由调用方降级。
   */
  private suspend fun getChannelVideosRaw(channelId: String): List<YoutubeVideo> {
    return getChannelVideosRawPage(channelId).items
  }

  /**
   * 按 videoId 合并 RSS 与 InnerTube 两路视频:以 RSS 为基底,用 InnerTube 补全 RSS 缺失的字段。
   * RSS 提供精确 ISO 8601 发布时间,InnerTube 相对时间反推是近似值 → 时间优先 RSS;
   * RSS 不提供 duration/live/upcoming/badge/头像 → 这些优先 InnerTube;viewCount 用更准确的 InnerTube。
   */
  private fun mergeByVideoId(
    rssVideos: List<YoutubeVideo>,
    innerTubeVideos: List<YoutubeVideo>,
  ): List<YoutubeVideo> {
    val byId = LinkedHashMap<String, YoutubeVideo>()
    for (v in rssVideos) byId[v.videoId] = v
    for (it in innerTubeVideos) {
      val existing = byId[it.videoId]
      byId[it.videoId] = if (existing == null) {
        // 仅 InnerTube 有(Shorts/直播/首映/RSS 未覆盖),直接保留。
        it
      } else {
        existing.copy(
          publishedAt = existing.publishedAt ?: it.publishedAt,
          durationSec = it.durationSec ?: existing.durationSec,
          liveNow = existing.liveNow || it.liveNow,
          isUpcoming = existing.isUpcoming || it.isUpcoming,
          badge = existing.badge.ifBlank { it.badge },
          viewCount = it.viewCount ?: existing.viewCount,
          channelAvatarUrl = existing.channelAvatarUrl.ifBlank { it.channelAvatarUrl },
        )
      }
    }
    return byId.values.toList()
  }

  /**
   * 拉取单频道 RSS 订阅流并解析成 [YoutubeVideo]。失败抛异常，由调用方回退。
   *
   * 用 **UULF playlist_id 变体**而非 channel_id:YouTube 的 `feeds/videos.xml?channel_id=` 自
   * 2025-12 起大面积间歇性 404/500(YouTube 侧问题,见 youtube-api-notes),而 uploads 播放列表
   * feed(`playlist_id=UULF<后缀>`)走不同后端、更稳、更新近实时。channelId 形如 `UC`+22 位,
   * UULF 变体把 `UC` 换成 `UULF`(uploads 播放列表 id)。
   */
  private suspend fun getChannelRss(channelId: String): List<YoutubeVideo> {
    val playlistId = "UULF" + channelId.removePrefix("UC")
    val xml = client.getText("${YoutubeConstants.RssFeedBase}?playlist_id=$playlistId")
    return YoutubeRssParser.parse(xml)
  }

  /** 把 [YoutubeVideo] 映射成 biliMT 统一的 [VideoSummary] 卡片。 */
  fun toVideoSummary(video: YoutubeVideo): VideoSummary {
    return VideoSummary(
      bvid = video.videoId,
      title = video.title,
      pic = video.thumbnailUrl,
      ownerName = video.channelName,
      ownerFace = video.channelAvatarUrl,
      ownerMid = 0L,
      view = video.viewCount?.let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() } ?: 0,
      danmaku = 0,
      duration = video.durationSec ?: 0,
      pubdate = video.publishedAt ?: (System.currentTimeMillis() / 1000L),
      badge = video.badge,
      isLive = video.liveNow,
      source = SourceYoutube,
      channelId = video.channelId,
    )
  }
}
