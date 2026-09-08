package com.kirin.mt.core.youtube.sabr.media

import com.kirin.mt.core.youtube.sabr.BufferedRangeInput
import com.kirin.mt.core.youtube.sabr.FormatId
import com.kirin.mt.core.youtube.sabr.SabrProto

/**
 * alpha.64(端口 LibreTube `Segment`):一段 SABR 媒体数据——一个 [SabrProto.MediaHeader] 对应的
 * 一个 sequenceNumber 的若干 MEDIA part 字节块。下载时 [data] 累积多块(UMP MEDIA part 逐块到),
 * MEDIA_END 收尾后存进 [InitializedFormat.downloadedSegments]。
 *
 * 对齐 LibreTube `parser/SabrClient.kt` 的 `Segment` data class(MIT)。
 */
internal data class SabrSegment(
  /** 该段的 MediaHeader(含 itag/seq/isInitSeg/startMs/durationMs/contentLength)。 */
  val header: SabrProto.MediaHeader,
  /** 段在流中的序号(MediaHeader.sequenceNumber)。 */
  val sequenceNumber: Long,
  /** 段的原始媒体字节(多块,逐 MEDIA part 追加;消费时不合并,按块读)。 */
  val data: MutableList<ByteArray>,
  /** 段时长(ms)——MediaHeader.durationMs。 */
  val duration: Long,
) {
  /** 段字节总长(应等于 MediaHeader.contentLength,媒体端校验)。 */
  fun length(): Int = data.sumOf { it.size }
}

/**
 * alpha.64(端口 LibreTube `InitializedFormat`):一个已初始化的格式(由 FORMAT_INITIALIZATION_METADATA
 * part 建表),持有该格式的段缓存。**单流多段**核心——一次 POST 返回的多段都缓存在 [downloadedSegments],
 * 播放器逐段取([getSegment] 取出并标记 [bufferedSegments]),[buildBufferedRanges] 据缓存算真实 bufferedRange
 * 回传服务端(无 Int.MAX fake-full,对齐 LibreTube)。
 *
 * 对齐 LibreTube `parser/SabrClient.kt` 的 `InitializedFormat`(MIT)。
 */
internal class InitializedFormat(
  /** 该格式的 FormatId(itag/lastModified/xtags)。 */
  val id: FormatId,
  /** 已下载未消费段(seq → Segment);getNextSegment 从这取段。 */
  val downloadedSegments: MutableMap<Long, SabrSegment> = mutableMapOf(),
  /** 已喂播放器段(seq → 空 data Segment,仅作 buildBufferedRanges 锚点)。 */
  val bufferedSegments: MutableMap<Long, SabrSegment> = mutableMapOf(),
  /** 服务端自报的末段序号(FORMAT_INITIALIZATION_METADATA.endSegmentNumber)。 */
  val endSegmentNumber: Long,
  /** 服务端自报的格式总时长(ms,FORMAT_INITIALIZATION_METADATA.endTimeMs)。 */
  val duration: Long,
  /** init 段(isInitSeg=true 的段,seq 通常 0);ChunkExtractor 从此解 ChunkIndex。 */
  var initSegment: SabrSegment? = null,

  /**
   * P11-85:段号→绝对时间网格(由 init 段解出的 ChunkIndex 回喂,见 [seqStartMsBySeq])。
   * MEDIA_HEADER.startMs/durationMs 服务端恒回 0(visionOS 实测),header 不可信。
   */
  @Volatile var seqStartMsBySeq: LongArray? = null,
) {
  /**
   * 取出 seq 段(从 [downloadedSegments] 移除 → 标记 [bufferedSegments]),对齐 LibreTube `getSegment`。
   * init 段也在 [downloadedSegments](MEDIA_END 存入),或 [initSegment](兜底)。
   * @return 段;未就绪返回 null(调用方媒体重试)。
   */
  fun getSegment(sequenceNumber: Long): SabrSegment? {
    val segment = downloadedSegments.remove(sequenceNumber)
      ?: initSegment?.takeIf { it.sequenceNumber == sequenceNumber }
      ?: return null
    // 标记已消费(bufferedSegments 存空 data 段,供 buildBufferedRanges 锚定已缓冲范围)
    bufferedSegments[sequenceNumber] = segment.copy(data = mutableListOf())
    return segment
  }

  /** P11-85:wire seq(media 段号,init=0)→ 段绝对开始 ms;网格未回喂或越界返回 null。 */
  fun segmentStartMs(sequenceNumber: Long): Long? {
    val grid = seqStartMsBySeq ?: return null
    val index = (sequenceNumber - 1).toInt()
    if (index < 0 || index >= grid.size) return null
    return grid[index]
  }

  /**
   * 算本格式真实 bufferedRanges——把 [bufferedSegments]+[downloadedSegments] 按 seq 连续分段,
   * 每段一个 [BufferedRangeInput](startSegmentIndex/endSegmentIndex=段号区间)。**从不发 Int.MAX**。
   *
   * P11-85 修续播黑屏:startTimeMs/durationMs 原用 header.startMs/durationMs——visionOS 服务端
   * 恒回 0 → 上报 {start:0,dur:0} 垃圾 ranges;playerTimeMs=0(从头播)时服务端回落判定与真实
   * 状态巧合一致不炸,**playerTimeMs>0(历史续播)时服务端回落到垃圾 ranges → 段锚点错乱 →
   * 位置冻结黑屏**。有网格([seqStartMsBySeq])时按段号查真实绝对时间;无网格(首请求 init
   * 未解出前)维持旧行为(该窗口本就只有 init 请求,不含媒体范围语义)。
   */
  fun buildBufferedRanges(): List<BufferedRangeInput> =
    bufferedSegments.entries.union(downloadedSegments.entries).sortedBy { it.key }
      .fold(mutableListOf<MutableList<Pair<Long, SabrSegment>>>()) { acc, (id, segment) ->
        val previousId = acc.lastOrNull()?.lastOrNull()?.first
        if (previousId?.plus(1) != id) acc.add(mutableListOf())
        acc.lastOrNull()!!.add(id to segment)
        acc
      }.map { partition ->
        val firstId = partition.first().first
        val lastId = partition.last().first
        // 真实时间:wire seq N 覆盖网格第 N-1 段(网格=ChunkIndex.timesMs,init 占 seq 0)。
        val startMs = segmentStartMs(firstId)
        val endMs = segmentStartMs(lastId + 1)
        val duration = if (startMs != null && endMs != null && endMs > startMs) {
          endMs - startMs
        } else {
          partition.sumOf { it.second.duration }
        }
        BufferedRangeInput(
          itag = id.itag,
          lastModified = id.lastModified,
          xtags = id.xtags,
          startTimeMs = startMs ?: partition.first().second.header.startMs,
          durationMs = duration,
          startSegmentIndex = firstId.toInt(),
          endSegmentIndex = lastId.toInt(),
          timeRange = null,
        )
      }

  /** 本格式是否有 seq 段在 [downloadedSegments] 或 init 段。 */
  fun hasSegment(sequenceNumber: Long): Boolean =
    downloadedSegments.containsKey(sequenceNumber) || initSegment?.sequenceNumber == sequenceNumber
}
