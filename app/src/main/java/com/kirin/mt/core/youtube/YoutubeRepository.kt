package com.kirin.mt.core.youtube

import android.util.Log
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 已映射成 [VideoSummary] 的一页 YouTube 内容，带续页 token。 */
data class YoutubeVideoPage(
  val items: List<VideoSummary>,
  val continuation: String?,
)

/** 订阅流逐频道拉取的并发上限。并发太高易触发 InnerTube 风控，4 是实测安全值。 */
const val YoutubeMaxConcurrentChannelFetches = 4

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
    return client.postJson("/search", payload).let(YoutubeParsers::parseFeedPage)
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
  }

  /**
   * 频道"视频"tab 的最新视频，返回映射后的卡片 + 续页 token。
   * [params] 决定排序（[YoutubeConstants.ChannelVideoOrder.Latest] 最新 /
   * [YoutubeConstants.ChannelVideoOrder.Popular] 最热）；翻页 continuation 与排序无关。
   */
  suspend fun getChannelVideos(
    channelId: String,
    continuation: String? = null,
    params: String = YoutubeConstants.ChannelVideosParams,
  ): YoutubeVideoPage {
    val payload = buildJsonObject {
      if (continuation != null) {
        put("continuation", continuation)
      } else {
        put("browseId", channelId)
        put("params", params)
      }
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
          "reason=${reason ?: "no-reason(parse ok,真无视频)"}",
      )
    } else {
      Log.d(
        "YoutubeChannel",
        "getChannelVideos channelId=$channelId ${if (continuation == null) "first" else "next"} " +
          "items=${feed.items.size} next=${feed.continuation?.take(12) ?: "null"}",
      )
    }
    return YoutubeVideoPage(
      items = feed.items.map(::toVideoSummary),
      continuation = feed.continuation,
    )
  }

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
  ): List<VideoSummary> {
    if (channels.isEmpty()) {
      // 未配置频道时回退显示热门,避免动态 tab 空白(设置里可添加频道)。
      return getTrending(YoutubeConstants.TrendingTabs.values.first())
    }
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
            // RSS 与 InnerTube 并行拉取。RSS 提供精确发布时间,InnerTube 补全 duration/live 等字段。
            val rssDeferred = async {
              rssSemaphore.withPermit {
                feedCatching(emptyList(), "RSS", channel.channelId) { getChannelRss(channel.channelId) }
              }
            }
            val innerTubeDeferred = async {
              innerTubeSemaphore.withPermit {
                feedCatching(emptyList(), "InnerTube", channel.channelId) { getChannelVideosRaw(channel.channelId) }
              }
            }
            val rssVideos = rssDeferred.await()
            val innerTubeVideos = innerTubeDeferred.await()
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
      if (processed % BatchSize == 0) {
        delay(Random.nextLong(BatchDelayMs.first, BatchDelayMs.last + 1))
      }
    }
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
      if (processed % BatchSize == 0) {
        delay(Random.nextLong(BatchDelayMs.first, BatchDelayMs.last + 1))
      }
    }
    Log.d(
      "YoutubeFeed",
      "page ${if (previousContinuation == null) "first" else "next"} done: total=${accumulator.size} " +
        "channelsWithToken=${continuationAccumulator.values.count { it != null }}",
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

  /** 拉取单频道 RSS 订阅流并解析成 [YoutubeVideo]。失败抛异常，由调用方回退。 */
  private suspend fun getChannelRss(channelId: String): List<YoutubeVideo> {
    val xml = client.getText("${YoutubeConstants.RssFeedBase}?channel_id=$channelId")
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
