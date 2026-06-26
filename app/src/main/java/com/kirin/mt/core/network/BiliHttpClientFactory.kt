package com.kirin.mt.core.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor

class BiliHttpClientFactory {
  fun createApiClient(): OkHttpClient {
    return baseBuilder()
      .addInterceptor(BrotliInterceptor)
      .addInterceptor { chain ->
        val request = chain.request()
        val omitReferer = request.header(BiliHeaders.OmitRefererHeader) == BiliHeaders.OmitRefererValue
        val builder = request.newBuilder()
          .removeHeader(BiliHeaders.OmitRefererHeader)
          .header("User-Agent", request.header("User-Agent") ?: BiliHeaders.UserAgent)
        if (omitReferer) {
          builder.removeHeader("Referer")
        } else {
          builder.header("Referer", request.header("Referer") ?: BiliHeaders.Referer)
        }
        val enriched = builder.build()
        chain.proceed(enriched)
      }
      .build()
  }

  fun createPlaybackClient(): OkHttpClient {
    return baseBuilder()
      .addInterceptor { chain ->
        val request = chain.request()
        val enriched = request.newBuilder()
          .header("User-Agent", request.header("User-Agent") ?: BiliHeaders.UserAgent)
          .header("Referer", request.header("Referer") ?: BiliHeaders.Referer)
          .header("Origin", request.header("Origin") ?: BiliHeaders.Origin)
          .build()
        chain.proceed(enriched)
      }
      .build()
  }

  fun createDownloadClient(): OkHttpClient {
    return OkHttpClient.Builder()
      .connectTimeout(DownloadConnectTimeoutSeconds, TimeUnit.SECONDS)
      .readTimeout(DownloadReadTimeoutSeconds, TimeUnit.SECONDS)
      .writeTimeout(DownloadWriteTimeoutSeconds, TimeUnit.SECONDS)
      .build()
  }

  private fun baseBuilder(): OkHttpClient.Builder {
    return OkHttpClient.Builder()
      .connectTimeout(NetworkTimeoutSeconds, TimeUnit.SECONDS)
      .readTimeout(NetworkTimeoutSeconds, TimeUnit.SECONDS)
      .writeTimeout(NetworkTimeoutSeconds, TimeUnit.SECONDS)
      // cap 整个调用（含 body 读取）。OkHttp 的 readTimeout 按每次 read 重置，
      // 慢 drip 服务器（每 <15s 吐一个字节）会让 response.body.string() 永不返回、
      // getPlaybackInfo 无限挂死 → PGC 卡在 Loading。callTimeout 兜住整调用。
      .callTimeout(NetworkTimeoutSeconds, TimeUnit.SECONDS)
  }

  private companion object {
    const val NetworkTimeoutSeconds = 15L
    const val DownloadConnectTimeoutSeconds = 30L
    const val DownloadReadTimeoutSeconds = 300L
    const val DownloadWriteTimeoutSeconds = 60L
  }
}
