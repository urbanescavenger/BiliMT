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

### 未决/风险

- [ ] **真机复测**:4K 播放中应出现 `bw gap counted:` 日志,est 回落到可持续值(<39.4M)后自动降到 308/303,看门狗重载不再死循环;好网络下 4K 仍能稳定维持(填充期 gap≈0 不受影响)
- [ ] 段间 ~10s pacing 的归属(服务端限速 vs 客户端队列上限)未最终定性——若为客户端刻意 2 段队列上限,gap 计时会系统性低估带宽;但升档滞回(缓冲 ≥30s 才升)保证最坏结果是 315↔308 震荡而非永久低档
- [ ] GC 风暴根因未治:4K VP9 段 buffer(10-25MB)把 ~400MB 堆打满,单次 46MB `response.body?.bytes()` 分配即触发连环 GC;根治需段落盘/流式处理,另立项目
