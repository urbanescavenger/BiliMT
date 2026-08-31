package com.kirin.mt.core.player

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * IPTV 频道缩略图管理器(会话级):并发控制 + 去重 + 内存缓存。
 *
 * 每次进 IPTV tab 新建一个实例,离开销毁([clear] 清缓存)——用户要求"每次进列表都截",
 * 不永久磁盘缓存。只截当前可见频道(懒加载),[inFlight] 去重避免快速滚动触发重复截帧,
 * [semaphore] 限并发,避免同时拉多个流占满网络/解码资源。
 *
 * TV 端带 [probeStore] 时支持多源回退([getThumbnailForChannel]):urls[0] 截不出帧
 * 顺序补试下一个镜像(廉价探活已判死的跳过),出帧即回写"该源铁定可播"。移动端不传
 * store,行为与旧版一致(只截 urls[0])。
 */
class IptvThumbnailManager(
  private val context: Context,
  private val probeStore: IptvSourceProbeStore? = null,
) {
  // 截帧方案一(SurfaceTexture + EGL 离屏 glReadPixels):ImageReader 方案在部分设备/源的
  // codec 输出格式(0x7fa30c06 YUV/PRIVATE)与 ImageReader 配置格式(RGBA_8888=0x1)不匹配,
  // 抛 UnsupportedOperationException,整批截帧全失败。EGL 路径不做格式协商,兼容性更稳。
  private val capturer = IptvThumbnailCapturerEgl(context)
  private val cache = ConcurrentHashMap<String, Bitmap>()
  private val inFlight = ConcurrentHashMap.newKeySet<String>()
  private val semaphore = Semaphore(MaxConcurrent)

  /**
   * 取 [url] 的缩略图。已截过直接返回缓存;正在截返回 null(调用方跳过,截完会再触发);
   * 未截过则拉流截帧并缓存。失败/超时返回 null。
   */
  suspend fun getThumbnail(url: String): Bitmap? {
    cache[url]?.let { return it }
    if (!inFlight.add(url)) return null
    return try {
      semaphore.withPermit {
        capturer.capture(url)?.also { cache[url] = it }
      }
    } finally {
      inFlight.remove(url)
    }
  }

  /**
   * TV 多源回退截帧(判活换源的第二层,见 docs/iptv-feasibility.md 三期):顺序试频道
   * urls,某源出帧即返回——出帧 = 该源铁定可播(拉流+解码+渲染全链路走通),回写
   * 判活结果供列表重排/播放器换源复用。廉价探活已判死的 url 跳过,不浪费 22s。
   *
   * 注意:**截帧失败不回写 dead**——22s 超时的慢源/网络抖动会假死,只有廉价探活的
   * 硬失败(m3u8 都拉不到)才敢标死。
   */
  suspend fun getThumbnailForChannel(urls: List<String>): Bitmap? {
    if (urls.isEmpty()) return null
    for (url in urls.take(MaxSourceTries)) {
      if (probeStore?.isDead(url) == true) continue
      val bitmap = getThumbnail(url)
      if (bitmap != null) {
        probeStore?.markAlive(url)
        return bitmap
      }
    }
    return null
  }

  /** 清空缓存(离开 IPTV tab 时调用,下次进重新截)。 */
  fun clear() {
    cache.clear()
  }

  private companion object {
    const val MaxConcurrent = 3
    /** 多源回退补试上限:urls[0] 死再补 2 个,单频道最多 3×22s,不无限拖。 */
    const val MaxSourceTries = 3
  }
}