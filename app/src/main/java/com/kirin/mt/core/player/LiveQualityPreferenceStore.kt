package com.kirin.mt.core.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.kirin.mt.core.storage.biliDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 直播画质偏好持久化:记住用户上次在直播播放器选的清晰度 qn,下次进任何直播间默认沿用。
 * 镜像 [DanmakuSettingsStore] 的 DataStore 模式。默认 [DefaultQn](原画)。
 */
class LiveQualityPreferenceStore(private val context: Context) {
  val quality: Flow<Int> = context.biliDataStore.data.map { preferences ->
    preferences[Keys.Quality] ?: DefaultQn
  }

  suspend fun setQuality(qn: Int) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.Quality] = qn
    }
  }

  private object Keys {
    val Quality = intPreferencesKey("live_quality_qn")
  }

  private companion object {
    /** 与 [LivePlayerScreen] 的 LiveDefaultQn 对齐:原画。 */
    const val DefaultQn = 10000
  }
}