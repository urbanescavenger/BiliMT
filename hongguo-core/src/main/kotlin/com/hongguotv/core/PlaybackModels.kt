// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A deterministic source failure, unlike a transient transport failure. */
class PlaybackSourceException(val reason: Reason, val upstreamCode: Int? = null): IOException(
    when (reason) {
        Reason.REJECTED -> "上游拒绝播放请求（错误码 $upstreamCode），请稍后手动重试"
        Reason.HTTP_REJECTED -> "播放请求被拒绝（HTTP $upstreamCode），请稍后手动重试"
        Reason.INVALID_RESPONSE -> "播放信息格式异常，请稍后手动重试"
        Reason.NO_PLAYABLE_SOURCE -> "没有可播放的视频资源，可尝试其他清晰度或剧集"
    }
) {
    enum class Reason { REJECTED, HTTP_REJECTED, INVALID_RESPONSE, NO_PLAYABLE_SOURCE }
}

/** Pure parsing only: no device registration, requests or entitlement changes. */
internal object PlaybackModels {
    fun checkStatus(data: JSONObject) {
        val code = data.optInt("Code", data.optInt("code", 0))
        if (code != 0) throw PlaybackSourceException(PlaybackSourceException.Reason.REJECTED, code)
    }
    fun objectValue(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    fun model(data: JSONObject, id: String): JSONObject? {
        // Never silently play a different episode from a batch response.
        val entry = data.optJSONObject(id) ?: data.takeIf { it.has("video_model") }
        return objectValue(entry?.opt("video_model"))
    }
    fun fallback(model: JSONObject): String? {
        val raw = model.opt("fallback_api")
        val value = when(raw) {
            is JSONArray -> raw.optString(0)
            else -> objectValue(raw)?.str("fallback_api") ?: (raw as? String).orEmpty()
        }
        return url(value)?.takeIf { it.startsWith("https://") }
    }
    private fun url(raw: String): String? = cleanUrl(raw).trim().toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty()
    }?.toString()

    fun candidates(model: JSONObject): List<StreamInfo> = renditions(model).map {
        StreamInfo(it.url, it.key, it.quality.toString())
    }

    fun renditions(model: JSONObject): List<Rendition> {
        val list = model.opt("video_list")
        val entries = when(list) {
            is JSONObject -> list.keys().asSequence().mapNotNull { key -> list.optJSONObject(key)?.let { key to it } }.toList()
            is JSONArray -> list.objects().map { "" to it }
            else -> emptyList()
        }
        return entries.mapNotNull { (name, entry) ->
            // A malformed variant must not discard all the other variants.
            runCatching {
                val codec = listOf(entry.str("codec_type"), entry.str("gear_des_key"), entry.optJSONObject("video_meta")?.str("codec_type").orEmpty()).joinToString(" ").lowercase()
                if (codec.contains("bytevc2") || Regex("(^|[^a-z0-9])bvc2([^a-z0-9]|$)").containsMatchIn(codec)) return@runCatching null
                val seed = model.str("key_seed").takeIf { it.isNotEmpty() }?.let(::decode64)
                val encryption = entry.optJSONObject("encrypt_info")
                val key = encryption?.str("spade_a").orEmpty().ifEmpty { entry.str("spade_a") }
                    .takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
                if (encryption != null && key == null && encryption.opt("encrypt") !in listOf(false, 0, "0", "false")) return@runCatching null
                val resolved = listOf("main_url", "play_url", "backup_url_1").firstNotNullOfOrNull { field ->
                    runCatching {
                        val raw = cleanUrl(entry.str(field))
                        if(raw.isEmpty()) null else url(raw) ?: url(if(seed != null) MediaCrypto.decryptUrl(raw, seed) else String(decode64(raw), Charsets.UTF_8))
                    }.getOrNull()
                } ?: return@runCatching null
                val meta=entry.optJSONObject("video_meta")
                val width=entry.optInt("vwidth",meta?.optInt("vwidth",0) ?: 0); val height=entry.optInt("vheight",meta?.optInt("vheight",0) ?: 0)
                val dimensions=if(width>0 && height>0) minOf(width,height).toString() else ""
                val quality=ContentRepository.qualityNumber(entry.str("quality_desc").ifEmpty { meta?.str("definition").orEmpty() }.ifEmpty { dimensions.ifEmpty { entry.str("height").ifEmpty { entry.str("vheight").ifEmpty { entry.str("quality").ifEmpty { name } } } } })
                val label = entry.str("codec_type").ifEmpty { meta?.str("codec_type").orEmpty() }.lowercase()
                Rendition(quality, label, width.takeIf { it > 0 }, height.takeIf { it > 0 }, resolved, key)
            }.getOrNull()
        }
    }
    fun pick(rows: List<StreamInfo>, maxQuality: Int): StreamInfo {
        val known=rows.filter { it.quality.toInt()>0 }.sortedByDescending { it.quality.toInt() }
        val chosen=known.firstOrNull { it.quality.toInt()<=maxQuality } ?: known.lastOrNull() ?: rows.firstOrNull()
            ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
        val q=chosen.quality.toInt()
        return chosen.copy(quality=if(q>0) "${q}P"+(if(q>maxQuality) "（兼容资源）" else "") else "自动")
    }
}
