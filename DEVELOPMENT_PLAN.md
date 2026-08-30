# BiliMT 开发计划

## 目录

- [目标](#目标)
- [目标成果](#目标成果)
- [当前边界与暂缓内容](#当前边界与暂缓内容)
- [项目布局](#项目布局)
- [技术栈](#技术栈)
- [设计语言](#设计语言)
- [首页主题与玻璃视觉专项](#首页主题与玻璃视觉专项)
- [设计令牌](#设计令牌)
- [架构](#架构)
- [AI 实施约束](#ai-实施约束)
- [确定性焦点系统](#确定性焦点系统)
- [Media3 播放数据源规范](#media3-播放数据源规范)
- [PlayerView Surface 策略](#playerview-surface-策略)
- [图片解码和缓存策略](#图片解码和缓存策略)
- [弹幕帧循环规范](#弹幕帧循环规范)
- [生命周期保存策略](#生命周期保存策略)
- [B 站网络兼容规范](#b-站网络兼容规范)
- [Baseline Profile 策略](#baseline-profile-策略)
- [从 Flutter 到原生的功能映射](#从-flutter-到原生的功能映射)
- [里程碑](#里程碑)
  - [Phase 1：原生工程骨架](#phase-1原生工程骨架)
  - [Phase 2：核心 API 和 Session 层](#phase-2核心-api-和-session-层)
  - [Phase 3：首页和 TV 导航](#phase-3首页和-tv-导航)
  - [Phase 4：点播播放器](#phase-4点播播放器)
  - [Phase 5：弹幕和快进预览](#phase-5弹幕和快进预览)
  - [Phase 6：收尾与发布](#phase-6收尾与发布)
  - [Phase 7：剩余页面和设置](#phase-7剩余页面和设置)
  - [Phase 8：构建、体积和性能收尾](#phase-8构建体积和性能收尾)
- [当前可用范围](#当前可用范围)
- [移动端 UI 移植(mobile 分支)](#移动端-ui-移植mobile-分支)
  - [背景与决策](#背景与决策)
  - [已完成（均走云编译闭环，alpha tag 发布）](#已完成均走云编译闭环alpha-tag-发布)
  - [后续路线图（按优先级）](#后续路线图按优先级)
  - [已知问题 / 后续待修](#已知问题--后续待修)
  - [待修：空降助手首次进入识别不到广告段](#待修空降助手首次进入识别不到广告段)
  - [工程约束（移动端专用）](#工程约束移动端专用)
- [YouTube 内容集成（P11）](#youtube-内容集成p11)
  - [已定方案](#已定方案)
  - [播放（P11-09）实现要点](#播放p11-09实现要点)
  - [播放高清（P11-14）实现要点](#播放高清p11-14实现要点)
  - [反爬与废弃端点（实测关键）](#反爬与废弃端点实测关键)
  - [里程碑](#里程碑-1)
  - [TV 版 vs 移动端 YouTube 功能差异待办（P11-19）](#tv-版-vs-移动端-youtube-功能差异待办p11-19)
  - [发布](#发布)
- [测试计划](#测试计划)
- [发布策略](#发布策略)
- [风险清单](#风险清单)
- [已知崩溃 / 待修问题](#已知崩溃--待修问题)
  - [C-01 应用内更新下载 SSL 握手失败崩溃](#c-01-应用内更新下载-ssl-握手失败崩溃)
  - [Y-01 YouTube SABR 播放 RELOAD_PLAYER_RESPONSE 终端失败（部分视频无法播放）](#y-01-youtube-sabr-播放-reload_player_response-终端失败部分视频无法播放)
  - [F-01 TV 视频退出后焦点被头像抢占（播放后焦点消失）](#f-01-tv-视频退出后焦点被头像抢占播放后焦点消失)
- [实现原则](#实现原则)
- [NDK / so 引入原则](#ndk--so-引入原则)
- [已定决策](#已定决策)

## 目标

将 BiliTV 重写为原生 Android TV 应用，技术栈使用 Kotlin 和 Jetpack Compose，同时保留当前 Flutter 项目作为行为参考和回退版本。BiliMT 基于 BiliTVNative 1.0.0 继续开发。

这次重写的目标是改善电视遥控器操作响应、降低安装包体积和空闲内存，并保持现有播放行为兼容。

## 目标成果

- 使用 Kotlin 和 Jetpack Compose 构建原生 Android TV 应用。
- 与当前 Flutter 版 BiliTV 达成功能等价。
- D-pad 遥控器焦点导航更快、更可预测。
- 第一版主发 `armeabi-v7a` 发布 APK，体积小于当前 Flutter 构建。
- 低端 Android TV 设备上的空闲内存更低。
- B 站播放、登录、历史、搜索、弹幕和设置流程稳定可用；直播后续单独评估。

## 当前边界与暂缓内容

- 跨平台支持。
- 复刻完整插件系统。
- 复用 Flutter UI 代码。
- 直播播放和直播弹幕，直到用户明确要求恢复。
- 在当前单 app 模块稳定前拆分多 Gradle 模块。
- 引入 Room、Koin 或 Compose Navigation；当前代码没有使用这些依赖，只有出现明确需求时再评估。

## 项目布局

原生项目放在 Flutter 项目旁边：

```text
C:\Users\Kirin\OneDrive\Code\
  BiliTV\             # 现有 Flutter 实现，保留作为参考
  BiliMT\       # 新 Kotlin + Compose 实现
```

当前 Android 包名：

```text
com.kirin.mt
```

当前源码布局：

```text
BiliMT/
  app/
    src/main/java/com/kirin/bilitv/
      BiliTvApplication.kt
      MainActivity.kt
      core/
        app/
        auth/
        cache/
        i18n/
        image/
        model/
        network/
        player/
        settings/
        storage/
      ui/
        account/
        common/
        feed/
        focus/
        glass/
        home/
        i18n/
        login/
        player/
        search/
        settings/
        shell/
        theme/
```

初期使用单 Android app 模块。等主流程稳定后，再考虑拆分多模块。

## 技术栈

核心：

- Kotlin 2.x
- JDK 17
- Android Gradle Plugin 9.x
- Gradle 版本目录 / `libs.versions.toml` 统一管理依赖版本
- minSdk 当前为 23；如果 Compose、Media3 和目标设备测试都稳定，再评估降到 21
- compileSdk / targetSdk 当前为 36

UI：

- Jetpack Compose
- Compose for TV / `androidx.tv:tv-material`
- 当前不使用 Compose Navigation；页面级路由由 `AppShell`、`AppDestination` 和显式焦点恢复状态管理
- 自定义 TV 焦点工具，覆盖 D-pad 网格、侧边栏、播放器浮层和弹窗面板

播放：

- AndroidX Media3 ExoPlayer
- Media3 DASH 支持 B 站点播
- 需要时使用 Media3 HLS 支持直播流
- Media3 专用 `OkHttpDataSource.Factory`，统一注入 B 站播放请求头和 Cookie
- `PlayerView` 通过 Compose `AndroidView` 承载
- 控制栏、画质选择、面板和播放状态使用 Compose 叠加层

网络：

- OkHttp 负责 HTTP 和 WebSocket
- Kotlin coroutines 负责异步任务
- Kotlin Flow 负责状态更新
- kotlinx.serialization 负责 JSON 解析

存储：

- DataStore Preferences 存设置、auth/session、搜索历史、WBI key、播放进度和弹幕设置等小型状态
- 当前不使用 Room；只有播放进度、历史、本地元数据或缓存索引需要复杂查询/清理/同步时再迁移
- Android 文件缓存存图片和雪碧图缓存

图片：

- Coil 2.x / Coil Compose 负责图片加载
- 明确限制内存缓存和磁盘缓存
- 海报、头像请求必须约束目标尺寸
- TV 卡片离屏后懒加载，避免一次性解码太多图
- 列表海报缩略图允许使用 `Bitmap.Config.RGB_565` 降低内存
- 详情大图、头像、透明图和需要高质量显示的图片默认保留高质量配置

弹幕：

- 使用 XML pull parser 解析 B 站弹幕 XML
- 使用已确认的字节跳动 `danmaku-render-engine` `DanmakuView` 作为原生叠加层渲染高频弹幕
- 避免把每条弹幕渲染成一个 Compose 节点
- 弹幕 XML 解析、轨道分配和碰撞计算不能放在 UI 线程
- 第一版将轨道分配、碰撞和帧节奏委托给字节跳动弹幕引擎；若后续替换为自研 Canvas，再使用 Kotlin 预计算轨道和时间窗口缓存
- 恢复直播后，直播弹幕使用队列上限和帧预算控制 drain 频率

构建：

- 第一版主发 `armeabi-v7a` 包；工程保留 `arm64-v8a` 构建能力
- 发布构建开启 R8 代码压缩
- 发布构建开启资源裁剪
- 当前已有保守 Baseline Profile，发布构建已包含 `baseline.prof` / `baseline.profm`
- 发布签名前先沿用 debug 签名

## 设计语言

采用自定义 `BiliTV 设计系统`，底座使用 Material 3 for TV / Compose for TV，视觉层使用 B 站品牌风格。

原则：

- 使用 MD3 和 TV Material 的组件能力，但不照搬默认 Material 长相。
- 固定品牌色，不使用 Material You 动态取色。
- 面向电视远距离观看，优先保证焦点清晰、字号可读、层级明确。
- 视觉方向更接近媒体电视客户端，而不是普通 Android 设置应用。
- 焦点状态是核心视觉语言，不只改变颜色，还要包含缩放、边框、阴影和内容抬升。
- 动效可以比手机端更积极，但必须短、稳、可预测。

建议视觉基准：

- 左侧导航 + 横向内容轨道 + 大海报/沉浸式选中预览。
- 播放器控制层使用半透明暗色底，减少遮挡视频内容。
- 主要品牌色使用 B 站粉。
- 背景以深灰黑为主，避免大面积纯黑和大面积纯粉。
- 卡片、面板和按钮圆角统一从 token 读取。
- 焦点动画建议控制在 120-180ms。

## 首页主题与玻璃视觉专项

本专项覆盖首页、搜索、动态、历史、设置、侧边导航、主页标签栏和共享焦点控件。播放器视频内容和 `SurfaceView` 本体不参与主页主题、液态玻璃采样、裁切或变换；播放器覆盖层控件可以在液态玻璃开启时使用同一套单层玻璃 fallback，但不能影响视频 surface。

目标视觉方向：

- 做接近电视媒体客户端的深色玻璃质感，而不是普通 Material 卡片。
- 使用“假玻璃”为基础：半透明深色面板、细边框、渐变高光、轻阴影和背景氛围色。
- 高配置设备再启用环境高光、焦点流光和更强玻璃层次；不把实时模糊作为基础能力。
- Android 9-11 设备必须能显示合理 fallback，不依赖 `RenderEffect` 或系统级 Liquid Glass。
- Android 13+ 设备可以通过独立开关试验 AndroidLiquidGlass；当前仅在精致档允许启用，Android 13 以下持久化读取和写入都会强制关闭，并保留自绘玻璃边缘作为 fallback。
- 卡片列表禁止每张卡片持续实时模糊，避免滚动和换行焦点掉帧。
- 焦点缩放保持克制，建议不超过 `1.055`；主要反馈来自边框、提亮、文字颜色和一次性高光。

主页主题预设固定为 4 种：

- 默认粉：保留 B 站粉作为焦点色和主要强调色。
- 深黑：更低亮度背景，适合 OLED、夜间和高对比电视。
- 高级灰：接近参考图的灰蓝玻璃氛围，突出半透明层次。
- 蓝灰：偏电视系统 UI，降低粉色面积，适合长期观看。

视觉性能模式固定为 3 档：

- 流畅：保留当前低配置策略。关闭动画、模糊、流光、阴影和平滑滚动；缩略图使用低尺寸和 RGB_565；禁用图片内存缓存和预取。
- 均衡：默认推荐档。启用主题色、假玻璃、轻缩放、边框和文字颜色过渡、封面轻提亮；不启用实时模糊，流光默认关闭或极轻。
- 精致：手动开启的高视觉档。在均衡基础上启用更强玻璃氛围、环境高光、跟随主题色的焦点卡片斜向流光、液态玻璃感边缘、轻微放大、卡片上浮、更高质量缩略图和更大的图片缓存。

首次启动默认策略：

- 设备总内存低于 1GB：默认流畅。
- 设备总内存 1GB-2GB：默认均衡。
- 设备总内存高于 2GB：默认均衡，不自动打开精致。
- 精致模式必须由用户手动开启，避免电视系统虚标内存或 GPU 较弱导致卡顿。

当前已实现要点：

1. 主页主题、视觉性能模式、液态玻璃开关均通过 DataStore 持久化。
2. `HomeColorScheme` / `LocalHomeColors` 负责主页域主题，播放器视频内容不纳入主题。
3. 设置页按 `播放设置`、`UI/UX`、`系统设置` 分组；视觉效果档位、液态玻璃控件和主页主题属于 `UI/UX`。
4. 首页背景、侧边栏、分区胶囊、视频卡片、设置/搜索共享控件均支持液态玻璃开启/关闭两条路径。
5. 精致模式包含焦点卡片流光、上浮、轻微放大和液态玻璃感边缘；流畅模式关闭高成本动画、预取、阴影和内存缓存。
6. 播放器覆盖层控件可使用液态玻璃表面，视频 `SurfaceView`、弹幕引擎和播放链路不参与视觉采样。

## 设计令牌

从第一版开始建立统一 token 文件，所有颜色、尺寸、圆角、动画时长、焦点缩放和阴影都从 token 读取，页面内不散写魔法值。

建议文件：

```text
app/src/main/java/com/kirin/mt/ui/theme/BiliTokens.kt
```

建议初始内容：

```kotlin
object BiliColors {
    val BiliPink = Color(0xFFFB7299)
    val Background = Color(0xFF101014)
    val Surface = Color(0xFF1A1A20)
    val SurfaceElevated = Color(0xFF24242C)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xB3FFFFFF)
}

object BiliSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
}

object BiliRadius {
    val Card = 12.dp
    val Panel = 16.dp
    val Pill = 999.dp
}

object BiliMotion {
    const val FocusMs = 140
    const val PanelMs = 180
    const val OverlayMs = 160
    val FocusEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
}

object BiliFocus {
    const val CardScale = 1.06f
    val BorderWidth = 2.dp
}
```

命名规则：

- 品牌粉统一命名为 `BiliPink`，值为 `#FB7299`。
- 新增颜色必须先进入 token 文件，再被页面引用。
- 组件默认尺寸、间距、圆角、动画时长必须优先使用 token。
- 焦点缩放、焦点动画和面板动画必须使用 `BiliMotion` 中的时长和 easing。
- 特殊页面可以新增局部 token，但不能在 Composable 内直接散写关键视觉值。

## 架构

采用简单分层：

```text
UI 页面 / AppShell 状态
  -> Repository / Store
  -> API / DataStore / 文件缓存
```

规则：

- Screen 只渲染上层传入或本地状态持有器暴露的状态对象，避免在 UI 内散落跨页面全局状态。
- 当前没有引入 ViewModel 层；只有页面状态复杂到需要生命周期外持有、复用或测试时再增加 ViewModel，并用 `StateFlow` 暴露状态。
- Repository 负责数据加载、缓存策略和请求重试。
- Store 负责 DataStore 持久化和小型状态读写。
- API / Repository 行为尽量贴近现有 Flutter service，方便做等价校验。
- 播放器状态必须和普通页面状态隔离，避免播放中频繁大范围重组。

## AI 实施约束

后续开发会长期由 AI 辅助执行，因此必须把实现边界写清楚，避免过度发散、提前抽象和一次性生成不可维护的大文件。

约束：

- 实际开发时只执行用户指定的任务。
- 未要求进入下一项功能前，不生成后续功能代码。
- 不为了未来功能提前创建无实际调用的抽象层。
- 复杂模块先定义接口、数据结构和调用边界，再实现具体逻辑。
- 不一次性输出几千行复杂文件。
- Compose UI 必须优先使用 `BiliTokens.kt` 中的 token。
- 禁止在 Composable 中散写十六进制颜色、随机 `dp`、随机动画时长和临时视觉常量。
- 必要的新视觉值必须先进入 token 文件，再被页面引用。
- 每个阶段结束时优先保证可构建、可运行、可验证，而不是追求一次性完整。

项目根目录应保留 `AGENTS.md`，用于约束后续 AI 开发行为。`DEVELOPMENT_PLAN.md` 负责描述产品和架构计划，`AGENTS.md` 负责描述执行规则和代码约束。

## 确定性焦点系统

TV 焦点不能依赖默认最近邻搜索。复杂页面必须显式定义焦点路径，避免长按 D-pad 或从弹窗返回时跳到不可预测位置。

焦点系统要求：

- 在 `ui/focus` 下建立统一焦点工具。
- 侧边栏进入内容区必须使用 `FocusRequester` 指定进入点。
- 内容区返回侧边栏必须回到当前 tab 对应的侧边栏项。
- LazyRow、LazyGrid、播放器面板和设置弹窗必须定义边界行为。
- 弹窗关闭后必须恢复到打开弹窗前的焦点。
- 不在复杂网格中依赖系统默认 `FocusDirection.Right/Left/Up/Down` 最近邻搜索。
- 焦点移动逻辑和焦点视觉状态分离，避免为了视觉动画破坏导航确定性。

当前工具：

```text
ui/focus/BiliFocusableSurface.kt
ui/home/TvVideoGrid.kt
ui/shell/AppShell.kt
ui/player/PlayerOverlay.kt
ui/settings/SettingsScreen.kt
```

验收：

- 长按方向键不丢焦点。
- 从侧边栏进入内容区时总是进入当前页面的首个合理内容项。
- 从播放器面板、设置弹窗、画质选择弹窗返回后焦点可恢复。
- 焦点不跳到屏幕外、不可见项或错误 tab。

## Media3 播放数据源规范

B 站播放请求对请求头和 Cookie 敏感。播放器的数据源必须独立封装，不能散落在各个播放器调用处。

要求：

- 在 `core/player` 中建立 `BiliMediaDataSourceFactory`。
- 使用 Media3 `OkHttpDataSource.Factory`。
- 所有播放分片请求必须统一注入 `User-Agent`、`Referer`、`Origin` 和 Cookie。
- 播放请求头应尽量和 Flutter 版当前行为保持一致，便于排查 403。
- 点播和直播可以共享底层 OkHttpClient，但播放数据源配置必须显式。
- 画质切换、分 P 切换和重试必须复用同一套 header 策略。

建议初始 header：

```text
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36
Referer: https://www.bilibili.com/
Origin: https://www.bilibili.com
Cookie: SESSDATA=...
```

播放器缓冲策略：

- 电视端允许用适度内存换取弱网流畅。
- 第一版建立 `TvPlaybackLoadControl`，比 Media3 默认值略积极。
- 不无脑放大 buffer，避免高码率 DASH 播放、画质切换和低内存设备出现反效果。
- buffer 参数必须通过实际设备播放测试再调。

## PlayerView Surface 策略

Android TV 播放器默认使用 `SurfaceView`，优先保证硬解性能、稳定性和兼容性。

约束：

- 默认视频渲染使用 `SurfaceView`。
- 不追求视频本体的圆角裁切、透明度动画、缩放变换或复杂 Compose 特效。
- 控制栏、弹幕、画质面板和提示层必须作为独立叠加层位于 `PlayerView` 上方。
- 不对视频 View 本身做复杂变换，避免 SurfaceView 在 Compose `AndroidView` 中出现黑屏、穿透或层级异常。
- 如未来必须做复杂视频变换，再单独评估 `TextureView`，不能默认切换。

验收：

- 播放器控制层和弹幕能稳定覆盖在视频上方。
- 退出播放器后 Surface 和 player 资源能释放。
- 播放器页面不因圆角、透明或层级效果导致黑屏。

## 图片解码和缓存策略

图片内存治理不只限制尺寸，也要控制解码格式、缓存窗口和预取策略。

策略：

- 首页和列表海报缩略图优先约束宽高，并可在低内存设备上使用 `RGB_565`。
- 详情大图、头像、透明图、渐变图和快进预览图默认不强制 `RGB_565`。
- Coil 内存缓存和磁盘缓存必须设置上限。
- 首页只预取当前可见窗口附近内容，不预取全部分类。
- 进入播放器时释放非必要首页图片引用。
- 低内存模式可以进一步降低图片缓存、预取数量和海报解码质量。

验收：

- 首页滚动不会因为图片解码出现明显卡顿。
- 低端设备上图片缓存不会持续增长。
- 海报远距离观看无明显不可接受的色带或锯齿。

## 弹幕帧循环规范

弹幕刷新必须由字节跳动弹幕引擎或系统帧时钟驱动，避免用固定 `delay()` 驱动重绘导致 TV 上卡顿、撕裂或节奏不稳。

约束：

- 使用字节跳动 `DanmakuView` 时由引擎负责绘制循环；自研 Canvas 时必须使用 Android `Choreographer` 或 Compose `withFrameNanos`。
- 不使用 coroutine `delay(16)`、`Timer` 或固定 sleep 作为主绘制节拍。
- UI 线程只承载已解析数据到渲染引擎的更新，不做 XML 解码、解析或额外重型排布计算。
- XML 解析、轨道分配、碰撞计算和过滤规则在后台线程完成。
- 播放暂停、seek、倍速切换后必须重置弹幕时间窗口。

验收：

- 弹幕移动节奏和屏幕刷新率同步。
- seek 后弹幕能快速重新定位。
- 高弹幕量下 UI 线程没有持续重型计算。

## 生命周期保存策略

Android TV 设备内存较小，应用进入后台后容易被系统杀掉。核心状态不能只依赖 `onStop` 或 `onDestroy` 保存。

约束：

- 播放进度必须在 `onPause` 触发保存。
- 当前 BVID、CID、播放位置、画质、倍速和弹幕开关等播放器关键状态应尽早持久化或缓存。
- auth/session/settings 变更后立即写入 DataStore；当前未使用数据库。
- 二维码登录轮询必须感知生命周期，应用进入后台时暂停轮询，回到前台后重新判断二维码状态。
- WebSocket、播放器和后台任务必须在对应生命周期中停止或降级，避免回前台后状态错乱。

验收：

- 播放时切到系统设置或主页，再回到 app 后不丢关键状态。
- App 后台被杀后重新进入，登录态和主要设置仍存在。
- 二维码登录页后台暂停后不会继续无意义轮询。

## B 站网络兼容规范

B 站 API 和播放链路对压缩格式、签名和请求头敏感，原生实现必须从第一版就保留兼容策略。

要求：

- OkHttp API client 必须支持 Brotli 响应，必要时引入 `okhttp-brotli`。
- 恢复直播后，直播 WebSocket 包体 Brotli 解码需要显式处理。
- WBI 签名使用纯 Kotlin 实现 MD5、参数过滤、排序和 URL encode。
- 不为了 WBI 签名引入原生加密库或 C/C++。
- WBI key 缓存策略应参考 Flutter 版行为，避免每次启动重复请求。
- TV 登录签名和 WBI 签名分别实现，不混用。
- 播放请求头与普通 API 请求头分开管理，播放走 `BiliMediaDataSourceFactory`。

验收：

- 推荐、搜索、空间投稿等 WBI API 能稳定返回。
- 遇到 `Content-Encoding: br` 时 JSON 解析不乱码。
- 播放分片请求不因缺少请求头/Cookie 出现 403。

## Baseline Profile 策略

Compose 项目冷启动和首屏性能受类加载、Compose 运行时和主路径编译状态影响。当前发布构建已包含保守 Baseline Profile；后续只有在主路径明显变化或实测启动性能回退时再更新。

策略：

- 保留当前 profile 覆盖启动、首页、搜索、设置和播放器主路径。
- 发布构建必须继续产出 `assets/dexopt/baseline.prof` 和 `assets/dexopt/baseline.profm`。
- UI 频繁微调时不反复生成无效 profile；等交互稳定后再补。
- 直播路径在恢复直播阶段再补。

目标路径：

- 冷启动到首页。
- 侧边栏切换到首页内容。
- 首页首屏海报渲染。
- 搜索进入结果页。
- 视频卡片进入播放器。
- 播放器控制层显示和隐藏。

## 从 Flutter 到原生的功能映射

现有 Flutter 区域迁移关系：

- `AuthService` -> `core/auth`
- `BilibiliApi` 和 API service 文件 -> `core/network` 和 repositories
- `SettingsService` -> `core/storage`，使用基于 DataStore 的设置仓库
- `LocalServer` 和 `MpdGenerator` -> 优先尝试 Media3 原生 DASH 处理，只在必要时保留本地 MPD 回退方案
- `PlaybackProgressCache` -> 首个发布版使用 DataStore；如果播放进度/历史需要查询、清理或同步，再迁移到 Room。
- `canvas_danmaku` 用法 -> 字节跳动 `DanmakuView` 原生叠加层
- `HomeScreen` 和标签页 -> `AppShell` / `AppDestination` 状态路由和 Lazy 布局
- `PlayerScreen` 和播放器组件 -> Media3 播放器宿主 + Compose 叠加层
- 直播 socket service -> 暂缓；恢复直播时使用 OkHttp WebSocket 服务

## 里程碑

### Phase 1：原生工程骨架

预计时间：1-2 天

交付物：

- 在 `BiliMT` 下新建 Gradle Android 项目。
- 引入 `libs.versions.toml` 管理依赖版本。
- 配置 Compose 和 TV Material。
- 预留 Baseline Profile 配置。
- 应用能在 Android TV 上启动。
- 基础启动页。
- 侧边栏壳：搜索、首页、动态、历史、用户/设置；直播暂缓，不放入首个发布版导航。
- D-pad 焦点高亮可用。
- 每个页面有空状态内容。

验收：

- debug 构建可安装并启动。
- 遥控器上/下键能在侧边栏移动。
- 返回键行为可退出应用。
- 依赖版本集中在 Version Catalog 中。

### Phase 2：核心 API 和 Session 层

预计时间：3-5 天

交付物：

- 带 B 站请求头和 Cookie 处理的 OkHttp client。
- 推荐、视频信息、播放地址、历史、搜索、动态、登录相关 JSON models；直播 models 后续恢复直播阶段再补。
- 二维码登录后端逻辑。
- 基于 DataStore 的 auth/session 持久化。
- 带基础错误处理的 Repository 封装。

验收：

- 能获取推荐列表。
- 能搜索视频。
- 能获取视频详情和播放地址。
- 能完成二维码登录，或至少能轮询二维码状态。
- Flutter 和原生 API 结果的关键字段一致。

### Phase 3：首页和 TV 导航

预计时间：4-7 天

交付物：

- 首页推荐页。
- 分区横向列表和视频卡片。
- 尺寸稳定的可聚焦 TV 卡片。
- LazyRow/LazyGrid 渲染。
- 通过 Coil 加载图片，并限制目标尺寸。
- 列表缩略图支持低内存解码策略。
- 侧边栏到内容区、内容区回侧边栏的焦点切换。
- 复杂网格使用确定性焦点路径，不依赖默认最近邻搜索。
- 基础视频卡片进入播放器。

验收：

- 首页可完全用遥控器操作。
- 长按 D-pad 不丢焦点。
- 侧边栏、内容区、弹窗返回焦点可预测。
- 目标 TV 硬件上滚动顺滑。
- 图片缓存不会无限增长。

### Phase 4：点播播放器

预计时间：7-10 天

交付物：

- Media3 ExoPlayer 集成。
- `BiliMediaDataSourceFactory` 集成。
- B 站 DASH 视频/音频播放。
- 播放请求支持 header 和 cookie。
- `TvPlaybackLoadControl` 初版缓冲策略。
- 编码偏好和回退。
- 画质列表和画质切换。
- 播放/暂停、seek、快进、快退。
- 控制层自动隐藏。
- 续播进度。
- 上报播放进度。
- 分 P 切换。

验收：

- H.264 播放正常。
- 设备支持时 H.265 播放正常。
- AV1 回退行为正确。
- 播放分片请求稳定携带 B 站所需请求头和 Cookie。
- DASH 音视频保持同步。
- 画质切换后能从接近原位置继续播放。
- 返回退出播放器后能释放内存。

### Phase 5：弹幕和快进预览

预计时间：5-8 天

交付物：

- 弹幕 XML 获取和解析。
- 弹幕排布和碰撞由字节跳动弹幕引擎处理，应用层负责数据转换和配置。
- 字节跳动 `DanmakuView` 原生叠加层。
- 弹幕设置：开关、透明度、字号、显示区域、速度、顶部/底部过滤。
- 弹幕和播放进度时间同步。
- Videoshot API 迁移。
- 雪碧图加载和滑动淘汰窗口。
- 快进预览 UI。

验收：

- seek 后弹幕仍能同步。
- 高弹幕量下 UI 没有明显卡顿。
- UI 线程不做 XML 解码、解析或额外重型排布计算。
- 快进预览不会一次性预加载所有雪碧图。
- 离开播放器后内存可释放。

### Phase 6：收尾与发布

预计时间：5-8 天

交付物：

- 发布构建配置。
- 按 ABI 过滤的 APK 任务。
- R8 和资源裁剪规则。
- Baseline Profile 配置和保守 profile。
- 图标、TV 横幅、语言切换和发布前稳定性修复。
- 直播播放暂缓，后续单独开启时再补分区页、房间列表、HLS/WebSocket 和直播弹幕。

验收：

- `armeabi-v7a` 发布 APK 可构建并安装到目标电视。
- `arm64-v8a` 发布 APK 保持可构建。
- 发布构建启动无 R8 反射崩溃。
- 长按 D-pad、首页/设置/播放器主路径保持可用。

### Phase 7：剩余页面和设置

预计时间：8-12 天

交付物：

- 搜索 UI 和搜索历史。
- 历史页面。
- 动态页面。
- 登录/用户页面。
- 设置页面。
- 播放设置。
- 界面设置。
- 缓存清理。
- 空降助手设置开关。

验收：

- 主流程达到足够替代日常使用的功能等价。
- 设置重启后仍能持久化。
- 缓存清理可用。
- 登录/退出不会破坏已存 session。

### Phase 8：构建、体积和性能收尾

预计时间：3-5 天

交付物：

- 发布构建配置。
- 按 ABI 过滤的 APK 任务。
- R8 和资源裁剪规则。
- 首个可用版本稳定后，如有必要加入 Baseline Profile。
- 与 Flutter 版本的性能对比报告。

验收目标：

- `armeabi-v7a` APK：目标 14-17 MB。
- `arm64-v8a` APK：暂不作为第一版主发包；保留构建能力，后续如遇到 64-bit-only 设备再发布。
- 首页空闲 PSS 至少比 Flutter 目标构建低 20 MB。
- 播放态 PSS 更低或接近，同时保持稳定。
- 目标 TV 上冷启动到首页可操作低于 2 秒。
- 长按 D-pad 时焦点移动稳定。

## 当前可用范围

当前原生版本已包含：

- 侧边栏、首页推荐/分区、搜索结果、动态、历史、设置和账号登录入口。
- TV 二维码登录、session 持久化、搜索历史、WBI key、播放进度、弹幕设置和应用设置。
- 点播 DASH 播放、画质/编码偏好、分 P、UP 主更多视频、相关推荐、播放完成动作、播放心跳和续播。
- 字节跳动 `DanmakuView` 弹幕叠加层、弹幕样式设置、快进预览雪碧图和空降助手。
- 三档视觉性能模式、四种主页主题、Android 13+ 液态玻璃控件、迷你进度条开关、关于展示面板和缓存清理。
- `armeabi-v7a` / `arm64-v8a` 发布构建能力、R8、资源裁剪和保守 Baseline Profile。

当前仍不包含：

- 直播播放、直播房间列表和直播弹幕。
- 独立插件中心。
- Room 数据库、Koin 依赖注入和 Compose Navigation。

## 移动端 UI 移植(mobile 分支)

在 `mobile` 分支上参照 [BV](https://github.com/aaa1115910/bv) `feature/mobile` 的设计，把触屏移动端 UI 移植进 BiliTVNative。由于架构差异巨大（BV 多模块 + `dev.aaa1115910.bv` 命名空间 + 自研 player lib；BiliTVNative 单模块 + `com.kirin.mt` + Media3/ExoPlayer + 手搓 DI），这是**设计移植（重新实现），不是代码拷贝**。

### 背景与决策

- **单 APK 通吃 TV + 手机**：`applicationId com.kirin.mt` 不变。`MainActivity` 运行时用 `isTvUi()`（`UI_MODE_TYPE_TELEVISION` 或 `FEATURE_LEANBACK`）选 `BiliTvApp`（TV，零改动）或 `BiliMobileApp`（手机）。Manifest 挂 `LAUNCHER` + `LEANBACK_LAUNCHER` 双桌面入口，同一个 APK 出现在 TV 桌面和手机桌面。
- `AppContainer`/DataStore（设置/登录/播放进度）TV 与手机共享同一份，不拆包、不调整桌面入口。
- 移动端 UI 放 `ui/mobile/` 包，复用 `core/*` 全部引擎（网络/播放/auth/settings/storage/update），触屏交互替换 TV 焦点机制；TV 端零改动（仅个别 `private` helper 提升 `internal` 以供复用）。

### 已完成（均走云编译闭环，alpha tag 发布）

| tag | 内容 |
|---|---|
| v2.0.0-alpha.1 | 自适应外壳（`NavigationSuiteScaffold`）+ 触屏首页分区网格 |
| v2.0.0-alpha.2 | QR 登录 + 卡片式设置（`SettingsActivity`） |
| v2.0.0-alpha.3 | 触屏播放器（复用 Media3/ExoPlayer + `PlayerDanmakuLayer` + 进度/心跳/完成上报） |
| v2.0.0-alpha.4 | SMS WebView 登录 + 应用名改回 BiliMT |
| v2.0.0-alpha.5 | 登录改 SMS-only（移除移动端 QR，因 TV QR 接口在手机扫不出）+ 加固 WebView |
| v2.0.0-alpha.6 | 短信"点一下即完成"（时序修复）+ 动态 tab（关注 feed 网格 + offset 分页） |
| v2.0.0-alpha.7 | 搜索 tab（取代底栏 PGC 占位）+ 修播放器返回键退出 + 顶部留状态栏 inset |
| v2.0.0-alpha.8 | 状态栏透明+浅色图标 + 播放全屏（activity 旋转 + configChanges + 沉浸） |
| v2.0.0-alpha.9 | 底栏重复点"推荐"触发刷新+滚顶 |
| v2.0.0-alpha.10 | 后台播放（前台 service + PlayerHolder + 通知控件，去 ON_PAUSE 暂停） |
| v2.0.0-alpha.11 | 播放器设置弹窗（画质/倍速/弹幕，activeRequest 驱动 load 重载） |
| v2.0.0-alpha.12 | 选集（分P）侧栏弹窗 |
| v2.0.0-alpha.13 | 自动连播下一集（nextEpisodeCompletion） |
| v2.0.0-alpha.14 | UP 主空间页（头像/签名/关注 + 投稿网格，入口:播放器顶栏标题/"UP"） |
| v2.0.0-alpha.15 | 后台播放通知改 MediaStyle（MediaSessionService）+ 请求 POST_NOTIFICATIONS + 内容页下滑刷新 |
| v2.0.0-alpha.16 | 修通知不显示（改回普通 Service + 手动 MediaSession + 显式 startForeground + MediaStyle） |
| v2.0.0-alpha.17 | （播放器手势前序，release 已发，未单独记 notes） |
| v2.0.0-alpha.18 | 播放器手势优化（点中央暂停/长按 2x/横拖 seek 松手恢复） |
| v2.0.0-alpha.19 | 空降助手 AirJump（SponsorBlock 自动跳过广告/片头/片尾） |
| v2.0.0-alpha.20 | 播放器底栏按钮图标化（参考 TV 版） |
| v2.0.0-alpha.21 | 修拖拽 seek 松手后意外暂停 |
| v2.0.0-alpha.22 | 播放器推荐视频按钮 + 修内容页下拉刷新无效 |
| v2.0.0-alpha.23 | 修下拉刷新方向反了（上滑触发→下拉触发） |
| v2.0.0 | 稳定版：移动端 UI 移植完成，合并 mobile → mort_debug → main，单 APK 双桌面入口 |
| v2.0.1-alpha.1 | 播放器加视频分享 |
| v2.0.1-alpha.2 | 修加载列表时两个圈（顶部静止圈 + 居中转圈） |
| v2.0.1-alpha.3 | 首页内容区左右滑动切顶部 tab |
| v2.0.1-alpha.4 | 动态 tab 补 历史/收藏/追番 子 tab + 追番进季详情选集 |
| （当前分支，未打 tag） | **离线下载播放器对齐在线播放器**：`MobileOfflinePlayerScreen` 从极简裸 ExoPlayer 升级为功能对齐在线——后台播放通知（PlayerHolder+PlaybackService，抽公共 `startPlaybackService` 到 `core/player/PlaybackServiceHelper.kt`）、进度保存/续播（复用 Room `playbackRepository`，在线/离线互通）、倍速（ModalBottomSheet 七档）、画面点击暂停/拖拽 seek（`detectPlayerGestures`）、全屏/非全屏两种布局（沉浸+方向）、相关视频=已下载视频列表（2 列 `MobileVideoCard` 点击切换）、ExoPlayer 配置对齐（`createTvPlaybackLoadControl` + `setHandleAudioBecomingNoisy` + AudioAttributes）。数据源仍本地文件（合理差异）。 |
| （当前分支，未打 tag） | **离线下载列表交互对齐卡片页**：`MobileDownloadsScreen` 卡片主体 `combinedClickable`——**单击播放**（可播时）、**长按弹底部操作菜单**（YouTube「加入播放列表」复用 `MobilePlaylistPickerDialog` + 全部源「删除」）；右侧按钮列瘦身只保留下载控制（下载中/排队→暂停/取消，暂停/失败→续传，完成/取消→无按钮），播放/删除改走单击/长按。 |
| （当前分支，未打 tag） | **下载管理批量删除**：`SettingsTopBar` 加可选 `trailing` 槽；下载管理右上角**三点 `⋮`**（`Icons.Filled.MoreVert`）进入批量模式——列表项复选框、点卡勾选/取消、底部栏「已选 N/全选/删除所选」、删除二次确认后逐条 `downloadManager.delete`；顶部三点变「完成」退出。`SettingsActivity` 持有 `downloadsBatchMode`+`selectedDownloadIds`，注入 `MobileDownloadsScreen`。 |
| v3.0.5-alpha.3 | **多语言支持**：语言设置 3→6 档，新增 English / Español / Português。`ChineseTextVariant` 泛化（拉丁语系 identity，简繁只对中文变体）；`localizedContext()` locale 映射；两套设置页语言项；**移动端三入口补 LocalContext 包裹**（此前移动端 stringResource 走系统 locale，设置不生效）；数字本地化 `CountFormatter`（万/亿↔K/M/B，除数差异是代码逻辑）；`values-en` 全量英文翻译 654 key + `values-es`/`values-pt` 骨架；硬编码 UI 中文收口（UI 标签抽 stringResource，日志/来源数据保留中文） |
| （当前分支，未打 tag） | **播放列表详情批量移除**：`PlaylistDetailScreen` 编辑模式（顶栏「编辑/完成」）升级为批量移除——每卡前置复选框、点卡勾选/取消、底部栏「已选 N/全选/删除所选」、二次确认后 `store.removeVideos` 一次性过滤移除；**保留**卡片右侧单「移除」按钮（单选即时删，同时从选中集剔除）。选中集/确认弹窗局部状态持有；`YoutubePlaylistStore` 加批量 `removeVideos(playlistName, ids)`。批量字符串沿用简体（playlist 现有串本来只在 values）。 |

当前移动端能力：首页分区网格、**动态 tab 四子 tab（动态关注 feed / 历史 / 收藏（切夹）/ 追番（番剧·影视 + 想看·在看·看过筛选））**、搜索（历史/联想/排序/结果网格）、卡片式设置、短信登录、触屏播放器（播放/暂停/seek/弹幕开关/返回 + **全屏横屏沉浸** + **画质/倍速/弹幕设置弹窗** + **分P选集侧栏** + **自动连播下一集** + **UP 空间入口** + **空降助手** + **推荐视频切播** + **手势**）、**追番进季详情选集（`MobilePgcSeasonScreen`：封面/简介/同系列季切换/正片+花絮分集 → PGC PlaybackRequest）**、**后台播放（主流 MediaStyle 通知:封面+播放/暂停+锁屏控件,显式 startForeground 保活）**、**离线下载播放器（本地源,对齐在线：后台播放通知/进度续播互通/倍速/画面手势/全屏/已下载视频列表切换）**、**离线下载列表（卡片单击播放、长按加播放列表/删除,下载控制按钮居右,右上角三点批量删除）**、**播放列表详情（编辑模式批量移除：复选框+底部栏「已选 N/全选/删除所选」+单移除按钮保留）**、状态栏透明浅色图标、底栏重复点推荐刷新、内容页下拉刷新。底栏 PGC 仍为"开发中"占位（搜索已取代其位置）。

### 后续路线图（按优先级）

**P0 —— 补齐四个主 tab**

1. **PGC（影视）tab**：复用 `VideoRepository.getPgcFeed/getPgcSeasonInfo/getPgcIndex` + `PgcType`/`PgcIndexFilters`；类型横滚 tab → 季网格 → 季详情选集 → `toPlaybackRequest(epId=, seasonId=, subType=)` 开触屏播放器（PGC 分支 `MergingMediaSource` 已支持）。**（待做）**
2. ~~搜索 tab~~ **✅ 已完成（alpha.7）**。

**P1 —— 播放器 v2 + UP 空间**

3. ~~播放器增强（画质/倍速/弹幕设置弹窗、分P选集侧栏、自动连播）~~ **✅ 已完成（alpha.11/12/13）**；~~空降助手~~ **✅ 已完成（alpha.19）**；在线人数（可选，待做）。
4. ~~UP 空间~~ **✅ 已完成（alpha.14）**。

**P2 —— 个人内容 + 评论**

5. ~~历史/收藏/追番（`getHistoryPage`/`getFavoriteFolderVideos`/`getFollowingSeasons`）~~ **✅ 已完成（v2.0.1-alpha.4，动态 tab 四子 tab + 季详情选集）**。
6. 评论（`getComments`）。**（待做）**
7. 动态点赞/稍后再看（`likeDynamic`/`addToView`）。**（待做）**

**P3 —— 打磨与发布**

8. 主题（深色/跟随系统、首页 `HomeThemes` 适配手机）、失败重试、横屏/平板、启动闪屏、应用图标。**（部分完成:移动端已接入 4 套暗色主题 + 独立明/暗模式 Dark/Light/Auto,把 HomeThemes 映射成 Material 深浅 scheme;失败重试/横屏/平板/闪屏/图标待做——播放器为显式黑底白字）**
9. 2.0.0 稳定后按"大版本才合 main"规则合 `mobile` → `main`（届时打 `v2.0.0` 稳定 tag，二次确认后推）。**（当前 mobile 已合并到 mort_debug;main 待 2.0.0 稳定）**

### 已知问题 / 后续待修

- 后台播放通知样式已主流化（alpha.16），真机需确认已授予通知权限。
- PGC tab 仍占位（底栏已换成搜索,影视入口待 P0 补）。
- 内容页浅色主题未统一为深色（无 MaterialTheme 包装）,P3 主题阶段处理。**（已解决:移动端加 BiliMobileTheme 包装,映射成 Material 深浅 scheme）**
- 在线人数未做（P1 可选项；空降助手已做）。

### 待修：空降助手首次进入识别不到广告段

**现象**：很多 B 站视频要二次进入播放器，绿色跳过标记/自动跳过才生效。

**根因**：`AirJumpRepository.getAirJumpSegments` 每次进入播放器拉一次，且对第三方镜像 `bsbsb.top` 是冷连接（DNS+TLS+服务端），首击慢/失败时被 `LaunchedEffect` 里 `runCatching{}.getOrDefault(emptyList())` 静默吞成空 → 整次播放无段不跳；二次进入复用了 OkHttp keep-alive 热连接秒回才正常。

**修复方向**（计划全做）：
1. 失败自动重试（短退避），不再静默吞错。
2. 按 bvid 内存缓存，成功（含真无段 404）才缓存 → 再次进入秒回；冷连接首击靠重试保证成功，等效「预取+热连接」。
3. 失败/重试打日志，成功打条数日志。
4. TV `PlayerScreen` 与移动 `MobilePlayerScreen` 共用 repository 重试+缓存，拉段仍组合即启动（与拉流并行）。

### 待做：在线播放器命中缓存播放本地源（手机端）

**目标**：在线刷到已下载/缓存的视频，点进去视频区直接播本地缓存文件，简介/评论/弹幕仍走在线，与在线播放完全一致。B 站 + YouTube 都合并。

**方案**：`MobilePlayerScreen.loadRequest` 在 `getPlaybackInfo` 网络解析前查缓存命中（`downloadManager.downloads.first()` 按 `videoId==request.bvid && isPlayable` 匹配）。命中 → 自包含早返回块：用 `playbackFiles(id)` 建本地 `Progressive/MergingMediaSource`（镜像离线页），跳过网络取流；元数据/简介/评论/弹幕照常在线加载。缓存不命中 → 原网络路径零改动。

**决策**（用户已定）：
- 只做手机端；TV 无下载功能不涉及。
- 命中缓存即跳过网络解析，直接本地播。
- 清晰度只读：命中缓存时 HD 按钮+菜单换成静态文字（显示缓存 `qualityLabel`），不可切换。
- 已缓存即隐藏下载入口按钮。

**改动点**（全在 `MobilePlayerScreen.kt`）：
1. 新增状态 `usingCachedPlayback` / `cachedDownloadId`。
2. `loadRequest` 加缓存命中早返回块（合成 `PlaybackInfo`：qualities=[缓存清晰度]、tracks 空；本地 MediaSource）。
3. 清晰度菜单块（1416 行）命中缓存时渲染静态文字。
4. 下载按钮（1512 行）加 `&& !usingCachedPlayback`。

**边界**：只匹配 `isPlayable`（媒体分件全 COMPLETED）；部分下载回落在线；进度续播复用 `saveProgress/getSavedProgress`（bvid/cid 正确，与在线互通）。缓存命中按 `videoId + cid` 双匹配——多 P 视频只命中已下载的那个分P，其它分P正常走在线。

### 工程约束（移动端专用）

- 本地无 Android SDK，走云编译闭环；`mobile` 分支 push 不触发 CI（只有 main/master/mort_debug + tag 触发），先 push 再打 alpha tag 验证。
- 每阶段流程：实现 → commit/push `mobile` → `gh workflow run android-build.yml --ref mobile`（debug 验证编译）→ 绿后打 `v2.0.0-alpha.N` 并在 `RELEASE_NOTES.md` 加段 → release 构建绿 → 真机验收。
- alpha tag 可直接推；稳定版 tag 推送前二次确认。每次打 tag 递增，不删旧 tag 重建同名。
- **连续打 tag 必须等上一个 release CI 的 Create Release 步骤完成再推下一个**：工作流有"Delete tags without releases"步骤会删"远端有 tag 但无 release 且非当前 tag"的孤儿 tag,连推会撞清理竞态把刚推的新 tag 删掉(alpha.12/13 曾被误删,已补回)。
- 复用 `core/*` 引擎，不重写；TV 端零改动（除非提升 helper 可见性）。

## YouTube 内容集成（P11）

> 目标：把 YouTube 内容加进原生 搜索 / 首页热门 / 动态 三个入口。数据来自 FreeTube / YouTube.js（MIT）的 InnerTube 私有 API。**关键技术约束：InnerTube 逻辑全在 JS，且 FreeTube 本体是 AGPL（biliMT 是 MIT），只能 Kotlin 独立重写协议，不复用其代码。** 详见 `docs/youtube-api-notes.md`。

### 已定方案
- **内容数据**：原生 Kotlin 重写 InnerTube（guest 认证，无需 key）。
- **动态源**：手动配置频道列表，逐频道拉最新视频合并（免登录）。**（P11-13）动态页把 B 站动态 + YouTube 关注统一成一条流：B 站秒出、YouTube 后台 5s 兜底合并按 pubdate 倒序；移除独立「YouTube 关注」tab；无频道时动态纯 B 站。**
- **播放（P11-09）**：`POST /player`（WEB→ANDROID 回退）+ `n` 解密，用隐藏 WebView 执行 JS。主路径为无 PO token 直连（多数视频可播）；PO token（jnn WASM VM）best-effort，真机迭代。

### 播放（P11-09）实现要点
- `YoutubePlaybackResolver`：`/player` 解析 `adaptiveFormats`，按 codec 偏好挑视频 + 最佳音频，产出 progressive `PlaybackInfo`（segmentBase=null）。
- 播放器复用：TV/移动走 progressive `MergingMediaSource`（镜像 PGC），门控 B 站专属副作用（heartbeat/元数据/弹幕）。
- `YoutubeJsExecutor`：隐藏 WebView JS 引擎（eval 桥、懒建、会话级复用），SMS 登录同款机制。
- `YoutubeNDecryptor`：拉 base.js + 隐藏 WebView 整体 eval + 正则识别 transform 解密 `n`（AST 解析未移植，正则法可能需真机调）。
- `YoutubeBotGuard`：jnn PO token 结构占位，失败降级不阻塞直连。
- **运行时依赖真机**：`n` 解密（base.js 结构常变）与 PO token（jnn WASM 完整性校验）无法用云编译验证，需真机手测迭代。

### 播放高清（P11-14）实现要点
- **现状**：YouTube 实际最高只到 720p（Case B 优先单个合并 progressive 流 itag 18/22，≤720p）；`adaptiveFormats` 里的 1080P/2K/4K 已解析但选不到，且 `signatureCipherUrl` 只回填未签名 url、`s` 解密未实现（403）。
- **Tier 1（核心）**：① 复用 `YoutubeJsExecutor` 做 `s` 签名解密（拉 base.js 识别签名函数执行）→ 解锁 adaptive 高清直链；② `pickVideo` 把可解 adaptive 高清提到 progressive 之前 + 复用 `CodecCapabilityProbe` 过滤设备解不了的轨道 + `buildInfo` 把全部可播 quality 写进 `PlaybackInfo.qualities`（面板出 1080P/2K/4K 多档）；③ `parseFormat` 补解析 `initRange`/`indexRange` 填进 `PlaybackTrack.segmentBase` → 自动进 `PlayerScreen.kt:1407` DASH 分支（现有 `buildDashManifest` 已正确处理 SegmentBase/Initialization，零改动喂流）。
- **Tier 2（增强）**：PO token（jnn，`YoutubeBotGuard`）跑通，覆盖「YouTube 剥光所有 adaptive url」的极端场景。
- **Out of scope**：DRM 保护内容、8K、HDR（同 B 站理由：TV 面板普遍不支持，探测不到会黑屏）。
- 详见 `docs/youtube-hd-playback.md`。

### 反爬与废弃端点（实测关键）
- `hl`/`gl` 用 `zh-CN/CN` 触发反爬（搜索返回 `backgroundPromoRenderer`「出了点问题」）；必须用 `en/US`。
- 通用热门 `FEtrending` 已被 YouTube 废弃（400，`/feed/trending` 已移除）；改用 topic 热门（游戏/体育/播客）。
- renderer 因端点/客户端而异：WEB 搜索 `videoRenderer`、topic 热门 `gridVideoRenderer`、ANDROID `compactVideoRenderer`、频道页 `lockupViewModel`。

### 里程碑
| ID | 任务 | 状态 |
| --- | --- | --- |
| P11-01 | InnerTube 数据层（core/youtube 包 + source 字段 + DI） | ✅ Done |
| P11-02 | 搜索 B站/YouTube 来源切换（TV+移动） | ✅ Done |
| P11-03 | 首页 YouTube 热门分区 | ✅ Done |
| P11-04 | 动态页 YouTube 关注 tab（TV+移动，免登录） | ✅ Done |
| P11-05 | 首页迁移自动启用 YouTube 分区 | ✅ Done |
| P11-06 | 反爬与 renderer 解析修复（搜索/热门可用，用户确认） | ✅ Done |
| P11-07 | YouTube API 笔记文档 | ✅ Done |
| P11-08 | 设置页 YouTube 频道管理（TV+移动） | ✅ Done（v2.0.8-alpha.4，频道解析 + 双端面板） |
| P11-09 | Phase 2 YouTube 播放（InnerTube /player + n 解密，隐藏 WebView JS 引擎） | ✅ Done（v2.0.9-alpha，编译绿；n/PO token 运行时待真机迭代） |
| P11-10 | 移动播放器 YouTube 简介/评论 Tab | ✅ Done（编译绿，运行时待真机） |
| P11-11 | 移动端 UP主页关注/加入播放列表/动态播放列表tab | ✅ Done（v2.0.8-alpha.11，编译绿，运行时待真机） |
| P11-12 | 多播放列表（长按弹菜单→选列表/新建）+ 播放列表两层+长按拖动排序 + 播放器◀▶/去弹幕/相关视频=列表后续 | 实施中（编译绿待云编译，运行时待真机） |
| P11-13 | 动态页统一流：B 站动态 + YouTube 关注合并（TV+移动，5s 兜底，移除独立 YouTube tab） | 实施中 |
| P11-14 | YouTube 高清播放（Tier 1：`s` 解密 + adaptive 首选 + DASH 播放 + 硬件过滤 + 多档清晰度；Tier 2：PO token） | Pending（方案见 `docs/youtube-hd-playback.md`） |
| P11-15 | WebDAV 备份/还原 YouTube 关注频道（跨设备复用关注列表） | 实施中（代码改完待云编译） |
| P11-16 | YouTube 多语言配音修复 + 音轨切换 + 默认画质 | ✅ Done（云编译绿，详见 `docs/youtube-hd-playback.md` §6.11） |
| P11-17 | YouTube 播放历史 + 断电续播（`PlaybackProgressStore` 守卫放宽 + `YoutubeHistoryStore` + 移动端历史子 tab） | ✅ Done（云编译绿，详见 `docs/youtube-hd-playback.md` §6.12） |
| P11-18 | YouTube 历史并入「历史」tab（本地历史与 B 站历史**混合按播放时间倒序**、YouTube 绿框，未登录也显示本地历史；移除独立「YouTube 历史」子 tab） | ✅ Done（云编译绿） |
| P11-19 | TV 版 YouTube 功能补全（见下方「TV 版 vs 移动端 YouTube 功能差异待办」分项表） | 待办 |
| P11-20 | YouTube 相关视频（对齐 LibreTube：`/next` secondaryResults 的 compactVideoRenderer，TV 相关视频面板 + 移动简介 tab 相关视频区 + 播完自动连播） | 实施中（代码改完待云编译，运行时待真机） |
| P11-21 | YouTube 搜索排序对齐 B 站 4 项（综合/最多播放/最新发布/评分，key 复用 `YoutubeSearchParams`，TV+移动两端排序条放开到 YouTube 源，切源重置默认「综合」） | 实施中（代码改完待云编译，运行时待真机） |

### TV 版 vs 移动端 YouTube 功能差异待办（P11-19）

> 2026-08-10 对比 `ui/`（TV）与 `ui/mobile/`（移动端）的 YouTube 实现所得。**核心层（`YoutubeRepository` / `YoutubePlaybackResolver` / `SabrClient` / `InnerTubeClient` / `YoutubeBotGuard` 等 ~4000+ 行）两端 100% 共用，播放/协议能力完全对等（SABR DASH 4K、多语言音轨、WebVTT 字幕、倍速、续播、画质、PO Token、n/s 解密）。缺口全在 UI 交互层与"内容组织/发现"层。** 播放/设置核心项（默认画质、默认倍速、字幕、弹幕、空降助手）两端均已对齐 LibreTube 三项设置，无差异。

按优先级排列的 TV 版补全待办：

| 优先级 | 待办 | 移动端参照 | 说明 |
| --- | --- | --- | --- |
| 🔴 P0 | **YouTube 频道主页** | `MobileYoutubeChannelScreen.kt`（244 行）+ `MobileYoutubeChannelUiState.kt` | TV 版无专门频道页，无法浏览单频道视频/原地关注/看头像简介，仅能在设置页管列表 + 动态页看聚合流。复用 `YoutubeRepository.getChannelVideos` + `TvVideoGrid` + D-pad 焦点移植。 |
| 🔴 P1 | **YouTube 播放列表管理** | `MobileYoutubePlaylistPage.kt`（378 行）+ `MobileYoutubePlaylistDialogs.kt`（239 行） | TV 版完全无播放列表概念（创建/编辑/拖动排序/连播队列）。`YoutubePlaylistStore` 核心层已存在但仅移动端消费。D-pad 模拟长按拖动重排可参照 `SettingsYoutubeChannelsColumn.kt` 的 `ReorderableLazyColumn`。 |
| 🟡 P2 | **搜索联想 + 搜索历史** | `MobileSearchScreen.kt`（联想 250ms 防抖 + `SearchHistoryStore` 持久化） | TV 搜索（`SearchScreen.kt`，1279 行）用屏幕键盘 `TvKeyboardInput`，但无下拉联想、无历史记录入口。复用 `VideoRepository.getSearchSuggestions` + `SearchHistoryStore`。 |
| 🟡 P2 | **队列/连播 + 听视频模式** | `MobilePlayerScreen.kt`（`onStartPlaylist` 整列表连播 + 顶栏耳机按钮禁视频轨） | TV 版 `PlayerScreen` 单视频播放，无播放队列；无音频-only 入口。连播可接 P1 播放列表落地后做。 |
| 🟢 P3 | **首页 YouTube 热门挂载核实** | `HomeSection.YoutubeTrending` enum 已存在 | `HomeSection.YoutubeTrending` 已定义但需核实 `HomeScreen.kt` 是否真正渲染该 section；若未挂载则补齐（复用 `YoutubeRepository.getTrending(tab)`）。 |

**说明**：以下差异为 TV/移动端形态必然产物，不算缺陷，不列入待办——屏幕键盘 vs 软键盘、D-pad 焦点 vs 触屏手势、`PlayerOverlay` 覆盖层 vs 底栏 DropdownMenu、发送弹幕内联输入（TV 无软键盘不便）。两端 YouTube 源排序已由 P11-21 对齐 B 站（综合/最多播放/最新发布/评分）。

### 发布
- 测试版 `v2.0.8-alpha.1/.2/.3` 已发布验证；搜索/热门可用。

## 测试计划

手工设备检查：

- 冷启动。
- 遥控器焦点导航。
- 侧边栏 tab 切换。
- 搜索。
- 视频播放。
- 画质切换。
- seek。
- 续播进度。
- 弹幕开关。
- 从播放器返回。
- 直播播放（当前暂缓，恢复直播阶段再测）。
- 登录态重启后保持。

命令行检查：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
adb install -r $env:USERPROFILE\.gradle\bilitv-native-build\app\outputs\apk\debug\app-debug.apk
adb shell dumpsys meminfo com.kirin.mt
adb shell dumpsys gfxinfo com.kirin.mt
```

回归对比：

- Flutter 和原生版本使用同一个 BVID 对比。
- 对比视频 URL 选择、CID、画质列表、编码和播放请求头。
- 对比弹幕数量和时间点。
- 对比历史/进度上报行为。

## 发布策略

初期原生 app 包名定为：

```text
com.kirin.mt
```

Flutter 参考 app 继续保留在原项目中，用于行为对照和回退参考。

原生功能等价足够后：

1. 如有需要，加入 auth/session/settings 迁移逻辑。
2. 第一版主发 `armeabi-v7a` 发布包。
3. 保留 `arm64-v8a` 构建能力，遇到 64-bit-only 设备需求再发布。
5. 在原生播放稳定前，继续保留 Flutter app 作为回退参考。

## 风险清单

高风险：

- B 站 DASH 播放在不同 Android TV 设备上的兼容性。
- 播放请求头和 Cookie 行为正确性。
- 弹幕如果用太多 Compose 节点实现，会导致性能问题。
- 嵌套 LazyRows、网格和播放器面板中的 TV 焦点回归。

中风险：

- 二维码登录流程等价。
- 直播 WebSocket 包解析和心跳行为（后续恢复直播时处理）。
- 画质切换后没有 player 泄漏。
- 低内存设备上的图片缓存调优。

低风险：

- 静态设置页面。
- 基础搜索和历史页面。
- 应用壳和侧边栏 UI。

## 已知崩溃 / 待修问题

> 记录真机/日志发现的崩溃，先记问题、以后再修。每条含现象、根因、复现环境、修复方向。

### C-01 应用内更新下载 SSL 握手失败崩溃

- **现象**：`UpdateDownloader.download` 抛 `javax.net.ssl.SSLHandshakeException: connection closed`（根因 `java.io.EOFException: connection closed`），触发 UncaughtException 崩溃。
- **复现环境**：v2.0.8-alpha.14，Sony XQ-EC72（Android 16），下载 GitHub Releases 更新包时。
- **根因**：下载更新包时 SSL 握手被服务端/网络中断（GitHub Releases 下载域名 `objects.githubusercontent.com` 可能被墙/限流，或网络抖动）。
- **修复方向**（未做）：
  - 更新下载加 SSL 重试 + 超时处理，失败降级为「提示手动下载」而非崩溃。
  - 检查设备能否访问 GitHub Releases 下载域名；必要时走代理/CDN 镜像。
  - 用 `runCatching`/`try-catch` 包裹下载，避免 SSL 异常冒泡到 UncaughtException。
- **状态**：待修（与 YouTube 高清无关，独立问题）。

### Y-01 YouTube SABR 播放 RELOAD_PLAYER_RESPONSE 终端失败（部分视频无法播放）

- **现象**：部分 YouTube 视频（实测 2160p 的 `jNl6YkkzKxw`）起播即失败，播放器报 `ERROR_CODE_IO_UNSPECIFIED` / `Source error`，无法播放。
- **复现环境**：v3.0.2-alpha.3，Sony BRAVIA 4K AE2（Android 14），播放 2160p YouTube 视频时。
- **根因**：
  1. WEB `/player` 返回的 38 个 adaptive format 全部被 PO token 锁死（`parsedAdaptive=0`、`firstUrl=EMPTY`、`firstCipher=none`），普通直链路径走不通 → 回退 SABR。
  2. SABR 会话在第一段请求（seg=0）就收到 `RELOAD_PLAYER_RESPONSE`（part 46），video（itag=313）/audio（itag=139）两条流都收到。
  3. 代码把 `RELOAD_PLAYER_RESPONSE` 当终端错误（`SabrMediaFetcher.kt:154` → `SabrDataSource.kt:49-52`）→ evict 会话 → 抛 `Source error`。
  4. 播放器 `onPlayerError` 直接进 Failed 状态，无自动重试。
  - ⚠️ **根因更正（已推翻 itag/poToken 归因，见 `docs/youtube-hd-playback.md`「alpha.83 更正」段）**：经多轮排查确认 RELOAD 真因**不在** itag 选择/分类、**不在** SABR 端 poToken、也**不在** visionOS ClientInfo（LibreTube `SabrClient` 同样硬编码 visionOS ClientInfo）。真因是 **ustreamerConfig 来源**：我们走 NewPipe visionOS 客户端拿到**未 attested 的 visionOS-bound** ustreamerConfig，对需要 attestation 的视频（jNl6YkkzKxw）服务端直接 RELOAD（不可续命，到不了 status=2）；LibreTube **默认走 Piped 后端**（`/streams/{id}` 自带 poToken → 回已 attested 的 WEB-bound ustreamerConfig）故能播，差异纯粹在 ustreamerConfig 来源。RELOAD_PLAYER_RESPONSE 语义是「streams expired or new config」（会话/配置被判无效），attestation 走 `STREAM_PROTECTION_STATUS status=2/3` 另一通道，勿混为一谈。alpha.10「两次选轨不一致」、alpha.13「poToken contentBinding」、alpha.82/83「itag248 音频误分类」均被推翻。**修复方向**：接 Piped 后端产出 SABR 会话（对齐 LibreTube 默认路径），下游 SABR 机制不变。
- **修复方向**（治本）：
  - `RELOAD_PLAYER_RESPONSE` 是 YouTube 明令「player response 过期，去重载 /player 拿新 formats/poToken/sabrUrl」的信号，**不是** backoff、也不该当 terminal 直接失败。
  - 把 `RELOAD_PLAYER_RESPONSE` 从「terminal → evict → 失败」改成「触发一次 player response 重载（重新 harvest，拿新 poToken/sabrUrl/formats）后重试」。
  - 这是 SABR 结构性改动，风险较高，需单独规划（涉及重新调 /player + 重建 SABR 会话 + 重试）。
- **状态**：待修。

### F-01 TV 视频退出后焦点被头像抢占（播放后焦点消失）

- **现象**：退出视频后，焦点恢复 effect 成功把焦点拉回原视频卡片（`restore success`），但约 250~300ms 后头像又抢走焦点（`avatar focused ... openMyPage=true`），用户看到焦点环跳到头像/「我的」页 = 「焦点消失」。
- **复现环境**：v3.0.2-alpha.3，Sony BRAVIA 4K AE2，每次退出视频稳定复现。
- **根因**：
  1. 恢复 effect 成功把焦点拉到视频卡片。
  2. 恢复一成功，`clearFocusRestoreRequest` 立即把 `playbackFocusRestoreDestination` 置 null → `suppressAccountAutoConfirm` 变 false。
  3. 但约 250~300ms 后，Compose 焦点系统在内容从 `SaveableStateHolder` 还原后做了一次「延迟焦点回落」，把焦点落到侧栏第一个可聚焦节点（头像）。
  4. 此时抑制已撤，头像 `autoConfirmOnFocus=true` → 直接 `openMyPage=true`。
  5. 关键：现有 `suppressAccountAutoConfirm` 只抑制了头像的 autoConfirm（不打开「我的」页），**没阻止头像「接收焦点」**，所以焦点环仍跳到头像。
- **修复计划**（治本，两处改动，一次提交）：

  **改动 1：`AppSidebar.kt` — 抑制时侧栏真正不可聚焦**
  - `AppNavItem` 加 `suppressAutoConfirm: Boolean = false` 参数。
  - `AppNavItem` 调用点（AppSidebar 内 193-207 行）传 `suppressAutoConfirm = suppressAccountAutoConfirm`（`AccountNavItem` 已传，见 177 行）。
  - `AccountNavItem` 与 `AppNavItem` 的 `BiliFocusableSurface` modifier 链上，当 `suppressAutoConfirm` 为 true 时加 `Modifier.focusProperties { canFocus = false }`（需 import `androidx.compose.ui.focus.focusProperties`），让延迟焦点回落无处可落，只能留在视频卡片。

  **改动 2：`AppShell.kt` — 延长抑制窗口覆盖延迟回落**
  - 新增常量 `PlaybackFocusRestoreSuppressHoldMs = 400L`。
  - `clearFocusRestoreRequest`（354-363 行）里，把立即清 `playbackFocusRestoreDestination = null` 改成延迟清：`coroutineScope.launch { delay(PlaybackFocusRestoreSuppressHoldMs); if (playbackFocusRestoreDestination == destination && key == playbackFocusRestoreRequestKey) playbackFocusRestoreDestination = null }`。
  - 延迟清不会让恢复 effect 重跑（`restoreFocusRequestKeyFor` 返回的 key 不变），backstop（600 帧 ≈ 10s）也远长于 400ms 不会误清。

- **实施步骤**：① 改 `AppShell.kt`（改动 2）→ ② 改 `AppSidebar.kt`（改动 1）→ ③ 云编译绿 → ④ 打测试 alpha tag → ⑤ 真机验证。

- **验收标准**：
  1. 真机日志每次 `restore success` 后不再出现 `avatar focused`。
  2. 退出视频后焦点环稳定停在原视频卡片，不跳头像、不打开「我的」页。
  3. 回归：正常侧栏导航（头像/导航项聚焦、autoConfirm 打开「我的」页）不受影响。

- **验证方式**：云编译绿 → 打测试 alpha tag → 真机装 debug 版，退出视频后 grep `BiliMT:Focus` 确认无 `avatar focused`。

- **状态**：待修。

## 实现原则

- 先匹配 Flutter 行为，再改善架构。
- 播放器状态和页面状态隔离。
- 优先使用 Media3 原生能力，再考虑本地服务器回退方案。
- 从第一版就限制图片尺寸。
- D-pad 焦点必须确定性，不在复杂网格中依赖默认焦点搜索。
- 播放中避免大范围重组。
- 先测量，再优化包体或内存。
- 电视端优先级为流畅、美观、内存合理，功耗不作为主要约束。
- 不为了省电牺牲焦点动画、图片预取和播放器体验。
- 资源使用仍然必须有上限，避免缓存、弹幕和图片解码导致 UI 卡顿。

## NDK / so 引入原则

第一版默认不使用 C/C++ 或自定义 so。优先使用 Kotlin、Media3、OkHttp、Coil 和系统硬解能力完成重写。

不建议第一版引入 NDK 的原因：

- 增加 ABI 包体。
- 增加 Gradle 和 CMake 构建复杂度。
- JNI 边界会增加调试成本。
- 原生崩溃排查成本高于 Kotlin 崩溃。
- 大多数业务逻辑和 UI 性能瓶颈不需要 C/C++ 解决。

只有满足以下条件之一，才考虑引入 C/C++：

- 弹幕轨道分配、碰撞计算等纯计算热点在 Kotlin 优化后仍然明显卡顿。
- 需要复刻某些 native 签名、加密、压缩或二进制协议算法。
- 某个纯计算模块稳定占用过多 CPU，并且能被清晰隔离。
- 必须接入现成 native 库，例如特殊解码、特殊压缩或协议解析。

播放器不自研原生解码，优先交给 Media3 ExoPlayer 和系统硬解。弹幕第一版使用字节跳动 `danmaku-render-engine` 原生叠加层；如后续压测仍有瓶颈，再评估是否替换为自研 Canvas，或只把轨道分配、碰撞计算抽成原生 so。

## 已定决策

- 最终包名：`com.kirin.mt`。
- 最低 Android 版本：初步定为 minSdk 23；可以为覆盖更老电视评估 minSdk 21，但不能因此牺牲美观、流畅度和核心库选择。
- ABI 策略：第一版主发 `armeabi-v7a`；工程保留 `arm64-v8a` 构建能力，不把实现锁死在 32 位。
- 更新检查：仅做手动检查（设置 → 系统设置 → 程序更新），从 GitHub Releases API 拉取最新 tag 与本地 versionCode 对比，按设备 ABI 选 asset；不接入自动/后台检查。
- 插件策略：不做可扩展插件系统，不保留独立插件标签页；只保留空降助手，在设置页提供开关。
| P11-62 | Crashlytics 集成 + 日志手动「上报」(日志查看页一键把日志尾部送 Crashlytics 非致命报告) | 实施中(代码完成待云编译;google-services.json 未就位时插件条件禁用,待用户在 Firebase 控制台建项目后放入 app/ 目录自动激活) |
| P11-67 | 声明码率口径修正:ABR 带宽改用 averageBitrate(真平均)优先,peak 回落;calib 采样折算机制整体取消(required=裸声明=实需);顶档 sustained 门槛 0.6→×1.1(真平均口径 VBR 尖峰余量);升档重锚锚裸声明。缘起:23:00 真机 4K 升档失败复盘——`bitrate` 字段是 VBR 峰值(比真平均高 ~60-75%),calib/0.6 gate 连环补偿注定失真 | 实施中(代码完成待云编译,详见 `docs/youtube-hd-playback.md` §6.25 与 `docs/youtube-sabr-abr-upshift-notes.md` §16) |
| P11-68 | 顶档定向冷却:水位急救从顶档(2160p)降下时 excludeTrack 该顶档 3min,防「重填突发过门槛→升 4K→贴地漏光→又降」边缘横跳(23:28-31 真机 3.5min 两轮循环、每次级联切档卡顿);只锁顶档,1440p/1080p 升降照常(与已取消的全档冷却本质不同) | 实施中(代码完成待云编译,详见 `docs/youtube-sabr-abr-upshift-notes.md` §17) |
| P11-69 | TV YouTube 播放列表详情页「正在播放」标记(用户反馈:进列表看不出播过哪条):播放历史 lastPlayedAtMs 最新的视频行,序号换粉色 ▶ + 标题变粉(全列表唯一,从未播过无标记) | 实施中(代码完成待云编译) |
