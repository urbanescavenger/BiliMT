# 空降助手（B站 SponsorBlock）分段加载（P11-324）

## 1. 现象

用户反馈「跳过广告有些加载不出」。

真机日志（`Y:\download\bilitv\logs`）里可见两类问题：

1. **单节点超时**：`BiliMT:AirJump: fetch error ... java.io.InterruptedIOException: timeout`，
   三次尝试间隔约 16s（共享 OkHttp 客户端 `callTimeout=15s`），三次都打同一个
   `https://bsbsb.top`，全失败后整场无段。历史日志 09-24、09-30、10-31、11-13 均有同签名。
2. **多分P串段**：请求只带 `videoID=<bvid>`，镜像会返回该视频**所有分P**的段。
   2026-11-13 BV1pg4x61Ek4 实际播放 cid=43478355586，拿到两段：
   `970.611–1186.638`（属于 cid=43425531891）与 `1016.331–1186.365`（本分P），
   播放器会从 970s 就开始跳，提前砍掉约 46s 正片。

## 2. 服务端事实（2026-11-14 实测）

- `https://bsbsb.top/api/skipSegments`（官方 primary）、`https://bsbsb.cc`（官方 alias）、
  `http://103.236.70.57:9876`（官方 IP mirror）三者都可用，返回结构一致。
- 支持 `&cid=<cid>` 过滤，只返回该分P的段；响应里每段也自带 `cid` 字段。
- 无段时镜像返回 `200 []`（官方 SponsorBlock 是 404），两者都要按「真无段」处理。

节点列表来源：BilibiliSponsorBlock 扩展 `ota/servers.json`。扩展对分段请求用 6s 超时
（`serverConfig.hashRequestTimeoutMs`）。

## 3. 改动

- `AirJumpRepository`：
  - 按 `bvid+cid` 缓存；请求带 `cid`；响应带 `cid` 时客户端再过滤一次（防镜像忽略参数）。
  - 每次重试轮换节点（primary → alias → IP mirror），单次请求 `callTimeout=6000ms`，
    3 次尝试最坏 ~20s（原 45s+）。
  - 日志带 `cid=` 与 `server=<host>`，便于判读。
- `BiliApiClient.getJson` 增加可选 `callTimeoutMs`（默认 null，行为不变）。
- 播放器 AirJump effect 的键补上 `cid`（TV/Mobile 两端），切分P会重新拉段；
  落状态前校验 `bvid+cid` 一致，避免被取消的旧协程写回空列表。

## 4. 验收

- 日志应出现 `loaded segments: bvid=... cid=... server=bsbsb.top segments=N`。
- 主节点超时时应看到 `retrying next node` 且下一次 `server=bsbsb.cc` 或 IP mirror。
- 多分P视频只应用当前 cid 的段（不再出现别分P的起始时间）。
- 单次请求最长 6s，整轮失败上限约 20s。
