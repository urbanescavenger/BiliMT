package com.kirin.mt.core.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 「已看完」视频 id 本地存储。播放器在播放到结尾时写入,供移动端视频卡片右下角
 * 渲染「已看完」角标(B站存 bvid、YouTube 存 videoId,统一用 VideoSummary.bvid 承载)。
 * 照抄 [com.kirin.mt.core.youtube.YoutubeHistoryStore] 的 DataStore 列表 JSON 范式,
 * 复用 `context.biliDataStore`。仅移动端消费,TV 端不参与。
 */
class WatchedStore(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = ListSerializer(String.serializer())

  /** 已看完的视频 id 集合。 */
  val watched: Flow<Set<String>> = context.biliDataStore.data.map { prefs ->
    prefs[Keys.Watched]?.let(::decode).orEmpty().toSet()
  }

  /** 标记某视频已看完:去重、cap 到 [MaxEntries](只保留最近看的)。幂等。 */
  suspend fun markCompleted(id: String) {
    if (id.isBlank()) return
    context.biliDataStore.edit { prefs ->
      val current = decode(prefs[Keys.Watched]).orEmpty()
      val next = (listOf(id) + current.filterNot { it == id }).take(MaxEntries)
      prefs[Keys.Watched] = json.encodeToString(serializer, next)
    }
  }

  suspend fun remove(id: String) {
    context.biliDataStore.edit { prefs ->
      val next = decode(prefs[Keys.Watched]).orEmpty().filterNot { it == id }
      if (next.isEmpty()) {
        prefs.remove(Keys.Watched)
      } else {
        prefs[Keys.Watched] = json.encodeToString(serializer, next)
      }
    }
  }

  /** 全部已看完 id（备份用，顺序为最近先）。 */
  suspend fun all(): List<String> = watched.first().toList()

  /** 整体重建（还原用，清空旧列表再写入）。空列表删键。 */
  suspend fun replaceAll(ids: List<String>) {
    context.biliDataStore.edit { prefs ->
      if (ids.isEmpty()) {
        prefs.remove(Keys.Watched)
      } else {
        prefs[Keys.Watched] = json.encodeToString(serializer, ids.take(MaxEntries))
      }
    }
  }

  private fun decode(raw: String?): List<String> {
    if (raw == null) return emptyList()
    return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
  }

  private object Keys {
    val Watched = stringPreferencesKey("watched")
  }

  private companion object {
    const val MaxEntries = 300
  }
}
