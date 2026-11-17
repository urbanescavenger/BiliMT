// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONObject

enum class DramaCategory(val label: String, val route: String) {
    REAL("真人剧", "real-drama"),
    COMIC("漫剧", "comic-drama"),
    AI("AI 剧", "ai-drama"),
}

data class DramaTopic(val id: String, val label: String)

data class DiscoveryInfo(
    val topics: List<DramaTopic>,
    val totalPages: Int,
    val pageSize: Int,
    val total: Int,
)

data class DiscoveryMemory(
    val page: Int,
    val items: List<Series>,
    val hasMore: Boolean,
    val focus: String,
    val info: DiscoveryInfo?,
)

class DramaDiscoveryState {
    var category = DramaCategory.COMIC
    var topic = ""
    var info: DiscoveryInfo? = null
    var browse: DiscoveryMemory? = null

    fun select(category: DramaCategory, topic: String = "") {
        require(topic.isEmpty() || topic.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
        this.category = category
        this.topic = topic
        info = null
        browse = null
    }

    fun topicLabel() = info?.topics?.firstOrNull { it.id == topic }?.label ?: "全部题材"
}

internal object DiscoveryParser {
    fun info(
        section: JSONObject,
        category: DramaCategory,
        topic: String,
        page: Int,
    ): DiscoveryInfo {
        if (!section.optBoolean("isSuccess")) throw IOException("分类找剧暂不可用")
        val route = section.optJSONObject("categoryRoute") ?: throw IOException("分类信息已变化")
        val expected = "/category/${category.route}" + if (topic.isBlank()) "" else "/$topic"
        if (
            route.str("contentType") != category.route ||
                route.str("canonicalPath") != expected ||
                !route.optBoolean("enabled")
        )
            throw IOException("分类条件未被确认，请重新选择")
        val pagination = section.optJSONObject("pagination") ?: throw IOException("分类分页信息缺失")
        val pages = pagination.optInt("totalPages", -1)
        if (pagination.optInt("pageNum") != page || pages < 0) throw IOException("分类分页信息异常")
        val selectors = section.optJSONArray("selectorList") ?: throw IOException("题材选项格式已变化")
        val topics =
            selectors
                .objects()
                .flatMap { it.optJSONArray("items")?.objects().orEmpty() }
                .mapNotNull {
                    val id = it.str("selector_item_id")
                    val label = it.str("show_name")
                    if (
                        id.length <= 80 &&
                            id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) &&
                            label.isNotBlank() &&
                            label.length <= 40
                    )
                        DramaTopic(id, label)
                    else null
                }
                .distinctBy { it.id }
                .take(128)
        if (topic.isNotEmpty() && topics.none { it.id == topic }) throw IOException("该题材已变化，请重新选择")
        return DiscoveryInfo(
            topics,
            pages.coerceAtMost(100),
            pagination.optInt("pageSize", 24),
            pagination.optInt("total", 0).coerceAtLeast(0),
        )
    }
}
