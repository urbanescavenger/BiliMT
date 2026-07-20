package com.kirin.mt.ui.mobile.player

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.kirin.mt.core.player.PlaybackQuality
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.PlaybackService
import com.kirin.mt.core.player.PlaybackVideoMetadata
import com.kirin.mt.core.player.PlayerHolder
import com.kirin.mt.core.player.createTvPlaybackLoadControl
import com.kirin.mt.ui.player.PlayerDanmakuLayer
import com.kirin.mt.ui.player.buildDashMediaItem
import com.kirin.mt.ui.player.nextEpisodeCompletion
import com.kirin.mt.ui.player.withResolvedMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private const val ProgressUpdateMs = 500L
private const val HeartbeatIntervalMs = 15_000L
private const val CompletedProgressSeconds = -1
private const val CompletionActionDelayMs = 3000L

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
@OptIn(ExperimentalMaterial3Api::class)
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
  onOpenUpSpace: (mid: Long, ownerName: String, ownerFace: String) -> Unit = { _, _, _ -> },
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
  // 画质/分P 切换:activeRequest 驱动 load effect(镜像 TV),metadata 供选集,selectedQualityId 供画质高亮
  var activeRequest by remember(request) { mutableStateOf(request) }
  var metadata by remember { mutableStateOf<PlaybackVideoMetadata?>(null) }
  var selectedQualityId by remember { mutableStateOf<Int?>(null) }
  var playbackSpeed by remember { mutableFloatStateOf(1f) }
  var settingsSheet by remember { mutableStateOf(false) }
  var episodesSheet by remember { mutableStateOf(false) }

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
          epId = activeRequest.epId,
          seasonId = activeRequest.seasonId,
          subType = activeRequest.subType,
          aid = activeRequest.aid,
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

  // 加载(镜像 TV PlayerScreen 的 load 序列);key 为 activeRequest,支持画质/分P 切换重载
  LaunchedEffect(activeRequest, playbackCodecPreference, playbackQualityPreference, playbackCdnPreference) {
    playerState = MobilePlayerState.Loading
    completionReported = false
    seekPreviewMs = null
    playbackPositionState.longValue = 0L
    playbackDurationState.longValue = 0L
    danmakuEntries = emptyList()
    player.clearMediaItems()
    try {
      val videoMetadata = runCatching { playbackRepository.getVideoMetadata(activeRequest) }.getOrNull()
      metadata = videoMetadata
      val cid = activeRequest.cid.takeIf { it > 0L }
        ?: videoMetadata?.cid?.takeIf { it > 0L }
        ?: playbackRepository.resolveCid(activeRequest.bvid)
      if (cid <= 0L) {
        playerState = MobilePlayerState.Failed(context.getString(R.string.player_error_missing_cid))
        return@LaunchedEffect
      }
      val resolvedRequest = activeRequest.withResolvedMetadata(metadata = videoMetadata, cid = cid)
      displayTitle = resolvedRequest.title.ifBlank { activeRequest.title }
      val info = playbackRepository.getPlaybackInfo(
        request = resolvedRequest,
        codecPreference = playbackCodecPreference,
        qualityPreference = playbackQualityPreference,
      )
      selectedQualityId = info.selectedQuality.id
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
        ?: activeRequest.startPositionMs
      // 后台播放 MediaStyle 通知封面:下载 coverUrl bytes(IO),失败忽略。
      val coverBytes = activeRequest.coverUrl.takeIf { it.isNotEmpty() }?.let { url ->
        runCatching {
          withContext(Dispatchers.IO) {
            playbackHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute()
              .use { resp -> resp.body?.bytes() }
          }
        }.getOrNull()
      }
      val metadata = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(displayTitle)
        .setArtist(activeRequest.ownerName)
        .apply { if (coverBytes != null) setArtworkData(coverBytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        .build()
      val dataSourceFactory = DefaultDataSource.Factory(
        context,
        BiliMediaDataSourceFactory(client = playbackHttpClient, headers = effectiveInfo.headers).create(),
      )
      val mediaSource: MediaSource = if (resolvedRequest.isPgc) {
        val videoItem = androidx.media3.common.MediaItem.Builder()
          .setUri(effectiveInfo.videoTracks.first().baseUrl)
          .setMediaMetadata(metadata)
          .build()
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(videoItem)
        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
          .createMediaSource(androidx.media3.common.MediaItem.fromUri(effectiveInfo.audioTracks.first().baseUrl))
        MergingMediaSource(videoSource, audioSource)
      } else {
        val dashItem = buildDashMediaItem(effectiveInfo, playbackCdnPreference)
          .buildUpon()
          .setMediaMetadata(metadata)
          .build()
        DashMediaSource.Factory(dataSourceFactory).createMediaSource(dashItem)
      }
      player.setMediaSource(mediaSource)
      player.prepare()
      player.setPlaybackSpeed(playbackSpeed)
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

  // 自动连播下一集:播放完成(completionReported)后,按 metadata.pages 取下一分P,
  // 延迟 3s 切换 activeRequest 重载(镜像 TV PlayerCompletionPlanner)。切走/手动换集时
  // completionReported 复位,本 effect 重键取消,不会误触。
  LaunchedEffect(completionReported) {
    if (!completionReported) return@LaunchedEffect
    val next = activeRequest.nextEpisodeCompletion(metadata, selectedQualityId) ?: return@LaunchedEffect
    delay(CompletionActionDelayMs)
    activeRequest = next.request
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
        playbackSpeed = playbackSpeed,
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
          modifier = Modifier
            .weight(1f)
            .clickable(enabled = activeRequest.ownerMid > 0L) {
              onOpenUpSpace(activeRequest.ownerMid, activeRequest.ownerName, activeRequest.ownerFace)
            },
        )
        if (activeRequest.ownerMid > 0L) {
          TextButton(onClick = {
            onOpenUpSpace(activeRequest.ownerMid, activeRequest.ownerName, activeRequest.ownerFace)
          }) {
            Text("UP", color = Color.White)
          }
        }
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
          if ((metadata?.pages?.size ?: 0) > 1) {
            TextButton(onClick = { episodesSheet = true }) {
              Text("选集", color = Color.White)
            }
          }
          TextButton(onClick = { settingsSheet = true }) {
            Text("设置", color = Color.White)
          }
        }
      }
    }

    // 设置弹窗:画质 / 倍速 / 弹幕
    if (settingsSheet) {
      val sheetState = rememberModalBottomSheetState()
      val ready = playerState as? MobilePlayerState.Ready
      ModalBottomSheet(
        onDismissRequest = { settingsSheet = false },
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A20),
      ) {
        PlayerSettingsSheet(
          qualities = ready?.info?.qualities.orEmpty(),
          selectedQualityId = selectedQualityId,
          onQualitySelected = { q ->
            settingsSheet = false
            selectedQualityId = q.id
            activeRequest = activeRequest.copy(
              startPositionMs = player.currentPosition.takeIf { it > 0L }
                ?: playbackPositionState.longValue,
              preferredQualityId = q.id,
            )
          },
          playbackSpeed = playbackSpeed,
          onSpeedSelected = { rate ->
            playbackSpeed = rate
            player.setPlaybackSpeed(rate)
          },
          danmakuSettings = danmakuSettings,
          onDanmakuEnabled = { scope.launch { danmakuSettingsStore.setEnabled(it) } },
          onDanmakuOpacity = { scope.launch { danmakuSettingsStore.setOpacity(it) } },
          onDanmakuFontSize = { scope.launch { danmakuSettingsStore.setFontSize(it) } },
          onDanmakuArea = { scope.launch { danmakuSettingsStore.setArea(it) } },
          onDanmakuSpeed = { scope.launch { danmakuSettingsStore.setSpeed(it) } },
          onDanmakuAllowTop = { scope.launch { danmakuSettingsStore.setAllowTop(it) } },
          onDanmakuAllowBottom = { scope.launch { danmakuSettingsStore.setAllowBottom(it) } },
        )
      }
    }

    // 选集(分P)弹窗
    if (episodesSheet) {
      val epSheetState = rememberModalBottomSheetState()
      ModalBottomSheet(
        onDismissRequest = { episodesSheet = false },
        sheetState = epSheetState,
        containerColor = Color(0xFF1A1A20),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
          SectionTitle("选集")
          metadata?.pages.orEmpty().forEach { ep ->
            val selected = ep.cid == activeRequest.cid ||
              (ep.epId > 0L && ep.epId == activeRequest.epId)
            TextButton(
              onClick = {
                episodesSheet = false
                activeRequest = activeRequest.copy(
                  cid = ep.cid,
                  epId = ep.epId,
                  startPositionMs = 0L,
                  preferredQualityId = selectedQualityId,
                  forceStartPosition = true,
                  historyPage = ep.page,
                )
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = "P${ep.page} ${ep.title}",
                color = if (selected) Color(0xFFFB7299) else Color.White,
              )
            }
          }
          Spacer(Modifier.padding(top = 8.dp))
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

private val PlaybackSpeedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

/**
 * 播放器设置弹窗:画质(列表)/ 倍速(列表)/ 弹幕(开关 + 4 滑块 + 顶底开关)。
 * 画质切换改 activeRequest.preferredQualityId 重载;倍速实时设 player.playbackSpeed;
 * 弹幕经 DanmakuSettingsStore 持久化。
 */
@Composable
private fun PlayerSettingsSheet(
  qualities: List<PlaybackQuality>,
  selectedQualityId: Int?,
  onQualitySelected: (PlaybackQuality) -> Unit,
  playbackSpeed: Float,
  onSpeedSelected: (Float) -> Unit,
  danmakuSettings: com.kirin.mt.core.player.DanmakuSettings,
  onDanmakuEnabled: (Boolean) -> Unit,
  onDanmakuOpacity: (Float) -> Unit,
  onDanmakuFontSize: (Int) -> Unit,
  onDanmakuArea: (Float) -> Unit,
  onDanmakuSpeed: (Int) -> Unit,
  onDanmakuAllowTop: (Boolean) -> Unit,
  onDanmakuAllowBottom: (Boolean) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    SectionTitle("画质")
    qualities.forEach { q ->
      val selected = q.id == selectedQualityId
      TextButton(
        onClick = { onQualitySelected(q) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = q.description,
          color = if (selected) Color(0xFFFB7299) else Color.White,
        )
      }
    }

    SectionTitle("倍速")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      PlaybackSpeedOptions.forEach { rate ->
        val selected = rate == playbackSpeed
        TextButton(onClick = { onSpeedSelected(rate) }) {
          Text(
            text = "${rate}x",
            color = if (selected) Color(0xFFFB7299) else Color.White,
          )
        }
      }
    }

    SectionTitle("弹幕")
    SettingRow("弹幕开关") {
      Switch(checked = danmakuSettings.enabled, onCheckedChange = onDanmakuEnabled)
    }
    SliderRow("不透明度", danmakuSettings.opacity, 0.1f..1f) { onDanmakuOpacity(it) }
    SliderRow("字号", danmakuSettings.fontSize.toFloat(), 16f..36f) { onDanmakuFontSize(it.toInt()) }
    SliderRow("显示区域", danmakuSettings.area, 0.25f..1f) { onDanmakuArea(it) }
    SliderRow("速度", danmakuSettings.speed.toFloat(), 3f..7f, steps = 3) { onDanmakuSpeed(it.toInt()) }
    SettingRow("顶部弹幕") {
      Switch(checked = danmakuSettings.allowTop, onCheckedChange = onDanmakuAllowTop)
    }
    SettingRow("底部弹幕") {
      Switch(checked = danmakuSettings.allowBottom, onCheckedChange = onDanmakuAllowBottom)
    }
    Spacer(Modifier.padding(top = 8.dp))
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(
    text = text,
    color = Color.White,
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
  )
}

@Composable
private fun SettingRow(label: String, trailing: @Composable () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = Color.White)
    trailing()
  }
}

@Composable
private fun SliderRow(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  steps: Int = 0,
  onValueChange: (Float) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    Slider(
      value = value.coerceIn(range.start, range.endInclusive),
      onValueChange = onValueChange,
      valueRange = range,
      steps = steps,
    )
  }
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