// SPDX-License-Identifier: GPL-3.0-only
// Kotlin port of fqapp (github.com/ch6vip/fqapp) rust/src/device/register.rs
// and the registration half of rust/src/device/crypto.rs; GPL-3.0-only.
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** One upstream device issued by the log.snssdk.com registration endpoint. */
data class PlayerDevice(
    val deviceId: String,
    val installId: String,
    val cdid: String,
    val openudid: String,
    val secretKey: String,
) {
    fun toJson(): String = JSONObject()
        .put("device_id", deviceId).put("install_id", installId)
        .put("cdid", cdid).put("openudid", openudid).put("secret_key", secretKey).toString()
    companion object {
        fun fromJson(raw: String): PlayerDevice? = runCatching {
            val o = JSONObject(raw)
            val deviceId = o.optString("device_id")
            val installId = o.optString("install_id")
            if (!deviceId.matches(Regex("[0-9]{1,25}")) || !installId.matches(Regex("[0-9]{1,25}"))) return null
            PlayerDevice(deviceId, installId, o.optString("cdid"), o.optString("openudid"), o.optString("secret_key"))
        }.getOrNull()
    }
}

/** Persisted device slot; the TV app wires this to its own preferences store. */
interface PlayerDeviceStore {
    fun load(): String?
    fun save(value: String?)
}

internal class DeviceRejectedException(message: String, val identityExpired: Boolean = false) : java.io.IOException(message)

internal class DeviceRegistrar(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private inner class DevInfo(
        val osApi: String, val osVersion: String, val openudid: String, val cdid: String,
        val clientudid: String, val rom: String, val romVersion: String, val userAgent: String,
    ) {
        fun urlParams(extra: Map<String, String>): List<Pair<String, String>> {
            val now = (clock() / 1000).toString()
            val base = linkedMapOf(
                "tt_data" to "a", "os_api" to osApi, "device_type" to "MI 12", "ssmix" to "a",
                "manifest_version_code" to "65132", "dpi" to "560", "version_name" to "6.5.1.32",
                "ts" to now, "cpu_support64" to "true", "app_type" to "normal", "appTheme" to "light",
                "ac" to "wifi", "host_abi" to "arm64-v8a", "update_version_code" to "65132",
                "channel" to "xiaomi_1967_64", "_rticket" to clock().toString(),
                "device_platform" to "android", "version_code" to "65132", "cdid" to cdid,
                "os" to "android", "is_android_pad" to "0", "openudid" to openudid,
                "package" to PKG_NAME, "resolution" to "1440*2392", "os_version" to osVersion,
                "language" to "zh", "device_brand" to "Xiaomi", "need_personal_recommend" to "1",
                "aid" to "1967", "minor_status" to "0", "app_name" to "novel", "mcc_mnc" to "46007",
            )
            for ((k, v) in extra) { base.remove(k); base[k] = v }
            return base.entries.map { it.key to it.value }
        }
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
    private fun randomAlnum(n: Int): String = buildString(n) { repeat(n) { append(ALNUM[random.nextInt(ALNUM.length)]) } }
    private fun randomMac(): String = (1..6).joinToString(":") { "%02X".format(random.nextInt(256)) }
    private fun pickString(value: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!value.has(key)) continue
            val text = when (val item = value.opt(key)) {
                is String -> item
                is Number, is Boolean -> item.toString()
                else -> ""
            }
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    fun register(http: OkHttpClient): PlayerDevice {
        val di = newDevInfo()
        val (deviceId, installId) = deviceRegister(http, di)
        // Optional registration extras must not consume the first playback's entire budget.
        runCatching {
            RequestScope.withDeadline(System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1)) {
                appAlertCheck(http, di, deviceId, installId)
            }
        }
        RequestScope.ensureActive()
        val secretKey = runCatching {
            RequestScope.withDeadline(System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)) {
                registerKey(http, di, deviceId, installId, attempts = 1)
            }
        }.getOrDefault("")
        RequestScope.ensureActive()
        return PlayerDevice(deviceId, installId, di.cdid, di.openudid, secretKey)
    }

    private fun newDevInfo(): DevInfo {
        val openudid = ByteArray(8).also { random.nextBytes(it) }.hex()
        val osVersion = "10"
        val rom = "EMUI-" + randomAlnum(13)
        val ttNet = "TTNetVersion:9ac8d95c 2024-11-25 QuicVersion:3f326df4 2024-11-14"
        val userAgent = "com.dragon.read/65132 (Linux; U; Android $osVersion; zh_CN; MI 12; Build/MMB29M; Cronet/$ttNet)"
        return DevInfo("29", osVersion, openudid, UUID.randomUUID().toString(), UUID.randomUUID().toString(), rom, randomAlnum(2), userAgent)
    }

    private fun deviceRegister(http: OkHttpClient, di: DevInfo): Pair<String, String> {
        val header = JSONObject()
            .put("display_name", "番茄小说").put("update_version_code", "65132")
            .put("manifest_version_code", "65132").put("aid", 1967).put("channel", "xiaomi_1967_64")
            .put("package", PKG_NAME).put("app_version", "6.5.1.32").put("version_code", "65132")
            .put("sdk_version", SDK_VERSION).put("sdk_target_version", 29).put("os", "Android")
            .put("os_version", di.osVersion).put("os_api", di.osApi).put("device_model", "MI 12")
            .put("device_brand", "Xiaomi").put("device_manufacturer", "Google").put("device_category", "phone")
            .put("cpu_abi", "arm64-v8a").put("release_build", UUID.randomUUID().toString())
            .put("density_dpi", "560").put("display_density", "mdpi")
            .put("resolution", "1440x2392").put("language", "zh").put("mac", randomMac())
            .put("timezone", 8).put("access", "wifi").put("not_request_sender", 0)
            .put("carrier", "CHINA MOBILE").put("mcc_mnc", "46007").put("rom", di.rom)
            .put("rom_version", di.romVersion).put("sig_hash", digest("MD5", UUID.randomUUID().toString().toByteArray()).hex())
            .put("openudid", di.openudid).put("clientudid", di.clientudid)
            .put("sim_serial_number", org.json.JSONArray()).put("region", "CN")
            .put("tz_name", "Asia/Shanghai").put("tz_offset", 28800).put("sim_region", "cn")
        val body = JSONObject().put("magic_tag", "ss_app_log").put("header", header).put("_gen_time", clock())
        val query = sortedQuery(di.urlParams(emptyMap()))
        val request = Request.Builder()
            .url("https://log.snssdk.com/service/2/device_register/?$query")
            .header("content-type", "application/octet-stream;tt-data=a")
            .header("user-agent", di.userAgent)
            .header("connection", "Keep-Alive")
            .post(ttEncrypt(gzip(body.toString().toByteArray()), randomBytes(32)).toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val raw = execute(http, request)
        val parsed = playbackJson(raw.toString(Charsets.UTF_8), "device-register")
        val deviceId = pickString(parsed, "device_id_str", "device_id")
        val installId = pickString(parsed, "install_id_str", "install_id")
        if (deviceId.isEmpty() || installId.isEmpty()) throw java.io.IOException("设备注册响应异常")
        return deviceId to installId
    }

    private fun appAlertCheck(http: OkHttpClient, di: DevInfo, deviceId: String, installId: String) {
        val query = sortedQuery(di.urlParams(linkedMapOf("device_id" to deviceId, "iid" to installId)))
        val builder = Request.Builder()
            .url("https://ichannel.snssdk.com/service/2/app_alert_check/?$query")
            .header("content-type", "application/octet-stream;tt-data=a")
            .header("log-encode-type", "gzip")
            .header("x-tt-request-tag", "t=0;n=1")
            .header("x-ss-req-ticket", clock().toString())
            .header("sdk-version", "2")
            .header("passport-sdk-version", "65132")
            .header("x-vc-bdturing-sdk-version", "3.7.4.cn")
            .header("user-agent", di.userAgent)
            .header("connection", "Keep-Alive")
        for ((k, v) in PlayerSigner.registrationHeaders(query, null)) builder.header(k, v)
        runCatching { execute(http, builder.build()) }
    }

    private fun registerKey(http: OkHttpClient, di: DevInfo, deviceId: String, installId: String, attempts: Int = 3): String {
        val content = encryptRegisterKeyRequest(deviceId, null)
        val parts = listOf(
            "aid=1967", "app_name=novelapp", "channel=0", "device_id=$deviceId",
            "device_platform=android", "iid=$installId", "os_version=0",
            "version_code=6.1.5.32", "version_name=6.1.5.32",
        ).sorted().joinToString("&")
        var lastError: Exception? = null
        for (attempt in 0 until attempts) {
            try {
                val payload = gzip(JSONObject().put("content", content).toString().toByteArray())
                val request = Request.Builder()
                    .url("https://reading.snssdk.com/reading/crypt/registerkey?$parts")
                    .header("User-Agent", PKG_NAME)
                    .header("Content-Encoding", "gzip")
                    .post(payload.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                val signed = PlayerSigner.registrationHeaders(parts, payload)
                val builder = request.newBuilder()
                for ((k, v) in signed) builder.header(k, v)
                val raw = execute(http, builder.build())
                val key = JSONObject(raw.toString(Charsets.UTF_8)).optJSONObject("data")?.optString("key").orEmpty()
                if (key.isEmpty()) throw java.io.IOException("注册密钥响应缺少 key")
                return decryptRegisterKey(key).hex().uppercase()
            } catch (e: Exception) {
                RequestScope.ensureActive()
                lastError = e
                if (attempt + 1 < attempts) sleep(1000)
            }
        }
        throw lastError ?: java.io.IOException("注册密钥失败")
    }

    private fun execute(http: OkHttpClient, request: Request): ByteArray =
        RequestScope.execute(http.newCall(request)) { response ->
            if (response.code == 401 || response.code == 403 || response.code == 10001 || response.code == 10002)
                throw DeviceRejectedException("设备注册被拒绝（HTTP ${response.code}）")
            if (!response.isSuccessful) throw java.io.IOException("设备注册请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw java.io.IOException("设备注册响应为空")
            val input = body.byteStream(); val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() <= 4 * 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
            if (out.size() > 4 * 1024 * 1024) throw java.io.IOException("设备注册响应过大")
            out.toByteArray()
        }

    companion object {
        private const val PKG_NAME = "com.dragon.read"
        private const val SDK_VERSION = "v04.04.05-ov-android"
        private val TT_FIXED_STRING = decode64("TdTC5rgxYgkOUrPHpnM7pByyRiuCmrWKGWs521cXdST0m69/COjWjSanLjfBqVovHwWlGJKu8pSXMrYqOKrdWA==")
        private val TT_MAGIC = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)
        private const val REGISTER_KEY_MASTER_HEX = "ac25c67ddd8f38c1b37a2348828e222e"
        private const val REGISTER_KEY_REQ_KEY_B64 = "rCXGfd2POMGzeiNIgo4iLg=="
        private const val ALNUM = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        internal fun gzip(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(data) }
            return out.toByteArray()
        }

        // key/iv = SHA512(SHA512(random32) + fixedString)[0:16], [16:32]
        // output = magic + random32 + AES-128-CBC(PKCS7(SHA512(data) + data))
        internal fun ttEncrypt(data: ByteArray, randomBytes32: ByteArray): ByteArray {
            val inner = digest("SHA-512", randomBytes32)
            val hv = digest("SHA-512", inner + TT_FIXED_STRING)
            val padded = pkcs7Pad(digest("SHA-512", data) + data)
            return TT_MAGIC + randomBytes32 + aesEncryptWith(padded, hv.copyOfRange(0, 16), hv.copyOfRange(16, 32))
        }

        internal fun pkcs7Pad(data: ByteArray): ByteArray {
            val pad = 16 - data.size % 16
            return data + ByteArray(pad) { pad.toByte() }
        }
        internal fun pkcs7Unpad(data: ByteArray): ByteArray {
            if (data.isEmpty()) return data
            val pad = data.last().u()
            if (pad !in 1..16 || pad > data.size) return data
            if (data.takeLast(pad).any { it.u() != pad }) return data
            return data.copyOfRange(0, data.size - pad)
        }

        /** Left-pads the decimal id's hex to 32 chars, then reverses byte pairs. */
        internal fun reverseHex(deviceId: String): String {
            val n = deviceId.toLongOrNull() ?: throw IllegalArgumentException("device_id is not numeric: $deviceId")
            require(deviceId.matches(Regex("[0-9]+")))
            val h = "%032x".format(n)
            val out = StringBuilder(32)
            var i = h.length
            while (i >= 2) { out.append(h, i - 2, i); i -= 2 }
            return out.toString()
        }

        /** base64(iv + AES-128-CBC-PKCS7(hex2bin(reverseHex(deviceId)))) */
        internal fun encryptRegisterKeyRequest(deviceId: String, iv: ByteArray?): String {
            val key = decode64(REGISTER_KEY_REQ_KEY_B64)
            val realIv = iv ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
            val raw = hex(reverseHex(deviceId))
            return (realIv + aesEncryptWith(pkcs7Pad(raw), key, realIv)).base64()
        }

        internal fun decryptRegisterKey(encryptedKeyB64: String): ByteArray {
            val data = decode64(encryptedKeyB64)
            require(data.size >= 16) { "registerkey data too short" }
            val cipherText = data.copyOfRange(16, data.size)
            require(cipherText.isNotEmpty() && cipherText.size % 16 == 0) { "registerkey ciphertext not block-aligned" }
            return pkcs7UnpadStrict(aes("CBC", hex(REGISTER_KEY_MASTER_HEX), data.copyOfRange(0, 16), cipherText))
        }
        internal fun pkcs7UnpadStrict(data: ByteArray): ByteArray {
            require(data.isNotEmpty())
            val pad = data.last().u()
            require(pad in 1..16 && pad <= data.size) { "registerkey padding invalid" }
            require(data.takeLast(pad).all { it.u() == pad }) { "registerkey padding bytes invalid" }
            return data.copyOfRange(0, data.size - pad)
        }

        /** Go url.Values.Encode(): sorted keys, '+' for spaces, ~ unreserved. */
        internal fun goQueryEscape(s: String): String = buildString {
            for (byte in s.toByteArray(Charsets.UTF_8)) {
                val b = byte.toInt() and 255
                when {
                    b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code || b in '0'.code..'9'.code ||
                        b == '-'.code || b == '_'.code || b == '.'.code || b == '~'.code -> append(b.toChar())
                    b == ' '.code -> append('+')
                    else -> append("%%%02X".format(b))
                }
            }
        }
        internal fun sortedQuery(params: List<Pair<String, String>>): String =
            params.sortedBy { it.first }.joinToString("&") { "${goQueryEscape(it.first)}=${goQueryEscape(it.second)}" }
    }
}

private fun aesEncryptWith(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
    javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
        init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        doFinal(data)
    }
