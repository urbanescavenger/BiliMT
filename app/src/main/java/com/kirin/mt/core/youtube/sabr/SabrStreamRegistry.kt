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
     * alpha.65:STREAM_PROTECTION_STATUS status=2(Attestation pending)时重铸 PO token 的回调,
     * 对齐 LibreTube `SabrClient.generatePoToken`。由 [com.kirin.mt.core.youtube.YoutubePlaybackResolver]
     * 在 resolve 阶段注入(捕获进程级 [com.kirin.mt.core.youtube.YoutubeBotGuard])。
     * null=alpha.64 行为(不刷新,~60s 后 status=3 terminal)。由 [SabrMediaFetcher] 取用。
     */
    val refreshPoToken: (suspend () -> ByteArray?)? = null,
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
  fun registerByVideoId(videoId: String, session: SabrSession, client: SabrClient, windowStartMs: Long = 0L, refreshPoToken: (suspend () -> ByteArray?)? = null): String {
    val existingSid = byVideoId[videoId]
    val sid = if (existingSid != null && sessions.containsKey(existingSid)) {
      sessions[existingSid] = Entry(session, client, windowStartMs, refreshPoToken)
      existingSid
    } else {
      val bytes = ByteArray(16)
      SecureRandom().nextBytes(bytes)
      val newSid = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
      sessions[newSid] = Entry(session, client, windowStartMs, refreshPoToken)
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
