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
 * 本类镜像 FreeTube 两点:① `playerTimeMs` = 当前播放位置(playhead);② `bufferedRange` 滑动小窗口
 * `[playhead..cumulative]`(`durationMs` 小,非绝对终点);③ EOF 时驱逐会话使 stall-retry 走新 harvest。
 * playhead 用 wall-elapsed 代理(实时 1x 播放下 ≈ 真实播放位置)。
 *
 * **alpha.45:删除 alpha.36 在 read() 内的 Thread.sleep pacing**。alpha.36 想「不贪婪 burst,lead 超
 * MAX_LEAD_MS 则睡到追上」,但 sleep 在 ExoPlayer loader 调用的同步 read() 路径里 → 阻塞 loader →
 * 解码器抽空 sample 队列 → stall buffered=0% → stall-retry 反复 reuse 重载 → 在途 fetch 被 cancel 成
 * timeout → EOF → evict(alpha.44 真机:1080p pace sleep 9636ms → 8s 后 stall @3110 buffered=0%,三轮重载
 * 后 rn=37/38 timeout 死,死时 cumulative 峰值 53133 未到 60s——非 60s 断崖,是 pacer 阻塞致死)。
 * 防 60s 断崖改由 LoadControl `MaxBufferMs=50s`(< 60s)自动停拉替代,不在 read 路径阻塞。alpha.36/37
 * 对 playerTimeMs 的反复(startMs→cumulative→playhead)与本次无关——alpha.30 已证伪 playerTimeMs=
 * bufferedEnd 致停发(seq1-6 正常),60s 软拒的真变量是 cumulative/bufferedRange 终点不是 playerTimeMs。
 * **alpha.37 修正 alpha.36 的 playerTimeMs 误读**:alpha.36 把 FreeTube `playerTimeMs=startTimeMs`
 * 误解成「固定 0 锚点」,实装成 `playerTimeMs=startMs`(首播=0 恒定)。真机证伪:服务端 ABR 见客户端
 * 恒在 0 位置 → 对 seq=2 请求永远重发 seq=1 的 MEDIA_HEADER → [matchesFormat] 判 seq 不等 →
 * 6 backoff → EOF,video ~5s(audio seq1=10001ms ~10s)即断(原 60s 断崖反退成 5s)。pacing/滑动窗口保留(非 5s 成因)。
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
  /**
   * alpha.48(轮换):videoId(来自 sabr:// URI `&videoId=`)。当前窗口 cumulative 距锚点 ~[PREFETCH_AT_MS]
   * 时主动触发 [SabrStreamRegistry.requestRotation] 建下一个窗口会话(锚定窗口起点),绕开服务端每会话
   * ~60s 服务量上限。null=老 URI 无 videoId(轮换不可用,保持原被动 stall-retry 行为)。
   */
  private val videoId: String?,
) : DataSource {
  private val tag = "YtSabr"
  private var entry: SabrStreamRegistry.Entry? = null
  private var currentUri: Uri? = null
  /**
   * alpha.52(窗口):当前会话的窗口起点(anchor)——轮换触发/切换判断用「cumulative 距本锚点」,而非绝对
   * cumulative。否则每次切换把 cumulative 重置回锚点后,下一次 read() 立即满足绝对阈值 → 死循环轮换。
   * 初始 = [startMs] 所在窗口(floor(startMs/60)*60);窗口切换后 = 下一个窗口起点(+WINDOW_LEN)。服务端
   * 每会话服务量上限 ~60s(锚点..+60s),故每窗口只预取一次、耗尽才切。
   */
  private var sessionAnchorMs: Long = 0L
  /** alpha.48:当前会话是否已触发过轮换(video/audio 两路 DataSource 各自触发一次无妨,registry 防并发)。 */
  private var rotationTriggered: Boolean = false
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
    val initResult = fetchUntilReady { SabrFetchRequest(isInit = true, streamType = streamType, videoItag = requestedItag) }
      ?: run {
        // alpha.41:init 失败必须 evict——否则播放器/ExoPlayer 重开同一 sabr:// URI 时 registry 仍命中
        // 同一死会话 → 反复 RELOAD_PLAYER_RESPONSE/backoff 死循环(alpha.40 同 sid 反复 re-route 的根因)。
        // evict 后 getByVideoId cache miss → 重 resolve 走新 harvest(配合 MobilePlayer error-retry)。
        // alpha.49:统一走 evictIfStillOwner——open 时 entry===cur 恒真(刚 get),行为不变,只保证
        // 没有任何绕过守卫的 evict 路径(避免误删轮换新会话)。
        evictIfStillOwner()
        Log.w(tag, "SabrStream open: init fetch failed sid=$sessionId stream=$streamType → evict+throw")
        throw java.io.IOException("SABR init fetch failed: sid=$sessionId stream=$streamType")
      }
    buffer = initResult.data
    bufferPos = 0
    cumulativeDurationMs = startMs + (initResult.mediaHeader?.durationMs ?: 0L)
    // alpha.52(窗口):会话锚点 = startMs 所在 60s 窗口起点(floor(startMs/60s)*60s),而非 startMs 本身。
    // 服务端对每会话服务量上限 [anchor..anchor+60s];续播/seek 到任意位置 X 时,会话由 resolver 锚定到
    // floor(X/60)*60(覆盖 X),这里锚点取同窗口起点,使「窗口耗尽/预取触发」按距窗口起点的累计增量判断
    // (滚动:anchor 0→60→120...)。startMs=0 首播 → 0,原行为。
    sessionAnchorMs = (startMs / WINDOW_LEN) * WINDOW_LEN
    // alpha.52(seek):上报「正在播放的窗口会话」——resolver 据此判断 seek 目标窗口可否复用本会话。
    videoId?.let { SabrStreamRegistry.noteActive(it, e) }
    return C.LENGTH_UNSET.toLong()
  }

  override fun read(target: ByteArray, offset: Int, length: Int): Int {
    if (done) return C.RESULT_END_OF_INPUT
    var e = entry ?: return C.RESULT_END_OF_INPUT
    while (bufferPos >= buffer.size) {
      // alpha.45:删除 alpha.36 在 read() 内的 Thread.sleep pacing。sleep 阻塞 ExoPlayer loader
      // 线程 → 解码器抽空 sample 队列 → stall buffered=0% → stall-retry 反复 reuse 重载 → 在途 fetch
      // 被 cancel 成 timeout(InterruptedIOException: Canceled)→ EOF → evict。alpha.44 真机坐实:
      // pace sleep 9636ms → 8s 后 stall @pos=3110 buffered=0%;三轮重载后 rn=37/38 fetch timeout 死,
      // 死时 cumulative 峰值才 53133(未到 60s,非 60s 断崖致死)。1080p 段大,audio 1s 内 burst 到
      // 20-30s 后 pacer 长 sleep,video 只拿到 5.1s → 解码器抽干 video → stall;480p 段小撑得过 sleep 窗。
      // 防 60s 断崖改由 LoadControl MaxBufferMs=50s(< 60s)自动停拉替代,不在 read 路径阻塞。
      // lead 仍由 SegDiag 逐段记录,供验证。
      // 当前段读完,拉下一段。alpha.36/42:bufferedRange 改滑动窗口 [playhead..cumulative]
      // (durationMs=小窗口),playerTimeMs=cumulativeDurationMs(所请求段呈现起始,随段推进涨);
      // 让服务端流控见「客户端只缓冲少量」持续发新段。alpha.42:playhead 含 startMs(续播/切清晰度
      // 不再死睡 61s)。
      maybeTriggerRotation()
      // alpha.48:轮换完成(registry entry 被 registerByVideoId 覆盖为新会话)后刷新 e——
      // 否则 buildSegRequest 仍用旧 entry 的 session(旧 sabrUrl)→ 服务端已停发 → 继续 backoff 死。
      if (switchIfRotated()) e = entry ?: return C.RESULT_END_OF_INPUT
      val seg = fetchUntilReady { buildSegRequest(e) }
      if (seg == null) {
        // alpha.49(轮换):旧会话服务量耗尽 EOF 时,若轮换已在本段失败的请求期间完成
        // (registerByVideoId 已覆盖同 sid),直接交棒新会话续读——否则 evict(sessionId)
        // 会误删轮换好的新会话(共享 sid,alpha.48 真机 register 后紧接 evict active=0 即此因)。
        if (switchIfRotated()) {
          Log.i(tag, "SabrStream sid=$sessionId stream=$streamType EOF→rotation ready → hand off to new session")
          e = entry ?: return C.RESULT_END_OF_INPUT
          continue
        }
        done = true
        Log.i(tag, "SabrStream read EOF sid=$sessionId stream=$streamType at seq=$nextSeq playerTimeMs=$cumulativeDurationMs (no more segments)")
        // alpha.36:驱逐会话——stall-retry 重跑 resolve 时 getByVideoId cache miss → 新 harvest,
        // 不复用服务端已停发的死会话(alpha.35 日志:stall-reload 复用死会话 → 立即 backoff 死循环)。
        // alpha.49:仅当本 DataSource 仍是该 sid 的当前 entry 才 evict(避免误删轮换新会话)。
        evictIfStillOwner()
        return C.RESULT_END_OF_INPUT
      }
      buffer = seg.data
      bufferPos = 0
      val dur = seg.mediaHeader?.durationMs ?: 0L
      segDurations.add(dur)
      cumulativeDurationMs += dur
      // alpha.44:对齐 nextSeq 到服务端实际给的段 seq+1。续播/切清晰度时 playerTimeMs=startMs 让服务端
      // 按续播点定位,返回段 seq 可能 > 请求 seq(matchesFormat 已 accept >=);不对齐会持续错位(下次仍 req
      // 低于服务端给的 seq)→ matched=false → EOF。首播 realSeq==nextSeq 走 +1 等同原 ++,行为不变。
      val realSeq = seg.mediaHeader?.sequenceNumber
      if (realSeq != null && realSeq >= nextSeq) nextSeq = realSeq + 1 else nextSeq++
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
   * alpha.36/42:估算当前播放位置,用于 bufferedRange 滑动窗口起点 + pacing lead 计算。实时 1x 播放下
   * ≈ 真实 playhead。对齐 FreeTube `player.getBufferedInfo()`(shaka 给真实 buffer,我们无 player 引用,
   * 用 wall-elapsed 代理)。
   *
   * **alpha.42 修清晰度切换/续播死睡**:alpha.36 只用 `open` 以来的墙钟 elapsed 当 playhead——对首播
   * (startMs=0)正确(elapsed≈播放位置),但对续播/切清晰度(startMs>0,player 从 startMs 处开播)
   * **漏了 startMs 基准** → 开播瞬间 playhead≈0 而 cumulative=startMs(74753)→ lead≈74753 →
   * sleep 61s → 服务端会话空闲过期 → seq=1 只回 NEXT_REQUEST_POLICY backoff 不给 media → 6 backoff
   * → EOF → evict(alpha.41 真机:16:40:04 pace sleep 61442ms → 16:40:57 seq=1 反复 backoff 死)。
   * 改回 `startMs + elapsed`:续播点 + 实时推进;首播 startMs=0 时 = elapsed,行为不变(不动 60s 断崖基线)。 */
  private fun playheadEstimateMs(): Long {
    if (openWallMs == 0L) return startMs
    val elapsed = System.currentTimeMillis() - openWallMs
    return (startMs + elapsed).coerceAtLeast(0L).coerceAtMost(cumulativeDurationMs)
  }

  /**
   * alpha.36/42:playhead 落在哪段(1-based seq)——给 bufferedRange.startSegmentIndex。遍历 [segDurations]
   * 累计,找包含 playhead 的段。playhead 超出已取段则返回最后一段(后续 buildSegRequest 会先 pacing)。
   *
   * **alpha.42**:playhead 现含 startMs(见 [playheadEstimateMs]),故段时间线起点也是 startMs
   * (续播/切清晰度时 seg1 呈现于 startMs 处,非 0)。acc 从 startMs 起累加。首播 startMs=0 时同原行为。 */
  private fun segIndexAtPlayhead(playheadMs: Long): Int {
    if (segDurations.isEmpty()) return nextSeq.coerceAtLeast(1)
    var acc = startMs
    for ((i, d) in segDurations.withIndex()) {
      acc += d
      if (playheadMs < acc) return i + 1
    }
    return segDurations.size
  }

  /**
   * 构造下一段请求:
   *  - `playerTimeMs = playheadEstimateMs()`(当前播放位置)——SABR clientAbrState.playerTimeMs 标准语义即
   *    客户端当前播放位置,服务端 ABR 据此预发段。alpha.44 改此:alpha.37 误设成 `cumulativeDurationMs`
   *    (缓冲终点)→ 播到 ~60s 时 cumulative>60000=maxTimeSinceReq → 服务端软拒(只回 NEXT_REQUEST_POLICY
   *    backoff 不给 MEDIA_HEADER)→ 6 backoff → EOF → evict(alpha.43 真机 seq=13 playerTimeMs=60031 死)。
   *    alpha.36/39 更早误设成 `startMs`(首播恒 0)→ 服务端见客户端恒在 0 → 重发 seq=1 → seq=2 matched=false
   *    → 6 backoff → EOF,video 5s/audio 10s 即断。playhead 随实时 1x 播放涨,既非恒 0(不撞 5s 断崖)
   *    亦非缓冲终点(不撞 60s 断崖)。
   *  - `bufferedRange = [playhead..cumulative]` 滑动窗口:`startTimeMs=playhead`、`durationMs=cumulative-playhead`
   *    (小窗口,非绝对终点)、`startSegmentIndex=segIndexAtPlayhead`、`endSegmentIndex=nextSeq-1`
   *  - 对方格式标「满缓冲」(createFullBufferRange)让服务端只发 own 下一段(沿用 alpha.30)
   *
   * **alpha.40 诊断(60s 断崖真因取证)**:SegDiag 逐段打 playerTimeMs/playhead/cumulative/lead/ownRange,
   *   alpha.43 真机坐实「缓冲膨胀」假设——seq=13 时 playerTimeMs(=cumulative)=60031 > 60000,服务端 6×
   *   `no MEDIA_HEADER but NEXT_REQUEST_POLICY backoff` 软拒 → evict。alpha.44 据此改 playerTimeMs=playhead。 */
  /**
   * alpha.52(窗口):当前窗口会话 cumulative 距锚点 >= [PREFETCH_AT_MS] 时,后台预取下一个窗口的会话
   * ([SabrStreamRegistry.requestRotation] 锚定 sessionAnchorMs+WINDOW_LEN)。服务端对每会话 ~60s 服务量
   * 上限(窗口耗尽即软拒);被动 stall-retry 已太晚。cumulative 40s 时 playhead 才 ~25s,harvest 5-6s 在
   * ~48s 完成,窗口耗尽(60s)时新会话已就绪,[switchIfRotated] 无缝切换续播。
   * 每窗口只触发一次(rotationTriggered);切换后 sessionAnchorMs 推进 + rotationTriggered 复位,下一窗口再触发。
   * video/audio 两路 DataSource 各自触发一次无妨(registry rotationInFlight 防并发重复 harvest)。
   */
  private fun maybeTriggerRotation() {
    val vid = videoId ?: return
    if (rotationTriggered) return
    // alpha.52(窗口):改按 cumulative 距窗口锚点的增量判断(非 alpha.49 的 playhead)。alpha.51 真机坐实
    // 会话死在 cumulative 达 ~60s(media 服务量上限),而 playhead 领先累积 ~20s(缓冲 lead)→ playhead
    // 触发点永远够不到(playhead 只到 ~40s 会话已死)→ 0 次轮换。cumulative 到 40s 时 playhead 才 ~25s,
    // 远在死亡点前,触发可靠。预取锚定**下一个窗口起点**(+60s,非 playhead)——让浏览器 watch `&t=`
    // 锚定新会话窗口到边界,旧窗口耗尽后新会话从边界续,无 gap。
    // alpha.52B(Bug B 修复):当前窗口取**共享** activeWindow(registry 按 videoId 记录真正在播的窗口),
    // 而非本 DataSource 私有 sessionAnchorMs——VIDEO/AUDIO 共用同 sid,各自私有锚点会错位(一路切了、
    // 另一路锚点仍旧)→ 对已轮换窗口重复 harvest(alpha.52 真机 VIDEO 重复 w60)。用共享窗口 + 按窗口
    // 去重(markRotationRequested),每窗口只 harvest 一次。
    val currentWindow = SabrStreamRegistry.activeWindow(vid) ?: sessionAnchorMs
    val sinceAnchor = cumulativeDurationMs - currentWindow
    if (sinceAnchor < PREFETCH_AT_MS) return
    rotationTriggered = true
    val nextWindowStart = currentWindow + WINDOW_LEN
    if (!SabrStreamRegistry.markRotationRequested(vid, nextWindowStart)) {
      Log.i(tag, "ROTATION trigger skip sid=$sessionId stream=$streamType window=$nextWindowStart (already requested) → no redundant harvest")
      return
    }
    Log.i(tag, "ROTATION trigger sid=$sessionId stream=$streamType cumulativeSinceAnchor=${sinceAnchor}ms window=${currentWindow} → requestRotation(videoId=$vid startMs=$nextWindowStart window=$nextWindowStart)")
    SabrStreamRegistry.requestRotation(vid, nextWindowStart)
  }

  /**
   * alpha.48(轮换):检测轮换是否已完成——[requestRotation] 的工厂用 registerByVideoId(同 videoId)
   * 覆盖了 registry entry(sid 不变)。若当前 [entry] 与 registry 里不再是同一对象 → 新会话已就绪:
   * 切换到它并重置段状态(cumulative/nextSeq/segDurations 归零到新锚点),下个 buildSegRequest 用新
   * session 的 sabrUrl/poToken。返回是否切换(切换后调用方须刷新本地 entry 引用)。
   */
  private fun switchIfRotated(): Boolean {
    val cur = SabrStreamRegistry.get(sessionId) ?: return false
    if (cur === entry) return false
    // alpha.52(窗口):切换门 = 旧窗口**已耗尽**(cumulative 距锚点 >= SWITCH_AT_MS=60s)。新会话(register
    // 覆盖同 sid)可能在预取触发后 ~8s 就已就绪,若此时就切,旧会话只服务到 ~48s → 新会话从 60s 续 →
    // buffer gap [48..60] → stall。必须等旧会话把窗口服务满(最后一段 duration 把 cumulative 顶过 60s,
    // 如 video seq12→61604)才切 → 旧已缓冲到边界,新从 60s 续,连续。
    if (cumulativeDurationMs - sessionAnchorMs < SWITCH_AT_MS) return false
    val nextWindowStart = sessionAnchorMs + WINDOW_LEN
    Log.i(tag, "ROTATION switch sid=$sessionId stream=$streamType oldSession=${entry?.session?.sabrUrl?.take(50)} newSession=${cur.session.sabrUrl.take(50)} window=$nextWindowStart")
    // 新会话(新 sabrUrl/poToken)需先发 init 建立服务端会话态(同 open());init 字节丢弃——extractor
    // 已持有同 itag 的 moov(格式一致,不重喂避免 "Multiple Segment elements not supported")。
    val e0 = entry
    entry = cur
    val init = fetchUntilReady { SabrFetchRequest(isInit = true, streamType = streamType, videoItag = requestedItag) }
    if (init == null) {
      // 新会话 init 失败(不该发生;若发生,说明新会话也死)——回退旧 entry,交 read() 正常 EOF 走 evict。
      Log.w(tag, "ROTATION switch: new session init failed sid=$sessionId stream=$streamType → revert old session")
      entry = e0
      return false
    }
    // 锚点推进到下一个窗口起点(cumulative 从该窗口续,首段 playerTimeMs=nextWindowStart → 服务端从边界发段)。
    sessionAnchorMs = nextWindowStart
    cumulativeDurationMs = nextWindowStart + (init.mediaHeader?.durationMs ?: 0L)
    // alpha.52(seek):上报切换到的新窗口会话——seek 复用判断取当前播放窗口。
    videoId?.let { SabrStreamRegistry.noteActive(it, cur) }
    nextSeq = 1
    segDurations.clear()
    // 每窗口独立触发一次预取——切到新窗口后复位,下一窗口 cumulative 到 PREFETCH_AT_MS 再触发(否则第二窗口永久不预取)。
    rotationTriggered = false
    return true
  }

  /**
   * alpha.49(轮换):驱逐会话,但只删「仍是当前 entry」的死会话。轮换([registerByVideoId])复用同
   * sid 覆盖 entry;若本 DataSource 的 [entry] 已不是 registry 里该 sid 的当前 entry(即轮换已完成、
   * 新会话已就位),则**跳过 evict**——否则会误删轮换好的新会话(共享 sid,alpha.48 真机 register 后
   * 紧接 evict active=0 即此因)。只有确认真无更新会话时才 evict,让 stall-retry 重跑 resolve 走新 harvest。
   */
  private fun evictIfStillOwner() {
    val cur = SabrStreamRegistry.get(sessionId)
    if (cur === entry) {
      SabrStreamRegistry.evict(sessionId)
    } else {
      Log.i(tag, "SabrStream sid=$sessionId stream=$streamType skip evict: entry replaced by rotation (keep new session)")
    }
  }

  private fun buildSegRequest(e: SabrStreamRegistry.Entry): SabrFetchRequest {
    // alpha.29:视频流按 requestedItag 取 FormatId(poToken 会话级不绑 itag);audio 用会话默认。
    val fmt = if (streamType == SabrStreamType.AUDIO) e.session.audioFormatId
      else requestedItag?.let { e.session.videoFormat(it) } ?: e.session.videoFormatId
    val playheadMs = playheadEstimateMs()
    // alpha.36/42:own 格式报滑动窗口 [playhead..cumulative](durationMs=小窗口,非绝对终点)。
    // 对方格式的「满缓冲」条目由 SabrClient.fetch 自行追加(见 alpha.30 createFullBufferRange),
    // 这里只带 own——与原结构一致,不扩散可见性。
    // alpha.42:条件改 segDurations.isNotEmpty()——只有真取到 ≥1 条媒体段后才报 own。续播/切清晰度
    // (startMs>0)时 open 后 cumulative=startMs>0 但尚无媒体段,旧条件 `cumulativeDurationMs>0` 会造出
    // 零宽 own=[startMs..+0] 畸形窗口;首段应同首播(startMs=0,cumulative=0→旧亦 null)报 null,
    // 拿到首条媒体段后再报真实窗口。首播行为不变。
    // alpha.45:封顶上报的 bufferedRange 终点。alpha.44 真机坐实服务端看 bufferedRange 终点(cumulative)
    // 而非 playerTimeMs——seq=11 playerTimeMs=50686(<60000)仍被拒,因 cumulative=62686>60000。MaxBufferMs
    // 限的是 buffered(cumulative-playhead)不是 cumulative,故 cumulative=playhead+50s 会在 playhead≈10s 就
    // 撞 60s 断崖(比 pacer 更早)。封顶上报终点在 MAX_REPORTED_BUFFER_MS(<60s),服务端永远看到客户端只
    // 缓冲到 55s 持续发段;客户端实际缓冲更多(更流畅),但上报封顶不触发服务端软拒。
    val reportedEnd = minOf(cumulativeDurationMs, MAX_REPORTED_BUFFER_MS)
    val own = if (segDurations.isNotEmpty()) BufferedRangeInput(
      itag = fmt.itag,
      lastModified = fmt.lastModified,
      xtags = fmt.xtags,
      startTimeMs = playheadMs,
      durationMs = (reportedEnd - playheadMs).coerceAtLeast(0L),
      startSegmentIndex = segIndexAtPlayhead(playheadMs),
      endSegmentIndex = (nextSeq - 1).coerceAtLeast(0),
      timeRange = null,
    ) else null
    // alpha.40:SegDiag——逐段打 playerTimeMs(发,alpha.52C 起=段绝对起点 cumulative)/playhead(实时)/
    // cumulative(缓冲终点)/lead(缓冲超前量)/ownRange(报给服务端的窗口),到 60s 断崖点取证。
    val lead = cumulativeDurationMs - playheadMs
    Log.i(tag, "SegDiag sid=$sessionId stream=$streamType seq=$nextSeq playerTimeMs=$cumulativeDurationMs playhead=$playheadMs cumulative=$cumulativeDurationMs reportedEnd=$reportedEnd lead=${lead}ms ownRange=[${own?.startTimeMs}..+${own?.durationMs}] segIdx=${own?.startSegmentIndex}..${own?.endSegmentIndex}")
    return SabrFetchRequest(
      isInit = false,
      sequenceNumber = nextSeq,
      streamType = streamType,
      videoItag = requestedItag,
      // alpha.52C:不再拼 URL `&startTimeMs=`/`&sq=`(alpha.51 Track A 诊断已证伪),对齐 FreeTube
      // SabrStreamingAdapter 只发 `rn`。轮换到新会话(harvest 自 &t=60)时旧实现 URL startTimeMs=
      // cumulative=60000 未封顶直接触发服务端 maxTimeSinceReq 软拒(NEXT_REQUEST_POLICY 不给段)→ 6 backoff
      // → EOF(alpha.52 真机:轮换首段 seq=1 startTimeMs=60000 死)。去掉 URL 参数即去掉该触发源。
      // alpha.52C:playerTimeMs 改为所请求段的**绝对起点**(cumulativeDurationMs=缓冲边=下一段起点),对齐
      // FreeTube "abusing playerTimeMs as exact segment start"(服务端按该时间精确发段,不断推进即无 60s 上限)。
      // alpha.44 曾因此 60s 拒,但那是 alpha.45 的 MAX_REPORTED_BUFFER_MS 封顶**之前** bufferedRange 终点
      // (cumulative)不封顶所致,非 playerTimeMs;封顶已上线 + FreeTube 段起点先例 → 安全。对轮换新会话
      // playerTimeMs=60000 正确锚定其首段(旧 playhead≈34159 对从 60s 起的新会话是无效位置)。
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
   *
   * alpha.46:入参改 [reqProvider] 而非固定 req——每次重试(含 backoff)都重建请求,playhead/
   * bufferedRange 随墙钟刷新。服务端流控按「客户端缓冲超前量 lead」决定发不发段(target readahead
   * 15s),旧实现重发固定 req 使服务端永远看到 lead 超限 → 6× backoff → EOF(alpha.46 真机 55s 断崖)。
   */
  private fun fetchUntilReady(reqProvider: () -> SabrFetchRequest): SabrFetchResult.Success? {
    val e = entry ?: return null
    var attempt = 0
    var lastReq: SabrFetchRequest? = null
    while (attempt < BACKOFF_MAX_ATTEMPTS) {
      attempt++
      // alpha.46:每次重试都重建请求——服务端流控按「客户端缓冲超前量(lead)」决定发不发段(target
      // readahead=15s,lead 超即只回 NEXT_REQUEST_POLICY backoff)。旧实现把 buildSegRequest 的 req 当
      // 固定参数重发,playhead/bufferedRange 全程不变 → 服务端永远看到 lead=18.5s>15s → 6× backoff →
      // EOF → evict(alpha.46 真机:video seq=12 bufferedEnd=54558 卡 55s)。重建后 playhead 随墙钟涨,
      // lead 逐次回落 <15s → 服务端恢复发段。init 请求重建结果不变(无 playhead/bufferedRange)。
      val req = reqProvider()
      lastReq = req
      val result = runBlocking { e.client.fetch(e.session, req) }
      when (result) {
        is SabrFetchResult.Success -> {
          if (attempt > 1) Log.i(tag, "SabrStream sid=$sessionId stream=$streamType seq=${req.sequenceNumber} RESUMED after ${attempt} backoffs (bufferedEnd=$cumulativeDurationMs)")
          // alpha.51(Track A 诊断):reqSeq=请求的 sq / realSeq=服务端实际回段 seq / startTimeMs=请求的 URL 锚点。
          // 对比三者可判定服务端是否接受显式 startTimeMs 按其发段:若 realSeq 持续跟进、startTimeMs 推进,
          // 且 cumulative 越过 60000 仍继续 → 移动播放点生效,FreeTube 模型成立;若 realSeq 停在某 seq 反复 /
          // 回 startTimeMs 附近重发 → 服务端未按 URL 锚点发,轮换兜底。
          Log.i(tag, "SabrStream sid=$sessionId stream=$streamType reqSeq=${req.sequenceNumber} realSeq=${result.mediaHeader?.sequenceNumber} startTimeMs=${req.startTimeMs} isInit=${req.isInit} bytes=${result.data.size}B dur=${result.mediaHeader?.durationMs}ms playerTimeMs=${req.playerTimeMs}")
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
    Log.w(tag, "SabrStream sid=$sessionId stream=$streamType seq=${lastReq?.sequenceNumber} fetchUntilReady exhausted $BACKOFF_MAX_ATTEMPTS backoffs (server not resuming) → EOF → evict session for stall-retry fresh harvest")
    return null
  }

  private companion object {
    /** alpha.36:单段请求的 backoff 重试上限(对齐 FreeTube 简洁 reload 语义;耗尽→EOF→evict→stall-retry 新 harvest)。 */
    const val BACKOFF_MAX_ATTEMPTS = 6
    /**
     * alpha.52(窗口):每 SABR 会话服务的 60s 窗口长度(ms)。服务端对每会话服务量上限 [anchor..anchor+60s]
     * (alpha.51 坐实,URL 锚点绕不开)。窗口起点 = harvest 的 watch `&t=`(起播位置),滚动推进 0→60→120...。
     */
    const val WINDOW_LEN = 60_000L
    /**
     * alpha.52(窗口):预取触发阈值——当前窗口会话 cumulative 距锚点 ≥ 此值(40s)时,后台 harvest 下一个
     * 窗口的会话([SabrStreamRegistry.requestRotation] 锚定 sessionAnchorMs+WINDOW_LEN)。cumulative 40s 时
     * playhead 才 ~25s(缓冲 lead,alpha.51 证),远在 60s 死亡点前;harvest 5-6s 在 ~48s 完成,60s 切换时
     * 已就绪。比 alpha.48/49 的 playhead≥45s(够不到,0 次触发)可靠得多。
     */
    const val PREFETCH_AT_MS = 40_000L
    /**
     * alpha.52(窗口):切换门阈值——旧窗口 cumulative 距锚点 ≥ 此值(60s=窗口耗尽)才切到新窗口会话。
     * 旧会话最后一段 duration 把 cumulative 顶过 60s(如 video seq12→61604)→ 旧已缓冲到窗口边界,新从
     * 边界续,无 buffer gap。切早了(如 50s)留 [50..60] 缺口 → stall。
     */
    const val SWITCH_AT_MS = 60_000L
    /** alpha.45:上报给服务端的 bufferedRange 终点封顶值(ms)。服务端对客户端缓冲终点有 ~60s 上限
     * (maxTimeSinceReq),超过即软拒只回 NEXT_REQUEST_POLICY backoff 不发 media。封顶在 55s(<60s)
     * 让服务端永远看到客户端只缓冲到 55s 持续发段,避免 cumulative 随 playhead 涨破 60s 触发断崖。 */
    const val MAX_REPORTED_BUFFER_MS = 55_000L
  }
}
