package com.kirin.mt.core.youtube.sabr

import android.net.Uri
import android.util.Log
import androidx.media3.common.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener

/**
 * scheme 路由 DataSource:`sabr://` URI 交给 [SabrStreamingDataSource](走 [SabrStreamRegistry]
 * 查表 + SabrClient 驱动),其余(http/https)交给包装的 OkHttp DataSource(B 站/YouTube 回退流)。
 *
 * 包装在 [SabrAwareDataSourceFactory] 里,嵌进两播放器 `DefaultDataSource.Factory` 的基座——
 * 一处包装,非 sabr 流不受影响。`sabr://` 只由 YouTube resolver 在 SABR 数据齐全时发出,
 * 故无需按 source 分流。
 */
internal class SabrAwareDataSourceFactory(
  private val httpFactory: DataSource.Factory,
) : DataSource.Factory {
  override fun createDataSource(): DataSource = SabrAwareDataSource(httpFactory.createDataSource())
}

internal class SabrAwareDataSource(private val http: DataSource) : DataSource {
  private val tag = "YtSabr"
  /** open() 选定的 delegate:sabr 路径是 [SabrStreamingDataSource],否则是 [http]。 */
  private var delegate: DataSource = http

  override fun open(dataSpec: DataSpec): Long {
    val scheme = dataSpec.uri.scheme
    val parsed = if (scheme?.equals("sabr", ignoreCase = true) == true) parseSabrUri(dataSpec.uri) else null
    return if (parsed != null) {
      val (sid, stream) = parsed
      Log.i(tag, "route sabr:// sid=$sid stream=$stream → SabrStreamingDataSource")
      val sabr = SabrStreamingDataSource(sid, stream)
      delegate = sabr
      sabr.open(dataSpec)
    } else {
      delegate = http
      http.open(dataSpec)
    }
  }

  override fun read(target: ByteArray, offset: Int, length: Int): Int = delegate.read(target, offset, length)

  override val uri: Uri? get() = delegate.uri

  override fun addTransferListener(transferListener: TransferListener) {
    // 转发给 http delegate,保 B 站/回退流的带宽估计与现有行为一致。sabr delegate 在 open 时才建,
    // 来不及注册监听(SABR 不需带宽估计,接受)。
    http.addTransferListener(transferListener)
  }

  override fun close() = delegate.close()

  /** `sabr://youtube/<sessionId>?stream=video|audio` → (sessionId, streamType)。 */
  private fun parseSabrUri(uri: Uri): Pair<String, SabrStreamType>? {
    // host 段 = "youtube";最后一个 path segment = sessionId。
    val sid = uri.lastPathSegment ?: return null
    if (sid.isBlank()) return null
    val stream = uri.getQueryParameter("stream") ?: "video"
    val st = if (stream.equals("audio", ignoreCase = true)) SabrStreamType.AUDIO else SabrStreamType.VIDEO
    return sid to st
  }
}
