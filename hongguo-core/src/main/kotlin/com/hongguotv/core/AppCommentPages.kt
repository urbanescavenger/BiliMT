// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** App episode comments, including the server-issued continuation cursor. No write methods. */
class AppCommentPages {
    private val transport = CommentTransport()

    fun read(series: String, episode: String, cursor: String): JSONObject {
        require(series.matches(Regex("[0-9]{6,30}")) && episode.matches(Regex("[0-9]{6,30}")))
        validateCursor(cursor)
        val payload =
            JSONObject()
                .put("comment_source", 4)
                .put("server_channel", 18)
                .put("group_id", episode)
                .put("group_type", 30)
                .put("comment_type", 4)
                .put("sort", 1)
                .put("need_count", true)
                .put("count", 20)
                .put("cursor", cursor)
                .put("aid", 8662)
                .put("compliance_status", 0)
                .put("business_param", JSONObject().put("book_id", series).put("req_type", 0))
        return parse(transport.read(episode, payload, 4, 18), series, episode, cursor)
    }

    companion object {
        private fun validateCursor(cursor: String): Long {
            require(cursor.length <= 8192)
            if (cursor.isBlank()) return 0
            val data = JSONObject(cursor)
            require(data.getString("session_id").length in 1..200)
            return data.getLong("offset").also { require(it >= 0 && it <= 10000000) }
        }

        fun parse(result: JSONObject, series: String, episode: String, cursor: String): JSONObject {
            require(result.optInt("code", -1) == 0) { "评论接口未返回成功数据" }
            val previous = validateCursor(cursor)
            val data = result.getJSONObject("data")
            val info = data.getJSONObject("common_list_info")
            val more = info.getBoolean("has_more")
            val next = info.optString("cursor")
            if (more) require(next != cursor && validateCursor(next) > previous) { "评论游标未推进" }
            val rows = data.getJSONArray("data_list")
            val items = JSONArray()
            val seen = mutableSetOf<String>()
            for (i in 0 until minOf(rows.length(), 100)) {
                val row = rows.optJSONObject(i)?.optJSONObject("comment") ?: continue
                val common = row.optJSONObject("common") ?: continue
                val expand = row.optJSONObject("expand") ?: continue
                require(
                    common.optString("group_id") == episode && expand.optString("book_id") == series
                ) {
                    "评论剧集或分集不匹配"
                }
                if (common.optInt("status", -1) != 1) continue
                val id = row.optString("comment_id")
                val content =
                    common.optJSONObject("content")?.optString("text").orEmpty().trim().take(20000)
                if (id.isBlank() || id.length > 120 || content.isBlank() || !seen.add(id)) continue
                val user = common.optJSONObject("user_info")?.optJSONObject("base_info")
                val avatar =
                    user
                        ?.optString("user_avatar")
                        .orEmpty()
                        .take(4096)
                        .takeIf { it.startsWith("https://") }
                        .orEmpty()
                items.put(
                    JSONObject()
                        .put("id", id)
                        .put("author", user?.optString("user_name").orEmpty().take(200))
                        .put("avatar", avatar)
                        .put("content", content)
                        .put("created", common.optLong("create_timestamp", 0).coerceAtLeast(0))
                        .put(
                            "likes",
                            row.optJSONObject("stat")?.optLong("digg_count", 0)?.coerceAtLeast(0)
                                ?: 0,
                        )
                        .put("rating", 0)
                )
            }
            return JSONObject()
                .put("seriesID", series)
                .put("episodeID", episode)
                .put("total", info.optLong("total", 0).coerceAtLeast(0))
                .put("items", items)
                .put("nextCursor", if (more) next else "")
                .put("hasMore", more)
        }
    }
}
