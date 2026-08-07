# YouTube 高清播放实现笔记

> 本文档记录 biliMT `core/youtube/` 实现 **YouTube 高清(1080P/2K/4K)播放** 的可行性分析、三个阻断点与实现方案。参考实现:FreeTube(本地/匿名,不登录——与 bilibili 主 App 登录策略一致,见 `docs/youtube-api-notes.md` §5)。数据来自代码实测(2026-08),协议来源 FreeTube / YouTube.js(MIT)。

## 1. 现状:最高只有 720p,但数据其实已就绪

YouTube 播放已上线(P11-09),走 `POST /youtubei/v1/player`(WEB→ANDROID 回退)+ `n` 解密,产出 progressive `PlaybackInfo`。但**实际最高只到 720p**,原因不是缺数据:

- `YoutubePlaybackResolver.parseFormat`(L244)已解析 `adaptiveFormats` 里的 `itag/mimeType/width/height/bitrate/qualityLabel`,**1080P/2K/4K 的 avc1/vp9/av01/hevc 候选都在**(L69-70)。
- 但 `buildInfo`(L113)只把选中的**单个**格式写进 `PlaybackInfo.qualities`,所以清晰度面板对 YouTube 只显示一项「当前清晰度」,没有多档可选。

> **结论:数据层已解析高清 itag,缺的不是数据,是三个环节的工程缺口。** 下文三个阻断点按"取流 URL → 选择策略 → 播放路径"排列。

## 2. 阻断点 1:取流 URL(硬前置)

高清流要能播,先得拿到**可播的直链 URL**。这里有两个坑:

### 2.1 无 PO token 时 adaptive url 常被剥离
`adaptiveFormats` 高清流在无 PO token 时**经常被 YouTube 剥掉 `url`**,只剩 `formats`(progressive)有 url([L68 注释](app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L68))。

### 2.2 `s` 签名解密未实现
当以 `signatureCipher` 形式返回时,`signatureCipherUrl`([L205-212](app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L205))**只回填未签名 url、不执行 `s` 解密** → 拿到的 url 必然 403。

**这是高清能否拿到可播直链的前提。**

| 形态 | 现状 | 需要 |
| --- | --- | --- |
| `url` 直给 | 直接可播 | 无需处理 |
| `signatureCipher`(s 签名) | 回填未签名 url → 403 | **实现 `s` 解密** |
| 无 PO token 被剥 url | 拿不到 | Tier 2:PO token(jnn) |

## 3. 阻断点 2:选择策略

`resolve()` 里 **Case B 优先取单个合并 progressive 流**(itag 18/22,≤720p),[L84](app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L84):

```kotlin
val combined = combinedCandidates.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
if (combined != null) { ... return buildInfo(...combined...) }
```

adaptive 高清永远选不到。**要让高清生效,须把可解的 adaptive 高清提到 progressive 之前,progressive 仅作兜底。** 同时用 `CodecCapabilityProbe`(B 站已有)过滤 adaptive 候选,避免选到 TV 解不了的 4K VP9/AV1(黑屏)。

## 4. 阻断点 3:播放路径

[PlayerScreen.kt:1390](app/src/main/java/com/kirin/mt/ui/player/PlayerScreen.kt#L1390) 因 YouTube 的 `segmentBase==null`(`isProgressive=true`)走 `ProgressiveMediaSource`/`MergingMediaSource`。但 L83 注释明说 **adaptive fMP4 分片喂 ProgressiveMediaSource 会解析失败**。

真正的 DASH 分支(L1407 `buildDashMediaItem`)对 YouTube 从未进入。**而 `buildDashManifest`(L2053)已正确处理 SegmentBase/Initialization——只要把 YouTube adaptive 的 `initRange`/`indexRange` 填进 `PlaybackTrack.segmentBase`,YouTube 就自动进 DASH 分支,零改动喂流。**

## 5. 实现方案:三个环节闭环(可行性=高)

复用现有基础设施,不引新播放器:

### ① `s` 签名解密(解锁高清 url)
复用隐藏 WebView JS 引擎 `YoutubeJsExecutor`(n 解密同款机制):拉 base.js 一次,识别签名函数执行 `s` 解密,回填 `signatureCipher` 的 url。**运行时依赖真机**(base.js 结构常变)。

### ② 高分辨 adaptive 设为首选
`pickVideo` 把可解的 adaptive 高清提到 progressive 之前;用 `CodecCapabilityProbe` 过滤设备解不了的轨道;`buildInfo` 把全部可播 quality 写进 `PlaybackInfo.qualities`,让清晰度面板出现 1080P/2K/4K 多档。

### ③ 走 DASH 播放(用现有 MPD 构建器)
`parseFormat` 补解析 `initRange`/`indexRange`(on-demand fMP4),填进 `PlaybackTrack.segmentBase` → 自动进 `PlayerScreen.kt:1407` DASH 分支。

## 6. 分阶段

| 阶段 | 内容 | 收益 |
| --- | --- | --- |
| **Tier 1(核心)** | ①②③ | 1080P,视设备与 url 可得性常到 2K/4K |
| **Tier 2(增强)** | PO token(jnn)跑通 | 覆盖「YouTube 剥光所有 adaptive url」的极端场景 |
| **Out of scope** | DRM 保护内容、8K、HDR | 与 B 站同理由:TV 面板普遍不支持,探测不到会黑屏 |

## 6.5 实测结论:高清唯一前置是 PO token(2026-08)

curl 实测 `/youtubei/v1/player`(guest,无 PO token):

| 客户端 | 结果 |
| --- | --- |
| WEB | UNPLAYABLE(被拦) |
| ANDROID | OK,29 个 adaptive 格式(2160/1440/1080p 都在),但**全部无 url、无 signatureCipher**——只有元数据 |
| ANDROID_VR / TVHTML5 / IOS | 全部失败 |

**结论:无 PO token 时 YouTube 在所有客户端剥掉流 url,只留元数据;360p 来自唯一的 progressive 兜底流(itag 18)。** 合并 ANDROID 客户端救不了高清,PO token 是唯一路径。

## 6.6 PO token 实现(跟随 bgutils-js, MIT)

- **打包**:`assets/youtube/bgutils.js`(esbuild 打包 bgutils-js v4.0.3,暴露 `__runSnapshot`/`__mint`)。
- **流程**(`YoutubeBotGuard`):`POST /api/jnn/v1/Create`(requestKey=`O43z0dpjhgX20SCx4KAo`)→ descramble(base64+97)→ interpreter JS 加载进 WebView → `__runSnapshot`(BotGuardClient.create + snapshot)→ `POST Waa/GenerateIT` → `__mint`(WebPoMinter)→ 视频 ID 绑定 PO token。
- **注入**:resolver 生成 token 后注入 `/player` 的 `serviceIntegrityDimensions.poToken`。
- **降级**:任一步失败返回 null,走无 token 直连(360p),不阻塞主路径。
- **脆弱点(需真机)**:snapshot 的 contentBinding `c` 值当前为占位(`b=PLACEHOLDER&hh=PLACEHOLDER`),需对照真实 player 响应/attestation 钉死;interpreter JS/WASM 能否通过 BotGuard 运行时校验。真机看 logcat `YtBotGuard` 行定位。

## 6.7 PO token 真机调试记录(2026-08,逐层推进)

真机(Sony XQ-EC72, Android 16)逐层修掉的障碍,按日志 `YtBotGuard`/`YtJsExecutor` 行定位:

| # | 真机日志 | 根因 | 修复 |
| --- | --- | --- | --- |
| 1 | `window.__runSnapshot is not a function` | WebView 被 app 后台销毁后 `bgUtilsLoaded` 残留 true,bundle 没重载 | `eval` 用「evaluateJavascript 抛错」检测 WebView 销毁,重建重试;`loadBgUtilsBundle` 验证 `__runSnapshot` 丢失重载 |
| 2 | `__runSnapshot typeof="undefined"`(bundle 加载后) | `createWebView` 返回时 js_shell.html 还在异步加载,`evaluateJavascript` 对未就绪 WebView 失败 | `onPageFinished` 置位 `shellReady`(CompletableDeferred),`eval` 前 `awaitShellReady`(最多 3s) |
| 3 | `pollState parse failed: "{\"status\":...}"` | `evaluateJavascript` 对 `JSON.stringify` 的 JS 字符串结果做 JSON 编码(带引号+转义),直接 `.jsonObject` 解析失败 | 先 `jsonPrimitive.contentOrNull` 解出内层字符串再解析 |
| 4 | `GenerateIT response missing integrityToken` | GenerateIT 响应格式 `[null, <ttl>, null, "<token>"]`,token 在 **index 3**(纯字符串),原解析只查 `{integrityToken}` 对象和 `[null,{integrityToken}]` | 加 `arr.getOrNull(3)?.jsonPrimitive?.contentOrNull` |
| 5 | index-3 修复后仍取不到 token | 修复里 `arr?.getOrNull(1)?.jsonObject` 在 `arr[1]` 是数字(43200)时抛 `ClassCastException`,index 3 永远取不到 | index 3 提到优先,整条解析链包 `runCatching`(cast 失败回退 index 3),加 `onFailure` 日志 |
| 6 | `PO token JS error: TypeError: Cannot read properties of undefined (reading '0')` at `_WebPoMinter.create` | bgutils.js `__mint` 先覆盖 `window.__poToken` 为 `{status:"minting"}` 再读 `prev`,导致 `prev.webPoSignalOutput` 为 undefined,`WebPoMinter.create` 里 `webPoSignalOutput[0]` 报错 | `__mint` 在覆盖 `__poToken` **之前**先捕获 `prev`,再取 `prev.webPoSignalOutput` |
| 7 | `PO token JS error: BgError: PMD:Undefined` at `_WebPoMinter.create` | `webPoSignalOutput[0]` 为空——snapshot 传了带占位符 `c`(b=PLACEHOLDER&hh=PLACEHOLDER) 的 contentBinding,VM 不产生 minter。FreeTube 只传 `{ webPoSignalOutput }` 不带 contentBinding,视频绑定在 mint 阶段用 videoId 完成 | `__runSnapshot` 的 `client.snapshot` 只传 `{ webPoSignalOutput }`,去掉 contentBinding |
| 8 | 去掉 contentBinding 后仍 `BgError: PMD:Undefined` | `webPoSignalOutput` 仍空——问题不在 contentBinding,而在 challenge 源:jnn `/api/jnn/v1/Create` 给的 program 不产生 minter。FreeTube 用 `/youtubei/v1/att/get`(ENGAGEMENT_TYPE_UNBOUND) | challenge 源对齐 FreeTube:`fetchBotGuardChallenge` POST `/att/get` + 从 `bgChallenge` 取 program/globalName/interpreterUrl,interpreter 单独 GET |
| 9 | `att/get response missing challengeData.bgChallenge` | 解析多套了一层 `challengeData`——FreeTube 的 `challengeData` 是响应根变量名,`bgChallenge` 在**根**(`challengeData.bgChallenge`),不是 `{challengeData:{bgChallenge}}` | 改 `jsonObject.obj("bgChallenge")`(去掉 challengeData 嵌套),加响应文本诊断日志 |
| 10 | `/att/get` 已取到 challenge(`program=35311B`)但 `webPoSignalOutput[0]` 仍空 → `PMD:Undefined`;首尝试 `PO token failed: timeout` | challenge 源已对(program 35KB > jnn 10KB),但 BotGuard VM 在该 WebView 环境**仍不产生 minter**(anti-bot 检测/运行时缺 API);program 变大后 8s 总超时不够,首尝试 VM 加载中就被杀 | **待查**:加日志确认 `webPoSignalOutput.length`;可能需 `skipPrivacyBuffer`/真实 contentBinding `c`/更完整的 WebView 环境;适当加长 OverallTimeoutMs |
| 11 | `PMD:Undefined` 主因疑为 WebView 环境检测 | BotGuard 的 minter(webPoSignalOutput[0])被反爬门控,检测 `navigator.userAgent`。默认移动端 WebView UA 被判定"非真浏览器",故只产 botguardResponse 不产 minter。FreeTube 跑 Electron(真桌面 Chromium UA)能拿到 | **尝试**:`YoutubeJsExecutor` 给 WebView 设桌面 Chrome UA(`settings.userAgentString = YoutubeConstants.UserAgent`),加 `__diag` 日志确认 `webPoSignalOutput.length`;`OverallTimeoutMs` 8s→20s |
| 12 | 桌面 UA + skipPrivacyBuffer 后 `webPoSignalOutput` 仍 `{"length":0}`(alpha.18/19) | minter 门控**不是** UA 或隐私缓冲能绕过的——是 BotGuard VM 对该 WebView 环境的**深度 anti-bot 指纹检测**(navigator 属性/WebGL/canvas/行为时序等)。bgutils.js 只转发 webPoSignalOutput,填充全在混淆 VM 内部,看不到触发点 | **结论**:在 Android WebView 跑通 BotGuard minter 极难;裸 JS 引擎(QuickJS/V8)更不像浏览器,大概率同样不产 minter。**待决策**:①加 WebView 环境诊断确认具体检测项;②QuickJS+browser polyfill(大改高风险);③搁置 PO token 接受 360p |

**已跑通(alpha.17~19)**:challenge 获取(`/att/get`,program≈35KB)→ interpreter 加载(`window.trayride` 定义)→ snapshot 成功(`botguardResponse` 拿到)→ GenerateIT 返回 `[null,43200,null,"<token>"]`。

**卡点(alpha.19 结论)**:`webPoSignalOutput[0]` 始终为空 → `WebPoMinter.create` 抛 `BgError: PMD:Undefined`。已试过且**都无效**:
- 换 `/att/get` challenge 源(program 35KB > jnn 10KB)
- 去掉 snapshot 的 contentBinding
- WebView 设桌面 Chrome UA(`__diag` 确认新构建在跑,仍 `length:0`)
- `skipPrivacyBuffer: true`

**根因判断**:BotGuard VM 对 Android WebView 环境的**深度 anti-bot 指纹检测**门控了 minter——bgutils.js 只把 `webPoSignalOutput` 转发给混淆 VM,minter 填充全在 VM 内部,产出 botguardResponse 但不产 minter。**裸 JS 引擎(QuickJS/V8)更不像浏览器,大概率同样不产 minter**。

**待决策方向**:①WebView 环境诊断(确认具体检测项,便宜);②QuickJS + browser polyfill(大改高风险);③搁置 PO token,接受 360p 兜底,优先其它功能。

| # | 新证据 | 结论/行动 |
| --- | --- | --- |
| 13 | 对照 **FreeTubeAndroid**(MarmadileManteater/FreeTubeAndroid,Cordova 包装 FreeTube web 版)原生侧 `BotGuardWebView.kt`:同一套 Android WebView + bgutils,能稳定产出 PO token 并切 1080P+。**它没用 QuickJS,也没换 UA** | 推翻 alpha.18/19「Android WebView 无法产 minter」结论——minter 门控不是「深度指纹不可绕」,而是**宿主页 document 上下文** |
| 14 | FreeTubeAndroid 关键差异:`loadDataWithBaseURL("https://www.youtube.com/", …)` 把宿主页 **origin 设成 youtube.com**(真浏览器页面环境);页内 `fetch` 经 `shouldInterceptRequest`→HttpURLConnection 注入 Referer/Origin/Sec-Fetch-*,并回注 `Access-Control-Allow-Origin: *`;`allowUniversalAccessFromFileURLs=true` + 每次 `clearCache(true)`。我们原来宿主页是 `file://`,origin 非 youtube,VM 判「非真页面」→ 不产 minter | **alpha.20 方案**:`YoutubeJsExecutor` 宿主页改 `loadDataWithBaseURL(Origin, js_shell.html, …)` 为 youtube.com 同源;加 shouldInterceptRequest 页内网络代理 + CORS;补 allowUniversalAccessFromFileURLs + clearCache。challenge/interpreter/GenerateIT 仍 Kotlin 发。真机看 `webPoSignalOutput.length` 是否 >0 |
| 15 | **alpha.20 真机突破**:`webPoSignalOutput diag={"length":1,"isFunc":"function"}` —— minter 终于产生!youtube.com 同源基址破了门控。但 GenerateIT 响应**格式随 minter 产生而变**:`["<token>",43200,100]`(token 在 **index 0**,对齐 FreeTube `response[0]`),不再是旧的 `[null,43200,null,"<token>"]`(index 3)。原解析只查 index 3/1,漏 index 0 → `GenerateIT token parse failed` → 降级无 token | **修复**:`generateIntegrityToken` 解析链加 `arr[0]`(字符串)优先,再回退 index 3/1。修后应能拿到 PO token → 注入 /player → 高清 adaptive url 落地 |
| 16 | **alpha.21 真机**:PO token 完整 minted(`poll #0 state={"status":"done","token":...}` + `YtResolver: PO token minted (128 chars)`)。但**高清仍没落地**:WEB `Video unavailable`(本环境 guest 整块被拦),ANDROID `adaptive=0 progressive=1`。根因:**token 是 WEB context 铸的,却发给 ANDROID /player → token 对 ANDROID 无效 → adaptive url 仍被剥**。`parseFormat` 把无 url 无 signatureCipher 的 adaptive 全滤掉(→adaptive=0),与 §6.5「ANDROID 无 token 剥光 url」一致。之前注释误以为「ANDROID 直接返回带 url 高清」,实测不成立 | **尝试**:`fetchBotGuardChallenge` 的 context 改 ANDROID,让铸 token 与 ANDROID /player 一致 |
| 17 | **alpha.22 真机(改 ANDROID context 后)**:`att/get response missing bgChallenge` → **ANDROID context 的 /att/get 不返回 bgChallenge**,铸 token 直接失败(降级无 token,ANDROID 仍 adaptive=0 360p)。印证 att/get 是 **WEB 客户端专属 challenge 通道**(FreeTube botGuardScript.js 也硬编码 X-Youtube-Client-Name:'1') | **回退**到 WEB context 铸取;给 WEB /player 失败加诊断日志 dump 完整 playabilityStatus。待真机看 WEB "Video unavailable" 的 status/reason/errorScreen 定位真因,再决定走 WEB(修 /player)或另寻 ANDROID 取流 |
| 18 | **alpha.23 真机**:PO token 完整 minted(WEB context,128 chars),但 WEB /player 仍 `UNPLAYABLE reason=Video unavailable`,errorScreen `playerErrorMessageRenderer` subreason="The page needs to be reloaded." → 降级 ANDROID 仍 adaptive=0 360p。**根因**:对照 FreeTubeAndroid(用 youtubei.js WEB session)与 youtubei.js 源码,纯 WEB 客户端**不设** thirdParty.embedUrl(HTTPClient.ts 里 WEB 走 default 分支),真正差异是 **WEB context 太单薄**——youtubei.js 的 WEB context 带 `mainAppWebInfo`/`clientScreen:"WATCH"`/`originalUrl`/`platform:"DESKTOP"`/`userAgent`/`timeZone`/`utcOffsetMinutes`/`screenWidthPoints` 等反爬字段,且请求带 `Origin`/`Accept`/`Accept-Language` 头;我们只有 clientName/clientVersion/hl/gl/visitorData + Referer 头。缺这些 WEB /player 被判"非真浏览器" → "The page needs to be reloaded"(playerErrorMessageRenderer),即使带有效 PO token 也被拦(§6.5 无 token 时 WEB 同样被拦,印证与 token 无关) | **修复**:①`buildContext` 对 WEB 客户端补全 youtubei.js 的 context 字段(mainAppWebInfo/clientScreen/originalUrl/platform/userAgent/timeZone/utcOffsetMinutes/screenWidthPoints 等);②WEB 请求加 `Origin`/`Accept`/`Accept-Language` 头;③`contentPlaybackContext` 对齐 youtubei.js(vis/splay/lactMilliseconds)。修后 WEB /player 应返回 OK + 带 url 的 adaptive 高清,PO token 与 WEB /player 同 context 绑定应生效 → 高清落地 |
| 19 | **alpha.24 真机(补全 context + Origin 头后)**:WEB /player **仍** `UNPLAYABLE "The page needs to be reloaded."`(PO token 照常 minted 128 chars)。**context 丰富度不是根因**。对照 youtubei.js `Session.ts #getSessionData`:FreeTubeAndroid 用 `createInnertube({ generateSessionLocally:false })` 从 `https://www.youtube.com/sw.js_data` 取**真实 visitorData**(`device_info[13]`)+ **当前 client version**(`device_info[16]`);我们一直用**合成** visitorData(`encodeVisitorData` 本地生成)+ 硬编码 client version。**WEB /player 用合成 visitorData 被判"非真浏览器" → "The page needs to be reloaded"**(ANDROID 对合成 visitorData 宽容,能返回 streamingData 但剥 url) | **修复**:`InnerTubeClient` 加 `ensureRealSessionData()`——首次请求前 GET `sw.js_data`(带 `VISITOR_INFO1_LIVE` cookie),解析 JSPB 取真实 visitorData + client version,缓存复用;`postJson`/`fetchBotGuardChallenge` 开头调用,保证铸 token 与 /player 用**同一真实 visitorData**(token 绑定前提)。失败回退合成 visitorData(不阻塞)。修后 WEB /player 应返回 OK + 带 url 的 adaptive 高清 |
| 20 | **alpha.25 真机(拉真实 visitorData 后)**:`real session data: visitorData=... clientVersion=2.20260805.01.00` **成功拉到真实 visitorData + 当前 client version**(比硬编码 2.20260623.01.00 新)。但 WEB /player **仍** `UNPLAYABLE "The page needs to be reloaded."`。**根因:并发 bug**——`real session data` 出现 3 次且 visitorData 各不同:铸 token 线程 fetch 到 visitorData B,`/player` 线程又 fetch 到 visitorData C 并覆盖缓存 → **token 绑定 B、/player 用 C → token 无效**。`ensureRealSessionData()` 的 `if (realSessionData != null) return` 有竞态,多线程并发时各自 fetch | **修复**:`ensureRealSessionData()` 加 `Mutex` 双检锁——`sessionMutex.withLock { if (realSessionData != null) return; fetch }`,保证**只 fetch 一次**,铸 token 与 /player 用**同一真实 visitorData**。修后 token 与 /player 的 visitorData 一致 → token 应生效 → WEB /player 返回 OK + 高清 |
| 21 | **alpha.26 真机(Mutex 双检锁后)**:`real session data` **仍出现 3 次**且 visitorData 各不同(A/B/C)。**Mutex 只锁单实例,跨实例无效**——根因是 **AppContainer 里建了 3 个独立 InnerTubeClient 实例**(BotGuard 用实例 A、Repository 用实例 B、PlaybackResolver 用实例 C),各自有独立的 `realSessionData`/`visitorData` 字段。BotGuard 铸 token 用实例 A 的 visitorData B,PlaybackResolver 调 /player 用实例 C 的 visitorData C → **token 绑定 B、/player 用 C → token 无效** | **修复**:AppContainer 共享**同一个** `youtubeInnerTubeClient` 实例给 BotGuard/Repository/PlaybackResolver,保证铸 token 与 /player 用**同一真实 visitorData**。修后 token 与 /player 的 visitorData 一致 → token 应生效 → WEB /player 返回 OK + 高清 |
| 22 | **alpha.27 真机(共享实例后)**:`real session data` **只出现 1 次**(22:34:59.077),铸 token 与 /player 用**同一真实 visitorData**。但 WEB /player **仍** `UNPLAYABLE "The page needs to be reloaded."`。**根因:PO token 放错位置**——对照 youtubei.js `Innertube.ts getInfo` + `HTTPClient.ts #processJsonPayload`:youtubei.js 把 `serviceIntegrityDimensions.poToken` 放请求**顶层**(`extra_payload.serviceIntegrityDimensions`),`context` 由 HTTP 层单独注入;我们一直把 `serviceIntegrityDimensions` 放 **context 里面** → **token 不被 YouTube 应用** → 仍 "The page needs to be reloaded" | **修复**:`postJson` 把 `serviceIntegrityDimensions.poToken` 移到请求**顶层**(`buildJsonObject { put("serviceIntegrityDimensions", {poToken}) }`),`buildContext` 不再放 context 里。修后 token 被 YouTube 应用 → WEB /player 应返回 OK + 带 url 的 adaptive 高清 |
| 23 | **alpha.28 方案(补 FreeTubeAndroid 剩余差异)**:对照 §6.8.4 表格,已对齐项之外还剩两处 FreeTubeAndroid 有而我们没有:①`contentPlaybackContext.signatureTimestamp`(youtubei.js 每个 /player 都带,从 player base.js 正则 `signatureTimestamp:(\d+)` 提取);②WEB 请求头 `Sec-Fetch-Site`/`Sec-Fetch-Mode`/`X-Youtube-Bootstrap-Logged-In`(FreeTubeAndroid shouldInterceptRequest 对 youtubei/ 注入的浏览器指纹头)。文档 §6.8.4 曾标 signatureTimestamp「待补」 | **修复**:①`YoutubePlaybackResolver` 加 `resolveSignatureTimestamp`(拉 base.js 正则提取,缓存复用)+ 注入 postPlayer 的 contentPlaybackContext;②`InnerTubeClient` WEB 分支加 `Sec-Fetch-Site: same-origin`/`Sec-Fetch-Mode: cors`/`X-Youtube-Bootstrap-Logged-In: false`。修后 WEB /player 应返回 OK + 带 url 的 adaptive 高清(待真机验证) |
| 24 | **alpha.29 方案(根因:请求没走真实浏览器网络栈)**:alpha.28 补全 signatureTimestamp + Sec-Fetch 头后 WEB /player **仍** `UNPLAYABLE "The page needs to be reloaded."`。**核对 FreeTubeAndroid 源码后的关键发现**:FreeTubeAndroid 的 **/player 请求走主 WebView(`FreeTubeWebView.kt`)的真实浏览器网络栈(Chromium)**,不是 shouldInterceptRequest——主 WebView 只有 `shouldOverrideUrlLoading`,**没有** `shouldInterceptRequest`;`getLocalVideoInfo` 里 `webInnertube.getInfo(id)` 是 youtubei.js 在主 WebView 直接 fetch → Chromium 真实网络栈,带真实浏览器头/cookie/TLS 指纹。shouldInterceptRequest + fetch 包装器(`Android.queueBody` + `x-fta-request-id`)只用于 **BotGuard WebView**(PO token 铸取)。**根因**:我们 OkHttp 直连被拦、FreeTubeAndroid 能过的根因是请求没走真实浏览器网络栈(Chromium)——HttpURLConnection(Java)和 Chromium 的 TLS 指纹/上下文不同 | **修复(方案 A,WebView 原生)**:`YoutubeJsExecutor` 加 `fetchViaWebView`——在隐藏 WebView 里 eval 一个同源 fetch(带 InnerTube 头 + body),`shouldInterceptRequest` 对 `/youtubei/v1/player` 返回 **null** 让 WebView 用 Chromium 真实网络栈原生发出(对齐 FreeTubeAndroid 主 WebView),响应存 `window.__webViewResp` 轮询取回;`InnerTubeClient.postJson` 加 `viaWebView` 参数,WEB /player 走 WebView 原生,ANDROID 保持 OkHttp 直连作回退。修后 WEB /player 应返回 OK + 带 url 的 adaptive 高清(待真机验证) |
| 25 | **alpha.30/2.0.9 真机突破(WebView 原生破拦截成功,但 adaptive 仍 0)**:`fetchViaWebView ok status=200 body=202714B` —— WEB /player 走 WebView 原生网络栈**成功返回真实 200**,`UNPLAYABLE "The page needs to be reloaded"` / `Video unavailable` 拦截**彻底消失**(方案 A 核心目标达成)。但 **`WEB formats: adaptive=0 progressive=1`**——PO token 照常 minted(128 chars)且经 postJson 放请求顶层 `serviceIntegrityDimensions.poToken` 随 WebView fetch body 一起发出,却**仍无 adaptive 高清**;ANDROID 也 `adaptive=0 progressive=1`(两客户端同为 guest 未登录)。`parseFormat` 对 `url==null && signatureCipher==null` 的项丢弃 → adaptive=0 意味着响应里 adaptiveFormats 要么没有、要么 url/signatureCipher 被剥空 | **下一步(待做)**:给 resolver 加诊断日志 dump WEB /player 的 `playabilityStatus.reason` + `streamingData.adaptiveFormats` 条数 + 首条 url 是否为空,定位是「token 没被应用(有 adaptive 但 url 空)」还是「guest 压根不给 adaptive(无 adaptiveFormats)」,再决定修法 |
| 26 | **2.0.10-alpha.1 真机诊断(回答 row 25 问题)**:`WEB diag: playable=OK rawAdaptive=30 parsedAdaptive=0 firstUrl=EMPTY firstCipher=none progressiveRaw=1`;`ANDROID diag: playable=OK rawAdaptive=31 parsedAdaptive=0 firstUrl=EMPTY firstCipher=none progressiveRaw=1`。**结论:adaptiveFormats 确实存在(30/31 条),但首条 url=EMPTY 且 signatureCipher=none** —— 坐实「token 没被应用(有 adaptive 但 url 空)」分支,排除「guest 不给 adaptive」。但矛盾:PO token 已 minted(128 chars)且注入请求顶层,YouTube 仍剥 url → **token 被 YouTube 判为无效**。对照 FreeTubeAndroid 源码(§6.8)发现两处差异:①snapshot 我们带 `skipPrivacyBuffer: true`,FreeTube 不带;②GenerateIT 端点我们用 `jnn-pa.googleapis.com/$rpc/.../Waa/GenerateIT`,FreeTube 用 `buildURL('GenerateIT', true)`=`www.youtube.com/api/jnn/v1/GenerateIT`(PR #6931 说明 youtube.com/api/jnn 是 jnn-pa 的代理,应等价)。另 FreeTube PR #6931 揭示 YouTube 已切到 **content PO token(绑定 videoId,加 /player 请求体)** 机制 | **本次修改**:①`bgutils.js` snapshot 去掉 `skipPrivacyBuffer: true` 对齐 FreeTube;②`InnerTubeClient.postJson` 加 /player 请求体诊断(dump `serviceIntegrityDimensions.poToken` 是否真的在 body 里,排除注入分支)。待真机验证:去掉 skipPrivacyBuffer 后 token 是否变有效(adaptive>0);若仍 0,看请求体诊断确认 token 在 body,再查 challenge context 是否缺字段 |
| 27 | **2.0.10-alpha.2 真机诊断(回答 row 26 待验证项)**:新增 `/player` 请求体诊断确认 **token 确实在 body 里**(`postJson /player client=WEB viaWebView=true poTokenArg=128B bodySID=present bodySIDToken=128B bodyLen=1839B`;ANDROID 同 `bodySIDToken=128B`)——**排除注入 bug**。但去掉 `skipPrivacyBuffer` 后 **adaptive 仍 0**(`WEB diag: playable=OK rawAdaptive=30 parsedAdaptive=0 firstUrl=EMPTY firstCipher=none`;ANDROID 同 31 条仍剥空)→ **token 仍被 YouTube 判无效,skipPrivacyBuffer 不是根因**。另发现 line 483 有个**无 token 的 WEB /player 调用**(`poTokenArg=null viaWebView=false`,发生在 token mint 之前),疑似另一代码路径(预检/搜索预览),待查是否干扰主流程 | **本次修改**:`YoutubeBotGuard.generateIntegrityToken` GenerateIT 端点从 `jnn-pa.googleapis.com/$rpc/.../Waa/GenerateIT` 切到 `www.youtube.com/api/jnn/v1/GenerateIT`(完全对齐 FreeTube buildURL('GenerateIT', true))。待真机验证:YouTube 托管端点 mint 的 token 是否变有效(adaptive>0);若仍 0,查 challenge context 是否缺字段 + 那个无 token 的 /player 调用 |
| 28 | **2.0.10-alpha.3 真机诊断(回答 row 27 待验证项)**:GenerateIT 切到 YouTube 托管端点后 **adaptive 仍 0**(`WEB diag: playable=OK rawAdaptive=30 parsedAdaptive=0 firstUrl=EMPTY firstCipher=none`;ANDROID 同 31 条仍剥空)→ **GenerateIT 端点不是根因**。另查清那个无 token 的 /player 调用是 `YoutubeRepository.getVideoDetail`(简介 Tab 元数据抓取,`postJson("/player")` 没传 poToken),发生在 token mint 前是因为视频页先抓详情后播播放,**不干扰播放主流程,排除** | **本次修改**:`YoutubePlaybackResolver` 加「带/不带 token 对比」诊断——同一 videoId 再发一次无 token 的 WEB /player(走 WebView 原生栈,与 with-token 同路径),dump `rawAdaptive`/`firstUrl` 与 with-token 对比。待真机验证:若带/不带 token 响应**完全一样**(都剥空)→ token 无效;若有差异 → token 在起作用但不够,再查 challenge context |
| 29 | **2.0.10-alpha.4 真机诊断(回答 row 28 待验证项)**:带/不带 token 对比——`with-token WEB: rawAdaptive=30 firstUrl=EMPTY`;`diag no-token WEB: playable=OK rawAdaptive=30 firstUrl=EMPTY`。**带/不带 token 响应完全一样(都剥空)→ token 完全无效**(在 body 里但被 YouTube 判无效)。对照 youtubei.js `#buildContext`(FreeTubeAndroid 传给 /att/get 的完整 session context)发现我们 `buildContext(Client.WEB)` **缺浏览器指纹字段**:`osName/osVersion`(device_info[17]/[18])、`browserName/browserVersion`([86]/[87])、`deviceMake/deviceModel`([11]/[12])、`memoryTotalKbytes`、`timeZone`([79])、`deviceExperimentId`([103])、`rolloutToken`([107])。BotGuard VM 用缺指纹的 context 产 token → 服务端校验拒绝 → adaptive 被剥 | **本次修改**:`InnerTubeClient.fetchRealSessionData` 扩展解析上述 device_info 字段存入 `RealSessionData`;`buildContext(Client.WEB)` 补浏览器指纹字段(有则带,`memoryTotalKbytes` 固定 "8000000",`timeZone` 用真实值回退 Asia/Shanghai);`fetchBotGuardChallenge` 加 challenge context 诊断 log(dump os/browser/device/mem/tz 确认字段真的进去)。待真机验证:补指纹后 token 是否变有效(adaptive>0);若仍 0,查 contentBinding 是否需带 `c` 字段 |
| 30 | **2.0.10-alpha.5 真机诊断(回答 row 29 待验证项)**:challenge context 补指纹字段**生效但没解决**——`challenge context: os=Windows/10.0 browser=Chrome/126.0.0.0 device=/ mem=8000000 tz=Asia/Shanghai`(deviceMake/Model 空是桌面浏览器正常),但 `WEB diag: playable=OK rawAdaptive=30 parsedAdaptive=0 firstUrl=EMPTY` 仍剥空。**挑战 context 补指纹不是根因**。同时确认 token 铸造管线完全正常:challenge → interpreter(62294B)→ snapshot(`webPoSignalOutput diag={"length":1,"isFunc":"function"}`)→ GenerateIT(`["<token>",43200,100]`,token 在 index 0 对齐 FreeTube)→ mint → 128B。token 铸得对、注入对、context 也对齐,但 YouTube 仍不认 → 剩余假设:visitorData 绑定错位(WebView 发 /player 时 cookie/header 的 visitorData 与铸 token 时不一致) 或 /player 请求经 WebView 是否真的带 token | **本次修改**:按 FreeTubeAndroid 加 **WEB_EMBEDDED 客户端**回退(`YoutubeConstants.WebEmbeddedClientVersion/Name/NameId`;`InnerTubeClient.Client.WEB_EMBEDDED` + buildContext 共享 WEB 浏览器指纹 + thirdParty.embedUrl + buildWebViewHeaders 用 clientNameId=56;resolver 循环 WEB → WEB_EMBEDDED → ANDROID,viaWebView 同 WEB)。FreeTubeAndroid 对 WEB 失败时回退 WEB_EMBEDDED(复用 WEB visitorData + 同一 WEB 绑定 token),该嵌入式客户端对 PO token 校验可能更宽容。待真机验证:WEB_EMBEDDED 是否返回带 url 的 adaptive(>0);若仍 0,加 visitorData 绑定诊断(dump /att/get 与 /player 的 visitorData 是否一致) |
| 31 | **2.0.10-alpha.6 真机诊断 + 根因定位(回答 row 30)**:WEB_EMBEDDED **死路**——每个视频硬 `Error code: 152 - 18 "This video is unavailable"`(客户端版本 `1.20260206.01.00` 过期 + 缺 embed 授权,直接排除,不再用)。三客户端(WEB/WEB_EMBEDDED/ANDROID)全部 `playable=OK` 但 adaptive URL 全 EMPTY;带/不带 token 响应完全一致(token 零作用)。**根因定位(核对 FreeTube botGuardScript.js + youtubei.js HTTPClient 源码)**:botguard 铸 token 全链路与 FreeTube **逐字节一致**(create 不带 visitorData/snapshot 只传 webPoSignalOutput/GenerateIT 同端点同 key/mint 用 videoId),token 本身有效;但 **/player 请求必须带与 visitorData 配对的 `VISITOR_INFO1_LIVE` cookie**(youtubei.js HTTPClient 第 137 行对每个请求设 `Cookie`,cookie 值==visitorData proto base64),YouTube 靠它验证 token 是否绑定真实浏览器会话。我们只带 `X-Goog-Visitor-Id: V`、cookie 却是 sw.js_data 抓取时**随机注入**的 `VISITOR_INFO1_LIVE=randomId()`(与 V 不配对)/WebView 自己的 → 无法绑定 → 剥空 adaptive。FreeTubeAndroid 能成就是因为它 /player 同时带配对 cookie + visitorData | **本次修改**:①`buildWebViewHeaders` 显式 `Cookie: VISITOR_INFO1_LIVE=currentVisitorData(); PREF=tz=Asia.Shanghai`(覆盖 WebView 不配对 cookie,删 CookieManager best-effort + import);②OkHttp 分支加同款 Cookie 头(所有 InnerTube 请求带会话 cookie);③sw.js_data 抓取**去掉随机 VISITOR_INFO1_LIVE 注入**(response body 的 device_info[13] 才是真实 visitorData,用它作配对 cookie);④/player 诊断加 `cookieV1L=<visitorData.take(24)>` 真机确认配对。待真机验证:adaptive 的 firstUrl 是否从 EMPTY 变 present |
| 32 | **2.0.10-alpha.7 真机诊断 + 进一步定位**:cookie 配对**确认生效**但 **adaptive 仍剥空**——`cookieV1L=Cgt0a0NKQ1NVYTVKYyigsNXT` == 会话 visitorData(三客户端都带上),token 铸对(128B)注入(bodySIDToken=128B),但 WEB/ANDROID 仍 `rawAdaptive=30/31 firstUrl=EMPTY`。**VPN 下重测也剥空**(第二个视频只播 360p progressive)→ **IP 网络限制被排除**。cookie 配对理论被证伪。**剩余差异(核对 youtubei.js Session)**:youtubei.js 的会话 cookie 是**抓真实首页**拿到的完整一套(`CONSENT`+`SOCS`+`VISITOR_INFO1_LIVE`+`PREF`),我们只发 `VISITOR_INFO1_LIVE`+`PREF`。YouTube 对**无 consent cookie** 的会话判"未同意浏览",常只回 progressive 不给 adaptive——最可能元凶。**本次修改**:①新增 `captureSessionCookies` 抓真实首页 `https://www.youtube.com/` 捕获全套 Set-Cookie(CONSENT/SOCS/VISITOR_INFO1_LIVE/PREF/YSC),存入 `RealSessionData.sessionCookies`,`currentSessionCookies()` 取用;②buildWebViewHeaders/OkHttp/att-get 全部改用完整会话 cookie;③**决定性诊断**:dump 第一条 adaptive 完整 JSON + 全表扫描 url/cipher/sabr/pot 类字段,确认 YouTube 到底给没给任何拿流方式。待真机验证:adaptive 是否解锁;rawAdaptive first format json 是否含 url/pot 字段 |

**注意**:早期注释说「`webPoSignalOutput` 显示 `[]` 是正常的(函数确实在数组里)」——**已被 alpha.17+ 证明错误**,数组确为空(否则不会 `PMD:Undefined`)。`JSON.stringify` 省略函数没错,但这里 minter 真的没生成。

## 6.8 FreeTubeAndroid 核心实现参考(2026-08 核对源码)

> 参考仓库:MarmadileManteater/FreeTubeAndroid(development 分支,Cordova 包装 FreeTube web 版)。
> 它是**唯一能在 Android WebView 稳定产出 PO token 并切 1080P+ 的参考实现**,逐文件核对如下。

### 6.8.1 架构:WebView 只跑 BotGuard,网络全走 Kotlin/原生

- **`android/.../webviews/BotGuardWebView.kt`**:隐藏 WebView,宿主页 `loadDataWithBaseURL("https://www.youtube.com/", …)` 把 **origin 设成 youtube.com**(真浏览器页面环境,破 minter 门控的关键)。`allowUniversalAccessFromFileURLs=true` + 每次 `clearCache(true)`。
- **页内网络代理**:`shouldInterceptRequest` 拦截页内 `fetch` → `HttpURLConnection` 转发,对 `youtubei/` 注入 `Referer`/`Origin`/`Sec-Fetch-Site`/`Sec-Fetch-Mode`/`X-Youtube-Bootstrap-Logged-In`,对 `google.com/js/` 注入 `referer`/`origin`/`Sec-Fetch-Dest`/`Sec-Fetch-Site`/`Accept-Language`;回注 `Access-Control-Allow-Origin: *`。GenerateIT 请求放行给原生。
- **`javascript/BotGuardJavascriptInterface.kt`**:`@JavascriptInterface returnToken(token)` 把铸好的 PO token 回传给 Kotlin;`queueBody` 缓存 GenerateIT 请求体。
- **`src/botGuardScript.js`**(webpack 打包成字符串,`evaluateJavascript` 注入):完整 mint 流程,见下。

### 6.8.2 PO token 铸取流程(botGuardScript.js,对齐 bgutils-js)

```js
// 1) att/get challenge(WEB 客户端专属通道)
fetch('https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false&alt=json', {
  headers: { Accept:'*/*', 'Content-Type':'application/json',
    'X-Goog-Visitor-Id': context.client.visitorData,
    'X-Youtube-Client-Version': context.client.clientVersion,
    'X-Youtube-Client-Name': '1' },   // 硬编码 WEB=1
  body: JSON.stringify({ engagementType:'ENGAGEMENT_TYPE_UNBOUND', context })
})
// 2) interpreterUrl 单独 GET,new Function(interpreterJavascript)() 定义 window[globalName]
// 3) BotGuardClient.create({ program, globalName, globalObject: window })
// 4) botGuard.snapshot({ webPoSignalOutput }, 10_000) → botguardResponse
// 5) GenerateIT: POST buildURL('GenerateIT') body JSON.stringify([requestKey, botguardResponse])
//    response[0] 必须是 string(integrityToken)
// 6) WebPoMinter.create({ integrityToken: response[0] }, webPoSignalOutput)
//    .mintAsWebsafeString(videoId) → 视频 ID 绑定的 PO token
```

**关键点**:
- **att/get 是 WEB 客户端专属 challenge 通道**(硬编码 `X-Youtube-Client-Name:'1'`);ANDROID context 的 att/get 不返回 bgChallenge(alpha.22 实测)。
- **snapshot 只传 `{ webPoSignalOutput }`**,不带 contentBinding(alpha.17 曾因带占位 contentBinding 报 `PMD:Undefined`)。
- **GenerateIT 响应 `response[0]` 是 integrityToken**(minter 产生后格式 `["<token>",ttl,n]`,token 在 index 0)。
- **token 绑定 videoId**(`mintAsWebsafeString(videoId)`)+ 铸取时的 context。

### 6.8.3 /player 请求(web 侧 local.js + youtubei.js)

- **`src/renderer/helpers/api/local.js` `getLocalVideoInfo`**:`createInnertube({ withPlayer:true })` 建 **WEB session**(`webInnertube`),`generatePOToken(id, JSON.stringify(webInnertube.session.context))` 把**完整 WEB context** 传给铸取,`webInnertube.session.player.po_token = contentPoToken`,再 `webInnertube.getInfo(id, { po_token })`。
- **token 与 /player 同 context 绑定**:铸取和 /player 都用同一个 `webInnertube.session.context`(含同一 visitorData),token 才有效。**这是高清落地的核心前提**。
- **youtubei.js `Innertube.ts getInfo` 的 /player body**:
  ```json
  { "videoId":"...", "racyCheckOk":true, "contentCheckOk":true,
    "playbackContext":{ "contentPlaybackContext":{ "vis":0, "splay":false,
      "lactMilliseconds":"-1", "signatureTimestamp":<player JS 提取> } },
    "serviceIntegrityDimensions":{ "poToken":"..." } }
  ```
- **youtubei.js `Session.ts #buildContext` 的 WEB context**(反爬关键字段):
  ```json
  { "client":{ "hl":"en","gl":"US","screenDensityFloat":1,"screenHeightPoints":1440,
      "screenPixelDensity":1,"screenWidthPoints":2560,"visitorData":"...",
      "clientName":"WEB","clientVersion":"...","osName":"...","osVersion":"...",
      "userAgent":"...","platform":"DESKTOP","clientFormFactor":"UNKNOWN_FORM_FACTOR",
      "userInterfaceTheme":"USER_INTERFACE_THEME_LIGHT","timeZone":"...",
      "originalUrl":"https://www.youtube.com","browserName":"...","browserVersion":"...",
      "utcOffsetMinutes":0,"memoryTotalKbytes":"8000000",
      "mainAppWebInfo":{ "graftUrl":"https://www.youtube.com",
        "pwaInstallabilityStatus":"PWA_INSTALLABILITY_STATUS_UNKNOWN",
        "webDisplayMode":"WEB_DISPLAY_MODE_BROWSER","isWebNativeShareAvailable":true } },
    "user":{ "enableSafetyMode":false,"lockedSafetyMode":false },
    "request":{ "useSsl":true,"internalExperimentFlags":[] } }
  ```
- **youtubei.js `HTTPClient.ts #setupCommonHeaders` 的请求头**:`Accept:*/*`、`Accept-Language:*`、`X-Goog-Visitor-Id`、`X-Youtube-Client-Version`、`X-Youtube-Client-Name`、`User-Agent`(桌面 Chrome)、`Origin: request_url.origin`。
- **纯 WEB 客户端不设 `thirdParty.embedUrl`**(HTTPClient.ts 里 WEB 走 `default` 分支;仅 TV_EMBEDDED/WEB_EMBEDDED 设)。**visitorData 也是本地生成的**(`ProtoUtils.encodeVisitorData(generateRandomString(11), now)`),与我们一致。

### 6.8.4 对我们实现的启示(alpha.23 结论)

| 维度 | FreeTubeAndroid | 我们(alpha.23 前) | 修复 |
| --- | --- | --- | --- |
| 铸取 context | 完整 WEB context(含 visitorData) | 最小 WEB context | 已补全(§6.7 row 18) |
| /player context | 同一完整 WEB context | 最小 WEB context | 已补全 |
| 请求头 | Origin/Accept/Accept-Language + Sec-Fetch-Site/Mode + X-Youtube-Bootstrap-Logged-In | 仅 Referer | 已加 Origin/Accept/Accept-Language + Sec-Fetch-* + Bootstrap-Logged-In(§6.7 row 23) |
| contentPlaybackContext | vis/splay/lactMilliseconds/signatureTimestamp | 仅 html5Preference | 已对齐(含 signatureTimestamp,§6.7 row 23) |
| 宿主页 origin | youtube.com 同源 | youtube.com 同源(alpha.20 已修) | ✓ |
| **/player 网络栈** | **主 WebView 真实浏览器网络栈(Chromium)**——`webInnertube.getInfo(id)` 在主 WebView 直接 fetch,带真实头/cookie/TLS 指纹 | OkHttp 直连(Java TLS 指纹,被判非真浏览器) | **alpha.29 已改**:`fetchViaWebView` 走隐藏 WebView 原生网络栈,`shouldInterceptRequest` 对 /player 返回 null 放行 Chromium(§6.7 row 24) |

**已补(§6.7 row 23)**:`signatureTimestamp`(从 player base.js 正则 `signatureTimestamp:(\d+)` 提取,缓存复用)已实现并注入 /player 的 contentPlaybackContext;WEB 请求头补 `Sec-Fetch-Site: same-origin`/`Sec-Fetch-Mode: cors`/`X-Youtube-Bootstrap-Logged-In: false`(对齐 FreeTubeAndroid shouldInterceptRequest 注入)。

**alpha.29 关键补(§6.7 row 24)**:FreeTubeAndroid 的 /player 走**主 WebView 真实浏览器网络栈(Chromium)**,不是 shouldInterceptRequest(那只用于 BotGuard WebView 铸 token)。我们 OkHttp 直连被拦("The page needs to be reloaded")、FreeTubeAndroid 能过的根因是请求没走真实浏览器网络栈。已实现 `YoutubeJsExecutor.fetchViaWebView`(隐藏 WebView eval 同源 fetch,shouldInterceptRequest 对 /player 返回 null 放行 Chromium 原生发出,响应存 `window.__webViewResp` 轮询取回),`InnerTubeClient.postJson` 加 `viaWebView` 参数,WEB /player 走 WebView 原生,ANDROID 保持 OkHttp 直连作回退。

**alpha.30/2.0.9 真机验证(§6.7 row 25)**:WebView 原生 /player **已真机验证成功**——`fetchViaWebView ok status=200 body=202714B`,拦截彻底消失。但 adaptive 仍 0(有 PO token 仍无高清),待加诊断 dump streamingData 定位。

**§6.7 row 33(alpha.9,VM 桌面指纹硬化)**:row 32 的 raw dump 证明 YouTube 对 guest+token 会话**拒签所有 DASH 流**——40 条 adaptive 全是纯元数据(initRange/indexRange/contentLength/qualityLabel 齐全),**无 url/cipher/sabr/pot**;ANDROID 仍给 progressive(360p 能播),WEB 连 progressive 都不给。token 结构在 body(128B serviceIntegrityDimensions)、visitorData 配对正确、session cookies 捕获(YSC+VISITOR_INFO1_LIVE,无 CONSENT)。结论:token 结构性有效但**信任度不足以解锁签名流**。根因锁定为 **botguard VM 环境指纹与 context 自相矛盾**——context 声称 `os=Windows/10.0 browser=Chrome/126`(桌面),但 VM 跑在安卓 WebView:UA 虽覆盖为桌面 Chrome,`navigator.platform=Linux aarch64`、`userAgentData.mobile=true`、`plugins` 空、`screen` 安卓物理像素、`maxTouchPoints=5`。FreeTubeAndroid 用 CDP `setDeviceMetricsOverride` 让 VM 呈现一致桌面指纹。**修复**:js_shell.html 头部加内联 polyfill,在 document 解析时(早于任何 eval 的 interpreter)覆盖 `navigator.platform/oscpu/vendor/appVersion/deviceMemory/hardwareConcurrency/maxTouchPoints/language/languages/webdriver/userAgentData/plugins/mimeTypes/connection` + `screen.*` + `window.devicePixelRatio/outer*/inner*/screenX/Y` 为桌面 Chrome 值;YoutubeBotGuard.runSnapshot 加 `VM fingerprint=` 诊断 dump 确认真机生效。

**§6.7 row 34(alpha.10,GenerateIT 补会话 cookie)**:row 33 的 VM 指纹 polyfill **真机完全生效**(`VM fingerprint={"platform":"Win32","uaMobile":false,"uaPlatform":"Windows","plugins":5,"screenW":1920,"screenH":1080,"dpr":1,"touch":0,"webdriver":false,"mem":8,"cores":8}`),但 adaptive **仍全剥空**(`rawAdaptive=40 firstUrl=EMPTY`,带/不带 token 响应逐字节同构)。**VM 指纹假设被证伪**。用户确认 FreeTubeAndroid 同机无登录能拿 1080p(1080p 只能走 adaptive DASH,progressive 最高 720p)→ 前提成立,必还有差异。**核对 FreeTube botGuardScript.js 发现关键差异**:FreeTube 的 **GenerateIT 在 WebView 同源发,自动携带完整浏览器 cookie(含 VISITOR_INFO1_LIVE)+ visitorData**;我们 OkHttp 直发 GenerateIT **只带 Content-Type/x-goog-api-key/x-user-agent/User-Agent,无 Cookie 无 X-Goog-Visitor-Id** → integrityToken 未绑定到会话 → 最终 PO token 无效 → /player 拒签 DASH。**修复**:`InnerTubeClient.currentSessionCookies()`/`currentVisitorData()` 改 public;`YoutubeBotGuard.generateIntegrityToken` 的 GenerateIT 请求加 `.header("Cookie", currentSessionCookies())` + `.header("X-Goog-Visitor-Id", currentVisitorData())` + 诊断 log dump cookie/visitor。待真机验证:GenerateIT cookie 是否带上 + adaptive 是否解锁。

**§6.7 row 35(alpha.11,WebView /player 补会话 cookie + VM 指纹深挖)**:row 34 真机确认 GenerateIT cookie 已带上(`GenerateIT cookie=YSC=...; VISITOR_INFO1_LIVE=CgtONTJpa3U1bjFpOCik2tXT visitor=CgtONTJpa3U1bjFpOCik2tXT`),但 adaptive **仍全剥空**。**GenerateIT cookie 假设被证伪**。真机日志确认 token 全链路正常:minter 产生(`webPoSignalOutput={"length":1,"isFunc":"function"}`)、GenerateIT 返回 Shape B(`["<token>",43200,100]`)、token 注入 /player(`bodySID=present bodySIDToken=124B`)、signatureTimestamp 解析(20670)——但带/不带 token 响应仍逐字节同构 → **token 被 YouTube 完全忽略,即密码学无效**。**核对 FreeTube poTokenGenerator.js 发现两个新差异**:(1) FreeTube 的 token 生成跑在 **Electron 独立 cookie-less session**(`session.fromPartition('potoken',{cache:false})`),GenerateIT 也**不带 Cookie/X-Goog-Visitor-Id**——我们 alpha.10 加的 cookie 是多余且非根因;(2) FreeTube 的 **/player 走主 Electron session 带真实浏览器 cookie**,而我们的 WEB /player 走 WebView `fetch`,**`Cookie` 是 fetch 的 forbidden header 被浏览器静默忽略** → WebView 原生网络栈不带会话 cookie → token 无法绑定会话。**修复**:`YoutubeJsExecutor.fetchViaWebView` 把 `Cookie` 头改写入 `CookieManager.setCookie("https://www.youtube.com", ...)` 让原生网络栈自动携带;js_shell.html 补 `navigator.vendorSub/productSub/appCodeName/appName/product/doNotTrack/pdfViewerEnabled` + `window.chrome` polyfill(安卓 WebView 缺/不同的强指纹信号);`YoutubeBotGuard.runSnapshot` 的 VM fingerprint 诊断扩到实际 `navigator.userAgent`/`window.chrome`/`document.URL`/`baseURI`;`InnerTubeClient.postJson` /player 诊断加 ctxOs/ctxBrowser/ctxMem 与 challenge context 对比。待真机验证:WebView /player 是否带上 cookie + adaptive 是否解锁。

**§6.7 row 36(alpha.12,SABR 拿流机制——根因候选)**:row 35 真机确认 VM 指纹**完全桌面化**(`ua=...Chrome/126.0.0.0 platform=Win32 uaMobile=false plugins=5 chrome=object docURL=https://www.youtube.com/`)、/player context 与 challenge context **逐字段一致**(`ctxOs=Windows/10.0 ctxBrowser=Chrome/126.0.0.0 ctxMem=8000000`)、CookieManager 写入生效——但 adaptive **仍全剥空**(`rawAdaptive=30 firstUrl=EMPTY`)。**Cookie/VM 指纹/context 匹配假设全被证伪**。**用户提示 FreeTube 播放有 SABR 后退时间 → 核对 FreeTube createLocalSabrManifest 发现根因候选**:FreeTube **不用 legacy DASH 直链**,而是用 `/player` 响应的 **`streaming_data.server_abr_streaming_url`(SABR 基址)+ adaptive 元数据(itag/initRange/indexRange)** 构建 SABR manifest,把 poToken 传给 SABR 流播放。**即 YouTube 对 guest+token 会话不再在 adaptiveFormats 里给签名直链,而是期望客户端走 SABR 协议**——我们死磕 legacy DASH 的 url 空是方向错了。**本次修改**:`YoutubePlaybackResolver` 诊断加 `sabrUrl=present/ABSENT` dump(带/不带 token 都加),确认 /player 是否真有 `server_abr_streaming_url`。待真机验证:sabrUrl 是否 present;若 present → 下一步实现 SABR 播放(构建 SABR manifest + 走 SABR 流 + 传 poToken)。

**§6.7 row 37(alpha.13,移动一致化——SABR 证伪 + 根因锁定)**:row 36 真机日志三 case(WEB 带/不带 token、ANDROID)全部 `sabrUrl=ABSENT` → SABR 第一道闸不存在,**SABR 方向整个关闭**(FreeTube 决策逻辑根本不会走 SABR,ustreamerCfg 第二道闸无需验证)。反推:FreeTubeAndroid 同机无登录能拿 1080p,其 /player adaptive 必带签名直链 url → **FreeTube 的 token 是真的,我们密码学无效**。**系统性 diff FreeTubeAndroid 源码(本地参考:github.com/MarmadileManteater/FreeTubeAndroid development)锁根因**:FreeTubeAndroid 全链路**零 UA 覆盖、一致地移动**——原生移动 WebView UA(无 setUserAgent/无 setDeviceMetricsOverride)、原生移动 VM 指纹、sw.js_data(移动 UA)报 Android context(osName=Android/browserName=mobile Chrome);`platform=DESKTOP` 是 youtubei.js `device_category` 默认(desktop),非差异。**而我们 alpha.8/9 把 UA/VM 指纹/context(os=Windows)全硬成桌面** = Android 真机上跑桌面 polyfill,假环境与真实设备不一致 → token 判无效 → DASH 拒签。youtubei.js `#adjustContext` 对 WEB 分支不覆盖任何字段(平台保持 DESKTOP、clientFormFactor 保持 UNKNOWN_FORM_FACTOR),`#buildContext` 的 osName/browserName 来自 sw.js_data device_info([17]/[18]/[86]/[87])。**修复(移动一致化)**:①`YoutubeConstants.MobileUserAgent`(移动 Chrome UA:Android 13 Pixel 7);②`YoutubeJsExecutor` WebView `settings.userAgentString` 桌面→移动;③`InnerTubeClient` 全链路改移动 UA:WEB 客户端 userAgent、`buildWebViewHeaders`、`fetchBotGuardChallenge`、`fetchRealSessionData`(sw.js_data,让 context 报 Android)、`getText`、`captureSessionCookies`、`buildContext` 的 client.userAgent;④`YoutubeBotGuard.generateIntegrityToken` UA 改移动;⑤`YoutubePlaybackResolver` watch 页+base.js UA 改移动;⑥`js_shell.html` **删除桌面指纹 polyfill**(露出原生移动指纹,与移动 context 一致)。待真机验证:VM fingerprint 是否移动值 + context osName 是否 Android + adaptive 是否解锁(firstUrl EMPTY→present)。

**§6.7 row 38(alpha.14,诊断根因修正——camelCase 假阴性推翻 row 37「SABR 证伪」+ n-decrypt plasma 失配)**:核对 FreeTubeAndroid `src/renderer/views/Watch/Watch.js` `createLocalSabrManifest`(L1617)与 `src/renderer/helpers/api/local.js`(L542 `info.streaming_data.server_abr_streaming_url`、L884 决策逻辑 `streaming_data?.server_abr_streaming_url && player_config.media_common_config.media_ustreamer_request_config`)发现**致命字段名错位**:FreeTube 代码读的全是 **snake_case**(`server_abr_streaming_url`/`player_config.media_common_config.media_ustreamer_request_config.video_playback_ustreamer_config`/`adaptive_formats[0].signature_cipher`)——但这是 **youtubei.js 库端把 raw InnerTube 响应的 camelCase 转 snake_case 后的形态**,不是 raw JSON 的 key。我们 `YoutubePlaybackResolver` 读的是 **raw /player JSON(camelCase)**:`streamingData`/`adaptiveFormats`/`formats`/`signatureCipher`/`url`/`itag` 全 camelCase 且**解析成功**(rawAdaptive=40、metadata 齐全)——唯独 SABR 两道闸用了 snake_case `server_abr_streaming_url`/`player_config.media_common_config...` → raw JSON 里**根本没有这俩 snake_case key** → row 37 三 case 全报 `sabrUrl=ABSENT ustreamerCfg=ABSENT` 是**假阴性**,不是 YouTube 真没给。**这推翻 row 37「sabrUrl=ABSENT → SABR 方向整个关闭」的结论**——SABR 方向**重新打开**。旁证:row 33-37 一直困惑的「adaptive 40 条全是纯元数据(initRange/indexRange/contentLength/qualityLabel 齐全)但无 url/cipher」**正是 SABR 模式的特征形态**——YouTube 期望客户端走 SABR(serverAbrStreamingUrl + 元数据构建 manifest),adaptiveFormats 本就不该有 url;读错 key 看不到 sabrUrl,误判「YouTube 拒签 DASH」。**本次修改**:`YoutubePlaybackResolver` 诊断 SABR 字段全改 camelCase(`serverAbrStreamingUrl`、`playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig.videoPlaybackUstreamerConfig`),并补 `streamingData.keys`/`playerConfig.keys`/首条 adaptive 全 key 的**原始 key 全量 dump**——一次真机坐实理论与 SABR 数据是否真 present。**n-decrypt 独立根因(同步定位,本次未修)**:row 37 progressive itag=18 回退 403,日志 `YtNDecrypt: could not locate transform name`。拉当前 base.js(`player-plasma-es6-en_US.vflset/base.js`,player id 95daa498,1480318B)发现 YouTube 已切 **plasma 播放器变体**,经典 n-transform 锚点 `.get("n"))` **彻底不存在**——`YoutubeNDecryptor` 三条正则全依赖 `.get("n")` 或 `(b=...` 形态,对 plasma 全失配 → n 原样未动 → googlevideo 403。需按 plasma 结构重写 n-transform 定位(待补)。待真机验证(alpha.14):①`streamingData keys` 是否含 `serverAbrStreamingUrl`;②`playerConfig keys` 是否含 `mediaCommonConfig`→`mediaUstreamerRequestConfig`→`videoPlaybackUstreamerConfig`;③sabrUrl/ustreamerCfg 是否 ABSENT→present。若 present → SABR 数据齐全,下一步实现 SABR 播放(Media3 无原生 SABR source,需自建,工程量大);若仍 ABSENT → 诊断已无歧义,转其它拿流路径。n-decrypt 不影响 SABR 主路径(先确认 SABR present 再投入)。

**§6.7 row 39(alpha.14 真机结果——SABR 确认成立(camelCase 理论坐实)+ plasma WASM n-decrypt 深挖定位)**:真机日志(15:48,videoId=m-zCHXBbO58)直接坐实 row 38 的 camelCase 理论:`WEB streamingData keys=[expiresInSeconds, adaptiveFormats, serverAbrStreamingUrl]`、`ANDROID streamingData keys=[expiresInSeconds, formats, adaptiveFormats, serverAbrStreamingUrl]` → **`serverAbrStreamingUrl` 真的 present(WEB 918B、ANDROID 883B,带/不带 token 都 present)**。alpha.13 的 `sabrUrl=ABSENT` 确认是 snake_case 假阴性,**SABR 方向确认成立,row 37「SABR 证伪」彻底推翻**。adaptive 仍是 40/41 条纯元数据(无 url/cipher)——正是 SABR 模式形态(YouTube 期望客户端走 serverAbrStreamingUrl + 元数据构建 manifest)。**gate 2(ustreamerCfg)诊断需修正**:我们读了子层 `playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig.videoPlaybackUstreamerConfig` 报 ABSENT,但 `mediaCommonConfig` 在 playerConfig keys 里;FreeTube Watch.js L884 的 gate 只查**父层** `media_ustreamer_request_config`(不查 videoPlaybackUstreamerConfig),createLocalSabrManifest 才用子层——需补查父层 `mediaUstreamerRequestConfig` present/ABSENT 才能定 SABR gate 2。其它:WEB 现在 `progressiveRaw=0`(连 progressive 都不给,纯 SABR);ANDROID `progressiveRaw=1`(仍给 itag=18,resolve 落 360p)。**plasma WASM n-decrypt 根因(深挖 base.js 锁定,非正则可修)**:拉当前 base.js(`player-plasma-es6-en_US.vflset/base.js`,player id 95daa498,1480318B)发现 YouTube 已切 plasma 变体,n/sig 解码**移进 WASM**,无 JS n-transform 函数可正则定位——证据:①`get("n")` 全表只 1 处,在 `yoh` 函数里做 URL 路径 `/n/X` 规范化(非签名 transform);②经典 n-transform 调用点 `.get("n"))&&(b=Name(c))`/`&&(b=Name(c)` **全 0 匹配**,`b=Name(c)` 只 1 处且无关;③`"sig"`/`sig=`/`decipher`/`set("n"` 全 0,`nsig` 只匹配无关子串;④WASM 重度:`WebAssembly`×6/`wasm`×44/`o_W`(WASM 模块)×2,`signature`/`encrypt` 跑在 WASM 实例 `c.DY` 上(`c.DY.memory.moveOut`、`x.encrypt(I,...,iv)`)。→ `YoutubeNDecryptor` 的「正则找名 + WebView eval 调用」方案对 plasma **结构性失效**,alpha.13/14 的 `could not locate transform name` 不是正则写错是 player 变体变了(已存 memory `youtube-plasma-wasm-n-decrypt`)。**下一步路径(待定,非一正则可修)**:①加 IOS InnerTube 客户端(yt-dlp 用法,常给无 n-param 直链绕开 n-decrypt,中等工作量,最务实);②实现 SABR(gate 2 父层先补诊断确认,SABR url 仍可能需 decipher 但走 poToken+ustreamerConfig);③经 WebView 调 plasma WASM 入口(找混淆入口难);④移植 youtubei.js decipher(jintr,巨大)。

**§6.7 row 40(alpha.15/16 确认 SABR 数据齐全 + Phase 2a 协议往返探针 + proto 字段号全量核对上游)**:alpha.15 真机(16:15)补查 gate 2 **父层** `mediaUstreamerRequestConfig` = **present**(WEB 3147B、ANDROID 3504B)——FreeTube Watch.js L884 的 gate 判据通过,SABR 两道闸全过。alpha.16 真机(16:26)进一步定位 `videoPlaybackUstreamerConfig` 的真实类型:它是 proto **bytes**,在 raw JSON 里是 **base64 字符串**(`ustreamerCfgStr=present`,WEB 3100B / ANDROID 3352B),`.obj()` 必返回 null(alpha.15 报 ABSENT 是类型不符假阴性,非真无)。子层 keys:WEB=`[videoPlaybackUstreamerConfig]`、ANDROID=`[enableVideoPlaybackRequest, videoPlaybackUstreamerConfig, videoPlaybackPostEmptyBody, isVideoPlaybackRequestIdempotent]`。→ **SABR 数据三要素全齐**:sabrUrl + ustreamerConfig bytes + adaptive 元数据(itag/lastModified/xtags)。

**Phase 2a 协议往返探针(本提交)**:`YoutubePlaybackResolver` 在 WEB+SABR 数据齐全时,从 rawAdaptive 直读(itag/lastModified/xtags,绕开 `parseFormat` 对无 url 的 SABR adaptive 的过滤)构造 `SabrFormatId`,建 `SabrSession` + 发一次 **init** 段请求(POST `sabrUrl?alr=yes&cpn=<16B>&rn=N`,headers `content-type:application/x-protobuf / accept:application/vnd.yt-ump / accept-encoding:identity`),流式解析 UMP 响应,匹配 MEDIA_HEADER→收集 MEDIA→MEDIA_END 收尾。**仅诊断**——拿回字节即证明协议层通(encode→POST→UMP→MEDIA 全链),不接 Media3 播放(Phase 2b 再接)。探针 log 覆盖:每个 part type+名+payloadLen、MEDIA_HEADER 真实 itag/lmt/xtags/isInit/seq/contentLen/dur + matched 对比、STREAM_PROTECTION_STATUS(status=3→InvalidPoToken)、SABR_REDIRECT/Error/NextRequestPolicy。

**proto 字段号全量核对上游(本提交)**:逐个拉 `LuanRT/googlevideo` 仓库 proto 源文件核对 Kotlin 移植字段号,全部正确——`media_header.proto`(header_id=1/video_id=2/itag=3/lmt=4/xtags=5/is_init_seg=8/sequence_number=9/start_ms=11/duration_ms=12/content_length=14;**itag 是顶层 int32 非 nested format_id**,format_id=13 子消息我们不解码因 itag/lmt/xtags 已顶层齐全)、`video_playback_abr_request.proto`(client_abr_state=1/selected_format_ids=2/buffered_ranges=3/player_time_ms=4/video_playback_ustreamer_config=5/preferred_audio=16/preferred_video=17/preferred_subtitle=18/streamer_context=19)、`streamer_context.proto`(client_info=1/po_token=2/playback_cookie=3/sabr_contexts=5/unsent_sabr_contexts=6)+ ClientInfo(device_make=12/device_model=13/client_name=16/client_version=17/os_name=18/os_version=19/accept_language=21/accept_region=22/screen_width=37/screen_height=38/screen_pixel_density=41/client_form_factor=46/android_sdk=64/screen_density_float=65/utc_offset=67/time_zone=80)、`client_abr_state.proto`(field13/16/18/19/21/22/23/28/35/40/46/76 全对)、`buffered_range.proto`(format_id=1/start_time_ms=2/duration_ms=3/start_segment_index=4/end_segment_index=5/time_range=6)、`misc/common.proto FormatId`(itag=1/last_modified=2/xtags=3)、响应 `stream_protection_status`(status=1)/`sabr_error`(type=1/code=2)/`sabr_redirect`(url=1)/`next_request_policy`(backoff_time_ms=4/playback_cookie=7/video_id=8)。**UMP varint 与 `UmpReader.ts` 逐 case 对齐**(1/2/3/4/5 字节全同),`ump_part_id.proto` 全枚举值核对。**MEDIA/MEDIA_END 首字节=headerId** 与 `SabrUmpProcessor.handleMedia/handleMediaEnd` 一致。→ 协议层移植正确性已尽可核对,剩唯一未知=真机往返是否被服务端接受(探针即验此)。待真机(alpha.17)验证探针 log:Success bytes? / InvalidPoToken? / Error? 据结果定 Phase 2b(Media3 接线)或调 PO token 绑定。

**§6.7 row 41(alpha.17 真机——探针触发,协议 encode 成功,但 googlevideo CDN 返 HTTP 403 空响应体;alpha.18 加会话传输头)**:alpha.17 真机(17:12)探针**成功触发并 encode**(log:`SabrSession: sabrUrl=https://rr1---sn-oj3pm5-5u.googlevideo.com/videoplayback?expire=... poToken=88B ustreamerCfg=2288B cpn=... audio=itag=140 video=itag=315(2160p)` + `fetch rn=0 isInit=true body=2612B`)——**协议层 encode/POST 全链跑通**。但 googlevideo 返 **HTTP 403 + 空响应体**(不是 STREAM_PROTECTION_STATUS=3,那是 UMP 流内 InvalidPoToken;这是 HTTP 层直接拒签)。根因候选:**SABR POST 缺会话传输头**。`SabrStreamingAdapter` 是 shaka 请求拦截器,不显式设 UA/Cookie/visitor——那些由 shaka→浏览器 fetch 自动带(FreeTube Electron 走主 session 带 VISITOR_INFO1_LIVE cookie + 浏览器 UA + visitor);我们 OkHttp 裸 POST 只设 `content-type/accept/accept-encoding`,**无 UA/Cookie/X-Goog-Visitor-Id/Origin/Referer** → googlevideo 判非会话请求 → 403。**alpha.18 修**:①`SabrSession` 加 `userAgent/cookieHeader/visitorData` 字段(探针从 `client.userAgent`+`currentSessionCookies()`+`currentVisitorData()` 注入,与 /player 同会话);②`fetch` Request 加 `User-Agent/Cookie/X-Goog-Visitor-Id/Origin/Referer` 头;③403/非 200 时 log **全量响应头**(googlevideo 403 空体时 header 常带 `x-guploader-errorcode`/`x-goog-*` 错误码定位真因),不再只 log 空 body。待真机(alpha.18)验证:403 是否→200(成功拿 UMP 流)或 header 揭示真正因(IP 不符/sig 破坏/缺其它头)。

## 6.9 SABR 实现调研(FreeTubeAndroid 源码 + googlevideo proto + UMP 协议)

## 6.9 SABR 实现调研(FreeTubeAndroid 源码 + googlevideo proto + UMP 协议)

为评估「②实现 SABR」的工程量,系统性 diff FreeTubeAndroid 的 SABR 全链路 + `googlevideo` npm 包(v4.0.4,作者 LuanRT)的 proto/UMP 协议。结论:**SABR 是一个自包含的二进制流协议,FreeTube 用 shaka-player 的「scheme plugin」机制接它;Kotlin/Media3 侧需自建 protobuf 编码 + UMP 流解析 + 自定义 DataSource/MediaSource,工程量大但有界**。

### 6.9.1 FreeTube 决策门(Watch.js L885-915)

```
if (streaming_data.server_abr_streaming_url &&
    player_config.media_common_config.media_ustreamer_request_config)        // ← 父层!不查 video_playback_ustreamer_config
  → SABR: createLocalSabrManifest → manifestMimeType=application/sabr+json
else if (adaptive_formats[0].url || signature_cipher || cipher)             // legacy DASH 直链
  → createLocalDashManifest → application/dash+xml
else → enableLegacyFormat() (progressive 回退)
```

**关键**:gate 2 只查父层 `media_ustreamer_request_config`(camelCase=`mediaUstreamerRequestConfig`),**不查子层** `video_playback_ustreamer_config`。alpha.14 诊断只 dump 了子层报 ABSENT——这正是 alpha.15 补父层诊断的原因。若父层也 ABSENT → FreeTube 自己也不会走 SABR → 说明我们的 /player 响应本就没拿到 SABR 数据(需查为何 YouTube 不下发 ustreamer config,可能缺 client context 字段)。

### 6.9.2 createLocalSabrManifest(Watch.js L1617)——/player 响应 → SABR manifest JSON

`sabrData`(给 scheme plugin用)= `{ url, poToken, ustreamerConfig(子层 bytes), clientInfo }`,其中 `url` = `serverAbrStreamingUrl` + `?alr=yes&cpn=<cpn>`。

manifest JSON(`data:application/sabr+json,<urlencoded>`,给 shaka parser 用):
- `duration` = min(各 adaptive `approx_duration_ms`) / 1000
- `formats[]` = adaptive_formats 映射:`itag`/`lastModified`(last_modified_ms)/`mimeType`/`xtags`/`bitrate`/`initRange`/`indexRange`/`width`/`height`/`frameRate`(fps)/`quality`/`language`/`audioSampleRate`/`audioChannels`/`isDrc`/`isVoiceBoost`/`isOriginal`/`isDubbed`/`isAutoDubbed`/`isDescriptive`/`isSecondary`/`spatialAudio`/`label`(audio_track.display_name)/`colorTransferCharacteristics`/`colorPrimaries`
- `captions[]` / `storyboards[]`

→ **这些字段全在我们 /player raw JSON(camelCase)里已解析**:itag/mimeType/bitrate/initRange/indexRange/width/height/fps/quality(label)/audioSampleRate/audioChannels/approxDurationMs/contentLength/lastModified/xtags/spatialAudioType/colorInfo。**数据层面我们已具备建 SABR manifest 的全部输入**,缺的只是 `videoPlaybackUstreamerConfig`(子层 bytes,塞进 protobuf field 5)与 `cpn`。

### 6.9.3 SabrManifestParser(shaka 插件)——manifest JSON → shaka Manifest

`data:` URI 反序列化 → `variants`(audio × video 全交叉,按 codec 优先级 av01>vp09>vp9>avc1 排序)/`textStreams`(captions)/`imageStreams`(storyboards)。每个 stream 的 `createSegmentIndex`:
1. 构造 `sabr:<audio|video>?formatId=<itag>-<lastModified>-<xtags>[&videoFormatId=...][&drc|&vb][&resolution=N]` URI
2. 发 init 请求(`&init`)拿回 init 段字节,从 `initRange`/`indexRange` 切出 init data + index data
3. 用 `parseMp4SegmentIndex`(sidx box)/`parseWebmSegmentIndex`(Cues)解析 index → 生成 SegmentReference 列表(每个 ref 的 uri = 上面的 sabr url + `&sq=<seq>`)
4. 后续每段 media 请求都走 `sabr:` scheme → SabrSchemePlugin

### 6.9.4 SabrSchemePlugin(shaka `sabr:` networking scheme)——核心协议引擎

每次 segment 请求(init/media)触发:

**请求构造**(`VideoPlaybackAbrRequest` protobuf,见 §6.9.5):
```
POST <sabrUrl>?rn=<requestNumber>
headers: content-type: application/x-protobuf, accept-encoding: identity, accept: application/vnd.yt-ump
body = VideoPlaybackAbrRequest.encode({
  clientAbrState: { bandwidthEstimate, playbackRate, playerTimeMs,
    clientViewportWidth/Height, clientViewportIsFlexible=false,
    stickyResolution, lastManualSelectedResolution, enabledTrackTypesBitfield(audio=1?),
    drcEnabled, enableVoiceBoost, timeSinceLastManualFormatSelectionMs },
  preferredAudioFormatIds: [audioFormatId],
  preferredVideoFormatIds: [videoFormatId],
  preferredSubtitleFormatIds: [],
  selectedFormatIds: isInit ? [] : [audioFormatId, videoFormatId],
  bufferedRanges: [...],
  streamerContext: { poToken, clientInfo, sabrContexts, unsentSabrContexts, playbackCookie? },
  field1000: [],
  videoPlaybackUstreamerConfig: <base64-decoded 子层 bytes>
})
```
FormatId 字符串解析:`"<itag>-<lastModified>-<xtags>"`。

**响应解析**(UMP 流式容器,见 §6.9.6):`UmpReader` 逐 part 处理:
- `STREAM_PROTECTION_STATUS`(status==3 → PO token 无效,CRITICAL)
- `SABR_ERROR`(type+code → 抛错)
- `SABR_REDIRECT`(新 url → 用新 sabrUrl 重试,shouldRetry)
- `MEDIA_HEADER`(匹配 itag/lastModified/xtags + isInitSeg/sequenceNumber,记 `mediaHeaderId`)
- `MEDIA`(`part.data.getUint8(0)==mediaHeaderId` 的段字节 → 收集)
- `MEDIA_END`(该 headerId 段完成 → segmentComplete,abort)
- `NEXT_REQUEST_POLICY`(backoffTimeMs/playbackCookie → 更新 abrRequest,shouldRetry)
- `SABR_CONTEXT_UPDATE`/`SABR_CONTEXT_SENDING_POLICY`(维护 activeSabrContextTypes Set,下次请求回传)
- `RELOAD_PLAYER_RESPONSE`(整视频无法播 → reload)
- `FORMAT_INITIALIZATION_METADATA`(忽略)

收集的 MEDIA chunks → `concatenateChunks` → 段字节,返回给 shaka。重试时把 `streamerContext.sabrContexts/unsentSabrContexts` 填进 abrRequest 重新 encode POST。backoff 用 `setTimeout` 等待(可 abort),累计 backoff ≥3 次或逼近 timeout → 触发 fake reload。

### 6.9.5 protobuf schema(googlevideo/protos,移植 Kotlin 依据)

**`VideoPlaybackAbrRequest`**(`video_playback_abr_request.proto`,proto2):
| field# | name | type |
|---|---|---|
| 1 | client_abr_state | ClientAbrState |
| 2 | selected_format_ids | repeated FormatId |
| 3 | buffered_ranges | repeated BufferedRange |
| 4 | player_time_ms | int64 |
| **5** | **video_playback_ustreamer_config** | **bytes** ← /player 子层 |
| 6 | field6 | UnknownMessage1(format_id/lmt/sequence_number/time_range) |
| 16 | preferred_audio_format_ids | repeated FormatId |
| 17 | preferred_video_format_ids | repeated FormatId |
| 18 | preferred_subtitle_format_ids | repeated FormatId |
| 19 | streamer_context | StreamerContext |
| 21/22/23 | — | — |
| 1000 | field1000 | repeated UnknownMessage3 |

**`FormatId`**(`misc/common.proto`):`itag`(int32)/`last_modified`(uint64)/`xtags`(string)。

**`ClientAbrState`**(`client_abr_state.proto`):关键字段号——13 time_since_last_manual_format_selection_ms(int64)、16 last_manual_selected_resolution(int32)、18 client_viewport_width、19 client_viewport_height、21 sticky_resolution、22 client_viewport_is_flexible(bool)、23 bandwidth_estimate(int64)、28 player_time_ms、35 playback_rate(float)、40 enabled_track_types_bitfield(int32)、46 drc_enabled(bool)、76 enable_voice_boost(bool)。

**`StreamerContext`**(`streamer_context.proto`):`client_info`(ClientInfo)/`po_token`(bytes)/`playback_cookie`(bytes)/`sabr_contexts`(repeated)/`unsent_sabr_contexts`。ClientInfo 含 clientName/clientVersion/clientFormFactor/osName/osVersion/deviceMake/deviceModel/screenInfo 等(对齐我们 InnerTubeClient.buildContext 已有的字段)。

**`MediaHeader`**(`media_header.proto`):1 header_id(uint32)/3 itag/4 lmt(uint64)/5 xtags/8 is_init_seg(bool)/9 sequence_number(int32)/13 format_id(FormatId)/14 content_length/15 time_range。

→ **移植路径**:可用 `wire`(Square)或 `kotlinx-protobuf` 生成,或手写轻量 protobuf 编码(消息数有限,手写可控)。**重点**:field 5 的 `video_playback_ustreamer_config` 是 /player 下发的 opaque bytes,我们原样透传,不需解码——这是 SABR 相对 legacy DASH 的优势:**ustreamerConfig 是服务端签好的会话凭证,客户端不参与签名/n-decrypt**,只要 poToken 有效就能拿流。

### 6.9.6 UMP 二进制流容器(googlevideo/src/core/UmpReader.ts)

每个 UMP part = **[type varint][size varint][payload bytes]**,无 magic/delimiter,纯长度前缀,parts 背靠背串行。

**varint 是 YouTube 自定义格式(非标准 protobuf varint!)**,按首字节高位判总字节数(little-endian):
- `<128`:1 byte,值=byte0
- `<192`:2 byte,值=`(b0&0x3F) + 64*b1`
- `<224`:3 byte,值=`(b0&0x1F) + 32*(b1 + 256*b2)`
- `<240`:4 byte,值=`(b0&0x0F) + 16*(b1 + 256*(b2 + 256*b3))`
- `≥240`:5 byte,b0 纯长度 tag,后 4 byte 按 `getUint32(littleEndian)` 读

`UmpReader.read(handlePart)` 循环:读 type→读 size→`canReadBytes(offset,partSize)`?够则 split 出 payload 调 handlePart、剩余作 tail;不够则返回 partial Part(data=整个剩余 buffer,等调用方 append 更多数据重试)。`CompositeBuffer` = 多 chunk 逻辑拼接(append/getUint8/canReadBytes/split(focus+extractedBuffer/remainingBuffer),跨 chunk 不拷贝)。

→ **Kotlin 移植**:`ByteArrayOutputStream` 或 `Deque<ByteBuffer>` 模拟 CompositeBuffer;varint 按上表解码;part 流循环读 type/size/payload,partial 时攒着等下次 append。UMP part type 用 `UMPPartId` 枚举(0-67,见 `ump_part_id.proto`)。

### 6.9.7 Kotlin/Media3 移植评估

| 模块 | 工作量 | 说明 |
|---|---|---|
| protobuf 编码 VideoPlaybackAbrRequest + 子消息 | 中 | wire/kotlinx-protobuf 或手写;消息约 10 个,字段号已定 |
| UMP varint + UmpReader + CompositeBuffer | 中 | 自定义 varint + chunk 流,~300 行 |
| UMP part 解码(MediaHeader/SabrError/...) | 中 | 同上 protobuf 解码 |
| MP4 sidx / WebM Cues index 解析 | 中-高 | Media3 已有 `Mp4Extractor`/`WebmExtractor` 的 box 解析可借,但 SABR 的 init+index 是从单次 init 响应里切的,要适配 |
| Media3 集成 | 高 | Media3 无 shaka 的 scheme plugin;需自建 `MediaSource`(或 `BundledChunkExtractor`+自定义 `ChunkSource`/`DataSource`),把 sabr: URI 映射到 SABR POST+UMP。最贴近的是实现 `DataSource.Factory` 处理 sabr: scheme 返回段字节,外层用 `MergingMediaSource`(audio+video)+ 自建 segment timeline |
| SABR 状态机(redirect/backoff/nextRequestPolicy/contextUpdate/reload) | 中 | SabrSchemePlugin 的 doRequest 循环移植 |

**总体:中-大工程(估 1500-2500 行 + proto schema),但有界、有上游 JS 逐行对照、数据层已具备**。相对 n-decrypt(plasma WASM 不可解),SABR 是**唯一不需要解 n/sig 的拿流路径**——ustreamerConfig 是服务端签好的,客户端只透传。

### 6.9.8 启动 SABR 移植的前置闸——已确认通过(alpha.15/16)

**前置闸结果(已坐实,非待验)**:
- alpha.15 真机:`mediaUstreamerRequestConfig`(父层,gate 2 判据)= **present**(WEB 3147B / ANDROID 3504B)→ FreeTube gating 通过。
- alpha.16 真机:`videoPlaybackUstreamerConfig` = proto bytes → JSON base64 字符串 = **present**(WEB 3100B / ANDROID 3352B),`.obj()` 报 ABSENT 是类型不符假阴性。

→ SABR 数据三要素全齐(sabrUrl + ustreamerConfig bytes + adaptive 元数据),启动 Kotlin 移植有据。**Phase 1(二进制基础模块)已提交(513d9f3)**:UmpReader/CompositeBuffer/ProtoWire/SabrProto——纯逻辑、proto 字段号全量核对上游(见 §6.7 row 40)。**Phase 2a(协议引擎 + 诊断探针)本提交**:SabrClient + InnerTubeClient.sabrClientInfo() + YoutubePlaybackResolver 探针——仅诊断,验真机协议往返是否被服务端接受,据结果定 Phase 2b(Media3 DashMediaSource + sabr:// DataSource 接线)。

## 7. 关键文件

| 文件 | 作用 |
| --- | --- |
| `core/youtube/YoutubePlaybackResolver.kt` | `parseFormat`/`signatureCipherUrl`/`resolve`/`pickVideo`/`buildInfo` + PO token 注入 |
| `core/youtube/YoutubeSDecryptor.kt` | `s` 签名解密 |
| `core/youtube/YoutubeBotGuard.kt` | PO token 生成(challenge/snapshot/GenerateIT/mint) |
| `core/youtube/YoutubeJsExecutor.kt` | WebView JS 引擎 + `loadBgUtilsBundle` |
| `assets/youtube/bgutils.js` | 打包的 bgutils-js(MIT) |
| `ui/player/PlayerScreen.kt:1390` / `:2038-2104` | 喂流分支 + MPD 构建(改 feed 路径即可复用) |
| `core/player/CodecCapabilityProbe.kt` | 硬件过滤 |

## 8. 参考来源
- FreeTube 上游:`src/renderer/helpers/api/local.js`(InnerTube 封装)与 `formatUtils`(itag/adaptive 选择)
- **FreeTubeAndroid(MarmadileManteater/FreeTubeAndroid,development)**:`android/.../webviews/BotGuardWebView.kt`、`javascript/BotGuardJavascriptInterface.kt`、`src/botGuardScript.js`、`src/renderer/helpers/api/local.js`、`src/renderer/helpers/android/potokens.js`(Android WebView 产 PO token 的唯一参考,§6.8)
- YouTube.js(LuanRT/YouTube.js):`core/Player.ts`、`core/Session.ts`(#buildContext)、`core/Innertube.ts`(getInfo)、`utils/HTTPClient.ts`(#setupCommonHeaders)、`utils/FormatUtils.ts`
- bgutils-js(LuanRT/BgUtils,MIT):`BotGuardClient`/`ChallengeFetcher`/`WebPoMinter`
- FreeTube PR #8137(video ID 绑定 poToken)、#6931(jnn 端点)、#6977(legacy 360p 兜底)
