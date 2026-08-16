package com.kirin.mt.core.youtube.piped

import android.util.Log
import com.kirin.mt.core.network.BiliHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Piped 后端客户端——`GET {instanceUrl}/streams/{videoId}` 拿 SABR 会话数据。
 *
 * 对齐 LibreTube `PipedMediaServiceRepository` 默认路径(LibreTube `MediaServiceRepository: else ->
 * PipedMediaServiceRepository()`,NewPipe 只是可选本地提取模式)。Piped 实例自带 poToken 请求 YouTube,
 * 回**已 attested 的 WEB-bound** `serverAbrStreamingUrl` + `videoPlaybackUstreamerConfig`,修 NewPipe
 * visionOS 路径拿到未 attested config 致 `RELOAD_PLAYER_RESPONSE` 死循环的问题(见
 * [docs/youtube-hd-playback.md]「alpha.83 更正」段)。
 *
 * 复用 [AppContainer] 的 `youtubeHttpClient`(无 B 站拦截器)与 `json`(`ignoreUnknownKeys=true`,
 * Piped 响应里不用的字段安全忽略)。网络/解析失败返回 null,由 [YoutubePlaybackResolver] 回退 NewPipe。
 */
class PipedClient(
  private val httpClient: OkHttpClient,
  private val json: Json,
) {
  private companion object {
    const val Tag = "YtPiped"
  }

  /**
   * 拉 Piped `/streams/{videoId}`。suspend + IO 调度(网络阻塞)。失败(网络错/非 200/解析错)返回 null,
   * 不抛——调用方据此回退 NewPipe 路径。成功但缺关键字段(sabrUrl/ustreamerConfig)也由调用方判 null。
   */
  suspend fun fetchStreams(videoId: String, instanceUrl: String): PipedStreams? = withContext(Dispatchers.IO) {
    val base = instanceUrl.trimEnd('/')
    val url = "$base/streams/$videoId"
    val request = Request.Builder()
      .url(url)
      .get()
      .header("Accept", "application/json")
      // 浏览器 UA:多数 Piped 实例(pipedapi.kavin.rocks 等)前置 Cloudflare,对 OkHttp 默认
      // `okhttp/4.x` UA 直接 403。带 Chrome UA 才放行(真机日志 alpha.85 实测无 UA → 403 回退)。
      .header("User-Agent", BiliHeaders.UserAgent)
      .build()
    try {
      httpClient.newCall(request).execute().use { response ->
        val code = response.code
        if (code != 200) {
          Log.w(Tag, "fetchStreams $url → HTTP $code (fall back to NewPipe)")
          return@use null
        }
        val body = response.body?.string()
        if (body.isNullOrBlank()) {
          Log.w(Tag, "fetchStreams $url → empty body (fall back to NewPipe)")
          return@use null
        }
        val parsed = runCatching { json.decodeFromString<PipedStreams>(body) }.getOrNull()
        if (parsed == null) {
          Log.w(Tag, "fetchStreams $url → decode failed (fall back to NewPipe)")
          return@use null
        }
        Log.i(
          Tag,
          "fetchStreams $url → OK video=${parsed.videoStreams.size} audio=${parsed.audioStreams.size} " +
            "sabrUrl=${if (parsed.serverAbrStreamingUrl.isNullOrBlank()) "ABSENT" else "present(${parsed.serverAbrStreamingUrl.length}B)"} " +
            "ustreamerCfg=${if (parsed.videoPlaybackUstreamerConfig.isNullOrBlank()) "ABSENT" else "present(${parsed.videoPlaybackUstreamerConfig.length}B)"} " +
            "dur=${parsed.duration}s"
        )
        parsed
      }
    } catch (e: Exception) {
      Log.w(Tag, "fetchStreams $url → ${e.javaClass.simpleName}: ${e.message} (fall back to NewPipe)")
      null
    }
  }
}