package com.kirin.mt.core.youtube.sabr

import java.util.ArrayDeque

/**
 * 分块只读字节缓冲——对 googlevideo `CompositeBuffer` 的 Kotlin 简化实现。
 *
 * SABR 响应流式到达(OkHttp 分块读),UMP part 可能跨 chunk。本类把多个 chunk 逻辑拼接,
 * 不拷贝整体(段可能 MB 级),按全局 offset 读字节/切片/丢弃。
 *
 * offset 均相对「未消费区头」(已 drop 的字节不占位)。内部 [headOffset] 是第一个 chunk 里
 * 已消费到第几个字节;chunk 0..headOffset-1 的字节视为已消费,不再可见。
 */
internal class CompositeBuffer {
  private val chunks: ArrayDeque<ByteArray> = ArrayDeque()
  private var headOffset: Int = 0
  private var length: Long = 0L

  fun append(data: ByteArray) {
    if (data.isEmpty()) return
    chunks.addLast(data)
    length += data.size
  }

  fun clear() {
    chunks.clear()
    headOffset = 0
    length = 0L
  }

  /** 当前未消费字节数。 */
  fun size(): Long = length

  /** [offset, offset+count) 是否全在未消费区内。 */
  fun canRead(offset: Int, count: Int): Boolean =
    offset >= 0 && count >= 0 && offset.toLong() + count <= length

  /** 读 [offset] 处单字节(0..255);调用方需保证 canRead(offset,1)。 */
  fun byteAt(offset: Int): Byte {
    var remaining = offset
    val it = chunks.iterator()
    var chunk = it.next()
    // headOffset 只对第一个 chunk 生效;之后每个 chunk 从 0 起
    var first = true
    while (it.hasNext()) {
      val start = if (first) headOffset else 0
      first = false
      val avail = chunk.size - start
      if (remaining < avail) {
        return chunk[start + remaining]
      }
      remaining -= avail
      chunk = it.next()
    }
    val start = if (first) headOffset else 0
    return chunk[start + remaining]
  }

  /** 切出 [offset, offset+count) 的连续 ByteArray(可跨 chunk,会拷贝)。调用方需保证 canRead。 */
  fun slice(offset: Int, count: Int): ByteArray {
    val out = ByteArray(count)
    var remaining = offset
    var written = 0
    var first = true
    val it = chunks.iterator()
    while (written < count && it.hasNext()) {
      val chunk = it.next()
      val start = if (first) headOffset else 0
      first = false
      val avail = chunk.size - start
      if (remaining >= avail) {
        remaining -= avail
        continue
      }
      // chunk 内从 start+remaining 起
      val from = start + remaining
      remaining = 0
      val canCopy = minOf(chunk.size - from, count - written)
      System.arraycopy(chunk, from, out, written, canCopy)
      written += canCopy
    }
    return out
  }

  /** 从头丢弃 [n] 字节(已消费)。 */
  fun drop(n: Int) {
    if (n <= 0) return
    require(n.toLong() <= length) { "drop $n exceeds length $length" }
    var remaining = n
    while (remaining > 0 && chunks.isNotEmpty()) {
      val chunk = chunks.peekFirst()!!
      val avail = chunk.size - headOffset
      if (remaining < avail) {
        headOffset += remaining
        length -= remaining
        return
      }
      remaining -= avail
      chunks.removeFirst()
      headOffset = 0
      length -= avail
    }
  }
}
