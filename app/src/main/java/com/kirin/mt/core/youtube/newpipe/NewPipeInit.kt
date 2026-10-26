package com.kirin.mt.core.youtube.newpipe

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.io.IOException

/**
 * path C(LibreTube 对齐):NewPipeExtractor fork 作为 YouTube 取流层。
 *
 * - [init] 在 [com.kirin.mt.BiliTvApplication.onCreate] 里 AppContainer 构造之后调用一次,
 *   早于任何 [StreamingService] / [org.schabi.newpipe.extractor.StreamInfo.getInfo] 调用。
 * - [Downloader] 复用 AppContainer.youtubeHttpClient(共享连接池/超时/cookie),把 NewPipe 的
 *   [Request] 翻译成 OkHttp call。UA 先填桌面 Firefox 默认,再用 NewPipe 请求自带 headers 覆盖
 *   (对齐 LibreTube NewPipeDownloaderImpl)。
 * - [setPoTokenProvider] 注入 [NewPipePoTokenGenerator](移植 LibreTube 的 NewPipe 原生 PoTokenGenerator),
 *   使 NewPipe 在 getInfo() 期间铸造的 poToken 与 SABR init 复用的是同一枚(单一 NewPipe minter,
 *   contentBinding 正确 → 修 visionOS SABR RELOAD 死循环,alpha.80)。
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
   * 最近一次 [NewPipePoTokenGenerator.getWebClientPoToken] 铸造并缓存的 PoToken 结果。
   * resolver 取流时用它保证 SABR init poToken 与 getInfo() 期间铸造的是同一枚。
   */
  @Volatile private var poTokenProvider: NewPipePoTokenGenerator? = null

  fun cachedPoToken(): NewPipePoTokenGenerator? = poTokenProvider

  fun init(httpClient: OkHttpClient, poTokenProvider: NewPipePoTokenGenerator) {
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

    // P11-275:临时诊断(排查完删除)。NewPipe 的 429 / 重定向环此前看不到端点与跳转链;
    // 只打 host + path,不打 query(可能带签名/token)、请求头与响应体。
    val redirects = redirectCount(response)
    if (response.code !in 200..299 || redirects > 0) {
      val from = url.toHttpUrlOrNull()?.let { "${it.host}${it.encodedPath}" } ?: "-"
      val to = "${response.request.url.host}${response.request.url.encodedPath}"
      Log.w(
        TAG,
        "NewPipe http ${response.code} $httpMethod $from -> $to redirects=$redirects " +
          "retryAfter=${response.header("Retry-After")}",
      )
    }

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
    private const val TAG = "BiliMT:YtNet"

    // 桌面 Firefox UA(与 LibreTube NewPipeDownloaderImpl 一致);NewPipe 各 InnerTube 客户端
    // 请求会自带 UA 覆盖此默认。
    private const val DEFAULT_UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0"
  }
}

/** P11-275:OkHttp 跟随的重定向次数(>0 说明服务端要求跳转;≥20 会抛 Too many follow-up requests)。 */
private fun redirectCount(response: okhttp3.Response): Int {
  var count = 0
  var prior = response.priorResponse
  while (prior != null) {
    count++
    prior = prior.priorResponse
  }
  return count
}
