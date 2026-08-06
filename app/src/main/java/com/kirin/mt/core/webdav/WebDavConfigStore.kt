package com.kirin.mt.core.webdav

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kirin.mt.core.storage.biliDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * WebDAV 服务器配置(URL / 账号 / 密码),用 DataStore 明文持久化。
 * 与 [com.kirin.mt.core.settings.AppSettingsStore] 同款模式,复用同一个 biliDataStore。
 */
data class WebDavConfig(
  val url: String = "",
  val username: String = "",
  val password: String = "",
) {
  val isConfigured: Boolean get() = url.isNotBlank()
}

class WebDavConfigStore(private val context: Context) {
  val config: Flow<WebDavConfig> = context.biliDataStore.data.map { prefs ->
    WebDavConfig(
      url = prefs[Keys.Url] ?: "",
      username = prefs[Keys.Username] ?: "",
      password = prefs[Keys.Password] ?: "",
    )
  }

  suspend fun setConfig(config: WebDavConfig) {
    context.biliDataStore.edit { prefs ->
      prefs[Keys.Url] = config.url
      prefs[Keys.Username] = config.username
      prefs[Keys.Password] = config.password
    }
  }

  private object Keys {
    val Url = stringPreferencesKey("webdav_url")
    val Username = stringPreferencesKey("webdav_username")
    val Password = stringPreferencesKey("webdav_password")
  }
}
