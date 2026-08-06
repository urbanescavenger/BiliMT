# YouTube 高清播放实现笔记

> 本文档记录 biliMT `core/youtube/` 实现 **YouTube 高清(1080P/2K/4K)播放** 的可行性分析、三个阻断点与实现方案。参考实现:FreeTube(本地/匿名,不登录——与 bilibili 主 App 登录策略一致,见 `docs/youtube-api-notes.md` §5)。数据来自代码实测(2026-08),协议来源 FreeTube / YouTube.js(MIT)。

## 1. 现状:最高只有 720p,但数据其实已就绪

YouTube 播放已上线(P11-09),走 `POST /youtubei/v1/player`(WEB→ANDROID 回退)+ `n` 解密,产出 progressive `PlaybackInfo`。但**实际最高只到 720p**,原因不是缺数据:

- `YoutubePlaybackResolver.parseFormat`(L244)已解析 `adaptiveFormats` 里的 `itag/mimeType/width/height/bitrate/qualityLabel`,**1080P/2K/4K 的 avc1/vp9/av01/hevc 候选都在**(L69-70)。
- 但 `buildInfo`(L113)只把选中的**单个**格式写进 `PlaybackInfo.qualities`,所以清晰度面板对 YouTube 只显示一项「当前清晰度」,没有多档可选。

> **结论:数据层已解析高清 itag,缺的不是数据,是三个环节的工程缺口。** 下文三个阻断点按"取流 URL → 选择策略 → 播放路径"排列。

## 2. 阻断点 1:取流 URL(硬前置)

高清流要能播,先得拿到**可播的直链 URL**。这里有两个坑:

### 2.1 无 PO token 时 adaptive url 常被剥离
`adaptiveFormats` 高清流在无 PO token 时**经常被 YouTube 剥掉 `url`**,只剩 `formats`(progressive)有 url([L68 注释](app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L68))。

### 2.2 `s` 签名解密未实现
当以 `signatureCipher` 形式返回时,`signatureCipherUrl`([L205-212](app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L205))**只回填未签名 url、不执行 `s` 解密** → 拿到的 url 必然 403。

**这是高清能否拿到可播直链的前提。**

| 形态 | 现状 | 需要 |
| --- | --- | --- |
| `url` 直给 | 直接可播 | 无需处理 |
| `signatureCipher`(s 签名) | 回填未签名 url → 403 | **实现 `s` 解密** |
| 无 PO token 被剥 url | 拿不到 | Tier 2:PO token(jnn) |

## 3. 阻断点 2:选择策略

`resolve()` 里 **Case B 优先取单个合并 progressive 流**(itag 18/22,≤720p),[L84](app/src/main/java/com/kirin/mt/core/youtube/YoutubePlaybackResolver.kt#L84):

```kotlin
val combined = combinedCandidates.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
if (combined != null) { ... return buildInfo(...combined...) }
```

adaptive 高清永远选不到。**要让高清生效,须把可解的 adaptive 高清提到 progressive 之前,progressive 仅作兜底。** 同时用 `CodecCapabilityProbe`(B 站已有)过滤 adaptive 候选,避免选到 TV 解不了的 4K VP9/AV1(黑屏)。

## 4. 阻断点 3:播放路径

[PlayerScreen.kt:1390](app/src/main/java/com/kirin/mt/ui/player/PlayerScreen.kt#L1390) 因 YouTube 的 `segmentBase==null`(`isProgressive=true`)走 `ProgressiveMediaSource`/`MergingMediaSource`。但 L83 注释明说 **adaptive fMP4 分片喂 ProgressiveMediaSource 会解析失败**。

真正的 DASH 分支(L1407 `buildDashMediaItem`)对 YouTube 从未进入。**而 `buildDashManifest`(L2053)已正确处理 SegmentBase/Initialization——只要把 YouTube adaptive 的 `initRange`/`indexRange` 填进 `PlaybackTrack.segmentBase`,YouTube 就自动进 DASH 分支,零改动喂流。**

## 5. 实现方案:三个环节闭环(可行性=高)

复用现有基础设施,不引新播放器:

### ① `s` 签名解密(解锁高清 url)
复用隐藏 WebView JS 引擎 `YoutubeJsExecutor`(n 解密同款机制):拉 base.js 一次,识别签名函数执行 `s` 解密,回填 `signatureCipher` 的 url。**运行时依赖真机**(base.js 结构常变)。

### ② 高分辨 adaptive 设为首选
`pickVideo` 把可解的 adaptive 高清提到 progressive 之前;用 `CodecCapabilityProbe` 过滤设备解不了的轨道;`buildInfo` 把全部可播 quality 写进 `PlaybackInfo.qualities`,让清晰度面板出现 1080P/2K/4K 多档。

### ③ 走 DASH 播放(用现有 MPD 构建器)
`parseFormat` 补解析 `initRange`/`indexRange`(on-demand fMP4),填进 `PlaybackTrack.segmentBase` → 自动进 `PlayerScreen.kt:1407` DASH 分支。

## 6. 分阶段

| 阶段 | 内容 | 收益 |
| --- | --- | --- |
| **Tier 1(核心)** | ①②③ | 1080P,视设备与 url 可得性常到 2K/4K |
| **Tier 2(增强)** | PO token(jnn)跑通 | 覆盖「YouTube 剥光所有 adaptive url」的极端场景 |
| **Out of scope** | DRM 保护内容、8K、HDR | 与 B 站同理由:TV 面板普遍不支持,探测不到会黑屏 |

## 6.5 实测结论:高清唯一前置是 PO token(2026-08)

curl 实测 `/youtubei/v1/player`(guest,无 PO token):

| 客户端 | 结果 |
| --- | --- |
| WEB | UNPLAYABLE(被拦) |
| ANDROID | OK,29 个 adaptive 格式(2160/1440/1080p 都在),但**全部无 url、无 signatureCipher**——只有元数据 |
| ANDROID_VR / TVHTML5 / IOS | 全部失败 |

**结论:无 PO token 时 YouTube 在所有客户端剥掉流 url,只留元数据;360p 来自唯一的 progressive 兜底流(itag 18)。** 合并 ANDROID 客户端救不了高清,PO token 是唯一路径。

## 6.6 PO token 实现(跟随 bgutils-js, MIT)

- **打包**:`assets/youtube/bgutils.js`(esbuild 打包 bgutils-js v4.0.3,暴露 `__runSnapshot`/`__mint`)。
- **流程**(`YoutubeBotGuard`):`POST /api/jnn/v1/Create`(requestKey=`O43z0dpjhgX20SCx4KAo`)→ descramble(base64+97)→ interpreter JS 加载进 WebView → `__runSnapshot`(BotGuardClient.create + snapshot)→ `POST Waa/GenerateIT` → `__mint`(WebPoMinter)→ 视频 ID 绑定 PO token。
- **注入**:resolver 生成 token 后注入 `/player` 的 `serviceIntegrityDimensions.poToken`。
- **降级**:任一步失败返回 null,走无 token 直连(360p),不阻塞主路径。
- **脆弱点(需真机)**:snapshot 的 contentBinding `c` 值当前为占位(`b=PLACEHOLDER&hh=PLACEHOLDER`),需对照真实 player 响应/attestation 钉死;interpreter JS/WASM 能否通过 BotGuard 运行时校验。真机看 logcat `YtBotGuard` 行定位。

## 6.7 PO token 真机调试记录(2026-08,逐层推进)

真机(Sony XQ-EC72, Android 16)逐层修掉的障碍,按日志 `YtBotGuard`/`YtJsExecutor` 行定位:

| # | 真机日志 | 根因 | 修复 |
| --- | --- | --- | --- |
| 1 | `window.__runSnapshot is not a function` | WebView 被 app 后台销毁后 `bgUtilsLoaded` 残留 true,bundle 没重载 | `eval` 用「evaluateJavascript 抛错」检测 WebView 销毁,重建重试;`loadBgUtilsBundle` 验证 `__runSnapshot` 丢失重载 |
| 2 | `__runSnapshot typeof="undefined"`(bundle 加载后) | `createWebView` 返回时 js_shell.html 还在异步加载,`evaluateJavascript` 对未就绪 WebView 失败 | `onPageFinished` 置位 `shellReady`(CompletableDeferred),`eval` 前 `awaitShellReady`(最多 3s) |
| 3 | `pollState parse failed: "{\"status\":...}"` | `evaluateJavascript` 对 `JSON.stringify` 的 JS 字符串结果做 JSON 编码(带引号+转义),直接 `.jsonObject` 解析失败 | 先 `jsonPrimitive.contentOrNull` 解出内层字符串再解析 |
| 4 | `GenerateIT response missing integrityToken` | GenerateIT 响应格式 `[null, <ttl>, null, "<token>"]`,token 在 **index 3**(纯字符串),原解析只查 `{integrityToken}` 对象和 `[null,{integrityToken}]` | 加 `arr.getOrNull(3)?.jsonPrimitive?.contentOrNull` |
| 5 | index-3 修复后仍取不到 token | 修复里 `arr?.getOrNull(1)?.jsonObject` 在 `arr[1]` 是数字(43200)时抛 `ClassCastException`,index 3 永远取不到 | index 3 提到优先,整条解析链包 `runCatching`(cast 失败回退 index 3),加 `onFailure` 日志 |
| 6 | `PO token JS error: TypeError: Cannot read properties of undefined (reading '0')` at `_WebPoMinter.create` | bgutils.js `__mint` 先覆盖 `window.__poToken` 为 `{status:"minting"}` 再读 `prev`,导致 `prev.webPoSignalOutput` 为 undefined,`WebPoMinter.create` 里 `webPoSignalOutput[0]` 报错 | `__mint` 在覆盖 `__poToken` **之前**先捕获 `prev`,再取 `prev.webPoSignalOutput` |
| 7 | `PO token JS error: BgError: PMD:Undefined` at `_WebPoMinter.create` | `webPoSignalOutput[0]` 为空——snapshot 传了带占位符 `c`(b=PLACEHOLDER&hh=PLACEHOLDER) 的 contentBinding,VM 不产生 minter。FreeTube 只传 `{ webPoSignalOutput }` 不带 contentBinding,视频绑定在 mint 阶段用 videoId 完成 | `__runSnapshot` 的 `client.snapshot` 只传 `{ webPoSignalOutput }`,去掉 contentBinding |
| 8 | 去掉 contentBinding 后仍 `BgError: PMD:Undefined` | `webPoSignalOutput` 仍空——问题不在 contentBinding,而在 challenge 源:jnn `/api/jnn/v1/Create` 给的 program 不产生 minter。FreeTube 用 `/youtubei/v1/att/get`(ENGAGEMENT_TYPE_UNBOUND) | challenge 源对齐 FreeTube:`fetchBotGuardChallenge` POST `/att/get` + 从 `challengeData.bgChallenge` 取 program/globalName/interpreterUrl,interpreter 单独 GET |

**已跑通**:challenge 获取 → descramble → interpreter 加载(`window.trayride` 定义)→ snapshot 成功(`botguardResponse` 拿到)→ GenerateIT 返回 `[null,43200,null,"<token>"]`。

**待验证(alpha.12 起)**:GenerateIT 解析修复后 → mint(WebPoMinter)→ `PO token minted`。若 mint 报错,看 `PO token JS error`(可能 webPoSignalOutput 或 contentBinding `c` 占位)。

**注意**:`webPoSignalOutput` 在 JSON 里显示 `[]` 是正常的——minter 是函数,`JSON.stringify` 会省略它,但函数确实在数组里。

## 7. 关键文件

| 文件 | 作用 |
| --- | --- |
| `core/youtube/YoutubePlaybackResolver.kt` | `parseFormat`/`signatureCipherUrl`/`resolve`/`pickVideo`/`buildInfo` + PO token 注入 |
| `core/youtube/YoutubeSDecryptor.kt` | `s` 签名解密 |
| `core/youtube/YoutubeBotGuard.kt` | PO token 生成(challenge/snapshot/GenerateIT/mint) |
| `core/youtube/YoutubeJsExecutor.kt` | WebView JS 引擎 + `loadBgUtilsBundle` |
| `assets/youtube/bgutils.js` | 打包的 bgutils-js(MIT) |
| `ui/player/PlayerScreen.kt:1390` / `:2038-2104` | 喂流分支 + MPD 构建(改 feed 路径即可复用) |
| `core/player/CodecCapabilityProbe.kt` | 硬件过滤 |

## 8. 参考来源
- FreeTube 上游:`src/renderer/helpers/api/local.js`(InnerTube 封装)与 `formatUtils`(itag/adaptive 选择)
- YouTube.js(LuanRT/YouTube.js):`core/Player.ts`、`utils/FormatUtils.ts`
- bgutils-js(LuanRT/BgUtils,MIT):`BotGuardClient`/`ChallengeFetcher`/`WebPoMinter`
- FreeTube PR #8137(video ID 绑定 poToken)、#6931(jnn 端点)、#6977(legacy 360p 兜底)
