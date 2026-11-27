// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 推荐流接口适配：处理推荐请求、分页游标和内容结果。

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Pagination cursor; only bounded, account/source-scoped snapshots are used for cold starts. */
class RecommendationCursor
internal constructor(
    @Volatile internal var session: String = "",
    @Volatile internal var offset: Int = 0,
) {
    internal val seen = linkedSetOf<String>()
    internal var expectedPage = 1
    internal var lastPage = 0
    internal var lastResult: CatalogPage? = null
    internal data class CellFlow(val cellId: String, val planId: String, val versionTag: String)
    @Volatile internal var cellFlow: CellFlow? = null
    internal var needsBootstrap = false
    @Volatile internal var unsignedFallback = false
    @Volatile internal var signedProfile: PlaybackDeviceSession.Profile? = null

    internal fun restartServer() {
        session = ""
        offset = 0
        cellFlow = null
        needsBootstrap = false
    }

    fun nextBatch(restartSession: Boolean = false) =
        RecommendationCursor(if (restartSession) "" else session, if (restartSession) 0 else offset)
            .also {
                it.remember(seenIds())
                if (!restartSession) {
                    it.cellFlow = cellFlow
                    it.needsBootstrap = needsBootstrap
                    it.unsignedFallback = unsignedFallback
                    it.signedProfile = signedProfile
                }
            }

    // UI 展示记录与后台分页共用集合；单独锁集合，不能让 UI 等待 page() 的网络锁。
    internal fun seenIds(): Set<String> = synchronized(seen) { seen.toSet() }

    internal fun remember(ids: Collection<String>) {
        synchronized(seen) {
            seen.addAll(ids)
            while (seen.size > 540) seen.remove(seen.first())
        }
    }

    /**
     * 冷启动续刷用的最小快照：分页会话、offset 与最近看过的 ID。不含任何凭据。
     *
     * 同进程可继续使用原服务端会话；跨进程或换注册身份后重读入口，
     * 保留最近去重记录，避免重新播放同一批冷启动作品。
     */
    fun snapshot(): String =
        JSONObject()
            .put("schema", 2)
            .put("unsignedFallback", unsignedFallback)
            .put("cellFlow", cellFlow?.let {
                JSONObject().put("cellId", it.cellId).put("planId", it.planId).put("versionTag", it.versionTag)
            })
            .put("session", session)
            .put("offset", offset)
            .put("seen", JSONArray(seenIds().toList()))
            .toString()

    /** 记录一部已在首页展示过的作品；冷启动时用它跳过重复内容。 */
    fun markSeen(id: String) {
        if (id.isBlank() || id.length > 32) return
        remember(listOf(id))
    }

    /** 是否已经刷出进度（有服务端会话或非零 offset）；用于判断要不要接着上次继续。 */
    val hasProgress: Boolean
        get() = session.isNotEmpty() || offset > 0

    /** 恢复 [snapshot]；格式不合法或数值越界时返回 false，调用方按新会话处理。 */
    fun restore(value: String): Boolean {
        val saved = runCatching { JSONObject(value) }.getOrNull() ?: return false
        val savedSession = saved.optString("session")
        val savedOffset = saved.optInt("offset", 0)
        if (savedSession.length > 4096 || savedOffset !in 0..1_000_000 ||
            savedSession.any { it == '\r' || it == '\n' || it == '\u0000' }) return false
        val savedFlow = saved.optJSONObject("cellFlow")
        val flow = savedFlow?.let {
            val cellId = it.optString("cellId")
            val planId = it.optString("planId")
            val versionTag = it.optString("versionTag")
            if (!cellId.matches(Regex("[1-9][0-9]{0,29}")) ||
                !planId.matches(Regex("[1-9][0-9]{0,29}")) ||
                versionTag.length > 4096 || versionTag.any { ch -> ch == '\r' || ch == '\n' || ch == '\u0000' }) return false
            CellFlow(cellId, planId, versionTag)
        }
        session = savedSession
        offset = savedOffset
        cellFlow = flow
        signedProfile = null
        unsignedFallback = saved.optBoolean("unsignedFallback", false)
        // 旧快照没有单列续刷信息，重新读取入口元数据但保留去重，不能一直沿用错误分页。
        needsBootstrap = saved.optInt("schema", 1) < 2 && hasProgress
        synchronized(seen) {
            seen.clear()
            saved.optJSONArray("seen")?.let { rows ->
                for (i in 0 until minOf(rows.length(), 540)) {
                    rows.optString(i).takeIf(String::isNotBlank)?.let { seen += it }
                }
            }
        }
        return true
    }

    /**
     * A local starter page, with no persisted server session. The next read starts a new server
     * session at offset zero and filters these already-displayed IDs as usual.
     */
    @Synchronized
    fun restoreStarter(page: CatalogPage): Boolean {
        if (
            expectedPage != 1 ||
                lastPage != 0 ||
                offset != 0 ||
                session.isNotBlank() ||
                seenIds().isNotEmpty() ||
                page.items.isEmpty()
        )
            return false
        remember(page.items.map { it.id })
        lastResult = page
        lastPage = 1
        expectedPage = 2
        return true
    }
}

/** 有效推荐批次耗尽或连续重复，需要用户主动重新获取当前来源。 */
class NoNewRecommendationsException(val rememberedCount: Int = 0) : IOException("推荐接口未返回新作品，请稍后再试")

/** Official App feed, not the public website's sorted category list. No behavior reporting. */
class RecommendationFeed(
    private val http: OkHttpClient,
    private val account: AccountCredentials? = null,
    private val cursor: RecommendationCursor = RecommendationCursor(),
    private val category: DramaCategory? = null,
    private val playbackSession: PlaybackDeviceSession? = null,
) {
    private val accountHttp =
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun page(page: Int): CatalogPage =
        synchronized(cursor) {
            require(page in 1..100)
            if (page == cursor.lastPage) return@synchronized checkNotNull(cursor.lastResult)
            if (page != cursor.expectedPage) throw IOException("推荐已刷新，请重新获取")
            if (category == null && cursor.needsBootstrap) cursor.restartServer()
            repeat(3) { attempt ->
                val signed = category == null && playbackSession != null && !cursor.unsignedFallback
                val identity = if (signed) {
                    RequestScope.ensureActive()
                    playbackSession!!.profile(accountHttp).also {
                        RequestScope.ensureActive()
                        // 服务端分页会话随注册身份使用；换身份或进程恢复后重读入口，保留去重。
                        if (cursor.signedProfile !== it && cursor.hasProgress) cursor.restartServer()
                        cursor.signedProfile = it
                    }
                } else null
                val requestVersion = if (signed) "74332" else "73532"
                val params =
                    linkedMapOf(
                            "aid" to "8662",
                            "app_name" to "novelread",
                            "channel" to "update_64",
                            "device_platform" to "android",
                            "os" to "android",
                            "ssmix" to "a",
                            "device_type" to "25053RT47C",
                            "device_brand" to "Redmi",
                            "language" to "zh",
                            "os_api" to "36",
                            "os_version" to "16",
                            "resolution" to "1280*2772",
                            "dpi" to "520",
                            "ac" to "wifi",
                        )
                        .apply {
                            account?.let {
                                val allowed =
                                    setOf(
                                        "channel",
                                        "device_platform",
                                        "os",
                                        "ssmix",
                                        "device_type",
                                        "device_brand",
                                        "language",
                                        "os_api",
                                        "os_version",
                                        "resolution",
                                        "dpi",
                                        "ac",
                                    )
                                putAll(it.device.filterKeys { key -> key in allowed })
                                // 登录后不再带 device_id/iid：2026-11-03 实测带账号 Cookie 时
                                // 再塞登录用的固定设备号，feed 直接回 `110 ILLEGAL_ACCESS`；
                                // 只带 Cookie 返回 `0 SUCCESS`。设备身份由会话自身体现。
                            }
                            put("version_code", requestVersion)
                            put("version_name", if (signed) "7.4.3.32" else "7.3.5.32")
                            put("manifest_version_code", requestVersion)
                            put("update_version_code", requestVersion)
                            put("tab_type", "16")
                            put("bottom_tab_type", "7")
                            put("landing_bottom_tab_type", "7")
                            put("offset", cursor.offset.toString())
                            put("client_req_type", if (cursor.offset == 0) "1" else "2")
                            put("_rticket", System.currentTimeMillis().toString())
                            if (cursor.session.isNotEmpty()) put("session_id", cursor.session)
                        }
                // 主域之外再备两个可用的同族域名：110 一类校验/限流错误换路比反复打同一节点更容易
                // 逃出去（与播放接口的换路策略一致）。
                val host = FEED_HOSTS[attempt % FEED_HOSTS.size]
                val flow = if (category == null) cursor.cellFlow else null
                val request =
                    if (category == null) {
                        // 官方单列首屏允许无限流时，后续改走单元续刷接口，而非继续递增 tab 的 offset。
                        val path = if (flow == null) "/reading/bookapi/bookmall/tab/v1/"
                            else "/reading/bookapi/bookmall/cell/change/v1/"
                        if (flow != null) {
                            params["cell_id"] = flow.cellId
                            params["plan_id"] = flow.planId
                            params["version_tag"] = flow.versionTag
                            params["client_template"] = "7"
                            params["change_type"] = "0"
                            params["client_req_type"] = "2"
                            params["filter_ids"] = cursor.seenIds().joinToString(",")
                        }
                        val url = "$host$path".toHttpUrl().newBuilder()
                        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
                        Request.Builder().url(url.build())
                    } else {
                        val genre =
                            if (category == DramaCategory.COMIC) "comic_series" else "short_play"
                        val selector = JSONObject().put("genre", JSONArray().put(genre))
                        listOf(
                                "sort",
                                "gender",
                                "category_dim_theme",
                                "category_dim_role",
                                "category_dim_epoch",
                                "online_time",
                                "creation_status",
                            )
                            .forEach { selector.put(it, JSONArray()) }
                        val body =
                            JSONObject()
                                .put(
                                    "req_scene",
                                    if (category == DramaCategory.COMIC) "comic_series"
                                    else "default",
                                )
                                .put("offset", cursor.offset)
                                .put("limit", 24)
                                .put("req_type", "only_content")
                                .put("need_selector_panel", false)
                                .put("client_req_type", if (cursor.offset == 0) 3 else 2)
                                .put("session_id", cursor.session)
                                .put("filter_ids", cursor.seenIds().joinToString(","))
                                .put("select_items", selector)
                                .toString()
                                .toByteArray()
                        val device = VendorConstants.device + params
                        val query =
                            params.filterKeys {
                                it !in
                                    setOf(
                                        "tab_type",
                                        "bottom_tab_type",
                                        "landing_bottom_tab_type",
                                        "offset",
                                        "client_req_type",
                                        "session_id",
                                    )
                            }
                        val signed =
                            Signer(device = device)
                                .sign(
                                    "$host/reading/distribution/category/landpage/v/",
                                    query,
                                    body,
                                )
                        Request.Builder()
                            .url(signed.url)
                            .post(
                                body.toRequestBody("application/json; charset=utf-8".toMediaType())
                            )
                            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
                    }
                request
                    .header(
                        "User-Agent",
                        "com.dragon.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C)",
                    )
                    .header("Accept", "application/json")
                    .header("X-XS-From-Web", "0")
                    .header("Sdk-Version", "2")
                account?.cookie?.takeIf { it.isNotBlank() }?.let { request.header("Cookie", it) }
                if (identity != null) {
                    RequestScope.ensureActive()
                    val values = identity.values + params + mapOf("pv_player" to "74332")
                    val base = request.build().url.newBuilder().query(null).build().toString()
                    val signature = identity.recommendationSigner(System::currentTimeMillis, values)
                        .signPlayback(base, values)
                    request.url(signature.url)
                    signature.headers.forEach { (key, value) -> request.header(key, value) }
                    request.header("User-Agent", "com.phoenix.read/74332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)")
                    request.header("Cookie", account?.cookie?.takeIf { it.isNotBlank() } ?: identity.cookie)
                }
                val result =
                    RequestScope.execute(accountHttp.newCall(request.build())) { response ->
                        JSONObject(feedResponse(response))
                    }
                val parsed =
                    runCatching {
                        when {
                            category != null -> parseCategory(result)
                            flow != null -> parseCell(result)
                            else -> parse(result)
                        }
                    }
                        .getOrElse { problem ->
                            // 110 一类错误多来自服务端判定旧分页会话不合法的限流/校验：丢掉旧
                            // session 与 offset 从头拉一次，否则用户只能等下次刷新才恢复。
                            val code = result.optInt("code", -1)
                            // 账号推荐历史上存在设备校验 110；仅对此明确拒绝保留原 Cookie 只读兼容路径。
                            if (signed && code == 110) {
                                cursor.unsignedFallback = true
                                cursor.restartServer()
                                return@repeat
                            }
                            if (code != 0 && cursor.session.isNotEmpty() && problem is IOException) {
                                cursor.restartServer()
                                return@repeat
                            }
                            throw problem
                        }
                val nextSession = parsed.second.ifBlank { cursor.session }
                val discoveredFlow = if (category == null && flow == null) cellFlow(result) else null
                cursor.offset = when {
                    flow != null -> {
                        val next = result.getJSONObject("data").optLong("next_offset", -1)
                        if (next <= cursor.offset || next > 1_000_000)
                            throw IOException("推荐续页游标未前进，请重新获取")
                        next.toInt()
                    }
                    discoveredFlow != null -> 0
                    category == null -> cursor.offset + maxOf(1, rawVideoCount(result))
                    else -> result.getJSONObject("data").optInt("next_offset", cursor.offset + parsed.first.items.size)
                }
                cursor.session = nextSession
                if (discoveredFlow != null) cursor.cellFlow = discoveredFlow
                val seen = cursor.seenIds()
                val fresh = parsed.first.copy(items = parsed.first.items.filter { it.id !in seen })
                cursor.remember(parsed.first.items.map { it.id })
                // 游标已经按对应协议推进，整页重复时继续读取下一批，不重复请求同一偏移。
                if (fresh.items.isEmpty()) return@repeat
                cursor.expectedPage = page + 1
                cursor.lastPage = page
                cursor.lastResult = fresh
                return@synchronized fresh
            }
            throw NoNewRecommendationsException(cursor.seenIds().size)
        }

    companion object {
        /**
         * The signed-in mall tab is personalized and far larger than account payloads; the strict 2
         * MiB account reader must not gate this feed.
         */
        private const val FEED_RESPONSE_LIMIT = 16 * 1024 * 1024L

        /** 推荐可用的同族域名；首选项沿用原来的 fqnovel 端点。 */
        private val FEED_HOSTS =
            listOf(
                "https://api5-normal-sinfonlinea.fqnovel.com",
                "https://api5-normal-hl.fqnovel.com",
                "https://api5-normal-lf.fqnovel.com",
            )

        internal fun feedResponse(response: okhttp3.Response): String {
            if (!response.isSuccessful) throw IOException("推荐请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("推荐接口响应为空")
            if (body.contentLength() > FEED_RESPONSE_LIMIT) throw IOException("推荐接口响应过大")
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val input = body.byteStream()
            while (out.size() <= FEED_RESPONSE_LIMIT) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            val bytes = out.toByteArray()
            if (bytes.size > FEED_RESPONSE_LIMIT) throw IOException("推荐接口响应过大")
            val text = String(bytes, Charsets.UTF_8)
            if (text.isBlank()) throw IOException("推荐接口返回空响应，请稍后重试")
            return text
        }

        /** 只接受服务端明确启用且标识完整的单列续刷上下文。 */
        private fun cellFlow(result: JSONObject): RecommendationCursor.CellFlow? {
            val tab = result.optJSONObject("data")?.optJSONArray("tab_item")?.objects()
                ?.firstOrNull { it.str("tab_type") == "16" } ?: return null
            if (!tab.optBoolean("bottom_unlimited") || tab.optInt("client_template", -1) != 7) return null
            val cellId = tab.optJSONArray("cell_data")?.optJSONObject(0)?.let {
                it.str("cell_id_str").ifBlank { it.str("cell_id") }
            }.orEmpty()
            val planId = tab.str("bookstore_id")
            val version = tab.str("version_tag")
            if (!cellId.matches(Regex("[1-9][0-9]{0,29}")) ||
                !planId.matches(Regex("[1-9][0-9]{0,29}")) || version.length > 4096 ||
                version.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
            return RecommendationCursor.CellFlow(cellId, planId, version)
        }

        /** 将单元续刷响应归一化成现有卡片结构，首集核验与本地去重保持一致。 */
        internal fun parseCell(result: JSONObject): Pair<CatalogPage, String> {
            if (result.optInt("code", -1) != 0)
                throw IOException("推荐续刷暂不可用（错误码 ${result.optInt("code", -1)}）")
            val data = result.optJSONObject("data") ?: throw IOException("推荐续刷数据格式已变化")
            val view = data.optJSONObject("cell_view") ?: throw IOException("推荐续刷单元缺失")
            val cell = if (view.has("cell_data")) view else JSONObject().put("cell_data", JSONArray().put(view))
            val tab = JSONObject().put("tab_type", 16).put("session_id", data.str("session_id"))
                .put("cell_data", JSONArray().put(cell))
            val normalized = JSONObject().put("code", 0)
                .put("data", JSONObject().put("tab_item", JSONArray().put(tab)))
            // 单列响应的 has_more=false 仍可能带来新作品，由去重和最多三批尝试共同收敛。
            return parse(normalized)
        }

        internal fun parseCategory(result: JSONObject): Pair<CatalogPage, String> {
            if (result.optInt("code", -1) != 0)
                throw IOException("分类推荐暂不可用（错误码 ${result.optInt("code",-1)}）")
            val data = result.optJSONObject("data") ?: throw IOException("分类推荐数据格式已变化")
            val rows = data.optJSONArray("video_data")?.objects() ?: throw IOException("分类推荐列表缺失")
            // Normalize the known video_data card shapes through the same parser.
            val tab =
                JSONObject()
                    .put("tab_type", 16)
                    .put("session_id", data.str("session_id"))
                    .put(
                        "cell_data",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put(
                                        "cell_data",
                                        JSONArray()
                                            .put(JSONObject().put("video_data", JSONArray(rows))),
                                    )
                            ),
                    )
            val normalized =
                JSONObject()
                    .put("code", 0)
                    .put("data", JSONObject().put("tab_item", JSONArray().put(tab)))
            val parsed = parse(normalized)
            return parsed.first.copy(hasMore = data.optBoolean("has_more", true)) to parsed.second
        }

        /** 一次响应里实际下发的视频条数，用于推进服务端的按条 offset。 */
        internal fun rawVideoCount(result: JSONObject): Int {
            val tab =
                result.optJSONObject("data")?.optJSONArray("tab_item")?.objects()
                    ?.firstOrNull { it.str("tab_type") == "16" } ?: return 0
            val cells = tab.optJSONArray("cell_data") ?: return 0
            var count = 0
            for (i in 0 until cells.length()) {
                val inner = cells.optJSONObject(i)?.optJSONArray("cell_data") ?: continue
                for (k in 0 until inner.length()) {
                    count += inner.optJSONObject(k)?.optJSONArray("video_data")?.length() ?: 0
                }
            }
            return count
        }

        internal fun parse(result: JSONObject): Pair<CatalogPage, String> {
            if (result.optInt("code", -1) != 0)
                throw IOException("推荐暂不可用（错误码 ${result.optInt("code",-1)}），请稍后刷新")
            val tab =
                result.optJSONObject("data")?.optJSONArray("tab_item")?.objects()?.firstOrNull {
                    it.str("tab_type") == "16"
                } ?: throw IOException("推荐数据格式已变化")
            val session = tab.str("session_id")
            if (session.length > 4096 || session.any { it == '\r' || it == '\n' || it == '\u0000' })
                throw IOException("推荐分页标记无效")
            val rows =
                tab.optJSONArray("cell_data")?.objects().orEmpty().flatMap { cell ->
                    cell.optJSONArray("cell_data")?.objects().orEmpty().flatMap { card ->
                        card.optJSONArray("video_data")?.objects().orEmpty().map {
                            it.optJSONObject("video_detail") ?: it
                        }
                    }
                }
            val items =
                rows
                    .mapNotNull { row ->
                        val id = row.str("series_id").ifBlank { row.str("book_id") }
                        val title =
                            row.str("series_title")
                                .ifBlank { row.str("book_name") }
                                .ifBlank { row.str("title") }
                        if (!id.matches(Regex("[0-9]{1,24}")) || title.isBlank()) null
                        else
                            Series(
                                id,
                                title,
                                cleanUrl(row.str("series_cover").ifBlank { row.str("cover") }),
                                row.str("series_intro").ifBlank { row.str("video_desc") },
                                row.optInt("episode_cnt")
                                    .takeIf { it > 0 }
                                    ?.let { "共 $it 集" }
                                    .orEmpty(),
                            )
                    }
                    .distinctBy { it.id }
            if (items.isEmpty()) throw IOException("推荐未返回可播放的剧集，请刷新重试")
            val hints =
                rows
                    .mapNotNull { row ->
                        val id = row.str("series_id").ifBlank { row.str("book_id") }
                        val first =
                            row.str("first_vid").ifBlank {
                                row.optJSONArray("video_list")
                                    ?.objects()
                                    ?.firstOrNull { it.optInt("vid_index") == 1 }
                                    ?.str("vid")
                                    .orEmpty()
                            }
                        val count = row.optInt("episode_cnt")
                        if (
                            id in items.map { it.id } &&
                                first.matches(Regex("[0-9]{1,30}")) &&
                                count > 0
                        )
                            id to TrialHint(first, count)
                        else null
                    }
                    .toMap()
            // Live offsets 0,1,2 return new cards despite false/missing has_more and
            // bottom_unlimited. This feed is not a category paginator. The caller stops
            // on duplicate-only batches and bounds each browse session to 100 pages.
            return CatalogPage(items, true, trialHints = hints) to session
        }
    }
}
