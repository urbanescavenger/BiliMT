package com.kirin.mt.core.network

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response

/**
 * P11-275:YouTube 链路**临时诊断**(排查完删除)。
 *
 * 起因:`logs_live_20261026_213735.log`(debug r2247)三次起播全失败,但日志里只有
 * `NewPipe getInfo failed: reCaptcha Challenge requested`(HTTP 429)与
 * `Too many follow-up requests: 21`(重定向环),**看不到**解析到了哪个 IP、实际连到哪个地址、
 * 429 打在哪个端点 —— 无法区分「DNS 污染 / 出口节点 / 请求本身被拦」。
 *
 * 这里补的正是这一层可观测性:
 * - [dns]:每次 `lookup` 打印全部 A/AAAA 答案(判污染:是否落在 Google 段、是否漂移);
 * - [listener]:connectStart/connectEnd/connectFailed 打印 host → 实际 IP:port(判 v4/v6、判坏 IP),
 *   responseHeadersEnd 打印 code + method + host + path(判 429/30x 打在哪个端点)。
 *
 * **不打 query、请求头、响应体**:YouTube URL 的 query 可能带签名/token,不能落日志。
 */
internal object YoutubeNetDiag {
  private const val Tag = "BiliMT:YtNet"

  val dns: Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
      val startedAt = System.currentTimeMillis()
      val addresses = try {
        Dns.SYSTEM.lookup(hostname)
      } catch (e: UnknownHostException) {
        Log.w(Tag, "dns fail $hostname (${System.currentTimeMillis() - startedAt}ms): ${e.message}")
        throw e
      }
      Log.i(
        Tag,
        "dns $hostname -> ${addresses.joinToString(",") { it.hostAddress }} " +
          "(${System.currentTimeMillis() - startedAt}ms)",
      )
      return addresses
    }
  }

  val listener: EventListener = object : EventListener() {
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
      Log.i(
        Tag,
        "connect ${call.request().url.host} -> ${inetSocketAddress.address?.hostAddress}" +
          ":${inetSocketAddress.port} proxy=${if (proxy.type() == Proxy.Type.DIRECT) "direct" else proxy.toString()}",
      )
    }

    override fun connectEnd(
      call: Call,
      inetSocketAddress: InetSocketAddress,
      proxy: Proxy,
      protocol: Protocol?,
    ) {
      Log.i(
        Tag,
        "connected ${call.request().url.host} -> ${inetSocketAddress.address?.hostAddress}" +
          ":${inetSocketAddress.port} proto=$protocol",
      )
    }

    override fun connectFailed(
      call: Call,
      inetSocketAddress: InetSocketAddress,
      proxy: Proxy,
      protocol: Protocol?,
      ioe: IOException,
    ) {
      Log.w(
        Tag,
        "connect failed ${call.request().url.host} -> ${inetSocketAddress.address?.hostAddress}" +
          ":${inetSocketAddress.port}: ${ioe.javaClass.simpleName}: ${ioe.message}",
      )
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
      val request = response.request
      Log.i(Tag, "http ${response.code} ${request.method} ${request.url.host}${request.url.encodedPath}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
      val url = call.request().url
      Log.w(Tag, "http failed ${url.host}${url.encodedPath}: ${ioe.javaClass.simpleName}: ${ioe.message}")
    }
  }
}
