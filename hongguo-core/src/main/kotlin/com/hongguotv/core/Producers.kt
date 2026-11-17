// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONObject

data class Producer(val id: String, val name: String)
data class ProducerCredit(val producer: Producer, val series: Series)
data class ProducerPage(val items: List<Series>, val hasMore: Boolean)

/** The content provider's media_id is NOT a social-profile user_id. */
object Producers {
    fun parse(raw: JSONObject): Map<String, ProducerCredit> {
        if (raw.optInt("code", -1) != 0) throw IOException("制作方资料暂不可用")
        val data = raw.optJSONArray("data") ?: throw IOException("制作方资料格式已变化")
        return data.objects().mapNotNull { row ->
            val id = row.str("book_id")
            val media = row.str("media_id")
            val name = row.str("author")
            if (!id.matches(Regex("[0-9]{1,30}")) || name.isBlank()) return@mapNotNull null
            val count = row.optInt("total_chapter_num", row.optInt("chapter_number", row.optInt("final_chapter_number")))
            id to ProducerCredit(
                Producer(media.takeIf { it.matches(Regex("[1-9][0-9]{0,29}")) }.orEmpty(), name),
                Series(id, row.str("book_name"), cleanUrl(row.str("thumb_url")), row.str("abstract"),
                    if (count > 0) "全 $count 集" else ""),
            )
        }.toMap()
    }

    fun matches(expected: Producer, actual: Producer): Boolean =
        if (expected.id.isNotBlank()) actual.id == expected.id
        else actual.name == expected.name

    fun verified(producer: Producer, candidates: List<Series>, credits: Map<String, ProducerCredit>): List<Series> =
        candidates.distinctBy { it.id }.filter { candidate ->
            credits[candidate.id]?.let { matches(producer, it.producer) } == true
        }
}
