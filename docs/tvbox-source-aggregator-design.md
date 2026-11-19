# TVBox 源汇总 + 校验 方案设计

状态：Part A（汇总）已实现，Part B（校验）设计定型未落码
更新：2026-11-19

## 1. 目标与拆分

把「汇总各个 TVBox 源」和「测速校验源可靠性」拆成两个独立部分：

| 部分 | 形态 | 职责 |
|---|---|---|
| A. 汇总源 | GitHub Actions 定时任务 | 拉取上游聚合源 → 结构校验 → 去重 → 按校验状态分层 → 发布订阅产物 |
| B. 校验 | **OpenWrt LuCI 插件**（跑在 op 路由器上） | 对单个源做分层测活 + 测速，写回校验状态文件 |

衔接方式：**B 在 OpenWrt 上跑，结果主存在路由器本地（2026-11-19 拍板「结果留在 op」）**，
LuCI 界面直接查；回传仓库（导出 checks.json 提交到 `state/`）为**可选**动作，只影响 A 的
`recommended.json` 分层——没有回传时 A 按 `absent=全量` 发布，行为天然兼容。
CI 不做站点测活（国内/路由器侧网络才有参考价值，也避免 Actions IP 被源站风控），只做结构校验（字节门槛/可解析/条目数）。

参考项目：

- 汇总：**隔壁 CFIP 仓库**（`E:\GITHUB\CFIP` 的 `merge_ip_libs.py` + `merge-ip-libs.yml`，同作者同风格：零依赖标准库、按源统计 stats.json、仅变更才提交、SSL certifi 回退——本项目 aggregate.py 直接沿用这套写法）；`hebijunge/tvbox-config`（分层判据）、`TVboxorg/TVbox`（清单式探测、CSP 源带原线路 jar）
- 校验：`hebijunge` 的 L1/L2/L3 分层 + 字节门槛 + 连续失败计数；LuCI 形态参考本机已有的 `luci-app-cloudflarespeedtest`、`luci-app-passwall-speedtest`（均在 E:\GITHUB），打包进 `MortOP` 路由器固件工程

## 2. 目录布局（Part A 已落码）

```
tools/tvbox/
  sources.toml            # 人工维护的权威清单：上游聚合源 + 覆盖规则
  scripts/
    aggregate.py          # Part A：拉取/结构校验/去重/分层/写 dist（CI 跑，已实现）
  luci-app-tvboxcheck/    # Part B：OpenWrt LuCI 包（引擎+界面，未实现，见 §5.0）
  state/
    checks.json           # 每个源的校验状态（probe 写，人工提交回仓库；可缺省）
    blacklist.txt          # 手动黑名单（一行一个 key，优先级最高；可缺省）
  dist/                   # 产物（aggregate.py 生成并提交，不手工编辑）
    tvbox.json            # 全量合并单仓配置
    recommended.json      # 仅 healthy 源（无校验数据时=全量并标注）
    status.md             # 人读状态表（上游级）
    status.json           # 机器读状态
.github/workflows/
  tvbox-aggregate.yml     # Part A workflow：cron 每日 + 手动 dispatch
```

## 3. 源清单 `sources.toml` 格式（已定稿）

选择 TOML 而非 YAML：Python 3.11 标准库 `tomllib` 直接解析，CI 不需要 pip install（沿用 CFIP 零依赖原则）。
只放**上游聚合源**和**覆盖规则**；展开后的单个 site 不手写，校验状态落在 `state/checks.json`。

```toml
version = 1

[[upstreams]]
name = "tvboxorg"                       # 唯一名，日志/统计用
url = "https://raw.../official.json"
kind = "config"                         # 当前仅支持 config（TVBox 单仓 JSON）
group = "聚合"
min_bytes = 10240                       # 字节门槛：低于此值视为假 200
min_items = 5                           # 条目门槛：sites 数（不含 parses/lives）
enabled = true                          # false=临时停用，保留条目与血统
note = "来源/血统备注"

[overrides]
blacklist = []        # 手动判死（site key），优先于 probe 结果
force_include = []    # 强制保留，可豁免 blacklist 与 probe 过滤
```

已选定的首批上游（2026-11-19 实测全部 200 + 合法 JSON）：

| name | url | 说明 |
|---|---|---|
| tvboxorg | `TVboxorg/TVbox` `dist/official.json` | 官方人工审清单 + CI 探测拼合，160 sites |
| hebijunge | `hebijunge/tvbox-config` `tvbox_recommended.json` | 每日自动聚合精选（实测连通），1630 sites；要全量可换 `tvbox.json` |
| fswws | `fswws/tvbox-source` `tvbox.json`（jsDelivr） | 轻量点播 9 sites，国内友好 |

落选候选（留档）：`live.fanmingming.com/en.m3u` 本机连接超时；`iptv-org.github.io/iptv/.../cn.m3u` 404；`sese972010/tvbox` subscribe.json 404。

## 4. Part A：汇总 workflow（已实现）

`.github/workflows/tvbox-aggregate.yml`，触发：cron `30 19 * * *`（UTC=北京 03:30）+ `workflow_dispatch`。

流程：checkout → setup-python 3.11 → `python tools/tvbox/scripts/aggregate.py` → 仅 dist 有变更时提交回当次触发的分支。

aggregate.py（`tools/tvbox/scripts/aggregate.py`，仅标准库）：

1. **拉取**：逐 upstream 下载（UA、25s 超时、2 次重试、certifi 回退），单源失败不中断整体，记入 status。
2. **结构校验**（CI 唯一的"校验"）：字节数 ≥ `min_bytes`、JSON 可解析、顶层结构合法、sites 数 ≥ `min_items`、sha256 指纹记录。
3. **合并去重**：sites 按 `key`（缺 key 用 `name|api`），parses 按 `name`，先到先得；`spider/wallpaper/logo` 取第一个携带上游的值。
4. **CSP 源带 jar**：`api` 以 `csp_` 开头的站点自动带上其原线路的 `spider`（单仓只能留一个全局 spider，与 TVboxorg merge.py 同思路）。实测 1044 个 csp_ 站点全部带上、0 漏挂。
5. **分层发布**：读 `state/checks.json` + `state/blacklist.txt` + overrides（`force_include` 豁免）：
   - `tvbox.json` = 全量（剔除黑名单）
   - `recommended.json` = 仅 `status == "healthy"`；无校验数据（probe 未接入）时=全量并在 status 标 `absent`；有数据但全空时兜底回全量。
     probe 跳过的站（type 3 非 B 站、searchable=0）永远进不了 healthy，checks 接入后会自动
     从 recommended 消失——即 **recommended = 本仓库（BiliMT）口径的健康子集**，`tvbox.json` 才是全客户端全量。
6. **产物**：`dist/tvbox.json`、`recommended.json`、`status.json`、`status.md`。

两个关键机制：

- **`[skip ci]`**：dist 回推的提交信息带它，GitHub 会跳过该提交触发的所有 workflow——否则 `android-build.yml`（对所有 push 触发）每天白跑一次安卓云编译。
- **schedule 只能挂默认分支**：GitHub 定时任务只执行默认分支（本仓库=main）上的 workflow。mort_debug 上只能手动 dispatch。挂接方式见 §7 待确认。

## 5. Part B：本地校验（设计定稿，形态改为 LuCI）

### 5.0 形态：OpenWrt LuCI 插件（放本仓库）

校验跑在 op 路由器上（不是 PC 脚本）。**LuCI 包放本仓库**（2026-11-19 用户拍板），
布局照 cfpick 模板：

```
tools/tvbox/luci-app-tvboxcheck/     # luci.mk 打包（本仓库内）
  Makefile                           # LUCI_DEPENDS 带 python3 子包（cfpick 同款先例）
  root/
    usr/bin/tvboxcheck               # 探测引擎 CLI（判据源码与 aggregate.py 同仓维护）
    etc/config/tvboxcheck            # UCI：订阅URL/超时/并发/判死阈值
    etc/init.d/tvboxcheck
    usr/share/rpcd/ucode/tvboxcheck.uc   # rpcd: status/start/stop/export
    usr/share/rpcd/acl.d/…json + menu.d/…json
  htdocs/luci-static/resources/view/tvboxcheck/   # status.js / settings.js
```

- 引擎与界面分离：`tvboxcheck` 纯 CLI 先在 ssh 里跑通，再包 LuCI；postinst 记得
  `rpcd reload`（cfpick 踩过的坑：rpcd 只在启动时加载 ucode 插件）。
- Python 直接带上路由器：cfpick 的 `+python3-light +python3-urllib +python3-codecs
  +python3-openssl` 依赖先例，不退回 sh。
- MortOP 引入方式：feeds 用 `src-link` 指到本仓库 `tools/tvbox/`（feed 根=含
  luci-app-tvboxcheck/ 的目录），或推远端后 src-git；不需要独立新仓库。
- 结果留 op：checks.json 主存在路由器（如 `/etc/tvboxcheck/`），LuCI 界面查历史/状态；
  路由器不碰 git。**回传可选**：想让仓库 `recommended.json` 分层生效时，手动从 LuCI 导出
  checks.json 提交到本仓库 `state/`；不回传则 CI 按 `absent=全量` 发布。
- **全程无令牌**：公开仓库 raw 订阅匿名可读；CI 用内置 GITHUB_TOKEN；回传走电脑本机凭据；
  路由器不持有任何凭据（仓库转私有才需要给订阅 URL 换 PAT，属配置项不改代码）。

### 5.1 分层测活（对 dist/tvbox.json 展开后的每个 site）

**核心判据是 L3 随机试播**（2026-11-19 拍板）：光拿到播放地址不算能用，必须真把
一条媒体分片拉通。**口径唯一（2026-11-20 拍板）：校验出的 healthy 源，用本仓库
`TvboxRepository` 播放方法必须可播**——探的就是 BiliMT 自己播得动播不动。

| 源类型 | 处置 |
|---|---|
| type 0/1（MacCMS 采集站，本仓库 config 唯一收的类型） | 本仓库 `TvboxRepository` 方法测活（下表 L1-L3） |
| type 3 蜘蛛（含 52 个 B 站类 `csp_Bili*`） | `untestable` 不测不判死——本仓库 config 直接过滤，测通了也播不了 |
| `searchable=0` | 跳过不测（本仓库 config 解析就把它过滤） |

（原 B 站类源 BV 单独试播路径已砍，2026-11-20：BV 测通 ≠ 本仓库可播，
会把不可播源洗进 recommended，与口径矛盾。）

| 层 | 判据（本仓库同款） | 通过含义 |
|---|---|---|
| L0 | HTTP 失败、非 JSON 应答（本仓库按纯文本弃）、`list` 空 | 不可用 |
| L1 | `GET {api}?ac=detail&wd=<热词>` 返回非空 `list[]` 且有条目带非空 `vod_play_url` | 有片库可出地址 |
| L2 | 随机挑一条 vod，按本仓库 `parseEpisodeList` 规则解析选集，再按需 `resolveLineUrl` 三级提取拿到流地址 | 线路可解析 |
| L3 | **试播**：拉通 m3u8 + 首个分片实读 N KB（默认 256KB，裸头） | 真能出片 |

本仓库方法细则（照 `TvboxRepository.kt` 逐条对齐）：

- **入口只有搜索，不做 `ac=list` 浏览**——本仓库没有浏览路径，取址就是
  `?ac=detail&wd=` 一步直出。热词从固定池随机取（等价用户输关键词）。
- **选集解析照 `parseEpisodeList`**：`$$$` 分组 → 含 `.m3u8` 直链的组优先 →
  `#` 分集、`集名$URL` 逐条 → 剔除官方播放页（v.qq / iqiyi / youku / mgtv /
  bilibili / sohu / 1905 / pptv / le 特征表同款）。候选里**随机取一条**（不取第一条，
  避开首集特例）。
- **线路解析照 `resolveLineUrl`**：直链 m3u8 原样用；红果播放页走 `main_url`
  明文提取分支；share/play HTML 页三级提取——①`const url = "…"` ②页内首个绝对
  m3u8 ③页内首个相对 m3u8（按页面 URL resolve）。全部失败→按 L0 计数。
- **头与超时对齐**：裸头（无 Referer/Origin/自定义 UA，与本仓库播放头一致）；
  连接 5s / 读 8s（本仓库 `httpClient` 同参）。
- 解析出的候选全是官方播放页（本仓库口径下该站无货）→ `untestable_local`，不判死。
- 分片用 Range/限量读，256KB 拉通即判过；m3u8 内 AES key URI 一并拉通（只验证可达，
  不解密）。

原「B 站类源走 BV 方法」小节已删除（2026-11-20 拍板，见上表）：
这 52 个源与其余 type 3 一同 `untestable`，不进测活、不进 recommended。

两条防误判规则（必留）：

1. **连接层失败（超时/拒绝/SSL）单次不判死** —— 可能是路由器网络/DNS 问题，记 `network_error`，只有源站返回 200 却无有效数据才立刻记 L0。
2. **连续 3 次 L0 才进黑名单**；一旦任一次测到 L3，`fail_streak` 清零并自动移出黑名单。

### 5.2 测速（试播附带）

- `ttfb_ms`：L1 请求首字节时间。
- `play_kbps`：试播分片 256KB 的实测吞吐（代替原 m3u8 拉表测速）。
- 三档：`fast`/`ok`/`slow`，仅参考，**不参与判死**（速度波动大，可用性才是硬指标）。

### 5.3 checks.json 单条结构（与 Part A 约定）

```json
{
  "key": "site-key",
  "level": 3,
  "status": "healthy",
  "ttfb_ms": 320,
  "play_kbps": 860,
  "bytes": 52341,
  "fail_streak": 0,
  "last_check": "2026-11-19T12:00:00+08:00",
  "last_ok": "2026-11-19T12:00:00+08:00",
  "reason": ""
}
```

`status` 状态机：`healthy`=最近 L3 通过；`degraded`=L1/L2 过 L3 失败；`unknown`=未测；`dead`=fail_streak ≥ 3。
文件顶层是 `{site_key: record}` 映射（aggregate.py 按此读取）。

### 5.4 已知边界（诚实声明）

- **`type 3` 蜘蛛源 / drpy 源 HTTP 测不出来**（要跑 JS/爬虫逻辑才有内容）。probe 对这类只测 `jar`/`ext` 可达性，标 `untestable_local`，**不自动判死**。深度实测列为后续可选。
- 编码偏好、播放器兼容性不在本方案范围——只回答「源在不在、能不能搜、能不能出片、多快」。

## 6. 里程碑

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 源清单 + 汇总脚本 + 本地跑通出表 | ✅ 2026-11-19，3/3 上游、1792 sites |
| M2 | `tvbox-aggregate.yml` 上线：dispatch 验绿 + 定时挂稳 | 进行中 |
| M3 | 状态机闭环：LuCI probe → checks.json 提交 → CI 进 recommended.json | 未开始 |
| M4（可选） | 蜘蛛源沙箱深测、每日快照回滚 | 按需 |

Part B 里程碑：B1 `tvboxcheck` CLI（ssh 跑通 L1-L3 试播出 checks.json，结果存 op 本地）→
B2 rpcd + LuCI 页面（状态表 + 导出按钮）→ B3 src-link 进 MortOP 固件真机跑一轮（对应 M3；
回传分层为可选增强，不阻塞 B3 验收）。
包位置：本仓库 `tools/tvbox/luci-app-tvboxcheck/`（拍板 2026-11-19）。

订阅地址（dist 提交落在哪个分支就用哪个，暂定）：
`https://raw.githubusercontent.com/urbanescavenger/BiliMT/<branch>/tools/tvbox/dist/tvbox.json`

## 7. 待确认问题

1. ~~直播~~ **已拍板：不做**（2026-11-19）——m3u 上游不接、上游内嵌 lives 也丢弃。
2. ~~发布通道~~ **已拍板：起步只用「提交回 dist/ + raw 订阅」**。
3. ~~schedule 挂哪~~ **已拍板：tools/tvbox + workflow 单独提交（跳过 CI 前缀，不动 app 代码）到 main**，订阅 URL 指向 main；之后改 sources.toml 需同步到 main。
4. ~~LuCI 包放哪~~ **已拍板：本仓库 `tools/tvbox/luci-app-tvboxcheck/`**。
