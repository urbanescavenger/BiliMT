package com.kirin.mt.core.youtube

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.biliDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 移动端动态页"YouTube 关注"流的持久化缓存。
 *
 * 关注频道多时逐频道拉取可能超过动态超时,缓存让进动态页先秒出上次的流,后台再刷新。
 * 存一份拉取时的频道 id 列表([CachedYoutubeFeed.channelIds]),读缓存时对比当前频道列表,
 * 增删频道后自动失效 —— 无需在 [YoutubeChannelStore] 增删处主动清缓存,完全解耦。
 */
@Serializable
data class CachedYoutubeFeed(
  /** 拉取时的频道 id 列表,用于频道变化自动失效。 */
  val channelIds: List<String>,
  val videos: List<VideoSummary>,
  /** 拉取时间戳(epoch millis),用于 TTL 判断。 */
  val fetchedAt: Long,
)

class YoutubeFeedCacheStore(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = CachedYoutubeFeed.serializer()

  suspend fun read(): CachedYoutubeFeed? {
    val raw = context.biliDataStore.data.first()[Keys.Feed] ?: return null
    return decode(raw)
  }

  suspend fun write(channelIds: List<String>, videos: List<VideoSummary>) {
    context.biliDataStore.edit { prefs ->
      prefs[Keys.Feed] = json.encodeToString(
        serializer,
        CachedYoutubeFeed(channelIds = channelIds, videos = videos, fetchedAt = System.currentTimeMillis()),
      )
    }
  }

  suspend fun clear() {
    context.biliDataStore.edit { prefs -> prefs.remove(Keys.Feed) }
  }

  private fun decode(raw: String): CachedYoutubeFeed? {
    return runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
  }

  private object Keys {
    val Feed = stringPreferencesKey("youtube_feed_cache")
  }
}
