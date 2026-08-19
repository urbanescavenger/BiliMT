package com.kirin.mt.ui.mobile.player

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
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
      // DefaultDataSource 同时支持本地文件与网络;本地文件内部走 FileDataSource。
      val dataSourceFactory = DefaultDataSource.Factory(context)
      val videoUri = Uri.fromFile(videoFile)
      val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(videoUri))
      val source = if (audioFile != null) {
        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
          .createMediaSource(MediaItem.fromUri(Uri.fromFile(audioFile)))
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
  var audioOnly by remember { mutableStateOf(false) }
  var seekPreviewMs by remember { mutableLongStateOf(-1L) }

  // 听视频模式(音频-only):禁用视频轨,只留音频。与在线播放器 MobilePlayerScreen.toggleAudioOnly 一致。
  val toggleAudioOnly: () -> Unit = {
    audioOnly = !audioOnly
    player.setTrackSelectionParameters(
      player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
        .build(),
    )
  }

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

    // 听视频模式:叠一层黑底 + 音频指示遮住画面(不销毁 PlayerView,避免 surface 重建黑闪)。与在线播放器一致。
    if (audioOnly) {
      Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            painter = painterResource(R.drawable.ic_player_audio),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp),
          )
          Spacer(Modifier.height(8.dp))
          Text("听视频模式", color = Color.White)
        }
      }
    }

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
      // 听视频模式(音频-only)切换:顶栏右侧耳机按钮,激活态粉色高亮。与在线播放器一致。
      IconButton(onClick = toggleAudioOnly) {
        Icon(
          painter = painterResource(R.drawable.ic_player_audio),
          contentDescription = if (audioOnly) "退出听视频" else "听视频",
          tint = if (audioOnly) BiliColors.BiliPink else Color.White,
        )
      }
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
      // 可拖拽 seek 滑块:拖动实时预览,松手 seekTo。与在线播放器 SlimSeekSlider 交互一致。
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(formatMs(if (seekPreviewMs >= 0L) seekPreviewMs else positionMs), color = Color.White)
        SlimSeekSlider(
          value = (if (seekPreviewMs >= 0L) seekPreviewMs else positionMs).toFloat()
            .coerceIn(0f, durationMs.toFloat()),
          valueRange = 0f..durationMs.toFloat(),
          onValueChange = { seekPreviewMs = it.toLong() },
          onValueChangeFinished = {
            seekPreviewMs.takeIf { it >= 0L }?.let { target ->
              player.seekTo(target.coerceIn(0L, durationMs))
            }
            seekPreviewMs = -1L
          },
          modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(formatMs(durationMs), color = Color.White)
      }
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
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

/**
 * 瘦身 seek 滑块:Canvas 自绘细轨道(3dp)+ 小拇指(5dp 半径),总高约 20dp,
 * 替代 Material3 Slider(~48dp)以解决"进度条上下太厚"。seek 逻辑由调用方经
 * onValueChange/onValueChangeFinished 复用。与在线播放器 MobilePlayerScreen.SlimSeekSlider 一致。
 */
@Composable
private fun SlimSeekSlider(
  value: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0f)
  val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
  var widthPx by remember { mutableStateOf(1f) }
  var dragFraction by remember { mutableStateOf<Float?>(null) }
  val current = dragFraction ?: fraction
  Box(
    modifier
      .height(20.dp)
      .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
      .pointerInput(valueRange) {
        detectHorizontalDragGestures(
          onDragStart = { offset ->
            val f = (offset.x / widthPx).coerceIn(0f, 1f)
            dragFraction = f
            onValueChange(f * span + valueRange.start)
          },
          onHorizontalDrag = { change, _ ->
            val f = (change.position.x / widthPx).coerceIn(0f, 1f)
            dragFraction = f
            onValueChange(f * span + valueRange.start)
          },
          onDragEnd = {
            onValueChangeFinished()
            dragFraction = null
          },
          onDragCancel = { dragFraction = null },
        )
      },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val trackPx = 3.dp.toPx()
      val thumbPx = 5.dp.toPx()
      val cy = size.height / 2f
      val corner = CornerRadius(trackPx / 2f, trackPx / 2f)
      drawRoundRect(
        color = Color(0x66FFFFFF),
        topLeft = Offset(0f, cy - trackPx / 2f),
        size = Size(size.width, trackPx),
        cornerRadius = corner,
      )
      drawRoundRect(
        color = BiliColors.BiliPink,
        topLeft = Offset(0f, cy - trackPx / 2f),
        size = Size(size.width * current, trackPx),
        cornerRadius = corner,
      )
      drawCircle(
        color = Color.White,
        radius = thumbPx,
        center = Offset(size.width * current, cy),
      )
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
