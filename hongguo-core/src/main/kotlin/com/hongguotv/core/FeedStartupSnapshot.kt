// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

/**
 * Short-lived card metadata and verified first-episode IDs only. No URLs for playback, server
 * cursors, account identifiers or credentials are serialized.
 */
object FeedStartupSnapshot {
    private const val MAX_AGE = 10 * 60 * 1000L

    fun encode(page: CatalogPage, now: Long): String {
        val root = JSONObject(CatalogSnapshot.encode(page.items, page.hasMore, now))
        val hints = JSONObject()
        page.items.take(30).forEach { item ->
            page.trialHints[item.id]?.let { hint ->
                hints.put(
                    item.id,
                    JSONObject()
                        .put("episodeId", hint.episodeId)
                        .put("episodeCount", hint.episodeCount),
                )
            }
        }
        return root.put("trialHints", hints).toString()
    }

    fun decode(value: String, now: Long): CatalogPage? =
        runCatching {
                require(value.length <= 200_000)
                val root = JSONObject(value)
                require(now - root.getLong("savedAt") in 0..MAX_AGE)
                val page = CatalogSnapshot.decode(value, now) ?: return null
                val hints = mutableMapOf<String, TrialHint>()
                val rows = root.optJSONObject("trialHints")
                page.items.forEach { item ->
                    rows?.optJSONObject(item.id)?.let { row ->
                        val id = row.getString("episodeId")
                        val count = row.getInt("episodeCount")
                        require(id.matches(Regex("[0-9]{1,30}")) && count in 1..10000)
                        hints[item.id] = TrialHint(id, count)
                    }
                }
                // This is a local starter, not proof that a fresh server session has ended.
                page.copy(hasMore = true, trialHints = hints)
            }
            .getOrNull()
}
