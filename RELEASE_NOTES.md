# BiliMT 版本发布说明

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
