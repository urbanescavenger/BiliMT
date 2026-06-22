# BiliMT 版本发布说明

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
