@file:OptIn(UnstableApi::class)

package com.kirin.mt.core.youtube.sabr.media

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
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
 *
 * 2026-08-30(修「Auto 卡 1080p 不升 1440p,手动切正常」,20:45-20:52 4K 源真机案例):升档乘数
 * ×1.25 → ×1.1。门①原防「突发样本撑高 est → 升完必卡」,重锚机制落地后该风险已结构性消除(升完
 * est=声明码率起步,扛不住立刻塌+水位急救兜底);且高码率视频声明值本就虚高(1080p 声明 22M、1440p
 * 41.6M、4K 110M),×1.25 把门槛抬到 52M+ 而活跃 est 滑动均值天花板 ~40M,升档永远批不下来。
 *
 * 2026-08-30(实测码率校准门槛,×1.1 仍卡 720→1080 临界 5-8% 后的根治):连续两轮调乘数都是治标——
 * 声明码率虚高 ~2×(302 声明 11.25M 实测 6.3M)+ est 滑动均值被 gap 样本压低(管道 30-57M 读 18-24M),
 * 双失真下任何乘数都贴脸。全部候选门槛改为 required = declared × calib(当前档实测消耗/声明,clamp
 * [0.35,1],未实测退 1.0=声明行为);乘数取消 ×1.0。降档判据从而落到真实消耗(est 起伏 18-24M 不再
 * 误降),升档候选按同内容系数外推真实需求;升档重锚基准同步改为校准值(锚声明会造成声明虚高源的
 * 连环误升)。已对 20:45-52 与 21:03-05 两份日志做决策级复盘通过(docs §13)。
 *
 * 2026-08-30(修「Auto 升 1440p 后 1s 打回 → 3min 冷却锁死在 1080p」,21:27 真机)两处:
 * ①校准系数死锁修复——media3 Merging/TrackGroup 层给 Format.id 加序号前缀(实证 id="0:302"),
 *   toIntOrNull 解析失败 → calib 恒 1.0,实测门槛整场未生效。改取最后一个冒号后段解析。
 * ②升档后 10s 禁止 est 回降——重锚把 est 精确锚在新档门槛,起步期 0 供给 gap 样本立刻打回旧档,
 *   再被 3min 冷却锁死(真饿由水位急救兜底,不经此禁令)。
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
    // 降到下一个低分辨率档。逐级一步一档:降到可持续档后缓冲回 8s 以上自动停。
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
    // = 过去 60s 墙钟实际交付(SabrMediaFetcher.getSustainedBitrateEstimate),要求 ≥ 校准门槛才许升。
    val sustained = (bandwidthMeter as? SabrBandwidthMeter)?.getSustainedBitrateEstimate() ?: -1L
    // 2026-08-30 实测码率校准门槛(已复盘验证,见 docs §12/§13):声明码率在高码率源上虚高约 2×,
    // 连续两轮 ×1.25/×1.1 乘数都卡在临界。改为用「当前档实测消耗/声明」校准系数(calib)折算全部候选:
    // required = candidateDeclared × calib。对当前档,declared×calib = 实测消耗 → 降档判据落到真实消耗
    // (est 起伏不再每周期误降);对升档候选,按同内容系数外推真实需求。
    // 2026-08-30(calib 成熟度地板,修「升 308 后 3s 决策 4K 门槛被压到 0.35 地板 → 105M 声明的 4K
    // 按 36.9M 误批→漏光缓冲」):刚升入的档只有 3-4 段样本,比值偏低属采样噪声。实测段数 <5 地板
    // 0.65、<12 地板 0.5、≥12 才放 0.35——第一分钟门槛不被新采样拉穿,4K(105M×0.65=68M)当时批不下来。
    val currentItag = itagOf(getFormat(selected))
    val currentDeclared = getFormat(selected).bitrate.toLong()
    val calibPermille = if (currentItag > 0 && currentDeclared > 0) {
      val meter = bandwidthMeter as? SabrBandwidthMeter
      val measured = meter?.getMeasuredBitrateBps(currentItag) ?: -1L
      if (measured > 0L) {
        val segs = meter?.getMeasuredSegmentCount(currentItag) ?: 0L
        val floorPermille = when {
          segs < 5L -> 650L
          segs < 12L -> 500L
          else -> CALIB_MIN_PERMILLE
        }
        (measured * 1000L / currentDeclared).coerceIn(floorPermille, 1000L)
      } else 1000L
    } else 1000L
    // alpha.9Z(升档滞回,防降档后横跳):带宽估计在档位临界值附近抖动时,无滞回会 308↔315 反复切轨
    // (每次切轨都要拉新 init 段,还丢已缓冲的高档数据)。升档要求 ①活跃 est ≥ 校准门槛(乘数 ×1.0,
    // 2026-08-30:防卡职能已由重锚+水位急救结构性承担,乘数在声明虚高的视频上只会卡死爬梯)
    // ②持续带宽 ≥ 校准门槛
    // ③降档后:缓冲 ≥30s 才许升(首次选档 lastDowngrade=0 不受此限,起播爬档不被卡)。
    // 2026-08-30(用户决策):**3min 升档冷却取消**——22:11-22:14 真机:急救降回 1080p 后缓冲已填满
    // 45s、门槛 12.7M 明明可升,却被冷却硬锁到 22:14:57,用户被迫手动切档。横跳防护由「缓冲 ≥30s
    // 才许升 + 升档后 10s 禁止 est 回降 + 重锚基线」承担,不再需要冷却。
    val canUpgrade = lastDowngradeElapsedMs == 0L || bufferedDurationUs >= UPGRADE_MIN_BUFFERED_US
    var best = length - 1
    var bestHeight = -1
    for (i in 0 until length) {
      if (isTrackExcluded(i, nowMs)) continue
      val f = getFormat(i)
      val isUpgrade = f.height > currentHeight
      val required = f.bitrate * calibPermille / 1000L // bitrate 只当带宽门槛,校准底座
      if (required > effective) continue
      if (isUpgrade && (!canUpgrade || (sustained in 0 until required))) continue
      // 选声明码率可负担的最高分辨率档;同 height 多 codec(VP9/H264)按 bitrate 降序遍历先到的码率
      // 最高,`f.height > bestHeight` 严格大于不会替换 → 自然保留高码率变体。
      if (f.height > bestHeight) {
        best = i
        bestHeight = f.height
      }
    }
    // 2026-08-30 升档后 10s 禁止 est 回降(修「升 308 后 1s 内被打回 303 → 3min 冷却锁死」):
    // 重锚把 est 精确锚在新档门槛上,新档 init/段请求期的 0 供给 gap 样本立刻把 est 拽到 required
    // 以下 → 打回旧档,再被 3min 冷却锁死(21:27:31-32 真机,21:29:30 用户手动切 1440 才恢复)。
    // 回降宽限 10s:est 基线若真守不住,10s 内水位急救路径(5s 宽限)会接管,不依赖本禁令。
    if (best != selected &&
      getFormat(best).height < getFormat(selected).height &&
      nowMs - lastUpgradeElapsedMs < UPGRADE_DOWNGRADE_GRACE_MS
    ) {
      best = selected
    }
    selected = best
    when {
      // 降档(height 变小)记时间,驱动升档冷却
      getFormat(selected).height < currentHeight -> lastDowngradeElapsedMs = nowMs
      // 2026-08-30 升档重锚:est 基准重锚到「新档校准码率」(declared×calib,即其预估真实消耗)——
      // 若锚在声明值,声明虚高的源会瞬间显得"还够再下一档"(连环误升);锚在预估真实值则需真实带宽
      // 样本顶上来才允许继续爬(见类头注释②)。
      getFormat(selected).height > currentHeight -> {
        lastUpgradeElapsedMs = nowMs
        val newDeclared = getFormat(selected).bitrate.toLong()
        (bandwidthMeter as? SabrBandwidthMeter)?.reseedToBitrate(newDeclared * calibPermille / 1000L)
        Log.i(
          "YtSabrAbr",
          "upshift reseed: est baseline → ${newDeclared * calibPermille / 1000L} " +
            "(declared=${getFormat(selected).bitrate} calib=${calibPermille / 1000.0} " +
            "segs=${(bandwidthMeter as? SabrBandwidthMeter)?.getMeasuredSegmentCount(itagOf(getFormat(selected))) ?: 0L} " +
            "itag${itagOf(getFormat(selected))})"
        )
      }
    }
  }

  override fun getSelectedIndex(): Int = selected

  /**
   * 2026-08-30:从 media3 Format.id 解析 itag。id 不保证是裸 itag——media3 的 Merging/TrackGroup 层
   * 会给子源格式加序号前缀(真机重锚日志实证 id="0:302",ToInt 直接失败 → 校准系数恒 1.0 死锁)。
   * 取最后一个冒号后段兼容 "0:302"/"302"/null。
   */
  private fun itagOf(f: Format): Int {
    val id = f.id ?: return -1
    return id.substringAfterLast(':', id).toIntOrNull() ?: -1
  }

  private companion object {
    /** alpha.9Z:升档所需的最低缓冲水位(us)——降档自救后缓冲重建到这一水位前,不允许弹回高档。 */
    const val UPGRADE_MIN_BUFFERED_US = 30_000_000L
    /** alpha.9Z:降档后升档所需最低缓冲水位(us)——缓冲重建到这一水位前不允许弹回高档。 */
    const val DOWNGRADE_BUFFERED_US = 8_000_000L
    /** 2026-08-30:升档后的水位急救宽限(ms)——新档刚起步缓冲未回填,不能立刻按同一水位反弹降档。 */
    const val DOWNGRADE_AFTER_UPGRADE_GRACE_MS = 5_000L
    /** 2026-08-30:校准系数下限(permille)——防「低档轻内容高估品种码率」把高档门槛压穿到真饿。 */
    const val CALIB_MIN_PERMILLE = 350L
    /** 2026-08-30:升档后禁止 est 回降宽限(ms)——重锚精确锚在新档门槛,起步期小样本会立刻打回。 */
    const val UPGRADE_DOWNGRADE_GRACE_MS = 10_000L
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
