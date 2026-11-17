// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 内容数据访问入口：封装搜索、详情与播放信息请求；界面层通过此处获取内容数据。

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 站点 CDN 在重定向时下发 ttwid 并拦截无 Cookie 的后续请求，内存 Cookie 罐使其自动流转。 */
class MemoryCookies : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    // RFC 语义：同名 + 同域 + 同路径的新 Cookie 替换旧值，而不是叠加。
    private fun List<Cookie>.replacedBy(cookies: List<Cookie>) =
        filter { kept ->
                cookies.none {
                    it.name == kept.name && it.domain == kept.domain && it.path == kept.path
                }
            }
            .toMutableList()
            .also { it += cookies }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        store.compute(url.host) { _, kept -> kept.orEmpty().replacedBy(cookies) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host].orEmpty().filter { it.expiresAt > now }
    }
}

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()

internal fun cleanUrl(s: String) =
    s.replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

data class Series(
    val id: String,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val badge: String = "",
    val tags: String = "",
) {
    fun toJson() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("cover", cover)
            .put("description", description)
            .put("badge", badge)
            .put("tags", tags)

    companion object {
        fun fromJson(o: JSONObject) =
            Series(
                o.str("id"),
                o.str("title"),
                o.str("cover"),
                o.str("description"),
                o.str("badge"),
                o.str("tags"),
            )
    }
}

data class Detail(val series: Series, val episodes: List<String>)

data class RankPosition(val rank: Int?, val heat: String, val metrics: String = "")

data class ComicRanking(
    val positions: Map<String, RankPosition>,
    val totalPages: Int,
    val updatedText: String,
)

data class TrialHint(val episodeId: String, val episodeCount: Int)

data class CatalogPage(
    val items: List<Series>,
    val hasMore: Boolean,
    val ranking: ComicRanking? = null,
    val discovery: DiscoveryInfo? = null,
    val trialHints: Map<String, TrialHint> = emptyMap(),
)

data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)

/** One playable rendition; width/height are the coded dimensions when the API reports them. */
data class Rendition(
    val quality: Int,
    val codec: String,
    val width: Int?,
    val height: Int?,
    val url: String,
    val key: ByteArray?,
) {
    fun toStreamInfo() = StreamInfo(url, key, if (quality > 0) "${quality}P" else "自动")
}

/**
 * All playable renditions of one episode. Capability-first selection: TV boxes commonly cap AVC
 * decoders at 1920x1088 with width/height checked independently, rejecting portrait sizes.
 */
class StreamLadder(val renditions: List<Rendition>) {
    // Ascending order: a greater element is the better tier (higher quality, h264 over others).
    // maxWithOrNull picks the best tier; minWithOrNull yields the closest-below fallback.
    private val better =
        compareBy<Rendition> { it.quality }.thenBy { if (it.codec in AVC_LABELS) 1 else 0 }

    fun select(maxQuality: Int, supported: (Rendition) -> Boolean = { true }): Rendition? {
        val pool = renditions.filter(supported).ifEmpty { renditions }
        return pool.filter { it.quality in 1..maxQuality }.maxWithOrNull(better)
            ?: pool.filter { it.quality <= 0 }.maxWithOrNull(better)
            ?: pool.minWithOrNull(better)
    }

    /** select() first, then every lower or equal tier best-first for verified downgrade. */
    fun playbackOrder(
        maxQuality: Int,
        supported: (Rendition) -> Boolean = { true },
    ): List<Rendition> {
        val pick = select(maxQuality, supported) ?: return emptyList()
        val alternates =
            renditions
                .filter { it !== pick && it.quality <= pick.quality }
                .sortedWith(better.reversed())
        return listOf(pick) + alternates
    }

    companion object {
        private val AVC_LABELS = setOf("h264", "avc", "avc1", "avc3")
    }
}

class ContentHttpException(val statusCode: Int) : IOException("内容请求失败（HTTP $statusCode）")

private class PlaybackModelRejectedException(val code: Int) : IOException("播放接口暂不可用（错误码 $code）")
private class PlaybackServiceBackoffException : IOException("播放服务繁忙，请稍后重试")

class SearchSessionExpiredException : IOException("搜索结果已过期，请从第 1 页重新搜索")

/** `detail` 仅携带路径代号、HTTP/字节数/协议与注册结果，不含地址与凭据。 */
class EmptyPlaybackResponseException(val detail: String? = null, internal val allCompatibilityModelsEmpty: Boolean = false) :
    IOException("播放接口暂时返回空响应，请稍后重试")

class PlaybackDiagnosticException(val detail: String) : IOException("播放恢复诊断")

/** Classify empty playback responses before JSON parsing so bounded recovery can handle them. */
internal fun playbackJson(raw: String, stage: String): JSONObject {
    val text = raw.trim().removePrefix("\uFEFF").trim()
    if (text.isEmpty()) throw EmptyPlaybackResponseException("stage=$stage")
    return try { JSONObject(text) } catch (_: org.json.JSONException) {
        throw IOException(if (stage == "device-register") "播放设备初始化响应格式异常，请重试" else "播放信息响应格式异常，请重试")
    }
}

/** 连续前台空失败的恢复阈值；全路径已知为空时，还可在本轮剩余额度内提前恢复。 */
private const val IDENTITY_ROTATE_ROUNDS = 2

class ContentRepository
@JvmOverloads
constructor(
    val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .cookieJar(MemoryCookies())
            .build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession(),
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    // 签名时钟与应用时钟同源：测试可注入，重签后的 ts/_rticket 保持在同一时间基上。
    private val signer = Signer(clock)

    // 有界恢复状态：部分采样中新会话可以恢复，但不能保证消除所有空响应。
    // prefetch/试播/正式播放可能并发，统一持锁；轮换的网络 I/O 在锁外执行。
    private val playbackIdentityLock = Any()
    private var emptyPlaybackRounds = 0
    private var emptyPlaybackRotated = false
    private var playbackFailureGeneration = 0
    private var playbackRotationResult: Boolean? = null
    private var playbackRotationAt = 0L

    // 榜单页偶发被 CDN 软拦截（空响应或挂起到超时）：收紧超时并单次重试，
    // 让整榜失败在最坏约 15 秒内暴露，而不是分钟级的全局超时。
    private val rankHttp: OkHttpClient by lazy {
        http
            .newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val site = "https://hongguoduanju.com"
    private val suggestionWebId = UUID.randomUUID().toString()

    private data class SearchCursor(
        val searchId: String,
        val passback: String,
        val offset: Int?,
        val updatedAt: Long,
    )

    private val searchCursors = linkedMapOf<Triple<ContentType, String, Int>, SearchCursor>()

    private data class UpcomingPlaybackModel(val entry: JSONObject, val savedAt: Long)

    private val upcomingPlaybackModels = linkedMapOf<String, UpcomingPlaybackModel>()
    private val playbackModelOwner = Any()
    private val requestedPlaybackModels = linkedSetOf<String>()

    // 构造并执行内容请求，统一检查 HTTP 状态和响应大小；仅按规则重试临时故障。
    private fun text(
        url: String,
        signed: Signer.Request? = null,
        retryTransient: Boolean = false,
        client: OkHttpClient = http,
        retryEmptyPlayback: Boolean = false,
    ): String {
        val request = signedRequest(url, signed)
        var retries = 0
        while (true) {
            try {
                return fetchOnce(request, client, refreshTicket = retryEmptyPlayback)
            } catch (e: IOException) {
                val temporary = PlayRetryPlan.temporary(e)
                val delay =
                    if (e is EmptyPlaybackResponseException) listOf(700L, 1500L).getOrNull(retries)
                    else if (retries == 0) 700L else null
                if (
                    !retryTransient ||
                        !temporary ||
                        delay == null ||
                        Thread.currentThread().isInterrupted
                )
                    throw e
                // 空响应最多三次读取；连接故障仍只重试一次，不改变搜索、榜单的原有预算。
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("请求已取消")
                }
                retries++
            }
        }
    }

    // 构造内容请求：站点 UA 与语言头在前，签名头按序覆盖；供 text() 与播放信息重试循环共用。
    private fun signedRequest(url: String, signed: Signer.Request?): Request.Builder {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", VendorConstants.VIDEO_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
        if (signed == null && request.build().url.host == "hongguoduanju.com") {
            request.header("Referer", "$site/")
            // Search rejects the abbreviated browser UA; retain the complete client UA there.
            if (request.build().url.encodedPath != "/api/search/result")
                request.header("User-Agent", "Mozilla/5.0")
        }
        signed?.headers?.forEach { (k, v) -> request.header(k, v) }
        signed?.body?.let {
            request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        return request
    }

    /** Sign the final query and body together, using the same registered identity in Medusa.
     * Never refresh _rticket after signing; each retry builds a new signature instead.
     */
    private fun playbackRequest(
        base: String,
        identity: PlaybackDeviceSession.Profile,
        extra: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): Request.Builder {
        val profile = identity.values + mapOf(
            "version_code" to "73932", "manifest_version_code" to "73932",
            "update_version_code" to "73932", "pv_player" to "73932",
            "version_name" to "7.3.9.32",
        )
        val signed = identity.signer(clock, profile)
            .signPlayback(base, profile + extra, body, now = clock())
        return signedRequest(signed.url, signed).apply {
            if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
        }
    }

    /** 单集播放协议使用真实 Gorgon/Argus/Ladon；不能混用旧 Medusa 签名或账户 Cookie。 */
    private fun singleVideoModel(id: String, identity: PlaybackDeviceSession.Profile,
        trace: PlaybackRequestTrace, client: OkHttpClient): JSONObject {
        val now = clock()
        val allowed = setOf("ac", "channel", "aid", "app_name", "version_code", "version_name",
            "device_platform", "os", "ssmix", "device_type", "device_brand", "language", "os_api",
            "os_version", "manifest_version_code", "resolution", "dpi", "update_version_code", "device_id", "iid")
        val profile = identity.values.filterKeys { it in allowed } + mapOf(
            "version_code" to "73532", "version_name" to "7.3.5.32", "manifest_version_code" to "73532",
            "update_version_code" to "73532", "_rticket" to now.toString())
        val query = Signer.encode(profile.toSortedMap())
        val payload = """{"biz_param":{"need_all_video_definition":true,"video_platform":3},"content_type":1,"video_id":"$id"}""".toByteArray()
        val request = Request.Builder()
            .url("https://${PlayRetryPlan.host(0)}/novel/player/video_model/v1/?$query")
            .header("User-Agent", VendorConstants.VIDEO_UA.replace("/71332", "/73532"))
            .header("Accept", "application/json").header("Sdk-Version", "2").header("X-XS-From-Web", "0")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
        PlayerSignature().headers(query, payload, identity.values.getValue("device_id"), now)
            .forEach { (key, value) -> request.header(key, value) }
        // The caller may have a catalogue cookie jar; this protocol must remain anonymous.
        val anonymous = client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
        val raw = fetchPlaybackModel(request, anonymous, trace, PlaybackRequestTrace.Stage.SINGLE)
        val root = try { JSONObject(raw) } catch (_: Exception) { throw IOException("播放信息格式异常") }
        val code = root.optInt("code", root.optInt("Code", 0))
        if (code != 0) throw PlaybackModelRejectedException(code)
        val data = root.optJSONObject("data") ?: throw IOException("播放信息为空")
        val encoded = data.opt("video_model")
        val model = when (encoded) {
            is JSONObject -> encoded
            is String -> runCatching { JSONObject(encoded) }.getOrElse {
                runCatching { JSONObject(String(decode64(encoded), Charsets.UTF_8)) }.getOrNull()
            }
            else -> data.takeIf { it.optJSONArray("video_list") != null }
        } ?: throw IOException("播放信息格式异常")
        return JSONObject().put("video_model", model)
    }

    /** 单次请求执行：HTTP 状态、响应大小与空响应判定；重试策略由调用方决定。 */
    private fun fetchOnce(
        request: Request.Builder, client: OkHttpClient, refreshTicket: Boolean,
        rejectEmptyPlayback: Boolean = refreshTicket,
        observed: ((Int, Int?, okhttp3.Protocol) -> Unit)? = null,
    ): String {
        val next =
            request.build().let { original ->
                if (refreshTicket)
                    original
                        .newBuilder()
                        .url(
                            original.url
                                .newBuilder()
                                .setQueryParameter("_rticket", clock().toString())
                                .build()
                        )
                        .build()
                else original
            }
        return RequestScope.execute(client.newCall(next)) { response ->
            if (!response.isSuccessful) {
                observed?.invoke(response.code, null, response.protocol)
                throw ContentHttpException(response.code)
            }
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16 * 1024 * 1024) throw IOException("内容响应过大")
            val input = body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= 16 * 1024 * 1024) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            val bytes = out.toByteArray()
            if (bytes.size > 16 * 1024 * 1024) throw IOException("内容响应过大")
            observed?.invoke(response.code, bytes.size, response.protocol)
            val content = String(bytes, Charsets.UTF_8)
            if (rejectEmptyPlayback && content.isBlank()) {
                if (!response.header("Retry-After").isNullOrBlank()) throw PlaybackServiceBackoffException()
                throw EmptyPlaybackResponseException()
            }
            content
        }
    }

    private fun fetchPlaybackModel(
        request: Request.Builder,
        client: OkHttpClient,
        trace: PlaybackRequestTrace,
        stage: PlaybackRequestTrace.Stage,
    ): String {
        var recorded = false
        val host = request.build().url.host
        return try {
            fetchOnce(request, client, refreshTicket = false, rejectEmptyPlayback = true) { code, bytes, protocol ->
                recorded = true
                trace.response(stage, host, code, bytes, protocol)
            }
        } catch (problem: IOException) {
            if (!recorded) trace.failed(stage, host, problem)
            throw problem
        }
    }

    private fun resetPlaybackFailures() = synchronized(playbackIdentityLock) {
        emptyPlaybackRounds = 0
        emptyPlaybackRotated = false
        playbackRotationResult = null
        playbackFailureGeneration++
    }

    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")

    private fun card(item: JSONObject): Series {
        val o = item.optJSONObject("video_data") ?: item
        val count =
            o.optInt(
                "episode_cnt",
                o.optJSONObject("series_episode_info")?.optInt("episode_cnt", 0) ?: 0,
            )
        val tags = o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(
            o.str("series_id"),
            o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },
            cleanUrl(o.str("series_cover")),
            o.str("series_intro"),
            o.str("episode_right_text").ifEmpty { if (count > 0) "全 ${count} 集" else "" },
            (0 until minOf(tags.length(), 5)).joinToString(" · ") {
                tags.optJSONObject(it)?.str("name") ?: tags.optString(it)
            },
        )
    }

    private fun valid(rows: List<Series>) =
        rows
            .filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }
            .distinctBy { it.id }

    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"), page)
    }

    private val warmRanks = WarmPageCache<CatalogPage>(20)
    private val warmDiscovery = WarmPageCache<FilteredDiscoveryPage>(4)
    private val warmDetails = WarmPageCache<Detail>(12)

    fun warmRankPage(board: RankBoard, page: Int): CatalogPage = fetchRankPage(board, page).also {
        warmRanks.put("${board.name}:$page", it)
    }
    fun warmDiscovery(): FilteredDiscoveryPage = fetchFilteredDiscovery(DiscoverySelection(), null).also {
        warmDiscovery.put("default", it)
    }
    fun warmDetail(id: String) { warmDetails.put(id, fetchDetail(id)) }

    fun rankPage(board: RankBoard, page: Int = 1): CatalogPage =
        warmRanks.take("${board.name}:$page") ?: fetchRankPage(board, page)

    private fun fetchRankPage(board: RankBoard, page: Int): CatalogPage {
        require(page in 1..100)
        // 榜单走收紧超时的专用客户端并重试一次瞬时失败；ttwid 由内存 Cookie 罐自动携带。
        val body =
            text("$site/rank/${board.route}?page=$page", retryTransient = true, client = rankHttp)
        return ComicRank.parse(body, page, board)
    }

    fun rankSnapshot(board: RankBoard): RankSnapshot {
        val first = rankPage(board)
        val meta = first.ranking ?: throw IOException("榜单信息缺失")
        // Public boards currently expose 100 entries across five pages. Never claim
        // a complete board if the upstream size changes beyond this bounded request.
        if (meta.totalPages > 5) throw IOException("榜单页数已变化，请稍后重试")
        val rows = first.items.toMutableList()
        val positions = meta.positions.toMutableMap()
        for (page in 2..meta.totalPages) {
            val next = rankPage(board, page)
            if (next.ranking?.totalPages != meta.totalPages) throw IOException("榜单正在更新，请刷新重试")
            rows += next.items
            positions.putAll(next.ranking.positions)
        }
        return RankSnapshot(board, rows.distinctBy { it.id }, meta.copy(positions = positions))
    }

    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if (type == ContentType.COMIC) return comicRanking(page)
        val data = router("$site/category?tab=1&sort_type=1" + if (page > 1) "&page=$page" else "")
        val section =
            data.optJSONObject("category_page")
                ?: data.optJSONObject("category_$")
                ?: throw IOException("首页数据结构已变化")
        val rows =
            section.optJSONArray("recommendList")
                ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList")
                ?: throw IOException("首页数据暂不可用")
        val items = valid(rows.objects().map(::card))
        val lastPage = section.optJSONObject("pagination")?.optInt("totalPages", 100) ?: 100
        return CatalogPage(items, items.isNotEmpty() && page < minOf(lastPage, 100))
    }

    fun filteredDiscovery(
        selection: DiscoverySelection,
        cursor: DiscoveryCursor? = null,
    ): FilteredDiscoveryPage =
        (if (cursor == null && selection.values.isEmpty()) warmDiscovery.take("default") else null)
            ?: fetchFilteredDiscovery(selection, cursor)

    private fun fetchFilteredDiscovery(selection: DiscoverySelection, cursor: DiscoveryCursor?): FilteredDiscoveryPage {
        val values = VendorConstants.device.toMutableMap()
        for (key in
            listOf(
                "version_code",
                "manifest_version_code",
                "update_version_code",
                "pv_player",
            )) values[key] = "73532"
        values["version_name"] = "7.3.5.32"
        val signed =
            signer.sign(
                "https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/",
                values,
                selection.request(cursor).toString().toByteArray(Charsets.UTF_8),
            )
        return FilteredDiscoveryParser.parse(
            text(signed.url, signed, retryTransient = true),
            cursor,
        )
    }

    fun discover(category: DramaCategory, topic: String = "", page: Int = 1): CatalogPage {
        require(
            page in 1..100 && (topic.isEmpty() || topic.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
        )
        val path = "/category/${category.route}" + if (topic.isBlank()) "" else "/$topic"
        val data = router("$site$path?page=$page")
        val section =
            data.optJSONObject("category_$")
                ?: data.optJSONObject("category_page")
                ?: throw IOException("分类找剧数据已变化")
        val info = DiscoveryParser.info(section, category, topic, page)
        val rows = section.optJSONArray("recommendList") ?: throw IOException("分类剧集列表缺失")
        val items = valid(rows.objects().map(::card))
        if (rows.length() > 0 && items.isEmpty()) throw IOException("分类剧集格式已变化")
        return CatalogPage(items, items.isNotEmpty() && page < info.totalPages, discovery = info)
    }

    // 按内容类型执行关键词搜索；分页状态由对应的搜索实现维护。
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length <= 80 && page in 1..100)
        // The website search does not constrain content type. Never use it for comics.
        if (type == ContentType.COMIC) return appSearch(keyword, page, type)
        try {
            val result = appSearch(keyword, page, type)
            if (result.items.isNotEmpty() || !result.hasMore) return result
        } catch (e: Exception) {
            if (e is SearchSessionExpiredException) throw e
            /* Website fallback for short dramas only. */ }
        return searchAll(keyword, page, type)
    }

    /** Anonymous first-party website suggestions; pinyin matching is provided by the server. */
    // 获取服务端搜索联想，供遥控搜索输入框展示候选词。
    fun searchSuggestions(keyword: String): List<String> {
        val clean = keyword.trim()
        if (clean.isEmpty()) return emptyList()
        require(clean.length <= 80)
        val raw =
            text(
                "$site/incent_resource/suggestion?app_id=8662&web_id=$suggestionWebId&query=${Signer.encode(clean)}&count=8"
            )
        val rows = JSONObject(raw).optJSONArray("suggest_list") ?: throw IOException("搜索联想暂不可用")
        return rows
            .objects()
            .map {
                it.str("name").ifBlank {
                    it.optJSONArray("display_words")?.optString(0).orEmpty().trim()
                }
            }
            .filter { it.isNotBlank() && it.length <= 80 }
            .distinct()
            .take(8)
    }

    /** The public website now loads cross-category results through its JSON endpoint. */
    fun searchAll(keyword: String, page: Int = 1, type: ContentType? = null): CatalogPage {
        require(keyword.isNotBlank() && keyword.length <= 80 && page in 1..100)
        val raw =
            text(
                "$site/api/search/result?query=${Signer.encode(keyword)}&page=$page",
                retryTransient = true,
            )
        val section =
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw IOException("搜索响应格式已变化，请稍后重试")
            }
        if (section.has("pageNum") && section.optInt("pageNum") != page)
            throw IOException("搜索页码不匹配，请重新搜索")
        val rows = section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val filtered = rows.objects().filter {
            val genre = it.optJSONObject("video_data")?.str("genre").orEmpty()
            type == null || genre == (if (type == ContentType.COMIC) "205" else "203") ||
                (type == ContentType.SHORT && genre.isBlank())
        }
        val items = SearchPayload.prioritize(valid(filtered.map(::card)), keyword)
        val more =
            when {
                section.has("hasMore") -> section.optBoolean("hasMore")
                section.optInt("totalPages") > 0 -> page < section.optInt("totalPages")
                else -> page < 100
            }
        // Filtering a recommendation-only page must not hide later matching pages.
        return CatalogPage(items, rows.length() > 0 && page < 100 && more)
    }

    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values = VendorConstants.device.toMutableMap()
        for (k in
            listOf(
                "version_code",
                "manifest_version_code",
                "update_version_code",
                "pv_player",
            )) values[k] = "72232"
        values["version_name"] = "7.2.2.32"
        values.putAll(
            linkedMapOf(
                "query" to keyword,
                "count" to "20",
                "offset" to ((page - 1) * 20).toString(),
                "tab_type" to type.searchTab.toString(),
                "bookshelf_search_plan" to "4",
                "use_correct" to "true",
                "user_is_login" to "0",
            )
        )
        val cursor =
            synchronized(searchCursors) {
                val now = clock()
                searchCursors.entries.removeAll {
                    now - it.value.updatedAt > 300000 ||
                        (page == 1 && it.key.first == type && it.key.second == keyword)
                }
                searchCursors[Triple(type, keyword, page - 1)]
            }
        if (type == ContentType.COMIC && page > 1 && cursor == null)
            throw SearchSessionExpiredException()
        cursor?.let {
            if (it.searchId.isNotBlank()) values["search_id"] = it.searchId
            if (it.passback.isNotBlank()) values["passback"] = it.passback
            it.offset
                ?.takeIf { offset -> offset >= 0 }
                ?.let { offset -> values["offset"] = offset.toString() }
        }
        val signed =
            signer.sign(
                "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",
                values,
            )
        val data = JSONObject(text(signed.url, signed))
        if (data.optInt("code", -1) != 0) throw IOException("搜索接口暂不可用")
        val tabs =
            data.optJSONArray("search_tabs")
                ?: data.optJSONObject("data")?.optJSONArray("search_tabs")
                ?: throw IOException("搜索格式异常")
        val tab =
            tabs.objects().firstOrNull { it.optInt("tab_type") == type.searchTab }
                ?: throw IOException("${type.label}搜索暂不可用")
        val list = mutableListOf<Series>()
        for (cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells = mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }
                .getOrNull()
                ?.let { cells.addAll(it) }
            for (row in cells) {
                val video = row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id =
                    video.str("series_id").ifEmpty {
                        row.str("book_id").ifEmpty { row.str("search_result_id") }
                    }
                val title =
                    row.optJSONObject("search_high_light")
                        ?.optJSONObject("title")
                        ?.str("text")
                        .orEmpty()
                        .ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count = video.optInt("episode_cnt")
                list +=
                    Series(
                        id,
                        title,
                        cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),
                        video.str("video_desc"),
                        if (count > 0) "全 $count 集" else video.str("rec_text"),
                        video.str("sub_title"),
                    )
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type, keyword, page)] =
                SearchCursor(
                    tab.str("search_id"),
                    tab.str("passback"),
                    tab.str("next_offset").toIntOrNull(),
                    clock(),
                )
            while (searchCursors.size > 100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list), tab.optBoolean("has_more", false) && page < 100)
    }

    private val producerCredits = linkedMapOf<String, ProducerCredit>()

    private fun producerMetadata(ids: List<String>): Map<String, ProducerCredit> {
        require(ids.all { it.matches(Regex("[0-9]{1,30}")) })
        val result = synchronized(producerCredits) { producerCredits.filterKeys { it in ids } }.toMutableMap()
        for (batch in ids.distinct().filterNot { it in result }.chunked(20)) {
            val signed = signer.sign(
                "https://api5-normal-sinfonlinec.fqnovel.com/reading/bookapi/multi-detail/v1/",
                VendorConstants.device + mapOf("book_id" to batch.joinToString(","), "book_type" to "2"),
            )
            val rows = Producers.parse(JSONObject(text(signed.url, signed))).filterKeys { it in batch }
            result.putAll(rows)
            synchronized(producerCredits) {
                producerCredits.putAll(rows)
                while (producerCredits.size > 200) producerCredits.remove(producerCredits.keys.first())
            }
        }
        return result
    }

    fun producer(id: String): Producer? = producerMetadata(listOf(id))[id]?.producer

    /** Known related works are useful even when the upstream search service is unavailable. */
    fun relatedProducerWorks(producer: Producer, current: Series): List<Series> {
        val candidates = listOf(current) + related(current.id).filterNot { it.upcoming }.map { it.series }
        return Producers.verified(producer, candidates, producerMetadata(candidates.map { it.id }))
    }

    /** Search is a candidate source, never proof of attribution or a complete filmography. */
    fun producerWorks(producer: Producer, page: Int = 1): ProducerPage {
        require(producer.name.isNotBlank() && producer.name.length <= 200 && page in 1..100)
        val raw = JSONObject(text("$site/api/search/result?query=${Signer.encode(producer.name)}&page=$page"))
        val rows = raw.optJSONArray("searchList") ?: throw IOException("制作方作品检索暂不可用，请稍后重试")
        if (raw.has("pageNum") && raw.optInt("pageNum") != page) throw IOException("作品页码不匹配，请重新打开")
        val candidates = valid(rows.objects().filter {
            it.optJSONObject("video_data")?.str("genre") in listOf("203", "205")
        }.map(::card))
        val verified = Producers.verified(producer, candidates, producerMetadata(candidates.map { it.id }))
        val more = when {
            raw.has("hasMore") -> raw.optBoolean("hasMore")
            raw.optInt("totalPages") > 0 -> page < raw.optInt("totalPages")
            else -> rows.length() > 0
        }
        return ProducerPage(verified, more && page < 100)
    }

    private val relatedCache = linkedMapOf<String, List<RelatedWork>>()

    fun related(id: String, force: Boolean = false): List<RelatedWork> {
        require(id.matches(Regex("[0-9]{1,30}")))
        if (!force)
            synchronized(relatedCache) {
                relatedCache[id]?.let {
                    return it
                }
            }
        var failure: Exception? = null
        val values =
            VendorConstants.device.toMutableMap().apply {
                for (key in
                    listOf(
                        "version_code",
                        "manifest_version_code",
                        "update_version_code",
                        "pv_player",
                    )) put(key, "72232")
                put("version_name", "7.2.2.32")
                putAll(
                    mapOf(
                        "scene" to "10",
                        "from" to "detail_page_more_related",
                        "book_id" to id,
                        "new_search_middle_page" to "false",
                        "bookstore_tab_type" to "0",
                        "query_history_removed" to "false",
                        "user_is_login" to "0",
                        "bookstore_tab" to "0",
                        "search_middle_page_version" to "0",
                        "pad_column_detail" to "0",
                        "hot_word_exchange" to "false",
                        "device_level" to "3",
                        "limit" to "0",
                        "offset" to "0",
                        "is_horizontal_screen" to "false",
                        "reader_single_col_from_ip_strategy" to "false",
                    )
                )
            }
        for (host in
            listOf(
                "api5-normal-sinfonlinec.fqnovel.com",
                "api5-normal-hl.fqnovel.com",
                "api5-normal-lf.fqnovel.com",
            )) {
            try {
                val request = signer.sign("https://$host/reading/bookapi/plan/v:version/", values)
                val raw = text(request.url, request)
                if (raw.isBlank()) {
                    failure = IOException("相关作品接口暂无响应")
                    continue
                }
                val rows = RelatedWorks.parse(raw, id)
                synchronized(relatedCache) {
                    if (relatedCache.size >= 8) relatedCache.remove(relatedCache.keys.first())
                    relatedCache[id] = rows
                }
                return rows
            } catch (problem: Exception) {
                if (Thread.currentThread().isInterrupted) throw problem
                failure = problem
            }
        }
        throw IOException("相关作品暂时无法加载，请重试", failure)
    }

    fun detail(id: String): Detail = warmDetails.take(id) ?: fetchDetail(id)

    private fun fetchDetail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o =
            router("$site/detail?series_id=$id")
                .optJSONObject("detail_page")
                ?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw = o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes = (0 until raw.length()).map { raw.getString(it) }
        if (
            episodes.isEmpty() ||
                episodes.any { !it.matches(Regex("[0-9]{1,30}")) } ||
                episodes.distinct().size != episodes.size
        )
            throw IOException("分集数据异常")
        return Detail(card(o).copy(id = id), episodes)
    }

    /**
     * 官方 App 现行的播放模型接口：`GET /reading/bookapi/video_tab/multi_video_model/v1/`。
     *
     * 服务端对播放模型的放行策略按时间窗与「身份 × 路径」波动，注释结论均须带日期快照：
     * - 2026-11-02：旧 `POST /novel/player` 全域名 200+0 字节（无签名形态），GET video_tab 出五档。
     * - 2026-11-05：无签名请求（含本 GET）被验签阶段短路返回空；带签名 POST 曾恢复五档。
     * - 2026-11-06 `--signmatrix` 复测：无签名 GET 稳定空；签名 GET/POST 均可出五档但随身份
     *   波动；同日后续采样中，新连接仍空，同身份同一集静默一分钟后仍空，新会话首发可用。
     *   尚不能区分频率限制、签名/请求指纹校验与身份/路径限制，轮换不保证恢复。
     *   当日缺 Cookie 或 x-ss-stub 的 POST 形态均空，保留完整签名与会话形态。
     * 官方 APK（classes5.dex 的 `RpcOperation`）仍保留这条 GET 路径，参数取自
     * `GetVideoModelRequest` 的 `@SerializedName`（snake_case）；缺任一字段服务端回
     * `100103 PARAM_INVALID`。
     *
     * 失败（非 0 code、空正文、结构不符、网络异常）一律返回 null，由调用方回退签名 POST 旧接口，
     * 调用方按会话健康度、次数和绝对截止时间选择后续路径。
     */
    private fun tabVideoModel(
        id: String,
        identity: PlaybackDeviceSession.Profile,
        trace: PlaybackRequestTrace = PlaybackRequestTrace(),
        transport: OkHttpClient = http,
        strict: Boolean = false,
    ): JSONObject? {
        val extra = mapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "",
        )
        // One preferred request; the legacy path retains the bounded cross-host retry budget.
        for (host in listOf(PlayRetryPlan.hosts.first())) {
            val request = playbackRequest(
                "https://$host/reading/bookapi/video_tab/multi_video_model/v1/",
                identity, extra,
            )
            val text =
                try {
                    fetchPlaybackModel(request, transport, trace, PlaybackRequestTrace.Stage.GET)
                } catch (problem: IOException) {
                    if (strict || Thread.currentThread().isInterrupted) throw problem
                    continue
                } catch (problem: RuntimeException) {
                    continue
                }
            val root = runCatching { JSONObject(text) }.getOrNull()
            if (root == null) { trace.rejected(); continue }
            val code = root.optInt("code", root.optInt("Code", -1))
            if (code != 0) { trace.rejected(code); continue }
            val data = root.optJSONObject("data") ?: continue
            val rows = data.optJSONObject("video_model_data") ?: data
            val row =
                rows.optJSONObject(id)
                    ?: rows.keys().asSequence().mapNotNull { rows.optJSONObject(it) }.firstOrNull()
                    ?: continue
            val raw = row.opt("video_model") ?: continue
            val model =
                (when (raw) {
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrNull()
                    else -> null
                }) ?: continue
            if ((model.optJSONArray("video_list")?.length() ?: 0) == 0) continue
            return JSONObject().put("video_model", model)
        }
        return null
    }

    /** 诊断旁路：返回原始 video_list 各档（含 bytevc2），不参与正式播放路径；relay 探针专用。 */
    fun rawVideoModel(id: String): List<Rendition> {
        require(id.matches(Regex("[0-9]{1,30}")))
        val entry = tabVideoModel(id, playbackSession.profile(http)) ?: return emptyList()
        val model = entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        return model.optJSONArray("video_list")?.objects().orEmpty().mapNotNull { row ->
            runCatching {
                val meta = row.optJSONObject("video_meta") ?: return@runCatching null
                val encryption = row.optJSONObject("encrypt_info") ?: return@runCatching null
                val codec = meta.str("codec_type").lowercase()
                val url = cleanUrl(row.str("main_url"))
                if (codec.isBlank() || (!url.startsWith("https://") && !url.startsWith("http://")))
                    return@runCatching null
                val key =
                    encryption
                        .str("spade_a")
                        .takeIf(String::isNotBlank)
                        ?.let(MediaCrypto::deriveKey)
                if (
                    key == null &&
                        encryption.opt("encrypt") !in listOf(false, 0, "0", "false")
                )
                    return@runCatching null
                Rendition(
                    qualityNumber(meta.str("definition")),
                    codec,
                    dimension(meta, "vwidth", "width"),
                    dimension(meta, "vheight", "height"),
                    url,
                    key,
                )
            }.getOrNull()
        }
    }

    fun stream(
        id: String,
        maxQuality: Int = 1080,
        supported: (Rendition) -> Boolean = { true },
    ): StreamInfo {
        val selected =
            streamLadder(id).select(maxQuality, supported)
                ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val info = selected.toStreamInfo()
        return if (selected.quality > maxQuality) info.copy(quality = info.quality + "（兼容资源）")
        else info
    }

    /** v5 model requests use the same anonymous registration across foreground and prefetch. */
    fun streamLadderWithCompatibility(id: String, metadataAhead: List<String> = emptyList(), speculative: Boolean = false): StreamLadder =
        RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(if (speculative) 2 else 8)) {
            val profile = if (speculative) playbackSession.existing()
                ?: throw IOException("播放设备尚未就绪") else playbackSession.profile(http)
            MobilePlayback.ladder(id, profile.values, http, MobilePlayback.signer, clock())
        }

    /** Retained legacy transport for compatibility regression coverage. */
    internal fun legacyStreamLadderWithCompatibility(id: String, metadataAhead: List<String> = emptyList(), speculative: Boolean = false): StreamLadder {
        try {
            return streamLadder(id, metadataAhead, speculative)
        } catch (error: IOException) {
            RequestScope.ensureActive()
            if (!(error is EmptyPlaybackResponseException ||
                    error is ContentHttpException && error.statusCode in listOf(401, 403))) throw error
            if (speculative) {
                // Reuse an already registered device; background work must never register or rotate one.
                val device = devices.existing() ?: throw error
                try {
                    return RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)) { playerLadder(id, device) }
                } catch (fallback: IOException) {
                    RequestScope.ensureActive()
                    fallback.addSuppressed(error)
                    throw fallback
                }
            }
            try {
                // Registration and retries remain inside the existing playback deadline.
                val device = RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)) { devices.obtain() }
                return RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)) {
                    try { playerLadder(id, device) } catch (empty: EmptyPlaybackResponseException) {
                        if (!empty.allCompatibilityModelsEmpty) throw empty
                        RequestScope.ensureActive()
                        val replacement = try { devices.refreshEmpty(device) } catch (registration: IOException) {
                            RequestScope.ensureActive()
                            empty.addSuppressed(PlaybackDiagnosticException("compat-reg=failed"))
                            empty.addSuppressed(registration)
                            throw empty
                        }
                        if (replacement == null) {
                            empty.addSuppressed(PlaybackDiagnosticException("compat-reg=cooldown"))
                            throw empty
                        }
                        synchronized(upcomingPlaybackModels) { upcomingPlaybackModels.clear() }
                        try { playerLadder(id, replacement) } catch (again: IOException) {
                            RequestScope.ensureActive()
                            if (again is DeviceRejectedException && again.identityExpired) devices.rejectReplacement(replacement)
                            again.addSuppressed(PlaybackDiagnosticException("compat-reg=ok"))
                            again.addSuppressed(empty)
                            throw again
                        }
                    } catch (rejected: DeviceRejectedException) {
                        if (!rejected.identityExpired) throw rejected
                        RequestScope.ensureActive()
                        synchronized(upcomingPlaybackModels) { upcomingPlaybackModels.clear() }
                        val replacement = devices.refreshRejected(device)
                        try { playerLadder(id, replacement) } catch (again: DeviceRejectedException) {
                            if (again.identityExpired) devices.rejectReplacement(replacement)
                            throw again
                        }
                    }
                }
            } catch (fallback: IOException) {
                RequestScope.ensureActive()
                fallback.addSuppressed(error)
                throw fallback
            }
        }
    }

    /** 读取一集的可播档位；前台按会话路径健康度选择，后台只轻量准备一次。 */
    @JvmOverloads
    fun streamLadder(id: String, metadataAhead: List<String> = emptyList(), speculative: Boolean = false): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        require(metadataAhead.size <= 2 && metadataAhead.all { it.matches(Regex("[0-9]{1,30}")) })
        val firstRead = synchronized(requestedPlaybackModels) {
            val first = requestedPlaybackModels.add(id)
            while (requestedPlaybackModels.size > 64) requestedPlaybackModels.remove(requestedPlaybackModels.first())
            first
        }
        // 后续模型仅在内存保留 60 秒，领取即移除；失败重试不会反复复用同一个旧地址。
        val cached =
            synchronized(upcomingPlaybackModels) {
                val now = clock()
                upcomingPlaybackModels.entries.removeAll { now - it.value.savedAt !in 0..60_000 }
                upcomingPlaybackModels.remove(id)?.entry
            } ?: playbackSession.takeModel(id, playbackModelOwner, clock()).takeIf { firstRead }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(if (speculative) 2000 else 8000)
        val entry =
            cached
                ?: RequestScope.withDeadline(deadline) {
                    val ids = (listOf(id) + metadataAhead).distinct()
                    // 单集接口优先；旧批量接口仅保留为有界兼容回退，两者复用注册身份。
                    val trace = PlaybackRequestTrace()
                    fun transport(fresh: Boolean = false): OkHttpClient {
                        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                        if (remaining <= 0) throw java.net.SocketTimeoutException("播放信息读取超时")
                        return http.newBuilder()
                            .connectTimeout(minOf(remaining, 4000), TimeUnit.MILLISECONDS)
                            .readTimeout(minOf(remaining, 4000), TimeUnit.MILLISECONDS)
                            .callTimeout(remaining, TimeUnit.MILLISECONDS)
                            .apply { if (fresh) connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)) }
                            .build()
                    }
                    val previousIdentity = playbackSession.profile(transport())
                    // 后台不消耗前台失败额度，也不触发注册；前台按已知失效状态恢复一次。
                    fun refreshIdentity(previous: PlaybackDeviceSession.Profile): PlaybackDeviceSession.Profile {
                        RequestScope.ensureActive()
                        val rotationGeneration = synchronized(playbackIdentityLock) {
                            // 失败注册五秒后允许后续前台调用再试；替代身份仍失败则至少冷却三十秒。
                            // 只在后续请求中重新开放额度，不新增后台定时循环。
                            val cooldown = if (playbackRotationResult == false) 5_000L else 30_000L
                            if (emptyPlaybackRotated && playbackRotationResult != null &&
                                clock() - playbackRotationAt >= cooldown) {
                                emptyPlaybackRotated = false
                                playbackRotationResult = null
                            }
                            if (!speculative && !emptyPlaybackRotated) {
                                emptyPlaybackRotated = true
                                playbackRotationAt = clock()
                                playbackFailureGeneration
                            } else null
                        }
                        if (rotationGeneration != null) {
                            try {
                                val ready = playbackSession.rotate(transport(), previous)
                                RequestScope.ensureActive()
                                synchronized(playbackIdentityLock) {
                                    if (playbackFailureGeneration == rotationGeneration) {
                                        playbackRotationResult = ready
                                        playbackRotationAt = clock()
                                    }
                                }
                            } catch (error: IOException) {
                                synchronized(playbackIdentityLock) {
                                    if (playbackFailureGeneration == rotationGeneration) {
                                        emptyPlaybackRotated = false
                                        playbackRotationResult = null
                                        playbackFailureGeneration++
                                    }
                                }
                                throw error
                            }
                        }
                        return playbackSession.profile(transport())
                    }
                    val shouldRefresh = !speculative && (synchronized(playbackIdentityLock) {
                        emptyPlaybackRounds >= IDENTITY_ROTATE_ROUNDS
                    } || previousIdentity.routes.exhausted(clock()))
                    var identity = if (shouldRefresh) refreshIdentity(previousIdentity) else previousIdentity
                    val plan = identity.routes.plan(clock(), speculative)
                    val pendingRoutes = java.util.ArrayDeque(plan)
                    val payload =
                        """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":${JSONArray(ids)}}}"""
                            .toByteArray()
                    var current: JSONObject? = null
                    var responseMap: JSONObject? = null
                    var lastError: IOException? = null
                    var allEmpty = plan.isNotEmpty()
                    var postCount = 0
                    var emptyPosts = 0
                    var attempts = 0
                    while (attempts < (if (speculative) 1 else 4)) {
                        if (pendingRoutes.isEmpty()) {
                            // 已知路径全部为空时，在本轮剩余额度内恢复身份，不先弹错误再重跑整轮。
                            if (!speculative && identity.routes.exhausted(clock())) {
                                val replacement = refreshIdentity(identity)
                                if (replacement !== identity) {
                                    identity = replacement
                                    pendingRoutes.addAll(identity.routes.plan(clock(), false))
                                }
                            }
                            if (pendingRoutes.isEmpty()) break
                        }
                        val route = pendingRoutes.removeFirst()
                        val attempt = attempts++
                        val client = transport(fresh = attempt > 0)
                        if (!route.single) postCount++
                        try {
                            val row = if (route.single) {
                                singleVideoModel(id, identity, trace, client)
                            } else {
                                val request = playbackRequest(
                                    "https://${route.host}/novel/player/multi_video_model/v1/",
                                    identity, body = payload,
                                )
                                val body = fetchPlaybackModel(request, client, trace, PlaybackRequestTrace.Stage.POST)
                                val data = try { JSONObject(body) } catch (_: Exception) {
                                    throw IOException("播放信息格式异常")
                                }
                                val code = data.optInt("Code", data.optInt("code", 0))
                                if (code != 0) throw IOException("播放接口暂不可用（错误码 $code）")
                                val map = data.optJSONObject("data") ?: throw IOException("播放信息为空")
                                responseMap = map
                                map.optJSONObject(id) ?: (if (ids.size == 1)
                                    map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull() else null)
                                    ?: throw IOException("播放信息为空")
                            }
                            // 只有模型结构有效才记为健康，HTTP 200 或非空正文不能代表播放成功。
                            val model = row.optJSONObject("video_model") ?: runCatching { JSONObject(row.str("video_model")) }.getOrNull()
                            if (model == null || (model.optJSONArray("video_list")?.length() ?: 0) == 0 && !model.has("fallback_api"))
                                throw IOException("播放信息格式异常")
                            current = row
                            RequestScope.ensureActive()
                            identity.offerModel(id, row, playbackModelOwner, clock())
                            identity.routes.success(route, clock())
                            if (!speculative) resetPlaybackFailures()
                            break
                        } catch (error: IOException) {
                            RequestScope.ensureActive()
                            if (error is PlaybackServiceBackoffException ||
                                error is ContentHttpException && error.statusCode in 400..499 ||
                                error is PlaybackModelRejectedException && error.code != 100103) throw error
                            lastError = error
                            val empty = error is EmptyPlaybackResponseException
                            allEmpty = allEmpty && empty
                            if (!route.single && empty) emptyPosts++
                            if (route.single || empty || PlayRetryPlan.temporary(error)) identity.routes.failed(route, clock(), empty)
                            // 单集接口明确不支持该参数时可回退；拒绝/限流已在上方停止。批量格式错误不盲试。
                            if (!route.single && !PlayRetryPlan.temporary(error)) throw error
                        } finally {
                            if (attempt > 0) client.connectionPool.evictAll()
                        }
                    }
                    if (current == null) {
                        RequestScope.ensureActive()
                        if (!speculative) synchronized(playbackIdentityLock) {
                            emptyPlaybackRounds = if (allEmpty || postCount > 0 && emptyPosts == postCount) emptyPlaybackRounds + 1 else 0
                        }
                        val error = lastError
                        if (error == null || error is EmptyPlaybackResponseException) {
                            val rotation = synchronized(playbackIdentityLock) {
                                if (!emptyPlaybackRotated) "" else " rotated=1 reg=" +
                                    when (playbackRotationResult) { true -> "ok"; false -> "failed"; null -> "pending" }
                            }
                            throw EmptyPlaybackResponseException(
                                (if (plan.isEmpty()) "route=cooldown" else trace.summary()) + rotation,
                            )
                        }
                        throw error
                    }
                    val map = responseMap ?: JSONObject()
                    synchronized(upcomingPlaybackModels) {
                        ids.drop(1).forEach { next ->
                            map.optJSONObject(next)
                                ?.takeIf { row ->
                                    val model = row.opt("video_model")
                                    (model is JSONObject ||
                                        model is String && model.isNotBlank()) &&
                                        row.toString().toByteArray(Charsets.UTF_8).size <=
                                            256 * 1024
                                }
                                ?.let {
                                    upcomingPlaybackModels[next] =
                                        UpcomingPlaybackModel(it, clock())
                                }
                        }
                        while (upcomingPlaybackModels.size > 6) upcomingPlaybackModels.remove(
                            upcomingPlaybackModels.keys.first()
                        )
                    }
                    current
                }
        val model = entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        if (cached != null && !speculative) resetPlaybackFailures()
        // The current model already carries plain media URLs, dimensions and CENC keys.
        // Only use the legacy fallback endpoint when this shape has no usable rendition.
        val inline =
            model.optJSONArray("video_list")?.objects().orEmpty().mapNotNull { row ->
                runCatching {
                        val meta = row.optJSONObject("video_meta") ?: return@runCatching null
                        val encryption =
                            row.optJSONObject("encrypt_info") ?: return@runCatching null
                        val codec = meta.str("codec_type").lowercase()
                        val url = cleanUrl(row.str("main_url"))
                        if (
                            codec.isBlank() ||
                                codec in setOf("bytevc2", "bvc2") ||
                                (!url.startsWith("https://") && !url.startsWith("http://"))
                        )
                            return@runCatching null
                        val width = dimension(meta, "vwidth", "width") ?: return@runCatching null
                        val height = dimension(meta, "vheight", "height") ?: return@runCatching null
                        val key =
                            encryption
                                .str("spade_a")
                                .takeIf(String::isNotBlank)
                                ?.let(MediaCrypto::deriveKey)
                        if (
                            key == null &&
                                encryption.opt("encrypt") !in listOf(false, 0, "0", "false")
                        )
                            return@runCatching null
                        Rendition(
                            qualityNumber(meta.str("definition")),
                            codec,
                            width,
                            height,
                            url,
                            key,
                        )
                    }
                    .getOrNull()
            }
        if (inline.isNotEmpty()) return StreamLadder(inline)
        val raw = model.opt("fallback_api")
        val fallback =
            when (raw) {
                is JSONObject -> raw.str("fallback_api")
                is JSONArray -> raw.optString(0)
                else ->
                    runCatching { JSONObject(raw.toString()).str("fallback_api") }
                        .getOrDefault(raw.toString())
            }
        if (!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        var info =
            playbackJson(text(fallback), "video-info").optJSONObject("video_info")?.optJSONObject("data")
                ?: throw IOException("没有播放资源")
        var videoList = info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        val offered = videoList.keys().asSequence().mapNotNull(videoList::optJSONObject).toList()
        if (offered.isNotEmpty() && offered.all {
                it.str("main_url").isNotBlank() &&
                    it.str("codec_type").lowercase() in setOf("bytevc2", "bvc2")
            }) {
            // 官方播放器有 force_codec 和 codec_type=0 的兼容路径。仅全档 bytevc2 时
            // 额外读取一次；参数名不保证返回 h264，必须继续按实际模型与视频轨道选档。
            val compatible = fallback.toHttpUrl().newBuilder()
                .setQueryParameter("codec_type", "0")
                .setQueryParameter("force_codec", "h264")
                .build().toString()
            info = playbackJson(text(compatible), "compatible-video-info").optJSONObject("video_info")?.optJSONObject("data")
                ?: throw IOException("没有播放资源")
            videoList = info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        }
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4
        // extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows =
            videoList
                .keys()
                .asSequence()
                .mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }
                .filter {
                    it.second.str("main_url").isNotEmpty() &&
                        it.second.str("codec_type").lowercase() !in setOf("bytevc2", "bvc2")
                }
                .map { (k, o) ->
                    Triple(
                        qualityNumber(
                            o.str("quality_desc").ifEmpty {
                                o.str("height").ifEmpty {
                                    o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } }
                                }
                            }
                        ),
                        k,
                        o,
                    )
                }
                .sortedByDescending { it.first }
                .toList()
        val seed = info.str("key_seed")
        // A broken rendition entry must not take down otherwise playable siblings.
        val renditions =
            rows.mapNotNull { (quality, _, o) ->
                runCatching {
                        var url = cleanUrl(o.str("main_url"))
                        if (seed.isNotEmpty()) url = MediaCrypto.decryptUrl(url, decode64(seed))
                        if (!url.startsWith("https://") && !url.startsWith("http://"))
                            return@runCatching null
                        Rendition(
                            quality,
                            o.str("codec_type").lowercase(),
                            dimension(o, "vwidth", "width"),
                            dimension(o, "vheight", "height"),
                            url,
                            o.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey),
                        )
                    }
                    .getOrNull()
            }
        if (renditions.isEmpty()) throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        return StreamLadder(renditions)
    }

    private fun dimension(o: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            val value = o.optInt(key, 0)
            if (value > 0) return value
        }
        return null
    }

    private val devices = PlayerDeviceSource(http)
    internal fun registeredStream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        for(attempt in 0..1) {
            try { return playerStream(id,maxQuality) }
            catch(e: DeviceRejectedException) { devices.invalidate() }
            catch(e: Exception) { if(Thread.currentThread().isInterrupted) throw e; break }
        }
        return currentStream(id,maxQuality)
    }
    internal fun currentStream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/",VendorConstants.device + mapOf("video_id" to id,"video_id_type" to "0","device_level" to "1","need_all_video_definition" to "1","need_mp4_align" to "0","use_os_player" to "0","video_platform" to "1024","source" to "0","from_video_id" to ""))
        val data=try { JSONObject(playbackText(signed.url,signed)) } catch(e: org.json.JSONException) {
            throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        }
        PlaybackModels.checkStatus(data)
        val map=data.optJSONObject("data") ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        val model=PlaybackModels.model(map.optJSONObject("video_model_data") ?: map,id) ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        val direct=PlaybackModels.candidates(model)
        if(direct.isNotEmpty()) return PlaybackModels.pick(direct,maxQuality)
        val fallback=PlaybackModels.fallback(model) ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
        val response=try { JSONObject(playbackText(fallback)) } catch(e: org.json.JSONException) {
            throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        }
        PlaybackModels.checkStatus(response)
        val info=response.optJSONObject("video_info")?.optJSONObject("data")
            ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        return PlaybackModels.pick(PlaybackModels.candidates(info),maxQuality)
    }

    /** Playback-only HTTP classification; catalog/search transport stays unchanged. */
    private fun playbackText(url: String, signed: Signer.Request? = null): String {
        val request=Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (key,value) -> request.header(key,value) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if(response.code in 400..499 && response.code !in setOf(408,429))
                throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED,response.code)
            if(!response.isSuccessful) throw IOException("播放请求失败（HTTP ${response.code}）")
            val body=response.body ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val limit=16*1024*1024
            if(body.contentLength()>limit) throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); val input=body.byteStream()
            while(out.size()<=limit) { val count=input.read(buffer); if(count<0) break; out.write(buffer,0,count) }
            if(out.size()>limit) throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            String(out.toByteArray(),Charsets.UTF_8)
        }
    }
    private fun playerStream(id: String, maxQuality: Int): StreamInfo {
        val selected = playerLadder(id).select(maxQuality)
            ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val stream = selected.toStreamInfo()
        return if (selected.quality > maxQuality) stream.copy(quality = stream.quality + "（兼容资源）") else stream
    }

    private fun playerLadder(id: String, device: PlayerDevice = devices.obtain()): StreamLadder {
        val rows = mutableListOf<Rendition>()
        var rejected = false
        var identityExpired = false
        var sawResponse = false
        var emptyModels = 0
        val trace = PlaybackRequestTrace()
        var transportFailure: Exception? = null
        val requests = listOf(
            Triple(PlayerDeviceSource.substituteDevice(PlayerDeviceSource.SHORTPLAY_URL, device), PlayerDeviceSource.shortplayBody(id), mapOf("User-Agent" to "com.dragon.read", "Content-Type" to "application/json; charset=utf-8")),
            Triple(PlayerDeviceSource.substituteDevice(PlayerDeviceSource.PHOENIX_URL, device), PlayerDeviceSource.phoenixBody(id), mapOf("User-Agent" to VendorConstants.VIDEO_UA, "Accept" to "application/json; charset=utf-8,application/x-protobuf", "Content-Type" to "application/json", "x-xs-from-web" to "0", "sdk-version" to "2")),
        )
        for ((index, request) in requests.withIndex()) {
            val (url, body, headers) = request
            try {
                val signed = PlayerSigner.headers(url.substringAfter('?', ""), body)
                val data = playbackJson(fetch(url, headers + signed, body, trace,
                    if (index == 0) PlaybackRequestTrace.Stage.COMPAT_SHORT else PlaybackRequestTrace.Stage.COMPAT_MULTI), "compatibility-model")
                val code = data.optInt("code", data.optInt("Code", 0))
                if (code != 0) trace.rejected(code)
                if (code == 10001 || code == 10002 || code == 110001) throw DeviceRejectedException("播放接口拒绝该设备", identityExpired = true)
                sawResponse = true
                if(code!=0) throw IOException("播放接口拒绝请求（错误码 $code）")
                val payload=data.optJSONObject("data") ?: JSONObject()
                val model=PlaybackModels.model(payload,id) ?: continue
                for (model in listOf(model)) {
                    rows += PlaybackModels.renditions(model)
                    val fallbackUrl = StreamModels.fallbackApiUrl(model)
                    if (fallbackUrl.startsWith("https://")) runCatching {
                        val fallback = playbackJson(fetch(fallbackUrl, mapOf("User-Agent" to PlayerDeviceSource.FALLBACK_USER_AGENT, "Accept-Language" to "zh-CN,zh;q=0.9"), null), "compatibility-video-info")
                        val info = fallback.optJSONObject("video_info")?.optJSONObject("data")
                        if (info != null) rows += PlaybackModels.renditions(info)
                    }.onFailure { if (it is IOException) transportFailure = it }
                }
            } catch (e: EmptyPlaybackResponseException) { emptyModels++; transportFailure = e }
            catch (e: DeviceRejectedException) { rejected = true; identityExpired = identityExpired || e.identityExpired }
            catch (e: Exception) { if(Thread.currentThread().isInterrupted) throw e; transportFailure = e }
        }
        if (rows.isEmpty()) {
            if (rejected) throw DeviceRejectedException("播放设备已被上游拒绝", identityExpired).apply {
                addSuppressed(PlaybackDiagnosticException("stage=compatibility-model ${trace.summary()}"))
            }
            if (transportFailure is EmptyPlaybackResponseException) throw EmptyPlaybackResponseException(
                "${transportFailure.detail ?: "stage=compatibility-model"} ${trace.summary()}", emptyModels == requests.size)
            transportFailure?.addSuppressed(PlaybackDiagnosticException("stage=compatibility-model ${trace.summary()}"))
            if (!sawResponse) throw transportFailure ?: IOException("播放接口暂不可用")
            throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集").apply {
                addSuppressed(PlaybackDiagnosticException("stage=compatibility-model ${trace.summary()}"))
            }
        }
        return StreamLadder(rows.distinctBy { it.url })
    }

    private fun fetch(url: String, headers: Map<String, String>, body: ByteArray?,
        trace: PlaybackRequestTrace? = null, stage: PlaybackRequestTrace.Stage = PlaybackRequestTrace.Stage.GET): String {
        val request = Request.Builder().url(url).header("Accept-Language", "zh-CN,zh;q=0.9")
        headers.forEach { (k, v) -> request.header(k, v) }
        body?.let { request.post(it.toRequestBody("application/json; charset=UTF-8".toMediaType())) }
        var observed = false
        return try { RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) {
                trace?.response(stage, request.build().url.host, response.code, null, response.protocol)
                observed = true
            }
            if (response.code == 401 || response.code == 403) throw DeviceRejectedException("播放请求被拒绝（HTTP ${response.code}）")
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val bodyStream = response.body ?: throw IOException("内容响应为空")
            if (bodyStream.contentLength() > 16 * 1024 * 1024) throw IOException("内容响应过大")
            val input = bodyStream.byteStream(); val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() <= 16 * 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
            if (out.size() > 16 * 1024 * 1024) throw IOException("内容响应过大")
            trace?.response(stage, request.build().url.host, response.code, out.size(), response.protocol)
            observed = true
            val content = String(out.toByteArray(), Charsets.UTF_8)
            // An explicit upstream backoff is not evidence of a stale identity.
            if (trace != null && content.isBlank() && !response.header("Retry-After").isNullOrBlank())
                throw PlaybackServiceBackoffException()
            content
        } } catch (error: IOException) {
            if (!observed) trace?.failed(stage, request.build().url.host, error)
            throw error
        }
    }
    companion object {
        @Volatile var deviceStore: PlayerDeviceStore? = null
        fun extractRouter(html: String): JSONObject {
            val marker = html.indexOf("_ROUTER_DATA")
            if (marker < 0) throw IOException("页面没有内容数据")
            val first = html.indexOf('{', marker)
            if (first < 0) throw IOException("页面数据格式错误")
            var depth = 0
            var quoted = false
            var escaped = false
            for (i in first until html.length) {
                val ch = html[i]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                    continue
                }
                when (ch) {
                    '"' -> quoted = true
                    '{',
                    '[' -> depth++
                    '}',
                    ']' -> {
                        depth--
                        if (depth == 0) return JSONObject(html.substring(first, i + 1))
                    }
                }
            }
            throw IOException("页面数据不完整")
        }

        fun qualityNumber(value: String): Int =
            Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt()
                ?: mapOf("1920" to 1080, "1280" to 720, "1024" to 576, "854" to 480, "640" to 360)[
                    value]
                ?: value.toIntOrNull()
                ?: 0
    }
}
