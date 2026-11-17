// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

internal fun readCommentBytes(input: java.io.InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= limit) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
