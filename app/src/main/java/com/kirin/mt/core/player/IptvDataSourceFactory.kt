package com.kirin.mt.core.player

import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.kirin.mt.core.network.BiliHeaders
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * IPTV 流专用数据源工厂。
 *
 * 关键:强制 IPv4。实测 cf.19961226.xyz/iptv/ 这类源的 m3u8 会 302 重定向到 CDN 节点,
 * 重定向目标按客户端 IP 族选择——客户端走 IPv6 就回 IPv6 字面量节点。真机(如 Sony BRAVIA)
 * 常处于"IPv6 已启用但不可路由"的网络,连不上 IPv6 节点 → HLS 一直 BUFFERING、0 轨道 → 黑屏。
 * 这里用只返回 A 记录(过滤 AAAA)的 Dns 强制客户端走 IPv4,服务端即回 IPv4 节点。
 *
 * 与 B站直播的 [BiliMediaDataSourceFactory] 独立:不套 B站 UA/Referer/Origin 头,
 * 只设一个通用 User-Agent(部分源对无 UA 请求返回 403)。
 */
class IptvDataSourceFactory {
  private val client = OkHttpClient.Builder()
    .dns(Ipv4OnlyDns)
    .eventListener(IptvEventListener)
    .connectTimeout(ConnectTimeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(ReadTimeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(WriteTimeoutSeconds, TimeUnit.SECONDS)
    .build()

  fun create(): DataSource.Factory {
    return OkHttpDataSource.Factory(client).setUserAgent(BiliHeaders.UserAgent)
  }

  private companion object {
    const val ConnectTimeoutSeconds = 15L
    const val ReadTimeoutSeconds = 15L
    const val WriteTimeoutSeconds = 15L
    const val LogTag = "BiliMT:IptvNet"
  }
}

/** 只返回 IPv4(A 记录),过滤 IPv6(AAAA),强制客户端走 IPv4。 */
private object Ipv4OnlyDns : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    val resolved = Dns.SYSTEM.lookup(hostname)
    val filtered = resolved.filter { it is Inet4Address }
    val hasV6 = resolved.any { it is Inet6Address }
    Log.i(
      IptvDataSourceFactoryLogTag,
      "dns lookup $hostname -> ${resolved.joinToString { it.hostAddress }} (filtered v6=$hasV6 kept=${filtered.size})",
    )
    return filtered
  }
}

/** 记录 IPTV 网络请求的 DNS 族 / 连接 / 失败,定位黑屏根因(拉流失败?还是没走到拉流?)。 */
private object IptvEventListener : EventListener() {
  override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
    val hasV6 = inetAddressList.any { it is Inet6Address }
    Log.i(
      IptvDataSourceFactoryLogTag,
      "dnsEnd $domainName v6=$hasV6 addrs=${inetAddressList.joinToString { it.hostAddress }}",
    )
  }

  override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: java.net.Proxy) {
    val addr = inetSocketAddress.address
    val family = when (addr) {
      is Inet6Address -> "v6"
      is Inet4Address -> "v4"
      else -> "?"
    }
    Log.i(IptvDataSourceFactoryLogTag, "connectStart ${inetSocketAddress.hostString}:${inetSocketAddress.port} family=$family")
  }

  override fun connectFailed(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: java.net.Proxy,
    protocol: Protocol?,
    ioe: IOException,
  ) {
    Log.e(
      IptvDataSourceFactoryLogTag,
      "connectFailed ${inetSocketAddress.hostString}:${inetSocketAddress.port} proto=$protocol ${ioe.javaClass.simpleName}: ${ioe.message}",
    )
  }

  override fun responseFailed(call: Call, ioe: IOException) {
    Log.e(
      IptvDataSourceFactoryLogTag,
      "responseFailed ${call.request().url} ${ioe.javaClass.simpleName}: ${ioe.message}",
    )
  }

  override fun responseHeadersStart(call: Call) {
    Log.i(IptvDataSourceFactoryLogTag, "response ${call.request().url}")
  }

  override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
    Log.i(IptvDataSourceFactoryLogTag, "responseEnd code=${response.code} url=${call.request().url} loc=${response.header("Location")}")
  }
}

private const val IptvDataSourceFactoryLogTag = "BiliMT:IptvNet"
