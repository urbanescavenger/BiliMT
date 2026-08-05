package com.kirin.mt.core.youtube

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.biliDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 本地自定义 YouTube 播放列表，用 DataStore 持久化，免登录。
 * 卡片长按 / 播放器"加入播放列表"按钮把 [VideoSummary] 存入，动态页"播放列表"tab 展示。
 */
class YoutubePlaylistStore(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = ListSerializer(VideoSummary.serializer())

  val videos: Flow<List<VideoSummary>> = context.biliDataStore.data.map { prefs ->
    prefs[Keys.Playlist]?.let(::decode).orEmpty()
  }

  /** 加入播放列表：按 videoId(bvid) 去重，新项前置。已在列表则忽略。 */
  suspend fun add(video: VideoSummary) {
    context.biliDataStore.edit { prefs ->
      val current = decode(prefs[Keys.Playlist]).orEmpty()
      if (current.any { it.bvid == video.bvid }) return@edit
      prefs[Keys.Playlist] = json.encodeToString(serializer, listOf(video) + current)
    }
  }

  /** 从播放列表移除指定 videoId(bvid)。 */
  suspend fun remove(videoId: String) {
    context.biliDataStore.edit { prefs ->
      val next = decode(prefs[Keys.Playlist]).orEmpty().filterNot { it.bvid == videoId }
      if (next.isEmpty()) {
        prefs.remove(Keys.Playlist)
      } else {
        prefs[Keys.Playlist] = json.encodeToString(serializer, next)
      }
    }
  }

  /**
   * 切换加入/移除，返回操作后是否在列表中（true=刚加入，false=刚移除）。
   * 供长按/播放器按钮的乐观切换 + Toast 文案。
   */
  suspend fun toggle(video: VideoSummary): Boolean {
    var added = false
    context.biliDataStore.edit { prefs ->
      val current = decode(prefs[Keys.Playlist]).orEmpty()
      added = current.none { it.bvid == video.bvid }
      if (added) {
        prefs[Keys.Playlist] = json.encodeToString(serializer, listOf(video) + current)
      } else {
        val next = current.filterNot { it.bvid == video.bvid }
        if (next.isEmpty()) prefs.remove(Keys.Playlist)
        else prefs[Keys.Playlist] = json.encodeToString(serializer, next)
      }
    }
    return added
  }

  /** 判断 videoId(bvid) 是否已在播放列表。 */
  suspend fun contains(videoId: String): Boolean {
    return context.biliDataStore.data.first()[Keys.Playlist]?.let(::decode)
      .orEmpty().any { it.bvid == videoId }
  }

  private fun decode(raw: String?): List<VideoSummary> {
    if (raw == null) return emptyList()
    return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
  }

  private object Keys {
    val Playlist = stringPreferencesKey("youtube_playlist")
  }
}
