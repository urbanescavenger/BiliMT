# IPTV 纳入直播 —— 可行性研究

> 状态:**一期已实现并云编译通过**(TV 版)。样例:`tmp/result.m3u`(fanmingming/live 类公开源,408 频道)。alpha.29 修:裸 IP 源明文放行 + 截帧器补重试策略(见下「已知坑」)。

## 结论

**高度可行,且比预期省事。** IPTV 的本质(一堆频道,每个频道一个 m3u8/HLS 流地址)与现有直播播放路径几乎一一对应。核心播放器已能直接播 IPTV,主要工作量在"频道列表来源 + 入口 UI",不在播放本身。

## 现有直播链路(代码依据)

```
LiveScreen (TV 列表)
  → LiveRepository (B站 API: getLiveList / getAreaList / getLiveListByArea)
  → LiveRoom.toVideoSummary()  → VideoSummary(liveRoomId>0)   [PlayerLaunchSupport.kt:54]
  → VideoSummary.toPlaybackRequest() → PlaybackRequest(liveRoomId)  [AppShell.kt:412]
  → request.isLive → LivePlayerScreen  [AppShell.kt:1206]
  → playbackRepository.getLivePlayInfo(roomId, qn)  [PlaybackRepository.kt:159]
  → LivePlayInfo(streamUrl, isHls, qualities, headers)  [PlaybackModels.kt:68]
  → ExoPlayer + HlsMediaSource(m3u8) / ProgressiveMediaSource(FLV)  [LivePlayerScreen.kt:246]
```

**关键点**:播放器已用 `HlsMediaSource` 播 m3u8。IPTV 频道几乎全是 m3u8。`LivePlayInfo` 的 `streamUrl + isHls` 就是 IPTV 频道的完整描述。

## 样例实测(`tmp/result.m3u`)

- **408 频道 / 817 行**(约 2 行/频道:EXTINF + URL,部分频道多个镜像)。
- **全部 HLS**:`index.m3u8`(118)、`1080p.m3u8`(29)、`720p.m3u8`(23)、`playlist.m3u8`(20)、目录式无扩展名 HLS(12)、带 `?zzhed`/`?key=txiptv` query 的 m3u8。**裸 `.ts` 直链 0 个** → ExoPlayer `HlsMediaSource` 直接吃。
- **唯一例外:3 个 `rtmp://`**(~0.7%)。ExoPlayer 不支持 RTMP,解析时过滤。
- **分组天然**:`group-title` = `📺央视频道`(51)/`📡卫视频道`(71)/`☘️浙江频道`(82)/`☘️河南频道`(47)... 与现有 Live 分区 tab 结构同构。
- **缩略图 100% 覆盖**:408/408 频道都有 `tvg-logo`(githubusercontent png)→ 映射 `VideoSummary.pic`。
- **EPG 可做(二期)**:第 1 行 `x-tvg-url` 声明 EPG 源,每频道 `tvg-id` 作关联键。

## 解析器要处理的坑(样例实测)

1. **重复频道**:同名频道多个镜像 URL(如 CCTV-7 有 6 个)→ **合并成一个频道,URL 列表作"切换源"**(见下)。
2. **伪频道**:`group-title="🕘️更新时间"` 的 `2026-08-11 03:13:08` 是元数据非真频道,跳过。
3. **query 串必须保留**:`?zzhed`、`?key=txiptv&playlive=1&authid=0` 是取流参数,不能 strip。
4. **rtmp 过滤**:3 个 `rtmp://` 直接丢弃。

## 设计:重复频道合并 + 切换源

**核心洞察**:播放器现成的"清晰度面板"机制天然就是"源切换"面板,零新 UI。

- `selectedQn`(Int)→ 直接当**源索引**用。
- `qualities: List<LiveQuality>` → 合成 `[线路1, 线路2, ...]`。
- `openQualityPanel` / `LiveQualityPanel` / `onPick` → 已建好,不用动。
- `LaunchedEffect(roomId, selectedQn, ...)` → 切源自动重载,已建好。

### 数据模型

解析器(`IptvRepository`)按频道名/tvg-id 分组,**合并**镜像 URL(非去重丢弃):

```kotlin
data class IptvChannel(
  val name: String, val logo: String, val group: String,
  val urls: List<String>,   // 合并后的镜像源列表
)
```

`VideoSummary` 加字段(带默认值,沿用 `liveRoomId` 模式):
```kotlin
val source: String = SourceBili,   // 新增 SourceIptv = "iptv"
val iptvUrls: List<String> = emptyList(),
```
`pic` 已承载 logo,`title` 承载频道名。`toPlaybackRequest()` 加 `source == SourceIptv` 分支。

### 播放:合成 LivePlayInfo + 裸数据源

`LivePlayerScreen` 加载逻辑加 IPTV 分支(替代 `getLivePlayInfo`):
```kotlin
val info = if (request.isIptv) {
  val urls = request.iptvUrls
  val idx = selectedQn.coerceIn(0, urls.lastIndex)
  LivePlayInfo(
    roomId = 0,
    streamUrl = urls[idx],
    isHls = true,                       // 样例全 HLS
    currentQn = idx,
    qualities = urls.mapIndexed { i, _ -> LiveQuality(i, "线路${i+1}") },
    headers = BiliPlaybackHeaders(),    // 空头
  )
} else {
  playbackRepository.getLivePlayInfo(roomId, selectedQn)
}
```

**数据源要换**:IPTV 不能走 `BiliMediaDataSourceFactory`(强制套 B站 UA + headers,[BiliMediaDataSourceFactory.kt:13])。IPTV 分支用裸 `DefaultDataSource.Factory(context)`。

### 3 个要 guard 的点

1. **`persistQuality` 跳过 IPTV**:`LiveQualityPreferenceStore` 是全局单值,但源索引是**每频道**的(频道A线路2 ≠ 频道B线路2),不能全局持久化。
2. **`initialResolved` 跳过 IPTV**:首次进入别从全局 store 读 qn,IPTV 直接 `selectedQn = 0`(第一个源)。
3. **自动切源**:`onPlayerError` 里,IPTV 且还有下一个源时 `selectedQn++`(切下一个镜像)而非重试同一个 —— 失败自动换源。

## 落地范围(一期,仅 TV)

| 模块 | 改动 |
| --- | --- |
| `IptvRepository`(新) | 拉配置的 m3u URL → 解析(处理 4 坑)→ 返回 `IptvChannel` 列表。无 B站 API/WBI/cookie |
| m3u 解析器 | `#EXTINF` + URL 行解析,按名合并镜像,过滤 rtmp/伪频道,保留 query |
| `VideoSummary` | 加 `source`(SourceIptv)+ `iptvUrls` |
| `PlaybackRequest` | 加 `iptvUrls` + `isIptv` getter |
| `LivePlayerScreen` | IPTV 分支合成 `LivePlayInfo` + 裸数据源 + 3 guard + 自动切源 |
| `LiveScreen`(TV) | 加 IPTV tab,按 group-title 分组,卡片复用 `TvVideoGrid` |
| 设置页 | 加"IPTV 源 URL"输入(DataStore string key) |

## 一期实现记录(已落地,云编译通过)

### 设置:IPTV 源地址(URL + 账号 + 密码)

- `AppSettings`/`AppSettingsStore` 加 `iptvSourceUrl`/`iptvSourceUsername`/`iptvSourcePassword`(DataStore string key)。
- `SettingsScreen` 加「IPTV 源地址」行,未配置显示"未配置,点按编辑"占位,点击弹 `SettingsIptvDialog`。
- `SettingsIptvDialog` 镜像 WebDAV 弹窗:URL/账号/密码三字段(密码掩码),保存/取消按钮。
- **URL 自动补全**:`normalizeIptvUrl` 不带 `http://`/`https://` 时补 `https://`(优先加密)。
- **连通性校验**:保存后 `IptvRepository.checkSourceReachable`(独立 20s 超时 client 直接 GET,带 Basic Auth),Toast 提示"连接成功/连接失败"。**坑**:源服务器(如 `cf.19961226.xyz/iptv/`)对 HEAD 直接挂起不响应,若复用 download 的 300s read 超时 client 先 HEAD 再回退,HEAD 会卡满 300s 才轮到 GET → 保存像死掉误报"连接失败";故探测不复用长超时 client,统一短超时 GET(只判响应码不读 body)。**超时不能太短**:该源 TTFB 可达 9s+、偶发 SSL 失败,10s 超时在真机网络下误判,取 20s。

### 数据模型

- `VideoSummary` 加 `source`(新增 `SourceIptv = "iptv"`)+ `iptvUrls: List<String>`。
- `PlaybackRequest` 加 `iptvUrls` + getter `isIptv`。
- `IptvChannel.toVideoSummary()` 映射频道卡片(`pic`=logo,`ownerName`=group)。

### 新 `IptvRepository` + m3u 解析器

- `getChannels()`:拉配置的 m3u URL(带 Basic Auth)→ `parseM3u` → `List<IptvChannel>`。无 B站 API/WBI/cookie。
- `parseM3u` 处理 4 坑:同名合并镜像 URL、跳伪频道(时间戳名)、滤 rtmp、保留 query 串。
- `checkSourceReachable()`:连通性校验(独立 20s 超时 client 直接 GET,不复用 download 300s read)。

### 播放:合成 LivePlayInfo + 裸数据源 + 3 guard + 自动切源

- `LivePlayerScreen` IPTV 分支:`selectedQn` 当源索引,`qualities` 合成 `[线路1, 线路2, ...]` 复用清晰度面板切源,`headers` 置空。
- **裸数据源**:IPTV 用 `DefaultDataSource.Factory(context)`,不走 `BiliMediaDataSourceFactory`(强制套 B站 UA/头)。
- **3 guard**:`persistQuality` 跳过 IPTV(源索引每频道,不全局持久化);`initialResolved` 直接 true;`LaunchedEffect(roomId)` 读 store 加 `!request.isIptv`。
- **自动切源**:`onPlayerError` 里 IPTV 且还有下一源时 `selectedQn++` 切下一镜像。

### 入口:AppShell 路由 + LiveScreen IPTV tab

- `AppShell`:`BiliTvApp` 注入 `iptvRepository`;本地 `toPlaybackRequest` 加 `source == SourceIptv` 分支;播放器挂载条件 `isLive || isIptv` → 走 `LivePlayerScreen`。
- `LiveScreen`:加 `LiveSection.Iptv` tab(推荐后),一次拉全量无分页,未配置源显示"请先在设置中配置 IPTV 源地址"。
- DI:`AppContainer` 建 `iptvRepository`,`MainActivity` 注入。

### 真机验证源

`https://cf.19961226.xyz/iptv/`(fanmingming/live 类,408 频道合并 288 个,含镜像源)。设置里填此地址 → 保存提示连接成功 → Live 页 IPTV tab 列频道 → 点频道起播线路1,清晰度面板切线路,断流自动切下一镜像。

## 已知坑(alpha.29 修复)

- **裸 IP 源明文被拦**:CDN 节点多是 `IP:port` 直连(tsfile/gitv/cntv 的 `223.110.x.x`/`61.x`/`183.x`)。明文放行原只在 `Ipv4OnlyDns.lookup`(DNS 解析时)注册 host,而 OkHttp 对裸 IP 字面量**不查 Dns** → 永不注册 → `CLEARTEXT communication ... not permitted` → IPTV 黑屏。修复:`IptvCleartextPlatform.isCleartextTrafficPermitted` 对裸 IP 字面量直接放行(`isLiteralIp`)。**注意** 与 alpha.25 的 302 重定向放行互补:302 只覆盖重定向目标,直连 IP 源走本修复。
- **缩略图截帧缺重试**:`IptvThumbnailCapturer` 的 HlsMediaSource 若不挂 `LiveLoadErrorHandlingPolicy`,域名源(如 mobaibox.com)首载 403/断连时无重试 → 卡 BUFFERING 到 15s 超时 → 缩略图回退台标。修复:与 `LivePlayerScreen` 对齐挂 `LiveLoadErrorHandlingPolicy`(重试 7 次 + 指数退避)。

## 已知坑(alpha.30 修复)

- **截帧器出声(退出直播后仍有音频)**:截帧器为拿画面真播一条流,此前未静音 → 每个截帧都从扬声器放声(2 并发、最长 15s)。进 IPTV 列表/退出直播后列表持续截帧,用户误以为"直播 player 没释放"。修复:`player.setVolume(0f)` 静音,截帧只取画面。
- **截帧器仍超时(卡 BUFFERING 无重试可触发)**:加重试策略后,部分源仍卡满 15s `timeout (no ready frame)`——这些源卡 BUFFERING 且进度不前进但**不报错**(重试策略不触发),真实播放器靠 stall 看门狗主动重挂才到 READY,截帧器无此机制只能干等。修复:等 READY 循环内加同款 stall 检测(卡 3s → `clearMediaItems + setMediaSource + prepare` 重挂,最多 3 次)。

- **裸 IP 源明文被拦**:CDN 节点多是 `IP:port` 直连(tsfile/gitv/cntv 的 `223.110.x.x`/`61.x`/`183.x`)。明文放行原只在 `Ipv4OnlyDns.lookup`(DNS 解析时)注册 host,而 OkHttp 对裸 IP 字面量**不查 Dns** → 永不注册 → `CLEARTEXT communication ... not permitted` → IPTV 黑屏。修复:`IptvCleartextPlatform.isCleartextTrafficPermitted` 对裸 IP 字面量直接放行(`isLiteralIp`)。**注意** 与 alpha.25 的 302 重定向放行互补:302 只覆盖重定向目标,直连 IP 源走本修复。
- **缩略图截帧缺重试**:`IptvThumbnailCapturer` 的 HlsMediaSource 若不挂 `LiveLoadErrorHandlingPolicy`,域名源(如 mobaibox.com)首载 403/断连时无重试 → 卡 BUFFERING 到 15s 超时 → 缩略图回退台标。修复:与 `LivePlayerScreen` 对齐挂 `LiveLoadErrorHandlingPolicy`(重试 7 次 + 指数退避)。

## 三期:源判活 + 自动换源(TV only,已实现待云编译)

> 目标:频道多镜像源时,载入列表即切到可用源——点开直接播活源,不再"首开线路1 黑屏 → 报错才轮询换源"。**仅 TV 端**,移动端本轮不动。

### 分层判活(成本核算后的设计)

截帧探活(拉流+解码+出帧)单次约 1~3 MB、3~22s,全列表(几百~上千频道)扫一遍是 GB 级流量 + 数十分钟,不可行。分两层:

- **第一层 廉价 m3u8 探活(启动后台扫全列表)**:对每个**多源频道**的 urls 顺序发 GET 拉源 m3u8 文本(约 10~100 KB/次,10s 级),首个成功即止。约 50 MB 扫完全列表。校验不止看 2xx,还 peek 前 2 KB 必须像 m3u8(`#EXTM3U`/`#EXT-X`/`#EXTINF`)——部分源 200 回 HTML 错误页。局限:m3u8 能拉 ≠ ts 段一定能播,但过滤明显死源已够用。
- **第二层 截帧探活(可见频道,即现有缩略图功能)**:拉流出帧既当缩略图又当终极判活。urls[0] 截不出帧(段 403/解码失败)→ 顺序补试 urls[1..2](每频道最多 3 源),某源出帧 = 该源铁定可播 → 回写判活结果。廉价探活已判死的 url 直接跳过,不浪费 22s。

### 判活结果复用与会话架构

- 判活一次,本次启动全程复用:`IptvSourceProbeStore` **app 级单例**(挂 AppContainer),`url → alive` 的 StateFlow。
- 启动入口:`BiliTvApplication.onCreate` → `AppContainer.startIptvSourceProbe()`,延迟 15s(避开冷启动图片/接口流量高峰)后台扫一次,fire-and-forget。未配置源时 getChannels 返回空自然退出。
- 扫描 client 与拉流同栈(IPv4-only DNS + 裸 IP 明文放行 + 事件日志,`IptvDataSourceFactory.createProbeClient`),否则裸 IP http 源探活必假死。超时更短(connect 10s/read 8s)快速失败。

### urls 重排(活源前置)

拿到判活结果后把频道 `urls` 重排:`[廉价探活活的(原相对序)] + [未探(原序)] + [判死(原序)]`,稳定分区不动频道列表本身。重排后:

- 列表页 `VideoSummary.iptvUrls[0]` = 活源 → 点开即播活源(播放器 `selectedQn=0`)。
- TV 播放器自己重拉 m3u 后也过一遍重排(频道列表侧栏 + 断流自动切源 `selectedQn++` 的顺序都变成活源优先)。
- 探活结果流式到达:TV LiveScreen debounce 后回写 IPTV section 的 videos,滚动中途到结果也会生效。

### 关键坑位

- **"无缩略图 ≠ 死源"**:22s 超时的慢源/网络抖动会假死,只降级"同频道有别的源成功"的失败源,绝不单独判死。截帧失败不回写 dead。
- **缩略图 key 一致性**:截帧 winner 可能不是 urls[0](重排前),缩略图 map 给频道**所有 urls** 都塞同一 bitmap,重排后 first 变也命中。
- **频道列表项身份**:TV 网格 key 用 `liveRoomId`(IPTV 全 0)+ 行 index,urls 重排不影响 key/焦点。
- **扫描并发限 2**,不与用户播放抢带宽(m3u8 GET 是 KB 级,影响可忽略);单频道廉价补试上限 3 个源。

### 三期真机首验发现与修复(alpha.5 真机日志,BRAVIA)

**生效确认**:sweep 35.8s 扫完 83 个多源频道(78 活/14 死),死源全是 `125.94.244.29/1234.php` 类垃圾 php 代理/组播地址;点开三个台里两个直接播活源、一个线路1 秒 ENDED 后切线路2 播出。

**发现 1:半死源(m3u8 活但 ts 段超时)廉价探活判不出来**。实例 CCTV-1 `183.129.255.66`:m3u8 秒回 200(判活正确),ts 段 `SocketTimeoutException` 频发,真拉流 BUFFERING ≥7.5s 用户退出。这正是第一层的设计局限,只有第二层截帧能证伪——但截帧 22s 太慢,救不了首开。修复:播放器 stall 看门狗 IPTV 分支从"同源重载"(重挂还是它)改为**优先换镜像源**(`selectedQn++`,同 onPlayerError 分支;单源台/末源退回同源重载),卡 8s 即切活源,不再烧满 3 次重试。日志:`iptv stall detected, switch to source #N/M`。

**发现 2:截帧多源回退引入两个并发槽浪费 bug,放大"进 tab 一会才有缩略图"**:
- 在截中的 url 被 `getThumbnail` 防重入返回 null,`getThumbnailForChannel` 误判"源不行"→ 又开 urls[1] 第二路截帧,同频道占双份槽位;
- 半死频道逐源烧满 3×22s=66s 霸占并发槽,3 个槽被一两个这种频道锁死,首屏缩略图全排队。
修复:urls 已有在截 url 时直接让位(首次触发的协程 await 到会自己回填,可见范围重触发不开第二路)+ 单频道截帧总预算 26s(`withTimeoutOrNull`,略高于单次 22s 给补试源留几秒)。

**遗留认知**:进 IPTV tab 缩略图要逐频道拉流截帧(3~22s/张、并发 3),首屏填满本来就要 15~30s——这是"每次进列表都截新鲜画面"的固有代价,非 bug;要秒出图需磁盘缓存上次会话缩略图(与该旧需求冲突,待定)。

### 三期真机二验发现与修复(21:33 真机日志,BRAVIA,CCTV-1"有画面不播"不自动换源)

**现象**:CCTV-1 首源 `183.129.255.66:8480/hls/1` 画面冻住 25s 不播,全程零 `iptv stall detected` 日志,最终用户手动按键切的源(日志 43.609 的 ENDED tracks=0 是切源 `clearMediaItems` 的瞬时态,非真 ENDED)。

**真凶:半死源状态机舞步绕过 8s 看门狗**。该源 m3u8 每 6s 轮询全 200 但 24s 内只吐 1 个 10s ts 段:
1. 唯一段在 BUFFERING 态下被播完(ExoPlayer 有缓冲数据 position 照样前进)→ 看门狗 `stallSince` 被"进度在动"一路重置;
2. ~34.3s 段耗尽 position 冻结,看门狗计时,将于 ~42.5s 开枪;**42.318s ExoPlayer 翻 READY**(判定流无更多媒体)——差 0.2s,看门狗条件 `state==BUFFERING` 变假,stallSince 清零;
3. READY 冻帧(="有画面但是不播"的实体)看门狗零覆盖;且 IPTV 真播到 ENDED 在代码里没有任何恢复分支(直播流没有合法 ENDED)。

**修复(LivePlayerScreen)**:①看门狗条件 IPTV 扩展——`playWhenReady && position 冻结 && (BUFFERING || ENDED || (READY && !isPlaying))` 累计 8s 照切源;B站直播维持仅 BUFFERING 语义(ENDED 是主播下播合法终态);②`onPlaybackStateChanged` 加 IPTV ENDED 即时切源分支(直播 ENDED 即断流,不等 8s),用 `mediaItemCount>0` 排除切源 `clearMediaItems` 的瞬时 ENDED 防自激循环,last source 退回同源重载、retry 上限同看门狗。READY&&!isPlaying 的罕见误判(audio focus suppressed 等)由 retry 上限 3 兜底。日志:`iptv playback ended, switch to source #N/M`。

**预期时序**:同场景下 ~42.5s 看门狗开枪(冻结后 8s)自动切活源,不再依赖用户手动;若源直接播到 ENDED 则秒切。

### 三期实现记录

| 模块 | 改动 |
| --- | --- |
| `IptvSourceProbe.kt`(新) | `IptvSourceProbeStore`(app 级判活结果 StateFlow,markAlive/markDead/isDead/reorderUrls 稳定分区)+ `IptvSourceProber`(启动扫描:多源频道顺序廉价探活,已证活即止,并发 2,单频道补试上限 3;URL 校验 peek 2 KB 像 m3u8;日志 URL 裁 query) |
| `IptvDataSourceFactory` | 暴露 `createProbeClient()`:与拉流同栈(IPv4-only DNS + 裸 IP 明文放行 + 事件日志)+ 短超时(connect 10s/read 8s)。**必须同栈**否则裸 IP http 源探活必假死 |
| `IptvThumbnailManager` | 构造加可选 `probeStore`(移动端不传,行为不变);新增 `getThumbnailForChannel(urls)`:TV 多源回退截帧,判死源跳过、出帧即 markAlive 回写、截帧失败**不**写 dead;补试上限 3 |
| `LiveScreen`(TV) | IPTV 加载时 urls 活源前置重排;新增 `LaunchedEffect` 收集判活结果流(collectLatest+delay 2s debounce)增量重排 IPTV section 的 videos(只写有变化项);可见范围截帧改 `getThumbnailForChannel`,bitmap 映射频道**全部** urls(重排后 first 变不失联) |
| `LivePlayerScreen` | 加可选 `iptvProbeStore`(移动端默认 null 不变);TV 频道列表拉取后重排——频道侧栏切源、断流自动 `selectedQn++` 顺序均活源优先 |
| `AppContainer`/`Application` | `iptvSourceProbeStore` 单例 + `startIptvSourceProbe()`(延迟 15s 避开冷启动流量高峰,fire-and-forget);AppShell/MainActivity 插线 |

**判活结果语义(重要约定)**:`true`=证活(m3u8 探活 2xx 且像 m3u8,或截帧真出画面=铁证);`false`=**仅**廉价探活硬失败(m3u8 拉不到/回 HTML);截帧超时**永不**写 false(慢源假死)。重排稳定分区 `[活] + [未探] + [死]`,无活源证据时只把判死沉底不动未探相对序。

**真机验证点**:①冷启动 15s 后 logcat `BiliMT:IptvProbe` 应见 sweep alive/dead 计数;②进 IPTV 列表,死源排前的频道(如 CCTV-x 镜像)点开应直接播活源不再先黑屏报错;③滚动列表缩略图,urls[0] 段损坏的频道应自动换镜像出图。

## 二期(暂缓)

- EPG 节目单(tvg-id + x-tvg-url)。
- 频道分组折叠、本地 m3u 文件(SAF)、频道收藏。
- 移动端接入。
- 裸 TS / H.265 探测降级(样例无此需求,暂不做)。
