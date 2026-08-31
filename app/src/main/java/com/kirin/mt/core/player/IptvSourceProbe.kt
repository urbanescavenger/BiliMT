package com.kirin.mt.core.player

import android.util.Log
import com.kirin.mt.core.network.BiliHeaders
import com.kirin.mt.core.network.IptvChannel
import com.kirin.mt.core.network.IptvRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * 会话级 IPTV 源判活结果存储(app 级单例,挂 AppContainer,启动建、进程亡)。
 * 判活一次本次启动全程复用:列表页重排 urls、播放器频道侧栏、缩略图截帧换源都读它。
 *
 * 结果语义(`url → alive`):
 * - `true` = 已证活(廉价 m3u8 探活 2xx 且内容像 m3u8,或截帧真出了画面——后者是铁证)。
 * - `false` = 廉价探活判死(m3u8 拉不到/回的不是 m3u8)。**截帧失败绝不写 false**:
 *   22s 超时的慢源/网络抖动会假死,只有"m3u8 都拉不到"这种硬失败才敢标死(见
 *   docs/iptv-feasibility.md 三期「无缩略图 ≠ 死源」)。
 * - 无条目 = 未探。重排时未探源保持原相对序,夹在活源与死源之间。
 */
class IptvSourceProbeStore {
  private val _results = MutableStateFlow<Map<String, Boolean>>(emptyMap())

  /** 全量判活结果(流式增长:启动扫描/截帧回写都会推进它,收集方可据此增量重排)。 */
  val results: StateFlow<Map<String, Boolean>> = _results.asStateFlow()

  /** 廉价探活已判死(硬失败)。截帧换源时跳过,不浪费 22s。 */
  fun isDead(url: String): Boolean = _results.value[url] == false

  fun markAlive(url: String) {
    if (url.isBlank()) return
    _results.update { results ->
      if (results[url] == true) results else results + (url to true)
    }
  }

  fun markDead(url: String) {
    if (url.isBlank()) return
    _results.update { results ->
      if (results[url] == false) results else results + (url to false)
    }
  }

  /**
   * urls 活源前置重排(稳定分区,不动组内相对序):
   * `[已证活] + [未探] + [已判死]`。重排后 urls[0] 即最佳源,播放器 selectedQn=0
   * 直接播活源;断流自动 `selectedQn++` 切的也是次优源而非又一个死源。
   * 单源/无判活数据时原样返回(分区恒等)。
   */
  fun reorderUrls(urls: List<String>): List<String> {
    if (urls.size < 2) return urls
    val current = _results.value
    val alive = urls.filter { current[it] == true }
    if (alive.isEmpty()) {
      // 无活源证据:仅把已判死的沉底(未探的保持在前,别乱动未探源的相对序)。
      val dead = urls.filter { current[it] == false }
      if (dead.isEmpty()) return urls
      return urls.filter { current[it] != false } + dead
    }
    val unknown = urls.filter { !current.containsKey(it) }
    val dead = urls.filter { current[it] == false }
    return alive + unknown + dead
  }
}

/**
 * 启动后台廉价判活扫描(一次性,见 [sweepOnce])。对多源频道的 urls 顺序发 GET 拉
 * 源 m3u8 文本(~10-100 KB/次),首个成功即止——urls[0] 活就只花 1 次请求,死了才
 * 补试下一个(单频道上限 [MaxUrlsPerChannel],全死频道最多 3×8s)。
 *
 * 只扫**多源频道**:单源频道没有可切的源,探了也白探,砍掉一半流量。
 * 校验不止 2xx:peek 前 2 KB 必须像 m3u8(`#EXTM3U`/`#EXT-X`/`#EXTINF`)——部分源
 * 200 回 HTML 错误页,只看响应码会误判活。
 *
 * 与截帧探活([IptvThumbnailCapturerEgl])的分工:本类便宜但浅(m3u8 能拉 ≠ ts 段
 * 能播),截帧贵但深(真出画面 = 铁证可播)。列表重排吃本类结果打底,截帧换源做兜底。
 */
class IptvSourceProber(
  private val repository: IptvRepository,
  private val store: IptvSourceProbeStore,
) {
  // 与拉流同栈(IPv4-only DNS + 裸 IP 明文放行 + 事件日志)+ 短超时,见工厂注释。
  private val client = IptvDataSourceFactory().createProbeClient()

  /**
   * 扫一遍全列表。fire-and-forget:未配置源时 getChannels 返回空,自然退出。
   * 并发 [MaxConcurrency](m3u8 GET 是 KB 级,2 路并发不与用户播放抢带宽)。
   */
  suspend fun sweepOnce() = coroutineScope {
    val channels = runCatching { repository.getChannels() }.getOrDefault(emptyList())
    val multiSource = channels.filter { it.urls.size >= 2 }
    if (multiSource.isEmpty()) {
      Log.i(LogTag, "sweep: no multi-source channels (total=${channels.size}), skip")
      return@coroutineScope
    }
    val semaphore = Semaphore(MaxConcurrency)
    val started = System.currentTimeMillis()
    multiSource.map { channel ->
      async {
        semaphore.withPermit { probeChannel(channel) }
      }
    }.awaitAll()
    Log.i(
      LogTag,
      "sweep done: channels=${multiSource.size} aliveUrls=${store.results.value.count { it.value }} " +
        "deadUrls=${store.results.value.count { !it.value }} " +
        "${System.currentTimeMillis() - started}ms",
    )
  }

  /** 单频道:顺序探 urls,已证活即止(不浪费流量探余下镜像),全探完都不活则全标死。 */
  private suspend fun probeChannel(channel: IptvChannel) {
    val current = store.results.value
    if (channel.urls.any { current[it] == true }) return // 已有活源证据,不用再探
    for (url in channel.urls.take(MaxUrlsPerChannel)) {
      when (store.results.value[url]) {
        true -> return
        false -> continue // 已判死,跳到下一个镜像
        else -> Unit
      }
      if (probeM3u8(url)) {
        store.markAlive(url)
        Log.i(LogTag, "probe alive: ${channel.name} -> ${redactUrl(url)}")
        return
      }
      store.markDead(url)
      Log.w(LogTag, "probe dead: ${channel.name} -> ${redactUrl(url)}")
    }
  }

  /** 日志里的 URL 裁剪:只留 host+path,query 里可能带 key/token 不宜全文落日志。 */
  private fun redactUrl(url: String): String {
    val httpUrl = url.toHttpUrlOrNull() ?: return url
    return "${httpUrl.scheme}://${httpUrl.host}${httpUrl.encodedPath}"
  }

  /** GET 拉 m3u8 文本:2xx 且前 2 KB 像 m3u8 才算活。阻塞 execute() 切 IO。 */
  private suspend fun probeM3u8(url: String): Boolean = withContext(Dispatchers.IO) {
    try {
      val request = Request.Builder()
        .url(url)
        .header("User-Agent", BiliHeaders.UserAgent)
        .get()
        .build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@withContext false
        // peek 不消费 body,最多 2 KB:有些 m3u8 URL 实际是无穷 ts 流,读全 body 会拖流量。
        val head = response.peekBody(PeekBytes).string()
        head.contains("#EXTM3U") || head.contains("#EXT-X") || head.contains("#EXTINF")
      }
    } catch (error: kotlinx.coroutines.CancellationException) {
      throw error
    } catch (error: Exception) {
      Log.d(LogTag, "probeM3u8 $url failed: ${error.javaClass.simpleName}: ${error.message}")
      false
    }
  }

  private companion object {
    /** 并发上限:廉价探活也要限,2 路不与用户播放/其它流量抢带宽。 */
    const val MaxConcurrency = 2
    /** 单频道补试上限:全死频道最多烧 3×8s,不无限拖。 */
    const val MaxUrlsPerChannel = 3
    const val PeekBytes = 2048L
    const val LogTag = "BiliMT:IptvProbe"
  }
}