package com.kirin.mt.core.youtube

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kirin.mt.core.storage.biliDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 用户手动配置的 YouTube 频道列表（用于动态页"YouTube 关注"tab）。
 * 用 DataStore 持久化，无需登录。
 */
@Serializable
data class YoutubeChannel(
  /** 频道 id（UC 开头）。 */
  val channelId: String,
  val name: String,
  /** 频道头像 URL（yt3.ggpht.com）；旧数据/未解析时为空串。 */
  val avatar: String = "",
)

class YoutubeChannelStore(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = ListSerializer(YoutubeChannel.serializer())

  val channels: Flow<List<YoutubeChannel>> = context.biliDataStore.data.map { prefs ->
    prefs[Keys.Channels]?.let(::decode).orEmpty()
  }

  suspend fun add(channel: YoutubeChannel) {
    context.biliDataStore.edit { prefs ->
      val current = decode(prefs[Keys.Channels]).orEmpty()
      val next = current.filterNot { it.channelId == channel.channelId } + channel
      prefs[Keys.Channels] = json.encodeToString(serializer, next)
    }
  }

  /** 按 channelId 更新头像并回写 DataStore（用于旧频道懒解析后回填）。 */
  suspend fun updateAvatar(channelId: String, avatar: String) {
    if (avatar.isBlank()) return
    context.biliDataStore.edit { prefs ->
      val current = decode(prefs[Keys.Channels]).orEmpty()
      val next = current.map { if (it.channelId == channelId) it.copy(avatar = avatar) else it }
      if (next != current) {
        prefs[Keys.Channels] = json.encodeToString(serializer, next)
      }
    }
  }

  suspend fun remove(channelId: String) {
    context.biliDataStore.edit { prefs ->
      val next = decode(prefs[Keys.Channels]).orEmpty().filterNot { it.channelId == channelId }
      if (next.isEmpty()) {
        prefs.remove(Keys.Channels)
      } else {
        prefs[Keys.Channels] = json.encodeToString(serializer, next)
      }
    }
  }

  suspend fun clear() {
    context.biliDataStore.edit { prefs -> prefs.remove(Keys.Channels) }
  }

  private fun decode(raw: String?): List<YoutubeChannel> {
    if (raw == null) return emptyList()
    return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
  }

  private object Keys {
    val Channels = stringPreferencesKey("youtube_channels")
  }
}
