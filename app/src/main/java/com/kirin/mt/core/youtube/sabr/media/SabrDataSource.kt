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
    // P11-90(修续播位置冻结连环重载):把段内所有 tfdt 的 baseMediaDecodeTime 相对化(首个→0,
    // 其余减首值)。media3 1.10 移除了老 ChunkExtractorWrapper 的「首样本自校准到 startTimeUs +
    // seekTimeUs 裁剪」逻辑(BundledChunkExtractor 时间戳纯透传 tfdt),续播时若服务端段 tfdt 与
    // 段表网格不一致(相对时间/漂移),ContainerMediaChunk 的 clip(extractor.seek(0, clipped−offset))
    // 会在段内找不到 ≥clip 的样本 → 永远 BUFFERING、位置冻结在续播点、看门狗连环重载
    // (真机 2026-09-09 logs_live_…_212154:31min 视频续播 801s 四轮会话全冻,数据层全绿
    // frameRendered=false;全新起播视频正常)。配合 DefaultSabrChunkSource 的
    // sampleOffsetUs=startTimeUs,样本时间 = 网格起点 + 段内相对值,新老语义一致;
    // tfdt 本就绝对且与网格一致的段,相对化后 +offset 仍得原值,幂等无害。
    patchTfdtRelativeToFirst()
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

  /**
   * P11-90:把 [data] 内所有 tfdt box 的 baseMediaDecodeTime 相对化(首个→0,其余减首值)。
   * 严格按 box 树走(moof→traf→tfdt),不全文扫 'tfdt' 字节——mdat 视频负载里可能撞出同样
   * 四字节,盲扫会改坏视频数据。init 段(ftyp/moov/sidx)无 tfdt,自然 no-op。
   */
  private fun patchTfdtRelativeToFirst() {
    if (data.size < 12) return
    try {
      val patches = mutableListOf<Pair<Int, Long>>() // (值字段偏移, version 0/1)
      walkTopLevel(0, data.size, patches)
      if (patches.isEmpty()) return
      val v0 = readTfdtValue(patches.first().first, patches.first().second)
      for ((valueOffset, version) in patches) {
        writeTfdtValue(valueOffset, readTfdtValue(valueOffset, version) - v0, version)
      }
    } catch (e: Exception) {
      // 任何解析异常都不动原字节(宁可维持现状,也不改坏数据)。
      Log.w("YtSabr", "tfdt relativize skipped: ${e.message}")
    }
  }

  /** 遍历 [start,end) 的顶层 box;对 moof 递归找 traf→tfdt,把 (值字段偏移, version) 收进 [out]。 */
  private fun walkTopLevel(start: Int, end: Int, out: MutableList<Pair<Int, Long>>) {
    var pos = start
    while (pos + 8 <= end) {
      val boxEnd = boxEnd(pos, end) ?: return
      if (typeAt(pos) == MOOF) walkContainer(pos + 8, boxEnd, out, TRAF)
      pos = boxEnd
    }
  }

  /** 在 [wanted] 容器(traf)内遍历找 tfdt,收进 [out];[wanted]=TRAF 时递归其内找 tfdt。 */
  private fun walkContainer(start: Int, end: Int, out: MutableList<Pair<Int, Long>>, wanted: Int) {
    var pos = start
    while (pos + 8 <= end) {
      val boxEnd = boxEnd(pos, end) ?: return
      val type = typeAt(pos)
      if (type == TFDT && wanted == TRAF) {
        out.add(pos + 12 to (data[pos + 8].toInt() and 0x01).toLong())
      } else if (type == wanted) {
        walkContainer(pos + 8, boxEnd, out, if (wanted == TRAF) TFDT else TRAF)
      }
      pos = boxEnd
    }
  }

  /** 返回 pos 处 box 的结束下标;size 异常/越界返回 null(调用方终止该层遍历)。 */
  private fun boxEnd(pos: Int, end: Int): Int? {
    if (pos + 8 > end) return null
    var size = readUint32(pos)
    if (size == 1) {
      if (pos + 16 > end) return null
      size = readLong64(pos + 8).toInt()
    } else if (size == 0) {
      size = end - pos
    }
    if (size < 8 || pos + size > end) return null
    return pos + size
  }

  private fun typeAt(pos: Int): Int = readUint32(pos + 4)

  private fun readTfdtValue(valueOffset: Int, version: Long): Long =
    if (version == 1L) readLong64(valueOffset) else readUint32(valueOffset).toLong()

  private fun writeTfdtValue(valueOffset: Int, value: Long, version: Long) {
    val clamped = value.coerceAtLeast(0L)
    if (version == 1L) writeLong64(valueOffset, clamped) else writeUint32(valueOffset, clamped.toInt())
  }

  private fun readUint32(pos: Int): Int =
    ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos + 1].toInt() and 0xFF) shl 16) or
      ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)

  private fun readLong64(pos: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
    return v
  }

  private fun writeUint32(pos: Int, v: Int) {
    data[pos] = (v ushr 24).toByte()
    data[pos + 1] = (v ushr 16).toByte()
    data[pos + 2] = (v ushr 8).toByte()
    data[pos + 3] = v.toByte()
  }

  private fun writeLong64(pos: Int, v: Long) {
    for (i in 0 until 8) data[pos + i] = (v ushr (8 * (7 - i))).toByte()
  }

  private companion object {
    val MOOF = intType("moof")
    val TRAF = intType("traf")
    val TFDT = intType("tfdt")

    fun intType(s: String): Int =
      ((s[0].code and 0xFF) shl 24) or ((s[1].code and 0xFF) shl 16) or
        ((s[2].code and 0xFF) shl 8) or (s[3].code and 0xFF)
  }
}
