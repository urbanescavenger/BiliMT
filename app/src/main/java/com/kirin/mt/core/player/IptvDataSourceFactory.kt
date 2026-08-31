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
import okhttp3.internal.platform.Platform

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

  /**
   * 廉价 m3u8 探活用 client(见 [IptvSourceProber]):与拉流同栈(IPv4-only DNS、
   * 裸 IP 明文放行、请求事件日志——否则裸 IP http 源探活必假死),仅把超时压短
   * (探活只拉 m3u8 文本,失败要快速回吐,不留 15s)。
   */
  fun createProbeClient(): OkHttpClient = client.newBuilder()
    .connectTimeout(ProbeConnectTimeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(ProbeReadTimeoutSeconds, TimeUnit.SECONDS)
    .build()

  private companion object {
    const val ConnectTimeoutSeconds = 15L
    const val ReadTimeoutSeconds = 15L
    const val WriteTimeoutSeconds = 15L
    const val ProbeConnectTimeoutSeconds = 10L
    const val ProbeReadTimeoutSeconds = 8L
    const val LogTag = "BiliMT:IptvNet"
  }
}

/**
 * 允许明文 HTTP 的 IPTV host 集合(动态注册)。
 *
 * IPTV 流 URL 是 http://(明文),而 app targetSdk=36 且 manifest 未开 usesCleartextTraffic,
 * Android 9+ 默认禁明文 → OkHttp 在 connect() 开头、connectStart 之前抛
 * "CLEARTEXT communication not permitted" → 黑屏。这里动态放行 IPTV 源里的 host,
 * 其余 host 仍走系统 NetworkSecurityPolicy(不影响 B站 https 流量)。
 */
object IptvCleartextHosts {
  private val hosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  fun add(host: String) {
    hosts.add(host)
  }
  fun contains(host: String): Boolean = hosts.contains(host)
}

/**
 * 只对已注册的 IPTV host 放行明文,其余委托原始 AndroidPlatform(保留系统 NetworkSecurityPolicy
 * 与 Android 的 TLS/证书处理,不影响 B站 https)。
 *
 * 不能直接子类化 AndroidPlatform(它是 final),故子类化基类 Platform 并把所有方法委托给
 * 替换前捕获的原始 Platform 实例,仅重写 isCleartextTrafficPermitted。
 */
class IptvCleartextPlatform(private val delegate: Platform) : Platform() {
  override fun isCleartextTrafficPermitted(hostname: String): Boolean =
    // ① 动态注册的 IPTV host(DNS 解析/302 重定向时注册)→ 放行;
    // ② 裸 IP 字面量 → 直接放行。IPTV CDN 节点多是 "IP:port" 直连(如 tsfile/gitv/cntv 的
    //    223.110.x.x / 61.x / 183.x),OkHttp 对 IP 字面量不查 Dns(不走 Ipv4OnlyDns.lookup,
    //    永不触发注册)→ 不在此放行则 connect 前抛 CLEARTEXT 错误 → IPTV 黑屏/播放失败。
    if (IptvCleartextHosts.contains(hostname) || isLiteralIp(hostname)) true
    else delegate.isCleartextTrafficPermitted(hostname)

  override fun newSSLContext(): javax.net.ssl.SSLContext = delegate.newSSLContext()
  override fun platformTrustManager(): javax.net.ssl.X509TrustManager = delegate.platformTrustManager()
  override fun trustManager(sslSocketFactory: javax.net.ssl.SSLSocketFactory): javax.net.ssl.X509TrustManager? =
    delegate.trustManager(sslSocketFactory)
  override fun configureTlsExtensions(
    sslSocket: javax.net.ssl.SSLSocket,
    hostname: String?,
    protocols: List<Protocol>,
  ) = delegate.configureTlsExtensions(sslSocket, hostname, protocols)
  override fun afterHandshake(sslSocket: javax.net.ssl.SSLSocket) = delegate.afterHandshake(sslSocket)
  override fun getSelectedProtocol(sslSocket: javax.net.ssl.SSLSocket): String? =
    delegate.getSelectedProtocol(sslSocket)
  override fun connectSocket(socket: java.net.Socket, address: java.net.InetSocketAddress, connectTimeout: Int) =
    delegate.connectSocket(socket, address, connectTimeout)
  override fun log(message: String, level: Int, t: Throwable?) = delegate.log(message, level, t)
  override fun getStackTraceForCloseable(closer: String): Any? = delegate.getStackTraceForCloseable(closer)
  override fun logCloseableLeak(message: String, stackTrace: Any?) = delegate.logCloseableLeak(message, stackTrace)
  override fun buildCertificateChainCleaner(trustManager: javax.net.ssl.X509TrustManager): okhttp3.internal.tls.CertificateChainCleaner =
    delegate.buildCertificateChainCleaner(trustManager)
  override fun buildTrustRootIndex(trustManager: javax.net.ssl.X509TrustManager): okhttp3.internal.tls.TrustRootIndex =
    delegate.buildTrustRootIndex(trustManager)
  override fun newSslSocketFactory(trustManager: javax.net.ssl.X509TrustManager): javax.net.ssl.SSLSocketFactory =
    delegate.newSslSocketFactory(trustManager)
}

/** 是否为裸 IP 字面量(IPv4 或 IPv6)。IPTV CDN 节点常是 "IP:port" 直连,须放行明文。 */
private fun isLiteralIp(hostname: String): Boolean {
  val host = hostname.removePrefix("[").removeSuffix("]")
  if (host.isEmpty()) return false
  if (host.contains(':')) {
    // IPv6(含 IPv4-mapped),去掉 zone id 等仅凭字符集粗判即可(纯 IPv4 已在上分支处理)。
    return Regex("""^[0-9a-fA-F:.]+$""").matches(host)
  }
  // IPv4:4 段点分十进制,每段 0-255。
  val parts = host.split('.')
  return parts.size == 4 && parts.all { part ->
    part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && (part.toIntOrNull() ?: -1) in 0..255
  }
}

/** 只返回 IPv4(A 记录),过滤 IPv6(AAAA),强制客户端走 IPv4。 */
private object Ipv4OnlyDns : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    // 注册明文放行:DNS 发生在 findConnection(先于 connect() 的明文检查),注册后即通过。
    // 302 重定向到新 host 时,新 host 的 DNS 也会注册,天然覆盖。
    IptvCleartextHosts.add(hostname)
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
    val loc = response.header("Location")
    Log.i(IptvDataSourceFactoryLogTag, "responseEnd code=${response.code} url=${call.request().url} loc=$loc")
    // 302 重定向目标常是 IP 字面量(如 http://223.110.246.83:80/...),IP 字面量不走 DNS,
    // 不会触发 Ipv4OnlyDns.lookup 注册 → 明文检查仍拦。这里在拿到 302 时把 Location host
    // 注册进明文放行集合,OkHttp 随后 follow 重定向时即通过。
    if (response.code in 300..399 && loc != null) {
      val target = runCatching { call.request().url.resolve(loc)?.host }.getOrNull()
      if (target != null) IptvCleartextHosts.add(target)
    }
  }
}

private const val IptvDataSourceFactoryLogTag = "BiliMT:IptvNet"
