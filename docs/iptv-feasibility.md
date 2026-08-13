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

## 二期(暂缓)

- EPG 节目单(tvg-id + x-tvg-url)。
- 频道分组折叠、本地 m3u 文件(SAF)、频道收藏。
- 移动端接入。
- 裸 TS / H.265 探测降级(样例无此需求,暂不做)。
