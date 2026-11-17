// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Shared anonymous transport for read-only comment lists. Never accepts account credentials. */
internal class CommentTransport(
    private val playbackSession: PlaybackDeviceSession? = null,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build(),
) {
    private val signature = CommentSignature()

    fun read(episode: String, payload: JSONObject, source: Int, channel: Int): JSONObject {
        val query =
            linkedMapOf(
                "aid" to "8662",
                "app_name" to "novelread",
                "version_code" to "73532",
                "version_name" to "7.3.5.32",
                "manifest_version_code" to "73532",
                "update_version_code" to "73532",
                "channel" to "update_64",
                "device_platform" to "android",
                "os" to "android",
                "ssmix" to "a",
                "device_type" to "25053RT47C",
                "device_brand" to "Redmi",
                "language" to "zh",
                "os_api" to "36",
                "os_version" to "16",
                "resolution" to "1280*2772",
                "dpi" to "520",
                "ac" to "wifi",
                "device_id" to "7397450988462912345",
                "iid" to "7397450988462912346",
                "_rticket" to System.currentTimeMillis().toString(),
            )
        val encoded =
            query.toSortedMap().entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v,"UTF-8")}"
            }
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val request =
            Request.Builder()
                .url(
                    "https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/$episode/v1/?$encoded"
                )
                .header(
                    "User-Agent",
                    "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)",
                )
                .header("Accept", "application/json")
                .header("X-XS-From-Web", "0")
                .header("Sdk-Version", "2")
                .header("Comment-Source", source.toString())
                .header("Server-Channel", channel.toString())
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (playbackSession == null) {
            signature.headers(encoded).forEach { (k, v) -> request.header(k, v) }
        } else {
            // 时间轴弹幕复用匿名注册会话和完整推荐版本签名，不能使用固定占位设备标识。
            val identity = playbackSession.profile(http)
            val values = identity.values + mapOf(
                "aid" to "8662", "version_code" to "74332", "version_name" to "7.4.3.32",
                "manifest_version_code" to "74332", "update_version_code" to "74332", "pv_player" to "74332",
            )
            val base = "https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/$episode/v1/"
            val signed = identity.recommendationSigner(System::currentTimeMillis, values).signPlayback(base, values, body)
            request.url(signed.url)
            signed.headers.forEach { (k, v) -> request.header(k, v) }
            request.header("Cookie", identity.cookie)
            request.header("User-Agent", "com.phoenix.read/74332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)")
        }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("评论读取失败（HTTP ${response.code}）")
            val bytes =
                response.body?.byteStream()?.let { readCommentBytes(it, 2 * 1024 * 1024) }
                    ?: throw IOException("评论响应为空")
            if (bytes.size > 2 * 1024 * 1024) throw IOException("评论响应过大")
            JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
}
