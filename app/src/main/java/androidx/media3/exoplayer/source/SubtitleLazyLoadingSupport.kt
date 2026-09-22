@file:Suppress("PackageDirectoryMismatch")

package androidx.media3.exoplayer.source

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/**
 * P11-120:字幕轨「懒加载」开关的**唯一入口** —— 让 ProgressiveMediaSource 在 prepare 期**零读取**。
 *
 * ## 为什么本文件声明在 media3 的包名下
 *
 * media3 的 `ProgressiveMediaSource.Factory.enableLazyLoadingWithSingleTrack(int, Format)` 是
 * **包私有**(package-private),包外无法直接调用。两条路:
 *  ① 反射调用(LibreTube 的写法)—— 方法名/签名写错要到真机运行才发现,且依赖 proguard keep 规则保名字;
 *  ② **把本类放进同一个包直接调用** —— Kotlin 对 Java 符号按「声明包名」判可见性,同包即可访问,
 *     编译期就能校验方法确实存在。
 * 取 ② 作主路径,① 作运行期兜底(见 [enable] 的 catch)。
 *
 * ## 这个方法解决什么问题
 *
 * javadoc 原文:*"Allows the ProgressiveMediaSource to complete preparation without reading any
 * data"* —— 数据只在该轨被 **track selection 选中** 时才开始读。正是字幕这种「网络不可信、
 * 且非关键」的外挂轨需要的语义:
 *  - 未选中字幕 = 一个请求都不发(用户没开字幕时零开销);
 *  - 选中但网络黑洞 = 只有该 child 的 loader 挂着,prepare 早已完成,主源照常播。
 *
 * 这正是 media3 自己的 `DefaultMediaSourceFactory` 处理 `MediaItem.SubtitleConfiguration` 时的做法
 * (同包内直调,配 `setLoadOnlySelectedTracks`);LibreTube 则是用反射调同一个方法
 * (`OnlinePlayerService.kt:238-289`)。
 *
 * **前置契约**(media3 明确要求):source 必须只产出一条、id/format 与传入值一致的轨。
 * 我们用 `SubtitleExtractor` + `SubtitleExtractor.TRACK_ID` 构建,天然满足。
 *
 * ## R8 说明(release 是 minify=true)
 *
 * 同包直调在运行期要求「调用方与 media3 的 Factory 同属一个 runtime package(同 classloader + 同包名)」。
 * 若 R8 把本类挪出 media3 包,或把 [enable] 内联进别的包的调用方,包访问会失败 → proguard-rules.pro
 * 对本类加了 `-keep`(钉住包名与方法名),且此处保留反射兜底 —— 真出问题会打错误日志而不是静默失效。
 * 静默失效的后果是字幕轨退回非懒加载 → 重现 P11-72 的「字幕拖死主源」,所以这里必须吵。
 */
@OptIn(UnstableApi::class)
internal object SubtitleLazyLoadingSupport {

  /**
   * 对 [factory] 开启「单轨懒加载」。返回是否成功。
   *
   * **返回 false 时调用方必须放弃这条字幕轨** —— 没有懒加载的 ProgressiveMediaSource 会在 prepare 期
   * 读数据,网络黑洞时拖死整个 MergingMediaSource(P11-72 真机 81s 等头,视频转圈加载不出)。
   */
  fun enable(factory: ProgressiveMediaSource.Factory, trackId: Int, format: Format): Boolean {
    return try {
      // 同包直调(编译期校验方法存在)。
      factory.enableLazyLoadingWithSingleTrack(trackId, format)
      true
    } catch (error: Throwable) {
      // IllegalAccessError / NoSuchMethodError 等链接期错误:退回反射(方法名由 proguard keep 规则保底)。
      Log.w(LogTag, "同包直调失败: ${error.javaClass.simpleName}: ${error.message} → 退回反射")
      enableReflectively(factory, trackId, format)
    }
  }

  private fun enableReflectively(
    factory: ProgressiveMediaSource.Factory,
    trackId: Int,
    format: Format,
  ): Boolean {
    return try {
      val method = ProgressiveMediaSource.Factory::class.java.getDeclaredMethod(
        "enableLazyLoadingWithSingleTrack",
        Int::class.javaPrimitiveType,
        Format::class.java,
      )
      method.isAccessible = true
      method.invoke(factory, trackId, format)
      Log.i(LogTag, "懒加载经反射兜底开启成功")
      true
    } catch (error: Throwable) {
      Log.e(LogTag, "反射兜底也失败: ${error.javaClass.simpleName}: ${error.message}")
      false
    }
  }
}

private const val LogTag = "BiliSubtitle"
