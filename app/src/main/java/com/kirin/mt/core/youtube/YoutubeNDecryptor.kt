package com.kirin.mt.core.youtube

import android.util.Log
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * YouTube googlevideo URL 的 `n` 参数解密。
 *
 * 播放流 URL 常带 `n` 签名参数（防爬），必须用 player base.js 里的 transform 函数
 * 解出真值，否则 googlevideo 返回 403。本类：
 *  1. 拉取 base.js（URL 由 [YoutubePlaybackResolver] 从 player 配置里取）。
 *  2. 在隐藏 WebView 里整体 eval base.js（best-effort），把 transform 函数定义进全局。
 *  3. 用正则识别 transform 函数名（`name` 或 `name[idx]`），对 `n` 求值换回 URL。
 *
 * 脆弱点（已知，文档已标）：Google 常改 base.js 结构，正则/整体 eval 可能失效。
 * 失败时静默回退原 URL（多半 403，由播放器报错暴露），留日志供真机迭代。
 * 网络请求由 Kotlin 发（WebView 跨源 fetch 会被 CORS 拦），WebView 只执行 JS。
 */
class YoutubeNDecryptor(
  private val executor: YoutubeJsExecutor,
  private val httpClient: OkHttpClient,
) {

  private var baseJs: String? = null
  private var resolvedName: String? = null
  private var resolvedIndex: Int? = null

  /** 拉取并缓存 base.js 文本。失败返回 null。 */
  suspend fun loadBaseJs(playerJsUrl: String): String? {
    baseJs?.let { return it }
    val text = runCatching {
      httpClient.fetchText(playerJsUrl)
    }.getOrNull()
    if (text.isNullOrBlank()) {
      Log.w(Tag, "n-decrypt: base.js fetch failed/blank")
      return null
    }
    // 首次解析时整体 eval 进隐藏 WebView，让 transform 函数成为全局可调用。
    val evalOk = runCatching { executor.eval(text) != null }.getOrDefault(false)
    if (!evalOk) Log.w(Tag, "n-decrypt: base.js eval produced no result")
    resolveTransformName(text)
    baseJs = text
    return text
  }

  /**
   * 解密一个带 `n` 的播放 URL。
   * @param playerJsUrl base.js URL（首次解密时用来拉取+eval）。
   */
  suspend fun decrypt(baseUrl: String, playerJsUrl: String): String {
    val n = extractParam(baseUrl, "n") ?: return baseUrl
    loadBaseJs(playerJsUrl)
    val name = resolvedName ?: run {
      Log.w(Tag, "n-decrypt: no transform name resolved, leaving n untouched")
      return baseUrl
    }
    val result = runCatching {
      val callExpr = if (resolvedIndex != null) "($name)[$resolvedIndex]" else "($name)"
      executor.eval("($callExpr)(${jsonString(n)})")
    }.getOrNull()
    val transformed = result?.trim()?.removeSurrounding("\"")
    if (transformed.isNullOrBlank() || transformed == "null") {
      Log.w(Tag, "n-decrypt: transform returned ${result ?: "null"}, leaving n untouched")
      return baseUrl
    }
    Log.i(Tag, "n-decrypt: ok (${n.length}->${transformed.length})")
    return replaceParam(baseUrl, "n", transformed)
  }

  /** 从 base.js 文本里识别 transform 函数名（name 或 name[idx]）。 */
  private fun resolveTransformName(text: String) {
    if (resolvedName != null) return
    // 经典 youtubei.js 正则：匹配 `(b=<name>[<idx>](<arg>)` 紧跟在 n 相关代码后。
    val patterns = listOf(
      Regex(""".get\("n"\)\)\|\|\[\]\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\([a-zA-Z0-9]\)"""),
      Regex(""".get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\([a-zA-Z0-9]\)"""),
      Regex("""\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\([a-zA-Z0-9]\)"""),
    )
    for (pattern in patterns) {
      val m = pattern.find(text)
      if (m != null) {
        resolvedName = m.groupValues[1]
        resolvedIndex = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull()
        Log.i(Tag, "n-decrypt: transform name=${resolvedName} idx=${resolvedIndex}")
        return
      }
    }
    Log.w(Tag, "n-decrypt: could not locate transform name")
  }

  private fun extractParam(url: String, key: String): String? {
    val start = url.indexOf("$key=")
    if (start < 0) return null
    val valueStart = start + key.length + 1
    val end = url.indexOf('&', valueStart).let { if (it < 0) url.length else it }
    val raw = url.substring(valueStart, end)
    return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
  }

  private fun replaceParam(url: String, key: String, value: String): String {
    val start = url.indexOf("$key=")
    if (start < 0) return url
    val valueStart = start + key.length + 1
    val end = url.indexOf('&', valueStart).let { if (it < 0) url.length else it }
    return url.substring(0, valueStart) + value + url.substring(end)
  }

  private fun jsonString(input: String): String {
    val escaped = input
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    return "\"$escaped\""
  }

  private suspend fun OkHttpClient.fetchText(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).build()
    newCall(request).execute().use { response ->
      if (!response.isSuccessful) "" else response.body?.string().orEmpty()
    }
  }

  private companion object {
    const val Tag = "YtNDecrypt"
  }
}
