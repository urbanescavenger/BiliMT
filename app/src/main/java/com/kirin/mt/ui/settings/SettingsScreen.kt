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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.kirin.mt.R
import com.kirin.mt.core.i18n.ChineseTextVariant
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.player.CodecCapability
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.core.settings.AppVisualPerformanceMode
import com.kirin.mt.core.settings.HomeThemeVariant
import com.kirin.mt.core.update.UpdateUiState
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
  onPlaybackCodecPreferenceChange: (PlaybackCodecPreference) -> Unit,
  onPlaybackCdnPreferenceChange: (PlaybackCdnPreference) -> Unit,
  onAirJumpAssistantEnabledChange: (Boolean) -> Unit,
  onConfirmPlaybackExitChange: (Boolean) -> Unit,
  onAutoPlayNextEpisodeChange: (Boolean) -> Unit,
  onAutoPlayRelatedVideoChange: (Boolean) -> Unit,
  onAutoReturnHomeOnCompletionChange: (Boolean) -> Unit,
  onShowClockChange: (Boolean) -> Unit,
  onShowMiniProgressBarChange: (Boolean) -> Unit,
  onAutoConfirmOnFocusChange: (Boolean) -> Unit,
  onAutoRefreshOnSwitchChange: (Boolean) -> Unit,
  onHomeSectionEnabledChange: (HomeSection, Boolean) -> Unit,
  updateState: UpdateUiState,
  onCheckUpdate: () -> Unit,
  onDownloadUpdate: () -> Unit,
  onInstallUpdate: () -> Unit,
  onOpenReleaseNotes: () -> Unit,
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
      SettingsItemAutoConfirmOnFocus to FocusRequester(),
      SettingsItemAutoRefreshOnSwitch to FocusRequester(),
      SettingsItemVisualPerformanceMode to FocusRequester(),
      SettingsItemLiquidGlassCards to FocusRequester(),
      SettingsItemHomeThemeVariant to FocusRequester(),
      SettingsItemUpdateCheck to FocusRequester(),
      SettingsItemUpdateDownloadOrInstall to FocusRequester(),
      SettingsItemUpdateReleaseNotes to FocusRequester(),
      SettingsItemAbout to FocusRequester(),
    )
  }
  var lastFocusedSettingItem by remember { mutableIntStateOf(SettingsItemPlaybackQuality) }
  var focusSettingJob by remember { mutableStateOf<Job?>(null) }
  var rightPanel by remember { mutableStateOf(SettingsRightPanel.HomeSections) }

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
    val targetItem = SettingsFocusableItems.getOrNull(currentOrderIndex + direction) ?: return true
    return focusSettingItem(targetItem, direction)
  }

  Box(
    modifier = Modifier.fillMaxSize(),
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
          rightPanel = if (itemIndex == SettingsItemAbout) {
            SettingsRightPanel.About
          } else {
            SettingsRightPanel.HomeSections
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
        onPlaybackCodecPreferenceChange = onPlaybackCodecPreferenceChange,
        onPlaybackCdnPreferenceChange = onPlaybackCdnPreferenceChange,
        onAirJumpAssistantEnabledChange = onAirJumpAssistantEnabledChange,
        onConfirmPlaybackExitChange = onConfirmPlaybackExitChange,
        onAutoPlayNextEpisodeChange = onAutoPlayNextEpisodeChange,
        onAutoPlayRelatedVideoChange = onAutoPlayRelatedVideoChange,
        onAutoReturnHomeOnCompletionChange = onAutoReturnHomeOnCompletionChange,
        onShowClockChange = onShowClockChange,
        onShowMiniProgressBarChange = onShowMiniProgressBarChange,
        onAutoConfirmOnFocusChange = onAutoConfirmOnFocusChange,
        onAutoRefreshOnSwitchChange = onAutoRefreshOnSwitchChange,
        onAboutSelected = {
          rightPanel = SettingsRightPanel.About
        },
        updateState = updateState,
        onCheckUpdate = onCheckUpdate,
        onDownloadUpdate = onDownloadUpdate,
        onInstallUpdate = onInstallUpdate,
        onOpenReleaseNotes = onOpenReleaseNotes,
        modifier = Modifier.weight(1f),
      )
      when (rightPanel) {
        SettingsRightPanel.HomeSections -> SettingsHomeSectionsColumn(
          settings = settings,
          onMoveLeftToSettings = { focusSettingItem(lastFocusedSettingItem) },
          onHomeSectionEnabledChange = onHomeSectionEnabledChange,
          modifier = Modifier.weight(1f),
        )
        SettingsRightPanel.About -> SettingsAboutColumn(
          modifier = Modifier.weight(1f),
        )
      }
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
  onPlaybackCodecPreferenceChange: (PlaybackCodecPreference) -> Unit,
  onPlaybackCdnPreferenceChange: (PlaybackCdnPreference) -> Unit,
  onAirJumpAssistantEnabledChange: (Boolean) -> Unit,
  onConfirmPlaybackExitChange: (Boolean) -> Unit,
  onAutoPlayNextEpisodeChange: (Boolean) -> Unit,
  onAutoPlayRelatedVideoChange: (Boolean) -> Unit,
  onAutoReturnHomeOnCompletionChange: (Boolean) -> Unit,
  onShowClockChange: (Boolean) -> Unit,
  onShowMiniProgressBarChange: (Boolean) -> Unit,
  onAutoConfirmOnFocusChange: (Boolean) -> Unit,
  onAutoRefreshOnSwitchChange: (Boolean) -> Unit,
  onAboutSelected: () -> Unit,
  updateState: UpdateUiState,
  onCheckUpdate: () -> Unit,
  onDownloadUpdate: () -> Unit,
  onInstallUpdate: () -> Unit,
  onOpenReleaseNotes: () -> Unit,
  modifier: Modifier = Modifier,
) {
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
        description = stringResource(R.string.settings_update_section_description),
        value = currentVersionText(updateState),
        modifier = Modifier
          .focusRequester(focusRequesters.getValue(SettingsItemUpdateCheck))
          .settingsBoundaryKeys(
            itemIndex = SettingsItemUpdateCheck,
            onMoveSettingFocus = onMoveSettingFocus,
            onMoveLeftToNav = onMoveLeftToNav,
          ),
        onFocused = { onSettingFocused(SettingsItemUpdateCheck) },
        onClick = onCheckUpdate,
      )
    }
    if (shouldShowDownloadOrInstallRow(updateState)) {
      item(key = "update-download-or-install") {
        SettingsActionRow(
          title = when (updateState.status) {
            is UpdateUiState.Status.Downloaded -> stringResource(R.string.settings_update_install_action)
            else -> stringResource(R.string.settings_update_download_action)
          },
          description = when (updateState.status) {
            is UpdateUiState.Status.Downloaded -> stringResource(R.string.settings_update_status_downloaded)
            is UpdateUiState.Status.Downloading -> stringResource(R.string.settings_update_checking)
            else -> stringResource(R.string.settings_update_latest_version_value_available)
          },
          value = latestVersionText(updateState),
          modifier = Modifier
            .focusRequester(focusRequesters.getValue(SettingsItemUpdateDownloadOrInstall))
            .settingsBoundaryKeys(
              itemIndex = SettingsItemUpdateDownloadOrInstall,
              onMoveSettingFocus = onMoveSettingFocus,
              onMoveLeftToNav = onMoveLeftToNav,
            ),
          onFocused = { onSettingFocused(SettingsItemUpdateDownloadOrInstall) },
          onClick = {
            if (updateState.status is UpdateUiState.Status.Downloaded) {
              onInstallUpdate()
            } else {
              onDownloadUpdate()
            }
          },
        )
      }
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
private const val SettingsItemVisualPerformanceMode = 12
private const val SettingsItemLiquidGlassCards = 13
private const val SettingsItemHomeThemeVariant = 14
private const val SettingsItemAutoConfirmOnFocus = 15
private const val SettingsItemAutoRefreshOnSwitch = 16
private const val SettingsItemClearCache = 18
private const val SettingsItemChineseTextVariant = 19
private const val SettingsItemAbout = 20
private const val SettingsItemUpdateCheck = 22
private const val SettingsItemUpdateDownloadOrInstall = 23
private const val SettingsItemUpdateReleaseNotes = 24
private const val SettingsItemPlaybackCdn = 21

private val SettingsFocusableItems = listOf(
  SettingsItemPlaybackQuality,
  SettingsItemPlaybackCodec,
  SettingsItemPlaybackCdn,
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
  SettingsItemUpdateCheck,
  SettingsItemUpdateDownloadOrInstall,
  SettingsItemUpdateReleaseNotes,
  SettingsItemClearCache,
  SettingsItemChineseTextVariant,
  SettingsItemAbout,
)

private enum class SettingsRightPanel {
  HomeSections,
  About,
}

private fun settingsItemToLazyIndex(
  itemIndex: Int,
  updateState: UpdateUiState,
): Int = when (itemIndex) {
  SettingsItemPlaybackHeader -> 0
  SettingsItemPlaybackQuality -> 1
  SettingsItemPlaybackCodec -> 2
  SettingsItemPlaybackCdn -> 3
  SettingsItemSeekPreviewSprites -> 4
  SettingsItemAirJumpAssistant -> 5
  SettingsItemConfirmPlaybackExit -> 6
  SettingsItemAutoPlayNextEpisode -> 7
  SettingsItemAutoPlayRelatedVideo -> 8
  SettingsItemAutoReturnHomeOnCompletion -> 9
  SettingsItemShowClock -> 10
  SettingsItemShowMiniProgressBar -> 11
  // 12 = "ui-header" section title in LazyColumn
  SettingsItemVisualPerformanceMode -> 13
  SettingsItemLiquidGlassCards -> 14
  SettingsItemHomeThemeVariant -> 15
  SettingsItemAutoConfirmOnFocus -> 16
  SettingsItemAutoRefreshOnSwitch -> 17
  // 18 = "update-header" section title in LazyColumn
  SettingsItemUpdateCheck -> 19
  SettingsItemUpdateDownloadOrInstall -> if (shouldShowDownloadOrInstallRow(updateState)) 20 else -1
  SettingsItemUpdateReleaseNotes -> if (shouldShowReleaseNotesAction(updateState)) {
    if (shouldShowDownloadOrInstallRow(updateState)) 21 else 20
  } else {
    -1
  }
  SettingsItemClearCache -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    20 + updateExtraCount
  }
  SettingsItemChineseTextVariant -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    21 + updateExtraCount
  }
  SettingsItemAbout -> {
    val updateExtraCount = updateExtraItemCount(updateState)
    22 + updateExtraCount
  }
  else -> 0
}

private fun updateExtraItemCount(updateState: UpdateUiState): Int {
  var count = 0
  if (shouldShowDownloadOrInstallRow(updateState)) count++
  if (shouldShowReleaseNotesAction(updateState)) count++
  return count
}
