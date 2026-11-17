// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Foreground-only LAN control. Two workers, two queued sockets, no retained response cache. */
class PhoneControlServer(address: String, private val request: (JSONObject) -> JSONObject) : Closeable {
    private val closed = AtomicBoolean()
    private val clients = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "hongguotv-phone-control").apply { isDaemon = true } })
    private val local = InetAddress.getByName(address).also {
        require(it.isLoopbackAddress || it.isSiteLocalAddress)
    }
    private val listener = ServerSocket(0, 4, local)
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host = "${local.hostAddress}:${listener.localPort}"
    val url = "http://$host/$token"
    private val acceptor = Thread({ serve() }, "hongguotv-phone-listener").apply { isDaemon = true; start() }

    private fun serve() {
        try {
            while (!closed.get()) {
                val socket = listener.accept()
                clients.add(socket)
                try {
                    workers.execute {
                        try { socket.use { if (!closed.get()) handle(it) } }
                        catch (_: Exception) { /* Disconnected, malformed or expired request. */ }
                        finally { clients.remove(socket) }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    clients.remove(socket)
                    socket.close()
                }
            }
        } catch (_: IOException) { /* close interrupts accept */ }
        finally { close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        workers.shutdownNow()
    }

    internal fun awaitClosed(): Boolean {
        acceptor.join(2000)
        return !acceptor.isAlive && workers.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun handle(socket: Socket) {
        if (!socket.inetAddress.let { it.isSiteLocalAddress || it.isLoopbackAddress }) return
        socket.soTimeout = 1500
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        val input = socket.getInputStream().buffered()
        fun read(): Int {
            if (closed.get() || System.nanoTime() >= deadline) throw IOException("Request expired")
            return input.read()
        }
        fun line(max: Int): String {
            val out = ByteArrayOutputStream()
            while (out.size() < max) {
                val next = read()
                if (next < 0) throw IOException("EOF")
                if (next == 10) return out.toString("US-ASCII").trimEnd('\r')
                out.write(next)
            }
            throw IOException("Header too long")
        }
        val first = line(1024).split(' ')
        if (first.size != 3 || first[2] != "HTTP/1.1") return
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val row = line(2048)
            total += row.length + 2
            if (total > 8192) return reply(socket, 431, "{}")
            if (row.isEmpty()) break
            val at = row.indexOf(':')
            if (at <= 0 || headers.put(row.substring(0, at).lowercase(Locale.ROOT), row.substring(at + 1).trim()) != null)
                return reply(socket, 400, "{}")
        }
        if (headers["host"] != host || first[1] !in listOf("/$token", "/$token/api"))
            return reply(socket, 404, "{}")
        if (headers["sec-fetch-site"] == "cross-site" ||
            (headers["origin"] != null && headers["origin"] != "http://$host"))
            return reply(socket, 403, "{}")
        if (first[0] == "GET" && first[1] == "/$token")
            return reply(socket, 200, PhoneControlPage.html(token), html = true)
        if (first[0] != "POST" || first[1] != "/$token/api") return reply(socket, 405, "{}")
        // Custom header + exact Origin prevent cross-site forms/fetch from issuing commands.
        if (headers["origin"] != "http://$host" || headers["x-control-request"] != "1")
            return reply(socket, 403, "{}")
        if (headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';') != "application/json")
            return reply(socket, 400, "{}")
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 2..4096 }
            ?: return reply(socket, 413, "{}")
        val bytes = ByteArray(length)
        for (i in bytes.indices) { val b = read(); if (b < 0) return; bytes[i] = b.toByte() }
        val body = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return reply(socket, 400, "{}")
        if (closed.get()) return
        val result = try { request(body) }
            catch (_: IllegalArgumentException) { JSONObject().put("error", "参数无效，请刷新页面重试") }
            catch (_: Exception) { JSONObject().put("error", "操作未完成，请稍后重试或检查电视状态") }
        val response = result.toString()
        if (response.toByteArray(Charsets.UTF_8).size > 256 * 1024)
            return reply(socket, 413, "{\"error\":\"结果过大，请缩小查询范围\"}")
        reply(socket, if (result.has("error")) 400 else 200, response)
    }

    private fun reply(socket: Socket, code: Int, text: String, html: Boolean = false) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $code Response\r\nContent-Type: ${if (html) "text/html" else "application/json"}; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; img-src data: https:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }
}
