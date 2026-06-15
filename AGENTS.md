# BiliMT 开发代理规则

## 项目目标

使用 Kotlin、Jetpack Compose、Compose for TV 和 Media3，将 `BiliMT` 构建为原生 Android TV 应用。BiliMT 基于 BiliTVNative 1.0.0 继续开发。

现有 Flutter 项目 `../BiliTV` 是行为参考。除非用户明确要求，否则不要修改 Flutter 项目。

## 任务纪律

- 只处理用户明确要求的任务。
- 除非当前任务编译、运行或一致性维护必需，否则不要顺手生成后续功能代码。
- 不要为未来功能创建当前没有实际用途的占位抽象。
- 变更范围要小，便于审查和运行。
- 对复杂模块，先定义接口和数据结构；只有在用户确认或当前任务明确需要时，再实现具体行为。
- 保持 `DEVELOPMENT_PROGRESS.md` 最新。每完成一个可验证任务，都要在同一次变更中更新状态和验证备注。

## 当前产品决策

- Android 包名：`com.kirin.mt`。
- 最低 SDK：当前 `minSdk 23`；只有在不损害 UI 质量、性能、Compose 或 Media3 的前提下，才评估 `minSdk 21`。
- 首发 ABI：`armeabi-v7a`。
- 保留 `arm64-v8a` 构建能力，但不要把它作为首个主发布包。
- 不做应用内更新检查。
- 不做可扩展插件系统。
- 只保留空降助手作为内置设置开关。
- 直播播放暂缓，直到用户明确要求恢复。
- 弹幕渲染可以使用已内置的字节跳动 `danmaku-render-engine` `DanmakuView`；除非性能分析或产品需求证明有必要，否则不要替换为自定义渲染器。
- 设置页固定分为 `播放设置`、`UI/UX`、`系统设置` 三组；播放器相关开关不要放回系统设置里。
- 关于页是设置页右侧的纯展示面板：焦点移到“关于”行时自动展示，右侧项目地址、协议、二维码和第三方库信息不可获得焦点、不可点击。

## 设计系统规则

- 在 Material 3 for TV / Compose for TV 之上使用 `BiliTV 设计系统`。
- 所有颜色、间距、圆角、焦点缩放、动画时长和缓动曲线必须来自 `BiliTokens.kt`。
- `BiliPink` 必须是 `#FB7299`。
- 不要在 Composable 内硬编码 `Color(0x...)`、`#FF...`、随机 `dp` 或随机动画时长。
- 如果需要新的视觉值，先把它加入 tokens。
- 焦点视觉语言应使用缩放、边框、阴影和 elevation，不要只依赖颜色。
- 优先保证电视远距离可读性和 D-pad 操作清晰度，而不是手机端的信息密度。

## 主页视觉与性能规则

- 主页、搜索、动态、历史、设置、侧边栏和标签栏使用主页专用主题系统；播放器视频内容不跟随主页主题。
- 液态玻璃开启和关闭两条路径都必须可用。开启时只影响卡片、导航、设置、搜索和播放器覆盖层控件；播放器 `SurfaceView` 本体永远不参与采样、裁切、透明或变换。
- 主页主题固定为 4 种：默认粉、深黑、高级灰、蓝灰。新增主题色必须先进入 `BiliTokens.kt`，再通过 `HomeColorScheme` 暴露给页面。
- 视觉性能模式固定为 3 档：流畅、均衡、精致。设置必须通过 DataStore 持久化。
- 流畅档优先保证低端电视可用：关闭平滑滚动、焦点阴影、封面模糊、流光、重动画、封面预取和图片内存缓存；列表封面使用较低尺寸和 `RGB_565`。
- 均衡档是默认推荐档：保留主题色、假玻璃、轻缩放、边框、文字颜色过渡、封面轻提亮和平滑滚动，但不启用高成本实时模糊。
- 精致档必须由用户手动开启：在均衡档基础上启用更强玻璃氛围、环境高光、更高质量缩略图、更大的图片缓存，以及跟随主题色的焦点卡片斜向流光、液态玻璃感边缘、轻微放大和卡片上浮。
- 进入播放器后，主页 Composable 必须释放或暂停；主页背景、卡片阴影/流光、封面预取等不应在播放期间继续运行。

## TV 焦点规则

- D-pad 焦点必须确定。
- 复杂网格不要依赖默认最近邻焦点搜索。
- 侧边栏到内容区、内容区到侧边栏的切换必须使用 `FocusRequester` / 焦点属性。
- 从播放器、对话框、面板或设置页返回时必须恢复焦点。
- 从播放器返回首页时，尽量聚焦之前选中的视频卡片。
- 长按 D-pad 不得丢焦点，也不能跳到隐藏项。

## 播放器规则

- 使用 Media3 ExoPlayer。
- 默认视频渲染使用 `SurfaceView`。
- 不要对视频 surface 本身应用圆角裁切、透明度动画、缩放变换或复杂 Compose 特效。
- 控制层、弹幕、面板和叠加层作为独立 UI 层叠在播放器上方。
- 播放请求使用专用的 Media3 `OkHttpDataSource.Factory` 包装器。
- 播放分片请求必须稳定携带 B 站请求头和 Cookie。
- 在选择播放编码偏好或 B 站播放参数前，先探测设备 MediaCodec 能力。
- 编码探测只能作为参考，不能当作绝对事实。播放仍必须支持 H.264 回退和设备级降级规则。
- 不要保留常驻播放器 HUD 或诊断叠加层。性能排查使用 `adb shell dumpsys gfxinfo`、`dumpsys meminfo`、日志和可删除的临时 instrumentation。
- 临时诊断信息绝不能显示 Cookie、SESSDATA、tokens 或其他敏感值，完成排查后必须清理。
- 播放器状态必须与普通页面状态隔离，避免播放期间大范围重组。
- 播放器内不要新增独立周期性 `Timer` 或 coroutine 轮询；播放位置、缓冲、时钟分钟变化、在线人数节流和迷你进度条状态应合并到 `BiliMotion.PlayerProgressUpdateMs` 的生命周期绑定循环。完成动作延迟、控制层自动隐藏和 seek 预览自动确认只允许作为可取消的一次性延迟。
- 在 `onPause` 保存播放进度，不要等到 `onStop` 或 `onDestroy`。

## 网络与 Bilibili 规则

- OkHttp 必须在需要时支持 Bilibili API 的 Brotli 响应。
- 直播 WebSocket 包体的 Brotli 解码必须显式处理。
- 需要 WBI 签名时，使用纯 Kotlin 实现。
- 除非有测量依据，否则不要为了 WBI 引入原生加密库或额外底层库。
- 请求头和签名行为要尽量贴近 Flutter 参考实现。
- 二维码登录轮询必须感知生命周期，应用进入后台时暂停。

## 图片规则

- 使用 Coil 加载图片。
- 海报和头像请求必须始终限制尺寸。
- 明确设置内存缓存和磁盘缓存上限。
- 低内存路径中的列表海报缩略图可以使用 `Bitmap.Config.RGB_565`。
- 不要对详情图、头像、透明图、渐变图或快进预览雪碧图全局强制 `RGB_565`。
- 不要预加载整页或全部分类。只预取可见窗口附近的内容。

## 弹幕规则

- 不要把每条弹幕渲染成一个 Compose 节点。
- 使用已批准的字节跳动 `danmaku-render-engine` 原生 `DanmakuView` 叠加层；如果以后移除该引擎，再使用基于 Canvas 的自定义叠加层。
- XML 获取、解码和解析不能运行在 UI 线程。
- 使用字节跳动 `DanmakuView` 时，轨道分配、碰撞计算和帧节奏交给引擎处理；应用代码不要在外层增加 coroutine `delay()` 重绘循环。
- 如果替换为自定义渲染器，轨道分配和碰撞计算必须在 UI 线程外执行，绘制循环必须用 `Choreographer` 或 `withFrameNanos` 与帧同步。
- 只有在性能分析证明 Kotlin 或已批准引擎是瓶颈后，才考虑用 C/C++/so 做弹幕轨道分配或碰撞计算。

## 构建规则

- 从一开始就使用 Gradle 版本目录 (`libs.versions.toml`)。
- 依赖必须集中管理。
- 发布构建应开启 R8 和资源裁剪。
- 即使首发 `armeabi-v7a`，也要保留 `arm64-v8a` 构建支持。
- 当前发布构建已包含保守 Baseline Profile；只有主路径明显变化或实测启动性能回退时再更新 profile。

## 编码风格

- 优先使用 Kotlin、coroutines、Flow、DataStore、Coil、OkHttp、Media3 和当前 `AppContainer`。
- 当前工程未使用 Room、Koin 或 Compose Navigation；不要为了“规范化”主动引入这些依赖。只有出现明确的数据查询/同步需求、依赖注入复杂度或路由栈需求时再单独评估。
- 避免为核心服务使用临时全局单例。
- 对 Network、Repository、Player、Storage、UseCase 和 ViewModel 依赖，使用轻量依赖注入或显式 `AppContainer`。
- 在真正出现第二个使用场景前，不要过度抽象。
- 文件尺寸保持合理。在文件变得难以审查前按职责拆分。
- 不要一次性输出几千行复杂模块代码。
- 静态 UI 文案必须放在 `res/values/strings.xml`，并在 Compose 中通过 `stringResource()` 访问。
- 不要在 Composable 内硬编码面向用户的中文或英文文案。服务端内容、日志和临时 debug-only 标签除外。
- 只为不明显的决策或复杂 TV/播放器行为添加注释。
- 与 `DEVELOPMENT_PLAN.md` 中的既定项目决策保持一致。

## 验证

每个已实现切片都运行最小但有效的验证：

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r $env:USERPROFILE\.gradle\bilitv-native-build\app\outputs\apk\debug\app-debug.apk
adb shell dumpsys meminfo com.kirin.mt
adb shell dumpsys gfxinfo com.kirin.mt
```

涉及播放时，使用相同的 BVID、CID、画质、编码、请求头和弹幕时间，对比 Flutter 参考应用行为。
