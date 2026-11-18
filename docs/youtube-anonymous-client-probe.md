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

**接入状态（P11-342，2026-11-18）**：已实装为**移动端手动开关**「IOS 直链优先」（`youtubeUseIosDirect`，默认关，TV 设置页不加行）——打开时 `resolve()` **最优先**直通 IOS 臂（`buildPlaybackInfoFromIosDirect`：探测同款最小 payload → `parseFormat` → 选档/codec 硬件过滤复用 → **直链 url 原样绕过 `resolveStreamUrl` 的 n-decrypt** → `buildInfo` 合成 MPD），失败自动落现有全链；**不进兜底链**（手动选项语义）。`Client.IOS` context/headers 见 `InnerTubeClient`。下列清单待真机验证。

### 真机验证清单

- [ ] OkHttp 原样 POST IOS client → 200 + `adaptiveFormats[].url`（验 TLS 门控）
- [ ] 拿 itag401/400 等直链喂 `buildDashFallback` → ExoPlayer 出画（验播放）
- [ ] attestation 视频（`jNl6YkkzKxw`）→ 是否 RELOAD（验天花板）
- [ ] URL 过期后 re-resolve 续播（验 403 恢复）

## 6. 关联

- [youtube-stream-resolution.md](youtube-stream-resolution.md)：SABR 主链与 §9.5 ReVanced 对照（本文修正其"无客户端给直链"的表述）
- [youtube-hd-playback.md](youtube-hd-playback.md)：逐 alpha 调试（alpha.29 WEB TLS 门控 —— 本文复现）
- [youtube-dash-fallback-plan.md](youtube-dash-fallback-plan.md)：DASH 兜底链 —— IOS 直链接入点
- 节点 IP 背景：RAC = PEG 机房段（Scamalytics 0 但标 VPN、IP2Location 99 High）；TK = xTom 原生 JP（IP2Location 3 低风险）——**本次两台播放判定完全一致，IP 质量差异未影响 YouTube 门控**
