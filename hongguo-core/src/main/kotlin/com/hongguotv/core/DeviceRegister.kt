// Adapted 2026-10-21 from charlie901208/HGTV feat/phone-login (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * 设备注册（`POST /service/2/device_register/`）。
 *
 * ## 为什么需要它
 *
 * 平台的**内容接口按设备放行**，而且认的是「服务端注册过的 `device_id` + `install_id` 这一对」：
 * * App 搜索 `POST /reading/bookapi/search/tab/v` —— 未注册设备一律 `HTTP 200 + 0 字节`； 注册过的设备回 `code 0`（第 1
 *   页 6/20 条、带 `has_more` 游标可翻页）或间歇性 `100103`， **从不回空响应**。实测：同一套签名，注册前 0/6 命中，注册后 6/6 有数据。
 * * 播放 `POST /novel/player/video_model/v1/` —— 未注册设备回 `101000 服务异常`， 注册后回 `code 0` + 完整播放模型。
 *
 * 也就是说「导入 sessionid 才能用」这个印象，其实是「设备没注册」。注册是**匿名**的： 不需要账号、不需要密码、不需要短信，请求里连签名头都没有。
 *
 * ## 协议（照抓包逐字段复现）
 *
 * ```
 * POST https://log5-applog-lf.fqnovel.com/service/2/device_register/
 *      ?tt_data=a&cronet_version=…&ttnet_version=…&use_store_region_cookie=1
 * Content-Type: application/octet-stream;tt-data=a
 * log-encode-type: gzip
 * x-ss-dp: 8662
 * x-neptune: -8|50:51:59:30:40:47:49:39
 * x-ss-stub: <body 的 MD5，大写>
 * body: ttEncrypt(gzip(JSON))
 * ```
 *
 * 响应：`{"server_time":…,"device_id":…,"install_id":…,"device_token":"…","new_user":0|1}` 并
 * `Set-Cookie` 下发 `store-region` / `store-region-src=did` / `install_id` / `ttreq`。
 *
 * ## 三个容易踩的坑（都是实测踩出来的）
 * 1. **body 是原始二进制**，不是 base64 文本。抓包 HAR 里 `postData.text` 是 base64， 但 `content-length`
 *    等于解码后的字节数——照着 `postData.text` 发 base64 文本， 服务端一律回 `device_id=0`（这正是本仓库早期「注册不出来」的原因）。
 * 2. **响应可能被 brotli 压缩**（`Content-Encoding: br`）。`Accept-Encoding` 里不带 `br` 也能拿到未压缩的
 *    JSON，但带上就必须自己解，否则 JSON 解析失败。
 * 3. **不要加签名头**。官方这条请求没有 `x-gorgon` / `x-khronos` / `x-ladon` / `x-argus` / `x-helios` /
 *    `x-medusa`，query 里也没有 `ts` / `_rticket`。带上签名头反而会被判异常客户端。
 */
object DeviceRegister {
    /** 注册接口的域名。`is.snssdk.com` 与 `log5-applog-lf` 等价，抓包里两个都在用。 */
    private const val HOST = "https://log5-applog-lf.fqnovel.com"
    private const val PATH = "/service/2/device_register/"
    private const val QUERY =
        "?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1"
    /** ttEncrypt 的固定前缀（`libEncryptor.so` 的魔数）。 */
    private val MAGIC = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)
    /** ttEncrypt 的 KDF 盐；与 `MediaCrypto.decryptUrl` 用的是同一份常量。 */
    private val KDF_SALT =
        hex(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
        )

    /** 注册结果：平台下发的设备身份 + 需要回写的那几个 Cookie。 */
    data class Registered(
        val deviceId: String,
        val installId: String,
        val deviceToken: String = "",
        /** `new_user=1` 表示平台这次新建了一台设备。 */
        val newUser: Boolean = false,
        /** `Set-Cookie` 里可直接回写的那几项（`install_id` / `ttreq` / `store-region` …）。 */
        val cookies: Map<String, String> = emptyMap(),
    )

    /**
     * ttEncrypt：`MAGIC || rnd[32] || AES-128-CBC(SHA512(SHA512(rnd)+KDF_SALT) 前 32 字节,
     * PKCS7(SHA512(data)||data))`。
     */
    fun ttEncrypt(data: ByteArray): ByteArray {
        val rnd = ByteArray(32).also { java.util.Random().nextBytes(it) }
        val derived = digest("SHA-512", digest("SHA-512", rnd) + KDF_SALT)
        val body = digest("SHA-512", data) + data
        val padding = 16 - (body.size % 16)
        val padded = body + ByteArray(padding) { padding.toByte() }
        val encrypted =
            javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
                init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(derived.copyOfRange(0, 16), "AES"),
                    javax.crypto.spec.IvParameterSpec(derived.copyOfRange(16, 32)),
                )
                doFinal(padded)
            }
        return MAGIC + rnd + encrypted
    }

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { out -> GZIPOutputStream(out).use { it.write(data) } }
            .toByteArray()

    /**
     * 组装注册请求体：`ttEncrypt(gzip(JSON))`。
     *
     * 字段与抓包里那份逐一对齐；`install_id` / `device_id` **不带**（冷启动首注册就是不带， 由服务端下发）。`_gen_time` 是毫秒时间戳。
     */
    fun payload(device: Map<String, String>, now: Long = System.currentTimeMillis()): ByteArray {
        fun value(key: String, fallback: String) =
            device[key]?.takeIf { it.isNotBlank() } ?: fallback
        val json = org.json.JSONObject()
        json.put("magic_tag", "ss_app_log")
        json.put(
            "header",
            org.json.JSONObject().apply {
                put("display_name", "红果免费短剧")
                put("update_version_code", 73932)
                put("manifest_version_code", 73932)
                put("app_version_minor", "7.3.9.32")
                put("aid", 8662)
                put("channel", value("channel", "69310258a"))
                put("package", "com.phoenix.read")
                put("app_version", "7.3.9.32")
                put("version_code", 73932)
                put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo")
                put("sdk_target_version", 29)
                put("git_hash", "3a88f4e")
                // `os_version` 必须是**字符串**：写成数字（`12`）服务端直接回 `device_id=0`
                // （实测字符串 6/6 成功、数字 0/6）。`os_api` / `density_dpi` 两种都收。
                put("os", "Android")
                put("os_version", value("os_version", "12"))
                put("os_api", value("os_api", "32").toIntOrNull() ?: 32)
                put("device_model", value("device_type", "AOSP on Intel Platform"))
                put("device_brand", value("device_brand", "SAMSUNG"))
                put("device_manufacturer", value("device_brand", "Intel"))
                put("cpu_abi", value("host_abi", "arm64-v8a"))
                put("release_build", "b5e187f")
                put("density_dpi", value("dpi", "360").toIntOrNull() ?: 360)
                put("display_density", "mdpi")
                put("resolution", value("resolution", "1440x2560").replace("*", "x"))
                put("language", "zh")
                put("timezone", 8)
                put("access", "wifi")
                put("not_request_sender", 0)
                put("carrier", "CHINA MOBILE")
                put("mcc_mnc", "46000")
                put("rom", "eng.root.20231116.160340")
                put("rom_version", value("rom_version", "W528JS release-keys"))
                put("cdid", value("cdid", UUID.randomUUID().toString()))
                // sig_hash 是安装包的签名指纹；平台不校验它是否与真实包一致，但缺了会拒。
                put("sig_hash", "56a962410c494bbaf0b58dba20cae56f")
                put(
                    "openudid",
                    value("openudid", UUID.randomUUID().toString().replace("-", "").take(16)),
                )
                put("clientudid", UUID.randomUUID().toString())
                put("ipv6_list", org.json.JSONArray())
                put("region", "CN")
                put("tz_name", "Asia/Shanghai")
                put("tz_offset", 28800)
                put("sim_region", "cn")
                put("sim_serial_number", org.json.JSONArray())
                put("oaid_may_support", false)
                put("req_id", UUID.randomUUID().toString())
                put("device_platform", "android")
                put(
                    "custom",
                    org.json.JSONObject().apply {
                        put("account_region_src", "fallback")
                        put("host_bit", 64)
                        put("account_region", "cn")
                        put("dragon_device_type", value("dragon_device_type", "pad"))
                    },
                )
                put("pre_installed_channel", "")
                put("apk_first_install_time", now)
                put("is_system_app", 0)
                put("sdk_flavor", "china")
                put("guest_mode", 0)
            },
        )
        json.put("_gen_time", now)
        return ttEncrypt(gzip(json.toString().toByteArray(Charsets.UTF_8)))
    }

    /** 注册请求的头。**刻意不加签名头**（见类注释第 3 条）。 */
    fun headers(
        body: ByteArray,
        device: Map<String, String>,
        now: Long = System.currentTimeMillis(),
    ): Map<String, String> =
        linkedMapOf(
            "User-Agent" to
                (device["user_agent"]?.takeIf { it.isNotBlank() } ?: VendorConstants.APP_UA),
            "Content-Type" to "application/octet-stream;tt-data=a",
            "log-encode-type" to "gzip",
            "x-ss-dp" to "8662",
            "x-ss-req-ticket" to now.toString(),
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "lc" to "101",
            "sdk-version" to "2",
            "passport-sdk-version" to "5051452",
            // **不要手动设 `accept-encoding`**：OkHttp 只在「自己加的」这个头时才透明解压。
            // 手动写成 `gzip, deflate` 会让它跳过解压，body 到手上还是压缩字节，JSON 解析必失败。
            // 交给 OkHttp 自己加 `gzip`，它顺手把响应解好。
            "x-neptune" to "-8|50:51:59:30:40:47:49:39",
            "x-ss-stub" to digest("MD5", body).hex().uppercase(),
        )

    fun url() = HOST + PATH + QUERY

    /**
     * 解析注册响应。
     *
     * `device_id` / `install_id` 平台有时给数字、有时给字符串，两种都认； 全为 `0` 表示服务端拒了这次注册（body 形态或头不对），返回 null
     * 由调用方放弃。
     */
    fun parse(body: String, setCookies: List<String> = emptyList()): Registered? {
        val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
        val device =
            json.opt("device_id_str")?.toString()?.takeIf { it.isNotBlank() }
                ?: json.opt("device_id")?.toString().orEmpty()
        val install =
            json.opt("install_id_str")?.toString()?.takeIf { it.isNotBlank() }
                ?: json.opt("install_id")?.toString().orEmpty()
        if (device.isBlank() || install.isBlank() || device == "0" || install == "0") return null
        val cookies = linkedMapOf<String, String>()
        setCookies.forEach { header ->
            header
                .split(';')
                .firstOrNull()
                ?.trim()
                ?.takeIf { it.contains('=') }
                ?.let { pair ->
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=').trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
                }
        }
        return Registered(
            device,
            install,
            json.optString("device_token", ""),
            json.optInt("new_user", 0) == 1,
            cookies,
        )
    }
}
