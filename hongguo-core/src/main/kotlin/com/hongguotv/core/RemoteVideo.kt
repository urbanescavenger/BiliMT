// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 远程视频资源处理：管理播放数据读取与资源关闭。

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, info: StreamInfo) : Closeable {
    @Volatile var info = info
        private set
    @Volatile private var closed = false
    private val calls = mutableSetOf<Call>()

    data class Range(val bytes: ByteArray, val total: Long)

    /** A prepared stream can move to a new player without inheriting the old player's HTTP calls. */
    class PreparedSource internal constructor(
        private val info: StreamInfo,
        private val size: Long,
        private val prepared: MediaCrypto.Prepared,
        private val prefix: ByteArray,
    ) {
        fun open(http: OkHttpClient): RemoteVideo = RemoteVideo(http, info).also { next ->
            next.total = size
            next.prefix = prefix
            next.prepared = prepared
        }
    }

    fun preparedSource(): PreparedSource? = synchronized(calls) {
        if (closed || !::prepared.isInitialized) null
        else PreparedSource(info, total, prepared, prefix)
    }

    var total: Long = 0
        private set

    @Volatile private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix = byteArrayOf()

    private class RangeHttpFailure(code: Int, val retryable: Boolean) :
        IOException("视频服务器不支持分段读取（HTTP $code）")

    private fun newCall(url: String, start: Long, end: Long): Call {
        if (closed) throw IOException("播放请求已取消")
        require(start >= 0 && end >= start && end - start < 8 * 1024 * 1024)
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", VendorConstants.VIDEO_UA)
                .header("Referer", "https://novel.snssdk.com/")
                .header("Range", "bytes=$start-$end")
                .header("Accept-Encoding", "identity")
                .build()
        val call = http.newCall(request)
        synchronized(calls) {
            if (closed) throw IOException("播放请求已取消")
            calls += call
        }
        return call
    }

    private fun rangeSize(response: Response, start: Long, end: Long, expectedTotal: Long): Pair<Int, Long> {
        if (response.code != 206) {
            val retryable = response.code in 500..599 && response.header("Retry-After").isNullOrBlank()
            throw RangeHttpFailure(response.code, retryable)
        }
        val match =
            Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                .matchEntire(response.header("Content-Range").orEmpty())
                ?: throw IOException("视频分段响应异常")
        val (begin, finish, size) = match.destructured
        val full = size.toLong()
        val last = minOf(end, full - 1)
        if (begin.toLong() != start || finish.toLong() != last || full <= start)
            throw IOException("视频分段范围不匹配")
        if (expectedTotal != 0L && full != expectedTotal) throw IOException("视频资源已变化，请重试")
        return (last - start + 1).toInt() to full
    }

    private fun range(url: String, start: Long, end: Long, expectedTotal: Long = total): Range {
        val call = newCall(url, start, end)
        try {
            return RequestScope.execute(call) { response ->
                val (n, full) = rangeSize(response, start, end, expectedTotal)
                val body = response.body ?: throw IOException("视频分段为空")
                val bytes = body.source().readByteArray(n.toLong())
                Range(bytes, full)
            }
        } finally {
            synchronized(calls) { calls -= call }
        }
    }

    /**
     * Consume the index progressively within one request. Avoid a second round trip when moov ends
     * after the initial probe but inside the existing 256 KiB range.
     */
    private fun indexPrefix(url: String, initialBytes: Int): Range {
        val call = newCall(url, 0, 256 * 1024 - 1)
        try {
            return RequestScope.execute(call) { response ->
                val (size, full) = rangeSize(response, 0, 256 * 1024 - 1, total)
                val source = (response.body ?: throw IOException("视频分段为空")).source()
                val first = source.readByteArray(minOf(initialBytes, size).toLong())
                val needed = minOf(MediaCrypto.headerEnd(first, full), size)
                val bytes =
                    if (needed > first.size)
                        first + source.readByteArray((needed - first.size).toLong())
                    else first
                Range(bytes, full)
            }
        } finally {
            synchronized(calls) { calls -= call }
        }
    }

    private fun <T> withCdnFallback(open: (String) -> T): T {
        val url = info.url
        return try {
            open(url)
        } catch (failure: IOException) {
            tryBackup(url, failure, open)
        }
    }

    private fun <T> tryBackup(failedUrl: String, failure: IOException, open: (String) -> T): T {
        val retryable = when (failure) {
            is RangeHttpFailure -> failure.retryable
            is SocketTimeoutException -> true
            is InterruptedIOException -> failure.message == "timeout" || failure.message == "deadline reached"
            else -> false
        }
        if (!retryable) throw failure
        return synchronized(this) {
            RequestScope.ensureActive()
            if (closed) throw failure
            val current = info
            if (current.url != failedUrl) return@synchronized open(current.url)
            val backup = current.backupUrl?.takeUnless { it == current.url } ?: throw failure
            // Consume the one backup attempt even if verification or the new range fails.
            info = current.copy(backupUrl = null)
            try {
                if (::prepared.isInitialized) verifyBackupIndex(backup)
                open(backup).also { info = info.copy(url = backup) }
            } catch (error: IOException) {
                if (error !== failure) error.addSuppressed(failure)
                throw error
            }
        }
    }

    private fun verifyBackupIndex(backup: String) {
        val size = prepared.header.size
        val original = prefix
        val index = RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(4)) {
            range(backup, 0, size - 1L).bytes
        }
        if (index.size != size || index.indices.any { index[it] != original[it] })
            throw IOException("备用视频资源的索引与当前集不一致")
    }

    /**
     * One bounded HTTP range, delivered as bytes arrive instead of waiting for the whole body. The
     * playback data source owns this reader; close() interrupts an outstanding body read.
     */
    fun openRead(start: Long, length: Int): InputStream {
        check(::prepared.isInitialized)
        require(start in 0 until total && length in 1..1024 * 1024)
        val end = minOf(total - 1, start + length - 1)
        val initial = withCdnFallback { url -> openRange(url, start, end) }
        return object : InputStream() {
            private var range = initial
            private var position = start
            private var remaining = (end - start + 1).toInt()
            private var readerClosed = false
            private val scratch = ByteArray(32 * 1024)

            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
            }

            override fun read(target: ByteArray, offset: Int, count: Int): Int {
                require(offset >= 0 && count >= 0 && offset <= target.size - count)
                if (count == 0) return 0
                if (readerClosed || closed) throw IOException("播放请求已取消")
                if (remaining == 0) return -1
                val limit = minOf(count, remaining, scratch.size)
                val n = try {
                    range.read(scratch, limit)
                } catch (failure: IOException) {
                    val interruptedUrl = range.url
                    range.close()
                    range = tryBackup(interruptedUrl, failure) { url -> openRange(url, position, end) }
                    range.read(scratch, limit)
                }
                MediaCrypto.decrypt(scratch.copyOf(n), position, info.key, prepared)
                    .copyInto(target, offset)
                position += n
                remaining -= n
                return n
            }

            override fun close() {
                if (readerClosed) return
                readerClosed = true
                range.close()
            }
        }
    }

    private fun openRange(url: String, start: Long, end: Long): OpenRange {
        val call = newCall(url, start, end)
        // The player may stop consuming a range while paused or sufficiently buffered.
        // A wall-clock call timeout would cancel healthy streams during that idle time.
        // Keep the client's connect/read timeouts and explicit close() cancellation.
        call.timeout().clearTimeout()
        var response: Response? = null
        try {
            val opened = call.execute()
            response = opened
            val (size, _) = rangeSize(opened, start, end, total)
            return OpenRange(url, call, opened, size)
        } catch (error: Exception) {
            call.cancel()
            response?.close()
            synchronized(calls) { calls -= call }
            throw error
        }
    }

    private inner class OpenRange(
        val url: String,
        private val call: Call,
        private val response: Response,
        private var remaining: Int,
    ) : Closeable {
        private val input = (response.body ?: throw IOException("视频分段为空")).byteStream()
        private var released = false

        fun read(target: ByteArray, count: Int): Int {
            if (released) throw IOException("播放请求已取消")
            val read = input.read(target, 0, count)
            if (read < 0) throw java.io.EOFException("视频分段意外结束")
            remaining -= read
            return read
        }

        override fun close() {
            if (released) return
            released = true
            if (remaining > 0) call.cancel()
            try {
                response.close()
            } finally {
                synchronized(calls) { calls -= call }
            }
        }
    }

    private fun readIndex(url: String, initialBytes: Int): Pair<Range, ByteArray> {
        val first =
            if (initialBytes < 256 * 1024) indexPrefix(url, initialBytes)
            else range(url, 0, 256 * 1024 - 1)
        val end = MediaCrypto.headerEnd(first.bytes, first.total)
        val header =
            if (end > first.bytes.size)
                first.bytes + range(url, first.bytes.size.toLong(), end - 1L, first.total).bytes
            else first.bytes.copyOf(end)
        return first to header
    }

    @JvmOverloads
    @Synchronized
    fun prepare(initialBytes: Int = 256 * 1024): RemoteVideo {
        require(initialBytes in 16 * 1024..256 * 1024)
        if (::prepared.isInitialized) return this
        val (first, header) = withCdnFallback { url ->
            if (info.backupUrl == null) readIndex(url, initialBytes)
            else RequestScope.withDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(4)) {
                readIndex(url, initialBytes)
            }
        }
        val layout = MediaCrypto.prepare(header, info.key)
        if (layout.samples.any { it.end > first.total }) throw IOException("视频样本超出资源大小")
        total = first.total
        prefix = if (header.size > first.bytes.size) header else first.bytes
        prepared = layout
        return this
    }

    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized
    fun warm(bytes: Int = 1024 * 1024): RemoteVideo {
        require(bytes in 256 * 1024..4 * 1024 * 1024)
        prepare()
        val size = minOf(total, bytes.toLong()).toInt()
        if (prefix.size < size) {
            val next = withCdnFallback { url -> range(url, prefix.size.toLong(), size - 1L).bytes }
            prefix = prefix + next
        }
        return this
    }

    /** Bytes already held locally; a player can consume these before another network range. */
    fun cachedBytes(start: Long): Int =
        (prefix.size.toLong() - start).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    fun read(start: Long, length: Int): ByteArray {
        require(length in 1..1024 * 1024 && start >= 0)
        if (closed) throw IOException("播放请求已取消")
        if (start >= total) return byteArrayOf()
        val cached = prefix
        val end = minOf(total, start + length)
        val available = (minOf(end, cached.size.toLong()) - start).coerceAtLeast(0).toInt()
        val first =
            if (available > 0) cached.copyOfRange(start.toInt(), start.toInt() + available)
            else byteArrayOf()
        val raw =
            if (start + available < end)
                first + withCdnFallback { url -> range(url, start + available, end - 1).bytes }
            else first
        return MediaCrypto.decrypt(raw, start, info.key, prepared)
    }

    /**
     * Coded video dimensions after prepare(); null before prepare or when the header has no
     * readable video track.
     */
    fun trackInfo(): VideoTracks.Track? {
        if (!::prepared.isInitialized) return null
        return VideoTracks.videoTrack(prepared.header)
    }

    override fun close() {
        synchronized(calls) {
            closed = true
            calls.toList().forEach { it.cancel() }
            calls.clear()
        }
    }
}
