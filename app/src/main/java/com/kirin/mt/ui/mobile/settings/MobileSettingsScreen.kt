package com.kirin.mt.ui.mobile.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.i18n.ChineseTextVariant
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.settings.AppVisualPerformanceMode
import com.kirin.mt.core.settings.HomeThemeVariant
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.storage.UserSession
import com.kirin.mt.core.update.ApkInstaller
import com.kirin.mt.core.update.InstallResult
import com.kirin.mt.core.update.UpdateManager
import com.kirin.mt.core.update.UpdateUiState
import com.kirin.mt.ui.settings.checkActionLabel
import com.kirin.mt.ui.settings.currentVersionText
import com.kirin.mt.ui.settings.downloadOrInstallLabel
import com.kirin.mt.ui.settings.downloadProgressFraction
import com.kirin.mt.ui.settings.isCheckActionEnabled
import com.kirin.mt.ui.settings.isDownloadOrInstallActionEnabled
import com.kirin.mt.ui.settings.latestVersionText
import kotlinx.coroutines.launch

@Composable
fun MobileSettingsScreen(
  appSettingsStore: AppSettingsStore,
  updateManager: UpdateManager,
  apkInstaller: ApkInstaller,
  sessionStore: SessionStore,
  authRepository: AuthRepository,
  onLogin: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings by appSettingsStore.settings.collectAsState(initial = AppSettings())
  val updateState by updateManager.state.collectAsState()
  val session by sessionStore.session.collectAsState(initial = UserSession())

  // 安装已下载的 APK:弹系统安装 Intent,补未知来源授权兜底(镜像 TV AppShell)。
  fun installDownloadedApk() {
    val activity = context.findActivity()
    val file = updateManager.downloadedFile()
    if (activity == null || file == null) return
    when (val result = apkInstaller.startInstall(activity, file)) {
      is InstallResult.NeedsUnknownSourcesPermission -> {
        context.startActivity(apkInstaller.buildUnknownSourcesIntent())
        Toast.makeText(
          context,
          R.string.settings_update_install_unknown_sources_required,
          Toast.LENGTH_LONG,
        ).show()
      }
      is InstallResult.Failed -> Toast.makeText(
        context,
        context.getString(R.string.settings_update_failed_with_message, result.message),
        Toast.LENGTH_SHORT,
      ).show()
      else -> Unit
    }
  }

  // 最新版本 row 的动作分派:Available → 下载,Downloaded → 安装,其它 → 不可点。
  val updateVersionOnClick: (() -> Unit)? = when (updateState.status) {
    is UpdateUiState.Status.Available -> { { scope.launch { updateManager.download() } } }
    is UpdateUiState.Status.Downloaded -> { { installDownloadedApk() } }
    else -> null
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // ===== 账号 =====
    MobileSettingsSectionHeader(stringResource(R.string.settings_account_section))
    MobileSettingsRow(
      title = if (session.isLoggedIn) {
        stringResource(R.string.mobile_account_signed_in, session.uname.orEmpty().ifBlank { "uid ${session.mid}" })
      } else {
        stringResource(R.string.mobile_account_signed_out)
      },
      description = if (session.isLoggedIn) null else stringResource(R.string.settings_login_description),
      onClick = {
        if (session.isLoggedIn) {
          scope.launch { authRepository.clearSession() }
        } else {
          onLogin()
        }
      },
      trailing = {
        Text(
          text = if (session.isLoggedIn) stringResource(R.string.mobile_logout) else stringResource(R.string.mobile_login),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      },
    )

    // ===== 播放设置 =====
    MobileSettingsSectionHeader(stringResource(R.string.settings_playback_section))
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_playback_quality_title),
      description = stringResource(R.string.settings_playback_quality_description),
      selected = settings.playbackQualityPreference,
      selectedLabel = qualityLabel(settings.playbackQualityPreference),
      options = enumOptions(PlaybackQualityPreference.entries) { qualityLabel(it) },
      onSelected = { scope.launch { appSettingsStore.setPlaybackQualityPreference(it) } },
    )
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_playback_codec_title),
      description = stringResource(R.string.settings_playback_codec_description),
      selected = settings.playbackCodecPreference,
      selectedLabel = codecLabel(settings.playbackCodecPreference),
      options = enumOptions(PlaybackCodecPreference.entries) { codecLabel(it) },
      onSelected = { scope.launch { appSettingsStore.setPlaybackCodecPreference(it) } },
    )
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_playback_cdn_title),
      description = stringResource(R.string.settings_playback_cdn_description),
      selected = settings.playbackCdnPreference,
      selectedLabel = cdnLabel(settings.playbackCdnPreference),
      options = enumOptions(PlaybackCdnPreference.entries) { cdnLabel(it) },
      onSelected = { scope.launch { appSettingsStore.setPlaybackCdnPreference(it) } },
    )
    MobileSwitchRow(
      title = stringResource(R.string.settings_seek_preview_sprites_title),
      description = stringResource(R.string.settings_seek_preview_sprites_description),
      checked = settings.seekPreviewSpritesEnabled,
      onCheckedChange = { scope.launch { appSettingsStore.setSeekPreviewSpritesEnabled(it) } },
    )
    MobileSwitchRow(
      title = stringResource(R.string.settings_air_jump_assistant_title),
      description = stringResource(R.string.settings_air_jump_assistant_description),
      checked = settings.airJumpAssistantEnabled,
      onCheckedChange = { scope.launch { appSettingsStore.setAirJumpAssistantEnabled(it) } },
    )
    MobileSwitchRow(
      title = stringResource(R.string.settings_confirm_playback_exit_title),
      description = stringResource(R.string.settings_confirm_playback_exit_description),
      checked = settings.confirmPlaybackExit,
      onCheckedChange = { scope.launch { appSettingsStore.setConfirmPlaybackExit(it) } },
    )
    MobileSwitchRow(
      title = stringResource(R.string.settings_auto_play_next_episode_title),
      description = stringResource(R.string.settings_auto_play_next_episode_description),
      checked = settings.autoPlayNextEpisode,
      onCheckedChange = { scope.launch { appSettingsStore.setAutoPlayNextEpisode(it) } },
    )
    MobileSwitchRow(
      title = stringResource(R.string.settings_auto_play_related_video_title),
      description = stringResource(R.string.settings_auto_play_related_video_description),
      checked = settings.autoPlayRelatedVideo,
      onCheckedChange = { scope.launch { appSettingsStore.setAutoPlayRelatedVideo(it) } },
    )
    MobileSwitchRow(
      title = stringResource(R.string.settings_auto_return_home_on_completion_title),
      description = stringResource(R.string.settings_auto_return_home_on_completion_description),
      checked = settings.autoReturnHomeOnCompletion,
      onCheckedChange = { scope.launch { appSettingsStore.setAutoReturnHomeOnCompletion(it) } },
    )
    MobileSwitchRow(
      title = stringResource(R.string.settings_show_clock_title),
      description = stringResource(R.string.settings_show_clock_description),
      checked = settings.showClock,
      onCheckedChange = { scope.launch { appSettingsStore.setShowClock(it) } },
    )
    MobileSwitchRow(
      title = stringResource(R.string.settings_show_mini_progress_bar_title),
      description = stringResource(R.string.settings_show_mini_progress_bar_description),
      checked = settings.showMiniProgressBar,
      onCheckedChange = { scope.launch { appSettingsStore.setShowMiniProgressBar(it) } },
    )

    // ===== 界面与交互 =====
    MobileSettingsSectionHeader(stringResource(R.string.settings_interaction_section))
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_visual_performance_title),
      description = stringResource(R.string.settings_visual_performance_description),
      selected = settings.visualPerformanceMode,
      selectedLabel = performanceLabel(settings.visualPerformanceMode),
      options = enumOptions(AppVisualPerformanceMode.entries) { performanceLabel(it) },
      onSelected = { scope.launch { appSettingsStore.setVisualPerformanceMode(it) } },
    )
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_home_theme_title),
      description = stringResource(R.string.settings_home_theme_description),
      selected = settings.homeThemeVariant,
      selectedLabel = themeLabel(settings.homeThemeVariant),
      options = enumOptions(HomeThemeVariant.entries) { themeLabel(it) },
      onSelected = { scope.launch { appSettingsStore.setHomeThemeVariant(it) } },
    )
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_language_title),
      description = stringResource(R.string.settings_language_description),
      selected = settings.chineseTextVariant,
      selectedLabel = languageLabel(settings.chineseTextVariant),
      options = enumOptions(ChineseTextVariant.entries) { languageLabel(it) },
      onSelected = { scope.launch { appSettingsStore.setChineseTextVariant(it) } },
    )

    // ===== 程序更新 =====
    MobileSettingsSectionHeader(stringResource(R.string.settings_update_section))
    MobileSettingsRow(
      title = stringResource(R.string.settings_update_current_version_title),
      description = currentVersionText(updateState),
    )
    // 最新版本 row 内联下载/进度/安装:不再单开下载更新栏。
    MobileUpdateVersionRow(
      title = stringResource(R.string.settings_update_latest_version_title),
      description = latestVersionText(updateState),
      actionLabel = downloadOrInstallLabel(updateState),
      actionEnabled = isDownloadOrInstallActionEnabled(updateState),
      progress = downloadProgressFraction(updateState),
      onClick = updateVersionOnClick,
    )
    MobileSettingsRow(
      title = stringResource(R.string.settings_update_check_action),
      description = stringResource(R.string.settings_update_check_action_description),
      enabled = isCheckActionEnabled(updateState),
      onClick = { scope.launch { updateManager.refresh() } },
      trailing = {
        Text(
          text = checkActionLabel(updateState),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      },
    )
  }
}

/**
 * 在 @Composable 上下文里用 for 循环把枚举映射成 (value, label) 列表。
 * 用 for 循环(而非 .map)以避免在非 composable lambda 里调用 @Composable 标签函数。
 */
@Composable
private fun <T> enumOptions(
  entries: Iterable<T>,
  labeler: @Composable (T) -> String,
): List<Pair<T, String>> {
  val list = ArrayList<Pair<T, String>>()
  for (e in entries) list.add(e to labeler(e))
  return list
}

@Composable
private fun qualityLabel(q: PlaybackQualityPreference): String = stringResource(
  when (q) {
    PlaybackQualityPreference.Highest -> R.string.settings_playback_quality_highest
    PlaybackQualityPreference.Q1080 -> R.string.settings_playback_quality_1080
    PlaybackQualityPreference.Q720 -> R.string.settings_playback_quality_720
    PlaybackQualityPreference.Q480 -> R.string.settings_playback_quality_480
  }
)

@Composable
private fun codecLabel(c: PlaybackCodecPreference): String = stringResource(
  when (c) {
    PlaybackCodecPreference.Auto -> R.string.settings_playback_codec_auto
    PlaybackCodecPreference.H264 -> R.string.settings_playback_codec_h264
    PlaybackCodecPreference.H265 -> R.string.settings_playback_codec_h265
    PlaybackCodecPreference.Av1 -> R.string.settings_playback_codec_av1
  }
)

@Composable
private fun cdnLabel(c: PlaybackCdnPreference): String = when (c) {
  PlaybackCdnPreference.Auto -> "自动"
  PlaybackCdnPreference.Official -> "官方"
  PlaybackCdnPreference.Aliyun -> "阿里云"
  PlaybackCdnPreference.Akamai -> "Akamai"
  PlaybackCdnPreference.Hw -> "华为"
}

@Composable
private fun performanceLabel(m: AppVisualPerformanceMode): String = stringResource(
  when (m) {
    AppVisualPerformanceMode.Smooth -> R.string.settings_visual_performance_smooth
    AppVisualPerformanceMode.Balanced -> R.string.settings_visual_performance_balanced
    AppVisualPerformanceMode.Refined -> R.string.settings_visual_performance_refined
  }
)

@Composable
private fun themeLabel(t: HomeThemeVariant): String = stringResource(
  when (t) {
    HomeThemeVariant.Pink -> R.string.settings_home_theme_pink
    HomeThemeVariant.Black -> R.string.settings_home_theme_black
    HomeThemeVariant.Gray -> R.string.settings_home_theme_gray
    HomeThemeVariant.BlueGray -> R.string.settings_home_theme_blue_gray
  }
)

@Composable
private fun languageLabel(v: ChineseTextVariant): String = stringResource(
  when (v) {
    ChineseTextVariant.Simplified -> R.string.settings_language_simplified
    ChineseTextVariant.HongKong -> R.string.settings_language_hong_kong
    ChineseTextVariant.Taiwan -> R.string.settings_language_taiwan
  }
)

private fun Context.findActivity(): Activity? {
  var ctx: Context? = this
  while (ctx is ContextWrapper) {
    if (ctx is Activity) return ctx
    ctx = ctx.baseContext
  }
  return null
}