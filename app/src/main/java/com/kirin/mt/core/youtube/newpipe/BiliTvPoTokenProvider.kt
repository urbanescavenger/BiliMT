package com.kirin.mt.core.youtube.newpipe

import android.util.Log
import com.kirin.mt.core.youtube.InnerTubeClient
import com.kirin.mt.core.youtube.YoutubeBotGuard
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

/**
 * path C:把 BiliTV 已有的 [YoutubeBotGuard.generatePoToken](128B BotGuard websafe 串)
 * + [InnerTubeClient.currentVisitorData] 适配成 NewPipeExtractor 的 [PoTokenProvider]。
 *
 * 对齐 LibreTube `PoTokenGenerator`:
 *  - `getWebClientPoToken` 返回 [PoTokenResult](visitorData, poToken, poToken)——三槽同 token
 *    (LibreTube PoTokenGenerator.kt:107),playerRequestPoToken == streamingDataPoToken。
 *  - 铸造结果缓存([cached]),供 [com.kirin.mt.core.youtube.YoutubePlaybackResolver] 取用,
 *    保证 SABR init poToken 与 getInfo() 期间 NewPipe 铸造的是**同一枚**(单一 minter,
 *    根除跨 minter status=3)。
 *  - 其余客户端(embed/android/ios)返回 null——仅 WEB 客户端走 SABR,对齐 LibreTube。
 *
 * NewPipe 在 [org.schabi.newpipe.extractor.StreamInfo.getInfo] 内**同步**调用此方法。
 * BiliTV 的 resolve() 在 Dispatchers.IO 调 getInfo,故 [runBlocking] 阻塞的是 IO 线程;
 * generatePoToken 内部自管线程切换(WebView 走主线程),resume 回 IO 后返回。
 */
class BiliTvPoTokenProvider(
  private val botGuard: YoutubeBotGuard,
  private val innerTubeClient: InnerTubeClient,
) : PoTokenProvider {
  private val tag = "BiliTvPoToken"

  private val lock = Any()

  @Volatile private var cached: PoTokenResult? = null

  /** resolver 取流时读:与 getInfo() 期间铸造的同一枚 poToken。 */
  fun cached(): PoTokenResult? = cached

  override fun getWebClientPoToken(videoId: String): PoTokenResult? {
    val visitorData = innerTubeClient.currentVisitorData()
    val poToken = runBlocking { botGuard.generatePoToken(videoId) }
    if (poToken.isNullOrEmpty()) {
      Log.w(tag, "getWebClientPoToken: BotGuard returned null/empty poToken for $videoId")
      return null
    }
    val result = PoTokenResult(visitorData, poToken, poToken)
    synchronized(lock) { cached = result }
    Log.i(tag, "getWebClientPoToken: videoId=$videoId poToken=${poToken.length}B visitorData=${visitorData.length}B (cached)")
    return result
  }

  override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult? = null

  override fun getAndroidClientPoToken(videoId: String?): PoTokenResult? = null

  override fun getIosClientPoToken(videoId: String?): PoTokenResult? = null
}
