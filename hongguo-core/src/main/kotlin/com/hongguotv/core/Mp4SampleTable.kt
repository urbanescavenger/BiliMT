// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 解密后 MP4 header（ftyp+moov）的诊断/软解采样表。纯 ISO BMFF 父子层级遍历（不做全文件扫描），
 * 只支持 progressive MP4（非 fragmented）。
 *
 * 输入是 `RemoteVideo.read` 返回的解密字节（头区域内 MediaCrypto 已将 encv 还原为原始编码
 * 标签并中和 sinf），因此这里无需感知 CENC。ctts 存在时计算每样本的展示时间戳（B 帧
 * 重排），缺失时 cts 取 dts。
 */
object Mp4SampleTable {
    /** 时间戳一律微秒（解析时按 mdhd timescale 换算），供软解与播放器时钟直接比较。 */
    data class Sample(val offset: Long, val size: Int, val dts: Long, val cts: Long)

    class Table(
        val fourcc: String,
        val width: Int,
        val height: Int,
        val timescale: Long,
        val samples: List<Sample>,
        /** 随机访问点样本下标（0 起，升序）；stss 缺失时为空表（调用方自行退化）。 */
        val syncSamples: List<Int>,
    )

    fun parse(header: ByteArray): Table? {
        val reader = Reader(header)
        var found: Box? = null
        while (true) {
            val box = reader.next() ?: break
            if (box.type == "moov") {
                found = box
                break
            }
        }
        val moov = found ?: return null
        val moovChild = Reader(header, moov.bodyStart, moov.end)
        while (true) {
            val trak = moovChild.next() ?: break
            if (trak.type != "trak") continue
            parseTrak(header, trak)?.let { return it }
        }
        return null
    }

    private class Box(val type: String, val bodyStart: Int, val end: Int)

    private fun parseTrak(data: ByteArray, trak: Box): Table? {
        val mdia = firstChild(data, trak, "mdia") ?: return null
        val hdlr = firstChild(data, mdia, "hdlr") ?: return null
        // hdlr body: version/flags(4) + pre_defined(4) + handler_type(4)
        if (hdlr.bodyStart + 12 > hdlr.end) return null
        if (str(data, hdlr.bodyStart + 8) != "vide") return null
        // mdhd：version/flags(4) + creation/modification(4 或 8 ×2) + timescale(4)。
        val mdhd = firstChild(data, mdia, "mdhd") ?: return null
        if (mdhd.bodyStart + 16 > mdhd.end) return null
        val mdhdVersion = data[mdhd.bodyStart].toInt() and 255
        val timescale =
            if (mdhdVersion == 1) data.u32(mdhd.bodyStart + 20) else data.u32(mdhd.bodyStart + 12)
        if (timescale <= 0) return null
        val minf = firstChild(data, mdia, "minf") ?: return null
        val stbl = firstChild(data, minf, "stbl") ?: return null
        val stsd = firstChild(data, stbl, "stsd") ?: return null
        val stsz = firstChild(data, stbl, "stsz") ?: return null
        val stsc = requiredChild(data, stbl, "stsc") ?: return null
        val stco = firstChild(data, stbl, "stco")
        val co64 = firstChild(data, stbl, "co64")
        val chunks = stco ?: co64 ?: return null
        val stts = firstChild(data, stbl, "stts")
        val ctts = firstChild(data, stbl, "ctts")
        val stss = firstChild(data, stbl, "stss")
        if (stsd.bodyStart + 16 > stsd.end) return null
        val fourcc = str(data, stsd.bodyStart + 12)
        // VisualSampleEntry：entry 头 8 字节（reserved 6 + data_reference_index 2）后
        // pre_defined(2)+reserved(2)+pre_defined(12)，width/height 在 entry+24/26；字节家
        // muxer 的真实流可能写 0（真实尺寸在元数据/SPS），这里仅作展示参考。
        val width = data.u16(stsd.bodyStart + 8 + 24)
        val height = data.u16(stsd.bodyStart + 8 + 26)

        // stsz：version/flags(4) + sample_size(4) + sample_count(4) [+ sizes]
        val defaultSize = data.u32(stsz.bodyStart + 4)
        val count = data.u32(stsz.bodyStart + 8).toInt()
        if (count <= 0 || count > 500_000) return null
        val sizes =
            IntArray(count) {
                if (defaultSize != 0L) defaultSize.toInt()
                else {
                    val at = stsz.bodyStart + 12 + it * 4
                    if (at + 4 > stsz.end) return null
                    data.u32(at).toInt()
                }
            }

        // stsc：version/flags(4) + entry_count(4) + entries(first_chunk, samples_per_chunk, id)
        val stscCount = data.u32(stsc.bodyStart + 4).toInt()
        if (stscCount <= 0) return null
        val runs =
            Array(stscCount) { run ->
                val at = stsc.bodyStart + 8 + run * 12
                if (at + 12 > stsc.end) return null
                data.u32(at).toInt() to data.u32(at + 4).toInt()
            }

        // stco/co64：version/flags(4) + entry_count(4) + offsets
        val chunkCount = data.u32(chunks.bodyStart + 4).toInt()
        if (chunkCount <= 0 || chunkCount > 500_000) return null
        val width64 = chunks.type == "co64"
        val chunkOffsets =
            LongArray(chunkCount) { chunk ->
                val at = chunks.bodyStart + 8 + chunk * (if (width64) 8 else 4)
                if (at + (if (width64) 8 else 4) > chunks.end) return null
                if (width64) data.i64(at) else data.u32(at).toLong()
            }

        // stts：version/flags(4) + entry_count(4) + entries(count, delta)
        val deltas =
            if (stts == null) {
                null
            } else {
                val sttsCount = data.u32(stts.bodyStart + 4).toInt()
                if (sttsCount <= 0) return null
                List(sttsCount) { run ->
                    val at = stts.bodyStart + 8 + run * 8
                    if (at + 8 > stts.end) return null
                    data.u32(at).toInt() to data.u32(at + 4).toLong()
                }
            }

        // ctts：version/flags(4) + entry_count(4) + entries(sample_count, offset 有符号)。
        val ctsOffsets =
            if (ctts == null) {
                null
            } else {
                val cttsCount = data.u32(ctts.bodyStart + 4).toInt()
                if (cttsCount <= 0) return null
                List(cttsCount) { run ->
                    val at = ctts.bodyStart + 8 + run * 8
                    if (at + 8 > ctts.end) return null
                    data.u32(at).toInt() to data.i32(at + 4).toLong()
                }
            }

        // stss：version/flags(4) + entry_count(4) + 同步样本号（1 起）。缺失 = 全部为同步样本。
        val syncSamples =
            if (stss == null) {
                emptyList()
            } else {
                val stssCount = data.u32(stss.bodyStart + 4).toInt()
                if (stssCount <= 0 || stssCount > 500_000) return null
                List(stssCount) { run ->
                    val at = stss.bodyStart + 8 + run * 4
                    if (at + 4 > stss.end) return null
                    data.u32(at).toInt() - 1
                }
            }

        // 逐 chunk 展开：stsc 运行长度映射每 chunk 样本数；dts/cts 按 timescale 换算为微秒。
        val toUs = { value: Long -> value * 1_000_000L / timescale }
        val samples = ArrayList<Sample>(count)
        var sampleIndex = 0
        var run = 0
        var dts = 0L
        var dtsRun = 0
        var dtsRemaining = deltas?.getOrNull(0)?.first ?: 0
        var dtsDelta = deltas?.getOrNull(0)?.second ?: 0
        var ctsRun = 0
        var ctsRemaining = ctsOffsets?.getOrNull(0)?.first ?: 0
        var ctsRunOffset = ctsOffsets?.getOrNull(0)?.second ?: 0
        for (chunk in chunkOffsets.indices) {
            while (run + 1 < runs.size && runs[run + 1].first <= chunk + 1) run++
            val perChunk = runs[run].second
            var position = chunkOffsets[chunk]
            repeat(perChunk) {
                if (sampleIndex >= count) return null
                val size = sizes[sampleIndex]
                if (size <= 0 || position < 0) return null
                if (ctsOffsets != null) {
                    if (ctsRemaining == 0 && ctsRun + 1 < ctsOffsets.size) {
                        ctsRun++
                        ctsRemaining = ctsOffsets[ctsRun].first
                        ctsRunOffset = ctsOffsets[ctsRun].second
                    }
                    if (ctsRemaining > 0) ctsRemaining--
                }
                samples += Sample(position, size, toUs(dts), toUs(dts + ctsRunOffset))
                position += size
                dts += dtsDelta
                if (deltas != null && dtsRemaining > 0 && --dtsRemaining == 0) {
                    if (++dtsRun < deltas.size) {
                        dtsRemaining = deltas[dtsRun].first
                        dtsDelta = deltas[dtsRun].second
                    } else {
                        dtsRemaining = Int.MAX_VALUE
                        dtsDelta = 0
                    }
                }
                sampleIndex++
            }
        }
        if (sampleIndex != count) return null
        return Table(fourcc, width, height, timescale, samples, syncSamples)
    }

    private fun firstChild(data: ByteArray, parent: Box, type: String): Box? {
        val child = Reader(data, parent.bodyStart, parent.end)
        while (true) {
            val box = child.next() ?: return null
            if (box.type == type) return box
        }
    }

    private fun requiredChild(data: ByteArray, parent: Box, type: String): Box? = firstChild(data, parent, type)

    /** 顺序读取同层 box；只支持 8 字节头即可覆盖的常规布局（largesize 也兼容）。 */
    private class Reader(private val data: ByteArray, private var pos: Int, private val end: Int) {
        constructor(data: ByteArray) : this(data, 0, data.size)

        fun next(): Box? {
            if (pos + 8 > end) return null
            var size = data.u32(pos).toLong()
            var header = 8
            if (size == 1L) {
                if (pos + 16 > end) return null
                size = data.i64(pos + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || pos + size > end) return null
            val box = Box(str(data, pos + 4), pos + header, pos + size.toInt())
            pos += size.toInt()
            return box
        }
    }

    private fun str(data: ByteArray, at: Int): String =
        String(data, at, 4, Charsets.US_ASCII)

    private fun ByteArray.u32(at: Int): Long =
        ((this[at].toLong() and 255) shl 24) or ((this[at + 1].toLong() and 255) shl 16) or
            ((this[at + 2].toLong() and 255) shl 8) or (this[at + 3].toLong() and 255)

    private fun ByteArray.u16(at: Int): Int =
        ((this[at].toInt() and 255) shl 8) or (this[at + 1].toInt() and 255)

    private fun ByteArray.i32(at: Int): Long {
        val raw = u32(at)
        return if (raw >= 0x80000000L) raw - (1L shl 32) else raw
    }

    private fun ByteArray.i64(at: Int): Long =
        (u32(at) shl 32) or u32(at + 4)
}
