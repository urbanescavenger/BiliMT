package com.kirin.mt.core.youtube

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * P11-101 Phase 2b:WebView 内 YouTube s/n decipher——打包 yt-dlp `yt.solver.core`
 * (meriyah AST 结构匹配 + URL 类方法触发 transform,Unlicense),走 [YoutubeJsExecutor] 执行。
 *
 * 为什么不用 [YoutubeNDecryptor](player hash→nClass config,alpha.32 真机证伪)与
 * [YoutubeSDecryptor](旧正则,plasma 后失配):solver 用 AST 结构匹配发现函数(无 config 依赖),
 * 且 `url.set("n", n)` 后**调用原型自有方法触发 transform** 再读回——这正是 alpha.32 旧法
 * 缺失的步骤。yt-dlp 同款机制 2026 生产可用;我们 WebView 是真浏览器环境(WASM/document/
 * location 原生),比 yt-dlp 的 QuickJS/Deno 环境更完整。
 *
 * 协议:assets 依次 eval(meriyah → astring → solver core → driver)→
 * `__ytSolveRun(baseJsUrl, n[], sig[])` → 轮询 `__ytSolveResult`(JSON 文本)。
 */
class YoutubeSolverDecipherer(context: Context, private val executor: YoutubeJsExecutor) {

  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private var loaded = false

  /** 首次使用加载 4 个 asset 脚本(会话级缓存;WebView 重建后 __ytSolveLoaded 丢失会自动重载)。 */
  suspend fun ensureLoaded(): Boolean {
    if (loaded && executor.eval("typeof window.__ytSolveLoaded")?.contains("true") == true) return true
    loaded = false
    for (asset in ASSETS) {
      val js = withContext(Dispatchers.IO) {
        runCatching {
          appContext.assets.open("youtube/$asset").bufferedReader().use { it.readText() }
        }.getOrNull()
      }
      if (js.isNullOrBlank()) {
        Log.w(Tag, "solver asset missing: $asset")
        return false
      }
      val result = runCatching { executor.eval(js) }.getOrElse {
        Log.w(Tag, "solver asset eval failed: $asset ${it.message}")
        return false
      }
      Log.d(Tag, "solver asset loaded: $asset (result=${result?.take(20)})")
    }
    // typeof 布尔值 → "boolean"(首版误写 contains("true") → ready 恒 false → solve 静默返回,
    // 07:55 r1920 真机:4 个 asset 全 loaded 却 solver n=FAILED 无 solver error 日志)
    val ready = executor.eval("typeof window.__ytSolveLoaded")?.contains("boolean") == true
    loaded = ready
    if (ready) Log.i(Tag, "yt solver loaded (meriyah+astring+core+driver)")
    return ready
  }

  /**
   * 解一个 base.js 的 n/s transform。
   * @param playerJsUrl base.js URL(WebView 内同源 fetch,不打 Kotlin 网络)。
   * @return map(challenge → transformed);失败返回 null(上层回退/放弃,留 verdict 日志)。
   */
  suspend fun solve(playerJsUrl: String, nChallenges: List<String>, sigChallenges: List<String>): Map<String, String>? {
    if (!ensureLoaded()) {
      Log.w(Tag, "solver: ensureLoaded failed (assets missing/eval failed)")
      return null
    }
    val nArr = nChallenges.joinToString(",") { JsonPrimitive(it).toString() }
    val sigArr = sigChallenges.joinToString(",") { JsonPrimitive(it).toString() }
    executor.eval("window.__ytSolveResult = null")
    val started = runCatching {
      executor.eval("window.__ytSolveRun(${JsonPrimitive(playerJsUrl)}, [$nArr], [$sigArr])")
    }.getOrNull()
    if (started == null) {
      Log.w(Tag, "solver: __ytSolveRun eval failed")
      return null
    }
    // 轮询结果(对齐 fetchViaWebView poll 模式;base.js fetch+AST 解析首跑数秒,预算放大)
    val deadline = System.currentTimeMillis() + SolveTimeoutMs
    var raw: String? = null
    while (System.currentTimeMillis() < deadline) {
      raw = executor.eval("window.__ytSolveResult")
      if (raw != null && raw != "null") break
      delay(SolvePollIntervalMs)
    }
    if (raw == null || raw == "null") {
      Log.w(Tag, "solver: timeout (${SolveTimeoutMs}ms)")
      return null
    }
    // evaluateJavascript 对字符串结果 JSON 编码(带引号+转义)→ 先解内层
    val inner = runCatching { json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull()
    val out = runCatching { json.parseToJsonElement(inner ?: raw).jsonObject }.getOrNull()
    if (out == null) {
      Log.w(Tag, "solver: result parse failed: ${raw.take(120)}")
      return null
    }
    if (out["type"]?.jsonPrimitive?.contentOrNull == "error") {
      Log.w(Tag, "solver: ${out["error"]?.jsonPrimitive?.contentOrNull}")
      return null
    }
    val responses = (out["responses"] as? List<*>)?.mapNotNull { it as? JsonObject }
    val data = responses?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "result" }
      ?.get("data") as? JsonObject
    if (data == null) {
      Log.w(Tag, "solver: no result data: ${out.keys}")
      return null
    }
    return data.entries.mapNotNull { (k, v) ->
      val transformed = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
      k to transformed
    }.toMap().also { map ->
      Log.i(
        Tag,
        "solver: ok challenges=${map.size} " +
          "nChanged=${nChallenges.firstOrNull()?.let { map[it] != it }} " +
          "sigChanged=${sigChallenges.firstOrNull()?.let { map[it] != it }}",
      )
    }
  }

  /** solver 输出 data 里取回 transformed n。 */
  fun transformedN(solved: Map<String, String>, original: String?): String? =
    original?.let { solved[it] } ?: solved.values.firstOrNull()

  private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

  companion object {
    private const val Tag = "YtSolver"
    private val ASSETS = listOf(
      // 裸名(不含 youtube/ 前缀):ensureLoaded 统一 open("youtube/$asset")。
      // ⚠️ 首版把全路径写进本表再拼前缀 → assets.open("youtube/youtube/…") FileNotFound
      // → "solver asset missing"(07:44 r1919 真机实锤)。
      "meriyah.min.js",
      "astring.min.js",
      "yt.solver.core.js",
      "yt_solver_driver.js",
    )
    private const val SolveTimeoutMs = 30_000L
    private const val SolvePollIntervalMs = 100L
  }
}