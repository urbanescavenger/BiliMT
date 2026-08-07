package com.kirin.mt.ui.mobile.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.cache.AppCacheManager
import com.kirin.mt.core.cache.formatCacheSize
import com.kirin.mt.core.image.BiliImageSizing
import com.kirin.mt.core.image.buildOwnerAvatarRequest
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
import com.kirin.mt.ui.settings.currentVersionText
import com.kirin.mt.ui.settings.downloadProgressFraction
import com.kirin.mt.ui.settings.isUpdateVersionActionEnabled
import com.kirin.mt.ui.settings.latestVersionText
import com.kirin.mt.ui.settings.updateVersionActionLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSettingsScreen(
  appSettingsStore: AppSettingsStore,
  updateManager: UpdateManager,
  apkInstaller: ApkInstaller,
  sessionStore: SessionStore,
  authRepository: AuthRepository,
  onOpenFollows: (FollowManageKind) -> Unit,
  onLogin: () -> Unit,
  onOpenLogs: () -> Unit,
  webdavConfigStore: com.kirin.mt.core.webdav.WebDavConfigStore,
  webdavBackupService: com.kirin.mt.core.webdav.WebDavBackupService,
  appCacheManager: AppCacheManager,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings by appSettingsStore.settings.collectAsState(initial = AppSettings())
  val updateState by updateManager.state.collectAsState()
  val session by sessionStore.session.collectAsState(initial = UserSession())
  val webDavConfig by webdavConfigStore.config.collectAsState(initial = com.kirin.mt.core.webdav.WebDavConfig())
  var showFollowSheet by remember { mutableStateOf(false) }
  var showLoginRequiredDialog by remember { mutableStateOf(false) }

  // 缓存大小:进入页面时算一次,清理后再算一次;null 时显示「计算中」(镜像 TV AppShell)。
  var cacheSizeBytes by remember { mutableStateOf<Long?>(null) }
  LaunchedEffect(Unit) { cacheSizeBytes = appCacheManager.cacheSizeBytes() }

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

  // 最新版本 row 的动作分派(已并入「检查更新」):Available → 下载,Downloaded → 安装,
  // Idle/UpToDate/Failed → 检查更新,Checking/Downloading → 不可点。
  val updateVersionOnClick: (() -> Unit)? = when (updateState.status) {
    is UpdateUiState.Status.Available -> { { scope.launch { updateManager.download() } } }
    is UpdateUiState.Status.Downloaded -> { { installDownloadedApk() } }
    is UpdateUiState.Status.Checking, is UpdateUiState.Status.Downloading -> null
    else -> { { scope.launch { updateManager.refresh() } } }
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
    MobileAccountHeader(
      session = session,
      onClick = {
        if (session.isLoggedIn) showFollowSheet = true
        else showLoginRequiredDialog = true
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

    // ===== 首页分区(与 TV 同一份配置,排序+显示隐藏;默认折叠,点标题展开) =====
    MobileHomeSectionsPanel(
      settings = settings,
      appSettingsStore = appSettingsStore,
      scope = scope,
    )

    // ===== 程序更新 =====
    MobileSettingsSectionHeader(stringResource(R.string.settings_update_section))
    MobileSettingsRow(
      title = stringResource(R.string.settings_update_current_version_title),
      description = currentVersionText(updateState),
    )
    // 最新版本 row 内联下载/进度/安装 + 检查更新(已并入此行,不再单开检查更新栏)。
    MobileUpdateVersionRow(
      title = stringResource(R.string.settings_update_latest_version_title),
      description = latestVersionText(updateState),
      actionLabel = updateVersionActionLabel(updateState),
      actionEnabled = isUpdateVersionActionEnabled(updateState),
      progress = downloadProgressFraction(updateState),
      onClick = updateVersionOnClick,
    )

    // ===== 系统设置 =====
    MobileSettingsSectionHeader(stringResource(R.string.settings_performance_section))
    MobileSettingsRow(
      title = stringResource(R.string.settings_logs_entry_title),
      description = stringResource(R.string.settings_logs_entry_description),
      onClick = onOpenLogs,
    )
    MobileSettingsRow(
      title = stringResource(R.string.settings_clear_cache_title),
      description = stringResource(R.string.settings_clear_cache_description),
      trailing = {
        Text(
          text = cacheSizeBytes?.let(::formatCacheSize)
            ?: stringResource(R.string.settings_clear_cache_calculating),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      onClick = {
        scope.launch {
          val result = appCacheManager.clearCache()
          cacheSizeBytes = appCacheManager.cacheSizeBytes()
          Toast.makeText(
            context,
            context.getString(R.string.settings_clear_cache_done, formatCacheSize(result.clearedBytes)),
            Toast.LENGTH_SHORT,
          ).show()
        }
      },
    )

    // ===== WebDAV 备份 =====
    MobileWebDavSection(
      config = webDavConfig,
      onConfigChange = { cfg -> scope.launch { webdavConfigStore.setConfig(cfg) } },
      onBackup = { cfg -> webdavBackupService.backup(cfg) },
      onRestore = { cfg -> webdavBackupService.restore(cfg) },
    )
  }

    if (showFollowSheet) {
      MobileFollowSheet(
        onDismiss = { showFollowSheet = false },
        onBiliFollows = {
          showFollowSheet = false
          onOpenFollows(FollowManageKind.BiliFollows)
        },
        onYoutubeFollows = {
          showFollowSheet = false
          onOpenFollows(FollowManageKind.YoutubeFollows)
        },
        onLogout = {
          showFollowSheet = false
          scope.launch { authRepository.clearSession() }
        },
      )
    }

    if (showLoginRequiredDialog) {
      AlertDialog(
        onDismissRequest = { showLoginRequiredDialog = false },
        title = { Text(stringResource(R.string.mobile_account_login_required)) },
        text = { Text(stringResource(R.string.mobile_account_login_required_body)) },
        confirmButton = {
          TextButton(onClick = {
            showLoginRequiredDialog = false
            onLogin()
          }) {
            Text(stringResource(R.string.mobile_login))
          }
        },
        dismissButton = {
          TextButton(onClick = { showLoginRequiredDialog = false }) {
            Text(stringResource(R.string.mobile_dialog_cancel))
          }
        },
      )
    }
}

/** 设置顶部账号信息卡:圆形头像 + 昵称/UID + VIP 角标 + 右箭头;点击走关注管理/登录弹窗。 */
@Composable
private fun MobileAccountHeader(
  session: UserSession,
  onClick: () -> Unit,
) {
  val context = LocalContext.current
  Card(
    modifier = Modifier.fillMaxWidth().clickable { onClick() },
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    shape = MaterialTheme.shapes.medium,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (session.isLoggedIn && !session.face.isNullOrBlank()) {
          val request = remember(context, session.face) {
            buildOwnerAvatarRequest(
              context = context,
              url = session.face.orEmpty(),
              sizePx = BiliImageSizing.AccountAvatarSizePx,
            )
          }
          AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(CircleShape),
          )
        } else {
          Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              painter = painterResource(R.drawable.ic_nav_account),
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(28.dp),
            )
          }
        }
        if (session.isVip) {
          Box(
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .size(16.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "V",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimary,
            )
          }
        }
      }
      Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
        Text(
          text = if (session.isLoggedIn) {
            session.uname.orEmpty().ifBlank { "uid ${session.mid}" }
          } else {
            stringResource(R.string.mobile_account_signed_out)
          },
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = if (session.isLoggedIn) {
            session.mid?.let { "UID $it" } ?: "UID"
          } else {
            stringResource(R.string.settings_login_description)
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Icon(
        painter = painterResource(R.drawable.ic_player_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** 登录后点账号卡弹出的底部选择:关注管理(二选一) + 退出登录。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileFollowSheet(
  onDismiss: () -> Unit,
  onBiliFollows: () -> Unit,
  onYoutubeFollows: () -> Unit,
  onLogout: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
    ) {
      MobileSettingsRow(
        title = stringResource(R.string.mobile_follows_bili),
        onClick = onBiliFollows,
      )
      MobileSettingsRow(
        title = stringResource(R.string.mobile_follows_youtube),
        onClick = onYoutubeFollows,
      )
      MobileSettingsRow(
        title = stringResource(R.string.mobile_logout),
        onClick = onLogout,
      )
    }
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

/** WebDAV 备份区:地址行只显示 URL,点按/长按弹窗编辑网址/账号/密码,备份/还原单独按钮行(成功 Toast)。 */
@Composable
private fun MobileWebDavSection(
  config: com.kirin.mt.core.webdav.WebDavConfig,
  onConfigChange: (com.kirin.mt.core.webdav.WebDavConfig) -> Unit,
  onBackup: suspend (com.kirin.mt.core.webdav.WebDavConfig) -> Result<Unit>,
  onRestore: suspend (com.kirin.mt.core.webdav.WebDavConfig) -> Result<Int>,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var showEditDialog by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var expanded by remember { mutableStateOf(false) }

  fun runBackup() {
    if (busy) return
    busy = true
    scope.launch {
      val result = onBackup(config)
      busy = false
      val msg = result.fold(
        onSuccess = { context.getString(R.string.settings_webdav_backup_success) },
        onFailure = { context.getString(R.string.settings_webdav_failed, it.message ?: "") },
      )
      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
  }

  fun runRestore() {
    if (busy) return
    busy = true
    scope.launch {
      val result = onRestore(config)
      busy = false
      val msg = result.fold(
        onSuccess = { count -> context.getString(R.string.settings_webdav_restore_success, count) },
        onFailure = { context.getString(R.string.settings_webdav_failed, it.message ?: "") },
      )
      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
  }

  MobileSettingsSectionHeader(
    text = stringResource(R.string.settings_webdav_section),
    onClick = { expanded = !expanded },
    trailing = {
      Icon(
        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
  )
  androidx.compose.animation.AnimatedVisibility(visible = expanded) {
    Column {
      MobileSettingsRow(
        title = stringResource(R.string.settings_webdav_url_label),
        description = config.url.ifBlank { stringResource(R.string.settings_webdav_configure_hint) },
        onClick = { showEditDialog = true },
        onLongClick = { showEditDialog = true },
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Button(onClick = ::runBackup, enabled = !busy, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.settings_webdav_backup))
        }
        Button(onClick = ::runRestore, enabled = !busy, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.settings_webdav_restore))
        }
      }
    }
  }

  if (showEditDialog) {
    MobileWebDavEditDialog(
      config = config,
      onSave = { cfg ->
        onConfigChange(cfg)
        showEditDialog = false
      },
      onDismiss = { showEditDialog = false },
    )
  }
}

/** WebDAV 编辑弹窗:URL/账号/密码三个输入框 + 保存/取消。 */
@Composable
private fun MobileWebDavEditDialog(
  config: com.kirin.mt.core.webdav.WebDavConfig,
  onSave: (com.kirin.mt.core.webdav.WebDavConfig) -> Unit,
  onDismiss: () -> Unit,
) {
  var url by remember { mutableStateOf(config.url) }
  var username by remember { mutableStateOf(config.username) }
  var password by remember { mutableStateOf(config.password) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.settings_webdav_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text(stringResource(R.string.settings_webdav_url_label)) },
          singleLine = true,
        )
        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text(stringResource(R.string.settings_webdav_username_label)) },
          singleLine = true,
        )
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text(stringResource(R.string.settings_webdav_password_label)) },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(com.kirin.mt.core.webdav.WebDavConfig(url, username, password)) }) {
        Text(stringResource(R.string.settings_webdav_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.mobile_dialog_cancel))
      }
    },
  )
}