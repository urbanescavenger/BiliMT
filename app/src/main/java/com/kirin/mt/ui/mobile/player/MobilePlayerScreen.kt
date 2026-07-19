package com.kirin.mt.ui.mobile.player

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kirin.mt.R
import com.kirin.mt.core.player.BiliMediaDataSourceFactory
import com.kirin.mt.core.player.CdnSelector
import com.kirin.mt.core.player.DanmakuSettingsStore
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackInfo
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.PlaybackService
import com.kirin.mt.core.player.PlayerHolder
import com.kirin.mt.core.player.createTvPlaybackLoadControl
import com.kirin.mt.ui.player.PlayerDanmakuLayer
import com.kirin.mt.ui.player.buildDashMediaItem
import com.kirin.mt.ui.player.withResolvedMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private const val ProgressUpdateMs = 500L
private const val HeartbeatIntervalMs = 15_000L
private const val CompletedProgressSeconds = -1

private sealed interface MobilePlayerState {
  data object Loading : MobilePlayerState
  data class Ready(val info: PlaybackInfo) : MobilePlayerState
  data class Failed(val message: String) : MobilePlayerState
}

/**
 * 移动端触屏播放器(Phase 3 v1):复用 BiliTVNative 的 Media3/ExoPlayer 引擎
 * (PlaybackRepository.getPlaybackInfo → DASH/PGC MediaSource + PlayerDanmakuLayer),
 * 输入层换成触屏——点击切换控件、Slider 拖动 seek、播放/暂停/弹幕/返回。
 * 画质/倍速/弹幕设置弹窗留待 Phase 3 v2。
 */
@Composable
fun MobilePlayerScreen(
  request: PlaybackRequest,
  playbackRepository: PlaybackRepository,
  danmakuSettingsStore: DanmakuSettingsStore,
  playbackHttpClient: OkHttpClient,
  cdnSelector: CdnSelector,
  playbackCodecPreference: PlaybackCodecPreference,
  playbackQualityPreference: PlaybackQualityPreference,
  playbackCdnPreference: PlaybackCdnPreference,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current

  val danmakuSettings by danmakuSettingsStore.settings.collectAsState(
    initial = com.kirin.mt.core.player.DanmakuSettings(),
  )

  var playerState by remember { mutableStateOf<MobilePlayerState>(MobilePlayerState.Loading) }
  var displayTitle by remember { mutableStateOf(request.title) }
  var controlsVisible by remember { mutableStateOf(true) }
  var isPlaying by remember { mutableStateOf(false) }
  var completionReported by remember { mutableStateOf(false) }
  var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
  val playbackPositionState = remember { mutableLongStateOf(0L) }
  val playbackDurationState = remember { mutableLongStateOf(0L) }
  var danmakuSyncToken by remember { mutableLongStateOf(0L) }
  var danmakuEntries by remember { mutableStateOf<List<com.kirin.mt.core.player.DanmakuEntry>>(emptyList()) }
  var fullscreen by rememberSaveable { mutableStateOf(false) }

  // 全屏切换:强制横屏 + 隐藏系统栏(沉浸);退出/关播放器恢复,避免主页卡横屏。
  DisposableEffect(fullscreen) {
    val activity = context.findActivity()
    if (activity != null) {
      val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
      if (fullscreen) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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

  val player = remember {
    ExoPlayer.Builder(context)
      .setLoadControl(createTvPlaybackLoadControl())
      .build()
  }

  fun saveAndReportProgress(progressSecondsOverride: Int? = null) {
    val ready = playerState as? MobilePlayerState.Ready ?: return
    val info = ready.info
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    val durationMs = player.duration.takeIf { it > 0 } ?: info.durationMs
    scope.launch {
      runCatching { playbackRepository.saveProgress(info.bvid, info.cid, positionMs, durationMs) }
      val progressSeconds = progressSecondsOverride
        ?: (positionMs / 1000L).toInt()
      runCatching {
        playbackRepository.reportProgress(
          bvid = info.bvid,
          cid = info.cid,
          progressSeconds = progressSeconds,
          epId = request.epId,
          seasonId = request.seasonId,
          subType = request.subType,
          aid = request.aid,
        )
      }
    }
  }

  // ExoPlayer 监听 + 生命周期释放
  DisposableEffect(player) {
    val listener = object : Player.Listener {
      override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && playerState is MobilePlayerState.Ready && player.mediaItemCount > 0 && !completionReported) {
          completionReported = true
          saveAndReportProgress(CompletedProgressSeconds)
          context.stopService(Intent(context, PlaybackService::class.java))
        }
      }

      override fun onPlayerErrorChanged(error: PlaybackException?) {
        if (error != null) {
          playerState = MobilePlayerState.Failed(error.message.orEmpty())
          context.stopService(Intent(context, PlaybackService::class.java))
        }
      }
    }
    player.addListener(listener)

    val window = context.findActivityWindow()
    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // 暴露 player 给后台 PlaybackService 做通知控件;不暂停,后台音频继续。
    PlayerHolder.player = player

    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> {
          // 后台播放:不暂停,仅存一次进度(心跳继续每 15s 上报)。
          saveAndReportProgress()
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose {
      player.removeListener(listener)
      lifecycleOwner.lifecycle.removeObserver(observer)
      saveAndReportProgress()
      context.stopService(Intent(context, PlaybackService::class.java))
      PlayerHolder.player = null
      player.release()
      window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  // 开始播放时启动后台保活服务(通知控件);标题变化时刷新。
  LaunchedEffect(isPlaying, displayTitle) {
    PlayerHolder.title = displayTitle
    if (isPlaying) {
      ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
    }
  }

  // 加载(镜像 TV PlayerScreen 的 load 序列)
  LaunchedEffect(request, playbackCodecPreference, playbackQualityPreference, playbackCdnPreference) {
    playerState = MobilePlayerState.Loading
    completionReported = false
    seekPreviewMs = null
    playbackPositionState.longValue = 0L
    playbackDurationState.longValue = 0L
    danmakuEntries = emptyList()
    player.clearMediaItems()
    try {
      val videoMetadata = runCatching { playbackRepository.getVideoMetadata(request) }.getOrNull()
      val cid = request.cid.takeIf { it > 0L }
        ?: videoMetadata?.cid?.takeIf { it > 0L }
        ?: playbackRepository.resolveCid(request.bvid)
      if (cid <= 0L) {
        playerState = MobilePlayerState.Failed(context.getString(R.string.player_error_missing_cid))
        return@LaunchedEffect
      }
      val resolvedRequest = request.withResolvedMetadata(metadata = videoMetadata, cid = cid)
      displayTitle = resolvedRequest.title.ifBlank { request.title }
      val info = playbackRepository.getPlaybackInfo(
        request = resolvedRequest,
        codecPreference = playbackCodecPreference,
        qualityPreference = playbackQualityPreference,
      )
      if (info.videoTracks.isEmpty() || info.audioTracks.isEmpty()) {
        playerState = MobilePlayerState.Failed(context.getString(R.string.player_error_empty_tracks))
        return@LaunchedEffect
      }
      // CDN 选择
      val resolvedVideo = info.videoTracks.map { track ->
        val sel = cdnSelector.select(track, playbackCdnPreference)
        track.copy(baseUrl = sel.primaryUrl, backupUrls = sel.fallbackUrls)
      }
      val resolvedAudio = info.audioTracks.map { track ->
        val sel = cdnSelector.select(track, playbackCdnPreference)
        track.copy(baseUrl = sel.primaryUrl, backupUrls = sel.fallbackUrls)
      }
      val effectiveInfo = info.copy(videoTracks = resolvedVideo, audioTracks = resolvedAudio)
      val startPositionMs = playbackRepository.getSavedProgress(info.bvid, info.cid)?.positionMs
        ?: request.startPositionMs
      val dataSourceFactory = DefaultDataSource.Factory(
        context,
        BiliMediaDataSourceFactory(client = playbackHttpClient, headers = effectiveInfo.headers).create(),
      )
      val mediaSource: MediaSource = if (resolvedRequest.isPgc) {
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
          .createMediaSource(androidx.media3.common.MediaItem.fromUri(effectiveInfo.videoTracks.first().baseUrl))
        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
          .createMediaSource(androidx.media3.common.MediaItem.fromUri(effectiveInfo.audioTracks.first().baseUrl))
        MergingMediaSource(videoSource, audioSource)
      } else {
        DashMediaSource.Factory(dataSourceFactory)
          .createMediaSource(buildDashMediaItem(effectiveInfo, playbackCdnPreference))
      }
      player.setMediaSource(mediaSource)
      player.prepare()
      if (startPositionMs > 0L) {
        player.seekTo(startPositionMs)
        playbackPositionState.longValue = startPositionMs
        danmakuSyncToken += 1L
      }
      player.playWhenReady = true
      playerState = MobilePlayerState.Ready(effectiveInfo)

      // 弹幕
      if (danmakuSettings.enabled && cid > 0L) {
        danmakuEntries = runCatching { playbackRepository.getDanmaku(cid) }.getOrDefault(emptyList())
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      playerState = MobilePlayerState.Failed(error.message.orEmpty())
    }
  }

  // 进度轮询
  LaunchedEffect(player, playerState) {
    while (true) {
      delay(ProgressUpdateMs)
      val ready = playerState as? MobilePlayerState.Ready ?: continue
      if (seekPreviewMs == null) {
        playbackPositionState.longValue = player.currentPosition.coerceAtLeast(0L)
      }
      val dur = player.duration
      if (dur > 0L) playbackDurationState.longValue = dur
    }
  }

  // 心跳上报
  LaunchedEffect(playerState is MobilePlayerState.Ready, isPlaying) {
    if (playerState !is MobilePlayerState.Ready || !isPlaying) return@LaunchedEffect
    while (true) {
      delay(HeartbeatIntervalMs)
      if (isPlaying) saveAndReportProgress()
    }
  }

  val positionMs = seekPreviewMs ?: playbackPositionState.longValue
  val durationMs = playbackDurationState.longValue.coerceAtLeast(1L)

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black)
      .pointerInput(Unit) {
        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
      },
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

    if (danmakuSettings.enabled && playerState is MobilePlayerState.Ready) {
      PlayerDanmakuLayer(
        entries = danmakuEntries,
        settings = danmakuSettings,
        positionState = playbackPositionState,
        syncToken = danmakuSyncToken,
        isPlaying = isPlaying && seekPreviewMs == null && !completionReported,
        playbackSpeed = 1f,
        lowSpecMode = false,
        modifier = Modifier.fillMaxSize(),
      )
    }

    when (val s = playerState) {
      MobilePlayerState.Loading -> CircularProgressIndicator(
        modifier = Modifier.align(Alignment.Center),
        color = Color.White,
      )
      is MobilePlayerState.Failed -> Column(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(s.message.ifBlank { "播放失败" }, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.padding(top = 12.dp))
        TextButton(onClick = onBack) { Text("返回", color = Color.White) }
      }
      is MobilePlayerState.Ready -> Unit
    }

    if (controlsVisible && playerState is MobilePlayerState.Ready) {
      // 顶栏
      Row(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .background(Color(0x99000000))
          .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = onBack) { Text("‹", color = Color.White) }
        Text(
          text = displayTitle,
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      }

      // 底栏
      Column(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .background(Color(0x99000000))
          .padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(formatMs(positionMs), color = Color.White)
          Slider(
            value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
            onValueChange = { seekPreviewMs = it.toLong() },
            valueRange = 0f..durationMs.toFloat(),
            onValueChangeFinished = {
              seekPreviewMs?.let { target ->
                player.seekTo(target)
                playbackPositionState.longValue = target
                danmakuSyncToken += 1L
              }
              seekPreviewMs = null
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
          )
          Text(formatMs(durationMs), color = Color.White)
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = {
            if (isPlaying) player.pause() else player.play()
          }) {
            Text(if (isPlaying) "⏸" else "▶", color = Color.White)
          }
          TextButton(onClick = {
            scope.launch {
              danmakuSettingsStore.setEnabled(!danmakuSettings.enabled)
            }
          }) {
            Text(
              text = if (danmakuSettings.enabled) "弹 开" else "弹 关",
              color = if (danmakuSettings.enabled) Color.White else Color.Gray,
            )
          }
          TextButton(onClick = { fullscreen = !fullscreen }) {
            Text(
              text = if (fullscreen) "退出全屏" else "全屏",
              color = Color.White,
            )
          }
        }
      }
    }
  }
}

private fun formatMs(ms: Long): String {
  val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
  val m = totalSeconds / 60
  val s = totalSeconds % 60
  return "%d:%02d".format(m, s)
}

/** 找到承载的 Activity 的 Window,用于点亮屏幕。 */
private fun android.content.Context.findActivityWindow(): android.view.Window? {
  var ctx: android.content.Context? = this
  while (ctx is android.content.ContextWrapper) {
    if (ctx is android.app.Activity) return ctx.window
    ctx = ctx.baseContext
  }
  return null
}

/** 找到承载的 Activity,用于全屏方向/系统栏控制。 */
private fun android.content.Context.findActivity(): Activity? {
  var ctx: android.content.Context? = this
  while (ctx is android.content.ContextWrapper) {
    if (ctx is Activity) return ctx
    ctx = ctx.baseContext
  }
  return null
}