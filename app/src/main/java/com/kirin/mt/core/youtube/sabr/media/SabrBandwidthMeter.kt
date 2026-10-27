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

  /**
   * P11-282:构造时的初始 seed(PlayerScreen/MobilePlayerScreen 传 `youtubeStartQuality.seedBps()`)。
   * SABR 会话在拿到第一笔真实样本前用它,而不是回退 delegate —— 见 [getBitrateEstimate]。
   */
  private val initialSeedBps: Long = delegate.getBitrateEstimate()

  /**
   * P11-282:最后一次**有效**的真实带宽(bps),-1 = 尚未测到。
   *
   * 为什么需要它:P11-167 定下「窗口零字节 → 返回 -1」,P11-259 又证实 delegate 会被
   * `SabrDataSource` 的内存瞬时读样本污染且跨视频不重置。两者相遇就是真机 r2249 的病象:
   * 连续 8s 超时 → real=-1 → 回退 delegate → **est=1307182K(1.3Gbps)** → ABR 不降档 → 缓冲漏光 stall。
   * 这里保留最后一次真实样本:既不像 0 那样把「证据不足」当成「带宽为零」(P11-167 的 144p),
   * 也不像 delegate 那样给出污染高估。
   */
  @Volatile
  private var lastRealBps: Long = -1L

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

  /**
   * P11-286:最后**真正交付过视频 media** 的 itag 来源。ABR 的升档 settle 用它判「当前选中档
   * 是否已经供上数据」——没供上就不许继续爬下一档(真机 alpha.5 起播 480→720→1080 连跳)。
   */
  @Volatile
  private var lastDeliveredVideoItagProvider: (() -> Int)? = null

  fun setLastDeliveredVideoItagProvider(provider: () -> Int) {
    lastDeliveredVideoItagProvider = provider
  }

  /** -2 = 未接线; -1 = 尚无视频媒体交付; >=0 = itag。未接线时调用方按"不拦"处理。 */
  fun getLastDeliveredVideoItag(): Int = lastDeliveredVideoItagProvider?.invoke() ?: -2

  /** alpha.9Z:注入持续带宽来源(60s 墙钟交付,升档判据用)。 */
  fun setSustainedBandwidthProvider(provider: () -> Long) {
    sustainedBpsProvider = provider
  }

  /**
   * P11-258:注入持续带宽的**裸值**来源(不扣需求空闲),仅供日志取证 —— 与 [getSustainedBitrateEstimateRaw]
   * 并排打印,才能用真机判断分母扣减是否落在窗口内(见 SabrMediaFetcher.getSustainedRawBitrateEstimate)。
   */
  @Volatile
  private var sustainedRawBpsProvider: (() -> Long)? = null

  fun setSustainedRawBandwidthProvider(provider: () -> Long) {
    sustainedRawBpsProvider = provider
  }

  /** 持续带宽裸值(bps);未接线/证据不足返回 -1。仅诊断,不参与判决。 */
  fun getSustainedBitrateEstimateRawUndeducted(): Long = sustainedRawBpsProvider?.invoke() ?: -1L

  /** 2026-08-30:接线升档重锚执行方。 */
  fun setReseedBandwidthProvider(provider: (Long) -> Unit) {
    reseedBandwidthProvider = provider
  }

  /** 2026-09-01 重填容量来源(SabrMediaFetcher.getRefillCapacityBps,免疫墙钟空转的管道实测)。 */
  @Volatile
  private var refillCapacityProvider: (() -> Long)? = null

  fun setRefillCapacityProvider(provider: () -> Long) {
    refillCapacityProvider = provider
  }

  /** 重填容量(bps);未接线/样本不足 -1,调用方回退活跃 est。仅供升档判据。 */
  fun getRefillCapacityEstimate(): Long = refillCapacityProvider?.invoke() ?: -1L

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
   * P11-178:注入「本 itag 最近一次零字节挂死」的墙钟来源(SabrMediaFetcher.getLastSilenceHangWallMs)。
   * 与实测吞吐走同一条既有通道(选择类拿不到 fetcher)——挂死证据要让水位急救在「挂死后重试成功」的
   * 那一刻仍然成立,不能被重试那笔成功样本抹平,详见 HeightAwareAdaptiveTrackSelection 的证据注释。
   */
  @Volatile
  private var silenceHangProvider: ((Int) -> Long)? = null

  fun setSilenceHangProvider(provider: (Int) -> Long) {
    silenceHangProvider = provider
  }

  /** 该 itag 最近一次零字节挂死的墙钟(epoch ms);未接线/从未挂死返回 0。 */
  fun getLastSilenceHangWallMs(itag: Int): Long = silenceHangProvider?.invoke(itag) ?: 0L

  /**
   * P11-203:注入「本 itag 最近一次请求往返耗时」来源(SabrMediaFetcher.getLastRoundTripMs)。
   * 与实测吞吐/silenceHang 走同一条既有通道(选择类拿不到 fetcher)——带宽闸据此判「平均带宽够 ≠
   * 供得上」,见 HeightAwareAdaptiveTrackSelection 的第四腿注释。
   */
  @Volatile
  private var roundTripProvider: ((Int) -> Long)? = null

  fun setRoundTripProvider(provider: (Int) -> Long) {
    roundTripProvider = provider
  }

  /** 该 itag 最近一次往返耗时(ms);未接线/无样本/样本过期返回 -1。 */
  fun getLastRoundTripMs(itag: Int): Long = roundTripProvider?.invoke(itag) ?: -1L

  /**
   * P11-259:换源清场。上面**全部** provider 都是**会话级**的 —— 每一个都指向某一场 SABR 会话的
   * [SabrMediaFetcher],由 [DefaultSabrChunkSource] 接线,**拆线这一侧从来没人做**。
   *
   * 病象(2026-10-15 TV 真机 `logs_live_20261015_205216.log`):同一个 player 先播 YouTube SABR
   * (20:49:29 收尾 `bw=8800K sus=8357K`),**不退出播放器**接着播 B 站 DASH
   * `BV1QMJ16AEWJ`(20:50:22)。B 站是 http(s) 源、根本不经过 SABR chunk source ⇒ 没有任何人
   * 重新接线,`realBpsProvider` 仍指着上一场那个已结束的 fetcher;窗口过期后它返回 -1
   * (`SabrMediaFetcher.getRealBitrateEstimate`),于是 [getBitrateEstimate] 回落到 delegate,
   * ABR 读到 `est=1270K`,而当前档实需 1469K ⇒ **每轮评估都判「买不起」**。
   *
   * 后果不是"判低一点"这么轻:B 站梯子只有**一档两种编码**(见 `PlaybackRepository` 的
   * `selectedQualityTracks`),按 height 的降档判据恒空 ⇒ 唯一还能"降"的动作变成**换 codec**
   * ⇒ 每 ~5s 拆建一次 MTK 解码器(真机 20:51:39~20:51:54 换 4 次 AVC↔HEVC),视频轨挂死后
   * **音频一起停、黑屏**,用户只能手动退出;而退出重进 = 新 player = 干净计 ⇒ "重载一次就正常"。
   *
   * 故:**非 SABR 源加载前必须清**。SABR 源不必清(会话由 `DefaultSabrChunkSource` 当场重新接线)。
   */
  fun clearSessionProviders() {
    // P11-282:换源后 lastRealBps 也失效;下一次 SABR 会话从 seed 重新起步。
    lastRealBps = -1L
    realBpsProvider = null
    lastDeliveredVideoItagProvider = null
    sustainedBpsProvider = null
    sustainedRawBpsProvider = null
    reseedBandwidthProvider = null
    measuredBitrateProvider = null
    measuredSegCountProvider = null
    silenceHangProvider = null
    refillCapacityProvider = null
    roundTripProvider = null
  }

  /**
   * P11-259 诊断:[getBitrateEstimate] 的两个来源并排打出来 —— 这是区分两种竞争解释的唯一手段:
   * ①`wired=true` 且 `real` 与 [getBitrateEstimate] 同值 ⇒ **provider 污染**(本类要修的那条);
   * ②`real=unwired`(或 -1)且 `delegate` 就是那个低值 ⇒ 底层计真的只测到这么点(链路/CDN 节点慢,
   * 不是本类的问题)。只读,不改任何状态。**不打印任何 URL/token。**
   */
  fun diagEstimateSources(): String {
    val real = realBpsProvider?.invoke()
    // P11-167 的教训:整数除法会把 -1 打成 0K,把「证据不足」误读成「带宽为零」。
    val realText = when {
      real == null -> "unwired"
      real < 0L -> "-1(证据不足)"
      else -> "${real / 1000}K"
    }
    return "est=${getBitrateEstimate() / 1000}K real=$realText " +
      "last=${if (lastRealBps >= 0L) "${lastRealBps / 1000}K" else "-1"} " +
      "delegate=${delegate.getBitrateEstimate() / 1000}K wired=${realBpsProvider != null}"
  }

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
    // 非 SABR 源(provider 已拆线):沿用 media3 默认计,行为不变。
    val provider = realBpsProvider ?: return delegate.getBitrateEstimate()
    // alpha.9Z:real=0(窗口内全是被迫空转,供给归零)也是有效判定,不再回退 delegate。
    val real = provider()
    if (real >= 0L) {
      lastRealBps = real
      return real
    }
    // ── P11-282:real=-1(窗口内零字节,如连续 8s 超时)────────────────────────────────────
    // 不能回退 delegate:它被 SabrDataSource 内存读样本污染过,真机 r2249 出现 est=1307182K
    // (1.3Gbps),ABR 因此不降档、缓冲漏光 → stall(P11-167 当时以为 delegate 免疫空转,
    // P11-259 已证实并非如此,但只给非 SABR 源拆了线)。
    // 也不能返回 0:那会把「证据不足」当「带宽为零」,直接砸最低档(P11-167 的 144p 病)。
    // 取最后一次有效真实值;会话内还没测到过就用构造 seed(≈起播画质),都比污染值可靠。
    if (lastRealBps >= 0L) return lastRealBps
    return initialSeedBps
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
