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
