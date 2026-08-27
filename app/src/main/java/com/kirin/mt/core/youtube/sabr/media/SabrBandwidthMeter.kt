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

  /** 由持有 [SabrMediaFetcher] 的一方(DefaultSabrChunkSource)注入真实带宽来源。 */
  fun setRealBandwidthProvider(provider: () -> Long) {
    realBpsProvider = provider
  }

  /** alpha.9Z:注入持续带宽来源(60s 墙钟交付,升档判据用)。 */
  fun setSustainedBandwidthProvider(provider: () -> Long) {
    sustainedBpsProvider = provider
  }

  /** 持续带宽(60s 墙钟);无数据(-1)回退活跃传输 est。 */
  fun getSustainedBitrateEstimate(): Long {
    val sustained = sustainedBpsProvider?.invoke() ?: -1L
    return if (sustained >= 0L) sustained else getBitrateEstimate()
  }

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
