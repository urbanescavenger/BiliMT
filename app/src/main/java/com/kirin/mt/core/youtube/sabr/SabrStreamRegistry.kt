package com.kirin.mt.core.youtube.sabr

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
   * alpha.66/67:会话级 PO token 状态(holder,避免 data class [Entry] 加 var 破坏 equals)。
   * [currentPoToken] 初始=[SabrSession.poToken];status=2 时由 [SabrMediaFetcher.media] **同步**重铸
   * 换新(对齐 LibreTube,下个请求一定带新 token)。提升到会话级(非 fetcher 实例)——切清晰度重建
   * fetcher 时新 fetcher 仍读已刷新的 token(修 alpha.65 fetcher-instance currentPoToken 重建即丢的
   * 回归)。@Volatile 保证跨线程可见,单 ByteArray 引用读写无撕裂。
   */
  class PoTokenState(initialPoToken: ByteArray) {
    @Volatile var currentPoToken: ByteArray = initialPoToken
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
  }

  /**
   * 注册会话并按 [videoId] 缓存(同视频切清晰度/seek 时复用)。返回 opaque sessionId。
   * 若 [videoId] 已有缓存会话,复用其 sid + 覆盖更新 entry(会话参数可能因重 harvest 略变)。
   * alpha.59(Phase 2 DASH):无窗口锚点——DASH 会话服务整段视频,无 60s 轮换。
   */
  fun registerByVideoId(videoId: String, session: SabrSession, client: SabrClient, windowStartMs: Long = 0L, refreshPoToken: (suspend () -> ByteArray?)? = null, forceSessionVideoItag: Boolean = false): String {
    val existingSid = byVideoId[videoId]
    val sid = if (existingSid != null && sessions.containsKey(existingSid)) {
      sessions[existingSid] = Entry(session, client, windowStartMs, refreshPoToken, forceSessionVideoItag = forceSessionVideoItag)
      existingSid
    } else {
      val bytes = ByteArray(16)
      SecureRandom().nextBytes(bytes)
      val newSid = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
      sessions[newSid] = Entry(session, client, windowStartMs, refreshPoToken, forceSessionVideoItag = forceSessionVideoItag)
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
