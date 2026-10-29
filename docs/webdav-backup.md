# WebDAV 备份:选型与客户端可靠性

本文回答两个问题:①这个应用对 WebDAV 服务端的**最小要求**是什么、该选哪套开源方案;②客户端侧为了让
「备份一次就成」还缺什么。

## 1. 我们对服务端的契约(决定了选型范围)

客户端只用四个动作([`WebDavRepository`](../app/src/main/java/com/kirin/mt/core/webdav/WebDavRepository.kt)):

| 动作 | 用途 | 备注 |
|---|---|---|
| `GET` | 连通探测(`ping`)/ 下载配置与频道备份 | 探测把 **405 也算通**(集合根常拒绝 GET,但能应答即说明 host 通 + 认证路径在) |
| `MKCOL` | 建 `bilitv/`、`bilitv/logs/` | **405 视为成功**(目录已存在) |
| `PUT` | 上传配置 JSON / 日志文件(同名**覆盖**) | 体积 1KB(配置)~ **5MB**(实时日志) |
| `GET` | 还原时下载 | 同上 |

认证:**HTTP Basic**。路径:`{url}/bilitv/…`、`{url}/bilitv/logs/…`。

**不需要**:同步语义、版本历史、分享、Web UI、客户端 App、SSO、S3 后端。任何"完整网盘"都远超所需。

## 2. 真机证据:问题不在客户端协议,在「主机的冷启动 + 客户端不重试」

`logs_live_20261012_235118.log`(debug r2201,BRAVIA AE2,主机 `cf.19961226.xyz`,Cloudflare 前置):

```
23:10:01  put code=201 ok=true bytes=5050714 elapsed=12090ms    ← 边缘热:5MB 只要 12s
23:24:00  put failed elapsed=169824ms SocketTimeoutException    ← 边缘冷:同一份包挂 170s
23:49:51  put failed elapsed=32051ms  SocketTimeoutException
23:51:03  put failed elapsed=36234ms  SocketTimeoutException
23:47:25  ping attempt=1/3 failed elapsed=8753ms timeout=8s     ← 连探测都要第二档 15s 才通
23:47:27  ping attempt=2/3 code=200 ok elapsed=2498ms timeout=15s
```

同一分钟里同一份 5MB 实时日志试了 3~4 次才成 —— 用户视角就是「**备份多次才成功**」。
即:**主机(Cloudflare 边缘/回源)冷启抖动**是外因,而**客户端 `put`/`get` 单发无重试**是内因。

## 3. 选型:主流开源方案

| 类别 | 方案 | 语言/体积 | 与我们的契合度 |
|---|---|---|---|
| **单文件超轻量**(摊现有目录) | **dufs** | Rust,单二进制 ~2.5MB,Apache-2.0 | ✅ **首选**:`PROPFIND`/`MKCOL`/`MOVE`/`COPY` + `PUT`(建/覆盖);额外支持 `PATCH + X-Update-Range` 断点续传;Basic/Digest 认证;无内置限速/日志 |
| | **copyparty** | Python 单文件 0.6MB,MIT | ✅ 上传能力最强(**可续传 + 分块 + 校验 + 去重**),WebDAV/FTP/SFTP 内置;代价是 UI 非主流、参数多 |
| | **chfs** | 单文件,国产 | ✅ 双击即用,Windows 友好,家用够 |
| | **rclone serve webdav** | Go | ⚪ 实现保守,够用不亮眼 |
| | **hacdias/webdav** | Go | ⚪ 纯 WebDAV,配置极简 |
| **聚合型**(别的网盘→WebDAV) | **OpenList**(AList 社区 fork) | AGPL,Docker `openlistteam/openlist` | ⚠️ AList 2025-06 被收购引发投毒信任危机,社区 fork 接手;**安全评分偏低**(path traversal CVE-2026-25059/25060、默认关 TLS 校验、SimpleHttp 任意文件写入已在 v4.2.3 修)⇒ 只在受控内网 + 及时升级下用 |
| **重型完整网盘** | Nextcloud / Seafile / Cloudreve v4 / FileBrowser | PHP+DB / 块同步 / Go / Go | ❌ 体量大一个数量级;要求把文件搬进自己的存储;**小文件上传吞吐据实测比单文件方案慢 1~2 个数量级**;Cloudreve 的 WebDAV 支持各家说法矛盾(需核) |
| 商业补充(非开源) | 坚果云 / InfiniCLOUD | — | 坚果云是国内**原生 WebDAV** 最稳的,但免费版 **1GB/月上传 + 每 30 分钟 ≤600 次请求**;InfiniCLOUD(日本)国内直连尚可 |

**结论**:有 VPS/NAS 就上 **dufs**(要续传/校验上 copyparty);**别套 Cloudflare**(本文第 2 节的抖动就是
CF 边缘冷启);挂现成网盘用商业的坚果云,自建聚合才用 OpenList 并注意安全。我们这种「小文件 + 覆盖写」
用途**明确不上** Nextcloud/Seafile/Cloudreve。

## 4. 客户端侧改动(P11-256)

`put`/`get` 原来各自单发一次,失败即抛;而 `ping` 早就有 `probeLadder`(8s→15s→25s,见 P11-174/P11-176)。
本次把**同一套阶梯接到传输路径上**:

- **重试判据**:`IOException`(超时/断连/重置)与 **5xx**(CF 边缘/源站冷启常见的 502/503/504)才重试;
  其余 HTTP 码(401/403/404/409…)是服务器的明确回答,**立即返回不空耗三档**。
- **档位**:复用 `probeLadder`(共享连接池,`ping` 成功后连接已热身)。
- **失败语义统一**:阶梯跑完仍失败 → 返回 `false`/`null`,**不再向上抛 IOException** —— 由调用方
  (`WebDavBackupService` 各入口)生成带上下文的失败消息(如「日志上传失败:logs_live_xxx.log」),
  判读细节(第几次、哪个档、什么原因)全在日志里:`put attempt=2/3 failed elapsed=… timeout=15s …` /
  `put give-up attempts=3 last=…`。
- **覆盖写是幂等的**:`PUT` 同名覆盖,所以「服务端已收但响应超时」时重发整包不会损坏数据。

### 补充:系统 DNS 只回一个坏 IP 时(P11-304)

`HealthyDns`(P11-269)的「坏 IP 排到最后」有个前提——候选集里得有健康地址。真机
`logs_live_20261029_230839.log` 10-28 22:59–23:00 打破了它:

```
22:59:55  lookup cf.19961226.xyz -> 172.67.153.187 (bad=)
23:00:03  ip marked bad (300s): 172.67.153.187
23:00:03  ping attempt=1/3 failed elapsed=9022ms  timeout=8s
23:00:18  ping attempt=2/3 failed elapsed=15021ms timeout=15s
23:00:19  ping attempt=3/3 failed elapsed=1248ms  SocketException: Connection reset
23:00:19  webdav prewarm failed: 25291ms
```

系统这次**只回了一个地址**,而且正是被网络 RST 的那个;「全部拉黑仍原样返回」的防锁死逻辑
于是只能把同一个坏 IP 再交出去。同一时刻在 PC 上用四个公共解析器查,该域名还有
`104.21.3.225` 与 `2606:4700:…:6815:3e1`(实测可用)⇒ 是**系统解析结果缺项**。

改动:系统答案里没有任何健康地址时,`HealthyDns` 用 DoH(`https://223.5.5.5/resolve`,
IP 字面量端点,避免在 DNS 已不可信时再去做主机名自举)补查 A;A 没给出新地址再查 AAAA。
结果缓存 300s,按 IP 去重合并进候选集,仍按健康优先排序。

判据:`doh supplement cf.19961226.xyz -> …` 只在系统答案全坏时出现;随后 ping 应落到补全地址
并成功。系统答案健康时**不触发** DoH,正常解析没有额外开销。

### 未做(留待有实测需求再开)

- **分块/可续传**:dufs 的 `PATCH + X-Update-Range`、copyparty 的分块上传客户端侧都还没接;当前靠
  「整包重发(最多 3 档)」扛。日志最大 5MB,现状够用。
- **`mkcol` 未上阶梯**:它紧跟成功的 `ping`(连接已热身)、请求体为空,实测从未失败;为控制改动面未动。
- **传输超时按体量自适应**:现在沿用阶梯档位(8/15/25s),没有按 body 大小放大。

## 5. 验收(真机)

- 对**冷边缘**(CF 前置那台)连按 3 次「备份」:每次要么一次成功,要么在同一档案里自己跨到第 2/3 档成功
  —— **不应**再出现「必须手动再按一次才成」。
- 日志应有 `put attempt=N/M …` 的阶梯轨迹;失败时是 `put give-up attempts=3 last=…`(而不是裸的
  `put failed … SocketTimeoutException` 单行)。
- 热连接下行为不变:第 1 档 8s 内 `code=201`。

## 6. 参考来源

- [国内网盘原生支持 WebDAV 的都有哪些 — V2EX](https://www.v2ex.com/t/1216022)
- [坚果云 WebDAV 与其他网盘对比(限制/稳定性)— 知乎](https://zhuanlan.zhihu.com/p/2077799928009585347)
- [全网最全 WebDAV 资源清单 — 啊靓啊笔记](https://alianga.com/articles/webdav-softwares)
- [AList 被卖事件时间线 — 码农真经](https://blog.mzh.ren/zh/posts/2025/06/alist/)
- [OpenList 版本与安全评分(2026-07 核验)— develata.me](https://develata.me/Repo-AI-Analysis/analysis/cloud-server/storage-aggregation/openlist)
- [VPS 搭建 OpenList(AList):Docker + WebDAV 教程 2026](https://vpscost.com/blog/post/vps-openlist-alist-docker-webdav-guide-2026)
- [copyparty 与同类文件服务器全景对比(上传能力矩阵)— GitCode 博客](https://blog.gitcode.com/14cd13a953b517d230b332419f54dba9.html)
- [copyparty vs Dufs(2026)— selfhostindex](https://selfhostindex.com/compare/copyparty-vs-dufs/)
- [Cloudreve vs Nextcloud vs Seafile vs FileBrowser vs Immich (2026)— ComputingForGeeks](https://computingforgeeks.com/cloudreve-vs-nextcloud-seafile-filebrowser-immich/)
- [6 款开源网盘方案对比与部署 — 加码频道](https://unboxvps.com/vps-self-hosted-cloud-drive-tutorial/)
- [sigoden/dufs(`PATCH + X-Update-Range` 断点续传)— DeepWiki](https://deepwiki.com/sigoden/dufs)
