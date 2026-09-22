package com.kirin.mt.core.youtube.sabr.media

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.kirin.mt.core.youtube.sabr.ClientAbrStateInput
import com.kirin.mt.core.youtube.sabr.FormatId
import com.kirin.mt.core.youtube.sabr.SabrProto
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_FORMAT_INITIALIZATION_METADATA
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_MEDIA
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_MEDIA_END
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_MEDIA_HEADER
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_NEXT_REQUEST_POLICY
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_RELOAD_PLAYER_RESPONSE
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_CONTEXT_SENDING_POLICY
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_CONTEXT_UPDATE
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_ERROR
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_SABR_REDIRECT
import com.kirin.mt.core.youtube.sabr.SabrProto.PART_STREAM_PROTECTION_STATUS
import com.kirin.mt.core.youtube.sabr.SabrRequestInput
import com.kirin.mt.core.youtube.sabr.SabrSession
import com.kirin.mt.core.youtube.sabr.SabrStreamRegistry
import com.kirin.mt.core.youtube.sabr.StreamerContextInput
import com.kirin.mt.core.youtube.sabr.UmpReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * alpha.64(端口 LibreTube `parser/SabrClient`):**单流多段** SABR fetcher。
 *
 * 取代 alpha.27-63 的双流 [com.kirin.mt.core.youtube.sabr.SabrClient](两路独立 POST,video bitfield=2 /
 * audio bitfield=1,对方格式 fake-full Int.MAX)。本 fetcher 对齐 LibreTube:
 *  - 一次 POST 同时请求 A+V([selectedFormatIds]=全部已初始化格式,[bitfield]=0)→ 服务端单响应推多段。
 *  - [bufferedRanges] 全部真实(每格式 buildBufferedRanges 按 downloadedSegments/bufferedSegments 算),
 *    **从不发 Int.MAX fake-full**——这是 alpha.63 证伪双流后锁定的 60s 断崖真因修复。
 *  - 多段缓存进 [initializedFormats](per itag)的 downloadedSegments,[getNextSegment] 逐段取。
 *
 * A/V 在 [SabrMediaPeriod] 层拆成两个 ChunkSampleStream 共享**一个**本 fetcher(单会话/单 clientNumber),
 * 服务端 60s-per-session 窗口在单流下靠 cookie 轮换无缝续推跨 60s,不再边界软拒。
 *
 * 终端结果处理保留(对齐我们 alpha.41-62 的稳健模型,优于 LibreTube 直接 throw):
 *  - Backoff → fetchStreamData 起始 sleep(封顶 2.5s 防 stall watchdog)+ [getNextSegment] 内重试同请求。
 *  - SABR_REDIRECT → [SabrSession.applyRedirect] 写回 sabrUrl,重试同请求。
 *  - RELOAD_PLAYER_RESPONSE / InvalidPoToken(status3)/ SABR_ERROR → 抛 [SabrTerminalException]
 *    → [SabrDataSource] evict 会话 → 播放器 error-retry 走新 harvest。
 *
 * 线程安全:[getNextSegment] 用 runBlocking{withContext(Dispatchers.IO.limitedParallelism(1)){...}}
 * 串行化(同 LibreTube),两个 ChunkSampleStream 的 loader 线程不会并发改 [initializedFormats]。
 *
 * 对齐 LibreTube `parser/SabrClient.kt`(MIT)。
 */
@OptIn(UnstableApi::class)
internal class SabrMediaFetcher(
  /**
   * alpha.66/67:整个会话 Entry(含 session/poTokenState/refreshPoToken)。改传 Entry 而非散参数,
   * 因 [poTokenState] 提升到会话级——切清晰度重建 fetcher 时新 fetcher 仍读已刷新的 token
   * (修 alpha.65 fetcher-instance currentPoToken 重建即丢的回归)。status=2 时在 [media] 的
   * readParts 后**同步**重铸 PO token(对齐 LibreTube,下个请求一定带新 token → status=3 不再出现)。
   */
  private val entry: SabrStreamRegistry.Entry,
  private val httpClient: OkHttpClient,
) {
  private val tag = "YtSabr"

  /**
   * P11-166:SABR 请求专用的 client 克隆 —— **把「静默超时」与「整调用上限」分开**。
   *
   * 共享的 YouTube client([BiliHttpClientFactory.baseBuilder])是给**小 API 调用**定的
   * `connect/read/write = 15s`;而 SABR 是流式媒体请求。P11-160 曾把 `readTimeout` 直接抬到与
   * `callTimeout` 同值(18s/40s),方向对但**代价没被算进去** —— 真机 `logs_live_20260921_171235.log`:
   * 第 1 次尝试 `17:11:07.944 fetch rn=0` 发出 → **18 秒零字节** → timeout → evict → **`rn=1` 立刻重试
   * 4.3 秒就成了**(`REAL 5114720B 4349ms`)⇒ **那 18 秒是我们自己在等的**,重试明明只要 4 秒。
   *
   * OkHttp 的 `readTimeout` 语义 = **「多久没有新字节」**(每收到一字节即重置)——正是"零字节停顿"的判据,
   * 而慢滴下载(字节一直在来)不受影响。故:
   *   · `readTimeout` = [SabrSilenceTimeoutMs] **8s**(静默 8 秒即切、立刻重试);
   *   · `callTimeout` 仍按档高自适应(P11-153:≤1080p 18s / ≥1440p 40s),由 `call.timeout()` 每次设。
   * 依据分布:**成功的首笔总耗时 3.5~7.2s**,**零字节停顿的都 ≥16.5s** —— 8s 正落在空档里。
   *
   * 克隆与父 client 共享连接池/Dispatcher,lazy 预建,不进热路径分配。
   */
  private val sabrHttpClient: OkHttpClient by lazy {
    httpClient.newBuilder()
      .readTimeout(SabrSilenceTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      .build()
  }
  /** 派生自 [entry](会话级,切清晰度重建 fetcher 时复用已刷新的 token)。 */
  private val session = entry.session
  private val poTokenState = entry.poTokenState

  /**
   * alpha.9X:本会话绑定的 videoId(透传 [entry.videoId])。供 [SabrDataSource.open] 在
   * RELOAD_PLAYER_RESPONSE 已发生(reloadCount>0)时快速失败判定,免得 ExoPlayer chunk load
   * 重试逐个白跑网络请求。
   */
  val videoId: String? get() = entry.videoId

  /** 已初始化格式(itag → InitializedFormat)。FORMAT_INITIALIZATION_METADATA part 建表。 */
  private val initializedFormats = mutableMapOf<Int, InitializedFormat>()

  /**
   * 2026-09-20(C1「跟着服务端走」):**服务端实际推来的视频 itag 集合**(FORMAT_INITIALIZATION_METADATA
   * 里出现过的,含被白名单跳过的)。
   *
   * 依据(r2019 真机):材料会话的供流格式**绑定在浏览器那一场会话上** —— 服务端只推 itag=251/396
   * (浏览器 Auto 选的),而我们选 140/315;两者不相交 ⇒ 我们的档从不被初始化 ⇒ `no seg 0 [fmt=null]`
   * 无限循环。**我们能选的只有服务端愿意给的**,所以选档必须在它推来的集合内进行。
   *
   * 自纠正:pot-less / 非材料会话里服务端推的就是我们的档 ⇒ 该集合≈我们的档 ⇒ 行为不变。
   */
  private val serverPushedVideoItags: MutableSet<Int> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

  /** C1:当前已知的「服务端推来的视频 itag」快照(空 = 还没收到任何 FORMAT_INIT)。 */
  fun serverServedVideoItags(): Set<Int> = serverPushedVideoItags

  /**
   * P11-130(升档预加载):下一档候选 itag —— 由 [DefaultSabrChunkSource] 在「下一档可负担 + 缓冲健康」
   * 时上报。生效期内本 fetcher 把该档一起写进请求的 `preferredVideoFormatIds`、**不报它的 bufferedRange**
   * (报"没有"=请把数据推给我)、并在清理非当前格式时保住它,让服务端把它的 init(+段)推回来缓存
   * ⇒ 切档那一刻 `getNextSegment` 直接命中缓存,不必现拉。
   * 动因(真机 2026-09-20):每次升档要 2.5–7.2s(新档 init 往返 + 新档首段 2.3–7.2MB),期间**视频轨**
   * 没数据、音频轨照播 ⇒ 用户看到「升档视频停顿、音频没断」。
   */
  @Volatile private var prefetchItag: Int? = null
  // 2026-09-20:@Volatile——写入方从此有两个(`prefetchFormat` 的 loading 线程 + `cancelPrefetch` 的
  // chunk-source 评估线程),而读取在请求构造线程;非 volatile 时撤销可能对下一笔请求不可见。
  @Volatile private var prefetchUntilMs = 0L

  /** P11-130:上报升档候选档(窗口期内有效;同一档重复上报不刷日志)。 */
  fun prefetchFormat(itag: Int) {
    val now = System.currentTimeMillis()
    val fresh = prefetchItag != itag || now >= prefetchUntilMs
    prefetchItag = itag
    prefetchUntilMs = now + PREFETCH_WINDOW_MS
    if (fresh) Log.i(tag, "prefetch: 候选档 itag$itag 随下一请求预取(窗口 ${PREFETCH_WINDOW_MS / 1000}s)")
  }

  /** P11-130:当前生效的预取档(null=无)。每次请求/清理时现读,窗口过期自动失效。 */
  private fun activePrefetchItag(): Int? =
    // 2026-09-20(A4a):材料会话整体停用预取 —— 请求不再发 preferred*(对齐浏览器的字段集),预取本就
    // 靠 preferred 第二位承载,留着它只会让 bufferedRanges 白剔一个格式 + retainAll 白保一份缓存。
    // 且预取本身已判定无效(P11-130 真机)并会抢响应预算(P11-136/137),停用只赚不亏。
    if (session.leadingClientAbrStateBytes != null) null
    else prefetchItag?.takeIf { System.currentTimeMillis() < prefetchUntilMs }

  /**
   * 2026-09-20(预取窗口撤销):让本窗口立刻失效。两个调用点、两条不同的证据链:
   *
   * ① **缓冲跌破撤销线**(DefaultSabrChunkSource.maybePrefetchNextTier)——语义"进入条件是持续条件"。
   * ② **本次请求的段没送达**(本类 `no seg` 重试路径)——语义"服务端把响应预算给了预取档,当次的段
   *    挤不进来"。这条是主钩子:水位那条挂在 `getNextChunk` 上,而 `no seg` 暴风期 loader 卡在重试
   *    循环里、`getNextChunk` 不被调用(r2006 实测 32s 只有 1 个评估点),那条路结构上够不着故障。
   *
   * 为什么必须能撤销:窗口(30s)此前是**进入时检查一次**就一路生效——真机三场播放到缓冲塌到 0 的
   * 全过程中,每个请求仍在 `preferredVideoFormatIds` 里点名要 1080p itag335,服务端照办(每笔响应
   * 7.6–7.9MB 全是 335),而白名单只认 `[140,698]` → 整段丢弃,且**当次请求的 698 段挤不进来** →
   * `no seg` → 6 连重试 → `terminal → evict` → 新会话(窗口还在)再问一次。r2006 实测量级:
   * 12 次 × ~7.6MB ≈ 91MB 全丢、`skip FORMAT_INIT itag=335` 14 次 == `no seg` 14 次、
   * 缓冲 29.5s → 5.8s → 2.6s 卡顿。撤销后请求不再点名它,当次段得以正常送达。
   *
   * 只清窗口,**不动**该档在 chunk source `prefetchedTiers` 里的记录:本会话不再重试该档,保持
   * 「同一档只报一次、不持续白吃带宽」的原意(该档能否重试需等白名单放行验证后再评估)。
   */
  fun cancelPrefetch(reason: String) {
    val cur = activePrefetchItag() ?: return
    prefetchItag = null
    prefetchUntilMs = 0L
    Log.i(tag, "prefetch canceled: itag$cur 撤销($reason) — 不再与正在播的格式抢响应预算")
  }

  /** 正在处理的 partial 段(headerId → Segment,MEDIA 累积/MEDIA_END 收尾)。 */
  private val partialSegments = mutableMapOf<Int, SabrSegment>()

  /**
   * 当前选中的音频/视频 FormatId(由 [SabrMediaPeriod] selectTracks→[selectFormat] 设)。
   * 2026-08-31 A:videoFormat 加 @Volatile——[recordFetchGap] 在 fetcher IO 线程读它的 height
   * 判顶档滑行余量,而 selectFormat 在 loader 线程写,跨线程可见。
   */
  private var audioFormat: FormatId? = null
  @Volatile private var videoFormat: FormatId? = null

  /**
   * 2026-08-31(修续播重建窗口饿死):**在途段请求的 itag 集合**(getNextSegment 进入时加、返回/抛错时移除,
   * ConcurrentHashMap.newKeySet 因 loader 线程与 fetcher 并发)。processPart 的广告白名单必须包含它们——
   * 21:01 真机:重建后新选组初始档翻 302(videoFormat 变更),在途旧 chunk(298 seg=336)的响应数据
   * 全被 `skip ad/unrequested` 丢弃 → hasSegment 永假 → 六连重试(含一次 5.4s 慢响应)独占串行 fetcher
   * 8.5s → 新轨 init 排不上队 → 看门狗重载。白名单=当前选中+在途请求,广告防御语义不变(从没被请求
   * 过的 itag 照丢)。
   */
  private val pendingRequestItags: MutableSet<Int> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

  /** 请求序号(每次 POST 追加 &rn=,AtomicInt 防 A/V 两 loader 并发)。 */
  private val requestNumber = AtomicInteger(0)
  /** 上次请求墙钟(epoch ms,0=首次),算 elapsedWallTimeMs。 */
  private val lastRequestMs = AtomicLong(0L)

  /** 服务端 NextRequestPolicy 回传的 backoff(ms),下次 fetchStreamData 起始 sleep。 */
  @Volatile private var backoffTime: Int? = null
  /** SABR_REDIRECT 给的新 url(写回 session.sabrUrl)。 */
  @Volatile private var redirectUrl: String? = null
  @Volatile private var invalidPo = false
  @Volatile private var fatalError: String? = null
  @Volatile private var reloadPlayerDump: String? = null
  /**
   * alpha.67(对齐 LibreTube status=2 同步刷新):processPart 里 status=2 置位,media() 的 readParts
   * 之后同步重铸 PO token(阻塞 loader 线程 ~1s)。下个请求一定带新 token → status=3 不再出现。
   * 异步(alpha.66 maybeRefreshPoToken)有竞态:刷新晚一拍,请求带旧 token 撞 status=3 → 全量重载。
   */
  @Volatile private var needsPoTokenRefresh = false

  /** selectTracks 时间戳(由 [SabrMediaPeriod] 写,ClientAbrState timeSinceLast* 用)。 */
  @Volatile var lastSeekMs: Long? = null
  @Volatile var lastManualFormatSelectionMs: Long? = null
  @Volatile var lastActionMs: Long? = null

  private val dispatcher = Dispatchers.IO.limitedParallelism(1)

  // alpha.9X(真实带宽判断机制):媒体3 DefaultBandwidthMeter 从 SabrDataSource 上报的样本波动离谱
  // (2026-08-24 真机 bw= 在 1M↔437M 之间 1000 倍跳变),effectiveBitrate 不可信 → AdaptiveTrackSelection 选档
  // 判定错误。这里直接从实际段下载采集真实吞吐。原实现是「分段采样→最近样本中位数」,但失败/卡死的段
  // 不产生样本 → 断流时刻带宽停留在历史高位,ABR 无降档依据,只能等看门狗整段重载。
  // 改为「滑动窗口累计」:带宽 = 窗口内累计下载量 / 窗口内累计耗时。**失败段也要计时间(下载量=0)**,
  // 卡死那段时间「分子不变、分母一直涨」→ 带宽自动下探,让 ABR 在缓冲耗尽前降档,而非静默丢弃失败。
  // 锁保护跨线程读写(getNextSegment 在 IO 线程、chunk source 在主 loader 线程)。
  private class RealBwSample(val bytes: Long, val timeMs: Long)

  private val realBandwidthLock = Any()
  private val realBwWindow = ArrayDeque<RealBwSample>()
  private var realBwBytes = 0L
  private var realBwTimeMs = 0L
  /** 2026-09-01 重填容量环(每次成功媒体请求一笔,无墙钟衰减),中位数喂升档判据。 */
  private val capacityWindow = ArrayDeque<RealBwSample>()

  // 2026-08-30(实测消耗码率,升降档门槛换地基):声明码率在高码率源上虚高约 2×(真机 302 声明 11.25M
  // 实测 6.3M、303 声明 22.26M 实测 ~11.5M),声明×est 双失真让乘数门槛(1.25/1.1)连续两轮卡在临界。
  // 这里从 MEDIA_END 挂账(itag → bytes + 已计段数),实测消耗码率 = bytes×8000 / 段数×平均段时长
  // (INIT metadata duration/endSegmentNumber)。只按单调 seq 增量记账(重传旧段不重复计入);
  // <MEASURED_MIN_SEGS 段时证据不足返回 -1,调用方回退声明值,起播首 ~3 段维持旧行为。
  private class MeasuredTrack {
    var bytes = 0L
    var segs = 0L
    var maxCountedSeq = -1L
  }

  private val measuredLock = Any()
  private val measuredTracks = mutableMapOf<Int, MeasuredTrack>()
  /** P11-85 诊断:tf dt 探针每 itag 只打一次的日志去重集合。 */
  private val tfdtProbeLogged = mutableSetOf<Int>()

  // alpha.9Z(升档用「持续带宽」):滑动窗口只计传输活跃耗时,重填缓冲期间背靠背突发会把 est 冲到
  // 40-70M(2026-08-27 真机:一笔 74Mbps 突发把 est 从 16M 抬到 40M,恰好过 4K 门槛 → 升完必卡——
  // 服务端 pacing 把长期有效供给压回 16-20M)。持续带宽 = 过去 60s 墙钟内成功交付的媒体字节 / 墙钟
  // 时长(固定分母,空闲/pacing 全摊入),专供升档判定「这条管子长期扛不扛得住」;降档仍用滑动窗口
  // est(反应快)。
  private class SustainedSample(val endWallMs: Long, val bytes: Long)
  private val sustainedSamples = ArrayDeque<SustainedSample>()
  private var sustainedBytes = 0L

  // 需求驱动空闲扣减(2026-08-30,sustained 分母修正):sustained 分母原为全墙钟跨度,满缓冲停闸的空窗(需求
  // 所致,管道容量与它无关)也摊进去 → pinned 低档时 sustained≈当前档消耗(定点死锁),永不满足高
  // 一档门槛。这里与 active est 的 gap 滑行量扣减同口径:满缓冲停闸部分不计入持续分母;真供给中断
  // (runway 小、滑行扣不掉)仍留在分母里,315 防卡意图不变。
  private class SustainedGapSample(val endWallMs: Long, val ms: Long)
  private val sustainedGapSamples = ArrayDeque<SustainedGapSample>()
  private var sustainedGapMs = 0L

  private fun addSustainedSample(bytes: Long) {
    val now = System.currentTimeMillis()
    synchronized(realBandwidthLock) {
      sustainedSamples.addLast(SustainedSample(now, bytes))
      sustainedBytes += bytes
      while (sustainedSamples.size > 1 && now - sustainedSamples.first().endWallMs > SUSTAINED_WINDOW_MS) {
        sustainedBytes -= sustainedSamples.removeFirst().bytes
      }
    }
  }

  private fun addSustainedGapSample(ms: Long) {
    if (ms <= 0L) return
    val now = System.currentTimeMillis()
    synchronized(realBandwidthLock) {
      sustainedGapSamples.addLast(SustainedGapSample(now, ms))
      sustainedGapMs += ms
      while (sustainedGapSamples.size > 1 && now - sustainedGapSamples.first().endWallMs > SUSTAINED_WINDOW_MS) {
        sustainedGapMs -= sustainedGapSamples.removeFirst().ms
      }
    }
  }

  // alpha.9Z(gap 计时归总到带宽):样本分母原先只计「传输活跃耗时」,fetch 与 fetch 之间的空窗一律不计。
  // 2026-08-27 真机实证:GC 风暴把 loader 线程卡死 8s,期间管道交付速率=0,但 fetch 根本没发起——连
  // 失败样本都不产生,est 钉在传输期高位(83M),ABR 永不降档,只能等看门狗整段重载。改为:每次发 POST
  // 前算 gap=本次开始−上次 fetch 结束,超出「缓冲可滑行量(runway − 安全余量)」的部分作为 bytes=0 样本
  // 计入窗口;满缓冲主动停闸期间缓冲从高位滑行的时间被扣掉,不误杀正常 prefetch。seek/手动选档后的
  // gap 是操作开销非供给问题,跳过。runway 由视频 chunk source 每次 getNextChunk 喂(noteBufferedAheadMs),
  // 在上次 fetch 结束时快照(gap 开始时刻的缓冲水位)。
  @Volatile private var lastFetchEndMs = 0L
  // alpha.9Z 修正(2026-08-27 真机):lastFetchEndMs 必须用墙钟(System.currentTimeMillis)——
  // lastSeekMs/lastManualFormatSelectionMs 都是 epoch 值(Instant.now/System.currentTimeMillis),
  // 若用 elapsedRealtime(开机时长 ~1e6)比较,(prevSeekMs > prevFetchEndMs) 恒真 → gap 永远被判成
  // seek 后开销跳过,带宽计整场 0 条 gap 样本,升档后 est 钉突发速率 → 卡死→重载→爬档→再卡死死循环。
  // r1660 实证:4K pinned 60s 缓冲 16s→5.4s 一条 gap 都没记。
  @Volatile private var bufferedAheadNoteMs = -1L
  @Volatile private var bufferedAheadMsAtLastFetch = -1L

  /** 由视频 [DefaultSabrChunkSource] 每次 getNextChunk 喂:播放位置前方缓冲水位(ms)。仅视频轨喂(音频轨缓冲远超需求,会污染滑行量判定)。 */
  fun noteBufferedAheadMs(ms: Long) {
    bufferedAheadNoteMs = ms
  }

  /**
   * 2026-09-20(材料会话的**位置锚**,见 fetchStreamData):由视频 [DefaultSabrChunkSource] 每次
   * getNextChunk 喂当前播放位置(ms)。
   *
   * 为什么需要:材料会话的 rn=0 是 init 请求,它自己的 `playerTimeMs` 按定义是 0,而材料那份 CAS 也是
   * `playerTimeMs=0` ⇒ 两边都说「我在 0」⇒ **服务端从 seg 0 起推**。续播到 402s 时我们要的是那附近,
   * 于是请求段与推送游标对不上 → `no seg` 6 连 → evict(r2017 真机:`MEDIA_END seq=0..4` 全是片子开头)。
   * 用真实播放位置替代那个 0,让服务端游标落在正确位置。仅材料会话生效。
   */
  @Volatile private var playbackPositionNoteMs = -1L

  fun notePlaybackPositionMs(ms: Long) {
    playbackPositionNoteMs = ms
  }



  /**
   * 2026-08-31 A(顶档 sustained 分母收紧):当前视频档是否顶档(≥2160)——顶档在位时 [recordFetchGap]
   * 的 sustained 滑行扣减改用 20s 余量(见 TOP_TIER_GAP_RUNWAY_RESERVE_MS)。4K 的 SABR pacing 下
   * 请求间隔多为 fetcher 串行排队等待(23:24 真机:水位 16.4s 下 14.2s 间隔),旧 10s 余量把它扣成
   * 「合法滑行」→ sus 全看不见死亡行军(有效 ~16M vs 声明 26.6M,sus 反而读 30M+),冷却一到期 4K
   * 准入闸就被低档突发样本「合法通过」。20s 与 ABR 顶档水位急救阈值(HeightAware B1)同值同语义:
   * 水位 <20s 期间的间隔全额留在持续分母 → sus 塌到有效供给,顶档重准入被真证据挡住。
   * **只收 sustained,不收活跃 est**:est 侧若同步加严,千兆管道 4K 满缓冲滑行期(loader 停拉期间
   * 缓冲 48s→10s,每周期 ~10s 空档)也会被打成 0 供给样本 → 好管道的 4K 被误踢。est 保持突发口径,
   * 死亡行军的退出由 B1 水位急救(确定性,拉 180s 冷却)兜底,A 只负责「失败证据进 sus,防快速重进」。
   */
  private fun isTopTierVideoSelected(): Boolean =
    (videoFormat?.height ?: 0) >= TOP_TIER_GAP_RESERVE_MIN_HEIGHT

  /**
   * 记录上次 fetch 结束到本次发起之间的被迫空转。只有超出「滑行量」的部分算供给损失,计 bytes=0 样本
   * (窗口带宽下探);满缓冲滑行部分不惩罚。在每次 media() 发请求前调用。
   *
   * 2026-09-20(**服务端 backoff 睡眠剔除**):`NEXT_REQUEST_POLICY` 要求我们等的那段(上限
   * [MAX_BACKOFF_SLEEP_MS])发生在 [fetchStreamData] 开头,fetchStartMs 在它之后才取 → 全额落进
   * rawGapMs。但那是**服务端叫停**,不是链路供给不足也不需求驱动空闲——计进零供给样本等于自己拽低
   * est。真机 13:58:11 / 13:58:15 各 2010ms / 2015ms(coast=0,因为 runway 4305/172 < 10s 余量),
   * est 23752K→17926K→13836K。修:由 [serverBackoffSleepMs] 原样扣除后再算 coast/counted/sustained,
   * 扣除量既不计 0 供给样本、也不作需求空闲扣减(两边都不偏袒);日志保留 raw 与 backoff 供取证。
   */
  private fun recordFetchGap(
    prevFetchEndMs: Long,
    prevSeekMs: Long?,
    prevManualMs: Long?,
    runwayMs: Long,
    fetchStartMs: Long,
    serverBackoffSleepMs: Long = 0L,
  ) {
    if (prevFetchEndMs == 0L) return
    val rawGapMs = fetchStartMs - prevFetchEndMs
    if (rawGapMs <= 0L) return
    // 服务端要求的 backoff 睡眠 = 操作开销,从 gap 里原样剔除(coerce 防时钟跳变导致的负值)。
    val backoffMs = serverBackoffSleepMs.coerceIn(0L, rawGapMs)
    val gapMs = rawGapMs - backoffMs
    if (gapMs <= 0L) return
    val demandIdleMs: Long
    if ((prevSeekMs ?: 0L) > prevFetchEndMs || (prevManualMs ?: 0L) > prevFetchEndMs) {
      // seek/手动选档后的 gap 是操作开销非供给问题:不计 active est,也不计入持续带宽分母。
      demandIdleMs = gapMs
      // P11-151(a):同时把「刚发生过操作事件」告诉 ABR —— 紧随其后的降档不按「供给不足」惩罚
      // (否则一次 seek 就把源档锁 90~180 秒,画面钉在最低档;22:00:09 真机实证)。
      SabrAbrMemory.noteOperationEvent()
    } else {
      // 2026-08-30:runway 负值 = 快照滞后(1263-1265 真机 runway=-65),拿不到可靠滑行量 → 证据不足,
      // 全额当需求空闲处理:不惩罚 est(不计 0 供给样本),空窗照常进 sustained 分母扣除。
      val coastMs = if (runwayMs < 0L) gapMs
        else (runwayMs - BW_GAP_RUNWAY_RESERVE_MS).coerceAtLeast(0L)
      // ── P11-167:超长 gap **不是供给不足**,不许当零供给样本入账 ─────────────────────────────
      // 真机 `logs_live_20260921_214100.log`:用户**手动暂停**了 113 秒,于是
      //   `bw gap counted: 30000ms (raw=113358ms backoff=0ms coast=38366ms runway=48366)`
      // 即 `addRealBwSample(0L, 30_000L)` 往 active 窗口塞了一个「0 字节 / 30 秒」样本 ⇒ est 塌到 ≈0
      // ⇒ 紧接着 `downgrade 1440p → 144p: est=0K sus=-1 bufS=8s` **砸到最低档**,而且之后的请求都是
      // 144p 小段(59~132KB,多被 [REAL_BW_MIN_BYTES] 过滤)、est 长时间爬不回(1.1~1.7Mbps),就
      // 「卡在 144p」。**供给不足时 loader 会立刻再要下一笔**(gap 量级≈秒级),113 秒只可能是
      // 用户暂停/后台/切页 ⇒ 一律按需求空闲处理(不计 active est),并留一行日志与「供给停顿」区分。
      val countedMs = if (gapMs > BW_GAP_IGNORE_MS) {
        Log.i(
          tag,
          "bw gap ignored: raw=${rawGapMs}ms(> ${BW_GAP_IGNORE_MS}ms)→ 按需求空闲处理(用户暂停/后台)," +
            "不计 active est(否则 est 会被拽到 0 ⇒ 砸最低档)",
        )
        0L
      } else {
        (gapMs - coastMs).coerceIn(0L, BW_GAP_MAX_MS)
      }
      if (countedMs >= BW_GAP_MIN_MS) {
        addRealBwSample(0L, countedMs)
        Log.i(
          tag,
          "bw gap counted: ${countedMs}ms (raw=${rawGapMs}ms backoff=${backoffMs}ms " +
            "coast=${coastMs}ms runway=$runwayMs)",
        )
      }
      // 需求驱动空闲扣减(2026-08-30):滑行部分(满缓冲主动停闸)= 需求驱动的管道空闲 → 记入持续分母扣除量。
      // 2026-08-31 A:顶档在位时持续分母的滑行扣减改用 20s 余量(见 isTopTierVideoSelected)——est 口径
      // 不变,只让 4K pacing 排队等待进 sus 分母。
      val sustainedCoastMs = if (runwayMs < 0L || gapMs > BW_GAP_IGNORE_MS) gapMs
        else if (isTopTierVideoSelected()) (runwayMs - TOP_TIER_GAP_RUNWAY_RESERVE_MS).coerceAtLeast(0L)
        else coastMs
      val countedForSustainedMs = (gapMs - sustainedCoastMs).coerceIn(0L, BW_GAP_MAX_MS)
      demandIdleMs = (gapMs - countedForSustainedMs).coerceAtLeast(0L)
    }
    addSustainedGapSample(demandIdleMs)
  }

  /** 记录一次真实段下载样本(fetchStreamData 成功时调用)。快小样本(init/retry/音频段)过滤;但慢小样本(2026-08-27 真机:服务端挂 8.5s 只回 939B)是真实供给中断,必须入账。 */
  fun recordRealBandwidthSample(bytes: Long, elapsedMs: Long) {
    if (elapsedMs <= 0) return
    if (bytes < REAL_BW_MIN_BYTES && elapsedMs < BW_SLOW_TINY_MS) return
    addRealBwSample(bytes, elapsedMs)
    if (bytes >= REAL_BW_MIN_BYTES) {
      addSustainedSample(bytes)
      addCapacitySample(bytes, elapsedMs)
    }
  }

  /**
   * 2026-09-01 重填容量通道(修「Auto 满缓冲后结构性不升档」):活动 est(realBwWindow)含被迫空转
   * (alpha.9Z gap 入账),满缓冲停闸 30-40s 后衰减到 ≈播放消耗码率 → 当前档 playing 时 effective
   * 结构性 < 下一档声明码率,升档门(required > effective)被定点锁死(12:05 真机:720p 会话 sus
   * 顶 4.4M,1080p 需 5.5M;手动切 1440 实测 14-24Mbps 铁证管道富余)。本通道记**每次成功请求的
   * 瞬时吞吐**(bytes/HTTP 耗时,天然免疫墙钟空转——不发请求不产生样本,满缓冲期不衰减),取
   * 近 [CAPACITY_WINDOW_SAMPLES] 笔中位数(单笔 TCP 爬升/小段噪声被中位数抚平)。专供升档判据
   * (HeightAwareAdaptiveTrackSelection,仅 isUpgrade 用);降档仍走 [getRealBitrateEstimate]
   * (gap 入账语义不变,防「卡死不降档」复发);4K 顶档 sus×1.1 闸不变。
   */
  private fun addCapacitySample(bytes: Long, elapsedMs: Long) {
    synchronized(realBandwidthLock) {
      capacityWindow.addLast(RealBwSample(bytes, elapsedMs))
      while (capacityWindow.size > CAPACITY_WINDOW_SAMPLES) capacityWindow.removeFirst()
    }
  }

  /** 重填容量(bps)= 近 N 笔成功请求瞬时吞吐的中位数;样本 < 3 返回 -1(证据不足)。无墙钟衰减。 */
  fun getRefillCapacityBps(): Long {
    synchronized(realBandwidthLock) {
      if (capacityWindow.size < CAPACITY_MIN_SAMPLES) return -1L
      val sorted = capacityWindow.sortedBy { it.bytes * 8000L / it.timeMs.coerceAtLeast(1L) }
      return sorted[sorted.size / 2].bytes * 8000L / sorted[sorted.size / 2].timeMs.coerceAtLeast(1L)
    }
  }

  /**
   * 持续带宽(bps)= 过去 60s 墙钟内成功交付媒体字节 / 分母(墙钟跨度 − 需求驱动的停闸空闲,2026-08-30 修)。
   * 2026-08-30 真机实证:1080p 满缓冲 50s 后停闸 28s,全墙钟分母把空窗全摊入 → sustained≈8-10M 恒
   * <308 门槛 13.4M(手动切 1440 实测持续 20M+),升档被定点锁死。分母改为扣除滑行量(与 active est
   * 的 gap 扣减同口径)后,「管道空闲因为需求低」不再拉低估计,而 GC/断流/服务端 pacing 期间 runway
   * 低、滑行扣不掉,照旧压低 sustained → 防升高档后卡死的意图保留。证据 <10s(刚起播/暂停恢复,
   * 2026-08-31 15s→10s,见 SUSTAINED_MIN_SPAN_MS)返回 -1,由调用方回退活跃传输 est。专供升档判定,
   * 降档走 [getRealBitrateEstimate]。
   *
   * 2026-08-30 修 sus 垃圾尖峰(真机实测 500-635M 物理不可能):gap 扣减后跨度原被 coerceIn 夹到
   * 下限 1s,停闸空窗接近全跨度时(bytes/1s)直接爆炸。现改为:扣减后跨度不足 SUSTAINED_MIN_SPAN_MS
   * (证据不足)返回 -1,由调用方回退活跃 est,不再出垃圾值。
   */
  fun getSustainedBitrateEstimate(): Long {
    synchronized(realBandwidthLock) {
      val now = System.currentTimeMillis()
      while (sustainedSamples.size > 1 && now - sustainedSamples.first().endWallMs > SUSTAINED_WINDOW_MS) {
        sustainedBytes -= sustainedSamples.removeFirst().bytes
      }
      while (sustainedGapSamples.size > 1 && now - sustainedGapSamples.first().endWallMs > SUSTAINED_WINDOW_MS) {
        sustainedGapMs -= sustainedGapSamples.removeFirst().ms
      }
      if (sustainedSamples.isEmpty() || sustainedBytes <= 0L) return -1L
      val rawSpanMs = now - sustainedSamples.first().endWallMs
      if (rawSpanMs < SUSTAINED_MIN_SPAN_MS) return -1L
      val activeSpanMs = rawSpanMs - sustainedGapMs
      if (activeSpanMs < SUSTAINED_MIN_SPAN_MS) return -1L
      return sustainedBytes * 8000L / activeSpanMs.coerceAtMost(SUSTAINED_WINDOW_MS)
    }
  }

  /** 记录一次失败/卡住段(fetchStreamData 异常时调用):下载量=0、耗时计满,让窗口带宽下探。 */
  fun recordRealBandwidthFailure(elapsedMs: Long) {
    if (elapsedMs <= 0) return
    addRealBwSample(0L, elapsedMs)
  }

  /**
   * 2026-08-30 升档重锚(HeightAwareAdaptiveTrackSelection 升档时调用):清空活跃 est 窗口,种入
   * 「新档声明码率 × REAL_BW_RESEED_MS」的合成样本。升档前窗口里是旧档重填期的突发高估样本(真机
   * 60-70M),升档后新档供给不足时这些样本顶住「est < 声明码率」的降档门槛(4K 案例实际吞吐 27M vs
   * est 报 35-52M),缓冲 35s→4s 不降档。重锚后 est 从声明码率起步,真实样本平滑接管:够用就持平,
   * 不够(慢样本/失败样本入账)快速下探触发正常降档。仅升档调用;降档不需要(低档时 est 高估无害)。
   */
  fun reseedActiveWindow(bitrateBps: Long) {
    if (bitrateBps <= 0L) return
    val seedBytes = bitrateBps / 8000L * REAL_BW_RESEED_MS
    if (seedBytes <= 0L) return
    synchronized(realBandwidthLock) {
      realBwWindow.clear()
      realBwBytes = 0L
      realBwTimeMs = 0L
      addRealBwSample(seedBytes, REAL_BW_RESEED_MS)
    }
    Log.i(tag, "bw reseeded: active est baseline → ${bitrateBps / 1000}K (seed ${seedBytes}B/${REAL_BW_RESEED_MS}ms)")
  }

  /** 推进进滑动窗口;窗口累计耗时超 [REAL_BW_WINDOW_MS] 从前端滚出(至少留 1 个样本防除零)。 */
  private fun addRealBwSample(bytes: Long, timeMs: Long) {
    synchronized(realBandwidthLock) {
      realBwWindow.addLast(RealBwSample(bytes, timeMs))
      realBwBytes += bytes
      realBwTimeMs += timeMs
      while (realBwTimeMs > REAL_BW_WINDOW_MS && realBwWindow.size > 1) {
        val front = realBwWindow.removeFirst()
        realBwBytes -= front.bytes
        realBwTimeMs -= front.timeMs
      }
    }
  }

  /**
   * 实测消耗码率(itag 子流):MEDIA_END 挂账 bytes ÷ 段数×平均段时长。证据不足(段数 <3、
   * 尚无 INIT metadata)返回 -1。供升/降档门槛做声明码率的校准底座(HeightAwareAdaptiveTrackSelection)。
   */
  fun getMeasuredBitrateBps(itag: Int): Long {
    if (itag <= 0) return -1L
    val snapshot = synchronized(measuredLock) {
      val st = measuredTracks[itag] ?: return -1L
      if (st.segs < MEASURED_MIN_SEGS) return -1L
      Pair(st.bytes, st.segs)
    }
    val fmt = initializedFormats[itag] ?: return -1L
    if (fmt.duration <= 0L || fmt.endSegmentNumber <= 0L) return -1L
    val avgSegMs = fmt.duration / fmt.endSegmentNumber
    if (avgSegMs <= 0L) return -1L
    return snapshot.first * 8000L / (snapshot.second * avgSegMs)
  }

  /** 2026-08-30:实测已挂账段数(calib 成熟度地板用,决定门槛下限 0.65/0.5/0.35)。 */
  fun getMeasuredSegmentCount(itag: Int): Long {
    if (itag <= 0) return 0L
    return synchronized(measuredLock) { measuredTracks[itag]?.segs ?: 0L }
  }

  /** 真实带宽估计(bps)= 窗口内累计下载量/累计耗时(含卡住与被迫空转)。无样本返回 -1;窗口内全是空转(量=0)返回 0,不回退底层高估。 */
  /**
   * P11-167:估计值打日志用 —— **-1 原样显示为 `-1`**,不要走 `-1 / 1000`(Kotlin 整除得 0,
   * 会把「证据不足」印成「0K」——本仓已踩过同一个坑,见 `sus` 字段注释)。
   */
  private fun fmtEstForLog(bps: Long): String = if (bps >= 0L) "${bps / 1000L}K" else "-1"

  fun getRealBitrateEstimate(): Long {
    synchronized(realBandwidthLock) {
      if (realBwTimeMs <= 0L) return -1L
      // ── P11-167:**「无字节证据」不是「实测带宽为零」** ────────────────────────────────────────
      // 原实现此处 `return 0L`,于是窗口里只剩零字节样本(如 P11-167 那个 30s 空转样本)时,
      // est 被报成 0 ⇒ 降档判据读到 `est=0K` ⇒ 直接砸最低档(真机 21:40:05
      // `downgrade 1440p → 144p: est=0K`)。这与本仓已记过的同类坑一模一样(sus 的 -1 曾被
      // `-1/1000` 打成 `0K`,把「证据不足」误读成「证据为零」)。
      // 改为返回 -1(证据不足)⇒ [SabrBandwidthMeter.getBitrateEstimate] 回落 delegate
      // (media3 默认计,天然免疫空转),而不是把 0 当结论用。
      if (realBwBytes <= 0L) return -1L
      return realBwBytes * 8000L / realBwTimeMs
    }
  }

  /**
   * 由 [SabrMediaPeriod] 选轨时调用,按 mimeType 分 audio/video(对齐 LibreTube selectFormat)。
   * 重复设同 Representation 直接返回(幂等)。
   */
  fun selectFormat(representation: Representation) {
    val mime = representation.format.containerMimeType
    if (MimeTypes.isAudio(mime)) {
      if (audioFormat != representation.formatId) audioFormat = representation.formatId
    } else if (MimeTypes.isVideo(mime)) {
      if (videoFormat != representation.formatId) videoFormat = representation.formatId
    }
  }

  /** 末段序号([SabrChunkSource] 判 endOfStream 用)。 */
  fun getEndSegmentNumber(formatItag: Int): Long? = initializedFormats[formatItag]?.endSegmentNumber

  /**
   * P11-85:回喂段号→绝对时间网格(init 段解出的 ChunkIndex,loader 线程调)。
   * buildBufferedRanges 据此上报真实 startTimeMs/durationMs(header.startMs 恒 0 不可信)。
   */
  fun registerSegmentGrid(formatItag: Int, timesMs: LongArray) {
    initializedFormats[formatItag]?.seqStartMsBySeq = timesMs
  }

  /**
   * 取 [req] 指定段。若该格式未初始化或该段未下载 → 调 [media] POST 一批(单流多段),
   * 然后从 [initializedFormats] 取段。终端错误(RELOAD_PLAYER/InvalidPoToken/SABR_ERROR)抛
   * [SabrTerminalException];transient(backoff/redirect/段未到)在 [MAX_ATTEMPTS] 内重试。
   *
   * P11-92(2026-09-13 真机三轮 308 重载死循环):两处改——①media() 后 retainAll 清非当前
   * 选中格式(对齐 LibreTube),切档后旧格式 bufferedRange 不再上报;②服务端跳段(只回请求段
   * 之后的段)时空段顶位,不再 6 连试 terminal evict。
   */
  fun getNextSegment(req: SabrSegmentRequest): SabrSegment {
    fatalError?.let { throw SabrTerminalException("SABR error: $it") }
    val itag = req.formatItag
    // 2026-08-31:在途 itag 入白名单(修重建后旧在途请求响应被广告过滤丢弃的死循环),finally 移除。
    pendingRequestItags.add(itag)
    return runBlocking {
      try {
        withContext(dispatcher) {
          var attempt = 0
          while (attempt < MAX_ATTEMPTS) {
            attempt++
            // 同步 bufferedSegments:按 req.bufferedSegments 保留(清除已不缓冲的,防内存泄漏)
            initializedFormats[itag]?.bufferedSegments?.keys?.retainAll(req.bufferedSegments)

            var fmt = initializedFormats[itag]
            var preExistingSeqs: Set<Long> = emptySet()
            if (fmt == null || !fmt.hasSegment(req.segment)) {
              // 已下载但播放器不再要的段(seek 后)清掉,防泄漏
              fmt?.downloadedSegments?.clear()
              preExistingSeqs = fmt?.downloadedSegments?.keys?.toSet() ?: emptySet()
              // 单流 POST——服务端推多段,缓存进 initializedFormats
              media(req)
              // P11-92(对齐 LibreTube getNextSegment 收尾 retainAll):media() 后清掉非当前选中
              // 格式的已初始化缓存。此前切档后旧格式(如 302)的 bufferedRange 一直随请求上报——
              // 真机 2026-09-13 三轮 308 死循环实锤:请求体没显式段号,服务端按「全部 bufferedRange
              // 最大 endSegmentIndex+1」起推(=旧档缓冲尾),目标段永不到 → 6 连试 → terminal evict
              // → 全量重载 → ABR 再升同档 → 循环。清掉旧格式后 bufferedRange 只带音频+当前视频,
              // 服务端回落 playerTimeMs 判(§26.1 已实证回落路径),从目标段起推。
              // LibreTube 同位置无条件 retainAll(仅当前 A/V);这里保守多留在途 itag,在途响应不白丢。
              // 2026-09-20(诊断):这道清理会**静默**丢格式,而丢了之后所有请求都变 `no seg`
              // (r2017 真机:140/302 的 init 登记过、也成功交付过一次,之后却 `no seg 0` 6 连 → evict)。
              // 打点把「丢了谁、当时 audioFormat/videoFormat/在途 是什么」一次说清,避免再靠猜。
              val beforeKeys = initializedFormats.keys.toList()
              initializedFormats.keys.retainAll { f ->
                f == audioFormat?.itag || f == videoFormat?.itag || f in pendingRequestItags ||
                  f == activePrefetchItag() // P11-130:预取候选档的缓存要保住,否则刚预取到就被清
              }
              val dropped = beforeKeys - initializedFormats.keys
              if (dropped.isNotEmpty()) {
                Log.w(
                  tag,
                  "cleanup dropped formats=$dropped (audio=${audioFormat?.itag} video=${videoFormat?.itag} " +
                    "pending=$pendingRequestItags prefetch=${activePrefetchItag()})",
                )
              }
            }
            // 终端检查(media 可能置位)
            reloadPlayerDump?.let { throw SabrTerminalException("RELOAD_PLAYER_RESPONSE: $it") }
            if (invalidPo) {
              // P11-102d 取证:分辨「60s 服务窗口」vs「sabrContexts 全空」。窗口假设 → sessAgeMs/
              // sessReqN 落在 ~60s 边界;contexts 假设 → unhandledParts(52/53/57)非零且 ctx 恒 0。
              val ranges = initializedFormats.values.flatMap { it.buildBufferedRanges() }
                .joinToString { "itag${it.itag}:s${it.startTimeMs}+${it.durationMs}(${it.startSegmentIndex}-${it.endSegmentIndex})" }
              Log.w(
                tag,
                "InvalidPoToken diag: sessAgeMs=${System.currentTimeMillis() - entry.diagSessionStartMs}" +
                  " sessReqN=${entry.diagRequestCount.get()} status2Seen=${entry.diagStatus2Count.get()}" +
                  // P11-154/155:§5.10.1 #4 的「status=3 计数 = 0」直接可读,不必外部 grep 统计。
                  " status3Count=${entry.diagStatus3Count.get()}" +
                  " pot=${poTokenState.currentPoToken.size}B" +
                  " ctxActive=${session.activeSabrContextTypes.size} ctxStored=${session.sabrContexts.size}" +
                  " unhandled=${entry.diagUnhandledParts.entries.sortedBy { it.key }.joinToString { "${it.key}x${it.value}" }.ifEmpty { "none" }}" +
                  " req=[itag=${req.formatItag} seg=${req.segment} playerTimeMs=${req.segmentStartTimeMs}]" +
                  " ranges=[$ranges]",
              )
              // 2026-09-20(**运行时判死 WEB-SABR**,修「WEB-SABR 会话建起来了却每笔都死 → 无限重选」):
              // 真机 15:09-15:18(logs_live_20260920_151317/151843,r2008):WEB 形状(shape=ft)每会话**第 2 笔**
              // 就被 nag(status=2,sessAgeMs≈5.5s)→ 同步重铸 88B→208B → status=3 起每笔死;而同一份日志里
              // pot-less 那条(shape=libre,**pot=0B** 完全不带 token)一路 status=1、15:18:26 起连续 20 段成功。
              // 用户实测结论同:「SABR 可以,WEB-SABR 不行」。
              //
              // **为什么必须在这里标,而不是只靠 resolve 里的构建失败标记**:[markWebSabrFailed] 此前只在
              // `buildWebSabrFallback` 返回 null(构建失败)时调,而今天构建**从没失败**(harvest 每次都交出 88B);
              // 且 [YoutubePlaybackResolver] 在**构建成功那一刻**就 `clearWebSabrFailed` 并 return ——
              // 标记在「建起来」时被清、失败却发生在运行时 ⇒ 标记永远留不住 ⇒ 建成功→清→运行时死→resolve
              // 再来→WEB-SABR 触发条件仍真(该视频 `reloadCount>0`)→ 建成功→清→… **无限循环**,5+ 个会话 0 段。
              // 补上这一笔后:建成功→清→运行时死→**标记**→下次 resolve 见 `isWebSabrFailed` → 跳过 WEB-SABR
              // → 落 pot-less 主路(实测全绿)。效果:把「无法播放」变成「第一次失败后自动换到能播的路」。
              // 只在 WEB 会话上标(clientName==1 即 webShape),pot-less 主路的 status=3 另有原因,不该连坐。
              if (session.clientInfo.clientName == 1) {
                entry.videoId?.let { SabrStreamRegistry.markWebSabrFailed(it) }
              }
              throw SabrTerminalException("InvalidPoToken (StreamProtectionStatus status=3)")
            }
            fatalError?.let { throw SabrTerminalException("SABR error: $it") }

            fmt = initializedFormats[itag]
            val seg = fmt?.getSegment(req.segment)
            if (seg != null) return@withContext seg
            // P11-92 跳段自适应:服务端对刚切入的格式可能只推「请求段之后的段」——真机 09-13 请求
            // seq N → 回 N+1,N+2(6 次响应字节级相同),09-09 137/247、09-10 271 同签名。重试注定
            // 落空(同请求同响应),此前 6 连试耗尽 → terminal evict → 全量重载死循环。这里检测
            // 「目标段缺失 + 收到目标段之后的新段」→ 接受服务端游标:该段以**空段**顶位(零字节,
            // extractor EOF 直接收尾零样本),视频内容缺一段(~一个段时长),时间线由下一段的真实
            // 样本时间接上(P11-90/91 段网格 offset 语义),A/V 不失步;后续段已在缓存或由服务端
            // 游标顺延。仅媒体段生效(req.segment>0),init 段缺失走 transient 重试。
            if (req.segment > 0 && fmt != null) {
              val freshAhead =
                fmt.downloadedSegments.keys.filter { it > req.segment && it !in preExistingSeqs }
              if (freshAhead.isNotEmpty()) {
                fmt.downloadedSegments[req.segment] = SabrSegment(
                  header = SabrProto.MediaHeader(
                    headerId = 0, videoId = null, itag = itag, lmt = 0L, xtags = null,
                    isInitSeg = false, sequenceNumber = req.segment.toInt(),
                    contentLength = 0L, durationMs = 0L, startMs = 0L,
                  ),
                  sequenceNumber = req.segment,
                  data = mutableListOf(),
                  duration = 0L,
                )
                Log.w(
                  tag,
                  "getNextSegment: server skipped seg ${req.segment} itag $itag " +
                    "(fresh ahead=$freshAhead) → 空段顶位跳过 (P11-92)",
                )
                return@withContext fmt.getSegment(req.segment)!!
              }
            }
            // transient:段未到。三种成因:①服务端只回了 context+backoff 或 redirect(backoff 已在 media
            // 起始 sleep,redirect 已写回 session.sabrUrl)——循环重试同请求即可;②服务端只推了**同格式**
            // 更后面的段(上面 P11-92 分支已接走);③**服务端把响应预算给了别的 itag**(真机三场同签名:
            // 响应 7.6–7.9MB 全是预取档 itag335、白名单丢弃、当次的段挤不进来)——这一种**重试注定落空**,
            // 因为每笔重试都带着同一个预取档,服务端每次都做同样的取舍。第 ③ 种正是下面这行的处理对象。
            //
            // 2026-09-20(预取抢预算的**即时反馈**,主钩子):段没送达 = 预取档是首要嫌疑,当场撤销,
            // 让**下一笔**重试就能把段拿回来,而不是干等 30s 窗口自然到期(那之前每笔都在白下 ~8MB)。
            // **钩子必须在这里**:水位撤销挂在 `getNextChunk` 上,而暴风期 loader 卡在本循环里,
            // `getNextChunk` 不被调用(r2006 实测 14:46:36→14:47:08 只有 1 个评估点),那条路结构上
            // 够不着故障现场 —— 第一版(c)挂在那边,整场没打出一条 `prefetch canceled` 就是这个原因。
            cancelPrefetch("请求 seg ${req.segment} itag $itag 未送达(attempt $attempt)")
            // 2026-09-20(诊断):`no seg` 有两种完全不同的成因 —— ①这一档的缓存被整条丢了(fmt==null);
            // ②缓存还在,但**服务端推的游标不在请求段附近**(r2017 真机:请求 402s 附近的段,服务端却从
            // seq 0..4 起推)。把已有段的区间一并打出来,一次就能分清是哪种,不必再猜。
            Log.i(
              tag,
              "getNextSegment: no seg ${req.segment} itag $itag after attempt $attempt (retry)" +
                (if (fmt == null) " [fmt=null 缓存被丢]"
                 else " [cached=${fmt.downloadedSegments.keys.sorted()} buffered=${fmt.bufferedSegments.keys.sorted()}]"),
            )
          }
          throw SabrTerminalException("exhausted $MAX_ATTEMPTS attempts for seg ${req.segment} itag $itag")
        }
      } finally {
        pendingRequestItags.remove(itag)
      }
    }
  }

  /**
   * 发一次单流 POST + 处理 UMP 响应(对齐 LibreTube media + processUmpStream)。
   * backoff 起始 sleep(封顶 2.5s);redirect 写回 session;cookie/contexts 写回 session。
   */
  private suspend fun media(req: SabrSegmentRequest) {
    val data = fetchStreamData(req)
    val ump = UmpReader()
    ump.append(data)
    // 2026-09-20(无效流量取证):一次响应内的丢弃统计——见 DiscardTracker 为何必须是调用内局部量。
    val discard = DiscardTracker()
    ump.readParts { type, payload -> processPart(type, payload, discard) }
    // 2026-09-20(无效流量取证,只观测不改口径):本响应里被整段丢弃的媒体字节。
    // 真机三场「预取窗口 = 零可用数据窗口」期间每笔响应丢 7.6–7.9MB,而这些字节照进
    // `recordRealBandwidthSample` → est 一路抬到 32Mbps → 又作为 `bandwidthEstimate` **上报服务端**
    // → 服务端更确信该推高分辨率(恶性闭环)。口径动不动(是否剔出 est/sus/上报)等这个数出来再定。
    if (discard.bytes > 0L) {
      Log.w(
        tag,
        "discarded media: ${discard.bytes}B of ${data.size}B " +
          "(itags=${discard.itags.sorted()}) — 未白名单格式的段整段丢弃",
      )
    }
    // P11-144(取证):一笔响应的「我们请求的档 vs 服务端真正推来的档 + 可用字节」摘要。
    // 材料派/自造派的**格式墙**判据就落在这行:usable=0 且 init/pushed 里没有 req ⇒ 服务端只供别的档。
    Log.i(
      tag,
      "resp summary: req=${req.formatItag} usable=${data.size - discard.bytes}B/${data.size}B " +
        "init=${initializedFormats.keys.sorted()} pushed=${serverPushedVideoItags.sorted()}",
    )
    // alpha.67(对齐 LibreTube processPart status==2 `poToken = generatePoToken()` 同步):status=2 在
    // 本响应里出现 → 此处(下个请求发出前)同步重铸 PO token,下个请求一定带新 token → status=3 不再出现。
    // 异步(alpha.66 maybeRefreshPoToken 后台 launch)有竞态:刷新需 ~550ms,下一段请求在此之前发出带旧 token
    // → 撞 status=3 → evict → onPlayerError 全量重载 → 重播前 60s + 音频先出现。看门狗已取消(改动 3),
    // 同步阻塞 loader 线程 ~1s 安全(前方 ~20s lookahead 缓冲兜底,LibreTube 同栈同做法)。
    //
    // P11-102c(single-flight):needsPoTokenRefresh 是 fetcher 实例状态,会话里每个 fetcher 都会
    // 在自己的 status=2 响应后各自重铸——r1928 真机 13:30:04-08 实锤 4 个 fetcher 并发 4 次完整
    // BotGuard mint,token last-write-wins 互相踩 → InvalidPoToken(status=3)整会话死。改走
    // [SabrStreamRegistry.refreshPoTokenSingleFlight]:会话一把锁 + 5s freshness 共铸共复用。
    if (needsPoTokenRefresh) {
      needsPoTokenRefresh = false
      // ── P11-144(2026-09-20):**keep-stale 实验 —— status=2 时不再刷新** ─────────────────────
      // 三次同视频的并排证据(P11-141 把编码修好之后仍然如此):
      //   r2023 LSnM:刷新出 208 字符(**未**解码)→ 紧接着 status=3
      //   r2028 LSnM:同一枚 token(**已**解码 = 154B)→ 紧接着 status=3(`potAge` 极小,不是过期)
      //   r2026 GRbG:同一枚 token(已解码 = 154B)→ 被接受,继续 status=2、媒体照流
      // 即:刷新出的 token 时而被接受时而被判 InvalidPoToken,而**会话开头那枚 88B token 一直是被接受的**
      // (r2028 rn=0 那笔 6.3MB 响应里既有 status=2、也有我们的档与真媒体段)。故本轮把刷新停掉、
      // 保留原 token,用下一笔请求的 status 把两个假设分开:
      //   下一笔 status=1/2 且媒体在流 ⇒ **刷新才是凶手** ⇒ 该做「只在铸出可用 token 时才换」;
      //   下一笔照样 status=3          ⇒ **status=2 是硬处决** ⇒ 方向转「同一条铸造链重铸真 token」。
      val keepAge = System.currentTimeMillis() - poTokenState.currentPoTokenAtMs
      if (!REFRESH_PO_TOKEN_ON_STATUS2) {
        Log.w(
          tag,
          "status=2 但**刻意不刷新**(P11-144 keep-stale 实验)→ keep " +
            "${poTokenState.currentPoToken.size}B (age=${keepAge}ms); 下一笔请求的 status 即判据",
        )
      } else {
        // C 线(2026-09-20):刷新前后都打出 token 字节数。r2023 的判死证据正是「字节数 == 字符数」
        // (= websafe 串没解码就当 token 发出去,服务端 InvalidPoToken/status=3);修好后这里应看到
        // 换上去的字节数 ≈ 原值的 3/4(120 字符→88B / 208 字符→156B),不是等长。
        val before = poTokenState.currentPoToken.size
        val fresh = SabrStreamRegistry.refreshPoTokenSingleFlight(
          entry.poTokenState,
          mint = { entry.refreshPoToken?.invoke() },
        )
        if (fresh != null && fresh.isNotEmpty()) {
          Log.i(
            tag,
            "PO token refreshed on status=2: ${before}B → ${fresh.size}B (websafe base64 已解码)" +
              " → next request uses fresh token",
          )
        } else {
          Log.w(
            tag,
            "PO token refresh null/empty on status=2 (refreshPoToken=${entry.refreshPoToken != null})" +
              " — keep stale ${before}B",
          )
        }
      }
    }
  }

  /**
   * 构造 VideoPlaybackAbrRequest 单流请求体并 POST(对齐 LibreTube fetchStreamData)。
   * 关键差异(修 60s 断崖):bitfield=0(videoFormat 存在时 A+V)/ selectedFormatIds=全部已初始化格式 /
   * bufferedRanges=全部真实 buildBufferedRanges(无 Int.MAX)。
   */
  private suspend fun fetchStreamData(req: SabrSegmentRequest): ByteArray {
    // 2026-09-20(服务端 backoff 不入账,见 recordFetchGap):本次睡眠是**服务端 NEXT_REQUEST_POLICY
    // 要求我们等的**,不是链路供给不足——时长要原样剔除,否则被计成 bytes=0 样本把 est 拽低。
    var serverBackoffSleepMs = 0L
    backoffTime?.let { backoff ->
      val sleep = min(backoff.toLong(), MAX_BACKOFF_SLEEP_MS)
      Log.i(tag, "fetchStreamData: sleeping backoff $backoff ms (capped $sleep) before request")
      delay(sleep)
      serverBackoffSleepMs = sleep
      backoffTime = null
    }

    val now = System.currentTimeMillis()
    entry.diagRequestCount.incrementAndGet() // P11-102d 会话级请求计数(status=3 取证)
    val lastMs = lastRequestMs.get()
    val elapsed = if (lastMs > 0L) (now - lastMs).coerceAtLeast(0L) else 0L

    val playerTimeMs = req.segmentStartTimeMs
    // alpha.83 诊断(forceSessionVideoItag):强制视频轨用**会话选中的** videoFormatId,跳过 selectFormat 按
    // 声明 itag 重选——证伪"某 itag 是 RELOAD 根因"红绯鱼。锁死后若仍 RELOAD → 根因在 ustreamerConfig 来源
    // 不在 itag(见 Piped 后端方案)。仅 Piped 路径默认开 / NewPipe 手动开;否则 videoFormat 由 selectFormat 设。
    if (entry.forceSessionVideoItag && session.videoFormatId.itag != 0) {
      videoFormat = session.videoFormatId
    }
    val selected = initializedFormats.values.map { SabrProto.encodeFormatId(it.id.itag, it.id.lastModified, it.id.xtags) }
    // P11-111(PipePipe-exact bufferedRanges):PipePipe Track.bufferedThrough = next-1——每轨只报到
    // 请求段的前一段,服务端多段推送的预取缓存**不上报**。我们此前把预取缓存全量上报(一次 range
    // 覆盖 30-70s):6s 墙钟在协议里"已缓冲"60s+ → 服务端播放进度语义破坏 → ~60s 服务窗口撞墙
    // (status=2 nag → status=3;GoogleVideo#52 downloader 实锤同一窗口,token 无关)。
    // 请求轨截到 req.segment-1;init(req.segment=0)→ 全空(对齐 FT rn=0-5 无 ranges)。
    // 其他轨维持自身缓存形态(其 loader 前沿=其自身 next)。P11-92 已实证:裁掉后服务端回落
    // playerTimeMs 判、从目标段起推,不会破坏推送游标。
    // P11-130(升档预加载):预取档**不报缓冲**(报"没有" = 请把数据推给我),否则服务端按我们的
    // bufferedRange 判「已给过」就不再推。其余轨维持自身缓存形态(P11-92 已实证裁掉当前轨不破坏推送游标)。
    val prefetchActive = activePrefetchItag()
    val bufferedRanges = initializedFormats.values
      .filter { it.id.itag != prefetchActive }
      .flatMap { fmt ->
        val cap = if (fmt.id.itag == req.formatItag) req.segment.toLong() else Long.MAX_VALUE
        fmt.buildBufferedRanges(cap)
      }
    val audioEnc = audioFormat?.let { SabrProto.encodeFormatId(it.itag, it.lastModified, it.xtags) }
    val videoEnc = videoFormat?.let { SabrProto.encodeFormatId(it.itag, it.lastModified, it.xtags) }
    // P11-130:把预取候选档一起报为 preferred —— 服务端在同一响应里把它的 init(+段)推回来,
    // 缓存进 initializedFormats ⇒ 切档那一刻 getNextSegment 直接命中缓存,不必现拉(真机实测切档
    // 现拉要 2.5–7.2s:新档 init 往返 + 新档首段 2.3–7.2MB)。
    val prefetchEnc = prefetchActive
      ?.takeIf { it != videoFormat?.itag }
      ?.let { pf -> session.videoFormats.firstOrNull { it.itag == pf } }
      ?.let { SabrProto.encodeFormatId(it.itag, it.lastModified, it.xtags) }
    val (activeCtxs, unsentCtxTypes) = session.prepareSabrContexts()
    val streamerContext = StreamerContextInput(
      clientInfo = session.clientInfo,
      // 2026-09-20(A1 身份对齐):材料 clientInfo 原样发在 typed 之后 ⇒ 合并语义下材料赢
      // (clientName=2/MWEB、deviceMake=google、deviceModel=pixel 7、f1=zh_CN 全落地,零猜测)。
      materialClientInfoBytes = session.leadingClientInfoBytes,
      poToken = poTokenState.currentPoToken,
      playbackCookie = session.playbackCookie,
      sabrContexts = activeCtxs,
      unsentSabrContexts = unsentCtxTypes,
    )
    val vHeight = videoFormat?.height ?: 0
    // alpha.16(对齐 LibreTube setAudioTrackId):当前选中音轨 id(audioTrack.id,如 "en.4")。
    // 按 itag 命中 session.audioTracks 里当前音频格式那条;单音轨视频 resolver 折叠成 "default"(audioTrackId
    // 为 null),对齐 LibreTube 发空串("" 而非 "default")——多音轨视频(如 jNl6YkkzKxw 5 音轨)发真实 id。
    val audioTrackId = session.audioTracks.firstOrNull { it.formatId.itag == audioFormat?.itag }?.id
      ?.takeIf { it != "default" } ?: ""
    // P11-104(WEB-SABR 请求体对齐 FreeTube 实测形状):FreeTube HAR(2026-09-15 bundle.har,门控视频
    // KXXZbbnm9t0 全 31 请求逐字节解码)显示 WEB 客户端请求体只含 clientAbrState{16 lastManualResolution/
    // 18 viewportW/19 viewportH/21 sticky/23 bandwidthEstimate(真实值)/28 playerTimeMs/35 playbackRate/
    // 40 bitfield(仅 audio=1,video 省略=默认 0)}+ selected/buffered/ustreamer/preferred + streamerContext
    // {clientInfo **仅 4 字段**(clientName/version/osName/osVersion),poToken,cookie};全部零值/布尔默认
    // **省略不写**。同一 token(90B)全会话不变一路播到 playerTimeMs=138240——服务端不 nag。
    // 我们此前:12 字段 clientInfo + 显式零值(timeSince*/visibility/drc/voiceBoost/audioTrackId="")
    // + bandwidthEstimate=0 → 服务端从首请求起 status=2 nag、playerTimeMs≥60s 升 status=3
    // (r1933:sessAgeMs=7.8s 实锤,判据是播放位置非会话墙钟)。visionOS 路径(能播)不动,webShape
    // 仅对 clientName=1(WEB)生效。
    val webShape = session.clientInfo.clientName == 1
    // P11-167:real est 现在可能返回 **-1**(「证据不足」,见 getRealBitrateEstimate 的说明)——
    // 上报服务端的 `bandwidthEstimate` 不能是负数,按既有口径(无证据即 0)夹一下,不改上传语义。
    val bwEstimateBps = getRealBitrateEstimate().takeIf { it > 0L } ?: 0L
    // 2026-09-20(A2 形状对齐):会话带 harvest 材料 ⇒ **只发动态字段**,静态部分全部留 null 由材料那份
    // 原始 f1 提供(它作为本请求 f1 之前的一份发出,见 SabrRequestInput.leadingClientAbrStateBytes)。
    //
    // 为什么这样切:静态(身份/能力/偏好——viewport/sticky/lastManualRes/bitfield/drc/visibility/
    // flexible/voiceBoost/audioTrackId)以材料为准 = 与浏览器逐字段一致,且不必猜那 ~11 个未建模字段;
    // 动态(进度/计时——playerTimeMs/bandwidthEstimate/各 timeSinceLast*/elapsedWall/playbackRate)必须
    // 我们给当前值:材料那份是**采集那一刻的快照**(它的 playerTimeMs=0),照抄等于向服务端谎报播放进度。
    // 编码器本就跳过 null ⇒ 合并结果 = 材料的静态 + 我们的动态,不需要任何字段级滤波。
    val materialAligned = session.leadingClientAbrStateBytes != null
    // 2026-09-20(位置锚,见 notePlaybackPositionMs):材料会话的 **init 请求**(playerTimeMs 按定义为 0)
    // 改用真实播放位置 —— 否则材料那份 CAS 的 playerTimeMs=0 会让服务端从 seg 0 起推,而续播时要的是
    // 当前位置附近(r2017 真机:`MEDIA_END seq=0..4` 全是片子开头 → 请求段与推送游标对不上 → no seg 死循环)。
    val positionAnchorMs = if (materialAligned && playerTimeMs == 0L) {
      playbackPositionNoteMs.takeIf { it > 0L }
    } else null
    if (positionAnchorMs != null) {
      Log.i(tag, "material session: init 请求用真实播放位置作锚 playerTimeMs=$positionAnchorMs(替代 0)")
    } else if (materialAligned && playerTimeMs == 0L) {
      // 2026-09-20(诊断):锚没生效时**把原因打出来** —— r2018 首次实测只看到「0 次命中」,分不清是
      // 「位置注入口还没被喂(-1)」还是「条件不成立」,白烧一轮。打 note 原值即可一次定性。
      Log.i(tag, "material session: init 位置锚未生效(note=${playbackPositionNoteMs}ms,>0 才用)")
    }
    val clientAbrState = if (materialAligned) {
      ClientAbrStateInput(
        bandwidthEstimate = bwEstimateBps.takeIf { it > 0 } ?: 1_000_000L,
        playerTimeMs = playerTimeMs.takeIf { it != 0L } ?: positionAnchorMs,
        timeSinceLastManualFormatSelectionMs = lastManualFormatSelectionMs?.let { now - it } ?: 0L,
        timeSinceLastSeekMs = lastSeekMs?.let { now - it } ?: 0L,
        elapsedWallTimeMs = elapsed,
        timeSinceLastActionMs = lastActionMs?.let { now - it } ?: 0L,
        playbackRate = req.playbackSpeed,
        // 2026-09-20(A3,修 `sabr.no_audio_selected`):**这两个属于「请求语义」不属于「身份」**,
        // 必须是我们自己的值,不能继承材料那份 —— 上一轮把它们一起交给材料是切错了类。真机 r2014 实证:
        // 材料会话的判决从 `InvalidPoToken(status=3)`(身份/token 无效,硬死)变成
        // `SABR Error type=sabr.no_audio_selected code=3`(语义错误)—— 身份那层已经过了,缺的就是这个。
        // 依据:①本仓库既有约定 `bitfield = if (videoFormat == null) 1 else 0`,**0 = A+V**(材料那份是 3,
        // 语义未知);②错误名字面指向音轨选择,而单音轨视频我们本就该发 `audioTrackId=""`
        // (LibreTube 同款,见上方 alpha.16 注释),材料那份没有 69 是**它的**状态,不是我们要的。
        // 两者都在材料之后发出 ⇒ 合并语义下我们赢(见 SabrRequestInput.leadingClientAbrStateBytes)。
        enabledTrackTypesBitfield = if (videoFormat == null) 1 else 0,
        audioTrackId = audioTrackId,
      )
    } else if (webShape) {
      ClientAbrStateInput(
        lastManualSelectedResolution = max(vHeight, 360),
        clientViewportWidth = 640,
        clientViewportHeight = max(vHeight, 360),
        stickyResolution = max(vHeight, 360),
        // P11-109(字节级 diff):FreeTube 的 bandwidthEstimate **恒有**(init=1400000 默认值)。
        bandwidthEstimate = bwEstimateBps.takeIf { it > 0 } ?: 1_000_000L,
        // P11-109:playerTimeMs=0 时省略(init 请求)——FreeTube rn=0-3 的 f28 全缺席,
        // 我们此前显式写 0(proto2 存在语义下=「会话已开始计时」,疑为 status=2 从首请求起 nag 触发点)。
        playerTimeMs = playerTimeMs.takeIf { it != 0L },
        playbackRate = req.playbackSpeed,
        enabledTrackTypesBitfield = if (videoFormat == null) 1 else null, // video→省略(=0,FreeTube 同)
      )
    } else {
      ClientAbrStateInput(
        timeSinceLastManualFormatSelectionMs = lastManualFormatSelectionMs?.let { now - it } ?: 0L,
        lastManualSelectedResolution = max(vHeight, 360),
        clientViewportWidth = 640,
        clientViewportHeight = max(vHeight, 360),
        stickyResolution = max(vHeight, 360),
        clientViewportIsFlexible = false,
        bandwidthEstimate = 0L,
        // 请求体 playerTimeMs = 段起点(对齐 LibreTube setPlayerTimeMs(segmentStartTimeMs))
        playerTimeMs = playerTimeMs,
        timeSinceLastSeekMs = lastSeekMs?.let { now - it } ?: 0L,
        visibility = 1,
        playbackRate = req.playbackSpeed,
        elapsedWallTimeMs = elapsed,
        timeSinceLastActionMs = lastActionMs?.let { now - it } ?: 0L,
        // alpha.64 单流:bitfield=0(A+V,videoFormat 存在时)。alpha.63 双流用 2(VIDEO_ONLY)/1(AUDIO_ONLY)
        // 是 60s 断崖根因——服务端双流模型在 ~60s 边界软拒。单流 bitfield=0 对齐 LibreTube。
        enabledTrackTypesBitfield = if (videoFormat == null) 1 else 0,
        drcEnabled = false,
        enableVoiceBoost = false,
        audioTrackId = audioTrackId,
      )
    }
    val clientInfo = if (webShape) {
      // P11-104:FreeTube WEB clientInfo 仅 4 字段(HAR 实测)——多余设备指纹字段
      //(deviceMake/Model、acceptLanguage/Region、screen、formFactor、utcOffset、timeZone)全部去掉。
      session.clientInfo.copy(
        deviceMake = null, deviceModel = null, acceptLanguage = null, acceptRegion = null,
        screenWidthPoints = null, screenHeightPoints = null, screenPixelDensity = null,
        clientFormFactor = null, androidSdkVersion = null, screenDensityFloat = null,
        utcOffsetMinutes = null, timeZone = null,
      )
    } else {
      session.clientInfo
    }
    val input = SabrRequestInput(
      clientAbrState = clientAbrState,
      // 2026-09-20(A2 形状对齐):材料会话带原始 client_abr_state ⇒ 作为本请求 f1 之前的一份发出,
      // 我们没建模的字段(浏览器 MWEB 那 ~11 个)原样保留,标量由下面这份覆盖。非材料会话为 null,零影响。
      leadingClientAbrStateBytes = session.leadingClientAbrStateBytes,
      selectedFormatIds = selected,
      bufferedRanges = bufferedRanges,
      // P11-109(字节级取证):FreeTube 31 个请求**从无顶层 playerTimeMs(field4)**——只放
      // clientAbrState.f28。我们此前每请求都显式发 f4(alpha.28 visionOS 服务端模型的结论,
      // visionOS 路径保留);webShape 对齐 FreeTube 整体不发。
      playerTimeMs = if (webShape) null else playerTimeMs,
      videoPlaybackUstreamerConfig = session.ustreamerConfig,
      // 2026-09-20(A4a,修 `sabr.no_audio_selected`):**材料会话不发 preferred\***。
      //
      // 依据(真机 r2015 `logs_live_20260920_172632.log` + 字节对比):浏览器那条**被服务端 200 接受**的
      // SABR 请求,body 里**只有三组字段** `{f1 clientAbrState, f5 ustreamerConfig, f19 streamerContext}` ——
      // 一个 `preferred*` 都不带;而我们比它多出 f16/f17,且恰好带着「我要 itag140」。而那份材料的
      // 会话本是围绕 **itag251(Opus)** 建的(材料解码 `audio=itag251`)⇒ 我们在一个 251 的会话里
      // 报「偏好 140」,服务端回的字面就是 `sabr.no_audio_selected`。
      //
      // 语义上也站得住:`preferred*` 是「额外点名要哪一档」的**提示**,而 SABR 材料会话本来就是
      // **服务端主导**(服务端按 ustreamerConfig + CAS 决定推什么);`selectedFormatIds` / `bufferedRanges`
      // 才是我们播放器的真实状态,仍照常发。顺带:预取(P11-130)在材料会话里也一并停用 —— 它本就
      // 已判定无效且会抢响应预算,停用只赚不亏。
      preferredAudioFormatIds = if (materialAligned) emptyList() else listOfNotNull(audioEnc),
      preferredVideoFormatIds = if (materialAligned) emptyList() else listOfNotNull(videoEnc, prefetchEnc),
      preferredSubtitleFormatIds = emptyList(),
      streamerContext = streamerContext.copy(clientInfo = clientInfo),
    )
    val body = SabrProto.encodeVideoPlaybackAbrRequest(input)
    val rn = requestNumber.getAndIncrement()
    lastRequestMs.set(now)
    val url = "${session.sabrUrl}&rn=$rn"
    Log.i(tag, "fetch rn=$rn itag=${req.formatItag} seg=${req.segment} playerTimeMs=$playerTimeMs shape=${if (webShape) "ft" else "libre"} bitfield=${clientAbrState.enabledTrackTypesBitfield ?: 0} selectedFmts=${selected.size} bufferedRanges=${bufferedRanges.size} pot=${poTokenState.currentPoToken.size}B potAgeMs=${System.currentTimeMillis() - poTokenState.currentPoTokenAtMs} cookie=${session.playbackCookie != null && session.playbackCookie!!.isNotEmpty()} contexts=${activeCtxs.size}/${unsentCtxTypes.size} bw=${bwEstimateBps}bps body=${body.size}B")
    // P11-108(字节级取证):WEB 会话前 2 个请求 dump body hex + token hex——与 FreeTube HAR
    // (tmp/bundle.har,已解码)逐字节对比用。协议层已全对齐(P11-104..107)仍 nag,剩最后
    // 检查手段:本地 diff 真实字节。
    //
    // 2026-09-20(修取证工具本身):**原来"单行 ~3.3KB 可容纳"的判断是错的** —— 真机实测 bodyHex
    // 在 3851 字符处被 logcat 截成半截(奇数长度),而丢掉的正是 body **最后**的 `streamerContext`
    // (clientInfo / poToken / playbackCookie = 身份段)——恰恰是「能通 / 必死」最可能的差异所在,
    // 导致逐字段对比结构上永远看不到想问的那一段。改**分片**:~1800 字符/行(前缀 + 1800 hex =
    // ~1.9KB,留足 logcat 单行上限余量),首片带 part=i/n 与 potHex 便于拼接。旧 grep
    // (`WEBREQDUMP rn=N potHex=… bodyHex=…`)仍命中首片,不破坏既有用法。
    if (webShape && rn <= 1) {
      val hex = body.joinToString("") { "%02x".format(it) }
      val potHex = poTokenState.currentPoToken.take(160).joinToString("") { "%02x".format(it) }
      val chunk = 1800
      val parts = (hex.length + chunk - 1) / chunk
      for (i in 0 until parts) {
        val seg = hex.substring(i * chunk, minOf((i + 1) * chunk, hex.length))
        Log.i(
          tag,
          if (i == 0) "WEBREQDUMP rn=$rn part=1/$parts potHex=$potHex bodyHex=$seg"
          else "WEBREQDUMP rn=$rn part=${i + 1}/$parts bodyHex=$seg",
        )
      }
    }

    val request = Request.Builder()
      .url(url)
      .post(body.toRequestBody("application/x-protobuf".toMediaType()))
      .header("accept-encoding", "identity")
      .header("accept", "application/vnd.yt-ump")
      .header("User-Agent", session.userAgent)
      // alpha.79:cookie/visitor 可空——空串=不带(对齐 LibreTube 无 HTTP cookie,靠 protobuf)。非空才带。
      .apply {
        if (session.cookieHeader.isNotBlank()) header("Cookie", session.cookieHeader)
        if (session.visitorData.isNotBlank()) header("X-Goog-Visitor-Id", session.visitorData)
      }
      .header("Origin", "https://www.youtube.com")
      .header("Referer", "https://www.youtube.com/")
      .build()
    val t0 = SystemClock.elapsedRealtime()
    // alpha.9Z:发请求前快照 gap 起点(上次 fetch 结束)与当时的缓冲水位,供 gap 计时(见 recordFetchGap)。
    // lastFetchEndMs/t0Wall 均为墙钟(与 lastSeekMs/lastManualFormatSelectionMs 同源可比);gap 时长也用
    // 墙钟差值,跳变时 fetchStartMs<=prevFetchEndMs 守卫自然跳过该样本,不产生错误样本。
    val t0Wall = System.currentTimeMillis()
    val prevFetchEndMs = lastFetchEndMs
    val prevSeekMs = lastSeekMs
    val prevManualMs = lastManualFormatSelectionMs
    val runwayMs = bufferedAheadMsAtLastFetch
    return try {
      // P11-134:SABR POST **整调用上限**。playback client 是 `callTimeout(0)`(为不切掉长分片),
      // 只有 per-read 15s ⇒ 服务端「慢滴」(每次 read 都在 15s 内挤一点)时**永不超时**。
      // 真机 2026-09-20 13:32 就是这样挂死的:`fetch rn=2` 发出后既无 REAL 也无 exception,缓冲耗尽、
      // 播放冻在 34.5s 达 54 秒。给整调用一个上限后,挂死的请求会被切断 → IOException →
      // 已有的 onPlayerErrorChanged error-retry 链接管(那条链本身是好的,缺的只是「谁来触发它」)。
      //
      // ── P11-153(③,2026-09-20 r2048 TV 真机):**上限按档高自适应,低档不再等满 40 秒** ────────
      // 那场 23:15:59 之后的 33 秒里**一个新请求都没发**(慢滴占着单并发许可),而整调用上限是 40s
      // ⇒ 用户在第 33 秒手动退出 —— 也就是说**冻结时长 ≈ 40s 上限本身**。但 40s 是 P11-134 为
      // **4K 大段**(实测 28.2s)留的余量,不能一刀切小。故按**本次请求的档高**分档:
      //   ≥1440p:保留 [SabrCallTimeoutMs](40s,大段合法慢)
      //   ≤1080p:收紧到 [SabrCallTimeoutMsLow](18s)——低档段小,慢滴到这个量级已无供给价值,
      //          早切早让 error-retry 链接管(4K 大段不受影响)。
      val reqHeight = session.videoFormats.firstOrNull { it.itag == req.formatItag }?.height ?: 0
      // P11-173:饥饿快切 —— 缓冲已知见底(< SabrStarvingBufferMs)时把整调用上限收到 8s/12s。
      // 这条通道存在的唯一理由:让请求在 **8s stall 看门狗把整场重载之前**失败,把「零字节/慢滴」
      // 变成一笔失败样本喂带宽计(recordRealBandwidthFailure)⇒ ABR 有机会先降档自救。
      val starving = bufferedAheadNoteMs in 0..SabrStarvingBufferMs
      val callCapMs = when {
        starving && reqHeight >= 1440 -> SabrStarvingCallTimeoutMsHigh
        starving -> SabrStarvingCallTimeoutMs
        reqHeight >= 1440 -> SabrCallTimeoutMs
        else -> SabrCallTimeoutMsLow
      }
      if (starving) {
        Log.i(
          tag,
          "fetch rn=$rn starving-fast-fail: bufAhead=${bufferedAheadNoteMs}ms ≤ " +
            "${SabrStarvingBufferMs}ms → callCap=${callCapMs}ms (base=" +
            "${if (reqHeight >= 1440) SabrCallTimeoutMs else SabrCallTimeoutMsLow}ms, P11-173)",
        )
      }
      // ── P11-160(r2054 续播多场实测):**readTimeout 必须一起抬,否则上面那个上限有一半是纸面的** ──
      // 共享的 YouTube client(`BiliHttpClientFactory.baseBuilder`)带的是 `readTimeout(15s)` /
      // `writeTimeout(15s)` / `connectTimeout(15s)` —— 那是给**小 API 调用**定的;而本方法此前只覆盖了
      // `callTimeout`,**没覆盖 readTimeout** ⇒ 服务端首包一旦超过 15s,读超时先触发,`callCapMs` 根本用不上。
      // 真机 `logs_live_20260921_094658.log`(续播 `iTY92w_uPys @415s`):
      //   `fetch rn=0 exception: timeout (fail=16533ms bwNow=0K)` + `rn=1 … fail=16622ms`(同一会话两笔都零字节)
      //   ⇒ 会话被 evict → 28s 后 stall 看门狗重试 → 新会话成功(该场 rn=0 只用 4.5s)
      // 而同日另外两场正常会话首包是 `rn=0 4569ms / 7204ms`、`rn=1 3565ms / 4457ms` ⇒ 16.5s **不是**常态,
      // 是服务端偶发的慢启动(与既有记录「首包偶发 16-20s」一致);正常情况本来就不该被杀,慢启动更不该。
      // 修法:用**静默超时 8s** 的 client 克隆(见 [sabrHttpClient]),`callTimeout` 仍按档高自适应 ——
      // 零字节停顿 8 秒即切、立刻重试(真机实测重试 ~4s 即成),慢滴下载不受影响。
      val call = sabrHttpClient.newCall(request)
      call.timeout().timeout(callCapMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      val resp = call.execute().use { response ->
        val code = response.code
        if (code != 200) {
          val hdrs = response.headers.joinToString("; ") { "${it.first}=${it.second.take(80)}" }
          Log.w(tag, "fetch rn=$rn HTTP $code headers=[$hdrs] body=${response.body?.string()?.take(200)}")
          throw IOException("SABR HTTP $code")
        }
        response.body?.bytes() ?: throw IOException("SABR empty body")
      }
      val elapsed = SystemClock.elapsedRealtime() - t0
      // alpha.9X(带宽驱动,滑动窗口累计):成功段只计「实际下载耗时」(不再混入缓冲等待 gap,那个是主动
      // 节奏非带宽不足),吞吐 = 窗口累计量/累计耗时。带宽充足时贴近真实下载速率,断流时靠失败段计时下探。
      val mbps = if (elapsed > 0) resp.size.toLong() * 8 / (elapsed * 1000L) else -1L
      recordRealBandwidthSample(resp.size.toLong(), elapsed)
      recordFetchGap(prevFetchEndMs, prevSeekMs, prevManualMs, runwayMs, t0Wall, serverBackoffSleepMs)
      lastFetchEndMs = System.currentTimeMillis()
      bufferedAheadMsAtLastFetch = bufferedAheadNoteMs
      Log.i(tag, "fetch rn=$rn REAL ${resp.size}B ${elapsed}ms → ${mbps}Mbps est=${fmtEstForLog(getRealBitrateEstimate())}")
      resp
    } catch (e: SabrTerminalException) {
      // 致命错误(RELOAD/InvalidPoToken/重试耗尽)不算普通网络降级,不喂带宽样本
      throw e
    } catch (e: Exception) {
      // 网络失败/超时:下载量=0、耗时计满 → 窗口带宽下探,让 ABR 有依据降档自救
      val failMs = SystemClock.elapsedRealtime() - t0
      recordRealBandwidthFailure(failMs)
      recordFetchGap(prevFetchEndMs, prevSeekMs, prevManualMs, runwayMs, t0Wall, serverBackoffSleepMs)
      lastFetchEndMs = System.currentTimeMillis()
      bufferedAheadMsAtLastFetch = bufferedAheadNoteMs
      Log.w(tag, "fetch rn=$rn exception: ${e.message} (fail=${failMs}ms bwNow=${fmtEstForLog(getRealBitrateEstimate())})")
      throw e
    }
  }

  /** 处理一个 UMP part(对齐 LibreTube processPart)。[discard] 只用于 2026-09-20 的无效流量取证。 */
  private fun processPart(type: Int, payload: ByteArray, discard: DiscardTracker) {
    when (type) {
      PART_MEDIA_HEADER -> {
        val mh = SabrProto.decodeMediaHeader(payload)
        if (mh == null) {
          Log.w(tag, "MEDIA_HEADER decode failed payloadLen=${payload.size}")
          return
        }
        if (mh.headerId == 0 && mh.itag == 0) {
          Log.w(tag, "MEDIA_HEADER empty (headerId=0 itag=0) payloadLen=${payload.size}")
          return
        }
        // alpha.71 广告防御层:跳过非白名单 itag 的 MEDIA_HEADER(广告段)。不入 partialSegments
        // → 其后续 MEDIA/MEDIA_END 按 headerId 找不到段自动丢弃。
        // 2026-08-31:白名单=当前选中格式+在途段请求 itag——选轨切换后旧在途请求的响应不再被误丢
        // (21:01 真机:videoFormat 翻 302 后 298 在途段响应全被丢,六连重试独占 fetcher 8.5s)。
        val hdrWhitelist = setOfNotNull(audioFormat?.itag, videoFormat?.itag) + pendingRequestItags
        if (hdrWhitelist.isNotEmpty() && mh.itag !in hdrWhitelist) {
          // 2026-09-20:记下 headerId→itag——PART_MEDIA 只有 headerId,靠这条才认得出被丢弃的字节是谁的。
          discard.headerItags[mh.headerId] = mh.itag
          Log.w(tag, "skip ad/unrequested MEDIA_HEADER itag=${mh.itag} headerId=${mh.headerId} (whitelist=$hdrWhitelist)")
          return
        }
        val duration = mh.durationMs
        if (partialSegments.containsKey(mh.headerId)) {
          Log.w(tag, "MEDIA_HEADER duplicate headerId=${mh.headerId} (overwrite)")
        }
        Log.i(tag, "MEDIA_HEADER headerId=${mh.headerId} itag=${mh.itag} seq=${mh.sequenceNumber} isInit=${mh.isInitSeg} startMs=${mh.startMs} dur=${duration}ms contentLen=${mh.contentLength}")
        partialSegments[mh.headerId] = SabrSegment(
          header = mh,
          sequenceNumber = mh.sequenceNumber.toLong(),
          data = mutableListOf(),
          duration = duration,
        )
      }
      PART_MEDIA -> {
        // payload = [headerId varint][media bytes]。headerId 是 UMP 自定义 varint(首字节<128 时单字节)。
        val (headerId, hdrLen) = readUmpVarint(payload, 0) ?: return
        // 2026-09-20 取证:partialSegments 里没有 = 其 MEDIA_HEADER 未登记。**只统计能归因到
        // 白名单跳过的那部分**(discard.headerItags 有记录才计),否则无从归因的字节(如 MEDIA_END
        // 之后到达的重复块)混进来会把数抬高——这个数是拿去判断口径要不要改的,必须可信。
        val seg = partialSegments[headerId] ?: run {
          discard.headerItags[headerId]?.let {
            discard.bytes += payload.size.toLong()
            discard.itags.add(it)
          }
          return
        }
        seg.data.add(if (hdrLen == 0) payload else payload.copyOfRange(hdrLen, payload.size))
      }
      PART_MEDIA_END -> {
        val (headerId, _) = readUmpVarint(payload, 0) ?: return
        val seg = partialSegments.remove(headerId) ?: return
        val fmt = initializedFormats[seg.header.itag]
        if (fmt == null) {
          Log.w(tag, "MEDIA_END headerId=$headerId itag=${seg.header.itag} no InitializedFormat (dropped)")
          return
        }
        Log.i(tag, "MEDIA_END headerId=$headerId itag=${seg.header.itag} seq=${seg.sequenceNumber} chunks=${seg.data.size} bytes=${seg.length()}")
        fmt.downloadedSegments[seg.sequenceNumber] = seg
        if (seg.header.isInitSeg) fmt.initSegment = seg
        // 2026-08-30 实测消耗码率挂账:只计非 init 段、只按单调 seq 增量(重传旧段不重复计入)。
        if (!seg.header.isInitSeg) {
          synchronized(measuredLock) {
            val st = measuredTracks.getOrPut(seg.header.itag) { MeasuredTrack() }
            if (seg.sequenceNumber > st.maxCountedSeq) {
              st.maxCountedSeq = seg.sequenceNumber
              st.bytes += seg.length()
              st.segs += 1
            }
          }
          // P11-85 诊断(每 itag 首个 media 段一次):扫 fMP4 段字节里的 tfdt box,读 baseMediaDecodeTime
          // 原始值——判样本时间戳域(绝对 ≈ seq×段时长×timescale,还是请求相对/0 基)。续播黑屏案:
          // chunk 数据就位、解码器就位、渲染器 11s 零读——唯一未证伪解释是样本 PTS 不在续播点域。
          synchronized(tfdtProbeLogged) {
            if (!tfdtProbeLogged.contains(seg.header.itag)) {
              tfdtProbeLogged.add(seg.header.itag)
              val first = seg.data.firstOrNull()
              if (first != null) {
                val tfdt = probeFmp4Tfdt(first)
                Log.i(tag, "MEDIA tfdt itag=${seg.header.itag} seq=${seg.sequenceNumber} startMs=${seg.header.startMs} durMs=${seg.duration} $tfdt")
              }
            }
          }
        }
      }
      PART_NEXT_REQUEST_POLICY -> {
        val policy = SabrProto.decodeNextRequestPolicy(payload)
        backoffTime = policy?.backoffTimeMs
        val cookieBytes = policy?.playbackCookieBytes
        val hasCookie = cookieBytes?.isNotEmpty() == true
        if (hasCookie) session.playbackCookie = cookieBytes
        Log.i(tag, "NEXT_REQUEST_POLICY backoff=${policy?.backoffTimeMs}ms cookie=$hasCookie maxTimeSinceReq=${policy?.maxTimeSinceLastRequestMs}")
      }
      PART_FORMAT_INITIALIZATION_METADATA -> {
        val fi = SabrProto.decodeFormatInitializationMetadata(payload)
        if (fi == null || fi.itag == 0) {
          Log.w(tag, "FORMAT_INITIALIZATION_METADATA decode failed/empty payloadLen=${payload.size}")
          return
        }
        if (initializedFormats.containsKey(fi.itag)) {
          Log.i(tag, "FORMAT_INITIALIZATION_METADATA itag=${fi.itag} already initialized (skip)")
          return
        }
        // alpha.71 广告防御层:只接受本会话请求的音视频 itag,跳过服务端注入的广告/未请求格式。
        // visionOS 客户端(path C)通常不注入广告,但作安全网:广告格式不入表 → 其 MEDIA 段
        // 在 partialSegments 找不到 headerId → MEDIA/MEDIA_END 自动丢弃 → 不喂解码器。
        // 白名单空(audioFormat/videoFormat 均未设)时不过滤,避免误杀。
        // 2026-08-31:同 MEDIA_HEADER 白名单,并入在途段请求 itag(见 pendingRequestItags)。
        val whitelistedItags = setOfNotNull(audioFormat?.itag, videoFormat?.itag) + pendingRequestItags
        if (whitelistedItags.isNotEmpty() && fi.itag !in whitelistedItags) {
          // C1:即便是被跳过的,也记进「服务端推来的集合」—— 被跳过恰恰说明服务端愿意给、而我们没选它,
          // 这正是选档该考虑的候选(见 serverPushedVideoItags)。
          serverPushedVideoItags.add(fi.itag)
          SabrStreamRegistry.noteServerServedItag(entry.videoId, fi.itag)
          Log.w(tag, "skip ad/unrequested FORMAT_INIT itag=${fi.itag} (whitelist=$whitelistedItags)")
          return
        }
        serverPushedVideoItags.add(fi.itag)
        SabrStreamRegistry.noteServerServedItag(entry.videoId, fi.itag)
        Log.i(tag, "FORMAT_INITIALIZATION_METADATA itag=${fi.itag} endSegNum=${fi.endSegmentNumber} duration=${fi.endTimeMs}ms")
        initializedFormats[fi.itag] = InitializedFormat(
          id = FormatId(fi.itag, fi.lastModified, fi.xtags, 0),
          endSegmentNumber = fi.endSegmentNumber,
          duration = fi.endTimeMs,
        )
      }
      PART_SABR_REDIRECT -> {
        val url = SabrProto.decodeSabrRedirect(payload)
        redirectUrl = url
        if (url != null) session.applyRedirect(url)
        Log.i(tag, "SABR_REDIRECT -> ${url?.take(80)}")
      }
      PART_SABR_CONTEXT_UPDATE -> {
        val u = SabrProto.decodeSabrContextUpdate(payload)
        if (u != null && u.type != 0 && u.value.isNotEmpty()) {
          val keepExisting = u.writePolicy == 2 && session.sabrContexts.containsKey(u.type)
          if (!keepExisting) {
            session.sabrContexts[u.type] = u.value
            if (u.sendByDefault) session.activeSabrContextTypes.add(u.type)
          }
          Log.i(tag, "SABR_CONTEXT_UPDATE type=${u.type} valLen=${u.value.size} sendByDefault=${u.sendByDefault} writePolicy=${u.writePolicy} ctxs=${session.sabrContexts.size} active=${session.activeSabrContextTypes.size}")
        }
      }
      PART_SABR_CONTEXT_SENDING_POLICY -> {
        val p = SabrProto.decodeSabrContextSendingPolicy(payload)
        if (p != null) {
          p.start.forEach { session.activeSabrContextTypes.add(it) }
          p.stop.forEach { session.activeSabrContextTypes.remove(it) }
          p.discard.forEach { session.sabrContexts.remove(it) }
          Log.i(tag, "SABR_CONTEXT_SENDING_POLICY start=${p.start} stop=${p.stop} discard=${p.discard}")
        }
      }
      PART_STREAM_PROTECTION_STATUS -> {
        val status = SabrProto.decodeStreamProtectionStatus(payload)
        Log.w(tag, "STREAM_PROTECTION_STATUS status=$status")
        // P11-155:首笔单独一行(判据行)——`status` 由 2 变 1 即「铸造上下文」实验成立。
        // P11-154 曾把这两行埋进 SabrClient.processUmpStream(Data Source 路径),真机上一行都没出;
        // 这里是**活跃的媒体路径**。计数与标记都在 Entry,与解析器无关。
        if (entry.diagFirstStatusLogged.compareAndSet(false, true)) {
          val pot = poTokenState.currentPoToken
          Log.w(
            tag,
            "首笔 STREAM_PROTECTION_STATUS status=$status (pot=${pot.size}B" +
              " first=0x%02x)".format(pot.firstOrNull()?.toInt()?.and(0xFF) ?: 0),
          )
        }
        if (status == 3) {
          entry.diagStatus3Count.incrementAndGet()
          invalidPo = true
        }
        // alpha.67(对齐 LibreTube processPart status==2 `poToken = generatePoToken()` 同步):
        // status=2(Attestation pending)= 服务端预警 → media() 的 readParts 后同步重铸 PO token
        // (阻塞 loader 线程 ~1s,看门狗已取消无 8s cancel 风险)。下个请求一定带新 token → status=3
        // 不再出现。异步(alpha.66 maybeRefreshPoToken)有竞态:刷新晚一拍撞 status=3 → 全量重载。
        else if (status == 2) {
          needsPoTokenRefresh = true
          entry.diagStatus2Count.incrementAndGet() // P11-102d
        }
      }
      PART_RELOAD_PLAYER_RESPONSE -> {
        // Phase 1(diag):结构化解析。reloadToken = ReloadPlaybackParams.token(整串 base64),
        // 是服务端下发的 reload 凭证,Phase 2 需原样回传进新 /player 的 playbackContext.reloadPlaybackContext。
        // 不改播放行为。真机看 reloadToken 是否稳定/含 videoId,为 Phase 2 定回传逻辑。
        val info = SabrProto.decodeReloadPlayer(payload)
        // alpha.88:补通用逐字段扫描 + hexDump——36B 短变体经 ProtoReader 越界修复后结构化解析可能仍取不到
        // token/videoId(若结构非 f1→f1),通用扫描 dump 每个顶层 field 的 number/wireType/值,一次真机即可
        // 看清 36B 到底是什么结构(是否带 token / token 在哪个 field)。
        val scan = SabrProto.decodeReloadPlayerResponse(payload)
        reloadPlayerDump = "videoId=${info.videoId} reloadTokenLen=${info.reloadToken?.length} " +
          "reloadTokenDecodedHex=${info.reloadTokenDecodedHex} innerToken=\"${info.innerToken}\" " +
          "innerTokenDecodedHex=${info.innerTokenDecodedHex} field7Hex=${info.field7Hex} " +
          "potSent=${poTokenState.currentPoToken.size}B fields={${info.fieldsSummary}} " +
          "scan={${scan.fieldsSummary}} hex=${info.hexDump}"
        Log.w(tag, "RELOAD_PLAYER_RESPONSE $reloadPlayerDump")
        // Phase 2(alpha.87 RELOAD 重载闭环):把 reloadToken 停车到进程级 registry(独立于 sessions,evict 不清),
        // 供 resolve() 下次重进 consumeReloadTokenSlot 取走 → WEB attested /player 重打。
        // alpha.88:改单槽存(视频无关)——36B 短变体可能无内层 videoId,旧 videoId-keyed 存不进。
        // 只要 reloadToken 非空就存(videoId 可解出时顺带计数)。
        val rt = info.reloadToken
        if (!rt.isNullOrBlank()) {
          // alpha.9X:计数用会话自己的 videoId(恒可得),而非 payload 解码的 info.videoId——36B 短变体
          // f4=videoId 解出 null 时旧逻辑计数恒 0 → 守卫永不触发 → 潜在无限 RELOAD 循环。
          SabrStreamRegistry.storeReloadTokenSlot(entry.videoId ?: info.videoId, rt)
        } else {
          Log.w(tag, "RELOAD_PLAYER_RESPONSE: reloadToken 空(payloadLen=${payload.size}) → 闭环无 token 可回传,scan+hex 见上")
        }
        // alpha.9X(兜底提前):首次 RELOAD 即抛终端错,不再读完全部 RELOAD part。RELOAD 语义 = streams
        // expired/new config(或 attestation 未过)——对 attestation 视频(4K/HD)SABR 必 RELOAD,继续读
        // 只会白耗 ~8 part;对齐 LibreTube「RELOAD 直接失败不循环」+ 我们的死循环守卫。抛 SabrTerminalException
        // → SabrDataSource.open evict → 播放器 error-retry → 重进 resolve 看到 reloadCount>0 → 直接落 DASH/HLS
        // 兜底(自合成 DASH 实测能出 4K)。reloadToken 已先停车,reload-closure 若启用仍可回传。
        throw SabrTerminalException("RELOAD_PLAYER_RESPONSE: $reloadPlayerDump")
      }
      PART_SABR_ERROR -> {
        val err = SabrProto.decodeSabrError(payload)
        fatalError = "SABR Error type=${err?.type} code=${err?.code}"
        Log.w(tag, "SABR_ERROR type=${err?.type} code=${err?.code}")
      }
      else -> {
        entry.diagUnhandledParts.merge(type, 1, Int::plus) // P11-102d 未识别 part 计数
        Log.i(tag, "part type=$type payloadLen=${payload.size} (unhandled)")
      }
    }
  }

  /**
   * P11-85 诊断:在一段 fMP4 段字节(单个 moof+mdat)里找 tfdt box 并读 baseMediaDecodeTime。
   * 结构:size(4) 'tfdt'(4) version(1) flags(3) baseMediaDecodeTime(ver0=4B/ver1=8B)。
   * 找到 't'f'd't' 四字节的首次出现即取值(段内第一个 tfdt;不做严格 box 树遍历,诊断够用)。
   * @return 人读字符串:版本 + 原始 tick 值(HEX),找不到返回提示。
   */
  private fun probeFmp4Tfdt(data: ByteArray): String {
    val limit = minOf(data.size - 12, 1 shl 20)
    var i = 0
    while (i < limit) {
      if (data[i] == 't'.code.toByte() && data[i + 1] == 'f'.code.toByte() &&
        data[i + 2] == 'd'.code.toByte() && data[i + 3] == 't'.code.toByte()
      ) {
        val version = data[i + 4].toInt() and 0xFF
        val width = if (version == 1) 8 else 4
        if (i + 8 + width <= data.size) {
          var value = 0L
          for (k in 0 until width) {
            value = (value shl 8) or (data[i + 8 + k].toLong() and 0xFF)
          }
          return "tfdtVer=$version baseMediaDecodeTime=$value ticks (hex=${value.toString(16)})"
        }
      }
      i++
    }
    return "tfdt not found in first ${minOf(data.size, 1 shl 20)}B"
  }

  /**
   * 读 UMP 自定义 varint(YouTube 格式,首字节高位判字节数,LE)。MEDIA/MEDIA_END part 的 headerId 用此编码。
   * 复用 [UmpReader] 同款规则;这里从 ByteArray offset 读。@return (value, byteLength) 或 null(字节不足)。
   */
  private fun readUmpVarint(data: ByteArray, offset: Int): Pair<Int, Int>? {
    if (offset >= data.size) return null
    val b0 = data[offset].toInt() and 0xFF
    val byteLength = when {
      b0 < 0x80 -> 1
      b0 < 0xC0 -> 2
      b0 < 0xE0 -> 3
      b0 < 0xF0 -> 4
      else -> 5
    }
    if (offset + byteLength > data.size) return null
    val value = when (byteLength) {
      1 -> b0
      2 -> (b0 and 0x3F) + 64 * (data[offset + 1].toInt() and 0xFF)
      3 -> (b0 and 0x1F) + 32 * (
        (data[offset + 1].toInt() and 0xFF) + 256 * (data[offset + 2].toInt() and 0xFF)
      )
      4 -> (b0 and 0x0F) + 16 * (
        (data[offset + 1].toInt() and 0xFF) + 256 * ((data[offset + 2].toInt() and 0xFF) + 256 * (data[offset + 3].toInt() and 0xFF))
      )
      else -> (data[offset + 1].toInt() and 0xFF) + 256 * (
        (data[offset + 2].toInt() and 0xFF) + 256 * ((data[offset + 3].toInt() and 0xFF) + 256 * (data[offset + 4].toInt() and 0xFF))
      )
    }
    return value to byteLength
  }

  private companion object {
    /**
     * P11-144(2026-09-20):status=2 时是否重铸 PO token。
     *
     * **false = 不刷新、keep stale**(当前值,取证实验)。依据见 [media] 里的长注释:同一枚刷新 token
     * 在 r2026(GRbG)被接受、在 r2023/r2028(LSnM)被判 `InvalidPoToken status=3`;而会话开头那枚
     * 88B token 一直是被接受的。停掉刷新即可用「下一笔请求的 status」把「刷新才是凶手」与
     * 「status=2 是硬处决」分开。置 true 可回退到 alpha.13 的同步刷新(代码原样保留)。
     */
    const val REFRESH_PO_TOKEN_ON_STATUS2 = false

    /** P11-130:预取窗口(ms)——上报后这段时间内每个请求都带候选档;过期自动失效,避免长期白吃带宽。 */
    const val PREFETCH_WINDOW_MS = 30_000L
    /**
     * P11-134:SABR POST **整调用上限**(ms)。实测单笔 2.1~28.2s(2160p 大段 28.2s),40s 给足余量,
     * 只切真挂死的慢滴请求。见调用点注释。
     */
    const val SabrCallTimeoutMs = 40_000L

    /**
     * P11-153(③):**≤1080p 档的整调用上限**(ms)。低档段小,慢滴到这个量级已无供给价值 ——
     * 早切早让 error-retry 链接管;≥1440p 仍用 [SabrCallTimeoutMs](4K 大段实测可达 28.2s)。
     * 依据(r2048 TV 真机):切轨后 33 秒无新请求(慢滴占着单并发许可),而 40s 上限还没到 ⇒ 用户先退出。
     */
    const val SabrCallTimeoutMsLow = 18_000L

    /**
     * P11-166:**静默超时**(ms)—— OkHttp `readTimeout` 语义 = 多久没有新字节(每字节重置)。
     *
     * 依据(真机 `logs_live_20260921_171235.log`):**成功的首笔总耗时 3.5~7.2s**,**零字节停顿的
     * 都 ≥16.5s**,而**停顿后立刻重试只需 4.3s 即成** ⇒ 8s 落在分布空档里,把"等 18 秒"压成"等 8 秒
     * 再重试"。慢滴(字节持续到来)不受影响。
     */
    const val SabrSilenceTimeoutMs = 8_000L

    /**
     * P11-173:判定「视频缓冲已见底(饥饿)」的水位阈值(ms)—— [noteBufferedAheadMs] 最近一次喂进来的值
     * 低于它就进入饥饿快切口径。
     */
    const val SabrStarvingBufferMs = 10_000L

    /**
     * P11-173:**饥饿快切的整调用上限**(ms,≤1080p / ≥1440p 两档)。
     *
     * 依据(真机 `logs_live_20260922_224548.log`):场1 `rn=3`(1080p)发出后零字节挂死,`callCapMs=18s`
     * 还没到、8s 静默超时也没触发(服务端在挤小包),而视频缓冲已被吃空 ⇒ **22:30:20 8s stall 看门狗
     * 把整场重载了**(重 harvest ≈16s,且第三次重载连 harvest 都没采到)。场2 同签名(`rn=8 20MB/22s`、
     * `rn=9 11.9MB/23.1s`)。
     *
     * 口径:只在**已知缓冲见底**时收短上限 —— 此时「等满 18s/40s」几乎没有收益(播放器本来就撑不过),
     * 而早切能让失败样本立刻喂带宽计 → ABR 在缓冲耗尽前降档(既有 `recordRealBandwidthFailure` 语义),
     * 避免整场重载。非饥饿时口径完全不变(4K 大段 28s 合法慢不受影响)。
     */
    const val SabrStarvingCallTimeoutMs = 8_000L

    /** P11-173:饥饿快切的 ≥1440p 上限 —— 大段本身更慢,给到 12s(仍早于 18s/40s 原口径)。 */
    const val SabrStarvingCallTimeoutMsHigh = 12_000L

    /** transient 重试上限(耗尽→SabrTerminalException→evict)。对齐旧 SabrDashDataSource BACKOFF_MAX_ATTEMPTS。 */
    const val MAX_ATTEMPTS = 6
    /** 单次 backoff 最大 sleep(ms,<8s stall watchdog)。对齐旧 MAX_BACKOFF_SLEEP_MS。 */
    const val MAX_BACKOFF_SLEEP_MS = 2_500L
    /** 过滤阈值:小于此字节数的样本视为 init/retry/非媒体段,不进真实带宽统计(真实媒体段 ≥ ~300KB)。 */
    const val REAL_BW_MIN_BYTES = 100_000L
    /** 2026-09-01 重填容量环容量(笔)——近 8 笔成功请求,中位数抗单笔 TCP 爬升/小段噪声。 */
    const val CAPACITY_WINDOW_SAMPLES = 8
    /** 2026-09-01 重填容量最少样本数,不足 -1(证据不足,升档判据回退活跃 est)。 */
    const val CAPACITY_MIN_SAMPLES = 3
    /**
     * 滑动窗口时间跨度(ms):窗口内累计下载量/累计耗时计带宽。约一两个段时长,能让一次 15s 卡死(0量/满耗时)
     * 显著压低带宽又不过度被历史稀释。卡死时长超窗口时窗口只留该失败段 → 带宽=0 → ABR 彻底降档。
     */
    const val REAL_BW_WINDOW_MS = 20_000L
    /** alpha.9Z:gap 计入带宽前扣掉的「缓冲安全余量」(ms)——缓冲水位高出它的部分视为主动滑行,不算供给损失。 */
    const val BW_GAP_RUNWAY_RESERVE_MS = 10_000L
    /** 2026-08-31 A:顶档滑行余量加严的档位门槛(当前视频档 ≥ 此 height 时启用 20s 余量)。 */
    const val TOP_TIER_GAP_RESERVE_MIN_HEIGHT = 2160
    /**
     * 2026-08-31 A:顶档在位时的 **sustained 分母**滑行余量(ms)——4K 死亡行军(23:24 真机)里旧 10s 余量
     * 把缓冲 16.4s 水位下 14.2s 的 fetcher 排队间隔扣成合法滑行,sus 只剩突发口径(读 30M+,有效供给
     * 才 ~16M),顶档重准入闸被低档突发「合法通过」。20s 与 ABR 顶档水位急救阈值(HeightAware B1)
     * 同值同语义。只作用于 sustained 分母,活跃 est 不收(防千兆 4K 满缓冲滑行被误伤,见
     * isTopTierVideoSelected)。
     */
    const val TOP_TIER_GAP_RUNWAY_RESERVE_MS = 20_000L
    /** alpha.9Z:gap 计量下限,短于此的被迫空转视为噪声。 */
    const val BW_GAP_MIN_MS = 500L
    /** alpha.9Z:gap 计量上限,防单次超长空窗(如长时间暂停后恢复)单样本毒化窗口。 */
    const val BW_GAP_MAX_MS = 30_000L

    /**
     * P11-167:**超过它就认定「不是供给不足」的 gap**(ms)⇒ 不计 active est(按需求空闲/操作开销处理)。
     *
     * 依据(真机 `logs_live_20260921_214100.log`):用户手动**暂停 113 秒**,被记 30s 零字节样本进 active
     * 窗口 → est 塌 0 → `downgrade 1440p → 144p: est=0K` 砸最低档且长时间爬不回。
     * 供给不足时 loader 会立刻再要下一笔(gap 量级≈秒级),故 30s 足以把两者分开,同时仍允许
     * 「慢滴 + 重试」造成的十几秒 gap 照旧入账(那是真实供给证据)。
     */
    const val BW_GAP_IGNORE_MS = 30_000L
    /**
     * alpha.9Z:快小样本过滤的时间上限(ms)——bytes<100KB 且耗时低于它视为 init/retry/音频噪声丢弃;
     * 超过它视为「慢小响应」(服务端挂住只回极小体)真实供给中断,按实际 (bytes, elapsed) 入账。
     * 2026-08-27 真机:rn=18 8.5s 只回 939B 被过滤,est 钉 52M,31s 墙钟仅交付 67.6MB(有效 17M),
     * 缓冲 19.6s→2% 看门狗重载——供给中断发生在传输内,原过滤器全盲。
     */
    const val BW_SLOW_TINY_MS = 2_000L
    /** alpha.9Z:持续带宽窗口(墙钟 ms)——升档判据「60s 内实际交付字节/墙钟」。 */
    const val SUSTAINED_WINDOW_MS = 60_000L
    /** 2026-08-30:升档重锚合成样本时长(ms)——est 从「声明码率」起步,随后真实样本平滑接管。 */
    const val REAL_BW_RESEED_MS = 4_000L
    /** 2026-08-30:实测消耗码率最少段数——少于 3 段(起播 ~16s)证据不足,返回 -1 防小样本抖动。 */
    const val MEASURED_MIN_SEGS = 3L
    /**
     * alpha.9Z:持续带宽最短跨度,不足视为证据不足返回 -1(顶档闸据此挡冷启动 4K,起播爬档不被卡)。
     * 2026-08-31 15s→10s:ABR 评估只发生在 getNextChunk(缓冲灌满后 loader 停拉=零评估),首灌窗口
     * 实测仅 ~11s——15s 成熟期落在「满缓冲空闲期」内,顶档要等缓冲漏到 10s(~50s 后)才有机会被
     * 评到(20:28 真机:首次升档拖到 53s,1440p 拖到 115s)。10s 让顶档证据在首灌窗口内成熟。
     */
    const val SUSTAINED_MIN_SPAN_MS = 10_000L
  }
}

/**
 * 终端错误——RELOAD_PLAYER_RESPONSE / InvalidPoToken / SABR_ERROR / 重试耗尽。
 * [SabrDataSource.open] 捕获 → [com.kirin.mt.core.youtube.sabr.SabrStreamRegistry.evict] → 播放器 error-retry 重 harvest。
 */
internal class SabrTerminalException(message: String) : Exception(message)

/**
 * 2026-09-20(无效流量取证):**一次响应内**被整段丢弃的媒体字节与来源 itag。
 *
 * **为什么是调用内局部量而不是 fetcher 的实例字段**:fetcher 是**跨轨共享**的(SabrMediaPeriod 注释:
 * 「底层共享同一 SABR 会话/fetcher——这是修 60s 断崖的核心」),视频与音频两个 loader 会并发进
 * `media()`;实例字段会互相踩,统计出来的数会串场。
 */
private class DiscardTracker {
  var bytes = 0L
  /** MEDIA_HEADER 被白名单跳过时记 headerId→itag——PART_MEDIA 只有 headerId,靠它才认得出是谁的字节。 */
  val headerItags = mutableMapOf<Int, Int>()
  val itags = mutableSetOf<Int>()
}
