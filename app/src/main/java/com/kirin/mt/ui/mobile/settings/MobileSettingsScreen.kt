package com.kirin.mt.ui.mobile.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import com.kirin.mt.core.network.IptvRepository
import com.kirin.mt.core.player.PlaybackBufferMax
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.YoutubeDefaultQuality
import com.kirin.mt.core.player.YoutubeDeliveryPriority
import com.kirin.mt.core.player.YoutubeStartQuality
import com.kirin.mt.core.youtube.YoutubeContentRegion
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.settings.AppAppearanceMode
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
import com.kirin.mt.core.webdav.WebDavBackupState
import com.kirin.mt.ui.settings.currentVersionText
import com.kirin.mt.ui.settings.downloadProgressFraction
import com.kirin.mt.ui.settings.isUpdateVersionActionEnabled
import com.kirin.mt.ui.settings.latestVersionText
import com.kirin.mt.ui.i18n.localizedContext
import com.kirin.mt.ui.settings.normalizeIptvUrl
import com.kirin.mt.ui.settings.updateVersionActionLabel
import com.kirin.mt.ui.settings.SettingsAboutLibraries
import com.kirin.mt.ui.settings.SettingsAboutProjectUrl
import kotlinx.coroutines.delay
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
  onOpenDownloads: () -> Unit,
  webdavConfigStore: com.kirin.mt.core.webdav.WebDavConfigStore,
  webdavBackupService: com.kirin.mt.core.webdav.WebDavBackupService,
  appCacheManager: AppCacheManager,
  iptvRepository: IptvRepository,
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

  // 最新版本 row 的动作分派:Available → 下载;Downloaded → 安装;Idle/UpToDate/Failed → 检查更新;
  // Checking/Downloading → 不可点。检查更新仅手动点击触发,发现更新自然进「下载更新」态(不保存状态、不两击)。
  // 动作必须先定义为显式 () -> Unit 变量:scope.launch 返回 Job,直接写 { scope.launch { ... } }
  // 会让 lambda 推断成 () -> Job,放入 when/if 分支期期望类型传不进、when 被推断成 Any? 编译失败。
  // 末尾补 Unit 强制成 () -> Unit;且不可用双层 lambda({ { ... } })——外层 body 是内层 lambda 字面量,
  // 被求值后丢弃,内层永不执行。
  val checkAction: () -> Unit = { scope.launch { updateManager.refresh() }; Unit }
  val downloadAction: () -> Unit = { scope.launch { updateManager.download() }; Unit }
  val installAction: () -> Unit = { installDownloadedApk() }
  val updateVersionOnClick: (() -> Unit)? = when (val s = updateState.status) {
    is UpdateUiState.Status.Available -> downloadAction
    is UpdateUiState.Status.Downloaded -> installAction
    is UpdateUiState.Status.Checking, is UpdateUiState.Status.Downloading -> null
    else -> checkAction
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
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_youtube_default_quality_title),
      description = stringResource(R.string.settings_youtube_default_quality_description),
      selected = settings.youtubeDefaultQuality,
      selectedLabel = settings.youtubeDefaultQuality.label,
      options = enumOptions(YoutubeDefaultQuality.entries) { it.label },
      onSelected = { scope.launch { appSettingsStore.setYoutubeDefaultQuality(it) } },
    )
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_youtube_start_quality_title),
      description = stringResource(R.string.settings_youtube_start_quality_description),
      selected = settings.youtubeStartQuality,
      selectedLabel = settings.youtubeStartQuality.label,
      options = enumOptions(YoutubeStartQuality.entries) { it.label },
      onSelected = { scope.launch { appSettingsStore.setYoutubeStartQuality(it) } },
    )
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_playback_buffer_title),
      description = stringResource(R.string.settings_playback_buffer_description),
      selected = settings.bufferMax,
      selectedLabel = settings.bufferMax.label,
      options = enumOptions(PlaybackBufferMax.entries) { it.label },
      onSelected = { scope.launch { appSettingsStore.setPlaybackBufferMax(it) } },
    )
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_youtube_content_region_title),
      description = stringResource(R.string.settings_youtube_content_region_description),
      selected = settings.youtubeContentRegion,
      selectedLabel = settings.youtubeContentRegion.label,
      options = enumOptions(YoutubeContentRegion.entries) { it.label },
      onSelected = { scope.launch { appSettingsStore.setYoutubeContentRegion(it) } },
    )
    // NOTE: seekPreviewSpritesEnabled 为 TV 播放器专属(TV 才有 seek preview 缩略图),移动端不生效,已隐藏。
    MobileSwitchRow(
      title = stringResource(R.string.settings_air_jump_assistant_title),
      description = stringResource(R.string.settings_air_jump_assistant_description),
      checked = settings.airJumpAssistantEnabled,
      onCheckedChange = { scope.launch { appSettingsStore.setAirJumpAssistantEnabled(it) } },
    )
    // NOTE: 以下 4 项为 TV 播放器专属(移动端不消费),已隐藏移动端设置页。字段/逻辑保留。
    //   confirmPlaybackExit / autoPlayNextEpisode / autoPlayRelatedVideo / autoReturnHomeOnCompletion
    MobileSwitchRow(
      title = stringResource(R.string.settings_auto_delete_watched_title),
      description = stringResource(R.string.settings_auto_delete_watched_description),
      checked = settings.autoDeleteWatchedCache,
      onCheckedChange = { scope.launch { appSettingsStore.setAutoDeleteWatchedCache(it) } },
    )
    // NOTE: showClock / showMiniProgressBar 为 TV 播放器专属(移动端不消费),已隐藏移动端设置页。字段/逻辑保留。

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
      title = stringResource(R.string.settings_appearance_title),
      description = stringResource(R.string.settings_appearance_description),
      selected = settings.appearanceMode,
      selectedLabel = appearanceLabel(settings.appearanceMode),
      options = enumOptions(AppAppearanceMode.entries) { appearanceLabel(it) },
      onSelected = { scope.launch { appSettingsStore.setAppearanceMode(it) } },
    )
    MobileEnumPickerRow(
      title = stringResource(R.string.settings_language_title),
      description = stringResource(R.string.settings_language_description),
      selected = settings.chineseTextVariant,
      selectedLabel = languageLabel(settings.chineseTextVariant),
      options = enumOptions(ChineseTextVariant.entries) { languageLabel(it) },
      onSelected = { variant ->
        scope.launch { appSettingsStore.setChineseTextVariant(variant) }
        // 用新语言弹 toast 确认切换(状态异步落盘,这里按新 variant 构造 locale 上下文取值)。
        val switchedCtx = context.localizedContext(variant)
        Toast.makeText(
          switchedCtx,
          switchedCtx.getString(R.string.settings_language_switched),
          Toast.LENGTH_SHORT,
        ).show()
      },
    )

    // ===== 首页分区(与 TV 同一份配置,排序+显示隐藏;默认折叠,点标题展开) =====
    MobileHomeSectionsPanel(
      settings = settings,
      appSettingsStore = appSettingsStore,
      scope = scope,
    )

    // ===== 系统设置 =====
    MobileSettingsSectionHeader(stringResource(R.string.settings_performance_section))
    MobileSettingsRow(
      title = stringResource(R.string.settings_logs_entry_title),
      description = stringResource(R.string.settings_logs_entry_description),
      onClick = onOpenLogs,
    )
    MobileSwitchRow(
      title = stringResource(R.string.settings_crash_auto_report_title),
      description = stringResource(R.string.settings_crash_auto_report_description),
      checked = settings.crashLogAutoReportEnabled,
      onCheckedChange = { scope.launch { appSettingsStore.setCrashLogAutoReportEnabled(it) } },
    )
    MobileSettingsRow(
      title = stringResource(R.string.downloads_settings_title),
      description = stringResource(R.string.downloads_settings_description),
      onClick = onOpenDownloads,
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
      onConfigChange = { cfg ->
        com.kirin.mt.core.webdav.validateAndSaveWebDavConfig(
          store = webdavConfigStore,
          ping = { url -> webdavBackupService.ping(url, cfg.username, cfg.password) },
          config = cfg,
        )
      },
      onBackup = { cfg, items -> webdavBackupService.backup(cfg, items) },
      onRestore = { cfg, items -> webdavBackupService.restore(cfg, items) },
    )

    // ===== IPTV 源 =====
    MobileIptvSection(
      settings = settings,
      appSettingsStore = appSettingsStore,
      iptvRepository = iptvRepository,
    )

    // ===== YouTube SABR 实验:Piped 后端 + itag 诊断(alpha.84,对齐 TV SettingsScreen) =====
    MobileYoutubeSabrSection(
      settings = settings,
      appSettingsStore = appSettingsStore,
    )

    // ===== 程序更新(2026-08-30 调整:与 TV 端对齐,移到设置列表最末尾) =====
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

    // ===== 关于(折叠面板,与 TV 关于面板同源信息;2026-08-30 补齐) =====
    MobileAboutSection()
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
private fun appearanceLabel(m: AppAppearanceMode): String = stringResource(
  when (m) {
    AppAppearanceMode.Dark -> R.string.settings_appearance_dark
    AppAppearanceMode.Light -> R.string.settings_appearance_light
    AppAppearanceMode.Auto -> R.string.settings_appearance_auto
  }
)

@Composable
private fun languageLabel(v: ChineseTextVariant): String = stringResource(
  when (v) {
    ChineseTextVariant.Simplified -> R.string.settings_language_simplified
    ChineseTextVariant.HongKong -> R.string.settings_language_hong_kong
    ChineseTextVariant.Taiwan -> R.string.settings_language_taiwan
    ChineseTextVariant.English -> R.string.settings_language_english
    ChineseTextVariant.Spanish -> R.string.settings_language_spanish
    ChineseTextVariant.Portuguese -> R.string.settings_language_portuguese
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
  onConfigChange: suspend (com.kirin.mt.core.webdav.WebDavConfig) -> Result<com.kirin.mt.core.webdav.WebDavConfig>,
  onBackup: suspend (com.kirin.mt.core.webdav.WebDavConfig, Set<com.kirin.mt.core.webdav.WebDavBackupItem>) -> Result<Unit>,
  onRestore: suspend (com.kirin.mt.core.webdav.WebDavConfig, Set<com.kirin.mt.core.webdav.WebDavBackupItem>) -> Result<Int>,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var showEditDialog by remember { mutableStateOf(false) }
  var showBackupDialog by remember { mutableStateOf(false) }
  var showRestoreDialog by remember { mutableStateOf(false) }
  var webDavState by remember { mutableStateOf<WebDavBackupState>(WebDavBackupState.Idle) }
  var expanded by remember { mutableStateOf(false) }
  // 展开后自动滚动,让备份/还原按钮滚进可视区(区块在设置列表底部,默认在折叠线以下)。
  val bringIntoViewRequester = remember { BringIntoViewRequester() }
  LaunchedEffect(expanded) {
    if (expanded) {
      delay(300) // 等展开动画(默认 300ms)把内容撑到完整高度再滚动
      bringIntoViewRequester.bringIntoView()
    }
  }

  val busy = webDavState is WebDavBackupState.Running

  fun runBackup(items: Set<com.kirin.mt.core.webdav.WebDavBackupItem>) {
    if (busy) return
    scope.launch {
      webDavState = WebDavBackupState.Running(isRestore = false)
      val result = onBackup(config, items)
      webDavState = WebDavBackupState.Idle
      val msg = result.fold(
        onSuccess = { context.getString(R.string.settings_webdav_backup_success) },
        onFailure = { context.getString(R.string.settings_webdav_failed, it.message ?: "") },
      )
      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
  }

  fun runRestore(items: Set<com.kirin.mt.core.webdav.WebDavBackupItem>) {
    if (busy) return
    scope.launch {
      webDavState = WebDavBackupState.Running(isRestore = true)
      val result = onRestore(config, items)
      webDavState = WebDavBackupState.Idle
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
    Column(
      modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester),
    ) {
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
        WebDavActionButton(
          isRestore = false,
          state = webDavState,
          onClick = { if (!busy) showBackupDialog = true },
          modifier = Modifier.weight(1f),
        )
        WebDavActionButton(
          isRestore = true,
          state = webDavState,
          onClick = { if (!busy) showRestoreDialog = true },
          modifier = Modifier.weight(1f),
        )
      }
    }
  }

  if (showEditDialog) {
    MobileWebDavEditDialog(
      config = config,
      onSave = onConfigChange,
      onDismiss = { showEditDialog = false },
    )
  }
  if (showBackupDialog) {
    MobileWebDavSelectionDialog(
      isRestore = false,
      onConfirm = { items ->
        showBackupDialog = false
        runBackup(items)
      },
      onDismiss = { showBackupDialog = false },
    )
  }
  if (showRestoreDialog) {
    MobileWebDavSelectionDialog(
      isRestore = true,
      onConfirm = { items ->
        showRestoreDialog = false
        runRestore(items)
      },
      onDismiss = { showRestoreDialog = false },
    )
  }
}

/**
 * 备份/还原按钮:空闲显示「备份/还原」文案;本按钮在运行中显示「旋转 spinner + 备份中…/还原中…」
 * (用 [AnimatedContent] 淡入淡出);任一操作运行时两按钮都禁用,避免重复点击。
 * 服务是单次 suspend 调用、无字节级进度,故用 indeterminate spinner。
 */
@Composable
private fun WebDavActionButton(
  isRestore: Boolean,
  state: WebDavBackupState,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val running = state is WebDavBackupState.Running
  val thisRunning = running && (state as WebDavBackupState.Running).isRestore == isRestore
  Button(onClick = onClick, enabled = !running, modifier = modifier) {
    AnimatedContent(
      targetState = thisRunning,
      label = "webdav-button",
    ) { showRunning ->
      if (showRunning) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
          )
          Text(
            stringResource(
              if (isRestore) R.string.settings_webdav_restore_running
              else R.string.settings_webdav_backup_running,
            ),
          )
        }
      } else {
        Text(stringResource(if (isRestore) R.string.settings_webdav_restore else R.string.settings_webdav_backup))
      }
    }
  }
}

/** WebDAV 备份/还原选择弹窗:「全选」复选框 + 各项复选框(isRestore 时只列频道+Piped,日志只备份不还原)。 */
@Composable
private fun MobileWebDavSelectionDialog(
  isRestore: Boolean,
  onConfirm: (Set<com.kirin.mt.core.webdav.WebDavBackupItem>) -> Unit,
  onDismiss: () -> Unit,
) {
  val items = if (isRestore) {
    listOf(
      com.kirin.mt.core.webdav.WebDavBackupItem.Channels,
      com.kirin.mt.core.webdav.WebDavBackupItem.Piped,
      com.kirin.mt.core.webdav.WebDavBackupItem.Watched,
      com.kirin.mt.core.webdav.WebDavBackupItem.BiliAccount,
      com.kirin.mt.core.webdav.WebDavBackupItem.Iptv,
    )
  } else {
    com.kirin.mt.core.webdav.WebDavBackupItem.entries
  }
  // 备份默认只勾选日志;B站账号登录态默认不选。
  // 还原默认勾选除 B站账号外的各项(登录态还原较敏感,需显式勾选)。
  val defaultSelected = if (isRestore) {
    items.filter { it != com.kirin.mt.core.webdav.WebDavBackupItem.BiliAccount }
  } else {
    items.filter { it == com.kirin.mt.core.webdav.WebDavBackupItem.Logs }
  }
  var selected by remember { mutableStateOf(defaultSelected.toSet()) }

  @Composable
  fun itemLabel(item: com.kirin.mt.core.webdav.WebDavBackupItem): String = when (item) {
    com.kirin.mt.core.webdav.WebDavBackupItem.Channels -> stringResource(R.string.settings_webdav_item_channels)
    com.kirin.mt.core.webdav.WebDavBackupItem.Piped -> stringResource(R.string.settings_webdav_item_piped)
    com.kirin.mt.core.webdav.WebDavBackupItem.Watched -> stringResource(R.string.settings_webdav_item_watched)
    com.kirin.mt.core.webdav.WebDavBackupItem.BiliAccount -> stringResource(R.string.settings_webdav_item_biliaccount)
    com.kirin.mt.core.webdav.WebDavBackupItem.Iptv -> stringResource(R.string.settings_webdav_item_iptv)
    com.kirin.mt.core.webdav.WebDavBackupItem.Logs -> stringResource(R.string.settings_webdav_item_logs)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(
      if (isRestore) R.string.settings_webdav_restore_select_title else R.string.settings_webdav_select_title,
    )) },
    text = {
      Column {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { selected = if (selected.size == items.size) emptySet() else items.toSet() }
            .padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(
            checked = selected.size == items.size,
            onCheckedChange = { selected = if (it) items.toSet() else emptySet() },
          )
          Text(stringResource(R.string.settings_webdav_select_all))
        }
        items.forEach { item ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .clickable { selected = if (item in selected) selected - item else selected + item }
              .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(
              checked = item in selected,
              onCheckedChange = { selected = if (it) selected + item else selected - item },
            )
            Text(itemLabel(item))
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = {
        if (selected.isNotEmpty()) onConfirm(selected)
      }) {
        Text(stringResource(
          if (isRestore) R.string.settings_webdav_start_restore else R.string.settings_webdav_start_backup,
        ))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.mobile_dialog_cancel))
      }
    },
  )
}

/** WebDAV 编辑弹窗:URL/账号/密码三个输入框 + 保存/取消。保存前校验连通,成功才关闭。 */
@Composable
private fun MobileWebDavEditDialog(
  config: com.kirin.mt.core.webdav.WebDavConfig,
  onSave: suspend (com.kirin.mt.core.webdav.WebDavConfig) -> Result<com.kirin.mt.core.webdav.WebDavConfig>,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var url by remember { mutableStateOf(config.url) }
  var username by remember { mutableStateOf(config.username) }
  var password by remember { mutableStateOf(config.password) }
  var saving by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  fun save() {
    if (saving) return
    saving = true
    error = null
    scope.launch {
      val result = onSave(com.kirin.mt.core.webdav.WebDavConfig(url, username, password))
      saving = false
      result.fold(
        onSuccess = {
          Toast.makeText(context, R.string.settings_webdav_connect_success, Toast.LENGTH_SHORT).show()
          onDismiss()
        },
        onFailure = { error = it.message },
      )
    }
  }

  AlertDialog(
    onDismissRequest = { if (!saving) onDismiss() },
    title = { Text(stringResource(R.string.settings_webdav_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text(stringResource(R.string.settings_webdav_url_label)) },
          singleLine = true,
          enabled = !saving,
        )
        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text(stringResource(R.string.settings_webdav_username_label)) },
          singleLine = true,
          enabled = !saving,
        )
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text(stringResource(R.string.settings_webdav_password_label)) },
          singleLine = true,
          enabled = !saving,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        error?.let { msg ->
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = ::save, enabled = !saving) {
        Text(
          stringResource(
            if (saving) R.string.settings_webdav_validating else R.string.settings_webdav_save,
          ),
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !saving) {
        Text(stringResource(R.string.mobile_dialog_cancel))
      }
    },
  )
}

/** IPTV 源配置区:地址行只显示 URL,点按/长按弹窗编辑网址/账号/密码,保存后校验连通性(成功/失败 Toast)。 */
@Composable
private fun MobileIptvSection(
  settings: AppSettings,
  appSettingsStore: AppSettingsStore,
  iptvRepository: IptvRepository,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var showEditDialog by remember { mutableStateOf(false) }
  var expanded by remember { mutableStateOf(false) }

  MobileSettingsSectionHeader(
    text = stringResource(R.string.settings_iptv_title),
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
        title = stringResource(R.string.settings_iptv_url_label),
        description = settings.iptvSourceUrl.ifBlank { stringResource(R.string.settings_iptv_configure_hint) },
        onClick = { showEditDialog = true },
        onLongClick = { showEditDialog = true },
      )
    }
  }

  if (showEditDialog) {
    MobileIptvEditDialog(
      url = settings.iptvSourceUrl,
      username = settings.iptvSourceUsername,
      password = settings.iptvSourcePassword,
      onSave = { url, username, password ->
        showEditDialog = false
        scope.launch {
          appSettingsStore.setIptvSourceUrl(url)
          appSettingsStore.setIptvSourceUsername(username)
          appSettingsStore.setIptvSourcePassword(password)
          // 保存后校验连通性,成功/失败都提示(镜像 TV AppShell onIptvSourceConfigChange)。
          val reachable = iptvRepository.checkSourceReachable(url, username, password)
          Toast.makeText(
            context,
            if (reachable) R.string.settings_iptv_connect_success else R.string.settings_iptv_connect_failed,
            Toast.LENGTH_SHORT,
          ).show()
        }
      },
      onDismiss = { showEditDialog = false },
    )
  }
}

/** IPTV 源编辑弹窗:URL/账号/密码三个输入框 + 保存/取消。保存时补全 URL 协议(镜像 TV SettingsIptvDialog)。 */
@Composable
private fun MobileIptvEditDialog(
  url: String,
  username: String,
  password: String,
  onSave: (url: String, username: String, password: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var urlValue by remember { mutableStateOf(url) }
  var usernameValue by remember { mutableStateOf(username) }
  var passwordValue by remember { mutableStateOf(password) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.settings_iptv_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = urlValue,
          onValueChange = { urlValue = it },
          label = { Text(stringResource(R.string.settings_iptv_url_label)) },
          singleLine = true,
        )
        OutlinedTextField(
          value = usernameValue,
          onValueChange = { usernameValue = it },
          label = { Text(stringResource(R.string.settings_iptv_username_label)) },
          singleLine = true,
        )
        OutlinedTextField(
          value = passwordValue,
          onValueChange = { passwordValue = it },
          label = { Text(stringResource(R.string.settings_iptv_password_label)) },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(normalizeIptvUrl(urlValue), usernameValue.trim(), passwordValue) }) {
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

/**
 * YouTube SABR 实验区(可折叠):启用 Piped 后端开关 + Piped 实例 URL 行(点按弹窗编辑)+
 * 锁定会话视频轨诊断开关。镜像 [MobileIptvSection] 的折叠段结构,对齐 TV SettingsScreen
 * 的三行 YouTube SABR 实验(alpha.84)。resolve() 读这三项:开 Piped 走 Piped 后端修
 * RELOAD_PLAYER_RESPONSE 死循环,失败回退 NewPipe;空串实例用默认 [DEFAULT_PIPED_INSTANCE]。
 */
@Composable
private fun MobileYoutubeSabrSection(
  settings: AppSettings,
  appSettingsStore: AppSettingsStore,
) {
  val scope = rememberCoroutineScope()
  var showEditDialog by remember { mutableStateOf(false) }
  var expanded by remember { mutableStateOf(false) }

  MobileSettingsSectionHeader(
    text = stringResource(R.string.settings_youtube_sabr_section),
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
      MobileSwitchRow(
        title = stringResource(R.string.settings_youtube_use_piped_title),
        description = stringResource(R.string.settings_youtube_use_piped_description),
        checked = settings.youtubeUsePiped,
        onCheckedChange = { scope.launch { appSettingsStore.setYoutubeUsePiped(it) } },
      )
      MobileSettingsRow(
        title = stringResource(R.string.settings_piped_title),
        description = settings.pipedInstanceUrl.ifBlank {
          stringResource(R.string.settings_piped_default_hint)
        },
        onClick = { showEditDialog = true },
        onLongClick = { showEditDialog = true },
      )
      // NOTE: sabrForceSessionVideoItag("锁定会话视频轨")诊断开关已隐藏(alpha.83 使命完成,
      // 证伪 itag 是 RELOAD 根因)。字段/逻辑保留,如需再作诊断可恢复此 MobileSwitchRow。
      MobileEnumPickerRow(
        title = stringResource(R.string.settings_youtube_delivery_priority_title),
        description = stringResource(R.string.settings_youtube_delivery_priority_description),
        selected = settings.youtubeDeliveryPriority,
        selectedLabel = settings.youtubeDeliveryPriority.label,
        options = enumOptions(YoutubeDeliveryPriority.entries) { it.label },
        onSelected = { scope.launch { appSettingsStore.setYoutubeDeliveryPriority(it) } },
      )
    }
  }

  if (showEditDialog) {
    MobilePipedEditDialog(
      url = settings.pipedInstanceUrl,
      onSave = { url ->
        showEditDialog = false
        scope.launch { appSettingsStore.setPipedInstanceUrl(url) }
      },
      onDismiss = { showEditDialog = false },
    )
  }
}

/**
 * 关于区(折叠面板,镜像 [MobileIptvSection]):项目名称/简介 + 项目地址(点击跳浏览器)+
 * 开源协议 + 依赖库清单(点击打开项目主页)。信息与 TV SettingsAboutColumn 同源
 * ([SettingsAboutProjectUrl]/[SettingsAboutLibraries]);TV 端渲染二维码,移动端在设备上
 * 直接可点,不放二维码。
 */
@Composable
private fun MobileAboutSection() {
  val context = LocalContext.current
  var expanded by remember { mutableStateOf(false) }

  MobileSettingsSectionHeader(
    text = stringResource(R.string.settings_about_title),
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
        title = stringResource(R.string.settings_about_project_name),
        description = stringResource(R.string.settings_about_project_intro),
      )
      MobileSettingsRow(
        title = stringResource(R.string.settings_about_project_url_title),
        description = SettingsAboutProjectUrl,
        onClick = { openInBrowser(context, SettingsAboutProjectUrl) },
      )
      MobileSettingsRow(
        title = stringResource(R.string.settings_about_license_title),
        description = stringResource(R.string.settings_about_license_value),
      )
      SettingsAboutLibraries.forEach { library ->
        MobileSettingsRow(
          title = library.name,
          description = stringResource(library.descriptionRes),
          onClick = { openInBrowser(context, library.url) },
        )
      }
    }
  }
}

/** 用系统浏览器打开链接;无浏览器(极少数精简包)时静默吞掉 ActivityNotFound。 */
private fun openInBrowser(context: Context, url: String) {
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
  } catch (_: ActivityNotFoundException) {
  }
}

/** Piped 实例编辑弹窗:单个 URL 字段 + 保存/取消。保存时补全协议(镜像 TV SettingsPipedDialog)。空串=用默认实例。 */
@Composable
private fun MobilePipedEditDialog(
  url: String,
  onSave: (url: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var urlValue by remember { mutableStateOf(url) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.settings_piped_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = urlValue,
          onValueChange = { urlValue = it },
          label = { Text(stringResource(R.string.settings_piped_instance_label)) },
          singleLine = true,
        )
        Text(
          text = stringResource(R.string.settings_piped_description),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(normalizeIptvUrl(urlValue)) }) {
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