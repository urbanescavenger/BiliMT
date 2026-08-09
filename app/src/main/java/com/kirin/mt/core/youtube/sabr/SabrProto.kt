package com.kirin.mt.core.youtube.sabr

/**
 * SABR 协议消息编码/解码——对 googlevideo/protos(video_streaming.* + misc.FormatId)的独立 Kotlin 实现。
 *
 * 字段号/类型严格对齐上游 proto2 定义(见 docs/youtube-hd-playback.md §6.9.5/§6.9.6):
 *  - 请求:VideoPlaybackAbrRequest(field1 clientAbrState / field2 selectedFormatIds / field3 bufferedRanges /
 *    field5 videoPlaybackUstreamerConfig / field16-18 preferredFormatIds / field19 streamerContext / field1000)
 *  - 响应:UMP part payload 是标准 protobuf 消息(MediaHeader/StreamProtectionStatus/SabrError/...)
 *
 * 只复用协议「形状」(googlevideo 是 MIT)。
 */
internal object SabrProto {

  // ---- UMPPartId 枚举数值(见 ump_part_id.proto)----
  const val PART_MEDIA_HEADER = 20
  const val PART_MEDIA = 21
  const val PART_MEDIA_END = 22
  const val PART_NEXT_REQUEST_POLICY = 35
  const val PART_FORMAT_INITIALIZATION_METADATA = 42
  const val PART_SABR_REDIRECT = 43
  const val PART_SABR_ERROR = 44
  const val PART_RELOAD_PLAYER_RESPONSE = 46
  const val PART_SABR_CONTEXT_UPDATE = 57
  const val PART_STREAM_PROTECTION_STATUS = 58
  const val PART_SABR_CONTEXT_SENDING_POLICY = 59
  const val PART_END_OF_TRACK = 62
  const val PART_SNACKBAR_MESSAGE = 67

  // ---- FormatId(misc/common.proto):itag(int32)/last_modified(uint64)/xtags(string) ----
  fun encodeFormatId(itag: Int, lastModified: Long, xtags: String?): ByteArray {
    val w = ProtoWriter()
    if (itag != 0) w.int32(1, itag)
    if (lastModified != 0L) w.varint(2, lastModified)
    if (!xtags.isNullOrEmpty()) w.string(3, xtags)
    return w.bytes()
  }

  // ---- ClientAbrState(client_abr_state.proto)关键字段 ----
  fun encodeClientAbrState(s: ClientAbrStateInput): ByteArray {
    val w = ProtoWriter()
    s.timeSinceLastManualFormatSelectionMs?.let { w.varint(13, it) }
    s.lastManualSelectedResolution?.let { w.int32(16, it) }
    s.clientViewportWidth?.let { w.int32(18, it) }
    s.clientViewportHeight?.let { w.int32(19, it) }
    s.stickyResolution?.let { w.int32(21, it) }
    s.clientViewportIsFlexible?.let { w.bool(22, it) }
    s.bandwidthEstimate?.let { w.varint(23, it) }
    s.playerTimeMs?.let { w.varint(28, it) }
    // alpha.63(对齐 LibreTube client_abr_state.proto):补 29/34/36/39,缺这些字段曾被疑为 60s 软拒辅因。
    s.timeSinceLastSeekMs?.let { w.varint(29, it) }
    s.visibility?.let { w.int32(34, it) }
    s.playbackRate?.let { w.float(35, it) }
    s.elapsedWallTimeMs?.let { w.varint(36, it) }
    s.timeSinceLastActionMs?.let { w.varint(39, it) }
    s.enabledTrackTypesBitfield?.let { w.int32(40, it) }
    s.drcEnabled?.let { w.bool(46, it) }
    s.enableVoiceBoost?.let { w.bool(76, it) }
    return w.bytes()
  }

  // ---- StreamerContext.ClientInfo(streamer_context.proto ClientInfo) ----
  fun encodeClientInfo(c: ClientInfoInput): ByteArray {
    val w = ProtoWriter()
    c.deviceMake?.let { w.string(12, it) }
    c.deviceModel?.let { w.string(13, it) }
    c.clientName?.let { w.int32(16, it) }
    c.clientVersion?.let { w.string(17, it) }
    c.osName?.let { w.string(18, it) }
    c.osVersion?.let { w.string(19, it) }
    c.acceptLanguage?.let { w.string(21, it) }
    c.acceptRegion?.let { w.string(22, it) }
    c.screenWidthPoints?.let { w.int32(37, it) }
    c.screenHeightPoints?.let { w.int32(38, it) }
    c.screenPixelDensity?.let { w.int32(41, it) }
    c.clientFormFactor?.let { w.int32(46, it) }
    c.androidSdkVersion?.let { w.int32(64, it) }
    c.screenDensityFloat?.let { w.float(65, it) }
    c.utcOffsetMinutes?.let { w.varint(67, it) }
    c.timeZone?.let { w.string(80, it) }
    return w.bytes()
  }

  // ---- StreamerContext(streamer_context.proto) ----
  fun encodeStreamerContext(s: StreamerContextInput): ByteArray {
    val w = ProtoWriter()
    if (s.clientInfo != null) w.message(1, encodeClientInfo(s.clientInfo))
    if (s.poToken.isNotEmpty()) w.bytes(2, s.poToken)
    if (s.playbackCookie != null && s.playbackCookie.isNotEmpty()) w.bytes(3, s.playbackCookie)
    for (ctx in s.sabrContexts) {
      // SabrContext: field1 type(int32) / field2 value(bytes)
      val cw = ProtoWriter()
      cw.int32(1, ctx.type)
      if (ctx.value.isNotEmpty()) cw.bytes(2, ctx.value)
      w.message(5, cw.bytes())
    }
    for (t in s.unsentSabrContexts) w.int32(6, t)
    return w.bytes()
  }

  // ---- BufferedRange(buffered_range.proto) ----
  fun encodeBufferedRange(r: BufferedRangeInput): ByteArray {
    val w = ProtoWriter()
    w.message(1, encodeFormatId(r.itag, r.lastModified, r.xtags)) // format_id(field1)
    w.varint(2, r.startTimeMs)
    w.varint(3, r.durationMs)
    w.int32(4, r.startSegmentIndex)
    w.int32(5, r.endSegmentIndex)
    if (r.timeRange != null) {
      val tw = ProtoWriter()
      tw.varint(1, r.timeRange.startTicks)
      tw.varint(2, r.timeRange.durationTicks)
      r.timeRange.timescale?.let { tw.int32(3, it) }
      w.message(6, tw.bytes())
    }
    return w.bytes()
  }

  /**
   * 编码 VideoPlaybackAbrRequest(video_playback_abr_request.proto)。
   * @param s 请求输入;空/无意义的可选字段跳过(proto2 optional 语义)。
   */
  fun encodeVideoPlaybackAbrRequest(s: SabrRequestInput): ByteArray {
    val w = ProtoWriter()
    // field1 client_abr_state
    if (s.clientAbrState != null) w.message(1, encodeClientAbrState(s.clientAbrState))
    // field2 selected_format_ids(repeated;isInit 时空,否则 [audio,video])
    for (f in s.selectedFormatIds) w.message(2, f)
    // field3 buffered_ranges(repeated)
    for (r in s.bufferedRanges) w.message(3, encodeBufferedRange(r))
    // field4 player_time_ms
    s.playerTimeMs?.let { w.varint(4, it) }
    // field5 video_playback_ustreamer_config(bytes,opaque 透传)
    if (s.videoPlaybackUstreamerConfig.isNotEmpty()) w.bytes(5, s.videoPlaybackUstreamerConfig)
    // field16/17/18 preferred audio/video/subtitle format_ids(repeated)
    for (f in s.preferredAudioFormatIds) w.message(16, f)
    for (f in s.preferredVideoFormatIds) w.message(17, f)
    for (f in s.preferredSubtitleFormatIds) w.message(18, f)
    // field19 streamer_context
    if (s.streamerContext != null) w.message(19, encodeStreamerContext(s.streamerContext))
    return w.bytes()
  }

  // ===================== 响应 part 解码 =====================

  /** MediaHeader(media_header.proto):匹配 formatId/itag/lastModified/xtags + isInitSeg/sequenceNumber 取 headerId。 */
  data class MediaHeader(
    val headerId: Int,
    val videoId: String?,
    val itag: Int,
    val lmt: Long,
    val xtags: String?,
    val isInitSeg: Boolean,
    val sequenceNumber: Int,
    val contentLength: Long,
    val durationMs: Long,
    val startMs: Long,
  )

  fun decodeMediaHeader(payload: ByteArray): MediaHeader? {
    val r = ProtoReader(payload)
    var headerId = 0; var videoId: String? = null; var itag = 0; var lmt = 0L
    var xtags: String? = null; var isInitSeg = false; var seq = 0
    var contentLength = 0L; var durationMs = 0L; var startMs = 0L
    while (true) {
      val f = r.nextField() ?: break
      when (f.fieldNumber) {
        1 -> headerId = (f.value as Long).toInt()
        2 -> videoId = String(f.value as ByteArray, Charsets.UTF_8)
        3 -> itag = (f.value as Long).toInt()
        4 -> lmt = f.value as Long
        5 -> xtags = String(f.value as ByteArray, Charsets.UTF_8)
        8 -> isInitSeg = (f.value as Long) != 0L
        9 -> seq = (f.value as Long).toInt()
        11 -> startMs = f.value as Long
        12 -> durationMs = f.value as Long
        14 -> contentLength = f.value as Long
      }
    }
    return MediaHeader(headerId, videoId, itag, lmt, xtags, isInitSeg, seq, contentLength, durationMs, startMs)
  }

  /** StreamProtectionStatus:status==3 → PO token 无效。 */
  fun decodeStreamProtectionStatus(payload: ByteArray): Int {
    val r = ProtoReader(payload)
    while (true) {
      val f = r.nextField() ?: break
      if (f.fieldNumber == 1) return (f.value as Long).toInt()
    }
    return 0
  }

  data class SabrError(val type: String?, val code: Int)
  fun decodeSabrError(payload: ByteArray): SabrError? {
    val r = ProtoReader(payload)
    var type: String? = null; var code = 0
    while (true) {
      val f = r.nextField() ?: break
      when (f.fieldNumber) {
        1 -> type = String(f.value as ByteArray, Charsets.UTF_8)
        2 -> code = (f.value as Long).toInt()
      }
    }
    return SabrError(type, code)
  }

  fun decodeSabrRedirect(payload: ByteArray): String? {
    val r = ProtoReader(payload)
    while (true) {
      val f = r.nextField() ?: break
      if (f.fieldNumber == 1) return String(f.value as ByteArray, Charsets.UTF_8)
    }
    return null
  }

  /**
   * alpha.41:解码 RELOAD_PLAYER_RESPONSE(part 46)。FreeTube 语义「whole video cannot be played →
   * reload /player」——服务端明令重载 player response(取新 formats/poToken/sabrUrl),**非** backoff。
   * alpha.40 真机:video 流 init 每次都收到 144B 的 part 46 + STREAM_PROTECTION_STATUS status=1 +
   * NEXT_REQUEST_POLICY,但 audio 流同会话正常拿 media → 服务端针对 video 格式/会话要求 reload。
   *
   * proto 结构 youtube 侧未公开,故**不假定字段名**——通用遍历顶层 field,逐个打 fieldNumber/wireType/
   * 值预览(varint 数 / length-delimited 的 len+hex 头 + 可打印则 UTF-8),加全量 hexdump。一次真机即可
   * 判:payload 是否带新 sabrUrl(field=str,googlevideo.com)+ 新 format 列表 / 原因码,
   * 从而区分「video itag 398 formatId 过期」(根因 A) vs「session 已初始化」(根因 B)。
   */
  fun decodeReloadPlayerResponse(payload: ByteArray): ReloadPlayerDiag {
    val fields = StringBuilder()
    val r = ProtoReader(payload)
    while (true) {
      val f = r.nextField() ?: break
      if (fields.isNotEmpty()) fields.append(' ')
      fields.append("f").append(f.fieldNumber).append('(').append(wireName(f.wireType)).append("):")
      when (f.wireType) {
        ProtoWire.WIRE_VARINT, ProtoWire.WIRE_64 -> fields.append(f.value as Long)
        ProtoWire.WIRE_32 -> fields.append(f.value as Int)
        ProtoWire.WIRE_LEN -> {
          val b = f.value as ByteArray
          fields.append('[').append(b.size).append("B]")
          val printable = b.isNotEmpty() && b.all { it.toInt() and 0xFF in 32..126 }
          if (printable) fields.append(" str=\"").append(String(b, Charsets.UTF_8).take(64)).append('"')
          fields.append(" hex=").append(hexHead(b, 16))
        }
      }
    }
    return ReloadPlayerDiag(fieldsSummary = fields.toString(), hexDump = hexHead(payload, payload.size))
  }

  private fun wireName(w: Int): String = when (w) {
    0 -> "varint"; 1 -> "i64"; 2 -> "len"; 5 -> "i32"; else -> "?$w"
  }

  private fun hexHead(b: ByteArray, max: Int): String {
    val n = minOf(b.size, max)
    val sb = StringBuilder(n * 2)
    for (i in 0 until n) sb.append("%02x".format(b[i].toInt() and 0xFF))
    if (b.size > max) sb.append("..(+").append(b.size - max).append("B)")
    return sb.toString()
  }

  data class ReloadPlayerDiag(val fieldsSummary: String, val hexDump: String)

  /**
   * alpha.31:解码 SABR_CONTEXT_UPDATE(part type 57)。服务端借此下发上下文,要求客户端
   * 把 {type, value} 回传进下次请求 streamerContext.sabr_contexts(field5,sendByDefault=true
   * 的才 active)+ unsent_sabr_contexts(field6,非 active 的只回 type id)。不回传 → 握手不闭合
   * → 服务端只回 context+backoff 不发 media → premature EOF(alpha.29/30 打不开视频根因)。
   * 对齐 FreeTube SabrSchemePlugin.js L430-447 + sabr_context_update.proto:
   *   field1 type(int32) / field2 scope(enum,不用) / field3 value(bytes) /
   *   field4 send_by_default(bool) / field5 write_policy(enum: UNSPECIFIED=0/OVERWRITE=1/KEEP_EXISTING=2)。
   */
  data class SabrContextUpdate(
    val type: Int,
    val value: ByteArray,
    val sendByDefault: Boolean,
    val writePolicy: Int,
  )
  fun decodeSabrContextUpdate(payload: ByteArray): SabrContextUpdate? {
    val r = ProtoReader(payload)
    var type = 0; var value: ByteArray = ByteArray(0)
    var sendByDefault = false; var writePolicy = 0
    while (true) {
      val f = r.nextField() ?: break
      when (f.fieldNumber) {
        1 -> type = (f.value as Long).toInt()
        3 -> value = f.value as ByteArray
        4 -> sendByDefault = (f.value as Long) != 0L
        5 -> writePolicy = (f.value as Long).toInt()
      }
    }
    return SabrContextUpdate(type, value, sendByDefault, writePolicy)
  }

  /**
   * alpha.31:解码 SABR_CONTEXT_SENDING_POLICY(part type 59)。服务端动态控制哪些上下文
   * 该发(start)/停发(stop)/丢弃(discard)。对齐 sabr_context_sending_policy.proto:
   *   field1 start_policy(repeated int32) / field2 stop_policy / field3 discard_policy。
   * 当前服务端未发 59(active 集合只由 57 的 sendByDefault 驱动),补全状态机对齐 FreeTube L450-470。
   */
  data class SabrContextSendingPolicy(
    val start: List<Int>,
    val stop: List<Int>,
    val discard: List<Int>,
  )
  fun decodeSabrContextSendingPolicy(payload: ByteArray): SabrContextSendingPolicy? {
    val r = ProtoReader(payload)
    val start = ArrayList<Int>(); val stop = ArrayList<Int>(); val discard = ArrayList<Int>()
    while (true) {
      val f = r.nextField() ?: break
      // repeated int32 在 proto wire 里每个元素都是独立 varint field;但 YouTube/googlevideo 的
      // protobuf 打包 repeated scalar 常用 packed 形态(wire 2, 一个 length 前缀包多个 varint)。
      // 两种都兼容:非 packed → f.value 是 Long;packed → f.value 是 ByteArray 需展开。
      when (f.fieldNumber) {
        1 -> addInts(f, start)
        2 -> addInts(f, stop)
        3 -> addInts(f, discard)
      }
    }
    return SabrContextSendingPolicy(start, stop, discard)
  }

  /** repeated int32 兼容 packed(wire2, ByteArray 内多无 tag varint)与 non-packed(wire0, 单 Long)。 */
  private fun addInts(f: ProtoReader.Field, out: ArrayList<Int>) {
    when (val v = f.value) {
      is Long -> out.add(v.toInt())
      is ByteArray -> {
        // packed: concatenated standard varints, no tags
        var i = 0
        while (i < v.size) {
          var result = 0L; var shift = 0
          while (i < v.size) {
            val b = v[i].toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
          }
          out.add(result.toInt())
        }
      }
    }
  }


  // ===================== 请求体解码(alpha.26) =====================
  // 从 WebView harvest 到的 VideoPlaybackAbrRequest body 解出建 SabrSession 所需的会话参数:
  // poToken(StreamerContext.field2)、ustreamerConfig(field5)、preferredAudio/VideoFormatId(field16/17[0])。
  // 这些是浏览器(watch 页)会话绑定的值——必须原样用,不能换我们 /player 的(会话不一致→服务端只回 context)。

  /** 从 harvested body 解出的会话参数(用于建 SabrSession 驱动 init/segment)。 */
  data class DecodedAbrRequest(
    val poToken: ByteArray,
    val ustreamerConfig: ByteArray,
    val audioFormatId: FormatIdLite?,
    val videoFormatId: FormatIdLite?,
  )
  data class FormatIdLite(val itag: Int, val lastModified: Long, val xtags: String?)

  fun decodeVideoPlaybackAbrRequest(body: ByteArray): DecodedAbrRequest {
    val r = ProtoReader(body)
    var ustreamerConfig: ByteArray = ByteArray(0)
    var audioFmt: FormatIdLite? = null
    var videoFmt: FormatIdLite? = null
    var poToken: ByteArray = ByteArray(0)
    while (true) {
      val f = r.nextField() ?: break
      when (f.fieldNumber) {
        5 -> ustreamerConfig = f.value as ByteArray
        16 -> if (audioFmt == null) audioFmt = decodeFormatIdLite(f.value as ByteArray)
        17 -> if (videoFmt == null) videoFmt = decodeFormatIdLite(f.value as ByteArray)
        19 -> poToken = decodeStreamerContextPoToken(f.value as ByteArray)
      }
    }
    return DecodedAbrRequest(poToken, ustreamerConfig, audioFmt, videoFmt)
  }

  /** FormatId(itag/lastModified/xtags)——对齐 encodeFormatId 的字段号(1/2/3)。 */
  fun decodeFormatIdLite(payload: ByteArray): FormatIdLite {
    val r = ProtoReader(payload)
    var itag = 0; var lmt = 0L; var xtags: String? = null
    while (true) {
      val f = r.nextField() ?: break
      when (f.fieldNumber) {
        1 -> itag = (f.value as Long).toInt()
        2 -> lmt = f.value as Long
        3 -> xtags = String(f.value as ByteArray, Charsets.UTF_8)
      }
    }
    return FormatIdLite(itag, lmt, xtags)
  }

  /** StreamerContext.field2 = poToken(bytes)。 */
  private fun decodeStreamerContextPoToken(payload: ByteArray): ByteArray {
    val r = ProtoReader(payload)
    while (true) {
      val f = r.nextField() ?: break
      if (f.fieldNumber == 2) return f.value as ByteArray
    }
    return ByteArray(0)
  }

  /** NextRequestPolicy:backoffTimeMs + playbackCookie(用于重试)。
   *  alpha.30:[playbackCookieBytes] 是 field7 的**原始 bytes**——服务端要求客户端把它原样
   *  回传进下个请求的 StreamerContext.field3(opaque 透传,不需解码/再编码,对齐 FreeTube
   *  `streamerContext.playbackCookie = PlaybackCookie.encode(...).finish()`,但我们直接透传 raw)。 */
  data class PlaybackCookie(val resolution: Int?, val videoFmt: Pair<Int, Long>?, val audioFmt: Pair<Int, Long>?)
  data class NextRequestPolicy(
    val backoffTimeMs: Int,
    val playbackCookie: PlaybackCookie?,
    /** alpha.30:field7 原始 bytes,供 StreamerContext.field3 回传(opaque 透传)。 */
    val playbackCookieBytes: ByteArray?,
    val videoId: String?,
    /** alpha.38 诊断:服务端流控上限字段(next_request_policy.proto)——
     * target_*_readahead_ms(1/2,期望缓冲量)、max_time_since_last_request_ms(3,请求间最大间隔)、
     * min_*_readahead_ms(5/6,最低缓冲)。全解码**只为打日志**——看 60s 软拒是不是我们超前缓冲触发
     * (target readahead vs 实际 lead)、或请求间隔超 max_time。不参与请求构造。 */
    val targetAudioReadaheadMs: Int?,
    val targetVideoReadaheadMs: Int?,
    val maxTimeSinceLastRequestMs: Int?,
    val minAudioReadaheadMs: Int?,
    val minVideoReadaheadMs: Int?,
  )
  fun decodeNextRequestPolicy(payload: ByteArray): NextRequestPolicy? {
    val r = ProtoReader(payload)
    var backoff = 0; var videoId: String? = null; var cookieBytes: ByteArray? = null
    var targetA: Int? = null; var targetV: Int? = null; var maxTime: Int? = null
    var minA: Int? = null; var minV: Int? = null
    while (true) {
      val f = r.nextField() ?: break
      when (f.fieldNumber) {
        1 -> targetA = (f.value as Long).toInt()
        2 -> targetV = (f.value as Long).toInt()
        3 -> maxTime = (f.value as Long).toInt()
        4 -> backoff = (f.value as Long).toInt()
        5 -> minA = (f.value as Long).toInt()
        6 -> minV = (f.value as Long).toInt()
        7 -> cookieBytes = f.value as ByteArray
        8 -> videoId = String(f.value as ByteArray, Charsets.UTF_8)
      }
    }
    val cookie = cookieBytes?.let { decodePlaybackCookie(it) }
    return NextRequestPolicy(backoff, cookie, cookieBytes, videoId, targetA, targetV, maxTime, minA, minV)
  }

  private fun decodePlaybackCookie(payload: ByteArray): PlaybackCookie? {
    val r = ProtoReader(payload)
    var resolution: Int? = null; var videoFmt: Pair<Int, Long>? = null; var audioFmt: Pair<Int, Long>? = null
    while (true) {
      val f = r.nextField() ?: break
      when (f.fieldNumber) {
        1 -> resolution = (f.value as Long).toInt()
        7 -> videoFmt = decodeFmtIdLite(f.value as ByteArray)
        8 -> audioFmt = decodeFmtIdLite(f.value as ByteArray)
      }
    }
    return PlaybackCookie(resolution, videoFmt, audioFmt)
  }

  private fun decodeFmtIdLite(payload: ByteArray): Pair<Int, Long>? {
    val r = ProtoReader(payload)
    var itag = 0; var lm = 0L
    while (true) {
      val f = r.nextField() ?: break
      when (f.fieldNumber) {
        1 -> itag = (f.value as Long).toInt()
        2 -> lm = f.value as Long
      }
    }
    return itag to lm
  }
}

// ===================== 输入数据类(供 SabrDataSource 填充) =====================

internal data class FormatId(val itag: Int, val lastModified: Long, val xtags: String?, val height: Int = 0)

internal data class ClientAbrStateInput(
  val timeSinceLastManualFormatSelectionMs: Long? = null,
  val lastManualSelectedResolution: Int? = null,
  val clientViewportWidth: Int? = null,
  val clientViewportHeight: Int? = null,
  val stickyResolution: Int? = null,
  val clientViewportIsFlexible: Boolean? = null,
  val bandwidthEstimate: Long? = null,
  val playerTimeMs: Long? = null,
  /** alpha.63(对齐 LibreTube client_abr_state.proto):time_since_last_seek=29,首播/无 seek 发 0。 */
  val timeSinceLastSeekMs: Long? = null,
  /** alpha.63:visibility=34(1=可见,LibreTube setVisibility(1))。 */
  val visibility: Int? = null,
  val playbackRate: Float? = null,
  /** alpha.63:elapsed_wall_time_ms=36,距上次请求墙钟(LibreTube setElapsedWallTimeMs)。 */
  val elapsedWallTimeMs: Long? = null,
  /** alpha.63:time_since_last_action_ms=39,无操作发 0(LibreTube setTimeSinceLastActionMs)。 */
  val timeSinceLastActionMs: Long? = null,
  val enabledTrackTypesBitfield: Int? = null,
  val drcEnabled: Boolean? = null,
  val enableVoiceBoost: Boolean? = null,
)

internal data class ClientInfoInput(
  val deviceMake: String? = null,
  val deviceModel: String? = null,
  val clientName: Int? = null,        // proto field16 是 int32(youtubei.js clientName 通常字符串,但这里 proto 是 int32——见上游)
  val clientVersion: String? = null,
  val osName: String? = null,
  val osVersion: String? = null,
  val acceptLanguage: String? = null,
  val acceptRegion: String? = null,
  val screenWidthPoints: Int? = null,
  val screenHeightPoints: Int? = null,
  val screenPixelDensity: Int? = null,
  val clientFormFactor: Int? = null,
  val androidSdkVersion: Int? = null,
  val screenDensityFloat: Float? = null,
  val utcOffsetMinutes: Long? = null,
  val timeZone: String? = null,
)

internal data class SabrContext(val type: Int, val value: ByteArray)

internal data class StreamerContextInput(
  val clientInfo: ClientInfoInput?,
  val poToken: ByteArray,
  val playbackCookie: ByteArray? = null,
  val sabrContexts: List<SabrContext> = emptyList(),
  val unsentSabrContexts: List<Int> = emptyList(),
)

internal data class BufferedRangeInput(
  val itag: Int,
  val lastModified: Long,
  val xtags: String?,
  val startTimeMs: Long,
  val durationMs: Long,
  val startSegmentIndex: Int,
  val endSegmentIndex: Int,
  val timeRange: TimeRangeInput? = null,
)

internal data class TimeRangeInput(val startTicks: Long, val durationTicks: Long, val timescale: Int? = null)

internal data class SabrRequestInput(
  val clientAbrState: ClientAbrStateInput?,
  val selectedFormatIds: List<ByteArray>,          // 已 encode 的 FormatId bytes
  val bufferedRanges: List<BufferedRangeInput>,
  val playerTimeMs: Long?,
  val videoPlaybackUstreamerConfig: ByteArray,    // base64-decoded 子层 bytes
  val preferredAudioFormatIds: List<ByteArray>,
  val preferredVideoFormatIds: List<ByteArray>,
  val preferredSubtitleFormatIds: List<ByteArray>,
  val streamerContext: StreamerContextInput?,
)
