package com.kirin.mt.core.youtube.sabr

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.withLock

/**
 * 进程级 SABR 流会话注册表——把 resolve 阶段 harvest+构造的 [SabrSession]/[SabrClient]
 * 按 opaque sessionId 存盘,供播放器侧 [SabrStreamingDataSource] 在 open() 时按
 * `sabr://youtube/<sessionId>?stream=...` 反查。
 *
 * 用全局 object 而非 AppContainer 注入:DataSource 只从 Media3 拿到一个 URI(无上下文),
 * 会话对象(poToken/ustreamerConfig/cpn)又太重无法塞进 URL/query,故用进程级查表。
 * 一次播放一个 entry;release 在播放器 teardown(未接线时由 LRU/进程退出回收——
 * entry 仅持有共享 [SabrClient] 引用,泄漏代价很小)。
 */
internal object SabrStreamRegistry {
  private const val tag = "YtSabr"
  private val sessions = ConcurrentHashMap<String, Entry>()
  /**
   * alpha.29:videoId → sessionId 反查表。切清晰度会重跑 resolve(播放器用 preferredQualityId 重建
   * MediaSource),但 poToken/ustreamerConfig/cpn 是**会话级**可复用(~6h 有效,FreeTube 证实同 token
   * 跨多 itag),无需重 harvest。命中则 [getByVideoId] 返回已有 sid,resolver 跳过 harvest 直接建
   * PlaybackInfo(用新 preferredQualityId 选 itag)。
   */
  private val byVideoId = ConcurrentHashMap<String, String>()

  /**
   * alpha.8 诊断(Phase 2 取证):RELOAD_PLAYER_RESPONSE 的 reloadToken(videoId → 144B base64,
   * ReloadPlaybackParams.token)停车槽。独立于 [sessions]/[byVideoId],**evict 不清**——token 必须在
   * evict 后仍存活,供 resolve() 下次重进时 [consumeReloadToken] 取走去重打 visionOS /player。
   * [reloadCounts] 记录每 videoId 连续 RELOAD 次数,防诊断期回传失败死循环(超 [MAX_RELOADS] 落 DASH)。
   */
  const val MAX_RELOADS = 3
  private val pendingReloads = ConcurrentHashMap<String, String>()
  private val reloadCounts = ConcurrentHashMap<String, Int>()

  /**
   * alpha.9X(P11-99b):自合成 DASH 兜底直链 403(BAD_HTTP_STATUS)已判死标记(videoId 集合)。
   * attestation 门控视频(如 Fhyu9sqcF-o/irrSuCb3BhI)SABR RELOAD → DASH 兜底 → NewPipe ANDROID
   * 未 attested 直链 403 → 重试只会原样再 403。播放器 error-retry 时标记,resolve 重进由
   * [buildDashFallbackFromNewPipe] 检查跳过自合成 DASH,直落 dashMpdUrl/HLS(visionOS HLS manifest
   * URL 不走 attestation 门控,LibreTube 次选兜底同源)。进程级,不随 evict 清(同 reloadCounts 语义)。
   */
  private val dashFallbackFailedVideos =
    java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  /**
   * P11-102(guard 放开):WEB-SABR 兜底**已失败**标记(videoId 集合)。reloadCount>0 放行走
   * WEB-SABR(pot>0 会话)后若仍失败(solver 失败/playability 非 OK 等),标记后本次进程不再
   * 重试——每次 WEB-SABR 尝试要 WebView /player + solver n-decrypt(~25s),auto-retry 链里反复
   * 烧无意义。成功时 [clearWebSabrFailed] 清除。进程级,同 [dashFallbackFailedVideos] 语义。
   */
  private val webSabrFailedVideos =
    java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  /**
   * 2026-09-20(C1「跟着服务端走」):**该视频上服务端实际推过的 itag**(进程级,跨会话重建有效)。
   *
   * 为什么必须进程级 + 按 videoId:材料会话里服务端只服务它那场会话绑定的格式(r2022:只推 399/251,
   * 而我们在请求 302)—— 而**首个 FORMAT_INIT 要几秒后才到**,rn=0 时收窄集合必然为空;更糟的是之后
   * loader 卡在 `getNextSegment` 的 6 连重试里、`getNextChunk` 不再被调用(同步点被阻塞),evict 后新
   * 会话又是空集合 ⇒ 永远收窄不到。挂在选档实例里同样会被"重建即丢"。故记在这里。
   *
   * 累积(不是覆盖):同一视频重新 harvest 出的材料可能服务不同格式,取并集只会更宽松、不会把候选清空;
   * 非材料会话里服务端推的就是我们的档 ⇒ 并集≈我们的档 ⇒ 行为不变。
   */
  private val serverServedItagsByVideo =
    java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()

  fun noteServerServedItag(videoId: String?, itag: Int) {
    if (videoId.isNullOrBlank()) return
    serverServedItagsByVideo.getOrPut(videoId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(itag)
  }

  fun serverServedItags(videoId: String?): Set<Int> =
    if (videoId.isNullOrBlank()) emptySet() else serverServedItagsByVideo[videoId]?.toSet() ?: emptySet()

  /**
   * P11-152:该视频当前是否有**材料会话**在册(决定选档要不要收窄到 served 集合)。
   *
   * 收窄本身只对材料会话有意义(服务端只供浏览器那场绑定的档);对普通会话收窄会把梯子冻在
   * 「已推过的档」上 —— r2042 TV 真机 `pushed=[139, 247]` ⇒ 全程停 720p(见 [SabrSession.fromHarvestMaterial])。
   */
  fun hasMaterialSession(videoId: String?): Boolean =
    if (videoId.isNullOrBlank()) false
    else sessions.values.any { it.videoId == videoId && it.session.fromHarvestMaterial }

  /**
   * P11-146(2026-09-20 历史复盘后的**四臂轮换**;取代 P11-145 的三臂)。
   *
   * 复盘发现(全部有日志,见 docs/youtube-web-sabr.md §5.9.7):当天**能播的两场都是「材料会话 +
   * harvest 页铸的 token」**(r1992 `status=1`×10/媒体块 35;r2002 `status=1`×100/媒体块 115),
   * 而「自铸 token」这条(r2008/r2028/r2030 自造臂)第一笔就被判 `status=2`;当天崩溃的真凶是
   * 已修的刷新 bug(刷新次数 r2002=0→status3 零次、r2006=1→22 次、r2008=3→66 次)。
   * 材料会话唯一的病是**它的格式集**:服务端只供「那一场浏览器选中的档」,我们的阶梯选档得落进去
   * (r1992 请求 136 ✓、r2002 请求 698 ✓ 就播;r2030 请求 302 ✗ 就 0 块)—— 这正是 C1 要解决的,
   * 而 C1 被起播锁夹回(P11-146 已修)。
   *
   * 故按 resolve 次数轮换四臂,一次构建覆盖全部假设:
   *   臂 A(0)= 自造会话 + 自铸 token(现况,2.6s)
   *   臂 B(1)= 自造会话 + **harvest 页 token**(只借 token;两边各一半的合成)
   *   臂 C(2)= 自造会话 + **不带 token**(pot-less)
   *   臂 D(3)= **完整材料会话**(URL/ust/cpn/token 全借)+ C1 起播锁修复 ⇒ 历史基底 + 新修
   */
  private val webSabrTokenArmCounters =
    java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

  /** P11-147:本视频已试过的臂数(r2032 实测:不查这个,臂 A 一死就会把 B/C/D 全短路)。 */
  fun webSabrArmsTried(videoId: String): Int = webSabrTokenArmCounters[videoId]?.get() ?: 0

  /** 0=自造+自铸 / 1=自造+页面 token / 2=pot-less / 3=完整材料会话。 */
  fun nextWebSabrTokenArm(videoId: String): Int {
    val n = webSabrTokenArmCounters
      .getOrPut(videoId) { java.util.concurrent.atomic.AtomicInteger() }
      .incrementAndGet()
    val arm = (n - 1) % 4
    val label = when (arm) {
      0 -> "A(自造+自铸 token)"
      1 -> "B(自造+harvest 页 token)"
      2 -> "C(自造+pot-less)"
      else -> "D(完整材料会话+C1 修复)"
    }
    Log.i(tag, "WEB-SABR 四臂(P11-146): videoId=$videoId resolve#$n → 臂$label")
    return arm
  }

  /** DASH 兜底直链 403 判死标记(播放器 onPlayerError 2004 + YouTube 请求时调)。 */
  fun markDashFallbackFailed(videoId: String) {
    if (dashFallbackFailedVideos.add(videoId)) {
      Log.w(tag, "markDashFallbackFailed videoId=$videoId → 下次 resolve 跳过自合成 DASH,直落 dashMpdUrl/HLS")
    }
  }

  fun isDashFallbackFailed(videoId: String): Boolean = dashFallbackFailedVideos.contains(videoId)

  /** WEB-SABR 兜底失败标记(resolve 内 buildWebSabrFallback 返回 null 时调)。 */
  fun markWebSabrFailed(videoId: String) {
    if (webSabrFailedVideos.add(videoId)) {
      Log.w(tag, "markWebSabrFailed videoId=$videoId → 本进程不再重试 WEB-SABR(防烧 WebView/solver)")
    }
  }

  fun isWebSabrFailed(videoId: String): Boolean = webSabrFailedVideos.contains(videoId)

  /** WEB-SABR 成功后清除失败标记(下次 resolve 可再走 WEB-SABR)。 */
  fun clearWebSabrFailed(videoId: String) {
    if (webSabrFailedVideos.remove(videoId)) {
      Log.i(tag, "clearWebSabrFailed videoId=$videoId(WEB-SABR 已成功)")
    }
  }

  /** 存 reloadToken 停车 + 递增连续 reload 计数。由 [SabrMediaFetcher.processPart] RELOAD 分支调用。 */
  fun storeReloadToken(videoId: String, token: String) {
    pendingReloads[videoId] = token
    val count = (reloadCounts[videoId] ?: 0) + 1
    reloadCounts[videoId] = count
    Log.w(tag, "storeReloadToken videoId=$videoId count=$count tokenLen=${token.length} → evict+re-resolve 重打 /player")
  }

  /** 原子取出停车 token(消费一次)。resolve() 重进时用;已取走则返回 null。 */
  fun consumeReloadToken(videoId: String): String? = pendingReloads.remove(videoId)

  /**
   * alpha.88:RELOAD **单槽**(视频无关)停车。真机 36B RELOAD payload 可能是较小变体,**不含**内层
   * videoId(旧 144B 变体才有 f4=videoId)→ 旧 [storeReloadToken] 的 `vid != null` 存不进 →
   * [consumeReloadToken] 恒返回 null → alpha.87 RELOAD 重载闭环永远不触发。单播放器同时只播一个视频,
   * 单槽足够:fetcher 存(无论 videoId 能否解出),resolve() [consumeReloadTokenSlot] 取走。
   * 仍顺带维护 videoId-keyed [pendingReloads]/[reloadCounts](诊断计数;vid 可解出时)。
   */
  @Volatile private var pendingReloadSlot: String? = null
  @Volatile private var pendingReloadSlotVid: String? = null

  /** 存 reloadToken 进单槽(+ vid 可解出时顺带 videoId-keyed 计数)。由 fetcher RELOAD 分支调用。 */
  fun storeReloadTokenSlot(videoId: String?, token: String) {
    pendingReloadSlot = token
    pendingReloadSlotVid = videoId
    if (videoId != null) {
      val count = (reloadCounts[videoId] ?: 0) + 1
      reloadCounts[videoId] = count
      pendingReloads[videoId] = token
      Log.w(tag, "storeReloadTokenSlot videoId=$videoId count=$count tokenLen=${token.length}")
    } else {
      Log.w(tag, "storeReloadTokenSlot videoId=null(短 RELOAD 变体?) tokenLen=${token.length} → 单槽存,resolve 走 slot")
    }
  }

  /** 原子取走单槽 token(消费一次)。resolve() 重进 RELOAD 闭环时用;已取走则返回 null。 */
  fun consumeReloadTokenSlot(): String? {
    val t = pendingReloadSlot
    pendingReloadSlot = null
    val vid = pendingReloadSlotVid
    pendingReloadSlotVid = null
    if (t != null) Log.i(tag, "consumeReloadTokenSlot tokenLen=${t.length} vid=$vid")
    return t
  }

  /** 当前连续 reload 次数。 */
  fun reloadCount(videoId: String): Int = reloadCounts[videoId] ?: 0

  /** 播放真正恢复(首个非 init MEDIA_END)后清零,打断 reload 链。 */
  fun resetReloadCount(videoId: String) {
    reloadCounts.remove(videoId)
  }

  /**
   * P11-102c:status=2 PO token 刷新 **single-flight** 入口。
   *
   * 并发调用者共享同一会话 [PoTokenState.refreshMutex]:持锁者执行 [mint](完整 BotGuard 重铸,
   * ~1-3s),其余排队;轮到时若 freshness 窗口([freshnessMs],默认 5s)内刚铸过则直接复用,
   * 不再重复铸。铸成后统一写 [PoTokenState.currentPoToken](会话内所有 fetcher 下个请求带同一
   * fresh token)。mint 失败/null 不写状态(keep stale),由调用方日志告警。
   *
   * 返回 fresh token(含 coalesce 复用);null=mint 失败或被并发者抢先铸出后复用失败(理论不可达,
   * lastRefreshedToken 非空即返回)。
   */
  suspend fun refreshPoTokenSingleFlight(
    state: PoTokenState,
    mint: suspend () -> ByteArray?,
    freshnessMs: Long = 5_000L,
  ): ByteArray? {
    return state.refreshMutex.withLock {
      val lastToken = state.lastRefreshedToken
      val lastAt = state.lastRefreshAtMs
      val now = System.currentTimeMillis()
      if (lastToken != null && lastAt > 0L && now - lastAt < freshnessMs) {
        Log.i(tag, "PO token refresh coalesced (fresh age=${now - lastAt}ms, ${lastToken.size}B) → reuse")
        return@withLock lastToken
      }
      val fresh = mint()
      if (fresh != null && fresh.isNotEmpty()) {
        state.currentPoToken = fresh
        state.currentPoTokenAtMs = System.currentTimeMillis()
        state.lastRefreshedToken = fresh
        state.lastRefreshAtMs = System.currentTimeMillis()
        fresh
      } else {
        null
      }
    }
  }

  /**
   * alpha.66/67:会话级 PO token 状态(holder,避免 data class [Entry] 加 var 破坏 equals)。
   * [currentPoToken] 初始=[SabrSession.poToken];status=2 时由 [SabrMediaFetcher.media] **同步**重铸
   * 换新(对齐 LibreTube,下个请求一定带新 token)。提升到会话级(非 fetcher 实例)——切清晰度重建
   * fetcher 时新 fetcher 仍读已刷新的 token(修 alpha.65 fetcher-instance currentPoToken 重建即丢的
   * 回归)。@Volatile 保证跨线程可见,单 ByteArray 引用读写无撕裂。
   *
   * P11-102c(single-flight 刷新):[refreshMutex] + [lastRefreshedToken]/[lastRefreshAtMs]。
   * 会话里每个 fetcher(视频轨/音轨/多轨组)在自己响应里看到 status=2 都会调刷新——r1928 真机
   * 13:30:04-08 实锤 4 个 fetcher **并发各铸一次**(完整 BotGuard 重铸),token(124/128B 混出)
   * last-write-wins 互相踩 → 服务端判 InvalidPoToken(status=3)整会话死。single-flight:并发
   * 刷新进同一把锁,锁内 freshness 窗口(5s)内刚铸过直接复用,4 次 mint 收敛 1 次。
   */
  class PoTokenState(initialPoToken: ByteArray) {
    @Volatile var currentPoToken: ByteArray = initialPoToken
    /**
     * P11-144(取证):当前 token 的**起始时刻**。请求日志打它的年龄 —— 用来分辨「后续请求被判
     * status=3」到底是 **token 过期**(年龄大)还是 **token 内容被拒**(刚换上去就被拒)。
     * r2028 实测:刷新后 0.5s 就被判 status=3 且 `potAge` 极小 ⇒ 内容问题,不是过期。
     */
    @Volatile var currentPoTokenAtMs: Long = System.currentTimeMillis()
    val refreshMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile var lastRefreshedToken: ByteArray? = null
    @Volatile var lastRefreshAtMs: Long = 0L
  }

  /** 一个 SABR 播放会话:SabrSession(会话参数)+ SabrClient(驱动器,持有 httpClient)+ 服务窗口起点。 */
  data class Entry(
    val session: SabrSession,
    val client: SabrClient,
    /**
     * alpha.52:本会话服务的 60s 窗口起点(anchor)。服务端对每会话服务量上限 [anchor..anchor+60s],
     * 由 harvest 的 `&t=`(watch 起播位置)决定。DataSource 用 `sessionAnchorMs` 判断窗口耗尽、seek
     * 用「目标窗口 == 当前窗口」判断是否可复用会话。
     */
    val windowStartMs: Long = 0L,
    /**
     * alpha.65/67:STREAM_PROTECTION_STATUS status=2(Attestation pending)时重铸 PO token 的回调,
     * 对齐 LibreTube `SabrClient.generatePoToken`。由 [com.kirin.mt.core.youtube.YoutubePlaybackResolver]
     * 在 resolve 阶段注入(捕获进程级 [com.kirin.mt.core.youtube.YoutubeBotGuard])。
     * null=不刷新(~60s 后 status=3 terminal)。由 [SabrMediaFetcher.media] 同步取用——下个请求一定带新 token。
     */
    val refreshPoToken: (suspend () -> ByteArray?)? = null,
    /**
     * alpha.66/67:会话级 PO token 状态([PoTokenState.currentPoToken])。
     * 初始 PoTokenState(session.poToken);status=2 时由 [SabrMediaFetcher.media] 同步重铸换新。
     * 提升 Entry 而非 fetcher 实例,修 alpha.65 切清晰度重建 fetcher 丢 token 的回归。
     */
    val poTokenState: PoTokenState = PoTokenState(session.poToken),
    /**
     * alpha.83 诊断(实验):强制视频轨用**会话选中的** videoFormatId(`session.videoFormatId`),跳过
     * [com.kirin.mt.core.youtube.sabr.media.SabrMediaFetcher] 的 selectFormat 按声明 itag 重选。
     * 用于证伪"itag313(或别的 itag)是 RELOAD 根因"这一红绯鱼——强制锁会话选轨后若仍 RELOAD,说明根因
     * 不在 itag 而在 ustreamerConfig 来源(见 Piped 后端方案)。Piped 路径默认 true;NewPipe 路径可手动开。
     * false(默认)= 正常 selectFormat 选轨(旧行为)。
     */
    val forceSessionVideoItag: Boolean = false,
    /**
     * alpha.9X:本会话绑定的 videoId(registerByVideoId 填充;register 无视频概念用 null)。
     * 供 fetcher 在 RELOAD 时可靠计数——RELOAD payload 的 36B 短变体可能解不出内层 videoId
     * (f4=null),用会话自己的 videoId 保证死循环守卫 [reloadCount] 总能递增、DASH 兜底可靠触发。
     */
    val videoId: String? = null,
  ) {
    /**
     * alpha.62(Phase 2 DASH A/V 同步修复):每流已取 media 段的**实际**累计媒体时长(ms)。
     *
     * SABR 服务端按 `clientAbrState.playerTimeMs` 选段,不认段号——playerTimeMs 必须发段的**真实展示起点**
     * (FreeTube SabrStreamingAdapter "abusing playerTimeMs as exact segment start";googlevideo 由 SIDX/Cues
     * 取真实 startTime,FreeTube 由 MPD/容器索引)。DASH 每段 DataSource open() 用本累计作 playerTimeMs,
     * 成功 fetch 后累加实际 MediaHeader.durationMs——对齐 progressive 兜底 [SabrStreamingDataSource.cumulativeDurationMs]
     * (alpha.36 已验证跨 60s 不断)。
     *
     * **不要**用 `(segmentNumber-1)*声明段长`(video 声明 6000 > 实际 ~4360 → playerTimeMs 超前 → 服务端跳段
     * realSeq>N → 视频内容有洞 → 视频抽干卡顿 → 与 audio 不同步 → 会话 ~60s 亡重播,alpha.61 真机坐实)。
     */
    val videoCumulativeMs = AtomicLong(0L)
    val audioCumulativeMs = AtomicLong(0L)
    /** 每流最后成功 fetch 的 media 段号(1-based);init 段不更新。非顺序请求(seek/重载)用它判断并按 MPD 时间线近似重置累计。 */
    val videoFetchedSeg = AtomicLong(0L)
    val audioFetchedSeg = AtomicLong(0L)
    /**
     * alpha.63(对齐 LibreTube buildBufferedRanges):每流首段服务端自报 startMs(MediaHeader.startMs)。
     * own bufferedRange 用 [firstStartMs .. firstStartMs+cumulative] 锚定到服务端时间线(非我们累计估算),
     * startSegmentIndex=1 / endSegmentIndex=fetchedSeg。首段请求前=0(无 own range,同 LibreTube 空列表)。
     * 非顺序(seek)时累计会重置但本字段不重置——首播顺序播跨 60s 是 60s 断崖场景,seek 续播另议。
     */
    val videoFirstStartMs = AtomicLong(0L)
    val audioFirstStartMs = AtomicLong(0L)

    // ── P11-102d 诊断:status=3(InvalidPoToken)终端取证计数 ──
    // r1931 真机:同一 fresh token 一次收(seg=12)一次拒(seg=13, playerTimeMs=61440>60000),
    // 3 会话不同 token 全死同一 playerTimeMs → token 无关。候选机制:60s 服务窗口(锚 0,续播
    // 吃光)vs sabrContexts 全空(contexts=0/0,type 52/53 part 未处理)。本组计数给一次真机分辨:
    // 窗口假设 → sessAgeMs/请求序号落在 ~60s 边界;contexts 假设 → unhandledParts(52/53/57)
    // 非零且 ctxActive/ctxStored 恒 0。
    val diagSessionStartMs = System.currentTimeMillis()
    val diagStatus2Count = AtomicLong(0L)
    val diagRequestCount = AtomicLong(0L)
    val diagUnhandledParts = ConcurrentHashMap<Int, Int>()
  }

  /**
   * 注册会话并按 [videoId] 缓存(同视频切清晰度/seek 时复用)。返回 opaque sessionId。
   * 若 [videoId] 已有缓存会话,复用其 sid + 覆盖更新 entry(会话参数可能因重 harvest 略变)。
   * alpha.59(Phase 2 DASH):无窗口锚点——DASH 会话服务整段视频,无 60s 轮换。
   */
  fun registerByVideoId(videoId: String, session: SabrSession, client: SabrClient, windowStartMs: Long = 0L, refreshPoToken: (suspend () -> ByteArray?)? = null, forceSessionVideoItag: Boolean = false): String {
    val existingSid = byVideoId[videoId]
    val sid = if (existingSid != null && sessions.containsKey(existingSid)) {
      sessions[existingSid] = Entry(session, client, windowStartMs, refreshPoToken, forceSessionVideoItag = forceSessionVideoItag, videoId = videoId)
      existingSid
    } else {
      val bytes = ByteArray(16)
      SecureRandom().nextBytes(bytes)
      val newSid = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
      sessions[newSid] = Entry(session, client, windowStartMs, refreshPoToken, forceSessionVideoItag = forceSessionVideoItag, videoId = videoId)
      byVideoId[videoId] = newSid
      newSid
    }
    Log.i(tag, "registerByVideoId videoId=$videoId sid=$sid windowStart=$windowStartMs videoFormats=${session.videoFormats.size} (active=${sessions.size})")
    return sid
  }

  /** alpha.29:按 videoId 查缓存的 sessionId(切清晰度复用会话,跳过 harvest);未缓存返回 null。 */
  fun getByVideoId(videoId: String): String? = byVideoId[videoId]?.let { if (sessions.containsKey(it)) it else null }

  /** alpha.52:按 videoId 查当前活跃会话 entry(含 windowStartMs);未缓存返回 null。 */
  fun getEntryByVideoId(videoId: String): Entry? = byVideoId[videoId]?.let { sessions[it] }

  /** 注册会话,返回 opaque sessionId(16 随机字节 base64url,用作 `sabr://youtube/<sid>` 查表 key)。 */
  fun register(session: SabrSession, client: SabrClient): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    val sid = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    sessions[sid] = Entry(session, client)
    Log.i(tag, "register sid=$sid poToken=${session.poToken.size}B ustreamerCfg=${session.ustreamerConfig.size}B (active=${sessions.size})")
    return sid
  }

  fun get(sessionId: String): Entry? = sessions[sessionId]

  /**
   * alpha.36:驱逐会话——清 [sessions] + [byVideoId] 反查表。SABR 流 EOF(backoff 耗尽 / 60s 断崖)
   * 时由 [SabrStreamingDataSource] read() 调用,使播放器 stall-retry 重跑 resolve 时 [getByVideoId]
   * cache miss → 重新 harvest 建新会话,而非复用服务端已停发的死会话(stall-reload 又开同一死会话
   * → 立即 backoff 死循环,alpha.35 日志证实)。正常播完也调,无害(视频已完,无复用需要)。
   */
  fun evict(sessionId: String) {
    sessions.remove(sessionId)
    val it = byVideoId.entries.iterator()
    while (it.hasNext()) { if (it.next().value == sessionId) it.remove() }
    Log.i(tag, "evict sid=$sessionId (active=${sessions.size})")
  }

  fun release(sessionId: String) {
    sessions.remove(sessionId)?.let {
      Log.i(tag, "release sid=$sessionId (active=${sessions.size})")
    }
  }
}
