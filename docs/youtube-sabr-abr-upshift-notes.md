# YouTube SABR ABR 升降档问题排查记录

> 专门记录 SABR 升降档(quality up/down-shift)排查中发现的问题、调整记录、处理方法与方法来源。
> 与 [youtube-hd-playback.md](youtube-hd-playback.md) §6.23 互补:§6.23 记「已落地的实现」,本文记「排查过程、证据、未决问题」。

- 排查时间:2026-08-24(真机日志 `logs_live.log`,v3.0.5-alpha.9 后)
- 相关代码:`DefaultSabrChunkSource.kt`(升降档核心)、`PlayerScreen.kt` / `MobilePlayerScreen.kt`(起始档锁)
- 参考实现:media3 1.10.0 `AdaptiveTrackSelection.java`、LibreTube `DefaultSabrChunkSource.kt`(E:\GITHUB\LibreTube)

---

## 1. 已实施手段(背景)

| 手段 | 位置 | 作用 |
|---|---|---|
| 起始档精确锁(min+max VideoSize) | PlayerScreen / MobilePlayerScreen `loadRequest` | 起播钉起始档,首帧 `clearVideoSizeConstraints` 松开 |
| ceiling 降档滞回(excludeTrack) | `DefaultSabrChunkSource.applyCeilingExclusions` | 降档后排除 source 档及以上,ABR 不爬回高档震荡 |
| relax 窗口 | `DefaultSabrChunkSource` getNextChunk | 带宽持续 ≥ 目标档码率÷0.7 满 `bufferMaxMs/2` 才放回一档 |

---

## 2. 现象

- 起播 1080p(起始锁生效,缓冲满 50s 顺畅)
- 4K 重新 resolve 后 selection 重建,锁被清 → 掉到 480p(itag244)
- 之后**一直不升回**,UI 清晰度停在 480p 不变
- ceiling 滞回确实挡住"爬回 4K"震荡(主目标达成,无黑屏无重载),但**放松闸后 ABR 不真正升档**

## 3. 关键证据(2026-08-24 logs_live.log)

**3a. sel 钉死 480p,ceiling 放宽仍不动**
```
10:04:32.123  sel=5 ceiling=5     ← 掉到 480p,锁定
10:04:32→40   sel=5 ceiling=5     ← 稳定,缓冲 0→49s,不爬回
10:05:23.487  sel=5 ceiling=4     ← relax 放回一档(1080p 解除排除),但 sel 仍 5
10:05:24→26   sel=5 ceiling=4     ← goodMs 累计,带宽充足,仍 5
```
fmts=299[6.2M],303[5.6M],298[3.5M],302[2.8M],135[1.16M],244[1.03M](降序,index0=最高)

**3b. 实际下载吞吐 8–13Mbps(fetch 每段)**
```
rn=7  798753B 593ms → 10Mbps
rn=9  966929B 658ms → 11Mbps
rn=10 873101B 527ms → 13Mbps
rn=15 938075B 847ms →  8Mbps
```
→ 实际带宽连 4K(299=6.2M)都够,绝不该停在 480p。

**3c. 段信息**:itag 135/244 `endSegNum=324 duration=1566782ms` → **每段 ≈4.8s**。

---

## 4. 排查过程(含推翻的假设)

### 假设 A:media3 有效带宽的"load-window 惩罚因子"挡了升档 —— ❌ 推翻

media3 1.10.0 `AdaptiveTrackSelection.getAllocatedBandwidth()`(源码,见 §7):
```
effectiveBitrate = bandwidthMeter.getBitrateEstimate() × 0.7 × (chunkDuration/playbackSpeed − ttfb) / (chunkDuration/playbackSpeed)
```
多乘 `(段时长 − ttfb)/段时长`(load-window 因子)。初判:段 4.8s、ttfb~1s → 因子 0.79,raw 若 1.9M → effective 1.05M 刚够 480p 不够 1080p。

**推翻依据**:§3b 实测吞吐 8–13M(含 ttfb),raw 远超 1.9M。乘 0.7×0.94 仍有 ~6M,远超任何档。**惩罚因子不是瓶颈**;且 fetch 总时长 0.5–0.85s,ttfb 不可能大到把 effective 压到 1M。媒体3 在真实带宽下完全能自动升档。

### 假设 B(当前,待证实):媒体3 带宽计 getBitrateEstimate() 严重低估 —— 待验证

- `ceiling=4` 已解除 index4(1080p)排除,带宽充足,但 `determineIdealSelectedIndex` 仍返回 index5(480p)。
- 由 media3 选轨逻辑(`第一个非排除且 trackBitrate ≤ effectiveBitrate 的轨`),选 5 说明 effectiveBitrate 落在 [1.03M, 1.16M)。
- 而实际吞吐 8–13M → effectiveBitrate 被算成只有 ~1M → **带宽计(或有效带宽计算)低估 ~5 倍**。
- 疑点:SabrDataSource 给 bandwidth meter 上报的字节/时长不对,或滑动窗口被慢首样本(早期 0–3M)拖住,或 sparse transfer 间隔过长被 meter 衰减。

**待办**:已加日志(§5)打 `bandwidthMeter.getBitrateEstimate()`,真机复测确认低估幅值,再对症下药。

---

## 5. 调整记录

| 日期 | 改动 | 文件 | 状态 |
|---|---|---|---|
| 2026-08-24 | 升降档改 excludeTrack 真正排除(方案B),替代失效的「EMPTY iterator 门控」 | DefaultSabrChunkSource.kt `applyCeilingExclusions` | 已落地 |
| 2026-08-24 | 起始档 maxHeight 单边 cap 改 min+max 精确锁(对齐 LibreTube) | PlayerScreen / MobilePlayerScreen | 已落地 |
| 2026-08-24 | 加带宽计诊断日志 `bw=`(getBitrateEstimate)到 YtSabrAbr 行 | DefaultSabrChunkSource.kt | 本次新增,待真机复测 |
| 2026-08-24 | **建立真实带宽机制**:SabrMediaFetcher 从实际字节(bytes/elapsed)记录样本,取最近 8 个中位数 `getRealBitrateEstimate()`;relax 放档判定改用真实带宽(替代不可信媒体3 带宽计)。诊断行同时打 `bw=`(媒体3计)与 `realBw=`(真实)对照 | SabrMediaFetcher.kt / DefaultSabrChunkSource.kt | 已落地 |
| 2026-08-24 | force-climb 钉档(排除式强制升档):relax 时排除更低码率档只留目标单轨,逼媒体3 兜底选中 | DefaultSabrChunkSource.kt `applyExclusions` | **已落地后又被推翻/回退**(见下) |
| 2026-08-24 | **带宽驱动选档(最终方案,替代全部 exclude/force 补丁)**:新增 `SabrBandwidthMeter : BandwidthMeter` 包装 DefaultBandwidthMeter,`getBitrateEstimate()` 返回 SabrMediaFetcher 实测真实带宽(中位数);由 DefaultSabrChunkSource 注入真实带宽来源。媒体3 原生 ABR 拿到可信带宽自然选最高可负担档、升降全自动,删除 ceiling/force-climb 排除机制(force-climb 真机反致掉 480p)。PlayerScreen/MobilePlayerScreen 用 wrapper 建带宽计,两处 type DefaultBandwidthMeter→BandwidthMeter | 新建 SabrBandwidthMeter.kt / SabrMediaSource.kt / 两 Screen | 已落地,复测通过(20:27-29 8K 段见下) |
| 2026-08-24 | **带宽样本计入段间等待(可持续带宽,根治「8K 缓冲掉不降档」)**:带宽驱动选档后 `bw=` 瞬时吞吐(84M)仍高估——8K 段(315 声明 31.6M,真实 ~80M)4.6s 下载后等 23-30s 才拉下一段,瞬时 84M vs 可持续 ~15M;媒体3 拿 73M→effective 51M>31.6M 误判 8K 可负担,缓冲 39s→3.6s 仍不降档。改 `SabrMediaFetcher` 采样 `bps = bytes/(elapsed+gapSinceLastFetchEnd)`,反映真实可持续带宽,ABR 选到可负担档、缓冲掉能降档。加 `lastFetchEndRealtimeMs` 墙钟锚点 | SabrMediaFetcher.kt | 本次新增,待真机复测 |
| 2026-08-24 | **分辨率优先选档(根治「Auto 卡 1080p 不升」)**:带宽驱动选档后带宽可信,但媒体3 `AdaptiveTrackSelection` 按 **bitrate 降序**选档(bitrate 兼当画质顺序+带宽门槛),而 YouTube 声明 bitrate 与 height 错位(308 1440p 声明 13.9M < 303 1080p 14.4M),媒体3 以为 1080p 是更高级档 → 带宽够也停在 1080p 不升(用户手动选 1440 缓冲正常涨,证明带宽够)。新建 `HeightAwareAdaptiveTrackSelection`:override public `updateSelectedTrack` 自算 `effective=getBitrateEstimate()`(可持续中位数,不再乘 0.7 保守因子)+ 按 **height** 选最高可负担档,bitrate 只当门槛;override public `getSelectedIndex()` 写回自维护索引(父类 `selectedIndex`/`determineIdealSelectedIndex`/`getAllocatedBandwidth` 全 private 不可复用)。Factory override protected `createAdaptiveTrackSelection`(5 参)注入,音频等无 height 组退化父类按码率选档,同 height 多 codec 自然保留高码率变体 | 新建 HeightAwareAdaptiveTrackSelection.kt / 两 Screen(DefaultTrackSelector(context)→(context, Factory)) | 已落地,待真机复测 |
| 2026-08-30 | **sustained 分母扣减需求驱动的停闸空闲**(修「Auto 不升 1440、手动切正常」,详见 §10):`recordFetchGap` 滑行部分与 seek/手动 gap 记入 `addSustainedGapSample`,`getSustainedBitrateEstimate` 分母改为 `rawSpan − gapMs`;YtSabrAbr 诊断行加 `sus=` | SabrMediaFetcher.kt / DefaultSabrChunkSource.kt | 已落地,待真机复测 |

---

## 6. 处理方法与方向

1. **稳定优先**:excludeTrack ceiling 滞回已达成主目标(不震荡、无黑屏),保留。
2. **升档回不来**:根因不是惩罚因子,疑带宽计低估。**先取证**(§5 的 bw= 日志),确认低估后再选方案:
   - 若带宽计低估 → 修 SabrDataSource 上报 / 调 meter 参数,让媒体3 自然爬回;
   - 若 effectiveBitrate 计算仍保守 → 走媒体3 fallback 绕行(relax 时同时排除当前低档,`determineIdealSelectedIndex` 兜底返回唯一非排除高档),强制升档(已评估可行,见假设 B 附注)。
3. **4K re-resolve 掉档问题**:re-resolve 重建 selection 清掉起始锁 → ABR 回落"诚实"带宽档。可考虑 re-resolve 后重挂起始锁或不让 ABR 回落,单独立项。

---

## 7. 方法来源

- **media3 1.10.0 `AdaptiveTrackSelection.java`**(androidx/media tag 1.10.0, GitHub raw):
  - `getAllocatedBandwidth()`:`effectiveBitrate = latestBitrateEstimate × 0.7 × (chunkDurationUs/playbackSpeed − ttfbEstimateUs)/(chunkDurationUs/playbackSpeed)`
  - `determineIdealSelectedIndex()`:遍历 0..length-1,**跳过 isTrackExcluded 的轨**,返回第一个 `trackBitrate ≤ effectiveBitrate` 的;无则返回最后一个访问的非排除轨(fallback,不看 effectiveBitrate)——这是「排除当前低档逼升档」可行性的依据。
  - `updateSelectedTrack()` 升档回退守卫:仅当 `bufferedDurationUs < minDurationForQualityIncreaseUs(默认10s)` 才推迟升档。
  - **无 iterator EMPTY/gap 门控**:iterator 只影响 `getNextChunkDurationUs`(时长估计),不阻止切轨 → §6.23 记的「EMPTY 挡不住选轨」在源码层面坐实。
- **LibreTube**(E:\GITHUB\LibreTube):起始档精确锁 `setMinVideoSize+setMaxVideoSize` 的参考实现。
- **真机日志** `Y:\download\bilitv\logs\logs_live.log`:fetch 吞吐、YtSabrAbr 选轨、段 endSegNum/duration 的证据来源。

---

## 8. 未决问题清单

- [x] **带宽计不可靠已坐实,最终用「带宽驱动选档」根治**(§5 末行):媒体3 `getBitrateEstimate()` 真机 1M↔437M 跳变 → 新建 `SabrBandwidthMeter` 让带宽计返回真实带宽(中位数),媒体3 原生 ABR 按可信带宽选档,删除 exclude/force 补丁。**2026-08-24 复测通过**:`bw=` 稳定平滑(912K→12M→25M,不再 27K↔232M 狂跳);会话1 真实带宽 12-28M 直接选到顶档 4K(299@6.2M)稳定钉住;会话2 带宽 886K→6M→9.7M→11M 时 **1080p→1440p→4K 自然爬升**,无震荡、无黑屏、无看门狗重载。**「没升」问题根治,媒体3 原生 ABR 按真实带宽选最高可负担档,升降全自动,无需任何 exclude/force 补丁**
- [x] **8K(315)缓冲掉不降档 → 可持续带宽高估**:带宽驱动选档后 20:27-29 真机爬到 8K(315 声明 31.6M,真实段 ~80M),瞬时下载 84M 但段间 gap 23-30s → 可持续 ~15M,缓冲 39s→3.6s 仍不降档。根因带宽样本 `bytes/elapsed` 漏掉段间等待。早期方案直接 `bytes/(elapsed+gap)` 全额计 gap,后被 alpha.9X 回退(gap 被当作主动节奏,满缓冲停闸误杀);**最终方案见 §9**(gap + 滑行量扣减,2026-08-27)
- [ ] **Auto 卡 1080p 不升 → 分辨率优先选档**:带宽驱动后带宽可信(用户手动选 1440 缓冲正常涨),但 media3 按 bitrate 降序选档,YouTube 声明 bitrate 与 height 错位(308 1440p 13.9M < 303 1080p 14.4M)→ 带宽够也停在 1080p。新建 `HeightAwareAdaptiveTrackSelection` 按 height 选档、bitrate 只当门槛。**待真机复测**:Auto 能从 1080p 自然升到 1440p/4K、带宽不够时能降档、无黑屏无看门狗重载
- [ ] re-resolve 掉档后如何从 480p 回档(4K 重新 resolve 重建 selection 清起始锁 → ABR 回落到真实带宽档,一般已够;待复测确认)
- [x] relax 强制抬升 → **已废弃**,改带宽驱动(上一条)

---

## 9. 2026-08-27 4K 卡死→看门狗重载死循环:GC 风暴 + 带宽分母漏计段间空窗(alpha.9Z)

### 现象(真机 logs_live.log,Sony BRAVIA 4K,itag315 4K VP9 声明 39.4M)

- 播放周期性 `stall detected, auto-retry`(看门狗)→ **整会话重新 harvest,新会话仍默认 315** → 同码率再来一遍,死循环
- `YtSabrAbr: sel=0 bitrate=39363148 bw=83029K down=1` —— `down=1` 只是相邻低档候选 index,**不是降档动作**;选轨器(HeightAwareAdaptiveTrackSelection)纯按「声明码率 ≤ 带宽估计」一票决定,est=83M > 39.4M 永不降档

### 时间线还原

1. **19:17:30** 缓冲填到 50s(bufferMax 停闸,`isLoading=false state=3`)
2. **19:18:09-33** 缓冲耗到 ~10-13s 后恢复取流,但供给贴地:每个 POST 周期 ~10.5s 只回 2 段 ≈10s 内容(供给 ≈0.95x 播放),缓冲不再回涨
3. **19:18:37-45** **GC 风暴**:堆顶死 380-400MB(0% free),每 ~0.5s 一次并发 GC 单次释放 100-250MB LOS(10-25MB 段 buffer),`Suspending all threads took 15-27ms` 连环;loader 线程(19922)被 blocking GC Alloc 卡 50-92ms 整串
4. **19:18:44.93** 下一次 fetch 根本没发出,视频缓冲耗干 → `state=BUFFERING`(音频还缓冲 28%,视频没了)
5. **19:18:53.89** 进度 9s 不动 → 看门狗 `retryKey++` 整会话重载

### 根因:带宽分母只计「传输活跃耗时」

`SabrMediaFetcher.media()` 样本 = `t0→response 读完`,alpha.9X 注释刻意排除段间 gap(「主动节奏非带宽不足」)。但 GC 卡死 loader 的 8s 里 **fetch 压根没发起**——连 `recordRealBandwidthFailure`(只覆盖"发起了且失败")都不产生样本,est 停在最漂亮的 83M。有效带宽(墙钟摊)实测仅 ~10.7Mbps(70s 墙钟仅交付 93.8MB ≈ 19s 内容),远低于 39.4M。

**为什么早期「直接全额计 gap」被回退**:满缓冲停闸(缓冲从 50s 滑行下来)的 gap 会被误判成供给不足 → 每次停闸后 est 崩塌 → 质量棘轮式下滑。**本版方案 = gap 计时 + 滑行量扣减**,两个矛盾同时满足:

| 判定 | 规则(`SabrMediaFetcher.recordFetchGap`) |
|---|---|
| gap 计入分母 | `counted = gap − max(0, runway − 10s 安全余量)`,≥0.5s 才记,≤30s 封顶 |
| 满缓冲主动停闸 | gap 开始时缓冲 ~50s → 滑行量 40s → 42s 的停闸 gap 只计 2s,不误杀 prefetch |
| GC/断流被迫空转 | runway ~12s → gap 16s 计 ~8s → est 下探 → 降档 |
| 消耗性 pacing(供给≈1.0x) | runway 10-13s → 每周期计 ~8-10s → est ≈17-33M < 39.4M → 降档 |
| seek / 手动选档后 | gap 是操作开销,跳过(`lastSeekMs`/`lastManualFormatSelectionMs` 判定) |
| runway 来源 | 仅**视频** chunk source 每次 getNextChunk 喂 `noteBufferedAheadMs`(音频缓冲远超需求会污染判定);在上次 fetch **结束时快照**(= gap 起点的水位) |

配套改动:
- `getRealBitrateEstimate()`:窗口内全是空转(量=0)返回 **0**(不再回退 delegate 高估);`SabrBandwidthMeter` 接受 `real >= 0` 为有效
- `HeightAwareAdaptiveTrackSelection` 升档滞回:升档需 **est ≥ 声明码率×1.25 且缓冲 ≥30s**(降档无门槛自救要快),防临界带宽 308↔315 反复切轨(每次切轨拉新 init 段还丢已缓冲数据)
- 修正 §52 注释:原「带宽含段间 gap」与 media() 实现矛盾(见上)

### r1657 复测(2026-08-27 19:54-56):gap 机制零触发,暴露第三条供给中断路

**按设计工作的部分**:起播 bw=20M 选 308(17.4M),bw 爬到 41M+ 升 315(升档滞回生效);rn=17→18 间 9.2s 间隔被 runway 19.6s 全兜住(19.6−10>9.2)不计——判定与真机行为吻合。

**新盲区(commit 1c7b08a 修复)**:`rn=18 REAL 939B 8552ms → 0Mbps`——服务端把请求挂 8.5s 只回 939B(供给中断发生在**传输内**),被 `REAL_BW_MIN_BYTES=100KB` 过滤器整条丢弃。31s 墙钟仅交付 67.6MB(有效 ~17M)vs 315 实际码率 ~33M,缓冲 19.6s→2% 看门狗重载,est 仍钉 47-52M。**供给中断的三条路现在全覆盖**:①fetch 没发起(GC,gap 计时)②发起但失败(recordRealBandwidthFailure)③发起"成功"但空转(慢小响应,bytes<100KB 且 elapsed≥2s 按实际入账)。

### r1660 复测(2026-08-27 20:26-33):gap 计数仍为 0——时钟单位 bug 把 gap 计时整个打死(commit 修正)

现象(用户口述):降到 1440 没问题,之后又升回 4K,升完触发重载。日志实锤:4K pinned 60s 缓冲 16s→5.4s,**`bw gap counted` 全场 0 条**,est 钉突发速率 45-60M。

根因(时钟单位不匹配):`lastSeekMs`/`lastManualFormatSelectionMs` 都是 **epoch 墙钟**(`Instant.now().toEpochMilli()`/`System.currentTimeMillis()`,~1.79×10¹²),而 `lastFetchEndMs` 用了 `SystemClock.elapsedRealtime()`(开机时长 ~10⁶)。`recordFetchGap` 的守卫 `(prevSeekMs > prevFetchEndMs)` 恒真 → **任何 gap 都被判成 seek 后开销跳过**。修正:gap 路径统一墙钟(`t0Wall`/`lastFetchEndMs=currentTimeMillis()`),墙钟跳变时由 `fetchStartMs<=prevFetchEndMs` 守卫自然跳过,不产生错误样本。

配套(升档冷却):修完 bug 后 pinned 阶段 est 会真实回落触发降档,但「重填缓冲 → 突发速率 est 冲高 → 立刻弹回高档 → 再卡死」横跳仍在(20:31:48 升 4K→20:32:47 卡死→重载→20:32:58 又升 4K,循环周期 ~70s)。HeightAwareAdaptiveTrackSelection 升档门槛追加 **③距上次降档 ≥3min**(手动选档走单轨组不受影响),打破「降→填→升→卡」循环。

### r1661 复测(2026-08-27 20:47-55):gap 计时生效、降档自救正常;剩「升 4K 必卡」——升档判据用的是突发速率

- ✓ 12 条 `bw gap counted:` 落账(raw/coast/runway 三值可核对),1 次 stall;降到 1440 后稳定
- ✗ 升 4K 依旧:20:53:04 缓冲爬到 30.4s 时一笔 74Mbps 突发把 est 从 16M 抬到 40M,过 29.9×1.25=37.3M 门槛 → 升 4K → 服务端 pacing 有效供给仅 16-20M,缓冲 80s 从 36s 掉到 6s(用户:每次升完就非常卡)
- 根因:est=滑动窗口(20s **活跃传输时间**)测的是突发速率;重填缓冲期背靠背拉流 burst 40-74M 是真的,但 4K pinned 后服务端 pacing 把长期供给压回 16-20M——burst est 对「扛不扛得住」是假信号
- 附带修掉倒挂 bug:×1.25 乘数原先只在缓冲 ≥30s 时生效,缓冲<30s 时升档门槛反而更低(方向反)

**修复(升档判据加「持续带宽」)**:`SabrMediaFetcher.getSustainedBitrateEstimate()` = 过去 60s **墙钟**内成功交付媒体字节 ÷ 墙钟跨度(固定含全部空窗;跨度 <15s 视为证据不足回退活跃 est,起播爬档不被卡)。HeightAware 升档门槛改为:①活跃 est ≥ 声明码率×1.25(乘数恒生效)②**持续带宽 ≥ 声明码率** ③降档后缓冲 ≥30s 且冷却 ≥3min。本网络 1440p 重填期持续 ≈18M < 29.9M → 4K 不再获批钉 1440p;千兆网络持续 48M+ 照常升。降档仍用滑动窗口 est(反应快)。

### 未决/风险

- [ ] **真机复测(r1658+)**:4K 播放中应出现 `bw gap counted:` 日志,慢小响应不再被过滤,est 回落到可持续值(<31.6M)后自动降到 308/303,看门狗重载不再死循环;好网络下 4K 仍能稳定维持(填充期 gap≈0 不受影响)
- [ ] 段间 ~10s pacing 的归属(服务端限速 vs 客户端队列上限)未最终定性——若为客户端刻意 2 段队列上限,gap 计时会系统性低估带宽;但升档滞回(缓冲 ≥30s 才升)保证最坏结果是 315↔308 震荡而非永久低档
- [ ] GC 风暴根因未治:4K VP9 段 buffer(10-25MB)把 ~400MB 堆打满,单次 46MB `response.body?.bytes()` 分配即触发连环 GC;根治需段落盘/流式处理,另立项目

---

## 10. 2026-08-30 「Auto 不升 1440、手动切正常」:sustained 分母摊入需求驱动停闸 → 定点死锁(commit 待推,§5 尾行修复)

### 现象(真机 logs_live.log 15:26-15:33,Sony BRAVIA)

- Auto 档起播 480p→720p→1080p60 正常爬,`sel=2(303,7.55M)` 后**钉死不再升**;active est(_bw=_)15:28:53 起 18-19M,早已过 308 门槛①(13.4M×1.25=16.8M),且无降档记录(门③豁免)——卡在门②持续带宽
- 手动切 1440(15:30:04,selection 重建单轨锁)后每笔 fetch 14-15MB/~9s → **持续 20-23Mbps,播放流畅**,证明管道实际远够 308

### 根因:sustained 的 60s 墙钟分母分不清「管道空闲因为需求低」和「管道空闲因为供给断」

`sustained = 过去 60s 交付字节 ÷ 墙钟跨度(全摊)` 的原意是防「重填期突发速率假信号 → 升完必卡」(§9 r1661)。但它有一个定点死锁:**pinned 低档 + 满缓冲时,管道只在「补一段 ≈10s 内容」的 10s 周期里活跃,其余全停闸空窗**——60s 窗口里 17s 爆发 + 28s 停闸,实测:

```
15:28:19 bufS=49.4 → 15:28:47 bufS=0.0(28s 零 fetch,满缓冲滑行)
15:27~15:29 全场交付 ≈60-75MB/60s → sustained ≈ 8-10M < 13.4M(308 档)
```

即 sustained ≈ **当前档消耗速率**(供给≈需求+服务端 pacing),恒低于高一档声明码率 → 从低档出发永远凑不出升档证据。手动切档走单轨组不经门②,所以一切正常。

### 修复(sustained 分母扣减需求驱动的停闸空闲,与 active est 的 gap 滑行量扣减同口径)

- `SabrMediaFetcher.recordFetchGap`:滑行部分(coast = runway − 10s 安全余量)记入新 `addSustainedGapSample`;seek/手动选档后的 gap 同样全扣
- `getSustainedBitrateEstimate`:分母 = `rawSpan − sustainedGapMs`(下限 1s 上限 60s)
- **判别力不变**:GC/断流/服务端 pacing 期间 runway 低、滑行扣不掉,空窗仍留在分母里压低 sustained → 315 防卡死意图保留;只有「满缓冲主动停闸」被豁免
- 增加诊断:YtSabrAbr 行新增 `sus=`(持续带宽 Kbps),与 `bw=`(活跃 est)并列,真机核对升档判定
- 风险(与 §9 已记一致):重填期 sustained≈突发速率,308↔315 临界网络可能再现「升 4K→pacing 供给不足→降档」一个循环;由门③缓冲 ≥30s + 冷却 ≥3min + 快速降档兜底,最坏是单次往返而非死循环/看门狗重载

### 待真机复测

- [ ] 1080p 起播满缓冲后应在 ~1-3min 内 Auto 升 1440(YtSabrAbr 行 sus= ≥ 13411K 且 bw= 过门槛)
- [ ] 真慢网络(供给 < 13.4M)时不误升;sustained 分母修正后 315 防卡死行为不回归(慢网升 4K 应仍被门②/③挡住)

---

## 11. 2026-08-30 「升 4K 后贴地滑行不降档 → 看门狗重载」:水位急救降档 + 升档 est 重锚

### 现象(真机 logs_live.log 20:17-20:20,Sony BRAVIA,v3.0.7 调试包)

- 20:17:47 Auto 爬到 308(1440p),20:18:19 升 315(4K)——升档当时合法(est 41M ≥ 26.6M×1.25、sustained 31M ≥ 26.6M、无降档记录)
- 随后网络劣化:单段 ~26.5MB 从 3.3s(60-63Mbps)涨到 7.8s(**27-32Mbps**),贴着 4K 声明码率 26.6M 滑行
- 缓冲 35.6s → 30.8 → 24.8 → 14.6 → 5.4 → **4.0s 全程一个档没降**(`down=1` 候选一直在),20:19:36 stall 看门狗触发 auto-retry → 会话 evict → 全新会话从 480p 重新爬 = 表现为「重载」
- est(bw=)整个滑行期报 35-52M,直到最后一刻(20:19:35,缓冲 4.0s)才塌到 15.3M——降档判据 `est < 声明码率` 这时才过,为时已晚

### 根因:降档判据依赖的 est 滞后,缓冲水位这个最硬的供给证据反而没参与判定

- est 是 20s 累计字节/累计时间滑动均值:升 4K 前两笔 60/63Mbps 大突发样本滞留窗口,劣化后旧快样本+滑行 gap 扣减把 est 长期抬在 35M+;
- sustained(60s)同样被突发样本稀释(实测劣化期报 36-44M vs 真实 27M);
- media3 / HeightAware 都**没有以缓冲水位为依据的降档条件**(水位只用于升档门③)——这是标准 DASH ABR 的缺口,YouTube 网页版按 buffer drain 判降档。

### 修复(本 commit,两处)

1. **水位急救降档**(`HeightAwareAdaptiveTrackSelection.updateSelectedTrack` 头部新增分支):
   `bufferedDurationUs < 8s` 且 `≤ 上次评估水位`(仍在下漏/持平,排除起播/重填期的正常低点)且升档后过 5s 宽限 → **无视 est 直接降到下一个低分辨率档**(一步一档;落到可持续档缓冲回 8s 以上自动停)。log 行 `YtSabrAbr: buffer-critical downgrade: ...`。阈值 8s < LoadControl MinBuffer 10s、max buffer ≥30s,只有真供给不足摸得到。
2. **升档 est 重锚**(`SabrMediaFetcher.reseedActiveWindow`,HeightAware 升档分支调用):升入新档瞬间清空活跃 est 窗口,种入「新档声明码率 × 4s」合成样本——旧档突发样本立即失效,est 从声明码率起步平滑接管真实样本;新档扛不住时 est 快速下探、正常降档不再迟钝。log 行 `YtSabrAbr: upshift reseed: ...` / `YtSabr: bw reseeded: ...`。仅升档调用(降档时 est 高估无害,不清)。

### 待真机复测

- [ ] 4K 贴地滑行场景:缓冲到 8s 前应出现 `buffer-critical downgrade`(4K→1440p),不再走到 stall 看门狗重载
- [ ] 重锚生效:升档后 YtSabrAbr 行 `bw=` 应立即回落到新档声明码率附近,而非滞留 40-70M
- [ ] 好网络回归:4K 正常播放时不应触发水位降档(缓冲 ≥8s 且水位回升);正常升降档无横跳(3min 冷却仍在)

---

## 12. 2026-08-30 「Auto 不升 1440p、手动切正常」:升档乘数 ×1.25 → ×1.1 + sus 垃圾尖峰修复

### 现象(真机 logs_live.log 20:45-20:52,高码率源,Sony BRAVIA)

- Auto 爬到 303(1080p)后不再升 308(1440p),`up=1` 候选全程都在;手动切 1440 流畅 → 管道够,是升档门槛问题
- 本视频声明码率整体虚高:303=22.26M、308=41.56M、315=110.5M(正常 1080p≈5.7M/1440p≈13.4M)
- 升 1440p 两道门:①活跃 est ≥ 41.56×**1.25**=51.9M ②sustained ≥ 41.56M。实测管道 44-75Mbps(fetch 逐笔),但活跃 est 滑动均值(掺 0 供给 gap 样本)整场天花板 ~40.6M,望 51.9M 永不可及 → 门①挡死
- 铁证 20:47:14.750 `bw=32947K sus=55972K up=1`:门②已过(sus≥41.56M),只差门①的 51.9M
- 附带:该会话 sus= 多次出现 494511K/635257K(500M+,物理不可能)——`activeSpanMs.coerceIn(1_000,…)` 在停闸空窗接近全跨度时把分母夹到 1s 所致

### 修复

1. 升档乘数 ×1.25 → ×1.1(`HeightAwareAdaptiveTrackSelection`):门①原防「突发撑高 est 升完必卡」,升档重锚(§11)已结构性消除该风险(升完 est=声明码率起步、扛不住立刻塌+水位急救兜底),乘数只保留防临界抖动余量;1.25 在声明虚高的视频上把门槛抬出活跃 est 天花板,升档永批不下来
2. sus 证据不足返回 -1(`SabrMediaFetcher.getSustainedBitrateEstimate`):gap 扣减后跨度 <15s(原夹到 1s)返回 -1 回退活跃 est,不再产生 500M 垃圾值

### 待真机复测

- [ ] 高码率源(本视频)Auto 应能升 1440p:门①≈45.7M,活跃 est 恢复期 40-56M 域应有通过窗口
- [ ] 好网络:4K(110.5M 声明)仍大概率批不下来(isinstance est 天花板 ~40M < 121.6M)——4K 实际消耗远低于声明,若需 4K 再议「用实测消耗替代声明码率做门槛」另案
- [ ] sus 垃圾尖峰应消失(YtSabrAbr 行 sus= 不再 >100M)
- [ ] §11 三项复测不回归(水位急救、重锚、无横跳)

---

## 13. 2026-08-30 实测码率校准门槛(×1.1 仍卡 720→1080 临界后的根治)

### 现象(b94378d 包,真机 logs_live.log 21:03-21:05,同 §12 高码率源)

- Auto 爬到 302(720p60,声明 11.25M)后钉死到会话结束;303(1080p,声明 22.26M)门槛 ×1.1=24.5M,全会话遥测 bw 天花板 23.37M、26.26M(sus)——**连续两头擦线不过,差 5-8%**
- 32:44-46 结构性证据:管道 fetch 实测 29-57Mbps,但 est = 18-24M:
  - 起步 pacing gap 全额入账(runway=-65 快照滞后 → coast=0,15 个 ~2s 的 0 供给样本)
  - 每个停闸周期漏 5s(42.8s gap 只免掉 37.9s,coast 用 gap 起始 runway,扣不掉后续播放消耗)
- 结论:×1.25 → ×1.1 连续两轮都是治标——声明码率虚高 ~2×(302 声明 11.25M 实测 6.3M、303 声明 22.26M 实测 ~11.5M,两个独立口径:段字节数、缓冲增速,互相吻合)+ est 被污染读低,双失真下任何乘数贴脸

### 设计(复盘通过,见上节对话推演)

全部候选档门槛换地基:required = candidateDeclared × **calib**(当前档实测消耗/声明,clamp [0.35, 1],未实测退 1.0=声明行为),乘数取消 ×1.0:

1. `SabrMediaFetcher.getMeasuredBitrateBps(itag)`:MEDIA_END 挂账(itag → bytes + 段数,单调 seq 去重),码率 = bytes×8000 / 段数×平均段时长(INIT duration/endSegmentNumber);<3 段返回 -1(起播首 ~16s 维持旧行为)
2. `HeightAwareAdaptiveTrackSelection`:当前档 declared×calib = 实测消耗 → **降档判据落到真实消耗**(est 起伏 18-24M 不再每停闸周期误降);升档候选按同内容系数外推真实需求;门②持续带宽也改对校准门槛
3. **升档重锚基准同步校准**:锚 declared(声明虚高源的档一升完 est 就"够再下一档"→连环误升);锚 declared×calib = 预估真实值,需真实带宽顶上来才续爬
4. `recordFetchGap`:runway<0(快照滞后)→ 全额当需求空闲,不再惩罚 est

### 复盘结果(按 21:03-05 日志决策级重放)

- 卡 720 那场:eff(303)≈11.1M → 21:03:55-57 升 1080p(实际:5 分钟钉 720);eff(308)≈20.8M → 21:04:48 升 1440p;315 不虚火
- 长期停闸复盘:停闸期 est 冻结不漂移(实测 17.6M→18.1M),恢复头两拍 sus=5.2M 被 gate② 挡住(正确),sus 5.7s 恢复后放行;est 污染窗口 18M vs 实测 6.3/11.5M 门槛裕量 >50%,误降消失
- §11 4K 重载案例:新策略下 4K 门槛 ~57M,27M 管道根本升不上去,事件从根上不发生

### 待真机复测

- [ ] 同视频 Auto:~10s 内升 1080p、~1min 内升 1440p(YtSabrAbr 行新增 **meas=** 可核对实测值与 calib)
- [ ] 无误降:升到 1080p/1440p 后跨停闸周期不应掉回(降档只由水位急救或 est<实测消耗触发)
- [ ] 4K:声明 110.5M×calib≈44-57M,今晚 pipe 不够则稳定留 1440p,不虚火
- [ ] §11/§12 各项不回归(水位急救、重锚、无看门狗重载、无横跳)

---

## 14. 2026-08-30「Auto 升 1440p 后 1s 打回 → 3min 冷却锁死在 1080p」:Format.id 前缀致 calib 死锁 + 回降宽限

### 现象(82b8119 包,真机 logs_live.log 21:27-21:29,新视频,声明值正常)

- 21:27:13 Auto 爬梯正常(302→303),21:27:31.79 **升 308(1440p)成功**(bw 15.6M ≥ 声明 14.48M)
- **1 秒内(21:27:32-35)打回 303** → 记 lastDowngrade → 3min 升档冷却 → 21:27:32-21:30:32 锁死 1080p
- 21:29:30 用户手动切 1440(fmts=308 单轨组)正常播放——管道没问题的又一例证
- 三条 `upshift reseed` 全部 `calib=1.0`,而同帧 ABR 行 meas=1761K/3572K 有值 → **实测校准整场没生效**

### 根因一(calib 死锁):media3 TrackGroup/Merging 层重写 Format.id

`HeightAware` 用 `Format.id.toIntOrNull()` 拿 itag;真机重锚日志实证 id 实为 **"0:302"**(media3 源格式在 Merging/TrackGroup 处理时被子序号前缀化)→ toIntOrNull=null → currentItag=-1 → calib 恒 1.0 → 门槛退回声明行为(声明值正常的视频靠运气加深爬梯,虚高的照旧卡死)。

### 根因二(打回-锁死循环):重锚把 est 精确锚在新档门槛上

- 21:27:31.79 升 308:重锚 est baseline = 14483466(calib=1.0 → 精确=声明值=308 门槛)
- 新档 init/段请求起步期(缓冲 0.7-10s,runway 低)几笔 0 供给 gap 样本把 est 拽到 14483K 以下
- 下一次评估 `required(308) > effective` 成立 → 打回 303(打回走 `f.height<currentHeight` 分支记 lastDowngrade)
- 3min 冷却:期间无论 bw 多高 enableUpgrade=false → 21:29:30 用户手动切档中断观察

### 修复

1. `itagOf(Format)`:id 取最后一个冒号后段再 toIntOrNull,兼容 "0:302"/"302"/null → calib 真正生效(本视频 calib≈0.47,308 门槛 14.48M→6.8M)
2. 升档后 10s 禁止 est 回降(best 落在 lower height 且距 lastUpgrade<10s → 维持现选):起步期不依赖 est 边界判定,真饿由水位急救路径(5s 宽限)兜底

### 待真机复测

- [ ] 重锚日志应出现 `calib=0.xx`(非 1.0)——"0:" 前缀确认在本设备复现
- [ ] 升 308 后 10s 内不回落;停闸周期恢复也不落(门槛=实测×calib,est 污染窗口裕量大)
- [ ] 4K(声明 28.45M×calib≈13.4M)本管道 est 19-40M 可能真升 315——观察是否扛得住(扛不住应走水位急救一步降回,非看门狗)

---

## 15. 2026-08-30「没稳住 1440p」:calib 新采样噪声把 4K 误批 + 冷却锁死救回后的档位(终局:冷却取消)

### 现象(r1732 包,真机 logs_live.log 22:02-22:14,新视频,315 声明 105M)

- 22:10:19 升 308(1440p)后 3 秒就决策 315(4K):此时 calib 来源 303 刚升入只有 3-4 段样本,比值偏低被压到 **0.35 地板** → 4K 门槛 105M×0.35=36.9M,est/sus 40-60M 批过
- 4K 真实消耗 ~21M、pacing 有效供给 ~20M(活跃吞吐 60M+ 但停闸摊薄)→ 贴地,74s 后缓冲 5s → **水位急救 315→308(22:11:49)→308→303(22:11:57)→302**,全程干净(零 stall 零 watchdog,§11 机制按设计工作)
- 22:12:02-06 重新爬回 303,缓冲回填满 45s、门槛 12.7M 可负担——但 22:11:57 降档重置冷却 → **3min 冷却硬锁到 22:14:57** → 用户被迫 22:12:39 手动切 1440(单轨组,播得动)

### 两处修复(82b8119 之后的迭代)

1. **calib 成熟度地板**:实测段数 <5 → 门槛地板 0.65、<12 → 0.5、≥12 才 0.35(`getMeasuredSegmentCount`)。第一分钟内刚升档的新采样(噪声)不再把高档门槛拉穿——22:10:19 那一刻 4K 要求 105×0.65=68M,直接批不下来
2. **3min 升档冷却取消(用户决策)**:降档后仅凭「缓冲 ≥30s 才许升」门槛回弹;横跳防护由 30s 缓冲闸 + 升档后 10s 禁止 est 回降 + 重锚基线承担。22:12:05 那类状态(缓冲满、bw 23M vs 门槛 18.1M)即刻回 1440p,不再干等 3 分钟

已知残余:4K pacing 供给≈消耗(20-21M)本就临界,成熟采样后仍可能在 est 高位(>43M)瞬间获批→漏光→急救降回,形成分钟级 315↔308 循环;最坏情形无看门狗重载、每步干净(init+重锚),属可接受边界;若真机观察到循环过频,再议「顶档升档要求 sustained ≥ declared×0.6」专项。

### 待真机复测

- [ ] 升 308/303 后重锚日志 `segs=` <5 时 calib ≥0.65,4K 不再 3s 内获批
- [ ] 急救降回后缓冲回满即自动回 1440p(无 3min 空窗),无需手动切
- [ ] 315↔308 循环若出现,记录频率与每次是否水位急救(非 watchdog)

### 2026-08-30 追加(方案B,用户决策,96d2390 后)

r1735 复盘通过(急救降回秒级回档、零 watchdog),唯一遗留:本视频 4K pacing 有效供给 (~20M) ≈ 真实消耗 (~21M) 天生临界,升 315 → 60-95s 漏光 → 急救降回 → 秒级爬回,分钟级 315↔308 干净循环。方案B:顶档(组内 height ≥2160 的最高档)升档额外要求 `sustained ≥ declared×0.6`(TOP_TIER_SUSTAINED_PERMILLE,声明虚高 ~2× 故 0.6 远低于真实消耗;本视频 63M vs sus 峰 57-59M → 4K 不批,稳 1440p;千兆管道 sus >63M 照常上 4K)。

## 16. 2026-08-30「升 2160 又失败」:声明码率口径修正 peak → averageBitrate + calib 取消 + 顶档 ×1.1 重标

**现象(23:00 前后真机,视频 4fBaRNYSSOY)**:ABR 按既定机制一路爬到 4K(itag315)后网络塌方,降档 1 步没救回来,水位急救/stall #1→#2 全走完,整段重载回 720p。

**决策级复盘——门槛"全部合法通过",失真在口径**:

| 时间 | 事件 | 判据数据 |
|---|---|---|
| 22:59:25 | 窄选起步 [298,302] | bw=14K |
| 22:59:48 | 升 308(1440p) | calib=0.751(segs=0),reseed=12.03M |
| 22:59:56 | 升 315(32.3M peak 声明) | calib=0.779 → 门槛 25.2M;est 31.2M 过;顶档 gate 0.6×32.3M=19.4M,sus 28.4-34.5M 过 |
| 23:00:39 | **rn=19 fetch 仅 942B/1.8s** | est 40509K→6160K 崩塌起点 |
| 23:00:55 | buffer-critical:315→308(一步) | bufS=4s,供给已 ~7Mbps(15s 下 15MB) |
| 23:00:57 / 23:01:24 | stall #1 → retry #2(同位置 72534ms);rn=21 47MB/29.6s=12.7M | 连 1440p 都养不起 → 整段重载回 720p |

**联网查证口径**(本节关键结论):InnerTube `/player` 每格式自带两个字段——`bitrate`=**VBR 峰值**、`averageBitrate`=**真实平均**(≈ contentLength/duration);VBR 视频上 peak 比真平均高 ~60-75%(实测样例 itag137:2.0M peak vs 1.19M avg,clen/dur 验证相等)。Google 官方 VP9 VOD 建议 2160p60 编码目标 ~18M;JDownloader itag 表 VP9 4K 标 ~20M;本视频 315 实测消耗 23.4M(与区间吻合)。**此前的声明显然虚高,而 calib/顶档 0.6 全部在 peak 口径上叠修正,连环补偿注定顾此失彼(105M 声明 vs 本视频 32.3M 声明结论相反)。**

**修改(已实施)**:
1. **resolver 全链路 declared 换 averageBitrate**(YoutubePlaybackResolver):`buildSabrTrack`/`parseFormat` 优先 `averageBitrate>0` 回落 `bitrate`;NewPipe raws 自算 `clen×8/durMs`(extractor 已解析进 ItagItem,`Stream.getItagItem()` 可达),Piped 无字段填 0 回落 peak(旧行为);
2. **ABR calib 机制整体取消**(用户决策):required=f.bitrate 裸判据;成熟期 calib 本就收敛 ≈1,只去掉未熟期折算噪声(采样/地板全删);
3. **顶档门槛保留、基准重标**:sustained ≥ declared×**1.1**(旧 0.6 是 peak 虚高修正;真平均=实需后,1.1 是 60s 均值口径的 VBR 尖峰余量)。本视频 315:门槛 19.4M→~25.3M,塌方段(sus 8M)永不批;
4. 升档重锚锚裸声明 `reseedToBitrate(newDeclared)`,日志去 calib 段。

**参照源码**:NewPipeExtractor fork(extractor/src/main/java/…/services/youtube/YoutubeStreamExtractor.java:1407-1410 已设 contentLength/approxDurationMs;ItagItem.getBitrate=peak)。web 佐证:FOSWLY/vot.js 类型定义、youtube-ext VideoFormat 文档、原始响应 gist(bitrate vs averageBitrate 实测)、developers.google.com/media/vp9/settings/vod。

**未决(下次观察)**:水位急救只降一步,bufS<4s 且已跨两档可降时是否直接跳两档?本次不改,先看口径修正后的表现。

## 17. 2026-08-30「4K 边缘档反复横跳、级联切档卡顿」:顶档定向冷却 3min

**现象(23:28-23:31 真机,新代码 babff35 已生效:重载/stall 零次、降档全走水位急救、reseed 无 calib)**:视频 4fBaRNYSSOY 的 4K 真实消耗 ~30-31M(meas 实测),网络持续供给在 27-42M 晃——供给 ≈ 需求的边缘档。循环:重填期(播低档,管道空闲)突发 est 40-60M、sus 41M 过升档门槛(32.3M + 顶档×1.1=35.5M)→ 升 4K → 边播边吸 pacing 供给 ~30M,buffer 40s 漏到 5-6s → 水位急救**级联**(315→308→299,每步拉新 init=一次卡顿)→ 低档重填到 30s+ → 又过门槛 → 再升 4K。3.5 分钟两轮完整循环,用户感知「不重载但一直在切、有部分卡顿」。

**结论**:这不是回归,是供给 ≈ 需求时边缘档的必然震荡;防抖缺失。

**修法(已实施,用户选定方案1)**:水位急救从**顶档**(height≥2160)降下时,`excludeTrack(leavingIndex, 180s)`——顶档 3 分钟内不参与候选;期间 1440p/1080p 升降完全照常。与 §15 已取消的「全档 3min 升档冷却」本质不同:那个把全部升降锁死、用户被打回 1080p 后连 1440p 都升不了;本冷却只锁刚崩的顶档,可持续档位照常工作。非顶档的水位降档不加冷却(降的是可持续档,回弹无碍)。冷却到期自然恢复试顶档;期间想立即回 4K 走手动切档。3min 覆盖一个完整误批-回填周期(实测周期 ~55-85s)。

**未决**:级联降档(315→308→299 三步三次卡顿)是否在水位 <4s 时跳两档——未做,待观察顶档冷却落地后的实际体感。

## 18. 2026-08-31「1440p↔1080p 临界来回切」:降档滞回余量 ×0.85(双阈值死区)

**现象(00:01-00:04 真机,新视频,308 声明=真平均 16.76M)**:00:01:40 est 17.9M 升 1440p → 00:02:13 est 滑到 15.8M(只差门槛 16.76M 的 6%)→ 立即降 1080p → 00:03:37 est 17.9M 又过线升回。全程 buffer 34-40s 充足,纯 est 穿线切换,每次拉 init 段=一次卡顿,3 分钟三轮。

**根因**:declared=真平均后,est 的巡航值(供给滑动估计,含 pacing/gap 样本)天然骑在相邻档门槛 ±10% 区间——这是常态而非异常。旧判据 `required > est` 无降档滞回,单样本穿线即降;降档后又因「3min 冷却已取消 + 缓冲 ≥30s」立即放行升档,est 回线即弹回 → 临界档循环。alpha.2 的顶档冷却只管 315 水位降档,不管稳态 est 穿线,管不到这。

**修法(已实施)**:classic ABR 双阈值滞回——**当前档(i==selected)降档判据 = required×0.85**,升档候选门槛保持 required 全额(预留 15% 死区)。est 15.8M > 14.25M → 稳守 1440p;真饿(est<14.25M)照降,水位急救(<8s)兜底。分工:临界抖动归滞回、真饿归水位、升档起步期归「10s 禁回降」、边缘档回弹归「顶档定向冷却」。四处机制互补不重叠。

**未决观察**:×0.85 是否需要按档位差异化(如 1080p 以上收紧到 ×0.9)——先看实际体感,单值够用就不加复杂度。

## 19. 2026-08-31「起播一直 2160 然后 ~16s 一轮无限重载」:冷启动爆发样本直跳顶档 + stall 重载无记忆

**现象(20:04-20:05 真机 logs_live.log,51 分钟视频 sid=p6mdaGH8aODvZYiRlM1kJA)**:起播 720p 首帧后 34ms 松开起始高度 cap → ABR 首评即从 720p **一步直跳 itag315(2160p,声明 26.9M)** → 切轨发生在 pos=0 首帧前 → 4K 数据其实全部拉到位(rn=2 一次 28MB/2.7s=84Mbps,init+seq1 10MB+seq2 18MB,c2.mtk.vp9.decoder 重建成功 output format 3840x2160 都出来了)→ **但之后 6s 再无任何 SABR fetch 发出**,播放器停在 BUFFERING/视频轨 null/`buffered=0%` 永不 READY → 9s 后 stall 看门狗 `auto-retry #1 @pos=0ms` **整链路重启**(metadata→playurl→cdn→prepare)→ 回到 720p READY → `recovered, counter reset` → 34ms 后同样误判再跳 4K → 再 stall。三连循环 @pos=0/138/232ms,每轮 ~16s,永不升级(每轮都是 retry #1,恢复发生在 720p 段把计数器清零)。

**根因一(冷启动误判,§本档)**:升档 sustained 闸门被整体废掉——`SabrBandwidthMeter.getSustainedBitrateEstimate()` 在持续带宽证据不足(<15s 跨度,fetcher 返回 -1)时**回退到活跃传输 est**,而活跃 est 窗口刚被 2 个 720p 段的爆发样本(rn=0 2.5MB/0.3-1s、rn=1 3.7MB/0.5s → 20-86Mbps 突发)撑到 33-58M,顶档门槛 26.9M×1.1=29.5M 「合法通过」。首帧后松 cap 触发重建 selection(2 轨→6 轨组),新实例首评 currentHeight=720、canUpgrade=true(无降档史)、sustained=假值 → 720p 直跳 2160p,且 reseed 把 est 锚死在 26.9M + 10s 禁回降。

**根因二(重载无记忆)**:看门狗 auto-retry 走整链路重启,ABR/带宽窗口/excludeTrack 冷却全部随实例销毁清零;每次恢复都在 720p 段(counter reset),同一个爆发误判必然复发——无状态机的同坑循环。

**修法 ①(冷启动防直跳,HeightAwareAdaptiveTrackSelection)**:
- sustained 改用**原值**(`SabrBandwidthMeter.getSustainedBitrateEstimateRaw()`,新增,-1 不回退)——证据不足(<15s)时 `sustainedEvidence=false`,**一律禁升档**;首档由起始选轨/高度 cap 决定,起播不被卡,证据成熟(连续拉流 15s 墙钟)后自然放行。
- **升档逐级爬**:候选只允许「下一个更高分辨率档」(未排除轨中最小严格更高 height;同 height 多 codec 变体全部放行)——爆发 est 误判的最坏后果从「一步到顶 4K」降为「多升一档」,真扛不住由滞回(§18)/水位急救/顶档 sustained×1.1 接管。降档不在此限(放开全部低档一步落位,2026-08-27 既有语义)。
- 诊断日志 `YtSabrAbr` 的 `sus=` 同步改打原值(旧打的是回退值,误导取证——20:04 日志里 sus=32884K 实为 -1 回退成活跃 est 的假值)。

**修法 ②(跨重载记忆,SabrAbrMemory 新单例)**:PlayerScreen stall 看门狗触发时,若 YouTube 请求且 `pos < 30s`(冷启动误跳期)→ `SabrAbrMemory.noteStartupStall()` 记进程级时间戳;重载后**新建**的 HeightAware 实例在 3min 冷却内跳过顶档(≥2160)候选(日志 `top-tier startup-stall cooldown: skip itagX(2160p), remain Ns`,每实例一次)。低档升降/手动选档不受影响。单例进程级不按 videoId:重载后立刻重进同一视频正是主场景;误伤面 = 起播 stall 后 3min 内换看其它 YouTube 视频不自动上 4K,可接受。

**log add(③切轨停发取证,DefaultSabrChunkSource)**:rn=2 之后 getNextChunk 再未被调用是「loader 停发」的直接死因,但其卡点在 media3 内部还是 chunk source 内部无证据。补四类打点(均为 chunk 粒度低频):
- `updateTrackSelection`:切轨时刻 sel 旧→新(高度)+新轨 chunkIndex 状态;
- `chunk completed`:init/media 交付回执(itag/bytes/当前 sel);
- `shouldCancelLoad=true`:在途 chunk 被取消(切轨取消链路);
- `chunk load error` + `getNextChunk → endOfStream(...)`:错误与拒发路径。
下轮真机若再现「起播切轨后停发」,由这些日志界定:有 `updateTrackSelection`+`chunk completed` 但无后续 `YtSabrAbr`(getNextChunk 入口日志)→ media3 loader 侧卡死(需往 sample stream 重建方向查);有 endOfStream/取消 → chunk source 侧。

**待真机复测**:①起播应稳在起始档 ≥15s,然后 1080→1440→2160 逐级爬(真网络扛得住才继续);②若起播仍 stall,重载后日志应出现 `top-tier startup-stall cooldown`,顶档 3min 不进候选,循环即断;③切轨停发的直接死因由新增日志定位。

### 19.1 2026-08-31 20:28 真机首验:死循环已断,但「无证据一律禁升」矫枉过正——升档拖一分钟

**现象(20:28-20:30 真机,修复后首验)**:零 stall、零重载、逐级爬生效(修复核心工作);但 720p 卡了 **53s** 才升 1080p,**115s** 才到 1440p,用户报「升档怎么要一分钟」。

**归因(日志逐帧)**:首灌 11s 灌满 ~48s 缓冲期间,每次 chunk 都有 ABR 评估机会,但 `sus=-1`(显示为 0K)→ 首版「无证据一律禁升」把非顶档梯子也冻死;随后缓冲满 → loader 停拉 → **42s 零次 getNextChunk = 零次评估**(ABR 只在 getNextChunk 时跑),梯子错过天然窗口后要等缓冲漏到 10s 才有下一次;15s 证据成熟期正好落在满缓冲空闲期里。之后每次升档 reseed 又把 est 锚死在新档声明值(5.7M),est 爬回 1440 门槛 13.4M 又花 ~2min。

**修订(2026-08-31 二版)**:
1. **撤「无证据一律禁升」**:非顶档冷启动照常爬(活跃 est 门 + 逐级爬约束,误判最坏=多升一档可回退);**顶档防死循环不受影响**——顶档 ×1.1 闸读 sustained 原值,-1 恒小于门槛,冷启动 4K 依然自动被挡。
2. **SUSTAINED_MIN_SPAN_MS 15s→10s**(SabrMediaFetcher):ABR 评估饥饿是结构性约束(满缓冲零评估),首灌窗口实测 ~11s,15s 成熟期落在空闲期=顶档永远要等 ~50s;10s 让顶档证据在首灌窗口内成熟。
3. **冷启动升档跳过 reseed**:锚点把 est 压在新档声明值,梯子每步都被拖(20:29:04 升 1080 锚 5.7M,est 从 2.8M 爬回 13.4M 花 ~2min);证据成熟后的稳态升档保持重锚语义不变。误判兜底由水位急救/滞回承担。
4. 显示修正:`sus=0K` 实为 -1(整数除法 -1/1000=0),负值原样显示,不再误导取证。

**诚实判断**:本视频管道 sustained 实测 ~20-25M < 4K 需求 29.5M(26.9M×1.1),最终停在 1440p 是正确结果非 bug;夜间带宽好转后 4K 自然放行。

**待真机复测**:起播几秒内应完成 720→1080→1440 阶梯(首灌窗口内逐级);4K 仅当 sustained ≥ 声明×1.1(本网络 ≈ 停 1440p 正常);顶档 stall 冷却与切轨停发取证日志维持原验证点。

### 19.2 2026-08-31 20:45 真机二验(P11-75c 后):续播场景重建窗口连升两档→看门狗死循环;冷启动锁档 10s

**现象(20:45-20:46 真机,续播 pos=1569s)**:READY(720p) → 首帧松 cap 重建 6 轨组(**样本队列整体丢弃**,BUFFERING video=null) → 36ms 后梯子升 299(1080p) → 4s 后再升 308(1440p,首档尚未渲染)→ 新档 init 请求(带 2s 服务端 backoff)排不上队 → bufS=0.-9(负载位置落后播放头,playhead 前方无数据)→ 视频轨永不激活 → 8s 看门狗 `stall @pos=1569901ms buffered=50%` → 整链路重载 → 续播同剧本再演,3 轮循环,每轮 retry #1(pos>30s,②的起播 stall 记忆不触发;且卡在 1440p 非顶档,记忆也救不了)。

**根因**:P11-75c 撤掉证据门后,冷启动梯子**没有任何水位/时间门**——cap 释放重建是每次会话必经的队列整丢点(起播 pos≈0 便宜、续播 pos=1569s 同样整丢刚灌的 6s),重建窗口内梯子连升两档,切轨请求挤进重灌期撑爆 8s 看门狗。19 版的 20:04 死循环是「重建窗口 + 直跳顶档」;本轮是「重建窗口 + 连升两档」——重建窗口是共同死因,梯子必须绕开它。

**修法(已实施,用户拍板「起播锁档 10s 没问题」)**:`canUpgrade` 加冷启动时间锁——selection 实例创建(≈重建时刻)后 **10s 内禁升档**(死亡窗口 8.8s,10s 覆盖;比 30s 水位门可预测,慢网络下灌 30s 缓冲会拖更久)。期满梯子自由爬:档内 ABR 切换**不丢样本队列**(队列整丢只发生在选组重建),无缝。原「首档豁免(lastDowngrade=0 不受 30s 水位限)」被锁取代;降档史语义不变(降档后仍需 ≥30s 缓冲回升)。

**遗留(下轮修)**:①**僵尸拉流**——重建丢弃旧 sample stream 后,在途 chunk load 未被取消:`SabrDataSource.open()` 同步阻塞在 `fetcher.getNextSegment()`(SABR POST 循环),media3 的 cancel 打不断;本轮该僵尸 6 次重复拉同一 seg=336(各 3.3MB 共 ~20MB 废流量),独占串行 fetcher 4s,把新轨 init/段请求全堵在后面,是重灌窗口被撑到 9s 的放大器。修法方向:getNextSegment 支持取消中断(DataSource.close 置位,循环检查);②seg=336 重复 6 次的 getNextSegment 内部循环终止条件需查(一次段请求拉了 6 个 POST)。

**待真机复测**:续播(pos>30s)起播应在 720p 稳住 ~10s(重建后 1-3s 内视频轨激活、READY),10s 后无缝逐级爬;全程零 stall 零重载。若仍卡,看新增 chunk 日志定位僵尸拉流占比。

### 19.3 2026-08-31 21:01 真机三验(P11-75d 后):10s 锁生效,但白名单丢包饿死续播重灌——在途请求 itag 入白名单

**现象(21:01-21:02 真机,续播 pos=1569s)**:P11-75d 的 10s 锁工作正常(重建窗口内零 upshift,恢复后梯子 21:03 正常爬 303→308→315),但**首次续播仍在重建后 ~10s 饿死**:`READY(720p)→ 重建(队列整丢,BUFFERING)→ 9.9s 后 stall @pos=1569947ms buffered=50% → 重载`,第二轮恢复仅 2.2s 后正常。

**真凶(§19.2 预判的「僵尸拉流」实锤 + 精确机制)**:重建后新 6 轨选组初始档=index 5=**itag302**(720p webm,bitrate 降序组的末位),`selectFormat` 把 fetcher 的 `videoFormat` 从 298 翻成 302;而在途旧 chunk(298 seg=336)还在 `getNextSegment` 循环里——processPart 的**广告白名单 `[audioFormat, videoFormat]=[139, 302]` 把服务端每次都回来的 298 段数据当广告丢弃**(`skip ad/unrequested MEDIA_HEADER itag=298`)→ `hasSegment(336)` 永假 → 六连重试(每次 POST 3.3MB,含一次 5.4s 慢响应)独占串行 fetcher **8.5s** → 新轨 init 21:01:59.99 才被服务,看门狗 21:02:00.81 开枪,**差 0.7s**。第二轮无在途冲突所以 2.2s 即恢复。

**修法(已实施,SabrMediaFetcher)**:白名单 = 当前选中格式 **+ 在途段请求 itag 集合**(`pendingRequestItags`:getNextSegment 进入时加、finally 移除;MEDIA_HEADER 与 FORMAT_INITIALIZATION_METADATA 两处过滤同步并入)。在途请求的响应不再被丢,旧 chunk 一次 POST 即完成(<1s),串行 fetcher 不再被占。广告防御语义不变:从未被请求过的 itag 照丢(广告段只可能出现在非请求 itag 上)。

**待真机复测**:续播应在重建后 1-3s 内出画面(720p 稳 ~10s),全程零 stall 零重载;日志不应再出现 `skip ad/unrequested` 丟在途 itag + `getNextSegment: no seg ... (retry)` 连发。若仍有饿死,查 getNextSegment 重试循环的其他出口。

## 20. 2026-08-31「23:25 中段重载」:冷却到期即回 4K → 漏 49s → 水位急救差 0.6s 输给在途(A/B1/B2 三修)

**现象(23:20-23:25 真机 logs_live.log,视频 IW5NIdD1sGc,315 声明=真平均 26.6M)**:起播两次 stall(pos=0,23:20:41/57)记入顶档冷却 180s → 23:22:36 重开后正常爬到 1440p,23:22:55 冷却剩 62s 正确跳过 4K → **23:23:57 冷却到期,2s 内 ABR 立刻回 4K**(23:23:59,rn=33 init itag315)→ 缓冲 26.3s→7.0s 匀速漏 49s → 23:24:48.052 水位急救 315→308 + 重拉 180s 冷却,但 **2ms 前已 staged 的 rn=37 仍载 4K 段**(3 段 39MB,seq32-34,7.6s 传完)→ 7.0s 缓冲 < 7.6s 在途 → 23:24:55 缓冲见底 BUFFERING(pos 冻 134051ms)→ 8s 看门狗 23:25:03 auto-retry #1 **整链路重载**。重载后新冷却把 4K 挡住,480→1440 稳定——用户所见「重载后降档正常」。

**根因解剖(三层延迟叠加,8s 阈值结构性必败)**:

| 层 | 机制 | 本例耗时 |
|---|---|---|
| B1 评估盲窗 | `updateSelectedTrack` 只在 getNextChunk 跑,4K 段循环 10-14s(传输 6-8s+里程碑间隔) | 23:24:36(bufS=11.7)→23:24:48(bufS=7.0)间 12s 零评估 |
| B2 staged 积压 | 急救只影响后续 getNextChunk,救不了已 staged 的 3 个 4K 段;且 holder/selectFormat 在 updateSelectedTrack **之前**取(上游 media3 是之后),同一次调用 staged 的段也用旧档 | rn=37 载 315 seq32-34 |
| B3 串行管道 | UMP 一票在途,积压 39MB 传完才轮得到 1440p 请求 | 7.6s(39MB @41Mbps) |

4K 期间 est/sus 全程失明:活跃 est 只计传输窗口(30-36M 恒 >26.6M×0.85 滞回线),sus 在 23:24:14 后掉 -1(证据窗失效);fetcher 排队间隔被旧 10s 滑行余量扣成 coast(水位 16.4s 下 14.2s 间隔,coast 扣 6.45s)→ pacing 有效供给 ~16M 在两个口径里都不可见。**8s 阈值的最坏反应线 = 盲窗 14s + 积压排空 8s + 替换段传输 4s ≈ 26s,结构性永远来不及**;本例只差 0.6s 是因为评估恰好撞在请求发出前 2ms。

**修法三件(已实施)**:

1. **A——顶档 sustained 分母收紧(SabrMediaFetcher)**:顶档在位时(当前视频 FormatId.height≥2160)`recordFetchGap` 的 sustained 滑行扣减余量 10s→20s(`TOP_TIER_GAP_RUNWAY_RESERVE_MS`):4K 失败期(水位 <20s)的 fetcher 排队间隔全额留在持续分母 → sus 塌到有效供给(~16M),冷却到期后的 4K 重准入闸(sus≥declared×1.1)被真证据挡住,不再被同一批低档突发样本「合法通过」。**只收 sustained 不收活跃 est**——est 若同步加严,千兆管道 4K 满缓冲滑行期(loader 停拉,缓冲 48s→10s 每周期 ~10s 空档)会被打成 0 供给样本误踢好管道。
2. **B1——顶档水位急救阈值 8s→20s(HeightAwareAdaptiveTrackSelection,`TOP_TIER_CRITICAL_BUFFERED_US`)**:顶档在位时水位 <20s 且评估间回落即触发(非顶档维持 8s 不动)——覆盖 B1+B2+B3 的最坏反应线。本例复盘:23:24:24 评估 bufS=14.5(自 20.5 回落)即触发 → 23:24:48 的请求载 1440p → 谷底 ~3-5s 活着,不触发看门狗。假阳性代价=180s 顶档冷却(升 4K 本就要求缓冲 ≥30s,重爬周期天然重叠,边际成本小);假阴性代价=看门狗重载(~11s 冻结+整链重启),不对称支持提前触发;千兆管道 4K 缓冲只涨不跌穿 20s 不受损。
3. **B2——决策后重读 holder(对齐上游 media3,DefaultSabrChunkSource)**: getNextChunk 的 staging holder 与 `fetcher.selectFormat` 移到 `updateSelectedTrack` 之后按新 selectedIndex 重读(决策前的候选/合成 iterator 语义不变,用 preSelectionHolder)——原顺序使切档决策对同一次调用 staged 的段无效,每次切档(急救/升档)白吃一个循环生效延迟。

**分工**:A 治「失败证据进 sus,防冷却到期后被同一批突发样本快速重准入」;B1 治「进来了也必须在死前退出去」(确定性,顺带拉 180s 冷却);B2 治「决策到生效多等一个循环」。三者互补;A/B2 不改变 4K 首次准入(那次凭的是 1440p 期真实突发传输,任何口径都拦不住,靠 B1 兜底退出)。

**待真机复测**:
- [ ] 同视频(或同边缘网络):4K 尝试应被 B1 在水位 ~14-20s 时踢下(日志 `buffer-critical downgrade: bufS=14s` 一类),零看门狗重载;
- [ ] 顶档失败后的 180s 冷却期内 sus 应塌到 ~16M 量级(非 30M+),冷却内 4K 重准入被挡;
- [ ] 千兆/好网络 4K 满缓冲滑行不误伤:staged 切档正常、est 不因满缓冲空档塌方;
- [ ] 水位急救触发的那次 getNextChunk,请求应立即载新档段(rn 不再夹带旧顶档段);
- [ ] 非顶档(≤1440p)水位急救仍在 8s 触发,行为不变。

## 21. 2026-09-01「升 1440p 视频冻住音频照播」:跨 codec 换解码器撞 codec 强制回收(VP9 粘性梯子 + 视频冻结看门狗)

**现象(00:30-00:32 真机 logs_live.log,新视频 1573s,v3.0.9-alpha.1)**:播放 ~1 分多钟后卡顿,之后**音频正常播、视频冻死**,画面清晰度显示停在 1080p 不动,用户手动退出。另:同晚 4K 段行为验证 A/B1/B2 全部按设计工作(会话 2:00:29:18 真实 sus=48.7M 升 2160 → 漏到 6.9s → 水位急救踢回 1440p + 180s 冷却,仅 2.3s 重缓冲,零看门狗)。

**根因(不是 ABR/网络,是解码器层)**:梯子 720p avc(298)→1080p avc(299,旧「同 height 保高码率变体」)→1440p 只有 VP9(308)→ 升 1440p **必然跨 codec 换解码器**。时间线:

```
00:31:25.5  avc 解码器原地重建(缓冲区账目乱,"discarded an unknown buffer")
            → MediaCodec::reclaim → 5.0s 后才 "Released by resource manager"
00:31:30.6  重建完成,继续播 1080p avc(~10s)
00:31:40.1  真正的 avc→vp9 切换 → reclaim 又卡 5.5s
00:31:45.7  vp9 就位(1440p 配置完成)——切换其实成功了
00:31:48.5  用户退出(距 vp9 就位 3s,第一帧还没渲染出来)
```

期间 ABR 已选 1440p 并在拉数据(升档发生)、渲染器停在 1080p 冻着(1440p 零帧渲染)、UI 清晰度显示跟实际解码高度(onVideoSizeChanged,显示=实际播放)如实停 1080p——三方一致。音频 aac 解码器独立,照常播;位置基 stall 看门狗(8s)因位置一直在走全程失明。

**联网查证(生态圈同类,平台层无解)**:[androidx/media #3059](https://github.com/androidx/media/issues/3059)(MTK 解码器,4K→2K 冻结,workaround `canReuseCodec`→`REUSE_RESULT_NO`)、[google/ExoPlayer #10369](https://github.com/google/ExoPlayer/issues/10369)(Amlogic STB,参考帧数不同档间切换解码器停吐帧)、[androidx/media #1615](https://github.com/androidx/media/issues/1615)(Pixel 6/7,官方 **bug: in platform**)。~5s 回收等待:AOSP [MediaCodec.cpp](https://android.googlesource.com/platform/frameworks/av/+/ea2b9c0/media/libstagefright/MediaCodec.cpp) 回收路径——codec 有未归还 buffer → `Can't reclaim codec right now due to pending buffers` → WOULD_BLOCK → 重试强拆(AOSP 等待 0.5s/次;实测 ~5s 为 MTK 定制 RM 重试策略)。同场对照:会话 2 同样的 avc→vp9 只要 **49ms**(旧实例干净,不走强制回收)——坑是概率性设备行为,app 不可治。media3 1.6+ 预热线(`experimentalSetEnableMediaCodecVideoRendererPrewarming`)只管播放列表条目切换,不适用流内 ABR。

**修法两件(已实施)**:

1. **VP9 粘性梯子(消触发面)**——HeightAwareAdaptiveTrackSelection 同 height 多 codec 变体改粘**全组顶档** codec(`isTopCodecVariant`,顶档必须从 fullGroup 取:窄选期 [298/302] 子集算不出整梯顶档;YouTube=VP9,顶档 avc 的视频自动反转):升档 302(720p vp9)→303(1080p vp9)→308→315 与水位急救降档全程单 codec,零解码器重建;旧规则会在 1080p 选 299 avc(码率 7.1M>303 的 5.7M),1440p 边界必换 codec,且降档路径(1440p→1080p)也会把 vp9→avc 引进来。起始档(窄选首评)同样粘 → 整场零切换。
2. **视频冻结看门狗(踩坑兜底)**——PlayerScreen 新增 `VideoFreezeThresholdMs=12s`:BUFFERING 挂死且**位置仍在前进**(音频驱动时钟)超阈值 → 走既有 auto-retry 恢复链(记 autoResumePositionMs、bump retryKey 重载续播、共享 MaxStallAutoRetry=2 预算)。阈值标定:必须让过合法解码器重建最坏情形(codec 回收 5.5s + 首帧 1-2s ≈ 8s)——8s 会正好打在恢复窗口里(本例 vp9 00:31:45.66 就位,8s 阈值 00:31:48.06 开枪,而用户 00:31:48.5 才退,差 0.4s 枪毙一个正在恢复的会话);12s 只兜不自愈的真挂死。与位置基看门狗分工:位置冻结(时钟全停)归 8s 老看门狗,「音频活视频死」归 12s 新看门狗。

**残余(已知不修)**:avc 解码器自身病倒原地重建(00:31:25 那道)是设备故障,粘性梯子消不掉,由看门狗兜底;VP9 拆起来干不干净无对照样本(vp9 从未当过被拆方),若将来出现 vp9 侧挂死,同样由看门狗兜。

**待真机复测**:
- [ ] 新会话梯子应全程 vp9:起始 720p 应选 **302**(webm)而非 298,升 1080p 应选 **303** 而非 299(YtSabrAbr 行 sel 对应 itag 核对);
- [ ] 升档到 1440p 应无解码器重建日志(无 `DMCodecAdapterFactory: Creating ... adapter for track type video`,无 `MediaCodec::reclaim`);
- [ ] 水位急救降档(1440p→1080p)应选 303 vp9,同 codec 无切换;
- [ ] 若再撞 codec 回收挂死(音频活视频死),~12s 应出现 `video freeze: BUFFERING ... with pos advancing, auto-retry` 并自动恢复,无需手动退出;
- [ ] 正常重缓冲(位置冻结)仍走 8s 老看门狗,行为不变。

## 22. 2026-09-01「07:22 一直黑屏,音频正常」:全档零帧渲染 × READY 态看门狗盲区(诊断三件 + 黑屏画质熔断)

**现象(07:22-07:25 真机 logs_live.log,同 1573s 视频,v3.0.9-alpha.2 后)**:续播 80s 起播后**全程黑屏但音频正常播**。5 轮会话:07:22:35 起(stall retry #1 @80s pos 12s 未出画)→ 07:23:00(READY 720p avc 298)→07:23:24 提前 ENDED →07:23:28(READY 1080p vp9 303,单格式会话)→ ~43s 再 launch →07:23:49(READY 720p,升档 4K 在途)→07:24:20 ENDED →07:24:24(READY 1440p vp9 308 单格式会话)→ 用户 11s 后手动退出。**关键证伪:720p avc 起始会话同样黑屏——§21「零帧只发生在 1440p VP9」不成立,当日故障与分辨率/codec 无关**。且位置/流完全健康:每轮会话 4-9MB 段连拉、bufS 到 47-48s(LoadControl 上限)、setFrameRate(60.0) 每轮 READY 都设上(解码器配置成功)、无 playback error、无 video size 上报(onVideoSizeChanged 零次)。

**另一未解**:前 4 轮在 pos≈100-128s 提前 STATE_ENDED(视频 1573s  never 播到头),每次 ENDED → reportPlaybackCompleted → 同视频重载循环——ENDED 时 app 无任何位置日志,无法判定是 sample 流早 EOF 还是时钟跳变(07:22 的 ENDED-position 诊断已加)。伴生 4 次 `SabrDataSource open ... InterruptedException → evict sid`(ExoPlayer cancel 在途 chunk 时 fetcher 打断,open 抛 IOException→evict 会话→重 harvest,与会话切换窗口吻合)。

**根因(结构盲区,两层看门狗共同失明)**:该故障态是 **READY + playWhenReady + 音频驱动位置前进 + 零帧渲染**——位置基 stall 看门狗(8s)要求位置不前进、视频冻结看门狗(12s)要求 BUFFERING,双双不触发 → 无限黑屏,用户唯一出路是手动退出(00:31 案与本案同构,§21 只处理了 BUFFERING 变体)。

**修法三件(已实施,PlayerScreen)**:

1. **决定性诊断日志**:①`onVideoSizeChanged` 打 `video size: WxH`(解码器真实出帧的系统级证据);②`onRenderedFirstFrame` 打回执 + 置 `frameRendered` 标志;③`STATE_ENDED` 打 `player ENDED @pos/buffered/duration/frameRendered`(提前 ENDED 案的位置取证)。下一次真机日志即可回答「零帧 vs 早 EOF vs 时钟跳变」。
2. **黑屏看门狗(READY 态变体)**:READY+音频前进但本会话从未渲染首帧,超 `VideoFreezeThresholdMs=12s` → 走既有 auto-retry 链(`autoResumePositionMs`+`retryKey` 重载续播);独立 `noFrameRetryCount` 预算(首帧真渲染即清零),与两条既有看门狗三态互补:位置冻结→8s、BUFFERING 视频死→12s、READY 黑屏→12s。
3. **黑屏画质熔断**:READY 零帧重试 ≥2 次仍黑,起始档压到 `BlackFrameHeightCap=1080` 重试(§21 对照 avc 1080p 可出画);若 720p/1080p 也黑则停手记 `video black: ... even at cap`(平台层故障非画质,避免同一坏档无限重载)。熔断标志首帧恢复时自动复位。

**待真机复测**:
- [ ] 复现时日志应出现 `video black: READY no first frame with pos advancing, auto-retry #N`(~12s 一拍),黑屏从「手动退出才能解」变「~12s 自动重载」;
- [ ] 若熔断生效,应见 `cap height to 1080, auto-retry`,且重载后 1080p 会话观察是否出画(区分「分档触发」vs「全档平台故障」);
- [ ] 若全档零帧,应见 `video black: ... even at cap 1080, stop auto-retry`——届时按 ENDED-position + video size 时间线定位 sample 流问题(SabrMediaPeriod 侧时间戳映射嫌疑);
- [ ] 正常会话不应出现上述任何一条(误报=0);
- [ ] 提前 ENDED 案:`player ENDED @pos=...` 应给出准确位置,判定 100-128s 提前结束的真因。

## 23. 2026-09-01「Auto 不升档,手切 1440 无误」:墙钟口径鸡生蛋死锁 → 重填容量中位数通道

**现象(12:05-12:13 真机 logs_live.log,重启电视恢复零帧故障后的新会话)**:Auto 会话从 720p 起
播,~100s 才靠冷启动梯子爬到 1080p,1440p 永远够不着;手动切 1440 单轨会话立刻正常(REAL 行 14-24Mbps)。
12:05 会话逐证:est 爬 2.6M→8.3M(重填期)→ LoadControl 满闸停拉 40s → **est 衰减回 4.1M** → 再重填
爬回 5.5M 过 303 门槛升 1080;12:12 会话 sus 顶 4.4-4.5M,1080p 需 5.6M、1440p 需 13.3M,升档门
(`required > effective`)**结构性过不去**。bw/sus 全程贴着播放消耗码率:720p 消耗 3.4M → 有效口径
顶棚 ~4.5M;要读出 5.6M/13.3M 的「容量」,必须先在按那一档消耗——**鸡生蛋**。唯一能把口径顶上去的是
重填期突发样本,而 §19/§20 刚好为防 4K 死亡行军把突发样本压掉了(压得对)。

**根因(口径冲突,不是 bug)**:effective(活跃 est,含 gap)与 sus(60s 墙钟交付)都是**消耗量
口径**——满缓冲停闸后交付=消耗,两个估计必收敛到当前档码率。下一档的容量证据只存在于「请求在途的
瞬时速率」里,而这恰恰是两个口径都不含的部分。§19 堵住了突发样本这条路 → 升档只剩冷启动梯子
(sus=-1 放行),会话一长满闸,梯子就停了。**堵对了一条路,但没开另一条。**

**修法(2026-09-01,重填容量中位数通道)**:

1. `SabrMediaFetcher.getRefillCapacityBps()`——每次成功媒体请求记一笔瞬时吞吐(`bytes/HTTP 耗时`,
   不含缓冲等待 gap,天然免疫墙钟空转),留近 8 笔取**中位数**(抗单笔 TCP 爬升/小段噪声),样本
   <3 返回 -1。**无墙钟衰减**:满缓冲期不发请求→不产生新样本→不衰减,容量证据跨空窗持久。
2. 判据接线:HeightAwareAdaptiveTrackSelection 升档候选改 `required > max(effective, capacityFloor)`
   过门;**降档仍用 effective**(alpha.9Z gap 入账语义不动,「卡死不降档」防线不碰);**4K 顶档
   sus×1.1 闸不动**(防 §20 死亡行军复发——4K 准入仍看 60s 墙钟真实交付,容量通道不参与)。
3. 取证:`YtSabrAbr` 日志行新加 `cap=` 字段(与 bw/sus 并列)。

**预期行为变化**:720p 会话中 1.4-2.4MB 段瞬时吞吐 8-14M(12:05 REAL)→ cap≈11M → 302→303 十秒级
触发(旧 ~100s);1080p 后段更大(11-24M)→ cap≈15M+ → 308 达标升 1440。1440p→4K 不受影响仍由
sus×1.1 看死。单笔慢样本(冷启动 rn=0 1.4MB/4.4s=2.7M)被中位数稀释,3 笔后证据成立。

**风险与护栏**:千兆 LAN 重填期 cap 可能冲高(9MB/0.3s)——但逐级爬(一次一档)+ 升档后 10s 禁回降
+ 水位急救 + 重锚语义全保留;最坏多升一档,真扛不住由滞回/急救接管。手动锁档单轨会话不走 ABR 不受影响。

**待真机复测**:
- [ ] Auto 会话 `cap=` 出现且 ≥ 5.6M 后,303 升档应在冷启动锁 10s 到期后一拍内触发(旧 ~100s);
- [ ] 继续爬 1440p 应触发(cap ≥ 13.3M 时);
- [ ] 4K 准入不变化:sus×1.1 不达标仍不进 315(除非真 60s 级持续);
- [ ] 夜间弱网不误升:cap 随失败/慢样本下跌,升档门自动回 effective 口径;
- [ ] 「提前 ENDED 悬案」取证仍在跑(12:0X 各 ENDED @pos=0 duration=UNSET——SABR 单流 period 时长
  UNSET,EOF 即 ENDED,位置日志打不出真实位置;下一步给 ENDED 分支改记 bufferedDurationMs/最后样本位)。

## 24. 2026-09-01「Auto 仍卡 480p,手切 1440 没问题」:服务端 pacing 鸡生蛋 → 满缓冲试探升档(trial upshift)

**现象(15:32/15:47 真机 logs_live.log,§23 修完后的首验)**:§23 的 cap 通道已生效(cap= 字段有值
2626-3778K)但 Auto 仍钉死 480p:升档门 `required(298 声明 4271302) > max(bw, cap=3129-3778K)` 全程
成立。15:47 Auto 会话全部口径贴地:est 1.8-3.2M / sus 3.4M / cap 2717-3163K,26 笔 fetch REAL 全在
2.5-5Mbps。**70 秒后手切 1440(15:48-49,同网络同分钟)**:REAL 21-27MB / 5.5-9.7s → **22-24Mbps
连续 6 笔**,sus=20.3M、**cap=22475K**——同一条网络。

**根因(比 §23 更深一层:cap 通道也被 pacing 污染)**:SABR 服务端按 selectedFmts 的节奏供流——
Auto 播 480p 时服务端按 480p 节奏吐段(每笔 0.5-0.9MB + 每请求 ~1.5s 固定开销),cap 中位数测出的
是**服务端供给节奏**,不是管道容量;手切 1440 后服务端必须按 23.5M/秒供流才追得上播放,单笔 25MB
把固定开销摊平,真实管道速率才显形。即:**播 X 档永远只能测到 ~X 档量级的「容量」——§23 的结论
(测量口径含墙钟空转)只对了一半,把 gap 剔掉(cap 通道)也逃不掉,因为样本本身被 pacing 封顶**。
测量与升档互为前提的鸡生蛋死锁在 pacing 层闭环:不升档 → 测不到高档容量 → 永不升。

**修法(9ffb66f1,满缓冲试探升档 trial upshift)**:既然测量通道结构性失真,升档判据补第二条路——
**用「满缓冲」本身当证据,把「用户手切」自动化**:

1. **触发(全部满足)**:①缓冲**升穿**试探水位线 `max(15s 地板, 0.8×本实例历史最高水位)`(满缓冲
   =服务端节奏都喂满闸=供给富余于当前档的硬证据);**跨线判定**(本评估 ≥ 线且上一评估 < 线)防首填
   单调期骑线常真——单调期 maxObs==bufS → 线恒随水位走 → 只在穿越瞬间/回填跨线各触发一次;②
   `canUpgrade` 不豁免(冷启动锁 10s + 降档后缓冲 ≥30s 既有防线保留);③下一档不在失败冷却。
2. **放行范围**:容量/持续闸失真也放行,但一次仍只升一档(逐级爬不动);**×1.1 顶档 sustained 闸与
   起播 stall 冷却不试探**(4K 死亡行军 §20 / 起播死循环 §19 防线不松)——顶档(4K)真证据由 1440p
   pacing 样本自然提供(手切会话 sus=20M 实证)。
3. **试探治愈测量**:升入新档后服务端按新档 pace,sus/cap 立刻被喂到真实量级 → 下一档门槛开始读真
   数据(1440p 手切会话 cap 3129K→22475K 实证);失败时重锚已锚新档声明值 → est 被真实样本快速拽塌
   → 滞回 ×0.85/水位急救 20s/8s 降回。
4. **失败冷却**:降出试探批准的档时记 3min 失败冷却(与顶档冷却同值),冷却期内该档既不过失真闸也
   不被试探,期满由下次满缓冲重新试探——窄管道代价 = 每 ~3-4min 一次试探,不每轮回填撞同一堵墙。
5. **取证**:`trial upshift (buffer-full probe)` / `trial fail cooldown` 两条日志。

**预期行为**:宽管道(本例 22M)Auto 从 480p 逐级试探 720p→1080p→1440p,每级一次切换;窄管道试探
失败→冷却→重试,梯子停在真实可持续档。4K 准入仍由 sus×1.1 看死不试探。

**待真机复测**:
- [ ] 满缓冲后日志出现 `trial upshift`,720p(298/302)→1080p→1440p 逐级上(本例宽管道应爬到 1440p);
- [ ] 试探失败场景:`trial fail cooldown` 日志 + 水位不穿底(急救 20s/8s 在重锚配合下活着);
- [ ] 4K 不被试探:无 `trial upshift` 指向 315, sus×1.1 不达标仍挡;
- [ ] bufferMax=30s 用户档也能触发(0.8×~28s≈22s > 15s 地板);
- [ ] §23 的 cap= 取证在试探后应显著抬升(480p ~3M → 720p ~10M 量级,验证「试探治愈测量」)。

### §24.1 alpha.5 首验复盘(2026-09-01 晚,21:0X 真机 4 轮重载)

**试探机制本身按设计工作**:①21:02:44 1440p 试探失败优雅降档(BUFFERING 2s 恢复,零重载);②
**20:54:08 出现「试探治愈测量」成功案例**——试探期 pacing 样本让 sus/cap 变真,gated 门合法升
1080p(§24 复测清单第 5 项 ✓)。

**4 轮 stall 重载(20:57:56 / 21:07:33 / 21:13:23 / 21:14:47)直接原因 = 网络塌方窗口**:fetch
单笔 24-36s 慢滴(rn=47 耗 30.4s / rn=48 36.6s / rn=32 24.7s)或长时间无响应(rn=54 >27s 无
REAL)、`unexpected end of stream` ×2。playbackHttpClient 刻意 callTimeout=0 + readTimeout 15s
(per-read 重置)——慢滴永不超时,这是为支持大段慢速下载的既有取舍,本轮不动。

**试探三缺陷放大了伤害(本轮修,12e60cab)**:

1. **重载后 maxObserved 归零 → 试探线跌到 0.8×20s=17s**:21:08:46 在 bufS=20s(远未满)就试探
   1440p(sus 仅 7.15M vs 需 13.3M)→ 大亏空 → 恰逢网络塌方 → 21:13:23 重载。修:试探准入加
   `maxObservedBufferedUs >= TRIAL_MIN_CEILING_US(25s)`——重载/冷启动后必须先完整回填一轮;
   bufferMax=30s 用户档(fill ~28s)仍可用。
2. **失败冷却不跨重载**:冷却/试探态是 selection 实例字段,重载即洗掉;且 21:13 案例重载发生在
   **试探期**(降档路径从未跑,冷却根本没记)→ 新实例立即重试同一堵墙。修:冷却/active 态迁
   `SabrAbrMemory`(墙钟,noteTrialFail/isTrialFailBlocked/onStallReload),PlayerScreen 看门狗
   重载路径(noteStartupStallMemory)无条件调 `onStallReload()` 把 activeTrial 转记失败冷却。
3. **试探期无熔断**:1440p 级亏空(~9M/s)下 20s 缓冲 1-2s 穿底,水位急救(5s 宽限 + 8s 阈值)
   来不及救(21:14:20 bufS=0s)。修:试探熔断(trial abort)——试探档缓冲 <15s(TRIAL_ABORT_
   BUFFERED_US)且仍在下漏 → 2s 宽限(TRIAL_ABORT_GRACE_MS)后无视 8s/20s 阈值与 5s 宽限立即降档。

**待真机复测(alpha.6)**:
- [ ] 试探只发生在 bufS ≥ 0.8×maxObs 且 maxObs ≥ 25s(重载后不再低水位试探);
- [ ] 试探失败/试探期重载后,新实例 180s 内不再试同档(日志 remain Ns, survives reload);
- [ ] 试探期缓冲跌破 15s 立即熔断降档(无 5s 宽限);
- [ ] 网络稳定时段 Auto 停在真实可持续档,重载次数回落到网络塌方次数。

### §24.2 alpha.6 复盘 + 失败冷却一致化(2026-09-01 晚,d4822104)

**alpha.6 三修全部按预期工作**(22:14-23:10 真机 55 分钟):①全部试探发生在 bufS=38-43s /
threshold=37-39s(浅填充防线 ✓);②失败冷却 `survives reload` 正确记录 ✓;③buffer-critical
全部在 4-9s 触发、缓冲从未穿底到 0(熔断 ✓);**试探致重载 0 次**(唯一一次 stall 是 23:04:01
@pos=0 起播毛刺,6s 自恢复;alpha.5 同场景 30 分钟 4 次)。

**残留泄漏:冷却只挡试探路径,gated 未挡**。gated 门(est/sus/cap)读到的恰是试探期 pacing
样本(虚高):22:17:00 1440p 试探失败记冷却 → **22:17:14(14s 后)gated 重批 1440p**;22:29:37
1080p 试探失败 → 22:30:19(42s 后)重批;23:04:20 起播恢复期爆发样本 2s 内 gated 直上 1440p
(16.4M)→ 结果 720↔1080↔1440 每 1-3 分钟一个来回,每轮 buffer-critical 探到 4-9s(短
BUFFERING、清晰度跳,未到重载)。

**一致化修(12e60cab→d4822104,用户拍板「既然要锁三分钟不要例外」)**:
1. **降档即记冷却,无例外**——水位饥饿(buffer-critical)、est 崩塌(滞回)、试探失败三种降档
   路径统一记被降出档的 180s 冷却(markDowngradeFromTrial 去 lastUpgradeWasTrial 前置条件,
   日志区分 trial fail/downgrade fail 两种前缀供取证);
2. **封锁 gated+试探一起**(候选循环 `isTrialFailBlocked(f.height)` 直接 continue)——饥饿与
   est 崩塌都是不可持续的证据,冷却期样本恰是最不该信的证据;
3. **锁「被降出的那一档」非全梯子**——更低档升降、更高档试探、手动选档照常(8/30 被取消的
   全局冷却不复辟),期满恢复原判据。

**预期**:720↔1080↔1440 分钟级来回切消失,Auto 稳定停在试探/降档验证过的可持续档;已知代价
= 真网络短暂塌方后,塌方期所在档多锁 ≤3min(期满自然恢复)。

**待真机复测(alpha.7)**:
- [ ] 降档后日志出现 `downgrade fail cooldown`,同档 gated 不再秒批(22:17:14 模式消失);
- [ ] 720↔1080↔1440 来回切频率显著下降(Auto 停在验证过的可持续档);
- [ ] 冷却期满后梯子恢复爬升(gated 或满缓冲试探)。

## 25. 2026-09-03「级联降档钉死 720p,手切 1440 稳跑」:缓冲读数塌方 → 假 buffer-critical 误降(物理塌方守门)

### 现象(23:36-23:47 真机 logs_live.log,24min 视频档,Sony BRAVIA)

级联降档四级复盘,只有最后一级是误降:

| 时间 | 事件 | 触发 | 判定 |
|---|---|---|---|
| 23:40:03 | 试探升 4K(声明 26.8M) | est 重锚 | — |
| 23:40:26 | 4K→1440p + 180s 冷却 | buffer-critical bufS=19s 回落 | ✅ 对(4K pacing 供给 ~16M 扛不住) |
| 23:41:40 | 1440p→1080p + 180s 冷却 | est 门:一笔 4.8MB/7.56s 慢 fetch 把 est 28.5M→9.2M,穿 0.85×13.37M=11.36M 门槛 | ⚠️ 方向对(23:42-23:45 连 720p 都填不住,网络真塌),但触发证据=单样本 |
| 23:44:15 | 1080p→720p + 180s 冷却 | buffer-critical bufS=0s | ❌ **误降(读数塌方)** |

**误降铁证链**:23:43:56-59 五笔 fetch 38-70Mbps、1.9s 回填 +39s,bufS=48.8(1080p 实需 5.8M
完全可持续,est 也在回升 10→17M);23:43:59.6→23:44:15.8 **17s 零 fetch**(fetcher 全静默)、
player 一直 READY 在播;然后 bufS 读数 **48.8→0.0** + BUFFERING——17s 播放最多消耗 17s,缓冲
该剩 ≥31s,**物理上不可能的衰减速率**。期间无 seek 日志、无 reload、无 status=2——这是 SABR
服务端 bufferedRange 周期性 reset(alpha.62 own-range-null 家族,本段 ~40-45s 周期,塌到
9.9s/0s 地板,23:42:28/23:43:10/23:43:55 同模式),不是真饥饿。

**代价**:1080p 白进 180s 冷却(23:47:15 到期),冷却期 est/cap 恰是低档 pacing 样本(正是
§24.2「降档即记冷却」想堵的洞反噬);ABR 钉死 720p 直到会话结束,手切 1440p 新会话(rr5 host,
54-79Mbps、sus 42-53M、零降档零 rebuffer 跑到日志尾)才恢复——**1440 稳定属实,但 ABR 自己
没有恢复路径**(est 结构性钉消耗码率 §23/§24 + 冷却),救场的是手动单轨换新会话。

### 修法(缓冲读数塌方守门,本 commit)

**物理判据:水位衰减速率不可能超过墙钟(播放消耗 1s/s)**。`updateSelectedTrack` 记录上次评估
墙钟时刻(`prevEvalElapsedMs`),两次评估间:

```
上次水位 - 本次水位 > 墙钟时长 + BUFFER_COLLAPSE_MARGIN_US(2s) → 读数塌方非真饿
```

- 命中 → 打 `buffered-range collapse artifact ignored` 日志,**跳过本轮水位降档**(试探熔断
  同源误判一并拦,est 降档路径不受影响——口径不同);
- 下轮评估衰减速率恢复 ≤1s/s 自然放行,真饿最多晚一轮急救(真饿时塌方后读数在地板,下轮
  bufS 仍 <8s 且不再「超速衰减」,正常触发);
- seek 后的合法骤减落同一守门,代价=最多延迟一轮急救,方向无害;
- 余量 2s 吸收评估时戳与水位读数的轻微异步。

### 待真机复测(alpha.7+)

- [ ] 周期塌方场景出现 `buffered-range collapse artifact ignored`,同轮不再 `buffer-critical downgrade`;
- [ ] 塌方误降消失后,1080p 不再进 180s 冷却、ABR 不被钉死低档;
- [ ] 真饥饿(供给持续 < 当前档)降档仍正常:水位 ≤1s/s 自然回落路径不被守门拦截。

## 26. 2026-09-07「历史续播黑屏到底」:SABR 续播位置冻结永不 READY → 深度重试兜底 (P11-85)

### 现象(09-07 真机 logs_live.log 三案连环)

- 18:54 Wa6q7ql0K1c@1667s、19:29 NZAHbh78ogk@6022s、19:49 XaqIROmRDow@1524s:历史 tab 续播,SABR 单流
  seekTo(startPositionMs) 后**播放位置精确冻结在 seek 点**,BUFFERING 永不 READY、零渲染(frameRendered=false);
  但数据(A/V chunk 覆盖续播点)、解码器(c2.mtk 创建成功)、bufS(20-53s)全就位。
- 8s stall 看门狗 auto-retry ×2 后放弃;用户手动重开同一视频 8+ 轮全挂(同位置同 sid 会话)。
- **同视频换位置(19:38@6829s)或换轨(19:32:12 单轨 247)后 ~2s 起播**;移动端用户报告正常。
  同位置重载有时也自愈(19:32:12 与失败会话同 sid 同位置)——竞态,非确定性路径错误。

### 排查要点(静态分析已核对、均排除)

- req.segment=segmentNum+1 与服务端 wire seq 严格对齐(init=seq0,media seq N 覆盖 chunkIndex[N-1],19:04 从 0 起播实拉验证);
- clippedStartTimeUs=loadPositionUs(裸中段位置)→ BundledChunkExtractor.init → extractor.seek(0, clip):
  FragmentedMp4Extractor 按 tfdt 绝对域逐样本跳过,19:38 中段裁剪也成功渲染,非关键帧理论不成立;
- SabrMediaPeriod/ChunkSampleStream 初始不连续点、SabrMediaFetcher 段缓存、buildBufferedRanges(startMs=0 疑点,
  服务端回落按 playerTimeMs 判)均 LibreTube 同构。**根因未定**,疑服务端段锚点与首段裁剪竞态。

### 处置(7305b13e,诊断+韧性双管)

1. `DefaultSabrChunkSource.getNextChunk` 首 media chunk 诊断行(trackType/loadPositionMs/segmentNum/
   startMs/clipMs/itag,queue.isEmpty 打点)——下轮复盘首段裁剪是否吃关键帧/段映射错位。
2. TV PlayerScreen 看门狗「深度重试」:常规重试(2 次)耗尽且位置仍冻结 ≥8s 时,若为 SABR 单流:
   evict SabrStreamRegistry 会话(重试 playurl 重建新会话+轨解析重跑)+ 续播点前推 10s(SabrDeepRetryNudgeMs,
   换段对齐),每视频限一次(sabrDeepRetryUsedForBvid)。预期黑屏 ~26s 内自愈,不再无限黑到底。

### 待真机复测

- [ ] 历史→YouTube 续播不再黑屏到底(最多 ~26s 自愈,日志应见 `stall retries exhausted, deep retry`);
- [ ] 深度重试后起播正常、位置≈续播点+10s;
- [ ] 正常续播(不卡)路径零回归:常规 2 次重试行为不变。

### §26.1 根因实锤:bufferedRanges 上报垃圾时间(P11-85b,5ae4237e,2026-09-08)

用户追问「是不是保存/上报有问题」后定位实锤:`buildBufferedRanges` 的 startTimeMs/durationMs 取
MEDIA_HEADER.startMs/durationMs,而 visionOS 服务端该字段**恒回 0**(全天日志每条
`MEDIA_HEADER ... startMs=0 dur=0ms`)→ 每次请求上报的「我已缓冲」状态全是 `{start:0,dur:0}` 垃圾。
这正是 §16 60s 断崖案记过的「服务端回落按 playerTimeMs 判」路径的输入:

- playerTimeMs=0(从头播):垃圾 ranges 与真实状态巧合一致 → 不炸 → 搜索/首页播放全正常;
- **playerTimeMs>0(历史续播):服务端回落到垃圾 ranges → 段锚点错乱 → 位置精确冻结永不 READY**;
- 同会话重试 → 同垃圾同结果(8 轮全挂);清缓存/换包(debug 包缓存独立)/换位置(续播点落段前)/
  换轨(VP9)→ 锚点重抽或绕开回落 → 恢复——全部真机现象由此贯通。

**修**(5ae4237e):init 段解出的 ChunkIndex 段号→绝对时间网格回喂 fetcher(`registerSegmentGrid`),
`buildBufferedRanges` 有网格时按 wire seq N→网格第 N-1 段查真实 startMs、durationMs=网格区间差;
无网格(首个 init 请求窗口)零风险回退旧行为。LibreTube 无此问题暴露面:其每次 createMediaSource
新建 SabrClient 无会话复用(§26 已记),且其 clientInfo 下服务端可能回填 startMs。

## 27. 2026-09-09「续播位置冻结连环重载(801s 四轮全冻)」:media3 1.10 缺失首样本自校准 → tfdt 相对化 + sampleOffsetUs=段网格起点 (P11-90)

**现象(21:17-21:19 真机 logs_live_20260909_212154,dev.r1874,31min 视频 pnsTunF6LM0 续播 ~800s)**:
四轮会话(初载 → auto-retry #1 → auto-retry #2 → 深度重试 evict 会话 + 续播点 +10s)全部冻在续播点
801000/811000,位置一步未走、`frameRendered=false`;换全新起播视频(3 小时长视频)立刻 READY 正常播。
数据层全绿:视频段(700KB~1.1MB×8+)音频段均交付、`STREAM_PROTECTION status=1`、无 skip ad/无 MEDIA error
——**数据到了、样本读不到**。

**根因(media3 1.10 语义考古)**:1.10 的 `chunk` 目录已无老 `ChunkExtractorWrapper`(项目早期分析的
tmp/ChunkExtractorWrapper.java 即此类)——老实现把每 chunk 首样本**自校准**到声明 startTimeUs
(`sampleOffsetUs = startTimeUs − 段内首样本时间`)+ 按 seekTimeUs 裁剪,段内 tfdt 无论是 0 基还是绝对值都能对上。
1.10 换成 `BundledChunkExtractor` 后**时间戳纯透传 tfdt、零 offset、零裁剪**,只把
`extractor.seek(0, clippedStartTimeUs)` 交给 FragmentedMp4Extractor 按 tfdt 自跳样本
(`TrackFragmentBundle.seek`:`firstSampleToOutputIndex` 停在 ≤seekTime 的最后同步帧);而项目
`DefaultSabrChunkSource` 传 `sampleOffsetUs=0`——续播段若 tfdt 与段表网格不一致(相对时间/漂移),
clip 在段内找不到 ≥clip 的样本 → 永远 BUFFERING → 位置基看门狗(8s)连环开枪,每次重载同位置复现。

**修(759c09d7/79704b1e)**:
1. `SabrDataSource` 拍平后把段内所有 tfdt 的 `baseMediaDecodeTime` **相对化**(首个→0、其余减首值):
   严格按 box 树走(moof→traf→tfdt),不全文扫 `'tfdt'` 字节——mdat 视频负载可能撞出同样四字节,
   盲扫会改坏数据;解析异常不动原字节(维持现状优先)。init 段无 tfdt,自然 no-op;
2. `DefaultSabrChunkSource` 的 `ContainerMediaChunk` `sampleOffsetUs` `0→startTimeUs`(段网格起点):
   样本时间 = 网格起点 + 段内相对值,与 chunk 声明时间线一致;clip 由 media3 自动换算
   (`clippedStartTimeUs − sampleOffsetUs` 传给 extractor)。
   合起来精确复刻老 ChunkExtractorWrapper「首样本==startTimeUs」语义。

**安全性**:tfdt 本就与网格一致的段幂等(重写后 +offset 仍得原值);全新起播行为不变(起点 0 offset 0);
A/V 各段独立校准、同步性不受影响。**真机验证待做**:31min 视频续播 ~13:20 应直接出画面零看门狗;
22:23 一轮解析层即败(`VISIONOS player response is not valid` ×2 → WEB /player 纯 SABR 无直链
→ no decodable formats),与 P11-90 无关(未到 SABR 层),属 §19 死路家族的服务端响应波动。

## 28. 2026-09-10「播3秒跳10s」:P11-90 tfdt 补丁遍历 bug(全程 no-op)+ webm offset 翻倍 → 遍历重写 + 容器分流 (P11-91)

**现象(alpha.7 真机 logs_live_20260910_060654,199s 视频 7E4_M-IME2I 续播 44.7s)**:首帧正常渲染
(READY pos=44687),26ms 后位置跳到 **79876 = 39938×2(音频段网格起点×2)**,之后播 3 秒跳一段、
循环——用户观感「播3秒跳10s」。两次会话(480p H264 + 1080p VP9)同样跳法。

**根因(P11-90 三层连环的 bug)**:
1. **tfdt 采集遍历写错**:`walkContainer(wanted)` 的 tfdt 命中分支 `type == TFDT && wanted == TRAF`
   在递归进 traf 后(wanted 已翻成 TFDT)恒假,tf dt 被当容器继续递归找 traf——**补丁全程 no-op**,
   零失败日志但零段被相对化;
2. 补丁失效后,`sampleOffsetUs=段网格起点`(P11-90 的 ②)叠在**原始绝对 tfdt** 上——该视频的
   tfdt 本就与网格一致(79876=2×39938 实锤),offset 一加时间轴翻倍,播放到段尾即跳下一段的
   翻倍位置;
3. webm(VP9/AV1 itag 244/247/248/271/313)无 tfdt,MatroskaExtractor 的 cluster 时间戳本身是
   绝对值(与网格一致),加 offset 同样翻倍。

**修(6291cb0c)**:
1. 遍历重写:单层 box 遍历 + `insideTraf` 标志(moof→递归;traf→insideTraf=true 递归;TFDT→
   仅 insideTraf 时采集),补丁真正生效;加 `tfdt relativized: found=N v0=…` 日志作生效回执;
2. `DefaultSabrChunkSource` offset 按容器分流:`containerMimeType` 以 webm 结尾 → 0(绝对 cluster
   时间即正确);否则(mp4)→ `startTimeUs`(相对化后样本=网格,clip 由 media3 自动换算);
3. `SabrDataSource` 的 `CancellationException`(media3 seek/丢弃打断在途拉流)不再整会话 evict——
   此前 06:04:04 `seg=11 InterruptedException → evict` 一次 seek 拆掉健康会话白吃一轮 init 重拉,
   现仅记日志按普通 load 取消上抛。

**待真机复测**:续播位置平滑前进零跳变;`tfdt relativized: found=N` 出现;31min 视频续播(~13:20)
应直接出画面(§27 场景一并验证)。

## 29. 2026-09-13「升 1440p 每 60-90s 一轮重载」:服务端跳段(只回请求段+1/+2)+ 旧档 bufferedRange 游标污染 (P11-92)

### 现象(真机 logs_live_20260913_124126,12:36-12:41)

三轮完整重载循环(12:38:36 / 12:39:25 / 12:40:42),**每一轮同一死法**:ABR 升档切 1440p(itag 308)
后,请求目标段 seq N → 服务端每次只回 **N+1、N+2 两段**(6 次重试响应字节级完全相同:请求 14→回
15,16;21→22,23;34→35,36);`getNextSegment` 6 连试耗尽 → `SABR terminal: exhausted` → evict 整
会话 → 播放器 ENDED → stall auto-retry 全量重载(回续播点重来)→ 带宽 est 高(80Mbps)ABR 又升
308 → 撞同一堵墙。`no seg` 共 60 次**无一例外全 itag 308**。

### 历史同签名(非单视频问题,结构性)

| 日志 | itag | 签名 |
|---|---|---|
| 09-13(本节) | 308(1440p)×3 轮 | 请求 N → 回 N+1,N+2 |
| 09-10 060654 | 271(1440p)×30 | 请求 24 → 回 25,26 |
| 09-09 210214 | 137(1080p H264)×12、247 ×3 | 请求 33 → 回 34,35 |

散案未串起,今天三轮同日志连发才看清:**会话中途切轨(多为 ABR 升档)即触发,与视频无关**。

### 机制(证据 + 推断)

1. **请求体没有显式目标段号**——`fetchStreamData` 的 `VideoPlaybackAbrRequest` 只有
   playerTimeMs + bufferedRanges + selected/preferred 格式;目标段只存在客户端 ChunkIndex 换算里
   (DefaultSabrChunkSource `segmentNum+1` 塞进 DataSpec.customData,服务端请求体里根本没这个字段)。
2. **服务端按「全部 bufferedRange 最大 endSegmentIndex+1」起推**(=被切走旧档的缓冲尾):三轮的旧
   档(302)游标分别 14/21/34,服务端回的恰好全是 +1、+2;09-09 的 137 案旧档游标 33 → 回 34,35。
   顺序播放时旧档游标+1 恰好==请求段,所以平时不炸;**只有切轨瞬间**请求段≠旧档游标+1 才炸。
3. 音频轨反证:会话 2 首个音频请求 bufferedRanges=1(仅自身 init),请求 seq 7 → **精确回 seq 7**。
   服务端能精确服务目标段——区别就在切轨后请求里还带着旧档的 fat bufferedRange。
4. bufferedRange 时间全是 `{start:0,dur:0}` 垃圾(§26.1 同源洞:含 init seq 0 的分区走
   `segmentStartMs(0)=null` 回退,网格存在也救不了),服务端回落到自己的跨档游标逻辑。
5. 辅证:全程 unhandled UMP **type=51 SABR_CONTEXT_UPDATE**(每响应 1740B,`contexts=0/0`),
   LibreTube 有处理并回传——显著差异,待查是否相关。

**用户实证**:手切 1440 没问题(同会话 selectTracks 路径,理论上应撞同一堵墙;疑切档时机/缓冲状态
差异,**未闭环**——需拿手切成功日志对照)。

### 修复(两处,均在 SabrMediaFetcher.getNextSegment)

1. **旧格式 bufferedRange 清除(根因候选,对齐 LibreTube)**:`media()` 收尾
   `initializedFormats.keys.retainAll { 当前音频 || 当前视频 || 在途 }`——LibreTube 同位置无条件
   retainAll(仅当前 A/V,我们保守多留在途 itag)。切档后旧格式 bufferedRange 不再上报,服务端回落
   playerTimeMs 判(§26.1 已实证回落路径),从目标段起推。
2. **跳段自适应(伤害兜底)**:检测「目标段缺失 + 收到目标段之后的新段」→ **空段顶位**(零字节,
   extractor EOF 直接收尾零样本),内容缺一段(~一个段时长),时间线由下一段的真实样本时间接上
   (P11-90/91 段网格 offset 语义),A/V 不失步;不再 6 连试注定相同的请求。仅媒体段生效
   (segment>0),init 段缺失走 transient 重试。日志:`server skipped seg N → 空段顶位跳过`。

### 待真机复测

- [ ] 宽管道 Auto 爬到 1440p 不再触发重载(日志无 terminal exhausted itag 308/271);
- [ ] 若服务端仍跳段:日志见 `server skipped seg N → 空段顶位跳过`,切档点视频跳 ~5s 继续(**非重载**);
- [ ] 空段被 extractor 正常收尾(零字节 read 不炸 FragmentedMp4/MatroskaExtractor)——若炸会退回
      今日行为(load error),需补合成空 moof/cluster;
- [ ] 顺序播放/续播/seek 回退无回归(bufferedRange 上报变少后,服务端回落路径 §26.1 场景复验);
- [ ] 手切 1440 与 ABR 升 1440 行为一致性对照。

### §29.1 手切 1440 稳定的机制闭环(2026-09-13 12:43-13:00 手切会话,logs_live_20260913_130133)

上一节「手切为何没事未闭环」已闭环:手切走**整播放器重建 + 锁单轨**(12:43:13 player ENDED →
launch → 12:43:19 init 308 全新 SABR 会话,`selectedFmts=2` 锁 139+308),请求 bufferedRanges=1~3
**只带自身格式**——无旧档(302)污染 → 服务端精确回请求段(续播 ~160s 请求 wire seq 35 → 回
35,36 ✓)。此后 12:43-13:00 308 稳定交付 215 段、零 `no seg`、零 terminal。

这直接证实 §29 的机制推断:**服务端跳段的触发输入就是请求里的跨格式 bufferedRange**(游标被旧档
endSegmentIndex 带偏),也证实 P11-92 修复①(retainAll 清非当前格式)方向正确——修复后 ABR 会话内
切档的请求形态将与本手切会话等价(bufferedRanges 只带自身格式)。

补充:手切路径本身的代价 = 一次全量重建(本例 ~6s);P11-92 后 ABR 升档应免重建且免跳段。

## 30. 2026-09-13「续播起播黑屏 ~50s 后自愈/清缓存后正常」:bootstrap 慢首包 vs 8s 看门狗赛跑 (P11-95)

**日志**:logs_live_20260913_210200(21:00 场)+ logs_live_20260913_211258(21:11 场,完整复现闭环,
同视频 pnsTunF6LM0、同续播点 ~1544s)。

**现象**:续播起播黑屏 ~50s(auto-retry #1/#2 循环)后第三次尝试 ~2s 起播;用户清缓存后以为修好。
21:08 从头播另一视频秒开正常——「黑屏」特异性绑定**续播起播**。

**根因链(21:11 场三尝试对照,证据铁)**:
1. 续播起播流程 = rn=0 首请求(常被 SABR_REDIRECT 踢节点,~0.3-2s)→ rn=1 拉 bootstrap 包
   (恒定 1673519B)→ 再二次请求目标段(seg=308@1544s)→ 出画。比从头播**多一个往返**。
2. 三次尝试请求完全相同,唯一差异是服务端吐 bootstrap 的耗时:**20.5s / 16.4s / 2s**(rr5 节点慢首包)。
3. `StallThresholdMs=8s` 位置冻结看门狗在数据到达前杀轮 → in-flight 慢响应回来时已被 evict →
   auto-retry 复用同一会话再撞慢首包 → 循环。第三次(新 sid/cpn)赶上快首包,READY 只比看门狗快 ~0.5s。
4. **清缓存与此无关**:cacheDir 播放链路不读(只有 image_cache/updates);起作用的只是重试梯子/新会话。

**两个日志读法修正**:
- stall 消息 `buffered=82%` 是 `player.bufferedPercentage` = seek位置/时长,续播时恒 ≈ 进度百分比,
  **不代表任何数据到达**(判数据看 `first media chunk` 是否出现)。
- `player ENDED @pos=0ms duration=MIN frameRendered=false` 是重载拆卸回声(clearMediaItems 后
  ExoPlayer 状态回调),非服务端提前 EOF——真·播完是 pos≈duration。07:22 案四轮「提前 ENDED」同款。

**修复三件套(P11-95,PlayerScreen.kt)**:
1. Ready 态首帧前(`!frameRendered && videoTracks 非空`)显示「正在缓冲...」——转圈此前只绑
   playerState==Loading,prepare 完即撤,黑屏阶段界面零反馈(用户误读「已结束」)。
2. 起播阶段 stall 阈值 8s → 25s(`StartupStallThresholdMs`,覆盖最慢实测首包 20.5s+渲染 ~3s);
   出帧后仍 8s。
3. 起播 stall 判死时立即 evict SABR 会话(重载 resolve 铸新会话,热路径 ~4-5s,别让重试复用同一个
   慢会话);播放中(已出帧)stall 不动会话——保 ~6h 会话复用(alpha.29)。

## 31. 2026-09-14「续播满缓冲黑屏死锁」:media3 1.10 initial-discontinuity 协议不兼容 (P11-96)

**日志**:logs_live_20260914_000747(00:06 死锁两轮,P11-96 诊断版 dev.r1896)+ 20260914_005734(00:57 复测通过 dev.r1898)。

**现象与 P11-95 的本质区别**:数据层全绿(41-51Mbps、init+resume 段全到、tfdt 相对化正常),
探针实锤 `BUFFERING fwdBuf=52000ms isLoading=false playWhenReady=true tracks=2 video=avc1.64001F rendered=0`
冻死 20+s——轨选了、格式读了、**52s 真实前向缓冲在手**,视频解码器 allocate 后从未 start,一帧未喂。
**触发条件**:续播点落在 chunk 中间(`first media chunk` 打点对比:死锁案 clip 落段内 3s 深
823000-820000,正常案 1s 811000-810000/段起点)。alpha.11 日志的 buffered=48%/bufS=49s 均为假象
(位置百分比/ABR 估算),真凭据只有探针 fwdBuf+rendered(P11-96 诊断打点新增)。

**根因**(tmp/ 留的 media3 1.10 源码逐行核):1.10 ChunkSampleStream 新增 initial-discontinuity
协议——首 chunk 开载时 `chunkStart < pendingResetPositionUs`(mid-chunk 续播)→
`hasInitialDiscontinuity=true` → readData/skipData 恒 NOTHING_READ,唯一解锁是 period
readDiscontinuity()→consumeInitialDiscontinuity()。core 无外部调用者(ExoPlayerImplInternal 每轮
doSomeWork 调 updatePlaybackPositions→readDiscontinuity),消费方只有 dash 库自带 DashMediaPeriod
(select 时全流 setSuppressRead(true) 压读 + tryConsumeInitialDiscontinuityFromStreams 全量消费 +
manifest 段表预算 firstChunkStartTimeUs 立即评估)。从 LibreTube(锁 media3 **1.9.2**,无此协议)
移植的 SabrMediaPeriod 踩雷两处:①handleInitialDiscontinuity 传 true + firstChunkStartTimeUs=UNSET
(评估推迟到 chunk 开载);②readDiscontinuity 消费到第一个 true 就 early-return,顺序在后的流永不
消费——视频流 consume 返回 true 后 return,音频流 hasInitialDiscontinuity 永不清 → 读通道死锁。

**修复演进**:P11-96b(402ee541)先退出协议(handleInitialDiscontinuity=false 恢复 1.9.2 语义);
P11-96c(3c61ff90)按用户定方向改**完整适配**(对齐 DashMediaPeriod 全套):①readDiscontinuity 全量
消费;②selectTracks 首次选轨任一流 may-have 即 setSuppressReadOnAllStreams(true);③消费完且无
may-have 才解除(consume 不清 needToEvaluate,评估未完继续压读等下一轮 doSomeWork——防「早调
扑空」的核心);④handleInitialDiscontinuity=首次选轨 && !all-sync(AAC-LC mp4a.40.x 按 MimeTypes
判 all-sync 不参与,视频参与);⑤firstChunkStartTimeUs 维持 UNSET(SABR 段表 init 加载前不可得,
上游 segmentIndex==null 同款分支)。

**状态:已修,真机验证通过**(00:57 死锁点 pnsTunF6LM0@823000ms prepare→首帧 5.5s→二次 first
media chunk seg164(=初始不连续被消费后 resetRendererPosition 重对齐重喂首段,协议设计行为)
→READY,零 stall;2b3D1GLctLM@34000ms 续播 6s 首帧正常)。

## 32. 2026-09-14 会话绑定档与自动选轨对齐(P11-97,判别实验待真机)

**背景**:4K 视频 irrSuCb3BhI(23:58/00:56 两晚)SABR 会话逐请求回 154B RELOAD_PLAYER_RESPONSE
→ 死循环守卫(reloadCount=8)→ 自合成 DASH 兜底正常构建(14 视频轨)→ NewPipe 直链 4 连 403
(n-param)→ 全链失败。该视频之外其它视频正常——特异性绑定**会话绑定档=最高可用 itag313**。

**两个归因理论**(各有真机证据,互斥待判):alpha.14「2160p 会话必 RELOAD,≤1080p 会话可播」
(绑定档高度);alpha.83「RELOAD 与 itag 无关,根因 visionOS 未 attested ustreamerConfig」
(响应级)。关键事实:sabrUrl/ustreamerConfig 均为 player 响应级、不随绑定档变;videoFormatId 仅
请求兜底(请求体 preferredVideoFormatIds 按 itag 查 videoFormats 全表,alpha.29)。

**修复**(fc4f22a8):会话绑定档从「默认画质上限」改为「自动选轨实际首轨」——起始画质
startHeight 下最高档(枚举≤720,恒在 alpha.14 安全区);起始=自动时绑最低档(自动选轨起点);
未设起始但设了默认上限时按上限。首 fetch(唯一 RELOAD 暴露时刻)请求的就是起始档 → 绑它=
身份与首请求逐 itag 一致,无 alpha.77 式错位。

**状态:代码完成,判别实验待真机**——irrSuCb3BhI 复测:仍 RELOAD → alpha.83 成立,走 DASH-first
方案(P11-98,顺带修直链 403);能播 → 绑定档高度成立,问题关死。

## 33. 2026-09-20「降档到 144p 后再也不升档」:一段饥饿连降四档 + 180s 冷却锁死下一级 (P11-135)

> **读日志前置**(首轮复盘踩过):本仓 `playerState=` 打的是 ExoPlayer 原始常量
> **1=IDLE / 2=BUFFERING / 3=READY / 4=ENDED**。`3` 是 READY 不是 BUFFERING——把两者读反会把
> 「正常播放的窗口」误当成「冻结窗口」,进而把因果讲反。

### 现象(真机 `logs_live_20260920_135925.log`,r2002,移动端,视频时长 527s)

播放到 pos≈63s 起:**32.8 秒零 ABR 评估**(13:57:36.057 → 13:58:08.889),水位从 31.9s 漏到 0;
13:58:07.853 首次 BUFFERING;数据到达后 13:58:08.935 复播(READY,pos=63284)——**此时水位只剩 5.8s**。
接下来 **5.7 秒是正常播放**(pos 63284→69016 **恰以 1x 前进**),但 ABR 在这 5.7s 里**连做 4 次水位急救
降档**(08.889 / 10.444 / 11.167 / 14.568),把仅剩的 5.8s 吃光 → **13:58:14.635 再次 BUFFERING
(pos=69016),3.48 秒卡顿**。落 144p 后水位 13:58:22 回满 30.3s。此后 **112 秒零升档**直到日志结束
(用户手动退出)。同期带宽 `bw` 3.0–11.3M、`sus` 4.0–21.7M、`cap` 3.9–5.0M、bufS 峰值 **34.8s**
(多次 ≥30s,`canUpgrade` 达标),144p 之上任何一档的门槛都只有几百 K —— **门全开,就是不动**。

### 触发链(三段,根因与症状分离)

**① 服务端改推白名单外的 itag(真正根因,独立问题)**:播放到 `playerTimeMs=63360` 请求
`seg=12 itag=698`,服务端改推 **itag=335**:12 次请求(6 次 → `terminal → evict` → 新会话再来 6 次)
每次都把 **7.89–8.22MB 以 22–36Mbps 完整下完**,却全部因 `whitelist=[140,698]` 打
`skip ad/unrequested FORMAT_INIT itag=335` **整段丢弃**,698 的 seg=12 永不出现。
`grep MEDIA_HEADER` 印证:该窗口只进了 `itag=140`(音频)与 12 条 335 的跳过告警。
→ 属 §29「服务端跳段」家族的另一形态(改推**别的 itag** 而非跳段号),**待单独一轮处理**。
同一形态在 r2003 再次复现(`logs_live_20260920_142332.log`:`no seg 31 itag 698` × 12,同样每次下满
7.87–8.2MB 全丢),见 §33.1。

**② 一段饥饿连降四档(本 commit 主症状)**:4 次降档全发生在**正常播放的 5.7 秒内**(不是冻结窗口内):

| 评估时刻 | bufS | 动作 | 与上次评估 |
|---|---|---|---|
| 13:58:08.889 | 5.8s | →480p | (①的 32.8s 零数据漏下来的存量,刚复播) |
| 13:58:10.444 | 4.3s | →360p | 掉 1.5s / 隔 1.555s |
| 13:58:11.167 | 3.5s | →240p | 掉 0.8s / 隔 0.723s |
| 13:58:14.568 | 0.1s | →144p | 掉 3.4s / 隔 3.401s |

**机制**:水位急救的「仍在下漏」判据是**每次评估重新判一遍**
(`bufferedDurationUs <= prevEvalBufferedUs`),而**没有任何「降档后宽限」**
——`lastDowngradeElapsedMs` 全场只被 `canUpgrade` 读,水位急救分支从不读它。于是同一段饥饿被
拆成 4 个独立评估、**算了 4 次账(饥饿时间在累加)**。而每次降档自身还要 1.5–3.4s 拉新档 init
(1856B 小请求 + 服务端 `NEXT_REQUEST_POLICY backoff=2000ms`),期间**零可用数据** → 5.8s 的存量
被 3 次纯开销切档吃光,直接导致 13:58:14.635 那次 3.48s 卡顿。
**对照**:144p 稳定后**不再切档**,水位 13:58:15(0.1s)→ 13:58:22(30.3s)**7 秒回满**。

**③ 落 144p 后被 180s 冷却锁死(症状放大器)**:每次降档经 `markDowngradeFromTrial` 写一笔
`SabrAbrMemory.noteTrialFail(height, 180s)`,级联沿途把 720p/480p/360p/**240p** 逐档各记一笔
(13:58:08.889 / 10.445 / 11.168 / 14.568)。该记忆是**单槽**(`trialFailedHeight` 只存一个 height),
**最后写入者胜出 → 留下 240p,解禁 14:01:14.568**。而逐级爬把升档候选限死在「下一个更高分辨率档」
= 240p,`isUpgrade && isTrialFailBlocked(240) → continue`(**静默,无日志**)→ **唯一出口被封**,
144p 硬锁到 14:01:14。其余门全开(见「现象」段的实测数字)。

### 修法(本 commit,两处)

**A. 饥饿 episode(`HeightAwareAdaptiveTrackSelection`)**:首次跌破急救阈值 → 降一档并置
`freezeEpisodeActive`;episode 内不再触发第二次水位降档;水位回到阈值以上(恢复)才清位。
**同一段饥饿整段只算一次账,与时长无关(不做任何时长累加)**。
真饿(供给持续不足)不靠本路径兜底:**est 降档路径完全不受本闸影响**——总供给不足时窗口被失败/零
样本填满 → est 塌到当前档 `required×0.85` 以下 → 候选循环照常逐级下探;水位急救的定位本就是
「est 反应太慢时的快速一档」,一 episode 一档符合其定位。级联链上的 180s 冷却误伤随之消失。
另补一行**诊断日志**(`freeze episode: … water-level downgrade suppressed`):
水位急救此前是唯一静默生效的路径,被闸住时日志里什么都没发生,无法区分「闸生效」与「ABR 没在评估」
(本次 144p 锁死复盘就吃过这个亏——②的 240p 候选被静默 continue,整段日志一片安静)。

**B. 服务端 backoff 不入账(`SabrMediaFetcher.recordFetchGap`)**:`NEXT_REQUEST_POLICY` 要求的
睡眠发生在 `fetchStreamData` 开头、`fetchStartMs` 在它之后才取 → 全额落进 `rawGapMs`。但那是
**服务端叫停**,既非链路供给不足也非需求驱动空闲——计进 `addRealBwSample(0L, …)` 等于自己拽低 est。
真机两笔:13:58:11 / 13:58:15 各 **2010ms / 2015ms**(`coast=0`,因 runway 4305/172 < 10s 余量),
**est 23752K→17926K→13836K**。修:新增 `serverBackoffSleepMs` 由 `fetchStreamData` 传入,原样扣除后
再算 coast/counted/sustained;扣除量**两边都不偏袒**(不计 0 供给样本,也不作需求空闲扣减);
日志保留 `raw=` 与 `backoff=` 双值供取证。

**同类账(未动,留档)**:`isTrialFailBlocked` 的静默 `continue` 已由 A 的日志补齐同类可观测性,
但「单槽冷却被最后一笔覆盖」本身未改 —— 若将来再遇级联,应重新评估是否改成多档集合。

### §33.1 真机复测闭环(r2003,`logs_live_20260920_142332.log`,同日 14:20-14:23 续播会话)

**A 生效**:14:23:03.402 降一档到 480p 后,14:23:04.967 打出
`freeze episode: bufS=4s held at 480p — water-level downgrade suppressed`,**不再级联到
360p/240p/144p**。该次真切档耗时 **2.855s**(决策 → 新档首个 media chunk `itag=697`),被决策时的
5.8s 水位覆盖,**切换前后均未进 BUFFERING**,水位 4.2 → 8.6 → 14.4s 立即回填 ⇒ **降档本身没有卡顿**。
另一次降档(14:22:26.275)被**起播锁夹回 720p**(日志 `held at 720p`,随后到达的 chunk 仍是
`itag=698`),**压根没切档**,零切换代价。

**本场真正的卡顿(4.8s)与降档无关**:14:22:28.285 READY(pos=143220)起正常播放,14:22:32.938 →
14:22:57.312 出现 **12 次 `no seg 31 itag 698`**(每次下满 7.87–8.2MB 全丢弃,与 ① 同形态),25 秒
拿不到 seg 31 → 水位在 172698 漏干 → **14:22:58.600 BUFFERING → 14:23:03.402 READY,4.8 秒**。
降档发生在 READY 那一刻(14:23:03.402),**晚于卡顿起点**。
(4.8s < `StallThresholdMs` 8s,看门狗未触发属正确行为。)

**暴露的两点待办**:① 服务端改推白名单外 itag / 跳段仍是这条链的根因,两场日志同签名,需单独一轮;
② `playerState=` 语义已在本文档开头标注,避免再读反。

### 待真机复测(alpha.8x)

- 日志应见 `freeze episode: … suppressed` 一行(每 episode 一次),且**同一段饥饿只出现一次**
  `buffer-critical downgrade`。← r2003 已闭环(§33.1)
- 级联场景下 `downgrade fail cooldown` 应**只记一笔**(源档),不再有 240p/360p/480p 沿途各一笔。
- ①的服务端改推 335/跳段若复现,本修法**不能**阻止第一次降档(那是正确反应),但应止步一档。

## 34. 2026-09-20「无效流量从哪来」:8MB 全丢弃是**我们自己点名要的** —— 预取窗口改成可撤销 (P11-136)

### 起因

§33 的 ①(服务端改推白名单外 itag、每次下满 ~8MB 全丢)当时只记为「服务端行为,待单独处理」。
追下去发现:**服务端不是乱推,是照我们的要求推的**。

### 机制(四步,全在代码里)

1. `DefaultSabrChunkSource.maybePrefetchNextTier` 选「比当前档分辨率更高的下一档」——播 720p(698)时
   就是 **1080p itag335**,`prefetchFormat(335)` 把它塞进 `preferredVideoFormatIds` **第二位**。
2. 并且**故意不报它的 bufferedRange**([SabrMediaFetcher.kt](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt)
   的 bufferedRanges filter,注释原话「报'没有' = 请把数据推给我」)——这是在明确要求服务端推它。
3. 服务端**照办**:每个响应都带 `FORMAT_INIT itag=335` + 5 个 335 段(≈**7.87MB**)+ 音频段;
   实测该笔 8.2MB = 335×5 + 140×2,**我们请求的 698 seg 一个字节都没有**。
4. 但白名单是 `{当前音频, 当前视频} + pendingRequestItags` ——**335 不在里面** → 直接 `return`,
   **连 `initializedFormats[335]` 都不建**(所以 P11-130 想要的「缓存进 initializedFormats 以便切档命中」
   **结构上永不可能发生**),335 的 MEDIA part 找不到 headerId 全部丢弃。

**代码里的自相矛盾**(一处漏接线):P11-130 作者**知道**预取档的缓存要保住——
`initializedFormats.keys.retainAll { … || f == activePrefetchItag() }`(注释:「预取候选档的缓存要保住,
否则刚预取到就被清」)——**但白名单不含 `activePrefetchItag()`,而白名单是更早的一道闸**。
那句保护的是一个从未被创建过的缓存。

### 闭环证据:两场日志的「零可用数据窗口」= 预取窗口本身

| | `logs_live_20260920_135925.log` | `logs_live_20260920_142332.log` |
|---|---|---|
| 宣布预取 335 | 13:57:35.977 | 14:22:29.994 |
| 30s 窗口到期 | ≈13:58:05.98 | ≈14:22:59.99 |
| 最后一笔 skip 335 | 13:58:05.978 | 14:22:57.301 |
| 窗口**内**发出的请求 | rn=3..14 全 `no seg` | rn=3..15 全 `no seg` |
| 窗口到期后**第一笔** | rn=15 **13:58:08.843 立刻拿到 698 seg 12/13/14** | rn=16 **14:23:01.206 立刻拿到 698 段** |

两场各 12/13 次 `skip ad/unrequested FORMAT_INIT`,**全部是 itag=335、没有别的 itag** ⇒ 这道 alpha.71
广告防线在实测里一次广告都没拦到,**拦的全是我们自己点名要的预取档**。

### 为什么"丢弃"这么贵(三重代价)

- **响应预算被占满**:335 吃掉 7.87MB 后,当次请求的段挤不进来 → `no seg` → 重试 `MAX_ATTEMPTS=6`
  (每次再下 ~8MB)→ `terminal → evict` → 新会话(窗口还在)**再问一次 335**。
- **丢弃发生在下载之后**:`response.body?.bytes()` 先拿完整 8MB 再逐 part 解析 —— 判断「要不要」时钱已花掉。
- **恶性闭环**:丢掉的字节照进 `est`(无条件喂 `recordRealBandwidthSample`),`bandwidthEstimate`
  被抬到 32Mbps 再**上报服务端** → 服务端更确信该推高分辨率 → 更多无效流量。

### 修法(本 commit,C 段;A 段另开一轮)

**预取窗口改成「持续条件」**:进入线仍是缓冲 ≥20s(`PREFETCH_MIN_BUFFERED_US`,不变),但**新增撤销线
15s**(`PREFETCH_REVOKE_BUFFERED_US`)——窗口期内缓冲跌破即由 `fetcher.cancelPrefetch()` 立刻撤销。
滞回带 5s 防缓冲在 20s 上下自然抖动时被一次轻微回落永久关掉。撤销是**单向**的(该档仍留在
chunk source 的 `prefetchedTiers`,保持「同一档只报一次、不持续白吃带宽」的原意):本会话不再重试该档。
诊断日志 `prefetch canceled: itagN 撤销(缓冲 Ns 跌破撤销线)`(每档一次)。
`prefetchUntilMs` 加 `@Volatile`——写入方从此有两个(loading 线程 + chunk-source 评估线程)。

> 修复前:14:22:29.994 在缓冲爬升到 ≥20s 时合法进入,此后 30s 一路照问,缓冲塌到 0 也照问 →
> 14:22:58.600 BUFFERING(4.8s 卡顿)。修复后缓冲跌破 15s 即撤销,当次段得以正常送达。

### A 段(待验证,另开一轮)

**白名单并入 `activePrefetchItag()`** —— 让预取数据真进 `initializedFormats`,P11-130 的原意才成立。
单独上之前要先验三件事:
1. **主害是否真消除**:白名单放行只是把浪费的 8MB 变成有用,但响应预算仍被它占 ——当次要的段能否送来,
   取决于服务端怎么分预算,需实测(这正是 C 段先做的原因)。
2. **能否被选中**:ABR 有 codec 粘性,可能选同 height 的别的变体 ——实测 14:23:06 的候选是
   `itag248`,而缓存里会是 `335`;预取 335、升档选 248 就白预取(r1995 已有 AV1 vs VP9 错配先例)。
3. **请求形状**:335 一旦进 `initializedFormats`,`selected = initializedFormats.values.map{}` 会把它
   报进 `selectedFormatIds` —— 请求体形状是逐字节对齐换来的(P11-104/P11-109),动它要单独验。

### 待真机复测

- 日志应见 `prefetch canceled: itagN 撤销(缓冲 Ns 跌破撤销线)`,且**此后不再有该 itag 的
  `skip ad/unrequested`** 与 `no seg` 重试风暴。
- 预取窗口期内若缓冲始终 ≥15s,行为与旧版一致(不误撤)。
- 反例防线:若某场预取窗口期内**没有**撤销、缓冲却仍塌 —— 说明触发点不在预取,需重查。

### §34.1 r2006 复测:第一版撤销**没生效**,而且原因可证(2026-09-20 14:45-14:49)

`logs_live_20260920_144844.log`(dev.r2006,含 P11-136)。同签名暴风**复现**,整场**零条**
`prefetch canceled`:

```
14:46:32.714  新会话 rn=0/1/2 → 6.76/6.57/6.37MB,缓冲爬到 29.5s
14:46:36.472  prefetch 335(bufS 23.7 ≥ 20,合法进入;窗口 → 14:47:06.472)
14:46:38.775  rn=3 7.94MB → no seg 39        ← 预取后 2.3 秒的第一笔
14:46:38.8–14:46:59.6   6 次尝试 × ~7.6MB → terminal → evict
14:46:51.4–14:47:01.7   再来 6 次 × ~7.6MB
             合计 12 次 ≈ 91MB 全丢;skip FORMAT_INIT itag=335 共 14 次 = 暴风里的 14 笔请求
14:47:05.932  BUFFERING(缓冲已被打穿 29.5s → 5.8s)
14:47:08.522  READY → 2.6s 卡顿
```

**两个原因叠加,都不是实现 bug 而是设计挂错点**:

1. **钩子错位**:水位撤销挂在 `maybePrefetchNextTier`(只由 `getNextChunk` 调),而暴风期 loader
   卡在**同一个段的 6 连重试循环**里,`getNextChunk` 不被调用。铁证:14:46:36.508(bufS=29.5)→
   14:47:08.488(bufS=5.8)之间**只有 1 个评估点**,32 秒盲窗。那条路**结构上够不着故障现场**。
2. **判据也不相关**:唯一那个评估点上,30s 窗口已在 2 秒前(14:47:06.472)自然到期 →
   `cancelPrefetch` 发现无生效窗口,早退,连日志都没打。

**前提被日志否证**:第一版假设「缓冲变薄才该撤销」,但伤害发生在**请求被拒的那一刻**(服务端把响应
预算给了 335,当次要的段直接不来),与缓冲厚薄无关 —— 这次缓冲 **29.5s 照样被打穿**。

### §34.2 阶段 1(本 commit,主钩子 + 取证)

| # | 改动 | 钩子 | 为什么它一定会跑到 |
|---|---|---|---|
| 1a | `no seg` 即撤销预取 | `getNextSegment` 重试路径(紧接 P11-92 分支之后)| **每笔尝试都经过这里** —— 暴风期唯一在跑的路径 |
| 1b | 丢弃字节取证(只观测,**不改** est/sus/上报口径)| `PART_MEDIA` 找不到 header 处 + `MEDIA_HEADER` 跳过处 | 每笔响应都解析 |
| 1c | 观测性三项 | 两端播放器 / 本类注释 | —— |

- **1a**:`cancelPrefetch("请求 seg N itag X 未送达(attempt M)")`。预期下一次 `no seg` 即撤销 →
  第 2–3 次尝试把段拿回来 → 省掉 ~76MB 与 ~19s,缓冲不被打穿。
  (误撤销代价当前为 **0** —— 预取数据反正被丢弃;等白名单放行后才是"该档本次会话不再预取"。)
- **1b**:`DiscardTracker` 做成**调用内局部量**——fetcher 跨轨共享(SabrMediaPeriod 注释:「底层共享
  同一 SABR 会话/fetcher」),视频与音频两个 loader 并发进 `media()`,实例字段会串场。
  只统计能归因到白名单跳过的字节(`discard.headerItags` 有记录才计),否则无从归因的字节
  (MEDIA_END 之后的重复块)会把数抬高 —— 这个数是拿去判断口径要不要改的,必须可信。
  日志:`discarded media: XB of YB (itags=[…])`。
- **1c**:①移动端 `playerState=` 补状态名——**TV 端早就有** `playbackStateName`,是移动端漏了,
  这次补齐且逐字与 TV 一致(这正是 §33 复盘读反的直接原因);②两个重试计数器日志分开喊名
  (`stall-watchdog retry budget` vs `playback error-retry budget`,并带上各自上限);
  ③`no seg` 路径注释补上**第三种成因**(服务端把响应预算给了别的 itag),原文只写了
  「服务端只回了 context+backoff 或 redirect」。

### §34.3 阶段 2 决策线(未做,先验后定)

> **能不能在 SABR 的响应预算下,同时给我们正在播的段**和**预取档?

- **2a(能)**:白名单并入 `activePrefetchItag()` + 候选档与实际升档目标对齐(实测候选 `itag248`
  vs 缓存 `335` 是错配)。
- **2b(不能)**:**删掉预取**,把 8MB 还给正在播的格式,升档维持现拉(2.9/6.6/14.3s)。

倾向 **2b**:机制是"用响应预算换"而非"额外给",而换来的是个 1856B 的 init —— 用 8MB 抢预算换
1.8KB,代价结构本身不划算。但判断交给实验。

### §34.4 验收/否证线

| 改动 | 应看到 | 反例(说明判断错) |
|---|---|---|
| 1a | `prefetch canceled` 出现在**第一次** `no seg`,此后 `no seg` ≤ 1–2 次 | 撤销后仍 6 连重试 → 与服务端行为无关,转阶段 3a |
| 1a | 缓冲不再被打穿(29.5s → 5.8s 那种) | 撤销了缓冲仍塌 → 主害另有来源 |
| 1b | `discarded media:` 每笔 ~7.6MB、itags=[335] | 数值与响应体量级不符 → 归因有漏 |
| 2a | 预取生效时当次段仍送达 + 切档命中缓存(无 `fetch rn=` 直接出 chunk) | 段被挤掉 → 2b |

---

## §35 P11-173/178 跨重载冷却被 P11-151(b) 秒清(2026-09-27 真机,`logs_live_20260927_201351.log`)

### §35.1 现象:同一视频 9 次整场重载,间隔 81~94s

设备 BRAVIA_AE2(Sony 4K,Mtk,`c2.mtk.*` 全硬解),r2113 前后构建。19:41:26 续播同一视频
(prepare `startPos=169472ms`)起,到 20:13:33 共 **9 次** `stall detected, auto-retry #1` → 整场
重载;19:53 换视频(`z8oBzo0LyKY`)后循环照旧:

```
19:42:38 stall → 19:42:48 prepare → 19:42:52 首帧    (10s 黑屏)
19:44:02 stall → 19:44:23 prepare → 19:44:26 首帧    (21s)
19:45:32 stall → 19:45:54 prepare                    (22s)
19:54:30 stall → 19:54:41 prepare  (新视频)           (11s)
19:55:52 stall → 19:56:10 prepare                    (18s)
20:09:10 stall → 20:09:32 prepare                    (22s)
20:10:44 stall → 20:11:00 prepare                    (16s)
20:12:09 stall → 20:12:24 prepare                    (15s)
20:13:33 stall → (日志结束)
```

每次 stall 的 cooldown 行都是 `2160p excluded 90s (starved=480p reached=2160p)`,即 P11-173/178
的到达档冷却**确实写了**。

### §35.2 根因:每次冷却都在 4~5 秒后被 `cooldown cleared early` 清掉

全日志 15 条 `cooldown cleared early`,其中 **19:42 之后 9 次 stall 一一对应 9 条**:

```
19:42:38 stall(2160p 冷却 90s,到期 19:44:08)
19:42:53.571 cooldown cleared early: 2160p (bufS=24s est=23861K ≥ declared=16278K×1.1, remain=75s → 0)
19:43:42 升 1080p → 19:43:48 升 1440p(+reseed) → 19:43:56 升 2160p
19:44:02 stall  ← 冷却被清后 69 秒
19:44:27.682 cooldown cleared early: 2160p (bufS=24s est=31653K, remain=65s → 0)  ← 重载后仅 4.7s
19:45:32 stall
```

即 **90 秒冷却实际寿命 4~5 秒**,ABR 随即爬回同一档,再饿死 → 再整场重载。这解释了循环间隔
(81~94s)与 `STALL_REACHED_HEIGHT_COOLDOWN_MS`(90s)几乎同长:间隔本来就是"爬回去再饿死"的时间,
不是冷却撑满的时间。

### §35.3 判据为什么在"重载后"必然成立(两条证据都失效)

`HeightAwareAdaptiveTrackSelection.updateSelectedTrack`(early-clear 块):

```kotlin
if (bufferedDurationUs >= EARLY_CLEAR_BUFFERED_US) {      // 20s
  val estNow = bandwidthMeter.getBitrateEstimate()
  if (isTrialFailBlocked(f.height) && estNow >= f.bitrate * 11 / 10) clearTrialFail(f.height)
}
```

1. **`bufferedDurationUs` 是低档回填的证据,不是高档可持续的证据。** 重载后由起播档(720p,
   1.5Mbps)起步,链路 23Mbps ⇒ **4 秒回填到 24s**。这 24s 缓冲对 2160p(16.3Mbps)零信息量。
2. **`estNow` 同源虚高。** 同一时刻 `meas=1414K`(单笔实测)而 `est=23861K`,差 17 倍 —— est 是
   低档段 bulk 下载(`chunk completed: media itag=398 bytes=850259` 类几十 ms 单笔)推出的窗口值。
   拿它比 `16278K×1.1` 判"带宽已达标",必然过。

同一虚高 est 还在 `buffer-critical downgrade **suppressed**(P11-168/177)` 上二次生效:19:42:35
`sel=0(2160p) bufS=0.0` 被判"带宽真撑得住"不降档,3.5 秒后即 stall。

### §35.4 机制冲突(不是实现 bug)

`clearTrialFail(height)` 同时清 `trialFailedHeight` 与 `stallReachedHeight`(P11-178 特意分开的两格)。
P11-151(b)(2026-09-20,治"降档钉死 144p")与 P11-173/178(2026-09-23,治"重载后爬回同一堵墙")
**目标相反**:前者要在缓冲健康时尽快解锁,后者要在重载后坚决锁住。而**重载场景恰好让前者的判据
无条件成立**,于是后者永远活不过 5 秒。

### §35.5 待定修法(未做,先验后定)

- **35a**:early-clear 对 `stallReachedHeight` 一格不适用(只放 `trialFailedHeight`)—— stall 冷却
  是"实测已证实饿死"的证据,不该被"低档回填"翻案。
- **35b**:early-clear 的 est 判据改用 `meas`/`getRefillCapacityEstimate()`(单笔实测口径),不用窗口 est。
- **35c**:early-clear 加"当前档 ≥ 被清档"前提(用低档回填去解锁高档在语义上就不成立)。

验收:重载后 `cooldown cleared early` 不再在 5s 内出现;同一视频连续 stall 间隔 > 90s(或不再复现);
反例是画面被按在 720p 过久(那说明 35a 收得过紧,退回 35c 组合)。

---

## §36 SABR 升降档链路全景 + 2026-09-27 实测分布

本轮把整条链路通读了一遍,记在这里做后续改动的底图(全部 file:line 见正文各节,此处只记结构与口径)。

### §36.1 候选池

全部**真视频轨塞进一个 AdaptationSet**([SabrManifest.kt:65-75](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrManifest.kt#L65-L75))
→ TrackGroup 全组 24 轨 = ABR 候选池。Auto 走 `HeightAwareAdaptiveTrackSelection`;
**手动选档只建选中 itag 单轨 = 精确锁档**(刻意设计,勿"修正"成全轨自适应)。

### §36.2 一次评估的顺序(`updateSelectedTrack`)

```
① 起播锁 / served 收窄     (served 收窄仅材料会话生效,P11-152)
② early-clear              缓冲≥20s 且 est≥声明×1.1 → 清冷却          ← §35 问题点
③ 水位急救降档             bufferCritical … 命中即 return(快速通道)
④ 主候选循环               est 滞回降档 + 逐级升档 + 冷却封锁 + 试探
⑤ 升档后处理               重锚(稳态)/ 跳过重锚(冷启动)+ noteReachedHeight
```

③ 命中即 `return`,不再走 ④ —— 快速通道与常规通道互斥。

### §36.3 四条降档路径

| 路径 | 判据 | 步长 | 附带 |
|---|---|---|---|
| 水位急救 | `bufS<8s`(顶档 `<20s`)且仍下漏 | 一 episode 一档 | `markDowngradeFromTrial` |
| est 滞回 | `est < 声明×0.85` | 逐级 | 同上 |
| 试探熔断 | 试探档 `bufS<15s` 且下漏 | 一档 | 同上 |
| 顶档定向 | 水位从 2160p 降下 | — | `excludeTrack` 180s |

水位急救身上另叠**四道闸**(全放行才真降档):读数塌方守门 / 冻结 episode /
**P11-168/177 带宽闸** / P11-178 静默挂死免闸。

### §36.4 升档的门

冷启动锁 10s → 降档后 `bufS≥30s` → 逐级爬(只许下一档) → 容量门 `required≤max(est,近8笔中位)`
→ 持续门 `sustained≥required` → 顶档 `sustained≥声明×1.1`(只认原值,-1 不回退) → 冷却封锁。
满缓冲**试探升档**可绕过容量/持续门,但受 `≤容量×1.5` 上限。升档后稳态重锚(锚值被实测容量 ×1.2 夹住),
冷启动梯子跳过重锚。

### §36.5 带宽口径(全部由 fetcher 提供,meter 只是转发壳)

| 口径 | 窗口/算法 | 喂给 |
|---|---|---|
| `est` 活跃 | 20s **耗时累计窗**,`bytes×8000/timeMs`;失败段入 0 字节、gap 扣滑行余量 | 降档 + 水位闸 |
| `sustained` | 60s **墙钟**交付,扣满缓冲空窗;顶档滑行余量 10s→20s | 升档持续门 |
| `capacity` | 近 8 笔成功请求瞬时吞吐中位样本,无衰减 | 升档容量门 |
| `meas` | 按 itag:挂账 bytes ÷ 段数×平均段长,**段数<3 或未解出 INIT metadata 返回 -1** | 水位闸第二条 |
| `silenceHang` | 按 itag 记最近零字节挂死墙钟 | 免闸证据 |

### §36.6 2026-09-27 实测:降档通道基本瘫痪

同日日志(`logs_live_20260927_201351.log`)决策行分布:

```
552  buffer-critical downgrade **suppressed**     ← 水位急救被闸掉
  9  buffer-critical downgrade                    ← 真降档
  1  downgrade (est 路径)                          ← est 降档几乎不触发
118  buffered-range collapse artifact ignored     ← 读数塌方守门
 16  cooldown cleared early
 20  upshift (cold-start ladder) / 23 upshift reseed
```

**两道降档通道合计只真降 10 次,闸掉 552 次**;闸内分布:

```
534 / 552  meas=未知      ← P11-177 加的实测判据 96.7% 不参与
509 / 552  当前档 = 2160p
est 实测 54M/58M/84M/92M…(该档声明 19.7M~23.5M)
```

即 P11-177 想补的"实测腿"结构性缺席(`getMeasuredBitrateBps` 要求段数 ≥3 且有 INIT metadata,
而**高档恰恰最不满足**——它总在被切来切去、挂账段数攒不够),闸只剩虚高 est 一条腿 ⇒ 必过。
结果:水位急救被闸死、est 滞回被同一个虚高 est 堵死,唯一出口只剩 8s stall 看门狗整场重载。
这是 §35 那 9 次重载的另一半成因 —— §35 治"冷却被秒清",本条治"闸让降档根本不发生"。

### §36.7 日志读法更正:`seg=0` = init 请求,不是"请求错段"

`DefaultSabrChunkSource` 的媒体段号恒为 `segmentNum+1 ≥ 1`;**只有新格式首请求**
(`representationHolder.chunkIndex == null`)走 init 分支,`SabrSegmentRequest.initRequest`
硬编码 `segment=0, segmentStartTimeMs=0`。故 `fetch rn=N itag=X seg=0 playerTimeMs=0`
**等价于"新轨 init"**,与播放位置无关;它后面 `REAL xxMB` 是**服务端对该 init 的多段推送**
(`pushed=[…]`),不是我们请求了那些段。

切轨的真实代价因此表述为:切轨 → 新轨 init → 服务端推窗重置到片子开头并一次吐几十 MB
(2026-09-27 实测 48.8MB/8588ms)→ 这 8.6s 播放器无可用数据 ≈ `StallThresholdMs`(8s)⇒ 判死重载。

另注意:该行日志的 `playerTimeMs` 打的是**原始值**;材料会话真正发进 proto 的是位置锚,只在
`material session: init 请求用真实播放位置作锚` 那行体现 —— 排查锚问题时必须先区分这两行,
否则会把"锚已生效但日志显示 0"误判成"请求真的发 0"。

---

## §37 起播期 InvalidPoToken(status=3)会话判死 + 早判死(P11-190)

真机 `logs_live_20260927_214503.log`(dev.r2118 = run 2118,与 P11-188/189 同版本;设备 BRAVIA_AE2)。

### §37.1 现象与代价

起播 17 秒后 `player error code=2000 ERROR_CODE_IO_UNSPECIFIED`,
`Caused by: IOException: SABR terminal: InvalidPoToken (StreamProtectionStatus status=3)`
(@`SabrDataSource.kt:85`)→ `playback error, auto-retry #1 @pos=112000ms` → 整场重载,21:28:55 → 21:29:37
共 **42 秒**。重载后新会话立刻正常,此后 16 分钟零重载(偶发,对齐 §`reload-player-response-flaky-not-deterministic`)。

### §37.2 完整序列:服务端拒的是**整个会话**

```
21:28:34.263  harvest 采到 poToken=89B(真 token,非桩)
21:28:45.170  首笔 STREAM_PROTECTION_STATUS status=2 (pot=89B first=0x32)
21:28:45.289  status=2 但刻意不刷新(P11-144 keep-stale 实验)→ keep 89B (age=6482ms)
21:28:45.848  rn=1 REAL 71B → status=3 → diag(status3Count=1) → evict   ← 同毫秒
21:28:47.990  rn=2 REAL 71B → status=3 …        之后 rn=3..7 每笔同样只回 71B
21:28:50.608  InvalidPoToken diag: sessAgeMs=11801 sessReqN=5 status2Seen=1
                status3Count=4 pot=89B ctxActive=0 ctxStored=0
21:28:55.965  耗尽重试 → 上抛 player error
```

**三个要点**:

1. **服务端在拒会话,不是挑 token** —— 每笔响应只有 **71B**(纯 status,零媒体),`ctxActive=0 ctxStored=0`
   (SABR context 根本没建立)。与 P11-116 的结论(token 洗清,残留是身份链)以及它记的
   「**第 4 请求必转终态 3**」精确吻合:本次 `status3Count=4 @ sessReqN=5`。
2. token 是 6.5 秒前刚 harvest 到的 89B 真 token,**不是过期**(`potAgeMs` 极小)。
3. P11-144 keep-stale 实验的判据行(「下一笔请求的 status 即判据」)本次答案是 **下一笔照样 status=3**;
   但成因更像会话级拒绝,而非 token 质量 —— 该实验是否结案需再攒同签名样本。

### §37.3 P11-190:「早判死」把 10 秒白等压到毫秒级

旧实现的判死点其实**已经立即抛**(`.848 diag → .854 evict`,同毫秒),慢的是**后续请求还照发**:
`invalidPo` 只在 `media()` **之后**查,而 media3 Loader 的重试会把 fetcher 拉回来 ⇒ 每次重试先真发一次
HTTP、再吃服务端 `NEXT_REQUEST_POLICY backoff=2000ms`,rn=1..7 **连撞 10 秒**(45.8→55.9)才耗尽上抛。

修:把 `invalidPo` 检查提到 `getNextSegment` **入口**(与既有的 `fatalError` 检查并列)——
会话级判死是终态(逐笔只回 status、零媒体),重试没有胜算,立即失败让 Loader 尽快耗尽重试上抛。
首次判死时已打过完整 diag,入口处静默以免刷屏。

预期:这段 10 秒 → 毫秒级,整次重载 42 秒 → ~32 秒。

---

## §38 P11-192 同高度跨 codec 变体 = 「降档之外的免费选项」(2026-10-03 真机,`logs_live_20261003_155810.log`)

### §38.1 现象:14 分钟里爬 4K→饿→降档 6 轮,渲染分辨率跳 11 次

视频 `FL8-Sw8PjJA`(1237s,24 轨混 VP9/AV01/AVC),15:43:26 起播 → 15:57:49 重载:

```
15:43:31  起播 720p 出帧
15:44:19  冷启动梯子 →1080p      15:44:22 →1440p
15:44:34  upshift reseed → itag315(2160p VP9, 24.9M)
15:44:52  buffer-critical downgrade 2160p→1440p  bufS=13s   ← top-tier cooldown: itag315 锁 180s
15:45:11  upshift → itag401(2160p AV01, 20.6M)               ← 仅隔 19 秒
15:46:16  downgrade  bufS=9s                                 ← itag401 也锁 180s
15:49:21  →401   15:49:49 downgrade bufS=19s
15:50:01  →315   15:50:22 downgrade bufS=12s
15:53:29  →401   15:54:04 downgrade bufS=4s
15:54:37  →315   15:55:05 downgrade bufS=5s
15:57:04  →401   → seg=179 请求零字节挂死 40s → 15:57:49 stall → 整场重载
```

同场统计:分辨率切换 **11 次**(3840x2160 ↔ 2560x1440)、`player state=BUFFERING` **5 次**、
`isLoading=true` **8 次**;最密一段 15:50:05→15:50:42 **37 秒切 4 次**。

**不是带宽不够**:同场实测吞吐 43~72Mbps(单笔 `REAL 33376367B 3701ms → 72Mbps`),
1440p VP9(11.4M)缓冲长期 20~48s。4K 侧的真实矛盾是吞吐贴地——一笔 79.9MB 的 4K 响应
`24126ms → 26Mbps`,而 315 档需要 24.9Mbps(余量 4%),所以一上 4K 就漏光。

### §38.2 根因:顶档冷却只锁 itag,不锁高度 ⇒ 顺手换一次解码器

`buffer-critical downgrade` 分支在顶档降下来时写的是(现 `HeightAwareAdaptiveTrackSelection.kt:921`):

```kotlin
if (currentHeight >= TOP_TIER_MIN_HEIGHT) {
  excludeTrack(leavingIndex, TOP_TIER_BUFFER_CRITICAL_COOLDOWN_MS)   // ← 只锁这一个 itag
  Log.i("YtSabrAbr", "top-tier cooldown: itag${current.id}(${current.height}p) excluded 180s")
}
```

于是 **315 被锁 ≠ 2160p 被锁**:候选集里 401(AV01 2160p)照常可选,而 `isTopCodecVariant`
只在**同高度**做平手判据(`f.height == bestHeight && …`),挡不住「更高的高度 + 另一个族」。
日志里 315/401 的交替时刻与各自 180s 冷却到期**逐一吻合**(401 于 15:46:16 被锁 → 15:49:16 到期
→ 15:49:21 立刻被选中),可证不是随机抖动。

代价:每次饥饿都换来一次**跨 codec 解码器重建**(VP9 ↔ AV01),这是所有切档里最贵的一种。

### §38.3 口径(P11-192,用户拍板)

> **itag 没问题,编码逻辑独立处理,将 itag 按编码分组,VP9 不合适换组。**

- 梯子按 **codec 族**分组(`codecFamilyOf`:`vp9` / `av01` / `avc`),ABR 平时**只在当前组内**升降档
  ——组内换高度是同解码器,便宜;
- **换组**是一次独立、显式的决定,只在当前组被判「不合适」时发生;
- 换出后原组冷却 180s,**期满允许回退**(但不主动回退,见下)。

### §38.4 实现要点

| 件 | 说明 |
|---|---|
| `anchorFamily` | 用户显式选族优先(P11-133),否则取**全组顶档那一档**的族(Auto;YouTube 通常 VP9) |
| `activeFamily` | 当前组;首次访问落锚点族 |
| 偏好序 | `bestIndexOf` / 水位急救降档循环 / 主候选循环的**比较器首关键字**改成「是否属于当前组」。**取偏好序而非硬过滤** —— 硬过滤会制造「组内该高度无档 ⇒ 候选集空 ⇒ 不降档 ⇒ 卡死」,正是 P11-146 结构性死锁的镜像 |
| `maybeSwitchFamily` | 每轮 `updateSelectedTrack` 一次,位置在两条降档路径与主候选循环**之前**(它们都读 `activeFamily`) |
| 判据① | 当前组在候选集里**一档都没有**(served 收窄把该族滤空 / 整族被解码器能力过滤)→ 换到天花板最高的族 |
| 判据② | 当前组滑窗(10min)内饥饿降档 ≥ **2** 次,且另一族在**同高度有更省的档**(声明码率更低)。真机场景即 315(VP9 2160p@24.9M)→ 401(AV01 2160p@20.6M) |
| 刻意不做 | 不做「换到天花板更低的族」:两个 4K 族都饿过之后会掉进 AVC(天花板 1080p),而 AVC 永不饥饿 ⇒ 整场钉死在 1080p。没有「同高度更省」的族就宁可在组内升降档 |
| `familyFailCount` | 在饥饿降档那一枪按**族**记账(不按 itag/高度)——要判的是「VP9 这一族在本机撑不住」,不是「2160p 这一档撑不住」(后者归既有的顶档冷却) |
| 回退语义 | `familyBlockedUntilMs` 到期只表示**允许**被再次选中(判据①②都可能落回原族),不主动切回 —— 否则族级振荡会以「族」为粒度重演一遍 |
| 常量 | `FAMILY_FAIL_WINDOW_MS=600_000`(要跨过 90/180s 冷却:真机两枪相隔 5.5min)、`FAMILY_FAIL_THRESHOLD=2`、`FAMILY_SWITCH_COOLDOWN_MS=180_000` |
| 兼容 | `activeFamily` 为 null(梯子无可辨族,如纯 avc)时 `inActiveFamily` 恒真 ⇒ 与旧行为逐字节等价 |

### §38.5 验收 / 否证线

- **验收**:不再出现「315 被锁 → 19s 内 upshift 到 401」这类**同高度跨 codec**跳跃;若 VP9 2160p 反复饿,
  应见**一条** `codec group switch: vp9 → av01 (reason=family starved x2 …, back-off 180s)`;
  此后分辨率切换全部落在同族内(2160p↔1440p),跨 codec 重建应为 0~1 次。
- **否证**:①若换组后 AV01 也反复饿、且日志出现反复 `vp9 → av01` / `av01 → vp9` 的往返 ⇒ 判据②太松,
  提高阈值或加大冷却;②若某视频只有单一族却出现「组内无候选」误判 ⇒ 查 `familyCandidateCount` 的
  served 口径(`restrictToServed` 只对材料会话生效)。

---

## §39 P11-193 升档判据链全景 + 冲突清单(2026-10-03 连续重载复盘)

> 立此表的起因:用户定下规矩——**以后每次调整升档/档位逻辑,先把整条判据链理一遍,查有无相互抵消的规则**。
> 下表就是当前口径的快照(改判据时先照它核对一遍)。触发案例:`logs_live_20261003_212240.log`(alpha.4,
> 视频 `jeHP-rT5E7U`)——3 次整场重载,其中两次是「重载 → 起播 720p → 冷启动梯子 1 分钟内爬到 4K →
> 饿死 → 再重载」的**连续重载**(21:19:48、21:22:24,隔 2 分 36 秒)。

### §39.1 升档(候选循环里能拦住一次升档的全部关卡,按顺序)

| # | 关卡 | 判据 | 备注 / 冲突 |
|---|---|---|---|
| 0 | `isTrackExcluded` | 冷却期内该 itag 除名 | 顶档冷却按 **itag**(P11-192 已改成按编码组优先) |
| 1 | `restrictToServed` | 仅材料会话收窄到 served 集合 | — |
| 2 | 逐级爬 | `f.height > nextUpgradeHeight` 跳过 | — |
| 3 | `topTierStallBlocked` | `isTopTier && SabrAbrMemory.isTopTierStartupBlocked()` | **失效**:见 §39.3-(3) |
| 4 | `isTrialFailBlocked(height)` | 该高度 90s/180s 冷却 | 与「提前解除」互抵(P11-188 已收口一半) |
| 5 | `canUpgrade` | 冷启动锁 10s **且 (lastDowngrade==0 或 buf≥30s)** | ★ **豁免项是本次病根**,见 §39.2-(1) |
| 6 | `capacityGateFail` | `required > max(est, 容量中位数)` | est 被重锚成声明值时可恒真 |
| 7 | `sustainedGateFail` | `!canUpgrade \|\| (sustained in 0 until required)` | **sustained = -1(证据不足)时不拦** ⇒ 冷启动梯子只剩 6 一道闸 |
| 8 | `topTierGateFail` | `isTopTier && i == 0 && sustained < 声明×1.1` | **双重失效**:见 §39.3-(3) |
| 9 | `trialOverCapacity` | 试探路径 `> 容量×1.5` 拒 | — |
| 10 | `trialUpgrade` | 缓冲**升穿** `max(15s, 0.8×maxObserved)` 且 `maxObserved ≥ 25s` | 与 6/7 是「或」关系(试探可绕过两道测量闸) |
| 11 | **`climbBufferFloorUs`(P11-193 新增)** | 非首爬/非试探时 `bufS ≥ min(30s, 试探线)` | 补 #5 的缺口,见 §39.2 |

### §39.2 冲突清单(单独看都有理,叠起来成死循环)

**(1) `lastDowngrade==0` 豁免 ⟂ 「降档后缓冲 ≥30s 才许升」——本次致死。**
豁免的本意是「起播首爬别被门槛卡」,但它与门槛是**或**关系:只要本实例从未真正降档,豁免**永久成立**。
而连续重载的循环里 ABR *恰恰从不降档*(饥饿那一枪被 (2) 的闸 suppress、或读数无效没开枪)⇒
`bufS=10.4s` 也能爬 4K。真机两次:
```
21:19:43  实例创建后 66s, bufS=3.8s(⇒-1.0 无效)  1080p→1440p→2160p
21:22:05  实例创建后 122s, bufS=10.4s            1440p→2160p
21:22:17  cleanup dropped formats=[271] → bufS=0.1s
21:22:17  buffer-critical downgrade **suppressed**(P11-168/177) ← 见 (2)
21:22:24  stall detected → 整场重载
```
**修法(P11-193)**:新增升档缓冲地板 `climbBufferFloorUs = min(30s, 试探线)`,非「真起播首爬」
(实例创建 30s 内 **且** 目标 ≤1080p)/非试探时,`bufS` 不达标就**不升**——不是永久封锁,填够地板即放行。

**(2) 带宽闸 suppress ⟂ 「切轨丢缓冲」——看着像冲突,理完结论是「不该动」。**
P11-168/177 的闸在 `est/meas ≥ 声明×1.15` 时 suppress 水位急救降档,理由是「低水位来自排空/切轨
≠ 供给不足」。而死循环里正是「刚切轨丢光缓冲」⇒ 看起来该给闸开例外。
**但理完否掉了这个修法**:21:22:17 那一刻 `meas=12357K` 是**已知且达标**的(401 刚交付 26MB),
按闸降档会再切一次格式(去 271,而它的缓存刚被 `cleanup dropped formats` 丢掉)⇒ **再丢一次缓冲、
再等一次 refetch**,只会更糟;真正致命的是紧接着那次**同高度换档**(401 → 313,见 §39.4)。
P11-188 已经把闸的盲区补对了:「实测未知(-1)= 证据不足 ⇒ 闸不成立 ⇒ 照常降档」——
即「新档还没交付任何段」的情形本来就放行,而「已交付且达标」的情形不该降档。**闸不动**。

**(3) `isTopTier` 判据 ⟂ 三条顶档保护(整类失效,**已修 P11-194**)——真机实锤见 §39.6**
`isTopTier = length > 1 && getFormat(0).height >= 2160` —— 而 SABR 组的第 0 档是**会话 primary 档**
(真机两组实测为 itag135/480p 与 itag136/720p),不是顶档 ⇒ 该式恒 false ⇒ #3、#8 两条关卡形同没有。
#8 还叠了第二处错:`i == 0` 是照「索引 0 = 最高码率」写的,而实测索引 0 是 primary(最低档之一)。
**已修(P11-194)**:`isTopTier` 改判「组内存在 ≥2160 的档」、`topTierGateFail` 去掉 `i == 0` 改判「该候选是顶档候选」,并补了一次性取证日志 `top-tier gate refused (P11-194)`(这道闸此前一类日志都没有,修了也无从验收)。

**(4) `sustained == -1` ⟂ sustained 闸**
`sustained in 0 until required` 在 `sustained = -1` 时为 false ⇒ **不拦**。这是刻意的(冷启动证据不足
不该被持续闸卡死),但也意味着**冷启动梯子只有 est 一道测量闸**,而 est 恰恰是重锚/突发最容易虚高的
那一个。P11-193 的缓冲地板正是在此处兜底:证据不足的档,用「缓冲水位」这条与 est 无关的证据来判。

### §39.3 降档侧判据(供对照)

| 路径 | 判据 | 与升档的互抵 |
|---|---|---|
| A 水位急救 | `bufS < 8s`(顶档 20s)、仍在下漏、过 5s 宽限、非读数塌方、一 episode 一枪 | 被 B 闸 suppress |
| B 带宽闸 | `est≥声明×1.15 && meas≥声明 && !silenceHang` ⇒ suppress | 见 §39.2-(2) |
| C est 滞回 | `required(当前档×0.85) > effective` | 重锚把 est 锚在声明值 ⇒ 需真塌方 |
| D 升档后宽限 | 升档后 10s 禁止 est 回降 | 与 C 互抵(那 10s 只能靠 A) |
| E 提前解除冷却 | `bufS≥20s && est≥声明×1.1` ⇒ 清降档冷却 | 与 #4 互抵(P11-188 已收口) |

### §39.4 本次未修但已定位:21:22 的同高度换档

`sel=1 itag401(2160p AV1, 8.5M)` → 12 秒后 `fetch rn=13 itag=313 seg=0`(换到 2160p **VP9**,15.2M)——
同高度内跨 codec 换成**更贵**的那一档(触发者是「同高度粘顶档 codec」的平手判据),而这次切换把刚
下到手的 26MB 401 数据丢掉、从头拉 313 ⇒ 缓冲 0.1s 撑不到首个段 ⇒ stall。
P11-192(alpha.5)的编码组锚定把这一跳去掉(锚在 VP9 就直接 271→313,不再经 401),但**锚定本身**
让 4K 落在更贵的 VP9(15.2M)上;能否扛住交给 P11-192 判据②(VP9 同高度反复饿 ⇒ 换到更省的 AV01)兜。

### §39.5 验收 / 否证线(P11-193)

- **验收**:重载后 1~2 分钟内的 4K 攀爬应被拦下,日志出现 `upshift held (buffer floor, P11-193)`,
  且 `bufS` 达到地板后才见 `upshift (cold-start ladder …)`;不再出现「爬 4K → `bufS≈0` → 8s stall」。
- **否证**:①若缓冲目标调小的档位(fill <30s、≥15s)从此爬不过 1080p ⇒ 地板公式要按用户目标再压;
  ②若 `bufS=-1` 长时间无效导致整场钉在 1080p ⇒ 无效读数的处理要从「不达标」改成「按上一次有效值判」。

### §39.6 P11-194:顶档闸复活(2026-10-03 真机实锤,`logs_live_20261003_214158.log` / dev.r2127)

**这是 P11-192 + P11-193 都已生效的那一版**(日志里可见 `codec group switch: vp9 → av01 (reason=family
starved x2 …)` 与 `upshift held (buffer floor, P11-193) … bufS=14s < floor=24s`,两条新机制都按设计工作),
**但仍有一次整场重载**,原因正是 §39.2-(3) 那条死判据 —— 于是它从「待办」升级为「实锤」:

```
21:39:26  trial refused (over-capacity): itag315(2160p) declared=21108K > floor=8844K×1   ← 贵的那档被拦了
21:39:26  trial upshift (buffer-full probe): bufS=38s threshold=35s → itag401(2160p)
          declared=9127940 est=8844K sus=6679K            ← 持续供给 6.7M < 该档 9.1M(更低于 ×1.1=10.0M)
21:40:09  fetch rn=16 REAL 46371131B 22888ms                ← 4K 单笔 46MB / 22.9s
21:40:57  fetch rn=17 REAL 25583811B 26383ms → 7Mbps        ← 单笔 26.4 秒;缓冲 41s → 6.6s
21:40:59  buffer-critical downgrade: 2160p@9127940 → 1440p@4184860   ← ABR 判得对
21:41:05  cleanup dropped formats=[401] (video=400 pending=[400])     ← 降档换轨把 4K 缓冲丢光
21:41:15  stall detected, auto-retry #1 @pos=599543ms → 整场重载      ← 9s 空窗 > 8s 看门狗
```

**要点**:放 4K 进来的唯一依据是 `est=8844K`,而 `sustained=6679K` 当场就说明供给不够——本该由
「×1.1 顶档 sustained 闸(且**不试探**)」拦下,但该闸因 `isTopTier` 取组内第 0 档(会话 primary)
+ `i == 0`(也指 primary)双重写错而**从未开过一枪**。

**修法(P11-194,两处判据 + 一条取证日志)**:见 §39.2-(3)。复活的两条防线:×1.1 顶档闸(gated 与
试探两路都挡)、起播 stall 后 180s 禁爬顶档(P11-173/178,同一 `isTopTier` 的另一个消费者)。

**副作用(已知且接受)**:VP9 4K(21.1M)要求 `sustained ≥ 23.2M`,AV01 4K(9.1M)要求 ≥10.0M ⇒
这台电视上「4K 自动档」会明显更难上,但实测数据支持这个保守(26.4 秒一笔 25MB 的供给撑不住 9.1M 的 4K)。
手动选 4K 不受影响(手切走 resolver 锁单轨,不经 ABR)。

**仍然开放的洞(不在本轮)**:降档换轨时 `cleanup dropped formats` 丢掉旧轨缓冲 ⇒ 新档首个段落地前
>8s 就吃看门狗(本次最后一步)。它的入口正是「4K 进去了」;顶档闸修好后剩余换档都发生在 ≤1440p
(响应 3~11MB / 2~6s,小于阈值),先观察。若仍在**非顶档**换档处 stall,再按「刚换档 N 秒内给 stall
判死加宽限」(与既有「视频冻结 12s 让过解码器重建」同一套思路)单独开一条,**不去动带宽闸**
(理由见 §39.2-(2))。

**验收 / 否证线**:日志出现 `top-tier gate refused (P11-194) … sustained=…K < 需要=…K` 与
`top-tier startup-stall cooldown: skip itag…(2160p)`(后者此前从未出现过);不再出现「试升 2160p → 饿死」。
否证:①`sustained` 明明 ≥ 门槛(4K 稳跑过)却仍爬不上去 ⇒ 复核 sustained 口径是否被「满缓冲停拉期」
拖低;②若想要自动 4K,下调 `TOP_TIER_SUSTAINED_PERMILLE`(1100 → 如 950)或按族分档。

---

## §39.7 P11-195:换组判据②没有「天花板守卫」⇒ 画面上限被砍到 1080p(2026-10-05 真机)

**现象**(`logs_live_20261005_104903.log`,dev.r2129 = P11-192/193/194 都在):用户报「一直 1080p,
手动切 1440p 没问题」。日志坐实:10:23:05 起**连续 23 分钟**渲染尺寸停在 1920x1080,而同一时段

- `bufS` 中位数 **29s**、最高 **53s**(缓冲充足,不是 P11-193 地板挡住);
- `bw` 27~43M、`cap` 27~43M(带宽充足);
- 10:46 用户换到下一个视频、手动选 1440p 后立刻正常(单轨锁 `up=null down=null`,`meas=12871K`)。

**根因(这是 P11-192 自己捅的)**:

```
10:22:05  codec group switch: vp9 → avc (reason=family starved x2 in 10min -> cheaper same-height tier,
          vp9 back-off 180s, avc candidates=7, cur=480p@493567)
```

判据② 是「同一档位换个更省的族」(原型:2160p 的 VP9@22.1M → AV01@12.4M),但「更省」在**任何高度**
都成立 —— 这次是在 **480p** 上触发(两个族只差 ~2%),而 **AVC 是唯一没有 1440p/2160p 的族**。
换过去之后,P11-192 的「同族优先」比较器只认 AVC 档 ⇒ 1440p 的候选(VP9 308 / AV01 400)全被判为
异族而**选不上**(比较器第一关键字 `inActiveFamily` 把它们压在下限)⇒ 上限被硬砍到 1080p。
§39.2 里我写「刻意不做『换到天花板更低的族』」时**只把它实现在判据①上**(`pickFamilyAnyHeight` 取
天花板最高者),判据② 漏了。

**修法(P11-195)**:判据② 加**天花板守卫** —— 目标族天花板 < 当前族天花板 ⇒ 不换(宁可在族内升降档);
并补一次性取证日志 `codec group switch held (ceiling guard, P11-195): … avc=1080p …—— 换过去会砍掉
画面上限,不换`(否则「为什么钉在 1080p」在日志里又是一段安静),换组成功的日志也带上
`(ceiling 2160p → 2160p)` 便于核对。

**冲突核对**:与判据①同向(那条本来就取天花板最高者);原型 4K 场景不受影响(VP9/AV01 天花板同为 2160p);
与 P11-193 地板、P11-194 顶档闸互不重叠。

**残留观察(未做,留待决定)**:判据② 只要求「更省」,不要求「**明显**更省」——480p 上差 2% 也会换一次
族(白换一次解码器,虽不再砍上限)。若要收紧,加一条比例门槛(如候选 ≤ 当前档 ×0.85)即可;
真机原型场景 12451/22112 = 56%,远低于该门槛。

**验收 / 否证**:日志不再出现 `vp9 → avc` 这类**换到天花板更低族**的 switch;若判据② 本可换而被挡,
应见 `codec group switch held (ceiling guard, P11-195)`;画面上限不应再被族切换砍掉(手动选档不受影响)。
否证:若某视频的 1440p 只存在于非锚点族(锚点族天花板 < 梯子天花板)⇒ 会被同族优先钉住,需另开一条
「锚点族天花板不足时改锚」。当前锚点取「全组顶档那一档的族」,天花板天然等于梯子最高档,故暂不触发。

---

### §39.8 P11-197:2026-10-05 真机「2160 卡顿不降档」复盘 —— 结论:**不是 ABR 判据的锅,是请求节奏**

> 触发:用户报「看下日志为什么在 2160 起播,多次卡顿不降档」。日志 `logs_live_20261005_193932.log`
> (dev.r2135,BRAVIA AE2 4K,视频 `xqSyhtQIsKQ`,1061s)。本轮**只做判据链复核 + 补证据,未改行为**
> (改判据前先把链理一遍的规矩)。

#### §39.8.1 事实纠正:日志里没有「2160 起播」

- 6 个 SABR 会话**全部 720p 起播**:`startup lock 720p[served]: 480p → 720p` +
  `SABR PlaybackInfo … sessionVideo=itag136(720p)`。
- 看起来像「2160 起播」的是**冷启动梯子**:19:06:23 起播 720p → 19:06:37 1080p → 19:06:44 1440p
  → 19:06:51 `upshift reseed → 23134103(itag401)` → 19:06:59 `sel=0 2160p`,**43 秒**;画面 19:07:16
  变 3840x2160。
- 唯一「开播即非 720p」的通路是**手动选档**:`preferredQualityId != null` 时只建选中 itag 单轨
  ([YoutubePlaybackResolver.kt:3076](app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L3076);
  日志 19:10:42 那场 `selected=itag308(1440p) videoTracks=1(all=24)`)⇒ ABR 只有一档、**物理上无法
  降档**,是刻意设计。若在手动选 2160p 的场景看到卡顿,原因就是这条。

#### §39.8.2 判据链复核(§39.1 / §39.3 那张表逐条对今天的状态)

| 关卡 | 今天实际 |
|---|---|
| #5 `canUpgrade` 冷启动豁免 | 起播首爬走豁免 ⇒ 梯子顺利爬到 4K(设计如此) |
| #11 `climbBufferFloorUs`(P11-193) | **正常工作**:19:09:04 拦下一次(`itag401(2160p) bufS=16s < floor=27s`),19:09:16 缓冲到线后放行 |
| #8 `topTierGateFail`(P11-194) | 19:09:16 **未拦** ⇒ 判定 `sustained ≥ 声明×1.1`(当时 `sus≈33M`)—— 闸成立,但证据被"停顿"抬高(§39.8.4-(2)) |
| B 带宽闸 `suppress`(P11-168/177) | **连开四枪**:19:06:59 / 19:07:16 / 19:07:22 / 19:07:27(`est 51~60M ≥ 26.6M`、`meas 28.2~31.9M ≥ 23.1M`)⇒ 水位急救被压住 |
| A 水位急救降档 | 全程只在 19:10:10 开了一枪(2160p→1440p):那一刻 `meas` 掉到 25.2M < 26.6M |
| 看门狗 | 19:07:31 `stall detected @pos=119586ms`(位置冻结 8s)→ auto-retry → 整场重载 |
| `stall-reached cooldown` | 19:07:31 记 `2160p excluded 90s` → 19:09:01 到期 → **19:09:22 又爬回 2160p** |

#### §39.8.3 病灶:请求节奏,不是降档判据

时间线(19:06:59–19:07:31,全部同一场会话):

```
19:06:59.379  fetch rn=9 itag=401 seg=8  bw=51125248b bufferedRanges=2
19:07:06.015  fetch rn=9 REAL 61447003B 6615ms → 74Mbps        ← 到手 61MB(≈21s 的 4K 媒体)
19:07:10.900  player state=BUFFERING                            ← 缓冲开始漏
19:07:16.574  chunk completed: media itag=401 bytes=19798305    ← 只交出 19.8MB
19:07:22.025  chunk completed: media itag=401 bytes=13326909    ← 再 13.3MB;ABR bufS=4.6s
19:07:23.033  fetch rn=10 itag=140 seg=12 … starving-fast-fail: bufAhead=4638ms ≤ 10000ms
                                                                ← 下一笔请求**到这时才发**,而且订的是 audio
19:07:27.558  first media chunk: trackNumber=2 itag=401 segmentNum=24   ← 4K 那一段这时才被要
19:07:30.899  fetch rn=10 REAL 73537217B 7860ms → 74Mbps        ← 往返 7.9s,数据到手晚 0.9 秒
19:07:31.809  stall detected, auto-retry #1 @pos=119586ms → 整场重载
```

**读法**:带宽与供给都在(单笔 61.4MB/6.6s、73.5MB/7.9s = 74Mbps,降档换轨还要再花一次
INIT+段往返,这次降档确实救不了)。缺的是**读前量**:上一笔响应交付完(19:07:06)到发出下一笔请求
(19:07:23/27)之间隔了 **17~21 秒**,这段空档正好把前方缓冲从 11.6s 耗尽到 0,而请求的 **7.9s 往返
直接踩在缓冲归零上** ⇒ 8s 看门狗开枪。**ABR 判据无从修复这一条**(它只决定播哪一档,不决定何时发请求)。

#### §39.8.4 顺带查实的三条独立问题(列入待办,本轮不改)

**(1) `meas` 这条腿是橡皮图章 —— 口径不是吞吐。**
[SabrMediaFetcher.getMeasuredBitrateBps](app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L553)
= `bytes × 8000 / (segs × 名义段长)` = **平均每段字节 ÷ 段名义时长 = 已交付媒体的码率**,与"多久送达"无关。
今天日志里它**恒等于声明码率的 ~1.2 倍**(2563/2158=1.19、28214/23134=1.22、12688/10286=1.23、
31934/23134=1.38)⇒ `measCoversTier` 只要攒够 ≥3 段就近乎恒真。真实吞吐应是「字节 ÷ 挂钟耗时」。

**(2) 顶档 sustained 闸的证据会被"停顿"抬高。**
`getSustainedBitrateEstimate` 会把 gap 中「需求空闲」部分(runway 滑行量以内)从分母扣掉 —— 本意是
满缓冲停闸不误杀。但今天 19:07:06→19:07:23 那 17s 恰恰是"有缓冲却不请求"(§39.8.3),被当成需求空闲
扣掉 ⇒ `sus` 仍报 33M ⇒ 顶档闸放行第二次爬 4K。**规则没错,错在它的前提被请求节奏缺陷破坏**。

**(3) stall 冷却与「重载+爬梯」赛跑。**
90s 从 **stall 时刻**起算,而实测重载 24s + 爬梯 81s ≈ **105s** ⇒ 冷却一到期就爬回同一堵墙
(19:09:01 到期、19:09:16 reseed、19:09:22 选中 2160p)。P11-188 的连击递增(90→180→300)最终能收口,
但用户先挨 2~3 轮卡顿。

#### §39.8.5 本轮做了什么 / 下一步(按证据强度排序)

- **本轮(纯诊断)**:请求行补 `sincePrevRespMs`(距上一笔响应完成多久)与 `bufAheadMs`(发请求那一刻的
  前方缓冲)—— 这两个数直接量化"读前量",是 §39.8.3 那条假设的验收/否证凭据。
- **候选修法(改前先在本节写口径)**:
  1. **请求读前量**(主):在缓冲降到阈值前就发下一笔 —— 阈值要等新日志的 `sincePrevRespMs`/`bufAheadMs`
     分布来定(先看数,不猜)。
  2. 顶档闸换不被"停顿"抬高的口径;或对 ≥1440p 目标把缓冲**地板按码率比放大**(27s@1440p(10.3M) 的
     runway 折成 4K(23.1M) 只剩 ~12s,这才是"能不能扛住新档"的实际余量)。
  3. stall 冷却从**重载后的重启**起算(而非 stall 时刻),让 90s 真正覆盖"重启+爬梯"。
- **否证线**:若新日志显示 `sincePrevRespMs` 都很小(如 <2s)且 `bufAheadMs` 健康(≥15s)却仍在 0 处 stall
  ⇒ 读前量假设不成立,转向 fetcher 的**交付/交接侧**(chunk hand-off 节奏:61MB 到手却分三笔在
  19:07:16/22/27 才交给播放器)。

### §39.9 P11-197 诊断落地即实锤:读前量确实不足 —— 续拉门槛 MinBufferMs=10s < SABR 往返 7.5~12s

新日志 `logs_live_20261005_203023.log`(dev.r2136,含 P11-197 的 `sincePrevRespMs`/`bufAheadMs`)一场
`p3fYRhADLRI`,20:28:27 起播 → 20:28:45 冷启动梯子爬到 itag315(2160p VP9 26.3M)→ 20:29:53 stall 重载。

**铁证:每次发请求都恰好踩在「前方缓冲 ≈ 10s」上**(请求行新字段):

```
20:21:09 fetch rn=12 itag=137  sincePrevRespMs=431    bufAheadMs=37915
20:21:56 fetch rn=13 itag=137  sincePrevRespMs=44180  bufAheadMs=9994    ← 空档 44s,到 10s 才续拉
20:22:00 fetch rn=15 itag=137  sincePrevRespMs=345    bufAheadMs=44376
20:23:39 fetch rn=18 itag=137  sincePrevRespMs=44875  bufAheadMs=9992    ← 又是 44s / 10s
20:29:39 fetch rn=7  itag=315  sincePrevRespMs=37295  bufAheadMs=5922    ← 4K:空档 37s,只省 5.9s 就拉
20:29:51 fetch rn=7 exception: timeout (fail=12010ms)                    ← 12s 上限切断,数据没到
20:29:53 stall detected → 整场重载
```

**机制**(media3 1.10 `DefaultLoadControl`,我们用 [createTvPlaybackLoadControl](app/src/main/java/com/kirin/mt/core/player/TvPlaybackLoadControl.kt)
配 `setBufferDurationsMs(MinBufferMs=10s, maxBufferMs=设置值(默认 50s), …)`):
`shouldContinueLoading` 的语义是「**buffered < minBufferUs 才继续拉**;buffered ≥ maxBufferUs 就停」——
所以一次响应到手(缓冲 25~50s)后 loader **停止取段**,一直等到缓冲掉到 **10s** 才回来要下一段。
10s 是 alpha.58 为「请求节奏与墙钟同步(paced)」刻意调小的;但 SABR 单笔往返实测 **7.5~12s**
(70MB/7.5s、另一场 12s 超时),**10s 的余量结构性不够** ⇒ 请求发出时缓冲已所剩无几,往返一抖就归零 ⇒
stall → 重载。itag137(4.4M)那几笔之所以没死,只是因为它掉 10s 的绝对耗时更长、往返更短。

**结论**:§39.8.3 记的「读前量不足」由疑似变成实锤;**降档判据与本病无关**(P11-168/177 那四枪
suppress 只是没帮上忙,不是病因)。

**候选修法**(待用户拍板;都有取舍,故先记录口径再改):
1. **按往返时长的动态续拉门槛**(推荐):给 SABR 走一层薄 LoadControl 包装,`shouldContinueLoading` 的
   续拉门槛改成 `max(10s, 最近一次本档 SABR 往返 × 1.5 + 3s)`(实测 7.5s ⇒ ~14s;12s ⇒ ~21s),
   其余照走 media3 默认。取舍:4K 档会多驻留 40~80MB(alpha.11 记过 50s×26Mbps≈162MB 的 GC/黑屏风险,
   已有 largeHeap + 单流分段缓解),要盯内存。
2. **固定抬高 MinBufferMs**(如 20~25s):一行改动,但所有内容源(含 B 站)一起变,且失去 alpha.58 的
   paced 意图;4K 内存代价同上。
3. **降单笔响应体量**(把上报的 bufferedRanges 收得更紧,让服务端少推):往返随之变短 ⇒ 10s 够用;
   但会改动 P11-111 已闭环的那条链,风险最高。

#### §39.9.1 本轮实现(方案 A:按往返时长的动态续拉门槛)

- [SabrAbrMemory](app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrAbrMemory.kt):新增往返样本
  `noteSabrResponseMs(elapsedMs)` + 门槛 `readAheadMinBufferUs()` = `max(10s, 最近往返×1.5+3s)`,
  封顶 **25s**、样本保鲜 **15 分钟**;无 SABR 活动 ⇒ 返回 0(完全退回 media3 原生,纯 B 站/本地/离线不受影响)。
- 新增 [SabrReadAheadLoadControl](app/src/main/java/com/kirin/mt/core/player/SabrReadAheadLoadControl.kt):
  包装 `DefaultLoadControl`,**只**拦 `shouldContinueLoading`(`bufferedDurationUs < 门槛 ⇒ 继续取段`),
  起播门槛/分配器/停止取段等全部原样委托 —— 不碰任何 ABR 档位判据。
- [createTvPlaybackLoadControl](app/src/main/java/com/kirin/mt/core/player/TvPlaybackLoadControl.kt) 增
  `readAheadMinBufferUs` 参数;TV/移动端播放器传 `{ SabrAbrMemory.readAheadMinBufferUs() }`
  (core.player 不反向依赖 sabr 包,故用 provider 注入;离线播放器不传 = 原生行为)。
- `SabrMediaFetcher` 在 **REAL**(成功)与 **异常/超时**两处喂样本 —— 超时按最坏情况(≈调用上限)记,门槛宁大勿小。

**验收**(真机):①日志出现 `YtSabrReadAhead: read-ahead threshold = Ns (last SABR round trip Mms …)`;
②此后请求行的 `bufAheadMs` 应 ≥ 该门槛(不再恒为 `9992/9994` 这一类"恰好 10s");③4K 档不再出现
「请求发出时缓冲 <10s → 往返 7.5~12s → 归零 stall → 整场重载」。

**否证**:①门槛抬到 25s 仍 stall ⇒ 往返不是唯一缺口,回到交付/交接侧(§39.8.3 否证线);
②4K 多驻留 40~80MB 若引发内存告警/GC 卡顿(alpha.11 前科)⇒ 降门槛上限或与 maxBuffer 设置联动。

---

## §39.10 P11-199:2026-10-05 真机「一直 480p」复盘 —— **声明码率虚高 ~3×,720p 明明喂得动却被判买不起**

> 触发:用户报「看下 log,一直 480」。日志 `logs_live_20261005_213214.log`(dev.r2137,SONY BRAVIA 4K AE2,
> `6Tx9e1D-5Oc` 20 分钟)。本轮**纯判读,未改行为**。

### §39.10.1 现象

20 分钟内 8 个 SABR 会话,**渲染分辨率绝大多数时间停在 854x480**(其间 360p↔480p 反复,末尾一度掉到 240p);
`video size: 1280x720` 全场只出现两次、每次仅数秒:

```
21:16:06 video size: 1280x720 → 21:16:12 video size: 854x480   (6s)
21:21:39 video size: 1280x720 → 21:21:53 video size: 640x360   (14s)
```

每次爬到 720p 都在数秒内被撤:

```
21:16:05 buffer-critical downgrade: bufS=0s itag302/720p@5965652 → 480p@1853490(P11-178)
21:20:17 buffer-critical downgrade: bufS=0s itag302/720p@5965652 → 480p@1853490(P11-178)
21:21:31 buffer-critical downgrade: bufS=0s itag302/720p@5965652 → 480p@1853490(P11-178)
21:21:44 downgrade 720p → 480p: est=3269K sus=6563K bufS=9s
21:29:09 buffer-critical downgrade: bufS=0s itag0:302/720p@8243818 → 480p@1834466(P11-178)
21:29:24 buffer-critical downgrade: bufS=5s itag0:302/720p@8243818 → 480p@1834466(P11-178)
21:31:35 downgrade 720p → 480p: est=3227K … meas=2729K … reason=trial-fail   ← 见 §39.10.2
```

### §39.10.2 主因:降档/试探判据拿**声明码率**当真,而声明码率虚高约 3 倍

`itag302`(VP9 1280x720@60)本场声明 **8243818 bps**(21:29 / 21:31 两个会话;21:15/21:20 两会话为
5965652;21:14 会话更是 2115288 —— **同一视频同一 itag 的声明值会在会话间跳 4 倍**)。ABR 的升降档判据
`required = f.bitrate` 用的就是这个声明值。

而**实际交付码率**(app 自己算的 `meas` 字段 = MEDIA_END bytes ÷ 段数÷名义段长)只有 **2.6~2.8 Mbps**:

```
21:29:24 sel=3 bitrate=8243818 bw=3262K sus=4100K meas=2772K
21:31:25 sel=3 bitrate=8243818 bw=4537K sus=-1     cap=8018K meas=2677K
21:31:35 sel=3 bitrate=8243818 bw=3227K sus=-1     cap=7937K meas=2729K   ← meas < bw,这档喂得动
```

逐段字节数也吻合(`itag=302 endSegNum=225 duration=1200000ms` ⇒ 5.33s/段):

```
seq=27 1841301B → 2.76M   seq=28 1166286B → 1.75M   seq=29 1135251B → 1.70M
seq=1  3246935B → 4.87M   seq=2  1983460B → 2.98M
```

即 **720p VP9 的真需求 ≈2.7 Mbps,而本场活跃 est 长期在 3.2~4.9 Mbps** —— 这条管子**喂得动 720p**。
但因为判据比的是 `est` vs **声明 8.24M**,结论永远是「买不起」:21:31:17 靠 `cap=8018K` 过闸试升 302
(`trial upshift (buffer-full probe): bufS=42s → itag302(720p) declared=8243818 est=4486K`),18 秒后
21:31:35 就 `downgrade 720p → 480p: est=3227K … meas=2729K reason=trial-fail` —— **同一行里 `meas` 已经
写明这档只要 2.7M,判据却只看声明值把它打回 480p**,随后 `720p excluded 90s`。

`meas` 通道早就有(`getMeasuredBitrateBps` / `measCoversTier`),但只挂在 P11-168/177 的
**suppress 闸**上(而那道闸还要求 `estHasMargin` 先成立 —— `est 3227K < 8243818×0.85` 故当场失效);
**降档与 trial-fail 这条主路径从不读 `meas`**。

### §39.10.3 帮凶:编码组锚定在 VP9 ⇒ 更省的同高度档够不着

本场会话只有 AVC + VP9 两族(无 AV01)。同高度两族的声明码率:

| 高度 | VP9(锚定族) | AVC |
|---|---|---|
| 480p | `244` 1834466 | `135` 1186074 |
| 720p | `302` 8243818 | `298` 3509144 |
| 1080p | `303` 15406188 | `299` 6002173 |
| 1440p | `308` 16260822 | — |
| 2160p | `315` 31366469 | — |

锚定族取「全组顶档那一档的族」⇒ VP9(天花板 2160p)。于是:①P11-192 的「同族优先」比较器在同一高度
上偏向 VP9 档 ⇒ 从 480p 往上,候选是 `302`(声明 8.24M)而不是 `298`(声明 3.5M);②P11-195 的
**天花板守卫**又把「换到 AVC 族」这条唯一能拿到便宜 720p 的路封死 —— 本场实锤两次:

```
21:21:50 codec group switch held (ceiling guard, P11-195): vp9 ceiling=2160p,
         更省的同高度候选族=[avc](天花板 avc=1080p)—— 换过去会砍掉画面上限,不换
21:29:26 (同上)
```

两边叠加后,梯子在 480p 与 720p 之间**只剩 VP9 的 1.83M → 8.24M 这一跳**,而链路恰好落在中间的空洞里。
(P11-195 §39.7 修的是「切到 AVC 之后 1440p 选不上」,本次是它的镜像面:**钉在 VP9 就拿不到便宜的 720p**。)

### §39.10.4 附带缺陷:单笔超时吃掉 20s 窗口 60% ⇒ 33s 缓冲仍两连降

```
21:31:47 fetch rn=21 exception: timeout (fail=11976ms bwNow=-1 silence-hang itag=244 recorded)
21:31:51 fetch rn=22 REAL 1253830B 4385ms → 2Mbps est=613K
21:31:51 sel=6 bitrate=1834466 bufS=33.6 … bw=613K cap=6601K
21:31:51 buffer-critical downgrade: bufS=33s itag0:244/480p@1834466 → 360p@1099340 silenceHang=true
21:31:52 downgrade 360p → 240p: est=613K sus=-1 bufS=32s
```

`est=613K` 可逐字节复算:`REAL_BW_WINDOW_MS=20_000` 的窗口里只剩两笔样本 —— 失败笔 0B/11976ms +
成功笔 1253830B/4385ms ⇒ `1253830×8000/16361 = 613,0xx bps`。**一笔读超时(8s 静默超时触发、计满
11976ms)就吃掉 20s 窗口的 73%**,把 est 从 3227K 砸到 613K(5.3 倍),于是 `silenceHang=true` 又让
P11-168/177 的 suppress 闸失效 ⇒ `bufS=33s`(远未饥饿)仍两连降到 240p,并给 480p/360p 各锁 90s。

### §39.10.5 候选修法(未实施,待拍板)

1. **降档主路径改读 `meas`(推荐,直击 §39.10.2)**:`required` 对**已交付过 ≥`MEASURED_MIN_SEGS` 段**的
   档改用 `min(declared, meas × 余量)`(或 `meas` 有证据时直接以它为准)。本场 `meas=2729K < est=3227K`,
   改完 720p 不会被 trial-fail 打回。风险:虚高只出现在 VP9 高档(§39.8.5-(a) 记过另一场 `meas ≈ declared×1.2`
   的反例)⇒ 需按 itag 攒样本再采信,且**升档仍保守**(升错了代价是卡顿,降错了代价是画面白降)。
2. **给 P11-195 天花板守卫加「买不起」例外**:当前族**下一高度档声明 > est×k**、而低天花板族在同高度有
   **est 买得起**的档时,放行换族(宁要 720p AVC 也不要 480p VP9)。需同时决定换族后是否允许「回切」。
3. **声明的会话间漂移本身要记账**:同一 itag 声明 2.1M/5.97M/8.24M 三值说明它不是稳定物理量;升档判据
   若继续依赖它,至少要在本会话内做一次「与 meas 的一致性检查」,不一致就降权。
4. **失败样本别独占窗口(§39.10.4)**:`addRealBwSample(0, elapsed)` 的单笔超时样本按 `min(elapsed, 5s)` 计入,
   或要求同一档连续 ≥2 笔零字节才允许把 est 压到当前档声明以下。

### §39.10.6 验收 / 否证线

- **验收**:①日志出现「降档/trial-fail 判据引用 meas」的痕迹(改法 1)或「ceiling guard 放行(买不起)」
  (改法 2);②`6Tx9e1D-5Oc` 这类「VP9 720p 声明虚高、实测够喂」的视频不再长时间钉在 480p;
  ③单笔超时不再让 `est` 跌破「当前档声明」并触发两连降。
- **否证**:①按 `meas` 降权后仍钉 480p ⇒ 病因不在声明虚高,回到链路本身(本场 link 峰值只 8~11M 突发、
  长期 3~4.5M,可能确实只到 480p);②改法 2 放行换族后 1440p 又选不上(P11-195 回归)⇒ 必须保留「换族后
  仍允许回切到高天花板族」的路径;③失败样本封顶后出现「真断流也不降档」⇒ 封顶值与条数阈值调紧。

---

## §39.11 P11-200 实施轮:档位基准改用「实测交付码率」—— 判据链复核 + 冲突清单(改代码前)

> 依据 §39.10。AGENTS.md 要求「调 ABR 升降档判据前先把整条判据链理一遍、把冲突写进本文档再改代码」,
> 本节即该复核。**改动只动「一档需要多少带宽」这个基准值,不动任何闸的门槛系数、不动豁免与冷却。**

### §39.11.1 病根坐实:声明的来源本身是「VBR 峰值」

```
21:15:49 YtResolver: declared falls back to VBR PEAK (itagItem 缺失,无 averageBitrate): itag=302 720p codec=vp9 peak=5965K
                     … itag=244 480p codec=vp9 peak=1853K / itag=135 480p peak=1301K …(本场共 19 行,含全部 avc 轨)
```

[YoutubePlaybackResolver.newPipeVideoRaw](app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L2216)
只在 `stream.itagItem != null` 时算得出 `averageBitrate`(= contentLength/approxDurationMs);
缺失时 `averageBitrate=0` ⇒ [buildSabrTrack](app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L3124)
回落 `bitrate`。**本视频 19 个 itag 全部缺失 ItagItem** ⇒ 全梯子的 declared 都是 VBR 峰值。

于是 2026-08-30「声明口径修正」的前提(**declared = averageBitrate = 真平均**)在本视频**不成立**;
那次修正顺手删掉的 calib 折算(类头 L56-64 记:「①**calib 机制整体取消** —— declared=真实平均后
required=f.bitrate 即实需」)因此留下一个空洞:**当 declared 是峰值时,没有任何机制把它拉回真实需求**。

### §39.11.2 用 declared 当「需要多少」的判据全清单(复核对象)

| # | 位置 | 判据 | 现状口径 | 本场失效方式 |
|---|---|---|---|---|
| 1 | [L1086](app/src/main/java/com/kirin/mt/core/youtube/sabr/media/HeightAwareAdaptiveTrackSelection.kt#L1086) | 主候选循环 `required`(当前档 ×0.85,其余全额) | declared | 720p 判成 8.24M > est(3.2~4.9M)⇒ 降档候选/升档候选同时被压 |
| 2 | [L909-921](app/src/main/java/com/kirin/mt/core/youtube/sabr/media/HeightAwareAdaptiveTrackSelection.kt#L909-L921) | P11-168/177 suppress 闸:`estHasMargin`(est ≥ declared×1.15)与 `measCoversTier`(meas ≥ declared) | declared | `meas ≥ declared` 在峰值声明下**结构性恒 false** ⇒ 闸形同虚设(33s 缓冲照降) |
| 3 | [L1147](app/src/main/java/com/kirin/mt/core/youtube/sabr/media/HeightAwareAdaptiveTrackSelection.kt#L1147) | 顶档 ×1.1 sustained 闸 | declared | same 病:VP9 4K 声明 60.6M/31.4M ⇒ 永不批(本次保守,不单独修) |
| 4 | [L1165](app/src/main/java/com/kirin/mt/core/youtube/sabr/media/HeightAwareAdaptiveTrackSelection.kt#L1165) | trial「超容量 1.5×」 | 经 `required`(=#1) | 随 #1 一起校准 |
| 5 | [L1253](app/src/main/java/com/kirin/mt/core/youtube/sabr/media/HeightAwareAdaptiveTrackSelection.kt#L1253) | 升档重锚锚点 | declared,已由 `cap×1.2` 夹住 | **不动**(P11-153①b 已有夹取) |
| 6 | [L740](app/src/main/java/com/kirin/mt/core/youtube/sabr/media/HeightAwareAdaptiveTrackSelection.kt#L740) | 冷却「提前解除」`est ≥ declared×1.1` | declared | 峰值声明下几乎永不成立 ⇒ 冷却跑满 90s。**刻意不动**(见 §39.11.4) |
| 7 | L1245/1273/1279/307 等 | 日志打印 | declared | **不动**(打印原始声明,便于与校准值对照) |

### §39.11.3 冲突与互相抵消(改之前必须标出来的)

1. **#1 ⟂ #2 同源**:两者都以 declared 为基准,#1 把档判成买不起、#2 又以「meas ≥ declared」否决翻案
   ⇒ 同一份假数据把**降档**和**防降档**两条路同时带偏(方向相反、结论一致:掉档)。
2. **#2 的 `estHasMargin` 已含 ×1.15**,若再在基准里叠余量会与 #3 的 ×1.1、#4 的 ×1.5 重复放大 ⇒
   **校准值本身不再叠任何余量**(实测交付码率已是「真需求」,余量交给各闸自己的系数)。
3. **与 P11-153①b(重锚夹取)不冲突**:那条防的是「锚到垃圾声明值把 est 抬一夜」,方向是**限高**;
   本轮的 `min(declared, meas)` 方向也是**限高**,同向叠加,不抵消。
4. **与 P11-188「未知(-1)不算证据」不冲突**:校准只在 `meas > 0` 时生效,`-1` 全额退回 declared
   ⇒ P11-188 那条腿的语义(证据不足 ⇒ 闸不成立)原样保留。
5. **与 P11-195(天花板守卫)不冲突但相邻**:#1 校准后「VP9 720p 买得起」,则 AVC 720p 那条更省的路
   不再是唯一出路,P11-195 的守卫得以维持原样(§39.10.5 方案 2 本轮**不做**)。
6. **#6 与 #1 的刻意不对称**:#1 判「当下扛不扛得住」(需要放宽,否则钉死);#6 判「要不要给刚判失败的
   档提前翻案」(宁严勿松,放宽会让刚失败的档立刻回爬)。两者用不同基准是**故意**的,写在此处防后人
   顺手统一。

### §39.11.4 本轮改动清单(P11-200)

- 新增 [HeightAwareAdaptiveTrackSelection.tierNeedBps]:**档位实需基准** = `min(declared, meas)`,
  `meas ≤ 0`(段数 < `MEASURED_MIN_SEGS=3`)⇒ 退回 declared(= 完全旧行为)。走既有
  `SabrBandwidthMeter.getMeasuredBitrateBps` 通道(与 #2 的 meas 同源)。
- #1 `required` 基准由 `f.bitrate` 改为 `tierNeedBps(f)`(当前档的 ×0.85 滞回系数不变)。
- #2 `curBitrateForGate` 改为 `tierNeedBps(getFormat(selected))`;`measForGate` 保持读原始 meas
  (它是「证据存在性」判据,不是基准)。
- #3 顶档 ×1.1 闸的基准改为 `tierNeedBps(f)`(系数 1.1 不变)。
- #4 随 #1(经 `required`)。
- 新增一次性取证日志 `tier need calibrated (P11-200): itag… declared=… meas=… → need=…(声明虚高 N.N×)`,
  **每 itag 一次**,否则「判据换基准」在日志里无从验收。
- **不动**:#5 重锚、#6 冷却提前解除、#7 日志、所有闸的系数/豁免/冷却时长。

### §39.11.4.1 改动前的前证:那次 trial 里缓冲**是在涨的**

判 `trial-fail` 撤回 720p 的那一刻(21:31:35),实测交付码率 2729K **低于**链路 est(3227K),
而同段的水位走势直接反证「720p 可持续」:

```
21:31:17 trial upshift → itag302(720p)   bufS=42.7   est=4486K cap=8018K
21:31:25 sel=3(720p)                     bufS=38.9 / 45.8 / 49.2   bw=4537K meas=2677K
21:31:35 sel=3(720p) → downgrade 720p → 480p   bufS=46.7 meas=2729K   ← 缓冲满着被撤回
```

18 秒里水位 33.6s → 49.2s **单调上升**,即「服务端按 2.7Mbps 供 720p、链路给得起」。
判据只看声明 8243818 就把它打回 480p —— 这正是本轮要修的那一枪。

### §39.11.5 验收 / 否证线

- **验收**:①日志出现 `tier need calibrated (P11-200)` 且 `need < declared`(本视频 itag302 应见
  `declared=8243818 meas≈2.7M → need≈2.7M`);②同一视频不再出现「est 3.2~4.9M 却 `downgrade 720p → 480p`
  / `reason=trial-fail`」这种「meas < est 仍判买不起」的降档;③低水位 suppress 闸开始生效
  (`buffer-critical downgrade suppressed … meas=…K` 且 bufS 明明有 30s+)。
- **否证**:①出现「升到 720p 后立刻饥饿降档」的循环 ⇒ 实测交付码率低估了真实需求(服务端按当前档
  pace 供流,meas 是「它愿给多少」而非「这档要多少」)⇒ 退回 `min(declared, meas×1.25)` 或按视频
  维度取 P90;②VP9 4K 顶档闸(#3)校准后开始批进 4K 又饿死 ⇒ #3 单独退回 declared 基准。

---

## §39.12 P11-203:2026-10-06 真机「4K 连续三轮整场重载」复盘 —— **带宽够 ≠ 供得上**,闸缺「交付节奏」那一腿

> 触发:用户「看下日志为什么重载」。日志 `logs_live_20261006_164353.log`(dev.r2146,BRAVIA AE2 4K,
> 视频 `pxnVBckDNXA` 38min,pid 9537)。本轮按规矩**先理判据链再改代码**(AGENTS.md)。

### §39.12.1 事实:4 次重载全是 stall 看门狗,没有一次是 ABR/trial/ExoPlayer 报错

判据:[PlayerScreen.kt](../../app/src/main/java/com/kirin/mt/ui/player/PlayerScreen.kt)
`StallThresholdMs=8_000`(未出首帧 `StartupStallThresholdMs=25_000`)——STATE_BUFFERING +
playWhenReady + **位置连续 8s 不前进** ⇒ `stall detected, auto-retry #1` ⇒ `retryKey++` ⇒ 整场重载。
日志里每次都是同一条链:`stall detected` → `player ENDED @pos=0ms` → `launch step: metadata/playurl`
→ `prepare startPos=<冻结位置>`。

| # | 时刻 | 冻结位置 | 当时档位 | 8s 内没进数据的直接原因 |
|---|---|---|---|---|
| 1 | 16:04:44 | 535805ms | 1440p(`308`) | `fetch rn=42 exception: timeout (fail=18007ms)`;前两笔 17MB 就要 6.6s/8.9s(est 21.7M→17.9M),重试又 `connection closed` ⇒ 音频段始终没到。**之后 resolve 也全线超时**(`att/get failed: timeout` / `NewPipe getInfo failed: timeout` / `postPlayer WEB … Timeout 45000ms`),16:04:44→16:07:31 黑了近 3 分钟才续上 |
| 2 | 16:22:11 | 715451ms | 2160p(`315`) | **误杀**:16:21:47 刚升 4K,16:22:02 用户 seek(209s→715s),seek 后首个 4K 段 39.3MB 往返 **7208ms**,数据 16:22:10.07 到齐,看门狗 16:22:11.76 开枪 —— **差 1.7 秒** |
| 3 | 16:32:10 | 1280217ms | 2160p(25.5M) | 4K 水位一路漏到 **3.5s**,往返 4.6~7.4s,`rn=67`(33MB/7.4s)之后到重载前**没有新请求发出** |
| 4 | 16:43:33 | 1933327ms | 1440p(`308`) | `fetch rn=75 timeout (fail=40002ms)`;重试服务端只回 971B 的 `SABR_REDIRECT`,`getNextSegment: no seg 416 itag 308`(服务端没这个段),再重试即挂死 |

即:**看门狗的枪是结果不是原因**;要修的是「为什么 8s 之内没有字节」。#1/#4 是链路与服务端(不属本轮),
**#2/#3 是 App 侧判据可修的**,两条各不相同:#2 = 阈值选择错(seek 后仍按「已出帧」的 8s 算),
#3 = 带宽闸把 4K 钉住不让降档。

### §39.12.2 「是不是升 4K 失败」——不是升失败,是升上去撑不住

升档本身**成功**:16:31:47 有 `video size: 3840x2160`,ABR 也确实稳在 `sel=0 itag315`。但 4 次里 3 次
死在 4K 上或紧随 4K 升档(#2/#3 明确 2160p,#4 是顶档冷却后回到 1440p)。4K 的问题是**升上去之后水位
被压在 3~16s**:itag315 单段 10~40MB、往返 4.6~7.4s,实测交付 28~30Mbps 对 25.5Mbps 的需求只剩 ~10% 余量。
循环形态(与 §39.6/§39.8 同签名,但触发闸不同):

```
16:21:47  upshift reseed: est baseline → 26666501 (itag315)     ← 升 4K
16:22:11  stall detected @715451ms → 整场重载 + `2160p excluded 90s`
16:25:14  upshift reseed → 25551738 (itag315)                    ← 2.5min 后又爬回 4K
16:31:38  sel=0(2160p) bufS=16.1 est=31681K meas=29903K → suppress
16:31:52  bufS=9.9 → fetch rn=67 往返 7392ms(33MB) → bufS=3.5
16:32:03  buffer-critical downgrade **suppressed** … bufS=3s est=31146K meas=28362K
16:32:10  stall detected @1280217ms → 整场重载
```

### §39.12.3 判据链复核(把 §39.1/§39.3 的表逐条对今天的状态)

| 关卡 | 今天实际 |
|---|---|
| 升 #11 `climbBufferFloorUs`(P11-193) | **在拦**:16:19:20 / 16:23:15 / 16:33:19 都有 `upshift held (buffer floor, P11-193) … bufS=9s < floor=30s` |
| 升 #5/#6/#7/#8(冷启动梯子 + est/sustained 闸) | 爬梯正常,4K 是**合法升上去的**(est 31~35M 确实过闸) |
| 降 A 水位急救 | **每轮都触发**:`bufS=3.5s < TOP_TIER_CRITICAL_BUFFERED_US=20s` ⇒ `bufferCritical` 成立 |
| 降 B 带宽闸(P11-168/177/200) | **连续 suppress**:`bufS=3~16s` 全被 `est 31~35M ≥ 25.5M×1.15`(29.4M)+ `meas 28.3~29.9M ≥ 25.5M` 挡下 ⇒ 4K 在位不动 |
| 降 C est 滞回 | 不成立(est 31~35M > required×0.85) |
| 降 D 升档后 5s 宽限 | 早已过期(升档在 16:31:16,stall 在 16:32:10) |
| 看门狗 | 每轮 8s 位置冻结 → 整场重载;**这是本轮唯一真正落地的「降档」** |
| `stall-reached cooldown` | 90s / 180s(repeat #2),而重载 24s + 爬梯几分钟 ⇒ 冷却到期即爬回同一堵墙 |

### §39.12.4 冲突清单(改之前必须标出来的)

**(1) 闸的第三条腿(`meas`)与 P11-200 校准**同源:今天 `curBitrateForGate = tierNeedBps = min(declared,
meas)` = declared(25551738,因为 meas 28362K > declared),`estHasMargin`(31.1M ≥ 29.4M)与 `measCoversTier`
(28.4M ≥ 25.5M)**同时为真** ⇒ 闸成立。注意这不是 P11-200 引入的:**校准只降权不抬权**,今天 meas 高于
declared,校准前后判据完全一致 ⇒ 病因是闸**少了一条腿**,不是基准错。

**(2) 与 §39.2-(2)「闸不动」的结论 —— 本轮为何仍要动它。**
§39.2-(2) 否掉的是「给闸开一个『刚切轨丢缓冲』的**例外**」(理由:那一刻 meas 已知且达标,降档要再切一次
格式、再丢一次缓冲)。本轮不是开例外,是**收紧闸的前提**:闸的语义是「带宽真撑得住 ⇒ 低水位只可能来自
排空/切轨」,而今天有它**没看的一手证据**——本档自己的请求往返 4.6~7.4s,水位只有 3s ⇒ 下一段结构性来不及,
低水位就是**真饿**,前提被证伪。新增一腿只可能**放宽降档**(永不增加 suppress),方向与 P11-188「未知 = 证据
不足 ⇒ 闸不成立」、与 §39.6 那句「一次切档的卡顿 vs 一次整场重载的 10~22s 黑屏,不对称,值得」一致。

**(3) 与 §39.11.5 否证线②**(「4K 校准后开始批进 4K 又饿死 ⇒ 顶档闸 #3 单独退回 declared」)**不冲突**:
那条说的是**升档侧**该不该放 4K 进来;本轮改的是**降档侧**该不该从 4K 退出去——4K 已经进来了(合法),退不出去才是今天的病。

**(4) 与 §39.6 末段「换档宽限另开一条,不去动带宽闸」**的分工:本轮**两条都做**,但各管各的——
①闸加「交付节奏」腿管**档位**(该不该降),②看门狗加 seek 宽限管**判定阈值**(该不该判死),
互不代偿。换档(非 seek)的 stall 宽限**仍不做**(§39.6 已论证先观察)。

**(5) seek 宽限与 P11-122(首帧宽限)同源不冲突**:P11-122 解决「慢起播把计时器灌满,首帧一到立刻开枪」;
本条解决「seek 后位置**停在目标点不动**,而数据要 5~8s 才到」——同一个看门狗、同一类假阳性、不同触发条件。

**(6) 与降档「一 episode 一枪」(`freezeEpisodeActive`)不冲突**:新腿只决定**能不能开这一枪**,开完依旧一次只降一档。

### §39.12.5 改动清单(P11-203)

1. **按 itag 记「最近一次 SABR 往返耗时」**(`SabrMediaFetcher`):成功记实际下载耗时、失败记计满时长
   (超时=最坏情况);证据保鲜 **90s**(超期视同无样本),与既有 `silenceHangWallMsByItag`(P11-178)同一套
   「fetcher 只记、选择类判」的分工。经 `SabrBandwidthMeter` 既有 provider 通道暴露(`getLastRoundTripMs`)。
2. **带宽闸加第四腿**(`HeightAwareAdaptiveTrackSelection`):`arrivalCoversBuffer = roundTrip ∈ (0, 水位/2]`
   —— 水位必须容得下**至少两次往返**;未知/过期(-1)按「证据不足 ⇒ 闸不成立」(P11-188 同向)。
   新增常量 `ROUND_TRIP_RUNWAY_FACTOR = 2`。**不改**任何既有系数(`×1.15`/`×1.1`/`×1.5`)、豁免、冷却时长。
3. **seek 后的 stall 宽限**(TV `PlayerScreen` + 移动 `MobilePlayerScreen`,`routeSeek` 两条分支都置位):
   新增 `SeekStallGraceMs = 15_000` —— 期内位置冻结改用起播阈值(25s)。依据:4K 单笔往返实测 7.2~7.4s,
   seek 后位置停在目标点不动 ⇒ 8s 判据在数据到手前 1.7s 就开枪。**只覆盖 seek**,不覆盖换档(§39.12.4-(4))。
4. 日志:闸的两行(suppress / downgrade)与 stall 行各补一个字段(`roundTrip=`/`seekGrace=`),否则新判据在日志里无从验收。

### §39.12.6 验收 / 否证线

- **验收**:①日志出现 `… suppressed` 与 `… buffer-critical downgrade` 时都能看到 `roundTrip=`,且 4K 在位
  时 `roundTrip > bufS/2` 的情形**不再**出现 `suppressed`(出现 `buffer-critical downgrade: 2160p → 1440p` 才是对的);
  ②`stall detected` 行出现 `seekGrace=true`,且 seek 后 8~15s 才落地的段不再触发整场重载;
  ③`pxnVBckDNXA` 这类「4K ↔ 重载」循环不再出现。
- **否证**:①降档变密(1080p/1440p 之间横跳)⇒ `ROUND_TRIP_RUNWAY_FACTOR=2` 太严,或往返样本被音频请求
  污染 ⇒ 退回只认视频主流的样本;
  ②seek 宽限期内真挂死(用户看到 15s 无反应才重载)⇒ 宽限值下调到 10s 或与「本档往返 ×2」联动;
  ③4K 从此再也爬不上去(刚升档时新档无往返样本 ⇒ 闸不成立 ⇒ 立刻被降回)⇒ 与既有
  `DOWNGRADE_AFTER_UPGRADE_GRACE_MS=5s` 叠加过紧,升档宽限要抬到 ≥ 一次往返。

---

## §40 P11-219:升降档判据链全列 + 真机「来回跳」复盘(改代码前,用户「升降档为什么不对」)

**起因**:真机 `logs_live_20261009_114649.log`(dev.r2160)里抓到一个完整的来回跳:

```
11:44:05.735  buffer-critical downgrade: bufS=0s  itag247/720p → 480p     ← 起播期水位=0 就降一档
11:44:05.735  downgrade fail cooldown: 720p excluded 90s                  ← 并把 720p 关 90 秒
11:44:52.920  upshift (cold-start ladder): itag248(1080p)
11:45:27.770  downgrade 1080p → 480p: est=1469K  bufS=13s freeze=false     ← 12 秒后又掉，跨过 720p
11:45:36.955  upshift: itag247(720p)
11:45:39.043  upshift: itag248(1080p)                                     ← 再过 2 秒又回 1080p
```

用户体感 = **清晰度来回跳**。以下是把 `HeightAwareAdaptiveTrackSelection.updateSelectedTrack`(:714-1330)
里**所有**允许/否决升档与降档的规则逐条列出(每条都对应一个日志点,便于真机核对)。

### §40.1 判据链(按代码执行顺序)

| # | 规则 | 位置(日志点) | 触发条件 | 作用 |
| --- | --- | --- | --- | --- |
| 0 | 非视频组直通 | `:714` 头 | 组里没有 height>0 的轨 | 交给父类 |
| 1 | **提前解除冷却** | `cooldown cleared early` `:754` | `bufS ≥ EARLY_CLEAR_BUFFERED_US(20s)` 且 `est ≥ 声明×1.1` | 清掉"降档失败冷却" |
| 2 | 塌方守门 | `bufferCollapseArtifact` | 水位读数周期性 reset(own-range-null 家族) | 该轮不算"低水位",`bufferCritical=false` |
| 3 | 冻结 episode | `freeze episode` `:839` | `belowCritical` 且 `freezeEpisodeActive` | 一次饥荒**只降一档**,后续 suppress |
| 4 | **水位急救带宽闸** | `buffer-critical downgrade **suppressed**` `:958` | `est ≥ 需要×1.15` **且** `meas ≥ 需要` **且** `roundTrip ∈ (0, bufS/2]` **且** `!silenceHang` | 撑得住 ⇒ **不开枪**(P11-168/177/188/203 四条腿) |
| 5 | **水位急救降档** | `buffer-critical downgrade` `:1000` | `bufferCritical = !塌方 && belowCritical && !冻结 && (水位不升 \|\| silenceHang) && (trialAbort \|\| 距升档 ≥5s)` | 降到**下一个未被排除**的档 |
| 6 | 顶档冷却 | `top-tier cooldown` `:1023` / `top-tier startup-stall cooldown` `:1115` | 顶档(≥2160)水位急救或起播 stall 后 | 顶档排除 `TOP_TIER_BUFFER_CRITICAL_COOLDOWN_MS=180s` |
| 7 | 降档失败冷却 | `downgrade fail cooldown` `:1408` | 某档降档后仍饿(`markDowngradeFromTrial`) | 该档**排除 90s**(`TRIAL_FAIL_COOLDOWN_MS`),跨重载存活 |
| 8 | 编码族天花板守卫 | `codec group switch held` `:560` | 当前族天花板 ≥ 目标族 | 挡"换族"这条拿便宜档的路(P11-195) |
| 9 | **升档缓冲地板** | `upshift held (buffer floor)` `:1164` | `bufS < floor=min(30s, max(15s, 0.8×maxObserved))` | 不开升档(P11-193) |
| 10 | 试探超容 | `trial refused (over-capacity)` `:1215` | 试探档声明 > 当前容量×1.5 | 不开试探 |
| 11 | **常规 est 滞回降档** | `downgrade A → B` `:1261` | `est < 当前档需要 × DOWNSHIFT_MARGIN_PERMILLE(850)/1000` | 降到下一个**未排除**的档 |
| 12 | 满缓冲试探升档 | `trial upshift (buffer-full probe)` `:1286` | 缓冲满 + 试探档不超容 | 升一档试探(失败则 90s 冷却) |
| 13 | 升档 + 重锚 | `upshift reseed` `:1315` / `upshift (cold-start ladder…)` `:1322` | 带宽地板达标 | 升档并把 est 重锚到新档声明值 |
| 14 | 起播锁 | `startup lock` `:316` | 首帧前 | 锁在起始档,首帧后释放(P11-128:锁内部,不换选择集) |

### §40.2 冲突 / 互相抵消(逐条标出)

1. **规则 5(水位急救)没有"起播期"豁免** —— 起播时 `buffered == 0` 是**必然状态**,`belowCriticalUs` 天然成立
   ⇒ 起播必开一枪。真机实证:`bufS=0s itag247/720p → 480p`,**并把 720p 用规则 7 关了 90 秒**。
   ⇒ 后果不是"降一档",而是**中间层消失 90 秒**:此后规则 5/11 都只能挑"下一个未排除的档" ⇒ **跨级跳**
   (`1080p → 480p`),升档也得多爬一级(11:45:36 720p → 11:45:39 1080p)。
2. **规则 11(est 滞回)不看水位** —— 真机 `downgrade 1080p → 480p: est=1469K **bufS=13s**`:
   缓冲很健康,仅凭 est 塌陷就降档。而那条链路的 est 是"一阵 1Mbps(三笔慢样本)、一阵 36Mbps"
   ⇒ **一次塌陷就能砍档位**,10 秒后又靠规则 13 爬回来 = 抖动。
3. **规则 4(带宽闸)只作用于规则 5**,不作用于规则 11 —— P11-168 的立论("带宽撑得住时低水位不是供给不足")
   在 est 路径上没有对应物;两条降档路的守卫**不对称**。
4. **规则 7(90s 冷却)是放大器** —— 它把"一次假降档"变成"90 秒内少一层梯子",而规则 1 的提前解除要求
   `bufS ≥ 20s`(起播/抖动场景里往往不到)。
5. **规则 3(一次饥荒只降一档)与规则 5 的分级挑选互相抵消** —— 前者想"一次只降一档",后者却因为
   冷却档位被排除而**一步跳两档**。两条同时成立时,用户看到的是"跳级"。

### §40.3 候选修法(未实施,待拍板)

- **① 起播期不开水位急救**:加一条"本会话曾有过缓冲"的前置(`maxObservedBufferedUs > 0`,该变量已有),
  即**从没缓冲过 ⇒ 不算低水位** —— 起播该等首段,而不是降档。同时消除规则 7 的假阳性冷却
  (连带消除跨级跳)。**风险**:真·慢启动(服务端慢滴)时少一次自救;但起播的自救本应由
  25s 启动看门狗兜底(与 P11-173 对"饥饿快切"的豁免同源)。
- **② est 滞回加缓冲健康豁免**:规则 11 在 `bufS ≥ DOWNGRADE_BUFFERED_US(8s)` 且 est 只跌了一轮时**再等一轮**
  (滞回),避免"三笔慢样本砍档位"。**风险**:真·带宽塌陷时降档晚 1 个评估周期(评估周期 = 每次 getNextChunk)。
- **③ 冷却档位不参与"下一步降档"挑选?** —— 不建议:它会让"降到哪都饿"时无档可降。
  ① 修好之后 ③ 自然不需要。

**判据(真机)**:起播场次不得再出现 `buffer-critical downgrade: bufS=0s`;
`downgrade … → …` 的 from/to 高度差应为**一档**(不再跨级);同一 session 内 `upshift` 与
`downgrade` 的交替次数下降;用户体感"清晰度不再来回跳"。
**否证**:①起播变慢或起播档偏高一档导致饿 ⇒ 起播期仍需要一次降档,但应**不锁冷却**(退一步:只降档不冷却);
②滞后一轮后出现真饿死 ⇒ 把滞回限定在"bufS ≥ 8s"时才等。

### §40.4 P11-220 实施:修复①(起播期不做水位急救)

**改动**(`HeightAwareAdaptiveTrackSelection.updateSelectedTrack`):

```kotlin
val lowBufferIsEvidence = maxObservedBufferedUs > 0L || silenceHang     // 新增
val bufferCritical = lowBufferIsEvidence && !bufferCollapseArtifact && belowCriticalUs && …
```

- **判据**「本实例是否曾缓冲过」用**已有字段** [maxObservedBufferedUs](:706,满缓冲试探用的"见过的最
  高水位")—— 从没缓冲过(起播期)⇒ 不算低水位,等首段。**零代码新增语义**,只是给既有水位急救加一道前置。
- **silenceHang 例外**:零字节挂死是**独立证据**(fetcher 按 itag 记的墙钟),与水位无关 ⇒ 起播期真挂死
  (20s 零字节)照旧允许降档自救。这与 P11-173 对"饥饿快切"的起播期豁免**同源同向**。
- **一次性日志**:`water-level rescue held (startup, P11-219): bufS=0s 从未缓冲过 ⇒ 等首段(不算低水位)`
  (每实例一次,便于真机核对本判据生效)。
- **时间细节**:`maxObservedBufferedUs` 的更新在 `bufferCritical` **之后** ⇒ 首个"有缓冲"的评估轮仍被挡一次,
  下一轮放行 —— 正好是我们想要的"等一拍"。

**连带效果**:起播期不再开那一枪 ⇒ **不再产生 `downgrade fail cooldown: 720p excluded 90s`** ⇒
中间层不会被关 ⇒ 消除"降档跨级跳"与"升档多爬一级"(§40.2 冲突①的整条后果链)。

**判据(真机)**:起播场次不得再出现 `buffer-critical downgrade: bufS=0s`;应出现
`water-level rescue held (startup, P11-219)`;此后同一 session 内 `downgrade` 的 from→to **高度差为一档**;
`降档 fail cooldown` 不再在起播 1 秒内出现。
**否证**:①起播变慢或起播档偏高一档后饿死 ⇒ 退一步:**仍降档但不锁冷却**(只放开 P11-220 的冷却部分);
②`water-level rescue held` 出现后紧跟 `stall detected` ⇒ 起播期确实需要那一枪,但应改用更早的判据
(例如"首段往返耗时 > 起播档段时长×1.5"这类供给证据),而不是"水位=0"。

### §40.5 P11-221 优化:带宽判据改「按每笔请求记账」(用户口径)

**用户口径(原话提炼)**:每次请求,按**清晰度 + 段数**可以估算这笔请求的**应得流量**;实得达到应得所用的时间才是
**有效时间**;而且**两次请求之间不会产生新流量**(那段空窗不该进带宽分母);**「有请求没有回复」也是很清晰的一类**。

**与现状逐条对照**:

| 用户口径 | 现状 | 差在哪 |
| --- | --- | --- |
| 应得流量(档位码率×段数) | 无此概念;`tierNeedBps` 只做"档位需要多少"的校准 | 缺"这笔该给多少" |
| 实得流量 | `REAL <readBytes>`(P11-217 起已是**实读**) | ✓ 已有 |
| 有效时间 | `elapsed`(到拿够为止) | ✓ 已有 |
| **空窗不进分母** | **`recordFetchGap` 会把"超出缓冲可滑行量的空窗"按 `bytes=0` 注入 est**(alpha.9Z 为"GC 卡死"加的) | ❌ **唯一的注入点**,就是它把 est 拉低 |
| 有请求没回复 | 只在 `SocketTimeoutException` 记 `silenceHang`;"200 但零媒体字节"没单独计 | ❌ 缺一类 |

**注意**:est 的窗口**本来就是 Σ elapsed**(不是墙钟)——`addRealBwSample` 的滚动条件是 `realBwTimeMs > 20s`,
而 `realBwTimeMs` 是样本耗时累加。所以用户模型与现有实现只差**那一笔 gap 注入**,不是整套推翻。

**P11-221 第一步(本轮,单变量:只改"量",门槛一律不动)**

1. **停用 gap 入账**:`recordFetchGap` 里的 `addRealBwSample(0L, countedMs)` 改为**只记日志不入账**
   (常量 `BW_GAP_FEED_EST = false`,一行可回退)。理由:那是"没有在途请求"的时段,按用户口径不产生流量证据;
   P11-197 也记过它的副作用(每次"满缓冲停拉/降档后停拉"都被当成一次低带宽事件)。
   **保留**:`demandIdleMs` 判定、`bw gap ignored`/`bw gap counted` 日志、以及 `sustained` 的 gap 扣减(那是**分母**修正,方向不同、与用户口径一致)。
2. **「有请求没回复」升级成一类独立证据**:新增 `zeroReplyCount` 与一次性日志
   (`zero-reply: 响应只有 XB(无媒体段)` / 失败路径的零字节)——它**不混进 est**,而是独立可数、可对照。
3. **每笔的应得/实得**先用现有量表达,不新造:请求行已带 `bw=/sus=/cap=/meas=`;`tier need calibrated` 已打
   `declared vs meas`(=该档"声明需求 vs 实测交付"),本步不重复造轮子。

**判据(真机)**:①"满缓冲停拉"与"降档后停拉"期间 **est 不再下探**(旧日志里那类 `bw gap counted` 紧跟 est 掉档的
场面应消失);②`zero-reply` 计数与 `no seg`/段未送达的场次对齐(而不是散落在正常场次里);③**门槛未动** ⇒
升降档行为与 alpha.13 逐条对得上(只应看到"est 更稳",不该看到档位选择变化)。

**否证**:①真·带宽塌陷时 est 反而偏高(因为没有在途请求也不计、失败笔又少)⇒ 说明"没有在途请求"的时段
必须按**上一笔的应得**计入(即回退本步或改成"应得/耗时");②`zero-reply` 计数在正常场次里也涨 ⇒ 判据太宽
(例如把"服务端只推了别的档"也算进来)⇒ 收窄到"本档一段都没到"。

**第二步(下一轮,动门槛)**:新量经真机确认更诚实后,把**升档缓冲地板**从"固定 30s"改成
"覆盖两次本档往返"(复用 P11-203 的 `roundTrip` 证据)——那时 9~18s 的缓冲才有资格放行 4K。
