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

## 推荐 Tab

- 新增 `HomeSection.Hongguo`（独立分区，类似 YouTube 热门），TV 与移动端共用。
- 数据：`HongguoAppRepository.feed(page)` → `ContentRepository.rankPage(RankBoard.ALL, page)`
  综合热榜（约 20 条/页、共 5 页，适合网格）；官方 App 推荐流每页仅 2~3 条，降为兜底；
  再失败回退 `ContentRepository.home(page)` 网页首页推荐。
- 卡片只带剧集元数据；TV `AppShell` / 移动 `MobileApp` 点击时走 `hongguoDetail` 补全
  `vid_list` 再进播放器。
- `AppSettingsStore` 增加 `HomeSectionsHongguoMigrationV1`：老安装升级后自动启用一次，
  之后用户可在设置里关闭；新装用户由 `DefaultOrder` 默认包含。

## 弹幕与评论

- **弹幕**：`TimedComments.read(series, episode, start, duration, cursor)` 按播放时间窗返回；播放器
  预取前 5 分钟（最多 20 个窗口），映射为 `DanmakuEntry`，复用现有 DanmakuRenderEngine。
- **评论**：`AppCommentPages.read(series, episode, cursor)` 游标分页；首屏失败回退
  `PublicComments`（公开网页评论/剧评）。复用 TV `CommentScreen` / 移动 `MobileCommentList`
  的评论行。
- **入口**：TV 红果控制行新增「弹幕开关」「评论」；移动播放器评论区自动切红果列表。
- **边界**：只读，不发弹幕/评论；弹幕只预取前 5 分钟，长剧后续时间窗未加载。

## 已知边界

- VIP/付费分集仍然锁定；匿名身份只覆盖免费分集。
- 流式播放首帧只等 moov/样本表 + 首段 Range，通常远小于整集；seek 会发新的 Range。
- 旧的整集下载缓存（`cacheDir/hongguo`）已不再使用，可手动清理。
- 搜索合并 App + 网页 `searchAll` 前两页；App 搜索分页游标仍未接入，网页分页按 `hasMore` 取第 2 页。
- 播放/弹幕共用 `hongguo_playback_identity` 里的匿名设备身份（device_id/install_id/device_token/cookies），
  冷启动优先复用；注册失败 1s 后重试一次，仍失败需等上游频控窗口。
- 旧 v3 兼容链路的设备槽仍是 `hongguo_app_device`，与 v5 播放身份分开。
- 上游接口/签名版本变化时可能整体失效；`HongguoRepository` 会回退网页 3 集。
- TV 端已加入搜索源与「红果推荐」Tab，播放器共用 `HongguoDataSource`；电视实机尚未复测。
- 许可：引用 `:hongguo-core` 的发行物整体按 GPL-3.0-only 分发；原 MIT 文本见 `LICENSE-MIT`。
