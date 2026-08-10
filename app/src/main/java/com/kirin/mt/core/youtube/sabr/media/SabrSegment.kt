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

  /**
   * 算本格式真实 bufferedRanges——把 [bufferedSegments]+[downloadedSegments] 按 seq 连续分段,
   * 每段一个 [BufferedRangeInput](startTimeMs=首段 header.startMs,durationMs=段时长和,
   * startSegmentIndex/endSegmentIndex=段号区间)。**从不发 Int.MAX**(对齐 LibreTube,修 alpha.63 双流 fake-full)。
   */
  fun buildBufferedRanges(): List<BufferedRangeInput> =
    bufferedSegments.entries.union(downloadedSegments.entries).sortedBy { it.key }
      .fold(mutableListOf<MutableList<Pair<Long, SabrSegment>>>()) { acc, (id, segment) ->
        val previousId = acc.lastOrNull()?.lastOrNull()?.first
        if (previousId?.plus(1) != id) acc.add(mutableListOf())
        acc.lastOrNull()!!.add(id to segment)
        acc
      }.map { partition ->
        val duration = partition.sumOf { it.second.duration }
        val (firstId, firstSegment) = partition.first()
        BufferedRangeInput(
          itag = id.itag,
          lastModified = id.lastModified,
          xtags = id.xtags,
          startTimeMs = firstSegment.header.startMs,
          durationMs = duration,
          startSegmentIndex = firstId.toInt(),
          endSegmentIndex = partition.last().first.toInt(),
          timeRange = null,
        )
      }

  /** 本格式是否有 seq 段在 [downloadedSegments] 或 init 段。 */
  fun hasSegment(sequenceNumber: Long): Boolean =
    downloadedSegments.containsKey(sequenceNumber) || initSegment?.sequenceNumber == sequenceNumber
}
