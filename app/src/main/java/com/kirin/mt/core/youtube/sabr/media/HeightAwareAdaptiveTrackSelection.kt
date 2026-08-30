@file:OptIn(UnstableApi::class)

package com.kirin.mt.core.youtube.sabr.media

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList

/**
 * alpha.9Y(分辨率优先选档,根治「1080p 不再升」):media3 `AdaptiveTrackSelection` 按 **bitrate 降序**
 * 排序 + `determineIdealSelectedIndex` 返回第一个 `bitrate <= effectiveBitrate` 的轨(bitrate 兼当
 * 「画质顺序」和「带宽门槛」)。但 YouTube 声明的 bitrate 与分辨率错位(如 itag308 1440p 声明 13.9M <
 * itag303 1080p 声明 14.4M),media3 以为 1080p 是更高级的档 → 带宽充足也停在 1080p 不升 1440p。
 *
 * 本类让 **height 当画质顺序**、bitrate 只当带宽门槛:带宽够就选声明码率可负担的**最高分辨率**档,
 * 不被 bitrate/height 错位卡住。手动选档菜单本来就按 height 排(见 YoutubePlaybackResolver),这里把
 * Auto 选档策略对齐它。
 *
 * media3 1.10.0 `AdaptiveTrackSelection.determineIdealSelectedIndex`/`getAllocatedBandwidth`/`selectedIndex`
 * 全是 private 不可复用,但 `updateSelectedTrack`(public)与 `getSelectedIndex()`(public)可 override:
 * 子类自算 effective、按 height 选档、自维护索引字段并 override `getSelectedIndex()` 写回。
 *
 * 2026-08-30(修「没降档→缓冲漏光→看门狗整段重载」,20:17-20:19 4K 真机案例):两条新增
 * ①**水位急救降档**——降档原唯一触发条件 est < 声明码率,est 是 20s 突发混合均值,重填期 60-70M 突发
 *   样本滞留窗口,供给不足时 est 迟迟跌不过当前档码率(实际吞吐 27M vs 4K 声明 26.6M 贴地滑行),缓冲
 *   36s→4s 全程不降档。缓冲水位是比 est 更硬的供给证据:<8s 且仍在下漏 → 无视 est 直接降一档。
 * ②**升档重锚**——升入新档瞬间把活跃 est 窗口重锚到新档声明码率(SabrMediaFetcher.reseedActiveWindow),
 *   旧档/重填期的突发高估样本从新档码率起步平滑失效,新档扛不住时 est 快速下探、降档不再迟钝。
 */
class HeightAwareAdaptiveTrackSelection(
  group: TrackGroup,
  tracks: IntArray,
  private val bandwidthMeter: BandwidthMeter,
) : AdaptiveTrackSelection(group, tracks, bandwidthMeter) {

  private var selected = length - 1

  /** alpha.9Z:上次降档时间(elapsedRealtime ms)——升档冷却基准。 */
  private var lastDowngradeElapsedMs = 0L

  /** 2026-08-30:上次升档时间(elapsedRealtime ms)——水位急救降档的宽限基准,0=从未升过。 */
  private var lastUpgradeElapsedMs = 0L

  /** 2026-08-30:上次评估的缓冲水位(us),首次评估为 -1——判定「水位仍在下漏」。 */
  private var prevEvalBufferedUs = -1L

  override fun updateSelectedTrack(
    playbackPositionUs: Long,
    bufferedDurationUs: Long,
    availableDurationUs: Long,
    queue: MutableList<out MediaChunk>,
    mediaChunkIterators: Array<MediaChunkIterator>,
  ) {
    val nowMs = SystemClock.elapsedRealtime()
    // 无分辨率信息的组(音频等)没有 height 语义 → 退化交给父类按码率选档。
    if ((0 until length).none { getFormat(it).height > 0 }) {
      super.updateSelectedTrack(
        playbackPositionUs, bufferedDurationUs, availableDurationUs, queue, mediaChunkIterators,
      )
      selected = super.getSelectedIndex()
      return
    }
    // 2026-08-30 水位急救降档:水位 <8s 且两次评估间仍在回落/持平(排除起播/重填期的短暂低点,那时水位
    // 在涨)、且过了升档宽限 → 水位下降本身就是最好的降档证据(供给持续低于当前档消耗),无视 est 直接
    // 降到下一个低分辨率档。逐级一步一档:降到可持续档后缓冲回 8s 以上自动停;配合现有 3min 升档冷却防空跳。
    val currentHeight = getFormat(selected).height
    val bufferCritical = bufferedDurationUs < DOWNGRADE_BUFFERED_US &&
      bufferedDurationUs <= prevEvalBufferedUs &&
      nowMs - lastUpgradeElapsedMs >= DOWNGRADE_AFTER_UPGRADE_GRACE_MS
    prevEvalBufferedUs = bufferedDurationUs
    if (bufferCritical) {
      var lower = -1
      var lowerHeight = -1
      for (i in 0 until length) {
        if (isTrackExcluded(i, nowMs)) continue
        val f = getFormat(i)
        if (f.height in 1 until currentHeight && f.height > lowerHeight) {
          lower = i
          lowerHeight = f.height
        }
      }
      if (lower >= 0) {
        val current = getFormat(selected)
        Log.i(
          "YtSabrAbr",
          "buffer-critical downgrade: bufS=${bufferedDurationUs / 1_000_000}s " +
            "itag${current.id}/${current.height}p@${current.bitrate} → " +
            "${getFormat(lower).height}p@${getFormat(lower).bitrate}"
        )
        selected = lower
        lastDowngradeElapsedMs = nowMs
        return
      }
    }
    // 带宽门槛:活跃传输 est(滑动窗口,含 gap/慢小样本)管「当前扛不扛得住」——降档用它,反应快。
    val effective = bandwidthMeter.getBitrateEstimate()
    // alpha.9Z(升档用持续带宽):突发速率 est 在重填缓冲期间会冲到 40-70M(2026-08-27 真机:一笔
    // 74Mbps 突发把 est 从 16M 抬到 40M 过 4K 门槛 → 升完必卡,pacing 有效供给只有 16-20M)。持续带宽
    // = 过去 60s 墙钟实际交付(SabrMediaFetcher.getSustainedBitrateEstimate),要求 ≥ 声明码率才许升。
    val sustained = (bandwidthMeter as? SabrBandwidthMeter)?.getSustainedBitrateEstimate() ?: -1L
    // alpha.9Z(升档滞回,防降档后横跳):带宽估计在档位临界值附近抖动时,无滞回会 308↔315 反复切轨
    // (每次切轨都要拉新 init 段,还丢已缓冲的高档数据)。升档要求 ①活跃 est ≥ 声明码率 ×1.25(乘数
    // 恒生效,不再随缓冲条件开关——旧实现缓冲<30s 时门槛反而更低,方向倒挂) ②持续带宽 ≥ 声明码率
    // ③降档后:缓冲 ≥30s 且距上次降档 ≥3min(首次选档 lastDowngrade=0 不受 30s 限制,起播爬档不被卡;
    // 网络真改善时最多晚 3min 升档;手动选档走单轨组不经此路,不受影响)。
    val canUpgrade = (lastDowngradeElapsedMs == 0L || bufferedDurationUs >= UPGRADE_MIN_BUFFERED_US) &&
      nowMs - lastDowngradeElapsedMs >= UPGRADE_COOLDOWN_MS
    var best = length - 1
    var bestHeight = -1
    for (i in 0 until length) {
      if (isTrackExcluded(i, nowMs)) continue
      val f = getFormat(i)
      val isUpgrade = f.height > currentHeight
      val required = if (isUpgrade) f.bitrate * 5L / 4L else f.bitrate.toLong()
      if (required > effective) continue // bitrate 只当带宽门槛
      if (isUpgrade && (!canUpgrade || (sustained in 0 until f.bitrate))) continue
      // 选声明码率可负担的最高分辨率档;同 height 多 codec(VP9/H264)按 bitrate 降序遍历先到的码率
      // 最高,`f.height > bestHeight` 严格大于不会替换 → 自然保留高码率变体。
      if (f.height > bestHeight) {
        best = i
        bestHeight = f.height
      }
    }
    selected = best
    when {
      // 降档(height 变小)记时间,驱动升档冷却
      getFormat(selected).height < currentHeight -> lastDowngradeElapsedMs = nowMs
      // 2026-08-30 升档重锚:新档声明码率作为 est 新基准,旧档突发样本立即失效(见类头注释②)
      getFormat(selected).height > currentHeight -> {
        lastUpgradeElapsedMs = nowMs
        (bandwidthMeter as? SabrBandwidthMeter)?.reseedToBitrate(getFormat(selected).bitrate.toLong())
        Log.i(
          "YtSabrAbr",
          "upshift reseed: est baseline → ${getFormat(selected).bitrate} (itag${getFormat(selected).id})"
        )
      }
    }
  }

  override fun getSelectedIndex(): Int = selected

  private companion object {
    /** alpha.9Z:升档所需的最低缓冲水位(us)——降档自救后缓冲重建到这一水位前,不允许弹回高档。 */
    const val UPGRADE_MIN_BUFFERED_US = 30_000_000L
    /** alpha.9Z:降档后升档冷却(ms)——打破「降档→重填→est 冲高→弹回高档→卡死」横跳循环。 */
    const val UPGRADE_COOLDOWN_MS = 180_000L
    /** 2026-08-30:水位急救降档阈值(us)——LoadControl max buffer ≥30s、8s 已低于 MinBuffer(10s),只有真供给不足才摸得到。 */
    const val DOWNGRADE_BUFFERED_US = 8_000_000L
    /** 2026-08-30:升档后的水位急救宽限(ms)——新档刚起步缓冲未回填,不能立刻按同一水位反弹降档。 */
    const val DOWNGRADE_AFTER_UPGRADE_GRACE_MS = 5_000L
  }

  override fun getSelectionReason(): Int = androidx.media3.common.C.SELECTION_REASON_ADAPTIVE

  override fun getSelectionData(): Any? = null
}

/**
 * 注入 DefaultTrackSelector 的 factory:混合 mime 视频组(DefaultSabrChunkSource 建的 5 轨自适应组)
 * 走 [createAdaptiveTrackSelection] 返回按 height 选档的 selection;单轨组仍由父类 Factory 返回
 * FixedTrackSelection;音频等无 height 组由 selection 内部退化给父类码率选档。
 */
class HeightAwareAdaptiveTrackSelectionFactory : AdaptiveTrackSelection.Factory() {

  override fun createAdaptiveTrackSelection(
    group: TrackGroup,
    tracks: IntArray,
    type: Int,
    bandwidthMeter: BandwidthMeter,
    adaptationCheckpoints: ImmutableList<AdaptiveTrackSelection.AdaptationCheckpoint>,
  ): AdaptiveTrackSelection = HeightAwareAdaptiveTrackSelection(group, tracks, bandwidthMeter)
}
