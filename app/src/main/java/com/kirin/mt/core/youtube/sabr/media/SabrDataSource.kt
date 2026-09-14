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
import kotlin.coroutines.cancellation.CancellationException

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
    // ⚠️ 顺序铁律:transferInitializing/transferStarted 必须先于任何 open 抛错——close() 无条件
    // transferEnded(),BaseDataSource 未记 dataSpec 时抛 NPE(P11-99 首版把 fast-fail 检查放
    // transferInitializing 之前,真机 09-15 00:01:56 UnexpectedNullPointerException 实锤)。
    transferInitializing(dataSpec)
    // alpha.9X(RELOAD 快速失败):会话已收过 RELOAD_PLAYER_RESPONSE(reloadCount>0)后,服务端对本
    // 视频只会继续回 RELOAD 终止包(alpha.14 jNl6YkkzKxw / 09-14 Fhyu9sqcF-o 真机:8 连 chunk 重试
    // 每次都是 RELOAD,首请求还可能是 15s ReadTimeout → 理论最坏 8×15s 白等)。不再发请求,立即抛错
    // 让 ExoPlayer 尽快耗尽重试上抛 source error → UI error-retry 重 resolve → alpha.93 守卫
    // (reloadCount>0 跳过 SABR)落 DASH/HLS 兜底。健康会话不受影响:RELOAD 即 terminal,本就 evict。
    val vid = fetcher.videoId
    if (vid != null && SabrStreamRegistry.reloadCount(vid) > 0) {
      Log.w(
        "YtSabr",
        "SabrDataSource open: seg=${req.segment} itag=${req.formatItag} videoId=$vid " +
          "reload-killed(reloadCount=${SabrStreamRegistry.reloadCount(vid)}) → fast-fail no-fetch",
      )
      throw IOException("SABR session reload-killed: videoId=$vid → resolver guard falls back to DASH")
    }
    // P11-99 首版教训:transferStarted 移到 fast-fail 检查**之后**仍必须在 getNextSegment 之前
    // (让 DefaultBandwidthMeter 量到真实网络耗时;失败时 transferStarted 已调、未收尾,由 close()
    // transferEnded 收尾——BaseDataSource 状态机要求 initializing/started 先行)。
    transferStarted(dataSpec)
    val segment = try {
      fetcher.getNextSegment(req)
    } catch (e: SabrTerminalException) {
      Log.w("YtSabr", "SabrDataSource open: terminal seg=${req.segment} itag=${req.formatItag}: ${e.message} → evict sid=$sessionId")
      SabrStreamRegistry.evict(sessionId)
      throw IOException("SABR terminal: ${e.message}")
    } catch (e: CancellationException) {
      // P11-91:chunk 加载被取消(media3 seek/丢弃打断在途拉流,runBlocking 中断以
      // CancellationException 浮出)是良性取消——此前落进通用 catch 当会话死亡整会话 evict,
      // 一次 seek 就拆掉健康会话、白吃一轮 init 重拉(真机 2026-09-10 06:04:04 实锤)。
      // 只按普通 load 取消上抛,不 evict。
      Log.i("YtSabr", "SabrDataSource open: seg=${req.segment} itag=${req.formatItag} cancelled (chunk load cancelled) → 不 evict")
      throw IOException("SABR fetch cancelled: ${e.message}")
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
      Log.i(
        "YtSabr",
        "tfdt relativized: found=${patches.size} v0=${v0}ms→0 (bytes=${data.size})",
      )
    } catch (e: Exception) {
      // 任何解析异常都不动原字节(宁可维持现状,也不改坏数据)。
      Log.w("YtSabr", "tfdt relativize skipped: ${e.message}")
    }
  }

  /** 遍历 [start,end) 的顶层 box;对 moof 递归找 traf→tfdt,把 (值字段偏移, version) 收进 [out]。 */
  private fun walkTopLevel(start: Int, end: Int, out: MutableList<Pair<Int, Long>>) {
    walkBoxes(start, end, /* insideTraf = */ false, out)
  }

  /**
   * P11-91 修遍历 bug:旧实现的 wanted 分支把 traf 内的 tfdt 又当容器递归(wanted==TFDT 时
   * `type == TFDT && wanted == TRAF` 恒假,落进 else-if 递归进 tfdt 内部找 traf)——tfdt 永远
   * 采集不到,补丁全程 no-op,offset 叠在原始绝对 tfdt 上时间轴翻倍(真机 2026-09-10:
   * READY pos=44687 → 26ms 后 79876 = 39938×2,「播3秒跳10s」)。改为单层遍历 + insideTraf 标志:
   * moof → 递归找 traf;traf → 递归捕获 tfdt。
   */
  private fun walkBoxes(start: Int, end: Int, insideTraf: Boolean, out: MutableList<Pair<Int, Long>>) {
    var pos = start
    while (pos + 8 <= end) {
      val boxEnd = boxEnd(pos, end) ?: return
      when (typeAt(pos)) {
        MOOF -> walkBoxes(pos + 8, boxEnd, false, out)
        TRAF -> walkBoxes(pos + 8, boxEnd, true, out)
        TFDT -> if (insideTraf) out.add(pos + 12 to (data[pos + 8].toInt() and 0x01).toLong())
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
