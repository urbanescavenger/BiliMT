package com.kirin.mt.core.youtube.sabr

import android.util.Base64
import android.util.Log
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

  /** 一个 SABR 播放会话:SabrSession(会话参数)+ SabrClient(驱动器,持有 httpClient)。 */
  data class Entry(
    val session: SabrSession,
    val client: SabrClient,
  )

  /**
   * 注册会话并按 [videoId] 缓存(同视频切清晰度时复用)。返回 opaque sessionId。
   * 若 [videoId] 已有缓存会话,复用其 sid + 覆盖更新 entry(会话参数可能因重 harvest 略变)。
   */
  fun registerByVideoId(videoId: String, session: SabrSession, client: SabrClient): String {
    val existingSid = byVideoId[videoId]
    val sid = if (existingSid != null && sessions.containsKey(existingSid)) {
      sessions[existingSid] = Entry(session, client)
      existingSid
    } else {
      val bytes = ByteArray(16)
      SecureRandom().nextBytes(bytes)
      val newSid = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
      sessions[newSid] = Entry(session, client)
      byVideoId[videoId] = newSid
      newSid
    }
    Log.i(tag, "registerByVideoId videoId=$videoId sid=$sid videoFormats=${session.videoFormats.size} (active=${sessions.size})")
    return sid
  }

  /** alpha.29:按 videoId 查缓存的 sessionId(切清晰度复用会话,跳过 harvest);未缓存返回 null。 */
  fun getByVideoId(videoId: String): String? = byVideoId[videoId]?.let { if (sessions.containsKey(it)) it else null }

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

  fun release(sessionId: String) {
    sessions.remove(sessionId)?.let {
      Log.i(tag, "release sid=$sessionId (active=${sessions.size})")
    }
  }
}
