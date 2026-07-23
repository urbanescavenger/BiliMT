package com.kirin.mt.ui.mobile.player

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.kirin.mt.core.image.buildOwnerAvatarRequest
import com.kirin.mt.ui.mobile.home.formatCount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.network.FavoriteFolder
import com.kirin.mt.core.network.BiliApiCodeException
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import com.kirin.mt.core.player.BiliMediaDataSourceFactory
import com.kirin.mt.core.player.AirJumpSegment
import com.kirin.mt.core.player.CdnSelector
import com.kirin.mt.core.player.DanmakuSettingsStore
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackInfo
import com.kirin.mt.core.player.PlaybackEpisode
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
// 空降助手阈值(镜像 TV PlayerScreen)
private const val AirJumpWarningLeadMs = 3_500L
private const val AirJumpCompletionToastSuppressMs = 1_500L
private const val AirJumpRewindResetThresholdMs = 2_000L
private const val AirJumpRewindResetLeadMs = 1_000L

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
  airJumpAssistantEnabled: Boolean,
  videoRepository: VideoRepository,
  onPlayVideo: (VideoSummary) -> Unit = {},
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
  // 视频真实尺寸:全屏方向据此自适应横/竖屏。null=尚未拿到,默认按横屏处理。
  var videoSizeInfo by remember { mutableStateOf<VideoSize?>(null) }
  // 画质/分P 切换:activeRequest 驱动 load effect(镜像 TV),metadata 供选集,selectedQualityId 供画质高亮
  var activeRequest by remember(request) { mutableStateOf(request) }
  var metadata by remember { mutableStateOf<PlaybackVideoMetadata?>(null) }
  var selectedQualityId by remember { mutableStateOf<Int?>(null) }
  var playbackSpeed by remember { mutableFloatStateOf(1f) }
  var settingsSheet by remember { mutableStateOf(false) }
  // 手势交互状态:横拖 seek 进行中 / 长按 2x 进行中 / 居中播放暂停反馈闪现
  var dragSeekActive by remember { mutableStateOf(false) }
  var speedBoostActive by remember { mutableStateOf(false) }
  // 用户主动暂停标志:驱动中央常驻暂停图标(区别于缓冲中/播放结束 isPlaying=false)
  var userPaused by remember { mutableStateOf(false) }
  // 拖拽 seek 起点记录的播放意图:松手 seek 后若之前在播放则恢复,避免拖拽后意外暂停
  var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
  // 空降助手(AirJump):SponsorBlock 风格自动跳过广告/片头/片尾段,镜像 TV PlayerScreen
  var airJumpSegments by remember { mutableStateOf<List<AirJumpSegment>>(emptyList()) }
  var warnedAirJumpIds by remember { mutableStateOf<Set<String>>(emptySet()) }
  var skippedAirJumpIds by remember { mutableStateOf<Set<String>>(emptySet()) }
  var lastAirJumpPositionMs by remember { mutableLongStateOf(0L) }
  // 推荐视频(相关视频):按 bvid 拉,简介 Tab 内列出,点击切播
  var relatedVideos by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }

  // 全屏切换:按视频真实比例自动选横/竖屏 + 隐藏系统栏(沉浸);退出/关播放器恢复,避免主页卡横/竖屏。
  // key 含 isPortraitVideo:视频尺寸到达后、用户已在全屏时也能纠正方向。
  val isPortraitVideo = videoSizeInfo?.let { it.height > it.width } ?: false
  DisposableEffect(fullscreen, isPortraitVideo) {
    val activity = context.findActivity()
    if (activity != null) {
      val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
      if (fullscreen) {
        activity.requestedOrientation =
          if (isPortraitVideo) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
          else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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

  // 播放/暂停切换:对齐 TV togglePlayback() 语义——暂停显控件 + 中央常驻暂停图标、
  // 播放隐控件 + 隐中央图标。isPlaying 异步回写,这里用调用前的值判断"即将进入"的状态。
  fun togglePlayback() {
    val willPlay = !isPlaying
    if (willPlay) player.play() else player.pause()
    controlsVisible = !willPlay
    userPaused = !willPlay
  }

  // 分享视频:bvid 优先,无 bvid 用 av{aid};文本=标题+换行+链接,走系统 share sheet。
  fun shareVideo() {
    val bvid = activeRequest.bvid
    val url = when {
      bvid.isNotBlank() -> "https://www.bilibili.com/video/$bvid"
      activeRequest.aid > 0L -> "https://www.bilibili.com/video/av${activeRequest.aid}"
      else -> return
    }
    val shareText = buildString {
      if (displayTitle.isNotBlank()) {
        append(displayTitle)
        append('\n')
      }
      append(url)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, shareText)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享视频")) }
  }

  // 空降助手:进度轮询每 tick 调用,命中段 seek 到段末,入段前预警,回退重置去重(镜像 TV)
  fun handleAirJumpPosition(currentPositionMs: Long) {
    if (!airJumpAssistantEnabled || seekPreviewMs != null || airJumpSegments.isEmpty()) {
      lastAirJumpPositionMs = currentPositionMs
      return
    }

    if (currentPositionMs < lastAirJumpPositionMs - AirJumpRewindResetThresholdMs) {
      val resetIds = airJumpSegments
        .filter { segment -> currentPositionMs < segment.startMs - AirJumpRewindResetLeadMs }
        .map(AirJumpSegment::id)
        .toSet()
      if (resetIds.isNotEmpty()) {
        warnedAirJumpIds = warnedAirJumpIds - resetIds
        skippedAirJumpIds = skippedAirJumpIds - resetIds
      }
    }
    lastAirJumpPositionMs = currentPositionMs

    val hitSegment = airJumpSegments.firstOrNull { segment ->
      segment.id !in skippedAirJumpIds &&
        currentPositionMs >= segment.startMs &&
        currentPositionMs < segment.endMs
    }
    if (hitSegment != null) {
      val duration = player.duration.takeIf { it > 0L } ?: 0L
      val targetPositionMs = hitSegment.endMs.coerceIn(
        0L,
        duration.takeIf { it > 0L } ?: hitSegment.endMs,
      )
      skippedAirJumpIds = skippedAirJumpIds + hitSegment.id
      warnedAirJumpIds = warnedAirJumpIds + hitSegment.id
      player.seekTo(targetPositionMs)
      playbackPositionState.longValue = targetPositionMs
      danmakuSyncToken += 1L
      if (duration <= 0L || targetPositionMs < duration - AirJumpCompletionToastSuppressMs) {
        Toast.makeText(context, context.getString(R.string.player_air_jump_skipped), Toast.LENGTH_SHORT).show()
      }
      return
    }

    val warningSegment = airJumpSegments.firstOrNull { segment ->
      segment.id !in warnedAirJumpIds &&
        segment.id !in skippedAirJumpIds &&
        currentPositionMs >= segment.startMs - AirJumpWarningLeadMs &&
        currentPositionMs < segment.startMs
    }
    if (warningSegment != null) {
      warnedAirJumpIds = warnedAirJumpIds + warningSegment.id
      Toast.makeText(context, context.getString(R.string.player_air_jump_will_skip), Toast.LENGTH_LONG).show()
    }
  }

  // ExoPlayer 监听 + 生命周期释放
  DisposableEffect(player) {
    val listener = object : Player.Listener {
      override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
      }

      override fun onVideoSizeChanged(videoSize: VideoSize) {
        videoSizeInfo = videoSize
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
    userPaused = false
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
      val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
      if (seekPreviewMs == null) {
        playbackPositionState.longValue = currentPositionMs
      }
      val dur = player.duration
      if (dur > 0L) playbackDurationState.longValue = dur
      // 空降助手:seekPreviewMs 期间 handleAirJumpPosition 内部早退,不与手动拖拽冲突
      handleAirJumpPosition(currentPositionMs)
    }
  }

  // 空降助手:按 bvid 拉 SponsorBlock 段;切集/开关变化时重置四组状态(镜像 TV)
  LaunchedEffect(airJumpAssistantEnabled, activeRequest.bvid, activeRequest.cid) {
    airJumpSegments = emptyList()
    warnedAirJumpIds = emptySet()
    skippedAirJumpIds = emptySet()
    lastAirJumpPositionMs = 0L
    if (!airJumpAssistantEnabled || activeRequest.bvid.isBlank()) {
      return@LaunchedEffect
    }
    airJumpSegments = runCatching {
      playbackRepository.getAirJumpSegments(activeRequest.bvid)
    }.getOrDefault(emptyList())
  }

  // 推荐视频(相关视频):按 bvid 拉,切视频重载(镜像 TV PlayerScreen)
  LaunchedEffect(activeRequest.bvid) {
    relatedVideos = emptyList()
    if (activeRequest.bvid.isBlank()) return@LaunchedEffect
    relatedVideos = runCatching {
      videoRepository.getRelatedVideos(activeRequest.bvid)
    }.getOrDefault(emptyList())
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

  // 控件自动隐藏:播放中控件可见时,4s 后自动隐(对齐 TV PlayerControlsAutoHideMs)。
  // 暂停时 isPlaying=false,本 effect 不触发,控件保持可见。
  LaunchedEffect(controlsVisible, isPlaying) {
    if (controlsVisible && isPlaying) {
      delay(4000)
      controlsVisible = false
    }
  }

  val positionMs = seekPreviewMs ?: playbackPositionState.longValue
  val durationMs = playbackDurationState.longValue.coerceAtLeast(1L)

  // 竖屏分栏:非全屏时上半 16:9 播放器 + 下半评论;全屏时播放器占满,评论区不渲染。
  // 视频播放区外层:竖屏宽度铺满、高度自适应(16:9 视频 + 顶/底栏堆叠);全屏铺满。
  // windowInsetsPadding(statusBars):竖屏 edge-to-edge 下最顶留系统状态栏高度;全屏 hide(systemBars) 时 inset=0 不留空。
  val playerAreaModifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
  // 视频播放区:顶栏(额外高度)+ 16:9 视频区 + 底栏(额外高度),两栏不再叠在视频上。
  Column(
    modifier = playerAreaModifier
      .windowInsetsPadding(WindowInsets.statusBars)
      .background(Color.Black),
  ) {
    // 视频区本身:竖屏固定 16:9;全屏取两栏之间剩余(weight(1f),需 ColumnScope)
    val videoModifier = if (fullscreen) Modifier.weight(1f).fillMaxWidth()
      else Modifier.aspectRatio(16f / 9f).fillMaxWidth()
    // 顶栏(仅 controlsVisible && Ready)
    if (controlsVisible && playerState is MobilePlayerState.Ready) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color.Black)
          .padding(horizontal = 16.dp, vertical = 4.dp),
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
        TextButton(onClick = { settingsSheet = true }) {
          Icon(
            painter = painterResource(R.drawable.ic_nav_settings),
            contentDescription = "设置",
            tint = Color.White,
          )
        }
      }
    }

    // 视频区(16:9 本身):PlayerView + 弹幕层 + 状态/暂停图标等均在此 Box 内。
    // 手势检测不再挂本 Box modifier,而是放到末尾的顶层透明 Box(z 序最顶=事件优先),
    // 避免弹幕层 AndroidView(DanmakuView)消费 ACTION_DOWN 挡住 tap/drag/longpress。
    Box(
      modifier = videoModifier
        .background(Color.Black),
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

      // 居中常驻暂停图标:用户暂停时显示,点击中央恢复播放。
      // 全限定调用顶层 AnimatedVisibility:视频 Box 内是 BoxScope,.align 用 BoxScope。
      // 用 userPaused 而非 !isPlaying,避免缓冲中/播放结束时误显;叠层无 clickable,点击透传到
      // 视频 Box 的 detectPlayerGestures.onCenterTap → togglePlayback() 恢复播放。
      androidx.compose.animation.AnimatedVisibility(
        visible = userPaused && playerState is MobilePlayerState.Ready,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center),
      ) {
        Box(
          modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color(0x99000000)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            painter = painterResource(R.drawable.ic_player_pause),
            contentDescription = "已暂停,点击播放",
            tint = Color.White,
            modifier = Modifier.size(36.dp),
          )
        }
      }

      // 长按 2 倍速提示
      androidx.compose.animation.AnimatedVisibility(
        visible = speedBoostActive,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
      ) {
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
          Text("2.0x", color = Color.White)
        }
      }

      // 横拖 seek 时间气泡(仅手势拖拽时;Slider 拖动 dragSeekActive=false 不显示)
      if (dragSeekActive && seekPreviewMs != null) {
        Box(
          modifier = Modifier.align(Alignment.Center),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(8.dp))
              .background(Color(0xCC000000))
              .padding(horizontal = 12.dp, vertical = 6.dp),
          ) {
            Text(formatMs(seekPreviewMs ?: 0L), color = Color.White)
          }
        }
      }

      // 顶层手势层:z 序最顶(最后绘制=事件分发优先),先于弹幕层 AndroidView(DanmakuView)
      // 与 PlayerView 收到触摸。弹幕层的 DanmakuView 会消费 ACTION_DOWN,若手势挂在父 Box
      // modifier 上会被它挡住(弹幕开启即点不暂停/切不出控件);提到这里一劳永逸避开,且不依赖
      // 第三方 View 的触摸行为。透明无内容,不遮挡下层视觉。width 现读,仍为视频区宽,中央 2/3
      // 判定边界不变(左右各 1/6 边缘 → 切控件,中间 2/3 → 暂停/播放)。
      Box(
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            detectPlayerGestures(
              onCenterTap = { togglePlayback() },
              onEdgeTap = { controlsVisible = !controlsVisible },
              onLongPressStart = {
                speedBoostActive = true
                player.setPlaybackSpeed(2f)
              },
              onLongPressEnd = {
                if (speedBoostActive) {
                  speedBoostActive = false
                  player.setPlaybackSpeed(playbackSpeed)
                }
              },
              onSeekStart = {
                dragSeekActive = true
                wasPlayingBeforeSeek = player.playWhenReady
              },
              onSeekDelta = { dx ->
                val dur = player.duration
                if (dur > 0L) {
                  val w = size.width.toFloat().coerceAtLeast(1f)
                  val cur = seekPreviewMs ?: player.currentPosition
                  seekPreviewMs = (cur + dx / w * dur.toFloat())
                    .coerceIn(0f, dur.toFloat())
                    .toLong()
                }
              },
              onSeekEnd = {
                dragSeekActive = false
                seekPreviewMs?.let { target ->
                  player.seekTo(target)
                  playbackPositionState.longValue = target
                  danmakuSyncToken += 1L
                }
                // 播放中拖拽松手后恢复播放(对齐手机播放器习惯),暂停态下拖拽保持暂停
                if (wasPlayingBeforeSeek) player.play()
                seekPreviewMs = null
              },
              onSeekCancel = {
                dragSeekActive = false
                seekPreviewMs = null
              },
            )
          },
      ) {}
    }

    // 底栏(仅 controlsVisible && Ready)
    if (controlsVisible && playerState is MobilePlayerState.Ready) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color.Black)
          .padding(horizontal = 16.dp, vertical = 4.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(formatMs(positionMs), color = Color.White)
          SlimSeekSlider(
            value = (seekPreviewMs ?: positionMs).toFloat().coerceIn(0f, durationMs.toFloat()),
            valueRange = 0f..durationMs.toFloat(),
            onValueChange = {
              if (seekPreviewMs == null) wasPlayingBeforeSeek = player.playWhenReady
              seekPreviewMs = it.toLong()
            },
            onValueChangeFinished = {
              seekPreviewMs?.let { target ->
                player.seekTo(target)
                playbackPositionState.longValue = target
                danmakuSyncToken += 1L
              }
              if (wasPlayingBeforeSeek) player.play()
              seekPreviewMs = null
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
          )
          Text(formatMs(durationMs), color = Color.White)
          MobilePlayerIconButton(
            iconRes = if (fullscreen) R.drawable.ic_player_fullscreen_exit else R.drawable.ic_player_fullscreen,
            contentDescription = if (fullscreen) "退出全屏" else "全屏",
            tint = BiliColors.TextPrimary,
            onClick = { fullscreen = !fullscreen },
          )
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
          onShare = {
            settingsSheet = false
            shareVideo()
          },
        )
      }
    }

  }
  // 下半区:简介/评论双 Tab(仅非全屏竖屏分栏时渲染;全屏横屏时隐藏)。
  // 简介 Tab 展示视频详情 + 相关视频;评论 Tab 复用 MobileCommentList。
  if (!fullscreen) {
    val tabPagerState = rememberPagerState(pageCount = { 2 })
    var commentTotalCount by remember { mutableIntStateOf(0) }
    // 切换视频(metadata.aid 变)时先清零,避免新视频评论加载完前 Tab 残留旧视频评论数;
    // 评论首屏加载后经 onTotalCountChange 回调更新为真实总数。
    LaunchedEffect(metadata?.aid) { commentTotalCount = 0 }
    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(Color.Black),
    ) {
      PrimaryScrollableTabRow(
        selectedTabIndex = tabPagerState.currentPage.coerceIn(0, 1),
        containerColor = Color(0xFF1A1A20),
        contentColor = Color.White,
        edgePadding = 0.dp,
      ) {
        Tab(
          selected = tabPagerState.currentPage == 0,
          onClick = { scope.launch { tabPagerState.animateScrollToPage(0) } },
          text = { Text("简介") },
        )
        Tab(
          selected = tabPagerState.currentPage == 1,
          onClick = { scope.launch { tabPagerState.animateScrollToPage(1) } },
          text = { Text(if (commentTotalCount > 0) "评论 ${formatCount(commentTotalCount)}" else "评论") },
        )
      }
      HorizontalPager(
        state = tabPagerState,
        modifier = Modifier.fillMaxSize(),
      ) { page ->
        when (page) {
          0 -> MobilePlayerIntroTab(
            metadata = metadata,
            request = activeRequest,
            relatedVideos = relatedVideos,
            onPlayVideo = onPlayVideo,
            onOpenUpSpace = onOpenUpSpace,
            videoRepository = videoRepository,
            onShare = { shareVideo() },
            onSelectPage = { ep ->
              activeRequest = activeRequest.copy(
                cid = ep.cid,
                epId = ep.epId,
                startPositionMs = 0L,
                preferredQualityId = selectedQualityId,
                forceStartPosition = true,
                historyPage = ep.page,
              )
            },
            modifier = Modifier.fillMaxSize(),
          )
          1 -> MobileCommentList(
            // aid 取自 metadata(加载后就绪);metadata 加载前 aid=0 → 列表显示加载圈,
            // 避免误显示"暂无评论"。
            aid = metadata?.aid ?: 0L,
            isPgc = activeRequest.isPgc,
            videoRepository = videoRepository,
            modifier = Modifier.fillMaxSize(),
            onTotalCountChange = { commentTotalCount = it },
          )
        }
      }
    }
  }
  }
}

/**
 * 瘦身 seek 滑块:Canvas 自绘细轨道(3dp)+ 小拇指(5dp 半径),总高约 20dp,
 * 替代 Material3 Slider(~48dp)以解决"进度条上下太厚"。seek 逻辑由调用方经
 * onValueChange/onValueChangeFinished 复用(与原 Slider 一致)。
 * 拖拽时拇指实时跟随 value(调用方传 seekPreviewMs ?: positionMs)。
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
  onShare: () -> Unit,
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

    SectionTitle("分享")
    SettingRow("分享视频") {
      TextButton(onClick = onShare) {
        Text("分享", color = Color(0xFFFB7299))
      }
    }
    Spacer(Modifier.padding(top = 8.dp))
  }
}

/**
 * 简介 Tab:视频详情(标题 / UP 主 / 播放量·弹幕·发布时间 / 简介 desc)+ 相关视频列表。
 * metadata 未就绪时居中加载圈占位;深色背景,MobileVideoCard 包在 darkColorScheme 内保文字可读。
 */
@Composable
private fun MobilePlayerIntroTab(
  metadata: PlaybackVideoMetadata?,
  request: PlaybackRequest,
  relatedVideos: List<VideoSummary>,
  onPlayVideo: (VideoSummary) -> Unit,
  onOpenUpSpace: (mid: Long, ownerName: String, ownerFace: String) -> Unit,
  videoRepository: VideoRepository,
  onShare: () -> Unit,
  onSelectPage: (PlaybackEpisode) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (metadata == null) {
    Box(
      modifier = modifier.background(Color.Black),
      contentAlignment = Alignment.Center,
    ) {
      CircularProgressIndicator()
    }
    return
  }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // 互动状态:从 metadata 同步(初次加载完成后),本地维护点击后的乐观更新。
  var liked by remember { mutableStateOf(metadata.liked) }
  var likeCount by remember { mutableStateOf(metadata.likeCount) }
  var coined by remember { mutableStateOf(metadata.coined) }
  var coinCount by remember { mutableStateOf(metadata.coinCount) }
  var faved by remember { mutableStateOf(metadata.faved) }
  var favCount by remember { mutableStateOf(metadata.favoriteCount) }
  LaunchedEffect(metadata.aid) {
    liked = metadata.liked
    likeCount = metadata.likeCount
    coined = metadata.coined
    coinCount = metadata.coinCount
    faved = metadata.faved
    favCount = metadata.favoriteCount
  }
  var busy by remember { mutableStateOf(false) }
  var showCoinDialog by remember { mutableStateOf(false) }
  var showFavDialog by remember { mutableStateOf(false) }
  var favFolders by remember { mutableStateOf<List<FavoriteFolder>>(emptyList()) }
  var favLoading by remember { mutableStateOf(false) }
  // 收藏夹多选:勾选要加入的收藏夹 mediaId 集合(本次只做 add 增量,不处理 del/取消已收藏)。
  var selectedFolderIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

  fun toast(ok: Boolean, successMsg: String) {
    Toast.makeText(
      context,
      if (ok) successMsg else "操作失败,请检查登录或稍后重试",
      Toast.LENGTH_SHORT,
    ).show()
  }

  // 透出 B站业务错误:如「硬币不足」「你已经对该视频投过币了」「请求错误」等,便于区分代码 bug 与业务失败。
  fun toastError(e: Throwable) {
    val msg = (e as? BiliApiCodeException)?.biliMessage?.takeIf { it.isNotBlank() }
      ?: "操作失败,请稍后重试"
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
  }

  // 投币:关弹窗 → 调 coin/add → 成功乐观 +multiply 计数;失败透出 B站消息(硬币不足/已投币等)。
  val doCoin: (Int) -> Unit = { multiply ->
    showCoinDialog = false
    scope.launch {
      busy = true
      try {
        val ok = videoRepository.coinVideo(metadata.aid, multiply = multiply, selectLike = false)
        if (ok) {
          coined = true
          coinCount += multiply
          toast(true, "投币成功")
        } else {
          toast(false, "")
        }
      } catch (e: BiliApiCodeException) {
        toastError(e)
      } catch (e: Exception) {
        toast(false, "")
      }
      busy = false
    }
  }
  MaterialTheme(colorScheme = darkColorScheme()) {
    Column(
      modifier = modifier
        .fillMaxSize()
        .background(Color(0xFF121217))
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
      // 标题
      Text(
        text = metadata.title.ifBlank { request.title },
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )

      // UP 主行(头像 + 名):PGC 无 owner 时整行隐藏。点头像/名进 UP 主页。
      if (metadata.ownerMid > 0L && metadata.ownerName.isNotBlank()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
              onOpenUpSpace(metadata.ownerMid, metadata.ownerName, metadata.ownerFace)
            },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          val avatarModifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
          if (metadata.ownerFace.isBlank()) {
            Box(modifier = avatarModifier)
          } else {
            AsyncImage(
              model = remember(context, metadata.ownerFace) {
                buildOwnerAvatarRequest(context, metadata.ownerFace)
              },
              contentDescription = metadata.ownerName,
              contentScale = androidx.compose.ui.layout.ContentScale.Crop,
              modifier = avatarModifier,
            )
          }
          Spacer(Modifier.width(10.dp))
          Text(
            text = metadata.ownerName,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      // 数据行:播放 · 弹幕 · 发布时间(pubdate 为秒,转 yyyy-MM-dd)
      val pubdateText = remember(metadata.pubdate) {
        if (metadata.pubdate <= 0L) "" else
          SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(metadata.pubdate * 1000L))
      }
      val metaParts = buildList {
        if (metadata.viewCount > 0) add("播放 ${formatCount(metadata.viewCount)}")
        if (metadata.danmakuCount > 0) add("弹幕 ${formatCount(metadata.danmakuCount)}")
        if (pubdateText.isNotBlank()) add(pubdateText)
      }
      if (metaParts.isNotEmpty()) {
        Text(
          text = metaParts.joinToString(" · "),
          color = BiliColors.TextSecondary,
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(top = 8.dp),
        )
      }

      // 简介 desc
      if (metadata.desc.isNotBlank()) {
        Text(
          text = metadata.desc,
          color = BiliColors.TextSecondary,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 10.dp),
        )
      }

      // 互动按钮行:点赞 / 投币 / 收藏 / 分享。PGC 无此交互,整行隐藏。
      if (!request.isPgc && metadata.aid > 0L) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
          horizontalArrangement = Arrangement.SpaceAround,
        ) {
          IntroActionButton(
            iconRes = R.drawable.ic_player_like,
            label = "点赞",
            count = formatCount(likeCount),
            active = liked,
            enabled = !busy,
            onClick = {
              if (busy) return@IntroActionButton
              scope.launch {
                busy = true
                try {
                  val ok = videoRepository.likeVideoArchive(metadata.aid)
                  if (ok) {
                    liked = !liked
                    likeCount = (likeCount + if (liked) 1 else -1).coerceAtLeast(0)
                    toast(true, if (liked) "已点赞" else "已取消点赞")
                  } else {
                    toast(false, "")
                  }
                } catch (e: BiliApiCodeException) {
                  toastError(e)
                } catch (e: Exception) {
                  toast(false, "")
                }
                busy = false
              }
            },
          )
          IntroActionButton(
            iconRes = R.drawable.ic_player_coin,
            label = "投币",
            count = formatCount(coinCount),
            active = coined,
            enabled = !busy,
            onClick = { if (!busy) showCoinDialog = true },
          )
          IntroActionButton(
            iconRes = R.drawable.ic_player_favorite,
            label = "收藏",
            count = formatCount(favCount),
            active = faved,
            enabled = !busy,
            onClick = {
              if (busy) return@IntroActionButton
              showFavDialog = true
              scope.launch {
                favLoading = true
                val mid = runCatching { videoRepository.currentMid() }.getOrDefault(0L)
                val folders = if (mid > 0L) {
                  runCatching { videoRepository.getFavoriteFolders(mid) }.getOrDefault(emptyList())
                } else emptyList()
                favFolders = folders
                selectedFolderIds = emptySet()
                favLoading = false
              }
            },
          )
          IntroActionButton(
            iconRes = R.drawable.ic_player_share,
            label = "分享",
            count = formatCount(metadata.shareCount),
            active = false,
            enabled = true,
            onClick = onShare,
          )
        }
      }

      // 多分P:在此处展示选集(替代相关视频);单P:保持相关视频列表。
      if (metadata.pages.size > 1) {
        SectionTitle("选集")
        metadata.pages.forEach { ep ->
          val selected = ep.cid == request.cid ||
            (ep.epId > 0L && ep.epId == request.epId)
          TextButton(
            onClick = { onSelectPage(ep) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "P${ep.page} ${ep.title}",
              color = if (selected) Color(0xFFFB7299) else Color.White,
            )
          }
        }
      } else {
        // 相关视频:2 列 chunked Row,复用 MobileVideoCard,点击切播 / 进 UP 主页。
        SectionTitle("相关视频")
        if (relatedVideos.isEmpty()) {
          Text(
            text = "暂无相关视频",
            color = BiliColors.TextSecondary,
            modifier = Modifier.padding(vertical = 12.dp),
          )
        }
        relatedVideos.chunked(2).forEach { rowItems ->
          Row(modifier = Modifier.fillMaxWidth()) {
            rowItems.forEach { v ->
              MobileVideoCard(
                video = v,
                onClick = { onPlayVideo(v) },
                onOpenOwner = { video ->
                  onOpenUpSpace(video.ownerMid, video.ownerName, video.ownerFace)
                },
                modifier = Modifier.weight(1f).padding(4.dp),
              )
            }
            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
          }
        }
      }
      Spacer(Modifier.height(16.dp))
    }

    // 投币弹窗:选择投 1 枚 / 2 枚。
    if (showCoinDialog) {
      AlertDialog(
        onDismissRequest = { showCoinDialog = false },
        title = { Text("投币") },
        text = {
          Column {
            TextButton(
              onClick = { doCoin(1) },
              modifier = Modifier.fillMaxWidth(),
            ) { Text("投 1 枚", modifier = Modifier.fillMaxWidth()) }
            TextButton(
              onClick = { doCoin(2) },
              modifier = Modifier.fillMaxWidth(),
            ) { Text("投 2 枚", modifier = Modifier.fillMaxWidth()) }
          }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showCoinDialog = false }) { Text("取消") } },
      )
    }

    // 收藏夹选择弹窗:多选,确认后调 deal(rid=aid, type=2, add_media_ids=选中)。
    if (showFavDialog) {
      AlertDialog(
        onDismissRequest = { showFavDialog = false },
        title = { Text("收藏到收藏夹") },
        text = {
          if (favLoading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          } else if (favFolders.isEmpty()) {
            Text(
              "暂无收藏夹",
              color = BiliColors.TextSecondary,
              modifier = Modifier.padding(16.dp),
            )
          } else {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
              favFolders.forEach { folder ->
                val checked = folder.mediaId in selectedFolderIds
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                      selectedFolderIds = if (checked) {
                        selectedFolderIds - folder.mediaId
                      } else {
                        selectedFolderIds + folder.mediaId
                      }
                    }
                    .padding(vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Checkbox(
                    checked = checked,
                    onCheckedChange = { c ->
                      selectedFolderIds = if (c) {
                        selectedFolderIds + folder.mediaId
                      } else {
                        selectedFolderIds - folder.mediaId
                      }
                    },
                  )
                  Column {
                    Text(folder.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${folder.mediaCount} 个内容", color = BiliColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                  }
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(
            enabled = !favLoading,
            onClick = {
              showFavDialog = false
              val adds = selectedFolderIds.toList()
              if (adds.isEmpty()) return@TextButton
              scope.launch {
                busy = true
                try {
                  val ok = videoRepository.dealFavorite(metadata.aid, addMediaIds = adds, delMediaIds = emptyList())
                  if (ok) {
                    if (!faved) favCount += 1
                    faved = true
                    toast(true, "已收藏")
                  } else {
                    toast(false, "")
                  }
                } catch (e: BiliApiCodeException) {
                  toastError(e)
                } catch (e: Exception) {
                  toast(false, "")
                }
                busy = false
              }
            },
          ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = { showFavDialog = false }) { Text("取消") } },
      )
    }
  }
}

// 简介页互动按钮:图标 + 计数纵向排列,active 时图标变 Bili 粉。
@Composable
private fun IntroActionButton(
  @DrawableRes iconRes: Int,
  label: String,
  count: String,
  active: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Column(
    modifier = Modifier
      .clip(RoundedCornerShape(10.dp))
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = label,
      tint = if (active) BiliColors.BiliPink else BiliColors.TextPrimary,
      modifier = Modifier.size(24.dp),
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = if (count.isBlank() || count == "0") label else count,
      color = if (active) BiliColors.BiliPink else BiliColors.TextSecondary,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
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

/**
 * 移动端播放器底栏图标按钮:扁平半透明圆角底 + 居中图标。
 * 选中/激活态由调用方传 tint(如弹幕开=BiliPink、关=TextSecondary)。无焦点/无玻璃(触屏)。
 */
@Composable
private fun MobilePlayerIconButton(
  @DrawableRes iconRes: Int,
  contentDescription: String,
  tint: Color,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(40.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(BiliColors.PlayerControlIdle)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(22.dp),
    )
  }
}