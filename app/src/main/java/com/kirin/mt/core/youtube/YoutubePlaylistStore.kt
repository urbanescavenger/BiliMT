package com.kirin.mt.core.youtube

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.storage.biliDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 默认播放列表名：首次使用预置,保证播放列表 tab 至少有一个可加入的列表。 */
const val DEFAULT_PLAYLIST_NAME = "默认"

/**
 * 一个命名 YouTube 播放列表。免登录，DataStore 持久化。
 */
@Serializable
data class YoutubePlaylist(
  val name: String,
  val videos: List<VideoSummary> = emptyList(),
)

/**
 * 本地自定义 YouTube 播放列表(可多个命名列表)，用 DataStore 持久化，免登录。
 * 卡片长按「加入播放列表」/ 播放器简介按钮把 [VideoSummary] 存入指定列表，
 * 动态页"播放列表"tab 分层展示，单列 + 长按拖动排序。
 */
class YoutubePlaylistStore(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = ListSerializer(YoutubePlaylist.serializer())
  private val legacySerializer = ListSerializer(VideoSummary.serializer())

  /** 全部命名播放列表；存储为空时返回一个空"默认"列表。 */
  val playlists: Flow<List<YoutubePlaylist>> = context.biliDataStore.data.map { prefs ->
    val list = prefs[Keys.Playlists]?.let(::decode).orEmpty()
    if (list.isEmpty()) listOf(YoutubePlaylist(DEFAULT_PLAYLIST_NAME)) else list
  }

  /** 首次迁移旧版单扁平列表(旧 key youtube_playlist)进"默认"列表，避免丢既有数据。 */
  suspend fun migrateLegacyIfNeeded() {
    context.biliDataStore.edit { prefs ->
      val legacy = prefs[Keys.LegacyPlaylist] ?: return@edit
      if (prefs[Keys.Playlists] != null) return@edit
      val videos = runCatching { json.decodeFromString(legacySerializer, legacy) }
        .getOrDefault(emptyList())
      prefs[Keys.Playlists] = json.encodeToString(
        serializer,
        listOf(YoutubePlaylist(DEFAULT_PLAYLIST_NAME, videos)),
      )
    }
  }

  /** 加入指定播放列表：列表不存在则新建；按 videoId(bvid) 去重，新项前置。 */
  suspend fun addVideo(playlistName: String, video: VideoSummary) {
    editPlaylists { list ->
      if (list.none { it.name == playlistName }) {
        list + YoutubePlaylist(playlistName, listOf(video))
      } else {
        list.map { pl ->
          if (pl.name == playlistName && pl.videos.none { it.bvid == video.bvid }) {
            pl.copy(videos = listOf(video) + pl.videos)
          } else {
            pl
          }
        }
      }
    }
  }

  /** 从指定播放列表移除 videoId(bvid)。 */
  suspend fun removeVideo(playlistName: String, videoId: String) {
    editPlaylists { list ->
      list.map { pl ->
        if (pl.name == playlistName) pl.copy(videos = pl.videos.filterNot { it.bvid == videoId })
        else pl
      }
    }
  }

  /** 批量移除：从指定播放列表一次性过滤掉多个 videoId(bvid)，单次写盘。 */
  suspend fun removeVideos(playlistName: String, videoIds: Set<String>) {
    if (videoIds.isEmpty()) return
    editPlaylists { list ->
      list.map { pl ->
        if (pl.name == playlistName) pl.copy(videos = pl.videos.filterNot { it.bvid in videoIds })
        else pl
      }
    }
  }

  /** 新建空播放列表；重名返回 false。 */
  suspend fun createPlaylist(name: String): Boolean {
    var created = false
    editPlaylists { list ->
      if (name.isBlank() || list.any { it.name == name }) {
        list
      } else {
        created = true
        list + YoutubePlaylist(name)
      }
    }
    return created
  }

  /** 用整列新顺序覆盖某播放列表(长按拖动排序结束后写入最终顺序)。 */
  suspend fun replaceVideos(playlistName: String, videos: List<VideoSummary>) {
    editPlaylists { list ->
      list.map { pl ->
        if (pl.name == playlistName) pl.copy(videos = videos) else pl
      }
    }
  }

  /** 长按拖动排序：把 fromIndex 位置移到 toIndex。 */
  suspend fun moveVideo(playlistName: String, fromIndex: Int, toIndex: Int) {
    editPlaylists { list ->
      list.map { pl ->
        if (pl.name == playlistName) {
          val videos = pl.videos.toMutableList()
          if (fromIndex in videos.indices && toIndex in videos.indices && fromIndex != toIndex) {
            val item = videos.removeAt(fromIndex)
            videos.add(toIndex, item)
            pl.copy(videos = videos)
          } else {
            pl
          }
        } else {
          pl
        }
      }
    }
  }

  /** 统一读写入口：读当前列表 → 变换 → 写回；结果为空则删 key。 */
  private suspend fun editPlaylists(transform: (List<YoutubePlaylist>) -> List<YoutubePlaylist>) {
    context.biliDataStore.edit { prefs ->
      val current = prefs[Keys.Playlists]?.let(::decode).orEmpty()
      val next = transform(current)
      if (next.isEmpty()) {
        prefs.remove(Keys.Playlists)
      } else {
        prefs[Keys.Playlists] = json.encodeToString(serializer, next)
      }
    }
  }

  private fun decode(raw: String): List<YoutubePlaylist> {
    return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
  }

  private object Keys {
    val Playlists = stringPreferencesKey("youtube_playlists")
    val LegacyPlaylist = stringPreferencesKey("youtube_playlist")
  }
}
