// SPDX-License-Identifier: GPL-3.0-only
// Player endpoint selection ported from fqapp (github.com/ch6vip/fqapp)
// rust/src/endpoints/video.rs and rust/src/upstream/client.rs; GPL-3.0-only.
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Holds one registered upstream device, mirroring fqapp's pool rotation with a
 * single device plus re-registration whenever upstream rejects the signature. */
internal class PlayerDeviceSource(
    private val http: OkHttpClient,
    private val registrar: DeviceRegistrar = DeviceRegistrar(),
    private val store: () -> PlayerDeviceStore? = { ContentRepository.deviceStore },
) {
    @Volatile private var cached: PlayerDevice? = null

    private fun key(): Any = store() ?: this

    fun existing(): PlayerDevice? = synchronized(identityLock) {
        val persistent = store()
        if (persistent != null) persistent.load()?.let(PlayerDevice::fromJson).also { cached = it }
        else cached
    }

    fun obtain(): PlayerDevice = synchronized(identityLock) {
        RequestScope.ensureActive()
        existing()?.let { return@synchronized it }
        if (System.nanoTime() < (retryAfter[key()] ?: 0L))
            throw IOException("播放连接恢复失败，请稍后重试")
        val fresh = registrar.register(http)
        RequestScope.ensureActive()
        store()?.save(fresh.toJson())
        cached = fresh
        fresh
    }

    /** Empty bodies are ambiguous: keep the old slot until a replacement is ready.
     * Share a monotonic cooldown across repositories, including successful rotations. */
    fun refreshEmpty(expected: PlayerDevice): PlayerDevice? = synchronized(identityLock) {
        RequestScope.ensureActive()
        existing()?.let { if (it != expected) return@synchronized it }
        val slot = key()
        if (System.nanoTime() < (emptyRetryAfter[slot] ?: 0L)) return@synchronized null
        emptyRetryAfter[slot] = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        val fresh = registrar.register(http)
        RequestScope.ensureActive()
        store()?.save(fresh.toJson())
        cached = fresh
        fresh
    }

    fun invalidate() = synchronized(identityLock) {
        store()?.save(null)
        cached = null
    }

    /** One foreground recovery, shared across repositories using the same persisted slot. */
    fun refreshRejected(expected: PlayerDevice): PlayerDevice = synchronized(identityLock) {
        RequestScope.ensureActive()
        existing()?.let { if (it != expected) return@synchronized it }
        invalidate()
        try { obtain() } catch (error: IOException) {
            retryAfter[key()] = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
            throw IOException("播放连接恢复失败，请稍后重试", error)
        }
    }

    fun rejectReplacement(expected: PlayerDevice) = synchronized(identityLock) {
        if (existing() == expected) {
            invalidate()
            retryAfter[key()] = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        }
    }

    companion object {
        private val identityLock = Any()
        private val emptyRetryAfter = java.util.WeakHashMap<Any, Long>()
        private val retryAfter = java.util.WeakHashMap<Any, Long>()
        // The shortplay video_model URL from fetchShortplayVideoModel.
        internal const val SHORTPLAY_URL = "https://api3-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/?iid={install_id}&device_id={device_id}&aid=1967&app_name=novelapp&version_code=72132&version_name=7.2.1.32&device_platform=android&device_brand=Xiaomi&os_version=13&cdid={cdid}"
        // The phoenix multi_video_model URL from phoenix_player_url.
        internal const val PHOENIX_URL = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/?iid={install_id}&device_id={device_id}&ac=wifi&channel=update_64&aid=8662&app_name=novelread&version_code=71332&version_name=7.1.3.32&device_platform=android&os=android&ssmix=a&device_type=25053RT47C&device_brand=Redmi&language=zh&os_api=36&os_version=16&manifest_version_code=71332&resolution=1280*2772&dpi=520&update_version_code=71332&host_abi=arm64-v8a&dragon_device_type=phone&pv_player=71332&compliance_status=0&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0"
        internal const val FALLBACK_USER_AGENT = "com.xs.fm/632 (Linux; U; Android 15; zh_CN; 23049RAD8C; Build/AQ3A.250226.002; Cronet/TTNetVersion:fc4cebd3 2024-12-10 QuicVersion:d9628e3d 2024-10-11)"

        fun substituteDevice(url: String, device: PlayerDevice): String = url
            .replace("{device_id}", device.deviceId)
            .replace("{install_id}", device.installId)
            .replace("{secret_key}", device.secretKey)
            .replace("{cdid}", device.cdid)
            .replace("{openudid}", device.openudid.ifEmpty { device.deviceId })

        fun shortplayBody(videoId: String): ByteArray = JSONObject()
            .put("video_id", videoId).put("content_type", 1004)
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 3)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("from_video_id", "").put("need_all_video_definition", true)
                .put("need_mp4_align", false).put("source", 4)
                .put("use_os_player", false).put("use_server_dns", false)
                .put("video_platform", 3)).toString().toByteArray()

        fun phoenixBody(videoId: String): ByteArray = JSONObject()
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 3)
                .put("disable_digg_stat", false).put("need_all_video_definition", true)
                .put("need_mp4_align", false).put("use_os_player", false)
                .put("use_server_dns", false).put("video_platform", 1024))
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(videoId)))
            .toString().toByteArray()
    }
}

/** A resolved playable row: score decides the ranking, quality labels the row. */
internal data class PlayerVariant(val score: Long, val url: String, val key: ByteArray?, val quality: Int)

/** Model/variant extraction mirroring video.rs; every entry point is pure JSON. */
internal object StreamModels {
    /** fqapp's video_quality_score: bytevc2 never wins, h264 wins ties. */
    fun qualityScore(info: JSONObject): Long? {
        val codec = buildString {
            append(info.str("codec_type").lowercase())
            append(' ').append(info.optJSONObject("video_meta")?.str("codec_type").orEmpty().lowercase())
            append(' ').append(info.str("gear_des_key").lowercase())
        }
        if (codec.contains("bytevc2")) return null
        val w = int64FromAny(info, "vwidth")
        val h = int64FromAny(info, "vheight")
        var b = int64FromAny(info, "bitrate")
        if (b == 0L) b = int64FromAny(info, "real_bitrate")
        val bonus = if (codec.contains("h264") || codec.contains("avc1")) 1 else 0
        return w * 1_000_000_000 + h * 1_000_000 + b + bonus
    }

    /** Short-edge naming, normalised through the same quality table as before. */
    fun displayName(w: Long, h: Long): Int {
        val edge = if (w > 0 && h > 0) minOf(w, h) else maxOf(w, h)
        return ContentRepository.qualityNumber(edge.toString())
    }

    fun int64FromAny(info: JSONObject, key: String): Long {
        when (val value = info.opt(key)) {
            is Number -> return value.toLong()
            is String -> return scanInt64(value)
            else -> return 0
        }
    }

    private fun scanInt64(s: String): Long {
        val t = s.trim()
        var index = 0
        var sign = 1L
        if (t.startsWith("-")) { sign = -1; index = 1 } else if (t.startsWith("+")) index = 1
        var digits = ""
        while (index < t.length && t[index] in '0'..'9') { digits += t[index]; index++ }
        return digits.toLongOrNull()?.times(sign) ?: 0
    }

    /** base64 decode that tolerates missing padding and newlines, like audio_b64_decode. */
    fun lenientB64(value: String): ByteArray? {
        val trimmed = value.trim().replace("\r", "").replace("\n", "")
        val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
        return runCatching { decode64(padded) }.getOrNull()
    }

    /** One video_list entry into a playable (url, key) pair; empty url on failure. */
    fun decryptEntry(info: JSONObject, keySeed: ByteArray?): PlayerVariant? {
        val score = qualityScore(info) ?: return null
        val model=JSONObject().put("video_list",JSONArray().put(info))
        keySeed?.let { model.put("key_seed",it.base64()) }
        val stream=PlaybackModels.candidates(model).firstOrNull() ?: return null
        return PlayerVariant(score,stream.url,stream.key,stream.quality.toInt())
    }

    private fun ByteArray.startsWithHttp() = size >= 4 && String(this, 0, 4, Charsets.US_ASCII) == "http"

    /** Spade URL decryption; returns null when the blob is not spade-framed. */
    fun spadeDecrypt(value: String, keySeed: ByteArray): String? =
        runCatching { MediaCrypto.decryptUrl(value, keySeed) }.getOrNull()

    /** Every video_model map in a model response, object- or string-shaped. */
    fun models(data: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        decodeModel(data.opt("video_model"))?.let { out += it }
        data.keys().asSequence().forEach { key ->
            data.optJSONObject(key)?.let { decodeModel(it.opt("video_model"))?.let { model -> out += model } }
        }
        return out
    }
    private fun decodeModel(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    fun keySeed(model: JSONObject): ByteArray? =
        model.str("key_seed").takeIf { it.isNotEmpty() }?.let { lenientB64(it) }

    /** Rows from an object-shaped video_list (the shortplay/phoenix shape). */
    fun listRows(videoList: JSONObject?, keySeed: ByteArray?): List<PlayerVariant> =
        videoList?.keys()?.asSequence()?.mapNotNull { videoList.optJSONObject(it) }?.mapNotNull { decryptEntry(it, keySeed) }?.toList() ?: emptyList()

    /** Rows from an array-shaped legacy video_list (play_url / main_url, no spade). */
    fun arrayRows(videoList: JSONArray?): List<PlayerVariant> {
        return videoList?.objects()?.mapNotNull { decryptEntry(it,null) } ?: emptyList()
    }

    /** The model's fallback_api URL, audio-encrypt stream parameters stripped. */
    fun fallbackApiUrl(model: JSONObject): String {
        var url = when (val raw = model.opt("fallback_api")) {
            is JSONObject -> raw.str("fallback_api")
            is String -> runCatching { JSONObject(raw).str("fallback_api") }.getOrDefault(raw)
            else -> ""
        }
        url = url.replace("&stream_type=audio_encrypt", "").replace("?stream_type=audio_encrypt&", "?")
        return url
    }

    fun pick(rows: List<PlayerVariant>, maxQuality: Int): PlayerVariant? =
        rows.sortedByDescending { it.score }.let { sorted -> sorted.firstOrNull { it.quality <= maxQuality } ?: sorted.lastOrNull() }
}
