package com.kirin.mt.core.network

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import android.util.Log

/**
 * DNS 结果优选(P11-269)+ 系统答案缺项时的 DoH 补全(P11-304)。
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
 *
 * P11-304 补全:系统 DNS 有时只回一个地址,而且正好是被阻断的那个 —— 此时「排到最后」无从下手
 * (全部拉黑仍原样返回)。真机 `logs_live_20261029_230839.log` 10-28 22:59–23:00:
 * `lookup cf.19961226.xyz -> 172.67.153.187 (bad=172.67.153.187)` 反复出现,ping 三档
 * 9.0s / 15.0s / 1.2s 全失败;而同一时刻在 PC 上查到该域名还有 104.21.3.225 与 2606:4700:… 两条
 * 可用备选 ⇒ 是**系统解析结果缺项**,不是域名只有坏 IP。故在系统答案里没有任何健康地址时,用
 * DoH(阿里 223.5.5.5,国内可达,IP 字面量端点免除自举解析)补查 A/AAAA 并合并进候选集。
 */
internal object HealthyDns : Dns {
  private const val Tag = "BiliMT:Dns"

  /** 拉黑时长:只用于跨越同一次业务重试(几秒~几分钟),不宜太长。 */
  private const val BadIpTtlMs = 5 * 60 * 1000L

  /** DoH 补全结果的缓存时长,与 Cloudflare 的 A/AAAA TTL(300s)对齐。 */
  private const val DohCacheTtlMs = 5 * 60 * 1000L

  /**
   * DoH 端点用 IP 字面量:补全发生在「系统 DNS 已经不可信」的失败路径上,不能再依赖系统解析
   * dns.alidns.com。该端点支持 JSON 格式(`application/dns-json`),返回码 0 + Answer 数组。
   */
  private const val DohEndpoint = "https://223.5.5.5/resolve"

  private val dohClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

  /** ip -> 拉黑到期时间(毫秒纪元)。key 用 hostAddress,不含 host:网络级不可达与域名无关。 */
  private val badUntil = ConcurrentHashMap<String, Long>()

  private val dohCache = ConcurrentHashMap<String, CachedAddresses>()

  private class CachedAddresses(val addresses: List<InetAddress>, val until: Long)

  override fun lookup(hostname: String): List<InetAddress> {
    val resolved = Dns.SYSTEM.lookup(hostname)
    val now = System.currentTimeMillis()
    val candidates = ArrayList<InetAddress>(resolved.size + 2)
    val seen = HashSet<String>(resolved.size + 2)
    fun addAll(addresses: List<InetAddress>) {
      addresses.forEach { address ->
        val ip = address.hostAddress ?: return@forEach
        if (seen.add(ip)) candidates += address
      }
    }
    addAll(resolved)
    // 只有「系统答案里一个健康地址都没有」时才补查:正常解析不付这份网络开销。
    if (candidates.none { !isBad(it, now) }) {
      addAll(dohSupplement(hostname, seen, now))
    }
    val healthy = candidates.filter { !isBad(it, now) }
    val unhealthy = candidates.filter { isBad(it, now) }
    // 全部拉黑时仍返回全部:否则域名会被彻底锁死(TTL 内没有任何可用地址)。
    val ordered = if (healthy.isEmpty()) candidates else healthy + unhealthy
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

  private fun isBad(address: InetAddress, now: Long): Boolean =
    (badUntil[address.hostAddress] ?: 0L) > now

  /**
   * 系统答案全坏时的 DoH 补全。先查 A;若 A 没能给出任何系统答案之外的新地址,再查 AAAA
   * (没有 IPv6 出口的设备查了也用不上,省一次往返)。
   */
  private fun dohSupplement(
    hostname: String,
    knownIps: Set<String>,
    now: Long,
  ): List<InetAddress> {
    dohCache[hostname]?.let { cached ->
      if (cached.until > now) return cached.addresses
    }
    val addresses = ArrayList<InetAddress>(4)
    queryDoh(hostname, "A").let { addresses += it }
    if (addresses.none { it.hostAddress !in knownIps }) {
      queryDoh(hostname, "AAAA").let { addresses += it }
    }
    if (addresses.isEmpty()) {
      Log.w(Tag, "doh supplement $hostname -> empty")
      return emptyList()
    }
    dohCache[hostname] = CachedAddresses(addresses, now + DohCacheTtlMs)
    Log.i(Tag, "doh supplement $hostname -> ${addresses.joinToString { it.hostAddress }}")
    return addresses
  }

  private fun queryDoh(hostname: String, type: String): List<InetAddress> {
    val request = Request.Builder()
      .url("$DohEndpoint?name=$hostname&type=$type")
      .header("Accept", "application/dns-json")
      .build()
    return try {
      dohClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          Log.w(Tag, "doh $type $hostname failed: HTTP ${response.code}")
          return emptyList()
        }
        val body = response.body?.string().orEmpty()
        val answer = JSONObject(body).optJSONArray("Answer")
        val result = ArrayList<InetAddress>(answer?.length() ?: 0)
        for (i in 0 until (answer?.length() ?: 0)) {
          val item = answer?.optJSONObject(i) ?: continue
          // 只取 A(1)/AAAA(28);type=5 的 CNAME data 是主机名,getByName 会再走一次系统 DNS。
          val recordType = item.optInt("type")
          if (recordType != 1 && recordType != 28) continue
          val data = item.optString("data")
          if (data.isEmpty()) continue
          runCatching { InetAddress.getByName(data) }.getOrNull()?.let { result += it }
        }
        result
      }
    } catch (e: Exception) {
      Log.w(Tag, "doh $type $hostname failed: ${e.javaClass.simpleName}: ${e.message}")
      emptyList()
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
