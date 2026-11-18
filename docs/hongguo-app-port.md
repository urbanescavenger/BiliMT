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

## 画质与相关（P11-340，对齐 B站 播放器）

- **画质切换**：v5 取流 ladder（`need_all_video_definition=true` 全档返回）的档位填
  `PlaybackInfo.qualities`（`id=档位数值`，description 如 `720P`），HD 按钮/画质面板复用 B站
  同一套 UI 与重解析机制（选档 → `preferredQualityId=档位数值` → 重跑 resolve 保持进度）。
  - 取流上限：手动选过档用 `preferredQualityId`；首播回退设置页全局默认画质
    （`PlaybackQualityPreference.hongguoMaxQuality()`：Highest/Q1080→1080、Q720→720、Q480→480）。
  - 红果恒单线路：线路索引侧 `coerceIn(0, lastIndex)` 把档位数值钳回 0，两种语义不冲突；
    `qualities` 仅在 ladder 无 `>0` 档时才退回线路列表，保证面板不空。
  - 红果是单档固定流（无变体轨/无 ABR）：TV/移动 `onVideoSizeChanged` 对 `hongguoStream != null`
    跳过按高度重映射（dummy 轨高度不代表实际档），HD 回显=请求档。
- **相关视频**：红果 request 没有 bvid（`toPlaybackRequest` 置空），在线相关键改用 `hongguo:<series>`
  （TV 控制行 loader、移动相关 effect 同源）；`VideoRepository.getRelatedVideos` 红果分支喂
  「红果推荐」热榜首页并排除当前剧；点行补 `hongguoDetail` 拿全量分集（TV `RelatedVideos` 激活分支、
  移动 `onPlayVideo` 壳层，镜像卡片点击）。
- **移除**：影视库 TVBox/IPTV 控制行的「相关视频」——request 无 bvid 在线查询恒空、队列 bvid
  匹配恒 miss，面板永远「暂无数据」，按钮无实义。
- 边界：播完自动相关仍只对 B站/YouTube 生效（红果 request 无 bvid，completion 分支未改）；
  弹幕时间窗跟随（播放超过预取窗口后的后续窗口）仍是遗留项。

## 本地播放历史（P11-341）

红果是匿名只读端、没有服务端历史；新增本地存储把红果播放记录融入 TV/移动历史列表并续播。

- **存储**：`core/storage/HongguoHistoryStore`，照抄 `YoutubeHistoryStore` 的 DataStore 列表范式。
  条目按 `seriesId` 前置去重（**一部剧一张卡**，cap 100），记录最近分集 `episodeVid`/`episodeIndex`、
  进度 `positionMs`、时长与 `lastPlayedAtMs`。`toHongguoSummary()` 映射成卡片：bvid=`hongguo:<seriesId>`、
  角标「第N集」、progress/viewAt 秒。
- **写入**：播放器 Ready 起播时 `recordPlay`（每次解析都写 → 切集/切档自动更新最近分集；
  网页回退分集无 vid 不记录）；`saveProgressNow` 周期 `updatePosition`（回填起播时未知的
  duration，播完写满进度 → 卡片判已看完从头播）。TV `PlayerScreen` 与移动 `MobilePlayerScreen` 在线路径同源。
- **列表**：TV `UserFeedScreen` History tab 与移动 `MobileHistoryPage` 把红果 + 本地 YouTube +
  B站历史按 viewAt（秒）倒序混合；未登录也显示本地两条。
- **续播**：`VideoSummary` 新增 `historyEpisodeVid`/`historyEpisodeIndex`；点击经详情补拉后
  `toHongguoPlaybackRequest(resolved)` 按 `hongguo-app:<vid>` 定位续播集（vid 失配回退序号再落首集），
  起点=卡片 progress（接近播完从头）。TV 动态/历史 tab 与移动动态/历史页的 `onVideoSelected`
  补上此前缺失的 `hongguoDetail` 补拉分支；全部红果详情补拉点统一改走该 helper（无历史字段的卡退化为原行为）。
- 边界：不提供历史清空/单条删除的 UI 入口（store 已备 `remove`/`clear`）；B站历史的清空不影响本地两条。

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
