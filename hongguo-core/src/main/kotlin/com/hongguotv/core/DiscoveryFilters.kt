// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

data class DiscoveryFilter(val id: String, val label: String)

data class DiscoveryGroup(val type: String, val label: String, val options: List<DiscoveryFilter>)

data class DiscoverySelection(val values: Map<String, String> = emptyMap()) {
    init {
        require(
            values.all { (key, value) ->
                key in labels && value.matches(Regex("[a-zA-Z0-9_-]{1,80}"))
            }
        )
    }

    fun choose(type: String, id: String): DiscoverySelection {
        require(type in labels)
        // Content types have different taxonomies; discard the previous type's conditions.
        val next = if (type == "genre") emptyMap() else values
        return DiscoverySelection(if (id.isEmpty()) next - type else next + (type to id))
    }

    fun request(cursor: DiscoveryCursor?): JSONObject {
        val selected = JSONObject()
        labels.keys.forEach { key ->
            selected.put(key, JSONArray().apply { values[key]?.let { put(it) } })
        }
        return JSONObject()
            .put("offset", cursor?.offset ?: 0)
            .put("limit", 24)
            .put("need_selector_panel", cursor == null)
            .put("select_items", selected)
            .put("session_id", cursor?.session.orEmpty())
            .put("req_type", if (cursor == null) "default" else "only_content")
            .put("client_req_type", 3)
            .put("filter_ids", "")
            .put(
                "req_scene",
                values["genre"]?.takeIf { it in setOf("comic_series", "ai_series") } ?: "default",
            )
    }

    companion object {
        val labels =
            linkedMapOf(
                "genre" to "类型",
                "category_dim_theme" to "主题",
                "category_dim_role" to "设定",
                "category_dim_epoch" to "背景",
                "sort" to "推荐",
                "gender" to "受众",
                "online_time" to "时间",
                "creation_status" to "状态",
            )
    }
}

data class DiscoveryCursor(val offset: Int, val session: String, val page: Int) {
    init {
        require(offset > 0 && session.isNotBlank() && session.length <= 4096 && page in 2..100)
    }

    override fun toString() = "DiscoveryCursor(offset=$offset,page=$page)"
}

data class FilteredDiscoveryPage(
    val items: List<Series>,
    val groups: List<DiscoveryGroup>,
    val next: DiscoveryCursor?,
)

class DiscoveryBrowserState {
    var selection = DiscoverySelection()
    var groups = emptyList<DiscoveryGroup>()
    var items = emptyList<Series>()
    var next: DiscoveryCursor? = null
    var loaded = false
    var position: Pair<String, Int>? = null
    var expanded = true
    var headerOffset = 0
    var browseStep = 0
    var filterVerticalOffset = 0
    val filterOffsets = mutableMapOf<String, Int>()
}

internal object FilteredDiscoveryParser {
    fun parse(raw: String, cursor: DiscoveryCursor?): FilteredDiscoveryPage {
        val root =
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw IOException("分类响应格式异常")
            }
        if (root.optInt("code", -1) != 0) throw IOException("分类服务暂不可用")
        val data = root.optJSONObject("data") ?: throw IOException("分类数据缺失")
        val rows = data.optJSONArray("video_data") ?: throw IOException("分类剧集列表缺失")
        val items =
            rows
                .objects()
                .mapNotNull { video ->
                    val id = video.str("series_id")
                    val title = video.str("title")
                    if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
                    val tags =
                        runCatching {
                                JSONArray(video.str("category_schema"))
                                    .objects()
                                    .map { it.str("name") }
                                    .filter { it.isNotBlank() }
                                    .take(8)
                                    .joinToString(" · ")
                            }
                            .getOrDefault("")
                    Series(
                        id,
                        title,
                        cleanUrl(video.str("cover")),
                        video.str("video_desc"),
                        video.optInt("episode_cnt").takeIf { it > 0 }?.let { "共 $it 集" }.orEmpty(),
                        tags,
                    )
                }
                .distinctBy { it.id }
        if (rows.length() > 0 && items.isEmpty()) throw IOException("分类剧集格式异常")
        val groups =
            data
                .optJSONArray("selector_rows")
                ?.objects()
                .orEmpty()
                .mapNotNull { row ->
                    val type = row.str("type")
                    val label = DiscoverySelection.labels[type] ?: return@mapNotNull null
                    val options =
                        row.optJSONArray("items")
                            ?.objects()
                            .orEmpty()
                            .mapNotNull {
                                val id = it.str("selector_item_id")
                                val name = it.str("show_name")
                                if (
                                    id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) &&
                                        name.isNotBlank() &&
                                        name.length <= 40
                                )
                                    DiscoveryFilter(id, name)
                                else null
                            }
                            .distinctBy { it.id }
                            .take(128)
                    DiscoveryGroup(type, label, options)
                }
                .distinctBy { it.type }
        if (cursor == null && groups.none { it.type == "genre" }) throw IOException("分类筛选条件缺失")
        val page = cursor?.page ?: 1
        val more = data.optBoolean("has_more") && page < 100
        val next =
            if (more) {
                val offset = data.optInt("next_offset", -1)
                val session = data.str("session_id").ifEmpty { cursor?.session.orEmpty() }
                if (offset <= (cursor?.offset ?: 0) || session.isBlank() || session.length > 4096)
                    throw IOException("分类续页信息异常，请刷新重试")
                DiscoveryCursor(offset, session, page + 1)
            } else null
        return FilteredDiscoveryPage(items, groups, next)
    }
}
