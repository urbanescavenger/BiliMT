# Crashlytics 云端已有日志的程序化拉取方法

> 2026-08-30 研究并实测跑通。背景:App 通过 `FirebaseLogSender`(recordException + sendUnsentReports)
> 手动/自动上报日志到 Firebase Crashlytics;本文记录如何从云端把这些日志完整拉回来。
> SDK 端(设备侧)没有任何读回 API,只能从云端取。

## 1. 平台与标识(必背)

| 项 | 值 |
|---|---|
| 平台 | Firebase Crashlytics |
| service | `https://firebasecrashlytics.googleapis.com` |
| API 版本 | `v1alpha`(公开 REST 参考);v1beta 未对公开 REST 提供 |
| project_id | `bilimt`(仅控制台/`--project` 参数用) |
| **project number** | `392012664321`(REST 路径只能用它,用 `bilimt` 一律 HTML 404) |
| release app id | `1:392012664321:android:1c43e185454e981012fa00`(com.kirin.mt;**查询 404**,无数据) |
| **debug app id** | `1:392012664321:android:57b895940878fb9f12fa00`(com.kirin.mt.debug;真机测试数据全挂在这) |

⚠️ **坑 1**:REST 路径用 project number 而不是 project_id。
来源:firebase-tools 源码 `lib/crashlytics/utils.js` 的 `parseProjectNumber()`。

⚠️ **坑 2**:数据挂在 debug app id。真机测试装的都是 debug 包,上报全记在
debug app 名下;对 release app id 查任何报告/事件都返回
`404 "Requested entity was not found"`。查全量 app 列表:`firebase apps:list --project bilimt`。

## 2. 前置条件

1. **代理**:国内直连 Google 不可达(DNS 都解析不了)。本机代理开着后 curl 直连即可,
   不需要显式 `-x`(实测 TUN/系统代理生效时 curl 正常)。判断:HTTP 000 + 失败说明没通。
2. **firebase-tools**(npm 包,15.28.2 实测):安装在 lcchat 环境的全局 npm,
   shim 在 `C:\Users\Mort\AppData\Roaming\npm\firebase`。安装要在 conda lcchat 环境下:
   ```bash
   source D:/PG/CONDA/etc/profile.d/conda.sh && conda activate lcchat
   npm install -g firebase-tools
   firebase --version   # 若报 Cannot find module,重跑上面的 install 即可修复残缺包
   ```
3. **登录拿 user OAuth**:`firebase login --no-localhost` 会打印一段授权 URL + session code,
   浏览器(走代理)打开授权,拿到形如 `4/0ATs...` 的 code,再 `firebase login "<code>"` 完成。
   登录成功后凭证落在 `~/.config/configstore/firebase-tools.json`。

⚠️ **坑 3(鉴权类型)**:**必须 user OAuth**。service account(ADC/GOOGLE_APPLICATION_CREDENTIALS)
调 Crashlytics v1alpha 端点恒返回 404 "Method not found"(GitHub firebase-tools #9876/#10004,
官方确认未支持)。

## 3. 拉取配方(实测命令)

### 推荐方式:现成脚本 [scripts/fetch_crashlytics_logs.py](../scripts/fetch_crashlytics_logs.py)

token 只从本地 configstore 运行时读取,代码里无任何凭证,可安全提交;access_token
过期时自动用 refresh_token 换新并回写(refresh_token 长期有效,通常永不过期,
只有改密码/主动撤销才需要重新 `firebase login`)。

⚠️ **坑 5**:conda 环境的 openssl 加载 Windows 证书库可能撞上坏证书,报
`ssl.SSLError: [ASN1: NOT_ENOUGH_DATA]`。脚本已内置 certifi CA bundle 兜底
(`pip install certifi` 即可),不再走系统证书库。

另:`/events` 端点**必须带 `filter.issue.id`**,不带直接 400;issue 发现只能走
topIssues(默认 7 天,支持 `filter.interval.start_time/end_time` 拉长窗口)。

```bash
# 列最近 issue(7 天,找 "Manual log share: xxx.log")
python scripts/fetch_crashlytics_logs.py issues

# 列某 issue 全部事件摘要
python scripts/fetch_crashlytics_logs.py events --issue f83626fd107dfed690db1b30c98c9372 --list-only

# 导出最新事件日志正文到 tmp/crashlytics_<eventId>.log(--event-index 1 取次新)
python scripts/fetch_crashlytics_logs.py events --issue f83626fd107dfed690db1b30c98c9372
```

### 手动 curl 方式(等价,便于排查)

```bash
TOKEN=$(python -c "import json;print(json.load(open(r'C:\Users\Mort\.config\configstore\firebase-tools.json'))['tokens']['access_token'])")
PNUM=392012664321
APP='1:392012664321:android:57b895940878fb9f12fa00'   # debug app id
BASE="https://firebasecrashlytics.googleapis.com/v1alpha/projects/$PNUM/apps/$APP"
```

⚠️ **坑 4**:查询参数是 **snake_case**(如 `page_size`),驼峰 `pageSize` 被静默忽略。

**① 拉 reports 列表(确认 app/report 存在,200 即通了)**
```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/reports"
```

**② topIssues(手动上报 → 找 "Manual log share: xxx.log" 的 issue)**
```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/reports/topIssues?page_size=15" -o tmp/top_issues.json
```
返回 `groups[].issue`:`id`(hex)、`title`、`subtitle`、`errorType`、`state`、`uri`(控制台链接)、
`sampleEvent`、`firstSeen/lastSeenVersion`。

**③ 拉 issue 的 events(日志正文在这)**
```bash
ISSUE=f83626fd107dfed690db1b30c98c9372   # 从 topIssues 的 issue.id 拿
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/events?filter.issue.id=$ISSUE&filter.issue.error_types=NON_FATAL&page_size=5" \
  -o tmp/events.json
```
返回分页 `events[]`(每条平铺一层,不是 {event:{}} 包裹):

```python
import json
d = json.load(open('tmp/events.json', encoding='utf-8'))
evs = d['events']                       # 平铺结构
evs.sort(key=lambda e: e['eventTime'], reverse=True)
e = evs[0]
e['eventTime']; e['receivedTime']       # 上报时/服务端收
e['version'];  e['device'];  e['operatingSystem']
e['customKeys']                         # {'log_file': 'logs_live.log', 'log_size': '85636', ...}
logs = e.get('logs', [])                # [{'logTime': ..., 'message': <原始 logcat 行>}, ...]
e['exceptions']                         # [{"type": "RuntimeException", "exceptionMessage": "Manual log share..."}]
```

写入文本:
```python
with open('tmp/latest_event_logs.txt', 'w', encoding='utf-8') as f:
    for l in logs:
        f.write(f"[{l.get('logTime','')}] {l.get('message','')}\n")
```

## 4. 数据形态与限制

- **App 侧上送时机(alpha.11 起)**:采集属性恒关,上传全走显式 `sendUnsentReports()`——
  手动分享恒即时;开关开时启动送行一次送掉崩溃报告积压。alpha.10 及之前采集跟随开关时,
  开着的手动分享会被 SDK 的 no-op 语义拖到「下次启动/退后台」才送达(eventTime 与
  receivedTime 差 11~36 分钟即此因),对账时注意老数据有这个延迟。
- 事件保留 **90 天**(filter interval 只能往前 90 天;topIssues 默认 7 天)。
- 每个 event 的 `logs[]` 是注入的原始日志行。上限:**Crashlytics 环形缓冲 ~64KB(按字节计)**,
  超限自动丢最旧。2026-08-30 起 App 侧(FirebaseLogSender `selectLinesForInjection`)在注入前
  做降噪压缩:连续重复行折叠、HWUI/CCodec 等噪音 tag 整类丢、56KB 字节预算从尾往前装
  (高价值 tag 无条件保留),避免预算被刷屏行吃光后云端只剩一段无效行(如 r1700 曾 1500 行
  注入只截到 82 行纯 HWUI 噪音)。
- NON_FATAL 事件带 `exceptions[0].exceptionMessage = "Manual log share: <文件名>"`,
  可以按这个字符串定位手动上报。
- `customKeys` 里有上报时打的 `log_file` / `log_size`,可和本地文件对账。
- issue 有 `sampleEvent` 名,`events?filter.issue.id=` 列全部;支持
  `filter.version.display_names`、`filter.interval.start_time/end_time`(ISO 8601)等过滤。

## 5. Token 维护

- access token 有效 ~1 小时,存 `~/.config/configstore/firebase-tools.json` 的
  `tokens.access_token`;过期用 `tokens.refresh_token` 换新:

```bash
# firebase-tools 内置 client(公开值,源码 lib/api.js)
CLIENT_ID=563584335869-fgrhgmd47bqnekij5i8b5pr03ho849e6.apps.googleusercontent.com
CLIENT_SECRET=j9iVZfS8kkCEFUPaAeJV0sAi
REFRESH=$(python -c "import json;print(json.load(open(r'C:\Users\Mort\.config\configstore\firebase-tools.json'))['tokens']['refresh_token'])")
curl -s https://oauth2.googleapis.com/token -d client_id=$CLIENT_ID -d client_secret=$CLIENT_SECRET \
  -d refresh_token=$REFRESH -d grant_type=refresh_token | python -c "import json,sys;print(json.load(sys.stdin)['access_token'])"
```

- refresh token 长期有效,一般不用重新 `firebase login`。
- 也可直接 `firebase projects:list` 验证 CLI 认证是否还活着。

## 6. 备选路线(未走但有据可查)

- **Firebase 控制台**(最简单,不需要 CLI):代理下开
  `console.firebase.google.com` → bilimt → Crashlytics → Non-fatal issue "Manual log share: ..." 详情页。
- **firebase-tools MCP**:把 `firebase mcp` 挂进 Claude Code,内置 crashlytics
  list_issues/get_issue 等工具(内部同样走 v1alpha + user OAuth,和本文同源),适合常态化查询。
- **BigQuery 导出**:需 Blaze 计费计划,本项目不适用。

## 7. 单事件实例(验证记录)

| 字段 | 值 |
|---|---|
| eventTime | 2026-08-30T05:29:04Z( received 06:05:48Z,≈36min 同步延迟) |
| version | dev.r1696 (1001696) |
| device | Sony XQ-EC72 · Android 16 |
| customKeys | log_file=logs_live.log, log_size=85636 |
| logs | 520 行(85KB 日志,环形缓冲截断为尾部) |
| issue | f83626fd107dfed690db1b30c98c9372, NON_FATAL, OPEN, 7 天 12 次 |