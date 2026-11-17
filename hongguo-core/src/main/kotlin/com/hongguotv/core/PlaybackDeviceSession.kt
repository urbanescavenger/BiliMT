// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/** Anonymous playback identity. Register once, then keep the same identity across repositories. */
class PlaybackDeviceSession(
    private val register: (OkHttpClient) -> DeviceRegister.Registered? = { http ->
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (key, value) ->
            request.header(key, value)
        }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        // Registration has no account cookies. Cancellation belongs to the requesting work scope.
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        RequestScope.execute(transport.newCall(request.build())) { response ->
            val text = try { cloudResponse(response) } catch (_: EmptyAccountResponseException) {
                throw EmptyPlaybackResponseException("stage=register")
            }
            DeviceRegister.parse(text, response.headers("Set-Cookie"))
        }
    }
) {
    companion object {
        val shared = PlaybackDeviceSession()
    }

    internal data class Profile(
        val values: Map<String, String>,
        val cookie: String,
        val routes: PlaybackRoutes = PlaybackRoutes(),
    ) {
        private var playbackSigner: Signer? = null
        private var recommendationSigner: Signer? = null
        private data class Model(val entry: org.json.JSONObject, val owner: Any, val savedAt: Long)
        private val models = linkedMapOf<String, Model>()

        // 首页进入全集可一次性交接刚取得的模型；同仓库主动重试始终重新读取，不复用旧地址。
        @Synchronized
        fun takeModel(id: String, owner: Any, now: Long): org.json.JSONObject? {
            models.entries.removeAll { now - it.value.savedAt !in 0..15_000 }
            val model = models.remove(id) ?: return null
            return if (model.owner === owner) null else org.json.JSONObject(model.entry.toString())
        }

        @Synchronized
        fun offerModel(id: String, entry: org.json.JSONObject, owner: Any, now: Long) {
            val encoded = entry.toString()
            if (encoded.toByteArray(Charsets.UTF_8).size > 256 * 1024) return
            models.entries.removeAll { now - it.value.savedAt !in 0..15_000 }
            models[id] = Model(org.json.JSONObject(encoded), owner, now)
            while (models.size > 6) models.remove(models.keys.first())
        }


        // 同一注册会话的进程 nonce 与启动时间保持一致，跨仓库实例也不重新生成。
        @Synchronized
        fun signer(clock: () -> Long, values: Map<String, String>): Signer =
            playbackSigner ?: Signer(clock, values).also { playbackSigner = it }

        // 推荐使用另一版本请求上下文，不能借用已绑定播放器版本的签名器。
        @Synchronized
        fun recommendationSigner(clock: () -> Long, values: Map<String, String>): Signer =
            recommendationSigner ?: Signer(clock, values).also { recommendationSigner = it }
    }

    private var cdid = UUID.randomUUID().toString()
    private var ready: Profile? = null

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let {
            return it
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return toProfile(registerDevice(http)).also { ready = it }
    }

    @Synchronized
    internal fun existing(): Profile? = ready

    @Synchronized
    internal fun takeModel(id: String, owner: Any, now: Long) = ready?.takeModel(id, owner, now)

    /**
     * 在调用方的失败期限制内尝试替换匿名身份，部分历史采样有效，但不保证恢复。
     * 若其他仓库实例已替换 [expected]，直接复用替代身份，不重复注册。
     * 替代身份就绪时返回 true；失败时保留原身份。
     */
    @Synchronized
    internal fun rotate(http: OkHttpClient, expected: Profile? = null): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        // 共享会话的其他仓库实例已替换该失败身份，直接复用。
        if (expected != null && ready !== expected && ready != null) return true
        val device =
            try {
                register(http) ?: return false
            } catch (error: InterruptedIOException) {
                // 网络超时也可能使用此异常类型，不能据此把正常工作线程标为已取消。
                return false
            } catch (error: IOException) {
                return false
            }
        if (!validDevice(device)) return false
        cdid = UUID.randomUUID().toString()
        ready = toProfile(device)
        return true
    }

    private fun registerDevice(http: OkHttpClient): DeviceRegister.Registered {
        val device =
            try {
                register(http) ?: throw PlaybackDeviceRegistrationException()
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                throw PlaybackDeviceRegistrationException(error)
            }
        if (!validDevice(device)) throw PlaybackDeviceRegistrationException()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return device
    }

    private fun validDevice(device: DeviceRegister.Registered) =
        validId.matches(device.deviceId) && validId.matches(device.installId)

    private fun toProfile(device: DeviceRegister.Registered): Profile =
        Profile(
            VendorConstants.device +
                mapOf(
                    "device_id" to device.deviceId,
                    "iid" to device.installId,
                    "cdid" to cdid,
                ),
            device.cookies
                .filterKeys {
                    it in setOf("install_id", "ttreq", "store-region", "store-region-src")
                }
                .entries
                .joinToString("; ") { (name, value) -> "$name=$value" },
        )
}

private val validId = Regex("[1-9][0-9]{0,29}")
