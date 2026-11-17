// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

class SearchUnavailableException(message: String, cause: Throwable? = null): IOException(message, cause)

/** Parse one upstream tab without silently mixing comics and short dramas. */
internal object SearchPayload {
    data class Page(val items: List<Series>, val hasMore: Boolean, val searchId: String,
                    val passback: String, val nextOffset: Int?)

    fun parse(body: String, type: ContentType, keyword: String): Page {
        if (body.isBlank()) throw SearchUnavailableException("搜索服务返回空内容，请稍后重试；这不一定是电视网络故障")
        val data = try { JSONObject(body.removePrefix("\uFEFF")) }
            catch (e: Exception) { throw SearchUnavailableException("搜索服务返回了无法识别的内容，请稍后重试", e) }
        val code = data.optInt("code", data.optInt("Code", -1))
        if (code != 0) throw SearchUnavailableException("搜索服务暂不可用（错误码 $code），请稍后重试")
        val tabs = data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs")
            ?: throw SearchUnavailableException("搜索数据格式已变化，需要更新内容接口")
        val tab = tabs.objects().firstOrNull { it.optInt("tab_type") == type.searchTab }
            ?: throw SearchUnavailableException("${type.label}搜索暂不可用，请稍后重试")
        val rows = tab.optJSONArray("data")
            ?: throw SearchUnavailableException("搜索列表格式已变化，需要更新内容接口")
        val items = mutableListOf<Series>()
        for (cell in rows.objects()) {
            val cells = mutableListOf(cell)
            val embedded = when (val raw = cell.opt("lynx_data")) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            }
            embedded?.optJSONArray("cell_data")?.objects()?.let { cells.addAll(it) }
            for (row in cells) {
                val videos = when (val raw = row.opt("video_data")) {
                    is JSONArray -> raw.objects()
                    is JSONObject -> listOf(raw)
                    else -> emptyList()
                }
                for (video in videos) {
                    val id = video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                    // A multi-season cell has one row heading but several distinct video titles.
                    val title = cleanTitle(video.str("series_name").ifEmpty { video.str("series_title") }
                        .ifEmpty { video.str("title") }.ifEmpty {
                            row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty()
                        }.ifEmpty { row.str("cell_name") })
                    if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) continue
                    val count = video.optInt("episode_cnt")
                    items += Series(id, title, cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }.ifEmpty { video.str("series_cover") }),
                        video.str("video_desc").ifEmpty { video.str("series_intro") },
                        if (count > 0) "全 $count 集" else video.str("rec_text"), video.str("sub_title"))
                }
            }
        }
        val more = when (val raw = tab.opt("has_more")) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw == "1" || raw.equals("true", ignoreCase = true)
            else -> false
        }
        return Page(prioritize(items.distinctBy { it.id }, keyword), more,
            tab.str("search_id"), tab.str("passback"), tab.str("next_offset").toIntOrNull()?.takeIf { it >= 0 })
    }

    fun prioritize(items: List<Series>, keyword: String): List<Series> {
        val query = normalized(keyword)
        if (query.isEmpty()) return items
        // The server resolves pinyin/initials; Chinese titles cannot contain the Latin query.
        if (query.any { it in 'a'..'z' } && query.all { it in 'a'..'z' || it.isDigit() }) return items
        // Keep the requested title and its other seasons, not unrelated upstream recommendations.
        val seriesQuery = query.replace(Regex("第[零〇一二三四五六七八九十百千万两0-9]+[季部集]"), "")
        return items.filter { item ->
            val title = normalized(item.title)
            title.contains(query) || (seriesQuery.length >= 2 && title.contains(seriesQuery))
        }.sortedBy { item ->
            val title = normalized(item.title)
            when { title == query -> 0; title.startsWith(query) -> 1; title.contains(query) -> 2; else -> 3 }
        }
    }

    private fun cleanTitle(value: String): String = Jsoup.parse(value).text().trim()
    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
