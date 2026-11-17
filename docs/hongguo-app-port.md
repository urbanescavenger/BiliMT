# 红果短剧 App 端移植（移动端优先）

## 目标

解决 P11-86 遗留的「网页匿名端只放开前 3 集」：移动端搜索红果短剧，点开后可播放超过第 3 集
的免费分集。不改动 B 站/YouTube 数据链，不新增红果专属页面。

## 模块边界

- `hongguo-core/`：GPL-3.0-only Android library，移植自
  [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) `kotlin-tv/core`。
  - 匿名设备注册、v3/v5 签名、搜索/详情、`player/v1` 取流、CENC 解密。
  - 保留上游 SPDX 头与 `NOTICE.md`；Gradle 依赖 okhttp / BouncyCastle / jsoup。
- `app/.../HongguoAppRepository.kt`：应用侧适配，只暴露三个动作：
  1. `search(keyword)` → 剧集卡（不带分集）；
  2. `detail(seriesId)` → 完整 `vid_list`，映射成 `TvboxLine` 分集表；
  3. `resolveEpisode(vid)` → v5 取流 + 解密 + 缓存，返回 `file://` 本地 MP4。
- `HongguoRepository`：优先 App 端，失败/空结果回退网页 3 集链路。
- `PlaybackRepository`：识别 `hongguo-app:<vid>` 分集 URL，走 `resolveEpisode`；
  其余 TVBox/网页 URL 仍走 `TvboxRepository.resolveLineUrl`。

## 数据流

```
移动端搜索
  → HongguoRepository.search
  → HongguoAppRepository.search（App 搜索 + 网页 searchAll 前两页合并，不按 genre 过滤）
  → 卡片（bvid=hongguo:<series_id>，无分集）

点开卡片
  → MobileApp 检测 SourceHongguo && tvboxLines 为空
  → HongguoAppRepository.detail（网页 SSR detail 的 vid_list = 全量分集）
  → VideoSummary + TvboxLine("红果短剧", [第1集 … 第N集])
  → 现有播放器

播放某集
  → PlaybackRepository.getTvboxPlaybackInfo
  → hongguo-app:<vid> → HongguoAppRepository.resolveStream
  → ContentRepository.streamLadderWithCompatibility（v5 签名 + 匿名设备）
  → HongguoStream(url, key) → PlaybackInfo.hongguoStream
  → HongguoDataSource（Media3 BaseDataSource）按 Range 读取
  → RemoteVideo 解析 moov/样本表 + MediaCrypto 边读边解
  → ProgressiveMediaSource 直接播放
```

## 已知边界

- VIP/付费分集仍然锁定；匿名身份只覆盖免费分集。
- 流式播放首帧只等 moov/样本表 + 首段 Range，通常远小于整集；seek 会发新的 Range。
- 旧的整集下载缓存（`cacheDir/hongguo`）已不再使用，可手动清理。
- 搜索合并 App + 网页 `searchAll` 前两页；App 搜索分页游标仍未接入，网页分页按 `hasMore` 取第 2 页。
- 设备身份存 `SharedPreferences`（`hongguo_app_device`），换进程复用，减少注册频控。
- 上游接口/签名版本变化时可能整体失效；`HongguoRepository` 会回退网页 3 集。
- TV 端暂未把红果加入搜索源轮换；本阶段只做移动端。
- 许可：引用 `:hongguo-core` 的发行物整体按 GPL-3.0-only 分发；原 MIT 文本见 `LICENSE-MIT`。
