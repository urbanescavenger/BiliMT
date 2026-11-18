# YouTube 匿名客户端探测与直链实测（RAK/TK 节点，2026-11-18）

> 本文记录在两台东京 VPS 上**以纯匿名 Python（OpenSSL 栈）**系统性探测 InnerTube 各客户端的实测结果、拉流测速方法与节流规律，核心发现：**IOS 客户端匿名即给全阶梯签名直链（含 4K），可作免 PO token 的 DASH 兜底候选** —— 补上 [youtube-stream-resolution.md](youtube-stream-resolution.md) §9.5 "2026-10 已没有客户端给出直链"结论的缺口。
>
> - 测试机：**RAC**（rak.19961226.xyz，107.148.76.33，PEG AS398993 东京）与 **TK**（45.143.235.110，xTom AS3258 东京），Debian 13，`python3` 标准库（urllib + cookiejar），无浏览器、无 WebView、无任何 Google 账号
> - 脚本：[scripts/youtube-anonymous-probe/](../scripts/youtube-anonymous-probe/)
> - 两台 IP 实测结果**逐项一致**（除吞吐速度），说明门控是**客户端维度**而非 IP 维度

---

## 1. 一句话结论

1. **IOS 客户端（clientName=IOS 20.10.4）匿名 POST `/player` → 16 条 adaptiveFormats 全部自带签名 url，阶梯 144p→2160p，最高 itag401（av01 4K 17.4Mbps）实拉成功**，另给 `hlsManifestUrl`（3840x2160 变体）——不需要 WebView / PO token / n 解密。
2. WEB 客户端的 TLS 指纹门控（alpha.29 结论）在 OpenSSL 栈上**复现**：`UNPLAYABLE: The page needs to be reloaded`；而 **IOS 客户端对非浏览器 TLS 不设防**（Python/OpenSSL 直接过）——推断 OkHttp 有戏，**待真机验证**。
3. 社区的 "Sign in to confirm you're not a bot" 在同一 IP 上**由客户端选择决定**：ANDROID_VR 必弹、ANDROID 不弹 —— 不是 IP 被封（两台机房 IP 的 watch 页匿名均干净）。
4. **测 YouTube 节点速度不能用 DASH 直链**：Google 对直链按码率逐连接 pacing（4K 17.4Mbps 的流单连接死贴 18.0Mbps，4 并发正好 ×4）；要用 **HLS 分段**（整文件直出无节流）。

## 2. 测试方法（可复现）

```
① warmup: GET https://www.youtube.com/watch?v=<id>  (桌面 UA, 收 cookie)
           GET https://www.youtube.com/              (抽 INNERTUBE_API_KEY / CLIENT_VERSION / VISITOR_DATA)
② POST /youtubei/v1/player?prettyPrint=false
   {context:{client:{clientName, clientVersion, hl,gl, visitorData}},
    videoId, contentCheckOk:true, racyCheckOk:true}
   头: Content-Type: application/json, 对应 UA, X-Goog-Visitor-Id, Origin: https://www.youtube.com
   (cookiejar 自动回传 cookie —— 无 cookie 时 WEB 客户端报 "page needs to be reloaded")
③ 判定: playabilityStatus / adaptiveFormats[].url / serverAbrStreamingUrl / hlsManifestUrl
④ 实拉: 对最高码率直链 Range/全量 GET 计时 → 验证真可播 + 测速
```

脚本用法（`scripts/youtube-anonymous-probe/`）：

```sh
python3 yt_play_test.py                 # 四路径判定: ANDROID_REEL / WEB / ANDROID / ANDROID_VR
python3 yt_speed_hd.py <videoId>        # 五客户端探测 → 选最高直链拉流 x3 → 4并发 → HLS 分段
python3 yt_speed_test.py aqz-KE-bpKQ    # itag18 全文件反复拉(无节流口径的基线)
```

## 3. 客户端探测矩阵（2026-11-18 实测，RAC/TK 两台一致）

视频 `dQw4w9WgXcQ`（4K 修复版）：

| 客户端 | version | 结果 |
|---|---|---|
| **ANDROID** | 20.44.38（无 key） | **OK**。混流 1（itag18 360p 直链）+ 分离 26 条 **SABR-only**（无 url），阶梯 144→2160 全量，`serverAbrStreamingUrl=Y` |
| **IOS** | 20.10.4 | ✅ **OK。分离 16 条全部直链**（直链=16），阶梯 144→2160，`hlsManifestUrl=Y`。最高 **itag401 av01 2160p 17400kbps clen=240334643**，实拉出字节 |
| TVHTML5_SIMPLY_EMBEDDED_PLAYER | 2.0 + embedUrl | **ERROR**（0 格式）—— 该免 token 老通道已死 |
| MWEB | 2.20261116.01.00 | **UNPLAYABLE** |
| TVHTML5 | 7.20241112.18.00 | **UNPLAYABLE** |
| **WEB**（web key + 新鲜 visitorData + cookie） | 2.20261116.00.00 | **UNPLAYABLE: The page needs to be reloaded** —— 非 Chromium 栈被拒，复现 alpha.29 门控 |
| **ANDROID_VR** | 1.61.48 | **LOGIN_REQUIRED: "Sign in to confirm you're not a bot"** —— 社区 bot 提示当场复现，**同 IP 换客户端即触发/消失** |
| ANDROID_REEL + `/reel/reel_item_watch`（ReVanced 形态） | 20.44.38 | 200 但 `REEL_ITEM_WATCH_STATUS_BAD_REQUEST`（带不带 `X-Goog-Api-Format-Version` 均同）—— §9.5 记载的 2026-10-30 可用形态**已失效** |

watch 页匿名加载（两台）：`playabilityStatus=OK`、`playableInEmbed=true`、`streamingData` 有、全文无 "Sign in to confirm" —— **两台机房 IP 均未被 Google 标 bot**（对照：社区普遍结论是机房 IP 永久封；这两个 PEG/xTom 段 2026-11-18 仍健康）。

## 4. 拉流节流规律（重要，测速方法论）

| 拉法 | 表现 | 结论 |
|---|---|---|
| **DASH 直链**（itag401 4K 17.4Mbps） | 单连接死贴 **18.0 Mbps**（=声明码率×1.03），RAC/TK 数字逐位一致；4 并发 = 精确 ×4（71.9） | **服务端按码率逐连接 pacing** —— 测出的是片源码率不是链路速度，**两台无差异是假象** |
| **DASH 小文件全量**（itag18 28.52MB 360p） | RAC 单连 ~1.2Gbps、4 并发聚合 **1822–1833Mbps**；TK 单连 ~390M、4 并发 **不涨**（394M） | 文件小于 pacing 累积窗口时能冲出码率；**TK 是 390Mbps 硬顶**（1C 小机/宿主限速，与 NodeQuality 基线吻合） |
| **HLS 分段**（4K 变体 19.1Mbps，8 段并发 72.3MB） | RAC **0.37s ≈ 1566Mbps**；TK 1.57s ≈ **369Mbps** | 整段文件直出**无节流** —— ✅ **测节点→YouTube 吞吐用这个**（SABR 段同理） |

**节点对比结论**：RAC ≈ 1.5–1.8Gbps vs TK ≈ 390Mbps 硬顶；单流 4K 播放需求仅 17.4Mbps，**两台体验无差别**，差异在多路并发/下载场景。

## 5. 对 BV 的意义：IOS 直链 = 免 token 4K DASH 兜底候选

- **接入点现成**：IOS 的 16 条带 url 格式正好喂 `buildDashFallback*`（判定条件 `adaptiveFormats[].url` 即触发）→ 合成 MPD → ExoPlayer DASH，**不动 SABR 主链、不碰 WebView/BotGuard/n 解密**。
- **在 fallback 链中的位置**：Piped opt-in → NewPipe → **IOS 直链（新增）** → itag18/legacy 兜底。它补的正是现在 "4K 且免 token" 的空档（目前 ANDROID/WEB 都 SABR-only，兜底实际只剩 itag18 360p）。
- **AV1 解码**：最高档 itag401 是 av01 4K，新 SoC 硬解、老机可能没有 —— 选档走既有 `YoutubeCodecPreference`，IOS 的阶梯里有 VP9/H264 可退。
- **TLS 门控推断**：本次全部请求出自 Python/OpenSSL（非 Chromium 非 Apple 栈）且 IOS 客户端照常给数据 → IOS 未做严格 JA3 校验，**OkHttp 大概率能过，但必须真机实测确认**（WEB 客户端的教训：Java TLS 被拒）。

### 风险（与 §9.5 / NewPipe attestation 翻车同款）

1. **attestation 视频**：普通视频拉流成功 ≠ 全部视频；带 attestation 的（如 `jNl6YkkzKxw`）可能 URL 拿得到但拉流 RELOAD/403 —— 未测。
2. **URL expire ~6h**：重取成本极低（一个 POST，远轻于 SABR re-harvest 的 30s WebView），但 mid-stream 403 要做 re-resolve。
3. **被砍风险**：Google 收紧无 token 定端点是时间问题（ReVanced 补丁史），定位是**兜底**不是主路径。

**接入状态（P11-342，2026-11-18）**：已实装为**「播放优先级」两个独立档**——第 4 档「IOS 直链优先」（`IosDirect`，key=`ios`，www base+iOS UA）与第 5 档「visionOS 直链优先」（`VisionOsDirect`，key=`visionos`，GAPIS base+visionOS UA，同日 §7.3 G/G2 实测后加入；曾短暂做过档内双臂，用户定夺**分开各自成档**手动选）。TV/移动两套设置页经 `.entries` 自动出现，默认档仍 NewPipe。选中时 `resolve()` **最优先**直通对应单臂，走 `buildPlaybackInfoFromDirect`（探测同款最小 payload → `parseFormat` → 选档/codec 硬件过滤复用 → **直链 url 原样绕过 `resolveStreamUrl` 的 n-decrypt** → `buildInfo` 合成 MPD，清晰度按 height 去重、代表轨 P11-129 挑法）；**单臂失败即落现有全链，不进兜底链**（档位=「先走这条路」，与 Dash/WebSabr 同语义）。日志判读词：`direct playback ready: arm=IOS|VISIONOS`（首测 14:20 旧词 `IOS direct playback ready` 同义）。注意直链臂是 **ExoPlayer DASH 静态选档，不使用 SABR 升降档规则**（那套判据链仅限 `sabr://` 会话路径；带宽自适应升降列为直链 v2 议题、当前不动）。初版独立开关方案（`youtubeUseIosDirect`）已废弃移除。`Client.IOS`/`Client.VISION_OS` context/headers 见 `InnerTubeClient`。下列清单待真机验证。

**真机首测（2026-11-18 14:20，`logs_live_20261118_142002.log`，videoId=UVqLyLizppk）**：IOS 臂一次通过——`rawAdaptive=28、videoUrl=18/audioUrl=10、costMs=802ms`，选中 itag137 1080p，`nDecrypt=left`（签名直链直通 ✓）、`dash=yes`（init/index range 齐全）→ **能播**（§5 清单 1/2 条间接坐实：OkHttp TLS 过、合成 MPD 出画）。暴露问题一个：`qualities=18`——`buildQualityList` 按 itag 去重，IOS 阶梯同 height 多 codec 变体（1080p=137/248/399）致清晰度列表同档重复 3 次；**已按 alpha.9X NewPipe DASH 修正**（`distinctBy height`，每档纯 `"${height}p"`，选中档为代表 itag 保切档链闭合）。

### 真机验证清单

- [x] OkHttp 原样 POST IOS client → 200 + `adaptiveFormats[].url`（验 TLS 门控）——**2026-11-18 真机 14:20 首测过**（`IOS direct playback ready`，见接入状态段）
- [x] 拿 itag401/400 等直链喂 `buildDashFallback` → ExoPlayer 出画（验播放）——同上首测过（视频正常播放）
- [x] attestation 视频（`jNl6YkkzKxw`）→ 是否 RELOAD（验天花板）——**2026-11-18 RAK 匿名实测初判过**：status=OK、18 条全直链、4K HDR AV1 首段实拉 206 不 403（§7.3 H1）；真机全程播放待验
- [ ] URL 过期后 re-resolve 续播（验 403 恢复）

## 6. 关联

- [youtube-stream-resolution.md](youtube-stream-resolution.md)：SABR 主链与 §9.5 ReVanced 对照（本文修正其"无客户端给直链"的表述）
- [youtube-hd-playback.md](youtube-hd-playback.md)：逐 alpha 调试（alpha.29 WEB TLS 门控 —— 本文复现）
- [youtube-dash-fallback-plan.md](youtube-dash-fallback-plan.md)：DASH 兜底链 —— IOS 直链接入点
- 节点 IP 背景：RAC = PEG 机房段（Scamalytics 0 但标 VPN、IP2Location 99 High）；TK = xTom 原生 JP（IP2Location 3 低风险）——**本次两台播放判定完全一致，IP 质量差异未影响 YouTube 门控**

---

## 7. 社区动态对照与候选 client 实测（2026-11-18 下午，RAK）

### 7.1 社区时间线（Web 调研，2026-11-18）

| 时间 | 事件 |
|---|---|
| 2026-02 | YouTube 对 ANDROID/iOS 索 poToken → 社区转 **ANDROID_VR** 免 token（YoutubeExplode #936） |
| 2026-06 | ANDROID_VR 也被 gate（ranged>256KB 403 + 单 GET ~13KB/s 限速）→ visitorData 引导 + 分块 range（ytdown 75e3eea） |
| 2026-06→08 | **LibreTube SABR 合入**（PR #7689），v32.0/32.1（2026-08-20）：SABR 原生 Kotlin + poToken 生成/发送 + token 实验缓存 + visionOS 播放 |
| 2026-08 | **NewPipeExtractor #1529 删除 ANDROID 和 IOS client**——理由"这些 client 要 poToken 没人能生成" |
| 2026-08 | ReVanced 系转**真 poToken 生成**（BotGuard WebView 缓存 ~50min，Morphe c9a1622：TV Simply 登出态靠生成 PoToken 可用） |
| 2026-09 | ANDROID_VR 全球 "not a bot" → 社区转 **VISIONOS client**（不加密、无 cipher）——我们 NewPipe 主链已在该位 |
| 2026-11-02 | **FreeTube v0.26.0-beta 大面积播放坏**：#10095 "Legacy formats not available" 全视频、#10090/#10024 SABR reload 循环未修——**WEB-SABR 对齐方向短期无可借力** |
| 2026-11 中 | yt-dlp nightly 仅 post-live/web_embedded 维护，**签名/n-sig 层无新危机**（大改仍是 2025-11 外部 JS runtime 必需） |

**IOS 窗口稀缺性**：社区 2026-08 已删 IOS client（判定要 poToken），ReVanced 侧记载 iOS 2024 末起 A/B 索 token——而我们 11-18 两台机房 IP 匿名实测**全阶梯直链照给**。当前是少数派窗口（A/B 或形态差异），**P11-342 实验档定位正确，随时可能被收**。

### 7.2 LibreTube poToken 缓存对照（clone 已拉至 b9a486e）

`PoTokenGenerator.kt`（对应 upstream "experimentally cache generated PoToken"，避免 extractor 与 SABR 各铸一次）：

- `private var poToken: PoTokenResult?` 单字段内存缓存：`getWebClientPoToken` 每次生成后 `.also { poToken = it }` 存入，SABR 侧经 `getCachedWebClientPoToken()` 直取**同一份**。
- 生成器本体（`webPoTokenGenerator` + `webPoTokenVisitorData`）也缓存：按 `isExpired()` 判过期才重建；生成失败 `forceRecreate` 从头重建再试一次（WebView 内容丢失场景）。
- **我们可跟进**：alpha.91 已统一 minter 到 PoTokenWebView，但 NewPipe 侧已铸 token 与 SABR `status=2` 刷新之间**缺这层复用缓存**——补上可少铸一次 token、减 status=2 摩擦。

### 7.3 候选 client RAK 实测（`yt_candidate_probe.py` + `yt_tv_retry.py`，2026-11-18 下午）

| 路 | client | 结果 |
|---|---|---|
| **G** | **VISIONOS 匿名（GAPIS，client_id=101）** | ✅ **重大发现**：status=OK、adaptive=27 **全直链**、144→2160 全阶梯、SABR 基址+hls 同给、**4K VP9 itag313 实拉 206 OK** —— 第二条免 token 全直链臂（与社区 2026-09 推荐方向吻合） |
| **G2** | **VISIONOS × attestation `jNl6YkkzKxw`** | ✅ status=OK、**adaptive=40 全直链**（比普通视频还多 13 条）、4K VP9 实拉 206 —— 第二臂对 attestation 同样通 |
| **H1** | **IOS × attestation `jNl6YkkzKxw`** | ✅ **§5 清单第 3 条初判通过**：status=OK、18 条全直链、**4K HDR AV1 itag701 首段实拉 206 不 403**（对照 P11-99 WEB/ANDROID 直链 403 —— IOS 签名链不受 pot 门控） |
| H2 | IOS × 普通对照 | ✅ 16 直链 + 4K 实拉 OK（与上午矩阵一致） |
| F | TVHTML5_SIMPLY_EMBEDDED(+embedUrl) | ❌ 正式判死：`YouTube is no longer supported in this application or device.` |
| E | TVHTML5_SIMPLY_PLAYER / TVHTML5_SIMPLY | ❌ 匿名无门：前者 400 枚举无效、后者 404 不存在——与 Morphe 方案自洽（**TVSimply 必须配 poToken**）；带 token 形态留后续（需 BotGuard WebView，服务器铸不了） |

**下一步**：①两条直链臂已进 P11-342 真机待验（清单剩第 4 条 URL 过期）；②VISIONOS 直链臂**已接入为独立第 5 档**（`buildPlaybackInfoFromDirect` 参数化 client/useGapis，`postJson` 增 `useGapis` 开关切 GAPIS base；与 IOS 档分开手动选，用户定夺）；③LibreTube 式 poToken 复用缓存跟进；④TVSimply+poToken 需设备侧 BotGuard，暂缓；⑤直链带宽自适应升降（全阶梯 MPD+起播锁档+降档可升回+UI 显示 active 档）列为 v2 议题，用户指示当前先不动。
