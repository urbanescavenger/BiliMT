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

  /** PUT 上传文件内容,2xx 视为成功。 */
  suspend fun put(url: String, username: String, password: String, body: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
      val request = Request.Builder()
        .url(url)
        .put(body.toRequestBody(jsonMediaType))
        .header("Authorization", Credentials.basic(username, password))
        .build()
      val started = System.currentTimeMillis()
      try {
        client.newCall(request).execute().use { response ->
          val ok = response.isSuccessful
          Log.i(
            WebDavLogTag,
            "put code=${response.code} ok=$ok bytes=${body.size} elapsed=${System.currentTimeMillis() - started}ms url=$url",
          )
          ok
        }
      } catch (e: IOException) {
        Log.w(
          WebDavLogTag,
          "put failed elapsed=${System.currentTimeMillis() - started}ms ${e::class.simpleName}: ${e.message} url=$url",
        )
        throw e
      }
    }

  /** GET 下载文件内容,非 2xx 返回 null。 */
  suspend fun get(url: String, username: String, password: String): ByteArray? =
    withContext(Dispatchers.IO) {
      val request = Request.Builder()
        .url(url)
        .get()
        .header("Authorization", Credentials.basic(username, password))
        .build()
      val started = System.currentTimeMillis()
      try {
        client.newCall(request).execute().use { response ->
          val body = if (!response.isSuccessful) null else response.body?.bytes()
          Log.i(
            WebDavLogTag,
            "get code=${response.code} ok=${response.isSuccessful} bytes=${body?.size ?: 0} " +
              "elapsed=${System.currentTimeMillis() - started}ms url=$url",
          )
          body
        }
      } catch (e: IOException) {
        Log.w(
          WebDavLogTag,
          "get failed elapsed=${System.currentTimeMillis() - started}ms ${e::class.simpleName}: ${e.message} url=$url",
        )
        throw e
      }
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
