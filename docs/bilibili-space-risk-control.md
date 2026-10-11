# B站 space(UP 主页)接口风控 / 频控笔记

本文件记录 **实测确认过** 的 `api.bilibili.com` space 系接口行为,供以后排查
「UP 页资料/统计/投稿列表显示 0 或空」这类问题时直接对照,不必重新试错。

## 1. 进一次 UP 页会打出哪些请求

`UpSpaceScreen` / `MobileUserSpaceScreen` 打开时会**在同一秒**发出:

| 调用 | 接口 | 签名 | 由谁发 |
| --- | --- | --- | --- |
| 资料 | `x/space/acc/info`(`mid` + `platform=web` + `web_location=333.1387`) | wbi(`w_rid`) | `SpaceProfileRepository.fetchAccInfo` |
| 统计 | `x/relation/stat`(`vmid`) | **无签名** | `SpaceProfileRepository.fetchRelationStat` |
| 投稿列表 | `x/space/wbi/arc/search` | wbi | `SpaceVideoRepository` |
| 关注态 | `x/relation`(`fid`) | — | `VideoRepository.checkFollowStatus` |

四个请求同一秒落地,足以触发 B站 对 space 接口的**频控**。

## 2. 风控 / 频控码语义(实测)

| 码 | 含义 | 是否瞬态 | 处置 |
| --- | --- | --- | --- |
| `-799` | 请求过于频繁(**频控**) | 是,~2s 窗口 | 退避重试 |
| `412`(HTTP) | 风控页(HTML body) | 是 | 退避重试 |
| `-352` | 风控校验失败(指纹/buvid 不满足) | 多数是 | 退避重试,必要时刷新 buvid/wbi key |
| `452` / `-452` | 首进 warm-up 软风控 | 是 | 退避重试 |
| `-401` | 非法访问(**完全不带 cookie 时**) | 否 | 不重试 |
| `-101` | 账号未登录(`x/relation/followings` 需要登录) | 否 | 不重试 |

判据统一收敛在 `SpaceHttpSupport`:可重试码 `-352/-799/452/-452` + HTTP `412/429/5xx`,
梯子 `InteractiveRetryDelaysMs = 2s/4s/6s`、`RecoveryRetryDelaysMs = 1.2s/2.4s`、
`RecoveryFallbackRetryDelaysMs = 1.2s`。**新加 space 调用必须复用这套策略,不要自判**。

## 3. 2026-09-25 实测结论(避免以后走弯路)

同款请求(同 UA / space `Referer`+`Origin` / `sec-ch-ua` / 走 `x/frontend/finger/spi` 拿真
`buvid3`+`buvid4` 再带上)在本机复现:

- **`x/space/acc/info` 旧路径没坏**:带 buvid 时 `code=0` + 正确昵称(`影视飓风`);
  **不要**切成 `x/space/wbi/acc/info` —— 它反而返回 `-352`(签名)/ `-403`(无签名),需要比
  buvid 更完整的指纹参数。
- **`x/relation/stat` 无需签名、无需登录**:裸请求即返回真实 `following`/`follower`。
  抽查 117 个 UP,仅 3 个 `following:0` —— 那 3 个是 UP 自己关了「公开我的关注列表」
  (空间隐私设置),属**服务端真 0**,不是 bug。
- **`-799` 与 buvid 有效性无关**:间隔 70s 的三发 A/B(无 cookie / 伪造 buvid / spi 真 buvid)
  分别拿到 `-401` / `code=0` / `-799` ⇒ 它随**请求节奏**波动,是瞬时频控而非身份问题。
  ⇒ 唯一的正确处置是**退避重试**,不是换接口/换 cookie。

## 4. 已知坑

- `SpaceProfileState.Failed` 目前**没有 UI 渲染分支**(TV 只有视频列表失败才有 `FeedStatusScreen`),
  资料请求失败时页面静默回落到视频卡带来的昵称头像 + 统计行 `0/0`。P11-179 已让
  `acc/info` 失败但 `relation/stat` 成功时返回「空资料 + 真统计」,不再让统计一起变 0;
  若要再加可见的「加载失败 + 重试」入口,需同时改 TV / 移动端两套页面与 6 个 locale 字符串。
- `x/relation/stat` 的 `whisper` / `black` 只对本人有意义(他人恒 0),别拿来当数据源。
- 排查时先看这三个日志:
  `BiliSpaceProfile`(资料:每级 fallback + `profile mid=… ok/partial fans=… following=…`)、
  `BiliVideoRepository`(投稿列表:`retry attempt=N delayMs=…` / `ok … videos=N`)、
  `BiliSpaceHttp`(buvid 供给:`buvid ready source=spi/fallback`)。
