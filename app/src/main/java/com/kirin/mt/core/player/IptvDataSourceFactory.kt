package com.kirin.mt.core.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.kirin.mt.core.network.BiliHeaders
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

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
  }
}

/** 只返回 IPv4(A 记录),过滤 IPv6(AAAA),强制客户端走 IPv4。 */
private object Ipv4OnlyDns : Dns {
  override fun lookup(hostname: String): List<InetAddress> =
    Dns.SYSTEM.lookup(hostname).filter { it is Inet4Address }
}
