# 应用内更新下载:崩溃与慢速判读

## 2026-10-19 移动端「下载更新」崩溃(已修)

### 取证

日志:`logs_live_20261019_142341.log`(设备实时日志,com.kirin.mt release,minify)。

```
14:23:30.825 E LogCatcher: javax.net.ssl.SSLHandshakeException: connection closed
14:23:30.972 E AndroidRuntime: FATAL EXCEPTION: main
    at com.kirin.mt.core.update.UpdateDownloader.downloadOnce
    at com.kirin.mt.core.update.UpdateDownloader.access$downloadOnce
    at com.kirin.mt.core.update.UpdateDownloader$download$3.invokeSuspend
14:23:31.1   新进程 27556 启动 MainActivity
```

### 崩溃链

1. GitHub release asset 的 TLS 握手被对端关闭 → `SSLHandshakeException`(底层 `EOFException`)。
2. `UpdateDownloader.download()` 的 5 次重试(退避 1+2+3+4s)全部失败,最后一次错误被 rethrow。
3. `UpdateManager.download()` 把状态退回 `Available` 后继续 rethrow。
4. 移动端 `MobileSettingsScreen` 的 `downloadAction` **没有 try/catch**,异常从
   `rememberCoroutineScope().launch` 逃逸到 Android 未捕获处理器 → `FATAL EXCEPTION: main`
   → 进程被杀、Activity 重启(表现为「重载」)。
   TV 端 `AppShell` 一直有 try/catch,所以在 TV 上同样失败只弹 Toast。

### 修复

- `MobileSettingsScreen.downloadAction`:补 try/catch + `settings_update_download_failed_with_message` Toast
  (与 TV 对齐)。
- `UpdateDownloader`:新增 `BiliMT:Update` 日志(开始/每次尝试/成功速率/放弃),
  以后可直接从实时日志判断失败宿主与耗时。
- `UpdateDownloader`:修正 `callTimeout` 单位 —— 原代码把毫秒值按 `TimeUnit.SECONDS` 传入,
  实际上限是 600000 秒(≈7 天),整调用兜底形同虚设;改为 `MILLISECONDS`,保持 [10min, 30min]。
- 下载读缓冲 8KB → 64KB;进度回调从「每 8KB 一次」改为按时间节流(200ms 一次),
  避免整个设置页随下载高频重组。

## 为什么下载感觉慢

1. **网络路径是主因**:日志显示设备所有出站请求都走本地代理 `127.0.0.1:10808`
   (`IptvNet: connectStart 127.0.0.1:10808`)。GitHub API 能连通不代表 asset 宿主
   (`release-assets.githubusercontent.com`) 也快;同一设备上 WebDAV(`cf.19961226.xyz`)、
   IPTV、YouTube 都反复出现 `SSLHandshakeException: connection closed`,说明代理/上游不稳定。
   这不是应用侧能修好的,只能换节点或直连。
2. **重试会「看起来很慢」**:断流后最多 5 次尝试,每次失败要等退避 + 30s readTimeout;
   期间 UI 一直停在「下载中」。失败日志现在能看到每次尝试的部分字节数,可区分「完全连不上」
   与「传到一半被断」。
3. **代码侧次要开销**(已优化):8KB 小缓冲 + 每 8KB 推一次 StateFlow 会让读循环和 UI 互相拖;
   64KB + 200ms 节流后读循环基本只受网络限制。
4. **包本身**:universal APK(当前约 8.7MB,历史上 27.5MB)。按 32KB/s 慢速底线估算,
   8.7MB 也需要约 4.5 分钟;先看新日志里的 `avg=...KB/s` 再判断是否异常。

### 判读方法

下载后查实时日志:

```
BiliMT:Update: download start: name=... size=... url=...
BiliMT:Update: downloadOnce: name=... code=200/206 base=... start=... total=...
BiliMT:Update: download attempt=n/5 failed: ... partial=x/y
BiliMT:Update: download complete: ... elapsed=...ms avg=...KB/s
```

- `avg` < 100KB/s 且 `partial` 持续增长 → 代理/节点限速;
- `partial=0` 且失败很快 → TLS/连接被拒(代理规则或宿主不可达);
- `partial` 每次从同一位置续传 → 断流,但 Range 生效,不是整包重下;
- 出现 `code=200` 且 `base>0` → 服务端不认 Range,该次会整包重下。
