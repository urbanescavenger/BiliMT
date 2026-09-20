package com.kirin.mt.ui.settings

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import android.widget.Toast
import com.kirin.mt.R
import com.kirin.mt.core.i18n.ChineseTextVariant
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.player.CodecCapability
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.DefaultPlaybackSpeed
import com.kirin.mt.core.player.PlaybackBufferMax
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.YoutubeCodecPreference
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.player.SpeedTestUiState
import com.kirin.mt.core.player.YoutubeDefaultQuality
import com.kirin.mt.core.player.YoutubeDeliveryPriority
import com.kirin.mt.core.player.YoutubeStartQuality
import com.kirin.mt.core.youtube.YoutubeContentRegion
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.core.settings.AppVisualPerformanceMode
import com.kirin.mt.core.storage.UserSession
import com.kirin.mt.core.settings.HomeThemeVariant
import com.kirin.mt.core.update.UpdateUiState
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.core.webdav.WebDavBackupState
import com.kirin.mt.ui.focus.focusDiag
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
  settings: AppSettings,
  cacheSizeText: String,
  codecCapability: CodecCapability,
  modifier: Modifier = Modifier,
  firstItemFocusRequester: FocusRequester,
  userSession: UserSession,
  onAccountClick: () -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onVisualPerformanceModeChange: (AppVisualPerformanceMode) -> Unit,
  liquidGlassCardsSupported: Boolean,
  onLiquidGlassCardsEnabledChange: (Boolean) -> Unit,
  onHomeThemeVariantChange: (HomeThemeVariant) -> Unit,
  onChineseTextVariantChange: (ChineseTextVariant) -> Unit,
  onClearCache: () -> Unit,
  onSeekPreviewSpritesEnabledChange: (Boolean) -> Unit,
  onPlaybackQualityPreferenceChange: (PlaybackQualityPreference) -> Unit,
  onYoutubeDefaultQualityChange: (YoutubeDefaultQuality) -> Unit,
  /** P11-131:YouTube 专属解码器 / 起播倍速(与 B站 的两项相互独立)。 */
  onYoutubePlaybackCodecPreferenceChange: (YoutubeCodecPreference) -> Unit,
  onYoutubeDefaultSpeedChange: (DefaultPlaybackSpeed) -> Unit,
  onYoutubeStartQualityChange: (YoutubeStartQuality) -> Unit,
  onYoutubeContentRegionChange: (YoutubeContentRegion) -> Unit,
  onDefaultPlaybackSpeedChange: (DefaultPlaybackSpeed) -> Unit,
  onPlaybackBufferMaxChange: (PlaybackBufferMax) -> Unit,
  onPlaybackCodecPreferenceChange: (PlaybackCodecPreference) -> Unit,
  onPlaybackCdnPreferenceChange: (PlaybackCdnPreference) -> Unit,
  onAirJumpAssistantEnabledChange: (Boolean) -> Unit,
  onConfirmPlaybackExitChange: (Boolean) -> Unit,
  onAutoPlayNextEpisodeChange: (Boolean) -> Unit,
  onAutoPlayRelatedVideoChange: (Boolean) -> Unit,
  onAutoReturnHomeOnCompletionChange: (Boolean) -> Unit,
  onShowClockChange: (Boolean) -> Unit,
  onShowMiniProgressBarChange: (Boolean) -> Unit,
  onPlayerLogOverlayEnabledChange: (Boolean) -> Unit,
  onCrashLogAutoReportChange: (Boolean) -> Unit,
  onAutoConfirmOnFocusChange: (Boolean) -> Unit,
  onAutoRefreshOnSwitchChange: (Boolean) -> Unit,
  onHomeSectionEnabledChange: (HomeSection, Boolean) -> Unit,
  onHomeSectionsOrderChange: (List<HomeSection>) -> Unit,
  logFiles: List<LogCatcherUtil.LogFileInfo>,
  isRecordingLog: Boolean,
  viewingLogFile: java.io.File?,
  onViewLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onBackFromLogView: () -> Unit,
  onShareLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onBackupLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onSendLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onToggleLogRecording: () -> Unit,
  updateState: UpdateUiState,
  onCheckUpdate: () -> Unit,
  onDownloadUpdate: () -> Unit,
  onInstallUpdate: () -> Unit,
  onOpenReleaseNotes: () -> Unit,
  speedTestState: SpeedTestUiState,
  onRunSpeedTest: () -> Unit,
  onDismissSpeedTest: () -> Unit,
  channels: List<com.kirin.mt.core.youtube.YoutubeChannel>,
  onAddYoutubeChannel: suspend (String) -> Boolean,
  onRemoveYoutubeChannel: suspend (String) -> Boolean,
  webDavConfig: com.kirin.mt.core.webdav.WebDavConfig,
  onWebDavConfigChange: suspend (com.kirin.mt.core.webdav.WebDavConfig) -> Result<com.kirin.mt.core.webdav.WebDavConfig>,
  onWebDavBackup: suspend (com.kirin.mt.core.webdav.WebDavConfig, Set<com.kirin.mt.core.webdav.WebDavBackupItem>) -> Result<Unit>,
  onWebDavRestore: suspend (com.kirin.mt.core.webdav.WebDavConfig, Set<com.kirin.mt.core.webdav.WebDavBackupItem>) -> Result<Int>,
  onIptvSourceConfigChange: (url: String, username: String, password: String) -> Unit,
  onTvboxConfigChange: (url: String) -> Unit,
  onPipedInstanceChange: (url: String) -> Unit,
  onYoutubeUsePipedChange: (Boolean) -> Unit,
  onYoutubeDeliveryPriorityChange: (YoutubeDeliveryPriority) -> Unit,
) {
  val settingsListState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val context = LocalContext.current
  val density = LocalDensity.current
  val settingsRowFallbackHeightPx = with(density) {
    (BiliSizing.SettingsRowHeight + BiliSpacing.Md).roundToPx()
  }
  val settingsScrollInsetPx = with(density) {
    BiliSpacing.Md.roundToPx()
  }
  val settingFocusRequesters = remember {
    mapOf(
      SettingsItemAccount to FocusRequester(),
      SettingsItemPlaybackQuality to FocusRequester(),
      SettingsItemYoutubeDefaultQuality to FocusRequester(),
      SettingsItemYoutubeStartQuality to FocusRequester(),
      SettingsItemDefaultSpeed to FocusRequester(),
      SettingsItemPlaybackBufferMax to FocusRequester(),
      SettingsItemChineseTextVariant to FocusRequester(),
      SettingsItemClearCache to FocusRequester(),
      SettingsItemPlaybackCodec to FocusRequester(),
      SettingsItemPlaybackCdn to FocusRequester(),
      SettingsItemSeekPreviewSprites to FocusRequester(),
      SettingsItemAirJumpAssistant to FocusRequester(),
      SettingsItemConfirmPlaybackExit to FocusRequester(),
      SettingsItemAutoPlayNextEpisode to FocusRequester(),
      SettingsItemAutoPlayRelatedVideo to FocusRequester(),
      SettingsItemAutoReturnHomeOnCompletion to FocusRequester(),
      SettingsItemShowClock to FocusRequester(),
      SettingsItemShowMiniProgressBar to FocusRequester(),
      SettingsItemPlayerLogOverlay to FocusRequester(),
      SettingsItemCrashLogAutoReport to FocusRequester(),
      SettingsItemAutoConfirmOnFocus to FocusRequester(),
      SettingsItemAutoRefreshOnSwitch to FocusRequester(),
      SettingsItemVisualPerformanceMode to FocusRequester(),
      SettingsItemLiquidGlassCards to FocusRequester(),
      SettingsItemHomeThemeVariant to FocusRequester(),
      SettingsItemUpdateCurrentVersion to FocusRequester(),
      SettingsItemUpdateDownloadOrInstall to FocusRequester(),
      SettingsItemUpdateReleaseNotes to FocusRequester(),
      SettingsItemSpeedTest to FocusRequester(),
      SettingsItemHomeSections to FocusRequester(),
      SettingsItemYoutubeChannels to FocusRequester(),
      SettingsItemYoutubeContentRegion to FocusRequester(),
      SettingsItemPiped to FocusRequester(),
      SettingsItemYoutubeUsePiped to FocusRequester(),
      SettingsItemYoutubeDeliveryPriority to FocusRequester(),
      SettingsItemWebDav to FocusRequester(),
      SettingsItemWebDavBackup to FocusRequester(),
      SettingsItemWebDavRestore to FocusRequester(),
      SettingsItemIptv to FocusRequester(),
      SettingsItemTvbox to FocusRequester(),
      SettingsItemLogs to FocusRequester(),
      SettingsItemAbout to FocusRequester(),
      // P11-131/P11-148:「YouTube 设置」三项 requester(缺项会让 focusSettingItem 静默 no-op,
      // 行可达但焦点恢复坏掉)。
      SettingsItemYoutubeSettings to FocusRequester(),
      SettingsItemYoutubeDefaultSpeed to FocusRequester(),
      SettingsItemYoutubeCodec to FocusRequester(),
    )
  }
  var lastFocusedSettingItem by remember { mutableIntStateOf(SettingsItemAccount) }
  var focusSettingJob by remember { mutableStateOf<Job?>(null) }
  var rightPanel by remember { mutableStateOf(SettingsRightPanel.None) }
  var showWebDavDialog by remember { mutableStateOf(false) }
  var showIptvDialog by remember { mutableStateOf(false) }
  var showTvboxDialog by remember { mutableStateOf(false) }
  var showPipedDialog by remember { mutableStateOf(false) }
  var showBackupDialog by remember { mutableStateOf(false) }
  var showRestoreDialog by remember { mutableStateOf(false) }
  var webDavState by remember { mutableStateOf<WebDavBackupState>(WebDavBackupState.Idle) }

  /**
   * P11-148:抢焦点失败时的兜底落点——在**当下可见**的行里挑一个离 [targetItem] 最近的。
   * 只在失败路径调用(开销无所谓);返回 null 表示列表里没有任何可见行(那时也无处可落)。
   */
  fun nearestVisibleSettingItem(targetItem: Int): Int? {
    val visibleLazyIndices = settingsListState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
    if (visibleLazyIndices.isEmpty()) return null
    val targetOrder = SettingsFocusableItems.indexOf(targetItem)
    return SettingsFocusableItems
      .filter { item -> settingsItemToLazyIndex(item, updateState) in visibleLazyIndices }
      .minByOrNull { item -> kotlin.math.abs(SettingsFocusableItems.indexOf(item) - targetOrder) }
  }

  fun focusSettingItem(itemIndex: Int, direction: Int = 0): Boolean {
    val lazyIndex = settingsItemToLazyIndex(itemIndex, updateState)
    if (lazyIndex < 0) {
      Log.i(SettingsLogTag, "focus skip hidden item=$itemIndex")
      return true
    }
    val requester = settingFocusRequesters[itemIndex]
    if (requester == null) {
      // 缺 requester 此前是静默 no-op(焦点悄悄留在原行/丢掉,无迹可查)——补日志。
      Log.w(SettingsLogTag, "focus skip unknown item=$itemIndex")
      return true
    }
    Log.i(SettingsLogTag, "focus item=$itemIndex lazy=$lazyIndex")
    focusSettingJob?.cancel()
    focusSettingJob = coroutineScope.launch {
      // P11-148:滚 → 等目标行**真正进入布局** → 有界重试抢焦点(共用助手,见 SettingsFocus.kt)。
      // 此前是「滚完只等一帧就 requestFocus」:离屏行没组合 ⇒ FocusRequester 无挂载节点 ⇒ 请求
      // 落空,而原行又被这次滚动回收 ⇒ 焦点树空、D-pad 全哑。跨屏跳转(长按连发、首尾环绕、
      // 进页恢复上次行)最易触发;列表深处的 WebDAV「备份」行因此按不到。
      val focused = settingsListState.focusItemWithLayoutWait(
        index = lazyIndex,
        direction = direction,
        requester = requester,
        fallbackItemHeightPx = settingsRowFallbackHeightPx,
        edgeInsetPx = settingsScrollInsetPx,
        label = "item=$itemIndex lazy=$lazyIndex dir=$direction",
      )
      if (focused) {
        return@launch
      }
      // 兜底:目标行最终抢不到焦点时,绝不能把焦点树留空(D-pad 会彻底没反应,只能退出重进)。
      // 就近把焦点交给**目标旁边一个当下确实可见的行**,保住继续导航的能力,并留日志备查。
      val fallbackItem = nearestVisibleSettingItem(itemIndex) ?: return@launch
      Log.w(
        SettingsLogTag,
        "focus fallback: $itemIndex → $fallbackItem " +
          "visible=${settingsListState.layoutInfo.visibleItemsInfo.map { item -> item.index }}",
      )
      settingFocusRequesters[fallbackItem]?.requestFocusWithRetry(label = "fallback item=$fallbackItem")
    }
    return true
  }

  fun moveSettingFocus(itemIndex: Int, direction: Int): Boolean {
    val currentOrderIndex = SettingsFocusableItems.indexOf(itemIndex)
    val size = SettingsFocusableItems.size
    var nextOrderIndex = currentOrderIndex + direction
    var wrapped = false
    // 循环:越界从另一端继续找,实现首尾相接(最上按上→最底,最底按下→最上)。
    // repeat(size) 兜底,避免全部项被跳过时死循环。
    repeat(size) {
      if (nextOrderIndex !in SettingsFocusableItems.indices) {
        nextOrderIndex = ((nextOrderIndex % size) + size) % size
        wrapped = true
      }
      val targetItem = SettingsFocusableItems[nextOrderIndex]
      if (settingsItemToLazyIndex(targetItem, updateState) >= 0) {
        Log.i(SettingsLogTag, "move from=$itemIndex dir=$direction → $targetItem")
        // 循环跳转时滚动方向取反,把远端项滚进视口(上→底要往下滚,下→顶要往上滚)。
        return focusSettingItem(targetItem, if (wrapped) -direction else direction)
      }
      nextOrderIndex += direction
    }
    return true
  }

  Box(
    modifier = modifier.fillMaxSize(),
  ) {
    SettingsEntryFocusTarget(
      focusRequester = firstItemFocusRequester,
      onFocused = {
        focusSettingItem(lastFocusedSettingItem)
      },
    )
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = BiliSpacing.Md),
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Xl),
    ) {
      SettingsBehaviorColumn(
        settings = settings,
        cacheSizeText = cacheSizeText,
        codecCapability = codecCapability,
        listState = settingsListState,
        focusRequesters = settingFocusRequesters,
        userSession = userSession,
        onAccountClick = onAccountClick,
        onSettingFocused = { itemIndex ->
          // 两层架构:聚焦只记录最近项(供返回焦点),不展开右侧二级菜单;
          // 二级菜单只由对应行「点击」开合(见 onAboutSelected 等)。
          lastFocusedSettingItem = itemIndex
          // 二级菜单只在其归属一级项仍持有焦点时显示;焦点移到其它一级项(不统属)即隐藏。
          val owner = rightPanel.ownerItem()
          if (owner != null && owner != itemIndex) {
            rightPanel = SettingsRightPanel.None
          }
        },
        onMoveSettingFocus = ::moveSettingFocus,
        onMoveLeftToNav = onMoveLeftToNav,
        onVisualPerformanceModeChange = onVisualPerformanceModeChange,
        liquidGlassCardsSupported = liquidGlassCardsSupported,
        onLiquidGlassCardsEnabledChange = onLiquidGlassCardsEnabledChange,
        onHomeThemeVariantChange = onHomeThemeVariantChange,
        onChineseTextVariantChange = onChineseTextVariantChange,
        onClearCache = onClearCache,
        onSeekPreviewSpritesEnabledChange = onSeekPreviewSpritesEnabledChange,
        onPlaybackQualityPreferenceChange = onPlaybackQualityPreferenceChange,
        onDefaultPlaybackSpeedChange = onDefaultPlaybackSpeedChange,
        onPlaybackBufferMaxChange = onPlaybackBufferMaxChange,
        onPlaybackCodecPreferenceChange = onPlaybackCodecPreferenceChange,
        onPlaybackCdnPreferenceChange = onPlaybackCdnPreferenceChange,
        onAirJumpAssistantEnabledChange = onAirJumpAssistantEnabledChange,
        onConfirmPlaybackExitChange = onConfirmPlaybackExitChange,
        onAutoPlayNextEpisodeChange = onAutoPlayNextEpisodeChange,
        onAutoPlayRelatedVideoChange = onAutoPlayRelatedVideoChange,
        onAutoReturnHomeOnCompletionChange = onAutoReturnHomeOnCompletionChange,
        onShowClockChange = onShowClockChange,
        onShowMiniProgressBarChange = onShowMiniProgressBarChange,
        onPlayerLogOverlayEnabledChange = onPlayerLogOverlayEnabledChange,
        onCrashLogAutoReportChange = onCrashLogAutoReportChange,
        onAutoConfirmOnFocusChange = onAutoConfirmOnFocusChange,
        onAutoRefreshOnSwitchChange = onAutoRefreshOnSwitchChange,
        onAboutSelected = {
          rightPanel = if (rightPanel == SettingsRightPanel.About) {
            SettingsRightPanel.None
          } else {
            SettingsRightPanel.About
          }
        },
        onHomeSectionsSelected = {
          rightPanel = if (rightPanel == SettingsRightPanel.HomeSections) {
            SettingsRightPanel.None
          } else {
            SettingsRightPanel.HomeSections
          }
        },
        // P11-148:「YouTube 设置」入口行 → 右侧二级面板(取代 P11-131 的折叠组)。
        onYoutubeSettingsSelected = {
          rightPanel = if (rightPanel == SettingsRightPanel.YoutubeSettings) {
            SettingsRightPanel.None
          } else {
            SettingsRightPanel.YoutubeSettings
          }
        },
        onWebDavSelected = { showWebDavDialog = true },
        onBackupSelected = { showBackupDialog = true },
        onRestoreSelected = { showRestoreDialog = true },
        webDavState = webDavState,
        onIptvSourceConfigChange = onIptvSourceConfigChange,
        onIptvSelected = { showIptvDialog = true },
        onTvboxConfigChange = onTvboxConfigChange,
        onTvboxSelected = { showTvboxDialog = true },
        onPipedInstanceChange = onPipedInstanceChange,
        onLogsSelected = {
          rightPanel = if (rightPanel == SettingsRightPanel.Logs) {
            SettingsRightPanel.None
          } else {
            SettingsRightPanel.Logs
          }
        },
        logFiles = logFiles,
        isRecordingLog = isRecordingLog,
        viewingLogFile = viewingLogFile,
        onViewLog = onViewLog,
        onBackFromLogView = onBackFromLogView,
        onShareLog = onShareLog,
        onBackupLog = onBackupLog,
        onSendLog = onSendLog,
        onToggleLogRecording = onToggleLogRecording,
        updateState = updateState,
        onCheckUpdate = onCheckUpdate,
        onDownloadUpdate = onDownloadUpdate,
        onInstallUpdate = onInstallUpdate,
        onOpenReleaseNotes = onOpenReleaseNotes,
        speedTestState = speedTestState,
        onRunSpeedTest = onRunSpeedTest,
        onAddYoutubeChannel = onAddYoutubeChannel,
        onRemoveYoutubeChannel = onRemoveYoutubeChannel,
        webDavConfig = webDavConfig,
        onWebDavBackup = onWebDavBackup,
        onWebDavRestore = onWebDavRestore,
        modifier = Modifier.weight(1f),
      )
      when (rightPanel) {
        SettingsRightPanel.None -> Unit
        SettingsRightPanel.HomeSections -> SettingsHomeSectionsColumn(
          settings = settings,
          onMoveLeftToSettings = { focusSettingItem(lastFocusedSettingItem) },
          onHomeSectionEnabledChange = onHomeSectionEnabledChange,
          onHomeSectionsOrderChange = onHomeSectionsOrderChange,
          modifier = Modifier.weight(1f),
        )
        SettingsRightPanel.Logs -> SettingsLogsColumn(
          files = logFiles,
          isRecording = isRecordingLog,
          viewingFile = viewingLogFile,
          onView = onViewLog,
          onBackFromView = onBackFromLogView,
          onShare = onShareLog,
          onBackupLog = onBackupLog,
          onSendLog = onSendLog,
          onToggleRecording = onToggleLogRecording,
          onMoveLeftToSettings = { focusSettingItem(lastFocusedSettingItem) },
          modifier = Modifier.weight(1f),
        )
        SettingsRightPanel.About -> SettingsAboutColumn(
          modifier = Modifier.weight(1f),
        )
        SettingsRightPanel.YoutubeChannels -> SettingsYoutubeChannelsColumn(
          channels = channels,
          onAdd = onAddYoutubeChannel,
          onRemove = onRemoveYoutubeChannel,
          onMoveLeftToSettings = { focusSettingItem(lastFocusedSettingItem) },
          modifier = Modifier.weight(1f),
        )
        // P11-148:原折叠组 9 行的新家(独立焦点岛,D-pad 不再跨屏)。
        SettingsRightPanel.YoutubeSettings -> SettingsYoutubeSettingsColumn(
          settings = settings,
          codecCapability = codecCapability,
          channels = channels,
          onDefaultQualityChange = onYoutubeDefaultQualityChange,
          onStartQualityChange = onYoutubeStartQualityChange,
          onDefaultSpeedChange = onYoutubeDefaultSpeedChange,
          onCodecChange = onYoutubePlaybackCodecPreferenceChange,
          onContentRegionChange = onYoutubeContentRegionChange,
          onChannelsSelected = {
            rightPanel = if (rightPanel == SettingsRightPanel.YoutubeChannels) {
              SettingsRightPanel.None
            } else {
              SettingsRightPanel.YoutubeChannels
            }
          },
          onPipedSelected = { showPipedDialog = true },
          onUsePipedChange = onYoutubeUsePipedChange,
          onDeliveryPriorityChange = onYoutubeDeliveryPriorityChange,
          onMoveLeftToSettings = { focusSettingItem(lastFocusedSettingItem) },
          modifier = Modifier.weight(1f),
        )
      }
    }
    if (speedTestState !is SpeedTestUiState.Idle) {
      SpeedTestDialog(
        state = speedTestState,
        onDismiss = onDismissSpeedTest,
        modifier = Modifier.align(Alignment.Center),
      )
    }
    if (showWebDavDialog) {
      SettingsWebDavDialog(
        config = webDavConfig,
        onSave = onWebDavConfigChange,
        onDismiss = {
          showWebDavDialog = false
          // 弹窗关闭后焦点回到打开它的 WebDAV 行,避免落到侧栏头像。
          focusSettingItem(SettingsItemWebDav)
        },
      )
    }
    if (showIptvDialog) {
      SettingsIptvDialog(
        url = settings.iptvSourceUrl,
        username = settings.iptvSourceUsername,
        password = settings.iptvSourcePassword,
        onSave = { url, username, password ->
          onIptvSourceConfigChange(url, username, password)
          showIptvDialog = false
          // 保存后焦点回到 IPTV 行(弹窗内 URL 字段随弹窗移除,不恢复会落到侧栏头像)。
          focusSettingItem(SettingsItemIptv)
        },
        onDismiss = {
          showIptvDialog = false
          focusSettingItem(SettingsItemIptv)
        },
      )
    }
    if (showTvboxDialog) {
      SettingsTvboxDialog(
        url = settings.tvboxConfigUrl,
        onSave = { url ->
          onTvboxConfigChange(url)
          showTvboxDialog = false
          // 保存后焦点回到 TVBox 行(弹窗内 URL 字段随弹窗移除,不恢复会落到侧栏头像)。
          focusSettingItem(SettingsItemTvbox)
        },
        onDismiss = {
          showTvboxDialog = false
          focusSettingItem(SettingsItemTvbox)
        },
      )
    }
    if (showPipedDialog) {
      SettingsPipedDialog(
        url = settings.pipedInstanceUrl,
        onSave = { url ->
          onPipedInstanceChange(url)
          showPipedDialog = false
          focusSettingItem(SettingsItemPiped)
        },
        onDismiss = {
          showPipedDialog = false
          focusSettingItem(SettingsItemPiped)
        },
      )
    }
    if (showBackupDialog) {
      SettingsWebDavSelectionDialog(
        isRestore = false,
        onConfirm = { items ->
          showBackupDialog = false
          coroutineScope.launch {
            webDavState = WebDavBackupState.Running(isRestore = false)
            val result = onWebDavBackup(webDavConfig, items)
            webDavState = WebDavBackupState.Idle
            val msg = result.fold(
              onSuccess = { context.getString(R.string.settings_webdav_backup_success) },
              onFailure = { context.getString(R.string.settings_webdav_failed, it.message ?: "") },
            )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
          }
          focusSettingItem(SettingsItemWebDavBackup)
        },
        onDismiss = {
          showBackupDialog = false
          focusSettingItem(SettingsItemWebDavBackup)
        },
      )
    }
    if (showRestoreDialog) {
      SettingsWebDavSelectionDialog(
        isRestore = true,
        onConfirm = { items ->
          showRestoreDialog = false
          coroutineScope.launch {
            webDavState = WebDavBackupState.Running(isRestore = true)
            val result = onWebDavRestore(webDavConfig, items)
            webDavState = WebDavBackupState.Idle
            val msg = result.fold(
              onSuccess = { count -> context.getString(R.string.settings_webdav_restore_success, count) },
              onFailure = { context.getString(R.string.settings_webdav_failed, it.message ?: "") },
            )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
          }
          focusSettingItem(SettingsItemWebDavRestore)
        },
        onDismiss = {
          showRestoreDialog = false
          focusSettingItem(SettingsItemWebDavRestore)
        },
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsBehaviorColumn(
  settings: AppSettings,
  cacheSizeText: String,
  codecCapability: CodecCapability,
  listState: LazyListState,
  focusRequesters: Map<Int, FocusRequester>,
  userSession: UserSession,
  onAccountClick: () -> Unit,
  onSettingFocused: (Int) -> Unit,
  onMoveSettingFocus: (Int, Int) -> Boolean,
  onMoveLeftToNav: () -> Boolean,
  onVisualPerformanceModeChange: (AppVisualPerformanceMode) -> Unit,
  liquidGlassCardsSupported: Boolean,
  onLiquidGlassCardsEnabledChange: (Boolean) -> Unit,
  onHomeThemeVariantChange: (HomeThemeVariant) -> Unit,
  onChineseTextVariantChange: (ChineseTextVariant) -> Unit,
  onClearCache: () -> Unit,
  onSeekPreviewSpritesEnabledChange: (Boolean) -> Unit,
  onPlaybackQualityPreferenceChange: (PlaybackQualityPreference) -> Unit,
  onDefaultPlaybackSpeedChange: (DefaultPlaybackSpeed) -> Unit,
  onPlaybackBufferMaxChange: (PlaybackBufferMax) -> Unit,
  onPlaybackCodecPreferenceChange: (PlaybackCodecPreference) -> Unit,
  onPlaybackCdnPreferenceChange: (PlaybackCdnPreference) -> Unit,
  onAirJumpAssistantEnabledChange: (Boolean) -> Unit,
  onConfirmPlaybackExitChange: (Boolean) -> Unit,
  onAutoPlayNextEpisodeChange: (Boolean) -> Unit,
  onAutoPlayRelatedVideoChange: (Boolean) -> Unit,
  onAutoReturnHomeOnCompletionChange: (Boolean) -> Unit,
  onShowClockChange: (Boolean) -> Unit,
  onShowMiniProgressBarChange: (Boolean) -> Unit,
  onPlayerLogOverlayEnabledChange: (Boolean) -> Unit,
  onCrashLogAutoReportChange: (Boolean) -> Unit,
  onAutoConfirmOnFocusChange: (Boolean) -> Unit,
  onAutoRefreshOnSwitchChange: (Boolean) -> Unit,
  onAboutSelected: () -> Unit,
  onHomeSectionsSelected: () -> Unit,
  /** P11-148:「YouTube 设置」入口行的开合(9 行在右侧面板,入口行本身仍在主列表里渲染)。 */
  onYoutubeSettingsSelected: () -> Unit,
  onLogsSelected: () -> Unit,
  logFiles: List<LogCatcherUtil.LogFileInfo>,
  isRecordingLog: Boolean,
  viewingLogFile: java.io.File?,
  onViewLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onBackFromLogView: () -> Unit,
  onShareLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onBackupLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onSendLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onToggleLogRecording: () -> Unit,
  updateState: UpdateUiState,
  onCheckUpdate: () -> Unit,
  onDownloadUpdate: () -> Unit,
  onInstallUpdate: () -> Unit,
  onOpenReleaseNotes: () -> Unit,
  speedTestState: SpeedTestUiState,
  onRunSpeedTest: () -> Unit,
  onAddYoutubeChannel: suspend (String) -> Boolean,
  onRemoveYoutubeChannel: suspend (String) -> Boolean,
  webDavConfig: com.kirin.mt.core.webdav.WebDavConfig,
  onWebDavBackup: suspend (com.kirin.mt.core.webdav.WebDavConfig, Set<com.kirin.mt.core.webdav.WebDavBackupItem>) -> Result<Unit>,
  onWebDavRestore: suspend (com.kirin.mt.core.webdav.WebDavConfig, Set<com.kirin.mt.core.webdav.WebDavBackupItem>) -> Result<Int>,
  onWebDavSelected: () -> Unit,
  onBackupSelected: () -> Unit,
  onRestoreSelected: () -> Unit,
  webDavState: WebDavBackupState,
  onIptvSourceConfigChange: (url: String, username: String, password: String) -> Unit,
  onIptvSelected: () -> Unit,
  onTvboxConfigChange: (url: String) -> Unit,
  onTvboxSelected: () -> Unit,
  onPipedInstanceChange: (url: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  CompositionLocalProvider(LocalBringIntoViewSpec provides SettingsBringIntoViewSpec) {
    LazyColumn(
      state = listState,
      // P11-148:焦点区域诊断标签——设置列表此前在 FocusDiag 轨迹里是「无标签区域」,真机日志分不清
      // 「焦点彻底丢光(root LOST 后无 GAINED)」与「焦点跑到别处」。此标签让下次 TV 日志一眼定位。
      modifier = modifier.fillMaxSize().focusDiag("settings"),
      contentPadding = PaddingValues(bottom = BiliSpacing.Xxl),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
    ) {
      item(key = "account") {
        SettingsAccountRow(
          userSession = userSession,
          onFocused = { onSettingFocused(SettingsItemAccount) },
          onClick = onAccountClick,
          modifier = Modifier
            .focusRequester(focusRequesters.getValue(SettingsItemAccount))
            .settingsBoundaryKeys(
              itemIndex = SettingsItemAccount,
              onMoveSettingFocus = onMoveSettingFocus,
              onMoveLeftToNav = onMoveLeftToNav,
            ),
        )
      }
      item(key = "playback-header") {
        SettingsSectionTitle(text = stringResource(R.string.settings_playback_section))
      }
      item(key = "playback-quality") {
        val qualityOptions = remember { PlaybackQualityPreference.entries.toList() }
        val effectivePreference = settings.playbackQualityPreference
        SettingsOptionRow(
          title = stringResource(R.string.settings_playback_quality_title),
          description = stringResource(R.string.settings_playback_quality_description),
          value = effectivePreference.qualityLabel(),
          modifier = Modifier
            .focusRequester(focusRequesters.getValue(SettingsItemPlaybackQuality))
            .settingsBoundaryKeys(
              itemIndex = SettingsItemPlaybackQuality,
              onMoveSettingFocus = onMoveSettingFocus,
              onMoveLeftToNav = onMoveLeftToNav,
            ),
          onFocused = { onSettingFocused(SettingsItemPlaybackQuality) },
          onClick = {
            val currentIndex = qualityOptions.indexOf(effectivePreference).takeIf { it >= 0 } ?: 0
            onPlaybackQualityPreferenceChange(qualityOptions[(currentIndex + 1) % qualityOptions.size])
          },
        )
      }
      item(key = "default-playback-speed") {
        val speedOptions = remember { DefaultPlaybackSpeed.entries.toList() }
        val effectiveSpeed = settings.defaultPlaybackSpeed
        SettingsOptionRow(
          title = stringResource(R.string.settings_default_speed_title),
          description = stringResource(R.string.settings_default_speed_description),
          value = effectiveSpeed.speedLabel(),
          modifier = Modifier
            .focusRequester(focusRequesters.getValue(SettingsItemDefaultSpeed))
            .settingsBoundaryKeys(
              itemIndex = SettingsItemDefaultSpeed,
              onMoveSettingFocus = onMoveSettingFocus,
              onMoveLeftToNav = onMoveLeftToNav,
            ),
          onFocused = { onSettingFocused(SettingsItemDefaultSpeed) },
          onClick = {
            val currentIndex = speedOptions.indexOf(effectiveSpeed).takeIf { it >= 0 } ?: 0
            onDefaultPlaybackSpeedChange(speedOptions[(currentIndex + 1) % speedOptions.size])
          },
        )
      }
      item(key = "playback-buffer-max") {
        val bufferOptions = remember { PlaybackBufferMax.entries.toList() }
        val effectiveBuffer = settings.bufferMax
        SettingsOptionRow(
          title = stringResource(R.string.settings_playback_buffer_title),
          description = stringResource(R.string.settings_playback_buffer_description),
          value = effectiveBuffer.label,
          modifier = Modifier
            .focusRequester(focusRequesters.getValue(SettingsItemPlaybackBufferMax))
            .settingsBoundaryKeys(
              itemIndex = SettingsItemPlaybackBufferMax,
              onMoveSettingFocus = onMoveSettingFocus,
              onMoveLeftToNav = onMoveLeftToNav,
            ),
          onFocused = { onSettingFocused(SettingsItemPlaybackBufferMax) },
          onClick = {
            val currentIndex = bufferOptions.indexOf(effectiveBuffer).takeIf { it >= 0 } ?: 0
            onPlaybackBufferMaxChange(bufferOptions[(currentIndex + 1) % bufferOptions.size])
          },
        )
      }
      item(key = "playback-codec") {
        val codecOptions = remember(codecCapability) { codecCapability.playbackCodecOptions() }
        val configuredPreference = settings.playbackCodecPreference.takeIf { preference ->
          preference in codecOptions
        } ?: PlaybackCodecPreference.Auto
        val effectivePreference = if (settings.lowSpecMode) {
          PlaybackCodecPreference.H264
        } else {
          configuredPreference
        }
        SettingsOptionRow(
          title = stringResource(R.string.settings_playback_codec_title),
          description = stringResource(R.string.settings_playback_codec_description),
          value = effectivePreference.codecLabel(),
          modifier = Modifier
            .focusRequester(focusRequesters.getValue(SettingsItemPlaybackCodec))
            .settingsBoundaryKeys(
              itemIndex = SettingsItemPlaybackCodec,
              onMoveSettingFocus = onMoveSettingFocus,
              onMoveLeftToNav = onMoveLeftToNav,
            ),
          onFocused = { onSettingFocused(SettingsItemPlaybackCodec) },
          onClick = {
            val currentIndex = codecOptions.indexOf(configuredPreference).takeIf { it >= 0 } ?: 0
            onPlaybackCodecPreferenceChange(codecOptions[(currentIndex + 1) % codecOptions.size])
          },
        )
      }
      item(key = "playback-cdn") {
        val cdnOptions = remember { PlaybackCdnPreference.entries.toList() }
        val effectivePreference = settings.playbackCdnPreference
        SettingsOptionRow(
          title = stringResource(R.string.settings_playback_cdn_title),
          description = stringResource(R.string.settings_playback_cdn_description),
          value = effectivePreference.cdnLabel(),
          modifier = Modifier
            .focusRequester(focusRequesters.getValue(SettingsItemPlaybackCdn))
            .settingsBoundaryKeys(
              itemIndex = SettingsItemPlaybackCdn,
              onMoveSettingFocus = onMoveSettingFocus,
              onMoveLeftToNav = onMoveLeftToNav,
            ),
          onFocused = { onSettingFocused(SettingsItemPlaybackCdn) },
          onClick = {
            val currentIndex = cdnOptions.indexOf(effectivePreference).takeIf { it >= 0 } ?: 0
            onPlaybackCdnPreferenceChange(cdnOptions[(currentIndex + 1) % cdnOptions.size])
          },
        )
      }
      item(key = "speed-test") {
        val speedTestValue = when (speedTestState) {
          SpeedTestUiState.Running -> stringResource(R.string.settings_speed_test_running)
          is SpeedTestUiState.Succeeded -> stringResource(
            R.string.settings_speed_test_result_count,
            speedTestState.results.size,
          )
          else -> ""
        }
        SettingsActionRow(
          title = stringResource(R.string.settings_speed_test_title),
          description = stringResource(R.string.settings_speed_test_description),
          value = speedTestValue,
          modifier = Modifier
            .focusRequester(focusRequesters.getValue(SettingsItemSpeedTest))
            .settingsBoundaryKeys(
              itemIndex = SettingsItemSpeedTest,
              onMoveSettingFocus = onMoveSettingFocus,
              onMoveLeftToNav = onMoveLeftToNav,
            ),
          onFocused = { onSettingFocused(SettingsItemSpeedTest) },
          onClick = onRunSpeedTest,
        )
      }
      item(key = "seek-preview-sprites") {
        SettingsToggleRow(
        title = stringResource(R.string.settings_seek_preview_sprites_title),
        description = stringResource(R.string.settings_seek_preview_sprites_description),
        checked = settings.seekPreviewSpritesEnabled,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemSeekPreviewSprites))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemSeekPreviewSprites,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemSeekPreviewSprites) },
        onCheckedChange = onSeekPreviewSpritesEnabledChange,
      )
    }
    item(key = "air-jump-assistant") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_air_jump_assistant_title),
        description = stringResource(R.string.settings_air_jump_assistant_description),
        checked = settings.airJumpAssistantEnabled,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemAirJumpAssistant))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemAirJumpAssistant,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemAirJumpAssistant) },
        onCheckedChange = onAirJumpAssistantEnabledChange,
      )
    }
    item(key = "confirm-playback-exit") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_confirm_playback_exit_title),
        description = stringResource(R.string.settings_confirm_playback_exit_description),
        checked = settings.confirmPlaybackExit,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemConfirmPlaybackExit))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemConfirmPlaybackExit,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemConfirmPlaybackExit) },
        onCheckedChange = onConfirmPlaybackExitChange,
      )
    }
    item(key = "auto-play-next-episode") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_auto_play_next_episode_title),
        description = stringResource(R.string.settings_auto_play_next_episode_description),
        checked = settings.autoPlayNextEpisode,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemAutoPlayNextEpisode))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemAutoPlayNextEpisode,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemAutoPlayNextEpisode) },
        onCheckedChange = onAutoPlayNextEpisodeChange,
      )
    }
    item(key = "auto-play-related-video") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_auto_play_related_video_title),
        description = stringResource(R.string.settings_auto_play_related_video_description),
        checked = settings.autoPlayRelatedVideo,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemAutoPlayRelatedVideo))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemAutoPlayRelatedVideo,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemAutoPlayRelatedVideo) },
        onCheckedChange = onAutoPlayRelatedVideoChange,
      )
    }
    item(key = "auto-return-home-on-completion") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_auto_return_home_on_completion_title),
        description = stringResource(R.string.settings_auto_return_home_on_completion_description),
        checked = settings.autoReturnHomeOnCompletion,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemAutoReturnHomeOnCompletion))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemAutoReturnHomeOnCompletion,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemAutoReturnHomeOnCompletion) },
        onCheckedChange = onAutoReturnHomeOnCompletionChange,
      )
    }
    item(key = "show-clock") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_show_clock_title),
        description = stringResource(R.string.settings_show_clock_description),
        checked = settings.showClock,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemShowClock))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemShowClock,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemShowClock) },
        onCheckedChange = onShowClockChange,
      )
    }
    item(key = "show-mini-progress-bar") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_show_mini_progress_bar_title),
        description = stringResource(R.string.settings_show_mini_progress_bar_description),
        checked = settings.showMiniProgressBar,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemShowMiniProgressBar))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemShowMiniProgressBar,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemShowMiniProgressBar) },
        onCheckedChange = onShowMiniProgressBarChange,
      )
    }
    item(key = "ui-header") {
      SettingsSectionTitle(
        text = stringResource(R.string.settings_interaction_section),
        modifier = Modifier.padding(top = BiliSpacing.Lg),
      )
    }
    item(key = "visual-performance-mode") {
      val performanceOptions = remember { AppVisualPerformanceMode.entries.toList() }
      val effectiveMode = settings.visualPerformanceMode
      SettingsOptionRow(
        title = stringResource(R.string.settings_visual_performance_title),
        description = stringResource(R.string.settings_visual_performance_description),
        value = effectiveMode.visualPerformanceLabel(),
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemVisualPerformanceMode))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemVisualPerformanceMode,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
        ),
        onFocused = { onSettingFocused(SettingsItemVisualPerformanceMode) },
        onClick = {
          val currentIndex = performanceOptions.indexOf(effectiveMode).takeIf { it >= 0 } ?: 0
          onVisualPerformanceModeChange(performanceOptions[(currentIndex + 1) % performanceOptions.size])
        },
      )
    }
    item(key = "liquid-glass-cards") {
      val liquidGlassEnabled = settings.visualPerformanceMode == AppVisualPerformanceMode.Refined && liquidGlassCardsSupported
      SettingsToggleRow(
        title = stringResource(R.string.settings_liquid_glass_cards_title),
        description = if (liquidGlassCardsSupported) {
          stringResource(R.string.settings_liquid_glass_cards_description)
        } else {
          stringResource(R.string.settings_liquid_glass_cards_unsupported_description)
        },
        checked = liquidGlassEnabled && settings.liquidGlassCardsEnabled,
        enabled = liquidGlassEnabled,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemLiquidGlassCards))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemLiquidGlassCards,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemLiquidGlassCards) },
        onCheckedChange = onLiquidGlassCardsEnabledChange,
      )
    }
    item(key = "home-theme-variant") {
      val themeOptions = remember { HomeThemeVariant.entries.toList() }
      val effectiveTheme = settings.homeThemeVariant
      SettingsOptionRow(
        title = stringResource(R.string.settings_home_theme_title),
        description = stringResource(R.string.settings_home_theme_description),
        value = effectiveTheme.homeThemeLabel(),
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemHomeThemeVariant))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemHomeThemeVariant,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemHomeThemeVariant) },
        onClick = {
          val currentIndex = themeOptions.indexOf(effectiveTheme).takeIf { it >= 0 } ?: 0
          onHomeThemeVariantChange(themeOptions[(currentIndex + 1) % themeOptions.size])
        },
      )
    }
    item(key = "auto-confirm-on-focus") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_auto_confirm_on_focus_title),
        description = stringResource(R.string.settings_auto_confirm_on_focus_description),
        checked = settings.autoConfirmOnFocus,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemAutoConfirmOnFocus))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemAutoConfirmOnFocus,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemAutoConfirmOnFocus) },
        onCheckedChange = onAutoConfirmOnFocusChange,
      )
    }
    item(key = "auto-refresh-on-switch") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_auto_refresh_on_switch_title),
        description = stringResource(R.string.settings_auto_refresh_on_switch_description),
        checked = settings.autoConfirmOnFocus && settings.autoRefreshOnSwitch,
        enabled = settings.autoConfirmOnFocus,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemAutoRefreshOnSwitch))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemAutoRefreshOnSwitch,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemAutoRefreshOnSwitch) },
        onCheckedChange = onAutoRefreshOnSwitchChange,
      )
    }
    item(key = "system-header") {
      SettingsSectionTitle(
        text = stringResource(R.string.settings_performance_section),
        modifier = Modifier.padding(top = BiliSpacing.Lg),
      )
    }
    item(key = "clear-cache") {
      SettingsActionRow(
        title = stringResource(R.string.settings_clear_cache_title),
        description = stringResource(R.string.settings_clear_cache_description),
        value = cacheSizeText,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemClearCache))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemClearCache,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemClearCache) },
        onClick = onClearCache,
      )
    }
    item(key = "chinese-text-variant") {
      val languageOptions = remember { ChineseTextVariant.entries.toList() }
      val effectiveVariant = settings.chineseTextVariant
      SettingsOptionRow(
        title = stringResource(R.string.settings_language_title),
        description = stringResource(R.string.settings_language_description),
        value = effectiveVariant.languageLabel(),
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemChineseTextVariant))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemChineseTextVariant,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemChineseTextVariant) },
        onClick = {
          val currentIndex = languageOptions.indexOf(effectiveVariant).takeIf { it >= 0 } ?: 0
          onChineseTextVariantChange(languageOptions[(currentIndex + 1) % languageOptions.size])
        },
      )
    }
    item(key = "home-sections") {
      SettingsActionRow(
        title = stringResource(R.string.settings_home_sections_entry_title),
        description = stringResource(R.string.settings_home_sections_entry_description),
        value = "",
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemHomeSections))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemHomeSections,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemHomeSections) },
        onClick = onHomeSectionsSelected,
      )
    }
    // ── P11-148:原「YouTube 设置」可折叠分组 → 二级面板入口。
    //    折叠组把 10 行塞在列表中段(组头还压在视口边缘),D-pad 在组两端一次跨 9 行,
    //    正是「离屏行未组合 ⇒ 抢焦点落空 ⇒ 焦点树空、D-pad 全哑」最容易踩的场景;
    //    改成一枚入口行 + 右侧面板后:①主列表少 9 行,到 WebDAV「备份」这类深处行的
    //    跳转距离整体缩短;②面板是独立焦点岛(与首页分区/日志/关于/频道管理同一套机制),
    //    行间移动不再跨屏。入口行仍是面板的归属项(Left 回得来、聚焦别的一级项即收起)。
    item(key = "youtube-settings") {
      SettingsActionRow(
        title = stringResource(R.string.settings_youtube_group_title),
        description = stringResource(R.string.settings_youtube_group_desc),
        value = "",
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemYoutubeSettings))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemYoutubeSettings,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemYoutubeSettings) },
        onClick = onYoutubeSettingsSelected,
      )
    }
    item(key = "webdav") {
      SettingsActionRow(
        title = stringResource(R.string.settings_webdav_title),
        description = stringResource(R.string.settings_webdav_description),
        value = webDavConfig.url,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemWebDav))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemWebDav,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemWebDav) },
        onClick = onWebDavSelected,
      )
    }
    item(key = "webdav-backup") {
      SettingsWebDavBackupRow(
        title = stringResource(R.string.settings_webdav_backup_row),
        description = stringResource(R.string.settings_webdav_backup_desc),
        isRestore = false,
        state = webDavState,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemWebDavBackup))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemWebDavBackup,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemWebDavBackup) },
        onClick = {
          if (webDavState is WebDavBackupState.Running) return@SettingsWebDavBackupRow
          onBackupSelected()
        },
      )
    }
    item(key = "webdav-restore") {
      SettingsWebDavBackupRow(
        title = stringResource(R.string.settings_webdav_restore_row),
        description = stringResource(R.string.settings_webdav_restore_desc),
        isRestore = true,
        state = webDavState,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemWebDavRestore))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemWebDavRestore,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemWebDavRestore) },
        onClick = {
          if (webDavState is WebDavBackupState.Running) return@SettingsWebDavBackupRow
          onRestoreSelected()
        },
      )
    }
    item(key = "iptv") {
      SettingsActionRow(
        title = stringResource(R.string.settings_iptv_title),
        description = stringResource(R.string.settings_iptv_description),
        value = settings.iptvSourceUrl.ifBlank {
          stringResource(R.string.settings_iptv_configure_hint)
        },
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemIptv))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemIptv,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemIptv) },
        onClick = onIptvSelected,
      )
    }
    item(key = "tvbox") {
      SettingsActionRow(
        title = stringResource(R.string.settings_tvbox_title),
        description = stringResource(R.string.settings_tvbox_description),
        value = settings.tvboxConfigUrl.ifBlank {
          stringResource(R.string.settings_tvbox_configure_hint)
        },
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemTvbox))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemTvbox,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemTvbox) },
        onClick = onTvboxSelected,
      )
    }
    item(key = "logs") {
      SettingsActionRow(
        title = stringResource(R.string.settings_logs_entry_title),
        description = stringResource(R.string.settings_logs_entry_description),
        value = if (isRecordingLog) {
          stringResource(R.string.settings_logs_recording_badge)
        } else {
          "${logFiles.size}"
        },
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemLogs))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemLogs,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemLogs) },
        onClick = onLogsSelected,
      )
    }
    item(key = "player-log-overlay") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_player_log_overlay_title),
        description = stringResource(R.string.settings_player_log_overlay_description),
        checked = settings.playerLogOverlayEnabled,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemPlayerLogOverlay))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemPlayerLogOverlay,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemPlayerLogOverlay) },
        onCheckedChange = onPlayerLogOverlayEnabledChange,
      )
    }
    item(key = "crash-log-auto-report") {
      SettingsToggleRow(
        title = stringResource(R.string.settings_crash_auto_report_title),
        description = stringResource(R.string.settings_crash_auto_report_description),
        checked = settings.crashLogAutoReportEnabled,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemCrashLogAutoReport))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemCrashLogAutoReport,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemCrashLogAutoReport) },
        onCheckedChange = onCrashLogAutoReportChange,
      )
    }
    // 「程序更新」节放在列表最末尾(与移动端对齐:2026-08-30 调整)。
    item(key = "update-header") {
      SettingsSectionTitle(
        text = stringResource(R.string.settings_update_section),
        modifier = Modifier.padding(top = BiliSpacing.Lg),
      )
    }
    item(key = "update-current-version") {
      SettingsActionRow(
        title = stringResource(R.string.settings_update_current_version_title),
        description = stringResource(R.string.settings_update_current_version_description),
        value = currentVersionText(updateState),
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemUpdateCurrentVersion))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemUpdateCurrentVersion,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemUpdateCurrentVersion) },
        onClick = {},
      )
    }
    // 最新版本合并行(镜像移动端):内联检查/下载/进度/安装 + 进度条,无条件渲染。
    // 「检查更新」已并入此行——Idle/UpToDate/Failed 点按触发检查,Available 下载,Downloaded 安装。
    item(key = "update-latest-version") {
      SettingsUpdateVersionRow(
        title = stringResource(R.string.settings_update_latest_version_title),
        description = latestVersionText(updateState),
        actionLabel = updateVersionActionLabel(updateState),
        actionEnabled = isUpdateVersionActionEnabled(updateState),
        progress = downloadProgressFraction(updateState),
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemUpdateDownloadOrInstall))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemUpdateDownloadOrInstall,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemUpdateDownloadOrInstall) },
        onClick = {
          when (updateState.status) {
            is UpdateUiState.Status.Available -> onDownloadUpdate()
            is UpdateUiState.Status.Downloaded -> onInstallUpdate()
            is UpdateUiState.Status.Checking, is UpdateUiState.Status.Downloading -> {}
            else -> onCheckUpdate()
          }
        },
      )
    }
    // 条件项:有新版才显示,渲染在「最新版本」之后。
    if (shouldShowReleaseNotesAction(updateState)) {
      item(key = "update-release-notes") {
        SettingsActionRow(
          title = stringResource(R.string.settings_update_release_notes_action),
          description = stringResource(R.string.settings_update_release_notes_action_description),
          value = "",
          modifier = Modifier
            .focusRequester(focusRequesters.getValue(SettingsItemUpdateReleaseNotes))
            .settingsBoundaryKeys(
              itemIndex = SettingsItemUpdateReleaseNotes,
              onMoveSettingFocus = onMoveSettingFocus,
              onMoveLeftToNav = onMoveLeftToNav,
            ),
          onFocused = { onSettingFocused(SettingsItemUpdateReleaseNotes) },
          onClick = onOpenReleaseNotes,
        )
      }
    }
    // 「关于」并入程序更新组,作为列表最末行(2026-08-30 调整)。
    item(key = "about") {
      SettingsActionRow(
        title = stringResource(R.string.settings_about_title),
        description = stringResource(R.string.settings_about_description),
        value = "",
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemAbout))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemAbout,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemAbout) },
        onClick = onAboutSelected,
      )
    }
  }
  }
}

@Composable
private fun SettingsSectionTitle(
  text: String,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  Text(
    text = text,
    color = homeColors.textSecondary,
    fontSize = BiliTypography.SectionTitle,
    fontWeight = FontWeight.Bold,
    modifier = modifier,
  )
}

// SettingsLogTag 见 SettingsFocus.kt(P11-148 起与焦点重试工具同处,弹窗侧也要用)。

internal const val SettingsItemAccount = 41
internal const val SettingsItemPlaybackHeader = 0
internal const val SettingsItemPlaybackQuality = 1
internal const val SettingsItemPlaybackCodec = 2
internal const val SettingsItemSeekPreviewSprites = 3
internal const val SettingsItemAirJumpAssistant = 4
internal const val SettingsItemConfirmPlaybackExit = 5
internal const val SettingsItemAutoPlayNextEpisode = 6
internal const val SettingsItemAutoPlayRelatedVideo = 7
internal const val SettingsItemAutoReturnHomeOnCompletion = 8
internal const val SettingsItemShowClock = 9
internal const val SettingsItemShowMiniProgressBar = 10
internal const val SettingsItemSpeedTest = 11
internal const val SettingsItemVisualPerformanceMode = 12
internal const val SettingsItemLiquidGlassCards = 13
internal const val SettingsItemHomeThemeVariant = 14
internal const val SettingsItemAutoConfirmOnFocus = 15
internal const val SettingsItemAutoRefreshOnSwitch = 16
internal const val SettingsItemYoutubeDefaultQuality = 17
internal const val SettingsItemYoutubeStartQuality = 40
internal const val SettingsItemDefaultSpeed = 23
internal const val SettingsItemPlaybackBufferMax = 39
internal const val SettingsItemClearCache = 18
internal const val SettingsItemChineseTextVariant = 19
internal const val SettingsItemAbout = 20
internal const val SettingsItemHomeSections = 26
internal const val SettingsItemUpdateCurrentVersion = 22
internal const val SettingsItemUpdateDownloadOrInstall = 24
internal const val SettingsItemUpdateReleaseNotes = 25
internal const val SettingsItemPlaybackCdn = 21
internal const val SettingsItemLogs = 27
internal const val SettingsItemPlayerLogOverlay = 28
internal const val SettingsItemCrashLogAutoReport = 38
internal const val SettingsItemYoutubeChannels = 29
internal const val SettingsItemWebDav = 30
internal const val SettingsItemYoutubeContentRegion = 33
internal const val SettingsItemWebDavBackup = 31
internal const val SettingsItemWebDavRestore = 32
internal const val SettingsItemIptv = 34
// P11-85 修:原 41 与 SettingsItemAccount 重复——settingsItemToLazyIndex 的 when 先命中 Account→0,
// TVBox 行上/下键聚焦永远滚到列表顶端(focusRequesters map 键 41 也被 Account 抢)。
internal const val SettingsItemTvbox = 42
internal const val SettingsItemPiped = 35
internal const val SettingsItemYoutubeUsePiped = 36
internal const val SettingsItemYoutubeDeliveryPriority = 37

// P11-148:主列表里的「YouTube 设置」二级面板入口行(原 P11-131 折叠组头的位置/编号)。
// 常量取 47+ 是**有意避开** settingsItemToLazyIndex 的位置空间(常量值与 LazyColumn 下标撞号极易误读);
// 现用常量占满 0..42,故新值必须 ≥43。
internal const val SettingsItemYoutubeSettings = 47
internal const val SettingsItemYoutubeDefaultSpeed = 48
internal const val SettingsItemYoutubeCodec = 49

/**
 * P11-148:「YouTube 设置」二级面板内的 9 行,**按面板里的视觉顺序**排列。
 *
 * 这份列表是面板 D-pad 顺序的唯一真源([SettingsYoutubeSettingsColumn] 用它遍历),同时供
 * [SettingsItemConstantsAreUnique] 核对「每个常量都有归属」——主列表的 [SettingsFocusableItems]
 * 与这份面板列表**合起来**必须覆盖全部 `SettingsItem*` 常量。**新增面板行必须同时加进这里**,
 * 否则该行在面板里不可达、且常量守卫会当场报错(这是刻意的网)。
 */
internal val SettingsYoutubePanelItems = listOf(
  SettingsItemYoutubeDefaultQuality,
  SettingsItemYoutubeStartQuality,
  SettingsItemYoutubeDefaultSpeed,
  SettingsItemYoutubeCodec,
  SettingsItemYoutubeChannels,
  SettingsItemYoutubeContentRegion,
  SettingsItemPiped,
  SettingsItemYoutubeUsePiped,
  SettingsItemYoutubeDeliveryPriority,
)

private val SettingsFocusableItems = listOf(
  SettingsItemAccount,
  SettingsItemPlaybackQuality,
  SettingsItemDefaultSpeed,
  SettingsItemPlaybackBufferMax,
  SettingsItemPlaybackCodec,
  SettingsItemPlaybackCdn,
  SettingsItemSpeedTest,
  SettingsItemSeekPreviewSprites,
  SettingsItemAirJumpAssistant,
  SettingsItemConfirmPlaybackExit,
  SettingsItemAutoPlayNextEpisode,
  SettingsItemAutoPlayRelatedVideo,
  SettingsItemAutoReturnHomeOnCompletion,
  SettingsItemShowClock,
  SettingsItemShowMiniProgressBar,
  SettingsItemVisualPerformanceMode,
  SettingsItemLiquidGlassCards,
  SettingsItemHomeThemeVariant,
  SettingsItemAutoConfirmOnFocus,
  SettingsItemAutoRefreshOnSwitch,
  SettingsItemClearCache,
  SettingsItemChineseTextVariant,
  SettingsItemHomeSections,
  // P11-148:「YouTube 设置」只剩一枚入口行(9 行在右侧二级面板里,不走主列表 D-pad)。
  SettingsItemYoutubeSettings,
  SettingsItemWebDav,
  SettingsItemWebDavBackup,
  SettingsItemWebDavRestore,
  SettingsItemIptv,
  SettingsItemTvbox,
  SettingsItemLogs,
  SettingsItemPlayerLogOverlay,
  SettingsItemCrashLogAutoReport,
  SettingsItemUpdateCurrentVersion,
  SettingsItemUpdateDownloadOrInstall,
  SettingsItemUpdateReleaseNotes,
  SettingsItemAbout,
)

/**
 * 防「常量重复」回归(P11-85 TVBox 行焦点跳顶:常量与 Account 撞号 ⇒ `when` 首命中抢走另一行的位置,
 * 表现为该行上/下键永远滚到列表顶端)。首次访问本文件顶层属性即校验。
 *
 * **必须定义在 [SettingsFocusableItems] 之后**——Kotlin 顶层属性按文件顺序初始化,放前面会读到尚未
 * 初始化的空列表而使 check 恒失败。仓库无 test source set,这是唯一的自动网。
 */
private val SettingsItemConstantsAreUnique: Unit = run {
  val all = listOf(
    SettingsItemAccount, SettingsItemPlaybackHeader, SettingsItemPlaybackQuality,
    SettingsItemPlaybackCodec, SettingsItemSeekPreviewSprites, SettingsItemAirJumpAssistant,
    SettingsItemConfirmPlaybackExit, SettingsItemAutoPlayNextEpisode, SettingsItemAutoPlayRelatedVideo,
    SettingsItemAutoReturnHomeOnCompletion, SettingsItemShowClock, SettingsItemShowMiniProgressBar,
    SettingsItemSpeedTest, SettingsItemVisualPerformanceMode, SettingsItemLiquidGlassCards,
    SettingsItemHomeThemeVariant, SettingsItemAutoConfirmOnFocus, SettingsItemAutoRefreshOnSwitch,
    SettingsItemYoutubeDefaultQuality, SettingsItemYoutubeStartQuality, SettingsItemDefaultSpeed,
    SettingsItemPlaybackBufferMax, SettingsItemClearCache, SettingsItemChineseTextVariant,
    SettingsItemAbout, SettingsItemHomeSections, SettingsItemUpdateCurrentVersion,
    SettingsItemUpdateDownloadOrInstall, SettingsItemUpdateReleaseNotes, SettingsItemPlaybackCdn,
    SettingsItemLogs, SettingsItemPlayerLogOverlay, SettingsItemCrashLogAutoReport,
    SettingsItemYoutubeChannels, SettingsItemWebDav, SettingsItemYoutubeContentRegion,
    SettingsItemWebDavBackup, SettingsItemWebDavRestore, SettingsItemIptv, SettingsItemTvbox,
    SettingsItemPiped, SettingsItemYoutubeUsePiped, SettingsItemYoutubeDeliveryPriority,
    SettingsItemYoutubeSettings, SettingsItemYoutubeDefaultSpeed, SettingsItemYoutubeCodec,
  )
  check(all.size == all.distinct().size) { "duplicate SettingsItem* constant: $all" }
  // SettingsItemPlaybackHeader 是「播放设置」节标题,本就不参与 D-pad 遍历——除此之外每个常量
  // 都必须有归属:主列表的一级/入口行走 SettingsFocusableItems,「YouTube 设置」面板内的 9 行
  // 走 SettingsYoutubePanelItems。两处都没有 = 该行在任何地方都不可达(漏加必被这条 check 拦下)。
  val nonFocusable = setOf(SettingsItemPlaybackHeader)
  val reachable = SettingsFocusableItems.toSet() + SettingsYoutubePanelItems.toSet()
  check(reachable == all.toSet() - nonFocusable) {
    "焦点项表与常量表不一致: 缺=" +
      "${(all.toSet() - nonFocusable) - reachable} " +
      "多=${reachable - (all.toSet() - nonFocusable)}"
  }
}

private enum class SettingsRightPanel {
  None,
  HomeSections,
  Logs,
  About,
  YoutubeChannels,
  // P11-148:原「YouTube 设置」折叠组的替代——9 行搬进右侧二级面板,入口行是归属项。
  YoutubeSettings,
}

// 每个二级菜单(右侧面板)归属的一级菜单项。焦点移到其它一级项时,面板应隐藏(不统属)。
private fun SettingsRightPanel.ownerItem(): Int? = when (this) {
  SettingsRightPanel.None -> null
  SettingsRightPanel.HomeSections -> SettingsItemHomeSections
  SettingsRightPanel.Logs -> SettingsItemLogs
  SettingsRightPanel.About -> SettingsItemAbout
  SettingsRightPanel.YoutubeChannels -> SettingsItemYoutubeChannels
  SettingsRightPanel.YoutubeSettings -> SettingsItemYoutubeSettings
}

/**
 * 项 → 主列表 LazyColumn 下标表。
 *
 * P11-148 起不再有「折叠位移」这层:YouTube 的 9 行已搬进二级面板,不占主列表下标,
 * 故本表即最终下标(旧 `expandedItemToLazyIndex` + 位移包装已合并回本函数)。
 */
private fun settingsItemToLazyIndex(
  itemIndex: Int,
  updateState: UpdateUiState,
): Int = when (itemIndex) {
  SettingsItemAccount -> 0
  SettingsItemPlaybackHeader -> 1
  SettingsItemPlaybackQuality -> 2
  SettingsItemDefaultSpeed -> 3
  SettingsItemPlaybackBufferMax -> 4
  SettingsItemPlaybackCodec -> 5
  SettingsItemPlaybackCdn -> 6
  SettingsItemSpeedTest -> 7
  SettingsItemSeekPreviewSprites -> 8
  SettingsItemAirJumpAssistant -> 9
  SettingsItemConfirmPlaybackExit -> 10
  SettingsItemAutoPlayNextEpisode -> 11
  SettingsItemAutoPlayRelatedVideo -> 12
  SettingsItemAutoReturnHomeOnCompletion -> 13
  SettingsItemShowClock -> 14
  SettingsItemShowMiniProgressBar -> 15
  // 16 = "ui-header" section title in LazyColumn
  SettingsItemVisualPerformanceMode -> 17
  SettingsItemLiquidGlassCards -> 18
  SettingsItemHomeThemeVariant -> 19
  SettingsItemAutoConfirmOnFocus -> 20
  SettingsItemAutoRefreshOnSwitch -> 21
  // 22 = "system-header" section title in LazyColumn(「程序更新」节已移到列表最末尾,更新项不再插在中间)
  SettingsItemClearCache -> 23
  SettingsItemChineseTextVariant -> 24
  SettingsItemHomeSections -> 25
  // 26 = "youtube-settings" 二级面板入口行(P11-148;原折叠组头位置)。
  // 组内 9 行已搬进 SettingsYoutubeSettingsColumn 面板,**不再占主列表下标**——所以这里
  // 没有它们的条目,下面各行比 P11-131 的展开态整体前移 9。
  SettingsItemYoutubeSettings -> 26
  SettingsItemWebDav -> 27
  SettingsItemWebDavBackup -> 28
  SettingsItemWebDavRestore -> 29
  SettingsItemIptv -> 30
  SettingsItemTvbox -> 31
  SettingsItemLogs -> 32
  SettingsItemPlayerLogOverlay -> 33
  SettingsItemCrashLogAutoReport -> 34
  // 35 = "update-header" section title in LazyColumn
  SettingsItemUpdateCurrentVersion -> 36
  SettingsItemUpdateDownloadOrInstall -> 37
  // 38 = "update-release-notes"(有新版才渲染);「关于」并入程序更新节,排在更新日志之后。
  SettingsItemUpdateReleaseNotes -> if (shouldShowReleaseNotesAction(updateState)) 38 else -1
  SettingsItemAbout -> if (shouldShowReleaseNotesAction(updateState)) 39 else 38
  // P11-148:未知项(例如只属于二级面板、不占主列表下标的 YouTube 行)一律 -1 = 跳过,
  // **不要**回落 0——0 是 Account,会表现成「按上下键跳到列表顶端」(P11-85 那类 bug 的形态),
  // 而 -1 只会被 moveSettingFocus 静默跳过并留一条日志,错得可控。
  else -> -1
}


