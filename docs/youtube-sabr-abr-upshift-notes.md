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
