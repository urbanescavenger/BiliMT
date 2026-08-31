package com.kirin.mt.core.youtube.sabr.media

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * alpha.9X(带宽驱动选档,替代 exclude/force 补丁):媒体3 DefaultBandwidthMeter 被 SabrDataSource 喂的
 * 内存瞬时读样本污染(bw= 真机 1M↔437M 1000 倍跳变),effectiveBitrate 不可信 → AdaptiveTrackSelection 升档
 * 判据失真。本类是 [BandwidthMeter] 包装:一切委托给底层 [DefaultBandwidthMeter](保留 TransferListener 传递、
 * 初始带宽 seed、事件分发),仅覆盖 [getBitrateEstimate] 返回 SabrMediaFetcher 从实际段下载测的真实带宽
 * (滑动窗口累计量/累计耗时,含失败段计时,断流时自动下探)。真实带宽充足时 media3 原生 ABR 自然选最高可负担档、升降档全自动,不再需要
 * force-climb/exclude 补丁。
 *
 * 真实带宽提供者由 [DefaultSabrChunkSource] 在拿到 fetcher 后注入(`setRealBandwidthProvider`);未提供或
 * 尚无真实样本(返回 <=0)时回退底层估计(含初始 seed)。
 */
@OptIn(UnstableApi::class)
internal class SabrBandwidthMeter(
  private val delegate: DefaultBandwidthMeter,
) : BandwidthMeter {

  @Volatile
  private var realBpsProvider: (() -> Long)? = null

  @Volatile
  private var sustainedBpsProvider: (() -> Long)? = null

  /** 2026-08-30:注入升档重锚执行方(SabrMediaFetcher.reseedActiveWindow)。 */
  @Volatile
  private var reseedBandwidthProvider: ((Long) -> Unit)? = null

  /** 2026-08-30:注入实测消耗码率来源(SabrMediaFetcher.getMeasuredBitrateBps,降/升档门槛校准底座)。 */
  @Volatile
  private var measuredBitrateProvider: ((Int) -> Long)? = null

  /** 由持有 [SabrMediaFetcher] 的一方(DefaultSabrChunkSource)注入真实带宽来源。 */
  fun setRealBandwidthProvider(provider: () -> Long) {
    realBpsProvider = provider
  }

  /** alpha.9Z:注入持续带宽来源(60s 墙钟交付,升档判据用)。 */
  fun setSustainedBandwidthProvider(provider: () -> Long) {
    sustainedBpsProvider = provider
  }

  /** 2026-08-30:接线升档重锚执行方。 */
  fun setReseedBandwidthProvider(provider: (Long) -> Unit) {
    reseedBandwidthProvider = provider
  }

  /** 2026-08-30:接线实测码率来源。 */
  fun setMeasuredBitrateProvider(provider: (Int) -> Long) {
    measuredBitrateProvider = provider
  }

  /** 2026-08-30:实测消耗码率;未接线/证据不足时 -1(调用方回退声明值)。 */
  fun getMeasuredBitrateBps(itag: Int): Long = measuredBitrateProvider?.invoke(itag) ?: -1L

  /** 2026-08-30:实测已挂账段数(calib 成熟度地板用);未接线时 0。 */
  @Volatile
  private var measuredSegCountProvider: ((Int) -> Long)? = null

  fun setMeasuredSegCountProvider(provider: (Int) -> Long) {
    measuredSegCountProvider = provider
  }

  fun getMeasuredSegmentCount(itag: Int): Long = measuredSegCountProvider?.invoke(itag) ?: 0L

  /**
   * 2026-08-30 升档重锚:升入新档后把活跃 est 窗口重锚到该档声明码率——原窗口里旧档/重填期的突发高估
   * 样本(60-70M)会顶住降档门槛,新档扛不住时 est 迟迟跌不过声明码率,缓冲漏光前不降档只能看门狗重载。
   * 重锚后 est 从声明码率起步、真实样本平滑接管。委托给 fetcher(窗口在它那),未接线时静默忽略。
   */
  fun reseedToBitrate(bitrateBps: Long) {
    if (bitrateBps > 0L) reseedBandwidthProvider?.invoke(bitrateBps)
  }

  /** 持续带宽(60s 墙钟);无数据(-1)回退活跃传输 est。 */
  fun getSustainedBitrateEstimate(): Long {
    val sustained = sustainedBpsProvider?.invoke() ?: -1L
    return if (sustained >= 0L) sustained else getBitrateEstimate()
  }

  /**
   * 2026-08-31(冷启动防直跳顶档):持续带宽**原值**,证据不足(<10s 跨度,见 SabrMediaFetcher.
   * getSustainedBitrateEstimate)返回 -1 且**不回退**活跃 est——旧 getSustainedBitrateEstimate 在
   * 冷启动(刚起播/重载后)回退到被 1-2 笔爆发样本撑高的活跃 est(真机 20:04:2 个 720p 段
   * 爆发 33-58M 直接过 4K 门槛),顶档的 sustained ×1.1 闸门在冷启动期被整体废掉。ABR 升档门槛改用
   * 本方法:-1 恒小于顶档门槛 → 冷启动 4K 自动被挡;非顶档梯子不受 sustained 约束(逐级爬+est 门
   * 兜底)。日志/降档仍可用带回退的旧方法。
   */
  fun getSustainedBitrateEstimateRaw(): Long = sustainedBpsProvider?.invoke() ?: -1L

  override fun getBitrateEstimate(): Long {
    // alpha.9Z:real=0(窗口内全是被迫空转,供给归零)也是有效判定——回落 delegate 会用传输期高估
    // 把选轨器弹回高档,正好复现「卡死不降档」。仅 -1(尚无任何样本)才回退底层估计。
    val real = realBpsProvider?.invoke() ?: -1L
    return if (real >= 0L) real else delegate.getBitrateEstimate()
  }

  override fun getTimeToFirstByteEstimateUs(): Long = delegate.getTimeToFirstByteEstimateUs()

  override fun getTransferListener(): TransferListener? = delegate.getTransferListener()

  override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) {
    delegate.addEventListener(eventHandler, eventListener)
  }

  override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
    delegate.removeEventListener(eventListener)
  }
}
