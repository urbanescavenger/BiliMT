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
