package com.kirin.mt.core.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * YouTube googlevideo URL 的 `s` 签名解密。
 *
 * 部分播放流（尤其 adaptive 高清 1080P/2K/4K）以 `signatureCipher` 形式返回：
 * `s=<sig>&sp=signature&url=<googlevideo 直链>`。其中 `s` 是被 player base.js 里
 * 解密函数加密过的签名，必须还原后替换 `sp` 参数，否则 googlevideo 返回 403。
 *
 * 实现（与 [YoutubeNDecryptor] 同款机制）：
 *  1. 拉取并整体 eval base.js（best-effort，把解密函数及其辅助函数定义进全局）。
 *  2. 用正则识别解密函数名（`var <name>=function(a){...a.split("")...}`）。
 *  3. 用隐藏 WebView 对 `s` 求值，拿回还原后的签名。
 *
 * 脆弱点（已知）：Google 常改 base.js 结构，正则可能失效；失败时静默回退原始 url
 * （多半 403，由播放器报错暴露），留日志供真机迭代。网络请求由 Kotlin 发。
 */
class YoutubeSDecryptor(
  private val executor: YoutubeJsExecutor,
  private val httpClient: OkHttpClient,
) {

  private var baseJs: String? = null
  private var resolvedFunction: String? = null

  /** 拉取并缓存 base.js 文本（共享给 n 解密的同一份）。失败返回 null。 */
  suspend fun loadBaseJs(playerJsUrl: String): String? {
    baseJs?.let { return it }
    val text = runCatching {
      httpClient.fetchText(playerJsUrl)
    }.getOrNull()
    if (text.isNullOrBlank()) {
      Log.w(Tag, "s-decrypt: base.js fetch failed/blank")
      return null
    }
    // 整体 eval 进隐藏 WebView，让解密函数及其辅助函数成为全局可调用。
    val evalOk = runCatching { executor.eval(text) != null }.getOrDefault(false)
    if (!evalOk) Log.w(Tag, "s-decrypt: base.js eval produced no result")
    resolveFunctionName(text)
    baseJs = text
    return text
  }

  /**
   * 解密一个 `s` 签名。
   * @param playerJsUrl base.js URL（首次解密时用来拉取+eval）。
   * @return 还原后的签名；失败/无法定位解密函数时返回 null（调用方回退原始 url → 403）。
   */
  suspend fun decrypt(signature: String, playerJsUrl: String): String? {
    if (signature.isBlank()) return null
    loadBaseJs(playerJsUrl)
    val fn = resolvedFunction ?: run {
      Log.w(Tag, "s-decrypt: no decipher function resolved")
      return null
    }
    val result = runCatching {
      executor.eval("($fn)(${jsonString(signature)})")
    }.getOrNull()
    val deciphered = result?.trim()?.removeSurrounding("\"")
    if (deciphered.isNullOrBlank() || deciphered == "null") {
      Log.w(Tag, "s-decrypt: decipher returned ${result ?: "null"}")
      return null
    }
    Log.i(Tag, "s-decrypt: ok (${signature.length}->${deciphered.length})")
    return deciphered
  }

  /** 从 base.js 文本里识别 `s` 解密函数名。 */
  private fun resolveFunctionName(text: String) {
    if (resolvedFunction != null) return
    val patterns = listOf(
      Regex("""var ([a-zA-Z0-9$]+)=function\(a\)\{var \w+=\w+\.split\(\"\"\)"""),
      Regex("""var ([a-zA-Z0-9$]+)=function\(a\)\{[a-zA-Z0-9$]+=\w+\.split\(\"\"\)"""),
    )
    for (pattern in patterns) {
      val m = pattern.find(text)
      if (m != null) {
        resolvedFunction = m.groupValues[1]
        Log.i(Tag, "s-decrypt: decipher function=${resolvedFunction}")
        return
      }
    }
    Log.w(Tag, "s-decrypt: could not locate decipher function")
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
    const val Tag = "YtSDecrypt"
  }
}
