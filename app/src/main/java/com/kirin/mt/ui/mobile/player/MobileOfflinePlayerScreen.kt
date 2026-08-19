package com.kirin.mt.ui.mobile.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.kirin.mt.R
import com.kirin.mt.core.download.DownloadManager
import com.kirin.mt.core.download.DownloadSource
import com.kirin.mt.core.download.DownloadStatus
import com.kirin.mt.core.model.SourceBili
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackService
import com.kirin.mt.core.player.PlayerHolder
import com.kirin.mt.core.player.createTvPlaybackLoadControl
import com.kirin.mt.core.player.startPlaybackService
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import com.kirin.mt.ui.theme.BiliColors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 离线播放器:播放已下载到应用私有目录的视频文件。
 * 单文件(muxed)→ [ProgressiveMediaSource];视频+音频两文件 → [MergingMediaSource] 现场 mux。
 * 完全本地,飞行模式可播。功能对齐在线播放器 MobilePlayerScreen:
 * 后台播放通知(PlayerHolder+PlaybackService)、进度保存/续播(复用 Room,在线/离线互通)、
 * 倍速、画面点击暂停/拖拽 seek、全屏/非全屏两种布局、相关视频=已下载视频列表。
 * 数据源仍是本地文件(DefaultDataSource),无网络 headers/DRM/SABR(合理差异)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileOfflinePlayerScreen(
  downloadId: Long,
  downloadManager: DownloadManager,
  playbackRepository: PlaybackRepository,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // 内部当前播放的下载项 id:相关视频列表点击切换时更新,驱动 player 重建。
  var currentDownloadId by remember { mutableStateOf(downloadId) }

  // 全部下载任务(Room 驱动),取当前项元数据 + 已下载相关视频列表。
  val downloads by downloadManager.downloads.collectAsState(initial = emptyList())
  val current = downloads.firstOrNull { it.download.id == currentDownloadId }
  val videoId = current?.download?.videoId ?: ""
  val cid = current?.download?.cid ?: 0L
  val title = current?.download?.title ?: ""
  // 相关视频 = 已下载的其他视频(排除当前,仅 COMPLETED)。
  val related = downloads.filter {
    it.download.id != currentDownloadId && it.download.status == DownloadStatus.COMPLETED.key
  }

  val (videoFile, audioFile) = remember(currentDownloadId) { downloadManager.playbackFiles(currentDownloadId) }

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

  var fullscreen by remember { mutableStateOf(false) }
  var controlsVisible by remember { mutableStateOf(true) }
  var isPlaying by remember { mutableStateOf(true) }
  var positionMs by remember { mutableLongStateOf(0L) }
  var durationMs by remember { mutableLongStateOf(0L) }
  var audioOnly by remember { mutableStateOf(false) }
  var seekPreviewMs by remember { mutableLongStateOf(-1L) }
  var playbackSpeed by remember { mutableFloatStateOf(1f) }
  var showSpeedSheet by remember { mutableStateOf(false) }
  var wasPlayingBeforeSeek by remember { mutableStateOf(false) }

  // 全屏:跟随设备方向 + 隐藏系统栏(沉浸);非全屏:竖屏 + 系统栏可见。退出/关播放器恢复。
  DisposableEffect(fullscreen) {
    val activity = context.findActivity()
    if (activity != null) {
      val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
      if (fullscreen) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        controller.show(WindowInsetsCompat.Type.systemBars())
      }
      onDispose {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        controller.show(WindowInsetsCompat.Type.systemBars())
      }
    } else {
      onDispose {}
    }
  }

  val player = remember(currentDownloadId) {
    ExoPlayer.Builder(context)
      .setLoadControl(createTvPlaybackLoadControl())
      // 后台播放优化:别的应用抢音频焦点→自动暂停,焦点回来→自动续播;
      // 耳机/蓝牙音频设备断开(AUDIO_BECOMING_NOISY)→自动暂停。与在线播放器一致。
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
          .build(),
        /* handleAudioFocus = */ true,
      )
      .setHandleAudioBecomingNoisy(true)
      .build().apply {
        // DefaultDataSource 同时支持本地文件与网络;本地文件内部走 FileDataSource。
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val videoUri = Uri.fromFile(videoFile)
        val videoItem = MediaItem.Builder()
          .setUri(videoUri)
          .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
          .build()
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(videoItem)
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

  // 听视频模式(音频-only):禁用视频轨,只留音频。与在线播放器 toggleAudioOnly 一致。
  val toggleAudioOnly: () -> Unit = {
    audioOnly = !audioOnly
    player.setTrackSelectionParameters(
      player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
        .build(),
    )
  }

  // 进度保存(复用 Room,在线/离线互通):退出/定期存。positionMs 由调用方同步读入,
  // 避免 onDispose 里 launch 异步读 player.currentPosition 时 player 已 release 的竞态。
  fun saveProgress(positionMs: Long) {
    if (videoId.isNotBlank() && cid > 0L) {
      scope.launch {
        runCatching { playbackRepository.saveProgress(videoId, cid, positionMs, durationMs) }
      }
    }
  }

  // 后台播放:暴露 player 给 PlaybackService 做通知控件;不暂停,后台音频继续。
  LaunchedEffect(player) {
    PlayerHolder.player = player
    PlayerHolder.title = title
  }

  // 播放时启动后台保活服务(通知控件);标题变化时刷新。
  LaunchedEffect(isPlaying, title) {
    PlayerHolder.title = title
    if (isPlaying) {
      startPlaybackService(context)
    }
  }

  // 进度轮询。
  LaunchedEffect(player) {
    while (true) {
      isPlaying = player.isPlaying
      positionMs = player.currentPosition
      durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
      delay(500)
    }
  }

  // 定期保存进度(每 15s,对齐在线心跳节奏)。
  LaunchedEffect(player) {
    while (true) {
      delay(15_000)
      saveProgress(player.currentPosition.coerceAtLeast(0L))
    }
  }

  // 续播:进入时读 Room 保存的进度,若在有效区间内 seek 过去。
  LaunchedEffect(player, videoId, cid) {
    if (videoId.isNotBlank() && cid > 0L) {
      val saved = runCatching { playbackRepository.getSavedProgress(videoId, cid) }.getOrNull()
      if (saved != null && saved.positionMs > 0L) {
        val dur = saved.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        if (saved.positionMs < dur) {
          player.seekTo(saved.positionMs)
        }
      }
    }
  }

  DisposableEffect(player) {
    onDispose {
      saveProgress(player.currentPosition.coerceAtLeast(0L))
      PlayerHolder.player = null
      context.stopService(Intent(context, PlaybackService::class.java))
      player.release()
    }
  }

  // 控件自动隐藏:仅全屏(沉浸式)下,播放中 4s 后自动隐(对齐在线)。非全屏栏常驻。
  LaunchedEffect(controlsVisible, isPlaying, fullscreen) {
    if (controlsVisible && isPlaying && fullscreen) {
      delay(4_000)
      controlsVisible = false
    }
  }

  // 切换全屏状态时重置控制栏可见(对齐在线)。
  LaunchedEffect(fullscreen) {
    controlsVisible = true
  }

  // 画面手势:中央点击暂停/播放,边缘点击切控件,横向拖拽 seek。与在线播放器一致。
  val gestureModifier = Modifier
    .fillMaxSize()
    .pointerInput(fullscreen) {
      detectPlayerGestures(
        onCenterTap = {
          if (player.isPlaying) player.pause() else player.play()
        },
        onEdgeTap = { controlsVisible = !controlsVisible },
        onLongPressStart = {},
        onLongPressEnd = {},
        onSeekStart = {
          wasPlayingBeforeSeek = player.playWhenReady
        },
        onSeekDelta = { dx ->
          val dur = player.duration
          if (dur > 0L) {
            val w = size.width.toFloat().coerceAtLeast(1f)
            val cur = if (seekPreviewMs >= 0L) seekPreviewMs else player.currentPosition
            seekPreviewMs = (cur + dx / w * dur.toFloat())
              .coerceIn(0f, dur.toFloat())
              .toLong()
          }
        },
        onSeekEnd = {
          if (seekPreviewMs >= 0L) {
            val clamped = seekPreviewMs.coerceIn(0L, durationMs)
            player.seekTo(clamped)
            positionMs = clamped
          }
          // 播放中拖拽松手后恢复播放(对齐手机播放器习惯),暂停态下拖拽保持暂停。
          if (wasPlayingBeforeSeek) player.play()
          seekPreviewMs = -1L
        },
        onSeekCancel = {
          seekPreviewMs = -1L
        },
      )
    }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
    Column(Modifier.fillMaxSize()) {
      // 视频区:全屏/非全屏均 weight(1f) 占满剩余(全屏铺满整屏,非全屏占上半、下方列表占下半)。
      // 非全屏留状态栏高度(全屏沉浸式 inset=0 自动不留)。
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .then(if (!fullscreen) Modifier.statusBarsPadding() else Modifier),
      ) {
        // 视频画面区:全屏铺满;非全屏 16:9 垂直居中(上下黑边,对齐在线播放器)。
        val videoFrameModifier = if (fullscreen) Modifier.fillMaxSize()
          else Modifier.fillMaxSize().aspectRatio(16f / 9f).align(Alignment.Center)
        Box(modifier = videoFrameModifier) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
              PlayerView(ctx).apply { useController = false }
            },
            // player 随 currentDownloadId 切换重建时,update 重新绑定,避免 PlayerView 仍指向旧 player。
            update = { view -> view.player = player },
          )

          // 听视频模式:叠一层黑底 + 音频指示遮住画面(不销毁 PlayerView,避免 surface 重建黑闪)。
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
        }

        // 手势层:z 序最顶,先于 PlayerView 收到触摸。透明无内容,不遮挡下层视觉。
        Box(modifier = gestureModifier) {}

        // 顶栏:返回 + 标题 + 听视频/倍速/全屏。
        if (controlsVisible) {
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
              text = title,
              color = Color.White,
              maxLines = 1,
              modifier = Modifier.weight(1f),
            )
            IconButton(onClick = toggleAudioOnly) {
              Icon(
                painter = painterResource(R.drawable.ic_player_audio),
                contentDescription = if (audioOnly) "退出听视频" else "听视频",
                tint = if (audioOnly) BiliColors.BiliPink else Color.White,
              )
            }
            IconButton(onClick = { showSpeedSheet = true }) {
              Text(
                text = "${playbackSpeed}x".removeSuffix(".0x").let { if (playbackSpeed == 1f) "1x" else it },
                color = if (playbackSpeed != 1f) BiliColors.BiliPink else Color.White,
              )
            }
            IconButton(onClick = { fullscreen = !fullscreen }) {
              Icon(
                painter = painterResource(
                  if (fullscreen) R.drawable.ic_player_fullscreen_exit else R.drawable.ic_player_fullscreen,
                ),
                contentDescription = if (fullscreen) "退出全屏" else "全屏",
                tint = Color.White,
              )
            }
          }
        }

        // 底栏:进度条 + 播放/暂停。
        if (controlsVisible) {
          Column(
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth()
              .background(Color(0x66000000))
              .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
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
                  if (seekPreviewMs >= 0L) {
                    val clamped = seekPreviewMs.coerceIn(0L, durationMs)
                    player.seekTo(clamped)
                    positionMs = clamped
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

      // 相关视频(非全屏):已下载视频列表,点击切换播放。
      if (!fullscreen) {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(Color.Black)
            .padding(horizontal = 8.dp),
        ) {
          item {
            Text(
              text = "已下载视频",
              color = Color.White,
              modifier = Modifier.padding(vertical = 10.dp),
            )
          }
          if (related.isEmpty()) {
            item {
              Text(
                text = "暂无其他已下载视频",
                color = BiliColors.TextSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
              )
            }
          }
          related.chunked(2).forEach { rowItems ->
            item {
              Row(modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { d ->
                  val coverPath = d.download.coverPath
                  MobileVideoCard(
                    video = VideoSummary(
                      bvid = d.download.videoId,
                      title = d.download.title,
                      pic = d.download.coverUrl,
                      ownerName = "",
                      ownerFace = "",
                      ownerMid = 0L,
                      view = 0,
                      danmaku = 0,
                      duration = (d.download.durationMs / 1000).toInt(),
                      pubdate = 0L,
                      badge = "",
                      cid = d.download.cid,
                      source = if (d.download.source == DownloadSource.YOUTUBE.key) SourceYoutube else SourceBili,
                    ),
                    coverOverride = coverPath?.let { File(it) },
                    onClick = { currentDownloadId = d.download.id },
                    modifier = Modifier.weight(1f).padding(4.dp),
                  )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
              }
            }
          }
        }
      }
    }
  }

  // 倍速设置弹窗(对齐在线 PlayerSettingsSheet 的倍速段,简化:只保留倍速)。
  if (showSpeedSheet) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
      onDismissRequest = { showSpeedSheet = false },
      sheetState = sheetState,
      containerColor = Color(0xFF1A1A20),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
          text = "倍速",
          color = Color.White,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        PlaybackSpeedOptions.forEach { rate ->
          val selected = rate == playbackSpeed
          TextButton(
            onClick = {
              playbackSpeed = rate
              player.setPlaybackSpeed(rate)
              showSpeedSheet = false
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "${rate}x".removeSuffix(".0x"),
              color = if (selected) BiliColors.BiliPink else Color.White,
            )
          }
        }
      }
    }
  }
}

/** 倍速选项(与在线播放器一致)。 */
private val PlaybackSpeedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

/**
 * 瘦身 seek 滑块:Canvas 自绘细轨道(3dp)+ 小拇指(5dp 半径),总高约 20dp,
 * 替代 Material3 Slider(~48dp)以解决"进度条上下太厚"。与在线播放器 SlimSeekSlider 一致。
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

/** 找到承载的 Activity,用于全屏方向/系统栏控制。 */
private fun Context.findActivity(): Activity? {
  var ctx: Context? = this
  while (ctx is android.content.ContextWrapper) {
    if (ctx is Activity) return ctx
    ctx = ctx.baseContext
  }
  return null
}
