// SPDX-License-Identifier: GPL-3.0-only
// Argus/Simon structure adapted from tr4cex/TikTok-Encryption (MIT; see THIRD_PARTY.md).
// Gorgon and Ladon use the existing GPL core Signer algorithms with the comment protocol
// parameters.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SM3Digest
import org.bouncycastle.util.encoders.Base64

class CommentSignature {
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b)

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hexString() = joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun le(n: Long) =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()

    private fun word(b: ByteArray, at: Int) =
        ByteBuffer.wrap(b, at, 8).order(ByteOrder.LITTLE_ENDIAN).long

    private fun pad(b: ByteArray) = b + ByteArray(16 - b.size % 16) { (16 - b.size % 16).toByte() }

    private fun sm3(b: ByteArray) =
        SM3Digest().run {
            update(b, 0, b.size)
            ByteArray(32).also { doFinal(it, 0) }
        }

    private fun proto(fields: Map<Int, Any>): ByteArray =
        ByteArrayOutputStream().run {
            fun variable(value: Long) {
                var n = value
                while (n ushr 7 != 0L) {
                    write((n.toInt() and 127) or 128)
                    n = n ushr 7
                }
                write(n.toInt())
            }
            for ((tag, value) in fields) {
                if (value is Number) {
                    variable((tag shl 3).toLong())
                    variable(value.toLong())
                } else {
                    val bytes = if (value is ByteArray) value else value.toString().toByteArray()
                    variable(((tag shl 3) or 2).toLong())
                    variable(bytes.size.toLong())
                    write(bytes)
                }
            }
            toByteArray()
        }

    fun headers(
        query: String,
        now: Long = System.currentTimeMillis(),
        nonce: ByteArray = ByteArray(11).also { SecureRandom().nextBytes(it) },
    ): Map<String, String> {
        require(nonce.size == 11)
        val ts = now / 1000
        val first = ((nonce[0].toInt() and 255) % 7 + 1) shl 5
        val second = 0xe0 or (nonce[1].toInt() and 15)
        val input =
            md5(query.toByteArray()).copyOf(4) +
                ByteArray(8) +
                hex("00010704") +
                ByteBuffer.allocate(4).putInt(ts.toInt()).array()
        val key = intArrayOf(0x4a, 0, 0x16, second, 0x47, 0x6c, 0, first)
        val box = IntArray(256) { it }
        var j = 0
        for (i in box.indices) {
            j = (j + box[i] + key[i % 8]) and 255
            box[i] = box[j]
        }
        j = 0
        for (n in input.indices) {
            val i = n + 1
            j = (j + box[i]) and 255
            box[i] = box[j]
            input[n] = (input[n].toInt() xor box[(box[i] + box[j]) and 255]).toByte()
        }
        for (i in input.indices) {
            val v = input[i].toInt() and 255
            val swapped = ((v shl 4) or (v ushr 4)) and 255
            input[i] =
                ((Integer.reverse(swapped xor (input[(i + 1) % input.size].toInt() and 255)) ushr
                        24) xor 0xeb)
                    .toByte()
        }
        return mapOf(
            "X-Gorgon" to
                (byteArrayOf(0x84.toByte(), 4, first.toByte(), second.toByte(), 0, 0) + input)
                    .hexString(),
            "X-Khronos" to ts.toString(),
            "X-SS-Req-Ticket" to now.toString(),
            "X-SS-STUB" to "",
            "X-Argus" to argus(query, ts, nonce),
            "X-Ladon" to ladon(ts, nonce.copyOfRange(2, 6)),
        )
    }

    private fun ladon(ts: Long, salt: ByteArray): String {
        val ascii = md5(salt + "8662".toByteArray()).hexString().toByteArray()
        val queue = java.util.ArrayDeque<Long>()
        queue.add(word(ascii, 16))
        queue.add(word(ascii, 24))
        var left = word(ascii, 0)
        var right = word(ascii, 8)
        val keys = LongArray(34)
        for (i in keys.indices) {
            keys[i] = left
            val next = (java.lang.Long.rotateRight(right, 8) + left) xor i.toLong()
            queue.add(next)
            left = next xor java.lang.Long.rotateLeft(left, 3)
            right = queue.removeFirst()
        }
        val data = pad("$ts-1611921764-3019".toByteArray())
        val result = ByteArrayOutputStream()
        result.write(salt)
        for (at in data.indices step 16) {
            var a = word(data, at)
            var b = word(data, at + 8)
            for (k in keys) {
                b = k xor (a + java.lang.Long.rotateRight(b, 8))
                a = b xor java.lang.Long.rotateLeft(a, 3)
            }
            result.write(le(a))
            result.write(le(b))
        }
        return Base64.toBase64String(result.toByteArray())
    }

    private fun argus(query: String, ts: Long, nonce: ByteArray): String {
        val random =
            ByteBuffer.wrap(nonce, 6, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val nested =
            proto(
                linkedMapOf(
                    1 to (2 + (nonce[10].toInt() and 255) % 49 * 2),
                    2 to 1388734,
                    3 to 1388734,
                    4 to 1388734,
                )
            )
        val message =
            proto(
                linkedMapOf(
                    1 to 0x40401252,
                    2 to 2,
                    3 to random,
                    4 to "3019",
                    6 to "1611921764",
                    7 to "6.8.1.32",
                    8 to "v04.07.01-ml-android",
                    9 to 135135744,
                    10 to ByteArray(8),
                    12 to ts * 2,
                    13 to sm3(ByteArray(16)).copyOf(6),
                    14 to sm3(query.toByteArray()).copyOf(6),
                    15 to nested,
                    20 to "none",
                    21 to 738,
                )
            )
        val secret = hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
        val derived = sm3(secret + hex("3ccc567b") + secret)
        val keys = LongArray(72)
        for (i in 0..3) keys[i] = word(derived, i * 8)
        for (i in 4..71) {
            val x = java.lang.Long.rotateRight(keys[i - 1], 3) xor keys[i - 3]
            keys[i] =
                keys[i - 4].inv() xor
                    x xor
                    java.lang.Long.rotateRight(x, 1) xor
                    ((0x3dc94c3a046d678bL ushr ((i - 4) % 62)) and 1) xor
                    3
        }
        val blocks = pad(message)
        val encrypted = ByteArrayOutputStream()
        for (at in blocks.indices step 16) {
            var a = word(blocks, at)
            var b = word(blocks, at + 8)
            for (k in keys) {
                val next =
                    a xor
                        (java.lang.Long.rotateLeft(b, 1) and java.lang.Long.rotateLeft(b, 8)) xor
                        java.lang.Long.rotateLeft(b, 2) xor
                        k
                a = b
                b = next
            }
            encrypted.write(le(a))
            encrypted.write(le(b))
        }
        val mask = hex("d04ffdffd04ffdff")
        val scrambled =
            encrypted
                .toByteArray()
                .mapIndexed { i, b -> (b.toInt() xor mask[i % 8].toInt()).toByte() }
                .toByteArray()
        val container = hex("a6e783ee7001100918") + (mask + scrambled).reversedArray() + hex("567b")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(md5(secret.copyOfRange(0, 16)), "AES"),
            IvParameterSpec(md5(secret.copyOfRange(16, 32))),
        )
        return Base64.toBase64String(hex("3ccc") + cipher.doFinal(container))
    }
}
