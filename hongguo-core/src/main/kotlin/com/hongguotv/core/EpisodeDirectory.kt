// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Keep upstream episode order: sorting or dropping bad IDs would change saved episode indexes. */
internal object EpisodeDirectory {
    private val numericId = Regex("[0-9]{1,30}")

    fun expectedCount(detail: JSONObject): Int = maxOf(
        0,
        detail.optInt("episode_cnt", 0),
        detail.optJSONObject("series_episode_info")?.optInt("episode_cnt", 0) ?: 0,
    )

    fun fromWeb(detail: JSONObject): List<String>? {
        val raw = detail.optJSONArray("vid_list") ?: return null
        val ids = (0 until raw.length()).map { raw.optString(it) }
        return ids.takeIf { valid(it, expectedCount(detail)) }
    }

    fun parse(body: String, expectedCount: Int): List<String> {
        val root = try { JSONObject(body) } catch (_: org.json.JSONException) {
            throw IOException("完整分集响应格式异常")
        }
        val success = when {
            root.has("code") -> root.optInt("code", -1) == 0
            root.has("Code") -> root.optInt("Code", -1) == 0
            else -> root.optString("message").equals("SUCCESS", ignoreCase = true)
        }
        if (!success) throw IOException("完整分集接口暂不可用")
        val rows = root.optJSONObject("data")?.optJSONArray("item_data_list") ?: JSONArray()
        val ids = (0 until rows.length()).map { rows.optJSONObject(it)?.str("item_id").orEmpty() }
        if (!valid(ids, expectedCount)) throw IOException("完整分集数据缺失或异常")
        return ids
    }

    private fun valid(ids: List<String>, expectedCount: Int): Boolean =
        ids.isNotEmpty() && ids.size >= expectedCount &&
            ids.all { it.matches(numericId) } && ids.distinct().size == ids.size
}
