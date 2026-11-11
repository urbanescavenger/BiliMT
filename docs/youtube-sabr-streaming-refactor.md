# SABR 流式消费改造计划（P11-314）

> 状态：**计划 + 历史验证完成；Stage A（交接面）已落地（P11-315），待云编译/真机验证**
> 触发：2026-11-10 真机 `logs_live_20261110_142010.log`（4K 手选 2160p，无重载但 4 次
> 0.5~6.6s 卡顿）
> 关联：P11-217/218（流式读 + 早停）、P11-305（慢滴让路）、P11-313（两端看门狗同源）

---

## 0. 结论先行

- **现状**：`SabrDataSource.open()` = 一笔 POST = 一次交付；读循环、解析、交付在同一个调用栈，
  播放器要等**整笔响应结束**才能拿到段（margin 后继段也算在等待里）。
- **目标**：对齐 YouTube 自家客户端的消费模型——**会话级 reader + 每格式段队列**，
  `MEDIA_END` 一落地就交付；请求策略由队列水位驱动，不再由 loader 的阻塞调用触发。
- **分三步**：A 交接面迁移（行为等价）→ B 请求策略水位驱动（去掉 margin 阻塞）→ C 可选并发（默认不做）。
- **历史验证**：17 个可归因的短卡顿里，**9 个（53%）的请求段在冻结前就已到手**（早交付可完全避免），
  **8 个（47%）在冻结期间到手**（可缩短，中位 1.0s）；**94% 的响应耗时超过了请求时的缓冲余量**；
  margin 多等的中位数 **3.8s**、p90 **6.2s**。

---

## 1. 现状与证据

### 1.1 代码链

```
SabrDataSource.open()                     [loader 线程]
  └─ fetcher.getNextSegment(req)
       └─ runBlocking { withContext(dispatcher) { media(req) } }   [单通道]
            └─ fetchStreamData: 一次 POST，读循环 + UMP 增量解析
                 ├─ MEDIA_END(请求段) → initializedFormats[itag].downloadedSegments
                 ├─ MEDIA_END(后继段)…
                 └─ streamNeedSatisfied() → 早停、关连接、返回
       └─ fmt.getSegment(请求段) → 返回给 DataSource
  └─ open() 返回，read() 才开始把字节喂给 extractor
```

三个前提被写死在实现里：

- `dispatcher = Dispatchers.IO.limitedParallelism(1)`（`SabrMediaFetcher.kt:280`）；
- `downloadedSegments` / `bufferedSegments` 是普通 `mutableMapOf()`（`SabrSegment.kt:40-42`）；
- `respPacing*` / `inFlight*` / `backoffTime` 等是单例上的普通字段，注释明写“请求在 dispatcher 上
  串行 ⇒ 普通字段即可”（`SabrMediaFetcher.kt:314-318`）。

### 1.2 2026-11-10 真机（4K 手选 2160p）

| 卡顿 | 时长 | 在途请求 | 请求时缓冲 | 请求段落地 | 整笔返回 |
|---|---|---|---|---|---|
| 14:18:53 | 6.59s | rn=27 seg128 | 16.82s | 16.84s | 18.78s（10 Mbps） |
| 14:19:14 | 0.47s | rn=30 seg131 | 8.66s | **4.25s** | 9.00s |
| 14:19:34 | 1.04s | rn=32 seg135 | 12.74s | **6.70s** | 13.69s |
| 14:19:59 | 2.96s | rn=36 seg139 | 7.00s | **6.20s** | 9.83s |

后三次的请求段都在缓冲耗尽前到手，是**等 margin 后继段**把整笔拖过了 runway。第一次是链路
瞬时掉到 ~10 Mbps，请求段本身就超了 runway，与 margin 无关。

### 1.3 2026-11-11 判读：2160 掉档先查交付节奏，不是带宽（P11-319 三面诊断首用）

`logs_live_20261111_151323.log`（最新，XQ-EC72，全程走 `127.0.0.1:10808` HTTP 代理）：

| 时刻 | 事件 | TTFB | bodyRate | pace | est |
|---|---|---|---|---|---|
| 12:21:54 rn=0 | 2160 段交付 | 1169ms | 35.9Mbps | – | 27.0Mbps |
| 12:21:58 rn=1 | 2160 段交付 | 876ms | 32.6Mbps | 0.99x | 26.3Mbps |
| 12:22:10 rn=2 | 读完 21.9MB 后 **12.0s 超时** | 1952ms | – | – | 6.5Mbps |
| 12:22:13 | stall → auto-retry #1（仍保 315） | | | | |
| 12:22:27 rn=0（新会话） | 只给 0.5MB，**2.3s 无首包** | 2772ms | 5.5Mbps | – | 1.2Mbps |
| 12:22:29 | 用户手选 1440p（`点=308`） | | | | |

- 2160 健康窗口 `bodyRate` 32–36Mbps、`est` 25–30Mbps，高于该轨实测码率 18.5–21.5Mbps
  （同日 `logs_live_20261111_092312.log` 的 `tier need calibrated`）；掉档由「响应中途静默
  12s + 下一笔慢启动 2.3s」触发，按 P11-319 判据属**延迟型**，不是带宽型。
- 同日 09:15 场同签名：一次 11.7s 静默（`read=19925483B`）后 6.4s TTFB 的下一笔把缓冲打到
  11.9s，才触发 buffer-critical 降档；09:22 重新升回 315 后 bw 30–37Mbps、`meas`≈21Mbps、
  `pace` 1.9x 稳定。⇒ 顶档 180s 冷却把「一次交付停滞」当成了供给不足。
- 反例：12:23:58 之后（已切 1440p）链路确实掉到 6–13Mbps（rn=11 `pace` 0.83x、bodyRate
  8.7Mbps），该窗口 2160 确实不够。判读时须区分「停滞窗口」与「真实低带宽窗口」。
- 非新问题：扫本地 293 份日志，4K 场次在 r2115/r2155/r2169/r2249/r2266/r2279 等旧版本同样出现
  callTimeout / silence-hang / stall（r2266 一场 19 次 callTimeout、r2169 一场 8 次 silence、
  10-25 那场 43 次 callTimeout）。P11-307（11-02 起）之前，超时即使请求段已交付也会整会话
  evict；现在只对「请求段未交付」仍走 evict（`SabrDataSource` 通用 catch，新旧一致）。

### 1.4 2026-11-11 15:25 手切 2160，1 分钟后整场重载（dev.r2309）

`logs_live_20261111_152649.log`：

| 时刻 | 请求 | 结果 |
|---|---|---|
| 15:25:46–15:26:02 | rn0/1/3 | 30–35Mbps，2160 正常 |
| 15:26:06 rn5 (seg241) | 请求段已交付，读到 27.4MB 后 12s callTimeout | P11-307 当成功，不 evict |
| 15:26:18 rn6 (seg242) | 同会话重取，23.17MB/4.6s = 40Mbps | **会话没死，重取即恢复** |
| 15:26:26 rn8 (seg244) | 22.79MB 段，12s 只读到 7.27MB（4.8Mbps） | 请求段未交付 |
| 15:26:38.805 | `SabrDataSource` 通用 catch → evict | 整会话丢弃 |
| 15:26:43.548 | `stall detected` → `loadRequest quality=315` | 整场重载 |

同一场更早的同类签名：15:20:04 rn26 23.9MB/14.3s（13Mbps，pace 0.53x）；15:22:16 rn20
`fail=40005ms read=13.8MB`（2.8Mbps 慢滴烧满 40s）。结论：不是带宽不够，而是单笔请求偶发
掉到 3–5Mbps 慢滴；12s starving callCap 到点后请求段未交付 ⇒ evict ⇒ 8s 后看门狗整场重载。
修法指向「慢滴早断（按剩余缓冲/近期速率，1–2s 内） + 同段同会话重取（≤2 次）」，且请求段
未交付时不 evict。

### 1.5 2026-11-11 同一场的「480 锁定」判读

第一场（15:17:42）并没有停在 480：启动锁 720p → 1080p（15:17:54）→ 1440p（15:17:55）→
2160p（15:18:01）。停在 480 的是两次重载之后：

| 场景 | 证据 |
|---|---|
| 重建后首笔样本被 TTFB 污染 | 15:20:26 `rn0 590KB/1598ms ttfb=1073ms bodyRate=8997K → est=2956K`；15:20:28 `rn1 525KB/2257ms ttfb=1486ms bodyRate=5452K → est=2315K`；15:20:30 `downgrade 720p→480p` + `720p excluded 90s` |
| 同上（第二场） | 15:23:11 `rn0 531KB/2035ms bodyRate=2854K → est=2089K`；15:23:14 `downgrade 720p→480p` + 90s |
| 低档小段 + 满缓冲停闸 | 480p 段 0.2–0.5MB，缓冲填到 50s 后下载器停；`susRaw` 长期 1.2Mbps，`trial refused (sustained low)` 挡住 720p 试探 |
| 中途（非首帧）也会锁 | 15:23:27 rn10（720p）1.46MB/4.5s `bodyRate=3049K pace=1.04x`；15:24:09 rn11 `bodyRate=3180K pace=1.67x`；15:24:15 rn12 触发 `buffer-critical downgrade 720p→480p` + 90s |
| 中途降档与带宽无关 | 15:19:56 `bufS=6s` + `roundTrip=4719ms` 把 2160p→1440p，当时 `bw=34209K meas=20400K` |

结论：`recordRealBandwidthSample` 按「字节 ÷ 总耗时（含 TTFB）」记账，在重建后的首笔小响应上
会把 est 压到 2–3Mbps；而低档 720p 的服务端交付本身就是 `pace≈1.0–1.7`（按播放速率供），
用 bodyRate 当链路带宽会长期低估。急性问题只在首帧/重建后；慢性低估和中途的 90s 冷却锁定
全时段都有。中途 480p 请求的 `bodyRate` 可达 7–18Mbps，证明同一链路并不只有 3Mbps。

### 1.6 修改收益的历史日志校核（2026-11-11）

口径：本地 293 份日志（265 份含 SABR 事件）；只有 dev.r2309 的 3 份带 `ttfb/bodyRate`
诊断，共 14 个会话、244 笔成功请求。

**M1：带宽样本改用 bodyRate（首字节→末字节），init/极小响应不计**

| 分层 | 笔数 | bodyRate/总耗时 中位 | p75 | ≥2× | TTFB 占比 >30% |
|---|---|---|---|---|---|
| 全部 | 244 | 1.41× | 1.98× | 58（23%） | 118（48%） |
| <1MB | 75 | **2.73×** | 4.02× | 49（65%） | 66（88%） |
| ≥1MB | 169 | 1.29× | 1.53× | 9（5%） | 52（30%） |

⇒ 小响应（480p/720p 段、启动 init）是系统性低估源；大段几乎不受影响。

**M1+M2：启动降档**

- r2309 的 14 个会话里 **4 个**在启动 8s 内 `downgrade 720p → 480p`（est 1.54–2.51Mbps）；
  同会话前 3 笔 bodyRate 中位数 5.1–11.5Mbps，均高于 720p 声明 3.54Mbps。
- 这 4 次降档造成的 <720p 时长：**49.3s / 6.7s / 3.3s / 13s**，合计 ~72s；
  3 份日志总播放约 1300s ⇒ 约 **5.5%** 的播放时间。
- 全部日志中「会话开始 8s 内的 est 降档」仅 r2309 有 4 次（旧构建多为 bcd/无该日志），
  说明这是当前构建的急性问题；用 bodyRate 记账即可全部消除。15:23:14 那笔首样本
  2.85Mbps 略低于 3.54，需「首笔不单独决定 / 连续 2 笔低样本」兜底。

**M3：慢滴早断 + 同段同会话重取（全部日志）**

- `timeout` 类 evict 430 次：104 次（**24%**）在 10s 内出现 ≥5Mbps 的成功请求（重取可救的上限）；
  134 次（31%）在 10s 内跟着 `stall detected`。
- 全部 `stall detected` 322 次中，134 次（**42%**）紧跟在 timeout evict 之后。
- 当前构建实例：15:26:38 `seg=315 timeout → evict` 后，下一笔 4.77s 读了 15.28MB
  （≈25.6Mbps）才被重载掐断 ⇒ 同段重取有足够吞吐。

**M4：冷却**

- 90s `downgrade fail cooldown`：562 次；313 次在会话内重新达到被排除档（恢复中位 12.3s、
  p75 33.3s）；249 次会话结束前未恢复（含正常结束，不能全算损失）。
- 180s `top-tier cooldown`：43 次；仅 13 次在会话内恢复，恢复中位 **204.8s**（基本等冷却到期）；
  30 次会话结束前未恢复。
- 当前构建实例：09:18:13 拉黑 2160p，实际 09:22:07 才重新升回（**234s**）；而 09:22 场
  2160 实测 30–37Mbps、pace 1.9x ⇒ 那次拉黑是误伤。

**校核风险**：bodyRate 反事实只能直接算 r2309；跨版本计数受 ABR 版本差异影响，只作量级参考；
`never recovered` 包含会话正常结束。改后观察点：启动 8s 内 `downgrade` 应为 0；启动 10s 内
`downgrade fail cooldown` 应为 0；`timeout → evict` 应变成 `retry same seg`；`stall detected` 应下降。

**dev.r2310 真机首场验证（2026-11-11 18:53，同视频 `OwGemVUAUOQ`，Auto）**

- 18:53:31 `bw startup sample held (M2): 528783B/498ms`：该笔整笔口径 528KB/1127ms=3.75Mbps，
  bodyRate=8.49Mbps；暂存期间 `est=-1`（meter 回退 seed 5000K），没有触发 720p→480p。
- 第二笔 2.04MB/1521ms（bodyRate 16.9Mbps）入账后 est=14054K；18:53:41 升 1080p、
  18:53:42 升 1440p、18:53:48 升 2160p（**会话开始后 18.3s**）。
- 全程 172.8s 无 `downgrade` / `downgrade fail cooldown` / `stall detected`；2160p 播放
  154s，`bw` 30–42Mbps、`meas` 21–34Mbps、pace 1.5–2.4x。
- 对照 r2309 同视频 09:15 场：启动 8s 内 720p→480p（est 1542K），49.3s <720p，
  2160p 用了约 59s。
- 判读注意：本场链路本身很好（2160 `meas` 21–34Mbps），2160 能播**主要是带宽够**；
  首笔整笔口径 3.75Mbps 也刚好高于 720p 阈值 3.54，这一场不能单独证明「从 480 陷阱救回」。
  修复的直接证据是：M2 暂存生效、`est=-1→14Mbps`、启动零降档；M1 把第二笔后的 est 从
  旧口径约 7.8Mbps 抬到 14Mbps，爬档明显更快。

**dev.r2310 第二场（2026-11-11 19:00，视频 `SmteHN8UZoY`，Auto，从头播）**

- 19:00:52 `bw startup sample held (M2): 1461711B/2109ms`；该笔整笔口径 1.46MB/2863ms
  =4.09Mbps、bodyRate=5.54Mbps；暂存期间 `est=-1`，启动无降档。
- 19:01:01 升 1080p、19:01:03 升 1440p、19:01:08 升 2160p（**会话开始后约 19.8s**）。
- 19:01:07.450 顶档闸曾以 `sustained=17203K < need=23751K` 拒绝 2160 试探；443ms 后
  样本更新（est=25097K/sus=22676K）经 ladder/reseed 仍升到 2160（sel=0）。
- 2160 期间 `est` 38–53Mbps、`meas` 22–26Mbps、buf 10–36s、pace 1.7–2.4x；到 19:03:05
  用户退出为止无 `downgrade` / `cooldown` / `stall`。
- 同样不是受控对照：该视频 720p 声明 3.38Mbps，首笔整笔口径 4.09Mbps 也高于它。两场共同
  证明的是「M2 暂存生效 + 启动零降档 + 自动爬档未被拖慢」，不是「旧版在这两场必卡 480」。

### 1.7 2026-11-11 21:00 TV alpha.2：bot 风控 + 格式降级 + 手切 1080 重载

`logs_live_20261111_212100.log`（`com.kirin.mt` 3.1.3-alpha.2，Sony BRAVIA 4K AE2 / Android 14）：

- **初始 2160**：20:54:59 首次 launch 走 DASH（`launch bw: sabr=false`），选轨 itag315 →
  `video size: 3840x2160`；但只渲染 21 帧，20:55:20 `player ENDED @pos=0ms duration=Long.MIN`
  （DASH 流异常结束），随后重试。
- **bot 提示**：20:54:40/45、20:55:23/24 `YtResolver … LOGIN_REQUIRED: "Sign in to confirm
  that you're not a bot"`；`BiliMT:Player: playback launch failed: YouTube playback blocked:
  登入帳戶以確認你不是機器人`。20:54:52 / 20:55:26 两次重试后，20:55:31 SABR 建会话成功。
- **格式降级**：20:55:31 harvest `videoFormats=14`，SABR 选 308（1440p）单轨；21:16:20 新会话
  `videoFormats=5`（299/298/134/243/160，最高 1080p）、`qualities=4`。1440/2160 从可用档位
  消失——是 bot 风控/匿名访问降级的结果，不是播放器丢轨。
- **360/144 锁定**：21:16:27 首笔 bodyRate=2005K、21:16:29 第二笔 4974K → est=2686K <
  720p 4163K → 21:16:31 `downgrade 720p→360p`；21:16:33 缓冲 2.4s → `buffer-critical
  downgrade 360p→144p` + 90s 冷却。之后 144p（21:16:35–59）→ 360p（21:16:59）→ 720p
  （21:17:55）；恢复慢的卡点是 `trial refused (sustained low): 1.29Mbps < need 2.975Mbps`
  与 `upshift held (buffer floor 9s < 15s)`，当时 est=3.8Mbps、cap=6.6Mbps——低档小段 +
  满缓冲停闸导致 sustained 虚低，不是链路只有 360p 的吞吐。
- **手切 1080 重载**：21:18:14 手选 1080p（单轨 299）；实测 5–7Mbps body、pace 0.49–1.33
  （多数 <1，低于 1080p 实时码率 6.98Mbps）→ 缓冲耗尽 → 21:18:39 / 21:19:17 / 21:20:48
  三次 `stall detected, auto-retry`；21:20:06 / 21:20:35 两次 `timeout → evict`（含
  silence-hang）。这次确实是带宽不够 1080p，手动锁档让 ABR 无法降档，所以反复重载到退出。
- **M1/M2 表现**：两段 SABR 会话首笔都是 `est=-1`（暂存生效），启动没有误降；本次问题与
  M1/M2 无关，属 bot 风控 + 低档 sustained 低估 + 真实带宽不足三类叠加。

---

## 2. 历史日志验证（只读，脚本在 `tmp/`）

### 2.1 卡顿窗口（`tmp/verify_handoff_stutter.py`）

语料：339 份日志。配对 `BUFFERING → READY` 且 stall 时有媒体请求（`seg > 0`）在途的窗口：

| 指标 | 数量 |
|---|---|
| 可归因窗口（请求正常结束） | **17** |
| 请求段在冻结**前**已落地（早交付可避免） | **9（53%）** |
| 请求段在冻结**期间**落地（可缩短） | **8（47%）**，中位缩短 1.0s |
| 落地在 READY 之后 / 从未落地 | 0 / 0 |
| 响应耗时 > 请求时缓冲余量 | **16（94%）** |
| margin 多等（response_end − 请求段落地） | p50 **3.8s** / p75 5.8s / p90 6.2s / max 7.0s |
| 冻结时长 | p50 0.7s / p90 6.9s / max 7.2s |

另有 11 个窗口的请求没有正常结束（取消/异常），单独计数，不能归因给交接。

### 2.2 stall 侧（`tmp/verify_margin_stalls.py`）

316 次 `stall detected`（去重后约一半来自重叠日志）：

- 有媒体请求在途 135 次，**请求段已落地 12 次（去重 7 个事件）**，提前 ≥2s 的仅 **2 个事件**；
- 4K margin tax：p50 1.8s / p90 4.6s / max 16.4s；
- 单笔 fetch 耗时超过请求时缓冲：全部 2%，4K 3%（27/975）。

**结论**：margin 不是 8s 重载的主因，但它是**短卡顿**的主因——重载很少，卡顿很多，两者的
归因不能混为一谈。

### 2.3 复现命令

```sh
python -I tmp/verify_handoff_stutter.py Y:/download/bilitv/logs
python -I tmp/verify_margin_stalls.py   Y:/download/bilitv/logs
```

---

## 3. 目标架构

### 3.1 组件

```
SabrSessionReader（会话级，一个播放会话一个）
  │   - 持有当前 SabrResponse（一个 POST）
  │   - 按需发下一个 POST（水位/自然结束）
  │   - 处理 control part：backoff / redirect / status=2/3 / RELOAD
  │
  ├── SegmentQueue(itag=315) ── (seq → CompletableDeferred<SabrSegment>)
  ├── SegmentQueue(itag=139)
  │
SabrDataSource.open()  [loader]
  └─ reader.awaitSegment(itag, seq)   // 纯等待，不发网络
```

关键规则：

1. **单写者**：同一时刻只有一条 POST；同一 `(itag, seq)` 只有一个生产者。
2. **按段发布**：`processPart` 在 `MEDIA_END` 立即 `queue.publish(seg)`。
3. **消费即删**：`getSegment` 取走后从队列移除（保持现有 `bufferedSegments` 语义）。
4. **背压**：每队列最多 N 段 / M 字节（4K 建议 2 段），超过不再请求下一笔。
5. **水位驱动**：队列低于低水位或响应自然结束 → 发下一笔 POST；`bufferedRanges` 由队列 + 已消费
   状态生成（单写者，不并发快照）。
6. **取消**：会话级 Job；seek/切档只更新 demand，不拆会话；请求级 Job 只取消尾部。

### 3.2 接口草案

```kotlin
internal class SabrSessionReader(
  private val session: SabrSessionState,
  private val scope: CoroutineScope,
) {
  fun start()
  fun updateDemand(demand: SabrDemand)                 // playerTimeMs / bufferedRanges / selected formats
  suspend fun awaitSegment(itag: Int, seq: Long): SabrSegment   // 等待，不发请求
  fun cancel()
}

internal class SegmentQueue {
  private val pending = ConcurrentHashMap<Long, CompletableDeferred<SabrSegment>>()
  fun publish(seq: Long, seg: SabrSegment)
  suspend fun take(seq: Long): SabrSegment
  fun queuedSegments(): List<Long>
  fun queuedBytes(): Long
}

internal class SabrResponse(
  val request: SabrRequestMeta,
  val parseState: ResponseParseState,   // 已有，目前是 media() 局部对象
) {
  val pacing = PacingCounters()
  var terminal: SabrTerminal? = null
}
```

---

## 4. 分阶段

### Stage A：交接面迁移（行为等价）

- 新增 `SabrSessionReader` / `SegmentQueue`；把 `media()` 的读循环搬进 reader；
- `getNextSegment` 改成 `queue.take`；**请求策略、早停条件、单笔大小全部不变**；
- `SabrDataSource` 只做消费，不再触发网络。

**验收**：日志行为与现状一致（`fetch rn=`/`早停`/ABR 行不变），段交付时间前移到各自 `MEDIA_END`；
`getNextSegment slow` 不再出现（变成队列等待）。真机跑一场 4K + 一场 1440p 无新增 stall。

### Stage B：请求策略水位驱动（去掉 margin 阻塞）

- margin 从“等几段”改成“队列目标深度”；低于低水位就发下一笔；
- 早停条件从“段数”改为“队列够高或服务端预算耗尽”；
- `NEXT_REQUEST_POLICY` backoff、redirect、status 由 reader 统一处理。

**验收**：用 §2.1 的 17 个历史窗口做回归——新的卡顿窗口不应再出现同类归因；1440p 稳定场不回归；
服务端跳段日志（`server skipped seg`）不增加。

### Stage C：可选并发（默认不做）

- 仅在“音频饥饿且视频队列已满”时允许第二条 POST；YouTube 自家基本也是单流，先不做。

---

## 4.1 实施进度

- **2026-11-10，P11-315：Stage A（交接面）已落地**：
  - 读循环从 `getNextSegment` 的调用栈移到会话级 `readerScope`（单通道 `dispatcher`）；
  - `getNextSegment` 只等「请求段 `MEDIA_END`」（`CompletableDeferred`），不再等整笔响应；
  - margin 后继段由后台 drain 继续读进缓存，下一笔请求可在 `hasSegment` 命中后直接返回；
  - 单流语义不变：`fetchMutex` 串行化「等上一笔 drain + 发新请求」；同轨远距离 seek
    直接 `activeCall.cancel() + job.cancel() + join`，避免 seek 被慢响应拖住；
  - `downloadedSegments` / `bufferedSegments` 改 `ConcurrentHashMap`；
  - `SabrMediaSource.releaseSourceInternal` 取消后台读。
  - **2026-11-10 dev.r2300 真机回滚修正**：顺序请求等待上一笔 drain 的 1.5s 超时**不能**
    取消在途响应当作兜底——audio 请求加入 video drain 时超时会 cancel 掉 video 的 call，
    产生 `SocketException: Socket closed` → 整会话 evict 的 1.5s 取消风暴（2160 一直起不来、
    1440 也反复重载）。已移除该超时路径：顺序请求/A-V 另一路一律 `join()` 到底（与改造前
    「串行等整笔响应」行为一致），只有同轨远距离 seek 才 cancel。
  - **早停判据同步修正**:`streamNeedSatisfied` 原来只查 `downloadedSegments`;早交付后 loader
    会把请求段取走（转 `bufferedSegments`），判据会在交付瞬间变 false，后台 drain 永远不早停、
    把整笔响应读完。现在 `downloaded` 与 `buffered` 都算「已到手」。
  - **Stage C 第一步（P11-316）：按需抢占，不做并发 POST**。dev.r2302 真机里视频被音频
    drain 挡住 5s（音频 60KB 要 7.6s，同一响应先推视频字节），导致 50.76s 的 2.25s 卡顿。
    现在：视频缓冲 <10s 且挡路的是音频请求、音频已跑 ≥800ms 时，取消音频 drain 让视频先走
    （视频响应本来就会顺带推音频段）。**不做第二条并发 POST**——同一会话两条流会破坏服务端
    游标（重复推送/跳段），计划里 Stage C 的完整并发仍默认关闭。
  - **Stage B 第一步（P11-317）：runway 驱动 margin**。`streamMargin(itag)` 先取档位期望
    （0/1/2），再用「实测带宽 + 该 itag 已交付段平均字节」估每段墙钟；若水位覆盖不了
    `margin+1` 段的预估耗时（×1.2 + 1s），逐级降到 0（只等请求段）。证据不足时保持原行为。
    目的：弱链路 4K 下不再为了 margin 把整笔响应拖过缓冲跑道。
  - **P11-318：低档多搬 + handoff 感知的 margin 闸（修正 P11-317 过保守）**。同视频对照
    （2QY7Fbw6MBI）：旧版 margin=1（单笔 30~44MB）4K 实测 20~42Mbps、自动档很快到 1440p、
    4K 卡顿频率更低；新版 B1 margin=0（单笔 13~23MB）4K 只有 11~30Mbps、自动档只到 1080p。
    根因：低档段小（720p 0.3~1MB），margin=0/1 时每笔被 RTT 主导（实测 1~9Mbps）；
    且 handoff 后 margin 已不拖住请求段交付，只延长后台预取，P11-317 的 runway 闸过于保守。
    现在：
    - 水位 ≥20s 且档位 720~1440 且非小堆 → margin=3（4 段）；
    - 4K 按 P11-225 的 2 段/水位门；
    - 仅当缓冲 < 3/4 段墙钟时才砍到 0（其余保留档位余量）。
  - **P11-319：补“请求/反馈/解析”三面诊断日志**。`fetch rn=… REAL` 行追加
    `ttfb=…ms body=…ms bodyRate=…K pace=…x media=…ms`；异常行追加 `ttfb/read`；
    `resp summary` 追加 `early/media`。判读：
    - `TTFB` 占比高 + `bodyRate` 明显高于整体 Mbps ⇒ **延迟型**（服务端响应慢，应加大批次摊薄 RTT）；
    - `bodyRate ≈ 整体 Mbps < 码率` ⇒ **带宽型**（应保缓冲/降档，多要无益）；
    - `pace > 1` ⇒ 服务端供得比播放快，容量在；`pace ≤ 1` ⇒ 供给不足。
  - 待验证：云编译；真机 4K 掉速场应看到「段交付时间 = `MEDIA_END`」且卡顿窗口消失；
    Stage B 第二步（reader 水位预取）未动。

## 5. 风险与迁移点

| # | 风险 | 处理 |
|---|---|---|
| 1 | 非线程安全 map | 队列化（`ConcurrentHashMap` + `CompletableDeferred`）；`InitializedFormat` 的 map 只由 reader 线程写 |
| 2 | 响应级字段串场 | 全部收进 `SabrResponse`；fetcher 只留会话级汇总 |
| 3 | 服务端游标/重复请求 | 严格单 POST；下一笔等当前 drain 结束或显式取消；`bufferedRanges` 单写者 |
| 4 | 取消/生命周期 | 会话 Job + 请求 Job；seek 不拆会话；evict 时取消 reader |
| 5 | 晚到终态（RELOAD/status=3/redirect/backoff） | reader 统一判定并上报会话层；已交付段保留策略显式定义 |
| 6 | 带宽/ETA/会话质量记账 | 前台等待时间不计入带宽样本；`SabrResponse` 记录真实读时长与字节 |
| 7 | 内存/GC | 队列背压（每格式 ≤2 段）；小堆设备再降一档 |
| 8 | 诊断可读性 | 新增 `bg rn=` / `queue depth=` 行；保留现有 `fetch rn=` 语义 |

---

## 6. 回滚

- Stage A 是行为等价迁移，出问题直接 `git revert` 单个提交即可回到现状；
- Stage B 的策略切换可按 P11-313 的方式收在一个常量/入口里，必要时临时关闭；
- 不改 `Sabr/DASH` 优先级设置，保留现有逃生通道。

---

## 7. 验收清单

- [ ] Stage A 云编译绿 + 真机 4K/1440p 各一场无回归（`stall detected` 次数不增加）；
- [ ] 段交付时间 = `MEDIA_END`（日志可直接对照）；
- [ ] Stage B 后历史 17 窗口的同类卡顿不复现；
- [ ] `server skipped seg` / `no seg` / `RELOAD` 不增加；
- [ ] 内存峰值不超过现状（4K 单场 `dumpsys meminfo` 对照）。
