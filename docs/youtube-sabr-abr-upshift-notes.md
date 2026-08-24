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
| 2026-08-24 | **建立真实带宽机制**:SabrMediaFetcher 从实际段下载(bytes/elapsed)记录样本,取最近 8 个中位数 `getRealBitrateEstimate()`;relax 放档判定改用真实带宽(替代不可信的媒体3 带宽计)。诊断行同时打 `bw=`(媒体3计)与 `realBw=`(真实)对照 | SabrMediaFetcher.kt / DefaultSabrChunkSource.kt | 本次新增,待真机复测 |

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

- [ ] **带宽计不可靠已坐实,已建真实带宽绕行**(§5 末行):媒体3 `getBitrateEstimate()` 真机 1M↔437M 1000 倍跳变,effectiveBitrate 不可信;改为 SabrMediaFetcher 实际段下载测真实带宽(中位数)驱动 relax 放档。待真机复测 `realBw=` 稳定后,下一步做**排除式强制升档**:relax 时排除当前低档,逼媒体3 `determineIdealSelectedIndex` fallback 返回唯一非排除高档,绕开不可信的 effectiveBitrate
- [ ] re-resolve 掉档后如何不长期停留低档
- [ ] relax 强制升档方案是否采用(fallback 绕行,需评估 rebuffer 风险)→ **已定采用**,基于真实带宽机制,待实现
