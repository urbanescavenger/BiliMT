// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * Reads the coded video size from a decrypted MP4 header. MediaCodec checks the coded dimensions,
 * so a portrait 720P stream is 720x1280 even when displayed differently.
 */
object VideoTracks {
    data class Track(val fourcc: String, val width: Int, val height: Int)

    fun videoTrack(header: ByteArray): Track? {
        if (header.size < 32 || String(header, 4, 4, Charsets.US_ASCII) != "ftyp") return null
        val moov = MediaCrypto.box(header, header.u32(0).toInt()) ?: return null
        if (moov.type != "moov") return null
        var cursor = moov.body
        while (cursor < moov.end) {
            val trak = MediaCrypto.box(header, cursor, moov.end) ?: break
            cursor = trak.end
            if (trak.type != "trak") continue
            val mdia = MediaCrypto.find(header, "mdia", trak.body, trak.end) ?: continue
            val minf = MediaCrypto.find(header, "minf", mdia.body, mdia.end) ?: continue
            if (MediaCrypto.find(header, "vmhd", minf.body, minf.end) == null)
                continue // audio track
            val stbl = MediaCrypto.find(header, "stbl", minf.body, minf.end) ?: continue
            val stsd = MediaCrypto.find(header, "stsd", stbl.body, stbl.end) ?: continue
            // VisualSampleEntry: coded width/height are 16-bit fields at offsets 32/34.
            val entry = MediaCrypto.box(header, stsd.body + 8, stsd.end) ?: continue
            if (entry.end - entry.start < 36) continue
            val width = (header[entry.start + 32].u() shl 8) or header[entry.start + 33].u()
            val height = (header[entry.start + 34].u() shl 8) or header[entry.start + 35].u()
            if (width <= 0 || height <= 0) continue
            return Track(String(header, entry.start + 4, 4, Charsets.US_ASCII), width, height)
        }
        return null
    }
}
