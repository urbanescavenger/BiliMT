package com.kirin.mt.core.webdav

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kirin.mt.core.storage.biliDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException

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

/**
 * URL 规范化候选:输入以 http:// 或 https:// 开头则尊重用户显式 scheme(只返回这一个);
 * 否则返回 https 优先、http 兜底两个候选。空输入返回空列表。
 */
fun webDavUrlCandidates(input: String): List<String> {
  val trimmed = input.trim().trimEnd('/')
  if (trimmed.isEmpty()) return emptyList()
  return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
    listOf(trimmed)
  } else {
    listOf("https://$trimmed", "http://$trimmed")
  }
}

/**
 * 保存前校验连通:依次 ping 候选 URL(https 优先),第一个 2xx 连通的用其规范化 URL 落库并返回成功;
 * 全部不通返回失败(不落库)。[ping] 由调用方注入(需访问 repository)。
 */
suspend fun validateAndSaveWebDavConfig(
  store: WebDavConfigStore,
  ping: suspend (String) -> Boolean,
  config: WebDavConfig,
): Result<WebDavConfig> {
  val candidates = webDavUrlCandidates(config.url)
  if (candidates.isEmpty()) {
    return Result.failure(IllegalStateException("URL 为空"))
  }
  for (url in candidates) {
    if (ping(url)) {
      val normalized = config.copy(url = url)
      store.setConfig(normalized)
      return Result.success(normalized)
    }
  }
  val msg = if (candidates.size > 1) {
    "无法连接服务器（已尝试 https 和 http）"
  } else {
    "无法连接服务器: ${candidates.first()}"
  }
  return Result.failure(IOException(msg))
}
