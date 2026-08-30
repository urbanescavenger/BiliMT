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
 * 一条 YouTube 播放历史。由播放器在起播/暂停/退出时写入,供移动端"YouTube 历史"tab
 * 展示最近播放列表并续播。videoId 唯一,重复播放去重前置。
 */
@Serializable
data class YoutubeHistoryEntry(
  /** 视频 id（11 位）。 */
  val videoId: String,
  val title: String,
  /** 频道名。 */
  val channelName: String,
  /** 频道 id（UC 开头），用于从历史进频道主页。 */
  val channelId: String = "",
  /** 频道头像 URL（yt3.ggpht.com）。起播时从卡片 ownerFace 记录；旧条目为空，渲染时按 channelId 回退查 YoutubeChannelStore。 */
  val channelAvatarUrl: String = "",
  /** 缩略图 URL。 */
  val thumbnailUrl: String = "",
  val durationMs: Long = 0L,
  /** 发布时间(epoch 秒)。供历史进播放器简介 Tab 显示;卡片重建 toVideoSummary 时回填。 */
  val pubdate: Long = 0L,
  /** 上次播放位置（毫秒）；播完/接近播完时为 0。 */
  val positionMs: Long = 0L,
  /** 最近播放时间（epoch 毫秒），用于排序。 */
  val lastPlayedAtMs: Long = 0L,
)

/**
 * YouTube 播放历史本地存储。用 DataStore 持久化，无需登录。
 * 照抄 [YoutubeChannelStore] 的列表型 JSON 范式，复用 `context.biliDataStore`。
 */
class YoutubeHistoryStore(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = ListSerializer(YoutubeHistoryEntry.serializer())

  /** 最近播放列表，按 lastPlayedAtMs 倒序。 */
  val history: Flow<List<YoutubeHistoryEntry>> = context.biliDataStore.data.map { prefs ->
    prefs[Keys.History]?.let(::decode).orEmpty().sortedByDescending { it.lastPlayedAtMs }
  }

  /** 记录一次播放：按 videoId 去重前置，列表 cap 到 [MaxEntries]。 */
  suspend fun recordPlay(entry: YoutubeHistoryEntry) {
    if (entry.videoId.isBlank()) return
    context.biliDataStore.edit { prefs ->
      val current = decode(prefs[Keys.History]).orEmpty()
      val next = (listOf(entry) + current.filterNot { it.videoId == entry.videoId })
        .take(MaxEntries)
      prefs[Keys.History] = json.encodeToString(serializer, next)
    }
  }

  /** 更新某视频的播放位置/时长，并前置到列表头。 */
  suspend fun updatePosition(videoId: String, positionMs: Long, durationMs: Long) {
    if (videoId.isBlank()) return
    context.biliDataStore.edit { prefs ->
      val current = decode(prefs[Keys.History]).orEmpty()
      val existing = current.firstOrNull { it.videoId == videoId } ?: return@edit
      val updated = existing.copy(
        positionMs = positionMs.coerceAtLeast(0L),
        durationMs = durationMs.coerceAtLeast(0L),
        lastPlayedAtMs = System.currentTimeMillis(),
      )
      val next = (listOf(updated) + current.filterNot { it.videoId == videoId }).take(MaxEntries)
      prefs[Keys.History] = json.encodeToString(serializer, next)
    }
  }

  /**
   * 回填某条目的频道字段（channelId/channelAvatarUrl）。**不改变列表位置**（区别于 recordPlay 会前置）。
   * 历史列表懒加载时，对旧条目空白头像按频道解析一次后写回，避免逐个重播才愈合。
   * 只填空白位，不覆盖已有值（保持首次解析结果优先）。
   */
  suspend fun updateChannel(videoId: String, channelId: String, channelAvatarUrl: String) {
    if (videoId.isBlank()) return
    context.biliDataStore.edit { prefs ->
      val current = decode(prefs[Keys.History]).orEmpty()
      if (current.none { it.videoId == videoId }) return@edit
      val next = current.map { entry ->
        if (entry.videoId != videoId) entry else entry.copy(
          channelId = entry.channelId.ifBlank { channelId },
          channelAvatarUrl = entry.channelAvatarUrl.ifBlank { channelAvatarUrl },
        )
      }
      prefs[Keys.History] = json.encodeToString(serializer, next)
    }
  }

  suspend fun remove(videoId: String) {
    context.biliDataStore.edit { prefs ->
      val next = decode(prefs[Keys.History]).orEmpty().filterNot { it.videoId == videoId }
      if (next.isEmpty()) {
        prefs.remove(Keys.History)
      } else {
        prefs[Keys.History] = json.encodeToString(serializer, next)
      }
    }
  }

  suspend fun clear() {
    context.biliDataStore.edit { prefs -> prefs.remove(Keys.History) }
  }

  private fun decode(raw: String?): List<YoutubeHistoryEntry> {
    if (raw == null) return emptyList()
    return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
  }

  private object Keys {
    val History = stringPreferencesKey("youtube_history")
  }

  private companion object {
    const val MaxEntries = 300
  }
}

/**
 * 历史条目缩略图 URL：优先已存的 [YoutubeHistoryEntry.thumbnailUrl]；为空时回退到
 * 确定性 ytimg 缩略图 `https://i.ytimg.com/vi/{videoId}/hqdefault.jpg`。
 * 老版本可能在起播时未填 coverUrl 而存下空 thumbnailUrl，导致历史卡封面加载失败(YT ERR)，
 * 卡片渲染统一走这里，旧条目无需重播即修复。
 */
fun YoutubeHistoryEntry.resolveThumbnailUrl(): String =
  thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }

/**
 * 历史条目头像 URL：优先条目已存的 [YoutubeHistoryEntry.channelAvatarUrl]；为空时回退到
 * 外部提供的 [fallback]（调用方传 YoutubeChannelStore 按 channelId 查出的头像，修旧条目）。
 */
fun YoutubeHistoryEntry.resolveChannelAvatarUrl(fallback: String): String =
  channelAvatarUrl.ifBlank { fallback }
