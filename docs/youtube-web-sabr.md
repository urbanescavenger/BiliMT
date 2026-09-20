# YouTube WEB-SABR(自制 WEB 会话 SABR):实现路径与失败经验

> **这条路要做什么**:不借 Piped 等第三方实例,**自己铸一个 YouTube WEB 会话**,拿到服务端签发的
> `serverAbrStreamingUrl` + `ustreamerConfig`,再喂给既有的 SABR 播放栈(与 visionOS 路共用下游)。
>
> **状态(2026-09-16 晚):已打通(harvest 形态)。** P11-101 → P11-118 共 15 轮「原生对齐」(WEB 会话逐请求
> `status=2` nag → 第 4 个响应必升终态 `status=3` → 会话死)全部落空后,按 FreeTube 源码审计转**「材料」路线**:
> 真机 replay 实证 **浏览器亲手产出的材料(sabrUrl + body + 原 cpn)经我们的 OkHttp 原样重放即得 `status=1`
> + 完整媒体段**,且「补 / 不补 Cookie+visitor」两种传输形态结果**完全相同** ⇒ **nag 差异只在材料**。
> 把 WebView harvest 接成会话来源后,真机 r1958:`USING HARVEST MATERIAL` → `status=1 ×6`、零 `status=3`、
> 零 `Playback error`、16~19Mbps 在流。**待完善**:选定档落阶梯默认(body 的 formatId 字段搬家)、起播延迟
> = harvest 12~40s。详见 §6 计划与 §3 实测。
>
> **对照**:同一天的 NewPipe(visionOS)主路对**同一个门控视频** `status=1 ×24`、零 RELOAD、干净播通
> 100 秒以上(见 §3)。
>
> **2026-09-16 更新**:对 FreeTube 源码逐行审计后,**14 轮里追的方向有一半是空的** —— 桌面身份从未真正上线
> (见 §4 第 8 项),挑战与兑换不同源(§4 第 9 项)。缺口清单见 **§5**,据此重排的实现计划见 **§6**(P11-117/118)。
>
> **2026-09-19 更新(采集腿当天全废,非我们的改动)**:真机 r1979 整场 harvest **0 捕获**,watch 页恒
> `title= body=NOBODY player=false` 且**页内 JS 一行没跑**(零 YouTube console、零 `gv req`),同时 chromium 打了
> 48 次 `spdy_session.cc:2997 Received HEADERS for invalid stream`(09-17 正常日志 0 次);harvest 代码自 09-16
> 未改、同机同码 09-16/09-17 能出材料 ⇒ 判为**该机当天的 WebView 通路坏了**。据此加 **P11-125**:
> 补异常/页面层取证(§1.9)+ 空壳页丢弃实例重建(§1.11)。实测见 **§3.4**。
>
> **2026-09-19 二次判读(关键:真正的「播放不出」不是 WEB-SABR)**:第二次真机抓到
> `postPlayer … failed after 0ms (TimeoutCancellationException)` —— 上一份日志里三次「1ms 内 /player 失败」
> **是父协程已被取消**,不是网络/身份问题。父协程是 TV 起播写死的 30s 预算,而 WEB-SABR 优先链的固定开销
> 就有 ~21-28s ⇒ 整条 launch 被取消,**连已建好的 NewPipe 兜底会话也被丢弃**,用户黑屏 ~99s。据此加
> **P11-126**:起播预算按交付档给 + 不足早退 + 耗尽可见(§1.12)、harvest WebView 启动预热(§1.13)、
> 空壳页取证探针(§1.14,含 Sec-CH-UA / consent cookie / `; wv)` 三条社区线索,§3.6)。实测见 **§3.5**。
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
| `postPlayer … failed after Nms (类名: message)` + 栈 | [YoutubePlaybackResolver.kt:763-775](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L763-L775) | **P11-125**:rethrow 前落证。`after 1ms` = 瞬时抛错(会话数据/参数/WebView 状态);`after 30000ms` = 网络超时。两者修法完全不同 |
| `WEB-SABR(优先/兜底)链异常` | [YoutubePlaybackResolver.kt:187-190](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L187-L190) / [:280-283](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L280-L283) | **P11-125**:整条 `buildWebSabrFallback` 的 `runCatching` 落证(此前失败只剩一句 `abort`) |
| PAGE diag `dl=` / `rs=` / `ytcfg=` | [YoutubeSabrHarvester.kt:509](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt#L509) | **P11-125**:主文档 `documentElement.outerHTML` 长度 / `readyState` / kevlar 是否 boot。区别于 `body=NOBODY`:区分「空文档」与「渲染崩」 |
| `DOCLEN`(同步回传长度) | [YoutubeSabrHarvester.kt:500](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt#L500) | **P11-125**:与 PAGE diag 同源但走 `evaluateJavascript` 回传值(空壳判定必须同步拿到长度,不能等 console) |
| `onReceivedError(main frame)` / `onReceivedHttpError … mainFrame=` / fail-fast 汇总 | [YoutubeSabrHarvester.kt:357-393](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt#L357-L393) | **P11-125**:主文档错误**无条件**记(旧实现只记 URL 含 youtube/googlevideo 的);fail-fast / timeout 时汇总打 `mainFrameErr=` + `httpErr=` + `last=` |
| `webView=reuse\|rebuilt` | [YoutubeSabrHarvester.kt:189](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt#L189) | **P11-125**:上轮空壳被丢弃后这次必是 `rebuilt`;若重建后**仍然**空壳 ⇒ 问题不在实例层 |
| `prewarm: 采集 WebView 冷启完成 Nms` / `BiliWarmup: youtube harvest prewarm ok\|skipped` | [YoutubeSabrHarvester.kt](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt) / [AppContainer.kt](../app/src/main/java/com/kirin/mt/core/app/AppContainer.kt) | **P11-126**:预热是否真做、冷启实际多久(真机 09-19 是 10.9s) |
| `launch step: playurl (budget=Nms)` / `launch timeout after Nms (step=…)` | [PlayerScreen.kt](../app/src/main/java/com/kirin/mt/ui/player/PlayerScreen.kt) | **P11-126**:起播预算取到多少、预算耗尽死在哪一步(此前完全静默) |
| `WEB-SABR 优先:剩余预算 Nms < 45000ms → 跳过` | [YoutubePlaybackResolver.kt](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt) | **P11-126**:预算不足早退是否生效(有没有白烧 harvest) |
| `harvest forensic(...)` 四行 / `harvest main-doc request headers` | [YoutubeSabrHarvester.kt](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt) | **P11-126**:§1.14 的空壳页取证(见 §3.6 的四条社区线索) |

### 1.10 开关

`youtubeDeliveryPriority`(sabr/dash/websabr,[YoutubeDeliveryPriority.kt:18-29](../app/src/main/java/com/kirin/mt/core/player/YoutubeDeliveryPriority.kt#L18-L29))、
`youtubeUsePiped`、`pipedInstanceUrl`、`sabrForceSessionVideoItag`([AppSettings.kt:111-128](../app/src/main/java/com/kirin/mt/core/settings/AppSettings.kt#L111-L128));
TV 入口 [SettingsScreen.kt:1128-1178](../app/src/main/java/com/kirin/mt/ui/settings/SettingsScreen.kt#L1128-L1178),
移动入口 [MobileSettingsScreen.kt:1252-1281](../app/src/main/java/com/kirin/mt/ui/mobile/settings/MobileSettingsScreen.kt#L1252-L1281)。

### 1.11 空壳页处理:丢实例重建(P11-125)

alpha.61 起 harvest WebView **长期存活复用**(每次直接导航到新 watch 页,靠累积的真实浏览上下文躲风控)。
P11-125 给这条前提加了健康闸:

| 判据 | 阈值 | 动作 |
|---|---|---|
| `onPageFinished` 超时未触发 | `BLANK_PAGE_ABORT_MS = 8s` | fail-fast + `invalidateWebView()` |
| `onPageFinished` 已触发,但当前文档是 watch 页且主文档 `< EMPTY_DOC_ABORT_CHARS = 20KB` | 完成后 `DOC_LEN_CHECK_DELAY_MS = 1s` 量一次 | fail-fast + `invalidateWebView()` |

- 20KB 的依据:真实 watch 页 `documentElement.outerHTML` 恒 >100KB(kevlar 骨架 + 内联数据),空文档/错误壳只有几十字节。
- **watch 页闸门**:空壳判据只在 `lastFinishedUrl` 含 `/watch?` 时才生效 —— `stopPlayback()` 会把上个文档硬停到
  `about:blank`,它的 `onPageFinished` 可能落在新 harvest 的 `loadUrl` 之后(09-19 21:15:44 实测相差 16ms),
  不设这道闸会把正常重试误判成空壳。
- `invalidateWebView()` = `stopLoading()` + `loadUrl("about:blank")` + `destroy()` + 清 `webView`/`ready`/`pageFinishedMs`;
  下次 harvest 走 `ensureWebView()` 重建(重新加载首页建立上下文,即已验证过的冷启动路径)。
- **边界(诚实)**:Chromium 的网络栈(含 HTTP/2 socket 池)是**进程级**共享的,`destroy()` 只回收本实例的渲染进程与文档,
  cookie jar / 会话由 WebView 框架持有。若重建后仍空壳,`webView=rebuilt` + 同样取证即可判死「实例层」假设,
  直接指向网络/风控层 —— 这正是这条改动的第二个作用(把假设变成可判读的实验)。

---

### 1.12 起播预算:按交付档给,不够就早退(P11-126)

真机 09-19 判读:`WEB-SABR 优先` 链的**固定开销**已达 ~21-28s(PO token 铸造 ~9s + player jsUrl/
signatureTimestamp ~4s + harvest WebView 冷启 4~11s),而 TV 起播原本把整条 launch(resolve + CDN
选源 + 建 source + prepare)包在一个写死的 **30s** 里 ⇒ watch 页还没开始加载预算就到期,整条 launch
被取消,连**已经建好的 NewPipe 兜底会话也被一起丢弃**(用户黑屏 ~99s 直至手动退出)。

| 源 / 档 | 预算 | 依据 |
|---|---|---|
| B站 / 影视库(TVBox) / IPTV / 红果 / 未知源 | **30s**(不变) | 真机起播实测 1~3s |
| YouTube `SABR 优先` / `DASH 优先` | **45s** | 铸 token + 抓 player js |
| YouTube `WEB-SABR 优先` | **90s** | 额外承担 harvest WebView 冷启 |

取值的单一真源:[YoutubeLaunchBudget](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeLaunchBudget.kt)。
`PlayerScreen` 按 `youtubeDeliveryPriority`(P11-126 起从 AppShell 透传)取值,并把
`deadlineMs = now + budget` 一路传给 `getPlaybackInfo` → `YoutubePlaybackResolver.resolve`。

三件事都靠这个 deadline:

1. **预算不足早退**:进 WEB-SABR 优先/兜底前先看剩余预算,低于 `MinWebSabrFirstBudgetMs = 45s` 就
   整条跳过、直接落 NewPipe 主链 —— 不让注定失败的 harvest 白烧 40s+30s。
2. **harvest 超时收敛**:`harvestSessionMaterial` 的两次尝试从硬编码 `40s`/`30s` 改成
   `min(硬上限, 剩余 - FallbackReserveMs)`(`12s` 留给兜底落地),剩余不足 `MinHarvestAttemptMs = 3s`
   干脆不发。
3. **预算耗尽可见**:`withTimeoutOrNull` 返回 null 时打
   `launch timeout after Nms (step=…) → Failed(起播超时)`(此前完全静默,只能靠
   `Timed out waiting for 30000 ms` 的异常栈倒推)。注意 launch 超时**不会**触发自动重试
   (`onPlayerError` 只在 prepare 之后才可能回调),用户只能手点重试。超时文案也从写死的
   `起播超时（30s）` 改成带真实秒数 `起播超时（%1$d 秒）`(4 份 locale)。

### 1.13 harvest WebView 启动预热(P11-126)

`YoutubeSabrHarvester.prewarm()` 把那次冷启(建 WebView + 载 `https://www.youtube.com/`)挪到播放之外,
结束时调 `stopPlayback()` 硬停页面(只丢当前文档,实例/cookie jar/渲染进程保留)。触发点
`AppContainer.startYoutubeHarvestPrewarm()`(`BiliTvApplication.onCreate` 调用,启动后延迟 **8s**,
fire-and-forget、失败静默,与 `startIptvSourceProbe` 同款)。

**只在 `youtubeDeliveryPriority == WebSabr` 时做** —— 只有这一档 harvest 在起播关键路径上;
SABR/DASH 档的 harvest 只做很晚的兜底,不给不用它的用户白起一个 WebView + 拉一次首页。
**不发** `YoutubeLoadProgress`(全局单例,预热在播放器之外,emit 会留 stale UI 状态)。

并发:`ensureWebView` 的创建点用 `webViewMutex` 串行化(预热会在播放之外并发进入它);
`webView != null` 时 `prewarm` 直接 no-op。

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

### 3.4 2026-09-19:harvest 整场零捕获(采集腿全废,非代码回归)

日志 `logs_live_20260919_211811.log`(debug **r1979**,BRAVIA_AE2,「WEB-SABR 优先」档,三次 resolve / 两个视频)。

**症状**:整场 `captures=[1-9]` **0 次**、`USING HARVEST MATERIAL` **0 次**;每次 PAGE diag 恒为
`title=`(空)`body=NOBODY player=false vp=540x960 captures=0`。

**判据一:页内 JS 一行没跑。** 09-17 正常那次(21:34)首页加载时能看到 YouTube 自己的 console
(`LegacyDataMixin…@…/ytmainappweb…/kevlar_base…js`)、`PLAYERREQ`/`PLAYERRESP`、`gv req method=POST itag=? sabr=true`
→ `captured SABR POST status=200`;09-19 这些**一条都没有**(只剩我们自己的 PAGE diag),`gv req` 零条 ——
即文档拿到了、页内脚本从未执行,所以播放器没 init、不会发 SABR POST。

**判据二:chromium 的 HTTP/2 会话被搞坏。**

| 日志 | `spdy_session.cc:2997 Received HEADERS for invalid stream` |
|---|---|
| 09-16 r1962(正常) | 2 |
| 09-17(正常) | **0** |
| 09-19 r1979(失效) | **48** |

**判据三:首页那次 `onPageFinished` 从未触发** —— 21:15:07.837 `onPageStarted: https://www.youtube.com/`,
直到 12.2s 后的兜底超时才返回;watch 页要么 3s「完成」却给空文档(21:15:23.043),要么 8s 都不完成走 fail-fast。

**判据四:harvest 代码这次没动过** —— [YoutubeSabrHarvester.kt](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt)
最后一次改动是 09-16 `f30a4b07`(P11-118c);同机同码 09-16/09-17 能出材料;同一进程启动时(21:12)另一个 WebView
(`YtBrowserSession`)还能正常读到 visitorData/cookies。⇒ **09-19 那天这台电视的 WebView 通路坏了**,与我们的改动无关。

**回退自造材料那条腿当天同样不健康**:

- 视频 `qINttM4fKZo`:三次 `/player` 全部在 `WEB-SABR identity` 之后 **1ms** 内 `WEB /player failed → abort`,
  且 `postJson` 的诊断行一次都没打出来(异常抛在打日志之前),再被 `postPlayer` 的 `runCatching` 吞成一句**没有 message** 的失败。
- 视频 `5VmXkcTH4G8`:第 4 次 `/player` 成功、会话也建起来了(21:17:29 `WEB-SABR playback ready`),
  但**首个媒体段被服务端饿死**:rn=0 返回 104B、rn=1/rn=2 各 11B(只有一条无用的 `part type=67`),
  音频段 7.6s、视频段 **13.4s** 才回来 ⇒ prepare → 首个视频段共 **27s**。用户 21:17:54 退出,视频 21:17:56 才到 = 真机黑屏。

**对策(P11-125)**:埋点 + 空壳页丢实例重建,见 §1.9 与 §1.11。

### 3.5 2026-09-19 二次判读:真正的「播放不出」是起播预算,不是 WEB-SABR

`logs_live_20260919_214431.log`(r1980+,WEB-SABR 优先档,视频 `qINttM4fKZo` 续播 644s)。P11-125 加的
异常埋点**一次就定案**:

```
21:42:34.386 harvest failed: Timed out waiting for 30000 ms
21:42:34.396 WEB-SABR identity: …
21:42:34.399 postPlayer WEB viaWebView=false failed after 0ms
        (TimeoutCancellationException: Timed out waiting for 30000 ms) → 抛给调用方
```

`after 0ms` + `TimeoutCancellationException` = **父协程已被取消** —— 不是网络失败、不是身份/参数。
上一份日志里三次「1ms 内 `/player` 失败」是同一个原因。父协程是 `PlayerScreen` 的
`withTimeoutOrNull(LaunchTimeoutMs = 30_000)`,从 21:42:04.377 起算、21:42:34.386 到期。

后果:resolve 恢复时抛取消异常 ⇒ `launch step:` **只到 `playurl` 就没了**(正常应有
`cdn → prepare → BUFFERING → READY`),而 21:42:40.060 **已经成功建好的 NewPipe SABR 兜底会话**
因为协程已死而无人消费 ⇒ 用户从 21:42:34 黑屏到 21:44:13 手动退出,**约 99s**。
`?: Failed(player_error_launch_timeout)` 会显示「起播超时(30s),请重试」,且 launch 超时**不触发
自动重试**,只能手点。修法见 §1.12(预算按档 + 不足早退 + 耗尽可见)与 §1.13(预热)。

**空白页取证(新埋点)**:watch 页 `dl=39 rs=complete ytcfg=false title=` 空 —— 39 字符正好是空骨架
`<html><head></head><body></body></html>`(6+6+7+6+7+7);而**同一分钟 OkHttp 抓同一 watch 页得
1,437,481B 完整页**(21:42:07.071 `YtBotGuard: watch page data: ytcfg=true ytAtN=true ctx=true
(page=1437481B)`)⇒ **服务端对我们没关门,是 WebView/Chromium 那一路取不到 youtube.com 内容**。
WebView 提供方/版本自 09-16 未变(`com.sony.dtv.b2b.webview 1.0.2 code 6`,09-16 正常时也是它),
两条路径 **UA 完全一致**(`YoutubeConstants.UserAgent` 桌面 Chrome 126)⇒ UA 字符串不是差异点。

**P11-125 的行为改动本次仍未验证**:`onPageFinished`(21:42:34.437)比父预算取消(34.386)晚 51ms,
harvest 已死,空壳分支与新的取证探针都没机会跑到 —— 这也是 §1.14 的取证探针要包
`withContext(NonCancellable)` 的原因(取消态下普通 suspend 点会直接抛,证据永远打不出来)。

### 3.6 社区线索(2026-09-19 检索,用于 §1.14 取证设计)

FreeTube 侧(`v0.25.2` → `v0.25.3`,2026-08-11 / 08-28):

- **bgutils-js 3.2.0 → 4.0.2**(#9490)—— 我们捆的是 **v4.0.3**(见
  [youtube-hd-playback.md](youtube-hd-playback.md) §6.6),已对齐,不是缺口。
- **watch 页拿不到时回退用首页**取 poToken challenge data / ytcfg / playerId(#9637,关 issue #9632
  「Could not find ytcfg in the HTML page (**CAPTCHA page**)」),理由原文:*"The YouTube home page
  seems to work when the watch page is returning captchas."* 官方 NOTE:*"as long as YouTube requires
  us to use information from the HTML page, there will always be a chance that YouTube will return a
  CAPTCHA page instead."*
  **不能直接搬**:FreeTube 只要 ytcfg + challenge 就能自己铸 token 取流;我们的 harvest 腿必须让
  watch 页**真的播起来**才能截到 SABR POST,首页给不了这个。
- SABR redirect 没更新实际在用的 SABR URL(#9689)—— 记下备用。

更贴近我们 39 字节症状的两条(社区):

- **`Sec-CH-UA` 客户端提示与 UA 不一致**:Android WebView **会自动发送** Client Hints
  (`Sec-CH-UA: …"Android WebView"…`、`Sec-CH-UA-Mobile: ?1`、`Sec-CH-UA-Platform: "Android"`),
  **即使 UA 被覆盖成桌面字符串也一样**;机器人检测读这些,静默回空壳。且
  **`shouldInterceptRequest` 改不了、WebView 也没有 API 能覆盖/抑制 Sec-CH-UA**。
  → 与我们的现象高度吻合(WebView 身份自相矛盾 vs OkHttp 只有 UA 头、身份自洽)。
- **consent / 拦截壳**:有实测记录「HTTP 200、586KB、零视频」的空壳,补 `CONSENT=PENDING+987` +
  `SOCS=CAI`(免登录)可拿回真 payload。我们的采集 WebView 用自己的 cookie jar,是否带这两个 cookie
  从未查过。
- **UA 尾部 `; wv)`**:部分机器人检测专挑这个 token 静默剥内容。我们显式覆盖了 UA,理论上没有,但需实测确认。

**本轮决定:只取证不改行为** —— 先把 §1.14 的四组数据拿到,再定用哪条修法(consent cookie 注入 /
身份自洽 / 换实例)。

### 1.14 空壳页取证探针(P11-126)

`FORENSIC_JS` + `runForensicProbe(view, reason)`,在三处出口都调(onPageFinished 未触发 /
已触发但主文档 <20KB / 轮询到期),均在 `invalidateWebView()` **之前**。整段包
`withContext(NonCancellable)` —— 父预算取消时普通 suspend 点会立刻抛,证据就打不出来(§3.5 的教训)。

| 探针字段 | 判什么 |
|---|---|
| `dl` / `head`(前 240 字符) | 那 39 字节到底是什么(39 == 空骨架) |
| `enc` / `tr` / `dec`(`performance` 导航条目) | **决定性**:`enc≈1.4MB` 而 `dl=39` ⇒ 服务端发了、文档是空的(解析/渲染层);`enc≈39/0` ⇒ 服务端/链路真没发内容 |
| `ua` + `uadBrands`/`uadMobile`/`uadPlatform` + `__forensicUad`(高熵) | UA 与 Client Hints 是否自相矛盾 |
| `__forensicFetch`(页内同源 `fetch('/robots.txt')`) | 「导航路径坏」还是「WebView 网络整体坏」 |
| `harvest main-doc request headers`(Kotlin 侧,`shouldInterceptRequest` 打一次) | 同一时刻的请求头旁证(注:客户端提示不一定在此层可见,权威是 JS 侧) |
| `harvest forensic … cookie jar: socs=/consent=/visitor=` | 这两个 consent cookie 到底在不在 |

---

## 4. 当前未打通的关键点(占位 / 桩 / 死代码 / 口径不一致)

> **口径更新(2026-09-20,见 §5.6)**:本表是「全移动」之前的清单。其中「桌面身份」相关项(#5/#8/#9 的身份口径、以及被当作材料 token 来源的桌面挑战链)已随 P11-127 作废;下表保留作历史与后续清理参考。

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
**我们曾是"混合"**(移动铸造 + 桌面会话),两边都不是。
→ **2026-09-20 已按「全移动」收敛并打通,见 §5.6**(采集页/`/player`/会话 clientInfo 全部原生 Android;`clientName` 仍保持 1 以免动请求形状)。

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

### 5.6 2026-09-20 全移动:身份自洽 → `status=1` + 2160p(已打通,P11-127)

**结论先行:这条线通了。** 09-20 真机(`logs_live_20260920_091404.log`,`dev.r1992`,Sony XQ-EC72,WEB-SABR 优先档):`status=1` ×10 / `status=2` ×0 / `status=3` ×0;会话 18 轨含 **2160p**;单会话 09:12:40 连播到 09:14:38 用户退出(**~2 分钟**),`InvalidPoToken` / `auto-retry` / `playback error` / `stall detected` **全部零行**。

**怎么破的(单变量:身份)** —— 把 §5.1 G-3 判出的「混合身份」整条换成**原生 Android 移动**,四处同源:

| 位置 | 改前 | 改后 |
|---|---|---|
| 采集 WebView UA([YoutubeSabrHarvester.kt:391](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt#L391)) | 桌面 Chrome(Win64) | `MobileUserAgent` |
| 采集页 cookie seed | 只写 `www.youtube.com`(**host-only**) | 补写 `m.youtube.com`(移动 UA 会 302 过去) |
| `/player` 四个 override(`context`/`cookie`/`visitor`/`ua`) | 桌面 ytcfg context + 桌面 cookie/visitor + 桌面 UA | **全撤** → 落 `Client.WEB.userAgent` + `currentVisitorData()` + `currentSessionCookies()` + `buildContext(WEB)`(osName 来自移动 `sw.js_data`=Android) |
| SABR 会话 `clientInfo` / UA | `webDesktopSabrClientInfo`(clientName=1 + osName=**Windows**)+ 桌面 UA | `sabrClientInfo()`(clientName **仍=1**,osName=**Android**)+ 移动 UA;删掉 `webDesktopSabrClientInfo` |
| WEB-SABR 的 token 来源 | `botGuard`(桌面 watch 页取挑战 + `/att/get` 桌面 ctx) | **移动 minter**:`biliTvPoTokenProvider.ensureWebToken` = `PoTokenWebView`(SABR 主链同款,带缓存) |

**为什么必须这样**:
1. **Android WebView 覆盖 UA 改不了 Client Hints** —— `Sec-CH-UA-Platform` 恒 `"Android"`、`Mobile: ?1`,应用层**无 API 可覆盖/抑制**(本文件 §1.14 与 [YoutubeSabrHarvester.kt:460-462](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt#L460-L462) 早已记录)。桌面腿真机实证同一请求里 `UA=Windows NT 10.0` + `sec-ch-ua-platform="Android"` —— 「桌面身份」必然自相矛盾。FreeTube 桌面能全桌面是因为 Electron 有 `Emulation.setUserAgentOverride`+`userAgentMetadata`+`setDeviceMetricsOverride`,Android 应用拿不到这层。
2. **移动 UA 下 watch 页 302 到 m.youtube.com 且页内无 `ytAtN`**(§1.9 / P11-103)→ 桌面那条「页面取挑战 + 桌面 ctx 兑换」的 token 链在移动世界**结构性不存在**,必须换成本来就是移动的那枚 minter。
3. **`clientName` 保持 1 是刻意的**:[SabrMediaFetcher.kt:653](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt#L653) 的 `webShape = clientInfo.clientName == 1` 决定请求形状(4 字段 clientInfo + 不发顶层 `playerTimeMs`)。身份与请求形状是两个变量,一次只动一个。

**判读关键:采到的是「冷启桩」还是「真 token」**(§4 表的坑,现已绕开)。移动站播放器会连发多个 SABR POST:**首个带的是 cold-start 占位 token** —— 10 字节 = `0x22(34) 0x08(8) + 8B header`、identifier 长度 0(即 BgUtils 的 cold start 包格式),只在 `sps`(**StreamProtectionStatus**)`=2` 时有效;真 token 真机实测 **87–88B**。桌面腿恒抓到 10B 桩 ⇒ 会话 `status2Seen=0`、**第一个请求就被判死**。修法:采集改判 `poToken ≥ 80B` 才「命中即返回」,只拿到桩就再等 6s(`STUB_GRACE_MS`)找真 token、到期再退桩([YoutubeSabrHarvester.kt:333](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt#L333),`poTokenLenOf` 复用 `SabrProto.decodeVideoPlaybackAbrRequest`)。

**同时重新启用了 status=2 同步刷新**:P11-117 曾**故意**给 WEB-SABR 传 `refreshPoToken = null`(对齐 FreeTube「只对 `status===3` 反应」)—— 那是「桌面会话 + 移动铸 token」错配年代的决定,重铸了也不同源,刷了没用。身份统一后改回移动 minter 回调(与 SABR 主链同一枚;同步语义由 alpha.67/68 保证,P11-102c single-flight 防并发抢占)。**注意**:09-20 那轮服务端全程未 nag(`status=2` 零次),所以**这条回调整体尚未被真机触发验证**,它是「下次 nag 时的兜底」。

**残留(已记录,未修)**:
- **服务端 60s 级按会话处决仍在**:同日早先一轮(`logs_live_20260920_071935.log`)`hOv8` 会话 `status2Seen=5`(服务端从第 2 个响应起一路 nag)后播到 `sessAgeMs=35557` 升 `status=3`(回包仅 **135B**、无媒体)→ evict → ExoPlayer Source error → auto-retry @pos=63383ms(用户体感「能播但 ~60s 重载一次」);而**重新采集的新材料**建的会话立刻又是 `status=1` ⇒ 与「材料形态/身份」无关,是服务端策略,靠 status=2 刷新或提前轮换应对。
- **页面客户端是 MWEB,我们是 WEB**:`harvest ident host=m.youtube.com cfgName=MWEB cfgVer=2.20260918 cfgOs=Android`,而会话 `clientName=1`。材料与会话**不同客户端**却能 `status=1` ⇒ 这一层不是判据;若日后需要单开一轮评估 MWEB(必须连带验 `webShape` 翻 false 后的请求形状,不能和身份改动混判)。

---

### 5.7 2026-09-20 下午:同一残留**升级**为「必死」+ 运行时判死标记补齐(P11-138)

**现象**:`logs_live_20260920_151317.log` / `_151843.log`(`dev.r2008`,同机同视频)。WEB 会话**每会话第 2 笔**
就被 nag:实测 `rn=0`(`pot=88B`)→ `status=1`;`rn=1`(`pot=88B`,cookie=true)→ **`status=2`**
(`sessAgeMs≈5.5s`)→ 同步重铸 → `rn=2` 起 `pot=208B` → **`status=3`** 每笔死 → evict → 新会话原样重演。
整场 **0 个媒体段**。各版 status 分布对照,能播与否一目了然:

| 版本 | 成功段 | status=1 | status=2 | status=3 |
|---|---|---|---|---|
| r2002 | **115** | 100 | 0 | 0 |
| r2003 | 47 | 44 | 1 | 7 |
| r2006 | 108 | 88 | 1 | 7 |
| r2008 | **0** | **6** | **3** | **21** |

**这是 §5.6「残留」的升级,不是新根因**:失败态 diag 在 r2003/r2006/r2008 **三版逐字段一致**
(`sessAgeMs≈5.5s / sessReqN=2 / pot=208B / ctxActive=0 ctxStored=0 / unhandled=47x2,52x1,53x1`)
⇒ 机制相同,只是 §5.6 那轮是「能播但 ~60s 重载一次」,这轮退化成「一次都不播」。

**同一份日志里的对照(用户观测「SABR 可以,WEB-SABR 不行」的机器侧证据)**:15:18:24 起换成
pot-less 主路(`shape=libre`、**`pot=0B` 完全不带 PO token**、cookie=true)→ 一路 `status=1`,
15:18:26→15:18:29 **连续 20 段成功**。即:**WEB 形状必须带 token,而它带的 token 被服务端拒;
pot-less 那条不带 token,反而不被判死。**

**循环的真原因(本 commit 修的)**:`markWebSabrFailed` 机制本来就有,但差两处:

1. 它此前**只在「构建失败」时**被调(`buildWebSabrFallback` 返回 null)。今天构建从没失败(harvest 每次
   都交出 88B),所以标记从未置位。**已补**:fetcher 运行时撞 `InvalidPoToken status=3` 且
   `session.clientInfo.clientName == 1`(WEB 会话)时也置标记([SabrMediaFetcher.kt](../app/src/main/java/com/kirin/mt/core/youtube/sabr/media/SabrMediaFetcher.kt)
   的 status=3 抛出点)。只在 WEB 会话上标,pot-less 主路的 status=3 另有原因,不连坐。
2. **`webSabrFirst` 那条分支不查标记**([YoutubePlaybackResolver.kt](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt)
   的 `if (webSabrFirst && poToken != null && !webSabrFirstBudgetShort)`)—— 只补 fetcher 侧会漏掉它,
   「WEB-SABR 优先」设置下仍会建成功→clear→运行时死→标记→**本次仍重试**→建成功→clear→… 无限循环。
   **已补**:加 `isWebSabrFailed(videoId)` 守卫,与下方 `webSabrDue` 分支同款。

**修后的时序**:建成功→clear→运行时死→**标记**→下次 resolve 跳过 WEB-SABR → 落 NewPipe pot-less 主链
(今天的实测可播路径)。效果:把「无法播放」变成「第一次失败后自动换到能播的路」。

**仍待解决(未动)**:服务端为何在这轮把 WEB 会话的处决提前到第 2 笔。候选方向与 §5.6 同:材料形态/身份
已被那轮否定(「重新采集的新材料建的会话立刻又是 status=1」),指向服务端策略 + 会话轮换;而 §5.6 记的
「status=2 同步刷新回调**尚未被真机触发验证**」这轮被触发了 —— 刷新后的 208B token 依然被判 invalid,
说明**重铸这条兜底在当前形态下是无效的**。这条要单开一轮。

---

### 5.8 2026-09-20 晚:为「把 WEB-SABR 走通」补的取证工具 + 一条分岔判据(P11-139)

> 起因:§5.7 的处置是「失败即自动换到 pot-less」——那是**绕开**;用户要求的是**走通**。
> 要把 WEB 走通,先得能**看清**两边字节差在哪。动手前查工具,查出三处**结构性缺陷**(都不是逻辑
> bug,是「看不到」)。

| # | 缺陷 | 证据 | 修法 |
|---|---|---|---|
| ① | 我们的 `WEBREQDUMP` 被 **logcat 单行上限截断** | 真机 hex **3851 字符(奇数 = 半截)**,丢掉的正是 body **最后**的 `streamerContext`(clientInfo / poToken / playbackCookie = **身份段**)——恰恰是「能通 / 必死」最可能的差异所在 | 改**分片** dump:~1800 字符/行 + `part=i/n`,首片带 `potHex`;旧 grep 仍命中首片 |
| ② | **浏览器那份 body 的内容根本不在日志里** | 日志只有 `bodyB64=<长度>`(如 `19004B`),没有内容 ⇒ "我们的 body vs 浏览器的 body" 逐字段对比**结构上缺半边**(`tmp/webreq_diff.py` 按 base64 解必失败,实测) | 新增 `HARVBODY part=i/n bodyHex=…` 分片 dump,与 WEBREQDUMP **对称** |
| ③ | P11-118c 的**判别实验跑不到** | `replayHarvestCapture` 只在「材料解不出」的三个失败分支里被调;而今天失败形态是「材料解得出、会话也建起来了、运行时才被判死」⇒ **自 09-16 之后再没出过证据** | 见下:挂到 `resolve` 顶层 |

**③ 分岔判据(本轮核心产出)** —— 触发:上次 WEB 会话**运行时**被判死
(`SabrStreamRegistry.isWebSabrFailed`,由 fetcher 撞 `status=3` 置位,见 §5.7),且手里有那份材料的
原始捕获(本轮新增 `lastHarvestCapture`:材料**解得出**时也存下来;此前成功那份直接丢掉)——
则把**浏览器亲手产生、服务端已回 200** 的材料原样重放一发,记 `status=?`。**每视频一次**
(重放是白烧一发真实 POST,结论不因多跑而变;不限制反而把自己变成风控目标)。

**为什么必须挂在 `resolve` 顶层**:判死标记会让下面两条 WEB 分支整体跳过(`buildWebSabrFallback`
不再被调用),挂在 harvest 流程里就**永远跑不到** —— 这正是它 09-16 之后没出过证据的原因。

**结果二选一,决定后面所有工作的方向**:

| 重放结果 | 含义 | 下一步 |
|---|---|---|
| `status=1` | 材料是好的,差异在**我们的会话构造 / 传输** | **逐字段对齐可做,且那是唯一的活**(用 ①② 的完整字节) |
| `status=2/3` | 服务端不看材料(§5.6 已出过一次此结论) | **对齐字节没意义**,换杠杆:会话轮换节奏 / 身份 |

**④ 对比脚本的配对规则**(`tmp/webreq_diff.py`,不入库,已同步支持分片 + 旧单行两种格式):
必须按「**同 rn + 同 kind**」配对 —— 同为 `rn=1`,`seg=0`(init)与 `seg=N`(真实分段)的 body 结构
本来就不同(`clientAbrState` 无 `playerTimeMs`、无 `selectedFormatIds` / `bufferedRanges`),
**混比会读出假差异**(第一版脚本就差点这样误读:r2002 的 `rn=1` 是真实分段、r2008 的是 init,
字段数不同纯粹是 kind 不同)。

**明确不碰**(边界,写清免得下次误改):§5.6 已判读过的身份四处(UA / cookie / visitor / context)、
`webShape`、token 来源 —— 那些属于「对齐字节」分支里的活,**得等 ③ 的结果出来才谈得上**。

**待真机(一次即可)**:让一个视频的 WEB 会话死一次,看日志里 `P11-118 harvest replay[…]: … status=`
的值 ⇒ 按上表分流。

---

### 5.9 2026-09-20 夜:**首个双方字节对比** —— 首要嫌疑是「材料 MWEB / 会话 WEB」身份不一致(P11-139 产出的第一个结论)

工具补完后的第一份日志(`logs_live_20260920_154335.log`,`dev.r2011`)就给出了此前拿不到的东西:
同一份日志里**两边请求体都在**(我们的 `WEBREQDUMP` 分片 + 浏览器的 `HARVBODY` 分片),
用 `tmp/webreq_diff.py` 逐字段摊开后,**两边 `streamerContext.clientInfo` 不是同一个客户端**:

| | clientName | 其它字段 |
|---|---|---|
| **我们**(WEB-SABR 会话的真实请求体) | **1 = WEB** | 只有 4 字段(`clientVersion` / `osName=Android` / `osVersion=13`) |
| **浏览器**(那份被服务端回 200 的采集材料) | **2 = MWEB** | `f1=zh_CN`、`deviceMake=google`、`deviceModel=pixel 7`、`clientVersion`、`osName=Android`、`osVersion=13` |

> **更正一处首版误读**:首版还写了「我们的 URL 是 `c=WEB`、材料是 `c=MWEB`」——**不成立**。
> 那行 `WEB-SABR sabrUrl params(…) … c=WEB cver=null` 打的是 `sd = parseSabrData(player)`,即
> **我们自己 /player 响应**里的 URL,而它是在 harvest 期间顺带打的;**有材料时会话实际走的是
> `material.baseSabrUrl`**(`SabrSession.fromSabrBytes(material.baseSabrUrl, …)`),也就是浏览器那条
> URL。所以 URL 这一层是**同源**的,不构成差异。站得住的只有上表的 **clientInfo 不一致** ——
> 它来自双方**真实请求体**(我们的 `WEBREQDUMP` vs 浏览器的 `HARVBODY`),不是推断。

**机制**:harvest 采到的是 **MWEB 页面**的材料 —— `poToken` / `ustreamerConfig` / `cpn` 都是 MWEB 身份下铸的,
而我们把它包在 **WEB 会话**里发出去 ⇒ **令牌与身份不同源** ⇒ 服务端判 invalid ⇒ 每笔 `status=3`。

**这与 §5.6 的残留是同一条**,只是当时那轮还能 `status=1`,所以被判为「这一层不是判据」;现在有了字节证据,
它升为首要嫌疑。§5.6 当时已写明:「若日后需要单开一轮评估 MWEB(**必须连带验 `webShape` 翻 false 后的请求形状,
不能和身份改动混判**)」—— 那条警告现在正好适用。

**其它可见差异(次要,备查)**:①`clientAbrState` 形状:浏览器发富集合(`bitfield=3`、`drc=1`、`sticky=0`、
`viewport=1080x607`、`f57/f58/f59/f71/f72/f79/f80/f85`),我们发稀疏的 FreeTube WEB 集合(`bitfield=0`、
`sticky=720`、`viewport=640x720`);②`ustreamerConfig` 出现了**三个不同的数**:材料 `1275B` / 我们请求体
`2239B` / 会话日志 `3000B` —— 需查是否发错了那一份。

**判死→重放实验(P11-139 ③)仍未跑成**,原因不是接线:该日志末尾那次 `loadRequest`(15:43:28)之后
**1.8s `ExoPlayerImpl: Release`**(用户退出播放器),resolve 被取消 ⇒ 该实验**仍待一次不被打断的 resolve**。
不过材料被直接用于会话且照样死,已部分回答「材料内容」这一问 —— 问题更像在**我们给材料套的身份**。

**下一步三选一**(需拍板):

| 方案 | 内容 | 风险 |
|---|---|---|
| **A(推荐)** | 会话身份整体对齐材料:`clientName=2` + `c=MWEB` + `deviceMake/Model` + locale,并**同时**按 MWEB 对齐请求体形状(`webShape` 由 `clientName == 1` 派生,改它会顺带翻转形状) | 动的是 §5.6 已判读过的身份层,但这次**有字节证据**,且 §5.6 本就要求"评估 MWEB 必须连带验 webShape" |
| B | 只改 `c=MWEB` + clientName,形状不动 | 一半对齐可能是最坏情况(两边都不一致) |
| C | 先把重放实验跑出来再决定 | 最稳,需再跑一次真机 |

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

> **结果(2026-09-20)**:S1 触发过(桌面身份上线仍 nag)→ 按 S1 转「身份」假设以外的方向,最终以**全移动**打通(§5.6);**S2 未触发**(harvest 腿与身份都活了),本线保持启用。

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
