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
}

internal object MobilePlayback {
    private val v5Signer = Signer(mobileV5 = true)
    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val now = values.getValue("_rticket").toLong()
        val request = v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, now)
        MobileSignedRequest(request.url, request.headers)
    }

    fun ladder(id: String, device: Map<String, String>, http: OkHttpClient,
               signer: MobilePlaybackSigner, now: Long): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val query = linkedMapOf("aid" to "8662", "app_name" to "novelread",
            "version_code" to "74332", "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
            "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"), "_rticket" to now.toString())
        val url = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1"
            .toHttpUrl().newBuilder().apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        // TV uses platform decoders, not the official app's bytevc2 decoder.
        // Level 3 can return only bytevc2; level 1 negotiates compatible renditions.
        val payload = JSONObject("""{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}""")
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val request = Request.Builder().url(url)
            .header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)")
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
            if (source.buffer.size > limit) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) throw EmptyPlaybackResponseException("stage=mobile-model")
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
            if (rows.isEmpty()) throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
            StreamLadder(rows)
        }
    }
}
