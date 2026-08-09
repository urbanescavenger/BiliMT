package com.kirin.mt.core.youtube.newpipe

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamExtractor
import java.io.IOException

/**
 * path C(LibreTube 对齐):NewPipeExtractor fork 作为 YouTube 取流层。
 *
 * - [init] 在 [com.kirin.mt.BiliTvApplication.onCreate] 里 AppContainer 构造之后调用一次,
 *   早于任何 [StreamingService] / [org.schabi.newpipe.extractor.StreamInfo.getInfo] 调用。
 * - [Downloader] 复用 AppContainer.youtubeHttpClient(共享连接池/超时/cookie),把 NewPipe 的
 *   [Request] 翻译成 OkHttp call。UA 先填桌面 Firefox 默认,再用 NewPipe 请求自带 headers 覆盖
 *   (对齐 LibreTube NewPipeDownloaderImpl)。
 * - [setPoTokenProvider] 注入 [BiliTvPoTokenProvider](由 BotGuard + InnerTubeClient 支撑),
 *   使 NewPipe 在 getInfo() 期间铸造的 poToken 与 SABR init 复用的是同一枚(单一 minter)。
 */
object NewPipeHolder {
  private const val TAG = "NewPipeHolder"

  @Volatile private var initialized = false

  /**
   * NewPipe 服务(YouTube)。lazy 以保证 [init] 先执行——直接访问 [service] 前必须已 init。
   * init 未完成就访问会在 NewPipe 内部抛 NPE(无 downloader)。
   */
  val service: StreamingService by lazy {
    check(initialized) { "NewPipeHolder.init() must be called before accessing service" }
    NewPipe.getService(ServiceList.YouTube.serviceId)
  }

  /**
   * 最近一次 [BiliTvPoTokenProvider.getWebClientPoToken] 铸造并缓存的 PoToken 结果。
   * resolver 取流时用它保证 SABR init poToken 与 getInfo() 期间铸造的是同一枚。
   */
  @Volatile private var poTokenProvider: BiliTvPoTokenProvider? = null

  fun cachedPoToken(): BiliTvPoTokenProvider? = poTokenProvider

  fun init(httpClient: OkHttpClient, poTokenProvider: BiliTvPoTokenProvider) {
    if (initialized) {
      Log.w(TAG, "init() already done, skip")
      return
    }
    synchronized(this) {
      if (initialized) return@synchronized
      NewPipe.init(BiliTvNewPipeDownloader(httpClient))
      YoutubeStreamExtractor.setPoTokenProvider(poTokenProvider)
      this.poTokenProvider = poTokenProvider
      initialized = true
      Log.i(TAG, "NewPipe initialized (YouTube service + PoTokenProvider set)")
    }
  }
}

/**
 * NewPipe Downloader -> OkHttp 适配。复用共享 [httpClient](AppContainer.youtubeHttpClient),
 * 不自建裸 client——保持连接池/超时/cookie 与 InnerTube /player 一致。
 */
private class BiliTvNewPipeDownloader(private val httpClient: OkHttpClient) : Downloader() {

  @Throws(IOException::class, ReCaptchaException::class)
  override fun execute(request: Request): Response {
    val httpMethod = request.httpMethod()
    val url = request.url()
    val headers = request.headers()
    val dataToSend = request.dataToSend()

    val requestBuilder = okhttp3.Request.Builder()
      .method(httpMethod, dataToSend?.toRequestBody(null))
      .url(url)
      .addHeader("User-Agent", DEFAULT_UA)

    // 用 NewPipe 请求自带 headers 覆盖默认(对齐 LibreTube:先 remove 再逐个 add)。
    for ((headerKey, headerValues) in headers) {
      requestBuilder.removeHeader(headerKey)
      for (headerValue in headerValues) {
        requestBuilder.addHeader(headerKey, headerValue)
      }
    }

    val response = httpClient.newCall(requestBuilder.build()).execute()

    return when (response.code) {
      429 -> {
        response.close()
        throw ReCaptchaException("reCaptcha Challenge requested", url)
      }

      else -> {
        val responseBodyToReturn = response.body?.string() ?: ""
        Response(
          response.code,
          response.message,
          response.headers.toMultimap(),
          responseBodyToReturn,
          response.request.url.toString(),
        )
      }
    }
  }

  private companion object {
    // 桌面 Firefox UA(与 LibreTube NewPipeDownloaderImpl 一致);NewPipe 各 InnerTube 客户端
    // 请求会自带 UA 覆盖此默认。
    private const val DEFAULT_UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0"
  }
}
