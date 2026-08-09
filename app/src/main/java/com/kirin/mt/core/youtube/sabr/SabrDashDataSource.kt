package com.kirin.mt.core.youtube.sabr

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.runBlocking

/**
 * alpha.59(Phase 2 DASH):SABR 合成 DASH 的**每段** DataSource。
 *
 * DashMediaSource 按 MPD SegmentTemplate 逐段请求:init URL(`&init=1`)→ SABR init 请求(isInit=true,
 * seq=0);media URL(`&seg=N&dur=D`)→ SABR 段请求(playerTimeMs=(N-1)*D,段绝对起点,对齐 FreeTube
 * SabrStreamingAdapter "abusing playerTimeMs as exact segment start")。一次 open/read/close 只服务
 * 一段,播放器播到才拉下一段 → cumulative 随墙钟走,服务端持续发段跨过 60s 上限(FreeTube 模型),
 * 且原生 seek(DashMediaSource 按 MPD 时间线 seek,无需协议层 startMs 续播)。
 *
 * 与 [SabrStreamingDataSource](progressive 兜底)不同:后者把 init+全部段拼成连续字节流喂
 * ProgressiveMediaSource(贪婪 burst → 60s 断崖);本类每段独立,不 burst。
 *
 * Redirect → 更新 session.sabrUrl 重试同请求;Backoff → 封顶 sleep 重试;ReloadPlayer/Error/InvalidPoToken
 * → 返回 null → open() evict+throw(播放器 error-retry 走新 harvest)。runBlocking 桥接 suspend fetch
 * (ExoPlayer loader 线程是后台线程,阻塞网络是 DataSource 模型常态)。
 */
internal class SabrDashDataSource(
  private val sessionId: String,
  private val streamType: SabrStreamType,
  /** 本流要播的视频 itag(null=会话默认 videoFormatId)。来自 sabr:// URL 的 `&itag=`。 */
  private val requestedItag: Int?,
  /** true=init 段(seq=0,isInit=true);false=media 段。来自 URL `&init=1`。 */
  private val isInit: Boolean,
  /** media 段号(1-based,来自 URL `&seg=N`);init 段为 0。 */
  private val segmentNumber: Int,
  /** MPD 段时长(ms,来自 URL `&dur=D`)。playerTimeMs=(segmentNumber-1)*D。 */
  private val segmentDurationMs: Long,
) : DataSource {
  private val tag = "YtSabr"
  private var currentUri: Uri? = null
  /** 当前段字节缓冲(init 段或单个 media 段)。 */
  private var buffer: ByteArray = ByteArray(0)
  private var bufferPos: Int = 0

  override fun open(dataSpec: DataSpec): Long {
    currentUri = dataSpec.uri
    val e = SabrStreamRegistry.get(sessionId)
      ?: run {
        Log.w(tag, "SabrDash open: session NOT FOUND sid=$sessionId → throw")
        throw java.io.IOException("SABR session not found: $sessionId")
      }
    val playerTimeMs = if (isInit) 0L else (segmentNumber - 1L) * segmentDurationMs
    Log.i(tag, "SabrDash open sid=$sessionId stream=$streamType isInit=$isInit seg=$segmentNumber playerTimeMs=$playerTimeMs dur=$segmentDurationMs")
    val result = fetchUntilReady(e) {
      SabrFetchRequest(
        isInit = isInit,
        sequenceNumber = if (isInit) 0 else segmentNumber,
        streamType = streamType,
        videoItag = requestedItag,
        // alpha.59:playerTimeMs = 所请求段绝对起点(对齐 FreeTube)。bufferedRange=null——DASH 按需逐段
        // 拉,不报 own 缓冲窗口(服务端按 playerTimeMs 精确发段;对方格式满缓冲由 SabrClient 自行追加)。
        playerTimeMs = playerTimeMs,
        bufferedRange = null,
      )
    }
    if (result == null) {
      // init/段失败必须 evict——否则播放器重开同一 sabr:// URL 时 registry 仍命中同一死会话 → 反复
      // RELOAD_PLAYER_RESPONSE/backoff 死循环。evict 后 getByVideoId cache miss → 重 resolve 走新 harvest。
      SabrStreamRegistry.evict(sessionId)
      Log.w(tag, "SabrDash open: ${if (isInit) "init" else "segment $segmentNumber"} fetch failed sid=$sessionId stream=$streamType → evict+throw")
      throw java.io.IOException("SABR ${if (isInit) "init" else "segment $segmentNumber"} fetch failed: sid=$sessionId stream=$streamType")
    }
    buffer = result.data
    bufferPos = 0
    return buffer.size.toLong()
  }

  override fun read(target: ByteArray, offset: Int, length: Int): Int {
    if (bufferPos >= buffer.size) return C.RESULT_END_OF_INPUT
    val toCopy = minOf(length, buffer.size - bufferPos)
    System.arraycopy(buffer, bufferPos, target, offset, toCopy)
    bufferPos += toCopy
    return toCopy
  }

  override fun getUri(): Uri? = currentUri

  override fun addTransferListener(transferListener: TransferListener) {
    // SABR 流不走 ExoPlayer 的数据转移/带宽估计监听(alpha.27 MVP)。
  }

  override fun close() {
    Log.i(tag, "SabrDash close sid=$sessionId stream=$streamType isInit=$isInit seg=$segmentNumber")
    buffer = ByteArray(0)
    bufferPos = 0
  }

  /**
   * 发一次 SABR 请求,处理 Redirect(更新 sabrUrl 重试)/Backoff(封顶 sleep 重试);
   * Success → 返回段字节;Error/InvalidPoToken/ReloadPlayer → 返回 null(由 open() evict+throw)。
   * 逻辑同 [SabrStreamingDataSource.fetchUntilReady](alpha.46 每次重试重建请求,playhead/bufferedRange
   * 随墙钟刷新;alpha.56 backoff 封顶 <8s 防 stall watchdog 先 cancel)。
   */
  private fun fetchUntilReady(e: SabrStreamRegistry.Entry, reqProvider: () -> SabrFetchRequest): SabrFetchResult.Success? {
    var attempt = 0
    var lastReq: SabrFetchRequest? = null
    while (attempt < BACKOFF_MAX_ATTEMPTS) {
      attempt++
      val req = reqProvider()
      lastReq = req
      val result = runBlocking { e.client.fetch(e.session, req) }
      when (result) {
        is SabrFetchResult.Success -> {
          if (attempt > 1) Log.i(tag, "SabrDash sid=$sessionId stream=$streamType seg=${req.sequenceNumber} RESUMED after ${attempt} backoffs")
          Log.i(tag, "SabrDash sid=$sessionId stream=$streamType reqSeq=${req.sequenceNumber} realSeq=${result.mediaHeader?.sequenceNumber} isInit=${req.isInit} bytes=${result.data.size}B dur=${result.mediaHeader?.durationMs}ms playerTimeMs=${req.playerTimeMs}")
          return result
        }
        is SabrFetchResult.Redirect -> {
          Log.i(tag, "SabrDash sid=$sessionId stream=$streamType Redirect → ${result.sanitized} (applyRedirect + refetch same seq)")
          e.session.applyRedirect(result.newSabrUrl)
          continue
        }
        is SabrFetchResult.Backoff -> {
          Log.i(tag, "SabrDash sid=$sessionId stream=$streamType seg=${req.sequenceNumber} Backoff ${result.ms}ms attempt=$attempt (sleep + refetch)")
          val sleepMs = minOf(result.ms, MAX_BACKOFF_SLEEP_MS)
          try { Thread.sleep(sleepMs.toLong()) } catch (_: InterruptedException) { return null }
          continue
        }
        is SabrFetchResult.ReloadPlayer -> {
          Log.w(tag, "SabrDash sid=$sessionId stream=$streamType seg=${req.sequenceNumber} RELOAD_PLAYER_RESPONSE (terminal) → evict+throw dump=${result.dump}")
          return null
        }
        SabrFetchResult.InvalidPoToken -> {
          Log.w(tag, "SabrDash sid=$sessionId stream=$streamType InvalidPoToken at seg=${req.sequenceNumber} isInit=${req.isInit} → evict+throw")
          return null
        }
        is SabrFetchResult.Error -> {
          Log.w(tag, "SabrDash sid=$sessionId stream=$streamType Error at seg=${req.sequenceNumber} isInit=${req.isInit}: ${result.message} → evict+throw")
          return null
        }
      }
    }
    Log.w(tag, "SabrDash sid=$sessionId stream=$streamType seg=${lastReq?.sequenceNumber} fetchUntilReady exhausted $BACKOFF_MAX_ATTEMPTS backoffs → evict+throw")
    return null
  }

  private companion object {
    /** 单段请求的 backoff 重试上限(耗尽→null→open() evict+throw→播放器 error-retry 新 harvest)。 */
    const val BACKOFF_MAX_ATTEMPTS = 6
    /** 单次 backoff 最大 sleep(ms)。服务端 backoff 常到 8.8s > MobilePlayer StallThresholdMs(8s),
     *  长睡阻塞 loader → stall watchdog 先 cancel 在途 open() → 无限 re-harvest。封顶 <8s 让重试在 watchdog 前触发。 */
    const val MAX_BACKOFF_SLEEP_MS = 2_500
  }
}
