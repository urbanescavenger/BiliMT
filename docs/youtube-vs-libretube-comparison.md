# YouTube 播放实现对照:BiliTVNative vs LibreTube

> 彻底逐环节对照 BiliTVNative(`E:\GITHUB\BiliTVNative`,分支 `mort_debug`,alpha.86 终态)与
> LibreTube(`E:\GITHUB\LibreTube`,current)的 YouTube 视频播放实现。**纯代码层调查,不编译/不推送/不改代码**。
>
> 调查日期:2026-08-17。方法:并行派 Explore 子代理摸两边代码分布,再对关键文件逐行读源码确认
> (所有结论附 `file:line`,均经源码核对,非代理转述)。

---

## 0. TL;DR — 关键结论

1. **两边现在是同一条路,不是两条路。** BiliTVNative 的活跃 SABR 传输层
   (`core/youtube/sabr/media/*`,alpha.64+)是 **LibreTube `SabrMediaSource`/`SabrMediaFetcher`/`SabrClient`
   的直接移植**。两边都用同一个 NewPipeExtractor fork(`com.github.libre-tube:NewPipeExtractor`
   **`738c3d4`** — 两边 `gradle/libs.versions.toml` 逐字相同),都走 **visionOS /player → visionOS SABR**,
   visionOS `ClientInfo`(clientName=101)逐字对齐,首请求都**不带 poToken**,都同步刷 status=2,
   `buildBufferedRanges` 都报真实增长 own range(无 `Int.MAX` 假满缓冲)。

2. **"LibreTube 默认走 Piped 后端"是过时认知,需纠正。** 现版 LibreTube `NewPipeMediaServiceRepository.getStreams`
   用 `StreamInfo.getInfo`(NewPipe fork),**不碰 Piped**。BiliTV 文档/memory 里"对齐 LibreTube 默认 Piped
   路径"的表述基于旧版 LibreTube(Piped 时代),对当前 LibreTube 不成立。BiliTV 的 `buildSabrSessionFromPiped`
   是自创的实验 opt-in,LibreTube 并无对应物。

3. **真正的代码差异只有三处(都在边缘,不在 SABR 引擎主体):**
   - **RELOAD_PLAYER 处理**:LibreTube 致命抛 `"Server requested player reload"`(SabrClient.kt:583);
     BiliTV 解码 reloadToken 停车到 `SabrStreamRegistry`,Phase-2 用 `buildSabrSessionFromReloadPlayer`
     重打 visionOS /player 尝试续命(`MAX_RELOADS=3`)。**这是最大的行为分歧。**
   - **status=2 刷新的 minter 实现**:LibreTube 用 `PoTokenGenerator.getWebClientPoToken`
     (`PoTokenWebView`,NewPipe 栈);BiliTV 用 `YoutubeBotGuard`(bgutils-js,独立 WebView harness)。
     都产 WEB BotGuard token、都同步,但实现不同。
   - **PoTokenProvider 注册**:LibreTube `getIosClientPoToken` 返回 `null`;BiliTV 委派给
     `getWebClientPoToken`(NewPipePoTokenGenerator.kt:145-148)。这导致 NewPipe visionOS /player 请求体里
     一个带 WEB poToken、一个不带(见 §3.2)。

4. **4K / ≤1080p 的根因不是 itag 选择,也不是 SABR 引擎差异,而是 attestation。** 两边都用 visionOS
   (未 attested)ustreamerConfig;对需 attestation 的视频(多为 4K/HD)服务端直接 RELOAD,到不了 status=2。
   BiliTV 的 Phase-2 reload 重打的还是同一个未 attested 的 visionOS 路径 → 必然再 RELOAD → `MAX_RELOADS`
   耗尽放弃。**LibreTube 在这类视频上同样 RELOAD(代码路径相同),只是它直接崩溃不挣扎。** BiliTV 长期以为
   "LibreTube 能播 4K",该判断对**当前** LibreTube 未经证实,大概率源自 Piped 时代旧记忆。

5. **真正的 4K 分水岭在 SABR 之外的兜底**:LibreTube `setStreamSource` 有 DASH/HLS 兜底分支
   (NewPipeExtractor 内部自带 n-decrypt);BiliTV 的非 SABR 兜底走自研 `YoutubeNDecryptor`/`YoutubeSDecryptor`,
   在 plasma WASM player 上结构性失效(alpha.76 退役)→ 403。**这才是 BiliTV 4K 全死、LibreTube 可能靠 DASH
   兜底存活**的实质(若 LibreTube 真能播 4K,靠的也不是 SABR,是 DASH 兜底)。

---

## 1. 调查方法与范围

- 并行派两个 Explore 子代理分别摸 BiliTVNative 与 LibreTube 的 YouTube 播放代码分布(关键词:
  SABR/sabr/poToken/playerTimeMs/bufferedRange/buildBufferedRanges/own range/harvest/n-param/itag/
  signature/MediaSource/Dash/RELOAD/stall/watchdog)。
- 对关键文件逐行 Read 源码确认,所有 `file:line` 引用均经核读。
- 范围:视频点播(VOD)SABR 路径为主;直播/HLS/DASH 兜底只做架构对照。
- **不在范围**:不改代码、不推送、不打 tag、不本地编译(本地无 Android SDK)。

### 两边代码根

| | BiliTVNative | LibreTube |
|---|---|---|
| 根 | `E:\GITHUB\BiliTVNative` | `E:\GITHUB\LibreTube` |
| YouTube 包 | `app/src/main/java/com/kirin/mt/core/youtube/` | `app/src/main/java/com/github/libretube/`(player/api/poToken) |
| SABR 引擎 | `core/youtube/sabr/`(legacy)+ `core/youtube/sabr/media/`(活跃) | `player/parser/SabrClient.kt` + `player/`(media3) |
| NewPipe fork | `com.github.libre-tube:NewPipeExtractor` `738c3d4` | 同 `738c3d4`(逐字同) |
| proto 定义 | 手写 `SabrProto.kt`(ProtoWriter/ProtoReader) | `.proto` 文件 + protoc 生成(`app/src/main/proto/video_streaming/`) |

---

## 2. 架构总览:两边是同一条路

```
                BiliTVNative (alpha.86)                   LibreTube (current)
                ──────────────────────                    ───────────────────
取流元数据       StreamInfo.getInfo (NewPipe fork 738c3d4)  StreamInfo.getInfo (NewPipe fork 738c3d4)
  /player 客户端  visionOS (IOS)                            visionOS (IOS)
  ustreamerConfig visionOS-bound,未 attested                visionOS-bound,未 attested
  serverAbrUrl    visionOS(无 n-param)                      visionOS(无 n-param)
  poToken 铸取    NewPipePoTokenGenerator(WebView BotGuard)  PoTokenGenerator(PoTokenWebView)
                  getIosClientPoToken→委派 getWebClient        getIosClientPoToken→null
  SABR 首请求 poToken  "" (空,对齐 LibreTube)               "" (空,cache 在 getInfo 期恒空)
SABR 引擎        sabr/media/* = LibreTube 移植              parser/SabrClient.kt
  ClientInfo      visionOS(101/1.02/Apple/RealityDevice14,1)  visionOS(101/1.02/Apple/RealityDevice14,1) ←逐字同
  selectedFormatIds 全部 initializedFormats                 全部 initializedFormats
  bufferedRanges  initializedFormats.flatMap{buildBufferedRanges} 同(真实增长,无 Int.MAX)
  bitfield        video 存在→0 / 仅音频→1                    video 存在→0 / 仅音频→1
  playerTimeMs    segmentStartTimeMs                         segmentStartTimeMs
  status=2        同步刷(用 YoutubeBotGuard minter)          同步刷(用 PoTokenWebView minter)
  RELOAD_PLAYER   解码 reloadToken + Phase-2 重打 /player       致命抛 "Server requested player reload"
MediaSource      SabrMediaSource(移植) + MergingMediaSource  SabrMediaSource + MergingMediaSource
兜底             legacy dual-stream(死代码) + classic n-decrypt(退役) DASH/HLS(NewPipe 内置 n-decrypt)
```

**一句话**:alpha.64 起 BiliTV 把 LibreTube 的 SABR media3 栈几乎逐文件搬过来了,活跃路径两边行为基本一致;
差异收敛到 RELOAD 处理策略、status=2 minter 实现、PoTokenProvider 注册三点,以及 SABR 之外的非 SABR 兜底。

---

## 3. 逐环节对照

### 3.1 取流元数据(/player via NewPipe fork)

| | BiliTVNative | LibreTube |
|---|---|---|
| 入口 | `YoutubePlaybackResolver.buildSabrSessionFromNewPipe` (YoutubePlaybackResolver.kt:691-816) | `NewPipeMediaServiceRepository.getStreams` (NewPipeMediaServiceRepository.kt:287-366) |
| 调用 | `StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")` (L696) | `StreamInfo.getInfo("$YOUTUBE_FRONTEND_URL/watch?v=$videoId")` (L289) |
| fork 版本 | `738c3d4` (libs.versions.toml:17) | `738c3d4` (libs.versions.toml:14) |
| sabrUrl 取 | `info.serverAbrStreamingUrl` (L701),strip `cpn`(用 `visionOsCpn`,L711-712) | `resp.serverAbrStreamingUrl` (L363),原样 |
| ustreamerConfig | `info.ustreamerConfig` (L702) | `resp.ustreamerConfig` (L364) |
| 视频流 | `info.videoOnlyStreams` (L713) | `resp.videoOnlyStreams` (L338) |
| 音频流 | `info.audioStreams` (L714) | `resp.audioStreams` (L337) |

**差异**:无实质差异。两边同 fork 同调用。BiliTV 多一步 strip `cpn` 再用 `visionOsCpn` 重建
(注释 L707-710:ustreamerConfig 绑该 cpn,不能用随机 cpn 否则 RELOAD);LibreTube 直接用原始 URL
(原始 URL 已带 cpn)。**净效果相同**——都用 NewPipe 返回的 visionOS cpn。

**结论**:取流元数据层两边等价。

---

### 3.2 poToken 铸取与 PoTokenProvider 注册(★ 真实代码差异之一)

这是两边少有的、在 SABR 上游有实质代码差异的环节。

| | BiliTVNative | LibreTube |
|---|---|---|
| minter 实现 | `newpipe/NewPipePoTokenGenerator.kt` + `newpipe/PoTokenWebView.kt`(NewPipe 栈 WebView) + 独立 `YoutubeBotGuard.kt`(bgutils-js) | `api/poToken/PoTokenGenerator.kt` + `api/poToken/PoTokenWebView.kt` |
| `getWebClientPoToken` | 铸 WEB token,缓存 (NewPipePoTokenGenerator.kt:59-66) | 铸 WEB token,缓存 (PoTokenGenerator.kt:28-35) |
| **`getIosClientPoToken`** | **委派 `getWebClientPoToken`**(L145-148)→ visionOS getInfo 会拿到一个 WEB poToken | **返回 `null`**(PoTokenGenerator.kt:114)→ visionOS getInfo 不带 poToken |
| SABR 首请求 poToken | `poTokenB64 = ""`(空,YoutubePlaybackResolver.kt:766) | `poToken = getCachedWebClientPoToken()?.streamingDataPoToken`(SabrClient.kt:177-181)——但 visionOS getInfo 期 `getIosClientPoToken=null` 使 cache 恒空 → 实际空 |
| SABR ClientInfo | visionOS(`visionOsSabrClientInfo()`,InnerTubeClient.kt:579-587) | visionOS(SabrClient.kt:397-405) |
| status=2 刷新 minter | `botGuard.generatePoToken(videoId)`(YoutubeBotGuard,bgutils-js,YoutubePlaybackResolver.kt:367) | `poTokenGenerator.getWebClientPoToken(videoId)`(PoTokenWebView,NewPipe 栈,SabrClient.kt:594,621-624) |

**关键解读**:

- **SABR 首请求 poToken 两边都是空**,行为一致。BiliTV 的注释(YoutubePlaybackResolver.kt:760-766)
  "对齐 LibreTube:不带 poToken" **是正确的**,不是误读。alpha.80 曾尝试给 SABR 带 120B WEB poToken
  → visionOS 服务端按 visitor 不匹配全拒 RELOAD → 退回空。LibreTube 的 `getIosClientPoToken=null`
  使其 cache 在 visionOS getInfo 期间恒空,SabrClient.init 拿到 null → 首请求也 `setPoToken(empty)`(SabrClient.kt:396)。

- **真实差异在 visionOS /player 请求本身**:BiliTV 的 `getIosClientPoToken` 委派使 NewPipe 的 visionOS
  /player **带一个 WEB poToken**;LibreTube 的 /player **不带**。这会不会让两边拿到不同绑定属性的
  ustreamerConfig?静态分析无法定论(取决于服务端是否把 iOS poToken 绑进 visionOS ustreamerConfig),
  但这是两边 PoTokenProvider 注册上的**确凿代码分歧**,值得真机对照(见 §8 未决问题)。

- **status=2 minter 不同**:LibreTube 刷 status=2 用的是 NewPipe 栈 `PoTokenWebView`(维护中的参考实现);
  BiliTV 用自研 `YoutubeBotGuard`(bgutils-js,`buildContentBinding` 有 PLACEHOLDER c/hh,被注为脆弱)。
  若 BiliTV 的 minter 产出的 token 无效,status=2 刷新失败 → 下次 status=3 → 终端。这是 BiliTV 一处**风险点**,
  LibreTube 这边更稳。

**结论**:SABR 首请求 poToken 行为一致(空);但 /player 请求是否带 poToken 不同(minter 注册分歧),
且 status=2 刷新用的 minter 实现不同(BiliTV 脆弱)。

---

### 3.3 SABR 请求构建(URL / playerTimeMs / selectedFormatIds / bufferedRanges / ClientInfo)

BiliTV `SabrMediaFetcher.fetchStreamData`(SabrMediaFetcher.kt:201-307)对齐 LibreTube
`SabrClient.fetchStreamData`(SabrClient.kt:351-432)。

| 字段 | BiliTVNative (SabrMediaFetcher.kt) | LibreTube (SabrClient.kt) | 一致? |
|---|---|---|---|
| URL | `${session.sabrUrl}&rn=$rn` (L274) | `"$url&rn=${requestNumber++}"` (L417) | ✅ |
| playerTimeMs(req) | `req.segmentStartTimeMs` (L213,247) | `playbackRequest.segmentStartTimeMs` (L365,388) | ✅ |
| ClientAbrState bitfield | `if(videoFormat==null)1 else 0` (L255) | `if(videoFormat==null)1 else 0` (L369) | ✅ |
| stickyResolution | `max(vHeight,360)` (L243) | `max(videoFormat?.stream?.height?:0,360)` (L380) | ✅ |
| selectedFormatIds | `initializedFormats.values.map{encodeFormatId}` (L220) | `initializedFormats.values.map{it.id}` (L392) | ✅ |
| bufferedRanges | `initializedFormats.values.flatMap{buildBufferedRanges()}` (L221) | 同 (L393) | ✅ |
| ustreamerConfig | `session.ustreamerConfig` (L265) | `ustreamerConfig` (L389) | ✅ |
| preferredAudio/VideoFormatIds | `listOfNotNull(audioEnc/videoEnc)` (L266-267) | `listOfNotNull(audioFormat?.formatId()...)` (L390-391) | ✅ |
| StreamerContext poToken | `poTokenState.currentPoToken`(空, L227) | `poToken ?: empty`(空, L396) | ✅(都空) |
| StreamerContext ClientInfo | visionOS(session.clientInfo,L226) | visionOS(L397-405) | ✅(逐字同) |
| StreamerContext playbackCookie | `session.playbackCookie` (L228) | `playbackCookie?:empty` (L410) | ✅ |
| StreamerContext sabrContexts | `prepareSabrContexts()` active/unsent (L224,229-230) | `activeSabrContexts`/`filter!in active` (L407-409) | ✅ |
| HTTP headers | `x-protobuf`/`vnd.yt-ump`/`identity`/visionOS UA/Origin/Referer (L279-289) | 同 (L204-209, visionOS UA L631) | ✅ |
| Cookie/Visitor | 空串时不带(L285-286,对齐 LibreTube) | 不带 | ✅ |

**结论**:SABR 请求体构建两边逐字段对齐。BiliTV 的注释(SabrMediaFetcher.kt:197-199 "对齐 LibreTube fetchStreamData")
属实。这是 alpha.64 移植的直接成果。

---

### 3.4 bufferedRange / own range(曾经的核心 bug,现已对齐)

历史:这是 BiliTV 60s 断崖的主战场。alpha.30 引入"对方格式 `createFullBufferRange`(`Int.MAX` 假满缓冲)"
对齐 FreeTube;但 alpha.62 真机证 60s 断崖仍在(`own bufferedRange=null`)。alpha.63/64 改用 LibreTube
单流模型后,own range 改为真实增长。

| | BiliTVNative | LibreTube |
|---|---|---|
| own range 来源 | `InitializedFormat.buildBufferedRanges()`(SabrSegment.kt:69-89):并 `bufferedSegments`+`downloadedSegments`,按 seq 排序折叠成连续区段 | `InitializedFormat.buildBufferedRanges()`(SabrClient.kt:118-134):同逻辑 |
| 是否发 `Int.MAX` 假满缓冲 | **否**(alpha.63 修,SabrSegment.kt 注释) | **否** |
| 消费段追踪 | `getSegment` 把已下载段移出 `downloadedSegments`、放空 marker 进 `bufferedSegments` | 同(SabrClient.kt:108-115) |
| 与播放器缓冲同步 | `SabrSegmentRequest.bufferedSegments` 从 ExoPlayer chunk queue 来(DefaultSabrChunkSource.kt) | `PlaybackRequest.bufferedSegments` 从 ExoPlayer `queue` 来(DefaultSabrChunkSource.kt:286) |

**结论**:**两边现在完全对齐**。own range 真实增长,无 `Int.MAX`,无 60s 服务端软拒。BiliTV alpha.63 的
`Int.MAX` 移除 + alpha.64 单流移植正是对齐 LibreTube 的关键修复。legacy 双流路径(`SabrClient.createFullBufferRange`,
SabrClient.kt:580-589 / `SabrStreamingDataSource` 55s 滑窗)是死代码,活跃路径不走。

---

### 3.5 n-param / signature 解密

| | BiliTVNative | LibreTube |
|---|---|---|
| SABR 是否需要 n-decrypt | 否(visionOS serverAbrUrl 无 n-param) | 否(同) |
| 非 SABR 兜底的 n-decrypt | 自研 `YoutubeNDecryptor`(URL 类方式,zemer-cipher)+ `YoutubeSDecryptor`,**plasma WASM 上结构性失效,alpha.76 退役**(YoutubePlaybackResolver.kt:346-347) | **不实现**,完全依赖 NewPipeExtractor 内部 n-decrypt |
| 实现 | `YoutubeNDecryptor.kt`:`new g.<nClass>(url).get('n')` 注入 IIFE 闭包,WebView eval;config 224 条 `player_configs.json` | 无对应文件(grep `n.?param\|nsig\|signatureCipher` 仅命中无关 `UpdateParameters.kt`) |

**结论**:SABR 路径两边都不碰 n-decrypt。差异在**非 SABR 兜底**:LibreTube 把 n-decrypt 完全外包给
维护中的 NewPipeExtractor 库;BiliTV 自研的 n-decrypt 在 plasma WASM 上失效,已退役。**这是 BiliTV
4K 兜底全死的直接原因**(见 §4)。

---

### 3.6 status=2 PO token 刷新(同步,但 minter 不同)

| | BiliTVNative | LibreTube |
|---|---|---|
| 触发 | `processPart` PART_STREAM_PROTECTION_STATUS status==2 → `needsPoTokenRefresh=true`(SabrMediaFetcher.kt:419-428) | `processPart` status==2 → `poToken = generatePoToken()`(SabrClient.kt:591-595) |
| 执行时机 | `media()` 的 `readParts` **之后**同步调 `entry.refreshPoToken?.invoke()`(SabrMediaFetcher.kt:184-193) | `processPart` 内**同步**调(SabrClient.kt:594,在 `runBlocking{withContext(dispatcher)}` 内,SabrClient.kt:307-327) |
| 同步/异步 | **同步**(alpha.67,注释 SabrMediaFetcher.kt:423-426;alpha.66 异步致竞态 status=3 60s 重启已回退) | **同步** |
| minter | `botGuard.generatePoToken(videoId)` → `YoutubeBotGuard`(bgutils-js,独立 WebView) | `poTokenGenerator.getWebClientPoToken(videoId)` → `PoTokenWebView`(NewPipe 栈) |
| status=3 | `invalidPo=true` → 终端(SabrMediaFetcher.kt:422) | `throw "Attestation required"`(SabrClient.kt:598) |

**结论**:刷新策略两边对齐(都是同步,alpha.67 起 BiliTV 显式对齐 LibreTube)。**差异只在 minter 实现**:
BiliTV 用自研 bgutils-js minter(脆弱,PLACEHOLDER c/hh);LibreTube 用 NewPipe 栈 `PoTokenWebView`(参考实现)。
BiliTV 的 minter 可靠性是风险点——若产出 token 无效,status=2 刷新后仍 status=3 → 终端。

---

### 3.7 RELOAD_PLAYER 处理(★★ 最大行为分歧)

| | BiliTVNative | LibreTube |
|---|---|---|
| processPart 处理 | 解码 `decodeReloadPlayer` + dump + 存 reloadToken 到 `SabrStreamRegistry.storeReloadToken`(SabrMediaFetcher.kt:429-447) | **直接抛** `Exception("Server requested player reload")`(SabrClient.kt:579-584) |
| 是否终端 | 不直接抛;fetcher 循环随后抛 `SabrTerminalException` → `SabrStreamRegistry.evict` → 播放器 error-retry | 终端抛错 → media3 `LoadErrorHandlingPolicy` |
| 恢复机制 | Phase-2:`resolve()` 下次重进 `consumeReloadToken` → `buildSabrSessionFromReloadPlayer`(`postVisionOsPlayerReload` 用 reloadToken 重打 visionOS /player,**不带 poToken**)→ 新会话;`MAX_RELOADS=3` 上限(SabrStreamRegistry.kt:36-57) | **无恢复**,注释明言"第一个是罕见 edge-case,第二个无法处理"(SabrClient.kt:580-582) |

**关键解读**:

- BiliTV 的 Phase-2 reload 恢复是**自创的、LibreTube 没有的**机制。设计意图:服务端 RELOAD 下发 reloadToken,
  客户端用它重打 /player 拿新 ustreamerConfig 续命,不重新 harvest。

- **但对 attestation 视频这个恢复无效**:重打的是同一个 visionOS /player(**不带 poToken**,
  YoutubePlaybackResolver.kt 注释 L766 / buildSabrSessionFromReloadPlayer 同样空 poToken)→ 拿回的还是
  未 attested 的 visionOS ustreamerConfig → 服务端再 RELOAD → `MAX_RELOADS=3` 耗尽放弃。**恢复机制存在但治不了
  attestation RELOAD**,只是把同一个死循环多跑 3 遍。

- LibreTube 直接崩溃反而"诚实":它不假装能恢复。BiliTV 的 Phase-2 机制增加了复杂度但没解决根本问题,
  且 reloadToken 停车场(`SabrStreamRegistry.pendingReloads`)是额外状态。

**结论**:这是两边最大行为分歧。BiliTV 多一套(对 attestation 无效的)reload 恢复;LibreTube 直接致命。
对 attestation 视频**结果都一样**——播不了;对非 attestation 视频两边都不触发 RELOAD,无差异。

---

### 3.8 itag / 分辨率选择

| | BiliTVNative | LibreTube |
|---|---|---|
| itag 来源 | NewPipe `videoOnlyStreams` 全收 `height>0`(YoutubePlaybackResolver.kt:715) | NewPipe `videoOnlyStreams` 全收(NewPipeMediaServiceRepository.kt:338) |
| manifest 构建 | `SabrManifest.fromSession` 建**全部**视频 Representation,按 mimeType 分 AdaptationSet(SabrManifest.kt:41-66,alpha.81) | `SabrManifest` 按 mimeType+audioTrackId 分 AdaptationSet,全部 itag(SabrManifest.kt:37-60) |
| 选轨主体 | ExoPlayer `DefaultTrackSelector` 自适应选最高 bitrate(alpha.81 起不再预绑单 itag) | ExoPlayer `DefaultTrackSelector`,注释明言"all format selections are manual, as we do not let the server decide"(SabrClient.kt:248-250) |
| 默认画质上限 | `YoutubeDefaultQuality.maxHeight`(用户设置,Auto=最高可用,YoutubeDefaultQuality.kt:7-20) | `PlayerHelper.getDefaultResolution`(用户设置,空=auto,PlayerHelper.kt:376-387) |
| 会话选中 itag | `defaultItag` 按 `maxHeight` 选(YoutubePlaybackResolver.kt:720-730);`forceSessionVideoItag` 诊断开关(SabrStreamRegistry.kt:100) | 无预选,完全靠 ExoPlayer 选轨 |

**关键解读**:

- **≤1080p 的根因不在 itag 选择**。alpha.82 已推翻"itag248 误分类"假说(YoutubePlaybackResolver.kt:731-734
  注释):itag248 是 720p VP9 视频不是 Opus;RELOAD 与 itag 选择无关。`forceSessionVideoItag` 诊断(alpha.83)
  证伪"某 itag 是 RELOAD 根因"红绯鱼。

- **≤1080p 真因**:visionOS 路径对**需 attestation 的视频**(多是 4K/HD)直接 RELOAD;非 attestation 视频
  按用户 `maxHeight` 选档可正常播。所以"卡 ≤1080p"不是 itag 选择器卡,是 attestation 把高码率档全挡了。
  两边 itag 选择逻辑都无 1080p 硬上限——LibreTube 同样无 cap,差异在 attestation 视频能否播(见 §4)。

**结论**:itag 选择两边等价(都全量暴露给 ExoPlayer)。≤1080p 不是选择器问题,是 attestation 问题。

---

### 3.9 player 初始化 / MediaSource 拼装

| | BiliTVNative | LibreTube |
|---|---|---|
| MediaSource 类型 | `SabrMediaSource`(`sabr/media/`,移植 LibreTube)+ `MergingMediaSource`(字幕)+ legacy progressive/DASH 兜底 | `SabrMediaSource` + `MergingMediaSource`(字幕) |
| SABR 分支条件 | `PlaybackTrack.isSabrSingle==true`(PlayerScreen.kt:1472-1483 / MobilePlayerScreen.kt:634-646) | `!isLive && serverAbrStreamingUrl!=null && ustreamerConfig!=null`(OnlinePlayerService.kt:238-291) |
| 兜底分支 | PGC/progressive→`MergingMediaSource(Progressive×2)`(L1484-1500);非 SABR DASH→`DashMediaSource`(L1501-1503) | DASH(直播 / 无 SABR,OnlinePlayerService.kt:293-306)+ HLS(L308-321) |
| seek | `SabrMediaSource` 原生可 seek;legacy progressive 不可 seek→reload-seek(PlayerScreen.kt:397-406) | `SabrMediaSource` 原生可 seek(`getAdjustedSeekPositionUs`,DefaultSabrChunkSource.kt:128-149) |
| 字幕合并 | `MergingMediaSource(mediaSource, *subtitleSources)`(PlayerScreen.kt:1508-1524) | 同(OnlinePlayerService.kt:248-289,反射 `enableLazyLoadingWithSingleTrack`) |
| ExoPlayer 构建 | `TvPlaybackLoadControl`(MaxBufferMs=15_000,BufferForPlayback=2_500) | `PlayerHelper.createPlayer`(DefaultLoadControl,bufferingGoal 默认 50s,PlayerHelper.kt:507-518) |

**结论**:SABR 的 media3 拼装两边对齐(BiliTV 是移植)。差异在**兜底**与**缓冲**:
- BiliTV 有 legacy progressive/DASH 兜底分支(死代码居多);LibreTube 有 DASH/HLS 兜底(NewPipe 内置 n-decrypt)。
- BiliTV `MaxBufferMs=15s`(为避 4K 26Mbps OOM + status=2 同步后去 60s 重启 workaround);LibreTube 默认 50s。
  BiliTV 缓冲更短,与 status=2 同步刷新的历史修复绑定。

---

### 3.10 错误处理 / 重试 / 重载循环

| | BiliTVNative | LibreTube |
|---|---|---|
| 终端→evict | `SabrDataSource.open` 捕获 `SabrTerminalException`→`SabrStreamRegistry.evict`+抛 IOException(SabrDataSource.kt:48-58) | `SabrDataSource.open` 捕获→抛 IOException(SabrDataSource.kt:33-41) |
| fetcher 内重试 | `MAX_ATTEMPTS=6`/`MAX_BACKOFF_SLEEP_MS=2500`(SabrMediaFetcher.kt:492-494) | backoff 由 `NextRequestPolicy.backoffTimeMs` 驱动(SabrClient.kt:356-360),无固定 attempt 上限 |
| RELOAD 恢复 | Phase-2 reloadToken + `buildSabrSessionFromReloadPlayer`,`MAX_RELOADS=3` | 无,致命抛 |
| stall 看门狗 | TV 保留 8s 看门狗(PlayerScreen.kt:1638-1666);**mobile alpha.67 移除**(MobilePlayerScreen.kt:844-866) | 无专门看门狗;靠 `onPlayerError` + `LoadErrorHandlingPolicy` |
| error-retry | `autoRetryCount<MaxStallAutoRetry=2`→`retryKey+=1`→重 resolve(缓存 miss after evict→新 harvest) | `onPlayerErrorChanged`→播放器 error,服务层重试行为未深查 |

**结论**:BiliTV 有更复杂的重试栈(attempts/reloads/stall 看门狗三套),LibreTube 更简洁。BiliTV 的复杂度
源于历史 60s 断崖调试;alpha.64 移植 LibreTube 单流模型后多数已不必要,但代码仍保留。mobile 移除 stall 看门狗
是对齐 LibreTube 思路的体现。

---

### 3.11 缓冲 / LoadControl(附)

- BiliTV:`MinBuffer=10s`/`MaxBuffer=15s`/`BufferForPlayback=2.5s`/`Rebuffer=5s`(TvPlaybackLoadControl.kt:5-29)。
- LibreTube:`bufferingGoal` 默认 50s,min 10s(PlayerHelper.kt:213-217,507-518)。

BiliTV 缓冲更激进(短),历史原因:4K OOM + 60s 重启 workaround。移植后可考虑放宽,但与本次对照无功能影响。

---

### 3.12 字幕(附)

两边都不走 SABR 服务端字幕,直接拉 NewPipe 给的 WebVTT URL,`ProgressiveMediaSource`+`SubtitleExtractor`
转 MEDIA3_CUES,`MergingMediaSource` 合并。BiliTV 注释(youtube-api-notes.md §4.9)明言"对齐 LibreTube
`OnlinePlayerService`"。基本等价,不展开。

---

## 4. 4K / ≤1080p 终态定论:纠正"LibreTube 走 Piped"的误解

### 4.1 BiliTV 长期持有的假设(需纠正)

BiliTV 文档/memory 反复出现"LibreTube 默认走 Piped 后端→自带 poToken→回已 attested 的 WEB-bound
ustreamerConfig→故能播 4K"(见 youtube-stream-resolution.md alpha.82/83 段、youtube-hd-playback.md
alpha.83 更正、memory `youtube-4k-two-paths-dead-accept-1080p`)。

**对当前 LibreTube 这个假设不成立**:

- LibreTube `NewPipeMediaServiceRepository.getStreams`(NewPipeMediaServiceRepository.kt:287-366)
  用 `StreamInfo.getInfo`(NewPipe fork),**全仓库无 Piped client/`/streams/{id}` 调用**。
- 它拿的 `serverAbrStreamingUrl`/`ustreamerConfig` 来自 NewPipe fork 的 visionOS /player
  (NewPipeMediaServiceRepository.kt:363-364),与 BiliTV `buildSabrSessionFromNewPipe` **同一来源**。
- "Piped 自带 poToken 回 attested WEB config"描述的是**旧版 LibreTube**(Piped 时代),当前版已迁到
  NewPipe fork + 原生 SABR。BiliTV 的 `buildSabrSessionFromPiped`(YoutubePlaybackResolver.kt:838-914)
  是 BiliTV **自创**的实验 opt-in,LibreTube 并无对应物。

### 4.2 两边在 4K attestation 视频上的实际行为(代码推演)

两边代码路径相同(NewPipe fork visionOS getInfo → visionOS SABR,空 poToken),所以对需 attestation
的视频(如 jNl6YkkzKxw 4K):

1. /player 返回 visionOS-bound、**未 attested** 的 ustreamerConfig。
2. SABR 首请求带空 poToken + 未 attested config → 服务端 RELOAD_PLAYER(BiliTV 注释 YoutubePlaybackResolver.kt:824-825
   "对需 attestation 的视频服务端直接 RELOAD 不可续命")。
3. **BiliTV**:Phase-2 reload 重打 visionOS /player(空 poToken)→ 还是未 attested config → 再 RELOAD
   → `MAX_RELOADS=3` 耗尽 → evict → 播放器 error-retry → 重 resolve → 同样 RELOAD → 最终播不了。
4. **LibreTube**:RELOAD 即致命抛 `"Server requested player reload"`(SabrClient.kt:583)→ 播放器 error。
   除非 `OnlinePlayerService` 在 error 后回落 DASH 分支(未在本次调查中证实),否则**也播不了 4K**。

**结论**:对 attestation 4K 视频,SABR 路径两边都死。差异在 BiliTV 多跑 3 遍无效恢复后死,LibreTube 立刻死。

**alpha.93 后更糟(§4.2 补充,2026-08-17)**:alpha.93 NewPipe-first 主路径在 `resolve()` L151-173 对非 null 的
`buildSabrSessionFromNewPipe` **立即早退 return**,使真正消费 reload token 的 `consumeReloadTokenSlot()` +
`MAX_RELOADS=3` 闸门(上述步骤 3 的"3 遍")**永远不可达**。于是每次 error-retry 重进 resolve 又建**同一空 poToken
visionOS 会话** → 又 RELOAD → evict → **无界循环**(reloadCount 17→24),连"3 遍后放弃"都到不了,直到 sid 被清 →
Source error。**alpha.97 修复**:`resolve()` NewPipe-first 块加 `reloadCount(videoId)>0` 守卫,RELOAD 后跳过重建,
直接落 ≤1080p DASH/HLS 兜底(对齐 LibreTube 不循环,见 [youtube-dash-fallback-plan.md](youtube-dash-fallback-plan.md) alpha.97)。

### 4.3 真正可能的 4K 分水岭:SABR 之外的兜底

- **LibreTube**:`setStreamSource`(OnlinePlayerService.kt:232-328)有 DASH 分支(直播或无 SABR 时)+
  HLS 兜底。NewPipeExtractor 内部自带 n-decrypt(库维护中,对新 player 适配更好)。若 `onPlayerError` 回落
  DASH,LibreTube 可能靠 DASH 播 4K。**本次未读 `OnlinePlayerService` 的 error→DASH 回落逻辑,标记为未证实**(§8)。
- **BiliTV**:非 SABR 兜底走 `YoutubeNDecryptor`/`YoutubeSDecryptor`(YoutubePlaybackResolver.kt:611-623),
  plasma WASM 上结构性失效(alpha.76 退役)→ 403。**4K 兜底全死**。

**所以**:若 LibreTube 真能播 4K(待证实),靠的也是 **DASH 兜底 + NewPipeExtractor 内置 n-decrypt**,
**不是 SABR**。BiliTV 要追平 4K,方向不是继续在 visionOS SABR 上折腾 attestation,而是**修好/替换
非 SABR 兜底的 n-decrypt**(用 NewPipeExtractor 的内置实现,而非自研 `YoutubeNDecryptor`)。

### 4.4 用户已接受 ≤1080p

memory `youtube-4k-two-paths-dead-accept-1080p`:用户 2026-08-17 接受 ≤1080p,放弃 4K。本对照**不主张
重启 4K 工作**,仅厘清机制:≤1080p 的天花板是 visionOS attestation,不是 itag/不是 SABR 引擎差异。

---

## 5. 两边实现对照速查表

| 环节 | BiliTVNative | LibreTube | 一致? | 备注 |
|---|---|---|---|---|
| NewPipe fork | `738c3d4` | `738c3d4` | ✅ | 逐字同 |
| /player 客户端 | visionOS | visionOS | ✅ | |
| 取流入口 | buildSabrSessionFromNewPipe | NewPipeMediaServiceRepository.getStreams | ✅ | 同 fork 同调用 |
| **PoTokenProvider.getIosClientPoToken** | **委派 getWebClientPoToken** | **null** | ❌ | /player 请求带不带 poToken 不同 |
| SABR 首请求 poToken | 空 | 空 | ✅ | |
| SABR ClientInfo | visionOS(逐字同) | visionOS | ✅ | |
| selectedFormatIds | 全部 initialized | 全部 initialized | ✅ | |
| bufferedRanges | 真实 buildBufferedRanges | 真实 buildBufferedRanges | ✅ | alpha.63 对齐 |
| bitfield | video→0/仅音频→1 | 同 | ✅ | |
| playerTimeMs | segmentStartTimeMs | segmentStartTimeMs | ✅ | |
| status=2 刷新 | 同步 | 同步 | ✅ | |
| **status=2 minter** | **YoutubeBotGuard(bgutils-js,脆弱)** | **PoTokenWebView(NewPipe 栈)** | ❌ | |
| **RELOAD_PLAYER** | **Phase-2 reloadToken 恢复(MAX_RELOADS=3)** | **致命抛** | ❌ | 最大分歧 |
| itag 选择 | 全量给 ExoPlayer | 全量给 ExoPlayer | ✅ | |
| MediaSource | SabrMediaSource(移植) | SabrMediaSource | ✅ | |
| 兜底 | legacy progressive/DASH + classic n-decrypt(退役) | DASH/HLS + NewPipe 内置 n-decrypt | ❌ | 4K 分水岭 |
| 缓冲 | MaxBuffer 15s | bufferingGoal 50s | ❌ | 历史原因,无功能影响 |
| stall 看门狗 | TV 保留 / mobile 移除 | 无 | ~ | |
| 字幕 | WebVTT+MergingMediaSource | 同 | ✅ | |
| 广告 itag 白名单 | 有(alpha.71,SabrMediaFetcher.kt:324-328,381-385) | 无 | ❌ | BiliTV 多一层防御 |
| forceSessionVideoItag 诊断 | 有(alpha.83) | 无 | ❌ | BiliTV 诊断专用 |
| reloadToken 停车场 | 有(SabrStreamRegistry) | 无 | ❌ | BiliTV Phase-2 配套 |

**三处真实功能分歧**(打 ❌ 且影响行为):
1. PoTokenProvider.getIosClientPoToken(委派 vs null)——影响 /player 请求是否带 poToken
2. status=2 minter(YoutubeBotGuard vs PoTokenWebView)——影响刷新可靠性
3. RELOAD_PLAYER(恢复 vs 致命)+ 4K 兜底(n-decrypt 退役 vs NewPipe 内置)——影响 attestation 视频存活

其余 ❌ 是 BiliTV 多出的诊断/防御/历史代码,功能上两边等价或 BiliTV 更保守。

---

## 6. BiliTVNative 侧可能的改进点

> 纯调查结论,不实施。按"收益/风险"排序。

1. **[高收益/中风险] 4K 兜底换用 NewPipeExtractor 内置 n-decrypt,放弃自研 `YoutubeNDecryptor`**。
   LibreTube 把 n-decrypt 完全外包给 NewPipeExtractor 库(维护中,对新 player 适配更好)。BiliTV 自研的
   URL 类方式在 plasma WASM 上结构性失效。若要恢复 4K,把非 SABR 兜底的 `resolveStreamUrl`
   (YoutubePlaybackResolver.kt:611-623)改成走 NewPipeExtractor 已解密的 URL,而非自研 `nDecryptor.decrypt`。
   ——但这与用户"接受 ≤1080p"决定相悖,仅作记录。

2. **[中收益/低风险] status=2 刷新 minter 对齐 LibreTube,用 `NewPipePoTokenGenerator` 而非 `YoutubeBotGuard`**。
   BiliTV 的 `refreshPoToken` 注的是 `botGuard.generatePoToken`(YoutubeBotGuard,bgutils-js,PLACEHOLDER c/hh,
   被注脆弱)。LibreTube 用 `PoTokenWebView`(NewPipe 栈,参考实现)。BiliTV 已有 `NewPipePoTokenGenerator`
   且已铸 WEB token,可直接复用作 status=2 minter,去掉对 `YoutubeBotGuard` 的依赖。降低 status=2 刷新失败
   → status=3 终端的风险。

3. **[低收益/低风险] 评估 Phase-2 RELOAD 恢复是否值得保留**。对 attestation 视频无效(重打同未 attested 路径);
   对非 attestation 视频不触发。当前只是多跑 3 遍死循环 + 额外 reloadToken 停车场状态。可考虑对齐 LibreTube
   直接致命(简化代码),或在 Phase-2 重打时**带 poToken**(用 NewPipePoTokenGenerator 铸的 WEB token)
   尝试 attestation——但这与 alpha.80"带 WEB poToken→RELOAD"结论冲突,需真机重测(见 §8)。

4. **[低收益/低风险] 修正文档/memory 里"LibreTube 默认走 Piped"的过时表述**。当前 LibreTube 用 NewPipe fork,
   非 Piped。避免后续基于错误前提决策。

5. **[低收益/低风险] 缓冲放宽**。`MaxBufferMs=15s` 是 4K OOM + 60s 重启 workaround 的遗产;移植 LibreTube
   单流模型后可考虑放宽到 30-50s(对齐 LibreTube 默认),改善弱网体验。需真机验证不回退 OOM。

6. **[诊断] 真机对照 BiliTV vs LibreTube 的 visionOS /player 请求体**。两边 PoTokenProvider 注册不同
   (getIosClientPoToken 委派 vs null),抓包对比 /player 请求是否带 poToken、ustreamerConfig 返回是否不同,
   可定论 §3.2 的未决问题。

---

## 7. 两边文件清单对照

### BiliTVNative(`app/src/main/java/com/kirin/mt/`)

| 角色 | 文件 |
|---|---|
| SABR 引擎(活跃,移植) | `core/youtube/sabr/media/SabrMediaFetcher.kt` |
| | `core/youtube/sabr/media/SabrMediaSource.kt` |
| | `core/youtube/sabr/media/SabrMediaPeriod.kt` |
| | `core/youtube/sabr/media/DefaultSabrChunkSource.kt` |
| | `core/youtube/sabr/media/SabrDataSource.kt` |
| | `core/youtube/sabr/media/SabrManifest.kt` / `Representation.kt` / `AdaptationSet.kt` / `SabrSegment.kt` |
| SABR 引擎(legacy,死代码) | `core/youtube/sabr/SabrClient.kt` / `SabrStreamingDataSource.kt` / `SabrDashDataSource.kt` / `SabrAwareDataSource.kt` |
| proto 编解码 | `core/youtube/sabr/SabrProto.kt` / `UmpReader.kt` / `CompositeBuffer.kt` / `ProtoWire.kt` |
| 会话注册表 | `core/youtube/sabr/SabrStreamRegistry.kt` |
| 取流编排 | `core/youtube/YoutubePlaybackResolver.kt` |
| InnerTube /player | `core/youtube/InnerTubeClient.kt`(visionOsSabrClientInfo L579-587,postVisionOsPlayerReload L189-238) |
| poToken(NewPipe 栈) | `core/youtube/newpipe/NewPipePoTokenGenerator.kt` / `PoTokenWebView.kt` / `JavaScriptUtil.kt` / `NewPipeInit.kt` |
| poToken(自研 bgutils) | `core/youtube/YoutubeBotGuard.kt` / `YoutubeJsExecutor.kt` |
| n/s 解密 | `core/youtube/YoutubeNDecryptor.kt` / `YoutubeSDecryptor.kt`(退役) |
| 默认画质 | `core/player/YoutubeDefaultQuality.kt` |
| LoadControl | `core/player/TvPlaybackLoadControl.kt` |
| 播放器 UI(TV) | `ui/player/PlayerScreen.kt` |
| 播放器 UI(mobile) | `ui/mobile/player/MobilePlayerScreen.kt` |
| Piped 实验 | `core/youtube/piped/*` |
| 配置 | `gradle/libs.versions.toml`(newpipeextractor=738c3d4) |

### LibreTube(`app/src/main/java/com/github/libretube/`)

| 角色 | 文件 |
|---|---|
| SABR 引擎 | `player/parser/SabrClient.kt`(核心,fetchStreamData L351-432,processPart L439-614,buildBufferedRanges L118-134,status=2 L586-601,RELOAD L579-584) |
| | `player/parser/UmpParser.kt` / `CompositeBuffer.kt` / `Xtags.kt` |
| media3 栈 | `player/SabrMediaSource.kt` / `SabrMediaPeriod.kt` / `DefaultSabrChunkSource.kt` / `SabrDataSource.kt` / `SabrChunkSource.kt` |
| manifest | `player/manifest/SabrManifest.kt` / `Representation.kt` / `AdaptationSet.kt` |
| proto | `app/src/main/proto/video_streaming/*.proto`(27 个)+ `misc/common.proto` |
| 取流 | `api/NewPipeMediaServiceRepository.kt`(getStreams L287-366) |
| poToken | `api/poToken/PoTokenGenerator.kt`(getIosClientPoToken=null L114)/ `PoTokenWebView.kt` / `JavaScriptUtil.kt` |
| 播放器服务 | `services/OnlinePlayerService.kt`(setStreamSource L232-328: SABR/DASH/HLS 分支) |
| 播放器配置 | `helpers/PlayerHelper.kt`(createPlayer L482-501,getDefaultResolution L376-387,getLoadControl L507-518) |
| 下载(复用 SABR) | `repo/SabrDownloadProvider.kt` |
| 配置 | `gradle/libs.versions.toml`(newpipeextractor=738c3d4) |

---

## 8. 未决问题与后续验证

> 静态分析能定论的已写明;以下需真机/抓包/动态验证。

1. **visionOS /player 请求是否带 poToken 的实际影响**。BiliTV `getIosClientPoToken` 委派(带 WEB poToken)
   vs LibreTube `null`(不带)。需抓包对比两边 /player 请求体,确认服务端返回的 ustreamerConfig 绑定属性
   是否不同。若 BiliTV 因带 poToken 拿到 WEB-visitor 绑定的 config,而 SABR 发空 poToken → visitor 不匹配
   → 这才是 BiliTV attestation RELOAD 的真因(而非纯 attestation)。验证方式:临时把 BiliTV
   `getIosClientPoToken` 改回返回 null(对齐 LibreTube),真机看 attestation 视频是否仍 RELOAD。

2. **LibreTube 对 attestation 4K 视频到底能不能播**。代码推演两边 SABR 都死。需在 LibreTube 真机实测
   jNl6YkkzKxw 4K:若能播,走的是 SABR 还是 DASH 兜底(`OnlinePlayerService` error→DASH 回落逻辑未读);
   若不能,则确认"LibreTube 能播 4K"是 Piped 时代旧记忆,当前版同样不行。

3. **BiliTV status=2 minter(YoutubeBotGuard)产出的 token 是否有效**。若无效,同步刷新后下请求仍 status=3
   → 终端。真机看 status=2 后是否出现 status=3。可对照换成 `NewPipePoTokenGenerator` minter 看是否改善。

4. **Phase-2 reload 带 poToken 是否能过 attestation**。alpha.80 结论是"visionOS+WEB-poToken→RELOAD",
   但那是首请求带;Phase-2 reload 是服务端 RELOAD 后重打,语境不同。可真机试 `buildSabrSessionFromReloadPlayer`
   带 NewPipe 铸的 WEB poToken,看是否突破 attestation(低优先,与用户 ≤1080p 决定相悖)。

---

## 附:关键代码路径速查

- BiliTV SABR 请求构建:`SabrMediaFetcher.fetchStreamData` (SabrMediaFetcher.kt:201-307)
- BiliTV buildBufferedRanges:`SabrSegment.buildBufferedRanges` (SabrSegment.kt:69-89)
- BiliTV status=2:`SabrMediaFetcher.processPart` (SabrMediaFetcher.kt:419-428) + `media()` (L184-193)
- BiliTV RELOAD:`SabrMediaFetcher.processPart` (L429-447) + `buildSabrSessionFromReloadPlayer` (YoutubePlaybackResolver.kt:950-1018)
- BiliTV visionOS ClientInfo:`InnerTubeClient.visionOsSabrClientInfo` (InnerTubeClient.kt:579-587)
- BiliTV SABR 会话(空 poToken):`buildSabrSessionFromNewPipe` (YoutubePlaybackResolver.kt:760-766, L766 `poTokenB64=""`)
- LibreTube SABR 请求构建:`SabrClient.fetchStreamData` (SabrClient.kt:351-432)
- LibreTube buildBufferedRanges:`SabrClient.InitializedFormat.buildBufferedRanges` (SabrClient.kt:118-134)
- LibreTube status=2:`SabrClient.processPart` (SabrClient.kt:586-601)
- LibreTube RELOAD(致命):`SabrClient.processPart` (SabrClient.kt:579-584)
- LibreTube poToken seed(空):`SabrClient.init` (SabrClient.kt:177-181) + `PoTokenGenerator.getIosClientPoToken=null` (PoTokenGenerator.kt:114)
- LibreTube 兜底分支:`OnlinePlayerService.setStreamSource` (OnlinePlayerService.kt:232-328)