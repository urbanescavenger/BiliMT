// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONObject

data class RelatedWork(val series: Series, val season: String, val upcoming: Boolean, val seasonIndex: Int = 0) {
    val label
        get() =
            listOf(season, if (upcoming) "待上线 · 预约" else "已上线")
                .filter { it.isNotBlank() }
                .joinToString(" · ")
}

/**
 * Reading plan's related-work cell, verified with public season metadata. Only short-drama types
 * are actionable; novels/audio are intentionally excluded.
 */
object RelatedWorks {
    private val seasonSuffix = Regex("第?([0-9零〇一二两三四五六七八九十百]+)季$")
    // A final season may end in 「完结篇」: 「…第三季完结篇」 is season 3 of 「…」. Only that edition:
    // 「第X季」 elsewhere in a title can be part of the name itself (「爱在第三季的我们」).
    private val seasonWithEdition = Regex("第([0-9零〇一二两三四五六七八九十百]+)季\\s*(?:完结篇|完結篇)$")

    private fun seasonNumber(value: String): Int? {
        value.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
        val digits = "零一二三四五六七八九"
        var total = 0
        var digit = 0
        for (c in value) when (c) {
            '十', '百' -> { total += maxOf(1, digit) * if (c == '十') 10 else 100; digit = 0 }
            '两' -> digit = 2
            '〇' -> digit = 0
            else -> { digit = digits.indexOf(c); if (digit < 0) return null }
        }
        return (total + digit).takeIf { it > 0 }
    }

    /** The season marker in [title]: the title before it and its number (null if unreadable). */
    private fun marker(title: String): Pair<String, Int?>? {
        val trimmed = title.trim()
        val match = seasonSuffix.find(trimmed) ?: seasonWithEdition.find(trimmed) ?: return null
        return trimmed.substring(0, match.range.first) to seasonNumber(match.groupValues[1])
    }

    private fun stem(title: String) = (marker(title)?.first ?: title.trim())
        .replace(Regex("[\\p{P}\\p{Z}\\s]"), "")

    fun ordered(current: Series, rows: List<RelatedWork>): List<RelatedWork> = rows.sortedWith(
        compareBy<RelatedWork> { if (stem(it.series.title) == stem(current.title)) 0 else 1 }
            .thenBy { stem(it.series.title) }
            .thenBy { it.seasonIndex.takeIf { n -> n > 0 } ?: marker(it.series.title)?.second ?: 1 }
    )

    /** Conservative: identical title stem, consecutive season, unique playable candidate. */
    fun nextSeason(current: Series, rows: List<RelatedWork>): RelatedWork? {
        val title = current.title.trim()
        val own = marker(title)
        val number = if (own == null) 1 else own.second ?: return null
        val currentStem = stem(title)
        if (currentStem.isBlank()) return null
        return rows.filter { row ->
            val candidateTitle = row.series.title.trim()
            val fromTitle = marker(candidateTitle)?.second
            val index = row.seasonIndex.takeIf { it > 0 } ?: fromTitle
            !row.upcoming && row.series.id != current.id && index == number + 1 &&
                (fromTitle == null || fromTitle == index) &&
                stem(candidateTitle) == currentStem
        }.singleOrNull()
    }

    fun parse(raw: String, currentId: String): List<RelatedWork> {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 0) throw IOException("相关作品接口暂不可用")
        val cells = root.optJSONArray("data") ?: throw IOException("相关作品数据格式已变化")
        return cells
            .objects()
            .filter { it.optInt("show_type") == 547 || it.str("cell_name") == "相关作品" }
            .flatMap { cell -> cell.optJSONArray("video_data")?.objects().orEmpty() }
            .mapNotNull { row ->
                val id = row.str("series_id")
                val title = row.str("title")
                val type = row.optInt("content_type")
                if (
                    id == currentId ||
                        !id.matches(Regex("[0-9]{1,30}")) ||
                        title.isBlank() ||
                        type !in listOf(1004, 1007)
                )
                    return@mapNotNull null
                val upcoming = type == 1007
                if (!upcoming && (row.optBoolean("disable_play") || row.optInt("episode_cnt") <= 0))
                    return@mapNotNull null
                val season =
                    row.optJSONObject("tag_info")?.str("text").orEmpty().ifBlank {
                        row.optInt("season_index").takeIf { it > 0 }?.let { "第${it}季" }.orEmpty()
                    }
                RelatedWork(
                    Series(
                        id,
                        title,
                        cleanUrl(row.str("cover")),
                        row.str("video_desc"),
                        if (upcoming) "待上线" else "全 ${row.optInt("episode_cnt")} 集",
                    ),
                    season,
                    upcoming,
                    row.optInt("season_index").coerceAtLeast(0),
                )
            }
            .distinctBy { it.series.id }
            .sortedWith(compareBy<RelatedWork> { it.seasonIndex.takeIf { n -> n > 0 } ?: Int.MAX_VALUE })
    }
}
