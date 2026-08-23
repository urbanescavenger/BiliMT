package com.kirin.mt.core.youtube.sabr.media

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.kirin.mt.core.youtube.sabr.SabrStreamRegistry
import java.io.IOException

/**
 * alpha.64(端口 LibreTube `SabrDataSource`):SABR 流的 [DataSource]。
 *
 * [open] 从 [DataSpec.customData] 取 [SabrSegmentRequest]→[SabrMediaFetcher.getNextSegment] 取段
 * → 拍平成连续字节流喂 [androidx.media3.exoplayer.source.chunk.ContainerMediaChunk]/
 * [androidx.media3.exoplayer.source.chunk.InitializationChunk] 的 [BundledChunkExtractor]。
 *
 * 终端错误([SabrTerminalException])→ [SabrStreamRegistry.evict](会话亡,播放器重 harvest)→ 抛
 * [IOException] 走 chunk load error 通路。对齐 LibreTube `player/SabrDataSource.kt`(MIT),
 * 适配:加 sid 参数做 evict(对齐我们 alpha.36 的 evict-on-terminal 稳健模型)。
 */
@OptIn(UnstableApi::class)
internal class SabrDataSource(
  private val fetcher: SabrMediaFetcher,
  private val sessionId: String,
) : BaseDataSource(true) {
  private var data: ByteArray = ByteArray(0)
  private var position: Int = 0
  private var uri: Uri? = null

  class Factory(
    private val fetcher: SabrMediaFetcher,
    private val sessionId: String,
  ) : DataSource.Factory {
    override fun createDataSource(): DataSource = SabrDataSource(fetcher, sessionId)
  }

  override fun open(dataSpec: DataSpec): Long {
    uri = dataSpec.uri
    val req = dataSpec.customData as? SabrSegmentRequest
      ?: throw IOException("SABR DataSpec.customData is not SabrSegmentRequest")
    transferInitializing(dataSpec)
    // alpha.9X(对齐 LibreTube `SabrDataSource.open`):transferStarted 移到 getNextSegment **之前**,让
    // DefaultBandwidthMeter 把真实网络 POST 耗时计入带宽样本(否则只在 POST 之后才开始传输窗口,只量到内存
    // 瞬时读 → 带宽估计失真 → AdaptiveTrackSelection 升档判定错误)。getNextSegment 失败时 transferStarted 已
    // 调用、未收尾,交给 chunk load error 通路兜底(对齐 LibreTube)。
    transferStarted(dataSpec)
    val segment = try {
      fetcher.getNextSegment(req)
    } catch (e: SabrTerminalException) {
      Log.w("YtSabr", "SabrDataSource open: terminal seg=${req.segment} itag=${req.formatItag}: ${e.message} → evict sid=$sessionId")
      SabrStreamRegistry.evict(sessionId)
      throw IOException("SABR terminal: ${e.message}")
    } catch (e: Exception) {
      Log.w("YtSabr", "SabrDataSource open: seg=${req.segment} itag=${req.formatItag} ${e::class.simpleName}: ${e.message} → evict sid=$sessionId")
      SabrStreamRegistry.evict(sessionId)
      throw IOException("SABR open failed: ${e.message}")
    }
    // 拍平段字节(多 MEDIA part 块 → 单连续流),喂 ChunkExtractor
    data = if (segment.data.size == 1) segment.data[0]
    else segment.data.fold(ByteArray(0)) { acc, c -> acc + c }
    position = 0
    return data.size.toLong()
  }

  override fun getUri(): Uri? = if (position >= data.size) null else uri

  override fun close() {
    transferEnded()
    data = ByteArray(0)
    position = 0
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (position >= data.size) return C.RESULT_END_OF_INPUT
    val toCopy = minOf(length, data.size - position)
    System.arraycopy(data, position, buffer, offset, toCopy)
    position += toCopy
    bytesTransferred(toCopy)
    return toCopy
  }
}
