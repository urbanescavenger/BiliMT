# Plan: 设置页增加日志导出与查看入口

## Goal
在设置页给新加的日志系统补充两个交互入口：
1. **导出日志** — 把当前 logcat 写到文件，并通过系统分享/文件选择器让用户能拿到文件。
2. **显示日志** — 在应用内直接列出所有 crash / manual 日志文件，并查看内容。

## Current architecture
- 设置页是 `SettingsScreen`，左侧是 `LazyColumn` 列表，右侧可选面板（`HomeSections`、`About`）。
- 列表项用 `SettingsActionRow`/`SettingsOptionRow`/`SettingsToggleRow`。
- 交互通过回调回传到 `AppShell`，在那里启动协程或调用仓库。
- 弹窗/对话框模式已用于 `SpeedTestDialog`。
- 导航当前只有侧边栏几个一级页面，没有二级页面路由。

## Proposed approach
把「日志」做成类似「关于」的右侧面板入口：
- 设置列表新增 `SettingsItemLogs` 入口行。
- 右侧出现 `SettingsLogsColumn` 面板，里面列出所有日志文件。
- 每个日志文件行支持：
  - **查看**：点击后弹出一个全屏/居中对话框 `LogViewerDialog`，用 `LazyColumn` 显示文件内容（前 N 行，避免超大文件卡死）。
  - **导出/分享**：点击「导出当前日志」按钮，调用 `LogCatcherUtil.logLogcat(manual=true)` 生成新的 manual 日志，然后通过 `Intent.ACTION_SEND` 分享最新 manual 日志文件。
  - 对每个已有的日志文件，也可以直接分享。

## Files to change

### 1. `app/src/main/java/com/kirin/mt/core/util/LogCatcherUtil.kt`
- 增加 `logFiles()` 返回按时间排序的全部日志文件列表（含文件名、时间戳、类型 crash/manual）。
- 增加 `shareLogFile(context, file)` 用 `FileProvider` 生成 `content://` URI 并 `ACTION_SEND`。
- 增加 `readLogFileHead(file, maxLines)` 读取文件前若干行（默认 500）。

### 2. `app/src/main/AndroidManifest.xml`
- 注册一个 `FileProvider`：`androidx.core.content.FileProvider`，`authorities = "com.kirin.mt.fileprovider"`，指向 `files/crash_logs`。

### 3. `app/src/main/res/xml/file_paths.xml`（新建）
- 定义 `files-path name="crash_logs" path="crash_logs/"`。

### 4. `app/src/main/java/com/kirin/mt/ui/settings/SettingsScreen.kt`
- 新增 `SettingsItemLogs` 常量，加入 `settingFocusRequesters`、`SettingsFocusableItems`、`settingsItemToLazyIndex`。
- 在 `LazyColumn` 里新增「日志」入口行（放在「关于」附近）。
- 右侧 `SettingsRightPanel` 增加 `Logs` 分支。
- 新增回调：`onLogsSelected()`、`onViewLog(file)`、`onExportLog()`、`onShareLog(file)`。

### 5. `app/src/main/java/com/kirin/mt/ui/settings/SettingsRightPanels.kt`
- 新增 `SettingsLogsColumn(
    files: List<LogFile>,
    onView: (LogFile) -> Unit,
    onShare: (LogFile) -> Unit,
    onExport: () -> Unit,
    onMoveLeftToSettings: () -> Boolean,
  )`。

### 6. 新建 `app/src/main/java/com/kirin/mt/ui/settings/LogViewerDialog.kt`
- 显示文件名、文件大小、文件内容前 500 行。
- 提供「分享」「关闭」按钮。
- 用 `LazyColumn` + `Text` 渲染，避免一次性加载超大文件。

### 7. `app/src/main/java/com/kirin/mt/ui/shell/AppShell.kt`
- 维护 `var logViewerFile by remember { mutableStateOf<File?>(null) }`。
- 把回调传给 `SettingsScreen`：
  - `onLogsSelected`：toggle 右侧面板。
  - `onExportLog`：调用 `LogCatcherUtil.logLogcat(manual=true)`，刷新文件列表，Toast 提示。
  - `onShareLog(file)`：调用 `LogCatcherUtil.shareLogFile(context, file)`。
  - `onViewLog(file)`：`logViewerFile = file`。
- 当 `logViewerFile != null` 时在最上层显示 `LogViewerDialog`。

### 8. `app/src/main/res/values/strings.xml`
- 新增日志相关文案。

### 9. `app/src/main/java/com/kirin/mt/core/util/LogCatcherUtil.kt` / `SettingsRightPanels.kt` 类型
- 定义 `data class LogFileInfo(val file: File, val type: LogType, val createdAt: Date)`，方便 UI 显示。

## Out of scope
- 日志实时滚动显示（只显示已落盘文件）。
- 日志内容搜索/过滤。
- 日志自动上传到服务器。
- 崩溃后自动弹窗（保持静默写文件，不打扰用户）。

## Verification
- 云编译通过。
- 真机上：设置页 → 系统设置/关于附近看到「日志」入口 → 点开后列出日志 → 点击文件查看内容 → 点击分享能唤起系统分享。
