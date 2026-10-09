package com.kirin.mt.core.youtube.sabr

import java.util.ArrayDeque

/**
 * P11-234:**64KB 读块池**(进程级,`@Synchronized`)。
 *
 * 为什么要池:真机 `logs_live_20261010_005115.log`(dev.r2180,4K)取证 —— 一段 13.5MB 按 64KB 读 =
 * **206 个新建大对象**,每轮两段 ~54MB 全进 Java LOS ⇒ GC 每 ~250ms 回收 100~200MB、
 * `Suspending all threads 6→15ms` ⇒ 「字节已在内存 → 交给播放器」被拖到 **3.5~18s**
 * (720p 同样流程只要 0.15s;移动端堆充裕、同样过程亚秒级 ⇒ 纯堆/GC 问题,与带宽无关)。
 * 池化后稳态分配为 0:块由读方借、读满入队、被 [CompositeBuffer.drop]/[clear] 归还后再借。
 *
 * 为什么必须是**进程级**:[UmpReader]/[CompositeBuffer] 都是**每笔请求新建**(三处 `UmpReader()`,
 * 热路径见 `SabrMediaFetcher`),实例级池会随请求一起丢掉 ⇒ 复用等于零。
 * 为什么加锁:播放的 fetcher 内部串行,但 harvest/resolver 会话可与播放并发。
 * [MAX_BYTES] 上限保证池自身不会变成本身就是泄漏源 —— 超出的直接还给 GC。
 */
internal object ByteChunkPool {
  private const val MAX_BYTES = 4L * 1024 * 1024

  private val pool: ArrayDeque<ByteArray> = ArrayDeque()
  private var pooledBytes: Long = 0L

  /** 借一块读缓冲([size] 固定 = 读块大小)。 */
  @Synchronized
  fun obtain(size: Int): ByteArray {
    val reused = pool.pollLast() ?: return ByteArray(size)
    pooledBytes -= reused.size
    return if (reused.size == size) reused else ByteArray(size)
  }

  /** 归还一块(读满入队后由 [CompositeBuffer.drop]/[clear] 调;短读的剩余部分读完即还)。 */
  @Synchronized
  fun release(data: ByteArray) {
    if (pooledBytes + data.size > MAX_BYTES) return
    pool.addLast(data)
    pooledBytes += data.size
  }
}

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

  /** P11-234:借一块读缓冲(见 [ByteChunkPool])。 */
  fun obtain(size: Int): ByteArray = ByteChunkPool.obtain(size)

  fun append(data: ByteArray) = append(data, data.size)

  /**
   * [data] 只有前 [length] 字节有效(网络读常短读)。
   * - 读满:**零拷贝、零分配**入队(数组仍归本缓冲,消费后由 [drop]/[clear] 归还池);
   * - 短读:切出有效部分入队(每次响应末尾一次,≤64KB),整块立刻还给池。
   */
  fun append(data: ByteArray, length: Int) {
    if (length <= 0) {
      ByteChunkPool.release(data)
      return
    }
    if (length == data.size) {
      chunks.addLast(data)
    } else {
      chunks.addLast(data.copyOf(length))
      ByteChunkPool.release(data)
    }
    this.length += length
  }

  fun clear() {
    for (c in chunks) ByteChunkPool.release(c)
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

  /** 从头丢弃 [n] 字节(已消费)。整块消费掉的数组归还池(P11-234)。 */
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
      ByteChunkPool.release(chunks.removeFirst())
      headOffset = 0
      length -= avail
    }
  }
}
