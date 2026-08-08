package com.kirin.mt.core.youtube.sabr

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

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
  )

  /**
   * alpha.48(轮换):主动会话轮换工厂——服务端对每个 SABR 会话有 ~60s 服务量上限(锚点..+60s,
   * docs row 72),到点后同一会话任何请求都被软拒。被动 stall-retry 已太晚(playhead 已 >60s,新
   * 会话 0 锚点仍拒)。唯一解:播放到窗口耗尽前**主动**开新 harvest 建下一个窗口的会话(startMs=
   * 窗口起点),用 [registerByVideoId] 复用同 sid 覆盖 entry,DataSource 检测 entry 引用变化后无缝切换。
   * 由 resolver 在 SABR 路径装填(持有 harvester/client/videoFormats)。
   *
   * 签名 `(videoId, startMs, epoch) -> Unit`。返回即触发异步执行([requestRotation] 在后台协程跑,不
   * 阻塞 read() 路径);工厂内部自行 harvest+建会话+registerByVideoId。`epoch` 是发起时的旋转代际——
   * 工厂注册前用 [isRotationEpochCurrent] 校验,seek 后过期预取(旧窗口)丢弃,不覆盖 seek 锚定的新会话。
   * 防并发:同 videoId 在途旋转期间重复请求直接忽略。
   */
  @Volatile var rotationFactory: (suspend (videoId: String, startMs: Long, epoch: Long) -> Unit)? = null

  private val rotationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val rotationInFlight = ConcurrentHashMap.newKeySet<String>()
  /** alpha.52:每 videoId 的旋转代际计数——seek 时 bump,让旧窗口在途预取结果作废。 */
  private val rotationEpoch = ConcurrentHashMap<String, Long>()

  /**
   * alpha.48:主动触发会话旋转——后台协程跑 [rotationFactory],同 videoId 在途期间忽略重复请求
   * (video/audio 两路 DataSource 共享同 sid,都会触发;只许一次)。[rotationFactory] 未装填(非 SABR
   * 路径)→ no-op。完成后 [registerByVideoId] 覆盖 entry(复用同 sid),DataSource 检测到切换。
   */
  fun requestRotation(videoId: String, startMs: Long) {
    val factory = rotationFactory ?: run {
      Log.i(tag, "requestRotation videoId=$videoId startMs=$startMs (no factory — non-SABR path, skip)")
      return
    }
    if (!rotationInFlight.add(videoId)) {
      Log.i(tag, "requestRotation videoId=$videoId startMs=$startMs (already in flight, skip)")
      return
    }
    val epoch = rotationEpoch[videoId] ?: 0L
    Log.i(tag, "requestRotation videoId=$videoId startMs=$startMs epoch=$epoch (start async harvest)")
    rotationScope.launch {
      try {
        factory(videoId, startMs, epoch)
      } catch (t: Throwable) {
        Log.w(tag, "requestRotation videoId=$videoId failed: ${t.message}", t)
      } finally {
        rotationInFlight.remove(videoId)
      }
    }
  }

  /**
   * alpha.52:seek 重新起播前调用——bump 该 videoId 的旋转代际 + 释放 in-flight 锁。此后在途的旧窗口
   * 预取,其工厂注册前经 [isRotationEpochCurrent] 校验发现代际不符 → 丢弃(否则会覆盖 seek 锚定的新
   * 窗口会话,致 DataSource 开出来的窗口与实际会话错位)。
   */
  fun resetForSeek(videoId: String) {
    rotationEpoch[videoId] = (rotationEpoch[videoId] ?: 0L) + 1
    rotationInFlight.remove(videoId)
    Log.i(tag, "resetForSeek videoId=$videoId epoch=${rotationEpoch[videoId]} (drop stale prefetch after seek)")
  }

  /** alpha.52:某次轮换发起时代际是否仍是最新——false 表示期间发生过 seek,结果应丢弃。 */
  fun isRotationEpochCurrent(videoId: String, epoch: Long): Boolean =
    (rotationEpoch[videoId] ?: 0L) == epoch

  /**
   * 注册会话并按 [videoId] 缓存(同视频切清晰度/seek 时复用)。返回 opaque sessionId。
   * 若 [videoId] 已有缓存会话,复用其 sid + 覆盖更新 entry(会话参数可能因重 harvest 略变)。
   * @param windowStartMs 本会话服务的 60s 窗口起点(见 [Entry.windowStartMs])。
   */
  fun registerByVideoId(videoId: String, session: SabrSession, client: SabrClient, windowStartMs: Long = 0L): String {
    val existingSid = byVideoId[videoId]
    val sid = if (existingSid != null && sessions.containsKey(existingSid)) {
      sessions[existingSid] = Entry(session, client, windowStartMs)
      existingSid
    } else {
      val bytes = ByteArray(16)
      SecureRandom().nextBytes(bytes)
      val newSid = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
      sessions[newSid] = Entry(session, client, windowStartMs)
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

  /**
   * alpha.52(seek):「正在播放的窗口会话」追踪——[SabrStreamingDataSource] 在 open()/窗口切换时调用,
   * 记录该 videoId 当前真正在播的会话(而非被预取覆盖的最新 entry)。resolver 据此判断 seek 目标窗口
   * 是否可复用当前会话(窗口内 seek 免 re-harvest)。
   */
  private val activeWindowByVideoId = ConcurrentHashMap<String, Long>()
  private val activeEntryByVideoId = ConcurrentHashMap<String, Entry>()

  fun noteActive(videoId: String, entry: Entry) {
    activeWindowByVideoId[videoId] = entry.windowStartMs
    activeEntryByVideoId[videoId] = entry
  }

  /** alpha.52(seek):目标窗口 == 当前播放窗口 → 返回当前播放会话(可复用,免 re-harvest);否则 null。 */
  fun activeSessionForWindow(videoId: String, windowStartMs: Long): Entry? =
    if (activeWindowByVideoId[videoId] == windowStartMs) activeEntryByVideoId[videoId] else null

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
