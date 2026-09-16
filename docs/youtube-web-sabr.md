# YouTube WEB-SABR(自制 WEB 会话 SABR):实现路径与失败经验

> **这条路要做什么**:不借 Piped 等第三方实例,**自己铸一个 YouTube WEB 会话**,拿到服务端签发的
> `serverAbrStreamingUrl` + `ustreamerConfig`,再喂给既有的 SABR 播放栈(与 visionOS 路共用下游)。
>
> **状态(2026-09-16):未打通。** P11-101 → P11-116 共 13 轮真机迭代,服务端始终 `status=2` nag,
> 且第 4 个响应必升终态 `status=3`(InvalidPoToken)→ 会话死 → 重试循环 → 播放失败。
> 带 token 与不带 token 的响应**逐字节一致**,`status=1` 一次都没出现过。
>
> **对照**:同一天的 NewPipe(visionOS)主路对**同一个门控视频** `status=1 ×24`、零 RELOAD、干净播通
> 100 秒以上(见 §3)。
>
> **2026-09-16 更新**:对 FreeTube 源码逐行审计后,**14 轮里追的方向有一半是空的** —— 桌面身份从未真正上线
> (见 §4 第 8 项),挑战与兑换不同源(§4 第 9 项)。缺口清单见 **§5**,据此重排的实现计划见 **§6**(P11-117/118)。
>
> 相关文档:[youtube-hd-playback.md](youtube-hd-playback.md)(总史/§6.x 逐条真机)、
> [youtube-dash-fallback-plan.md](youtube-dash-fallback-plan.md)(DASH 兜底)、
> [youtube-vs-libretube-comparison.md](youtube-vs-libretube-comparison.md)(逐环节对照)、
> [libretube-streaming-impl.md](libretube-streaming-impl.md)(LibreTube 移植来源)。

---

## 0. 为什么会有这条路

YouTube 侧的门控(attestation)让「拿不到已 attested 的 player response」变成一个死结,历史上试过三条路:

| 路 | 拿什么喂播放器 | 门控下的表现 |
|---|---|---|
| **visionOS SABR**(NewPipe fork,现主路) | NewPipe `getInfo` 的 `serverAbrStreamingUrl` + `ustreamerConfig` | **未 attested**(visionOS 客户端),服务端可判 `RELOAD_PLAYER_RESPONSE`;实测偶发、当天多数能播 |
| **WEB-SABR**(本文档) | 我们自己的 `/player` + 自铸 POT + yt-dlp solver 解 n | 服务端逐请求 `status=2` nag → 终态 `status=3` |
| **DASH 直链**(兜底) | NewPipe 直链拼 SegmentBase MPD | 非门控视频可用(可出 4K);门控视频直链 403 |

WEB-SABR 的动机来自 Y-01([DEVELOPMENT_PLAN.md:1032-1046](../DEVELOPMENT_PLAN.md#L1032-L1046)):
LibreTube 能播门控视频是因为它**默认走 Piped 后端**(`/streams/{id}` 回 attested 的 WEB-bound config),
所以「我们自己产一个 attested WEB 会话」看起来是治本方向。

**但"用别人的 Piped 实例"这条前置被否决了**(2026-09-15 用户决定):Piped 的价值本来就是借别人铸好的会话 + 出口 IP;
自建 Piped 也不是捷径——Piped-Backend 自己同样要跑 `bg-helper` 铸 poToken,而 poToken 与 IP 绑定,
还要额外做 IP 轮换防封,社区实测其 SABR/po_token gate 同样只回 360p。**这条路等于把客户端的活搬到服务器重做一遍。**
参考:[Self-Hosting - Piped](https://docs.piped.video/docs/self-hosting/)、
[Piped-Backend config.properties(`BG_HELPER_URL`)](https://gitdab.com/TeamPiped-mirror/Piped-Backend/src/branch/master/config.properties)、
[lighttube-org/pot-generator(PoTokens 与 IP 绑定)](https://github.com/lighttube-org/pot-generator)、
[captainzonks/spoke-piped(SABR gate 致实例只回 360p)](https://github.com/captainzonks/spoke-piped)。

---

## 1. 实现路径(链路图)

总览:`resolve()` 选路 → `buildWebSabrFallback`(桌面 watch 页身份 + 自铸 POT)→ `parseSabrData`
→ solver n-decrypt → cpn 注入 → `SabrSession.fromSabrData` → `registerByVideoId` → `SabrMediaSource`/`SabrMediaPeriod`
→ `SabrMediaFetcher`(webShape POST)→ `SabrDataSource`(终端处理)。

### 1.1 入口与优先级

- [YoutubePlaybackResolver.kt:99-101](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L99-L101)
  读 `youtubeDeliveryPriority`,派生 `dashFirst` / `webSabrFirst`;默认 `Sabr`。
- [:167-179](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L167-L179)
  **WebSabr 档强制优先**:条件 `webSabrFirst && poToken != null`;成功 `clearWebSabrFailed` 直接返回,
  失败 `markWebSabrFailed(videoId)` 落 NewPipe 主链(不重复尝试,防循环)。
- [:248-266](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L248-L266)
  **兜底触发**:`webSabrDue = !isWebSabrFailed && (isDashFallbackFailed || (reloadCount > 0 && poToken != null))`
  ——「DASH 直链已判死 403」或「已 RELOAD 过且手里有 token」。失败后顺带跑 `probeWebDashChain` 取证。
- 失败标记本体:[SabrStreamRegistry.kt:57-83](../app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrStreamRegistry.kt#L57-L83)
  进程级 `webSabrFailedVideos` 集合(姊妹通道 `isDashFallbackFailed` 在 [:48-67](../app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrStreamRegistry.kt#L48-L67))。
- 成功后必须 [:2010](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L2010)
  `resetReloadCount(videoId)` 清零 visionOS 死会话留下的计数,否则 `SabrDataSource` 会按 `reloadCount>0` 立刻 fast-fail 新会话。

### 1.2 `/player`:桌面 watch 页身份 + 页面挑战

- [YoutubePlaybackResolver.kt:1877-1903](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1877-L1903)
  `buildWebSabrFallback`:`poToken == null` 直接 abort;`botGuard.webSessionIdentity()` 取桌面身份,
  再 `postPlayer(... contextOverride / cookieOverride / visitorOverride ...)`(P11-115)。
- 身份来源:[YoutubeBotGuard.kt:251-255](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L251-L255)
  `webSessionIdentity()` = 桌面 watch 页 ytcfg 的 `INNERTUBE_CONTEXT`(osName=Windows)+ 页面 Set-Cookie。
  抓取在 [:178-241](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L178-L241),URL 带
  `bpctr=9999999999&has_verified=1`,**必须桌面 UA**(移动 UA 被 302 且页面无 `ytAtN`,P11-103)。
- 挑战解析:`findYtAtN`([:262-298](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L262-L298),
  引号感知平衡括号扫描,避开页面里无参 `window.ytAtN();` 空调用)+ `parseLooseJson`
  ([:307-325](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L307-L325),bgutils-js helpers 移植)。
- 请求体组装:`postPlayer` [:681-723](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L681-L723);
  WEB 走 `viaWebView=true`(WebView 原生栈),异常回退 OkHttp。
- poToken 位置:[InnerTubeClient.kt:68-95](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L68-L95)
  —— 放在**顶层 `serviceIntegrityDimensions.poToken`**(不在 context 内);`context` 可被 `contextOverride` 覆盖,
  `X-Goog-Visitor-Id` / `Cookie` 由 `visitorOverride` / `cookieOverride` 接管([:136-146](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L136-L146))。

### 1.3 PO token 铸造(BotGuard)

- [YoutubeBotGuard.kt:60-132](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L60-L132)
  `generatePoToken`(20s 超时)→ `mintPoToken` 五步:注入 `window.yt={config_:…}` → challenge 取源优先级
  `challengeFromPage`(页面 bgChallenge) → `attGetChallenge`(`POST /att/get`,`ENGAGEMENT_TYPE_UNBOUND` + eacrToken)
  → `fetchChallenge`(`create` 旧法兜底)。**落到 `create` 即 attestation 链占位**(P11-103 的根因)。
- [:433-452](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L433-L452)
  `loadInterpreterViaScript`:注入 `<script src>` + 轮询 `__interpLoad`(对齐 FreeTube `botGuardScript.js` c42fee2c7),
  失败才落 eval 文本兜底。
- [:523-572](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L523-L572)
  `generateIntegrityToken`:**裸发** `POST /api/jnn/v1/GenerateIT`,只带 `content-type` / `x-goog-api-key` / `x-user-agent`
  (P11-105 撤掉 Cookie + X-Goog-Visitor-Id)。
- 产出形态:124 字符 web64(`.` 填充)。会话侧字节形态见
  [SabrClient.kt:140-147](../app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrClient.kt#L140-L147)
  —— 先试 `Base64.DEFAULT`,失败(web64)落 UTF-8 原字节。**注意:创建路径与刷新路径的字节形态历史上并不一致**
  (r1949 会话创建时 89B、刷新后 124B),`P11-101` 结论是"原始字节"才对,下次带 token 的会话值得顺手核一眼。

### 1.4 n/s 解密(yt-dlp solver)

- [YoutubeSolverDecipherer.kt:28-60](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSolverDecipherer.kt#L28-L60)
  `ensureLoaded` 依次 eval 四个 asset:`meriyah.min.js` → `astring.min.js` → `yt.solver.core.js` → `yt_solver_driver.js`
  (目录 `app/src/main/assets/youtube/`);就绪判据 `typeof window.__ytSolveLoaded == "boolean"`。
- [:67-123](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSolverDecipherer.kt#L67-L123) `solve` 轮询 30s 取结果。
- 调用点 [YoutubePlaybackResolver.kt:1942-1965](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1942-L1965):
  只在 `sabrUrl` 带 `n` 时跑;`solverN == sabrN`(未变)即 abort(未 transform 必 403)。
- **已退役的旧方案**:`YoutubeNDecryptor`(player hash → nClass,alpha.32 真机证伪)与 `YoutubeSDecryptor`
  (旧正则,plasma 后失配),见 [YoutubeSolverDecipherer.kt:19-23](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSolverDecipherer.kt#L19-L23)。
  本体仍保留,YoutubeNDecryptor 仅剩诊断探针调用([:1378](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1378))。

### 1.5 cpn 注入

- [YoutubePlaybackResolver.kt:1966-1975](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1966-L1975)
  —— 服务端签发的 sabrUrl **不带 cpn**(P11-112 dump:34 键无 cpn/cver),故客户端生成 16 位随机 cpn 注入
  (对齐 FreeTube Watch.js L659/L1740、youtubei.js `generateRandomString(16)`)。
- [SabrClient.kt:137-139](../app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrClient.kt#L137-L139) + [:162-166](../app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrClient.kt#L162-L166):
  `sabrUrlWithParams` 追加 `alr=yes&cpn=<cpn>`(自适应重定向 + 会话绑定);`SABR_REDIRECT` 换 URL 时重加([:110-112](../app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrClient.kt#L110-L112))。
- 逐请求 URL = `${session.sabrUrl}&rn=$rn`([SabrMediaFetcher.kt:722](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L722))。

### 1.6 会话构造

- [YoutubePlaybackResolver.kt:1981-1998](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1981-L1998)
  `SabrSession.fromSabrData(...)`:`poToken` **传 `""`**(P11-116 pot-less 实验,硬编码)、clientInfo =
  `webDesktopSabrClientInfo(context)`、`userAgent = YoutubeConstants.UserAgent`(桌面)、`cookieHeader = ""`、`visitorData = ""`。
- [InnerTubeClient.kt:651-659](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L651-L659)
  `webDesktopSabrClientInfo`:**只 4 字段**(clientName=1 / clientVersion / osName / osVersion)。
  对照 `sabrClientInfo`(移动 sw.js_data 指纹)与 `visionOsSabrClientInfo`(clientName=101 / Apple RealityDevice14,1)。
- [:1999-2005](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1999-L2005)
  `registerByVideoId`,`refreshPoToken = { botGuard.generatePoToken(videoId)?.toByteArray(UTF_8) }`(**同 minter 续命**)。
  注释记录了失败版本:接 `biliTvPoTokenProvider` 输出 888B、绑自身 visitorData → `status=3` → 60s 重载循环。

### 1.7 请求形状(FreeTube 形状 = `webShape`)

- 判定:[SabrMediaFetcher.kt:653](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L653)
  `webShape = session.clientInfo.clientName == 1`(只有 WEB clientInfo 走 FreeTube 形状,visionOS 走 `libre` 形状)。
- [:655-668](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L655-L668) `ClientAbrState` 只写 6 字段
  (viewport 640×max(h,360)、bandwidthEstimate 恒有、`playerTimeMs` 零值省略、bitfield video 省略);
  对照 `libre` 形状的 12+ 字段([:669-692](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L669-L692))。
- [:693-704](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L693-L704) clientInfo 用 `copy(...)`
  把 device/screen/acceptLanguage/timeZone 等**全部置 null**(FreeTube 仅 4 字段)。
- 时间语义 [:709-712](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L709-L712):
  webShape 下顶层 `playerTimeMs`(f4)**不发**,只放 `clientAbrState.f28`。
- bufferedRanges [:623-626](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L623-L626) +
  [SabrSegment.kt:88-120](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrSegment.kt#L88-L120):
  请求轨只报到**请求段的前一段**(`minOf(partition.last().first, capSeq - 1)`,P11-111 对齐 PipePipe `bufferedThrough = next-1`),
  init → 全空;其他轨维持自身缓存。

### 1.8 status 处理

- [SabrMediaFetcher.kt:927-939](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L927-L939)
  `PART_STREAM_PROTECTION_STATUS`:`3` → `invalidPo = true`;`2` → `needsPoTokenRefresh = true`;**`1` 无分支**(静默,只打日志)。
- status=2 刷新 [:576-587](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L576-L587) 同步走
  `refreshPoTokenSingleFlight`([SabrStreamRegistry.kt:149-172](../app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrStreamRegistry.kt#L149-L172)):
  5s freshness 窗口内复用(coalesce),否则铸一次统一写回;动机是 4 个 fetcher 并发各铸一次 last-write-wins 互踩 → 整会话 `status=3`(P11-102c)。
- status=3 **terminal**:先打 `InvalidPoToken diag`([:499-508](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L499-L508)),
  再 `throw SabrTerminalException`([:509](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L509));
  上层 [SabrDataSource.kt:82-95](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrDataSource.kt#L82-L95)
  catch → `evict(sessionId)` → 播放器 error-retry 重跑 resolve。
- RELOAD:[SabrMediaFetcher.kt:940-973](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L940-L973)
  解析 `ReloadPlayerResponse` → 存单槽(`storeReloadTokenSlot`)→ **首次 RELOAD 立即抛**(不读完 part,对齐 LibreTube「RELOAD 直接失败不循环」)。

### 1.9 诊断埋点

| 埋点 | 位置 | 看什么 |
|---|---|---|
| `WEBREQDUMP`(potHex + bodyHex) | [:727-730](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L727-L730) | 仅 `webShape && rn<=1`,与 FreeTube HAR 逐字节 diff |
| `InvalidPoToken diag` | [:499-508](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L499-L508) | `sessAgeMs` / `sessReqN` / `status2Seen` / `pot` / `ctxActive` / `unhandled` / `req` / `ranges` |
| 每请求一行 | [:723](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L723) | `shape=ft\|libre bitfield= selectedFmts= bufferedRanges= pot= cookie= contexts= bw=` |
| `/player` 诊断行 | [InnerTubeClient.kt:103-115](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L103-L115) | `poTokenArg` / `bodySID` / `bodySIDToken` / `cookieV1L` / `cookieOv` / `ctxOs` / `ctxBrowser` / `bodyLen` |
| `VM fingerprint=` / solver 异常 | [YoutubeBotGuard.kt:466](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L466) / [:952-954](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L952-L954) | 铸 token 的 VM 环境;solver 抛错现形(P11-114b) |

### 1.10 开关

`youtubeDeliveryPriority`(sabr/dash/websabr,[YoutubeDeliveryPriority.kt:18-29](../app/src/main/java/com/kirin/mt/core/player/YoutubeDeliveryPriority.kt#L18-L29))、
`youtubeUsePiped`、`pipedInstanceUrl`、`sabrForceSessionVideoItag`([AppSettings.kt:111-128](../app/src/main/java/com/kirin/mt/core/settings/AppSettings.kt#L111-L128));
TV 入口 [SettingsScreen.kt:1128-1178](../app/src/main/java/com/kirin/mt/ui/settings/SettingsScreen.kt#L1128-L1178),
移动入口 [MobileSettingsScreen.kt:1252-1281](../app/src/main/java/com/kirin/mt/ui/mobile/settings/MobileSettingsScreen.kt#L1252-L1281)。

---

## 2. 失败经验(P11-101 → P11-116)

一天之内 13 轮,每轮都是「改一处 → 云编译 → 真机判读」。**除 P11-111 外,对 nag 全部无效。**

| 编号 | 尝试 | 判读原文 |
|---|---|---|
| P11-101 | WEB-DASH 兜底探针 → 发现 `dashManifestUrl` ABSENT 但 `serverAbrStreamingUrl` 在 → 转 WEB SABR 变体;打包 yt-dlp solver | r1921 探针全链通(403 消失),r1923-25 60s 死点仍在:`status=2 → refresh → status=3` |
| P11-102c | status=2 刷新改 single-flight | 修掉并发互踩;status=3 仍复现 |
| P11-102d | status=3 终端取证埋点 | 「3 会话不同 token 全死同一 playerTimeMs → **token 无关**」 |
| P11-103 | 挑战源:移动 UA 抓 watch 页拿不到 `ytAtN` → 三次 mint 全落 `source=create` | 修好后链**真通了**,但 nag 照旧 |
| P11-104 | 请求体对齐 FreeTube HAR(webShape:clientInfo 砍到 4 字段 / 真实带宽 / 零值省略) | 「请求形状非 nag 判据」 |
| P11-105 | GenerateIT 裸发(撤 Cookie + X-Goog-Visitor-Id) | 「绑定非 nag 判据」(裸发保留) |
| **P11-106** | **会话身份全链桌面化**(桌面 ytcfg context 接管 context/clientInfo/UA/cookie/visitor) | 「**身份已生效但 nag 依旧**」 |
| P11-107 | SABR POST 撤 HTTP Cookie / X-Goog-Visitor-Id 头 | 「HTTP 头非判据」 |
| P11-108 | 字节级 dump `bodyHex`/`potHex`,与 FreeTube HAR 逐字节 diff | 取证完成,锁定 3 处残差 |
| P11-109 | 时间语义对齐(不发 f4 / f28 省略 / bwEstimate 恒发) | 「时间语义非判据」→ 判定「剩余唯一未对齐层 = **铸 token 的 VM 环境**」 |
| **P11-110** | interpreter 改 `<script src>` 标签加载 | 「**VM 加载非判据**」;联网命中 **GoogleVideo#52** |
| **P11-111** | bufferedRanges 截断到请求段 | **唯一真修**:会话寿命 5-8s → 30s;**但 nag 依旧** |
| P11-112 | sabrUrl 参数键 dump | 34 键 vs FreeTube 多 **cpn/cver** |
| P11-113 | 客户端注入 cpn(r1947) | 链路全通,但 WEB 会话 **status=2 ×9 / status=1 ×0** |
| P11-115 | `/player` 换桌面 watch 页 cookie/visitor | 真机判死(见 §3):`cookieOv=547B` 生效,但 **status=1 ×0**、sabrUrl 仍缺 cpn/cver |
| P11-116 | **pot-less 判别实验** | 仍 nag → token 洗清(见 §3) |

### 2.1 已证伪的假设(别再走)

- **「token 内容不对」**:P11-105/106/109/110 逐层对齐后仍 nag;P11-116 pot-less 与会话带 token 的响应**逐字节一致**;
  外部独立互证 **GoogleVideo#52**(2026-08-18,downloader 场景 60s 停,**「有效 token 与无 token 停点一字节不差」**)。
- **「身份/UA/cookie/visitor 混搭」**:P11-106 全链桌面化**已生效**仍 nag;P11-107 撤 HTTP 头同样无效;P11-115 换桌面页 cookie/visitor 同样无效。
- **「请求体形状/时间语义」**:P11-104 / P11-109 字节级对齐后仍 nag。
- **「VM 加载方式」**:P11-110 `<script src>` 生效仍 nag。
- **「cpn 缺失」**:P11-113 注入后 WEB 会话 status=2 ×9(注:cpn 对 ~60s 窗口/会话寿命有用,但不是 nag 的解药)。
- **「n/s 未解密」**:P11-101 的 solver 已让 403 消失(这条确实修好了,不是 nag 的原因)。
- 更早被证伪的同类假设(alpha 时代,不同症状):桌面 VM 指纹 polyfill、`VISITOR_INFO1_LIVE` cookie 配对、
  GenerateIT 带 cookie 绑定、WEB_EMBEDDED 客户端、正则/URL-class n 方案等(见 [youtube-hd-playback.md](youtube-hd-playback.md) §6.7)。

### 2.2 反例:曾经推翻过的"定论"

文档里有些「已证伪」后来本身被推翻,引用旧结论前先核日期:
alpha.13「sabrUrl=ABSENT → SABR 方向关闭」(实为 snake_case 假阴性)、alpha.83「itag248 误分类」、
「60s 会话窗口是硬约束」(实为上报缓冲量 vs 墙钟进度不匹配,P11-111 修)、
「4K 两路全堵、接受 ≤1080p」(被 DASH 直链第三条路推翻)。

---

## 3. 决定性实测(2026-09-15 / 09-16)

### 3.1 pot-less 与会话带 token 逐字节一致(P11-116)

| 请求 | r1949(会话带 token) | r1950(pot-less) | 服务端状态 |
|---|---|---|---|
| rn=3 | pot=89B → 3542048B | pot=0B → 3542048B | status=2 |
| rn=4 | pot=89B → 3051373B | pot=0B → 3051373B | status=2 |
| rn=5 | pot=124B(刷新后) → 2233871B | pot=124B(刷新后) → 2233871B | status=2 |
| rn=6 | pot=124B → **71B** | pot=124B → **71B** | **status=3(终态)** |

- 三份日志里 `STREAM_PROTECTION_STATUS status=1` 出现次数 = **0**;`status=2` 恰好 3 次后第 4 个响应必升 `status=3`,4/4 会话一致。
- ⇒ nag 与终态**都不是 token 触发的**;`status=2` 是"未通过 attestation"的常态,`status=3` 是服务端对 3 次未响应 nag 的处决。

### 3.2 同日对照:visionOS 主路干净(P11-115/116 的判死依据)

`logs_live_20260916_094251.log`(dev.r1950,SABR 优先档):

- **status=1 ×24 / status=2 ×2 / status=3 ×0 / RELOAD ×0 / Playback error ×0**。
- 其中一段就是 WEB-SABR 反复失败的那个门控视频 `KXXZbbnm9t0`(startPos=59000 续播):首帧 09:41:12,连续播到日志末尾(~100s+),
  `shape=libre pot=0B`(**不带 token**);候选档含 1440p(271)与 2160p(313),ABR 末段稳在 1080p VP9。
- ⇒ 同样的服务端、同样的网络,**visionOS 会话不带 token 反而 `status=1`** —— 说明 status 与"我们铸的 token"无关,
  更可能与**客户端身份/服务端策略窗口**有关。

### 3.3 与 09-15 晚的对比(必须知道的抖动)

09-15 晚同一门控视频在 visionOS 主路**几乎每次都挂**(起播 2~6s 内 `RELOAD_PLAYER_RESPONSE`,seg=0 init 段);
09-16 早同一构建全通。`RELOAD_PLAYER_RESPONSE` 的语义是 "streams expired or new config",**随服务端配置/时效抖动**,
不是可稳定复现的代码缺陷。判「主路是否健康」必须看当天日志。

---

## 4. 当前未打通的关键点(占位 / 桩 / 死代码 / 口径不一致)

| # | 位置 | 问题 |
|---|---|---|
| 1 | [YoutubePlaybackResolver.kt:1982-1986](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1982-L1986) | **pot-less 实验硬编码**(`fromSabrData(sabrUrl, "", …)`),无开关;要做带 token 的对照必须先改回 |
| 2 | [YoutubeBotGuard.kt:576-586](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L576-L586) | `contentBinding`(`b=PLACEHOLDER&hh=PLACEHOLDER`)是占位;但 **FreeTube 无此物**(bgutils 只编 videoId 字节),且我们自己的 [bgutils.js:406-408](../app/src/main/assets/youtube/bgutils.js#L406-L408) 收下就丢 → **死代码,不是缺口**,别再追 |
| 3 | [BiliTvPoTokenProvider.kt:53-69](../app/src/main/java/com/kirin/mt/core/youtube/newpipe/BiliTvPoTokenProvider.kt#L53-L69) | `getWebEmbedClientPoToken` / `getAndroidClientPoToken` / `getIosClientPoToken` **恒 null(未实现)** → 门控视频直链必 403(P11-99b 根因) |
| 4 | [YoutubePlaybackResolver.kt:1999-2005](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1999-L2005) | WEB-SABR **未接 `sabrForceSessionVideoItag`**(只有 Piped 路径传) |
| 5 | [YoutubePlaybackResolver.kt:732](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L732) vs [YoutubeBotGuard.kt:212](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L212) | **UA 口径不一致**:挑战/身份页用桌面 UA,抽 base.js 的 `resolvePlayerJsUrl` 仍用移动 UA(solver 输入与身份不同源) |
| 6 | [SabrStreamRegistry.kt:96-128](../app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrStreamRegistry.kt#L96-L128) + [YoutubePlaybackResolver.kt:1160-1210](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1160-L1210) | RELOAD 闭环**半截**:单槽 parking / `consumeReloadTokenSlot` / `buildSabrSessionFromReloadPlayer` 无生产消费方(已被「首次 RELOAD 直接抛」取代) |
| 7 | [YoutubeNDecryptor.kt:17-39](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeNDecryptor.kt#L17-L39) | 已证伪的旧 n 方案本体保留(仅诊断探针调用),属死代码 |
| 8 | [InnerTubeClient.kt:68-79](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L68-L79) / [buildWebViewHeaders:579](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L579) / [YoutubeBrowserSession.kt:163](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBrowserSession.kt#L163) | **桌面身份从未上线(本轮新发现,最重要)**:`postJson` 的 UA 恒为 `client.userAgent`(WEB→`MobileUserAgent`),`postPlayer` 只有 context/cookie/visitor 三个 override **没有 uaOverride** → 桌面 UA 无入口;Cookie 被 `fetchViaWebView` 丢头 + Fetch 禁止头双保险失效 ⇒ P11-106/107/115 证明的是「桌面 **body context** 无关」,**不是**「桌面身份无关」 |
| 9 | [YoutubeBotGuard.kt:377-423](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L377-L423) | `/att/get` **与挑战不同源**:挑战来自 OkHttp 桌面 UA 抓的 watch 页,兑换用 `postJson(client=WEB)` 默认移动身份 + 合成 context;且该端点**零日志**(diag 门只开 `/player`) |

---

## 5. FreeTube 对齐缺口审计(2026-09-16,对源码逐行核)

审计对象:本地克隆 `E:\GITHUB\FreeTubeMt` @c210eca20(桌面)、`E:\GITHUB\FreeTubeAndroid` @5d486ad88。
结论:**铸造那一段基本对齐了,差的全在它周围**。

### 5.1 真缺口(结构性)

**G-1 挑战来源与身份(走偏最大的一处)**
FreeTube 的整个铸造流程只有 85 行([botGuardScript.js:11-85](../../FreeTubeMt/src/botGuardScript.js#L11-L85)),挑战**只从 `/att/get` 拿**:

```
POST https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false&alt=json
headers: Accept, Content-Type: application/json,
         X-Goog-Visitor-Id: context.client.visitorData,
         X-Youtube-Client-Version: context.client.clientVersion,
         X-Youtube-Client-Name: '1'
body:    { engagementType:'ENGAGEMENT_TYPE_UNBOUND', context }   ← 完整 context,没有 eacrToken
```

我们([YoutubeBotGuard.kt:377-423](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L377-L423))三处不同:
①**优先级反了**(我们「页面 bgChallenge 优先 → att/get → create」,FreeTube 是 att/get 唯一);
②**body 不同**(我们发 `{engagementType, eacrToken}`,FreeTube **没有 eacrToken 这个字段**);
③**头不同**(缺那三个 `X-Goog-*`/`X-Youtube-Client-*`,context 由 postJson 注入的是合成移动身份)。
而且 FreeTube 传进铸造脚本的 `context` **就是 `/player` 用的同一份对象**([local.js:445-448](../../FreeTubeMt/src/renderer/helpers/api/local.js#L445-L448))
—— **一份 context 贯穿三段**;我们四段四个身份。

**G-2 桌面身份从未上线** —— 见 §4 第 8 项。`postJson` 没有 UA override 入口、`fetchViaWebView` 丢 Cookie 头 ⇒
P11-106/107/115 证明的是「桌面 **body context** 无关」,不是「桌面身份无关」。
**反向证据**:唯一一次桌面 UA 真上线 = alpha.20-26 harvest(桌面 UA 真 WebView),也是**唯一跑通过 `status=1`** 的配置。

**G-3 身份一致性的机制不同**

| | FreeTube | 我们 |
|---|---|---|
| 一致性靠什么 | **同一个 context 对象**序列化传给铸造脚本 + UA 从 `defaultSession` **拷贝**给铸造分区([poTokenGenerator.js:91](../../FreeTubeMt/src/main/poTokenGenerator.js#L91)) | 无。四段各自取身份 |
| cookie 罐 | 铸造跑在 `partition('potoken', cache:false)`,**故意 cookie-less**,每次 `clearData()`;`/player` 用 defaultSession 真实 cookie | 进程级全局 jar,桌面身份从未写进去 |
| 铸造环境 | 每次新建 `WebContentsView`(offscreen)+ `Emulation.setDeviceMetricsOverride` 1920×1080 **mobile:false**,UA 桌面 | 常驻单例 WebView,移动 UA,壳页 |

形态判据:**FreeTube 桌面 = 全桌面,安卓版 = 全移动**(安卓 `local.js` 用 `navigator.userAgent` 当 `user_agent`);
**我们是混合**(移动铸造 + 桌面会话),两边都不是。

**G-4 interpreter 加载方式** FreeTube HEAD 用 `new Function(interpreterJavascript)()` **eval**
([botGuardScript.js:48-56](../../FreeTubeMt/src/botGuardScript.js#L48-L56));我们 P11-110 改成 `<script src>` 并自称对齐 FreeTube —— 对齐的是旧版。

### 5.2 行为差异(不是缺口,方向相反)

| 项 | FreeTube | 我们 |
|---|---|---|
| `status=2` | **完全忽略**,只对 `status===3` 反应(→ CRITICAL → 整页重载) | 同步刷新 token(P11-68/102c) |
| `bufferedRanges` | **不截断**:当前流报真实缓冲,另一条流塞 `MAX_INT32` 假满区间(明确告诉服务端"别发这条") | 截断到请求段前一档(P11-111,**对齐的是 PipePipe 不是 FT**) |
| SABR POST 的 `Content-Type` | 发 `application/x-protobuf`,但被自己的 webRequest 钩子 `delete` 掉 | 照发 |

### 5.3 我们做过、FreeTube 没有的东西

| 我们的做法 | FreeTube | 结论 |
|---|---|---|
| `contentBinding`(`c=…&b=PLACEHOLDER…` + `e=…`) | **无此物**(只 `TextEncoder().encode(videoId)`) | 死代码(我们的 bgutils 已忽略该参数) |
| 抓 watch 页解析 ytcfg | **无此物**:ytcfg 由 youtubei.js 从 `sw.js_data` + `POST /youtubei/v1/config` 拿 | 自创路径 |
| 页面 bgChallenge 优先 | 只用 `/att/get` | 自创 |
| 给 SABR URL 补 `cver` | **无此物**(youtubei.js decipher 的行为) | **纠正 §5 旧版**:不是 FT 的缺口 |
| 桌面 VM 指纹 polyfill | 无(只设 1920×1080/mobile:false) | 已移除,别再动 |

### 5.4 确认已对齐(别再改)

`GenerateIT` 的 URL / 三头 / body 形态 / `x-goog-api-key` 值(`AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw`)/
`RequestKey`(`O43z0dpjhgX20SCx4KAo`)—— 与 [YoutubeBotGuard.kt:604-605](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBotGuard.kt#L604-L605) **逐字相同**;
`snapshot` 不带 contentBinding、`mint` 用 videoId;SABR `clientInfo` 只 4 字段(FT 也是 4);SABR POST 不带 Cookie/`X-Goog-Visitor-Id`;`/player` body 不带 cpn。

### 5.5 一个最便宜的判别实验(从没做过)

FreeTube **从不刷新 token**(单 token 全程,只绑 videoId),我们却把「status=2 刷新」当核心机制在修(P11-68/102c)。
而 P11-116 的 pot-less 实验**不干净**:会话中途在 status=2 时**冒出一个新 token**,那是 FreeTube 绝不会做的动作。
判别法:铸一次 → 全程沿用(关掉 status=2 刷新)→ 看第 4 个响应还升不升 `status=3`。若不再升 ⇒ **我们自己把 nag 升级成了处决**。

---

## 6. 实现计划:打通 WEB-SABR(P11-117 / P11-118)

> 验收目标:`STREAM_PROTECTION_STATUS status=1` 出现在 WEB 会话,会话寿命 >30s,起播后 60s 内零 `Playback error`。
> 全程用真机日志判读,**G1 未达标不判 status**(13 轮的共同盲区就是在黑箱上投轮次)。

### 阶段 0:埋点(与阶段 1 同一个构建,零行为风险)

| # | 位置 | 改什么 |
|---|---|---|
| 0.1 | [InnerTubeClient.kt:99](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L99) | diag 门 `/player` → `/player \|\| /att/get`(这条链今天是黑的) |
| 0.2 | [InnerTubeClient.kt:103-115](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L103-L115) | diag 行加 `ua=<实际发出的 UA 前 30 字符>`(判"桌面 UA 是否真上线"的唯一证据) |
| 0.3 | [YoutubePlaybackResolver.kt:1894](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1894) 附近 | 打一条 `desktopVis=<桌面 visitor 前24> jarVis=<CookieManager 里 VISITOR_INFO1_LIVE 前24>` |
| 0.4 | [YoutubePlaybackResolver.kt:1921-1924](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1921-L1924) | sabrUrl dump 加 `c=` / `n=` 存在性 |
| 0.5 | [YoutubePlaybackResolver.kt:726-747](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L726-L747) | `resolvePlayerJsUrl` 的 jsUrl 打**内容**而非长度 |

### 阶段 1:第一刀 —— 让桌面身份真正上线 + 挑战同源(一个构建)

**1a. 新增 `uaOverride` 入口,让 `/player` 发出桌面 UA ⭐**
`postJson`([InnerTubeClient.kt:68-79](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L68-L79))加参数 `uaOverride`;
OkHttp 分支 [:140](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L140) 与 [buildWebViewHeaders:579](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt#L579)
都改 `uaOverride ?: client.userAgent`;`postPlayer`([:681-689](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L681-L689))透传,
`buildWebSabrFallback` 传 `YoutubeConstants.UserAgent`(桌面)。

**1b. `/att/get` 与挑战同源(对齐 FreeTube)** —— body 改 `{engagementType, context}`,传 context/visitor/ua override,去掉 `eacrToken`;
挑战取源顺序改 **`/att/get` 优先**。

**1c. `/player` 走 OkHttp** —— `postPlayer` 加 `forceOkHttp`,WEB-SABR 调用点传 `true`(今天 WEB 优先走 browserSession WebView,
那里 UA 由移动 `settings.userAgentString` 决定、Cookie 被丢)。若被判「The page needs to be reloaded」→ 退化方案是把
[YoutubeBrowserSession.kt:102](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBrowserSession.kt#L102) 的 UA 改桌面(注意会连带影响 feed 的 cookie)。

### 阶段 1 的判据(按序,前档不达标不判后档)

| 闸门 | 判据 |
|---|---|
| **G0** 埋点自证 | 新 diag 能打出 `ua=` / `desktopVis=` / `jarVis=` |
| **G1** 身份上线 | diag `ua=` 含 `Windows NT 10.0` 且 `/att/get` 的 `ctxOs=Windows/10` |
| **G2** 会话被接受 | `status=1` ≥1 次(强门槛:连续 3 次且 `status=3 ×0`) |
| **G3** 寿命 | `InvalidPoToken diag: sessAgeMs > 30000` 或全程无 status=3 |
| **G4** 播放 | 起播后 60s 内 `Playback error ×0`、`RELOAD ×0` |

G1 达标但 G2 仍 0 → 第二刀:**关掉 status=2 刷新**(§5.5)。再 0 → 按 S1 停「身份」假设,转阶段 2。

### 阶段 2:复活 harvest(已知可通配置 + 尺子)

触发条件:S1(连续 2 个构建 `status=1 ×0` 且 G1 已达标)。

- **取回**:`git show 1f55977d~1:app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt`(396 行)。
- **适配(每条都是坑)**:①**ustreamerConfig 字节形态** —— 退役代码用 STANDARD base64 编回,现在 [SabrClient.kt:150](../app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrClient.kt#L150)
  用 `URL_SAFE` 解码 → 第一轮必 `sabr.malformed_config`,修法是给 `fromSabrData` 加收**原始 bytes** 的重载;
  ②`extractParam` → `queryParam`;③**cpn 必须用浏览器原 cpn**;④**选档按 `youtubeDefaultQuality.maxHeight` 对齐**(alpha.77 的 RELOAD 死循环旧 bug);
  ⑤**不要恢复 60s 主动轮换**;⑥**不要 seed 移动 cookie**;⑦命门级前置原样保留(measure+layout 1080×1920、`mediaPlaybackRequiresUserGesture=false`、
  长期存活 + 先加载真实首页、hook 注入 `onPageStarted`、body 走 `input.clone().arrayBuffer()`、必须从 watch 页采集)。
- **接线**:`AppContainer` 加单例 + resolver 构造参数;复用现有两处调用点;`YoutubeLoadStep.HarvestWatch` 直接 emit。
- **当尺子**:同一天同一视频跑 1 与 2,逐字段 diff —— sabrUrl 键集 / ustreamerConfig 前 32B hex / poToken 字节 / `clientInfo` / bodyHex field 号集合 / `rn=0..3` 的 status 序列。

### 停止条件(钉死)

- **S1** 连续 2 个构建 `status=1 ×0` 且 G1 已达标 → 停「身份」假设,转阶段 2。
- **S2** harvest 复活当天也 `status=1 ×0` → **整条 WEB-SABR 线停**(同一天 visionOS 主路 `status=1 ×24` 是决定性反证)。
- **S3 预算** 阶段 1 ≤3 次云编译;阶段 2 ≤2 次。超了写回 §2「已证伪」表。
- **S4 风控熔断** 出现 `LOGIN_REQUIRED` → 当天停全部 WEB-SABR 实验,避免污染 visionOS 主路。

### 不做

`contentBinding` 的 `b/hh`(FreeTube 无此物)、`cver` 注入(FreeTube 无此物)、删 `fetchViaWebView` 的 Cookie 剥头(禁止头,删了也没用)、动 VM 指纹(变量不唯一)。

---

## 7. 清理清单(若触发 S2 把这条线降级为"可选档")

1. [YoutubePlaybackResolver.kt:1982-1986](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1982-L1986)
   pot-less 硬编码要么回滚成正常传 token,要么加显式实验开关(不留"看不懂是实验还是正式"的中间态)。
2. `WEBREQDUMP` 的 hex dump 保持 `rn<=1` 收敛即可,别扩大。
3. Piped 支路:DEFAULT_PIPED_INSTANCE 的公共实例默认值建议去掉(空 = 不启用),只留「用户填自己实例」入口。
4. §4 第 6/7 项的死代码(`buildSabrSessionFromReloadPlayer`、`YoutubeNDecryptor`)在 RELOAD 韧性改造时一并处理。

---

## 8. 外部参考

- [GoogleVideo#52](https://github.com/LuanRT/GoogleVideo/issues/52):downloader 场景 60s 停,`status=2` 卡死,**有效 token 与无 token 停点一字节不差**(与我们的排除实验互证)。
- **PipePipeExtractor #66**:完整 SABR 实现(Java/NewPipe 栈);`Track.bufferedThrough = next-1` 是 P11-111 的来源。
- FreeTube(桌面)与 FreeTubeAndroid:身份/挑战/裸发 GenerateIT 的对照来源(见各轮 commit message)。
- [Self-Hosting - Piped](https://docs.piped.video/docs/self-hosting/)(自建 Piped 需要 bg-helper + IP 轮换)。
