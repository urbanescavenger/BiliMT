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
