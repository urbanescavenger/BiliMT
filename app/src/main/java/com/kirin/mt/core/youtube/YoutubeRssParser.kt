package com.kirin.mt.core.youtube

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * YouTube 频道 RSS(`/feeds/videos.xml`)→ [YoutubeVideo] 解析器。
 *
 * 对齐 FreeTube 的 RSS 订阅流方法:每频道一个轻量 GET,不计入 InnerTube 请求配额、
 * 无 429 风控、无 lockupViewModel 渲染器变更风险。用 Android 内置 XmlPullParser,不新增依赖。
 *
 * RSS 不提供 duration / live / premiere,这些字段置默认值;需要时由调用方回退 InnerTube 补全。
 * 解析失败返回空列表(不抛异常),让调用方走回退路径。
 */
internal object YoutubeRssParser {

  private val publishedFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

  fun parse(xml: String): List<YoutubeVideo> {
    if (xml.isBlank()) return emptyList()
    return runCatching { parseInternal(xml) }.getOrDefault(emptyList())
  }

  private fun parseInternal(xml: String): List<YoutubeVideo> {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    parser.setInput(StringReader(xml))

    val videos = mutableListOf<YoutubeVideo>()
    var current: Entry? = null
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      when (event) {
        XmlPullParser.START_TAG -> {
          // 开启 namespace 处理后 parser.name 是本地名(无前缀):yt:videoId→videoId 等。
          when (parser.name) {
            "entry" -> current = Entry()
            "videoId" -> current?.videoId = parser.nextText().trim()
            "channelId" -> current?.channelId = parser.nextText().trim()
            "title" -> current?.title = parser.nextText().trim()
            "name" -> current?.channelName = parser.nextText().trim()
            "published" -> current?.publishedAt = parsePublished(parser.nextText().trim())
            "thumbnail" -> if (current?.thumbnailUrl.isNullOrBlank()) {
              current?.thumbnailUrl = parser.getAttributeValue(null, "url").orEmpty()
            }
            "statistics" -> current?.viewCount = parser.getAttributeValue(null, "views")?.toLongOrNull()
          }
        }
        XmlPullParser.END_TAG -> {
          if (parser.name == "entry") {
            current?.let { videos.add(it.toVideo()) }
            current = null
          }
        }
      }
      event = parser.next()
    }
    return videos
  }

  /** 单个 <entry> 的临时收集器。 */
  private class Entry {
    var videoId = ""
    var channelId = ""
    var title = ""
    var channelName = ""
    var publishedAt: Long? = null
    var thumbnailUrl = ""
    var viewCount: Long? = null

    fun toVideo(): YoutubeVideo? {
      if (videoId.isBlank() || title.isBlank()) return null
      return YoutubeVideo(
        videoId = videoId,
        title = title,
        channelName = channelName,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        viewCount = viewCount,
        durationSec = null,
        publishedAt = publishedAt,
        liveNow = false,
        isUpcoming = false,
        badge = "",
      )
    }
  }

  /** ISO 8601(如 `2026-08-05T12:34:56+00:00`)→ epoch 秒;解析失败返回 null。 */
  private fun parsePublished(raw: String): Long? {
    if (raw.isBlank()) return null
    return runCatching { publishedFormat.parse(raw)?.time?.div(1000L) }.getOrNull()
  }
}
