package com.kirin.mt.core.youtube

import android.content.Context
import android.util.Log
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * YouTube googlevideo URL 的 `n` 参数解密。
 *
 * 播放流 URL 常带 `n` 签名参数（防爬），必须用 player base.js 里的 transform 函数
 * 解出真值，否则 googlevideo 返回 403。本类：
 *  1. 拉取 base.js（URL 由 [YoutubePlaybackResolver] 从 player 配置里取）。
 *  2. 把 `window.__nTransformFunc = function(n){ var u=new g.<nClass>(url); return u.get('n') }`
 *     注入到 base.js 的 IIFE 闭包内（`})(_yt_player);` 之前），让函数闭包捕获 IIFE 内的
 *     `g` 局部,再 eval 整个 base.js 进隐藏 WebView。
 *  3. `evaluateJavascript("window.__nTransformFunc(n)")` 取 transformed n 回填 URL。
 *
 * **为什么用 URL 类实例化而非直接调函数名**（alpha.32,对齐 zemer-cipher）：
 * plasma 播放器把 n/sig transform 移进 WASM，经典「正则找函数名 + 外部 eval 直接调用」
 * 结构性失效——正则锚点 `.get("n"))&&(b=Name(c))` 在 plasma 上 0 匹配；即便命中，函数名是
 * base.js IIFE 内的局部，外部 `evaluateJavascript` 取不到。改实例化 YouTube 内部 URL 类
 * (`new g.<nClass>(url).get('n')`)——`.get('n')` 内部触发 transform(即便 transform 在 WASM
 * 里也照跑,这是 YouTube 播放器自己取 n 的原生路径)。`nClass` 从 player hash 查 config
 * (种子来自 zemer-cipher 公开 config，覆盖当前 plasma 95daa498=Xz 等)。
 *
 * 失败时静默回退原 URL（多半 403 → [YoutubePlaybackResolver] 走 WebView harvest 兜底），
 * 留日志供真机迭代。
 * 网络请求由 Kotlin 发（WebView 跨源 fetch 会被 CORS 拦），WebView 只执行 JS。
 */
class YoutubeNDecryptor(
  context: Context,
  private val executor: YoutubeJsExecutor,
  private val httpClient: OkHttpClient,
) {

  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private var baseJs: String? = null
  /** 当前 player 的 nClass（URL 类名），由 [resolveNClass] 从 player hash 查 config；null=无 config→兜底。 */
  private var resolvedNClass: String? = null

  /**
   * player hash → nClass(URL 类名) config，从 bundled asset
   * `youtube/player_configs.json` 加载（种子取自 zemer-cipher 公开 config，
   * https://github.com/ZemerTeam/zemer-cipher，覆盖 224 条 player 含当前 plasma）。
   * 每个 player 条目同时登记主 hash 与 [aliases]，故 alias 形式的 URL hash 也能命中。
   * YouTube 每次 player 轮换若 config 未覆盖 → resolveNClass 返 null → 走 harvest 兜底。
   * 后续(不在本 PoC)：叠自愈远程 fetch（6h TTL + 失败强刷，镜像 zemer PlayerConfigStore）。
   */
  private val nClassByPlayerHash: Map<String, String> by lazy { loadPlayerConfig() }

  private fun loadPlayerConfig(): Map<String, String> {
    return runCatching {
      val raw = appContext.assets.open("youtube/player_configs.json").bufferedReader().use { it.readText() }
      val root = json.parseToJsonElement(raw).jsonObject
      val players = root["players"]?.jsonObject ?: return emptyMap()
      val out = HashMap<String, String>(players.size * 2)
      for ((hash, entry) in players) {
        val e = entry.jsonObject
        val nClass = e["nClass"]?.jsonPrimitive?.contentOrNull ?: continue
        out[hash] = nClass
        val aliases = e["aliases"] as? JsonArray
        aliases?.forEach { a -> a.jsonPrimitive.contentOrNull?.let { alias -> out[alias] = nClass } }
      }
      out
    }.getOrElse {
      Log.w(Tag, "n-decrypt: failed to load player_configs.json (${it.message}); config empty → harvest fallback")
      emptyMap()
    }
  }

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
    // URL 类方式：查 nClass,注入 export 到 IIFE 闭包内,eval 整个 base.js,验证 __nTransformFunc 就绪。
    val nClass = resolveNClass(playerJsUrl, text)
    resolvedNClass = nClass
    if (nClass != null) {
      val modified = injectNExport(text, buildNTransformExport(nClass))
      executor.eval(modified)
      val probe = executor.eval("typeof window.__nTransformFunc")
      if (probe?.contains("function") != true) {
        // base.js IIFE 在我们 shell 上下文里中途抛了(__nTransformFunc 没定义)→ 视作不可用,走 harvest 兜底。
        Log.w(Tag, "n-decrypt: __nTransformFunc not defined after eval (IIFE threw? nClass=$nClass probe=$probe) → harvest fallback")
        resolvedNClass = null
      } else {
        Log.i(Tag, "n-decrypt: __nTransformFunc ready (nClass=$nClass)")
      }
    } else {
      Log.w(Tag, "n-decrypt: no nClass config for this player → harvest fallback")
    }
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
    val nClass = resolvedNClass ?: run {
      Log.w(Tag, "n-decrypt: no nClass resolved, leaving n untouched → harvest fallback")
      return baseUrl
    }
    val result = runCatching {
      executor.eval("window.__nTransformFunc(${jsonString(n)})")
    }.getOrNull()
    val transformed = result?.trim()?.removeSurrounding("\"")
    if (transformed.isNullOrBlank() || transformed == "null" || transformed == n) {
      Log.w(Tag, "n-decrypt: transform no-op/failed (nClass=$nClass result=$result), leaving n untouched → harvest fallback")
      return baseUrl
    }
    Log.i(Tag, "n-decrypt: ok nClass=$nClass (${n.length}->${transformed.length})")
    return replaceParam(baseUrl, "n", transformed)
  }

  /**
   * 从 base.js URL / 内容识别当前 player,查 [nClassByPlayerHash] 取 nClass。
   * 1) 从 URL 路径抽 8-hex hash（/s/player/<hash>/ 或 player_*.vflset/.../<hash>/）。
   * 2) fallback：首 10000 字节 MD5 取前 4 字节（对齐 zemer-cipher 的 alias 方案）。
   * 返回 null = config 无此 player → 走 harvest 兜底。
   */
  private fun resolveNClass(playerJsUrl: String, baseJsText: String): String? {
    val hash = PLAYER_HASH_PATTERNS.firstNotNullOfOrNull { it.find(playerJsUrl)?.groupValues?.get(1) }
      ?: md5Alias(baseJsText)
    if (hash == null) {
      Log.w(Tag, "n-decrypt: could not extract player hash from url/content")
      return null
    }
    val nClass = nClassByPlayerHash[hash]
    Log.i(Tag, "n-decrypt: playerHash=$hash nClass=$nClass ${if (nClass == null) "(no config)" else "(config hit)"}")
    return nClass
  }

  /** 首 10000 字节 MD5 取前 4 字节作 hash alias（对齐 zemer-cipher FunctionNameExtractor fallback）。 */
  private fun md5Alias(text: String): String? {
    return runCatching {
      val digest = MessageDigest.getInstance("MD5").digest(text.take(10000).toByteArray())
      digest.take(4).joinToString("") { "%02x".format(it) }
    }.getOrNull()
  }

  /**
   * 构造 n-transform export：实例化 YouTube 内部 URL 类,`.get('n')` 内部触发 transform
   * （即便 transform 在 WASM 里也照跑）。对齐 zemer-cipher `PlayerConfigParser.buildNJsExpression`。
   * `g` 是 base.js IIFE 内的 namespace 局部——所以 export 必须注入到 IIFE 闭包内才能捕获它。
   */
  private fun buildNTransformExport(nClass: String): String =
    "window.__nTransformFunc = function(n) { try { " +
      "var u = new g.$nClass('https://x.googlevideo.com/videoplayback?n=' + n, true); " +
      "var t = u.get('n'); return (t && t !== n) ? t : n; " +
      "} catch(e) { return n; } };"

  /** 把 export 注入到 base.js IIFE 闭包内（})(_yt_player); 之前）。找不到注入点则 append（此时 g 可能不在域 → transform 会 catch 返回原 n,安全失败）。 */
  private fun injectNExport(baseJs: String, export: String): String {
    val inj = "; $export"
    val modified = baseJs.replace("})(_yt_player);", "$inj })(_yt_player);")
    return if (modified != baseJs) modified else baseJs + "\n" + inj
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

    /** 从 base.js URL 路径抽 8-hex player hash。对齐 zemer-cipher FunctionNameExtractor.PLAYER_HASH_PATTERNS。 */
    val PLAYER_HASH_PATTERNS = listOf(
      Regex("""/s/player/([a-f0-9]{8})/"""),
      Regex("""/player/([a-f0-9]{8})/"""),
      Regex("""player_[a-z0-9_-]+\.vflset/[^/]+/([a-f0-9]{8})/"""),
    )
  }
}
