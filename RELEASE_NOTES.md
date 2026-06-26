# BiliMT 版本发布说明

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
