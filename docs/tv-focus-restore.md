# TV 焦点恢复(D-pad)机制与排障

TV 端所有「起播 → 返回 → 焦点丢」类问题的判读与修法都落在这份文档的模型里。
日志 tag:`BiliMT:Focus`(关键节点)、`BiliMT:FocusDiag`(按键级 + GAINED/LOST 节点级)。

## 1. 分层与 dispose 模型(AppShell)

`AppShell` 的层级(自上而下):

```
Box(root, focusDiag("root"))
├── if (visiblePlaybackRequest == null) { …Home/Sidebar/各 destination 内容… }   ← 播放期间整体卸载
├── if (visiblePlaybackRequest != null)  { PlayerScreen / LivePlayerScreen }      ← 兄弟层,盖在上面
├── space / youtubeChannel / youtubePlaylistDetail 覆盖层                          ← 兄弟层,条件里带 xxxPlaybackBehind
├── comment / 对话框 / 过渡 scrim
└── 侧栏 sidebar(内容层内,永远是默认焦点搜索的第一落点)
```

要点:

- **内容层在起播瞬间被 dispose**(`if (visiblePlaybackRequest == null)`,切换延后 `PlaybackTransitionScrimInMs=30ms`)。
  页面内 `remember`/`rememberSaveable` 之外的一切状态(列表、滚动位置、`LazyListState`)全部丢弃。
- 要跨播放存活的**数据**必须 hoist 到 AppShell:`youtubeChannelUiState`(频道页)、`upSpaceUiState`(UP 主页)。
  **反例**:`YoutubePlaylistDetailScreen` 的 `videos/header/listState` 是页面内 `remember`,
  返回后必然冷重组为「空 + 重拉 `/browse`」(P11-171 的直接成因)。
- 覆盖层(`space / channel / playlistDetail`)是**兄弟层**,`visiblePlaybackRequest == null` 分支里那些
  `|| xxxPlaybackBehind` 判断在内容层内部恒真,别拿它当「层可见」的判据。
- 层被卸载时被盖住的下层**仍在组合**(兄弟层同时存在),下层的 restore effect 照样会跑 ——
  下层的恢复失败/抢焦也是本层问题的常见帮凶(P11-171 里频道页 90 帧全败)。

## 2. 一条恢复链的标准要素

每个「从播放器返回」的页面都要齐这五件,缺一个就出「整页零焦点 / 按键逃到 sidebar」:

1. **arm**:起播前把离开时的落点 hoist 到 AppShell(`onFocusTargetChange("playall"/"back"/"row:N")`),
   播放器 `onBack` 里按「从哪层起播」分支 bump 对应的 `xxxFocusRestoreRequestKey += 1`(分支顺序:
   upSpace(Content) → **playlistDetail** → youtubeChannel(Content) → 默认 destination;
   从详情页起播时频道请求也非空,**详情页分支必须排在频道分支之前**)。
2. **冷重组后定位**:`rememberLazyListState(initialFirstVisibleItemIndex = if (key>0) targetRow else 0)`
   + `scrollToItem(targetRow)`,否则目标行不在首屏、requester 永不挂节点。
3. **等布局**:按帧等目标行进入 `layoutInfo.visibleItemsInfo`(上限 `…WaitLayoutFrames`,常用 360)。
4. **按帧重试**:`requestFocus()` 包 `runCatching`(requester 未挂节点会抛
   `FocusRequester is not initialized`,被 runCatching 吞成 false),上限 `…RetryCount`(常用 90)。
5. **闭环**:无论成功失败都要 `onRestoreFocusHandled(key)` 把 key 归零 ——
   否则初焦 effect 的守卫 `restoreFocusRequestKey != 0` 会让它永远让位。

## 3. 已知陷阱(每条都有真机实锤)

| 陷阱 | 症状/日志指纹 | 修法 |
|---|---|---|
| **空数据早退造成双向死锁** | `restore skipped: key=… videos=0` 之后该页**再无任何按键日志**,按键落 `avatar focused … openMyPage=true` | 数据未到时不要静默 return:先「占位」聚焦一个必然存在的落点(返回 chip),按帧等数据(`RestoreDataWaitFrames`);数据到了且用户没自己动过再做精确恢复,否则只消费 key |
| **恢复判据用「任一合法落点」** | 占位已持焦 → 精确恢复一拍都不试就 `restore done confirmed=true`,焦点留在占位 | 判据必须是**恢复目标本身**有焦点(按 `target` 分派:playall/back/行号集合) |
| **一次返回弹两层(待证)** | 下层 restore key 被莫名 bump(只有该层自己的 onBack 能 bump)而该层 onBack 无日志 ⇒ 用户停在下下层页面 | 先补日志:各层 onBack 分支**都要打日志**,否则判读无指纹。⚠️**别拿 `WindowOnBackDispatcher: OnBackInvokedCallback is not enabled` 当「用户按了 Back」的证据**——它是 BackHandler 注册/注销的产物:老日志 `logs_live_20260921_210436.log` 20:58:28.519 退出播放器后 20:58:28.819 也出现,而那次单层干净返回 |
| **下层恢复失败** | `channel-playlists restore failed … attached=false` + 90 次 `FocusRequester is not initialized`,而同一行 `rowVisible=true` | 目标卡 requester 挂不上:核对 requester 挂载条件(`playlist.id == focusedKey \|\| index == focusedIndex`)与目标项是否真的组合 |
| **onFocusChanged 放错位置** | 节点零回调而子树 `hasFocus=true`、requestFocus 静默失败 | `onFocusChanged`/`onPreviewKeyEvent` 必须写在 `focusable()` **之前**(只监听其后第一个 focusTarget) |
| **单布尔跟踪焦点** | 下键「弹回顶部/原地不动」,无关行入场补发 `isFocused=false` 清零 | 行聚焦按**行号集合**增删,不用 last-writer-wins 单布尔 |
| **requester 单发** | `FocusRequester is not initialized` 一闪而过,焦点落 sidebar | 一律「等一帧 + 校验 + 重试 N 帧」,不要单发 requestFocus |

## 4. 排障顺序(照这个顺序看日志)

1. 起播前:`playlist-focus initial done` / `… restore success … attempt=0` —— 进页面时焦点正常吗?
2. 起播瞬间有没有 `LOST [该页 focusDiag label]` —— 确认这一层被卸载(而不是只失焦)。
3. 退出播放器的 `video exit via …` 是哪一支 —— **arm 的层和实际可见层对得上吗**?
4. 该层自己的 restore 日志:`start` → `layout` → `success/failed`,还是一片空白(说明 effect 没跑或早退)?
5. 兄弟层(`channel-playlists` 等)在这次返回里跑没跑、成没成?
6. 用户第一次按键的落点(`grid-key` / `playlist-key` / `… focused` / `GAINED [sidebar]`)。
7. 全程有没有 `FocusRequester is not initialized` 风暴(计数对上哪个 `…RetryCount` 就锁定是哪条链)。

## 5. 历史轮次

- P11-72 / 72c-e:详情页初焦与「合法落点」判据、onFocusChanged 位置
- P11-93:行聚焦改行号集合(下键反弹)
- P11-98 / 98b / 98c:详情页 restore 上链、频道网格完整防御、网格边界逃逸
- P11-148:TV 设置页 D-pad 丢焦点
- **P11-171**:播放列表详情页返回零焦点(**空表死锁** + 数据未 hoist + 下层频道网格恢复全败 + 疑似一次返回弹两层(待证))

判断「用户到底按了几次 Back」只能看各层 onBack 自己的日志;**层切换警告不可作证**(见上表)。
