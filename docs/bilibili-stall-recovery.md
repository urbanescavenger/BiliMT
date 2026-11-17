# B站 直连 DASH 卡顿恢复：换 CDN + 逐档降画质（P11-323）

## 1. 现象与证据

2026-11-13 真机日志 `logs_live_20261113_211841.log`（3.1.3-alpha.2，Sony BRAVIA 4K AE2 / mt5897）：

- 视频 BV1pg4x61Ek4（cid=43478355586），B站 playurl 返回 `quality=120`，选中 `3840x2160 avc1.640034`（4K H.264，playurl 请求参数 `bw=19025266`，约 19Mbps）。
- 同一时段带宽估计在 13–35Mbps 之间抖动。21:06–21:18 的 124 个稳态探针里：9 次 `state=BUFFERING`，`fwdBuf` 中位 6.5s、最低 0ms，`isLoading=true` 占多数。
- 20:55:19、20:57:27 两次 `stall detected, auto-retry #1` 触发整轮重载（每次约 1.5–2s 黑屏重建），重载后仍回到 2160p（`stall-reached cooldown: 2160p excluded 180s` 在 B站 DASH 路径上没有生效——轨集里只有当前 quality 的轨，排除后没有降档目标）。

结论：B站 直连 DASH 的 stall 重载既**不换 CDN**（`playurl` 缓存命中 + `CdnSelector` 5 分钟选择缓存都把节点钉死），也**不降画质**（请求 qn 不变，服务端仍返回同一 quality），在码率贴边的链路上必然反复补缓冲。

## 2. 判据链（改前）

1. `PlaybackStallWatchdog` 在 `BUFFERING` 超过阈值时给出 `RELOAD` / `VIDEO_FREEZE`。
2. 处理分支只做三件事：`autoResumePositionMs = 当前播放位置`、`noteStartupStallMemory()`（写 `SabrAbrMemory`）、`retryKey += 1`。
3. `retryKey` 重跑 launch effect → `getPlaybackInfo()` 命中 `PlaybackCacheKey`（bvid/cid/codec/qn）→ 同一个 playurl 响应 → 同一个 `CdnSelector.select()`（5 分钟缓存命中）→ 同一个 CDN host、同一个 quality。
4. `SabrAbrMemory` 的 `stall-reached cooldown` 只会从**当前轨集**里排除该 height；B站 `parsePlaybackInfo` 的 `videoTracks` 只保留 `data.quality` 那一档的轨，排除后没有更低的轨可选，等于空操作。

## 3. 改后判据链

只在 `PlaybackRequest.source == SourceBili`（UGC + PGC，不含 YouTube / TVBox / IPTV）时生效：

1. **换 CDN**：stall 重载前把本次 launch 实际下发的主用视频 URL host 交给 `CdnSelector.avoidHosts()`，拉黑 3 分钟。
   - `CdnSelector.select()`（Auto 模式）读取缓存时若主用 host 已被拉黑，按 miss 处理并重新测量候选；候选列表也过滤被拉黑 host。
   - 所有候选都被拉黑时退回完整候选（宁可重试坏节点，也不能无 URL 可播）。
   - 手动指定 CDN（非 Auto）不受影响——用户的显式选择优先。
2. **逐档降画质**：仅在已起播（`playbackStarted`）的 stall 上执行。
   - 从 `PlaybackInfo.qualities`（服务端 `accept_quality`）里取「小于当前 `selectedQuality.id` 的最大一档」，写成 `activeRequest.preferredQualityId`，下一次 launch 的 playurl 请求 qn 即为该档。
   - 每次 stall 只降一档：2160 → 1080P60 → 1080P+ → 1080P → 720P → …；用户手动在画质面板选档会覆盖这一状态。
   - 起播前（未出首帧）的 stall 只换 CDN，不降档，避免冷启动抖动就砍画质。
3. **续播位置**：`autoResumePositionMs` 在降档前已写入，launch 的 `when` 分支优先取它，画质变化不会把播放位置打回 `request.startPositionMs`。

## 4. 验收判据

- stall 重载后日志出现 `CdnSelector: avoid CDN host <host> for 180s (stall)`，随后 `Measuring ... CDN candidates` 不再选中同一 host。
- 同一次 stall 的日志出现 `stall quality step-down (stall): <oldId>(<desc>) → <newId>`；下一次 `playurl qn requested=<newId>`、`returned`/`selected` 为更低档。
- 播放位置从 stall 点续播，不回到 0 或旧的 saved progress。
- 用户手动选画质后，后续不再自动降档（`preferredQualityId` 被手动值覆盖）。
- YouTube / TVBox / IPTV 不应出现上述两条日志。

## 5. 未做

- 未改默认画质（仍是 Highest）；本机制只在真实 stall 后按需降档。
- 未改 `PlaybackQualityPreference` 的 qn 映射；降档走的是现有 `preferredQualityId` 通道，与画质面板手动选档同一条路径。
