package com.kirin.mt.ui.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kirin.mt.BiliTvApplication
import com.kirin.mt.R
import com.kirin.mt.core.storage.UserSession
import com.kirin.mt.ui.mobile.downloads.MobileDownloadsScreen
import com.kirin.mt.ui.mobile.settings.FollowManageKind
import com.kirin.mt.ui.mobile.settings.MobileFollowManageScreen
import com.kirin.mt.ui.mobile.settings.MobileLogsScreen
import com.kirin.mt.ui.mobile.settings.MobileSettingsScreen
import com.kirin.mt.ui.theme.BiliTvTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    val appContainer = (application as BiliTvApplication).appContainer
    setContent {
      BiliTvTheme {
        Surface(modifier = Modifier.fillMaxSize().statusBarsPadding(), color = MaterialTheme.colorScheme.background) {
          val session by appContainer.sessionStore.session.collectAsState(initial = UserSession())
          var followScreen by remember { mutableStateOf<FollowManageKind?>(null) }
          var showLogs by remember { mutableStateOf(false) }
          var showDownloads by remember { mutableStateOf(false) }
          var playingDownloadId by remember { mutableStateOf<Long?>(null) }
          val scope = rememberCoroutineScope()
          // 下载批量删除:管理模式开关 + 已勾选任务 id。
          var downloadsBatchMode by remember { mutableStateOf(false) }
          var selectedDownloadIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
          Column(modifier = Modifier.fillMaxSize()) {
            val kind = followScreen
            if (showDownloads) {
              SettingsTopBar(
                title = stringResource(R.string.downloads_screen_title),
                onBack = {
                  if (downloadsBatchMode) {
                    downloadsBatchMode = false
                    selectedDownloadIds = emptySet()
                  } else {
                    showDownloads = false
                  }
                },
                trailing = {
                  if (downloadsBatchMode) {
                    TextButton(onClick = {
                      downloadsBatchMode = false
                      selectedDownloadIds = emptySet()
                    }) { Text(stringResource(R.string.downloads_batch_done)) }
                  } else {
                    IconButton(onClick = {
                      downloadsBatchMode = true
                      selectedDownloadIds = emptySet()
                    }) {
                      Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                      )
                    }
                  }
                },
              )
              MobileDownloadsScreen(
                downloadManager = appContainer.downloadManager,
                youtubePlaylistStore = appContainer.youtubePlaylistStore,
                onPlayDownload = { playingDownloadId = it },
                batchMode = downloadsBatchMode,
                selectedIds = selectedDownloadIds,
                onToggleSelection = { id ->
                  selectedDownloadIds = if (id in selectedDownloadIds) selectedDownloadIds - id else selectedDownloadIds + id
                },
                onSetSelection = { ids -> selectedDownloadIds = ids },
                onDeleteSelected = {
                  scope.launch {
                    selectedDownloadIds.forEach { appContainer.downloadManager.delete(it) }
                    selectedDownloadIds = emptySet()
                    downloadsBatchMode = false
                  }
                },
                modifier = Modifier.fillMaxWidth(),
              )
            } else if (showLogs) {
              SettingsTopBar(
                title = stringResource(R.string.settings_logs_entry_title),
                onBack = { showLogs = false },
              )
              MobileLogsScreen(
                onBack = { showLogs = false },
                modifier = Modifier.fillMaxWidth(),
              )
            } else if (kind == null) {
              SettingsTopBar(
                title = stringResource(R.string.mobile_settings_title),
                onBack = { finish() },
              )
              MobileSettingsScreen(
                appSettingsStore = appContainer.appSettingsStore,
                updateManager = appContainer.updateManager,
                apkInstaller = appContainer.apkInstaller,
                sessionStore = appContainer.sessionStore,
                authRepository = appContainer.authRepository,
                onOpenFollows = { followScreen = it },
                onLogin = { startActivity(android.content.Intent(this@SettingsActivity, LoginActivity::class.java)) },
                onOpenLogs = { showLogs = true },
                onOpenDownloads = { showDownloads = true },
                webdavConfigStore = appContainer.webdavConfigStore,
                webdavBackupService = appContainer.webdavBackupService,
                appCacheManager = appContainer.appCacheManager,
                iptvRepository = appContainer.iptvRepository,
                modifier = Modifier.fillMaxWidth(),
              )
            } else {
              SettingsTopBar(
                title = when (kind) {
                  FollowManageKind.BiliFollows -> stringResource(R.string.mobile_follows_bili)
                  FollowManageKind.YoutubeFollows -> stringResource(R.string.mobile_follows_youtube)
                },
                onBack = { followScreen = null },
              )
              MobileFollowManageScreen(
                kind = kind,
                mid = session.mid ?: 0L,
                videoRepository = appContainer.videoRepository,
                youtubeChannelStore = appContainer.youtubeChannelStore,
                youtubeRepository = appContainer.youtubeRepository,
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }
          // 离线播放器覆盖层:从下载库点「播放」时全屏盖在设置页之上。
          val playingId = playingDownloadId
          if (playingId != null) {
            com.kirin.mt.ui.mobile.player.MobileOfflinePlayerScreen(
              downloadId = playingId,
              downloadManager = appContainer.downloadManager,
              playbackRepository = appContainer.playbackRepository,
              onBack = { playingDownloadId = null },
              modifier = Modifier.fillMaxSize(),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SettingsTopBar(
  title: String,
  onBack: () -> Unit,
  trailing: (@Composable () -> Unit)? = null,
) {
  Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
    TextButton(onClick = onBack, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart)) {
      Text(stringResource(R.string.mobile_back))
    }
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
    )
    Box(modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd)) {
      trailing?.invoke()
    }
  }
}
