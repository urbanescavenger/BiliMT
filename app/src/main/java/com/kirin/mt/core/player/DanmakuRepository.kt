package com.kirin.mt.core.player

import android.graphics.Color
import android.util.Xml
import com.kirin.mt.core.network.BiliApiClient
import com.kirin.mt.core.network.BiliApiEndpoints
import com.kirin.mt.core.network.BiliHeaders
import com.kirin.mt.core.storage.SessionStore
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

internal class DanmakuRepository(
  private val apiClient: BiliApiClient,
  private val sessionStore: SessionStore,
) {
  suspend fun getDanmaku(cid: Long): List<DanmakuEntry> {
    if (cid <= 0L) return emptyList()
    val sessData = sessionStore.sessData.first()
    val biliJct = sessionStore.biliJct.first()
    val headers = buildDanmakuHeaders(sessData = sessData, biliJct = biliJct)
    return withContext(Dispatchers.IO) {
      var primaryError: Throwable? = null
      val primaryEntries = runCatching {
        val bytes = apiClient.getBytes(
          url = BiliApiEndpoints.PlayerDanmaku,
          params = mapOf(
            "type" to "1",
            "oid" to cid.toString(),
          ),
          headers = headers,
        )
        parseDanmakuXml(decodeDanmakuXmlBytes(bytes))
      }.onFailure { error ->
        primaryError = error
      }.getOrDefault(emptyList())
      if (primaryEntries.isNotEmpty()) {
        return@withContext primaryEntries
      }

      runCatching {
        val bytes = apiClient.getBytes(
          url = BiliApiEndpoints.legacyDanmaku(cid),
          headers = headers,
        )
        parseDanmakuXml(decodeDanmakuXmlBytes(bytes))
      }.getOrElse { error ->
        primaryError?.let { throw it }
        throw error
      }
    }
  }

  private fun parseDanmakuXml(bytes: ByteArray): List<DanmakuEntry> {
    if (bytes.isEmpty()) return emptyList()
    val parser = Xml.newPullParser()
    val entries = mutableListOf<DanmakuEntry>()
    ByteArrayInputStream(bytes).use { stream ->
      parser.setInput(stream, null)
      var eventType = parser.eventType
      while (eventType != XmlPullParser.END_DOCUMENT && entries.size < MaxDanmakuEntries) {
        if (eventType == XmlPullParser.START_TAG && parser.name == "d") {
          val params = parser.getAttributeValue(null, "p").orEmpty()
          val text = parser.nextText().trim()
          parseDanmakuEntry(params, text)?.let(entries::add)
        }
        eventType = parser.next()
      }
    }
    return entries.sortedBy(DanmakuEntry::showAtMs)
  }

  private fun buildDanmakuHeaders(sessData: String?, biliJct: String?): Map<String, String> {
    return buildMap {
      put("User-Agent", BiliHeaders.UserAgent)
      put("Referer", BiliHeaders.Referer)
      put("Origin", BiliHeaders.Origin)
      put("Accept", "text/xml,application/xml,*/*;q=0.8")
      put("Accept-Encoding", "gzip, deflate")
      BiliHeaders.cookie(sessData, biliJct)?.let { cookie -> put("Cookie", cookie) }
    }
  }

  private fun decodeDanmakuXmlBytes(bytes: ByteArray): ByteArray {
    if (bytes.isEmpty()) return bytes
    return when {
      bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() -> {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { stream -> stream.readBytes() }
      }
      bytes[0] == 0x78.toByte() -> {
        InflaterInputStream(ByteArrayInputStream(bytes)).use { stream -> stream.readBytes() }
      }
      bytes[0] == '<'.code.toByte() -> bytes
      else -> runCatching {
        val inflater = Inflater(true)
        try {
          InflaterInputStream(ByteArrayInputStream(bytes), inflater).use { stream -> stream.readBytes() }
        } finally {
          inflater.end()
        }
      }.getOrDefault(bytes)
    }
  }

  private fun parseDanmakuEntry(params: String, text: String): DanmakuEntry? {
    if (text.isBlank()) return null
    val parts = params.split(',')
    if (parts.size < 4) return null
    val showAtMs = ((parts[0].toDoubleOrNull() ?: return null) * 1000.0).toLong()
    val mode = when (parts[1].toIntOrNull()) {
      1, 2, 3, 6 -> DanmakuMode.Scroll
      4 -> DanmakuMode.Bottom
      5 -> DanmakuMode.Top
      else -> return null
    }
    return DanmakuEntry(
      showAtMs = showAtMs.coerceAtLeast(0L),
      text = text,
      mode = mode,
      color = parseDanmakuColor(parts[3]),
    )
  }

  private fun parseDanmakuColor(value: String): Int {
    val rgb = value.toLongOrNull()?.coerceIn(0L, 0xFFFFFFL)?.toInt() ?: return Color.WHITE
    return Color.rgb(
      (rgb shr 16) and 0xFF,
      (rgb shr 8) and 0xFF,
      rgb and 0xFF,
    )
  }

  private companion object {
    const val MaxDanmakuEntries = 5000
  }
}
