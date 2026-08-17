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