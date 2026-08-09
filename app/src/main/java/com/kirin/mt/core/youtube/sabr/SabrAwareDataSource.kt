package com.kirin.mt.core.youtube.sabr

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSpec
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
    val uri = dataSpec.uri
    val scheme = uri.scheme
    val parsed = if (scheme?.equals("sabr", ignoreCase = true) == true) parseSabrUri(uri) else null
    return if (parsed != null) {
      // alpha.59(Phase 2 DASH):带 `&init=`/`&seg=` 的 sabr:// URL 是合成 DASH MPD 的段请求
      // (DashMediaSource 按 SegmentTemplate 逐段拉)→ 交 [SabrDashDataSource](每段独立,不 burst)。
      // 其余(progressive 兜底,无 seg/init)仍走 [SabrStreamingDataSource]。
      if (parsed.isInit || parsed.segmentNumber > 0) {
        Log.i(tag, "route sabr:// sid=${parsed.sid} stream=${parsed.stream} itag=${parsed.itag ?: "default"} isInit=${parsed.isInit} seg=${parsed.segmentNumber} dur=${parsed.segmentDurationMs} → SabrDashDataSource")
        val sabr = SabrDashDataSource(parsed.sid, parsed.stream, parsed.itag, parsed.isInit, parsed.segmentNumber, parsed.segmentDurationMs)
        delegate = sabr
        sabr.open(dataSpec)
      } else {
        Log.i(tag, "route sabr:// sid=${parsed.sid} stream=${parsed.stream} itag=${parsed.itag ?: "default"} startMs=${parsed.startMs} videoId=${parsed.videoId} → SabrStreamingDataSource")
        val sabr = SabrStreamingDataSource(parsed.sid, parsed.stream, parsed.itag, parsed.startMs, parsed.videoId)
        delegate = sabr
        sabr.open(dataSpec)
      }
    } else {
      delegate = http
      http.open(dataSpec)
    }
  }

  override fun read(target: ByteArray, offset: Int, length: Int): Int = delegate.read(target, offset, length)

  override fun getUri(): Uri? = delegate.getUri()

  override fun addTransferListener(transferListener: TransferListener) {
    // 转发给 http delegate,保 B 站/回退流的带宽估计与现有行为一致。sabr delegate 在 open 时才建,
    // 来不及注册监听(SABR 不需带宽估计,接受)。
    http.addTransferListener(transferListener)
  }

  override fun close() = delegate.close()

  /**
   * `sabr://youtube/<sessionId>?stream=video|audio&itag=<N>&startMs=<ms>` → SabrUriParts。
   * alpha.29:`&itag=` 指定本次播放的视频清晰度(poToken 会话级不绑 itag,同 sid 换 itag 即换清晰度)。
   * audio 流不带 itag(用会话默认 audioFormatId)。
   * alpha.34:`&startMs=` 续播/切清晰度起始 playerTimeMs(见 [SabrStreamingDataSource.startMs]);无则 0(从头播)。
   * alpha.59(Phase 2 DASH):`&init=1`/`&seg=N&dur=D` 是合成 DASH MPD 的段请求参数——init 段(isInit=true)
   * 或第 N 段(段时长 D ms)。带这些参数 → 路由到 [SabrDashDataSource];否则 progressive 兜底。
   */
  private data class SabrUriParts(
    val sid: String,
    val stream: SabrStreamType,
    val itag: Int?,
    val startMs: Long,
    val videoId: String?,
    val isInit: Boolean,
    val segmentNumber: Int,
    val segmentDurationMs: Long,
  )

  private fun parseSabrUri(uri: Uri): SabrUriParts? {
    // host 段 = "youtube";最后一个 path segment = sessionId。
    val sid = uri.lastPathSegment ?: return null
    if (sid.isBlank()) return null
    val stream = uri.getQueryParameter("stream") ?: "video"
    val st = if (stream.equals("audio", ignoreCase = true)) SabrStreamType.AUDIO else SabrStreamType.VIDEO
    val itag = uri.getQueryParameter("itag")?.toIntOrNull()
    val startMs = uri.getQueryParameter("startMs")?.toLongOrNull() ?: 0L
    // alpha.48(轮换):`&videoId=`(兼容旧 progressive 兜底;DASH 段 URL 由 MPD 追加 &init/&seg)。
    val videoId = uri.getQueryParameter("videoId")
    // alpha.59(Phase 2 DASH):`&init=1` → init 段;`&seg=N&dur=D` → 第 N 段(段时长 D)。无则 0(progressive 兜底)。
    val isInit = uri.getQueryParameter("init")?.toIntOrNull() == 1
    val segmentNumber = uri.getQueryParameter("seg")?.toIntOrNull() ?: 0
    val segmentDurationMs = uri.getQueryParameter("dur")?.toLongOrNull() ?: 0L
    return SabrUriParts(sid, st, itag, startMs, videoId, isInit, segmentNumber, segmentDurationMs)
  }
}
