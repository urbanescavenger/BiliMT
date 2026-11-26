// Kotlin port of drpys 22261adfa31435e3b3ef5a730b8a2a75bcaf1715; GPL-3.0-only.
// Only hash branch 0 is emitted: adjust the request ticket before signing.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class Signer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val device: Map<String, String> = VendorConstants.device,
    private val random: () -> Double = Math::random,
    // 官方 App 的 medusa 明文内嵌「进程 nonce」（每次冷启动随机生成一次）与
    // 「进程启动时间」；测试对拍时注入固定值与 oracle 对齐。
    // Android 平台 Base64(URL-safe、无填充),等价于 JDK 的
    // java.util.Base64.getUrlEncoder().withoutPadding();minSdk 23 上 Java 8 Base64 不可用。
    private val processNonce: String = android.util.Base64.encodeToString(
        ByteArray(18).also(java.security.SecureRandom()::nextBytes),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
    ),
    private val mobileV5: Boolean = false,
) {
    private val processStarted: Long = clock()
    // medusa 明文里的 f5/f16 设备令牌：24 字符 base64url nonce 前置 'A' 补足 25 位。
    private val deviceToken: String =
        processNonce.takeIf { it.length == 25 && it.startsWith("A") }
            ?: ("A" + (processNonce + "AAAAAAAAAAAAAAAAAAAAAAAA").take(24))
    data class Request(val url: String, val headers: Map<String, String>, val body: ByteArray?)

    private fun rand() = (random() * 4294967296.0).toLong().toInt()

    private fun unsigned(v: Int) = v.toLong() and 0xffffffffL

    private fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream()
            .apply {
                while (n > 127) {
                    write(((n and 127) or 128).toInt())
                    n = n ushr 7
                }
                write(n.toInt())
            }
            .toByteArray()
    }

    private data class Field(val tag: Int, val value: Any, val type: String = "string")

    private fun f(tag: Int, value: Any, type: String = "string") = Field(tag, value, type)

    private fun proto(vararg fields: Field): ByteArray =
        ByteArrayOutputStream()
            .apply {
                for ((tag, value, type) in fields) {
                    when (type) {
                        "sint" -> {
                            val n = (value as Number).toLong()
                            write(varint(tag.toLong() shl 3))
                            write(varint(if (n < 0) -n * 2 - 1 else n * 2))
                        }
                        "float" -> {
                            write(varint((tag.toLong() shl 3) or 5))
                            write(
                                ByteBuffer.allocate(4)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .putFloat((value as Number).toFloat())
                                    .array()
                            )
                        }
                        else -> {
                            val bytes =
                                if (value is ByteArray) value else value.toString().toByteArray()
                            if (bytes.isNotEmpty()) {
                                write(varint((tag.toLong() shl 3) or 2))
                                write(varint(bytes.size.toLong()))
                                write(bytes)
                            }
                        }
                    }
                }
            }
            .toByteArray()

    private fun getIv(initial: Int, data: ByteArray): Int {
        var value = initial
        for (i in data.indices) value =
            if (i and 1 == 0) (value ushr 4) xor value xor (value shl 6) xor data[i].u()
            else ((value ushr 7) xor value xor (data[i].u() or (value shl 12))).inv()
        return value
    }

    private fun iv(query: ByteArray, body: ByteArray, ts: Int) =
        getIv(getIv(getIv(0x20230928, query), body), le32(ts))

    fun branch(query: String, body: ByteArray?, ts: Int): Int =
        (iv(sm3(query.toByteArray()), body?.let { digest("MD5", it) } ?: ByteArray(16), ts) and 15)
            .let { if (mobileV5) { if (it == 12) 0 else 1 } else it % 3 }

    private fun sumMd5(data: ByteArray): Int {
        var check = 0x20220420
        for (i in 0 until 12) {
            val temp = (check ushr (if (i and 1 == 0) 3 else 5)) xor check
            check =
                if (i and 1 == 0) data[i].u() xor (check shl 7)
                else (data[i].u() or (check shl 11)).inv()
            check = check xor temp
        }
        return (check or 4) xor 0x1000000
    }

    fun hashF13(query: ByteArray, body: ByteArray, ts: Int): ByteArray {
        val index = (iv(query, body, ts) and 15) / 3
        require(if (mobileV5) (iv(query, body, ts) and 15) == 12
            else (iv(query, body, ts) and 15) % 3 == 0) { "Unsupported hash branch" }
        val ivV1 =
            if (mobileV5) 0x9d4a97c2.toInt() else intArrayOf(
                0xc4a78580.toInt(),
                0xb3c0fd39.toInt(),
                0xc58c5686.toInt(),
                0xc9aa3ba7.toInt(),
                0xf5a7adf2.toInt(),
                0x963c2ed1.toInt(),
            )[index]
        val count = ivV1 + ts
        val tt = VendorConstants.hashTable.map { ror(it, (count + 1) and 255) }
        val shift = (count + 2) and 7
        val pad =
            intArrayOf(0xfa, 0x45, 0x61, 0xd7)
                .map { ((it or (it shl 8)) shr shift).toByte() }
                .toByteArray()
        val data = query + body + le32(ts) + pad + hex("00000000000001a0")
        val di = (data.indices step 4).map { data.i32(it) }.toMutableList()
        for (i in 0 until 112) {
            val x = di[i + 1]
            val y = di[i + 14]
            di.add(
                di[i] +
                    di[i + 9] +
                    (rol(x, 14) xor rol(x, 25) xor (x ushr 3)) +
                    (rol(y, 13) xor rol(y, 15) xor (y ushr 10))
            )
        }
        val initial =
            intArrayOf(
                    0x7aba4fc8,
                    0x67166507,
                    0x6403fa00,
                    0x340f512f,
                    984304912,
                    3005047866L.toInt(),
                    2874125293L.toInt(),
                    2152413264L.toInt(),
                )
                .map { ror(it, count) }
        val chosen =
            if (mobileV5) intArrayOf(96, 1, 3, 4, 0, 6, 5, 2, 0, 7, 7) else arrayOf(
                intArrayOf(101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3),
                intArrayOf(96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4),
                intArrayOf(96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5),
                intArrayOf(99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6),
                intArrayOf(96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4),
                intArrayOf(100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5),
            )[index]
        val d = initial.toMutableList()
        for (i in 0 until chosen[0]) {
            val base = ivV1 + i
            val n1 = ((d[chosen[3]] xor d[chosen[4]]) and d[chosen[1]]) xor d[chosen[3]]
            val n2 = rol(d[chosen[1]], 26) xor rol(d[chosen[1]], 21) xor rol(d[chosen[1]], 7)
            val n4 = di[base and 127] + n1 + n2 + tt[base and 63] + d[chosen[5]]
            val n5 = rol(d[chosen[2]], 30) xor rol(d[chosen[2]], 19) xor rol(d[chosen[2]], 10)
            val n6 =
                (d[chosen[2]] and d[chosen[6]]) or ((d[chosen[2]] or d[chosen[6]]) and d[chosen[7]])
            val old = d[chosen[9]]
            d.add(0, d.removeAt(7))
            d[chosen[10]] = n5 + n6 + n4
            d[chosen[8]] = old + n4
        }
        val ret = (0..7).fold(byteArrayOf()) { a, i -> a + be32(d[i] + initial[i]) }
        val folded = xor(ret.copyOfRange(0, 16), ret.copyOfRange(16, 32))
        return folded + le32(sumMd5(folded) or (if (mobileV5) 0x20 else 0))
    }

    fun gorgon(query: String, body: ByteArray?, ts: Int, random: Int): String {
        val input =
            digest("MD5", query.toByteArray()).copyOf(4) +
                (body?.let { digest("MD5", it).copyOf(4) } ?: ByteArray(4)) +
                ByteArray(4) +
                le32(67503104) +
                be32(ts)
        val key =
            intArrayOf(0x4a, 0x40, 0x16, (random shr 8) and 255, 0x47, 0x6c, 1, random and 255)
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + key[i % 8]) and 255
            s[i] = s[j]
        }
        j = 0
        val out =
            ByteArray(input.size) { n ->
                val i = n + 1
                j += s[i]
                val y = s[j and 255]
                s[i] = y
                (input[n].u() xor s[(y + y) and 255]).toByte()
            }
        for (i in out.indices) {
            val value = ((out[i].u() shr 4) or (out[i].u() shl 4)) and 255
            val next = out[if (i + 1 < out.size) i + 1 else 0].u()
            val reversed = Integer.reverse((next xor value) and 255) ushr 24
            out[i] = (reversed xor 20).inv().toByte()
        }
        return (hex("8404") + byteArrayOf(random.toByte(), (random shr 8).toByte(), 0x40, 1) + out)
            .hex()
    }

    // medusa 内嵌的设备身份必须与 query 中的注册身份一致（服务端核对 medusa 明文里的
    // device_id 与请求参数）；identity 为空时沿用构造时的静态占位身份。
    private fun deviceProto(identity: Map<String, String>? = null) =
        proto(
            f(1, 1, "sint"),
            f(2, 2, "sint"),
            f(3, "8662"),
            f(4, (identity ?: device).getValue("device_id")),
            f(5, processNonce),
            f(6, "!noperm!"),
            f(7, -888888, "sint"),
            f(8, -888888, "sint"),
            f(9, 3, "sint"),
            f(10, -888888, "sint"),
            f(11, "!notset!"),
            f(12, "Asia/Shanghai,8"),
            f(13, "zh_CN"),
            f(14, 4, "sint"),
            f(16, 0f, "float"),
            f(17, 0f, "float"),
            f(18, 0f, "float"),
            f(19, 0f, "float"),
            f(20, 0f, "float"),
            f(21, 0f, "float"),
            f(22, "16"),
            f(23, 41, "sint"),
            f(24, 36, "sint"),
            f(25, processStarted, "sint"),
            f(26, processStarted, "sint"),
            f(27, processStarted, "sint"),
            f(28, processStarted, "sint"),
            f(29, -1, "sint"),
            f(30, "25053RT47C"),
            f(31, "Redmi"),
            f(32, "25053RT47C"),
            f(33, "25053RT47C"),
            f(34, "Xiaomi"),
            f(35, "Redmi"),
            f(36, "Redmi"),
            f(38, 31, "sint"),
        )

    private fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val encoded = IntArray(data.size)
        for (i in data.indices) {
            val at = (i * 4) and 28
            val d0 = key[at].u()
            val d1 = key[at + 1].u()
            val v = data[i].u()
            var d2 = (((v shl 4) or (v ushr 4)) and 255) + d0
            d2 = d2.inv() xor d1
            d2 = (((d2 and 255) shl 3) or ((d2 and 255) ushr 5)) and 255
            d2 = (d2 + d1) and 255
            d2 = (d2 xor d0) and 255
            encoded[data.size - i - 1] = d2.inv() and 255
        }
        val last = encoded.last() xor encoded[encoded.size - 2]
        val first = encoded[0]
        encoded[0] = (last.inv() + first) and 255
        encoded[1] = ((encoded[0] xor encoded.last() xor 254) + encoded[1]) and 255
        encoded[2] =
            (encoded[2] +
                ((last - first) xor
                    (((encoded[1] shl 3) or (encoded[1] ushr 5)) and 255) xor
                    2)) and 255
        for (i in 0 until encoded.size - 4) {
            val temp =
                (((encoded[i + 2] shl 3) or (encoded[i + 2] ushr 5)) and 255) xor
                    encoded[i + 1] xor
                    (i + 3)
            encoded[i + 3] = (temp.inv() + encoded[i + 3]) and 255
        }
        encoded[encoded.size - 1] = encoded.last() xor encoded[encoded.size - 2]
        encoded[0] = ((encoded[0] xor encoded[1]) + encoded.drop(1).sum()) and 255
        return encoded.map { it.toByte() }.toByteArray()
    }

    fun medusa(url: String, body: ByteArray?, ts: Int, identity: Map<String, String>? = null): String {
        if (mobileV5) return medusaV5(url, body, ts, identity ?: device)
        val who = identity ?: device
        val bodyMd5 = body?.let { digest("MD5", it) } ?: ByteArray(16)
        val query = url.substringAfter('?').toByteArray()
        val querySm3 = sm3(query)
        val queryBodyTs = hashF13(querySm3, bodyMd5, ts)
        val nested =
            proto(
                f(1, 111, "sint"),
                f(2, 10, "sint"),
                f(3, 694367, "sint"),
                f(5, 586952199, "sint"),
            )
        val messageRand = unsigned(rand())
        val envLaunch = (random() * 21).toInt() + 100
        val envPid = (random() * 2000).toInt() + 10001
        val version = who.getValue("version_name")
        val env =
            proto(
                f(1, envLaunch, "sint"),
                f(2, 146331399, "sint"),
                f(3, 146331396, "sint"),
                f(5, 7, "sint"),
                f(6, "v04.06.04.03-bugfix"),
                f(7, envPid, "sint"),
                f(12, deviceProto(who)),
                f(13, proto(f(1, clock() / 1000, "sint"), f(2, -2, "sint"), f(4, 200, "sint"))),
                f(14, version),
            )
        val queryHash = sm3(query + bodyMd5 + "none".toByteArray())
        val message =
            proto(
                f(1, hex("f7e85ffad7d7dc3bd62ac87057cf6118")),
                f(2, 3, "sint"),
                f(3, messageRand, "sint"),
                f(4, "8662"),
                f(5, who.getValue("device_id")),
                f(6, "1588093228"),
                f(7, version),
                f(8, "v04.06.04-ml-android"),
                f(9, 67503104, "sint"),
                f(10, hex("4001000000000000")),
                f(12, ts, "sint"),
                f(13, queryBodyTs),
                f(14, querySm3.copyOf(6)),
                f(15, nested),
                f(16, processNonce),
                f(17, ts, "sint"),
                f(19, queryHash),
                f(20, "none"),
                f(21, 312, "sint"),
                f(23, env),
                f(
                    24,
                    """{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}""",
                ),
            )
        val r = rand()
        val signKey = hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
        val hash = sm3(signKey + le32(r) + signKey)
        val d1 = (r shr 16) and 255
        val seed = le32((((d1 shl 11) or (r ushr 24)) xor (d1 shr 5) xor d1).inv())
        val transformed = (hex("4001000000000000") + xmxor(message, hash)).reversedArray()
        for (i in transformed.indices) transformed[i] =
            (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val check =
            ((querySm3[0].u() and 63) shl 14) or 0x18000001 or ((queryBodyTs[0].u() and 63) shl 8)
        val packed =
            byteArrayOf(0x35) +
                le32(rand()) +
                le32(check) +
                transformed +
                byteArrayOf((r shr 16).toByte(), (r shr 24).toByte())
        val encrypted =
            AesV3(hex("f1593376766ea98d34f31b057a9d5be4"), ts)
                .encrypt(packed, hex("1fe109a4125283f418de9e051a969e12"))
        val versionBytes = hex("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        val prefix =
            (0 until 20 step 4).fold(byteArrayOf()) { a, i ->
                a + le32(versionBytes.i32(i, true) xor ts)
            }
        return (prefix + byteArrayOf(r.toByte(), (r shr 8).toByte(), 0, 1) + encrypted).base64()
    }

    private fun medusaV5(url: String, body: ByteArray?, ts: Int, who: Map<String, String>): String {
        val query = sm3(url.substringAfter('?').toByteArray(Charsets.UTF_8))
        val f13 = hashF13(query, body?.let { digest("MD5", it) } ?: ByteArray(16), ts)
        val flags = hex("4480000000000000")
        val version = who.getValue("version_name")
        // 补齐设备信封中截至字段 38 的 36 个已观测字段。
        // 这组字段在实测中恢复了空响应；尚未逐项证明每个字段都必需。
        val brand = who["device_brand"]?.takeIf { it.isNotEmpty() } ?: "HONOR"
        val type = who.getValue("device_type")
        val envLaunch = (random() * 21).toInt() + 100
        val envPid = (random() * 2000).toInt() + 10001
        val deviceInfo = proto(
            f(1, 1, "sint"), f(2, 2, "sint"), f(3, "8662"), f(4, who.getValue("device_id")),
            f(5, deviceToken), f(6, "!noperm!"),
            f(7, -888888, "sint"), f(8, -888888, "sint"), f(9, 3, "sint"), f(10, -888888, "sint"),
            f(11, "!notset!"), f(12, "Asia/Shanghai,8"), f(13, "zh_CN"), f(14, 4, "sint"),
            f(16, 0f, "float"), f(17, 0f, "float"), f(18, 0f, "float"),
            f(19, 0f, "float"), f(20, 0f, "float"), f(21, 0f, "float"),
            f(22, who.getValue("os_version")), f(23, 41, "sint"), f(24, 36, "sint"),
            f(25, processStarted, "sint"), f(26, processStarted, "sint"),
            f(27, processStarted, "sint"), f(28, clock(), "sint"),
            f(29, -1, "sint"), f(30, type), f(31, brand), f(32, type), f(33, type),
            f(34, brand), f(35, brand), f(36, brand), f(38, 31, "sint"),
        )
        val env = proto(
            f(1, envLaunch, "sint"), f(2, 247235599, "sint"), f(3, 247235596, "sint"),
            f(5, 7, "sint"), f(6, "v04.10.03.04-bugfix"), f(7, envPid, "sint"),
            f(8, who.getValue("channel")), f(12, deviceInfo),
            f(13, proto(f(1, ts, "sint"), f(2, -2, "sint"), f(4, 200, "sint"))),
            f(14, version),
        )
        val message = proto(
            f(1, V5Constants.magic), f(2, 5, "sint"), f(3, unsigned(rand()), "sint"),
            f(4, "8662"), f(5, who.getValue("device_id")), f(6, V5Constants.license),
            f(7, version), f(8, "v04.10.03-ml-android"), f(9, 0x040a0300, "sint"),
            f(10, flags), f(12, ts, "sint"), f(13, f13), f(14, query.copyOf(6)),
            f(15, proto(f(1, 305, "sint"), f(2, 4, "sint"), f(3, 694367, "sint"),
                f(5, 1621072674, "sint"))),
            f(16, deviceToken),
            f(17, ts, "sint"), f(20, "none"), f(21, V5Constants.field21, "sint"), f(23, env),
            f(24, """{"cmr":16777216,"cmr2":16777216,"un_h":11,"vpn":0,"kd":0,"dyn":"","do":1,"tk":true}"""),
        )
        val nonce = rand()
        val key = V5Constants.signKey
        val hash = sm3(key + le32(nonce) + key)
        val high = (nonce ushr 16) and 255
        val seed = le32((((high shl 11) or (nonce ushr 24)) xor (high ushr 5) xor high).inv())
        val transformed = (flags + xmxor(message, hash)).reversedArray()
        for (i in transformed.indices) transformed[i] =
            (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val check = 0x18000001 or ((query[0].u() and 63) shl 14) or ((f13[0].u() and 63) shl 8)
        val packed = byteArrayOf(0xca.toByte()) + le32(rand()) + le32(check) + transformed +
            byteArrayOf((nonce ushr 16).toByte(), (nonce ushr 24).toByte())
        val encrypted = AesV3(digest("MD5", key.copyOfRange(0, 16)), ts, v5 = true)
            .encrypt(packed, digest("MD5", key.copyOfRange(16, 32)))
        val prefix = le32(5) + V5Constants.magic
        for (i in prefix.indices) prefix[i] = (prefix[i].u() xor le32(ts)[i and 3].u()).toByte()
        return (prefix + byteArrayOf(nonce.toByte(), (nonce ushr 8).toByte(), 0, 1) + encrypted).base64()
    }

    fun helios(ts: Int): String {
        val random = rand()
        val ascii = digest("MD5", le32(random) + "8662".toByteArray()).hex().toByteArray()
        val words = (0..3).map { ascii.i64(it * 8, true) }.toMutableList()
        val table = mutableListOf(words[0])
        var b0 = words.removeAt(0)
        var b8 = words.removeAt(0)
        for (i in 0 until 34) {
            var x8 = (java.lang.Long.rotateRight(b8, 8) + b0) xor i.toLong()
            words.add(x8)
            x8 = x8 xor java.lang.Long.rotateRight(b0, 61)
            table.add(x8)
            b0 = x8
            b8 = words.removeAt(0)
        }
        val license = if (mobileV5) V5Constants.license else "1588093228"
        val text = "$ts-$license-8662".toByteArray()
        val padding = 16 - text.size % 16
        val data = text + ByteArray(padding) { padding.toByte() }
        var output = le32(random)
        for (at in data.indices step 16) {
            var a = data.i64(at, true)
            var b = data.i64(at + 8, true)
            for (i in 0 until 34) {
                b = table[i] xor (a + java.lang.Long.rotateRight(b, 8))
                a = b xor java.lang.Long.rotateRight(a, 61)
            }
            output += le64(a) + le64(b)
        }
        return output.base64()
    }

    /**
     * 生成带完整签名头的请求。
     *
     * `identity` 是播放接口的注册设备身份：除进入 query 外，还必须写进 medusa 明文
     * （服务端核对两者一致性）。`playback` 切换官方播放器的头集合：不携带
     * `x-tt-request-tag` / `passport-sdk-version` / `x-ss-stub`，改带
     * `x-tt-store-region(-src)`（对齐 jackotom/hongguo-drama 的 playbackRequest）。
     *
     * 注意：该形态 2026-11-05 实测可通过播放接口，但 2026-11-06 `--signmatrix` 复测
     * 0/12 被拒（补 version_code 73932 也不恢复）；当日对照实测服务端要求完整官方
     * 形态（Cookie 与 x-ss-stub 缺一即拒）。此形态仅作头形态基线保留，勿用于线上。
     */
    fun sign(
        base: String,
        values: Map<String, String>,
        body: ByteArray? = null,
        identity: Map<String, String>? = null,
        playback: Boolean = false,
    ): Request {
        val now = clock()
        val ts = (now / 1000).toInt()
        var ticket = now
        var query: String
        for (offset in 0..127) {
            ticket = now + offset
            query = encode(values + mapOf("ts" to ts.toString(), "_rticket" to ticket.toString()))
            if (branch(query, body, ts) == 0) {
                val url = "$base?$query"
                val random = (random() * 65536).toInt()
                val headers =
                    linkedMapOf(
                        "User-Agent" to
                            when {
                                playback -> VendorConstants.PLAYER_UA
                                body != null -> VendorConstants.VIDEO_UA
                                else -> VendorConstants.APP_UA
                            },
                        "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                        "x-ss-req-ticket" to ticket.toString(),
                    )
                if (!playback) {
                    headers["x-tt-request-tag"] = "t=0;n=0"
                    headers["passport-sdk-version"] = "50561"
                }
                headers["sdk-version"] = "2"
                headers["x-xs-from-web"] = "0"
                headers["x-khronos"] = ts.toString()
                headers["x-ladon"] = be32(ts).base64()
                headers["x-argus"] = le32(ts).base64()
                headers["x-gorgon"] = gorgon(query, body, ts, random)
                headers["x-helios"] = helios(ts)
                headers["x-medusa"] = medusa(url, body, ts, identity)
                headers["x-tt-dt"] = ""
                if (playback) {
                    headers["x-tt-store-region"] = "cn"
                    headers["x-tt-store-region-src"] = "did"
                }
                if (body != null) {
                    headers["Content-Type"] = "application/json; charset=UTF-8"
                    if (!playback) {
                        headers["x-vc-bdturing-sdk-version"] = "3.7.2.cn"
                        headers["x-ss-stub"] = digest("MD5", body).hex().uppercase()
                    }
                }
                return Request(url, headers, body)
            }
        }
        error("无法生成请求签名")
    }

    /** Playback uses the registered profile, no legacy ts query parameter, and branch 0.
     * Keep the existing branch-0 signing algorithm for deterministic compatibility.
     */
    fun signPlayback(
        base: String,
        values: Map<String, String>,
        body: ByteArray? = null,
        now: Long = clock(),
    ): Request {
        val ts = (now / 1000).toInt()
        for (offset in 0..1023) {
            val ticket = now + offset
            val query = encode(values + ("_rticket" to ticket.toString()))
            if (branch(query, body, ts) != 0) continue
            val url = "$base?$query"
            val headers = linkedMapOf(
                "User-Agent" to VendorConstants.PLAYER_UA,
                "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                "x-ss-req-ticket" to ticket.toString(), "sdk-version" to "2",
                "x-xs-from-web" to "0", "x-khronos" to ts.toString(),
                "x-ladon" to be32(ts).base64(), "x-argus" to le32(ts).base64(),
                "x-gorgon" to gorgon(query, body, ts, (random() * 65536).toInt()),
                "x-helios" to helios(ts), "x-medusa" to medusa(url, body, ts, if (mobileV5) values else null),
            )
            if (mobileV5) headers.keys.retainAll(setOf("x-khronos", "x-helios", "x-medusa"))
            if (body != null) {
                headers["Content-Type"] = "application/json; charset=utf-8"
                headers["x-ss-stub"] = digest("MD5", body).hex().uppercase()
            }
            return Request(url, headers, body)
        }
        error("无法生成播放签名")
    }

    companion object {
        fun encode(values: Map<String, String>) =
            values.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

        fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")
    }
}
