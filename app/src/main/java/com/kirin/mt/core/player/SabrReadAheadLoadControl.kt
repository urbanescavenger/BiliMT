package com.kirin.mt.core.player

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * P11-197:给播放套一层「按实测往返耗时抬高的续拉门槛」。
 *
 * ── 为什么 ────────────────────────────────────────────────────────────────────────────────
 * media3 `DefaultLoadControl.shouldContinueLoading` 的语义是「`bufferedDurationUs < minBufferUs`
 * 才继续取段」——即一次响应到手(缓冲 25~50s)后 loader **停止取段**,一直等到缓冲掉到 `MinBufferMs`
 * 才回来要下一段。我们把 `MinBufferMs` 按 alpha.58 的 paced 意图设成 **10s**,而 SABR 单笔往返实测
 * **7.5~12s**(真机 `logs_live_20261005_203023.log`:70MB/7.5s;另一场 `timeout (fail=12010ms)`)
 * ⇒ 请求发出时缓冲只剩 ~6~10s,往返一抖就归零 → 看门狗 stall → 整场重载。P11-197 的诊断字段
 * (`sincePrevRespMs` / `bufAheadMs`)把它钉死了:每次发请求都恰好踩在 `bufAheadMs≈9992~9994` 上。
 *
 * ── 怎么改 ────────────────────────────────────────────────────────────────────────────────
 * 只拦一件事:当 [readAheadMinBufferUs] 给出的门槛(由最近一次 SABR 往返折算,见
 * `SabrAbrMemory.readAheadMinBufferUs`)大于 media3 内置 10s、且当前缓冲还没到该门槛时,**继续取段**;
 * 其余一切(起播门槛、分配器、停止取段的判定)原样交给被包装的 [delegate]。
 *
 * 只影响「什么时候去要下一段」,不动任何 ABR 档位判据;没有 SABR 播放(纯 B 站/本地/离线)时
 * provider 返回 0 ⇒ 整层退化成原生 DefaultLoadControl。
 */
internal class SabrReadAheadLoadControl(
  private val delegate: LoadControl,
  private val readAheadMinBufferUs: () -> Long,
  /**
   * P11-230:**本档的缓冲上限**(us,0 = 不限)—— 到了就停止续拉。
   *
   * 为什么需要:media3 的 `MaxBufferMs` 是**用户设置**(默认 50s),而 4K 的样本缓冲
   * (media3 `Allocator`,Java 数组)按 `50s × 28.6Mbps ≈ 180MB` 算 —— TV 盒子(`largeHeap` 上限 448MB)
   * 上实测 `0% free, 448MB/448MB`,GC 暂停 **26~29ms**(60fps 帧预算只有 16.7ms)⇒ 渲染被打穿 ⇒
   * 位置冻结 ⇒ 看门狗整场重载(真机 `logs_live_20261010_000859.log`,BRAVIA AE2)。
   * 其他播放器(Kodi/MX/SmartTube)不受这个影响,是因为它们的媒体缓冲在 **native**;
   * 而 SABR 的解析/段缓存/样本队列**三份都在 Java 堆**,所以只能靠"别堆那么多"。
   *
   * **只收上限、不动下限**:`readAheadMinBufferUs`(P11-197 续拉门槛)照旧;两者的关系是
   * 「上限优先」—— 缓冲到上限就停拉,漏到下限以下再续拉(实测 4K 往返 9.2s ⇒ 门槛 ~17s < 上限 20s,自洽)。
   * 非 SABR / 无样本 ⇒ provider 返回 0 ⇒ 整层退化成原生行为。
   */
  private val bufferedCapUs: () -> Long = { 0L },
) : LoadControl {

  override fun onPrepared(playerId: PlayerId) {
    delegate.onPrepared(playerId)
  }

  override fun onTracksSelected(
    parameters: LoadControl.Parameters,
    trackGroups: TrackGroupArray,
    trackSelections: Array<ExoTrackSelection?>,
  ) {
    delegate.onTracksSelected(parameters, trackGroups, trackSelections)
  }

  override fun onStopped(playerId: PlayerId) {
    delegate.onStopped(playerId)
  }

  override fun onReleased(playerId: PlayerId) {
    delegate.onReleased(playerId)
  }

  override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

  override fun getBackBufferDurationUs(playerId: PlayerId): Long =
    delegate.getBackBufferDurationUs(playerId)

  override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
    delegate.retainBackBufferFromKeyframe(playerId)

  override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
    delegate.shouldStartPlayback(parameters)

  override fun shouldContinuePreloading(
    playerId: PlayerId,
    timeline: Timeline,
    mediaPeriodId: MediaSource.MediaPeriodId,
    bufferedDurationUs: Long,
  ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

  override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
    // P11-230:上限优先 —— 到本档上限就停止续拉(把 Java 堆里的样本量压住)。
    val capUs = bufferedCapUs()
    if (capUs > 0L && parameters.bufferedDurationUs >= capUs) return false
    val minBufferUs = readAheadMinBufferUs()
    if (minBufferUs > 0L && parameters.bufferedDurationUs < minBufferUs) return true
    return delegate.shouldContinueLoading(parameters)
  }
}
