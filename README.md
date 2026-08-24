# BiliMT

BiliMT 是一个原生 B 站 + YouTube 双平台客户端实验项目，基于 [BiliTVNative](https://github.com/Hyper-Beast/BiliTVNative) 1.0.0 开发，使用 Kotlin、Jetpack Compose 和 Media3 重写观看体验。同一个 APK 同时适配 Android TV 与安卓手机：TV 端用 Compose for TV 和遥控器焦点系统，手机端用触屏交互外壳，共享同一套网络、播放、账号、设置和存储引擎。

内容覆盖 B 站（推荐/热门/分区、搜索、动态、历史、收藏、追番、直播、下载、IPTV）与 YouTube（搜索、热门、关注流、频道、多播放列表、高清 SABR 播放、评论、相关视频），双平台均可播放。YouTube 部分基于 [LibreTube](https://github.com/libre-tube/libretube) 的取流与 SABR 播放方案（含其 NewPipeExtractor fork）独立重写实现。播放器基于 Media3 ExoPlayer，支持 DASH、弹幕、快进预览、空降助手、多语言配音音轨切换、默认画质、默认播放倍速、字幕与后台播放。界面支持 6 档语言（简中 / 港繁 / 台繁 / English / Español / Português），拉丁语系数字本地化（K/M/B）与相对时间。

电视端重点不是做一个极简壳，而是在电视设备上尽量平衡几个实际问题：播放稳定性、遥控器焦点可控性、弹幕性能、主页视觉质感，以及不同硬件档位下的流畅度。

## 截图

<img width="2048" height="1104" alt="image" src="https://github.com/user-attachments/assets/dd043188-5cb2-422b-8905-320ddac69473" />

<img width="2244" height="1216" alt="image" src="https://github.com/user-attachments/assets/9ecc0460-011f-4618-880c-46b7ecb368c8" />

<img width="2238" height="1218" alt="image" src="https://github.com/user-attachments/assets/8bc33ebd-013f-4041-b64b-2e377ee685ee" />

<img width="2248" height="1220" alt="image" src="https://github.com/user-attachments/assets/e2af9862-f306-413b-bf07-0b8a5e8686c8" />

<img width="2248" height="1212" alt="image" src="https://github.com/user-attachments/assets/758dd11d-4c6f-485a-ac15-a3b0811ee122" />

<img width="2242" height="1216" alt="image" src="https://github.com/user-attachments/assets/2b9832f6-948b-4bbf-a14e-bddd7679b388" />

<img width="2230" height="1232" alt="image" src="https://github.com/user-attachments/assets/1589bc8c-6427-43b7-9f56-3e963f6486fb" />



## 主要功能

- 首页推荐、热门、分区内容流；自适应底栏导航外壳和触屏首页分区网格。
- 搜索键盘、搜索建议、搜索历史、搜索结果排序和分页。
- 动态关注 feed、历史记录和账号登录（TV 二维码、手机短信 WebView）。
- 手机端"动态"tab 四子 tab：动态关注 feed / 历史 / 收藏（收藏夹切换）/ 追番（番剧·影视 + 想看·在看·看过筛选）。
- Media3 点播播放器，支持 DASH 播放、进度保存和返回焦点恢复。
- 默认画质、解码器偏好、倍速、弹幕、快进预览雪碧图；YouTube 另有独立默认画质、默认播放倍速、字幕与多语言配音音轨切换。
- CDN 自动测速择优：选择“自动”时会对 B 站返回的候选 CDN 并发测速（首字节时间 + 64 KB 下载吞吐），过滤 mcdn、szbdyd、裸 IP 等不良候选，并按区域缓存 5 分钟，避免每次播放都重复探测。
- 设置内网络测速：以最后一次播放的视频测各 CDN 节点速度，弹窗列出首字节/速度排名并标记最快节点，便于手动挑选 CDN 线路。
- 字节跳动 DanmakuRenderEngine 原生弹幕渲染，避免把高频弹幕做成 Compose 节点。
- 空降助手，支持跳过片段提示并在进度条上标出跳过范围。
- 自动播放下一集、自动播放相关推荐、播放完成后自动退出。
- 播放退出二次确认、应用退出二次确认。
- 触屏播放器：全屏横屏沉浸、画质/倍速/弹幕设置弹窗、分 P 选集侧栏、UP 主空间入口、空降助手、推荐视频切播、手势（点中央暂停/长按 2x/横拖 seek）。
- 追番进季详情选集：封面/简介/同系列季切换/正片+花絮分集，选集开 PGC 播放。
- 后台播放：前台 service + MediaStyle 通知（封面、播放/暂停、锁屏控件），显式 startForeground 保活。
- UP 主空间页：头像、签名、关注和投稿网格。
- 状态栏透明 + 浅色图标、内容页下滑刷新、底栏重复点推荐触发刷新 + 滚顶。
- 简体中文、香港繁体、台湾繁体界面和动态标题转换。
- Android TV launcher 图标和 TV 横幅，手机与 TV 双桌面入口。
- 应用内更新：从 GitHub Releases 手动检查、下载并安装新版 APK。
- 直播：TV + 移动端直播播放（HLS/FLV 取流、画质切换、-352 风控）与直播分区浏览。
- IPTV：设置页源配置（URL/账号/密码 + 连通性校验 + 自动补 https）、直播页 IPTV tab、TV 端 TVBox 式频道列表侧栏（确认键开关/左右切台/上下切线路）、断流自动切镜像源、强制 IPv4 明文数据源（302 重定向按客户端 IP 族选节点）、频道缩略图拉流截帧（SurfaceTexture+EGL 离屏，懒加载 + 会话级缓存）。TV + 移动端双端。
- 多语言：界面 6 档语言（简中 / 港繁 / 台繁 / English / Español / Português），拉丁语系数字本地化（K/M/B）与相对时间本地化；播放器/下载等硬编码中文已收口进翻译。
- 下载管理：B 站视频下载 + 下载任务管理（批量删除）。
- YouTube 内容：搜索/热门来源切换、动态关注流合并（真 continuation 分页逐页逼近最早）、频道管理、频道页最新/最热排序、多播放列表（批量移除）、高清 SABR 播放（多档清晰度）、播放优先级设置（SABR 优先 / DASH 优先）、多语言配音音轨切换、YouTube 默认画质、默认播放倍速、字幕、评论、相关视频、播放历史续播、WebDAV 备份/还原。
- 在线播放命中缓存播本地源：在线刷到已下载/缓存的视频直接播本地文件（简介/评论/弹幕仍走在线），清晰度只读、进度与在线互通，B 站 + YouTube 均适用。

## UI 与视觉

应用提供 4 种主页主题：

- 默认粉
- 深黑
- 高级灰
- 蓝灰

视觉性能模式分为 3 档：

- 流畅：面向低端电视，关闭重动画、流光、阴影、封面预取和图片内存缓存，缩略图使用较低尺寸与 RGB_565。
- 标准：默认推荐档，保留主题色、仿玻璃表面、轻缩放、边框、文字颜色过渡、封面轻提亮和平滑滚动。
- 高级：手动开启的高视觉档，增加更强玻璃氛围、环境高光、更高质量缩略图、主题色斜向流光、液态玻璃感边缘、卡片轻微放大和上浮。

Android 13 及以上设备可以在高级档中单独开启实验液态玻璃控件。开启后，侧边栏、首页分区胶囊、视频卡片、设置行和播放器控制面板会使用真实液态玻璃表面；关闭或不支持时自动回落到自绘半透明玻璃、边框和高光。

主页主题只作用于主页、搜索、动态、历史、设置、侧边栏和标签栏。播放器继续使用独立稳定配色，避免主题化影响播放性能和兼容性；播放器控制、面板和弹窗会按视觉性能策略使用液态玻璃或 fallback 表面。

## 播放器体验

播放器使用系统硬解优先的 Media3 ExoPlayer，并保留 SurfaceView 路径以优先保证兼容性和性能。

播放器 UI 包括：

- 顶部标题、UP、发布时间、播放量、当前时间。
- 底部大进度条、控制按钮、画质和弹幕状态。
- 控制层隐藏时的迷你进度条，可在设置中关闭，默认开启。
- 右侧设置、选集、UP 主更多视频、相关推荐面板。
- 画质、弹幕、倍速子面板，长列表使用滚动而不是压缩字号。
- 推荐视频和发布者更多视频使用更宽面板和更大封面，播放数、弹幕数、时长贴近主页卡片展示，避免小时级时长和万级弹幕数挤在一起。
- 进入和退出播放使用短黑屏遮罩过渡，避免 Surface 切换时出现闪烁。

弹幕层由原生 DanmakuView 承载，弹幕 XML 解码和解析放在后台线程，应用层不使用固定 delay 驱动弹幕重绘。

## 设置分组

设置页按使用语义分成三组：

- 播放设置：默认画质、YouTube 默认画质、默认播放倍速、播放优先级（SABR 优先 / DASH 优先）、解码器、快进预览、空降助手、退出确认、自动连播、自动推荐、播放完成退出、显示时间、迷你进度条。
- UI/UX：效果档位、液态玻璃、主页主题、切换时自动确认、切换时自动刷新。
- 系统设置：清理缓存、语言（6 档，含英/西/葡）、程序更新、WebDAV 备份、日志、关于。

首页分区开关独立显示在右侧，至少保留一个分区。

## 二合一架构

同一个 APK 同时适配 Android TV 和安卓手机，不拆包、不另起桌面入口。`MainActivity` 运行时用 `isTvUi()`（`UI_MODE_TYPE_TELEVISION` 或 `FEATURE_LEANBACK`）选择 `BiliTvApp`（TV，遥控器焦点）或 `BiliMobileApp`（手机，触屏外壳）。Manifest 同时挂 `LAUNCHER` 和 `LEANBACK_LAUNCHER`，一个包同时出现在手机桌面和 TV 桌面。`AppContainer` 和 DataStore（设置、登录、播放进度等）两端共享同一份；手机端 UI 放在 `ui/mobile/` 包，复用 `core/*` 全部引擎，触屏交互替换 TV 焦点机制。手机端 UI 参照 [BV](https://github.com/aaa1115910/bv) `feature/mobile` 的设计重新实现，属于设计移植而非代码拷贝。

仍在开发中：PGC（影视）tab、动态点赞/稍后再看、手机端深色主题统一。



## 技术栈

| 名称 | 用途 | 链接 |
| --- | --- | --- |
| Kotlin | 主要开发语言 | https://kotlinlang.org/ |
| Gradle / Android Gradle Plugin | 构建系统 | https://gradle.org/ |
| AndroidX / Jetpack | Android 基础库、Activity、Lifecycle、DataStore 等 | https://developer.android.com/jetpack/androidx |
| Jetpack Compose | 声明式 UI | https://developer.android.com/develop/ui/compose |
| Compose for TV | TV UI 和遥控器焦点基础能力 | https://developer.android.com/develop/ui/compose/tv |
| Media3 | ExoPlayer 播放器和 DASH 播放 | https://developer.android.com/media/media3 |
| OkHttp | HTTP、WebSocket、播放数据源请求 | https://square.github.io/okhttp/ |
| Coil | 图片加载 | https://coil-kt.github.io/coil/ |
| Kotlin Coroutines | 异步任务 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | JSON 解析 | https://github.com/Kotlin/kotlinx.serialization |
| DanmakuRenderEngine | 原生弹幕渲染 | https://github.com/bytedance/DanmakuRenderEngine |
| OpenCC4J | 简繁转换 | https://github.com/houbb/opencc4j |
| AndroidLiquidGlass / Backdrop | Android 13+ 实验液态玻璃控件 | https://github.com/Kyant0/AndroidLiquidGlass |
| ZXing | 二维码生成 | https://github.com/zxing/zxing |

第三方库遵循其各自许可证。

## 版本更新

### v3.0.6-alpha

移动端「已看完」闭环：看完的视频卡片右下角标角标（本地 WatchedStore 跟踪），并纳入 WebDAV 备份/还原；新增「已看完自动删除缓存」开关，看完自动清掉该视频下载文件。

| tag | 内容 |
| --- | --- |
| v3.0.6-alpha.4 | 设置弹窗 D-pad 焦点根治：WebDAV 编辑/IPTV/Piped 三个弹窗由内联叠层改真 `Dialog` 窗口（独立 window 自带焦点根，D-pad 不再逃到背后设置页）；备份/还原选择弹窗重构「固定顶底+滚动中段」——顶部全选与底部开始/取消锁定常显、中间选项 `LazyColumn` 随焦点滚动滚到底才进底部按钮，根治更多选项下底部按钮被裁剪焦点不可见 |
| v3.0.6-alpha.3 | SABR 可持续带宽 + 分辨率优先选档：①带宽样本计入段间等待根治「8K 缓冲掉不降档」；②Auto 选档改按 height 优先、bitrate 只当带宽门槛（`HeightAwareAdaptiveTrackSelection`），根治「Auto 卡 1080p 不升」（YouTube 声明 bitrate 与 height 错位） |
| v3.0.6-alpha.2 | SABR 带宽驱动选档根治升降档：媒体3 带宽计被 SabrDataSource 内存读样本污染（1M↔437M 跳变）致 ABR 钉死低档；新建 `SabrBandwidthMeter` 返回 SabrMediaFetcher 实测真实带宽（中位数），媒体3 原生 ABR 按可信带宽自动选最高可负担档，删 ceiling/force-climb 补丁。真机复测 1080p→1440p→4K 自然爬升，无震荡无黑屏 |
| v3.0.6-alpha.1 | 已看完闭环：① WebDAV 备份/还原新增「已看完列表」项（`bilitv/watched.json`，移动端+TV 弹窗都加，还原可选）；② 设置「播放」节新增「已看完自动删除缓存」开关（仅移动端），播放到结尾自动删该视频下载文件（`DownloadManager.deleteByVideoId`，含分件+「下载」播放列表存档），实际删了才弹 Toast 反馈；③ 视频卡片右下角「已看完」角标（`WatchedStore` + CompositionLocal，B站 bvid/YouTube videoId 统一承载） |

### v3.0.5-alpha

多语言线：界面从仅中文字体扩展为 6 档语言（简中/港繁/台繁 + English / Español / Português），数字与相对时间本地化，并修复 localeFilters 剪包真因。

| tag | 内容 |
| --- | --- |
| v3.0.5-alpha.9 | SABR 升降档控制：起始挡位改公开 API（`DefaultTrackSelector.setMaxVideoSize` 起播卡起始挡 + 首帧后 `clearVideoSizeConstraints` 松开，alpha.8 的 seed 方案真机失效首段仍恒最高档）；ceiling 降档滞回（降档排除源挡及以上候选，relax 窗口 bufferMaxMs/2；真机证实迭代器门控对 AdaptiveTrackSelection 失效，待改 excludeTrack） |
| v3.0.5-alpha.8 | YouTube 起始挡位设置生效：设置「起播挡位」此前无效（media3 1.10.0 `AdaptiveTrackSelection` 初始选轨纯带宽驱动 + `BaseTrackSelection` 内部按码率降序重排，resolver 挪 index0 无效）。改 `DefaultBandwidthMeter.setInitialBitrateEstimate` seed 到目标挡码率/0.7（TV+移动两 player），首段落目标挡后带宽实测自然爬升 |
| v3.0.5-alpha.7 | YouTube 点赞数显示修复：点赞数改从 `/player` `microformat.likeCount` 直接取（原靠另发 `/next` 取 videoActions 工具栏，真机取不到致移动端简介点赞行缺失），更快更稳；`/next` 保留作兜底 |
| v3.0.5-alpha.6 | TV 备份/还原选择弹窗崩溃修复（`SettingsWebDavSelectionDialog` `verticalScroll` 缺有限 max 高，弹窗无限高约束下测量滚动容器抛异常）+ 日志分享面板加「备份」单文件上传 + YouTube 搜索排序对齐 B 站 4 项（综合/最多播放/最新发布/评分，TV+移动端）+ 暂停状态 5s 无操作控制栏自动隐藏（TV 与移动全屏同步） |
| v3.0.5-alpha.5 | S905X5M 等 Amlogic 盒子解码器误判仅 H264 修复：`CodecCapabilityProbe` 改用 `ALL_CODECS` + `isHardwareAccelerated || !isSoftwareOnly` 判定（厂商 AV1/HEVC 硬解组件不置 `isHardwareAccelerated` 被漏判），修复后解码器出现 Auto/AV1/H265/H264 四项 |
| v3.0.5-alpha.4 | 多语言三语补齐 + localeFilters 剪包真因修复：`build.gradle.kts` `androidResources.localeFilters` 原只列 zh 变体把 `values-en/es/pt` 整个剪掉（APK arsc 字节搜索证实英文设置永远回落中文），补 `en/es/pt` 后拉丁资源真正进包；ES/PT 由骨架补为全量 664 key 翻译，占位符零错位；清掉语言切换 toast 诊断日志 |
| v3.0.5-alpha.3 | 多语言支持：语言设置扩展为 6 档（新增 English / Español / Português）。界面骨架文案可切拉丁语，中文动态内容保持原样；`localizedContext()` locale 映射、两套设置页语言项、移动端入口包裹；数字本地化 `CountFormatter`（中文 万/亿，拉丁 K/M/B）；`values-en` 全量 654 key 翻译；硬编码 UI 中文收口进 `stringResource` |
| v3.0.5-alpha.1 | YouTube SABR 自动档位升级修复：混合 H264/VP9 被默认轨道选择策略坍缩成单轨（`Representation.id` 全 null），显式开混合 mime 自适应后自动档可从 360p 逐步升到 1080p |

### v3.0.4-alpha.2

YouTube 首页订阅流接真实 continuation 分页：滚动到底可逐页逼近最早视频（此前每频道合并后硬截断 + 丢弃 continuation token）。YouTube 频道页「最新 / 最热」排序（对齐 B 站 UP 空间）；下载管理批量删除 + 播放列表批量移除（三点进批量模式、勾选/全选/删除所选）；在线播放命中缓存播本地源（已下载视频直接播本地文件，简介/评论/弹幕仍走在线，清晰度只读）；YouTube 播放优先级设置（SABR 优先 / DASH 优先，DASH 优先先走自合成兜底出 4K）。

### v3.0.4-alpha.1

TV 覆盖层/长按弹窗返回焦点恢复：UP 主页、YouTube 频道主页返回与动态长按操作菜单关闭后焦点不再丢失，统一走网格恢复精确回到进入前那张卡片，并抑制返回期间侧栏头像 autoConfirm。

### v3.0.3

稳定版：YouTube 评论/相关视频接入 + TV 焦点打磨 + 更新稳定性。对齐 LibreTube 补全 YouTube 评论区（TV + 移动端双端展示）与相关视频；TV 动态长按操作菜单改屏内覆盖层 + 显式焦点陷阱，修 D-pad 上下键丢焦点；修 YouTube 主页视频时长误读固定值（统一显示 2:58）；GitHub 在线更新 `/releases` 大响应间歇性 504（分页 + 退避重试）；WebDAV 备份/还原新增 Piped 配置。

### v3.0.2

稳定版：YouTube SABR 高清播放与 DASH 兜底稳定化（复活自合成 DASH 主兜底、NewPipe-first 主路径、DASH 切清晰度 init 段 EOF 修复、Representation id 唯一化），在线更新与真机测试链路打磨。

### v3.0.1

稳定版：IPTV 完整接入 + TV 交互打磨。IPTV 从零到完整落地（TV + 移动端双端）：源配置（URL/账号/密码 + 连通性校验 + 自动补 https）、直播页 IPTV tab、TV 端 TVBox 式频道列表侧栏（确认键开关/左右切台/上下切线路）、断流自动切镜像源、强制 IPv4 明文数据源（302 重定向按 IP 族选节点，IPv6 不可路由真机黑屏根因）、频道缩略图拉流截帧（ImageReader→SurfaceTexture+EGL 离屏）。TV 搜索栏 5 项优化、TV 历史合并本地 YouTube + 卡片绿框、YouTube 字幕接入 + 默认画质/倍速设置 + gl/hl 地区设置、TV 设置焦点循环导航 + WebDAV 校验 + 视频退出焦点恢复。

### v3.0.0

稳定版：YouTube 内容集成完整落地 + 移动端交互打磨。YouTube 多语言配音修复（中文视频不再误播英文配音 + 音轨切换 + 默认画质）、播放历史 + 断电续播、听视频模式（音频-only）、UP 头像完整实现、搜索与动态加载优化（对齐 LibreTube）、动态 feed 卡片 B站动态样式单列、UP 主页缓存、后台自动连播修复、短信登录页重叠修复。

### v3.0.1-alpha

v3.0.0 稳定版后的 patch 线 alpha。

| tag | 内容 |
| --- | --- |
| v3.0.1-alpha.33 | IPTV 缩略图截帧换方案：真机实锤 ImageReader 方案在本机不可行（codec 输出私有格式 0x7fa30c06 ≠ ImageReader 配置 RGBA_8888=0x1，抛 UnsupportedOperationException 全失败），切 SurfaceTexture+EGL 离屏 glReadPixels（IptvThumbnailCapturerEgl，不做格式协商），ImageReader 版保留对照 |
| v3.0.1-alpha.32 | IPTV 缩略图截帧全失败根因修复：stall 看门狗加 6s 启动宽限期（不再误杀 mobaibox 这类首帧要 ~8s 的慢源）、ready 判断改 ImageReader 帧可用信号（不再绑死 videoSize）、超时 15s→22s |
| v3.0.1-alpha.31 | IPTV 缩略图截帧改并发：信号量 2→3，TV/移动端消费循环串行 for 改 async 并发，死源/慢源不再串行堵住整批缩略图 |
| v3.0.1-alpha.28 | IPTV 缩略图截帧崩溃修复：capture 改专用 HandlerThread（ExoPlayer 必须在带 Looper 的线程访问，IO 线程抛 wrong thread 崩溃） |
| v3.0.1-alpha.14 | TV 历史 tab 合并本地 YouTube 历史（未登录也显示 + 登录后 B 站历史并入，按播放时间倒序）+ TV 动态/历史 YouTube 卡片标绿框识别 |
| v3.0.1-alpha.5 | TV 视频退出焦点恢复修复：恢复 effect 先等目标行进入视口布局再 requestFocus（不再盲重试），兜底清理 120→240 帧；新增 BiliMT:Focus 诊断日志（onBack/恢复 start-layout-success-failed/backstop/头像 onFocused），退出卡顿（734ms Davey）时焦点不再停在头像 |
| v3.0.1-alpha.4 | TV 搜索源 pill 点击当前源循环切换：点 BILIBILI 直接切到 YOUTUBE（点击已选中的源 pill 循环切到另一个源） |
| v3.0.1-alpha.3 | TV 搜索初始界面接通 D-pad 焦点：源切换按钮（B站/YouTube）+ 输入框可选中（源切换按钮加 FocusRequester、输入框加 focusable()+聚焦边框、键盘清空按钮加 onMoveUp） |
| v3.0.1-alpha.2 | 对齐 LibreTube：TV 端「YouTube 默认画质」+「默认播放倍速」设置项补齐 + YouTube 字幕接入（WebVTT URL 直拉，PlayerView 渲染；字幕轨切换 UI 后续迭代） |
| v3.0.1-alpha.1 | YouTube 频道页两处修复：首屏去重防 key 崩溃（loadFirst 补 distinctBy bvid，与翻页一致）+ 频道页头像补全（lockupViewModel 不带头像，从解析出的频道头像注入 ownerFace） |

### v3.0.0-alpha

v2.0.10 稳定版后的迭代线，主打 YouTube 多语言配音修复与交互打磨（已并入 v3.0.0 稳定版）。

| tag | 内容 |
| --- | --- |
| v3.0.0-alpha.1 | 修 WebDAV 弹窗按钮被系统键盘遮住、移动端 WebDAV 展开后备份/还原按钮自动上移、TV 搜索源切换还原双 pill |
| v3.0.0-alpha.3 | 多语言配音修复（中文视频不再误播英文配音）+ 播放器音轨切换按钮 + YouTube 默认画质设置 + YouTube 播放历史 + 断电续播 + 移动端听视频模式（音频-only，顶栏耳机按钮禁用视频轨只播音频） |
| v3.0.0-alpha.8 | YouTube 搜索与动态加载优化（对齐 LibreTube）：首页与动态共享关注缓存、动态请求去重、保留旧数据后台刷新、搜索请求去重/取消 |
| v3.0.0-alpha.9 | YouTube UP 头像完整实现（对齐 LibreTube）：视频卡片/播放器简介页/评论/动态关注 tab 全部真实显示头像，评论头像选最高分辨率，动态关注旧频道懒解析回填；另加短信登录页 DOM 结构诊断（临时） |
| v3.0.0-alpha.10 | 短信登录页两处重叠修复：顶栏改 Column 占位不再盖网页顶部；网页内"我已阅读并同意用户协议"不再叠登录按钮（诊断确认 .explain-tips 绝对定位叠按钮，注入 CSS 改 static 修复） |

### v2.0.10

稳定版：YouTube SABR 高清播放完整落地。YouTube 对 guest+token 会话不再给 legacy DASH 签名直链，拿流机制改为 SABR（Server-Assisted Bandwidth Regulation）。完整实现 SABR 协议引擎、WebView harvest 破 n-decrypt、NewPipeExtractor fork 取流层，并逐层修掉 60s 断崖/重启、黑屏、无声、全视频加载不出等真机问题，最终音视频稳定播放。

- SABR 取流：`server_abr_streaming_url` + `ustreamerConfig` 双闸驱动，替代 legacy DASH 直链。
- 多清晰度选择：poToken 会话级不绑 itag，`sabr://` 带 itag + videoId 缓存复用免重 harvest。
- 主动会话轮换：服务端每会话 ~60s 服务量上限，到点主动开新会话锚定播放头无缝续播，破 60s 断崖。
- SABR MediaSource 单流：完整移植 LibreTube 自定义 SABR MediaSource 单流替换 DASH 双流，根治 60s 断崖。
- 移动端：WebDAV 备份区默认折叠、清理缓存功能、YouTube 加载步骤 UI 提示。
- debug 云编译发布到固定 release 'debug'：固定 URL 覆盖更新，手机免登录下载最新 debug 包。

### v2.0.9

稳定版：YouTube 全链路 + 高清播放。从数据层到播放器完整接入 YouTube，支持关注流/搜索/UP 主页/播放列表/评论/多档清晰度切换，并实现 PO token（jnn）高清取流。另含 WebDAV 备份、移动端日志、空降段修复等。

- YouTube 关注流：动态页 B站动态 + YouTube 关注合并为一条流（TV+移动），免登录，空频道回退热门。
- YouTube 播放器：简介/评论 tab、多播放列表、◀▶/去弹幕/相关视频=列表后续、后台播放自动连播。
- YouTube 高清播放：多档清晰度（1080P/2K/4K）实时切换，adaptive 高清首选，`s`/`n` 签名解密，DASH 播放，硬件能力过滤。
- PO token（jnn）：bgutils-js 打包进隐藏 WebView 完整铸取流程，注入 /player 取高清；任一步失败降级 360p 不阻塞。
- WebDAV 备份/还原：YouTube 关注频道 + 日志一起备份上传。
- 移动端日志：设置页日志查看/导出。

### v2.0.8

稳定版：YouTube 内容集成。搜索/热门/动态关注/播放全链路接入 YouTube，移动端设置加账号与关注管理，动态页统一 B 站动态 + YouTube 关注为一条流，并优化关注流加载性能。

- 搜索/热门来源切换：搜索与首页热门可看 YouTube 内容（独立实现 InnerTube 私有 API，guest 认证免登录）。
- YouTube 播放：`POST /player`（WEB→ANDROID 回退）解析 `adaptiveFormats`/`formats`，含 `n` 参数解密 + PO token 结构 best-effort，默认 360p。
- 频道管理：设置页可添加/移除关注频道（`resolveChannel` 解析 UC ID / @handle / 名称 / 完整 URL），TV + 移动双端面板。
- 多播放列表：本地多命名播放列表（预置「默认」），长按加入、两层浏览、长按拖动排序、编辑删除；从播放列表起播后播放器出现 ◀▶ 连播。
- 动态页统一关注流：B站动态 + YouTube 关注合并为一条流（TV + 移动），5s 兜底；关注流逐频道并行化 + 持久化缓存 + RSS 优先加载。

### v2.0.1-alpha

v2.0.0 稳定版后的继续迭代线（patch 线 alpha），单 APK 通吃 TV + 手机。

| tag | 内容 |
| --- | --- |
| v2.0.1-alpha.1 | 播放器加视频分享 |
| v2.0.1-alpha.2 | 修加载列表时两个圈（顶部静止圈 + 居中转圈） |
| v2.0.1-alpha.3 | 首页内容区左右滑动切顶部 tab |
| v2.0.1-alpha.4 | 动态 tab 补 历史/收藏/追番 子 tab + 追番进季详情选集 |

### v2.0.0

稳定版：移动端 UI 移植完成，合并 mobile → mort_debug → main，单 APK 双桌面入口（`isTvUi()` 选 TV `BiliTvApp` 或手机 `BiliMobileApp`），TV 端零改动，复用全部 `core/*` 引擎。

### v2.0.0-alpha

手机端形态在独立分支上以 alpha tag 迭代，单 APK 通吃 TV + 手机，稳定后合主干。

| tag | 内容 |
| --- | --- |
| v2.0.0-alpha.1 | 自适应外壳（`NavigationSuiteScaffold`）+ 触屏首页分区网格 |
| v2.0.0-alpha.2 | QR 登录 + 卡片式设置（`SettingsActivity`） |
| v2.0.0-alpha.3 | 触屏播放器（复用 Media3/ExoPlayer + `PlayerDanmakuLayer` + 进度/心跳/完成上报） |
| v2.0.0-alpha.4 | SMS WebView 登录 + 应用名改回 BiliMT |
| v2.0.0-alpha.5 | 登录改 SMS-only（移除移动端 QR，因 TV QR 接口在手机扫不出）+ 加固 WebView |
| v2.0.0-alpha.6 | 短信"点一下即完成"（时序修复）+ 动态 tab（关注 feed 网格 + offset 分页） |
| v2.0.0-alpha.7 | 搜索 tab（取代底栏 PGC 占位）+ 修播放器返回键退出 + 顶部留状态栏 inset |
| v2.0.0-alpha.8 | 状态栏透明 + 浅色图标 + 播放全屏（activity 旋转 + configChanges + 沉浸） |
| v2.0.0-alpha.9 | 底栏重复点"推荐"触发刷新 + 滚顶 |
| v2.0.0-alpha.10 | 后台播放（前台 service + PlayerHolder + 通知控件，去 ON_PAUSE 暂停） |
| v2.0.0-alpha.11 | 播放器设置弹窗（画质/倍速/弹幕，activeRequest 驱动 load 重载） |
| v2.0.0-alpha.12 | 选集（分P）侧栏弹窗 |
| v2.0.0-alpha.13 | 自动连播下一集（nextEpisodeCompletion） |
| v2.0.0-alpha.14 | UP 主空间页（头像/签名/关注 + 投稿网格，入口：播放器顶栏标题/"UP"） |
| v2.0.0-alpha.15 | 后台播放通知改 MediaStyle（MediaSessionService）+ 请求 POST_NOTIFICATIONS + 内容页下滑刷新 |
| v2.0.0-alpha.16 | 修通知不显示（改回普通 Service + 手动 MediaSession + 显式 startForeground + MediaStyle） |
| v2.0.0-alpha.17 | 播放器手势前序（release 已发） |
| v2.0.0-alpha.18 | 播放器手势优化（点中央暂停/长按 2x/横拖 seek 松手恢复） |
| v2.0.0-alpha.19 | 空降助手 AirJump（SponsorBlock 自动跳过广告/片头/片尾） |
| v2.0.0-alpha.20 | 播放器底栏按钮图标化（参考 TV 版） |
| v2.0.0-alpha.21 | 修拖拽 seek 松手后意外暂停 |
| v2.0.0-alpha.22 | 播放器推荐视频按钮 + 修内容页下拉刷新无效 |
| v2.0.0-alpha.23 | 修下拉刷新方向反了（上滑触发→下拉触发） |

### v1.0.9
- 新增设置内"网络测速"：以最后一次播放的视频对各 CDN 节点并发测速，弹窗列出首字节/速度排名并标记最快节点。
- 改进 CDN 自动测速：每候选独立 2s 超时、只取 2s 内结果、4s 兜底、去掉固定延迟，择优更快更稳。
- 修复搜索/动态/历史卡片点击 UP 主头像无响应（补全 onOwnerSelected 回调链）。
- 修复 UP 主主页"取消关注"对话框按钮焦点穿透。
- CI 改用 `gh release create --generate-notes` 生成发布说明。

### v1.0.7
- 新增应用内更新：设置页可手动检查 GitHub Releases、下载 APK、安装并重启。
- 修复 prerelease tag 的版本解析，稳定版与 alpha 版均可正确判断新旧。
- 改进应用内更新 UI：当前版本与检查操作分开显示，下载时展示实时进度。
- CI 发布构建单一 universal release APK（同时含 arm64-v8a / armeabi-v7a）；发布稳定版时自动清理旧 prerelease，并只保留最近 10 次 workflow run。
- 关于页面项目地址与简介切换到当前 `urbanescavenger/BiliMT` 仓库。

## 开发说明

本项目全部由 AI 辅助完成。根目录文档用于保留上下文和约束：

- `AGENTS.md`：开发约束和项目规则。
- `DEVELOPMENT_PLAN.md`：产品、架构和技术路线。
- `DEVELOPMENT_PROGRESS.md`：阶段进度和历史决策。

继续开发时建议按小步修改、编译、安装、实机验证的节奏推进，不要一次性重写大模块。播放器、弹幕、焦点路径和液态玻璃开关尤其需要同时考虑性能档位和电视端遥控器操作。

## 致谢

BiliMT 基于 [BiliTVNative 1.0.0](https://github.com/Hyper-Beast/BiliTVNative) 继续开发，沿用了上游项目的整体架构、TV 焦点系统、播放器集成、弹幕叠加层和大量基础能力。感谢原作者及贡献者。

上游项目采用 MIT License，本项目同样以 MIT License 发布。

## 免责声明

本项目不是哔哩哔哩官方项目，也不与哔哩哔哩存在任何官方关联。

项目只作为个人学习、研究和自用客户端实现参考。使用者需要自行承担账号、接口、播放兼容性和后续维护风险。

## License

本项目代码使用 MIT License。详见 [LICENSE](LICENSE)。
