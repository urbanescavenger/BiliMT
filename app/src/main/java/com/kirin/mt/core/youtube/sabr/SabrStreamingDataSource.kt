package com.kirin.mt.core.youtube.sabr

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.runBlocking

/**
 * 把 Media3 的 progressive 读取请求翻译成 [SabrClient] 的 init/segment 段请求。
 *
 * SABR 段序列:seq=0 init(fMP4 ftyp+moov)→ seq=1,2,… 每 ~6s(moof+mdat fragment)。
 * 本 DataSource 在 [open] 拉 init, [read] 逐段拉取并拼接成连续字节流——
 * ProgressiveMediaSource 的 MP4 extractor 把 init+fragments 当 fragmented MP4 解析。
 *
 * open() 返回 [C.LENGTH_UNSET](未知总长 → 不可 seek;MVP 接受)。
 * Redirect → 更新 session.sabrUrl 重试同请求;Backoff → sleep 重试;
 * EndOfTrack/Error/InvalidPoToken → read 返回 [C.RESULT_END_OF_INPUT](EOF)。
 *
 * runBlocking 桥接 suspend fetch:播放器 loader 线程是后台线程,阻塞网络是
 * progressive DataSource 模型常态(同 OkHttpDataSource 阻塞 read)。
 */
internal class SabrStreamingDataSource(
  private val sessionId: String,
  private val streamType: SabrStreamType,
  /** alpha.29:本流要播的视频 itag(null=会话默认 videoFormatId)。来自 sabr:// URL 的 `&itag=`。 */
  private val requestedItag: Int?,
  /**
   * alpha.34:续播/切清晰度的起始 playerTimeMs(来自 sabr:// URL 的 `&startMs=`)。
   * open() 把它置进 [cumulativeDurationMs],使首段请求带 playerTimeMs=startMs → 服务端从续播点发段,
   * 续播由协议层完成而非 ExoPlayer seekTo(对 LENGTH_UNSET 不可 seek 的 SABR 源,seekTo 会取消
   * 正在飞的 fetch 重开 DataSource,init 喂两遍给 MatroskaExtractor → "Multiple Segment elements
   * not supported" 崩)。0=从头播(首播),等同原行为。
   */
  private val startMs: Long = 0L,
) : DataSource {
  private val tag = "YtSabr"
  private var entry: SabrStreamRegistry.Entry? = null
  private var currentUri: Uri? = null
  /** 当前段字节缓冲(init 段 + 各 media 段轮流替换)。 */
  private var buffer: ByteArray = ByteArray(0)
  private var bufferPos: Int = 0
  /** 下一段 seq(init 段后从 1 起)。 */
  private var nextSeq: Int = 1
  private var done: Boolean = false
  /** alpha.28:已取段的累计 durationMs——作下次请求的 playerTimeMs + bufferedRange 终点,
   * 推动服务端 lookahead 窗口前移(否则只发初始 ~4 段就 premature EOF → 黑屏)。init 段 dur=0 不累计。 */
  private var cumulativeDurationMs: Long = 0L

  override fun open(dataSpec: DataSpec): Long {
    currentUri = dataSpec.uri
    val e = SabrStreamRegistry.get(sessionId)
      ?: run {
        Log.w(tag, "SabrStream open: session NOT FOUND sid=$sessionId → throw")
        throw java.io.IOException("SABR session not found: $sessionId")
      }
    entry = e
    Log.i(tag, "SabrStream open sid=$sessionId stream=$streamType sabrUrl=${e.session.sabrUrl.take(80)}... startMs=$startMs")
    // init 段(seq=0,isInit=true);init durationMs=0。alpha.29:带 requestedItag 让服务端按该 itag 发对应清晰度的 init(视频流)。
    // alpha.34:续播点 startMs 置进 cumulativeDurationMs——首段请求带 playerTimeMs=startMs,
    // 服务端从续播点发段(0=从头播,等同原行为);绕开 ExoPlayer.seekTo(对 LENGTH_UNSET 不可 seek 的
    // SABR 源,seekTo 会取消 fetch 重开 DataSource 喂双 init 致 MatroskaExtractor "Multiple Segment
    // elements not supported" 崩)。
    val initResult = fetchUntilReady(SabrFetchRequest(isInit = true, streamType = streamType, videoItag = requestedItag))
      ?: run {
        Log.w(tag, "SabrStream open: init fetch failed sid=$sessionId stream=$streamType → throw")
        throw java.io.IOException("SABR init fetch failed: sid=$sessionId stream=$streamType")
      }
    buffer = initResult.data
    bufferPos = 0
    cumulativeDurationMs = startMs + (initResult.mediaHeader?.durationMs ?: 0L)
    return C.LENGTH_UNSET.toLong()
  }

  override fun read(target: ByteArray, offset: Int, length: Int): Int {
    if (done) return C.RESULT_END_OF_INPUT
    val e = entry ?: return C.RESULT_END_OF_INPUT
    while (bufferPos >= buffer.size) {
      // 当前段读完,拉下一段。alpha.28:带 playerTimeMs + bufferedRange(已缓冲 0..cumulative)
      // 让服务端 lookahead 窗口前移持续发新段(否则初始 ~4 段后 premature EOF → 黑屏)。
      val seg = fetchUntilReady(buildSegRequest(e))
      if (seg == null) {
        done = true
        Log.i(tag, "SabrStream read EOF sid=$sessionId stream=$streamType at seq=$nextSeq playerTimeMs=$cumulativeDurationMs (no more segments)")
        return C.RESULT_END_OF_INPUT
      }
      buffer = seg.data
      bufferPos = 0
      cumulativeDurationMs += seg.mediaHeader?.durationMs ?: 0L
      nextSeq++
    }
    val toCopy = minOf(length, buffer.size - bufferPos)
    System.arraycopy(buffer, bufferPos, target, offset, toCopy)
    bufferPos += toCopy
    return toCopy
  }

  override fun getUri(): Uri? = currentUri

  override fun addTransferListener(transferListener: TransferListener) {
    // SABR 流不走 ExoPlayer 的数据转移/带宽估计监听(alpha.27 MVP);http 流由外层
    // SabrAwareDataSource 转发给 OkHttp delegate。
  }

  override fun close() {
    Log.i(tag, "SabrStream close sid=$sessionId stream=$streamType (nextSeq=$nextSeq done=$done)")
    // 不 release session:另一条流(video/audio)可能仍在读;registry entry 由进程退出回收。
    done = true
    buffer = ByteArray(0)
    bufferPos = 0
  }

  /**
   * 构造下一段请求:playerTimeMs = 已缓冲终点(cumulativeDurationMs),bufferedRange = [0, cumulative]。
   * 服务端据此 lookahead 窗口发下一段(init 后 cumulative=0 时跳过 bufferedRange,等同 alpha.27 行为)。 */
  private fun buildSegRequest(e: SabrStreamRegistry.Entry): SabrFetchRequest {
    // alpha.29:视频流按 requestedItag 取 FormatId(poToken 会话级不绑 itag);audio 用会话默认。
    val fmt = if (streamType == SabrStreamType.AUDIO) e.session.audioFormatId
      else requestedItag?.let { e.session.videoFormat(it) } ?: e.session.videoFormatId
    val br = if (cumulativeDurationMs > 0L) BufferedRangeInput(
      itag = fmt.itag,
      lastModified = fmt.lastModified,
      xtags = fmt.xtags,
      startTimeMs = 0L,
      durationMs = cumulativeDurationMs,
      startSegmentIndex = 0,
      endSegmentIndex = nextSeq - 1,
      timeRange = null,
    ) else null
    return SabrFetchRequest(
      isInit = false,
      sequenceNumber = nextSeq,
      streamType = streamType,
      videoItag = requestedItag,
      playerTimeMs = cumulativeDurationMs,
      bufferedRange = br,
    )
  }

  /**
   * 发一次 SABR 请求,处理 Redirect(更新 sabrUrl 重试)/Backoff(sleep 重试);
   * Success → 返回段字节;Error/InvalidPoToken → 返回 null(由 read() 当 EOF)。
   *
   * alpha.35 诊断:60s 断流真因未定(n-throttle 限前视 / 8 次 backoff 过早 EOF / 会话硬拒 三者
   * 无法从现日志区分)。改「8 次 backoff 即 EOF」为「8 次内层耗尽后长睡 [BACKOFF_LONG_WAIT_MS]
   * 整轮重试,最多 [BACKOFF_OUTER_ROUNDS] 轮」(~84s 墙钟预算),每次重试打 elapsed/bufferedEnd。
   * 判读:
   *  - 某轮 RESUMED → 服务端在实时推进后恢复发段 = flow-control(n-throttle 限前视,非硬拒)。
   *  - 全 outer 轮 backoff 不恢复 → 「server never resumed」= 会话硬拒/真 EOF。
   * 请求数有界(≤ outer×8,带长睡间隔,不轰服务器)。诊断结论到手后回退或据此根修。
   * Redirect → 更新 sabrUrl 重试同请求;Error/InvalidPoToken → 立即返回 null(EOF)。
   */
  private fun fetchUntilReady(req: SabrFetchRequest): SabrFetchResult.Success? {
    val e = entry ?: return null
    val fetchStartMs = System.currentTimeMillis()
    var outer = 0
    while (outer < BACKOFF_OUTER_ROUNDS) {
      outer++
      var attempt = 0
      while (attempt < 8) {
        attempt++
        val result = runBlocking { e.client.fetch(e.session, req) }
        when (result) {
          is SabrFetchResult.Success -> {
            val elapsedMs = System.currentTimeMillis() - fetchStartMs
            // 重试过才打 RESUMED(首请求成功不必)——拿「恢复耗时」+ bufferedEnd 算超前差。
            if (outer > 1 || attempt > 1) {
              Log.i(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} RESUMED after ${elapsedMs}ms (outer=$outer attempt=$attempt bufferedEnd=$cumulativeDurationMs)")
            }
            Log.i(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} isInit=${req.isInit} bytes=${result.data.size}B dur=${result.mediaHeader?.durationMs}ms playerTimeMs=${req.playerTimeMs}")
            return result
          }
          is SabrFetchResult.Redirect -> {
            Log.i(tag, "SabrStream sid=$sessionId stream=$streamType Redirect → ${result.sanitized} (applyRedirect + refetch same seq)")
            e.session.applyRedirect(result.newSabrUrl)
            continue
          }
          is SabrFetchResult.Backoff -> {
            val elapsedMs = System.currentTimeMillis() - fetchStartMs
            Log.i(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} Backoff ${result.ms}ms outer=$outer attempt=$attempt elapsed=${elapsedMs}ms bufferedEnd=$cumulativeDurationMs (sleep + refetch)")
            try { Thread.sleep(result.ms.toLong()) } catch (_: InterruptedException) {}
            continue
          }
          SabrFetchResult.InvalidPoToken -> {
            Log.w(tag, "SabrStream sid=$sessionId stream=$streamType InvalidPoToken at seq=${req.sequenceNumber} isInit=${req.isInit} → EOF")
            return null
          }
          is SabrFetchResult.Error -> {
            Log.w(tag, "SabrStream sid=$sessionId stream=$streamType Error at seq=${req.sequenceNumber} isInit=${req.isInit}: ${result.message} → EOF")
            return null
          }
        }
      }
      // alpha.35 诊断:内层 8 次 backoff 耗尽——不立即 EOF,长睡 BACKOFF_LONG_WAIT_MS 后整轮重试,
      // 看实时播放推进(墙钟推进)后服务端是否恢复发段。最后一轮不再睡,落到下方 EOF。
      if (outer < BACKOFF_OUTER_ROUNDS) {
        val elapsedMs = System.currentTimeMillis() - fetchStartMs
        Log.w(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} inner exhausted (8×Backoff) at outer=$outer elapsed=${elapsedMs}ms bufferedEnd=$cumulativeDurationMs → sleep ${BACKOFF_LONG_WAIT_MS}ms then retry whole cycle [diagnostic]")
        try {
          Thread.sleep(BACKOFF_LONG_WAIT_MS)
        } catch (_: InterruptedException) {
          Log.w(tag, "SabrStream sid=$sessionId stream=$streamType long-wait interrupted → EOF")
          return null
        }
      }
    }
    val totalMs = System.currentTimeMillis() - fetchStartMs
    Log.w(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} fetchUntilReady exhausted ${BACKOFF_OUTER_ROUNDS}×8 attempts over ${totalMs}ms (all Backoff, server never resumed) → EOF [diagnostic: = hard-refuse/n-throttle, NOT flow-control]")
    return null
  }

  private companion object {
    /** alpha.35 诊断:内层 backoff 耗尽后,长睡再整轮重试的间隔(墙钟推进给实时播放追上)。 */
    const val BACKOFF_LONG_WAIT_MS = 20_000L
    /** alpha.35 诊断:外层整轮重试上限(含首轮)。4 轮 ×(8 内层 + 20s 长睡)≈ 84s 预算。 */
    const val BACKOFF_OUTER_ROUNDS = 4
  }
}
