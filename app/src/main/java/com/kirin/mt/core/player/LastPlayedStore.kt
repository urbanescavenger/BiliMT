package com.kirin.mt.core.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kirin.mt.core.storage.biliDataStore
import kotlinx.coroutines.flow.first

/**
 * Remembers the most recently played video (bvid + cid) so the CDN speed test
 * in settings can replay that video's signed media URLs against [CdnSpeedTester].
 *
 * Bilibili media URLs are signed per video, so the speed test cannot fabricate
 * candidates — it reuses the last playback's real URLs.
 */
class LastPlayedStore(private val context: Context) {

  suspend fun save(bvid: String, cid: Long) {
    if (bvid.isBlank() || cid <= 0L) {
      return
    }
    context.biliDataStore.edit { preferences ->
      preferences[stringPreferencesKey(KeyBvid)] = bvid
      preferences[longPreferencesKey(KeyCid)] = cid
    }
  }

  suspend fun load(): LastPlayedVideo? {
    val preferences = context.biliDataStore.data.first()
    val bvid = preferences[stringPreferencesKey(KeyBvid)]?.takeIf { it.isNotBlank() } ?: return null
    val cid = preferences[longPreferencesKey(KeyCid)]?.takeIf { it > 0L } ?: return null
    return LastPlayedVideo(bvid = bvid, cid = cid)
  }

  private companion object {
    const val KeyBvid = "last_played_bvid"
    const val KeyCid = "last_played_cid"
  }
}

data class LastPlayedVideo(
  val bvid: String,
  val cid: Long,
)