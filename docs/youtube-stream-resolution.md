# YouTube 视频流解析方案汇总

> 本文档是 BiliMT `core/youtube/` 实现 YouTube 高清(SABR)播放的**top-down 工程参考**,把「解析方案 / 实现细节 / 调试问题 / 样例日志 / 参考资料」汇于一处。按时间线堆叠的逐 alpha 调试记录见 [youtube-hd-playback.md](youtube-hd-playback.md) §6.7(row 1-53);本文是那条链的**结论提炼**。
>
- **状态(2026-08-08)**:SABR 协议层 + Media3 接线 + 多清晰度已跑通(alpha.17-37,跨 21 个 alpha);可出帧出音、可切清晰度,但 **60s 断崖仍未根除**。alpha.30 premature-EOF 三层修(cookie 回传 + 无-media→backoff 重试 + 对方 itag 标满缓冲)+ alpha.31 SABR 上下文握手(sabrContexts 回传)+ alpha.36/37 playerTimeMs 修(startMs 误读致 5s 断崖→回退 cumulative)均**未解 60s**。**alpha.37 真机重新排查(row 61)**证伪旧线索:① contexts=0/0 非病根——`ump_part_id.proto` 注释 `SABR_CONTEXT_UPDATE(57) usually used for ads`,普通播放服务端从不发 57,成功段也是 0/0;② part 47/52/53(PLAYBACK_START_POLICY/REQUEST_IDENTIFIER/REQUEST_CANCELLATION_POLICY)非病根——FreeTube switch 也无此三 case 却能跑 1080p+;③ cookie-clobber(§8 风险)大幅削弱——FreeTube 也是单共享 `sabrStreamState`(L644)共享一个 cookie 能跑。真因未锁,**alpha.38 上纯诊断叠层**(解全 NextRequestPolicy readahead 字段 + 逐流逐段 cookieHash + 发出 sentCookieHash,见 row 62)拿证据再定根修。
- **参考实现**:FreeTube / FreeTubeAndroid(MarmadileManteater,MIT),youtubei.js(LuanRT),googlevideo npm(LuanRT,MIT,proto/UMP 协议源),bgutils-js(LuanRT,MIT)。

---

## 1. 一句话方案

YouTube 已把高清流从「签名直链(legacy DASH)」迁到 **SABR(Server ABR 二进制流协议)**;直链的 `n`-参数解密在 **plasma 播放器**上经典正则方案失效(n/sig 移进 WASM,正则锚点 0 匹配 + 函数名是 IIFE 内局部外部 eval 取不到)。**解法(双路径)**:① **alpha.32 URL 类方式**(主路径,对齐 zemer-cipher):`new g.<nClass>(url).get('n')` 注入到 base.js IIFE 闭包内,`.get('n')` 内部触发 transform(即便在 WASM 也照跑),nClass 从 player hash 查 config(alpha.33 打包 224 条);② **WebView harvest**(兜底路径,alpha.20-25 跑通):n-decrypt 失败时加载 watch 页让浏览器引擎替我们做 n-transform + 采集它发出的 SABR POST body(含 poToken/ustreamerConfig/cpn 会话三要素),用自构 `VideoPlaybackAbrRequest` protobuf body 驱动 SABR,经自定义 `sabr://` DataSource 喂给 Media3 progressive 播放。主路径成功则跳过 30s harvest,失败自动回退(零回退风险)。

---

## 2. 总体架构:videoId → 画面

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. 取流数据(/player InnerTube)                                              │
│    WebView 原生网络栈(Chromium)POST /youtubei/v1/player                        │
│    → streamingData.adaptiveFormats(纯元数据:itag/initRange/indexRange/...)   │
│    → streamingData.serverAbrStreamingUrl(SABR 基址)                          │
│    → playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig               │
│        .videoPlaybackUstreamerConfig(会话凭证 bytes,base64)                  │
│    (PO token 由 BotGuard 铸取,WebView 同源 youtube.com 破门控)              │
├─────────────────────────────────────────────────────────────────────────────┤
│ 2. n-decrypt + 会话采集(WebView harvest)                                    │
│    隐藏 WebView 加载 https://www.youtube.com/watch?v=<id>                    │
│    onPageStarted 注入 fetch hook(Request.clone.arrayBuffer 读 body 不消耗)   │
│    → 播放器(plasma base.js)跑 WASM 做 n-transform → 发 SABR POST            │
│    → hook 截获 {url(已 transform 的 n), body(poToken/ustreamerCfg/itags/cpn)}│
├─────────────────────────────────────────────────────────────────────────────┤
│ 3. 建会话 + 自构 body 驱动                                                     │
│    decodeVideoPlaybackAbrRequest(body) → poToken/ustreamerConfig/formatIds    │
│    SabrSession(sabrUrl+alr+cpn, poToken, ustreamerCfg, clientInfo, formats)   │
│    SabrClient.fetch(session, init/seg) 自构 VideoPlaybackAbrRequest → POST   │
│    → UMP 流响应 → MEDIA_HEADER/MEDIA/MEDIA_END → 段字节                       │
├─────────────────────────────────────────────────────────────────────────────┤
│ 4. Media3 接线(progressive)                                                 │
│    resolver 返 sabr://youtube/<sid>?stream=video&itag=N + stream=audio 两条   │
│    track(segmentBase=null → progressive 分支)                                │
│    MergingMediaSource(ProgressiveMediaSource×2) 复用                          │
│    SabrAwareDataSourceFactory:sabr://→SabrStreamingDataSource,else→OkHttp    │
│    SabrStreamingDataSource.open→fetch init;read→逐段 fetch 拼接成 fMP4       │
└─────────────────────────────────────────────────────────────────────────────┘
```

**为什么走 SABR 而非 legacy DASH 直链**:`/player` 响应里 40 条 adaptive **全是纯元数据**(initRange/indexRange/contentLength 齐全但无 `url`/`signatureCipher`),这正是 SABR 模式形态——YouTube 期望客户端用 `serverAbrStreamingUrl` + 元数据构建 manifest,adaptiveFormats 本就不该有 url。死磕 legacy 直链的「url 空」是方向错了(alpha.14 真机坐实)。

**为什么 alpha.32 起 Kotlin 侧能解 n(URL 类方式)**:plasma 播放器把 n/sig 解码移进 **WASM**(`WebAssembly`×6、`signature`/`encrypt` 跑在 WASM 实例 `c.DY` 上),经典「正则找名 + WebView eval 直接调用」方案**两重失效**:①正则锚点 `.get("n"))&&(b=Name(c))` 全表 0 匹配;②即便命中,函数名是 base.js IIFE 内局部,外部 `evaluateJavascript` 取不到。**但**对齐 zemer-cipher 改实例化 YouTube 内部 URL 类 `new g.<nClass>(url).get('n')`——`.get('n')` 内部触发 transform(即便在 WASM 也照跑,这是 YouTube 播放器自己取 n 的原生路径);把 `window.__nTransformFunc=function(n){...}` 注入到 `})(_yt_player);` 之前(IIFE 闭包内捕获 `g` 局部)即可从外部调用。nClass 从 player hash 查 config(alpha.33 打包 zemer 224 条)。**alpha.32 真机**:URL 类方案 fallback 链正确(零回退),当前 player 854a788e 已在 alpha.33 config 覆盖(tL),待真机验成功路径。**WebView harvest**(alpha.20-25 跑通)保留作自动兜底:n-decrypt 失败 → `nTransformed=false` → 走 harvest 老路(最坏只是没提速)。

---

## 3. 两个阻断点与突破路径

### 3.1 阻断点 A:PO token(BotGuard minter)
高清流要 `serviceIntegrityDimensions.poToken`。铸取链:`/att/get`(WEB 专属 challenge 通道)→ interpreter(`new Function(interpreterJavascript)()` 定义 `window[globalName]`)→ `BotGuardClient.create` → `snapshot({webPoSignalOutput})` → `GenerateIT`(`response[0]` 是 integrityToken)→ `WebPoMinter.create().mintAsWebsafeString(videoId)`(视频绑定)。

**门控**:`webPoSignalOutput[0]`(minter)被 BotGuard VM 的 anti-bot 检测门控。**突破**:WebView 宿主页 origin 设成 `youtube.com`(`loadDataWithBaseURL("https://www.youtube.com/", …)`)→ 真浏览器页面环境 → minter 产生(`webPoSignalOutput={"length":1,"isFunc":"function"}`,alpha.20)。`file://` origin 判「非真页面」不产 minter。

**token 有效性**:token 必须**与 /player 同 context(含同一 visitorData)绑定**才有效。关键修法链:共享单一 `InnerTubeClient` 实例(alpha.26 修跨实例 visitorData 不一致)+ `ensureRealSessionData` Mutex 双检锁(只 fetch 一次)+ token 放请求**顶层** `serviceIntegrityDimensions`(非 context 内,alpha.22 修)+ /player 走 **WebView 原生网络栈**(alpha.29 修,OkHttp Java TLS 指纹被判非真浏览器)。

### 3.2 阻断点 B:n-decrypt(plasma WASM)
见上 §2。**双突破路径**:

**路径 ① URL 类方式(alpha.32,主路径)**:对齐 zemer-cipher,实例化 YouTube 内部 URL 类 `new g.<nClass>(url).get('n')`,`.get('n')` 内部触发 transform(即便在 WASM 也照跑),IIFE 注入 `window.__nTransformFunc` 捕获 `g` 局部,外部 evaluateJavascript 调用。nClass 从 player hash 查 config(alpha.33 打包 zemer 224 条 player_configs.json)。成功 → `nTransformed=true` 走 resolver 直接分支(用 /player 数据 + Flow A poToken + 自生成 cpn 建会话),**30s harvest 整个跳过**。失败 → 自动回退路径 ②(零回退风险)。待真机验成功路径。

**路径 ② WebView harvest(alpha.20-26,跨 7 个 alpha,兜底路径)**:
1. alpha.20-22:采集器分叉 + detached WebView `measure+layout` 给真实尺寸(0 尺寸播放器拒 init)+ 桌面 UA + watch 页回退(embed Error 153 不可解)。
2. alpha.23:**watch 页捕到 SABR POST status=200 + transformed-n**,证浏览器 WASM n-transform 被服务端接受(403 阻塞打破)。
3. alpha.24:body 采集修复(`input.Clone().arrayBuffer()` 克隆读 Request body 不消耗原请求)→ 4632B body。
4. alpha.25:**保浏览器 cpn**(只剥 rn 重置 0)→ 完整 UMP 媒体响应(`STREAM_PROTECTION_STATUS status=1` poToken 有效 + MEDIA itag 251/396 init+seg1)。
5. alpha.26:**自构 body 驱动**(解码 harvested body 取会话参数 → 建 SabrSession 用浏览器 cpn → SabrClient 自构 VideoPlaybackAbrRequest)→ init 237B + seg1 660025B 全拿到。**服务端只严查 poToken/ustreamerConfig/formatIds/cpn 会话绑定,不严查 clientInfo/clientAbrState。**

---

## 4. SABR 协议方案

### 4.1 数据三要素(全从 /player 响应取,alpha.15/16 真机坐实 present)
| 要素 | /player 路径(camelCase) | 用途 |
|---|---|---|
| `serverAbrStreamingUrl` | `streamingData.serverAbrStreamingUrl` | SABR POST 基址(附 `?alr=yes&cpn=<cpn>&rn=<rn>`) |
| `videoPlaybackUstreamerConfig` | `playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig.videoPlaybackUstreamerConfig` | **会话凭证 bytes**(base64,服务端签好,客户端 opaque 透传填 field5,**不参与签名/n-decrypt**——SABR 相对 legacy DASH 的核心优势) |
| adaptive 元数据 | `streamingData.adaptiveFormats[]`(itag/lastModified/xtags/height/initRange/indexRange) | FormatId(itag/lastModified/xtags),填 preferred/selected formatIds |

**决策门**(FreeTube Watch.js L885):`serverAbrStreamingUrl && mediaUstreamerRequestConfig`(父层,不查子层 videoPlaybackUstreamerConfig)→ SABR;`adaptive[0].url || signatureCipher || cipher` → legacy DASH;else → progressive 兜底。

⚠️ **字段名陷阱**:FreeTube 代码读 snake_case 是 youtubei.js 库端转换后的形态,raw /player JSON 是 **camelCase**。alpha.13 用 snake_case 查 `server_abr_streaming_url` 报 ABSENT 是**假阴性**,差点关闭整个 SABR 方向(alpha.14 修正)。

### 4.2 poToken 是会话级,不绑 itag(alpha.29 调研结论)
核对 FreeTube `Watch.js createLocalSabrManifest`:`this.sabrData = { url, poToken, ustreamerConfig }` 单一 poToken 给整个 SABR 会话,`formats` 是全部 itag → **清晰度 = 请求体 `preferredVideoFormatIds` 填哪个 itag**,换 itag 无需重 harvest。这推翻了早期「poToken 可能 itag-bound」假设,是 §6 多清晰度的前提。

### 4.3 请求/响应协议(对齐 googlevideo npm)
**请求** `VideoPlaybackAbrRequest`(POST,`content-type:application/x-protobuf`, `accept:application/vnd.yt-ump`, `accept-encoding:identity`):
```
field1  clientAbrState     { playerTimeMs(field28), bandwidthEstimate(23), playbackRate(35),
                             enabledTrackTypesBitfield(40, AUDIO=1/VIDEO=2),
                             stickyResolution(21), lastManualSelectedResolution(16), ... }
field2  selectedFormatIds  [] (init) | [audioFmt, videoFmt] (seg)
field3  bufferedRanges     [BufferedRange] (own 格式已缓冲段 + 对方格式「满缓冲」信号) ← alpha.30 已补
field4  playerTimeMs        = 当前请求段的呈现起始时间
field5  ustreamerConfig     (opaque bytes 透传)
field16 preferredAudioFormatIds  [audioFmt]
field17 preferredVideoFormatIds  [videoFmt]
field19 streamerContext    { clientInfo(1), poToken(2), playbackCookie(3) ← alpha.30 已补,
                             sabrContexts(5), unsentSabrContexts(6) }
field1000 []
```

**响应** = UMP 二进制流容器(非标准 protobuf varint!),每个 part = `[type varint][size varint][payload bytes]`,背靠背串行。varint 按首字节高位判字节数(`<128` 1B / `<192` 2B / `<224` 3B / `<240` 4B / `≥240` 5B)。part type(UMPPartId):

| type | 名 | 处理 |
|---|---|---|
| 20 | MEDIA_HEADER | 解 `itag/lmt/xtags/isInitSeg/seq/contentLength/durationMs`,匹配 formatId 取 `headerId` |
| 21 | MEDIA | `payload[0]==headerId` 的段字节(去掉首字节 headerId)收集 |
| 22 | MEDIA_END | 该 headerId 段完成 |
| 35 | NEXT_REQUEST_POLICY | `backoffTimeMs`(4) + `playbackCookie`(7) + `videoId`(8) → **回传 cookie + backoff 重试**(alpha.30 已补) |
| 43 | SABR_REDIRECT | 新 sabrUrl → `applyRedirect` 重试同 seq |
| 44 | SABR_ERROR | type+code → 抛错 |
| 57 | SABR_CONTEXT_UPDATE | 维护 `sabrContexts` Map(下次请求回传) ← alpha.30 待补(本次未动,先验 cookie 主因) |
| 58 | STREAM_PROTECTION_STATUS | status=1(poToken 有效)/ 2(非致命)/ 3(InvalidPoToken 硬失败) |
| 59 | SABR_CONTEXT_SENDING_POLICY | 切换 active context types |
| 62 | END_OF_TRACK | 真 EOS |

---

## 5. 实现细节(文件级)

### 5.1 文件清单
| 文件 | 作用 |
|---|---|
| [SabrProto.kt](app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrProto.kt) | protobuf 编解码(VideoPlaybackAbrRequest/MediaHeader/...)+ UMP part 解码;含 `ProtoWriter`/`ProtoReader` 轻量手写 proto + `decodeVideoPlaybackAbrRequest`(从 harvested body 解会话参数) |
| [SabrClient.kt](app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrClient.kt) | 协议引擎:`fetch(session, req)` 构造请求 POST → `processUmpStream` 逐 part 处理 → 返回 `SabrFetchResult`(Success/Redirect/Backoff/InvalidPoToken/Error);`requestNumber` AtomicInteger(双 loader 并发安全) |
| [SabrStreamingDataSource.kt](app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrStreamingDataSource.kt) | media3 `DataSource`:open→fetch init;read→逐段 fetch 拼接;Redirect/Backoff 重试;Error/InvalidPoToken→EOF;`runBlocking` 桥接 suspend fetch |
| [SabrAwareDataSource.kt](app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrAwareDataSource.kt) | scheme 路由:`sabr://`→SabrStreamingDataSource,http/https→OkHttp delegate;`parseSabrUri` 解析 `&itag=` |
| [SabrStreamRegistry.kt](app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrStreamRegistry.kt) | 进程级 `object`:sessionId→Entry(session,client) + videoId→sessionId 反查表(切清晰度复用会话) |
| [YoutubeSabrHarvester](app/src/main/java/com/kirin/mt/core/youtube/YoutubeSabrHarvester.kt) | 隐藏 WebView 加载 watch 页,fetch hook 截获 SABR POST(url+body+status) |
| [YoutubePlaybackResolver.kt](app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt) | SABR 数据齐全分支:harvest→`buildSabrSessionFromCapture`→register→`buildSabrPlaybackInfo` 返 sabr:// track |
| [PlayerScreen.kt](app/src/main/java/com/kirin/mt/ui/player/PlayerScreen.kt) / [MobilePlayerScreen.kt](app/src/main/java/com/kirin/mt/ui/mobile/player/MobilePlayerScreen.kt) | `SabrAwareDataSourceFactory` 一行包装 base factory |

### 5.2 关键代码契约(media3 1.10)
- `DataSource.addTransferListener` 是 **abstract**,必须 `override`(非可选)。
- `DataSource.getUri()` 是 Java getter → Kotlin 用 `override fun getUri(): Uri?`(非 `override val uri`,避 function/property 歧义)。
- `open()` 返回 `C.LENGTH_UNSET.toLong()`(未知总长 → 不可 seek)。
- `@UnstableApi` 是 lint 注解非编译期,CI 跑 `assembleDebug`/`assembleRelease` 无 lint task 不阻。

### 5.3 SABR 段序列与 progressive 接线
SABR = fragmented MP4:`seq=0` init(ftyp+moov) → `seq=1,2,…` 每 ~6s(moof+mdat fragment)。`SabrStreamingDataSource` 在 `open` 拉 init、`read` 逐段拉取拼接成连续字节流,ProgressiveMediaSource 的 MP4 extractor 当 fragmented MP4 解析。音频(itag 251 opus)+ 视频(itag 399 av01 / 248 vp9)分离两流 → 播放器既有 `MergingMediaSource(ProgressiveMediaSource×2)`(`segmentBase==null` 触发)直接复用,无需新 MediaSource 类型/本地 HTTP proxy/DASH MPD 合成。

### 5.4 多清晰度(alpha.29)
- `SabrSession.videoFormats: List<FormatId>`(从 /player adaptiveFormats 全收 `height>0` 视频 itag)+ `videoFormat(itag)` 查表。
- `SabrFetchRequest.videoItag`(URL `&itag=` 解析)→ `fetch` 用 `videoFmt = req.videoItag?.let{session.videoFormat(it)} ?: videoFormatId`。
- resolver `buildSabrPlaybackInfo`:`qualities`=videoFormats 按 height 降序(带 codec 标签 AV1/VP9/H264/HEVC),`videoTracks`=[仅选中 itag 一条 `sabr://youtube/<sid>?stream=video&itag=N`]。
- `SabrStreamRegistry.registerByVideoId`/`getByVideoId`:切清晰度重跑 resolve 时命中缓存→跳过 30s harvest 秒切(poToken/ustreamerConfig/cpn 会话级可复用 ~6h)。
- 播放器切档机制:`preferredQualityId` → 重跑 `getPlaybackInfo` → 重建整个 MediaSource(progressive 分支只取 `videoTracks.first()`,多 progressive track 静默丢弃,故只返选中 itag 一条)。

---

## 6. 调试遇到的问题(按主题,非时间线)

### 6.1 BotGuard minter 不产(alpha.1-19,跨 19 个 alpha)
**症状**:`webPoSignalOutput[0]` 始终空 → `WebPoMinter.create` 抛 `BgError: PMD:Undefined`。试过且**都无效**:换 challenge 源(`/att/get` vs `/jnn/Create`)、去 contentBinding、桌面 UA、`skipPrivacyBuffer`、补全 context 指纹字段、GenerateIT 加 cookie、visitorData 配对、VM 桌面指纹 polyfill。

**根因(alpha.20 突破)**:不是「深度指纹不可绕」,是**宿主页 document 上下文**——`file://` origin 判「非真页面」不产 minter。`loadDataWithBaseURL("https://www.youtube.com/", …)` 把 origin 设成 youtube.com 即破。FreeTubeAndroid `BotGuardWebView.kt` 同款做法。

### 6.2 token 密码学无效(alpha.21-13)
**症状**:token minted(128 chars)且注入 body,但 adaptive 全剥空(`firstUrl=EMPTY`),带/不带 token 响应逐字节同构 → token 被 YouTube 完全忽略。试过且无效:补 context 指纹、signatureTimestamp、Sec-Fetch 头、GenerateIT 端点/cookie、VM 桌面 polyfill。

**根因链**:
1. 跨实例 visitorData 不一致(AppContainer 建了 3 个 InnerTubeClient 实例,铸 token 用 A 的 visitorData、/player 用 C 的)→ 共享单一实例(alpha.26)。
2. token 放 context 内而非请求顶层 → 移到顶层 `serviceIntegrityDimensions`(alpha.22)。
3. /player 走 OkHttp(Java TLS 指纹)被判非真浏览器 → 改 `fetchViaWebView` 走隐藏 WebView 原生 Chromium 网络栈(alpha.29)。

### 6.3 n-decrypt 经典正则方案失效(plasma WASM)— alpha.32 URL 类方式已修
**症状**:`YoutubeNDecryptor: could not locate transform name`,SABR URL 带 `n` 原样未解 → googlevideo 403。

**根因(经典正则方案两重失效)**:YouTube 上线 plasma 播放器变体,把 n/sig 解码移进 WASM。证据:`get("n")` 全表只 1 处(在 `yoh` 做 URL 路径规范化非签名);经典调用点 `.get("n"))&&(b=Name(c))` 全 0 匹配;`WebAssembly`×6、`signature`/`encrypt` 跑在 WASM 实例 `c.DY`。①正则锚点 0 匹配;②即便命中,函数名是 base.js IIFE 内局部,外部 `evaluateJavascript` 取不到。**「不可在 Kotlin 侧修」的旧结论已被 alpha.32 推翻**(社区 zemer-cipher 已用 URL 类方式解)。

**突破 ① URL 类方式(alpha.32,主路径,对齐 zemer-cipher)**:实例化 YouTube 内部 URL 类 `new g.<nClass>(url).get('n')`,`.get('n')` 内部触发 transform(即便在 WASM 也照跑——YouTube 播放器自己取 n 的原生路径);`window.__nTransformFunc` 注入到 `})(_yt_player);` 之前(IIFE 闭包内捕获 `g` 局部),外部 evaluateJavascript 调用。nClass 从 player hash 查 config(alpha.33 打包 zemer 224 条)。**alpha.32 真机**:fallback 链正确(零回退),当前 player 854a788e 已在 alpha.33 config 覆盖(tL),待真机验成功路径。

**突破 ② WebView harvest(alpha.20-26,兜底)**:n-decrypt 失败 → 浏览器引擎原生跑 WASM,加载 watch 页让播放器替我们做 n-transform,hook 截获它发的 SABR POST。零回退风险:主路径失败自动回退此路径(最坏只是没提速)。

### 6.4 WebView harvest 采集器逐层修(alpha.20-25)
- **embed Error 153 不可解** → 回退 watch 页(无 embed 权限闸)。
- **detached WebView 0 尺寸** → 播放器 JS 检测 viewport=0 拒 init → `measure(1080×1920)+layout` 给真实尺寸。
- **bodyB64=0B** → 播放器用 `fetch(new Request(url,{body}))`,init.body 空、body 在 Request 对象 ReadableStream 不可同步读 → `input.clone().arrayBuffer()` 克隆读不消耗原请求。
- **重放只回 105B context 无媒体** → 剥了 cpn 失会话绑定 → 保浏览器 cpn(只剥 rn 重置 0)→ 完整媒体响应。

### 6.5 premature-EOF 黑屏(alpha.27-28,问题;alpha.30 已实现修复,待真机验证)
**症状**:可播放但约 60s 后黑屏只有声音。alpha.27:audio EOF at seq=3、video EOF at seq=5。alpha.28 修 playerTimeMs/bufferedRange 推进后:audio EOF **延后到 seq=7**(playerTimeMs=60001),video 在 seq=5 被 `InterruptedIOException` 打断(audio EOF 后 MergingMediaSource 拆流连带杀在途 video fetch)。56 分钟视频才放 60s,**确认仍 premature**。

**根因(alpha.30 锁定,FreeTube 源码确认)**:三个叠加问题(详见 §8):
1. **playbackCookie 没回传**(主因):服务端 NEXT_REQUEST_POLICY 每次带 `cookie=true`,要求客户端把 `playbackCookie` 塞进下个请求 `StreamerContext.field3`。我们只 log 不回传。前 ~6 段服务端宽容,seq7 切严格校验 → 只回 policy 不回 media → 「no matching MEDIA_HEADER」→ EOF。FreeTube 源码原话:「不回传 cookie,服务端丢失会话连续性,你会得到正是『几段后停止』症状」。
2. **无-media 响应当永久 EOF**:200 + NEXT_REQUEST_POLICY 无 MEDIA_HEADER,FreeTube 当 `shouldRetry`(backoff+重传 cookie+重发同 seq),我们当 Error→-1→黑屏。
3. **对方 itag 没标「满缓冲」**:FreeTube 两并发流各带 `selectedFormatIds=[audio,video]` 但用 `createFullBufferRange`(durationMs=Int.MAX, seg=MAX)标对方格式「别发」;我们没标 → 服务端每流试图发双格式 → 会话状态混乱。

### 6.6 字段名 camelCase 假阴性(alpha.13-14)
用 snake_case 查 `server_abr_streaming_url` 报 ABSENT,差点关闭 SABR 方向。raw /player JSON 是 camelCase(`serverAbrStreamingUrl`),snake_case 是 youtubei.js 库端转换形态。**教训**:核对参考实现时区分库端转换 vs raw JSON。

### 6.7 UMP varint 非标准 protobuf
UMP 的 varint 是 YouTube 自定义(非标准 protobuf varint),按首字节高位判字节数。手写 `UmpReader` + `CompositeBuffer` 模拟跨 chunk 逻辑拼接(append/getUint8/canReadBytes/split,不拷贝)。

---

## 7. 样例日志(真机 alpha.28,videoId Hmwo-47lSw8,56 分钟,itag 399 av01 1080p + 251 opus)

### 7.1 正常段推进(playerTimeMs 前移,证 alpha.28 协议层修生效)
```
fetch rn=9  isInit=false stream=AUDIO seq=6 body=1692B
MEDIA_HEADER headerId=0 itag=251 lmt=1785539448286167 xtags=CgcKAnZiEgEx isInit=false seq=6 contentLen=152169 dur=10000ms | matched=true
MEDIA_END headerId=0 chunks=5 bytes=152169
SabrStream stream=AUDIO seq=6 isInit=false bytes=152169B dur=10000ms playerTimeMs=50001   ← 推进到 50s
fetch rn=11 isInit=false stream=AUDIO seq=7 body=1692B
```
> `playerTimeMs` 从 40001→50001→60001 随段推进(alpha.27 是死 0)。

### 7.2 premature-EOF 触发点(audio seq7 无 media)
```
fetch rn=11 isInit=false stream=AUDIO seq=7 body=1692B
part type=47(?) payloadLen=12 (unhandled)              ← 47/52/53 是未处理的 context 类 part
STREAM_PROTECTION_STATUS status=2                       ← 非 3,poToken 仍有效(非致命)
part type=52(?) payloadLen=8 (unhandled)
part type=53(?) payloadLen=11 (unhandled)
NEXT_REQUEST_POLICY backoff=0ms cookie=true            ← 服务端给了 cookie 要求回传,但我们不回传
no MEDIA_HEADER matched; got 0 chunks but no header     ← 无 media 段
SabrStream stream=AUDIO Error at seq=7: no matching MEDIA_HEADER → EOF   ← 我们当永久 EOF
SabrStream read EOF stream=AUDIO at seq=7 playerTimeMs=60001 (no more segments)
SabrStream close stream=AUDIO (nextSeq=7 done=true)
```
> 关键:`cookie=true` 但无 MEDIA_HEADER,服务端在「软拒绝」(等 cookie 回传),我们却 EOF。

### 7.3 video 被 audio EOF 连锁打断
```
fetch rn=13 isInit=false stream=VIDEO seq=5 body=1680B
MEDIA_HEADER headerId=0 itag=251 ... seq=3 ... | matched=false (wanted itag=399)   ← audio 流的 itag 251 header 混进 video 响应(对方 itag 未标满缓冲的后果)
fetch rn=13 exception
java.io.InterruptedIOException: interrupted              ← audio EOF 后 MergingMediaSource 拆流,在途 video fetch 被中断
SabrStream stream=VIDEO Error at seq=5: SABR fetch exception: interrupted → EOF
SabrStream read EOF stream=VIDEO at seq=5 playerTimeMs=21835
```
> video 本可继续(audio EOF 才 seq7,video 才 seq5),但 merge 拆流连锁杀掉。

### 7.4 SABR 驱动成功(alpha.26,自构 body)
```
SABR drive: decoded poToken=10B ustreamerCfg=1284B audio=itag=251 video=itag=248 cpn=l9ZHsAYgJSQkIOv6
SABR drive init(video, constructed body): Success bytes=237B headerId=1 itag=248 isInit=true contentLen=237
SABR drive seg1(video, constructed body): Success bytes=660025B headerId=1 itag=248 isInit=false contentLen=660025 dur=6006ms
```

### 7.5 完整 SABR 媒体响应(alpha.25,保 cpn)
```
type=57(SABR_CONTEXT_UPDATE) payloadLen=90
type=58(STREAM_PROTECTION_STATUS) status=1              ← poToken 有效
type=42(FORMAT_INIT_METADATA)×2
type=35(NEXT_REQUEST_POLICY) backoff=0ms cookie=true
type=20(MEDIA_HEADER) itag=251 isInit=true seq=0 contentLen=284   ← audio init
type=21(MEDIA) 285B → type=22(MEDIA_END)
type=20(MEDIA_HEADER) itag=396 isInit=true seq=0 contentLen=744  ← video init
type=21(MEDIA) 745B → MEDIA_END
type=20(MEDIA_HEADER) itag=251 isInit=false seq=1 contentLen=101540 dur=6041ms  ← audio seg1
... type=20(MEDIA_HEADER) itag=396 isInit=false seq=1 dur=6006ms  ← video seg1
```

---

## 8. alpha.30 premature-EOF 修复方案(已实现,但 alpha.37 真机证 60s 断崖仍在——见 row 61)

基于 FreeTube `SabrSchemePlugin.js` 源码核对确认的三层修(详见 §6.5)。**代码已落地**(`SabrClient.kt` + `SabrProto.kt`,alpha.30),改动集中在协议层不动播放器接线,与 alpha.29 多清晰度正交。

| 修 | 改动(已实现) | FreeTube 依据 |
|---|---|---|
| ① 回传 playbackCookie(主因) | `SabrSession` 加 `@Volatile var playbackCookie: ByteArray?`(会话级——PlaybackCookie 含双格式 resolution,服务端对同一会话发同一值,两 loader 共享安全);`decodeNextRequestPolicy` 加 `playbackCookieBytes: ByteArray?`(field7 原始 bytes,opaque 透传免解码/再编码);`processUmpStream` NEXT_REQUEST_POLICY handler 捕获写回 `session.playbackCookie`;`fetch` 构造 `StreamerContextInput(playbackCookie=session.playbackCookie)`(field3,`encodeStreamerContext` L83 已支持);`SabrFetchResult.Success` 加 `playbackCookie: ByteArray?` 透传诊断 | NEXT_REQUEST_POLICY case:`streamerContext.playbackCookie = PlaybackCookie.encode(nextRequestPolicy.playbackCookie).finish()` |
| ② 无-media→backoff 重试 | `processUmpStream` 尾部重写:有 NEXT_REQUEST_POLICY(`sawPolicy=true`)无 MEDIA_HEADER 无 Error/EndOfTrack → 返回 `Backoff(backoffMs ?: 0)`(非 Error→EOF);`fetchUntilReady` 已有 backoff 重试 sleep+continue 复用——重发同 seq 自动读 session 已更新的 cookie;**真硬失败仅 STREAM_PROTECTION_STATUS status=3(InvalidPoToken)** | `shouldRetry=true` → sleep backoff → 回传 cookie+contexts → 重发同请求(不递增 seq);累计 backoff≥3 或重试≥100 → reload(非 EOS);仅 status=3 硬失败 |
| ③ 对方 itag 标「满缓冲」 | 新增 `createFullBufferRange(fmt)` 辅助(`durationMs/seg=Int.MAX, startTimeMs=0, timeRange{durationTicks=Int.MAX,startTicks=0,timescale=1000}`);`fetch` 构造 bufferedRanges:own 格式按已缓冲段标(现有 `[0..cumulative]`)+ **对方格式加一条 `createFullBufferRange`**;init 请求不发 bufferedRanges(对齐 FreeTube isInit 不 fillBufferedRanges) | `fillBufferedRanges`:`createFullBufferRange(otherFormatId)` 标对方格式「别发」 |

**playerTimeMs/bufferedRange 保留 alpha.28 行为**(seq1-6 正常返回 media 证明数值可用——`playerTimeMs==bufferedEnd` 致停发假设被证伪;cookie 是 seq7 触发点,非 playerTimeMs)。

**真机验证预期(alpha.30)**:① `fetch ... cookie=true`(cookie 回传生效);② seq7/8/9… 持续 `SabrStream ... bytes=NB`(非 EOF);③ audio playerTimeMs 过 60001、video 不被 InterruptedIOException 打断;④ 黑屏消失持续播放。**风险**:若 cookie 会话级假设错(stream-specific),两 loader cookie 互相覆盖致混乱 → 需改 per-stream cookie(较大重构)。

**⚠️ alpha.30-37 真机证伪(见 [youtube-hd-playback.md](youtube-hd-playback.md) row 61)**:上述预期①②③④**全未兑现**——cookie 回传后 60s 断崖仍在(audio seq7 playerTimeMs=60001 / video seq14 62170 均 6 backoff→EOF)。row 61 重新排查证伪 contexts(57=ads-only 服务端从不发)、part 47/52/53(FreeTube 也忽略)、cookie-clobber大幅削弱(FreeTube 单共享 state 也能跑)。**§8 风险项(per-stream cookie)未彻底排除**——需 alpha.38 诊断叠层打 cookieHash 比对 audio/video 收发才能定。真因未锁,alpha.38 起转纯诊断拿证据再改。

**已知限制(MVP 接受,后续)**:① 不可 seek(C.LENGTH_UNSET;后续 DASH MPD + /player lengthSeconds);② n-param/URL ~6h 过期,长暂停可能 mid-stream 403(后续 re-harvest on 403);③ harvest ~30s 加载慢(后续 video-detail 页后台预 harvest 缓存);④ alpha.29 多清晰度未真机验(切档后新 itag 请求成功否)。

---

## 9. 关键参考资料

### 9.1 参考实现仓库
- **FreeTubeAndroid**(MarmadileManteater/FreeTubeAndroid,development 分支,Cordova 包装 FreeTube web 版):唯一能在 Android WebView 稳定产 PO token + 切 1080P+ 的参考。关键文件:`android/.../webviews/BotGuardWebView.kt`(youtube.com 同源基址破 minter 门控)、`src/botGuardScript.js`(mint 流程)、`src/renderer/helpers/api/local.js`(`getLocalVideoInfo` /player 走主 WebView)、`src/renderer/views/Watch/Watch.js`(`createLocalSabrManifest` L1617 + 决策门 L885)、`src/renderer/helpers/player/SabrSchemePlugin.js`(SABR 协议引擎,§8 三层修的依据)。
- **googlevideo npm**(LuanRT/googlevideo,v4.0.4):SABR proto/UMP 协议源。`protos/video_streaming.*` + `misc/common.proto`(FormatId:itag/last_modified/xtags)+ `src/core/UmpReader.ts`(UMP varint + CompositeBuffer)。
- **youtubei.js**(LuanRT/YouTube.js):`core/Session.ts #buildContext`(WEB context 反爬字段)+ `utils/HTTPClient.ts #setupCommonHeaders`(请求头)+ `core/Innertube.ts getInfo`(/player body)。
- **bgutils-js**(LuanRT/BgUtils,MIT):`BotGuardClient`/`ChallengeFetcher`/`WebPoMinter`。
- **zemer-cipher**(ZemerTeam/zemer-cipher,alpha.32 URL 类 n-decrypt 方案源):`library/src/main/assets/player_configs.json`(224 条 player hash→nClass,含 plasma 95daa498=Xz、当前 854a788e=tL)+ `buildNJsExpression`/`buildModifiedPlayerJsImpl`(IIFE 注入 `window._nTransformFunc`)+ `FunctionNameExtractor`(PLAYER_HASH_PATTERNS + md5 alias fallback)。本仓库 `assets/youtube/player_configs.json` 即此 config 镜像。

### 9.2 关键源码 URL(raw.githubusercontent.com)
- SabrSchemePlugin.js:`MarmadileManteater/FreeTubeAndroid/development/src/renderer/helpers/player/SabrSchemePlugin.js`
  - `createFullBufferRange`(L85-97):满缓冲信号(`durationMs=MAX_INT32, startSeg=endSeg=MAX_INT32`)
  - `fillBufferedRanges`(L105-150):own 格式真实 buffered range + 对方格式 full range
  - NEXT_REQUEST_POLICY case(L415-424):`streamerContext.playbackCookie = PlaybackCookie.encode(...).finish()` + `backoffTimeMs`
  - retry/shouldRetry(L534-567 + L308-336):backoff→重传 cookie+contexts→重发;累计≥3/≥100 → reload 非 EOS
  - `playerTimeMs`(L712-716):从 URL `startTimeMs` 取 = 请求段呈现起始时间
- segment index parser:`Mp4SegmentIndexParser.js`(L156)/`WebmSegmentIndexParser.js`(L246):写 `startTimeMs` URL 参数

### 9.3 FreeTube PR
- #8137(video ID 绑定 poToken)、#6931(jnn 端点 youtube.com/api/jnn 是 jnn-pa 代理)、#6977(legacy 360p 兜底)。

### 9.4 内部文档
- [youtube-hd-playback.md](youtube-hd-playback.md):逐 alpha 调试记录(§6.7 row 1-53)+ FreeTube 核对(§6.8)+ SABR 调研(§6.9)。
- [DEVELOPMENT_PROGRESS.md](../../DEVELOPMENT_PROGRESS.md):P11-14 YouTube 播放进度。
- memory `youtube-plasma-wasm-n-decrypt`:plasma WASM 根因 + alpha.17-33 跨阶段突破记录(含 alpha.32 URL 类方式推翻「锁死」结论)。
