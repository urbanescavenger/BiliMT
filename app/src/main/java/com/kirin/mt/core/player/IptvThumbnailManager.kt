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
 * [semaphore] 限并发 2 个,避免同时拉多个流占满网络/解码资源。
 */
class IptvThumbnailManager(private val context: Context) {
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

  /** 清空缓存(离开 IPTV tab 时调用,下次进重新截)。 */
  fun clear() {
    cache.clear()
  }

  private companion object {
    const val MaxConcurrent = 3
  }
}
