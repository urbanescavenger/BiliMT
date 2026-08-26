package com.kirin.mt.core.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.kirin.mt.core.storage.biliDataStore
import kotlinx.coroutines.flow.first

data class PlaybackProgress(
  val cid: Long,
  val positionMs: Long,
  val durationMs: Long,
  val updatedAtMs: Long,
)

class PlaybackProgressStore(private val context: Context) {
  suspend fun getProgress(bvid: String, cid: Long): PlaybackProgress? {
    val keyPrefix = keyPrefix(bvid, cid)
    val preferences = context.biliDataStore.data.first()
    val positionMs = preferences[longPreferencesKey("${keyPrefix}_position_ms")] ?: return null
    val durationMs = preferences[longPreferencesKey("${keyPrefix}_duration_ms")] ?: 0L
    val updatedAtMs = preferences[longPreferencesKey("${keyPrefix}_updated_at_ms")] ?: 0L
    return PlaybackProgress(
      cid = cid,
      positionMs = positionMs,
      durationMs = durationMs,
      updatedAtMs = updatedAtMs,
    )
  }

  suspend fun getLatestProgress(bvid: String): PlaybackProgress? {
    if (bvid.isBlank()) {
      return null
    }
    val keyPrefix = latestKeyPrefix(bvid)
    val preferences = context.biliDataStore.data.first()
    val cid = preferences[longPreferencesKey("${keyPrefix}_cid")]?.takeIf { it > 0L } ?: return null
    val positionMs = preferences[longPreferencesKey("${keyPrefix}_position_ms")] ?: return null
    val durationMs = preferences[longPreferencesKey("${keyPrefix}_duration_ms")] ?: 0L
    val updatedAtMs = preferences[longPreferencesKey("${keyPrefix}_updated_at_ms")] ?: 0L
    return PlaybackProgress(
      cid = cid,
      positionMs = positionMs,
      durationMs = durationMs,
      updatedAtMs = updatedAtMs,
    )
  }

  /**
   * 一次 DataStore 读取批量取多个视频的最近进度(供卡片列表合入观看进度用,避免逐条
   * [getLatestProgress] 的 N 次读取)。无该 bvid 记录的不进结果。
   */
  suspend fun getLatestProgressMap(bvids: Set<String>): Map<String, PlaybackProgress> {
    if (bvids.isEmpty()) return emptyMap()
    val preferences = context.biliDataStore.data.first()
    return bvids.mapNotNull { bvid ->
      if (bvid.isBlank()) return@mapNotNull null
      val prefix = latestKeyPrefix(bvid)
      val positionMs = preferences[longPreferencesKey("${prefix}_position_ms")] ?: return@mapNotNull null
      val cid = preferences[longPreferencesKey("${prefix}_cid")] ?: 0L
      val durationMs = preferences[longPreferencesKey("${prefix}_duration_ms")] ?: 0L
      val updatedAtMs = preferences[longPreferencesKey("${prefix}_updated_at_ms")] ?: 0L
      bvid to PlaybackProgress(cid, positionMs, durationMs, updatedAtMs)
    }.toMap()
  }

  suspend fun saveProgress(
    bvid: String,
    cid: Long,
    positionMs: Long,
    durationMs: Long,
  ) {
    // cid=0 允许：YouTube 播放请求 bvid 承载 videoId、cid 恒为 0，靠 videoId 唯一续播。
    // B站视频 cid 恒 >0，不受影响。
    if (bvid.isBlank() || cid < 0L || positionMs < 0L) {
      return
    }
    val keyPrefix = keyPrefix(bvid, cid)
    val latestKeyPrefix = latestKeyPrefix(bvid)
    val updatedAtMs = System.currentTimeMillis()
    context.biliDataStore.edit { preferences ->
      preferences[longPreferencesKey("${keyPrefix}_position_ms")] = positionMs
      preferences[longPreferencesKey("${keyPrefix}_duration_ms")] = durationMs.coerceAtLeast(0L)
      preferences[longPreferencesKey("${keyPrefix}_updated_at_ms")] = updatedAtMs
      preferences[longPreferencesKey("${latestKeyPrefix}_cid")] = cid
      preferences[longPreferencesKey("${latestKeyPrefix}_position_ms")] = positionMs
      preferences[longPreferencesKey("${latestKeyPrefix}_duration_ms")] = durationMs.coerceAtLeast(0L)
      preferences[longPreferencesKey("${latestKeyPrefix}_updated_at_ms")] = updatedAtMs
    }
  }

  private fun keyPrefix(bvid: String, cid: Long): String {
    return "playback_${bvid}_${cid}"
  }

  private fun latestKeyPrefix(bvid: String): String {
    return "playback_${bvid}_latest"
  }
}
