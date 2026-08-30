package com.kirin.mt.core.webdav

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

  /** PUT 上传文件内容,2xx 视为成功。 */
  suspend fun put(url: String, username: String, password: String, body: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
      val request = Request.Builder()
        .url(url)
        .put(body.toRequestBody(jsonMediaType))
        .header("Authorization", Credentials.basic(username, password))
        .build()
      client.newCall(request).execute().use { it.isSuccessful }
    }

  /** GET 下载文件内容,非 2xx 返回 null。 */
  suspend fun get(url: String, username: String, password: String): ByteArray? =
    withContext(Dispatchers.IO) {
      val request = Request.Builder()
        .url(url)
        .get()
        .header("Authorization", Credentials.basic(username, password))
        .build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) null else response.body?.bytes()
      }
    }

  /**
   * 连通性探测:发 GET,仅 2xx 视为连通(401/403/404 等非 2xx 视为不通)。
   * 网络异常(超时/DNS/拒连)返回 false。用于保存配置前校验服务器可达,
   * 以及备份/还原入口的快速连通校验(超时见 [probeClient],最多 8s 即判失败)。
   */
  suspend fun ping(url: String, username: String, password: String): Boolean =
    withContext(Dispatchers.IO) {
      try {
        val request = Request.Builder()
          .url(url)
          .get()
          .header("Authorization", Credentials.basic(username, password))
          .build()
        probeClient.newCall(request).execute().use { it.isSuccessful }
      } catch (e: IOException) {
        false
      }
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
        response.isSuccessful || response.code == 405
      }
    }
}
