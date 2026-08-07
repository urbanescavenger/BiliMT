package com.kirin.mt.core.youtube.sabr

/**
 * SABR / UMP 协议二进制解码器——对 googlevideo npm 包(src/core/UmpReader.ts)的独立 Kotlin 实现。
 *
 * YouTube 的 SABR(Server ABR)流响应是一个 UMP(Universal Media Protocol)容器:若干 part 背靠背串行,
 * 每个 part = `[type varint][size varint][payload bytes]`,无 magic/delimiter,纯长度前缀。
 *
 * **varint 是 YouTube 自定义格式(非标准 protobuf varint)**,按首字节高位判总字节数,little-endian:
 *  - 首字节 < 128 :1 byte,value = byte0
 *  - < 192       :2 byte,value = (b0 & 0x3F) + 64 * b1
 *  - < 224       :3 byte,value = (b0 & 0x1F) + 32 * (b1 + 256 * b2)
 *  - < 240       :4 byte,value = (b0 & 0x0F) + 16 * (b1 + 256 * (b2 + 256 * b3))
 *  - >= 240      :5 byte,b0 纯长度标记,后 4 byte 按 uint32 LE 读
 *
 * 只复用协议「形状」(googlevideo 是 MIT,biliMT 也是 MIT,但为避免引入 npm/jstranspiler 依赖,这里手写)。
 */
internal class UmpReader {
  private val buffer = CompositeBuffer()

  fun append(data: ByteArray) {
    buffer.append(data)
  }

  /**
   * 尽可能多地解析完整 part,对每个完整 part 调 [onPart]。
   * 不完整的 part(头或负载未收齐)保留在 buffer 里,等下次 [append] 后再续。
   */
  fun readParts(onPart: (type: Int, payload: ByteArray) -> Unit) {
    while (true) {
      val type = readVarint(0)
      if (type == null) break
      val (typeValue, typeLen) = type
      val size = readVarint(typeLen)
      if (size == null) break
      val (sizeValue, sizeLen) = size
      val payloadOffset = typeLen + sizeLen
      if (!buffer.canRead(payloadOffset, sizeValue)) {
        // 头已读但负载未收齐——保留全部未消费数据等下次
        break
      }
      val payload = buffer.slice(payloadOffset, sizeValue)
      buffer.drop(payloadOffset + sizeValue)
      onPart(typeValue, payload)
    }
  }

  /** 释放已缓存数据(整段读完后清空)。 */
  fun clear() = buffer.clear()

  /**
   * 从 [offset] 起(相对未消费区头)读一个自定义 varint。
   * @return (value, byteLength) 或 null(字节不足)
   */
  private fun readVarint(offset: Int): Pair<Int, Int>? {
    if (!buffer.canRead(offset, 1)) return null
    val b0 = buffer.byteAt(offset).toInt() and 0xFF
    val byteLength = when {
      b0 < 0x80 -> 1
      b0 < 0xC0 -> 2
      b0 < 0xE0 -> 3
      b0 < 0xF0 -> 4
      else -> 5
    }
    if (!buffer.canRead(offset, byteLength)) return null
    val value = when (byteLength) {
      1 -> b0
      2 -> (b0 and 0x3F) + 64 * (buffer.byteAt(offset + 1).toInt() and 0xFF)
      3 -> (b0 and 0x1F) + 32 * (
        (buffer.byteAt(offset + 1).toInt() and 0xFF) +
          256 * (buffer.byteAt(offset + 2).toInt() and 0xFF)
        )
      4 -> (b0 and 0x0F) + 16 * (
        (buffer.byteAt(offset + 1).toInt() and 0xFF) +
          256 * ((buffer.byteAt(offset + 2).toInt() and 0xFF) +
            256 * (buffer.byteAt(offset + 3).toInt() and 0xFF))
        )
      else -> {
        // 5 byte:b0 纯标记,后 4 byte uint32 LE
        (buffer.byteAt(offset + 1).toInt() and 0xFF) +
          256 * ((buffer.byteAt(offset + 2).toInt() and 0xFF) +
            256 * ((buffer.byteAt(offset + 3).toInt() and 0xFF) +
              256 * (buffer.byteAt(offset + 4).toInt() and 0xFF)))
      }
    }
    return value to byteLength
  }
}
