# IPTV 纳入直播 —— 可行性研究

> 状态:已确认可行,一期只做 TV 版。样例:`tmp/result.m3u`(fanmingming/live 类公开源,408 频道)。

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

## 二期(暂缓)

- EPG 节目单(tvg-id + x-tvg-url)。
- 频道分组折叠、本地 m3u 文件(SAF)、频道收藏。
- 移动端接入。
- 裸 TS / H.265 探测降级(样例无此需求,暂不做)。
