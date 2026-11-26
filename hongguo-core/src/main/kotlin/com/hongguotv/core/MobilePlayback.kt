// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Signing returns the final URL because v5 selects its digest branch through the request ticket. */
internal data class MobileSignedRequest(val url: String, val headers: Map<String, String>)

internal fun interface MobilePlaybackSigner {
    fun sign(url: String, body: ByteArray, device: Map<String, String>): MobileSignedRequest
    val currentAppProtocol: Boolean get() = false
}

internal object MobilePlayback {
    private const val PRIMARY_MODEL_HOST = "api5-normal-sinfonlinea.fqnovel.com"
    private val v5Signer = Signer(mobileV5 = true)
    val signer = object : MobilePlaybackSigner {
        override val currentAppProtocol = true
        override fun sign(url: String, body: ByteArray, device: Map<String, String>): MobileSignedRequest {
            val parsed = url.toHttpUrl()
            val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
            val now = values.getValue("_rticket").toLong()
            val request = v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, now)
            return MobileSignedRequest(request.url, request.headers)
        }
    }

    private fun query(device: Map<String, String>, currentApp: Boolean = false): Map<String, String> =
        if (currentApp) linkedMapOf("aid" to "8662", "app_name" to "novelread",
            "version_code" to "74532", "version_name" to "7.4.5.32", "update_version_code" to "74532",
            "manifest_version_code" to "74532", "os" to "android", "ssmix" to "a",
            "channel" to "update_64", "device_platform" to "android", "device_type" to "25053RT47C",
            "device_brand" to "Redmi", "os_version" to "16", "os_api" to "36",
            "ac" to "wifi", "language" to "zh", "host_abi" to "arm64-v8a",
            "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id")) else
        linkedMapOf("aid" to "8662", "app_name" to "novelread",
            "version_code" to "74332", "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
            "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"))

    /** The player GET also requires v5, including MD5(empty) rather than a zero body digest.
     * Wire profile verified against hongguo-drama 92df757 and hongguo-tv 9e7386f.
     */
    fun videoTabRequest(id: String, device: Map<String, String>, host: String, now: Long,
                        signing: MobilePlaybackSigner = signer): Request.Builder {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val values = query(device, signing.currentAppProtocol) + linkedMapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
            "video_platform" to "1024", "source" to "0", "from_video_id" to "", "_rticket" to now.toString(),
        )
        val url = "https://$host/reading/bookapi/video_tab/multi_video_model/v1/"
            .toHttpUrl().newBuilder().apply { values.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val signed = signing.sign(url.toString(), ByteArray(0), device)
        RequestScope.ensureActive()
        return Request.Builder().url(signed.url).get()
            .header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply {
                signed.headers.forEach { (k, v) -> header(k, v) }
                // Empty bytes are signing input only: the GET has no body or POST headers.
                removeHeader("x-ss-stub")
                removeHeader("Content-Type")
                removeHeader("Content-Encoding")
            }
    }

    fun ladder(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        host: String? = null,
    ): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val currentApp = signer.currentAppProtocol
        val query = query(device, currentApp) + ("_rticket" to now.toString())
        val origin = host ?: if (currentApp) "reading.snssdk.com" else PRIMARY_MODEL_HOST
        val endpoint = if (currentApp) "https://$origin/novel/player/multi_video_model/preload/v1"
            else "https://$origin/novel/player/multi_video_model/player/v1"
        val url = endpoint
            .toHttpUrl().newBuilder().apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        // TV uses platform decoders, not the official app's bytevc2 decoder.
        // Keep the older protocol's level 1; the current App protocol uses the verified tier below.
        val payload = JSONObject("""{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}""")
        if (currentApp) {
            // 2026-11-26: same-device A/B on three episodes returned HEVC at level 4.
            // The bytevc2-only sample at level 2 no longer needed an extra VOD request.
            // Keep platform 1024: platform 3 is rejected by this preload endpoint.
            payload.getJSONObject("biz_param").put("device_level", 4)
            payload.put("dr_scene", "preload")
        }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val request = Request.Builder().url(url)
            .header("User-Agent", if (currentApp) System.getProperty("http.agent", "okhttp/4.12.0") else
                "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)")
            .header("Accept", "application/json").header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) })
        val signed = signer.sign(url.toString(), bytes, device)
        request.url(signed.url)
        signed.headers.forEach { (k,v) -> request.header(k,v) }
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
            val body = response.body ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val limit = 2 * 1024 * 1024L
            if (body.contentLength() > limit) throw IOException("播放信息过大")
            val source = body.source()
            source.request(limit + 1)
            val bytesRead = source.buffer.size
            if (bytesRead > limit) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) {
                if (!response.header("Retry-After").isNullOrBlank()) throw PlaybackServiceBackoffException()
                throw EmptyPlaybackResponseException("stage=mobile-model").apply {
                    addSuppressed(PlaybackDiagnosticException("mobile-model=${response.code}/${bytesRead}B"))
                }
            }
            val root = try { JSONObject(text) } catch (_: Exception) {
                throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            }
            PlaybackModels.checkStatus(root)
            val data = root.optJSONObject("data")
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val entries = data.optJSONObject("1004") ?: data
            val model = PlaybackModels.objectValue(entries.optJSONObject(id)?.opt("video_model"))
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
            val rows = PlaybackModels.renditions(model)
            if (rows.isEmpty() && currentApp) return@execute compatibleLadder(model, transport)
            if (rows.isEmpty()) throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
            StreamLadder(rows)
        }
    }

    /** App-provided VOD codec negotiation; no website or account headers. */
    private fun compatibleLadder(model: JSONObject, transport: OkHttpClient): StreamLadder {
        val endpoint = PlaybackModels.fallback(model)
            ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
        for (codec in listOf("1", "3")) {
            RequestScope.ensureActive()
            val url = endpoint.toHttpUrl().newBuilder().setQueryParameter("codec_type", codec).build()
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            val rows = RequestScope.execute(transport.newCall(request)) { response ->
                if (!response.isSuccessful) throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
                val source = response.body?.source()
                    ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
                source.request(2 * 1024 * 1024L + 1)
                if (source.buffer.size > 2 * 1024 * 1024L) throw IOException("播放信息过大")
                val root = try { JSONObject(source.readUtf8()) } catch (_: Exception) {
                    throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
                }
                val data = root.optJSONObject("video_info")?.optJSONObject("data")
                    ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
                PlaybackModels.renditions(data)
            }
            if (rows.isNotEmpty()) return StreamLadder(rows)
        }
        throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
    }
}
