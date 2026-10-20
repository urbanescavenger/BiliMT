package com.kirin.mt.core.webdav

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebDAV 协议封装:PUT(上传)/ GET(下载)/ MKCOL(建目录),Basic auth。
 * 用 OkHttp 原生动词实现,不引入第三方 WebDAV 库。
 *
 * 复用 [com.kirin.mt.core.network.BiliHttpClientFactory.createDownloadClient] 的长超时客户端
 * (connect 30s / read 300s / write 60s),适合大文件上传下载。
 */
class WebDavRepository(private val client: OkHttpClient) {
  private val jsonMediaType = "application/json".toMediaType()

  /**
   * 连通性探测用短超时客户端(经 newBuilder 派生,共享连接池):connect/read/write 各 8s。
   * 长超时客户端会让「ping 不可达服务器」最多干等 30s 才失败,与快速判失败诉求相悖;
   * 8s 对局域网/公网 WebDAV 都足够宽松,又不至于让失败等待体感过长。
   */
  private val probeClient: OkHttpClient = client.newBuilder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .writeTimeout(8, TimeUnit.SECONDS)
    .build()

  /**
   * P11-174:第二次探测用的宽松 client(connect/read/write 各 15s)。真机反馈「连不上备份服务器,
   * 连试很多次才成功」——8s 是硬门,首包/握手偶发超过 8s 就整轮判死,而用户手动重试又会碰上
   * 「刚好够快」的一次。故首次失败后换 15s 再试一次(见 [ping]),把「偶发慢首包」和「真不通」分开。
   */
  private val probeClientLong: OkHttpClient = client.newBuilder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .build()

  /**
   * P11-176:第三次探测用的最宽 client(各 25s)。真机 `logs_live_20260922_234839`(cf.19961226.xyz):
   * 空闲后第一笔 **8.9s 超时**、第二笔 **15.0s 超时**,而紧接着的第三次调用 6.7s 就成、第四次 1.3s
   * —— 是边缘/源站冷启(握手 + 边缘回源),不是网络不通。故探测给到三档 8s/15s/25s:一次用户操作
   * 内足以把冷启熬过去,而不是让用户反复按键撞运气(「连试很多次才成功」)。热连接下第一档即中(1.3s)。
   */
  private val probeClientSlow: OkHttpClient = client.newBuilder()
    .connectTimeout(25, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .writeTimeout(25, TimeUnit.SECONDS)
    .build()

  /** 探测档位:client + 该档超时秒数(与 client 配置保持一致,仅用于日志)。 */
  private val probeLadder: List<Pair<OkHttpClient, Int>> = listOf(
    probeClient to 8,
    probeClientLong to 15,
    probeClientSlow to 25,
  )

  /**
   * P11-256:传输层的瞬时失败判据 —— 只有**网络异常**(超时/断连/重置,由调用方在 catch 里判)和
   * **5xx**(Cloudflare 边缘/源站冷启时常见的 502/503/504)值得重试;其余 HTTP 码(401/403/404/409…)
   * 是服务器的明确回答,重试纯属浪费用户时间。
   */
  private fun isTransientTransferCode(code: Int): Boolean = code in 500..599

  /**
   * PUT 上传文件内容,2xx 视为成功。
   *
   * **P11-256:走 [probeLadder](8s→15s→25s)重试。** 此前只有 [ping] 有阶梯,`put`/`get` 是单发 ——
   * 于是「边缘冷启 + 大 body」一次超时就整轮备份失败,用户只能自己再按一次。真机
   * `logs_live_20261012_235118`(debug r2201,cf.19961226.xyz):**同一份 5MB 实时日志 23:49:51 挂 32s、
   * 23:51:03 挂 36s 失败,而边缘热起来后同样的包 12s 就 201 成功**(`bytes=5050714 elapsed=12090ms`)
   * ⇒ 用户视角就是「备份多次才成功」。现在一次用户操作内自己把冷启熬过去,与 [ping] 同一套理由与档位。
   *
   * 失败语义(P11-256 起统一):**不再向上抛 IOException** —— 阶梯跑完仍失败就返回 false,由调用方
   * ([WebDavBackupService] 各入口)生成带上下文的失败消息(如「日志上传失败:logs_live_xxx.log」),
   * 判读细节(第几次、什么原因)全在日志里。非瞬态 HTTP 码立即返回 false,不空耗三档。
   */
  suspend fun put(url: String, username: String, password: String, body: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
      val request = Request.Builder()
        .url(url)
        .put(body.toRequestBody(jsonMediaType))
        .header("Authorization", Credentials.basic(username, password))
        .build()
      var lastReason = "未尝试"
      probeLadder.forEachIndexed { index, (callClient, timeoutSec) ->
        val attempt = index + 1
        val started = System.currentTimeMillis()
        try {
          callClient.newCall(request).execute().use { response ->
            val elapsed = System.currentTimeMillis() - started
            val ok = response.isSuccessful
            Log.i(
              WebDavLogTag,
              "put attempt=$attempt/${probeLadder.size} code=${response.code} ok=$ok bytes=${body.size} " +
                "elapsed=${elapsed}ms timeout=${timeoutSec}s url=$url",
            )
            if (ok) return@withContext true
            lastReason = "HTTP ${response.code}"
            if (!isTransientTransferCode(response.code)) {
              Log.w(WebDavLogTag, "put give-up (non-transient) attempt=$attempt code=${response.code} url=$url")
              return@withContext false
            }
          }
        } catch (e: IOException) {
          lastReason = "${e::class.simpleName}: ${e.message}"
          Log.w(
            WebDavLogTag,
            "put attempt=$attempt/${probeLadder.size} failed elapsed=${System.currentTimeMillis() - started}ms " +
              "timeout=${timeoutSec}s $lastReason url=$url",
          )
        }
      }
      Log.w(WebDavLogTag, "put give-up attempts=${probeLadder.size} last=$lastReason url=$url")
      false
    }

  /**
   * GET 下载文件内容,非 2xx 返回 null。
   *
   * **P11-256:与 [put] 同一套阶梯重试**(理由/档位/失败语义见 [put] 的注释;下载同样是「冷边缘 +
   * 大 body」会挂,日志下载失败也走这条路)。
   */
  suspend fun get(url: String, username: String, password: String): ByteArray? =
    withContext(Dispatchers.IO) {
      val request = Request.Builder()
        .url(url)
        .get()
        .header("Authorization", Credentials.basic(username, password))
        .build()
      var lastReason = "未尝试"
      probeLadder.forEachIndexed { index, (callClient, timeoutSec) ->
        val attempt = index + 1
        val started = System.currentTimeMillis()
        try {
          callClient.newCall(request).execute().use { response ->
            val elapsed = System.currentTimeMillis() - started
            val body = if (!response.isSuccessful) null else response.body?.bytes()
            Log.i(
              WebDavLogTag,
              "get attempt=$attempt/${probeLadder.size} code=${response.code} ok=${response.isSuccessful} " +
                "bytes=${body?.size ?: 0} elapsed=${elapsed}ms timeout=${timeoutSec}s url=$url",
            )
            if (response.isSuccessful) return@withContext body
            lastReason = "HTTP ${response.code}"
            if (!isTransientTransferCode(response.code)) {
              Log.w(WebDavLogTag, "get give-up (non-transient) attempt=$attempt code=${response.code} url=$url")
              return@withContext null
            }
          }
        } catch (e: IOException) {
          lastReason = "${e::class.simpleName}: ${e.message}"
          Log.w(
            WebDavLogTag,
            "get attempt=$attempt/${probeLadder.size} failed elapsed=${System.currentTimeMillis() - started}ms " +
              "timeout=${timeoutSec}s $lastReason url=$url",
          )
        }
      }
      Log.w(WebDavLogTag, "get give-up attempts=${probeLadder.size} last=$lastReason url=$url")
      null
    }

  /**
   * 连通性探测:发 GET,2xx 视为连通(401/403/404 等非 2xx 视为不通)。
   * 网络异常(超时/DNS/拒连)返回 false。用于保存配置前校验服务器可达,
   * 以及备份/还原入口的快速连通校验。
   *
   * P11-174(真机「连试很多次才成功」):
   * ①**整层此前零日志** —— 失败时既不知道是超时、DNS、拒连还是 HTTP 码,也无法判断「重试变快」
   *   是连接复用还是服务端抖动。现在每次探测都落一行(attempt/耗时/HTTP 码或异常类);
   * ②**首次失败换 15s 长超时再试一次**([probeClientLong]) —— 8s 单发硬门会把偶发慢首包直接判死,
   *   用户手动重试就成了随机撞运气;
   * ③**405(Method Not Allowed)也算连通** —— 部分 WebDAV 服务器拒绝在集合根上做 GET,但能应答
   *   就说明「host 通 + 认证路径在」,把它当不通会让这类服务器永远存不上配置。
   *
   * P11-176(真机 `logs_live_20260922_234839`):探测改**三档 8s → 15s → 25s**([probeLadder])。
   * 那场证据:第一笔 8.9s 超时、第二笔 15.0s 超时,紧接着第三次 6.7s 成、第四次 1.3s = 边缘/源站冷启;
   * 两档不够,一次用户操作内必须能熬过冷启。另外 AppContainer.startWebDavPrewarm 在启动时先打一枪,
   * 把冷启挪出用户按键那一刻(热连接下第一档即中)。
   */
  suspend fun ping(url: String, username: String, password: String): Boolean =
    withContext(Dispatchers.IO) {
      val target = url.trimEnd('/')
      var lastReason = "未尝试"
      probeLadder.forEachIndexed { index, (callClient, timeoutSec) ->
        val attempt = index + 1
        val started = System.currentTimeMillis()
        try {
          val request = Request.Builder()
            .url(target)
            .get()
            .header("Authorization", Credentials.basic(username, password))
            .build()
          callClient.newCall(request).execute().use { response ->
            val elapsed = System.currentTimeMillis() - started
            val ok = response.isSuccessful || response.code == 405
            Log.i(
              WebDavLogTag,
              "ping attempt=$attempt/${probeLadder.size} code=${response.code} ok=$ok " +
                "elapsed=${elapsed}ms timeout=${timeoutSec}s url=$target",
            )
            if (ok) return@withContext true
            lastReason = "HTTP ${response.code}"
          }
        } catch (e: IOException) {
          val elapsed = System.currentTimeMillis() - started
          lastReason = "${e::class.simpleName}: ${e.message}"
          Log.w(
            WebDavLogTag,
            "ping attempt=$attempt/${probeLadder.size} failed elapsed=${elapsed}ms " +
              "timeout=${timeoutSec}s $lastReason url=$target",
          )
        }
      }
      Log.w(WebDavLogTag, "ping give-up attempts=${probeLadder.size} last=$lastReason url=$target")
      false
    }

  /**
   * MKCOL 建目录。目录已存在时服务器返回 405(Method Not Allowed),视为成功。
   * 网络/认证失败抛 [IOException],由调用方处理。
   */
  suspend fun mkcol(url: String, username: String, password: String): Boolean =
    withContext(Dispatchers.IO) {
      val request = Request.Builder()
        .url(url)
        .method("MKCOL", null)
        .header("Authorization", Credentials.basic(username, password))
        .build()
      client.newCall(request).execute().use { response ->
        val ok = response.isSuccessful || response.code == 405
        Log.i(WebDavLogTag, "mkcol code=${response.code} ok=$ok url=$url")
        ok
      }
    }

  companion object {
    const val WebDavLogTag = "BiliWebDav"
  }
}
