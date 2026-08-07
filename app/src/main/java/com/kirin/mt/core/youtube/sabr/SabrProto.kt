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
    s.playbackRate?.let { w.float(35, it) }
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

  /** NextRequestPolicy:backoffTimeMs + playbackCookie(用于重试)。 */
  data class PlaybackCookie(val resolution: Int?, val videoFmt: Pair<Int, Long>?, val audioFmt: Pair<Int, Long>?)
  data class NextRequestPolicy(
    val backoffTimeMs: Int,
    val playbackCookie: PlaybackCookie?,
    val videoId: String?,
  )
  fun decodeNextRequestPolicy(payload: ByteArray): NextRequestPolicy? {
    val r = ProtoReader(payload)
    var backoff = 0; var videoId: String? = null; var cookieBytes: ByteArray? = null
    while (true) {
      val f = r.nextField() ?: break
      when (f.fieldNumber) {
        4 -> backoff = (f.value as Long).toInt()
        7 -> cookieBytes = f.value as ByteArray
        8 -> videoId = String(f.value as ByteArray, Charsets.UTF_8)
      }
    }
    val cookie = cookieBytes?.let { decodePlaybackCookie(it) }
    return NextRequestPolicy(backoff, cookie, videoId)
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
  val playbackRate: Float? = null,
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
