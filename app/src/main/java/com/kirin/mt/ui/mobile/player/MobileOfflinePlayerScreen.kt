package com.kirin.mt.ui.mobile.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.file.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.kirin.mt.R
import com.kirin.mt.core.download.DownloadManager
import com.kirin.mt.ui.theme.BiliColors
import kotlinx.coroutines.flow.first

/**
 * 离线播放器:播放已下载到应用私有目录的视频文件。
 * 单文件(muxed)→ [ProgressiveMediaSource];视频+音频两文件 → [MergingMediaSource] 现场 mux。
 * 完全本地,飞行模式可播。简单 play/pause/seek/back,不做远端播放器的弹幕/画质等。
 */
@Composable
fun MobileOfflinePlayerScreen(
  downloadId: Long,
  downloadManager: DownloadManager,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val (videoFile, audioFile) = remember(downloadId) { downloadManager.playbackFiles(downloadId) }

  // 文件缺失(被删除/未完成)时兜底,避免 Uri.fromFile(null) 崩溃。
  if (videoFile == null) {
    Box(
      modifier = modifier.fillMaxSize().background(Color.Black).statusBarsPadding(),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = stringResource(R.string.downloads_offline_player_title),
        color = Color.White,
      )
    }
    return
  }

  val player = remember {
    ExoPlayer.Builder(context).build().apply {
      setAudioAttributes(
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
        /* handleAudioFocus = */ true,
      )
      val dataSourceFactory = FileDataSource.Factory()
      val videoUri = Uri.fromFile(videoFile)
      val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(videoUri)
      val source = if (audioFile != null) {
        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
          .createMediaSource(Uri.fromFile(audioFile))
        MergingMediaSource(videoSource, audioSource)
      } else {
        videoSource
      }
      setMediaSource(source)
      prepare()
      playWhenReady = true
    }
  }

  var isPlaying by remember { mutableStateOf(true) }
  var positionMs by remember { mutableLongStateOf(0L) }
  var durationMs by remember { mutableLongStateOf(0L) }
  var title by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(player) {
    while (true) {
      isPlaying = player.isPlaying
      positionMs = player.currentPosition
      durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
      kotlinx.coroutines.delay(500)
    }
  }

  LaunchedEffect(downloadId) {
    title = downloadManager.downloads.first()
      .firstOrNull { it.download.id == downloadId }?.download?.title
  }

  DisposableEffect(player) {
    onDispose {
      player.release()
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black)
      .statusBarsPadding(),
  ) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        PlayerView(ctx).apply {
          useController = false
          this.player = player
        }
      },
    )

    // 顶栏:返回 + 标题(取文件父行标题)。
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.TopCenter)
        .background(Color(0x66000000))
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) {
        Icon(
          painter = painterResource(R.drawable.ic_player_chevron_left),
          contentDescription = stringResource(R.string.mobile_back),
          tint = Color.White,
        )
      }
      Text(
        text = title ?: "",
        color = Color.White,
        maxLines = 1,
        modifier = Modifier.weight(1f),
      )
    }

    // 底部:进度条 + 播放/暂停。
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .background(Color(0x66000000))
        .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      LinearProgressIndicator(
        progress = { if (durationMs > 0L) positionMs.toFloat() / durationMs else 0f },
        modifier = Modifier.fillMaxWidth(),
        color = BiliColors.BiliPink,
        trackColor = Color(0x33FFFFFF),
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
          color = Color.White,
          style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        Box(
          modifier = Modifier
            .size(48.dp)
            .clickable {
              if (player.isPlaying) player.pause() else player.play()
            },
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            painter = painterResource(if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play),
            contentDescription = if (isPlaying) "暂停" else "播放",
            tint = Color.White,
            modifier = Modifier.size(32.dp),
          )
        }
      }
    }
  }
}

private fun formatMs(ms: Long): String {
  val totalSec = ms / 1000
  val h = totalSec / 3600
  val m = (totalSec % 3600) / 60
  val s = totalSec % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
