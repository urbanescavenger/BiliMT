# YouTube InnerTube API 笔记（实测）

> 本文档记录 biliMT `core/youtube/` 实现 InnerTube 私有 API 时**通过真实 curl 请求实测**得到的结论，含反爬规避与响应解析的关键点。协议来源：FreeTube / YouTube.js（MIT），独立 Kotlin 实现。

## 1. 请求基本形态

```
POST https://www.youtube.com/youtubei/v1/{endpoint}?key={API_KEY}&prettyPrint=false&alt=json
```

- **API key（WEB client）**：`AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8`
- **Headers**：`Content-Type: application/json`、`User-Agent`、`Referer: https://www.youtube.com`（必须）、`X-Youtube-Client-Version`、`X-Youtube-Client-Name: 1`（WEB=1）、ANDROID client 额外 `X-Goog-API-Format-Version: 2`
- **Body**：
```json
{
  "query": "...",                 // search 用
  "browseId": "...", "params": "...",  // browse 用
  "continuation": "...",           // 续页用（替代 browseId/query）
  "context": {
    "client": { "clientName": "WEB", "clientVersion": "...", "hl": "en", "gl": "US", "visitorData": "..." },
    "user": { "enableSafetyMode": false, "lockedSafetyMode": false },
    "request": { "useSsl": true, "internalExperimentFlags": [] }
  }
}
```

### 客户端常量（来自 YouTube.js）
- WEB: `clientName=WEB`, `clientVersion=2.20260623.01.00`, UA 用桌面 Chrome
- ANDROID: `clientName=ANDROID`, `clientVersion=21.03.36`, `androidSdkVersion=36`, UA=`com.google.android.youtube/21.03.36(...) gzip`

### guest 认证 visitorData
`visitorData` = base64url(protobuf `VisitorData{ id(field1,11位随机), timestamp(field5,epoch秒) }`)，再 URL 编码。
- protobuf 字节：`0x0A <len> <id> 0x28 <timestamp_varint>`
- base64url：`+`→`-`、`/`→`_`
- 可复用（同一 guest 会话）。

---

## 2. 反爬关键发现（最重要）

**`hl`/`gl` 用 `zh-CN`/`CN` 会触发反爬！**

- 实测：WEB 搜索带 `hl:zh-CN, gl:CN` → HTTP 200 但返回 `backgroundPromoRenderer`「出了点问题」+ CTA 按钮（反爬拦截），**无任何视频结果**。
- 改成 `hl:en, gl:US` → HTTP 200，正常返回 `videoRenderer`（约 20 个视频）。
- **结论：`YoutubeConstants.Hl/Gl` 必须用 `en/US`，不能 zh-CN/CN。**

### 各端点的可用性（2026-08 实测）

| 端点 | 客户端 | 结果 | 返回 renderer |
|------|--------|------|---------------|
| `/search` | WEB + en/US | ✅ 200 | `videoRenderer` |
| `/search` | ANDROID | ✅ 200 (2MB) | `compactVideoRenderer`（非 videoRenderer） |
| `/search` | WEB + zh-CN | ❌ 反爬 | `backgroundPromoRenderer`「出了点问题」 |
| `/browse` FEtrending（通用热门） | 任意 | ❌ **400 invalid argument** | 已废弃 |
| `/browse` topic 热门（游戏/体育/播客） | WEB | ✅ 200 | `gridVideoRenderer` |
| `/browse` 频道视频（UC... + params） | WEB | ✅ 200 | `lockupViewModel`（新格式） |
| `/browse` @handle（无 UC... id） | WEB | ❌ **400** | **只接受 `UC...` 频道 ID；`@handle` 做 browseId 会 400**，解析 handle 必须走 `/search` 找 `channelRenderer` |
| `/search`（handle/频道名） | WEB | ✅ 200 | `channelRenderer`（含 `channelId` + `title.simpleText`）|
| `/browse` 频道/热门 | ANDROID | ❌ 400 | ANDROID browse 不可用 |
| `/browse` FEwhat_to_watch（home feed） | WEB/ANDROID | ⚠️ 200 但无视频内容 | 空/导航壳 |

### 通用热门 FEtrending 已废弃
- `FEtrending` 自 **2025-03** 起返回 400 `"Request contains an invalid argument"`。
- `/feed/trending` 已被 YouTube 移除（2026-07 验证），重定向到首页。
- 影响 YouTube.js / FreeTube / NewPipe 等多个项目。
- **结论：不能用通用热门，改用 topic 热门**（游戏/体育/播客 browseId + params）。

### topic 热门 browseId（实测可用）
```kotlin
"游戏" to (browseId="UCOpNcN46UbXVtpKMrmU4Abg", params="Egh0cmVuZGluZ7gBAJIDAPIGBAoCMgA")
"体育" to (browseId="UCEgdi0XIXXZ-qJOFPf4JSKw", params="EglzcG9ydHN0YWK4AQCSAwDyBgQKAjIA")
"播客" to (browseId="FEpodcasts_destination", params="qgcCCAM%3D")
```
params 原样传，不额外 URL 编码。

---

## 3. 响应 renderer 解析

**不同端点/客户端返回不同 video renderer，字段结构大同小异，统一收集即可：**

| renderer key | 出现场景 |
|--------------|---------|
| `videoRenderer` | WEB 搜索 |
| `compactVideoRenderer` | ANDROID 搜索 |
| `gridVideoRenderer` | topic 热门 |
| `lockupViewModel` | 频道页（新格式，解析不稳定） |

`parseVideoRenderer` 读取字段：
- `videoId`
- `title.runs[0].text` 或 `title.simpleText`
- `ownerText.runs[0].text` / `longBylineText.runs[0].text`（频道名；trending 可能缺失）
- `thumbnail.thumbnails[].url`（取最后一张最大图）
- `viewCountText.simpleText`（如 "1,010,881 views"，需解析数字）
- `publishedTimeText.simpleText`（如 "8 hours ago"，反推 epoch）
- `badges[].metadataBadgeRenderer.label`（LIVE/New 等）

### 时长字段差异（重要）
- `videoRenderer`：`lengthText.simpleText`（如 "1:22"）
- `gridVideoRenderer`：**无 lengthText**，时长在 `thumbnailOverlayTimeStatusRenderer.text.simpleText`（如 "1:22"）
- `parseVideoRenderer` 需回退读取 overlay。
- `lockupViewModel` 时长在 `contentImage.thumbnailViewModel.overlays[].thumbnailOverlayBadgeViewModel.thumbnailBadges[].thumbnailBadgeViewModel.text`（如 "13:09"）。⚠️ 若用「递归收集 contentImage 全部字符串再取第一个像时长的」方式，`collectStrings` 会把缩略图 `width`/`height`/`backgroundColor` 等**纯数字**也收进来，而 `parseDuration` 若把单段纯数字当秒，就会误匹配到同一响应里所有卡片一致的缩略图尺寸数值 → 整个主页所有视频时长显示成同一个常量（实测 2:58）。**`parseDuration` 必须只接受含冒号的真实时长格式（`MM:SS`/`HH:MM:SS`）并校验秒位 0..59，拒绝单段纯数字**（v3.0.3-alpha.3 修复）。

### 续页 continuation
响应末尾 `continuationItemRenderer.continuationEndpoint.continuationCommand.token`（取最后一个）。续页请求 body 用 `{ "continuation": "...", context }`。

---

## 4. 解析实现建议

- 用「递归收集指定 renderer key」的方式统一兼容 search/browse/continuation 三种容器结构，比按固定路径遍历更稳。
- `lockupViewModel`（新格式，实测为频道视频 tab 唯一格式）实际结构：`contentId`（字符串=videoId）、`metadata.lockupMetadataViewModel.title.content`（标题）、`contentImage.thumbnailViewModel.image.sources[].url`（封面）、时长在 contentImage overlay 的 `thumbnailBadgeViewModel.text`（如 "13:09"）、播放量/发布时间在 `metadata.lockupMetadataViewModel.metadata.contentMetadataViewModel.metadataRows[].metadataParts[].text.content`（如 "56K views"/"4 days ago"）；无频道名（订阅流给空作者名补频道名）、**无频道头像**（`channelAvatarUrl` 恒空，频道页卡片头像需从 `parseChannelInfo` 解析出的频道头像注入 `ownerFace`）。

---

## 4.9 字幕（WebVTT URL 直拉，不走 SABR 服务端）

YouTube 字幕不经过 `/player` streamingData，也不用 SABR 服务端字幕（服务端行为未验证）。实测用 NewPipe fork（`com.github.libre-tube:NewPipeExtractor` `738c3d4`）`StreamInfo.getInfo` 时 `info.subtitles` 直接给出可拉取的 WebVTT URL，播放器层合并渲染：

- **fork 类型注意**：`info.subtitles` 返回 `List<SubtitlesStream>`（fork 改名，非上游 `SubtitleInfo`）。语言访问器是 **`getLanguageTag()`**（无 `getLanguageCode()`），`Stream.getUrl()` 返回 `String?`（`baseUrl` 赋值需 `orEmpty()`）。
- **合并渲染**：media3 1.10 的默认 `DefaultExtractorsFactory` **不含字幕 Extractor**，必须显式传 `ExtractorsFactory { arrayOf(SubtitleExtractor(DefaultSubtitleParserFactory().create(format), format)) }`（对齐 LibreTube `OnlinePlayerService`），`ProgressiveMediaSource` 拉 WebVTT → `SubtitleExtractor` 转 MEDIA3_CUES → `MergingMediaSource` 合并进主源 → PlayerView 内置 SubtitleView 自动渲染。
- `PlaybackInfo.subtitleTracks` 槽位（非 YouTube/无字幕为空）+ `PlaybackTrack.languageCode`（字幕轨用，A/V 轨 null）。字幕轨选择/语言切换 UI 后续迭代。

---

## 4.10 订阅流（关注动态）：RSS + InnerTube 并行合并

关注频道的最新视频（移动端动态/首页、TV 首页热门 tab 共用）。单走任一来源都有缺陷，正确做法是**每频道并发拉 RSS 与 InnerTube `/browse`，按 `videoId` 合并**：

- **RSS**（`/feeds/videos.xml?channel_id=UC...`）：轻量 GET，无 InnerTube 风控/不计配额；提供**精确 ISO 8601 `publishedAt`**。但**不含 duration/live/upcoming/badge/头像**，且直播/Shorts 覆盖不全。
- **InnerTube** `/browse`（频道视频 tab）：提供 `duration`/`liveNow`/`isUpcoming`/`badge`/`viewCount`/头像，补 RSS 缺失字段；但 `publishedAt` 是相对时间反推（"4 days ago"），月/年用固定天数近似，刷新排序不稳定。
- **合并规则**：以 RSS 为基底，`publishedAt` 优先 RSS（精确），`durationSec`/`viewCount`/`liveNow`/`isUpcoming`/`badge`/`channelAvatarUrl` 优先 InnerTube；仅单路有的直接保留。
- **降级**：任一路失败用另一路（RSS 失败→InnerTube 近似时间；InnerTube 失败→RSS 无 duration/live，降级可用），两者都失败该频道返回空、不影响其它频道。
- **并发**：RSS 用独立信号量放宽（8），InnerTube 仍受 4 限并发防风控；关注多时按批次放宽超时（`youtubeFeedTimeoutMs`，上限 10s）。

### 4.10.1 首页订阅流 continuation 分页（2026-08-18，alpha.94+）

§4.10 描述的是**单页**合并：每频道 RSS + InnerTube `/browse` 第一页合并后 `take(perChannel)`（默认 **15 条**），更早的视频被结构性截断，且 `parseFeedPage().continuation` 被丢弃——**最早视频永远拉不到**。对照 LibreTube：它对订阅流**同样不翻页**，用「最近 30 天时间窗 + 每频道各 tab 只拉第一页 + `cleanUpOlderThan` 清库」解决，即 **LibreTube 没有可抄的订阅流续页方案**。要翻到最早必须自己给聚合流加 continuation。

**新增分页（`YoutubeRepository.getSubscriptionsPage` / `getChannelVideosRawPage` / `fillChannelInfo`）：**
- 首屏（`previousContinuation==null`）：沿用 §4.10 每频道 RSS + InnerTube 第一页并行合并、`take(perChannel)`，但**记录该频道 InnerTube `/browse` 第一页的 continuation**。
- 续页：只对 `perChannelContinuation` 中 token **非 null** 的频道，调 `getChannelVideosRawPage(channelId, token)` 拉**更早一页** → `take(perChannel)` 映射；RSS 无续页概念，续页仅走 InnerTube。每批 `onChunkReady`、每 `BatchSize` 随机 delay 防节流、单频道 `runCatching` 降级空——沿用 §4.10 骨架。
- 返回模型 `YoutubeSubscriptionsPage(videos, perChannelContinuation)`，`endReached = perChannelContinuation.values.all { it == null }`（所有频道到底即全部加载完）。续页视频同样走「补频道名/id/头像」（`fillChannelInfo`，复用原 :290-296 逻辑）。

**转发层** `VideoRepository.youtubeHomeFeedPage(previousContinuation)` 读 `youtubeChannelStore.channels`，空→空页；头像回写 store 在此处理，UI 无感知。

**UI 消费（TV `RecommendScreen` + 移动 `HomeScreen`，共用）**：
- `Success.youtubeContinuation: Map<String,String?>?`（仅 YoutubeTrending 用；非 YouTube 分区为 null）。
- 首屏 `endReached=page.endReached`、`youtubeContinuation=page.perChannelContinuation`；续页 `(current+page).distinctBy{it.bvid}.sortedByDescending{it.pubdate}`、`endReached = page.endReached || merged.size == current.videos.size`（**续页未新增视频也视为到底**，防 token 不推进时死循环）。
- **缓存只存首屏快照、不落盘 continuation**（`YoutubeFeedCacheStore` 零改动）：10min 内 cache 命中视为单页到底（`endReached=true`），不续翻。

### 4.10.2 续页 token 提取对齐 NewPipe（2026-08-18，debug 分支）

**症状**：订阅流滑到底要么卡在 ~50 条（跨频道去重后首屏量，续页没补上更早的），要么死循环。根因在 `YoutubeParsers.findContinuation`：**全树 `collectByKey` 扫 + 取最后一个 continuationItemRenderer**。频道视频 tab 的 `/browse` 响应里可能夹带 shorts/相关频道等其它 section 的 continuation，取错后拿它续页返回的还是首屏那批 → `appendUniqueByBvid` 去重成零新增 → 不推进。

**对照 LibreTube/NewPipe**（`E:\GITHUB\NewPipeExtractor` `YoutubeChannelTabExtractor.java`）：LibreTube 订阅流本身**单页快照不翻页**（§4.10.1 已记），真正"能一直加载到几年前"的是**单频道视频 tab**，靠 NewPipe 每次从新响应里正确提取推进中的 token：
- 续页响应只读 `onResponseReceivedActions[].appendContinuationItemsAction.continuationItems`；
- 首屏只定位视频网格 `gridRenderer.items` / `richGridRenderer.contents`；
- 都取**第一个** continuation，不是最后一个。

**本次改动**：`YoutubeParsers.findContinuation` 改为三段式——①续页走 `appendContinuationItemsAction`；②首屏定位视频网格取第一个；③搜索等无网格容器回退全树第一个。同时 `RecommendScreen` 与移动 `HomeScreen` 的 YoutubeTrending 续页补 `merged.size == current.videos.size` 护栏。这样每频道 token 每次从新响应重新提取、能真正推进到更早（对齐 NewPipe 机制），且 token 一旦不推进立即到底不循环。

### 4.10.3 移动端频道页续页 UI 层（2026-08-18，v3.0.4-alpha.3）

§4.10.2 修的是**数据层** token 提取；移动端**单频道页**（`MobileYoutubeChannelScreen`，`getChannelVideos` 直拉 `/browse`）续页还叠了两个 **UI 层** bug，导致滑到底只翻一页就停：

1. **近底触发被布尔去重吞掉**：`snapshotFlow { 近底布尔 }.distinctUntilChanged()` 只在布尔翻转时发射一次。首屏 loading 时（continuation 仍 null，`loadNext` 早退）把唯一的 `true` 消耗掉；短列表近底后值不再变、不再发射 → 续页永不触发。**改发射 `(last, total)` 对**去重，任何滚动/加载导致 last 或 total 变化都重新求值。
2. **endReached 去重比较拿错列表**：`loadNext` 里先 `uiState.items = merged` 再算 `endReached = merged.size == uiState.items.size`，拿 merged 和自己比恒真 → 第二页后 `endReached=true` 永远停。**先存 `oldItems` 再比较 `merged.size == oldItems.size`**（对齐 TV 版 `latest.videos` 旧列表比较）。日志佐证：`next items=30 next=4qmFsgK9FRIY`（token 非 null）却 `endReached=true`。

数据层 `getChannelVideos` 首屏/续页都正确捕获 continuation（`first items=30 next=...` / `next items=30 next=...`），问题纯在 UI 触发/状态逻辑。修复后 `loadNext merged old=30 new=30 merged=60 endReached=false` 连续翻多页。

---

## 5. 播放（Phase 2，未实现）

完整播放需要：
1. `POST /player`（videoId + context + **PO token**）解析 `streamingData`
2. **PO token**：必须执行 Google 混淆的 BotGuard JS（bgutils-js，MIT），需**隐藏 WebView JS 引擎**，无法纯 Kotlin
3. **`n` 参数解密**：拉 player base.js 提取 `n`-transform 函数，需 JS 引擎执行
4. 回退方案：Invidious 实例取流 URL

这是工作量最大、最脆弱的部分，暂缓。

---

## 6. 参考来源
- FreeTubeApp/FreeTube 上游：`src/renderer/helpers/api/local.js`（InnerTube 封装）
- YouTube.js（LuanRT/YouTube.js）：`src/core/Actions.ts`、`Session.ts`、`utils/HTTPClient.ts`、`core/Player.ts`
- 相关 issue：YouTube.js #916（FEtrending 400）、FreeTube #8777（trending 400）、invidious-companion #236
