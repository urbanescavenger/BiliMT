// Adapted 2026-10-21 from charlie901208/HGTV feat/phone-login (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 手机号 + 验证码登录（大陆与境外号码都收）。
 * * [send]：沿用 passport 设备参数发送验证码，不进行匿名设备注册。
 * * [verify]：用**同一台设备**验码，平台在 `Set-Cookie` 里下发 `sessionid`。 换到的凭据带上这台设备的 `device_id` /
 *   `iid`（[AccountCredentials.device]）， 之后账号接口的签名都用它，会话与设备保持一致。
 *
 * 一个实例对应一次登录；调用方应在重新打开登录面板时复用 [RetryGate]，避免连续重试。
 */
class PhoneLogin(private val http: OkHttpClient, private val retryGate: RetryGate = RetryGate()) {
    var deviceId = ""
    var installId = ""
    var mobile = ""
    var ticket = ""
    var type = LOGIN_CODE_TYPE
    private var expired = false
    val cookies = linkedMapOf<String, String>()
    /** Compatibility field; authentication responses are never retained here. */
    val trace = mutableListOf<String>()

    /** 平台拒绝时的可读原因，界面直接显示 [message]。 */
    class Failure(
        message: String,
        val errorCode: Int? = null,
        val canRetryCode: Boolean = false,
        val retrySeconds: Int = 0,
    ) :
        Exception(message)

    /** 本地退避仅减少重复请求，并不表示平台限制会在倒计时结束时解除。 */
    class RetryGate(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
        @Volatile private var until = 0L

        fun remainingSeconds(): Int =
            ((until - nowMillis()).coerceAtLeast(0).let { (it + 999) / 1000 })
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        internal fun reject(seconds: Int): Nothing {
            until = nowMillis() + seconds.coerceAtLeast(60).toLong() * 1000
            check()
            error("rate limit deadline must be in the future")
        }

        internal fun check() {
            val seconds = remainingSeconds()
            if (seconds > 0) throw Failure(
                "平台提示操作过于频繁（错误码 7）。请至少等待 ${seconds} 秒后手动重试；倒计时不代表平台限制已解除。如仍失败，可稍后再试或改用扫码登录。",
                errorCode = 7,
                retrySeconds = seconds,
            )
        }
    }

    /** [maskedMobile] 是平台回显的打码号码（如 `+886******23`）。 */
    data class Sent(val maskedMobile: String, val retrySeconds: Int)

    fun send(mobile: String): Sent {
        retryGate.check()
        // 官方 App 的短信登录不注册新设备：它直接用设备表里的固定 device_id/iid，且不携带
        // 注册 cookie。实测新注册设备 + query 带 device_id 会被 passport 判 `2046`。
        deviceId = VendorConstants.device.getValue("device_id")
        installId = VendorConstants.device.getValue("iid")
        cookies.clear()
        ticket = ""
        expired = false
        this.mobile = mobile
        val reply =
            call(
                "/passport/mobile/send_code/v1/",
                mapOf("mobile" to mobile, "type" to type),
                form = false,
            )
        val data = reply.optJSONObject("data") ?: JSONObject()
        // 风控要求滑块验证：电视上没法做，也不去绕它。
        if (data.has("verify_center_decision_conf"))
            throw Failure("平台要求先完成滑块验证，当前电视登录流程不支持，尚未完成登录。")
        if (reply.optString("message") != "success")
            throw Failure(describe(data) ?: "验证码没发出去", data.optInt("error_code", 0))
        ticket = data.optString("mobile_ticket")
        return Sent(data.optString("mobile").ifBlank { mobile }, data.optInt("retry_time", 60))
    }

    fun verify(code: String): AccountCredentials {
        retryGate.check()
        if (expired) throw Failure("本次登录会话已失效，请重新获取验证码", 1)
        check(deviceId.isNotEmpty()) { "请先发送验证码" }
        var params =
            mapOf("mobile" to mobile, "code" to code, "type" to type, "mobile_ticket" to ticket)
        val failures = mutableListOf<Failure>()
        for ((path, form) in VERIFY_ATTEMPTS) {
            val reply = call(path, params, form)
            if (cookies.containsKey("sessionid")) break
            val data = reply.optJSONObject("data")
            // 平台会在每次尝试后下发新的 mobile_ticket，后续端点沿用最新值。
            data?.optString("mobile_ticket")?.takeIf(String::isNotBlank)?.let { fresh ->
                ticket = fresh
                params = params + ("mobile_ticket" to fresh)
            }
            val errorCode = data?.optInt("error_code", 0) ?: 0
            when {
                // 1 = 会话过期：这条端点不适用于「未登录换验证码」，继续试下一条。
                errorCode == 0 || errorCode == 1 -> {
                    if (errorCode == 1) failures += Failure("会话过期，请重新登录（错误码 1）", 1)
                    continue
                }
                // 1203 = 错误次数过多或验证码过期：本次会话作废，继续提交只会更糟。
                errorCode == 1203 -> {
                    expired = true
                    cookies.clear()
                    ticket = ""
                    throw Failure(
                        describe(data) ?: "错误次数过多或验证码过期，请重新获取验证码（错误码 $errorCode）",
                        errorCode,
                    )
                }
                else ->
                    throw Failure(
                        describe(data) ?: "验证码校验失败（错误码 $errorCode）",
                        errorCode,
                        errorCode == 1102,
                    )
            }
        }
        if (!cookies.containsKey("sessionid")) {
            throw failures.firstOrNull()
                ?: Failure("登录没有成功，平台没有下发登录态")
        }
        val header = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        return AccountCredentialsParser.parseHeader(header)
            .copy(
                deviceId = deviceId,
                installId = installId,
                device = mapOf("device_id" to deviceId, "iid" to installId),
            )
    }

    /** 带这台设备的身份签名后发一次 passport 请求；`Set-Cookie` 并进 [cookies]，返回 JSON（非 JSON 时包成 `raw`）。 */
    private fun call(path: String, params: Map<String, String>, form: Boolean): JSONObject {
        val device =
            LinkedHashMap(VendorConstants.device).apply {
                put("device_id", deviceId)
                put("iid", installId)
                for (key in
                    listOf(
                        "version_code",
                        "manifest_version_code",
                        "update_version_code",
                        "pv_player",
                    )) put(key, "73932")
                put("version_name", "7.3.9.32")
            }
        val passport =
            mapOf("aid" to "8662", "app_name" to "novelread", "device_platform" to "android") +
                params.filterValues { it.isNotBlank() }
        val payload = if (form) Signer.encode(passport).toByteArray() else null
        // 官方 App 的 passport 请求把 device_id/iid 从 query 里去掉（设备靠 Cookie 识别），
        // 带着它们发会被判异常设备；签名器内部仍使用完整设备表。
        val queryDevice = device.filterKeys { it != "device_id" && it != "iid" }
        val signed =
            Signer(device = device)
                .sign(PASSPORT + path, if (form) queryDevice else queryDevice + passport, payload)
        val request = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        // 刻意不回发 Cookie：实测把 send_code 下发的 cookie 带到 sms_login 会被 passport
        // 判 `2046`，而不带 cookie 时同一请求回 `1202 验证码错误`。Set-Cookie 仍会解析并保留，
        // 只用于判断是否已经拿到 sessionid。
        if (payload != null)
            request.post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        val reply =
            http.newCall(request.build()).execute().use { response ->
                response.headers("Set-Cookie").forEach { line ->
                    val pair = line.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
                }
                val text = cloudResponse(response)
                runCatching { JSONObject(text) }
                    .getOrElse {
                        JSONObject().put("http", response.code).put("raw", text.take(300))
                    }
            }
        // Stop before fallback endpoints or UI retries; never rotate identity to evade a limit.
        val data = reply.optJSONObject("data")
        if (data?.optInt("error_code") == 7) {
            cookies.clear()
            retryGate.reject(data.optInt("retry_time", 60))
        }
        // Never retain authentication responses or verification codes in diagnostics.
        return reply
    }

    private fun describe(data: JSONObject): String? =
        data
            .optString("description")
            .takeIf { it.isNotBlank() }
            ?.let { "$it（错误码 ${data.optInt("error_code")}）" }

    companion object {
        /** 官方 App 的 passport 域是 `security.snssdk.com`；`is.snssdk.com` 上短信登录会被风控回 2046。 */
        private const val PASSPORT = "https://security.snssdk.com"
        /** login-lab 实测：24 = 登录验证码；1 / 3 是绑定 / 换绑手机号。 */
        const val LOGIN_CODE_TYPE = "24"
        /**
         * 短信登录的主端点是 `sms_login` 系列；`validate_code` 是「已登录后校验手机号」的入口，
         * 对未登录会话固定回 `error_code 1 会话过期`（2026-11-03 实测），因此放到最后兜底。
         */
        private val VERIFY_ATTEMPTS =
            listOf(
                "/passport/mobile/sms_login/" to true,
                "/passport/mobile/sms_login_only/" to true,
                "/passport/mobile/sms_login_continue/" to true,
                "/passport/mobile/validate_code/v1/" to false,
                "/passport/mobile/validate_code/v1/" to true,
            )

        /**
         * 区号 + 本地号码 → 平台认的手机号；不合格时抛 [IllegalArgumentException]，[Throwable.message] 可直接显示。
         * * 大陆（86）：1 开头 11 位，原样发（实测可用）；
         * * 境外：`+区号 号码`（区号后一个空格，去掉本地开头的 0），passport 的惯用写法； 长度按 E.164——区号 1–3 位，区号 + 号码合计不超过 15
         *   位，号码至少 4 位。
         *
         * 号码里的空格、横线等非数字字符一律忽略。
         */
        fun format(countryCode: String, number: String): String {
            val cc = countryCode.filter(Char::isDigit)
            val digits = number.filter(Char::isDigit).removePrefix("0")
            if (cc == "86") {
                require(digits.matches(Regex("1\\d{10}"))) { "大陆手机号是 1 开头的 11 位数字" }
                return digits
            }
            require(cc.matches(Regex("[1-9]\\d{0,2}"))) { "区号是 1–3 位数字" }
            require(digits.length >= 4 && cc.length + digits.length <= 15) { "号码长度不对：区号加号码最多 15 位" }
            return "+$cc $digits"
        }

        /** 一整串的写法：`13800138000`（大陆），或 `+区号-号码` / `+区号 号码`（如 `+886-0912345678`）。 */
        fun parse(raw: String): String {
            val text = raw.trim()
            if (!text.startsWith("+")) return format("86", text)
            val cc = text.drop(1).takeWhile(Char::isDigit)
            val rest = text.drop(1 + cc.length)
            require(rest.firstOrNull() == '-' || rest.firstOrNull() == ' ') {
                "境外号码写成 +区号 号码，如 +886 0912345678"
            }
            return format(cc, rest)
        }
    }
}
