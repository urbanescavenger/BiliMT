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
    // alpha.62:playerTimeMs = 本流已取段**实际**累计媒体时长(段真实起点,FreeTube getTotalDownloadedDuration),
    // 不再用 (segmentNumber-1)*segmentDurationMs(video 声明 6000 > 实际 ~4360 → playerTimeMs 超前 → 服务端跳段
    // realSeq>N → 视频抽干卡顿/A/V 不同步,alpha.61 真机坐实)。非顺序请求(seek/重载,段号 != 已取+1)用 MPD
    // 时间线近似段起点并重置累计,后续顺序段从这往上累加实际时长。
    val cum = if (streamType == SabrStreamType.VIDEO) e.videoCumulativeMs else e.audioCumulativeMs
    val fetchedSeg = if (streamType == SabrStreamType.VIDEO) e.videoFetchedSeg else e.audioFetchedSeg
    val playerTimeMs: Long = if (isInit) {
      0L
    } else {
      if (segmentNumber.toLong() != fetchedSeg.get() + 1L) {
        cum.set((segmentNumber - 1L) * segmentDurationMs)
      }
      cum.get()
    }
    Log.i(tag, "SabrDash open sid=$sessionId stream=$streamType isInit=$isInit seg=$segmentNumber playerTimeMs=$playerTimeMs dur=$segmentDurationMs")
    // alpha.63(对齐 LibreTube buildBufferedRanges):报**真实增长 own bufferedRange** 替代 null——
    // DASH 路径此前 bufferedRange=null 让服务端回落按 body playerTimeMs 判缓冲,cumulative 过 60s 撞
    // MaxBufferMs 软拒 → 6 backoff → evict → 重播(alpha.62 真机 seg=13 playerTimeMs=61604 死)。LibreTube
    // (安卓/media3 同栈)用 firstSegment.header.startMs + Σduration 拼连续 own range,终点同样过 60s 却不死,
    // 证明服务端用 own range 而非 playerTimeMs 判缓冲。我们 MediaHeader 已解析 startMs,这里用它锚定。
    // own = [firstStartMs .. firstStartMs+cumulative] startSegmentIndex=1 endSegmentIndex=fetchedSeg(已取段)。
    // 首段(fetchedSeg=0)无 own range,同 LibreTube 空列表 → null(只报对方满缓冲,见 SabrClient)。
    val firstStartMs = if (streamType == SabrStreamType.VIDEO) e.videoFirstStartMs else e.audioFirstStartMs
    val ownRange: BufferedRangeInput? = if (isInit || fetchedSeg.get() == 0L) {
      null
    } else {
      val fmt = if (streamType == SabrStreamType.VIDEO) e.session.videoFormatId else e.session.audioFormatId
      BufferedRangeInput(
        itag = fmt.itag,
        lastModified = fmt.lastModified,
        xtags = fmt.xtags,
        startTimeMs = firstStartMs.get(),
        durationMs = cum.get(),
        startSegmentIndex = 1,
        endSegmentIndex = fetchedSeg.get().toInt(),
        timeRange = null,
      )
    }
    Log.i(tag, "SegDiag sid=$sessionId stream=$streamType seg=$segmentNumber playerTimeMs=$playerTimeMs cumulative=${cum.get()} firstStartMs=${firstStartMs.get()} fetchedSeg=${fetchedSeg.get()} ownRange=[${ownRange?.startTimeMs}..+${ownRange?.durationMs}] segIdx=1..${ownRange?.endSegmentIndex}")
    val result = fetchUntilReady(e) {
      SabrFetchRequest(
        isInit = isInit,
        sequenceNumber = if (isInit) 0 else segmentNumber,
        streamType = streamType,
        videoItag = requestedItag,
        // alpha.63:own bufferedRange 真实增长(替代 null),让服务端按 range 而非 playerTimeMs 判缓冲跨 60s。
        playerTimeMs = playerTimeMs,
        bufferedRange = ownRange,
      )
    }
    if (result == null) {
      // init/段失败必须 evict——否则播放器重开同一 sabr:// URL 时 registry 仍命中同一死会话 → 反复
      // RELOAD_PLAYER_RESPONSE/backoff 死循环。evict 后 getByVideoId cache miss → 重 resolve 走新 harvest。
      SabrStreamRegistry.evict(sessionId)
      Log.w(tag, "SabrDash open: ${if (isInit) "init" else "segment $segmentNumber"} fetch failed sid=$sessionId stream=$streamType → evict+throw")
      throw java.io.IOException("SABR ${if (isInit) "init" else "segment $segmentNumber"} fetch failed: sid=$sessionId stream=$streamType")
    }
    // alpha.62:成功 fetch 后累加本段实际时长(init 段 dur=0 不累计),作下一段的 playerTimeMs 起点
    // ——对齐 progressive [SabrStreamingDataSource.cumulativeDurationMs](alpha.36 已验证跨 60s)。
    if (!isInit) {
      // alpha.63:首段(fetchedSeg 此刻==0)记服务端自报 startMs(后续 own range 起点锚,不再覆盖);
      // 用 fetchedSeg==0 而非 firstStartMs==0 作哨兵——seg1 的 startMs 常就是 0(首播从 0 起)。
      if (fetchedSeg.get() == 0L) result.mediaHeader?.startMs?.let { firstStartMs.set(it) }
      result.mediaHeader?.durationMs?.let { cum.addAndGet(it) }
      fetchedSeg.set(segmentNumber.toLong())
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
