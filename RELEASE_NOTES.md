# BiliMT 版本发布说明

## v2.0.2

v2.0.1 后移动端播放器大改版:简介/评论双 Tab、点赞投币收藏分享、播放器顶/底栏重构不遮视频、全屏自适应横竖屏、检查更新内联;TV 端补点赞投币收藏与检查更新镜像移动端。合并 mort_debug → main 打稳定 tag。

### 移动端播放器重构
- 播放器下方改**简介/评论双 Tab**:简介 Tab 含视频详情 + 点赞/投币/收藏/分享互动行(简介 desc);评论 Tab 复用评论列表,标题显示评论总数。
- 顶/底栏**不再遮视频**:视频区固定 16:9,两栏作为额外高度叠在上下;进度条换自绘瘦身滑块;顶/底栏内缩统一变薄纯黑底,顶留状态栏。
- **底栏瘦身 + 设置聚合**:底栏只留一行进度条 + 全屏(全屏并到进度行最右);设置入口移到顶栏右端;弹幕与分享并入设置 sheet(画质/倍速/弹幕/分享四合一);选集并入简介 Tab(多分P 在相关视频位置列分P,单P 仍显示相关视频)。
- **全屏按视频比例自适应**:竖屏视频全屏切竖屏,横屏仍横屏;非全屏保持 16:9。
- 暂停:去底栏播放按钮,改点画面暂停 + 中央常驻暂停图标(点击闪现黄色图标 + 圆角);暂停点击区中间 2/3;弹幕开启时点视频不暂停(手势层提至 z 序最顶)。

### 点赞/投币/收藏/分享
- 简介页视频详情下增点赞/投币/收藏/分享互动行,显示真实计数与已操作状态。
- 修各端点:点赞端点修正、投币带 buvid3、view 接口带 sessData 取 req_user 状态(不可靠改用专门 check 端点取已赞/已投/已藏)、投币/收藏失败透出 B站真实错误(如硬币不足/已投币)。
- TV 端控制栏加点赞/投币/收藏(移植移动端,投币居中弹窗,收藏右侧面板多选收藏夹;PGC 隐藏);主播放路径拿到新 metadata 时同步真实计数,同 bvid 重试不同步避免回退用户互动。

### 检查更新 + 性能
- 移动端检查更新:下载/进度/安装内联进最新版本行,去单独下载栏。
- TV 端检查更新镜像移动端:最新版本合并行内联下载/进度/安装 + 进度条,复用 UpdateManager,无新增下载逻辑。
- 预热 api.bilibili.com 连接池:启动后台拉 BuvidSpi 暖 DNS+TCP+TLS,首开 UP 主主页等接口省冷建连(fire-and-forget 失败静默)。

### versionCode 说明
本版 vc=2,002,000。`computeVersionCode` 对 prerelease 加 `labelOrder*100+pre`,故 `v2.0.2-alpha.*`(2,002,101~2,002,117)高于本稳定版——已装 alpha 的用户需**手动安装 v2.0.2** 升级(与 v2.0.0/v2.0.1 同策略,不改 `labelOrder`)。

### 安装包
- `BiliMT-v2.0.2-arm64-v8a.apk`
- `BiliMT-v2.0.2-armeabi-v7a.apk`

## v2.0.1

v2.0.0 后移动端 UI 继续打磨：视频详情改上下分栏 + 评论列表、动态 tab 四子 tab、番剧季上次看到位置、首页/播放器多处体验修复。合并 mort_debug → main 打稳定 tag。

### 视频详情上下分栏 UI + 评论列表
- 点入视频由整屏播放器覆盖改为**竖屏上下分栏**：上半 16:9 播放器 + 下半评论列表；点底栏全屏按钮切横屏全屏沉浸（评论隐藏），退出回竖屏分栏。
- 评论列表 `MobileCommentList` 复用 `VideoRepository.getComments`（`/x/v2/reply`），滚动到底自动翻页、热门/最新排序切换；aid 取自 `PlaybackVideoMetadata`（卡片不带 aid），白底深字。PGC 暂占位"暂无评论"。

### 动态 tab 四子 tab + 季详情选集
- "动态"底栏 tab 改为动态/历史/收藏/追番四子 tab（`PrimaryScrollableTabRow` + `HorizontalPager` 左右滑动）。历史双游标分页续播、收藏夹 chip 切换、追番两组筛选。
- 触屏季详情外壳 `MobilePgcSeasonScreen`：封面/简介 + 同系列季切换 + 正片/花絮分集列表，续播高亮上次集，进/切季自动滚到上次看到的位置并叠主色进度条。

### 播放器与卡片体验
- 播放底栏加 UP 主页图标、视频分享；视频卡片 UP 名旁加头像可点进 UP 主页；UP 主页进视频返回回 UP 主页而非首页。
- 首页内容区左右滑动切顶部 tab；首页下滑到底不自动加载（PageSize 30→20）；加载列表两圈合并；全屏点击视频不暂停修复（width 缓存失真）。

### versionCode 说明
本版 vc=2,001,000。`computeVersionCode` 对 prerelease 加 `labelOrder*100+pre`，故 `v2.0.1-alpha.*`（2,001,101~2,001,106）高于本稳定版——已装 alpha 的用户需**手动安装 v2.0.1** 升级（与 v2.0.0 同策略，不改 `labelOrder`）。

### 安装包
- `BiliMT-v2.0.1-arm64-v8a.apk`
- `BiliMT-v2.0.1-armeabi-v7a.apk`

## v2.0.1-alpha.6

v2.0.1-alpha.5 后:修评论不显示 + 评论背景改白底。

### 修评论不显示
- `toPlaybackRequest` 未带 aid、卡片除动态外也不带 aid,导致 `activeRequest.aid` 恒为 0,`MobileCommentList` 永远走"暂无评论"占位。
- 改 aid 取自 `PlaybackVideoMetadata.aid`(播放器加载 `/x/web-interface/view` 后就绪):metadata 加载前显示加载圈(不再误判"暂无评论"),加载后自动拉评论;PGC 用 `isPgc` 占位(本期不接 PGC 评论)。

### 评论背景改白底
- 评论区背景由深色改为白色,配深色文字;不再套局部深色 `MaterialTheme`,自带浅色 `CommentColor` token。

### 安装包
- `BiliMT-v2.0.1-alpha.6-arm64-v8a.apk`
- `BiliMT-v2.0.1-alpha.6-armeabi-v7a.apk`

## v2.0.1-alpha.5

v2.0.1-alpha.4 后:移动端视频详情改上下分栏 UI + 补评论列表;番剧季详情补上次看到的位置。

### 视频详情上下分栏 UI + 评论列表
- 点入视频后由"整屏播放器覆盖"改为**竖屏上下分栏**:上半 16:9 播放器 + 下半评论列表;点底栏全屏按钮切横屏全屏沉浸播放(评论区隐藏),退出全屏回竖屏分栏。
- 新增 `MobileCommentList`(嵌入播放器下半区):复用 `VideoRepository.getComments`(`/x/v2/reply`, oid=aid、type=1),滚动到末尾自动翻页,顶部热门/最新排序切换重载;楼中楼本期仅显示回复计数(对齐 TV `CommentScreen`)。
- 评论列表局部套深色 `MaterialTheme`,深色背景下文字可读(全局内容页主题统一属 P3,不在本期)。
- `MobilePlayerScreen` 根布局改为 `Column`,player Box 在非全屏时 `aspectRatio(16/9).fillMaxWidth()`;PGC 等无 aid 内容评论占位"暂无评论"。外层 `Column` 引入 `ColumnScope` 致内层 `Box` 里的 `AnimatedVisibility` 三义,改全限定顶层调用修复。
- aid 随换 P / 切相关视频变化,`LaunchedEffect(aid, sort)` 自动重载评论。

### 番剧季详情补上次看到的位置
- 进季详情/切季后 `LazyColumn` 自动滚到 `progress.lastEpId` 那一集(对齐 TV 端初始焦点);该集封面底部叠主色进度条(`lastTime/duration`);badge 文案由"上次观看"改为"上次看到 mm:ss",`lastTime=0` 回退原文案。复用 `Int.formatDurationSeconds` 与 TV `VideoCard` 进度条样式,不动模型/网络/播放器。

### 安装包
- `BiliMT-v2.0.1-alpha.5-arm64-v8a.apk`
- `BiliMT-v2.0.1-alpha.5-armeabi-v7a.apk`

## v2.0.1-alpha.4

v2.0.1-alpha.3 后:移动端"动态"tab 补 历史/收藏/追番 三个子 tab + 追番进季详情选集。

### 动态 tab 四子 tab(动态/历史/收藏/追番)
- "动态"底栏 tab 改为 4 个子 tab(动态/历史/收藏/追番),`PrimaryScrollableTabRow` + `HorizontalPager` 左右滑动切换,镜像首页分区范式。未登录整体显示登录入口。
- **历史**:`getHistoryPage` 双游标(viewAt/max)分页,历史项 `VideoSummary` 带 cid/progress/historyPage,`toPlaybackRequest` 自动用 progress 作 startPositionMs 续播。
- **收藏**:顶部收藏夹 chip 切换(`getFavoriteFolders` + `getFavoriteFolderVideos`),page 分页,默认全部收藏夹。
- **追番**:番剧/影视 + 全部/想看/在看/看过 两组筛选(`getFollowingSeasons`),季映射 `VideoSummary` 渲染,page 分页;季非 UGC,点击走 `onSeasonSelected`。

### 追番进季详情选集
- 新增触屏季详情外壳 `MobilePgcSeasonScreen`:封面/简介 + 同系列其它季切换 + 正片与花絮分集列表,续播高亮上次集,选集回调 `onPlayEpisode`。
- `MobileApp` 加 `pgcSeasonRequest` 状态 + z 序(镜像 space),`onPlayEpisode` 照 TV `AppShell` 范式构造 PGC `PlaybackRequest`(epId/seasonId/subType/续播 startMs)。移动端播放器已支持 PGC,无需改播放器。

### 安装包
- `BiliMT-v2.0.1-alpha.4-arm64-v8a.apk`
- `BiliMT-v2.0.1-alpha.4-armeabi-v7a.apk`

## v2.0.0

2.0.0 稳定版:移动端 UI 移植完成。在 `mobile`/`mort_debug` 分支参照 [BV](https://github.com/aaa1115910/bv) `feature/mobile` 设计,把触屏移动端 UI 移植进**单 APK**(运行时 `isTvUi()` 选 TV `BiliTvApp` 或手机 `BiliMobileApp`,Manifest 双桌面入口),TV 端零改动,复用全部 `core/*` 引擎。本版合并 mobile → mort_debug → main,打稳定 tag。

### 移动端能力(alpha.1 ~ alpha.23 汇总)
- **外壳**:`NavigationSuiteScaffold` 自适应 + 状态栏透明浅色图标 + 内容页下滑刷新(下拉触发)。
- **内容页**:首页分区网格、动态关注 feed 网格、搜索(历史/联想/排序/结果网格)、卡片式设置、短信登录。
- **触屏播放器**:Media3/ExoPlayer + 弹幕层 + 进度/心跳/完成上报;**手势**(点中央播放/暂停、长按 2x、横拖 seek 松手自动恢复、控件 4s 自动隐藏);**画质/倍速/弹幕设置弹窗**;**分P选集**;**自动连播下一集**;**UP 空间入口**;**空降助手**(SponsorBlock 自动跳过广告/片头/片尾);**后台播放**(主流 MediaStyle 通知:封面+播放/暂停+锁屏控件);**全屏横屏沉浸**;**底栏图标按钮**(播放/弹幕/选集/推荐视频/设置/全屏);**推荐视频**(相关视频)切播。
- PGC 分支 `MergingMediaSource` 已支持。

### versionCode 说明
本版 vc=2000000。`computeVersionCode` 对 prerelease 加 `labelOrder*100+pre`,故 `2.0.0-alpha.*` 的 vc(2000101~2000123)高于本稳定版——已装 alpha 的用户需**手动安装 2.0.0** 升级(alpha 不自动提示)。后续如需让稳定版 vc 高于其 alpha,再调 `computeVersionCode` 的 stable labelOrder。

### 安装包
- `BiliMT-v2.0.0-arm64-v8a.apk`
- `BiliMT-v2.0.0-armeabi-v7a.apk`

## v2.0.0-alpha.23

v2.0.0-alpha.22 后:修下拉刷新方向反了(上滑触发→应为下拉触发)。

### 修下拉刷新方向
- 根因:`PullToRefreshLayout.onPostScroll` 原判 `available.y < 0` 触发,但 Compose 约定 `available.y > 0` = 手指下拖/下拉(见官方 PullToRefresh 样板 onPostScroll),`< 0` 是上滑 → 刷新错触在上滑方向。
- 修法:改成 `available.y > 0` 触发,累加 `available.y`(正值)。下拉时顶部指示器向下展开、达阈值松手刷新。`onPostFling` 阈值判定与指示器偏移不变。

### 安装包
- `BiliMT-v2.0.0-alpha.23-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.23-armeabi-v7a.apk`

## v2.0.0-alpha.22

v2.0.0-alpha.21 后:播放器加推荐视频按钮 + 修内容页下拉刷新无效。

### 播放器推荐视频(相关视频)按钮
- 底栏加"推荐视频"图标按钮(常驻,复用 `ic_player_related`),点击弹深色 `ModalBottomSheet`,2 列列出当前视频的相关视频(`VideoRepository.getRelatedVideos(bvid)`),点击切播(不关播放器,`playbackRequest = video.toPlaybackRequest()` 重载)。
- sheet 内容用 `MaterialTheme(darkColorScheme())` 包一层让 `MobileVideoCard` 文字在深色底可读。`MobilePlayerScreen` 加 `videoRepository`/`onPlayVideo` 参数,`MobileApp` 传 `toPlaybackRequest` 切播。选集守卫保持(单 P 不显示)。

### 修下拉刷新无效
- 根因:首页/动态/搜索三页把 `PullToRefreshLayout` 放在 `when(state)` 的 Success 分支内,`isRefreshing = state is Loading` 在 Success 分支求值永远 false;onRefresh 触发后 state→Loading,上层 `when` 切 Loading 分支用全屏转圈**替换掉整个 PullToRefreshLayout**(列表滚动位置、指示器一起销毁)→ 体感"下滑无效"。
- 修法:三页照 `MobileUserSpaceScreen` 范式重构——`PullToRefreshLayout` 提到 `when` 外、`isRefreshing` 顶层求值真值、Loading/Empty/Failed 内联为 grid `item(span=GridItemSpan(maxLineSpan))`,刷新时容器不卸载、滚动位置与顶部指示器保留。
- 次修:`PullToRefreshLayout` 的 `remember{object:NestedScrollConnection}` 捕获陈旧 isRefreshing/onRefresh,改用 `rememberUpdatedState` 持最新值。

### 安装包
- `BiliMT-v2.0.0-alpha.22-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.22-armeabi-v7a.apk`

## v2.0.0-alpha.21

v2.0.0-alpha.20 后:修播放中拖拽 seek 松手后意外暂停。

### 拖拽松手自动恢复播放
- 之前:播放中横拖 / 拖进度条 seek,松手后 `player.seekTo` 不保证恢复播放,会出现停在 seek 点不继续放。
- 修法:拖拽起点记录 `player.playWhenReady`(播放意图),松手 seek 后若之前在播放就 `player.play()` 恢复;暂停态下拖拽仍保持暂停。手势横拖与 Slider 两条路径都修。

### 安装包
- `BiliMT-v2.0.0-alpha.21-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.21-armeabi-v7a.apk`

## v2.0.0-alpha.20

v2.0.0-alpha.19 后:移动端播放器底栏按钮图标化(参考 TV 版)。

### 底栏按钮重设计
- 5 个文字按钮(⏸/▶、弹 开/弹 关、全屏、选集、设置)改为**图标按钮**,移动端扁平半透明风格(40dp 圆角半透明底 + 22dp 图标),参考 TV 版 `PlayerIconButton` 图标化思路但不搬液态玻璃/焦点(触屏不需要)。
- 图标:复用现有 `ic_player_subtitles`(弹幕)、`ic_player_playlist`(选集)、`ic_nav_settings`(设置);新增 `ic_player_play`/`ic_player_pause`/`ic_player_fullscreen`/`ic_player_fullscreen_exit` 四枚矢量图(沿用 32dp/viewport32/白色风格)。
- 状态高亮对齐 `BiliColors`:弹幕开=BiliPink、关=TextSecondary;播放/暂停、全屏按状态切图标。新增 `MobilePlayerIconButton` Composable。
- 功能集不变(倍速/画质仍走设置 sheet,空降/UP/相关本次不做)。

### 安装包
- `BiliMT-v2.0.0-alpha.20-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.20-armeabi-v7a.apk`

## v2.0.0-alpha.19

v2.0.0-alpha.18 后:移动端播放器接入空降助手(自动跳过广告/片头/片尾段)。

### 空降助手(AirJump)
- **自动跳过**:播放进度轮询每 tick 检查当前位置是否落在 SponsorBlock 段内(`sponsor`/`intro`/`outro`/`interaction`/`selfpromo`),命中则 seek 到段末。入段前 3.5s 弹"即将跳过广告"Toast,跳过后弹"已跳过广告"(距末尾 ≤1.5s 抑制)。回退超过 2s 重置段去重状态,允许再次触发。
- **数据来源**:第三方 SponsorBlock 镜像 `https://bsbsb.top/api/skipSegments?videoID=<bvid>`,按 bvid 拉取,独立于播放元数据。
- **设置开关**:复用移动端已有的"空降助手"设置项(`MobileSettingsScreen`),`MobileApp` 接通 `settings.airJumpAssistantEnabled` 传入播放器;关闭时不拉取、不跳过。
- 镜像 TV `PlayerScreen` 的 `handleAirJumpPosition` 与加载/重置 effect,复用共享层 `PlaybackRepository.getAirJumpSegments` / `AirJumpSegment`。不做进度条段标注(移动端 Material3 Slider,后续可加)。

### 安装包
- `BiliMT-v2.0.0-alpha.19-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.19-armeabi-v7a.apk`

## v2.0.0-alpha.18

v2.0.0-alpha.16 后:移动端播放器手势优化,符合手机使用习惯。

### 播放器手势
- **点中央播放/暂停**:点击画面中央三分之一区域切换播放/暂停(对齐 TV `togglePlayback` 语义——暂停显控件、播放隐控件),并闪现居中 ▶/⏸ 反馈图标(800ms)。
- **点边缘切控件**:点击左右三分之一区域切换顶/底栏控件显隐。
- **长按 2 倍速**:长按屏幕临时 2.0x,松手恢复原倍速,顶部显示"2.0x"提示。
- **横向拖动 seek**:左右拖动调整进度,画面中央显示目标时间气泡,松手 seek。
- **控件自动隐藏**:播放中控件可见时 4s 后自动隐(对齐 TV `PlayerControlsAutoHideMs`),暂停时保持可见。

### 实现备注
- 新增 `MobilePlayerGestures.kt`:单个 `pointerInput` 内手写 `awaitPointerEventScope` 循环,按「超时→长按、越过 touchSlop→拖拽、抬起→单击」分支互斥判定,避免 `detectTapGestures` + `detectHorizontalDragGestures` 双 detector 抢 down 事件。长按阈值(500ms)与 touchSlop(8dp.toPx())用 Compose 默认值硬编码,避开不同 Compose 版本 `ViewConfiguration` 成员名差异(`pointerSlop`/`touchSlop`、`longPressTimeout`/`*Millis`)。
- `MobilePlayerScreen` 抽出 `togglePlayback()`(底栏按钮与中央点击共用),复用现有 `isPlaying`/`seekPreviewMs`/`playbackSpeed`/`player.seekTo` 状态。TV 端、后台播放通知不动。

### 安装包
- `BiliMT-v2.0.0-alpha.18-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.18-armeabi-v7a.apk`

## v2.0.0-alpha.16

v2.0.0-alpha.15 后:修复后台播放通知不显示。

### 通知不显示修复
- 根因:`alpha.15` 用 `MediaSessionService`,但本应用从不连 `MediaController`(PGC `MergingMediaSource` 不能走 MediaController),`MediaSessionService` 无 controller 时不保证及时 `startForeground` → 通知不显示。
- 修法:`PlaybackService` 改回**普通 `Service`** + 手动 `MediaSession` + `MediaStyleNotificationHelper.MediaStyle(session)`(主流样式:封面 + 播放/暂停 + 锁屏控件)+ **显式 `startForeground`**(保证 5s 内显示)+ "停止"按钮;Manifest 去掉 `MediaSessionService` intent-filter。
- 保留:封面下载(`MediaItem.mediaMetadata.artworkData`)、`POST_NOTIFICATIONS` 运行时权限请求、下滑刷新。真机需确认已授予通知权限。

### 安装包
- `BiliMT-v2.0.0-alpha.16-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.16-armeabi-v7a.apk`

## v2.0.0-alpha.15

v2.0.0-alpha.14 后:后台播放通知改主流 MediaStyle + 内容页下滑刷新。

### 后台播放通知(MediaStyle 主流样式)
- `PlaybackService` 改 `MediaSessionService`,`MediaSession` 绑 `PlayerHolder.player`(composable 的 ExoPlayer),自动前台化 + 默认 MediaStyle 通知(**视频封面 + 标题/UP + 播放/暂停 + 锁屏媒体控件**),随播放状态自动更新。
- 封面:`PlaybackRequest` 加 `coverUrl`(`toPlaybackRequest` 带 `VideoSummary.pic`),加载时用 `playbackHttpClient` 下载 bytes 设 `MediaItem.mediaMetadata.artworkData`(UGC `buildDashMediaItem.buildUpon` + PGC `MediaItem` 带 metadata)。
- Manifest 加 `MediaSessionService` intent-filter;`MobileApp` 请求 `POST_NOTIFICATIONS` 运行时权限(Android 13+,此前未请求导致通知不显示)。加 `media3-session` 依赖。
- 上一版通知不显示根因:POST_NOTIFICATIONS 未运行时请求。

### 内容页下滑刷新
- 新增自定义 `PullToRefreshLayout`(foundation `nestedScroll`,版本无关——material3 无 `PullToRefreshBox` 符号):列表到顶继续下拉越过阈值松手触发 onRefresh,顶部转圈指示器。
- 接入首页/动态/搜索结果/UP 空间四个网格页(onRefresh 复用各自首屏重载)。

### 安装包
- `BiliMT-v2.0.0-alpha.15-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.15-armeabi-v7a.apk`

## v2.0.0-alpha.14

v2.0.0-alpha.13 后:UP 主空间页 —— P1 收尾。

### UP 主空间页
- 新增 `MobileUserSpaceScreen`:资料头(头像/名/签名/粉丝数)+ 关注按钮(`checkFollowStatus`/`setFollowStatus`)+ 投稿网格(`getSpaceVideos`,最新/最热排序,page 分页滚到底自动翻页,复用 `MobileVideoCard`),浅色底。
- **入口**:`MobilePlayerScreen` 顶栏标题与"UP"按钮(`ownerMid > 0`)→ `onOpenUpSpace` → `MobileApp` 的 `spaceRequest` 覆盖层(`BackHandler` 关,点视频先关空间再开播放器)。
- 复用 `VideoRepository.getSpaceVideos/getSpaceUserProfile/checkFollowStatus/setFollowStatus` 与 `ui.space.UpSpaceRequest`。TV 不动。

### 安装包
- `BiliMT-v2.0.0-alpha.14-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.14-armeabi-v7a.apk`

## v2.0.0-alpha.13

v2.0.0-alpha.12 后:自动连播下一集。

### 自动连播
- 播放完成(`completionReported`)后 `LaunchedEffect(completionReported)` 按 `metadata.pages` 取下一分P(`activeRequest.nextEpisodeCompletion(metadata, selectedQualityId)`),延迟 3s 切 `activeRequest` 重载(镜像 TV `PlayerCompletionPlanner`)。切走/手动换集时 `completionReported` 复位,effect 重键取消,不会误触。复用 `ui.player` 包 `internal nextEpisodeCompletion` 扩展。单 P 视频无下一集不触发。

### 安装包
- `BiliMT-v2.0.0-alpha.13-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.13-armeabi-v7a.apk`

## v2.0.0-alpha.12

v2.0.0-alpha.11 后:选集(分P)侧栏弹窗。

### 选集侧栏
- 多 P 视频(`metadata.pages.size > 1`)底栏显示"选集"按钮,弹 `ModalBottomSheet` 列分P,选中高亮(`cid`/`epId` 匹配),点击 `activeRequest.copy(cid, epId, forceStartPosition, historyPage, preferredQualityId)` 重载(镜像 TV `PlayerScreen.Episodes`)。单 P 不显示按钮。

### 安装包
- `BiliMT-v2.0.0-alpha.12-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.12-armeabi-v7a.apk`

## v2.0.0-alpha.11

v2.0.0-alpha.10 后:播放器设置弹窗(画质/倍速/弹幕)—— P1 开始。

### 播放器设置弹窗
- `MobilePlayerScreen` 底栏加"设置"按钮,弹 `ModalBottomSheet`:
  - **画质**:列 `info.qualities`,选中高亮;切换改 `activeRequest.preferredQualityId` + 携带当前位置 → load effect 重载(镜像 TV,`getPlaybackInfo` 按 qn 重取)。
  - **倍速**:`[0.5..3.0]x`,实时 `player.setPlaybackSpeed` + 弹幕层 `playbackSpeed` 同步。
  - **弹幕**:开关 + 不透明度/字号/显示区域/速度滑块 + 顶部/底部开关,经 `DanmakuSettingsStore` 持久化。
- 引入 `activeRequest`/`metadata`/`selectedQualityId`/`playbackSpeed` 状态,load effect 改 keyed `activeRequest`(支持画质/分P 切换重载);sheet 深色底 + 白字/品牌粉高亮。

### 安装包
- `BiliMT-v2.0.0-alpha.11-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.11-armeabi-v7a.apk`

## v2.0.0-alpha.10

v2.0.0-alpha.9 后:后台播放(前台 service 保活 + 通知控件)。

### 后台播放
- 新增 `PlaybackService`(前台 service,`foregroundServiceType=mediaPlayback`)+ `PlayerHolder`:播放器仍由 `MobilePlayerScreen` 持有,服务经 `PlayerHolder` 操作同一个 ExoPlayer,负责后台保活 + 通知控件(播放/暂停/停止)。
- `MobilePlayerScreen` 去掉 `ON_PAUSE` 的 `player.pause()`(仅存进度,心跳继续每 15s 上报),app 进后台/息屏**音频继续**;播放开始/标题变化启动 service,播放结束/出错/关播放器停 service。
- Manifest 加 `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_MEDIA_PLAYBACK`/`POST_NOTIFICATIONS` 权限与 service 声明。仅移动端启动,TV 不动。

### 安装包
- `BiliMT-v2.0.0-alpha.10-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.10-armeabi-v7a.apk`

## v2.0.0-alpha.9

v2.0.0-alpha.8 后:底栏重复点击"推荐"触发刷新。

### 推荐 tab 重复点击刷新
- 之前底栏重复点击已选中的"推荐"无反应(内容不刷新)。`MobileApp` 检测 Recommend 重复点击 → bump `recommendRefreshKey`,`MobileHomeScreen` 新增 `refreshKey` 参数,`LaunchedEffect(refreshKey>0)` 对当前分区 `loadSection(forceRefresh=true)` 并 `gridState.scrollToItem(0)`(滚顶 + 重载,参照 BV `navigate` 对 Home 的 `animateScrollToItem(0)`+重载)。仅移动端,TV 不动。

### 安装包
- `BiliMT-v2.0.0-alpha.9-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.9-armeabi-v7a.apk`

## v2.0.0-alpha.8

v2.0.0-alpha.7 后:状态栏透明+浅色图标 + 播放全屏。

### 状态栏透明 + 浅色图标
- 上一版顶部留了 inset,但状态栏条是深色、图标不可见。`MainActivity`/`SettingsActivity`/`LoginActivity` 的 `enableEdgeToEdge()` 改传 `SystemBarStyle.dark(Color.TRANSPARENT)`(状态栏 + 导航栏):透明系统栏背景 + 强制**浅色(白)图标**,深色 app 上白时钟/电量清晰可见。TV 仍 `!tvUi` 闸住,不动。

### 播放全屏
- `MainActivity` 加 `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`:旋转不 recreate activity,`remember` 的 ExoPlayer 与状态存活。
- `MobilePlayerScreen` 加 `fullscreen` 状态 + 底栏"全屏/退出全屏"按钮:`DisposableEffect(fullscreen)` 设 `requestedOrientation`(LANDSCAPE/UNSPECIFIED)+ `WindowInsetsControllerCompat` 隐藏/显示系统栏(沉浸 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`),`onDispose` 兜底恢复 —— 关播放器不卡横屏、主页不残留沉浸。

### 安装包
- `BiliMT-v2.0.0-alpha.8-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.8-armeabi-v7a.apk`

## v2.0.0-alpha.7

v2.0.0-alpha.6 后:移植搜索 tab(取代底栏影视占位)+ 修播放器返回键退出 + 顶部留状态栏 inset。

### 搜索 tab(新增,取代底栏 PGC 占位)
- **`MobileSearchScreen`**:底栏"影视"占位换成"搜索",新增触屏搜索页。顶部 `OutlinedTextField`(系统软键盘 + IME 搜索),输入态显示搜索历史 / 联想(防抖 250ms,复用 `SearchHistoryStore` 与 `VideoRepository.getSearchSuggestions`);结果态显示排序 chip(综合/播放/发布/弹幕)+ `LazyVerticalGrid` 复用 `MobileVideoCard`,page-number 分页(PageSize=20,滚到底自动翻页,按 bvid 去重)。
- **点卡片**走 `onVideoSelected → toPlaybackRequest → MobilePlayerScreen`,复用触屏播放器进度上报。
- **TV 端零改动**:排序选项移动端私有复制,不动 TV `SearchScreen`;`AppDestination.Search` 入口与 `search_*` 字符串本就存在。
- 历史清除、清空输入、结果态系统返回回输入态(BackHandler)。

### 播放器返回键修复
- **返回不再退 app**:之前播放器覆盖层没有 `BackHandler`,系统返回无人消费 → activity finish 退 app。现 `MobileApp` 播放器覆盖层加 `BackHandler { playbackRequest = null }`,组合在 `NavigationSuiteScaffold` 内容之后、`OnBackPressedDispatcher` 栈更靠顶,系统返回优先**关播放器回选片页**。搜索结果进播放器再返回也正确关播放器(而非回搜索输入态)。

### 顶部状态栏 inset
- **内容不再压在状态栏下**:`MainActivity` 仅移动端 `enableEdgeToEdge()`(TV 不动),`MobileApp` 的 `NavigationSuiteScaffold` 加 `statusBarsPadding()`;`SettingsActivity` 根 `Surface`、`LoginActivity` 的 `MobileLoginScreen` 同补顶部 inset。播放器覆盖层保持全屏不动。

### 安装包
- `BiliMT-v2.0.0-alpha.7-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.7-armeabi-v7a.apk`

## v2.0.0-alpha.6

v2.0.0-alpha.5 后:短信登录时序修复 + 移植动态 tab。

### 短信登录时序修复
- **"点一下即完成"**:B站"登录"是异步,之前点"完成登录"太早会读空 cookie 要点第二下。现点一下后自动每 400ms 重试 ~5s,cookie 一出现立刻完成;自动轮询也从 1s 缩到 500ms,B站登录完成更快自动返回。

### 动态 tab(新增)
- **`MobileDynamicScreen`**:底栏"动态"不再是占位,显示关注动态视频网格(复用 `VideoRepository.getDynamicFeed(type=video)` + `MobileVideoCard`),滑到底按 offset 自动分页(按 bvid 去重,`hasMore` 判结束)。点卡片进触屏播放器。
- **未登录态**:动态 tab 未登录时显示"未登录 + 登录"按钮,点进短信登录页。
- **卡片统计**:动态项 `view=0`,卡片尾统计改显示点赞数(`likeCount`),不再显示"0";首页视频(`view>0`)不变。

### 安装包
- `BiliMT-v2.0.0-alpha.6-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.6-armeabi-v7a.apk`

## v2.0.0-alpha.5

v2.0.0-alpha.4 后:移动端登录改 SMS-only + 修短信"点登录没反应"。

### 移动端登录
- **去掉二维码登录**:移动端 TV QR 接口一直扫不出来,登录页只留短信登录(WebView 托管 B站 登录页)。`MobileLoginScreen` 简化为直接渲染 `MobileSmsWebViewPanel`。TV 端 `AccountScreen` 的 QR 不受影响。
- **加固 SMS WebView**(修"点 B站 登录没反应"):
  - 顶部覆盖 **返回 / 完成登录** 栏——B站"登录"无自动返回时,点"完成登录"读 cookie 完成(`SESSDATA`+`bili_jct` 存 session + 刷新资料 + 返回);没抓到则 Toast 提示。
  - WebView 设置补全:`WebChromeClient`(开 console)、第三方 cookie、JS 可开窗、`onReceivedError` 日志 → logcat tag `BiliMT:SmsLogin` 便于 `adb logcat` 定位 B站页面卡点。
  - 扩大 cookie 域读取:`passport.bilibili.com` / `www.bilibili.com` / `m.bilibili.com`。
  - 自动轮询(1s)保留,与手动按钮共用 `completeLogin`。

### 安装包
- `BiliMT-v2.0.0-alpha.5-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.5-armeabi-v7a.apk`

## v2.0.0-alpha.4

v2.0.0-alpha.3 后:移动端 SMS 短信登录 + 应用名改回 BiliMT。

### 移动端短信登录(WebView 托管)
- **`MobileSmsWebViewPanel`**:WebView 打开 B站登录页(`https://passport.bilibili.com/login`),用户在 B站自己的页面完成 手机号 + 极验滑块 + 短信;轮询 `CookieManager` 抓 `SESSDATA`/`bili_jct`(+`buvid3/4`)存进 `SessionStore` 并 `refreshUserProfile`,成功自动 `finish` 回设置页。**不引入极验 SDK、不逆向 B站 sms/captcha API**——B站改 API 不受影响。启动先清旧 cookie 以便检测本次新登录。
- **登录页加 扫码登录 / 短信登录 Tab 切换**:QR 抽成 `QrLoginPanel`,与短信 WebView 并列。

### 应用名
- **改回 BiliMT**:此前 alpha 版本按"moblie版本叫BiliMO"改名为 BiliMO,现恢复 BiliMT(三个 locale 一并)。`applicationId`(`com.kirin.mt`)不动。单 APK,Tv/手机共用。

### 安装包
- `BiliMT-v2.0.0-alpha.4-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.4-armeabi-v7a.apk`

## v2.0.0-alpha.3

v2.0.0-alpha.2 后的 Phase 3:移动端触屏播放器。

### 触屏播放器
- **`MobilePlayerScreen`**:复用 BiliTVNative 的 Media3/ExoPlayer 引擎(`PlaybackRepository.getPlaybackInfo` → DASH/PGC `MediaSource` + `PlayerDanmakuLayer`),输入层换触屏——点击切换控件、`Slider` 拖动 seek、播放/暂停、弹幕开关、返回。
- **复用 TV load 序列**:CDN 并发选择、`saveProgress`/`reportProgress` 心跳上报(每 15s)、播放完成上报(`CompletedProgressSeconds=-1`)、`ON_PAUSE` 暂停存进度、屏幕常亮。弹幕复用 `PlayerDanmakuLayer`。
- **引擎复用改造**:把 `PlayerScreen.kt` 的 `buildDashMediaItem`/`buildDashManifest`/`toRepresentation`/`withResolvedMetadata` 从 `private` 提升为 `internal`(零行为风险);新增 `PlayerLaunchSupport.kt` 提供 `VideoSummary.toPlaybackRequest()`(TV/移动端共用)。
- **接入**:首页点视频 → `toPlaybackRequest()` → 全屏 `MobilePlayerScreen` 覆盖层,返回清除。`MainActivity` 透传播放引擎依赖。

### 暂未覆盖(Phase 3 v2)
- 画质/倍速/弹幕设置弹窗(当前用设置里的画质/编码/CDN 偏好与默认弹幕设置)
- 自动连播下一集 / 空降助手 / 在线人数 / 选集侧栏

### 安装包
- `BiliMT-v2.0.0-alpha.3-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.3-armeabi-v7a.apk`

## v2.0.0-alpha.2

v2.0.0-alpha.1 后的 Phase 2:移动端登录 + 卡片式设置。

### 移动端登录(QR)
- **`MobileLoginScreen` + `LoginActivity`**:复用 `AuthRepository` TV QR 流程(`generateTvQrCode`/`pollTvLogin`)+ `createQrCodeBitmap`,触屏 Material3 布局;扫码成功自动写入 session 并 `finish()` 回设置页。
- **短信登录暂未支持**:引擎(`AuthRepository`)无 SMS 路径,需另建 B站 SMS + geetest 验证流程(引擎工作,非 UI 移植),登录页留占位提示。

### 卡片式设置
- **`MobileSettingsScreen` + `SettingsActivity`**:Material3 卡片行 + 开关行 + 单选对话框,四组——账号 / 播放设置 / 界面与交互 / 程序更新。复用 `AppSettingsStore` 逐字段 `setX()` 写入(与 TV 共用同一 DataStore)。
- **程序更新卡**:复用 `UpdateManager` + `ApkInstaller.startInstall` 实现检查更新 / 下载 / 安装。
- **账号行**:显示登录态(用户名/uid)+ 登录 / 退出登录。

### 外壳
- **Settings tab 点击直接 `startActivity(SettingsActivity)`**:不切走当前内容,设置以独立 Activity 承载。`LoginActivity`/`SettingsActivity` 注册到 Manifest(复用 `AppTheme`)。

### 安装包
- `BiliMT-v2.0.0-alpha.2-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.2-armeabi-v7a.apk`

## v2.0.0-alpha.1

首个 **2.0.0 大版本**：参照 [BV](https://github.com/aaa1115910/bv) `feature/mobile` 分支设计，移植触屏移动端 UI。本版为 alpha.1（Phase 1：外壳 + 首页），后续阶段补登录/卡片式设置/触屏播放器。独立 `mobile` 分支，应用显示名 **BiliMO**。

### 构建形态
- **一个 APK 适配 TV + 手机**：`MainActivity` 新增 `isTvUi()` 检测（`UI_MODE_TYPE_TELEVISION` 或 `FEATURE_LEANBACK`），TV 设备走原 `BiliTvApp`（零改动），手机/平板走新 `BiliMobileApp`。
- **应用名 BiliMO**：mobile 分支 `app_name` 改为 BiliMO（`applicationId` 不变，不影响签名/升级）。

### 移动端外壳
- **`NavigationSuiteScaffold` 自适应导航**（`androidx.compose.material3:material3-adaptive-navigation-suite:1.4.0`）：窄屏底部 `NavigationBar`，宽屏侧边 `NavigationRail`，`layoutType` 由 scaffold 自动从 `WindowAdaptiveInfo` 推导。底栏复用 `AppDestination`（推荐/动态/影视/设置）。
- **触屏首页**：`PrimaryScrollableTabRow` 分区标签（推荐/热门/动画…，复用 `home_section_*` 文案）+ `LazyVerticalGrid` 可点卡片网格 + 滑到底自动分页，复用 `VideoRepository`/`HomeSection`/`VideoSummary` 加载引擎，去掉所有 `FocusRequester`/`onPreviewKeyEvent` 焦点逻辑。
- 其余 tab（动态/影视/设置）占位「功能开发中」，触屏播放器留待 Phase 3（点视频暂以 Toast 提示）。

### 依赖
- 新增 `androidx.compose.material3:material3-adaptive-navigation-suite` 1.4.0（Compose BOM 不覆盖该 artifact，显式 pin；`NavigationSuiteScaffold` 在主 material3 group，非 `androidx.compose.material3.adaptive` 点分 group）。

### 安装包
- `BiliMT-v2.0.0-alpha.1-arm64-v8a.apk`
- `BiliMT-v2.0.0-alpha.1-armeabi-v7a.apk`

## v1.1.2

v1.1.1 稳定版后的 alpha.1 ~ alpha.8 汇总。本版聚焦动态/追番页 UI 精简、PGC 番剧播放与进度上报对齐 BV、PGC/UGC 焦点导航修正。详细逐条见下方各 alpha 段。

### 动态页
- **「动态」tab 拆成「视频 / 综合」两个一级 tab**（alpha.1）：tab 行变 视频 综合 历史 收藏 追番（`BiliCapsuleTabRow` 横滚），删掉第二行「全部/视频」类型过滤 pill；`dynamic` state 拆成 `dynamicVideo` + `dynamicAll` 各自缓存；网格首行 Up 回 tab 行当前 pill。
- **追番 tab 去掉二次分区筛选行**（alpha.7）：删掉网格上方「番剧/影视 + 全部/想看/在看/看过」pill 行，直接显示卡片；上键回顶部 tab 行，与其它 tab 一致。保留 `番剧/全部` 默认值供 Bili follow 接口（必须带 `type`，无「全部」）。
- **追番卡片去掉分区行、标题完整显示**（alpha.4）：新增 `VideoCardMode.Bangumi`，不渲染第二行分区标签，标题 `maxLines = 2` 完整显示；其它 tab 卡片布局不变。

### PGC 番剧详情页
- **正片选集行 sticky 置顶常驻**（alpha.2）：`PgcSeasonScreen` 正片选集行改 `stickyHeader`，滚动简介/花絮时常驻顶部；花絮 section 行保持普通 item 随内容滚动。
- **☰ 选集按钮横滑不动**（alpha.3）：`PgcEpisodeRow` 把 ☰「选集」按钮从 `LazyRow` 内移到外层固定左栏，剧集卡片在右 `LazyRow` 独立横滑；回退 alpha.2 误做的整行垂直 sticky。

### PGC 番剧播放
- **播放进度上报带 PGC 字段 + 周期 heartbeat（对齐 BV）**（alpha.5）：`reportProgress` 对 PGC 在 `/x/click-interface/web/heartbeat` 附 `type=4/epid/sid/sub_type/aid`，服务端据此更新该季 `user_status.progress.last_ep_id/last_time`，退出后再进季页上次播放集焦点更新。新增播放中每 15s 周期 heartbeat（仅 `isPlaying` 时），退出前服务端即已最新。
- **`is_drm` 不再拦截**（alpha.8）：去掉 `is_drm=true` 时 `PGC content requires DRM` 硬拦截，对齐 BV 直接播返回的清晰 DASH（playurl 请求本就不带 `drm_tech_type`/`from_client`，服务端返回清晰流）。

### PGC / UGC 焦点导航
- **PGC 左键只在最左列跳侧栏**（alpha.4）：`PgcCard` 新增 `isFirstColumn`，左键仅最左列跳侧栏，其它列走默认遍历切左邻番剧卡；`PgcScreen` + `PgcIndexScreen` 一并修。
- **PGC 上键非首行不跳 tab**（alpha.6）：`PgcCard` 新增 `isFirstRow`，上键仅第一行回 tab，其它行上移一行。
- **UGC 网格长按进 UP 主页**（alpha.6）：推荐 / 搜索 `TvVideoGrid` 补 `onCardLongPress = { video -> onOwnerSelected(video) }`，长按确认键直接进 UP 主主页，与动态页历史/收藏 tab 一致。

### 检查更新
- **alpha 用户能收到更新的 alpha**（alpha.2）：`UpdateRepository.checkLatest` 改用 GitHub `/releases` 列表（含 prerelease）按 versionCode 取最大；稳定版/dev 用户只在稳定版里挑，alpha/beta/rc 用户在全部 release 里挑（能收新 alpha，也能毕业到新稳定版）。

### CI / 发布流程
- **alpha tag 标 `--prerelease`**（alpha.2）：`gh release create` 按 tag 含 `-` 判定 prerelease；「Delete old prereleases」minor 权重 1e4→1e5 与 `computeVersionCode` 三处统一，避免 patch≥10 跨 minor 时 prerelease 新旧顺序误判。

### 安装包
- `BiliMT-v1.1.2-arm64-v8a.apk`
- `BiliMT-v1.1.2-armeabi-v7a.apk`

## v1.1.1-alpha.8

v1.1.1-alpha.7 后的修复：部分 PGC 番剧因 `is_drm=true` 被硬拦截起播即失败，现在对齐 BV 直接播返回的清晰 DASH。

### PGC 番剧播放
- **`is_drm` 不再拦截**：`parsePlaybackInfo` 原先在 PGC playurl 响应 `is_drm=true` 时抛 `PGC content requires DRM, which is not supported yet`，导致部分番剧起播即失败。对齐 BV：BV 也没有 Widevine、不请求 `drm_tech_type`/`from_client`，服务端对 `fnval` DASH 请求仍返回清晰流，`is_drm` 只是「该标题有 DRM 版本可用」的标志，BV 直接忽略它播 DASH。本应用 playurl 请求参数本就和 BV 一致（无 `drm_tech_type`/`from_client`），返回的也是清晰 DASH，故去掉该硬拦截、仅记 warning，让清晰 DASH 正常起播。若服务端真给加密流（理论不会）会在解码层失败，与 BV 行为一致。

## v1.1.1-alpha.7

v1.1.1-alpha.6 后的修复：追番 tab 去掉二次分区筛选行，直接显示卡片。

### 追番
- **去掉顶部二次分区筛选行**：`BangumiFollowContent` 原本在网格上方多一行 `BiliCapsuleTabRow`（番剧/影视 类型 + 全部/想看/在看/看过 状态两组 pill）做二次分区，与其它 tab 不一致。现删除该筛选行、对应 `filterFocusRequester` 与筛选 reload `LaunchedEffect`，直接显示卡片。`BangumiGrid` 上键 `onMoveUpFromFirstRow` 改回 `tabFocusRequester`（顶部 tab 行），与视频/综合/历史/收藏 tab 一致。保留 `selectedType=番剧 / selectedStatus=全部` 默认值供 API 调用（Bili follow 接口必须带 `type`，无「全部」），即显示所有状态的追番番剧。

## v1.1.1-alpha.6

v1.1.1-alpha.5 后的两个 UI 修复：PGC 上键非首行不再跳 tab + UGC 网格长按进 UP 主页。

### PGC 番剧
- **上键非首行不再直接跳顶部 tab**：`PgcCard.onPreviewKeyEvent` 的 `DirectionUp` 原先无条件 `onMoveUpToTab()`，非首行按上也跳 tab。新增 `isFirstRow`（`index < columns`），仅第一行上键回 tab，其它行返回 `false` 走默认焦点遍历上移一行，与右键/左键修法对称。`PgcScreen` 与 `PgcIndexScreen` 两处 `itemsIndexed` 一并传 `isFirstRow`。

### UGC 视频
- **推荐 / 搜索网格长按确认键进 UP 主页**：`TvVideoGrid` 早支持长按确认键调 `onCardLongPress`，但推荐 `RecommendScreen`、搜索 `SearchScreen` 之前没传，长按无反应。现补 `onCardLongPress = { video -> onOwnerSelected(video) }`，长按确认键直接进 UP 主主页，与动态页历史/收藏 tab 一致。`UpSpaceScreen` 本就在 UP 主页内，不加。

## v1.1.1-alpha.5

v1.1.1-alpha.4 后的对齐 BV 修复：PGC 番剧播放退出后「上次播放」记录现在能更新到服务端。

### PGC 番剧播放
- **播放进度上报带 PGC 字段（对齐 BV）**：`PlaybackRepository.reportProgress` 原先对 PGC 也只发 UGC heartbeat（`/x/click-interface/web/heartbeat`，仅 `bvid/cid/played_time/start_ts/csrf`），服务端不会把进度记到该季 `user_status.progress.last_ep_id/last_time`，退出后再进番剧页焦点仍停在上次的集。现在 PGC 时附 `type=4`、`epid`、`sid`、`sub_type`（= `season.type`，1番剧/2电影/3纪录/4国创/5电视剧/7综艺）、`aid`，服务端据此更新该季上次播放集。复用 BV Web 端同一个 heartbeat 端点，未启用仓内废弃的 `PgcHeartbeat` 常量。
- **`PlaybackRequest` 新增 `subType`**：`AppShell.onPlayEpisode` 传 `subType = season.type`；切集路径（自动续播下一集、选集面板切集）经 `copy(...)` 保留 `seasonId/subType`，仅覆盖 `epId/cid`，无需额外改动。
- **`reportProgressNow` 透传 PGC 字段**：`PlayerScreen.reportProgressNow` 从当前 `activeRequest` 取 `epId/seasonId/subType/aid` 一并下发，退出 / 暂停 / 切集 / 完成等所有上报点自动生效。
- **播放中周期 heartbeat（对齐 BV）**：新增 `LaunchedEffect`，播放 Ready 时每 15s（仅 `isPlaying` 时）发一次 heartbeat（`BiliMotion.PlayerHeartbeatIntervalMs`）。BV 同款机制：保证退出前服务端已是最新 `last_ep_id`，`finishPlayer` 退出时那发 best-effort heartbeat 即便被取消也不影响。`PgcSeasonScreen` 退出后本就会重挂载重拉 `getPgcSeasonInfo`，服务端已更新即可刷新上次播放集焦点。

## v1.1.1-alpha.4

v1.1.1-alpha.3 后的两个 UI 修正：追番卡片去掉分区行 + PGC 页左键只在最左列跳侧栏。

### 追番
- **卡片去掉分区行、标题完整显示**：追番 tab 卡片改用新增的 `VideoCardMode.Bangumi`，不再渲染第二行 `MetadataRow`（空头像 + `seasonTypeName` 分区标签如「影视/番剧」+ 日期，对追番数据基本无用），标题改为 `maxLines = 2` 直接在卡片里换行完整显示，不挂跑马灯也能看全。视频 / 综合 / 历史 / 收藏等 tab 卡片仍保持原两行布局不变。

### PGC 番剧
- **页面左键只在最左列跳侧栏**：`PgcCard.onPreviewKeyEvent` 原先对 `DirectionLeft` 无条件 `onMoveLeftToNav()`，导致非首列卡片按左也直接跳侧栏。改为新增 `isFirstColumn`（`index % columns == 0`），仅最左列跳侧栏，其它列返回 `false` 走默认焦点遍历切到左邻番剧卡，与右键行为对称。`PgcScreen` 与 `PgcIndexScreen`（索引/筛选页）共用 `PgcCard`，一并修正，`items` 改 `itemsIndexed` 拿 index。

## v1.1.1-alpha.3

v1.1.1-alpha.2 后的修正：☰ 选集按钮横滑不动 + 回退上轮误做的整行垂直 sticky。

### PGC 番剧详情页
- **☰ 选集按钮横滑不动**：`PgcEpisodeRow` 把 ☰「选集」按钮（点开 `PgcEpisodesDialog` 批量跳集）从 `LazyRow` 内移到外层 `Row` 的固定左栏，剧集卡片在右边 `LazyRow`（`weight 1f`）里独立横滑。左右滑卡片时 ☰ 按钮钉在行最左不动，随时可点开批量跳集弹窗，不用先滑回最左。正片行 + 各花絮 section 行共用此布局，一致生效。
- **回退上轮整行垂直 sticky**：alpha.2 误把正片选集整行做 `stickyHeader` 垂直置顶（方向和范围都错了），已回退为普通 `item` 随页面垂直滚动。
- 同步修正滚动到目标集的 index 偏移：☰ 按钮不再占 LazyRow item 0，`scrollToItem(focusIndex)` 去掉 +1。

## v1.1.1-alpha.2

v1.1.1-alpha.1 后的改进：PGC 选集行常驻 + alpha 用户检查更新能收到新 alpha + 两个 CI 修复。

### PGC 番剧详情页
- **正片选集行 sticky 置顶常驻**：`PgcSeasonScreen` 把正片选集行从 `LazyColumn` 普通 item 改成 `stickyHeader`，滚动简介/花絮时它始终钉在顶部可见，换集不用先滚回顶部找回选集行。外层套页面底色 `BiliColors.VideoBlack` 背景遮挡滚动内容。花絮 section 行保持普通 item 随内容滚动。
- 注：Compose BOM 2026.05.00（Foundation ≥1.8.0）里 `LazyListScope.stickyHeader` 已稳定为成员，无需 `@OptIn(ExperimentalFoundationApi)`。

### 检查更新
- **alpha 用户能收到更新的 alpha**：`UpdateRepository.checkLatest` 改用 GitHub `/releases` 列表（含 prerelease）按 versionCode 取最大者，取代只返回非 prerelease 的 `/releases/latest`。新增 `includePrereleases` 参数：稳定版/dev 用户只在稳定版里挑（不推 alpha），alpha/beta/rc 用户在全部 release 里挑（能收新 alpha，也能毕业到新稳定版）。`UpdateManager.refresh` 按安装版本 versionName 含 `-` 判定预发布用户。

### CI / 发布流程
- **alpha tag 标 `--prerelease`**：`gh release create` 按 tag 含 `-` 判定 prerelease 加 `--prerelease`，与「Delete old prereleases」稳定版判定一致。修复 alpha 在 GitHub 上显示为普通 release、且被 `/releases/latest` 当成最新发布推给稳定用户的 bug。
- **「Delete old prereleases」minor 权重 1e4→1e5**：与 build.gradle `computeVersionCode` 和 `UpdateRepository.parseTagVersion` 三处统一，避免 patch≥10 跨 minor 时 prerelease 新旧顺序误判。

## v1.1.1-alpha.1

v1.1.1 稳定版后的第一个 alpha。把动态页两行结构合并成一行：动态 tab 拆成「视频」「综合」两个一级 tab，删掉第二行的类型过滤 pill。

### 动态页
- **「动态」tab 拆成「视频 / 综合」两个一级 tab**：tab 行变为 视频 综合 历史 收藏 追番（5 个，`BiliCapsuleTabRow` 横滚）。「视频」= `type=video`（默认），「综合」= `type=all`。
- **删掉第二行类型过滤 pill**：原来「动态」tab 下方还有一行「全部 / 视频」pill，与本端只渲染 archive 视频动态的现实冲突（选「全部」非视频类型被 `fromDynamicItem` 丢弃，结果与「视频」几乎一致）。拆成一级 tab 后语义清晰，不再有"选了等于没选"的困惑。
- **状态独立**：`dynamic` state 拆成 `dynamicVideo` + `dynamicAll`，各自缓存（切 tab 不丢已加载内容）；`DynamicFeedUiState.selectedType` 删除，type 改由 tab 决定、加载函数外部传入。
- **焦点**：网格首行 Up 直接回 tab 行当前选中 pill（原先是回到第二行类型 pill）；tab 行 Down 进网格、Up/Left 回左侧栏。
- **文案**：新增 `nav_dynamic_video` / `nav_dynamic_all`，TW/HK 繁体「影片 / 綜合」；删掉无用的 `feed_type_all` / `feed_type_video`。
- **「综合」tab 预留**：当前与「视频」可见结果接近，作为后续专栏 / 番剧等多类型动态渲染的入口。

## v1.1.1

v1.1.0-alpha.1 ~ alpha.6 稳定版汇总 + alpha 后续修复。本版聚焦动态页（Dynamic feed）体验完善与 PGC 番剧选集改进。

### 动态页
- **收藏 / 追番 tab**：动态页 tab 行新增「收藏」「追番」入口，复用现有收藏夹与追番基建。
- **动态卡片社交计数 + 长按操作菜单**（Phase A，alpha.2）：卡片展示点赞 / 评论 / 分享数；长按弹出操作面板（点赞 / 稍后再看 / 去 UP 主主页）。对照 BV 源码后自写 API（BV mobile 端这些均为 `notYetImplemented()` 桩，无可抄实现）。
- **类型过滤 pill 行**（Phase B，alpha.5）：顶部「全部 / 视频」胶囊 pill，默认「视频」，切换重载；网格 Up 落类型行、类型行 Up 回侧栏、Down 进网格。
- **网格 footer + 未读红点 + 收藏排序透传**（Phase C，alpha.5）：列表末尾 footer（加载中 / 没有更多了 / 加载失败 + 可聚焦重试）；侧栏 Dynamic 图标未读红点；`getFavoriteFolderVideos` 加 `order` 参数（默认 `mtime`，排序 pill UI 推迟）。
- **动态详情评论全屏页**（Phase D，alpha.6）：长按菜单「查看评论」进入全屏评论页，热门 / 最新排序、焦点驱动翻页，尾部 footer 复用 Phase C 模式；楼中楼二级回复本期仅显示回复数。

### PGC 番剧选集
- **选集弹窗每页 20 → 100**：抽出 `EPISODES_PER_PAGE = 100` 常量替换 4 处硬编码 `20`，绝大多数番剧一页搞定，不再翻 tab。
- **默认聚焦上次播放集**：用服务端 `user_status.progress.last_ep_id` 定位初始焦点，重新进入看过的番剧焦点直接停在上次那一集并滚动可见；无记录 / 找不到回退第 1 集正片。BV 源码只给对应集打进度徽标、未自动定位焦点，本版补强。

### 修复
- **`TvVideoGrid` footer 漏 `import Box`**：alpha.3 / alpha.4 编译失败根因，补齐导入（两个 tag 已被 CI 孤儿清理）。
- **`UserFeedScreen` 首个 `LaunchedEffect` 关闭**：避免重复加载副作用。
- **`UpdateRepository` versionCode 公式与 build.gradle 对齐**：应用内更新版本对比与构建期 versionCode 计算一致，稳定版 / prerelease 都能正确识别新旧关系。

### 安装包
- `BiliMT-v1.1.1-armeabi-v7a.apk`
- `BiliMT-v1.1.1-arm64-v8a.apk`

## v1.1.0-alpha.6

动态详情页 + 评论(Phase D):长按菜单「查看评论」进入全屏评论页,热门/最新排序、焦点驱动翻页。

### 新增
- **评论 API**：新增 `BiliApiEndpoints.CommentReply`(`/x/v2/reply`,`pn` 分页),`UserFeedRepository.getComments(aid, page, sort)` + `Comment` 模型(`rpid/uname/avatar/mid/content/like/reply_count/ctime`)+ `fromComment` mapper,`VideoRepository.getComments` 代理。oid=视频 aid、type=1。
- **评论页 `CommentScreen`**：Dialog 全屏覆盖层。顶部标题 + 热门(sort=1)/最新(sort=0)排序 pill(复用 `BiliCapsuleTabRow`/`BiliPillTab`),切换排序重载首页。`LazyColumn` 评论项(头像/用户名/相对时间/正文/点赞数/回复数),焦点驱动翻页(聚焦到倒数第 3 项触发 `loadCommentsNextPage`),尾部 footer(加载中/没有更多了/失败+重试,复用 Phase C footer 模式)。空/失败/重试复用 `FeedStatusScreen`。Back 关闭 Dialog。楼中楼二级回复本期不展开,仅显示回复数。
- **菜单「查看评论」入口**：`BiliActionSheet` 在点赞前加「查看评论」项(`video.aid > 0` 才启用),`UserFeedScreen` 加 `onCommentSelected` 回调;AppShell 用 `commentRequest: CommentRequest?(aid,title)` 状态驱动(参照 `spaceRequest` 模式),渲染 `CommentScreen` 覆盖层。

### 已知待验(真机,无 BV 参照)
- 评论 API `/x/v2/reply` 是否需要 wbi 签名:若返回 code!=0(被风控),需改走 wbi 或 `/x/v2/reply/main` cursor。先按非 wbi GET 实现,真机抓包确认。
- 长按动态 → 菜单「查看评论」→ 全屏评论页打开;热门/最新切换重载;翻到底部加载更多;Back 关闭回动态页。
- 评论项 D-pad 上下移动、排序 pill 与列表间焦点切换正常。
- 仅视频动态有 aid 可进评论;无 aid 的菜单项置灰。

## v1.1.0-alpha.5

动态页体验完善(Phase C)+ 按类型过滤(Phase B)合并发布。(alpha.3/alpha.4 因 `TvVideoGrid` footer 漏 `import Box` 编译失败,tag 已被 CI 孤儿清理,本 alpha 修复并合并。)

### 修复
- **`TvVideoGrid` 补 `import androidx.compose.foundation.layout.Box`**：footer 的重试按钮用到 `Box` 但原文件未导入 Box,导致 alpha.3/alpha.4 编译失败(Unresolved reference 'Box' + 级联 @Composable 报错)。

### 新增(Phase C)
- **网格尾部 footer**：`TvVideoGrid` 在列表末尾追加 footer 项,展示「加载中… / 没有更多了 / 加载失败 + 重试」。`UserFeedState.Success` 已有的 `loadingMore/endReached/loadMoreError` 之前未渲染,现经 `GridFooterState` 透传到网格。失败态的重试按钮可聚焦,OK 触发 `onLoadMore` 重试;`moveFocus` 末行 Down 改为不消费,让默认焦点遍历落到 footer 重试按钮。
- **未读动态红点**：新增 `VideoRepository.getDynamicUnread()`(端点 `DynamicUnread`,读 `data.new_default`/`new`)。AppShell 在登录态/切 tab/手动刷新时各拉一次,`AppSidebar` 在 Dynamic 导航项右上角叠红点(`NavUnreadDotSize=8dp`),未读为 0 不显示。
- **收藏排序 order 透传**：`getFavoriteFolderVideos` 加 `order` 参数(默认 `mtime`),`FavoriteFeedUiState.currentOrder` 字段就位。本期无 UI 设置,默认 `mtime` 行为不变;排序 pill 行(最近收藏/最多播放)推迟到后续,因其焦点接线需真机调试。

### 新增(Phase B)
- **动态类型过滤 pill 行**：新建 `DynamicFeedContent` 包装器(仿 `FavoriteFeedContent`),在 Dynamic tab 网格上方加 `BiliCapsuleTabRow`(全部=all / 视频=video)。`DynamicFeedUiState.selectedType` 默认 `video`;切换 type → `loadDynamicFirstPage(forceRefresh=true)` 重载。`getDynamicFeed(type, offset)` 把 `type` 透传给 `/x/polymer/web-dynamic/v1/feed/all`(原来硬编码 `all`)。网格 `onMoveUpFromFirstRow` 改指类型 pill 行的 `typeFocusRequester`(与收藏夹 pill 行同一焦点模式)。
- **默认 type 从 `all` 改 `video`**：本端只渲染 archive 视频动态,`video` 是更干净的连续视频流(`all` 会把图文/专栏等类型也拉回但被 `fromDynamicItem` 丢弃,offset 有空耗)。

### 已知局限
- 非 archive 类型(图文/专栏/转发/番剧)的卡片渲染不在本期范围,「全部」与「视频」可见集合基本一致(差异仅在 `all` 模式下非视频动态占用 offset 槽位)。专栏/番剧动态渲染(番剧可复用项目已有 PGC 播放基建)见后续,需给 `VideoSummary` 加 `epid/seasonId` 并改卡片点击路由。

### 已知待验(真机)
- 翻到网格底部:footer 显示「加载中…」→ 加载完显示「没有更多了」;加载更多失败时显示「加载失败」+ 可聚焦重试按钮,OK 重试;末行 Down 落到重试按钮、Up 回末行卡片。
- 登录后侧栏 Dynamic 图标右上角红点(有未读动态时);切 Dynamic tab / 手动刷新后更新;无未读不显示。
- Dynamic tab 顶部 全部/视频 pill,默认「视频」;切换重载;网格 Up 落类型行、类型行 Up 回侧栏、Down 进网格。
- 网格 D-pad 焦点/翻页加载/焦点恢复未回归(footer 注入 + moveFocus 末行 Down 改动 + 类型 pill 行)。

## v1.1.0-alpha.2

动态页（Phase A）增强：卡片展示动态社交计数 + 长按操作菜单（点赞 / 稍后再看 / 去 UP 主主页）。对照 BV 源码后补齐动态页缺失的社交属性——BV mobile 端的点赞/评论/分享均为 `notYetImplemented()` 桩，无可抄实现，本次自写 API 调用。

### 新增
- **动态卡片展示点赞/评论计数**：`fromDynamicItem` 补取 `module_stat.like/comment/forward.count` 与 `id_str`、`archive.aid`；`VideoSummary` 加 `dynId/aid/likeCount/commentCount/forwardCount` 字段（其它来源默认 0，不影响推荐/历史/收藏卡片）。`VideoCard` 封面元数据行在播放/弹幕后追加点赞、评论计数（>0 才显示，自然仅动态卡可见），新增 `ic_video_like_count` / `ic_video_comment_count` 矢量图标。
- **长按操作菜单**：动态卡片长按 OK（≥500ms）从「直接进 UP 主主页」改为弹出 `BiliActionSheet` 模态菜单（D-pad 上下选、OK 确认、Back 关闭、首项自动聚焦）。菜单项：点赞、稍后再看、去 UP 主主页。历史/收藏 tab 长按仍直接进 UP 主主页（无动态 id/aid，保留原行为）。
- **点赞 API**：新增 `BiliApiEndpoints.DynamicLike`（`/x/polymer/web-dynamic/v1/like/like`），`UserFeedRepository.likeDynamic(dynId)` 走 `postFormJson`（`dyn_id`+`csrf`），命中后 toast 提示。
- **稍后再看 API**：新增 `BiliApiEndpoints.ToviewAdd`（`/x/v2/history/toview`），`UserFeedRepository.addToView(aid)`（`aid`+`csrf`）；动态 archive 的 `aid` 已在 mapper 取出，无 aid 时菜单项置灰。
- **未读动态端点**：新增 `BiliApiEndpoints.DynamicUnread` + `UserFeedRepository.getDynamicUnread()`（读 `data.new_default`/`new`），为 Phase C 红点功能铺路（本期 UI 暂未接入）。

### 已知待验（真机，BV 无参照）
- 动态点赞 web 端点 `like/like` 入参/返回形状（疑似切换型）：菜单点「点赞」后 toast 显示「已点赞」即调用成功，需真机确认是否真翻转点赞态（接口可能为切换型，重复点击会取消）。
- 稍后再看 `toview` 端点：点「稍后再看」后到 B 站「稍后再看」列表确认是否出现。
- 长按菜单 D-pad 焦点是否正常在菜单项间移动、Back 是否关闭、蒙层外点击是否关闭。
- 动态卡片点赞/评论计数是否正确显示（来自 `module_stat`）。
- 短按仍起播、头像点击仍进 UP 主主页、网格 D-pad 焦点/翻页加载未回归。

## v1.1.0

v1.0.13-alpha.8 ~ alpha.17 稳定版汇总。焦点/布局/分区设置全面对齐 BV。

### 焦点与导航
- **UGC/PGC 侧键进入先落顶部 tab**（alpha.8/11）：侧键进 UGC/PGC 焦点先落顶部分区 tab 行（选中那个 pill），不直接进网格，对齐动态/BV。Down 进内容、Up 回侧栏。
- **PGC tab 焦点即选中**（alpha.13）：焦点落某 PgcType tab 即选中、grid 切到它的内容，无需先 Enter。
- **PGC 全屏详情页/索引页按 Back 显示侧键**（alpha.9）：番剧/索引全屏 overlay 按 Back 关闭回带侧栏基页（含分集对话框/滤镜的嵌套 Back）。
- **UGC 分区显隐面板 Up/Down 修复**（alpha.10）：33 行面板显式 D-pad 纵向导航 + 滚入视野 + 排序后焦点跟随。
- **UP 主页起播返回焦点无法选中修复**（alpha.16）：从 UP 主页起播返回后焦点落回离开时那张卡片。

### 布局与样式
- **UGC/PGC/动态 上部 tab 样式统一**（alpha.11）：提取共享 `BiliCapsuleTabRow` + `BiliPillTab`（玻璃胶囊 pill），三页顶部 tab 视觉一致。
- **PGC 网格 5 列**（alpha.14）：主内容网格 + index 网格改 5 列（PGC 专用 `PgcGridColumns`），一屏约 5×2。
- **PGC 海报比例 3:4**（alpha.15）：`PgcCard`/季详情封面改固定 3:4（`PgcPosterAspect=0.75`），不拉伸海报，对齐 BV。

### 分区设置
- **UGC 新增分区显隐切换修复**（alpha.12）：24 个新 UGC 分区关不掉的 bug（前向兼容每次补回）经一次性迁移修复。
- **UGC 排序显示的排前面**（alpha.17）：`homeSectionsOrder` 维持 enabled-first 不变式（显示的分区排前、隐藏排后），toggle 即时重排 + 重启检测。

### 构建
- **`computeVersionCode` minor 权重 1e4→1e5**：使 v1.1.0 的 versionCode（1,100,000）高于 v1.0.13（1,013,000），避免 patch≥10 时 minor bump 反而降级。历史 v1.0.x 的 versionCode 不变（minor=0）。

## v1.0.13-alpha.17

### 新增
- **UGC 排序:显示的分区排前面**：分区排序设置里，设为「显示」(enabled)的分区排在前面、「隐藏」(disabled)的排在后面，各自保持相对顺序。在 `homeSectionsOrder` 上维持 enabled-first 不变式（stable partition）：`setHomeSectionsOrder` 持久化前 re-partition（▲/▼ 同组内换序正常，隐藏分区无法跨进显示区）；`setHomeSectionEnabled` toggle 后即时 re-partition（面板里关掉立即落到隐藏区、开了回到显示区）；新增 `ensureEnabledSectionsFirst()` 重启时检测排序（idempotent），AppShell `LaunchedEffect` 每次启动调一次。排序面板和首页 tab 行读 `homeSectionsOrder` 不改，持久化顺序满足 enabled-first 后两端自然显示对。

### 已知待验（真机）
- 设置→首页分区：显示的分区排上半、隐藏的排下半，各自相对顺序保留。
- 关掉一个显示分区 → 立即落到隐藏区；再开 → 回显示区上半末尾。
- ▲/▼ 在显示区内换序正常；隐藏区内换序正常；隐藏分区无法 ▲ 进显示区。
- 首页 tab 行只显示 enabled 分区，顺序与面板显示区一致。
- 重启 app：顺序仍 enabled-first（脏顺序启动时修好）。

## v1.0.13-alpha.16

### 修复
- **从 UP 主页起播返回后焦点无法选中**：从 UP 主页（`UpSpaceScreen`，`spaceOrigin == Content`）点视频卡片起播，播完返回时焦点悬空、无法选中。根因：播放期间 UpSpace overlay 被拆除，返回后可见层是 UpSpace 网格，但 `PlayerScreen.onBack` 无脑 arm 内容网格的 restore（`playbackFocusRestoreRequestKey`），从不 bump UpSpace 的 `spaceFocusRestoreRequestKey` → `TvVideoGrid` 的 restore effect（gated on key>0）早退 → 没人 `requestFocus()` → 焦点悬空。现 `PlayerScreen.onBack` 加 Content-origin 分支：`spaceRequest != null && spaceOrigin == Content` 时 `playbackRequest = null` + `spaceFocusRestoreRequestKey += 1`（arm UpSpace 网格 restore，落回离开时聚焦的卡片），跳过内容网格 restore。Player-origin 不动。

### 已知待验（真机）
- 内容页点 UP 主头像进 UP 主页；聚焦某张卡片（非首张，可滚到屏外）→ Enter 起播 → Back 返回 → 焦点落回离开时那张卡片（可选中、Enter 能再起播），不再「无法选中」。
- 离开时聚焦的卡片在屏外时，返回应自动滚到该行再聚焦。
- 从内容页直接起播（不经 UP 主页）返回仍正常恢复内容网格焦点（不回归）。
- 从玩家内开 UP 主页（Player-origin）再返回，行为不变（不回归）。

## v1.0.13-alpha.15

### 修复
- **PGC 海报比例修正为固定 3:4**：用户指出「核心在比例，番剧海报有固定比例」。`PgcCard` 封面（`PgcScreen`）和季详情封面（`PgcSeasonScreen`）原用 `aspectRatio(0.7f)`，不是 B 站番剧季海报的标准比例，会拉伸/裁切海报。加固定比例 token `PgcPosterAspect = 0.75f`（3:4，B 站真实海报比例 + 对齐 BV `SeasonCard`），两处改用它。`PgcCard` 被主内容网格和 index 网格共用，两网格海报都正。0.75 比 0.7 略矮（高度 1.333w vs 1.428w），5×2 布局更易容下 2 行。分集缩略图（16:9）不动。

### 已知待验（真机）
- PGC 主内容网格、index 网格的季海报显示为 3:4 竖图，不拉伸/不裁切（对比 0.7 时略偏长）。
- 季详情页顶部封面也是 3:4。
- 5 列布局下 2 行更易容下。
- 分集缩略图仍 16:9（不回归）。

## v1.0.13-alpha.14

### 改进
- **PGC 网格改 5 列（对齐 BV 密度，一屏约 5×2）**：PGC 两个网格（主内容季度卡片网格 `PgcScreen` PgcGrid、index 索引网格 `PgcIndexScreen`）原都用 `BiliSizing.VideoGridColumns = 4`（与 UGC 共用）。加 PGC 专用 `PgcGridColumns = 5` token，两个 PGC 网格改用 5 列，一屏约 5×2=10 张可见，对齐 BV 更密的网格风格。仅布局调整：cursor feed / index 分页无限滚不变，卡片 aspect（0.7）/间距/padding 不变。UGC/推荐网格仍 4 列、PGC 分集对话框仍 4 列（不回归）。

### 已知待验（真机）
- PGC 主内容网格、index 索引网格都 5 列；Down 无限滚加载不变；一屏约 10 张可见。
- UGC/推荐网格仍 4 列；PGC 分集对话框仍 4 列（不回归）。
- 若一屏不是约 2 行，可再调 `PgcCard` cover aspect（现 0.7）或网格 vertical spacing 微调。

## v1.0.13-alpha.13

### 修复
- **PGC 上部 tab 焦点即选中（对齐 UGC/BV）**：alpha.11 把 PGC 侧键进入改成先落顶部 tab 并统一到 `BiliPillTab`，但 PGC 的 PgcType pill 没传 `onFocused`，焦点落到非选中 tab 不切 `selectedTab`，下方 grid 仍显示原 tab 内容，必须先按 Enter 选中才能进对应内容（用户反馈「只有先选中才能进入下部主页」）。现给 PgcType pill 加 `onFocused = { onSelect(type) }`，对齐 UGC `onSectionFocused` / BV `TopNav` onFocus：焦点落某 PgcType tab 即选中、grid 切到它的内容，Down 直接进该 tab grid，无需先 Enter。侧键进入焦点落已选中 tab（`selected=true`），`onFocused` 不触发，无多余切换；index 按钮不加（Enter 才开 index 页）。

### 已知待验（真机）
- 侧键进 PGC → 焦点落选中 PgcType tab；按 Down 直接进该 tab grid（无需先 Enter）。
- tab 行 Right 移焦点到另一 PgcType tab → 该 tab 立即变选中、grid 切内容；再 Down 进该 tab grid。
- 快速 Left/Right 扫过多个 tab → 依次选中、grid 跟切（已访问不重载、未访问加载）。
- index 按钮：焦点移上去不自动开 index 页，Enter 才开；tab Up 回侧栏、grid Up 回 tab 不回归。

## v1.0.13-alpha.12

### 修复
- **UGC 新增分区显隐切换不生效**：设置→首页分区面板里，alpha.6 新增的 24 个 UGC 分区（鬼畜/影视/娱乐/…/神秘学）按 Enter 关不掉、chip 永远卡在"开"。根因：`AppSettingsStore` 读 `enabledHomeSections` 时每次都把这 24 个新 key 补成启用（前向兼容 `newlyAddedHomeSectionKeys` 缺失就补），导致 `setHomeSectionEnabled(section, false)` 从持久化集合 `remove` 一个本不在集合里的 key（no-op），下次发射又被补回。原 9 个老 key 不在该集合里，故切换正常。alpha.10 修的 Up/Down 导航不受影响。
  - 修法（一次性迁移）：加 `HomeSectionsUgcMigrationV1` 标志；读路径前向兼容改为仅迁移未跑时才补；新增 `ensureHomeSectionsMigration()` 把 24 个新 key 写进持久化启用集合 + 置标志，`AppShell` 首启 `LaunchedEffect` 调一次。迁移后持久化集合即真相，`remove` 找得到 key、写回不含该 key、下次发射不再补回 → 切换生效。升级路径不变（首启仍默认启用新分区）。

### 已知待验（真机）
- 把 24 个新分区之一（如 鬼畜/神秘学）按 Enter 关掉 → 立即变未选，离开设置回来仍禁用；再按 Enter 开 → 持久化。
- 老 9 个分区切换仍正常（不回归）。
- 首页 `RecommendHeader` tab 行只显示启用的分区；只剩 1 个启用时关不掉（守卫不变）。
- 从老版本升级 → 首启 24 个新分区仍默认启用（可见状态不变），之后可正常切换。

## v1.0.13-alpha.11

### 新增
- **PGC 入口侧键先进入上部 tab**：镜像 alpha.8 对 UGC 做的，侧键进 PGC 焦点现在落顶部分区 tab 行（选中的 PgcType pill），不落 grid 首项。`AppShell` hoist `pgcTabFocusRequester`（与 `pgcFocusRequester` 分离），`requestDestinationFocus(Pgc)` 指向它；`PgcScreen` 接收 `tabFocusRequester` + `onMoveDownFromTab`，`PgcTabRow` 把 requester 绑到**选中的 PgcType pill**（原来是 index 按钮）、加 Down→grid。冷启动 grid 焦点不变。

### 重构
- **UGC/PGC/动态 上部 tab 样式统一**：三页顶部 tab 之前各用一套样式（UGC 玻璃胶囊+40dp/19sp/3 层色；PGC/动态 裸 Row+15sp/cardSurface 底）。现提取 UGC 的胶囊样式为共享组件 `BiliCapsuleTabRow`（玻璃胶囊容器+可横滚 pill 行）+ `BiliPillTab`（pill 项：透明底、focused=accent 边框+微底色、3 层文字色 selected=accent/focused=textPrimary/resting=textSecondary、19sp Bold when selected/focused），置于 `ui/common/BiliCapsuleTab.kt`。`RecommendHeader`/`PgcTabRow`/`UserFeedTabRow` 三处改用共享组件，删各自的 `HomeSectionTab`/`BiliFocusableSurface` pill 重复。`BiliPillTab` 支持 Left/Up 逃逸 + Down→grid 可选 hook，各页保留原逃逸方向（UGC=Left 首项、PGC/动态=Up）。PGC 的 index 按钮现在作为普通 pill 进胶囊。项目未依赖 `androidx.tv:tv-material`，故手写复用 `LocalHomeColors`，不引 TV-Material3。

### 已知待验（真机）
- PGC 侧键进入：焦点落选中 PgcType pill；tab 按 Down 进 grid；grid 按 Up 回 tab；tab 按 Up 回侧栏。
- 三页顶部 tab 视觉一致：都套玻璃胶囊容器、pill 同高(40dp)同字号(19sp)同配色；UGC 33 tab 横滚、PGC 6 type+index、动态 2 tab 在胶囊内排布。
- 三页 tab 的 Left/Right 移动、逃逸回侧栏、Down 进内容行为不回归。

## v1.0.13-alpha.10

### 修复
- **UGC 分区显隐面板 Up/Down 失效**：设置 → 首页分区的显隐/排序面板（`SettingsHomeSectionsColumn`）原本只靠 Compose 默认空间焦点遍历做纵向导航——无 `FocusRequester`、无 Up/Down 键处理、无 scroll-into-view。alpha.6 把分区从 2 个扩到 33 个后 `LazyColumn` 要滚动，默认遍历在屏外行失效，表现为上下键无反应、31 个新增 UGC 分区够不着也滚不到。现把左侧设置列那套 D-pad 纵向导航模式（`SettingsFocus.kt`）套过来：
  - 每行 `FocusRequester` 按 `HomeSection` 枚举身份建（reorder 后仍稳定），挂到每行 chip。
  - Row 级 `onPreviewKeyEvent` 拦 Up/Down → `moveRowFocus`（`scrollItemIntoComfortableView` + `requestFocus`），屏外行自动滚入视野；Left/Right 放行（chip 的 Left 逃逸、▲/▼ 的左右默认遍历不变）。捕获相 Row 先于子节点，Up/Down 在 Row 拦掉。
  - 外包 `CompositionLocalProvider(LocalBringIntoViewSpec provides SettingsBringIntoViewSpec)`，`requestFocus` 也能把屏外行带入视野（双保险）。
  - ▲/▼ 排序后焦点跟随：swap 后重新滚到新位置并聚焦移动的行（枚举身份不变，requester 仍有效）。
- **编译修复**：`LocalBringIntoViewSpec` 是实验 foundation API，给 `SettingsHomeSectionsColumn` 加 `@OptIn(ExperimentalFoundationApi::class)`（同 `SettingsBehaviorColumn`）。

### 已知待验（真机）
- 设置 → 首页分区，Right 进面板，焦点落第一行 chip。
- 按 Down 逐行下移到第 33 行（神秘学），屏外行自动滚入视野；按 Up 反向回走。
- 任意新分区行 Enter 切换显隐；▲/▼ Enter 排序，焦点跟随移动的行到新位置；首页 `RecommendHeader` tab 顺序对应变化。
- chip 按 Left 回左侧设置列（不变）；▲/▼ 按 Left 仍走默认遍历到 chip/▲（不变）。

## v1.0.13-alpha.9

### 修复
- **PGC 全屏详情页按 Back 显示侧键**：点番剧进 `PgcSeasonScreen`（全屏 overlay，盖住侧栏）后，硬件 Back 之前不会关掉详情页，而是落到 shell 的 app-exit 逻辑弹「再按一次退出」toast —— 因为 `PgcSeasonScreen`/`PgcIndexScreen` 的 `onBack` 只绑在 D-pad 左/上键，没绑 Back 键。现给两个全屏 overlay 加 `BackHandler`（镜像 `UpSpaceScreen.kt:109` 同款模式）：Back 关掉 overlay → 回到带侧栏的 PGC 基页（即「显示侧键」）。
  - `PgcSeasonScreen`：`BackHandler { onBack() }` 无条件。分集 `PgcEpisodesDialog` 是独立 `Dialog` window，开时 Back 由对话框 window 消费（多页回当前分页标签 / 单页关对话框），不误关详情页；对话框关掉后 Back 才关详情页。
  - `PgcIndexScreen`：`BackHandler { if (showFilter) showFilter = false else onBack() }`。分类索引的滤镜 `PgcIndexFilterDialog` 是内嵌 `Box` 非独立 window，故须带分支——Back 先关滤镜，再按才关 index 页，否则滤镜开时 Back 会直接关掉整个 index 页。
  - shell 级 app-exit `BackHandler` 不动；overlay 的 BackHandler 后注册（LIFO 优先），overlay 开时 Back 走 overlay，关掉后才回 app-exit。

### 已知待验（真机）
- PGC 基页点番剧 → 全屏详情页；按 Back → 回带侧栏基页。
- 详情页开分集对话框时按 Back → 只关对话框（不关详情页）；再按 Back → 关详情页回基页。
- PGC 基页进 index（分类索引）→ 全屏；按 Back → 回带侧栏基页。
- index 页开滤镜时按 Back → 关滤镜（不关 index 页）；再按 Back → 关 index 页回基页。
- 所有 overlay 都关掉后（PGC 基页）按 Back → 走原 app-exit「再按一次退出」（不变）。

## v1.0.13-alpha.8

### 改进
- **UGC 侧键进入先落顶部分区 Tab（对齐动态）**：从侧栏「推荐/UGC」项进入时，焦点落在顶部分区 Tab 行（选中分区胶囊）而非直接进内容网格——把 alpha.5 给动态做的 `7750ccc` 范式移植到 Recommend。`AppShell` 新增 `recommendTabFocusRequester`（上提自 `RecommendScreen` 内部的 `selectedSectionFocusRequester`），`requestDestinationFocus(Recommend)` 指向它；`Recommend` 从 `usesGridFocusRestore` 移除，侧栏进入改走 pending → `requestDestinationFocus` → Tab 焦点路径。`HomeSectionTab` 新增 ↓ 键显式跳转：有 banner 落 banner、无 banner 落 grid 首项（原靠默认焦点遍历不可靠）。焦点链闭环：tab↓→banner(UGC)/grid(推荐热门)、banner↓→grid、grid↑→banner/tab、banner↑→tab、tab↑/左→侧栏。

### 保持不变
- 冷启动仍在 Recommend 时焦点落 grid（`InitialHomeCardFocusEffect` 未改）。
- 看完视频返回仍走 `playbackFocusRestore` 恢复到离开时的网格卡片。
- 副作用（与动态一致）：从 UP 主空间返回 Recommend 会落 Tab 而非原 grid 卡。

### 已知待验（真机）
- 冷启动：焦点仍在 grid 第一个卡片（不变）。
- 从侧栏切到动态/PGC 再按侧栏回 UGC：焦点落顶部分区 Tab，不落 grid。
- 已在 UGC（grid 有焦）时再按一次侧栏 UGC：焦点跳到分区 Tab。
- 分区 Tab 按 ↓：有 banner 落 banner、无 banner 落 grid 首行；banner 上按 ↓ 落 grid。
- Tab 按 ↑ 回侧栏；首个分区按 ← 回侧栏。
- 看完一个视频返回：仍回原 grid 卡片（播放返回路径未变）。

## v1.0.13-alpha.7

### 新增
- **PGC 选集批量跳转（对齐 BV）**：番剧/影视季详情的分集行原本只能用 D-pad 左右一张张滚动，长番（几百集）跳集极慢。现每行（正片 + 花絮/番外各板块）最前面加一个「选集」入口按钮，点击弹出分页选集对话框——每页 20 集，顶部 `P1-20 / P21-40 / …` 标签焦点驱动切换，下方 4 列封面网格选集起播。完全移植 BV `SeasonInfoScreen` 的 `SeasonEpisodesDialog` 范式（`SeasonInfoScreen.kt:663`），仅把 2 列放大为 4 列以利用 TV 屏宽。
- **焦点衔接**：对话框打开默认聚焦首个分页标签（多页时）或首集（单页时）；网格内按 Back 返回当前分页标签（每个标签独立 `FocusRequester`，回到的是离开时所在页而非首页）；单页时 Back 直接关闭。关闭对话框后焦点回到「选集」按钮。分集行首集左键不再拦截逃逸侧栏，而是自然落到前面的「选集」按钮；侧栏逃逸改由「选集」按钮（行首）承担，对齐 BV「首集左键→ViewModule 按钮」。
- **复用**：对话框网格直接复用 `PgcEpisodeButton`（新增 `fillMaxWidth` 参数使其在网格单元内撑满），播放仍走原 `onPlayEpisode(season, ep)`，播放器自行按 `seasonId` 重建整季播放列表，无需额外传集表。

### 已知待验（真机）
- 选集按钮在正片/各 section 行的显示与点击弹窗；分页标签 `P1-20/…` 文案与数量（ceil(集数/20)）。
- 多页：标签焦点切换应即时翻网格内容；Back 从网格回到当前页标签（不是首页）；左键从首集到选集按钮、选集按钮左键回侧栏（仅正片行）。
- 单页（≤20 集）：不显示标签行，打开直接进网格，Back 关闭。
- 长番（如 500+ 集）标签行可横向滚动并自动滚入聚焦标签。
- 复用 `PgcEpisodeButton` 在 4 列网格中宽度撑满、封面/标题展示正常。

## v1.0.13-alpha.6

### 重构
- **UGC 全量对齐 BV：31 个一级分区 + 顶部轮播 banner + 取消子分区**。原本混在首页 HomeSection Tab 行的 10 个 UGC 分区替换为 BV `UgcTypeV2` 的完整 31 个一级分区（动画/游戏/鬼畜/音乐/舞蹈/影视/娱乐/知识/科技数码/资讯/美食/小剧场/汽车/时尚美妆/体育运动/动物/Vlog/绘画/人工智能/家装房产/户外潮流/健身/手工/旅游出行/三农/亲子/健康/情感/生活兴趣/生活经验/神秘学），顺序按 BV `UgcTopNavItem` 枚举声明顺序。删掉番剧(13)/生活(160)——BV 31 里无此两项（番剧是 PGC、生活拆成 LifeJoy/LifeExperience/Vlog/Emotion 等）。
- **删子分区**：移除 `UgcSubPartition`/`UgcPartitionTree`、`UgcSubPartitionBar`/`Chip`、`activeSubTidBySection`/`selectSubPartition`/`regionTidOverride` 全套子分区数据/UI/路由；三套 strings 删 64 条 `ugc_sub_*`；`BiliTokens` 删子分区尺寸 token。
- **数据层走 feed/rcmd only**：UGC 分区统一走 `/x/web-interface/region/feed/rcmd?from_region=<BV tid>&display_id=<页号>`（BV 唯一接口），删 `dynamic/region` 回退与 `getRegionVideos`/`BiliApiEndpoints.Region`。未登录/失败返回空（app 登录门控，与动态/历史一致）。
- **轮播 banner**：每个 UGC 分区顶部加轮播（`/x/web-show/region/banner?region_id=<tid>`），单张封面自动轮播（获焦暂停）、左右键循环、OK 起播。banner `url` 经移植的 BV `AvBvConverter`(av→bv) 解析为 bvid，cid 由播放器经 `/x/web-interface/view` 解析。`UgcBannerCarousel` 放在原子分区胶囊行位置，仅 UGC 分区显示；焦点链 tab↓→banner→grid、grid↑→banner、banner↑→tab。
- **分区 Tab 行可滚动**：`RecommendHeader` 加 `horizontalScroll`，33 个分区（推荐/热门 + 31 UGC）可横向滚动，聚焦分区自动滚入视野。
- **前向兼容启用集合**：`AppSettingsStore` 读取持久化启用集合时，把本轮 24 个新增分区 key 默认补为启用，不影响用户此前显式禁用的旧分区。

### 已知待验（真机，需登录态）
- 33 个分区 Tab 可滚动、顺序与文案；切到 UGC 分区加载 feed/rcmd 网格。
- 轮播 banner 自动轮播/获焦暂停/左右循环/OK 起播（avid→bvid 转换正确性需重点验证，避免跳错视频）。
- 焦点衔接：tab↓→banner(UGC)/grid(推荐热门)、banner↓→grid、grid↑→banner/tab、banner↑→tab、tab↑→侧栏。
- 未登录 UGC 分区为空（feed/rcmd 无回退，预期）。
- 老用户升级后 24 个新分区默认出现且启用，已禁用的旧分区保持禁用。

## v1.0.13-alpha.5

### 改进
- **侧栏进入合并页焦点落顶部 Tab（对齐 BV）**：从侧栏「动态」项进入合并页时，焦点落在顶部 TabRow（动态/历史）的当前 Tab，而非直接进内容网格——对齐 BV `HomeContent` 从 Drawer 进 Home 焦点落 `TopNav` 的行为。`AppShell` 新增 `feedTabFocusRequester` 绑到选中 Tab 胶囊，`requestDestinationFocus(Dynamic)` 指向它；`Dynamic` 从 `usesGridFocusRestore` 移除，侧栏进入改走 pending → `requestDestinationFocus` → Tab 焦点路径。播放返回仍走 `playbackFocusRestore` 恢复到离开时的网格卡片（不变）。`UserFeedTabRow` 新增 ↓ 键进网格、↑ 键回侧栏，网格首行 ↑ 回 Tab（alpha.4 已加），D-pin 焦点衔接闭环。

### 已知待验（真机）
- 侧栏→合并页：焦点应落在顶部 Tab（动态），按 ↓ 进网格，网格 ↑ 回 Tab，Tab ↑ 回侧栏。
- 播放返回：焦点恢复到播放前那张卡片（不变）。

## v1.0.13-alpha.4

### 新增
- **动态 / 历史合并为单页，顶部 Tab 切换**：侧栏原本「动态」「历史」两个独立入口合并为一个「动态」入口，进入后顶部一行胶囊 Tab（动态 / 历史，默认动态）切换两种内容。合并后的 `UserFeedScreen` 复用 `PgcScreen` 的 `PgcTabRow` 胶囊范式与 `TvVideoGrid.onMoveUpFromFirstRow`，实现网格首行 ↑→顶部 Tab、Tab ↑→侧栏的 D-pad 焦点衔接。每个 Tab 各自保留独立的游标/卡片模式/加载文案：动态走 `x/polymer/web-dynamic/v1/feed/all`（`VideoCardMode.Dynamic`）、历史走 `x/web-interface/history/cursor`（`VideoCardMode.History`，带进度条/已看完 badge），历史 Tab 点卡片仍从历史位置续播（`forceStartPosition=true`），动态 Tab 从头播。切 Tab 用 `key(selectedTab)` 重建网格，内容保留在子 state（`loadedOnce` 守卫不重拉），焦点按各自 `focusedVideoIndex/Key` 恢复。删除 `AppDestination.History` 及其在 `AppShell` 的 focusRequester / state / manualRefreshKey / `when` 分支。

### 已知待验（真机，需登录态）
- 两个 Tab 的加载/分页/卡片展示、Tab 间切换焦点衔接、历史续播位置、未登录态登录提示文案需真机确认。
- 手动刷新（侧栏「动态」项已选中时再按一次）应刷新当前 Tab。

## v1.0.13-alpha.3

### 修复
- **主分区按 OK 不刷新**：`dynamic/region` 是确定性"最新"流，重载几乎不变（实测 `rid=1` 两次调用 9/10 bvid 相同），按 OK 重载视频不变。BV 的 `feed/rcmd` 是推荐流、重载出不同内容。主分区改走 BV 的 `region/feed/rcmd?from_region=<新父tid>`（动画 1005 / 影视 1001 / 游戏 1008 / 知识 1010 / 科技 1012 / 音乐 1003 / 舞蹈 1004 / 美食 1020，带 SESSDATA），重载出新鲜推荐；失败/未登录回退 `dynamic/region` 旧 tid 不阻断。番剧/生活 BV 新体系无对应 UGC 主分类（番剧是 PGC、生活被拆成 vlog/life_joy/emotion 等），留 null 继续走 `dynamic/region` 旧流（确定性重载，边缘已知）。
- **主分区焦点不切显示**：`autoConfirmOnFocus` 默认 false 时三处门控挡住，d-pad 焦点落主分区不切 `activeSectionKey`、显示停在上一分区。解耦：焦点落上就切显示（对齐 BV `TopNav.onSelectedChanged`），已加载秒切不重载、未加载才加载；OK 键仍走 `selectSection(forceRefresh=true)` 强制重载。
- **子分区回退 `dynamic/region`**：alpha.2 的 `4b7bca3` 把子分区误切到 `feed/rcmd?from_region=<旧子tid>`，feed/rcmd 不认旧 tid、返回同质推荐 → 子分区不刷新。回退到 `dynamic/region?rid=<旧子tid>`（实测各子分区返回不同内容：rid=24→MAD、rid=25→MMD，零重叠），切换子分区即刷新。

### 已知待验（真机，需登录态）
- `feed/rcmd?from_region=<新父tid>` 能否在登录态返回新鲜推荐流需真机确认；未登录已自动回退 `dynamic/region`（确定性流，不阻断）。
- 番剧/生活主分区按 OK 内容可能不变（走 `dynamic/region` 旧流，BV 新体系无对应 UGC 主分类）。

## v1.0.13-alpha.2

### 修复
- **UGC 子分区再次点击不刷新**：`selectSubPartition` 有个去重 guard——点击的子分区 tid 等于当前已选 tid 时直接 `return`，导致再次点同一个子分区胶囊不重载、视频不变。主分区 `onSectionSelected` 走 `selectSection(forceRefresh=true)` 无此 guard，子分区行为不一致。删掉 guard，子分区胶囊点击（含重复点击当前子分区）始终强制刷新，与主分区对齐。
- **UGC 子分区内容文不对题**：alpha.1 用子分区 tid 作 `rid` 请求 `/x/web-interface/dynamic/region`，但该接口对子分区 tid 过滤不可靠——实测 `rid=25`（标"动画→MMD·3D"）返回的是游戏区内容，`rid=47`/`rid=17` 也与标签对不上。改对齐 BV 源码：子分区走 `/x/web-interface/region/feed/rcmd?from_region=<tid>`（带 SESSDATA，无需 WBI 签名），主分区仍走 `dynamic/region`。
  - `BiliApiEndpoints` 新增 `RegionFeedRcmd`；`fromArchive` 兼容两种 archive 形状（`owner`/`pic` 与 `author`/`cover`，feed/rcmd 用后者且 author 无 face）。
  - `HomeVideoRepository` 新增 `getRegionFeedRcmdVideos`：子分区（`regionTidOverride != null`）走该接口；未登录（feed/rcmd 会 -400）或接口异常时回退 `dynamic/region`，不阻断浏览。

### 已知待验（真机）
- `region/feed/rcmd` 需登录态，未登录已自动回退 `dynamic/region`（仍是文不对题的旧行为，但不阻断）。登录态下子分区内容是否准确需真机确认。

## v1.0.13-alpha.1

### 新增
- **UGC 子分区导航**：每个 UGC 主分区（动画/番剧/影视/游戏/知识/科技/音乐/舞蹈/生活/美食）下方新增一行子分区胶囊，移植自 BV 源码 `PartitionUtil` 传统 tid 树——如动画→MAD·AMV / MMD·3D / 短片·手书·配音 / 手办·模玩 / 特摄 / 动漫杂谈 / 综合；游戏→单机 / 电竞 / 手游 / 网游 / 桌游棋牌 / GMV / 音游 / Mugen，共 10 主分区 63 子分区。选中子分区后用子分区 tid 作为 `rid` 请求 `/x/web-interface/dynamic/region`，复用现有接口、无需新端点。按主分区记忆选中子分区，切回时恢复；D-pad 焦点衔接：子分区行 ↑→主 Tab、↓→网格，网格 ↑→子分区行（Recommend/热门等无子分区时回退主 Tab）。新增 64 条 `ugc_sub_*` 字符串资源（简体 + 繁体两套）。

### 已知待验（真机）
- `dynamic/region?rid=<子tid>` 返回 `data.archives` 结构需真机确认；若某子分区显示空/报错，后续换 `/x/web-interface/region/dynamic` 或 app 端 `/x/v2/region/dynamic` 兜底（仅接口层一处改动，逻辑层不动）。

## v1.0.13

### 修复
- **PGC（番剧）播放黑屏——彻底修复**：经过 alpha.9–alpha.19 多轮调试，定位到真根因：PGC 季详情接口 `/pgc/view/web/season` 的 payload 包在根级 `result` 字段下（和 PGC playurl 一样，BV 的 `BiliResponse.getResponseData()` 也是 `data?:result`），而 `getSeasonInfo`/`getPgcVideoMetadata` 只读 `data` → 拿到 null → 季详情加载失败 → PGC 卡在季详情页「正在加载…」根本进不了播放器。修：两处都改成读 `data ?: result`。PGC 现在能正常加载季详情、进播放器播放。

### 改进（本轮 alpha 累积）
- **实时日志**（alpha.11/14）：常驻滚动 logcat 写 `logs_live.log`，上限 10MB 丢弃最旧，每行 flush；设置→日志可查看/分享，播放器诊断叠层实时滚动。
- **播放器日志叠层**（alpha.13–16）：开关开启后，播放器/季详情页把实时日志 + 内存态（state/step/请求信息）盖在画面上，黑屏时直接排查，不用退出。
- **PGC 起播超时兜底**（alpha.14）：HTTP 客户端加 `callTimeout(15s)`，launch 协程包 `withTimeoutOrNull(30s)`，季详情 fetch 包 `withTimeoutOrNull(20s)`——不再无限卡 Loading，超时跳 Failed。
- **PGC playurl SDR fnval + 排除杜比视界**（alpha.10）：PGC 用 SDR fnval，轨道选择排除 dvhe/未知 codec。
- **PGC playurl 用 MergingMediaSource**（alpha.12）：对齐 BV，绕开合成 DASH MPD 的 SegmentBase 拼接风险。
- **网络层 Referer 对齐 BV**（alpha.18）：`https://www.bilibili.com`（去尾斜杠）。

### 调试基础设施
- 季详情/播放器 fetch 异常不再被吞，错误码显示在叠层 `ERR:` 行 + 失败页。
- PGC 季详情 fetch 区分「真超时」/「返回空(data=null)」/「异常」三种失败模式。

## v1.0.12-alpha.19

### 修复
- **PGC 季详情「返回空」**：alpha.18 报 `ERR: 返回空(data=null)`——请求成功（code=0）但 `data` 字段为 null。PGC playurl 的 payload 在根级 `result` 下（alpha.9 已修 `data?:result`），BV 的 `getResponseData()` 也是 `data?:result`。PGC 季详情 `/pgc/view/web/season` 同样把 payload 包在 `result` 下，而 `getSeasonInfo` 只读 `data` → 拿不到 → 返回空。`getSeasonInfo` 和 `getPgcVideoMetadata`（PlayerScreen 的 PGC metadata，同问题）都改成读 `data ?: result`。

## v1.0.12-alpha.18

### 改进
- **PGC 季详情对齐 BV + 抓原始响应定论**：alpha.17 加的 WBI 签名偏离了 BV（BV 的 `/pgc/view/web/season` 不签名），回退——PGC 季详情和 PGC playurl 恢复不签名（对齐 BV `getWebSeasonInfo`），Referer 去尾斜杠（`https://www.bilibili.com`）。同时加定论性诊断：`getSeasonInfo` 打印原始响应（code/message/hasData/keys，进实时日志→叠层）；修标签 bug——区分「真超时(HTTP 挂死)」「返回空(data=null)」「异常(含 code)」，不再把返回 null 误标成超时。装上后看叠层 `ERR:` 行 + 日志 `pgc season raw:` 行即可定论 BV 对齐请求的真实结果。

## v1.0.12-alpha.17

### 修复
- **PGC 季详情 fetch 失败（真根因）**：alpha.16 诊断叠层揭示 PGC 卡在 PgcSeasonScreen 的季详情 fetch（`/pgc/view/web/season`，state=失败），根本没到 PlayerScreen——前几轮 PlayerScreen 修复全用不上。猜测 2026-01 B 站关停 API 文档后收紧了 PGC 端点（可能要 w_rid）。给 PGC 季详情和 PGC playurl 都加了 WBI 签名（与 UGC 一致）；并捕获 fetch 异常（BiliApiCodeException 含 code、HTTP status）显示在诊断叠层 `ERR:` 行和失败页，不再被吞。仍失败时能看到真实错误码。

## v1.0.12-alpha.16

### 改进
- **PGC 叠层不渲染决定性诊断**：用户确认 UGC 有叠层、PGC 完全没叠层（连彩色字也没有），同一开关同一 PlayerScreen 按代码不可能——需设备级诊断。PlayerScreen 加内联 DEBUG 行（亮粉底黑字，toggle 开时显示 `isPgc/epId/seasonId/cid/state/step`，放早子节点不走叠层子组合）；叠层背景改不透明深灰+亮粉边框（排除黑底不可见）。PgcSeasonScreen fetch 包 `withTimeoutOrNull(20s)`（不再永远「正在加载…」）并加季详情诊断叠层。用于定位 PGC 到底卡在哪、为何叠层不显示。

## v1.0.12-alpha.15

### 改进
- **PGC 日志叠层改为内存态诊断**：用户反馈 UGC 叠层有日志、PGC 叠层只有「正在加载」没日志。说明叠层/logcat/写入都正常，PGC 是 launch 协程没跑到日志或没运行，靠 logcat 文件分不清。叠层头部现在直接显示**内存态**（不依赖 logcat）：「● 叠层工作中」+ 请求信息（isPgc/epId/seasonId/cid/bvid）+ 状态（Loading/Failed/Ready）+ 当前步骤（metadata/playurl/cdn/prepare）+ 实时日志大小，下方仍保留日志尾部。PGC 卡死时叠层一定有内容，一眼看出卡在哪步、是否识别为 PGC。

## v1.0.12-alpha.14

### 修复
- **PGC 卡在「正在加载」+ 日志叠层不显示**：用户反馈 alpha.13 PGC 黑屏实际是卡在 Loading（不是 Ready），launch 协程没走到 `prepare()`；且打开日志叠层后没有日志。根因：HTTP 客户端只有 readTimeout（按每次 read 重置），慢 drip 服务器能让 `getPlaybackInfo` 永不返回 → 无限 Loading；实时日志写者每 20 行才 flush，稀疏日志不落盘 → 叠层读空；叠层还被 `request.isPgc` 门控。修：
  - HTTP 客户端加 `callTimeout(15s)`，cap 整个调用含 body 读取，治慢 drip 无限挂死。
  - launch 协程包 `withTimeoutOrNull(30s)`，超时跳 Failed「起播超时」，不再永远 Loading；加 `launch step: metadata/playurl/cdn/prepare` 步骤日志，叠层能看出卡哪步。
  - 实时日志改为每行 flush（size 检查每 200 行），稀疏日志立即落盘。
  - 日志叠层去掉 `request.isPgc` 门控（开关开即显示），空时显示提示行。

## v1.0.12-alpha.13

### 新增
- **PGC 黑屏日志叠层**：PGC 黑屏后退出看日志太痛苦，新增「播放器日志叠层」开关（设置→系统设置，列表最底部，默认关）。打开后 PGC 播放时把实时日志尾部直接盖在黑屏上——半透明黑底、等宽小字、每秒刷新、自动滚到底、ERROR/WARN/DEBUG 分色，直接在黑屏上看到 `playurl pgc selected=xxx` 和 ExoPlayer 错误，不用退出。仅 PGC 显示，UGC 不受影响；复用 alpha.11 的常驻实时日志。

## v1.0.12-alpha.12

### 修复
- **PGC 番剧播放黑屏（第三轮）**：联网调研 + BV 源码对比发现，BV 用 `MergingMediaSource(ProgressiveMediaSource×2)` 直接喂视频+音频两条 progressive fMP4 流，而本应用自己拼合成 DASH MPD（`<SegmentBase indexRange/Initialization>`）喂 `DashMediaSource`。UGC 上合成 MPD 跑得通，但 PGC 一直黑屏（状态停 `Ready`、ExoPlayer 不出帧、无 `onPlayerError`），疑似合成 MPD 对 PGC 某字段拼错。现 PGC 改为与 BV 一致的 `MergingMediaSource`，绕开整层合成 MPD 风险；UGC 维持原合成 MPD 不动。

## v1.0.12-alpha.11

### 新增
- **实时日志（常驻滚动）**：应用启动即常驻一个 logcat 进程，持续把日志写入 `logs_live.log`，**上限 10MB，超过自动裁掉最旧部分**（保留较新 9MB，按行对齐）。跨重启累积——PGC 黑屏等问题发生时日志已在盘上，不用再手动开始/停止录制，也不用在黑屏里挣扎退出。日志列表顶部可见「实时」条目（带实时大小），点开查看（大文件只读尾部 2MB 防 OOM，底部「刷新」按钮可重读尾部），可分享。便于定位 PGC 黑屏等问题的 `BiliMT:Playback` 日志。

## v1.0.12-alpha.10

### 修复
- **PGC 番剧播放黑屏（第二轮）**：alpha.9 修复了 playurl 响应解析，但 PGC 仍黑屏。进一步分析返回键行为确认状态停在 `Ready`（parse 成功、`prepare()` 已跑）但 ExoPlayer 不出视频帧。根因：PGC 固定 `fnval=4048` 会请求 HDR/杜比视界/8K/杜比音轨，服务端给大会员返回顶部 HDR/杜比视界清晰度，而设备（编解码探测只覆盖 avc/hevc/av01）渲染不了 → 黑屏无 `onPlayerError`；`isH265` 又把杜比视界 `dvhe` 误判成可解 H.265，manifest 只剩不可解视频轨道。现 PGC 改用与 UGC 一致的 SDR `fnval`（仅 DASH+H265+AV1），并排除杜比视界/未知 codec 轨道。同时增强 `playurl` 日志，打印选中轨道原始 codec，便于后续定位。

## v1.0.12-alpha.9

### 修复
- **PGC 番剧播放黑屏**：对比 BV 源码定位到根因——`/pgc/player/web/playurl` v1 的响应把整个 payload 包在根级 `result` 对象下（其内层 `result` 才是 `"suee"`），而非 UGC 接口的 `data`。原代码固定从 `data` 读取，PGC 响应无 `data` 字段，导致 dash 轨道永远为空、起播直接进入 `Failed(empty_tracks)` 黑屏。现对齐 BV 的 `BiliResponse.getResponseData()`，改为 `data ?: result` 回退，PGC 终于能正确取到 dash 视音频轨道并起播。同时放宽 `result` 字符串校验，接受 `suee`/`success` 两种取值，避免误杀。

## v1.0.12-alpha.8

### 改进
- **日志查看器内嵌到右侧面板**：选中日志文件后不再弹窗，而是直接在设置页右侧面板里显示内容，退出查看即返回文件列表，焦点保持在设置页内。
- **修复日志查看焦点问题**：日志内容区域可独立响应遥控器上下键翻行、PageUp/PageDown 翻页、Home/End 跳顶底，按左键可返回「返回」按钮。

## v1.0.12-alpha.7

### 修复
- **撤销跨线程 prepare**：alpha.6 把 `player.prepare()` 放到 `Dispatchers.IO` 执行，但 ExoPlayer 在主线程创建，跨线程调用会抛 `IllegalStateException`，导致 UGC 和 PGC 都起播失败。已还原为在主线程调用 `prepare()`，同时保留强退逻辑。
- **保留 PGC 黑屏强制退出**：起播过程中（`PlayerScreenState.Loading`）按返回键仍会强制取消起播协程、释放 ExoPlayer 并退出播放页。

## v1.0.12-alpha.6

### 修复
- **PGC 黑屏时无法返回**：起播过程中（`PlayerScreenState.Loading`）按返回键现在会强制取消起播协程、释放 ExoPlayer 并退出播放页，避免 PGC playurl/prepare 阻塞导致 UI 卡死、返回无响应。
- **ExoPlayer prepare 不再阻塞主线程**：`player.prepare()` 改到 `Dispatchers.IO` 执行，降低起播阶段 UI 假死概率。

## v1.0.12-alpha.5

### 新增 / 改进
- **日志录制模式**：手动日志从「一键导出」改为「开始录制 → 复现问题 → 停止录制」的完整流程，能抓到问题发生时的实时 logcat。
- **日志查看器强化**：
  - 完整加载整个日志文件，不再限制 500 行预览。
  - 显示当前可见行范围（如 `当前 1-18 / 12345 行`）。
  - 支持 PageUp/PageDown 翻页（每次 20 行），Home/End 跳转顶部/底部。
  - 按日志级别高亮：ERROR/FATAL 粉色、WARN 绿色、DEBUG 浅灰。

## v1.0.12-alpha.4

### 新增
- **日志系统**：对比 BV 源码引入 `kotlin-logging` + `slf4j-handroid`，崩溃时自动抓取 logcat 保存到应用私有目录。
- **设置页日志入口**：设置页 → 系统设置 → 日志，可列出崩溃/手动日志文件，查看前 500 行内容，或通过系统分享导出日志文件。
- **手动导出日志**：点击「导出当前日志」可即时生成一份 `logs_manual_*.log` 并刷新列表。

## v1.0.12-alpha.3

### 修复
- **PGC 播放仍黑屏（第二轮）**：进一步对齐 BV 的 PGC playurl 请求参数。
  - `fnval` 改为固定 `4048`（BV 同款完整 DASH 能力集），替代原先按 codec 能力动态计算的 `16/80/1040/1104`。
  - 移除 BiliTVNative 自己添加的 `from_client=bilibili-web` 和 `support_multi_audio=true`；这两个参数可能让 B站返回 Web DRM 流或当前播放器无法处理的格式。
  - 增加 PGC 响应关键字段校验：`type` 非 DASH、`is_drm=true`、`is_preview=1`、`result` 异常时直接给出明确错误提示，不再黑屏 silent fail。
  - 增强 ExoPlayer 错误/状态日志，便于后续真机抓 logcat 定位。

## v1.0.12-alpha.2

### 修复
- **PGC 播放黑屏**：对比 BV 源码后发现 `/pgc/player/web/playurl` 的 Cookie 缺少 `DedeUserID`，导致服务端身份校验不通过、返回不可播放的流。`BiliPlaybackHeaders` 现在会携带 `mid`，PGC playurl 的 Cookie 与 UGC 对齐为 `SESSDATA=xxx;DedeUserID=xxx`。

## v1.0.12-alpha.1

### 新增完整 PGC（番剧/影视）
参照 BV 源码把 PGC 剧集体系移植到 BiliTVNative（按本项目 Compose 状态式导航 + Repository 范式改写，不引入 Koin/Activity）。五层闭环：

- **侧边栏「影视」入口**：`AppDestination.Pgc` + 图标 + 文案。
- **6 分区 feed**：番剧/国创/电影/纪录片/电视剧/综艺。番剧国创走 v3 feed（`/pgc/page/web/v3/feed`），其余走 v1 feed；cursor 分页，焦点近底自动加载。
- **索引筛选页**：6 列网格 + 13 维度筛选（排序/方向/类型/配音/地区/状态/版权/付费/季度/出品/年份/发布时间/风格），各维度按分区可用子集暴露；筛选变化清空重载。
- **季详情页**：封面/标题/类型/简介 + 正片分集行 + 花絮 section 行 + 多季选择器（切季重载）。
- **剧集播放**：选集构造 `PlaybackRequest(epId/cid/seasonId/bvid/aid)`，`PlaybackRepository` 走 `/pgc/player/web/playurl`（带 referer/SESSDATA），dash 解析复用；播放器选集面板列出全 ep、连播到下集、带 progress 续播起点。

数据层：`PgcType/PgcSummary/PgcEpisode/PgcSeason/PgcSection` 模型 + `PgcVideoRepository`（getFeed/getSeasonInfo/getPgcIndex）+ `PgcMappers` 手写 JSON 解析。

### v1 简化 / 已知项
- 未做顶部 carousel 轮播、追番/关注、新番时间线表。
- PGC 进度上报心跳暂用 UGC heartbeat（服务端可能不记 PGC 进度，本地 progressStore 仍记）。
- PGC feed/index/season 未 WBI 签名（对齐 BV 旧版）；若 B 站现强制 w_rid，运行时会报错，需真机验证后补签名。
- 焦点/滚动润色较朴素（用 LazyVerticalGrid，未用 TvVideoGrid 行滚动动画）。
- 云编译只保证构建通过，运行时正确性需真机验证。

## v1.0.12

汇总 v1.0.11-alpha.1 ~ alpha.7 的改动，发布为稳定版 v1.0.12。

### 首页分区设置（新功能）
- 设置页新增「首页分区」入口：点击 toggle 显示/隐藏右侧面板；聚焦普通设置项时左侧列表占满全宽，面板不再常驻占用半屏。
- 分区面板改为竖向列表 + ▲▼ 上移/下移按钮，可自定义首页 tab 的**显隐**与**排列顺序**，顺序持久化到 DataStore（`home_sections_order`），首页 tab 按自定义顺序显示。
- 顺序与显隐数据分离，读取时对持久化顺序补齐缺失分区，前向兼容新增分区。

### 播放器退出卡死修复
- 播放中按返回，第二次退不出去：退出被 `saveProgress`（DataStore IO 异常未兜底）和 `reportProgress`（网络心跳）阻塞。改为先本地保存（`runCatching` 兜底）→ 立即退出 → 网络上报 best-effort 放退出之后，退出不再被存储/网络卡住。

### CDN 测速与起播优化
- **Auto 起播变快**：测速增加 `earlyReturn` 模式，第一个够好的候选一回来就返回，不再被死链 backupUrl 拖到 ~1s；`probeUrl` 改 `enqueue` + 可取消；video/audio 选优并行。
- **测速更诚实**：对话框顶部摘要行区分「接口耗时」与「CDN 首字节」，数字与体感对得上；测速走 `CdnSelector` 实际候选过滤，测完预热缓存，重开同视频跳过重复测速。
- 测速候选按 host 去重，结果行显示 CDN 友好名（官方/阿里云/Akamai/华为云）。
- 起播紧预算（连接/读取 1s、整体 1.5s），playurl 90s 内存缓存，复用同 bvid 元数据。
- 修测速编译错误（`CompletableDeferred` 桥接 OkHttp `enqueue`）、读超时误用 `ConnectTimeoutMs` 等参数问题。

### 首页缩略图
- 封面 `ContentScale` 改 `FillBounds`（对齐 BV 源码 + B 站 `1c` 后缀），不再切边丢画面。

### 设置页交互
- 「首页分区」「关于」入口改为 toggle：点一下显示右侧面板、再点一下隐藏。

### 安装包
- `BiliMT-v1.0.12-arm64-v8a.apk`
- `BiliMT-v1.0.12-armeabi-v7a.apk`

---

以下为 1.0.11 测试版（alpha）的逐版记录：

## v1.0.11-alpha.7

### 设置入口 toggle + 首页分区点击修复
- **首页分区入口点击无反应修复**:上一版入口行的 `onClick` 是空,确认键/点击啥也不做(关于入口因有 `onAboutSelected` 才正常)。补上 `onHomeSectionsSelected` 回调,行为与关于对齐。
- **两个入口都改成 toggle**:「首页分区」和「关于」入口现在点一下显示右侧面板、再点一下隐藏,由 `rightPanel` 状态承载,本次设置页会话内持久。
- 叠加上一版「右侧面板无内容时不占位」,现在聚焦普通设置项时左侧列表占满全宽,点击入口才出现对应面板。

## v1.0.11-alpha.6

### 右侧面板无内容时不占位
上一版把分区面板改成按需进入后,聚焦普通设置项时右侧虽然不显示面板,但仍保留一个空 `Box` 占着 `weight(1f)`,导致右侧半屏留白、左侧列表被压成一半宽度。改为 `None` 时不渲染任何子项,左侧设置列表占满全宽;聚焦「首页分区」/「关于」时面板才出现、左侧收窄。顺手清理了不再使用的 `SettingsEmptyRightPanel`。

## v1.0.11-alpha.5

### 首页分区设置：按需进入 + 可排序
- **分区面板不再常驻右侧**：原来调任何设置项时右侧都占着分区面板,既浪费空间又易误触。改为左侧列表新增「首页分区」入口,聚焦/点击它时右侧才显示分区面板,其余设置项右侧留空。
- **支持调整分区顺序**：分区面板由固定网格改为竖向列表,每行 = 分区名(确认键切显隐)+ ▲/▼ 上移下移按钮,边界行按钮自动禁用。顺序写入 DataStore(`home_sections_order`),首页 tab 按自定义顺序过滤显示。
- 数据模型分离「顺序」与「显隐」:`homeSectionsOrder: List<HomeSection>` 控制排列,`enabledHomeSections: Set<HomeSection>` 控制显隐,二者独立。读取时对持久化顺序补齐缺失分区,前向兼容新增分区。

## v1.0.11-alpha.4

### 修复播放中无法退出
播放时按返回,第一次弹"再按一次退出"toast,第二次却退不出去。根因是 `finishPlayer` 把 `onBack()` 排在 `saveAndReportProgressNow()` 之后:
- `saveProgressNow()` → DataStore.edit 可抛 `IOException`(未兜底),一旦抛出 `onBack()` 永不执行,PlayerScreen 卡死;
- `reportProgressNow()` → 网络心跳 POST,suspend 到完成/超时,慢网下 `onBack()` 被卡数秒,看起来"没反应"。

改为:先本地 save(快、`runCatching` 兜底)→ 立即 `onBack()` 退出 → 网络 `reportProgressNow` best-effort 放退出之后。退出不再被存储/网络阻塞,进度仍保存,完成态上报逻辑保留。

## v1.0.11-alpha.3

### 测速修复与展示改进
- 修编译错误(`suspendCancellableCoroutine` 的 `resume`/`resumeWithException` 在本 kotlinx 版本报错):`probeUrl` 改用 `CompletableDeferred` 桥接 OkHttp `enqueue`;`measureAll` 的 `async` 用 `coroutineScope` 包住;`measureEarly` 用显式 `jobs.forEach { it.cancel() }` 取代 `cancelChildren`。
- 测速候选按 **host 去重**(原来按完整 URL 去重),每 host 只测一个代表 URL,不再出现同一 host 多行结果不同;6 个探测名额也能覆盖更多不同 host。
- `applyMeasurements` 改按 host 匹配,host 去重后缓存预热仍然正确(取本 track 自己在该 host 的签名 URL)。
- 测速结果行显示从原始 host 换成 **CDN 友好名**(官方/阿里云/Akamai/华为云),未识别 host 回退原始 host。

## v1.0.11-alpha.2

### Auto 起播变快
上一版把起播路径测速总超时从 4s 降到 1.5s,但没根治"Auto 慢于指定 CDN"——根因是测速等**所有**候选 probe 结束才返回,winner 83ms 回来了也会被一个死链 backupUrl 拖到 ~1s。

- `CdnSpeedTester.measure` 增加 `earlyReturn` 模式:第一个够好的候选一回来就返回,顺带带走已完成的作 fallback,其余取消。起播路径用 `earlyReturn=true`,设置页测速仍看完整排名。
- `probeUrl` 从阻塞 `execute()` 改成 `enqueue` + `suspendCancellableCoroutine`,协程取消现在真能 abort 卡在 connect 的死链探测。
- video/audio 两次 CDN 选优改并行(`coroutineScope { async }`),省一次串行等待。

### 首页缩略图不再切边
对照 BV 源码:BV 用 `ContentScale.FillBounds` + B 站 `1c` URL 后缀(服务端已强制裁成精确 16:9),不丢画面;这边原来用 `Crop` + `Precision.INEXACT`,解码位图比例略偏就被切边。封面改为 `FillBounds`,与 BV 一致。

## v1.0.11-alpha.1

### 测速更诚实
设置页测速显示 23ms，但实际打开仍要好几秒——因为 23ms 只量了 CDN 最后一跳的首字节时间，真正吃掉几秒的环节（接口解析、起播选优、缓冲）测速根本没覆盖。本轮让数字与体感对得上：

- 对话框顶部新增摘要行 `接口解析 Xms · CDN 首字节 Yms（接口耗时不在 CDN 测速内）`，直接告诉用户几秒花在接口、23ms 花在 CDN。
- 测速改走 `CdnSelector` 的候选过滤（`CdnRewriter` + `isEligibleCandidate`），与播放器实际使用的主机完全一致；非 Auto 偏好下不再给「播放器根本不会用」的主机打高分。
- 测速完成后预热 `CdnSelector` 缓存，下一次打开同一视频直接命中缓存，跳过起播路径的重复测速。

### 减少打开耗时
- 起播路径 Auto 选优改用紧预算（连接/读取 1s，整体 1.5s），坏 CDN 不再阻塞首帧 4s，测空自动回退 `baseUrl + backupUrls`。
- `PlaybackRepository` 为 playurl 解析增加 90s 内存缓存，90s 内重开同一视频或剧集来回切，跳过 1–3s 的 `api.bilibili.com` 往返。
- 起播时若已有同 bvid 元数据则复用，重试 / 切码率 / 切清晰度时省掉 view 接口往返。

### 修复
- `CdnSpeedTester` 读取超时误用 `ConnectTimeoutMs`（2s）而非声明的 `ProbeReadTimeoutMs`（3s），已修正并参数化为 `MeasureOptions.Dialog / Open` 两套预设。

## v1.0.10

验证 release notes 自动生成

## v1.0.9

### 新增与改进

#### 1. 设置内网络测速
设置页 → 播放设置新增"网络测速"入口，复用播放时的 CDN 测速能力：

- 以最后一次播放的视频为样本，取其 playurl 返回的 CDN 候选（baseUrl + backupUrls）并发探测。
- 弹窗按综合评分降序列出节点：排名、节点 host、首字节时间、下载速度（自动切换 KB/s 与 MB/s），并给最快的节点加"最快"标记与高亮。
- 无播放历史时提示先播放任意视频；测速失败或超时给出对应提示。
- 测出最快节点后，可在"CDN 线路"中手动指定对应提供商。

#### 2. CDN 自动测速择优化
`CdnSpeedTester` 的探测策略调整，"自动"模式选 CDN 更快更稳：

- 每个候选独立超时（连接/读取 2s），不再用单一总超时卡住所有候选。
- 只采纳 2s 内返回的结果，整体 4s 超时兜底；移除固定延迟，直接 await 结果。
- 降低读取超时到 2s，避免单个慢节点拖累整体择优。

### 修复
- 搜索、动态、历史视频卡片点击 UP 主头像无响应：补全 `onOwnerSelected` 回调链（Search / Dynamic / History / SearchResultsView）。
- UP 主主页"取消关注"确认对话框按钮焦点穿透：为对话框按钮加 `focusProperties`，避免焦点落到下层。

### CI / 发布流程
- 改用 `gh release create --generate-notes` 生成 GitHub Release 说明，替换原来的 softprops action，发布说明更可控。

### 安装包
- `BiliMT-v1.0.9-armeabi-v7a.apk`
- `BiliMT-v1.0.9-arm64-v8a.apk`

## v1.0.8

### 新增与改进

#### 1. UP 主主页
- 播放器侧栏「查看主页」入口;首页视频卡片长按 OK / 点击 UP 主名字进入。
- 展示头像、昵称、等级、签名、粉丝/关注数、认证信息。
- 「最新发布」/「最热门」排序,自动翻页。
- 关注 / 取消关注(含确认弹窗)。

### 修复
- CDN 自动切换卡死问题。
- 空间接口风控适配。

### CI / 发布流程
- 发布前自动清理孤立 tag。
- 稳定版发布时自动删除旧 prerelease。

## v1.0.7

### 新增与改进

#### 1. 应用内更新（程序更新）
设置页 → 系统设置 → 程序更新现在提供完整的手动更新流程：

- **当前版本展示**：单独显示已安装的版本号和 versionCode。
- **检查更新**：手动向 GitHub Releases 发起一次版本检查，支持稳定版（`v1.0.x`）和 prerelease（`v1.0.x-alpha.N`）。
- **下载更新**：发现新版本后可直接下载 APK；下载过程显示实时进度（百分比 + 已下载 / 总大小）。
- **安装并重启**：下载完成后通过系统安装器安装 APK。
- **查看发布说明**：在浏览器中打开对应 GitHub Release 页面。
- 版本对比统一走 tag 解析后的 `versionCode`，稳定版和 prerelease 都能正确识别新旧关系。

#### 2. CI / 发布流程改进
- **按 ABI 构建 release APK**：打 tag 时 CI 会为 `armeabi-v7a` 和 `arm64-v8a` 分别产出 APK，文件名包含 ABI 后缀。
- **版本号由 tag 自动推导**：无需手动修改 `app/build.gradle.kts`，CI 直接传入 `github.ref_name`。
- **发布稳定版时清理旧 prerelease**：推送非 prerelease tag（如 `v1.0.7`）时，会自动删除所有 versionCode 更低的 prerelease Release，只保留历史稳定版。
- **仅保留最近 10 次 workflow run**：每次构建完成后自动删除更早的 Actions 运行记录，避免仓库运行记录无限增长。

#### 3. 关于页面
- 项目地址二维码和链接从上游 `Hyper-Beast/BiliTVNative` 切换到当前项目 `urbanescavenger/BiliMT`。
- 三种语言的简介文案同步更新，明确 BiliMT 基于 BiliTVNative 继续开发，并涵盖播放稳定性、焦点、弹幕、视觉质感与多硬件档位流畅度等关注点。

### 安装包
- `BiliMT-v1.0.7-armeabi-v7a.apk`
- `BiliMT-v1.0.7-arm64-v8a.apk`
