package com.kirin.mt.core.youtube.sabr

import java.io.ByteArrayOutputStream

/**
 * 标准 Protocol Buffers wire format 读写器(proto2 兼容)。
 *
 * **注意**:与 [UmpReader] 里的 YouTube 自定义 varint 不同——protobuf 消息体(VideoPlaybackAbrRequest
 * 及 UMP part 的 payload)用**标准 protobuf varint**(7 bit/byte,MSB 续位,little-endian),
 * field key = (fieldNumber << 3) | wireType。
 *
 * wire type:0=varint,1=64-bit,2=length-delimited,5=32-bit。
 */
internal object ProtoWire {
  const val WIRE_VARINT = 0
  const val WIRE_64 = 1
  const val WIRE_LEN = 2
  const val WIRE_32 = 5

  /** 标准 protobuf varint(7 bit/byte,MSB 续位)。 */
  fun encodeVarint(value: Long): ByteArray {
    val out = ByteArrayOutputStream(10)
    var v = value
    while (v ushr 7 != 0L) {
      out.write(((v and 0x7F) or 0x80).toInt())
      v = v ushr 7
    }
    out.write(v.toInt())
    return out.toByteArray()
  }

  /** tag = (fieldNumber << 3) | wireType。 */
  fun encodeTag(fieldNumber: Int, wireType: Int): ByteArray = encodeVarint(((fieldNumber.toLong()) shl 3) or wireType.toLong())
}

/** protobuf 写入器:按 field number + 类型顺序写入,空字段跳过(对齐 proto2 optional 语义)。 */
internal class ProtoWriter {
  private val out = ByteArrayOutputStream(256)

  fun bytes(): ByteArray = out.toByteArray()

  private fun tag(fieldNumber: Int, wireType: Int) {
    out.write(ProtoWire.encodeTag(fieldNumber, wireType))
  }

  /** wire 0:varint(int32/int64/uint64/bool/enum)。 */
  fun varint(fieldNumber: Int, value: Long) {
    tag(fieldNumber, ProtoWire.WIRE_VARINT)
    out.write(ProtoWire.encodeVarint(value))
  }

  fun int32(fieldNumber: Int, value: Int) = varint(fieldNumber, value.toLong() and 0xFFFFFFFFL)

  fun bool(fieldNumber: Int, value: Boolean) = varint(fieldNumber, if (value) 1L else 0L)

  /** wire 5:32-bit fixed(float 用其 IEEE-754 整数表示)。 */
  fun float(fieldNumber: Int, value: Float) {
    tag(fieldNumber, ProtoWire.WIRE_32)
    val bits = java.lang.Float.floatToIntBits(value)
    out.write(bits and 0xFF)
    out.write((bits ushr 8) and 0xFF)
    out.write((bits ushr 16) and 0xFF)
    out.write((bits ushr 24) and 0xFF)
  }

  /** wire 2:length-delimited(bytes/string/嵌套 message)。 */
  fun bytes(fieldNumber: Int, value: ByteArray) {
    tag(fieldNumber, ProtoWire.WIRE_LEN)
    out.write(ProtoWire.encodeVarint(value.size.toLong()))
    out.write(value)
  }

  fun string(fieldNumber: Int, value: String) = bytes(fieldNumber, value.toByteArray(Charsets.UTF_8))

  /** 嵌套 message:先 encode 子消息拿到 bytes,再按 wire 2 写入。 */
  fun message(fieldNumber: Int, encoded: ByteArray) = bytes(fieldNumber, encoded)

  /** repeated message:对每个元素各写一个 tag+length+bytes。 */
  fun repeatedMessage(fieldNumber: Int, elements: List<ByteArray>) {
    for (e in elements) message(fieldNumber, e)
  }
}

/** protobuf 读取器:遍历 field,按 field number + wire type 取值。 */
internal class ProtoReader(private val data: ByteArray, private val start: Int = 0, private val end: Int = data.size) {
  private var pos = start

  fun hasMore(): Boolean = pos < end

  private fun readVarint(): Long {
    var result = 0L
    var shift = 0
    while (pos < end) {
      val b = data[pos].toInt() and 0xFF
      pos++
      result = result or ((b and 0x7F).toLong() shl shift)
      if (b and 0x80 == 0) return result
      shift += 7
      if (shift >= 64) break
    }
    return result
  }

  /** 返回下一个 field 的 (fieldNumber, wireType),或 null 表示读完。 */
  fun nextField(): Field? {
    if (!hasMore()) return null
    val key = readVarint()
    val fieldNumber = (key ushr 3).toInt()
    val wireType = (key and 0x7).toInt()
    val value: Any = when (wireType) {
      ProtoWire.WIRE_VARINT -> readVarint()
      ProtoWire.WIRE_64 -> {
        val v = readFixed64(); v
      }
      ProtoWire.WIRE_LEN -> {
        val len = readVarint().toInt()
        val s = pos
        // alpha.16:补 `len < 0`——malformed varint 溢出成负 int 时,`s + len` 可能 < s(负),
        // 原 `s + len > end` 判不中 → copyOfRange(s, s+len) 抛 IllegalArgumentException(from>to)
        // / ArrayIndexOutOfBounds(真机 RELOAD 间歇 `decode failed: 34 > -618552673` / `length=37; index=37`)。
        // 与正越界同处理:跳到结尾返回空字节,不 copyOfRange。
        if (len < 0 || s + len > end) {
          pos = end
          ByteArray(0)
        } else {
          pos += len
          data.copyOfRange(s, s + len)
        }
      }
      ProtoWire.WIRE_32 -> readFixed32()
      else -> 0L
    }
    return Field(fieldNumber, wireType, value)
  }

  private fun readFixed64(): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((data[pos + i].toInt() and 0xFF).toLong() shl (8 * i))
    pos += 8
    return v
  }

  private fun readFixed32(): Int {
    var v = 0
    for (i in 0 until 4) v = v or ((data[pos + i].toInt() and 0xFF) shl (8 * i))
    pos += 4
    return v
  }

  /** 从 length-delimited value 取子 reader。 */
  fun subReader(field: Field): ProtoReader {
    val bytes = field.value as ByteArray
    return ProtoReader(bytes, 0, bytes.size)
  }

  class Field(val fieldNumber: Int, val wireType: Int, val value: Any)
}
