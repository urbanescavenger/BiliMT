# YouTube DASH 兜底计划

> SABR 不可用 / RELOAD 耗尽时,用 **NewPipeExtractor 已解密**的流走 DASH 播放,绕开自研
> `YoutubeNDecryptor`(plasma WASM 失效)。对齐 LibreTube DASH 分支。
>
> 与用户"接受 ≤1080p"一致——目标是让 SABR 播不了的视频也能播,**不把 4K 作为目标**。
>
> 背景与逐环节对照见 [youtube-vs-libretube-comparison.md](youtube-vs-libretube-comparison.md)。

---

## 1. 现状为什么死(已核读)

- 非 SABR fallback [YoutubePlaybackResolver.kt:393-442](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L393-L442)
  用 WEB `/player` 的 `allAdaptive` + `resolveStreamUrl`→`nDecryptor.decrypt` → plasma WASM 失效 → 403。
- `buildSabrSessionFromNewPipe` 已拿 NewPipe `videoOnlyStreams`/`audioStreams`,但
  [newPipeVideoRaw/newPipeAudioRaw:1120-1136](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1120-L1136)
  只取 metadata,**丢掉已解密 URL + init/index range**。
- 合成 MPD [buildDashManifest:2228](../app/src/main/java/com/kirin/mt/ui/player/PlayerScreen.kt#L2228)
  用 PlaybackTrack.baseUrl(自研解密的死 URL)。

## 2. LibreTube 怎么做(对照)

- `Streams.dash = resp.dashMpdUrl`([NewPipeMediaServiceRepository.kt:312](../../LibreTube/app/src/main/java/com/github/libretube/api/NewPipeMediaServiceRepository.kt#L312))
  —— NewPipe 的 `dashMpdUrl`(YouTube 签名 DASH manifest,NewPipe 内部解密)。
- `setStreamSource` DASH 分支用 `DashMediaSource` 直拉 manifest;NewPipeExtractor 内部做 n-decrypt;
  注册 `PoTokenGenerator` 让 WEB 抽取拿 attested URL。

## 3. 分阶段计划

### Phase 0 — 验证(无代码改动行为,仅加诊断日志,真机/抓包先行)

0.1 确认 fork `738c3d4` 的 `StreamInfo.getInfo` 是否暴露 `info.dashMpdUrl` 且对 VOD 非空。
   **风险点**:visionOS `/player` 可能不返 `dashManifestUrl`(只返 serverAbrStreamingUrl + adaptiveFormats)。
0.2 确认 `VideoStream`/`AudioStream` 的 `getContent()`/`url` 是否已 n-decrypt,`itagItem` 是否有
   `initStart/End`、`indexStart/End` 可拼 SegmentBase。
0.3 **最关键**:确认 attestation 视频(如 jNl6YkkzKxw)的 NewPipe DASH URL 是否也 attestation-gated。
   若是,需确保 NewPipe 抽取走 WEB client + 已注册的 `NewPipePoTokenProvider`(BiliTV 已注册
   `NewPipePoTokenGenerator`)→ URL 已 attested。若 fork getInfo 只走 visionOS(无 poToken)→ DASH 也 403,
   需另想办法(见风险)。

**实施**:在 `buildSabrSessionFromNewPipe` 加诊断日志(不改播放行为),打成 alpha tag 走云编译,
真机日志读 `Y:\download\bilitv\logs`(memory `realtime-log-dir`)。

### Phase 1 — 最小可用:NewPipe `dashMpdUrl` 直拉(对齐 LibreTube)

1.1 `NewPipeSabrResult` 增 `dashMpdUrl: String?`(从 `info.dashMpdUrl` 取)。
1.2 `resolve()` SABR 失败分支([L356-358](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L356-L358)
   无 SABR 数据 / RELOAD 耗尽):若 `dashMpdUrl` 非空 → 构 DASH PlaybackInfo(单条 videoTrack,
   `baseUrl=dashMpdUrl`,新加 `isDashManifest=true` 标志),**不走 `resolveStreamUrl`/自研 n-decrypt**。
1.3 PlayerScreen/MobilePlayerScreen 分支:`isDashManifest` →
   `DashMediaSource.Factory.createMediaSource(Uri.parse(dashMpdUrl))`,复用现有
   [DashMediaSource 分支 L1501-1503](../app/src/main/java/com/kirin/mt/ui/player/PlayerScreen.kt#L1501-L1503),
   但用真 manifest URL 而非合成 `data:` URI。
1.4 `dashMpdUrl` 空才落现有自研 n-decrypt 路径(保留作 last resort)。

### Phase 2 — 合成 DASH from NewPipe adaptive(当 `dashMpdUrl` 空)

2.1 [newPipeVideoRaw/newPipeAudioRaw:1120-1136](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L1120-L1136)
   增字段:`url`(`stream.getContent()`)+ `initRange`/`indexRange`(从 `itagItem` 拼)。
2.2 新增 `buildDashPlaybackInfoFromNewPipe`:从 NewPipe adaptive 构 `PlaybackTrack`(带 `segmentBase`),
   复用现有 `buildDashManifest` 的 `<SegmentBase>` 路径。
2.3 选档复用 `youtubeDefaultQuality.maxHeight`(与 SABR 一致),硬件 codec 过滤复用 `codecKeySupported`
   ([L400](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L400))。
2.4 `DashMediaSource` 拉合成 MPD(`data:` URI,现有机制)。

### Phase 3 — SABR RELOAD 耗尽自动降级 DASH

3.1 `SabrStreamRegistry` reload 计数到 `MAX_RELOADS=3`
   ([SabrStreamRegistry.kt:36-57](../app/src/main/java/com/kirin/mt/core/youtube/sabr/SabrStreamRegistry.kt#L36-L57))
   后,resolve 重进时**切 DASH fallback**(Phase 1/2 PlaybackInfo),而非继续死循环重试 SABR。
3.2 加设置项 `youtubeDashFallback`(默认开),**TV `SettingsScreen` 与 Mobile `MobileSettingsScreen`
   两套都加**(memory `settings-row-two-screens`,漏一边那端开不了)。

## 4. 风险与边界

- **attestation 4K**:若 NewPipe DASH URL 也 attestation-gated 且 fork getInfo 只走 visionOS(无 poToken),
  4K 仍 403。Phase 0.3 是 go/no-go 关卡。若确认 visionOS 不给 attested DASH URL,4K 不恢复(符合用户决定);
  ≤1080p 的非 attestation 视频仍能靠 DASH 兜底播。
- **poToken minter**:DASH 若需 poToken,复用 `NewPipePoTokenGenerator`(已注册、NewPipe 栈),
  **不用脆弱的 `YoutubeBotGuard`**(对照结论 §6.2)。
- **保留 legacy 自研 n-decrypt** 作 last resort,不删(非 plasma player 仍可用,零回退风险)。
- **不碰 SABR 引擎主体**:DASH 兜底是 SABR 之外的新分支,与 alpha.86 SABR 路径正交。

## 5. 验证闭环(遵循全局 CLAUDE.md)

- 本地无 SDK,改完**直接走云编译**(memory `skip-local-gradle-build`),推送后 CronCreate 监控 CI
  (`--repo urbanescavenger/BiliMT`)。
- 改完推送后先打测试 alpha tag 用 tag 触发云编译验证(memory `test-tag-before-build-validation`)。
- 真机:挑一个 SABR 可播视频(回归对照)+ 一个 SABR RELOAD 视频证 DASH 兜底能播。
- 日志读 `Y:\download\bilitv\logs`(memory `realtime-log-dir`)。
- 新开发任务开工前补 PLAN、完成补 PROGRESS(memory `new-dev-updates-docs`)。

## 6. 进度

- [x] Phase 0:诊断日志 + 真机验证(b28e50c)
  - **dashMpdUrl 恒空** — visionOS `StreamInfo.getInfo` 不返 DASH manifest(fork `getDashMpdUrl` 仅读 android
    streamingData,android 无 poToken 取不到 protected manifest)。**Phase 1(直拉 manifest)不可行**。
  - **hlsUrl 非空** — visionOS /player 返 `hls_variant` manifest(`https://manifest.googlevideo.com/api/manifest/hls_variant/...`)。
    visionOS 是 Apple 平台,YouTube 给 Apple 平台的 hlsUrl 是 AVPlayer 级原生 HLS 交付(非 web attestation 路径)。
  - NewPipe firstVideo/firstAudio 为 **PROGRESSIVE_HTTP 已解密直链**(itag313 4K 在列)。**更正误判(alpha.92)**:
    range 字段不在 `itagItem`(其 toString 是默认实现看不出),而在 `VideoStream`/`AudioStream` **顶层**——
    `content`(已解密 URL)/`initStart`/`initEnd`/`indexStart`/`indexEnd`,同一 fork `738c3d4`,
    LibreTube `toPipedStream` 直读。当初"需另探 ItagItem 字段名"是打错对象(itagItem.toString)。
  - itag313(4K)SABR 仍 RELOAD(attestation),`potSent=0B`——坐实 ≤1080p 限制根因是 attestation 非 itag。
- [x] Phase 1(HLS 接替,alpha.90):dashMpdUrl 空时落 **hlsUrl** → HlsMediaSource
  - `PlaybackInfo.remoteHlsManifestUrl`(新增) + `isHlsManifest()`/`hasRemoteManifest()` helper。
  - `buildDashFallbackFromNewPipe` 扩展:dashMpdUrl 非空→DASH;否则 hlsUrl 非空→HLS;否则 null。
  - 两屏(TV `PlayerScreen` / Mobile `MobilePlayerScreen`)加 `HlsMediaSource` 分支(优先于 DashMediaSource)。
  - 两屏空轨守卫放宽:`audioTracks.isEmpty() && !isProgressive && !hasRemoteManifest()`(顺带修 alpha.88 DASH
    dummy 轨的潜在 empty-tracks 误判,此前因 dashMpdUrl 空从未触发)。
  - 两处耗尽点接 HLS:① RELOAD 闭环未回 SABR(L303);② NewPipe 无 SABR 数据(L366,替代已死的 classic n-decrypt)。
- [ ] Phase 1 真机验证:HLS 是否可播 / 是否 attestation 堵 / 多码率上限
- [x] **Phase 2(自合成 DASH 复活,alpha.92):从 NewPipe 流顶层 `content`+init/index range 自合成 MPD,提为主兜底**
  (对齐 LibreTube `createDashSource`;更正"itagItem 字段未暴露"误判——字段在 stream 顶层,同 fork 可读)。
  `buildDashFallbackFromNewPipe` 优先级改为:自合成 DASH(range 非空)→ dashMpdUrl(恒空)→ HLS。
  播放器零改动(复用 `buildDashManifest` → `DashMediaSource`,segmentBase 非 null 即合成)。
  **待真机验证**:visionOS getInfo 流 range 是否有值 + 自合成 MPD 能否起播(见下方 Phase 2 真机验证)。
- [ ] Phase 3:RELOAD 耗尽自动降级已由 alpha.87/88 闭环 + alpha.90 HLS 兜底覆盖;设置项 `youtubeDashFallback`
  (两屏)待加(当前兜底自动生效,设置项为可选开关)
- [x] **alpha.91:真根因推翻 HLS 兜底——复刻 LibreTube 懒鉴权 poToken**(2026-08-17,双 agent 对照 LibreTube)
  - **真机坐实**:4K/Auto attestation 视频(jNl6YkkzKxw)SABR 每 part RELOAD,alpha.90 的 HLS 兜底挂 L303/L366 到不了
    (首 resolve 返 SABR → RELOAD → re-resolve 钻坏 WebView harvest)。用户决定:不走 RELOAD→HLS workaround,**复刻
    LibreTube,保证 SABR/DASH 可用**。
  - **真根因(推翻"visitor mismatch"误诊)**:
    ① **minter split**:SABR `status=2` 刷新用 `YoutubeBotGuard`(contentBinding=PLACEHOLDER 弱 token)→ 服务端拒 →
       status=3/RELOAD。LibreTube 用 `PoTokenWebView`(真 BotGuard-attested web `streamingDataPoToken`)。BiliTV 已有
       [PoTokenWebView.kt](../app/src/main/java/com/kirin/mt/core/youtube/newpipe/PoTokenWebView.kt)(LibreTube 直移植),
       但从没喂给 SABR refresh。
    ② **iOS delegation**:[NewPipePoTokenGenerator.kt:145-148](../app/src/main/java/com/kirin/mt/core/youtube/newpipe/NewPipePoTokenGenerator.kt#L145-L148)
       `getIosClientPoToken` 委托铸 WEB token → visionOS `/player` 带 WEB poToken → ustreamerConfig 被绑 WEB visitor
       → 与 visionOS SABR 会话不匹配 → init 即 RELOAD(alpha.80 真因)。LibreTube 返 null。
  - **修复(4 行/2 文件)**:
    - [YoutubePlaybackResolver.kt](../app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt) L109/L284/L390 三处
      `refreshPoToken` → `biliTvPoTokenProvider.getWebClientPoToken(videoId)?.streamingDataPoToken?.toByteArray(UTF_8)`(统一 PoTokenWebView minter)。
    - [NewPipePoTokenGenerator.kt:145-148](../app/src/main/java/com/kirin/mt/core/youtube/newpipe/NewPipePoTokenGenerator.kt#L145-L148)
      `getIosClientPoToken` 回退 `null`(visionOS getInfo 不带 poToken → ustreamerConfig visitor 不绑定)。
  - **验证闭环**:空 init → 服务端 status=2(非 RELOAD)→ PoTokenWebView 重铸 attested token → SABR 起播。
    真机盯:status=2 出现 + refresh 走 PoTokenWebView(非 YoutubeBotGuard)+ 无 RELOAD_PLAYER_RESPONSE。≤1080p 无 status=2 不回归。
  - **对齐点**(不动):`poTokenB64=""`(init 空,L806)、visionOS SABR client/UA/空 visitorData/`cpn=visionOsCpn`。
- [x] **alpha.93:NewPipe-first 主路径(解耦坏 WEB WebView 收割门卫)**(2026-08-17,commit 330fc11)
  - **真机坐实(alpha.91/90 APK,13590 运行)**:resolve() 卡死在 `postPlayer(WEB)` 的 **viaWebView harvest**
    (m.youtube.com 错误页 27s,SSL/coroutine scope left composition),**从未到达 buildSabrSessionFromNewPipe(visonOS
    NewPipe SABR)与 DASH 兜底**——alpha.91/92 的 SABR/DASH 修复全被挡在门内。
  - **根因(对照 LibreTube)**:LibreTube 取流主路径就是 `StreamInfo.getInfo`(visionOS),**从不走 WEB /player、无
    WebView 收割**(其 WebView 只是 BotGuard token 生成器)。BiliTV 历史遗留:NewPipe(alpha.71 定为 SABR 唯一路径)
    仍被锁在 postPlayer(WEB) 成功之后;alpha.89 又给 WEB /player 加 viaWebView 收割,该收割脆弱(依赖长活 WebView
    浏览器会话),一坏全堵。
  - **修复(单文件 +37 行,零新逻辑)**:resolve() 在 signatureTimestamp 后、WEB client loop 前插 NewPipe-first 块:
    ① buildSabrSessionFromNewPipe(SABR,复用现有 register+refreshPoToken[alpha.91 Fix A])→ return;
    ② buildDashFallbackFromNewPipe(alpha.92 自合成 DASH→dashMpdUrl[恒空]→HLS)→ return;
    ③ 全失败才落 WEB /player last resort(classic reload-closure/DASH n-decrypt)。
  - **验证待真机**:resolve 首行即 `NewPipe SABR harvest`(非 `viaWebView=true`),无 27s 卡顿;attestation 视频
    SABR 空 poToken → status=2 → PoTokenWebView 重铸 → 起播,无 RELOAD;≤1080p 无回归;若 SABR 仍 RELOAD 落自合成 DASH。
  - **不做/留后续**:不修 WebView harvest 本体、不移动复用块(缺 durationMs,留后续给 Registry Entry 加)。
- [x] **alpha.94:修 alpha.89 回归——isOnYoutube 只认 www,误杀 m.youtube.com 重定向 → 共享 WebView 死循环**(2026-08-17)
  - **真机坐实(alpha.25 APK)**:YouTube 本身可达(RSS 通、BiliWarmup 通),但 **feed 全挂**——所有频道 InnerTube
    `/browse` 在 4s 预算内超时(`YoutubeFeed: InnerTube failed … Timed out waiting for 4000 ms`),同时播放器
    仍 `viaWebView failed` 27s 卡死。用户:同机 YouTube 可达、原版可达 → 非网络,是回归。
  - **真根因(推翻 alpha.93 的"WebView 收割脆弱"误读)**:YoutubeBrowserSession 用 **MobileUserAgent** 加载
    `www.youtube.com/`,被重定向到 `https://m.youtube.com/`;alpha.89 加的 `isOnYoutube` **只认
    `https://www.youtube.com`** → m.youtube.com 页加载成功也被判"不在 youtube.com" → `onPageFinished` 标
    loadFailed、deferred 不完成 → 15s 超时 → 销毁重建 → 重定向又回 m → **每次 ensureLoaded 烧 2×15s 死循环**。
    该 WebView 是**单例共享**(AppContainer L103-108),feed 与播放器都依赖:
    - **feed**:ensureRealSessionData 每次 InnerTube 请求调 browserSession.ensureLoaded(sw.js_data 也失败、
      realSessionData 恒空不缓存)→ 4 频道在 sessionMutex 排队做 30s 循环 → 超 4s 预算 → 动态空白。
    - **播放器**:fetchViaWebView→ensureLoaded 烧 30s → "viaWebView failed"。alpha.93(NewPipe-first)绕开
      它才绿,但 feed 没绕开 → 真机 feed 挂。
  - **alpha.89 初衷**(修 CORS origin=null 错误页)正确,但把合法 m.youtube.com 页也误杀了。www/m 都是真
    YouTube 页(移动 UA 重定向正常,预 alpha.89 就停在 m),只应拒 chrome-error:// 错误页。
  - **修复(2 文件,纯判空/缓存逻辑)**:
    - [YoutubeBrowserSession.kt](../app/src/main/java/com/kirin/mt/core/youtube/YoutubeBrowserSession.kt) `isOnYoutube`
      改为同时认 `https://www.youtube.com` 与 `https://m.youtube.com`(fetchViaWebView 同函数,恢复预 alpha.89
      的 m→www 跨源 fetch,YouTube 返 ACAO 本就可跨源)。
    - [InnerTubeClient.kt](../app/src/main/java/com/kirin/mt/core/youtube/InnerTubeClient.kt) `ensureRealSessionData`
      sw.js_data 失败时**不再丢弃 browserVisitor**,用真实浏览器会话 visitorData 兜底缓存(否则每次请求重跑慢
      引导 + 合成 visitorData 打 /browse 被限流)。
  - **验证待真机**:feed 正常加载(RSS+InnerTube 合并出视频,不再 4s 超时)、无 `browser session not on
    youtube.com` 死循环、播放器 viaWebView 不再 27s 卡死(alpha.93 NewPipe-first 本已绕开,此修顺带救回 WEB
    last-resort)。
- [x] **alpha.96:feed 冷启动超时根因再修——非 /player 请求不强制浏览器会话引导**(2026-08-17)
  - **真机坐实(alpha.95 debug APK)**:不崩了(alpha.95 启动修复生效),但 feed 仍挂——所有频道 `/browse` 4s
    预算内超时(`Timed out waiting for 4000 ms`),搜索正常、播放仍不行。
  - **真根因(alpha.94 只修了一半)**:alpha.94 修好了 `isOnYoutube` 死循环(浏览器会话 10:48:27.080 一次成功),
    但 `ensureRealSessionData` 仍**无条件** `browserSession.ensureLoaded()`(慢的 WebView 引导 ~1.3s)。feed 冷启动
    第一个 `/browse` 串行等 WebView 引导 + sw.js_data + readCookies,4 频道全堵在同一个 sessionMutex,整条
    coroutineScope 被外层 `youtubeFeedTimeoutMs`(5 频道=4000ms)取消 → 动态空白。搜索能出是因浏览器会话已热。
  - **修复(单文件)**:`ensureRealSessionData` 加 `requireBrowserSession` 参数。feed 的 `/browse`/`/search` 只需
    visitorData,走快的 sw.js_data(或已热浏览器 visitor)即可,不强制 WebView 引导;仅 `/player`(铸 PO token / 走
    WebView)与 reload/BotGuard 传 `true`。`postJson` 按 `viaWebView` 传。
  - **验证待真机**:动态正常加载(RSS+InnerTube 合并,不再 4s 超时)、播放不受影响(播放器路径仍强制引导)。
- [x] **alpha.97:SABR 死循环守卫——attestation 视频 RELOAD 后直接落 ≤1080p DASH/HLS 兜底**(2026-08-17,对照 LibreTube)
  - **真机坐实(alpha.95/96 APK)**:visionOS NewPipe SABR 会话建起(itag313 4K / itag139),每段请求
    RELOAD_PLAYER_RESPONSE,`reloadCount` 17→24 无界爬升,最终 `evict sid` → `ExoPlayerImplInternal: Source error`。
  - **真根因(两层,对照 LibreTube 逐行)**:① 视频 attestation-gated——visionOS 空 poToken + 未 attested config
    被服务端直接 RELOAD(不给 status=2);LibreTube 同样崩(`SabrClient.processPart` RELOAD 致命抛,不循环)。
    ② **alpha.93 NewPipe-first 早退回归**——`resolve()` L151-173 对非 null SABR 立即 return,使真正消费 reload
    token 的 `consumeReloadTokenSlot` + `MAX_RELOADS=3` 闸门**永远不可达** → reloadCount 无界递增(比 alpha.86
    文档 §4.2 所述「MAX_RELOADS=3 耗尽」更糟,闸门已失效)。每次 error-retry 重进又建同一空 poToken 会话 → 死循环。
  - **修复(单点守卫,对齐 LibreTube 不循环)**:`resolve()` NewPipe-first 块建 SABR 前查
    `SabrStreamRegistry.reloadCount(videoId)>0`;RELOAD 后跳过空 poToken 会话重建,直接落
    `buildDashFallbackFromNewPipe`(自合成 DASH→HLS,≤1080p)。循环收敛到 1 次 SABR 尝试。
  - **不做**(用户已接受 ≤1080p):不重启 4K 直连;保留 WEB reload-closure/`buildSabrSessionFromReloadPlayer` 作
    退化场景 WEB-attested 最后手段(主守卫已收敛,不误伤非 attestation 视频——其 reloadCount 恒 0)。
  - **验证待真机**:attestation 视频日志出现一次 `SABR dead-loop guard` + `DASH/HLS 兜底 playback ready`,
    reloadCount 不再 17→24;SABR 可播视频回归无 RELOAD、不走兜底。
- [x] **alpha.98:DASH 切清晰度播放失败——自合成 MPD 多 codec 变体(两个独立失败点,都要修)**(2026-08-17,对照 LibreTube)
  - **真机坐实(logs_live.log,0bgp9jdth7w)**:切到 1080p 后 MPD 有 2 轨(VP9+AVC),ExoPlayer 段边界(~9.6s)
    自动切 codec 时 `InitializationChunk.load` → `FragmentedMp4Extractor.readAtomPayload` → `java.io.EOFException`,
    每次重试都在 pos=9644ms 确定性失败。
  - **失败点①:重复 Representation id**。`buildDashFallbackFromNewPipe` 的 `buildVideoTrack` 所有视频轨 `id=0`
    → MPD 里多个 `<Representation id="0_0">` **重复 ID**。alpha.97 把手动选档从「单轨」改成「该分辨率全部
    codec 变体」后,1080p 两轨都叫 `0_0`,ExoPlayer 切轨时加载错 init 段 → EOF。alpha.97 之前单轨无重复 →
    正常;SABR 路径一直用 `id=videoFmt.itag`(唯一)所以没这问题。**修复**:`buildVideoTrack` 改 `id=v.itag`。
  - **失败点②(alpha.98 后暴露的下一失败点):混合容器塞同一 AdaptationSet**。id 修复后日志仍 EOF,但这次是
    **初始 init 段加载就 EOF,不是段边界切 codec**。真机坐实:能播 VP9 轨(`videoFmt=Format(0_243,...,video/webm,
    vp9)`),但切到 H264(MP4)轨 init 段就 EOF。根因:`buildDashManifest` 把**全部视频轨(H264 MP4 + VP9 WebM
    混合)塞进一个 `<AdaptationSet>`**,其 `mimeType` 取第一条轨(VP9→`video/webm`)。ExoPlayer 按 AdaptationSet
    mimeType 选**单一 extractor**——要加载 H264(MP4)轨 init 段时用 MatroskaExtractor 解析 MP4 → EOF。
  - **对照 LibreTube**:`DashHelper.createManifest` 按 `stream.mimeType` **分组建独立 AdaptationSet**(H264 MP4 /
    VP9 WebM 各一个),每个 AdaptationSet 有自己的 mimeType,ExoPlayer 就能给每条轨选对 extractor;
    `createVideoRepresentation` **不给 `<Representation>` 设 `id`**,ExoPlayer 自动分配唯一 ID。
  - **修复**:①`buildVideoTrack` 改 `id=v.itag`;②`buildDashManifest` 视频轨按 `mimeType` 分组到独立
    AdaptationSet(id 0,1,2…),audio 单独一个(id=videoGroups.size)。
  - **验证待真机**:切清晰度后不再 EOF,段边界 codec 切换正常;VP9/H264 轨 init 段各用正确 extractor 加载。
- [x] **alpha.96.1:第一次加载空白根因——多频道并发批次预算不足**(2026-08-17)
  - **真机坐实(alpha.28 APK)**:修复生效(动态能加载了),但**第一次自动加载空白、下拉刷新才出**。
  - **根因**:频道数 6 > InnerTube 并发上限 4 → 分 2 批。`youtubeFeedTimeoutMs(6)=ceil(6/4)=2批×1000+2000=4000ms`,
    **每批只给 1s**。冷启动第一批(含一次性浏览器会话建立 27.6→30.3)实测 ~3.5s,烧掉大半预算;第二批 2 频道
    还在 semaphore 排队就被外层 4000ms 取消 → 整条 coroutineScope 取消 → `result==null` → 空白 + 提示。
    刷新时 session 已热秒回。
  - **修复**:`youtubeFeedTimeoutMs` 每批预算 1s→3s(6 频道=2 批→8s,上限 10s 不变),让多频道能分完批次。
  - **验证待真机**:第一次自动加载即出(不再需下拉刷新)。
- [x] **alpha.98:关注流改分批增量拉取 + Room 逐频道缓存(几百频道可扩展,对齐 LibreTube LocalFeedRepository)**(2026-08-17)
  - **背景**:alpha.96.1 的"动态超时预算"模型在几百频道下结构性必失败——`youtubeFeedTimeoutMs` 上限 10s,
    几百频道(每频道 RSS+InnerTube)不可能在预算内完成,整批返回 null → 空白。用户要求"后续几百频道也正常"。
  - **参考 LibreTube**:`LocalFeedRepository.refreshFeed` = Room(SQLite)逐频道缓存 + `channelIds.chunked(5)`
    分批并发 + 每批写完 DB + 进度回调 + **无外层全局超时**(每频道独立 runCatching,慢的只丢自身);
    每累计 50 频道 `delay(500..1500ms)` 防节流。
  - **LibreTube 关键理解(2026-08-17 核源码,修正"进度回调=增量 merge"的误读)**:
    `LocalFeedRepository.getFeed`([LocalFeedRepository.kt:48-73](e:/GITHUB/LibreTube/app/src/main/java/com/github/libretube/repo/LocalFeedRepository.kt#L48-L73))
    的 `onProgressUpdate` **只驱动进度条,不 merge 数据**——`refreshFeed` 逐批拉取**写进 DB**,每批回调
    `onProgressUpdate` → 仅更新 `feedProgress` LiveData → UI 显示 "current/total" 进度条
    ([SubscriptionsViewModel.kt:35-47](e:/GITHUB/LibreTube/app/src/main/java/com/github/libretube/ui/models/SubscriptionsViewModel.kt#L35-L47))。
    **数据不增量 merge**:等 `getFeed` 返回**完整 `List<StreamItem>`** 后一次性 `videoFeed.postValue(videoFeed)`
    替换整个列表;数据一致性由 DB 保证(逐批写 DB,最后 `getAll()` 读全量)。故 LibreTube **没有"把一批
    merge 进已有列表"的操作**,一次性替换完整列表,天然无后批覆盖前批问题。
  - **我们的差异与二次覆盖修复**:我们把 `onChunkReady` 当"增量 merge 到 UI"通道(拉到一批显示一批),引入
    merge 语义——`mergeYoutube` 内部 `filterNot` 掉 state 里所有旧 YouTube 再合并传入列表,原传**单批 chunk**
    致后批覆盖前批(动态先后出现、二次覆盖)。修复:onChunkReady 先 `accumulator += chunk` 再
    `mergeYoutube(accumulator)`(累积全量),每批基于完整已拉列表合并,不丢不覆盖。若想完全对齐 LibreTube
    (进度条 + 一次性替换),onChunkReady 只更新进度提示、UI 等全量返回后一次性 setState——当前保留增量 merge
    体验,已修覆盖。
  - **改动**:
    ① **引入 Room+KSP**(Room 2.8.0 + KSP2 2.3.11):`YoutubeFeedEntity`(channelId PK, videosJson, fetchedAt)
      + `YoutubeFeedDao`(逐频道 upsert / getAll / deleteChannelsNotIn) + `FeedDatabase`。
      `YoutubeFeedCacheStore` 底层从 DataStore 单 key 全量 JSON 换 Room 逐频道行(几百频道增量写只写自身行,
      不再每次全量序列化进 prefs),对外接口兼容,新增 `writeChannel` 单频道增量写。
    ② **`getSubscriptionsFeed` 分批增量**:`channels.chunked(5)` 分批并发,每批就绪回调 `onChunkReady`
      (调用方拉到一批 merge 一批),仍返回全量(缓存写);删 `youtubeFeedTimeoutMs` 外层全局超时。
    ③ 调用方:动态 tab + TV **增量 merge**(onChunkReady 逐批 + 每批 writeChannel 写缓存);
      home **只放宽超时**(per-channel 容错,一次性消费全量)。
  - **关键编译坑**:`youtubeSubscriptionsFeed` 加 `onChunkReady` 尾参后,原尾随 lambda 绑定错位到
    onChunkReady(须改具名 onChannelAvatarResolved);`awaitAll` import 勿删(批内仍用);onChunkReady 非挂起
    回调不能直接调 suspend writeChannel(用 scope.launch 包);`return@onChunkReady` 非隐式标签(改 if 块)。
  - **验证待真机**:几百频道模拟(设置加大量频道),动态/TV 拉到一批显示一批不空白、无整批超时;home 无缓存
    出部分结果不整块 Failed;缓存跨启动秒出(进动态先秒出上次流再增量刷新);搜索/播放不回归。