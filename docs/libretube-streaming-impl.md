# LibreTube 取流实现细节(YouTube SABR/UMP)

> 独立记录 LibreTube(`E:\GITHUB\LibreTube`,current)的 YouTube **取流**(streaming)实现细节。
> 纯代码层调查,不编译/不推送/不改 BiliTVNative 代码。
>
> 调查日期:2026-08-17。方法:派 Explore 子代理摸代码分布 + 逐文件读源码核对,
> 所有结论附 `file:line`,均经源码核读(核心文件 SabrClient / PoTokenGenerator / PoTokenWebView /
> OnlinePlayerService / DefaultSabrChunkSource / UmpParser / SabrDataSource / SabrMediaSource /
> SabrMediaPeriod / SabrManifest / Representation / Xtags 全文读过)。
>
> NewPipeExtractor fork 版本:`com.github.libre-tube:NewPipeExtractor` `738c3d4`。
> **n-decrypt 无本地实现**,完全依赖 NewPipeExtractor 库内部(见 §10)。

---

## 0. TL;DR — 取流链路一句话

```
/player (NewPipe fork,visionOS)
  → getStreams 拿到 serverAbrStreamingUrl + ustreamerConfig + 视频/音频流
  → setStreamSource 按可用性选分支: SABR > DASH > HLS
  → SABR: SabrMediaSource(media3) → SabrClient 驱动 UMP 拉取 → processPart 分发
  → 播放数据经 SabrDataSource 喂给 ExoPlayer chunk 队列
```

LibreTube 的 YouTube 播放默认走 **visionOS 客户端的原生 SABR(Server-Aided ABR)/ UMP** 协议,
`media3` 播放栈是 `SabrMediaSource` 体系。非 SABR 场景(直播、无 ustreamerConfig)回落到
**DASH**(本地拼 MPD)与 **HLS** 兜底,二者都靠 NewPipeExtractor 内置 n-decrypt。

---

## 1. 取流链路总览

```
┌──────────────────────────────────────────────────────────────────────┐
│ 阶段一  取流元数据                                                    │
│   NewPipeMediaServiceRepository.getStreams (L287-366)                 │
│   StreamInfo.getInfo(visionOS /player)                                │
│   → serverAbrStreamingUrl / ustreamerConfig / videoOnlyStreams /      │
│     audioStreams / hlsUrl / dashMpdUrl / subtitles                    │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ Streams 模型
┌───────────────────────────────▼──────────────────────────────────────┐
│ 阶段二  poToken 铸取(惰性,status=2 时才真正需要)                      │
│   PoTokenGenerator.getWebClientPoToken                                │
│   → PoTokenWebView(WebView 跑 BotGuard + jnn/v1 换 integrityToken)    │
│   getIosClientPoToken = null (visionOS 路径不带 poToken)              │
└───────────────────────────────┬──────────────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────────────┐
│ 阶段三  MediaSource 构建与播放分支(OnlinePlayerService.setStreamSource)│
│   SABR:  SabrMediaSource.Factory(SabrManifest) + MergingMediaSource   │
│   DASH:  PlayerHelper.createDashSource(本地 MPD) / 直播用 YT 原生 dash │
│   HLS:   HlsMediaSource + YoutubeHlsPlaylistParser                    │
│   无流:  toast unknown_error                                          │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ (SABR 分支)
┌───────────────────────────────▼──────────────────────────────────────┐
│ 阶段四  SABR 引擎 (SabrClient)                                        │
│   getNextSegment → media → fetchStreamData(拼 VideoPlaybackAbrRequest)│
│   → POST $url&rn=N → 响应 bytes → UmpParser.readPart 逐 Part           │
│   → processPart 分发: MEDIA_HEADER/MEDIA/MEDIA_END 攒段                │
│     FORMAT_INIT 建 InitializedFormat / NEXT_REQUEST_POLICY 更新 backoff│
│     STREAM_PROTECTION_STATUS status=2 刷新 poToken / RELOAD 致命抛     │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ 已下载段数据
┌───────────────────────────────▼──────────────────────────────────────┐
│ 阶段五  media3 播放栈                                                  │
│   DefaultSabrChunkSource.getNextChunk 决定下一段 → 构造 PlaybackRequest│
│   SabrDataSource.open 调 sabrClient.getNextSegment 取段 bytes          │
│   → ContainerMediaChunk 入 ExoPlayer chunk 队列解码播放                │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. 阶段一:取流元数据(/player via NewPipe fork)

**入口**:`NewPipeMediaServiceRepository.getStreams(videoId)`
(`app/src/main/java/com/github/libretube/api/NewPipeMediaServiceRepository.kt:287-366`)。

- 唯一入口,调用 NewPipeExtractor 的 `StreamInfo.getInfo("$YOUTUBE_FRONTEND_URL/watch?v=$videoId")`(L289)。
  **不碰 Piped**(现版 LibreTube 已从 Piped 迁到 NewPipe fork)。
- 映射到内部 `Streams` 模型的关键字段:
  - `serverAbrStreamingUrl = resp.serverAbrStreamingUrl`(L363)——SABR 流 URL
  - `videoPlaybackUstreamerConfig = resp.ustreamerConfig`(L364)——Base64 的 UStreamer 配置
  - `hls = resp.hlsUrl` / `dash = resp.dashMpdUrl`(L311-312)
  - `audioStreams = resp.audioStreams.map { it.toPipedStream() }`(L337)
  - `videoStreams = resp.videoOnlyStreams.map { it.toPipedStream().copy(videoOnly=true) }`(L338)
  - `subtitles = resp.subtitles.map { ... }`(L351-359)
- 辅助映射 `toPipedStream()`:VideoStream 在 L57、AudioStream 在 L78。

**要点**:ustreamerConfig / serverAbrStreamingUrl 直接来自 NewPipe fork 的 **visionOS** /player 响应,
后续 SABR 请求体原样带上。`getIosClientPoToken` 返回 `null`(见 §3),所以这次 visionOS getInfo
请求**不带 poToken**。

---

## 3. 阶段二:poToken 铸取

**入口**:`PoTokenGenerator`(`app/src/main/java/com/github/libretube/api/poToken/PoTokenGenerator.kt`,类 L16),
实现 NewPipe 的 `PoTokenProvider` 接口。

| 方法 | 行为 | 位置 |
|---|---|---|
| `getCachedWebClientPoToken()` | 返回缓存的 poToken | L26 |
| `getWebClientPoToken(videoId)` | 铸 WEB token,成功后缓存 | L28-35 |
| `getWebClientPoToken(videoId, forceRecreate)` | 真正逻辑:拿 visitorData → 建 WebView → 铸 token;失败递归 forceRecreate | L42-108 |
| `getWebEmbedClientPoToken` | **null 桩** | L110 |
| `getAndroidClientPoToken` | **null 桩** | L112 |
| `getIosClientPoToken` | **null 桩** | L114 |

**铸 token 流程**(`getWebClientPoToken` L42-108):
1. 若需重建(`webPoTokenGenerator==null || forceRecreate || isExpired()`,L45),先 `getVisitorDataFromInnertube`
   拿 visitorData(L52-60),再在**主线程**新建 `PoTokenWebView`(L62-69)。
2. `poTokenGenerator.generatePoToken(videoId)` 执行 JS 铸取(L83-85)。
3. 失败且刚重建 → 抛;否则 `forceRecreate=true` 重试一次(L86-98)。
4. 返回 `PoTokenResult(visitorData, poToken, poToken)`(L107)。

**WebView BotGuard harness**:`PoTokenWebView`
(`app/src/main/java/com/github/libretube/api/poToken/PoTokenWebView.kt`,类 L24)。
- 配置:`javaScriptEnabled=true`、`blockNetworkLoads=true`(WebView 本身不联网,L42)、`safeBrowsingEnabled=false`。
- 初始化 `loadHtmlAndObtainBotguard`(L54-80):加载 `assets/po_token.html`,注入 JS 调 `downloadAndRunBotguard()`。
- `downloadAndRunBotguard`(L86-114):POST `youtube.com/api/jnn/v1/Create` 拿 challenge → `runBotGuard(data)`
  得 `botguardResponse`。
- `onRunBotguardResult`(L133-155):POST `jnn/v1/GenerateIT` 换 `integrityToken`,留 10 分钟余量记过期时间。
- `generatePoToken(identifier)`(L159-186):`obtainPoToken(webPoSignalOutput, integrityToken, identifier)`
  产出 poToken,u8 转 Base64。
- 工厂 `newPoTokenGenerator(context)`(L271-278)。

**关键**:LibreTube 的 visionOS 路径 poToken 为 null(§4 的 `SabrClient.init` 首请求也因
`getIosClientPoToken=null` 使缓存恒空 → 空 poToken)。只有 SABR 服务端下发 `status=2`
(Attestation pending)时才调 `getWebClientPoToken(videoId)` 现铸 WEB token(见 §8.2)。

---

## 4. 阶段三:MediaSource 构建与播放分支

**入口**:`OnlinePlayerService.setStreamSource()`
(`app/src/main/java/com/github/libretube/services/OnlinePlayerService.kt:232-328`)。分支优先级:

```kotlin
when {
    // ① SABR(排除直播,SABR impl 不支持直播)
    !isLive && serverAbrStreamingUrl != null && ustreamerConfig != null -> { ... }
    // ② DASH(直播用 YT 原生 dash,否则本地拼 MPD)
    streams.videoStreams.isNotEmpty() -> { ... }
    // ③ HLS 兜底
    streams.hls != null -> { ... }
    // ④ 无流
    else -> { toast unknown_error }
}
```

- **SABR 分支**(L238-291):`SabrMediaSource.Factory(SabrManifest(videoId, streams))` 建 `SabrMediaSource`;
  `MergingMediaSource` 把字幕源(`ProgressiveMediaSource` + `SubtitleExtractor`,反射开
  `enableLazyLoadingWithSingleTrack` 的私有方法,L265-285)合并进来。
- **DASH 分支**(L293-306):直播且 `streams.dash!=null` 用 YT 原生 dash URL;否则
  `PlayerHelper.createDashSource(streams, this)`(`PlayerHelper.kt:106-115`,用 `DashHelper.createManifest`
  本地拼 MPD 再 Base64 成 `data:` URI)。
- **HLS 分支**(L308-321):`HlsMediaSource` + `YoutubeHlsPlaylistParser.Factory`。

---

## 5. 阶段四:SABR 客户端核心(SabrClient)

**文件**:`app/src/main/java/com/github/libretube/player/parser/SabrClient.kt`。类定义 L149,
辅助构造 `SabrClient(manifest)` L171-175(取 `videoId` + `serverAbrStreamingUri` + 解码 `ustreamerConfig`)。

### 5.1 初始化

- `init`(L177-181):`poTokenGenerator.getCachedWebClientPoToken()` 预载 poToken——因 visionOS getInfo 期
  `getIosClientPoToken=null`,缓存恒空,首请求 `setPoToken(empty)`。
- OkHttp 客户端(L201-213):统一加 HTTP 头 `Content-Type: application/x-protobuf`、
  `Accept-Encoding: identity`、`Accept: application/vnd.yt-ump`、`Origin/Referer` 指向 youtube.com、
  `User-Agent` visionOS(L631)。
- 单线程调度:`Dispatchers.IO.limitedParallelism(1)`(L164),数据只能单线程访问。

### 5.2 拉取入口 `getNextSegment`(L288-328)

- 若 fatalError 已置,抛 `"SABR error: <type>"`(L289-291)。
- 同步 buffered segments:`initializedFormats[itag].bufferedSegments.keys.retainAll(playbackRequest.bufferedSegments)`(L305)。
- `runBlocking { withContext(dispatcher) }` 内:格式未初始化/无段 → `media(playbackRequest)` 拉新数据(L317),
  然后清掉非选中格式的旧段(`retainAll` 只留音频/视频当前格式,L322),最后 `getSegment`(L325)。

### 5.3 数据拉取 `media`(L336-346)+ `fetchStreamData`(L351-432)

`media` 循环读 `UmpParser.readPart()` 直到空,逐个 `processPart`。

`fetchStreamData`(L351-432):
1. **backoff 等待**(L356-360):`backoffTime` 非空先 `delay(backoff)`(服务端 `NextRequestPolicy` 下发)。
2. **构造 `ClientAbrState`**(L366-385):
   - `setPlayerTimeMs(playbackRequest.segmentStartTimeMs)`(L365)——**段起始时间**,非固定锚点
   - `setEnabledTrackTypesBitfield(if(videoFormat==null) 1 else 0)`(L369)
   - `setPlaybackRate` / `elapsedWallTimeMs` / `timeSinceLastSeek` / `timeSinceLastManualFormatSelectionMs` /
     `timeSinceLastActionMs`(L370-374)
   - `audioTrackId` / `drcEnabled`(读 Xtags)/ `enableVoiceBoost`(L375-377)
   - `bandwidthEstimate`(media3 带宽计)/ `stickyResolution=max(videoHeight,360)` / `clientViewportHeight/Width`(L379-383)
   - `setVisibility(1)`(L384)
3. **构造 `VideoPlaybackAbrRequest`**(L387-413):
   - `setClientAbrState` + `setPlayerTimeMs` + `setVideoPlaybackUstreamerConfig(ustreamerConfig)`(L387-389)
   - `addAllPreferredAudio/VideoFormatIds`(L390-391)
   - `addAllSelectedFormatIds = initializedFormats.values.map{it.id}`(L392)
   - `addAllBufferedRanges = initializedFormats.values.flatMap{it.buildBufferedRanges()}`(L393)
   - **`StreamerContext`**(L394-412):
     - `setPoToken(poToken ?: ByteString.empty())`(L396)
     - **`ClientInfo` 模拟 Apple visionOS**(L397-405):`clientName=101`/`clientVersion="1.02"`/
       `deviceMake="Apple"`/`deviceModel="RealityDevice14,1"`/`osName="visionOS"`/`osVersion="25.6.0.23O471"`
     - `addAllSabrContexts(active)` + `addAllUnsentSabrContexts`(L407-409)
     - `setPlaybackCookie`(L410)
4. **POST 请求**(L415-425):`url + "&rn=${requestNumber++}"`,body 是 proto bytes。注释:理想用 HTTP3,但
   okhttp 不支持,故 HTTP1.1。

### 5.4 请求常量

```
CONTENT_TYPE = "application/x-protobuf"
ENCODING     = "identity"
ACCEPT       = "application/vnd.yt-ump"
USER_AGENT   = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; GB)"
```

---

## 6. UMP 协议层

### 6.1 proto 定义

`app/src/main/proto/video_streaming/`(27 个)+ `misc/common.proto`。SABR 请求关键:
- `video_playback_abr_request.proto` —— 顶层 `VideoPlaybackAbrRequest`(client_abr_state / ustreamer_config /
  selected_format_ids / buffered_ranges / streamer_context / playback_cookie)
- `client_abr_state.proto` —— `ClientAbrState`
- `streamer_context.proto` —— `StreamerContext` + `StreamerContext.ClientInfo`
- `buffered_range.proto` / `format_initialization_metadata.proto` / `format_selection_config.proto`
- `media_header.proto` / `next_request_policy.proto` / `stream_protection_status.proto` /
  `reload_player_response.proto` / `sabr_error.proto` / `sabr_redirect.proto` /
  `sabr_context_update.proto` / `sabr_context_sending_policy.proto` / `ump_part_id.proto` /
  `request_identifier.proto` / `playback_cookie.proto` / `time_range.proto` / `crypto_params.proto`
- 其余:`live_metadata.proto` / `media_capabilities.proto` / `media_header.proto` /
  `playback_start_policy.proto` / `request_cancellation_policy.proto` / `sabr_seek.proto` /
  `snackbar_message.proto` / `innertube_request.proto`

`UMPPartId` / `UmpParser` 由这些 proto 编译生成;源码只引用枚举如 `UMPPartId.RELOAD_PLAYER_RESPONSE`。

### 6.2 UmpParser 解析格式

`app/src/main/java/com/github/libretube/player/parser/UmpParser.kt`。
- `readVarint()`(L64-90):UMP 变长整数——首字节高 5 位是长度前缀(1..5),低位是值首段,后续逐字节左移拼出。
- `readPart()`(L123-131):每个 Part = `[type varint][size varint][size bytes data]`,type 映射到 `UMPPartId`。
- `Part(type, data)`(L139-152)。

### 6.3 processPart 分发(SabrClient.kt:439-615)

| UMP Part | 处理 | 行号 |
|---|---|---|
| `MEDIA_HEADER` | 建 partial segment(校验 videoId、去重 seq) | L441-469 |
| `MEDIA` | 追加段数据 bytes | L471-480 |
| `MEDIA_END` | 校验 contentLength,段入 `downloadedSegments`;isInitSeg 设 initSegment | L482-503 |
| `NEXT_REQUEST_POLICY` | 更新 `backoffTime` + `playbackCookie` | L505-509 |
| `FORMAT_INITIALIZATION_METADATA` | 建 `InitializedFormat`(记 duration / endSegmentNumber) | L511-530 |
| `SABR_REDIRECT` | 改 `url` | L532-535 |
| `SABR_CONTEXT_UPDATE` | 存 sabrContext,`sendByDefault` 则 active | L537-552 |
| `SABR_CONTEXT_SENDING_POLICY` | 启/停/丢弃 sabrContext | L554-577 |
| `RELOAD_PLAYER_RESPONSE` | **致命抛** `"Server requested player reload"` | L579-584 |
| `STREAM_PROTECTION_STATUS` | status=2 刷新 poToken;status=3 抛 `"Attestation required"` | L586-601 |
| `SABR_ERROR` | 记 fatalError,抛错 | L603-608 |
| 其它 | warn 忽略 | L610-612 |

### 6.4 段结构

- `Segment`(L72-86):header(MediaHeader)+ sequenceNumber + data(MutableList<ByteArray>)+ duration。
- `InitializedFormat`(L89-141):
  - `getSegment(seq)`(L108-115):从 `downloadedSegments` 取出,复制空 data 放 `bufferedSegments` 标记已缓冲。
  - `buildBufferedRanges()`(L118-134):`bufferedSegments ∪ downloadedSegments` 按 seq 排序,折叠连续区段为
    `BufferedRange(formatId, startMs, durationMs, startSegmentIndex, endSegmentIndex)`。**真实增长,无假满缓冲**。
  - `hasSegment(seq)`(L139-140)。

---

## 7. 阶段五:media3 播放栈

### 7.1 SabrMediaSource(`player/SabrMediaSource.kt`,L31)

- 作用:把 SABR manifest 暴露为单 Period 的 media3 `MediaSource`,注入 chunk source factory。
- `Factory`(L47):`createMediaSource`(L73-90)new 一个 `SabrClient(manifest)` + `SabrDataSource.Factory(sabrClient)` +
  `DefaultSabrChunkSource.Factory`。`getSupportedTypes` 返回 `C.CONTENT_TYPE_OTHER`(L90)。
- `createPeriod`(L127-154)建 `SabrMediaPeriod`;`processManifest`(L165-178)用内部 `SabrTimeline`
  (L180-241,单 window/单 period)refreshSourceInfo。

### 7.2 SabrMediaPeriod(`player/SabrMediaPeriod.kt`,L37)

- `buildTrackGroups`(L309):把 manifest 的 adaptation sets 组装成 `TrackGroup`s,按 trackType/DRM cryptoType 分组。
- `selectTracks`(L123-161):选轨;同时把 `lastManualFormatSelectionMs`/`lastActionMs` 记为 now(告知服务端切格式时间,L136-139)。
- `buildSampleStream`(L268-302):创建 `ChunkSampleStream` + `SabrChunkSource`。
- `getAdjustedSeekPositionUs`(L198-205)委托给视频 stream。
- `getStreamKeys`(L86-121):stream key 映射。

### 7.3 SabrChunkSource(`player/SabrChunkSource.kt`,L16)+ DefaultSabrChunkSource(`player/DefaultSabrChunkSource.kt`,L39)

- `Factory`(L51):`createSabrChunkSource`(L56-79)。
- `init`(L107-126):把 adaptation set 的 representations 映射成 `RepresentationHolder`s(每 track 一个,
  含 `ChunkExtractor`)。
- `getAdjustedSeekPositionUs`(L128-149):seek 时 `sabrClient.lastSeekMs = now` 告知服务端,并用
  RepresentationHolder 的 segment 时间轴对齐 seek 位置。
- `getNextChunk`(L180-316,核心):
  - `sabrClient.selectFormat(representationHolder.representation)`(L218)在每次选轨时手动定格式
    ("all format selections are manual, as we do not let the server decide",注释 L248-250)。
  - 首次请求该格式 → `InitializationChunk`(L242-250,PlaybackRequest.initRequest)。
  - 否则构造 `PlaybackRequest`(L287-299):`formatId` / playerPosition / playbackSpeed /
    `segment = segmentNum+1`(chunk index 不算 init 段)/ `segmentStartTimeMs = startTimeUs`(L296)/
    `bufferedSegments = queue.map{ (customData as PlaybackRequest).segment }`(L286,**从 ExoPlayer chunk 队列来**)。
  - 产出 `ContainerMediaChunk`(L301-315)入队。
- `RepresentationHolder`(L439-458):`chunkIndex`(ChunkIndex)驱动的段时间轴:`getSegmentNum`/`getSegmentStartTimeUs`/
  `getLastAvailableSegmentNum` 等。
- `onChunkLoadCompleted`(L318-331):init chunk 加载完把 `chunkIndex` 从 extractor 拿回填。
- `onChunkLoadError`(L333-380):404 缺尾段 workaround + track 排除 fallback。

### 7.4 SabrDataSource(`player/SabrDataSource.kt`,L17)

- `Factory(sabrClient)`(L22-26)。
- `open`(L28-45):`dataSpec.customData` 取 `PlaybackRequest`,调 `sabrClient.getNextSegment(playbackRequest)`
  取段数据;异常 → 抛 `IOException`。
- `read`(L60-81):从 `CompositeBuffer` 读 bytes;末尾返回 `C.RESULT_END_OF_INPUT`。
- `getUri`(L47-53):无剩余数据返回 null(标记打开失败)。
- **错误处理**:`open` 里 `sabrClient.getNextSegment` 抛出的致命错误(RELOAD / SABR_ERROR / Attestation required)
  在此被捕获转 `IOException`,向上传导给 media3 播放器。

---

## 8. 关键机制细节

### 8.1 buffered ranges / own range

- `InitializedFormat.buildBufferedRanges()`(SabrClient.kt:118-134):并 `bufferedSegments` + `downloadedSegments`,
  按 seq 排序折叠成连续 `BufferedRange`,随每次请求 `addAllBufferedRanges`(L393)上报。
- **无 `Int.MAX` 假满缓冲**——own range 真实增长。
- 消费段追踪:`getSegment`(L108-115)把下载段移出 `downloadedSegments`、放**空 data marker** 进 `bufferedSegments`。
- 与播放器缓冲同步:DefaultSabrChunkSource.kt:286 从 ExoPlayer `queue` 抽 `PlaybackRequest.segment` 列表 → 上报;
  `getNextSegment` L305 `retainAll` 回写。

### 8.2 status=2 PO token 刷新(同步)

- 触发:`processPart` 收到 `STREAM_PROTECTION_STATUS` status=2(Attestation pending)→ `poToken = generatePoToken()`(L591-595)。
- `generatePoToken()`(L621-624):调 `poTokenGenerator.getWebClientPoToken(videoId)`(NewPipe 栈 WebView)铸新 token。
- **同步**:在 `processPart` 内执行(在 `runBlocking{withContext(dispatcher)}` 内,SabrClient.kt:307-327),
  刷新完才发下一请求,避免竞态。
- status=3:假设已发过 status=2 但 token 不被接受 → 抛 `"Attestation required"`(L598)。

### 8.3 RELOAD_PLAYER(致命)

- `processPart` 收到 `RELOAD_PLAYER_RESPONSE` → **直接抛** `Exception("Server requested player reload")`(L579-584)。
  注释明言:"the first one [streams expired] is a rare edge-case and the second one [new config feature]
  cannot be handled"——即**无恢复机制**,直接崩溃给播放器。
- 传导路径:`processPart` 抛 → `media`(L336)抛 → `getNextSegment`(runBlocking,L307)抛 → `SabrDataSource.open`
  (L33-41)捕获转 IOException → media3 `LoadErrorHandlingPolicy`。

### 8.4 seek / 缓冲同步

- seek:`DefaultSabrChunkSource.getAdjustedSeekPositionUs`(L128-149)记 `lastSeekMs` 并沿段时间轴对齐;
  `SabrMediaPeriod.seekToUs`(L193-196)委托各 sample stream。
- `PlaybackRequest`(SabrClient.kt:43-64):`initRequest`(L58-63)建首段请求;含 format / playerPosition /
  playbackSpeed / segment / segmentStartTimeMs / bufferedSegments。
- `playerTimeMs` 语义 = `segmentStartTimeMs`(段起始),随段涨,非固定 0 锚点。

---

## 9. 兜底分支(DASH / HLS)

| 分支 | 实现 | 位置 |
|---|---|---|
| DASH(直播) | `streams.dash` 直接用(经 ProxyHelper 代理) | OnlinePlayerService.kt:296-300 |
| DASH(非直播) | `PlayerHelper.createDashSource` 本地拼 MPD 成 `data:` URI | PlayerHelper.kt:106-115 |
| HLS | `HlsMediaSource` + `YoutubeHlsPlaylistParser` | OnlinePlayerService.kt:308-321 |

`PlayerHelper` 相关:`createPlayer`(L482-501,ExoPlayer 构建,`.setLoadControl(getLoadControl())` L495)、
`getLoadControl`(L507)、`getDefaultResolution`(L376)、`createRendererFactory`(L462)、`loadPlaybackParams`(L524)。

---

## 10. n-decrypt

**LibreTube 无本地 n-decrypt 实现**。n/sig 解密发生在 **NewPipeExtractor 外部依赖库**
(`org.schabi.newpipe:newpipeextractor`)内部,通过 `StreamInfo.getInfo()`(NewPipeMediaServiceRepository.kt:289)
隐式触发。仓库源码无 decrypt / n_function 相关代码。要看 n-decrypt 需读该 gradle 依赖 jar。

---

## 11. 下载复用(同一 SabrClient)

`app/src/main/java/com/github/libretube/repo/SabrDownloadProvider.kt`:
- `SabrDownloaderHandle(sabrClient, ...)`(L17)。
- `SabrDownloadProvider`(L26),构造 `SabrClient(sabrManifest)`(L35);`downloadNextChunk()`(override,L43)。
- 下载复用同一 `SabrClient`,按段拉取并落盘。

---

## 12. 文件清单

| 角色 | 文件 |
|---|---|
| 取流元数据 | `api/NewPipeMediaServiceRepository.kt`(getStreams L287-366,toPipedStream L57/78) |
| SABR 引擎核心 | `player/parser/SabrClient.kt`(类 L149,fetchStreamData L351-432,processPart L439-615,buildBufferedRanges L118-134,RELOAD L579-584,status=2 L586-601) |
| UMP 解析 | `player/parser/UmpParser.kt`(readPart L123-131)/ `CompositeBuffer.kt` / `Xtags.kt` |
| media3 栈 | `player/SabrMediaSource.kt`(L31)/ `SabrMediaPeriod.kt`(L37)/ `SabrChunkSource.kt`(L16)/ `DefaultSabrChunkSource.kt`(L39)/ `SabrDataSource.kt`(L17) |
| manifest | `player/manifest/SabrManifest.kt`(L16)/ `Representation.kt`(L16)/ `AdaptationSet.kt` |
| proto | `app/src/main/proto/video_streaming/*.proto`(27 个)+ `misc/common.proto` |
| poToken | `api/poToken/PoTokenGenerator.kt`(L16,getIosClientPoToken=null L114)/ `PoTokenWebView.kt`(L24)/ `JavaScriptUtil.kt` |
| 播放服务编排 | `services/OnlinePlayerService.kt`(setStreamSource L232-328) |
| 播放器配置 | `helpers/PlayerHelper.kt`(createPlayer L482-501,getDefaultResolution L376,getLoadControl L507) |
| 下载复用 | `repo/SabrDownloadProvider.kt` |
| NewPipe fork | `gradle/libs.versions.toml`(`newpipeextractor=738c3d4`) |

---

## 13. 关键代码路径速查

- 取流入口:`NewPipeMediaServiceRepository.getStreams`(NewPipeMediaServiceRepository.kt:287-366)
- SABR 分支判定:`OnlinePlayerService.setStreamSource`(OnlinePlayerService.kt:238-291)
- MediaSource 构建:`SabrMediaSource.Factory.createMediaSource`(SabrMediaSource.kt:73-90)
- SABR 请求构建:`SabrClient.fetchStreamData`(SabrClient.kt:351-432)
- visionOS ClientInfo 模拟:`SabrClient`(SabrClient.kt:397-405)
- buffered ranges:`SabrClient.InitializedFormat.buildBufferedRanges`(SabrClient.kt:118-134)
- 段拉取驱动:`SabrClient.getNextSegment`(SabrClient.kt:288-328)
- 缓冲同步(从 ExoPlayer queue):`DefaultSabrChunkSource.getNextChunk`(DefaultSabrChunkSource.kt:286)
- 段数据喂播放器:`SabrDataSource.open`(SabrDataSource.kt:28-45)
- status=2 刷新:`SabrClient.processPart`(SabrClient.kt:586-601)+ `generatePoToken`(L621-624)
- RELOAD(致命):`SabrClient.processPart`(SabrClient.kt:579-584)
- poToken 铸取:`PoTokenGenerator.getWebClientPoToken`(PoTokenGenerator.kt:28-108)+ `PoTokenWebView`
- poToken 空(首请求):`SabrClient.init`(SabrClient.kt:177-181)+ `PoTokenGenerator.getIosClientPoToken=null`(L114)
