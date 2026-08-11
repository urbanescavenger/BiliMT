package com.kirin.mt.ui.settings

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
import androidx.compose.runtime.withFrameNanos
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
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.player.SpeedTestUiState
import com.kirin.mt.core.player.YoutubeDefaultQuality
import com.kirin.mt.core.youtube.YoutubeContentRegion
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.core.settings.AppVisualPerformanceMode
import com.kirin.mt.core.settings.HomeThemeVariant
import com.kirin.mt.core.update.UpdateUiState
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.core.webdav.WebDavBackupState
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
  onYoutubeContentRegionChange: (YoutubeContentRegion) -> Unit,
  onDefaultPlaybackSpeedChange: (DefaultPlaybackSpeed) -> Unit,
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
  onAutoConfirmOnFocusChange: (Boolean) -> Unit,
  onAutoRefreshOnSwitchChange: (Boolean) -> Unit,
  onHomeSectionEnabledChange: (HomeSection, Boolean) -> Unit,
  onHomeSectionsOrderChange: (List<HomeSection>) -> Unit,
  onLogsSelected: () -> Unit,
  logFiles: List<LogCatcherUtil.LogFileInfo>,
  isRecordingLog: Boolean,
  viewingLogFile: java.io.File?,
  onViewLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onBackFromLogView: () -> Unit,
  onShareLog: (LogCatcherUtil.LogFileInfo) -> Unit,
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
  onWebDavConfigChange: (com.kirin.mt.core.webdav.WebDavConfig) -> Unit,
  onWebDavBackup: suspend (com.kirin.mt.core.webdav.WebDavConfig) -> Result<Unit>,
  onWebDavRestore: suspend (com.kirin.mt.core.webdav.WebDavConfig) -> Result<Int>,
  onIptvSourceUrlChange: (String) -> Unit,
) {
  val settingsListState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val density = LocalDensity.current
  val settingsRowFallbackHeightPx = with(density) {
    (BiliSizing.SettingsRowHeight + BiliSpacing.Md).roundToPx()
  }
  val settingsScrollInsetPx = with(density) {
    BiliSpacing.Md.roundToPx()
  }
  val settingFocusRequesters = remember {
    mapOf(
      SettingsItemPlaybackQuality to FocusRequester(),
      SettingsItemYoutubeDefaultQuality to FocusRequester(),
      SettingsItemDefaultSpeed to FocusRequester(),
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
      SettingsItemWebDav to FocusRequester(),
      SettingsItemWebDavBackup to FocusRequester(),
      SettingsItemWebDavRestore to FocusRequester(),
      SettingsItemIptv to FocusRequester(),
      SettingsItemLogs to FocusRequester(),
      SettingsItemAbout to FocusRequester(),
    )
  }
  var lastFocusedSettingItem by remember { mutableIntStateOf(SettingsItemPlaybackQuality) }
  var focusSettingJob by remember { mutableStateOf<Job?>(null) }
  var rightPanel by remember { mutableStateOf(SettingsRightPanel.None) }
  var showWebDavDialog by remember { mutableStateOf(false) }
  var showIptvDialog by remember { mutableStateOf(false) }

  fun focusSettingItem(itemIndex: Int, direction: Int = 0): Boolean {
    val lazyIndex = settingsItemToLazyIndex(itemIndex, updateState)
    if (lazyIndex < 0) return true
    focusSettingJob?.cancel()
    focusSettingJob = coroutineScope.launch {
      settingsListState.scrollItemIntoComfortableView(
        index = lazyIndex,
        direction = direction,
        fallbackItemHeightPx = settingsRowFallbackHeightPx,
        edgeInsetPx = settingsScrollInsetPx,
      )
      withFrameNanos { }
      settingFocusRequesters[itemIndex]?.requestFocus()
    }
    return true
  }

  fun moveSettingFocus(itemIndex: Int, direction: Int): Boolean {
    val currentOrderIndex = SettingsFocusableItems.indexOf(itemIndex)
    var nextOrderIndex = currentOrderIndex + direction
    while (nextOrderIndex in SettingsFocusableItems.indices) {
      val targetItem = SettingsFocusableItems[nextOrderIndex]
      if (settingsItemToLazyIndex(targetItem, updateState) >= 0) {
        return focusSettingItem(targetItem, direction)
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
        onSettingFocused = { itemIndex ->
          lastFocusedSettingItem = itemIndex
          rightPanel = when (itemIndex) {
            SettingsItemAbout -> SettingsRightPanel.About
            SettingsItemHomeSections -> SettingsRightPanel.HomeSections
            SettingsItemLogs -> SettingsRightPanel.Logs
            SettingsItemYoutubeChannels -> SettingsRightPanel.YoutubeChannels
            else -> SettingsRightPanel.None
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
        onYoutubeDefaultQualityChange = onYoutubeDefaultQualityChange,
        onYoutubeContentRegionChange = onYoutubeContentRegionChange,
        onDefaultPlaybackSpeedChange = onDefaultPlaybackSpeedChange,
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
        onYoutubeChannelsSelected = {
          rightPanel = if (rightPanel == SettingsRightPanel.YoutubeChannels) {
            SettingsRightPanel.None
          } else {
            SettingsRightPanel.YoutubeChannels
          }
        },
        onWebDavSelected = { showWebDavDialog = true },
        onIptvSourceUrlChange = onIptvSourceUrlChange,
        onIptvSelected = { showIptvDialog = true },
        onLogsSelected = onLogsSelected,
        logFiles = logFiles,
        isRecordingLog = isRecordingLog,
        viewingLogFile = viewingLogFile,
        onViewLog = onViewLog,
        onBackFromLogView = onBackFromLogView,
        onShareLog = onShareLog,
        onToggleLogRecording = onToggleLogRecording,
        updateState = updateState,
        onCheckUpdate = onCheckUpdate,
        onDownloadUpdate = onDownloadUpdate,
        onInstallUpdate = onInstallUpdate,
        onOpenReleaseNotes = onOpenReleaseNotes,
        speedTestState = speedTestState,
        onRunSpeedTest = onRunSpeedTest,
        channels = channels,
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
        onDismiss = { showWebDavDialog = false },
        modifier = Modifier.align(Alignment.Center),
      )
    }
    if (showIptvDialog) {
      SettingsIptvDialog(
        url = settings.iptvSourceUrl,
        onSave = { url ->
          onIptvSourceUrlChange(url)
          showIptvDialog = false
        },
        onDismiss = { showIptvDialog = false },
        modifier = Modifier.align(Alignment.Center),
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
  onYoutubeDefaultQualityChange: (YoutubeDefaultQuality) -> Unit,
  onYoutubeContentRegionChange: (YoutubeContentRegion) -> Unit,
  onDefaultPlaybackSpeedChange: (DefaultPlaybackSpeed) -> Unit,
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
  onAutoConfirmOnFocusChange: (Boolean) -> Unit,
  onAutoRefreshOnSwitchChange: (Boolean) -> Unit,
  onAboutSelected: () -> Unit,
  onHomeSectionsSelected: () -> Unit,
  onLogsSelected: () -> Unit,
  logFiles: List<LogCatcherUtil.LogFileInfo>,
  isRecordingLog: Boolean,
  viewingLogFile: java.io.File?,
  onViewLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onBackFromLogView: () -> Unit,
  onShareLog: (LogCatcherUtil.LogFileInfo) -> Unit,
  onToggleLogRecording: () -> Unit,
  updateState: UpdateUiState,
  onCheckUpdate: () -> Unit,
  onDownloadUpdate: () -> Unit,
  onInstallUpdate: () -> Unit,
  onOpenReleaseNotes: () -> Unit,
  speedTestState: SpeedTestUiState,
  onRunSpeedTest: () -> Unit,
  channels: List<com.kirin.mt.core.youtube.YoutubeChannel>,
  onAddYoutubeChannel: suspend (String) -> Boolean,
  onRemoveYoutubeChannel: suspend (String) -> Boolean,
  onYoutubeChannelsSelected: () -> Unit,
  webDavConfig: com.kirin.mt.core.webdav.WebDavConfig,
  onWebDavBackup: suspend (com.kirin.mt.core.webdav.WebDavConfig) -> Result<Unit>,
  onWebDavRestore: suspend (com.kirin.mt.core.webdav.WebDavConfig) -> Result<Int>,
  onWebDavSelected: () -> Unit,
  onIptvSourceUrlChange: (String) -> Unit,
  onIptvSelected: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var webDavState by remember { mutableStateOf<WebDavBackupState>(WebDavBackupState.Idle) }
  CompositionLocalProvider(LocalBringIntoViewSpec provides SettingsBringIntoViewSpec) {
    LazyColumn(
      state = listState,
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = BiliSpacing.Xxl),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
    ) {
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
      item(key = "youtube-default-quality") {
        val qualityOptions = remember { YoutubeDefaultQuality.entries.toList() }
        val effectiveQuality = settings.youtubeDefaultQuality
        SettingsOptionRow(
          title = stringResource(R.string.settings_youtube_default_quality_title),
          description = stringResource(R.string.settings_youtube_default_quality_description),
          value = effectiveQuality.label,
          modifier = Modifier
            .focusRequester(focusRequesters.getValue(SettingsItemYoutubeDefaultQuality))
            .settingsBoundaryKeys(
              itemIndex = SettingsItemYoutubeDefaultQuality,
              onMoveSettingFocus = onMoveSettingFocus,
              onMoveLeftToNav = onMoveLeftToNav,
            ),
          onFocused = { onSettingFocused(SettingsItemYoutubeDefaultQuality) },
          onClick = {
            val currentIndex = qualityOptions.indexOf(effectiveQuality).takeIf { it >= 0 } ?: 0
            onYoutubeDefaultQualityChange(qualityOptions[(currentIndex + 1) % qualityOptions.size])
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
    item(key = "youtube-header") {
      SettingsSectionTitle(
        text = stringResource(R.string.settings_youtube_section),
        modifier = Modifier.padding(top = BiliSpacing.Lg),
      )
    }
    item(key = "youtube-channels") {
      SettingsActionRow(
        title = stringResource(R.string.settings_youtube_channels),
        description = stringResource(R.string.settings_youtube_channels_desc),
        value = "${channels.size}",
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemYoutubeChannels))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemYoutubeChannels,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemYoutubeChannels) },
        onClick = onYoutubeChannelsSelected,
      )
    }
    item(key = "youtube-content-region") {
      val regionOptions = remember { YoutubeContentRegion.entries.toList() }
      val effectiveRegion = settings.youtubeContentRegion
      SettingsOptionRow(
        title = stringResource(R.string.settings_youtube_content_region_title),
        description = stringResource(R.string.settings_youtube_content_region_description),
        value = effectiveRegion.label,
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemYoutubeContentRegion))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemYoutubeContentRegion,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemYoutubeContentRegion) },
        onClick = {
          val currentIndex = regionOptions.indexOf(effectiveRegion).takeIf { it >= 0 } ?: 0
          onYoutubeContentRegionChange(regionOptions[(currentIndex + 1) % regionOptions.size])
        },
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
          coroutineScope.launch {
            webDavState = WebDavBackupState.Running(isRestore = false)
            val result = onWebDavBackup(webDavConfig)
            webDavState = WebDavBackupState.Idle
            val msg = result.fold(
              onSuccess = { context.getString(R.string.settings_webdav_backup_success) },
              onFailure = { context.getString(R.string.settings_webdav_failed, it.message ?: "") },
            )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
          }
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
          coroutineScope.launch {
            webDavState = WebDavBackupState.Running(isRestore = true)
            val result = onWebDavRestore(webDavConfig)
            webDavState = WebDavBackupState.Idle
            val msg = result.fold(
              onSuccess = { count -> context.getString(R.string.settings_webdav_restore_success, count) },
              onFailure = { context.getString(R.string.settings_webdav_failed, it.message ?: "") },
            )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
          }
        },
      )
    }
    item(key = "iptv") {
      SettingsActionRow(
        title = stringResource(R.string.settings_iptv_title),
        description = stringResource(R.string.settings_iptv_description),
        value = settings.iptvSourceUrl,
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

private const val SettingsItemPlaybackHeader = 0
private const val SettingsItemPlaybackQuality = 1
private const val SettingsItemPlaybackCodec = 2
private const val SettingsItemSeekPreviewSprites = 3
private const val SettingsItemAirJumpAssistant = 4
private const val SettingsItemConfirmPlaybackExit = 5
private const val SettingsItemAutoPlayNextEpisode = 6
private const val SettingsItemAutoPlayRelatedVideo = 7
private const val SettingsItemAutoReturnHomeOnCompletion = 8
private const val SettingsItemShowClock = 9
private const val SettingsItemShowMiniProgressBar = 10
private const val SettingsItemSpeedTest = 11
private const val SettingsItemVisualPerformanceMode = 12
private const val SettingsItemLiquidGlassCards = 13
private const val SettingsItemHomeThemeVariant = 14
private const val SettingsItemAutoConfirmOnFocus = 15
private const val SettingsItemAutoRefreshOnSwitch = 16
private const val SettingsItemYoutubeDefaultQuality = 17
private const val SettingsItemDefaultSpeed = 23
private const val SettingsItemClearCache = 18
private const val SettingsItemChineseTextVariant = 19
private const val SettingsItemAbout = 20
private const val SettingsItemHomeSections = 26
private const val SettingsItemUpdateCurrentVersion = 22
private const val SettingsItemUpdateDownloadOrInstall = 24
private const val SettingsItemUpdateReleaseNotes = 25
private const val SettingsItemPlaybackCdn = 21
private const val SettingsItemLogs = 27
private const val SettingsItemPlayerLogOverlay = 28
private const val SettingsItemYoutubeChannels = 29
private const val SettingsItemWebDav = 30
private const val SettingsItemYoutubeContentRegion = 33
private const val SettingsItemWebDavBackup = 31
private const val SettingsItemWebDavRestore = 32
private const val SettingsItemIptv = 34

private val SettingsFocusableItems = listOf(
  SettingsItemPlaybackQuality,
  SettingsItemYoutubeDefaultQuality,
  SettingsItemDefaultSpeed,
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
  SettingsItemPlayerLogOverlay,
  SettingsItemVisualPerformanceMode,
  SettingsItemLiquidGlassCards,
  SettingsItemHomeThemeVariant,
  SettingsItemAutoConfirmOnFocus,
  SettingsItemAutoRefreshOnSwitch,
  SettingsItemUpdateCurrentVersion,
  SettingsItemUpdateDownloadOrInstall,
  SettingsItemUpdateReleaseNotes,
  SettingsItemClearCache,
  SettingsItemChineseTextVariant,
  SettingsItemHomeSections,
  SettingsItemYoutubeChannels,
  SettingsItemYoutubeContentRegion,
  SettingsItemWebDav,
  SettingsItemWebDavBackup,
  SettingsItemWebDavRestore,
  SettingsItemIptv,
  SettingsItemLogs,
  SettingsItemAbout,
  SettingsItemPlayerLogOverlay,
)

private enum class SettingsRightPanel {
  None,
  HomeSections,
  Logs,
  About,
  YoutubeChannels,
}

private fun settingsItemToLazyIndex(
  itemIndex: Int,
  updateState: UpdateUiState,
): Int = when (itemIndex) {
  SettingsItemPlaybackHeader -> 0
  SettingsItemPlaybackQuality -> 1
  SettingsItemYoutubeDefaultQuality -> 2
  SettingsItemDefaultSpeed -> 3
  SettingsItemPlaybackCodec -> 4
  SettingsItemPlaybackCdn -> 5
  SettingsItemSpeedTest -> 6
  SettingsItemSeekPreviewSprites -> 7
  SettingsItemAirJumpAssistant -> 8
  SettingsItemConfirmPlaybackExit -> 9
  SettingsItemAutoPlayNextEpisode -> 10
  SettingsItemAutoPlayRelatedVideo -> 11
  SettingsItemAutoReturnHomeOnCompletion -> 12
  SettingsItemShowClock -> 13
  SettingsItemShowMiniProgressBar -> 14
  // 15 = "ui-header" section title in LazyColumn
  SettingsItemVisualPerformanceMode -> 16
  SettingsItemLiquidGlassCards -> 17
  SettingsItemHomeThemeVariant -> 18
  SettingsItemAutoConfirmOnFocus -> 19
  SettingsItemAutoRefreshOnSwitch -> 20
  // 21 = "update-header" section title in LazyColumn
  SettingsItemUpdateCurrentVersion -> 22
  SettingsItemUpdateDownloadOrInstall -> 23
  SettingsItemUpdateReleaseNotes -> if (shouldShowReleaseNotesAction(updateState)) 24 else -1
  SettingsItemClearCache -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    25 + updateExtraCount
  }
  SettingsItemChineseTextVariant -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    26 + updateExtraCount
  }
  SettingsItemHomeSections -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    27 + updateExtraCount
  }
  SettingsItemLogs -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    34 + updateExtraCount
  }
  SettingsItemAbout -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    35 + updateExtraCount
  }
  SettingsItemPlayerLogOverlay -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    36 + updateExtraCount
  }
  SettingsItemYoutubeChannels -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    29 + updateExtraCount
  }
  SettingsItemYoutubeContentRegion -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    30 + updateExtraCount
  }
  SettingsItemWebDav -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    31 + updateExtraCount
  }
  SettingsItemWebDavBackup -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    32 + updateExtraCount
  }
  SettingsItemWebDavRestore -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    33 + updateExtraCount
  }
  SettingsItemIptv -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    34 + updateExtraCount
  }
  else -> 0
}

// 合并行(update-latest-version)无条件渲染,只数 release-notes 一个条件项。
private fun updateExtraItemCount(updateState: UpdateUiState): Int {
  return if (shouldShowReleaseNotesAction(updateState)) 1 else 0
}
