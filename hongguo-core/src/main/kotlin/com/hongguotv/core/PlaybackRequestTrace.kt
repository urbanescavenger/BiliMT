// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.Protocol

/** 只记录一轮有界模型请求；不保留地址、请求头、身份或异常原文。 */
internal class PlaybackRequestTrace {
    enum class Stage { GET, POST, SINGLE, COMPAT_SHORT, COMPAT_MULTI }
    private val entries = mutableListOf<String>()

    fun response(stage: Stage, host: String, status: Int, bytes: Int?, protocol: Protocol) {
        val wire = if (protocol == Protocol.HTTP_2 || protocol == Protocol.H2_PRIOR_KNOWLEDGE) "h2" else "h1"
        add(stage, host, "$status/${bytes?.toString() ?: "?"}B/$wire")
    }

    fun failed(stage: Stage, host: String, error: IOException) {
        val kind = when (error) {
            is SocketTimeoutException -> "timeout"
            is UnknownHostException -> "dns"
            is java.io.InterruptedIOException ->
                if (error.message in setOf("timeout", "deadline reached")) "timeout" else "cancel"
            else -> "io"
        }
        add(stage, host, kind)
    }

    fun rejected(code: Int? = null) {
        if (entries.isNotEmpty()) entries[entries.lastIndex] += if (code == null) "/invalid" else "/c$code"
    }

    private fun add(stage: Stage, host: String, result: String) {
        if (entries.size >= 5) return
        val tag = if (host in PlayRetryPlan.hosts) PlayRetryPlan.tag(host) else "other"
        entries += "${when (stage) { Stage.GET -> "G"; Stage.POST -> "P"; Stage.SINGLE -> "S"; Stage.COMPAT_SHORT -> "CS"; Stage.COMPAT_MULTI -> "CM" }}:$tag=$result"
    }

    fun summary() = entries.joinToString(" ")
}
