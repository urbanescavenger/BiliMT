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
 *
 * 2026-08-30(声明码率口径修正:peak → averageBitrate,23:00 4K 失败真机复盘):ABR 一直拿
 * /player 的 `bitrate` 当"声明码率",但该字段是 **VBR 峰值**(比真实平均高 ~60-75%);真实平均是
 * `averageBitrate`(≈ contentLength/duration)。本视频 itag315 声明 32.3M(peak)而实测消耗 ~23.4M,
 * 两轮失败都源于在失真口径上叠校准:calib 从低档 peak 比外推高档(0.779),顶档 0.6 gate(0.6×32.3M=
 * 19.4M)在 sus 28-34M 时放行 4K——网络先真给 60s 高吞吐再塌,门槛全部"合法通过"。已由
 * YoutubePlaybackResolver 全链路把 declared 换成 averageBitrate(WEB 原生字段 / NewPipe 自算,
 * 见 buildSabrTrack)。口径修正后本类同步简化(用户决策):
 * ①**calib 机制整体取消**——declared=真实平均后 required=f.bitrate 即实需,采样折算(含成熟度地板
 *   0.65/0.5/0.35)失去意义,删除;成熟期 calib 本就收敛 ≈1,取消等价,只去掉未熟期折算噪声。
 * ②**顶档门槛保留、基准重标 ×1.1**——旧 0.6 是对 peak 虚高的修正(0.6×peak≈1.2×real);declared
 *   已是真平均后,门槛直接 sustained ≥ declared×1.1(1.1=60s 持续均值余量,防 VBR 尖峰打穿)。
 *   本视频:315 声明 32.3M→~23M,顶档门槛 19.4M→~25.3M,夜间塌方段(sus 8M)永不批准。
 * 其余机制(升档重锚、水位急救、升档冷却、10s 禁回降、逐步候选升降)语义不变,锚点改裸声明。
 *
 * 2026-08-30(顶档定向冷却,修「4K 边缘档反复横跳」,23:28-23:31 真机):供给(27-42M)贴着 4K
 * 真实消耗(~30M)与声明门槛(32.3M+顶档×1.1=35.5M)边缘,重填期突发 est/sus 过门槛升 4K →
 * 边播边吸 pacing 供给 ~30M → buffer 漏到 5-6s → 水位急救级联(315→308→299)→ 低档重填 →
 * 又过门槛升 4K:3.5 分钟两轮循环,每次级联切档都卡一次。修法:水位急救从**顶档**(height≥2160)
 * 降下时,仅把该顶档 excludeTrack 3 分钟——期间 1440p/1080p 升降完全照常(与已取消的「全档 3min
 * 冷却」不同,那锁死全部升降、用户被困低档;本冷却只锁顶档,不再横跳,网络真正好转由冷却自然到期
 * 或手动切档兜底)。非顶档的水位降档不加冷却(降的是可持续档,回弹无碍)。
 *
 * 2026-08-31(降档滞回余量 ×0.85,修「1440p↔1080p 临界来回切」,00:01-00:04 真机):declared=真平均后,
 * est 巡航值正好骑在相邻档门槛上下(本视频 1440p 声明 16.76M,est 15.8-18M)——旧判据 `required > est`
 * 一穿线即降(est 15.8M vs 门槛 16.76M,差 6% 就切),缓冲 ≥30s 升档冷却随即放行,est 又过线再升,
 * 每次切换拉 init 段=一次卡顿,3 分钟切了三轮。修法:**当前档(i==selected)降档判据放宽到
 * required×0.85**(15% 死区:est 15.8M > 14.25M 稳守 1440p),升档候选门槛不变(est ≥ 全额 required
 * + sustained gate)→ 双阈值滞回带。真饿(est < required×0.85)照样降,水位急救(<8s)是最后兜底
 * ——临界抖动归滞回,真饿归水位,分工不变。降档候选档(切出去的穿透验证)保持全额 required 不放宽。
 *
 * 2026-08-31(冷启动防直跳顶档 + stall 重载记忆,修「起播 4K→重载 死循环」,20:04 真机复盘):真机
 * 起播剧本:720p 首帧后 34ms 松开起始高度 cap → ABR 首评,活跃 est 已被 2 个 720p 段爆发样本撑到
 * 33-58M,sustained 因证据 <15s 返回 -1 且**被 getSustainedBitrateEstimate 回退成同一个爆发 est**
 * → 顶档门槛(26.9M×1.1)「合法通过」→ 720p 一步直跳 2160p + reseed 锚死 → 切轨发生在 pos=0 首帧前
 * → loader 停发 chunk 永不 READY → 看门狗整链路重载 → counter reset 后同样误判再来 → ~16s 无限循环。
 * 两处根治(①为 20:28 真机首验后的修订版):
 * ①**顶档需持续带宽原值证据 + 全档逐级爬**——sustained 改用原值(getSustainedBitrateEstimateRaw,
 *   -1=无证据**不回退**),顶档 ×1.1 闸读它,-1 恒小于门槛 → 冷启动 4K 自动被挡,不需要「无证据一律
 *   禁升」(首版曾那样做,20:28 真机:非顶档梯子也被冻,首灌窗口 ~11s 错过后满缓冲期零评估,首次升档
 *   拖到 53s、1440p 拖到 115s,用户报「升档要一分钟」)。非顶档冷启动照常爬:活跃 est 门 +
 *   **逐级爬**(只升下一个更高分辨率档,同 height 多 codec 变体不受限),爆发 est 误判的最坏后果
 *   从「直跳 4K」降为「多升一档」,真扛不住由滞回/水位急救接管。冷启动升档**跳过重锚**(锚点会把 est
 *   压在新档声明值拖死下一步爬升);证据成熟后的稳态升档保持重锚语义。降档仍放开全部低档一步落位
 *   (2026-08-27 既有语义,真饿要快)。
 * ③**冷启动锁档 10s**(20:45 真机续播死循环后补)——selection 实例创建(≈cap 释放重建、样本队列
 *   整丢)后 10s 内禁升档:重建窗口内连升两档会把新档 init/段请求挤进重灌期,视频轨永不激活 →
 *   看门狗重载死循环(死亡窗口 8.8s)。期满梯子自由爬,档内 ABR 切换不丢队列、无缝。
 * ②**顶档起播 stall 冷却(跨重载记忆)**——起播期(pos<30s)stall 重载由播放器侧记入
 *   [SabrAbrMemory],重载后新建的 selection 实例在冷却期(3min)内跳过顶档(≥2160)候选;
 *   打破「重载 → 全新状态 → 同样误判 → 再重载」的无记忆循环。低档升降/手动选档不受影响。
 *
 * 2026-08-31(23:25 真机「4K 回准入 → 漏 49s → 水位急救差 0.6s 输给在途 → 看门狗重载」复盘,三修):
 *   剧本:起播 stall 冷却 23:23:57 到期,**2s 内**凭 1440p 期的突发 sus(30670≥26.6M×1.1)回 4K →
 *   4K pacing 有效供给仅 ~16M(活跃 est 却读 30-36M:只计传输窗口;串行 fetcher 排队间隔被旧 10s
 *   滑行余量扣成 coast)→ 缓冲 26.3s→7.0s 漏 49s 全程无降档证据 → 7.0s 水位急救动手时 rn=37 已在途
 *   载 4K 段(39MB/7.6s),7.0<7.6 缓冲见底 → 看门狗重载;重载后 180s 冷却把 4K 挡住,一切正常
 *   ——即用户所见「后面降档正常」。修法三件(详见各处注释):
 *   A(SabrMediaFetcher)——顶档在位时 sustained 分母的滑行余量 10s→20s:4K 失败证据进 sus,
 *     防冷却到期后被同一批低档突发样本「合法」重准入;est 口径不动(防千兆满缓冲滑行误伤)。
 *   B1(本类 TOP_TIER_CRITICAL_BUFFERED_US)——顶档水位急救阈值 8s→20s:急救必须提前一个完整
 *     chunk 循环(评估盲窗 ~14s + staged 积压排空 ~8s + 替换段传输 ~4s)触发,8s 在串行管道里
 *     结构性来不及;非顶档维持 8s。
 *   B2(DefaultSabrChunkSource)——getNextChunk 的 holder/selectFormat 改到 updateSelectedTrack
 *     **之后**重读(对齐上游 media3 DashChunkSource):原顺序用切换前 selectedIndex 取 holder,
 *     切档决策对同一次调用 staged 的段无效,每次切档白吃一个循环生效延迟。
 *
 * 2026-09-01(VP9 粘性梯子,修「升档跨 codec 换解码器撞 codec 强制回收」):00:31 真机——梯子 720p
 *   avc(298)→1080p avc(299,旧规则保高码率变体)→1440p 只有 VP9(308)→ **avc→vp9 必然换解码器**;
 *   MTK codec2 回收脏实例两次各 ~5s(一次 avc 原地重建、一次 avc→vp9),视频冻住音频照播、清晰度
 *   显示如实停在 1080p,用户被迫手动退出。同场对照:会话 2 同样的切换只要 49ms(旧实例干净,不走强制
 *   回收)——坑是概率性的设备层行为(AOSP MediaCodec.cpp 回收路径:pending buffer → WOULD_BLOCK →
 *   重试强拆;MTK 定制 RM 重试 ≈5s),app 不可治,只能消触发面。修法:**同 height 多 codec 变体粘
 *   全组顶档 codec**(isTopCodecVariant,全组取顶档——窄选期子集算不出;YouTube=VP9,顶档 avc 的
 *   视频自动反转)→ 升档(302→303→308→315)与水位急救降档全程单 codec 零解码器重建。配套
 *   PlayerScreen 视频冻结看门狗(VideoFreezeThresholdMs=12s,位置基看门狗对「音频活视频死」结构性
 *   失明)兜底设备自抽风的残余场景。生态圈同类:androidx/media #3059(MTK)、google/ExoPlayer
 *   #10369(Amlogic STB)、androidx/media #1615(Pixel,官方 bug: in platform)——平台层无解。
 */
/* 2026-09-01(重填容量通道,修「Auto 满缓冲后结构性不升档」):12:05-12:13 真机——Auto 会话 720p
 *   起播后 bw/sus 结构性封顶在 ≈播放消耗码率(720p ~3.4-4.5M),升档门 required > effective 过不去,
 *   ~100s 才爬到 1080p(还得靠重填期突发抬 est);手动切 1440 单轨会话实测 14-24Mbps(REAL 行),铁证
 *   管道富余。根因:effective/sus 都含墙钟口径,满缓冲停闸 30-40s 后衰减/收敛到消耗码率,而下一档
 *   声明码率(303=5.6M/308=13.3M)只有「正在按该档消耗」才可能被墙钟口径读到——鸡生蛋死锁,梯子
 *   只剩冷启动爆发样本一条路(§19 压掉它是对的,但没给它第二条路)。修:**重填容量中位数**通道
 *   (SabrMediaFetcher.getRefillCapacityBps,近 8 笔成功请求 bytes/HTTP 耗时,免疫墙钟空转、无衰减),
 *   仅喂 isUpgrade 候选与 effective 取大;降档口径(alpha.9Z gap 入账)与 4K 顶档 sus×1.1 闸不动。
 *   日志 cap= 字段取证。
 */
/* 2026-09-01(满缓冲试探升档 trial upshift,修「Auto 满缓冲不升档」的 pacing 真根因):15:47/15:48
 *   真机同网络对照——Auto 480p 会话 est/sus/cap 全被 SABR 服务端 pacing 钉在 ~3M(cap 中位 3129K),
 *   70 秒后手切 1440 同网络 REAL 22-24Mbps 连续 6 笔(cap=22475K)。结论:播 X 档时服务端只按 ~X 档
 *   节奏供流,测量型门槛(est/sus/cap)结构性看不到真管道——2026-09-01 的 cap 重填容量通道也逃不掉
 *   (它测的仍是服务端供给节奏,不是管道容量)。唯一能让 ABR 获得高档真实数据的手段=试探性请求高档
 *   (等价手切:服务端立刻按新档 pace,sus/cap 被喂到真实量级,测量通道被治愈)。修法:
 *   ①触发=缓冲「升穿」试探水位线(max(15s 地板, 0.8×本实例见过的最高水位))+ canUpgrade(冷启动锁
 *     +降档后水位)+ 下一档不在失败冷却——跨线判定(本评估在上一评估之上)防首填单调期骑线常真;
 *   ②放行范围:容量/持续闸失真也升,一次仍只升一档(逐级爬不动);×1.1 顶档 sustained 闸与起播 stall
 *     冷却不试探(4K 死亡行军防线不松,顶档真证据由 1440p pacing 样本提供);
 *   ③失败回收:重锚已锚新档声明值 → est 真实样本快速下探 + 水位急救兜底;降出试探档时记 3min 失败
 *     冷却(与顶档冷却同值,防每轮回填都撞同一堵墙),期满由下次满缓冲重新试探。
 *
 * 2026-09-01 晚(alpha.5 首验复盘,21:0X 真机 4 轮重载):试探机制本身按设计工作(21:02:44 1440p
 *   试探失败优雅降档零重载;20:54:08 试探治愈测量——试探期 pacing 样本让 sus/cap 变真,gated 门合法
 *   升 1080p)。4 轮重载直接原因是网络塌方窗口(单笔 fetch 24-36s 慢滴/无响应,playbackHttpClient
 *   callTimeout=0 既有取舍,慢滴不超时),但试探三缺陷放大了伤害,本轮修:
 *   ①浅填充防线——maxObserved 随实例归零使试探线跌到 17s,21:08:46 在 bufS=20s 就试探 1440p;
 *     修:试探还要求 maxObserved ≥ 25s(TRIAL_MIN_CEILING_US),重载后必须先完整回填一轮。
 *   ②失败冷却不跨重载——实例字段被重载洗掉,21:13 案例重载发生在试探期(降档没跑,冷却没记)→
 *     新实例立即重试;修:冷却/active 态迁 SabrAbrMemory(墙钟),看门狗重载调 onStallReload()
 *     把 activeTrial 转记失败冷却(PlayerScreen.noteStartupStallMemory 无条件调用)。
 *   ③试探期无熔断——1440p 级亏空 ~9M/s 下 20s 缓冲 1-2s 穿底,5s 宽限+8s 阈值来不及救(21:14:20
 *     bufS=0s);修:试探档缓冲 <15s 且仍在下漏 → 2s 宽限后无视阈值立即降档(TRIAL_ABORT_*)。
 */
class HeightAwareAdaptiveTrackSelection(
  group: TrackGroup,
  tracks: IntArray,
  private val bandwidthMeter: BandwidthMeter,
) : AdaptiveTrackSelection(group, tracks, bandwidthMeter) {

  /**
   * 2026-09-01(VP9 粘性梯子):**全组**引用——窄选期(起始高度 cap)的 selection 只含低档子集
   * (如 [298/302] 两轨),按子集算「组内最高档」会得到 720p 的 codec(错);整张梯子的顶档
   * (如 315/2160p VP9)必须从全组取,粘性才知道该粘谁。
   */
  private val fullGroup: TrackGroup = group

  private var selected = length - 1

  /**
   * 2026-09-01(VP9 粘性梯子,修「升档 avc→vp9 解码器切换撞 codec 强制回收」):整张梯子最高
   * 分辨率档的 codec。YouTube 梯子 1440p/2160p 只有 VP9,avc 天花板在 1080p——旧「同 height 多
   * codec 保留高码率变体」规则让梯子在 1080p 选 avc(299 码率 > 303),爬到 1440p **必然跨 codec
   * 换解码器**,每次都在赌设备坑:00:31 真机 MTK codec2 回收脏实例 ~5s×2(avc 原地重建 + avc→vp9),
   * 视频冻住音频照播、清晰度显示如实停在 1080p,用户被迫手动退出;生态圈同类(平台层无解):
   * androidx/media #3059(MTK)、google/ExoPlayer #10369(Amlogic STB)、androidx/media #1615
   * (Pixel,官方标 bug: in platform)。修法:同 height 多 codec 变体改粘全组顶档 codec
   * (YouTube=VP9)→ 整场单 codec 零解码器重建;顶档是 avc 的视频自动反转,不写死 VP9。
   */
  private val topGroupCodec: String? = run {
    var top: Format? = null
    for (i in 0 until fullGroup.length) {
      val g = fullGroup.getFormat(i)
      if (top == null || g.height > top.height || (g.height == top.height && g.bitrate > top.bitrate)) {
        top = g
      }
    }
    top?.sampleMimeType
  }

  private fun isTopCodecVariant(f: Format): Boolean =
    f.sampleMimeType != null && f.sampleMimeType == topGroupCodec

  /**
   * 2026-08-31:selection 实例创建时间(elapsedRealtime ms)——冷启动梯子锁基准。实例创建 ≈
   * cap 释放重建(样本队列整体丢弃)时刻,锁内禁升档,越过重建重灌窗口。
   */
  private val createdElapsedMs = SystemClock.elapsedRealtime()

  /** alpha.9Z:上次降档时间(elapsedRealtime ms)——升档冷却基准。 */
  private var lastDowngradeElapsedMs = 0L

  /** 2026-08-30:上次升档时间(elapsedRealtime ms)——水位急救降档的宽限基准,0=从未升过。 */
  private var lastUpgradeElapsedMs = 0L

  /** 2026-08-30:上次评估的缓冲水位(us),首次评估为 -1——判定「水位仍在下漏」。 */
  private var prevEvalBufferedUs = -1L

  /** 2026-08-31:顶档 stall 冷却的跳过日志是否已打过(每 selection 实例一次,防每 chunk 刷屏)。 */
  private var topTierStallBlockLogged = false

  /** 2026-09-01 满缓冲试探:本实例见过的最高缓冲水位(us)——试探水位线 = max(地板, 0.8×此值)。 */
  private var maxObservedBufferedUs = 0L

  /**
   * 2026-09-01 满缓冲试探:最近一次升档是否试探批准——降档离开该档时据此记失败冷却 + 熔断判定用。
   * (试探失败冷却本体在 [SabrAbrMemory],跨重载有效——实例字段会被看门狗重载洗掉,21:13 真机案例。)
   */
  private var lastUpgradeWasTrial = false

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
    // 2026-09-01 满缓冲试探:先留上一评估的水位(试探用「升穿水位线」判定,防首填单调期骑线常真误触发)
    val prevBufferedUsForTrial = prevEvalBufferedUs
    // 2026-08-31 B1(顶档水位急救阈值 8s→20s):SABR 串行管道下急救必须提前一个完整循环触发——
    // ①评估只在 getNextChunk 发生,4K 段循环 10-14s → 两次评估间最长 ~14s 盲窗;②急救只影响后续
    // getNextChunk staged 的段,已 staged 的 4K 段积压(rn 一票 ~39MB/7.6s)仍占死串行 fetcher;
    // ③替换段传输还要 ~4s。23:24 真机:水位 7.0s 才触发,输给在途 7.6s 差 0.6s → 缓冲见底 → 看门狗
    // 整段重载。顶档在位时阈值提为 20s(覆盖 ①+②+③ 的最坏 ~26s 供给线,本例会在 23:24:24 评估
    // bufS=14.5 自 20.5 回落时触发 → 23:24:48 的请求载 1440p,谷底 ~3-5s 活着);非顶档维持 8s
    // 不动。假阳性代价=180s 顶档冷却(升 4K 本就要求缓冲 ≥30s,重爬周期天然重叠),假阴性代价=看门狗
    // 重载(~11s 冻结+整链重启),不对称性支持提前触发;千兆管道 4K 缓冲只涨不跌穿 20s,不受损。
    val criticalBufferedUs =
      if (currentHeight >= TOP_TIER_MIN_HEIGHT) TOP_TIER_CRITICAL_BUFFERED_US else DOWNGRADE_BUFFERED_US
    // 2026-09-01 试探熔断(trial abort,21:14 真机案例):试探是自己批准进去的,亏空要能秒退——
    // 缓冲 <15s 且仍在下漏时无视 8s/20s 阈值与 5s 宽限立即降档。1440p 级亏空(~9M/s)下 20s 缓冲
    // 1-2s 穿底,5s 宽限+8s 阈值来不及救(bufS=0s 才触发)。宽限仅 2s(一个评估循环)。
    val trialAbort = lastUpgradeWasTrial &&
      bufferedDurationUs < TRIAL_ABORT_BUFFERED_US &&
      nowMs - lastUpgradeElapsedMs >= TRIAL_ABORT_GRACE_MS
    val bufferCritical = (bufferedDurationUs < criticalBufferedUs || trialAbort) &&
      bufferedDurationUs <= prevEvalBufferedUs &&
      (trialAbort || nowMs - lastUpgradeElapsedMs >= DOWNGRADE_AFTER_UPGRADE_GRACE_MS)
    prevEvalBufferedUs = bufferedDurationUs
    if (bufferedDurationUs > maxObservedBufferedUs) maxObservedBufferedUs = bufferedDurationUs
    if (bufferCritical) {
      var lower = -1
      var lowerHeight = -1
      for (i in 0 until length) {
        if (isTrackExcluded(i, nowMs)) continue
        val f = getFormat(i)
        // 2026-09-01 VP9 粘性:同 height 多 codec 变体粘顶档 codec——水位急救降档(如 1440p→1080p)
        // 旧规则会选中 299(avc,码率更高),把 vp9→avc 跨 codec 换解码器引进降档路径。
        if (f.height in 1 until currentHeight &&
          (f.height > lowerHeight ||
            (f.height == lowerHeight && isTopCodecVariant(f) && !isTopCodecVariant(getFormat(lower))))
        ) {
          lower = i
          lowerHeight = f.height
        }
      }
      if (lower >= 0) {
        val current = getFormat(selected)
        val leavingIndex = selected
        Log.i(
          "YtSabrAbr",
          "buffer-critical downgrade: bufS=${bufferedDurationUs / 1_000_000}s " +
            "itag${current.id}/${current.height}p@${current.bitrate} → " +
            "${getFormat(lower).height}p@${getFormat(lower).bitrate}"
        )
        selected = lower
        lastDowngradeElapsedMs = nowMs
        markDowngradeFromTrial(nowMs, currentHeight)
        // 2026-08-30 顶档定向冷却:从顶档(2160p)水位降下后把该顶档 excludeTrack 3 分钟,防
        // 「重填突发过门槛→升 4K→贴地漏光→又降」边缘横跳反复切档卡顿;只锁顶档,低档升降照常
        // (与已取消的全档冷却不同)。非顶档(可持续档)的急救降档不加冷却。
        if (currentHeight >= TOP_TIER_MIN_HEIGHT) {
          excludeTrack(leavingIndex, TOP_TIER_BUFFER_CRITICAL_COOLDOWN_MS)
          Log.i(
            "YtSabrAbr",
            "top-tier cooldown: itag${current.id}(${current.height}p) excluded " +
              "${TOP_TIER_BUFFER_CRITICAL_COOLDOWN_MS / 1000}s"
          )
        }
        return
      }
    }
    // 带宽门槛:活跃传输 est(滑动窗口,含 gap/慢小样本)管「当前扛不扛得住」——降档用它,反应快。
    val effective = bandwidthMeter.getBitrateEstimate()
    // alpha.9Z(升档用持续带宽):突发速率 est 在重填缓冲期间会冲到 40-70M(2026-08-27 真机:一笔
    // 74Mbps 突发把 est 从 16M 抬到 40M 过 4K 门槛 → 升完必卡,pacing 有效供给只有 16-20M)。持续带宽
    // = 过去 60s 墙钟实际交付(SabrMediaFetcher.getSustainedBitrateEstimate),要求 ≥ 声明码率才许升。
    // 2026-08-31 改用原值(-1=证据不足**不回退**):旧 getSustainedBitrateEstimate 在冷启动把 -1 回退成
    // 被爆发样本撑高的活跃 est,sustained 闸门整体失效(720p 2 段爆发 33-58M 直接过 4K 门槛)。
    val sustained = (bandwidthMeter as? SabrBandwidthMeter)?.getSustainedBitrateEstimateRaw() ?: -1L
    // 2026-09-01 重填容量通道(修「Auto 满缓冲后结构性不升档」):effective 含被迫空转(alpha.9Z gap
    // 入账),满缓冲停闸 30-40s 后衰减到 ≈播放消耗码率(720p 玩出 ~3.4-4.5M 的 sus/bw 顶)——当前档
    // playing 时升档门 required > effective 被定点锁死,手动切 1440 却实测 14-24Mbps(12:05/12:12 真机)。
    // 本通道取每次成功请求的瞬时吞吐中位数(bytes/HTTP 耗时,不发请求不产生样本 → 满缓冲期不衰减,
    // 中位数抗 TCP 爬升/小段单笔噪声),仅喂**升档**判据与 effective 取大;降档仍走 effective(卡死不降
    // 防线不动)、4K 顶档 sus×1.1 闸不动(防 4K 死亡行军复发)。
    val capacityFloor = (bandwidthMeter as? SabrBandwidthMeter)?.getRefillCapacityEstimate() ?: -1L
    val upgradeEstFloor = if (capacityFloor > 0L) maxOf(effective, capacityFloor) else effective
    // 2026-08-30(声明口径修正,见类头):declared 现为 averageBitrate=真实平均消耗,
    // required = 裸声明;calib 采样折算(实测消耗/声明)机制整体取消。
    // alpha.9Z(升档滞回,防降档后横跳):带宽估计在档位临界值附近抖动时,无滞回会 308↔315 反复切轨
    // (每次切轨都要拉新 init 段,还丢已缓冲的高档数据)。升档要求 ①活跃 est ≥ 声明门槛
    // ②持续带宽 ≥ 声明门槛
    // ③降档后:缓冲 ≥30s 才许升(首次选档 lastDowngrade=0 不受此限,起播爬档不被卡)。
    // 2026-08-30(用户决策):**3min 升档冷却取消**——22:11-22:14 真机:急救降回 1080p 后缓冲已填满
    // 45s、门槛 12.7M 明明可升,却被冷却硬锁到 22:14:57,用户被迫手动切档。横跳防护由「缓冲 ≥30s
    // 才许升 + 升档后 10s 禁止 est 回降 + 重锚基线」承担,不再需要冷却。
    // 2026-08-31 冷启动锁档 10s(用户拍板):selection 实例创建 ≈ cap 释放重建(样本队列整体丢弃)
    // 时刻,重建窗口内连升两档=新档 init/段请求(带 2s 服务端 backoff)全挤进重灌期 → 视频轨永不
    // 激活 → 看门狗重载死循环(20:45 真机 pos=1569s 续播 3 轮,死亡窗口 8.8s)。实例创建后 10s 内
    // 禁升档,期满梯子自由爬(档内 ABR 切换不丢队列、无缝);原「首档豁免」取消,锁覆盖全部升档。
    val canUpgrade = nowMs - createdElapsedMs >= COLD_START_LADDER_LOCK_MS &&
      (lastDowngradeElapsedMs == 0L || bufferedDurationUs >= UPGRADE_MIN_BUFFERED_US)
    var best = length - 1
    var bestHeight = -1
    // 2026-09-01 满缓冲试探:最终选中的升档是否经试探放行(驱动 lastUpgradeWasTrial → 失败冷却)
    var bestIsTrial = false
    // 最高档(height 2160/4K)额外 sustained 门槛(2026-08-30 方案B→口径修正重标):4K 真平均就是
    // 实需,起步窄选期低档样本对 4K 无参考价值,且 4K 的 pacing 有效供给本就贴地 — 边缘反复获批/
    // 漏光/急救 → 315↔308 分钟级抖动。顶档要求 sustained ≥ declared×1.1(declared 已是真平均,
    // 1.1 挡的是 60s 均值口径下的 VBR 尖峰余量;千兆管道不受损,夜间塌方段 sus 8M 永不批 23M 的 4K)。
    val isTopTier = length > 1 && getFormat(0).height >= TOP_TIER_MIN_HEIGHT
    // 2026-08-31 ②stall 重载记忆:起播期 stall 重载后冷却期内跳过顶档(SabrAbrMemory,跨重载单例)。
    val topTierStallBlocked = isTopTier && SabrAbrMemory.isTopTierStartupBlocked()
    // 2026-08-31 ①逐级爬:升档候选只允许「下一个更高分辨率档」(未排除轨中最小的、严格高于当前的
    // height;同 height 多 codec 变体全部放行)——爆发 est 误判的最坏后果从「一步到顶 4K」降为
    // 「多升一档」,真扛不住由滞回/水位急救接管。降档不在此限(放开全部低档一步落位的既有语义)。
    var nextUpgradeHeight = Int.MAX_VALUE
    for (i in 0 until length) {
      if (isTrackExcluded(i, nowMs)) continue
      val h = getFormat(i).height
      if (h > currentHeight && h < nextUpgradeHeight) nextUpgradeHeight = h
    }
    // 2026-09-01 满缓冲试探升档(trial upshift):canUpgrade 已含冷启动锁 10s + 降档后缓冲 ≥30s;
    // 试探水位线 = max(15s 地板, 0.8×历史最高水位),要求「升穿」(上一评估在线下)——满缓冲本身
    // 就是供给富余于当前档的硬证据,也是测量型门槛在服务端 pacing 下唯一可补的盲区。
    // 2026-09-01 晚(浅填充防线,21:08:46 真机案例):maxObserved 随实例创建归零,看门狗重载后
    // 新实例试探线跌到 0.8×20s=17s,在 bufS=20s(缓冲远未满)就试探 1440p → 亏空恰逢网络塌方
    // → 重载。修:试探还要求 maxObserved ≥ 25s(本实例见过一次像样的回填)——重载/冷启动后必须
    // 先完整回填一轮才许试探;bufferMax=30s 用户档(fill ~28s)仍可用。失败冷却读 SabrAbrMemory
    // (墙钟,跨重载有效——21:13 案例重载洗掉冷却后立即重试同一堵墙)。
    val trialThresholdUs = maxOf(TRIAL_FLOOR_BUFFERED_US, maxObservedBufferedUs * 8 / 10)
    val trialUpgrade = canUpgrade &&
      maxObservedBufferedUs >= TRIAL_MIN_CEILING_US &&
      bufferedDurationUs >= trialThresholdUs &&
      prevBufferedUsForTrial < trialThresholdUs &&
      !SabrAbrMemory.isTrialFailBlocked(nextUpgradeHeight, System.currentTimeMillis())
    for (i in 0 until length) {
      if (isTrackExcluded(i, nowMs)) continue
      val f = getFormat(i)
      val isUpgrade = f.height > currentHeight
      // 2026-08-31 ①逐级爬:越级候选(高于下一档)跳过。非顶档升档在冷启动(sustained=-1 证据不足)
      // 也放行——`sustained in 0 until required` 对 -1 不成立,梯子靠活跃 est 门(required>effective)
      // + 本逐级约束跑,最坏多升一档(便宜可回退);顶档由下方 ×1.1 闸(-1 恒被挡)单独看死。
      if (isUpgrade && f.height > nextUpgradeHeight) continue
      // 2026-08-31 ②顶档 stall 冷却:起播 stall 重载后 3min 内顶档不进候选(低档升降照常)。
      if (isUpgrade && topTierStallBlocked && f.height >= TOP_TIER_MIN_HEIGHT) {
        if (!topTierStallBlockLogged) {
          topTierStallBlockLogged = true
          Log.i(
            "YtSabrAbr",
            "top-tier startup-stall cooldown: skip itag${itagOf(f)}(${f.height}p), " +
              "remain ${SabrAbrMemory.topTierStartupBlockedRemainSec()}s"
          )
        }
        continue
      }
      // 2026-08-31 降档滞回:仅当前档的降档判据放宽 ×0.85(15% 死区),升档/降档候选档保持全额
      // required——est 巡航骑在门槛 ±10% 时(declared=真平均后常态)不再每周期穿线切档。
      // 两分支统一 Long(Int×Long 若不显式 toLong 会推断成 Number&Comparable<*> 星投影,CompareTo 禁用)。
      val required: Long =
        if (i == selected) f.bitrate * DOWNSHIFT_MARGIN_PERMILLE / 1000L else f.bitrate.toLong()
      // 2026-09-01 重填容量:升档候选用 max(effective, 容量中位数) 过门;当前档(降档判据)与低档候选
      // 仍用 effective——降档口径不变(alpha.9Z「卡死不降档」防线)。
      if (!isUpgrade) {
        // 降档口径不变(alpha.9Z「卡死不降档」防线):当前档与低档候选仍用 effective。
        if (required > effective) continue
      } else {
        // 2026-09-01 满缓冲试探:容量/持续闸失真(服务端 pacing,见类头)时凭满缓冲放行;
        // ×1.1 顶档闸与起播 stall 冷却不试探(4K 死亡行军/起播死循环防线不松)。
        val capacityGateFail = required > upgradeEstFloor
        val sustainedGateFail = !canUpgrade || (sustained in 0 until required)
        val topTierGateFail = isTopTier && i == 0 &&
          sustained < f.bitrate * TOP_TIER_SUSTAINED_PERMILLE / 1000L
        if (capacityGateFail || sustainedGateFail) {
          if (topTierGateFail || !trialUpgrade) continue
          bestIsTrial = true
        } else if (topTierGateFail) {
          continue
        }
      }
      // 选声明码率可负担的最高分辨率档;同 height 多 codec(VP9/H264)2026-09-01 起改粘全组顶档
      // codec(VP9 粘性,见 isTopCodecVariant——旧「保留高码率变体」让 1080p 选 avc(299>303),爬到
      // 1440p(仅 VP9)必然跨 codec 换解码器,赌设备 codec 回收坑)。
      if (f.height > bestHeight ||
        (f.height == bestHeight && isTopCodecVariant(f) && !isTopCodecVariant(getFormat(best)))
      ) {
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
      getFormat(selected).height < currentHeight -> {
        lastDowngradeElapsedMs = nowMs
        markDowngradeFromTrial(nowMs, currentHeight)
      }
      // 2026-08-30 升档重锚:est 基准重锚到「新档声明码率」。declared 已是 averageBitrate=真实平均
      // 消耗,无需再校准折算;锚在实需值后,扛不住时真实带宽样本塌下来 est 快速下探降档,扛得住才
      // 允许继续爬。
      getFormat(selected).height > currentHeight -> {
        lastUpgradeElapsedMs = nowMs
        lastUpgradeWasTrial = bestIsTrial
        if (bestIsTrial) {
          // 2026-09-01 满缓冲试探取证:est/sus 此刻仍是低档 pacing 失真值,升入后服务端按新档
          // pace 供流,sus/cap 被喂到真实量级(1440p 手切会话 cap=22475K 实证)。active 记入
          // SabrAbrMemory——看门狗重载路径据此把「试探期被打死」转记失败冷却。
          SabrAbrMemory.noteTrialActive(getFormat(selected).height)
          Log.i(
            "YtSabrAbr",
            "trial upshift (buffer-full probe): bufS=${bufferedDurationUs / 1_000_000}s " +
              "threshold=${trialThresholdUs / 1_000_000}s → " +
              "itag${itagOf(getFormat(selected))}(${getFormat(selected).height}p) " +
              "declared=${getFormat(selected).bitrate} " +
              "est=${bandwidthMeter.getBitrateEstimate() / 1000}K " +
              "sus=${if (sustained >= 0L) "${sustained / 1000}K" else "-1"}"
          )
        } else {
          // 2026-09-01:试探档向上离开(gated 升档)→ 试探成功闭环,清 active 不记冷却。
          SabrAbrMemory.noteTrialActive(-1)
        }
        val newDeclared = getFormat(selected).bitrate.toLong()
        // 2026-08-31 冷启动梯子跳过重锚:sustained 证据不足(-1)时的升档是「爆发 est + 逐级爬」的
        // 冷启动梯子,重锚把 est 压到新档声明值会拖死下一步爬升(20:28 真机:升 1080 锚 5.7M,est 从
        // 2.8M 爬回 13.4M 门槛花了 ~2min 才到 1440p);梯子误判由水位急救/滞回兜底。证据成熟后的
        // 升档(稳态会话)保持重锚语义不变。
        if (sustained >= 0L) {
          (bandwidthMeter as? SabrBandwidthMeter)?.reseedToBitrate(newDeclared)
          Log.i(
            "YtSabrAbr",
            "upshift reseed: est baseline → $newDeclared (declared=${getFormat(selected).bitrate} " +
              "itag${itagOf(getFormat(selected))})"
          )
        } else {
          Log.i(
            "YtSabrAbr",
            "upshift (cold-start ladder, reseed skipped): " +
              "itag${itagOf(getFormat(selected))}(${getFormat(selected).height}p) declared=${getFormat(selected).bitrate}"
          )
        }
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

  /**
   * 2026-09-01 满缓冲试探:降档离开试探批准的档时记 3min 失败冷却(与顶档冷却同值),冷却期内
   * 该档既不过容量闸(失真值)也不被试探重试,期满由下次满缓冲重新试探;非试探降档只清标记。
   * 2026-09-01 晚:冷却改记 SabrAbrMemory(墙钟)——selection 实例字段会被看门狗重载洗掉,
   * 21:13 真机案例重载后新实例立即重试同一堵墙。
   */
  private fun markDowngradeFromTrial(nowMs: Long, fromHeight: Int) {
    if (lastUpgradeWasTrial) {
      SabrAbrMemory.noteTrialFail(fromHeight, TRIAL_FAIL_COOLDOWN_MS)
      Log.i(
        "YtSabrAbr",
        "trial fail cooldown: ${fromHeight}p excluded ${TRIAL_FAIL_COOLDOWN_MS / 1000}s " +
          "(remain ${SabrAbrMemory.trialFailBlockedRemainSec()}s, survives reload)"
      )
    }
    lastUpgradeWasTrial = false
  }

  private companion object {
    /** alpha.9Z:升档所需的最低缓冲水位(us)——降档自救后缓冲重建到这一水位前,不允许弹回高档。 */
    const val UPGRADE_MIN_BUFFERED_US = 30_000_000L
    /**
     * 2026-08-31:冷启动梯子锁(ms)——selection 实例创建(≈cap 释放重建、队列整丢)后这段时间内
     * 禁升档。20:45 真机死亡窗口 8.8s(重建→看门狗),10s 覆盖之;用户拍板起播锁 10s 可接受。
     * 期满档内 ABR 切换不丢样本队列,梯子无缝爬。
     */
    const val COLD_START_LADDER_LOCK_MS = 10_000L
    /** alpha.9Z:降档后升档所需最低缓冲水位(us)——缓冲重建到这一水位前不允许弹回高档。 */
    const val DOWNGRADE_BUFFERED_US = 8_000_000L
    /**
     * 2026-08-31 B1:顶档(≥2160)在位时的水位急救阈值(us)——见 updateSelectedTrack 的 B1 注释。
     * 与 SabrMediaFetcher.TOP_TIER_GAP_RUNWAY_RESERVE_MS(A)同值同语义:水位 <20s 即顶档供给线失守。
     */
    const val TOP_TIER_CRITICAL_BUFFERED_US = 20_000_000L
    /** 2026-08-30:升档后的水位急救宽限(ms)——新档刚起步缓冲未回填,不能立刻按同一水位反弹降档。 */
    const val DOWNGRADE_AFTER_UPGRADE_GRACE_MS = 5_000L
    /** 2026-08-30:升档后禁止 est 回降宽限(ms)——重锚精确锚在新档门槛,起步期小样本会立刻打回。 */
    const val UPGRADE_DOWNGRADE_GRACE_MS = 10_000L
    /** 2026-08-30 方案B:顶档(4K 级)定义高度门槛——组内最高档 height ≥ 此值时启顶档 sustained 加码。 */
    const val TOP_TIER_MIN_HEIGHT = 2160
    /**
     * 2026-08-30 修正:顶档升档 sustained 门槛系数千分位——sustained ≥ 声明×1.1 才许升 4K。
     * 旧 ×0.6 是对 peak 虚高(~2×)的折算;declared 换 averageBitrate=真平均后,1.1 是 60s 均值
     * 口径下的 VBR 尖峰余量(本视频 315 声明 ~23M → 门槛 ~25.3M)。
     */
    const val TOP_TIER_SUSTAINED_PERMILLE = 1100L
    /**
     * 2026-08-30:顶档定向冷却时长(ms)——水位急救从顶档降下后,顶档 excludeTrack 这段时间,
     * 防「重填突发过门槛→升 4K→贴地漏光→又降」边缘横跳(23:28-31 真机 3.5min 两轮循环)。
     * 只锁顶档,低档升降不受影响;3min 覆盖一个完整误批-回填周期(周期 ~55-85s)。
     */
    const val TOP_TIER_BUFFER_CRITICAL_COOLDOWN_MS = 180_000L
    /**
     * 2026-08-31:当前档降档判据滞回余量(千分位)——降档门槛 = required×0.85,升档门槛 = required
     * 全额,双阈值差 15% 作死区。est 巡航骑在相邻档门槛 ±10% 时(00:01-00:04 真机 1440p↔1080p
     * 每周期穿线)不再切;真饿(est < required×0.85)照降,水位急救兜底。与「升档后 10s 禁回降」互补:
     * 那管升档起步期,本滞回管稳态临界期(不限时)。
     */
    const val DOWNSHIFT_MARGIN_PERMILLE = 850L
    /**
     * 2026-09-01:试探水位线地板(us)——「满缓冲=供给富余」证据的最低可信线;30s bufferMax 档
     * (0.8×28s≈22s)也在线上,地板只在更小水位时兜底。
     */
    const val TRIAL_FLOOR_BUFFERED_US = 15_000_000L
    /**
     * 2026-09-01:试探失败档冷却(ms)——试探扛不住的档冷却这段时间,防每轮回填都重试同一堵墙;
     * 与顶档定向冷却同值(3min 覆盖一个完整误批-回填周期)。
     */
    const val TRIAL_FAIL_COOLDOWN_MS = 180_000L
    /**
     * 2026-09-01 晚(浅填充防线):试探准入的本实例历史最高水位下限——重载/冷启动后 maxObserved
     * 归零会令试探线跌到 17s(21:08:46 真机在 bufS=20s 就试探 1440p),必须先见过一次像样回填
     * (≥25s)才许试探。bufferMax=30s 用户档(fill ~28s)仍可用。
     */
    const val TRIAL_MIN_CEILING_US = 25_000_000L
    /**
     * 2026-09-01 晚(试探熔断,21:14 真机 bufS=0s 案例):试探档缓冲跌破此线且仍在下漏 → 无视
     * 8s/20s 水位急救阈值与 5s 宽限立即降档。试探是自己批准的,亏空秒退。
     */
    const val TRIAL_ABORT_BUFFERED_US = 15_000_000L
    /** 2026-09-01 晚:试探熔断宽限(ms)——升档后首个评估循环内不熔断(防重锚瞬间误判)。 */
    const val TRIAL_ABORT_GRACE_MS = 2_000L
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
