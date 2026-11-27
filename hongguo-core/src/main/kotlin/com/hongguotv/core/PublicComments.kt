// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import com.hongguotv.core.ContentRepository
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Reads public website previews only. Never forwards account credentials. */
class PublicComments(http: OkHttpClient) {
    private val transport =
        http
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    private val details = linkedMapOf<String, Pair<Long, String>>()

    fun read(series: String, episode: String): JSONObject {
        require(series.matches(Regex("[0-9]{6,30}")) && episode.matches(Regex("[0-9]{6,30}")))
        val player = page("https://hongguoduanju.com/player/$series/$episode")
        val cached =
            synchronized(details) {
                details[series]?.takeIf { System.currentTimeMillis() - it.first < 30000 }?.second
            }
        val detail =
            cached
                ?: page("https://hongguoduanju.com/detail?series_id=$series").also {
                    synchronized(details) {
                        details[series] = System.currentTimeMillis() to it
                        while (details.size > 16) details.remove(details.keys.first())
                    }
                }
        return parse(player, series, episode, detail)
    }

    private fun page(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val html =
            RequestScope.execute(transport.newCall(request)) { response ->
                if (!response.isSuccessful) throw IOException("公开评论读取失败（HTTP ${response.code}）")
                val input = response.body?.byteStream() ?: throw IOException("公开评论为空")
                val bytes = readCommentBytes(input, 8 * 1024 * 1024)
                if (bytes.size > 8 * 1024 * 1024) throw IOException("公开评论页面过大")
                String(bytes, Charsets.UTF_8)
            }
        return html
    }

    companion object {
        fun parse(
            html: String,
            series: String,
            episode: String,
            detailHTML: String? = null,
        ): JSONObject {
            val loaders = ContentRepository.extractRouter(html).getJSONObject("loaderData")
            val data =
                loaders
                    .keys()
                    .asSequence()
                    .mapNotNull { loaders.optJSONObject(it) }
                    .firstOrNull { it.has("seriesDetail") && it.optBoolean("isSuccess", true) }
                    ?: throw IOException("公开评论格式已变化")
            require(data.getJSONObject("seriesDetail").optString("series_id") == series) {
                "评论剧集不匹配"
            }
            require(data.optString("vid") == episode) { "评论分集不匹配" }
            fun list(value: JSONObject?, kind: String): JSONObject {
                val rows = value?.optJSONArray("reviews") ?: JSONArray()
                val items = JSONArray()
                val seen = mutableSetOf<String>()
                for (i in 0 until minOf(rows.length(), 100)) {
                    val row = rows.optJSONObject(i) ?: continue
                    val text = row.optString("content").trim().take(20000)
                    if (text.isBlank()) continue
                    val author = row.optString("user_name").take(200)
                    val created = row.optLong("create_time", 0).coerceAtLeast(0)
                    val key =
                        listOf(series, episode, kind, author, created.toString(), text)
                            .joinToString("\u0000")
                    val id =
                        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString(
                            ""
                        ) {
                            "%02x".format(it)
                        }
                    if (!seen.add(id)) continue
                    val avatar =
                        row.optString("user_avatar").take(4096).takeIf { it.startsWith("https://") }
                            ?: ""
                    items.put(
                        JSONObject()
                            .put("id", id)
                            .put("author", author)
                            .put("avatar", avatar)
                            .put("content", text)
                            .put("created", created)
                            .put("likes", row.optLong("digg_count", 0).coerceAtLeast(0))
                            .put(
                                "rating",
                                row.optDouble("rating", 0.0).takeIf { it.isFinite() } ?: 0.0,
                            )
                    )
                    if (items.length() >= 50) break
                }
                return JSONObject()
                    .put("total", value?.optLong("total_count", 0)?.coerceAtLeast(0) ?: 0)
                    .put("items", items)
            }
            val detailData =
                if (detailHTML == null) data
                else {
                    val values =
                        ContentRepository.extractRouter(detailHTML).getJSONObject("loaderData")
                    values
                        .keys()
                        .asSequence()
                        .mapNotNull { values.optJSONObject(it) }
                        .firstOrNull { it.has("seriesDetail") && it.optBoolean("isSuccess", true) }
                        ?: throw IOException("公开剧评格式已变化")
                }
            require(detailData.getJSONObject("seriesDetail").optString("series_id") == series) {
                "剧评剧集不匹配"
            }
            val social = detailData.optJSONObject("seriesSocialInfo")
            return JSONObject()
                .put("seriesID", series)
                .put("episodeID", episode)
                .put("reviews", list(social?.optJSONObject("reviews"), "series"))
                .put(
                    "comments",
                    list(
                        data.optJSONObject("episodeSocialInfo")?.optJSONObject("reviews"),
                        "episode",
                    ),
                )
                .put("rating", social?.optDouble("rating", 0.0)?.takeIf { it.isFinite() } ?: 0.0)
                .put("ratingCount", social?.optLong("rating_count", 0)?.coerceAtLeast(0) ?: 0)
                .put("sourceURL", "https://hongguoduanju.com/player/$series/$episode")
                .put("limited", true)
        }
    }
}
