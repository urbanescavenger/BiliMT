package com.kirin.mt.core.network

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Protocol
import android.util.Log

/**
 * DNS 结果优选(P11-269)。
 *
 * 背景:自建 WebDAV/IPTV 域名在 Cloudflare 后面,解析出多个任播 IP,其中某个 IP 在特定网络
 * 上会「秒 RST」(实测 172.67.153.187:Connection reset,TLS 都没开始;同域名的 104.21.3.225
 * 与 IPv6 正常)。OkHttp 默认只在**单次调用内**按路由顺序重试,Android 的 DNS 缓存又保持同一
 * 顺序 ⇒ 应用侧 3 次阶梯重试全撞同一个坏 IP。
 *
 * 做法:
 * - [lookup] 把被 [markFailed] 拉黑的 IP 排到最后(全部拉黑时仍原样返回,避免整域锁死);
 * - [HealthyDnsEventListener] 在 `connectFailed` 时按实际失败的地址拉黑,`connectionAcquired`
 *   时解除;
 * - 拉黑带 TTL,过期自动失效,不会永久把某个 IP 判死;
 * - 每次 lookup / 失败都打日志(DNS 层此前没有可观测性,这次排查只能靠 curl 手工复现)。
 */
internal object HealthyDns : Dns {
  private const val Tag = "BiliMT:Dns"

  /** 拉黑时长:只用于跨越同一次业务重试(几秒~几分钟),不宜太长。 */
  private const val BadIpTtlMs = 5 * 60 * 1000L

  /** ip -> 拉黑到期时间(毫秒纪元)。key 用 hostAddress,不含 host:网络级不可达与域名无关。 */
  private val badUntil = ConcurrentHashMap<String, Long>()

  override fun lookup(hostname: String): List<InetAddress> {
    val resolved = Dns.SYSTEM.lookup(hostname)
    val now = System.currentTimeMillis()
    val healthy = ArrayList<InetAddress>(resolved.size)
    val unhealthy = ArrayList<InetAddress>(resolved.size)
    resolved.forEach { address ->
      if ((badUntil[address.hostAddress] ?: 0L) > now) unhealthy += address else healthy += address
    }
    // 全部拉黑时仍返回全部:否则域名会被彻底锁死(TTL 内没有任何可用地址)。
    val ordered = if (healthy.isEmpty()) resolved else healthy + unhealthy
    Log.i(
      Tag,
      "lookup $hostname -> ${ordered.joinToString { it.hostAddress }} " +
        "(bad=${unhealthy.joinToString { it.hostAddress }})",
    )
    return ordered
  }

  fun markFailed(address: InetAddress?) {
    val ip = address?.hostAddress ?: return
    badUntil[ip] = System.currentTimeMillis() + BadIpTtlMs
    Log.w(Tag, "ip marked bad (${BadIpTtlMs / 1000}s): $ip")
  }

  fun markSucceeded(address: InetAddress?) {
    val ip = address?.hostAddress ?: return
    if (badUntil.remove(ip) != null) {
      Log.i(Tag, "ip recovered: $ip")
    }
  }
}

/**
 * 把连接失败/成功的实际 IP 喂给 [HealthyDns]。
 * `connectFailed` 同时覆盖 TCP 与 TLS 握手失败(OkHttp 在 connectSocket 内完成 TLS),
 * 正是本次「TCP 通、TLS 前被 RST」的场景。
 */
internal class HealthyDnsEventListener : EventListener() {
  override fun connectFailed(
    call: Call,
    inetSocketAddress: InetSocketAddress,
    proxy: Proxy,
    protocol: Protocol?,
    ioe: IOException,
  ) {
    HealthyDns.markFailed(inetSocketAddress.address)
  }

  override fun connectionAcquired(call: Call, connection: Connection) {
    HealthyDns.markSucceeded(connection.route().socketAddress.address)
  }
}
