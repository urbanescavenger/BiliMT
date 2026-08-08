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
 * SABR 段序列:seq=0 init(fMP4 ftyp+moov)→ seq=1,2,… 每 ~6-10s(moof+mdat fragment)。
 * 本 DataSource 在 [open] 拉 init, [read] 逐段拉取并拼接成连续字节流——
 * ProgressiveMediaSource 的 MP4 extractor 把 init+fragments 当 fragmented MP4 解析。
 *
 * open() 返回 [C.LENGTH_UNSET](未知总长 → 不可 seek;MVP 接受)。
 * Redirect → 更新 session.sabrUrl 重试同请求;Backoff → sleep 重试;
 * EndOfTrack/Error/InvalidPoToken → read 返回 [C.RESULT_END_OF_INPUT](EOF)。
 *
 * runBlocking 桥接 suspend fetch:播放器 loader 线程是后台线程,阻塞网络是
 * progressive DataSource 模型常态(同 OkHttpDataSource 阻塞 read)。
 *
 * **alpha.36(对齐 FreeTube SabrSchemePlugin.js)**:SABR 是服务端驱动 + 流控——服务端按
 * `clientAbrState.playerTimeMs` + `bufferedRanges` 决定发哪段,且对「客户端缓冲量」有上限。
 * 本类镜像 FreeTube 三点:① `playerTimeMs` = 所请求段的起始时间;② `bufferedRange` 滑动小窗口
 * `[playhead..cumulative]`(`durationMs` 小,非绝对终点);③ [read] 实时 pacing(lead > [MAX_LEAD_MS]
 * 则睡到追上,不贪婪 burst);④ EOF 时驱逐会话使 stall-retry 走新 harvest。playhead 用 wall-elapsed
 * 代理(实时 1x 播放下 ≈ 真实播放位置)。
 *
 * **alpha.37 修正 alpha.36 的 playerTimeMs 误读**:alpha.36 把 FreeTube `playerTimeMs=startTimeMs`
 * 误解成「固定 0 锚点」,实装成 `playerTimeMs=startMs`(首播=0 恒定)。真机证伪:服务端 ABR 见客户端
 * 恒在 0 位置 → 对 seq=2 请求永远重发 seq=1 的 MEDIA_HEADER → [matchesFormat] 判 seq 不等 →
 * 6 backoff → EOF,video ~5s(audio seq1=10001ms ~10s)即断(原 60s 断崖反退成 5s)。FreeTube
 * `startTimeMs` 实为**所请求段的呈现起始时间**(= 已缓冲终点 cumulativeDurationMs,随段推进涨),
 * 故改回 `playerTimeMs=cumulativeDurationMs`(alpha.28 原值)。pacing/滑动窗口保留(非 5s 成因)。
 * 60s 断崖是否随之消失待真机验;若仍在,次选线索:contexts=0/0 + 服务端发的 part 47/52/53 进 unhandled。
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
   *
   * 注意:本字段**只**作续播点,不作 playerTimeMs 锚点——alpha.36 曾误把它当固定 playerTimeMs
   * 锚点(首播=0 恒定)致服务端重发 seq=1 → 5s 断崖;alpha.37 已回退(playerTimeMs 改回
   * cumulativeDurationMs = 所请求段起始时间,对齐 FreeTube startTimeMs 每段推进)。
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
  /** 已取段的累计 durationMs——作 bufferedRange 终点(own 流报「已缓冲到哪」)。init 段 dur=0 不累计。 */
  private var cumulativeDurationMs: Long = 0L
  /** alpha.36:每段的 durationMs 列表(按 seq 顺序),算 playhead 落在哪段(startSegmentIndex)。 */
  private val segDurations = ArrayList<Long>()
  /** alpha.36:open() 时的墙钟,作 playhead 代理基准(实时 1x 播放下 elapsed ≈ 播放位置)。 */
  private var openWallMs: Long = 0L

  override fun open(dataSpec: DataSpec): Long {
    currentUri = dataSpec.uri
    val e = SabrStreamRegistry.get(sessionId)
      ?: run {
        Log.w(tag, "SabrStream open: session NOT FOUND sid=$sessionId → throw")
        throw java.io.IOException("SABR session not found: $sessionId")
      }
    entry = e
    openWallMs = System.currentTimeMillis()
    Log.i(tag, "SabrStream open sid=$sessionId stream=$streamType sabrUrl=${e.session.sabrUrl.take(80)}... startMs=$startMs")
    // init 段(seq=0,isInit=true);init durationMs=0。alpha.29:带 requestedItag 让服务端按该 itag 发对应清晰度的 init(视频流)。
    // alpha.34:续播点 startMs 置进 cumulativeDurationMs——首段请求带 playerTimeMs=startMs,
    // 服务端从续播点发段(0=从头播,等同原行为);绕开 ExoPlayer.seekTo(对 LENGTH_UNSET 不可 seek 的
    // SABR 源,seekTo 会取消 fetch 重开 DataSource 喂双 init 致 MatroskaExtractor "Multiple Segment
    // elements not supported" 崩)。
    val initResult = fetchUntilReady(SabrFetchRequest(isInit = true, streamType = streamType, videoItag = requestedItag))
      ?: run {
        // alpha.41:init 失败必须 evict——否则播放器/ExoPlayer 重开同一 sabr:// URI 时 registry 仍命中
        // 同一死会话 → 反复 RELOAD_PLAYER_RESPONSE/backoff 死循环(alpha.40 同 sid 反复 re-route 的根因)。
        // evict 后 getByVideoId cache miss → 重 resolve 走新 harvest(配合 MobilePlayer error-retry)。
        SabrStreamRegistry.evict(sessionId)
        Log.w(tag, "SabrStream open: init fetch failed sid=$sessionId stream=$streamType → evict+throw")
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
      // alpha.36 实时 pacing:不贪婪 burst-fetch。lead = 已缓冲终点 - 当前播放位置;若超过
      // MAX_LEAD_MS,睡到追上再拉下一段——对齐 FreeTube/shaka 逐段实时 paced。否则 burst 拉满 60s
      // → 服务端流控见「已缓冲 60s=maxed」→ backoff 不发 media → 60s 断崖(见类注释)。
      val playheadMs = playheadEstimateMs()
      val lead = cumulativeDurationMs - playheadMs
      if (lead > MAX_LEAD_MS) {
        val sleepMs = lead - MAX_LEAD_MS
        Log.d(tag, "SabrStream sid=$sessionId stream=$streamType pace: lead=${lead}ms > ${MAX_LEAD_MS}ms → sleep ${sleepMs}ms (cumulative=$cumulativeDurationMs playhead=$playheadMs nextSeq=$nextSeq)")
        try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { /* loader 被 cancel,继续尝试取段 */ }
      }
      // 当前段读完,拉下一段。alpha.36:bufferedRange 改滑动窗口 [playhead..cumulative]
      // (durationMs=小窗口),playerTimeMs 固定=startMs;让服务端流控见「客户端只缓冲少量」持续发新段。
      val seg = fetchUntilReady(buildSegRequest(e))
      if (seg == null) {
        done = true
        Log.i(tag, "SabrStream read EOF sid=$sessionId stream=$streamType at seq=$nextSeq playerTimeMs=$cumulativeDurationMs (no more segments)")
        // alpha.36:驱逐会话——stall-retry 重跑 resolve 时 getByVideoId cache miss → 新 harvest,
        // 不复用服务端已停发的死会话(alpha.35 日志:stall-reload 复用死会话 → 立即 backoff 死循环)。
        SabrStreamRegistry.evict(sessionId)
        return C.RESULT_END_OF_INPUT
      }
      buffer = seg.data
      bufferPos = 0
      val dur = seg.mediaHeader?.durationMs ?: 0L
      segDurations.add(dur)
      cumulativeDurationMs += dur
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
   * alpha.36:估算当前播放位置(墙钟自 open 起的 elapsed,clamp 到 [0, cumulative])。实时 1x 播放下
   * ≈ 真实 playhead。用于 bufferedRange 滑动窗口起点 + pacing lead 计算。对齐 FreeTube
   * `player.getBufferedInfo()`(shaka 给真实 buffer,我们无 player 引用,用 wall-elapsed 代理)。 */
  private fun playheadEstimateMs(): Long {
    if (openWallMs == 0L) return startMs
    val elapsed = System.currentTimeMillis() - openWallMs
    return elapsed.coerceAtLeast(0L).coerceAtMost(cumulativeDurationMs)
  }

  /**
   * alpha.36:playhead 落在哪段(1-based seq)——给 bufferedRange.startSegmentIndex。遍历 [segDurations]
   * 累计,找包含 playhead 的段。playhead 超出已取段则返回最后一段(后续 buildSegRequest 会先 pacing)。
   */
  private fun segIndexAtPlayhead(playheadMs: Long): Int {
    if (segDurations.isEmpty()) return nextSeq.coerceAtLeast(1)
    var acc = 0L
    for ((i, d) in segDurations.withIndex()) {
      acc += d
      if (playheadMs < acc) return i + 1
    }
    return segDurations.size
  }

  /**
   * 构造下一段请求:
   *  - `playerTimeMs = cumulativeDurationMs`(所请求段的呈现起始时间 = 已缓冲终点;对齐 FreeTube
   *    `startTimeMs`——它是**每段**该段的起始时间,随段推进涨,非固定 0 锚点。alpha.36/39 曾误读成
   *    `playerTimeMs=startMs`(首播=0 固定)→ 服务端 ABR 见客户端恒在 0 位置 → 永远重发 seq=1 →
   *    seq=2 matched=false → 6 backoff → EOF,video 5s/ audio 10s 即断。alpha.37/40 回退此错。)
   *  - `bufferedRange = [playhead..cumulative]` 滑动窗口:`startTimeMs=playhead`、`durationMs=cumulative-playhead`
   *    (小窗口,非绝对终点)、`startSegmentIndex=segIndexAtPlayhead`、`endSegmentIndex=nextSeq-1`
   *  - 对方格式标「满缓冲」(createFullBufferRange)让服务端只发 own 下一段(沿用 alpha.30)
   *
   * **alpha.40 诊断(60s 断崖真因取证)**:alpha.39 回退后 60s 断崖(alpha.37/38 基线)回归。横跳史证
   *   playerTimeMs 非 FreeTube-vs-我们的区分因子(FreeTube 发 '0' 跑通,我们发 0 撞 5s、发 cumulative 撞 60s)。
   *   主嫌假设:burst-fetch 让 cumulative(缓冲终点)跑在 playhead 前——playhead ~46s 时 cumulative 已
   *   60001,服务端按 playerTimeMs(=cumulative)判「客户端位置超 60s」软拒(backoff=0 不给 MEDIA_HEADER)。
   *   **SegDiag** 日志逐段打 playerTimeMs/playhead/cumulative/lead/ownRange,到 60s 断崖点(看 MEDIA_HEADER
   *   matched=false 或 NEXT_REQUEST_POLICY backoff 那刻)即可证/伪「缓冲膨胀」假设——若证,下版 alpha.41
   *   试 `playerTimeMs=playheadEstimateMs()`(随实时 1x 播放涨,既非 0 也非 buffered 终点,不膨胀)。 */
  private fun buildSegRequest(e: SabrStreamRegistry.Entry): SabrFetchRequest {
    // alpha.29:视频流按 requestedItag 取 FormatId(poToken 会话级不绑 itag);audio 用会话默认。
    val fmt = if (streamType == SabrStreamType.AUDIO) e.session.audioFormatId
      else requestedItag?.let { e.session.videoFormat(it) } ?: e.session.videoFormatId
    val playheadMs = playheadEstimateMs()
    // alpha.36:own 格式报滑动窗口 [playhead..cumulative](durationMs=小窗口,非绝对终点)。
    // 对方格式的「满缓冲」条目由 SabrClient.fetch 自行追加(见 alpha.30 createFullBufferRange),
    // 这里只带 own——与原结构一致,不扩散可见性。
    val own = if (cumulativeDurationMs > 0L) BufferedRangeInput(
      itag = fmt.itag,
      lastModified = fmt.lastModified,
      xtags = fmt.xtags,
      startTimeMs = playheadMs,
      durationMs = (cumulativeDurationMs - playheadMs).coerceAtLeast(0L),
      startSegmentIndex = segIndexAtPlayhead(playheadMs),
      endSegmentIndex = (nextSeq - 1).coerceAtLeast(0),
      timeRange = null,
    ) else null
    // alpha.40:SegDiag——逐段打 playerTimeMs(发)/playhead(实时)/cumulative(缓冲终点)/lead(缓冲超前量)/
    // ownRange(报给服务端的窗口),到 60s 断崖点取证「缓冲膨胀」假设(见方法注释)。
    val lead = cumulativeDurationMs - playheadMs
    Log.i(tag, "SegDiag sid=$sessionId stream=$streamType seq=$nextSeq playerTimeMs=$cumulativeDurationMs playhead=$playheadMs cumulative=$cumulativeDurationMs lead=${lead}ms ownRange=[${own?.startTimeMs}..+${own?.durationMs}] segIdx=${own?.startSegmentIndex}..${own?.endSegmentIndex}")
    return SabrFetchRequest(
      isInit = false,
      sequenceNumber = nextSeq,
      streamType = streamType,
      videoItag = requestedItag,
      // alpha.40:回退 alpha.39 的 startMs(0)→ cumulativeDurationMs(alpha.37 原值)。详见方法注释。
      playerTimeMs = cumulativeDurationMs,
      bufferedRange = own,
    )
  }

  /**
   * 发一次 SABR 请求,处理 Redirect(更新 sabrUrl 重试)/Backoff(sleep 重试);
   * Success → 返回段字节;Error/InvalidPoToken → 返回 null(由 read() 当 EOF)。
   *
   * alpha.35 诊断叠层已回退(alpha.36):不再 4 outer×8 + 20s 长睡(诊断证 60s 断崖非「服务端会恢复」
   * 的 flow-control 而是请求体报告错,长睡只挡 stall-retry)。恢复简洁 backoff:内层最多
   * [BACKOFF_MAX_ATTEMPTS] 次 backoff(sleep+重发同 seq,带已更新 cookie/context),耗尽 → null(EOF)
   * → read() 触发 [SabrStreamRegistry.evict] → stall-retry 新 harvest。对齐 FreeTube
   * `cumulativeBackOffRequested>=3` 即 reload 的语义(我们靠播放器 stall-retry 实现 reload)。
   * Redirect → 更新 sabrUrl 重试同请求;Error/InvalidPoToken → 立即返回 null(EOF)。
   */
  private fun fetchUntilReady(req: SabrFetchRequest): SabrFetchResult.Success? {
    val e = entry ?: return null
    var attempt = 0
    while (attempt < BACKOFF_MAX_ATTEMPTS) {
      attempt++
      val result = runBlocking { e.client.fetch(e.session, req) }
      when (result) {
        is SabrFetchResult.Success -> {
          if (attempt > 1) Log.i(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} RESUMED after ${attempt} backoffs (bufferedEnd=$cumulativeDurationMs)")
          Log.i(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} isInit=${req.isInit} bytes=${result.data.size}B dur=${result.mediaHeader?.durationMs}ms playerTimeMs=${req.playerTimeMs}")
          return result
        }
        is SabrFetchResult.Redirect -> {
          Log.i(tag, "SabrStream sid=$sessionId stream=$streamType Redirect → ${result.sanitized} (applyRedirect + refetch same seq)")
          e.session.applyRedirect(result.newSabrUrl)
          continue
        }
        is SabrFetchResult.Backoff -> {
          Log.i(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} Backoff ${result.ms}ms attempt=$attempt bufferedEnd=$cumulativeDurationMs (sleep + refetch)")
          try { Thread.sleep(result.ms.toLong()) } catch (_: InterruptedException) { return null }
          continue
        }
        is SabrFetchResult.ReloadPlayer -> {
          // alpha.41:服务端明令 RELOAD_PLAYER_RESPONSE——重发同一 req 是构造性死循环(alpha.40
          // 6× backoff 全 backoff=0 → EOF 的根因)。terminal:立即退出,交 open()/read() evict 会话
          // → 播放器 error/stall-retry 走新 harvest。dump 已在 SabrClient 记录,这里只标 terminal。
          Log.w(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} RELOAD_PLAYER_RESPONSE (terminal, not backoff) → ${if (req.isInit) "open() evict+throw" else "read() evict+EOF"} dump=${result.dump}")
          return null
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
    Log.w(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} fetchUntilReady exhausted $BACKOFF_MAX_ATTEMPTS backoffs (server not resuming) → EOF → evict session for stall-retry fresh harvest")
    return null
  }

  private companion object {
    /** alpha.36:实时 pacing 的最大超前量。lead 超此值则睡到追上,避免 burst 拉满触发服务端 maxed-out 流控。 */
    const val MAX_LEAD_MS = 12_000L
    /** alpha.36:单段请求的 backoff 重试上限(对齐 FreeTube 简洁 reload 语义;耗尽→EOF→evict→stall-retry 新 harvest)。 */
    const val BACKOFF_MAX_ATTEMPTS = 6
  }
}
