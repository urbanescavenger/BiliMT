package com.kirin.mt.core.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * YouTube PO token（Proof-of-Origin）生成。
 *
 * 现状（2025+，bgutils-js v4 实测）：PO token 来自 Google 的 **jnn WebAssembly VM**
 * 反滥用挑战（`POST jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/Create`），
 * challenge 返回 `interpreterJavascript`（VM 运行时）+ `program`（bytecode）+ `globalName`，
 * 需在真实 JS + WebAssembly 环境执行并做运行时完整性校验。隐藏 WebView 能否通过校验
 * 高度不确定，且 Google 频繁改协议。
 *
 * 因此本类定位为**结构化 best-effort**：跟随 bgutils-js(MIT) 的高层流程走一遍，
 * 任一步失败都返回 null，绝不阻塞"无 PO token 直连 /player"的主路径（多数视频仍可播）。
 * 后续若在真机上能跑通/逼近 jnn，再迭代补齐；目前失败静默降级。
 *
 * 网络请求由 Kotlin 发（WebView 跨源 fetch 被 CORS 拦），WebView 只执行 interpreter JS + WASM。
 */
class YoutubeBotGuard(
  private val executor: YoutubeJsExecutor,
  private val httpClient: OkHttpClient,
) {

  /**
   * 尝试生成一个 PO token。失败/不可用返回 null。
   */
  suspend fun generatePoToken(videoId: String, visitorData: String): String? {
    return withContext(Dispatchers.IO) {
      runCatching { mintJnnToken(videoId, visitorData) }
        .onFailure { Log.w(Tag, "PO token failed: ${it.message ?: it::class.simpleName}") }
        .getOrNull()
    }
  }

  /**
   * jnn Waa Create → 执行 interpreter → 取 webPoSignalOutput[0] token。
   * 当前为结构占位：协议细节版本敏感，需真机对照 bgutils-js 源码逐字钉死；
   * 实现不完整时返回 null（调用方降级）。
   */
  private suspend fun mintJnnToken(videoId: String, visitorData: String): String? {
    // TODO(P11-09): 按 bgutils-js src/core/Challenge.ts 的 Waa Create 请求体构建
    //   body + 解析 interpreterJavascript/program/globalName，再在隐藏 WebView 里
    //   eval interpreter + 载入 WASM program 取 webPoSignalOutput 首元素作为 token。
    //   需真机 + 网络实测；当前阶段直接降级，避免生成必然无效的假 token。
    Log.d(Tag, "jnn PO token minting not implemented; degrade to no-token path (videoId=$videoId)")
    return null
  }

  private companion object {
    const val Tag = "YtBotGuard"
  }
}
