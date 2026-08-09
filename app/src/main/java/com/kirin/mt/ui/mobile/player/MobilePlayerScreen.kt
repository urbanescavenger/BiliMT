package com.kirin.mt.ui.mobile.player

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.youtube.YoutubeLoadProgress
import com.kirin.mt.core.youtube.YoutubeLoadStep
import com.kirin.mt.core.youtube.YoutubeVideoDetail
import com.kirin.mt.ui.mobile.feed.MobilePlaylistPickerDialog
import com.kirin.mt.core.network.FavoriteFolder
import com.kirin.mt.core.network.BiliApiCodeException
import com.kirin.mt.core.network.BiliNetworkException
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import com.kirin.mt.core.player.BiliMediaDataSourceFactory
import com.kirin.mt.core.player.AirJumpSegment
import com.kirin.mt.core.player.CdnSelector
import com.kirin.mt.core.youtube.sabr.SabrAwareDataSourceFactory
import com.kirin.mt.core.player.DanmakuEntry
import com.kirin.mt.core.player.DanmakuMode
import com.kirin.mt.core.player.DanmakuPostResult
import com.kirin.mt.core.player.DanmakuSettingsStore
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackInfo
import com.kirin.mt.core.player.PlaybackEpisode
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.PlaybackService
import com.kirin.mt.core.player.PlaybackVideoMetadata
import com.kirin.mt.core.player.PlayerHolder
import com.kirin.mt.core.player.createTvPlaybackLoadControl
import com.kirin.mt.ui.player.PlayerDanmakuLayer
import com.kirin.mt.ui.player.appendSabrStartMs
import com.kirin.mt.ui.player.buildDashMediaItem
import com.kirin.mt.ui.player.isSabrDash
import com.kirin.mt.ui.player.isSabrProgressive
import com.kirin.mt.ui.player.nextEpisodeCompletion
import com.kirin.mt.ui.player.toPlaybackRequest
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
private const val DanmakuSendLogTag = "BiliDanmakuSend"
/** 本地插入的发送弹幕前置时间:Bytedance 引擎对 showAtTime < start(time) 的弹幕不显示,
 *  发送时 showAtMs=当前位置会被 start(currentPos≥该位置) 跳过,故向前加 1s 让其落在 set time 之后。 */
private const val LocalDanmakuLeadMs = 1000L
private const val MobilePlayerLogTag = "BiliMT:MobilePlayer"
/** BUFFERING 且进度不前进超过此阈值判定为 stall,触发自动重载续播。 */
private const val StallThresholdMs = 8_000L
/** 单次播放会话内 stall 自动重试上限,超过则交用户手动重试,避免死循环刷 CDN。 */
private const val MaxStallAutoRetry = 2
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
  youtubePlaylistStore: com.kirin.mt.core.youtube.YoutubePlaylistStore,
  playQueue: List<VideoSummary> = emptyList(),
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
  // alpha.49:YouTube 加载步骤提示(resolver/harvester 经 YoutubeLoadProgress 写入当前步骤)。
  // 独立于 playerState——初始加载、切画质/续播轮换都显示;播放就绪/失败时置 null 隐藏。
  val youtubeLoadStep by YoutubeLoadProgress.step.collectAsState()
  var displayTitle by remember { mutableStateOf(request.title) }
  var controlsVisible by remember { mutableStateOf(true) }
  var isPlaying by remember { mutableStateOf(false) }
  // 中途缓冲(STATE_BUFFERING)态:seek 后重载/网络抖动时为 true,驱动加载图标 + 强制显控制栏。
  // 区别于 playerState.Loading(仅初始加载);此态下 playerState 仍为 Ready。
  var isBuffering by remember { mutableStateOf(false) }
  var completionReported by remember { mutableStateOf(false) }
  var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
  val playbackPositionState = remember { mutableLongStateOf(0L) }
  val playbackDurationState = remember { mutableLongStateOf(0L) }
  var danmakuSyncToken by remember { mutableLongStateOf(0L) }
  // stall 自动恢复:BUFFERING 且进度长时间不前进时,记当前位置并 bump retryKey 重载源续播。
  // autoResumePositionMs=-1L 表示无续播位(走 saved progress);>=0 时 launch effect 优先 seekTo 它。
  var retryKey by remember { mutableLongStateOf(0L) }
  var autoResumePositionMs by remember { mutableLongStateOf(-1L) }
  // alpha.52:SABR 源不可 seekTo(LENGTH_UNSET 双 init 崩)→ 中段 seek 走「重新起播」:记目标位置 + bump
  // sabrSeekReloadKey 重跑 loadRequest(fresh MediaSource + startMs=target,窗口内复用会话/跨窗口新 harvest)。
  // pendingSABRSeekMs 由 loadRequest 消费后置空,避免重复 seek。
  var pendingSABRSeekMs by remember { mutableStateOf<Long?>(null) }
  var sabrSeekReloadKey by remember { mutableIntStateOf(0) }
  var autoRetryCount by remember { mutableIntStateOf(0) }
  var danmakuEntries by remember { mutableStateOf<List<com.kirin.mt.core.player.DanmakuEntry>>(emptyList()) }
  var fullscreen by rememberSaveable { mutableStateOf(false) }
  // 画质/分P 切换:activeRequest 驱动 load effect(镜像 TV),metadata 供选集,selectedQualityId 供画质高亮
  var activeRequest by remember(request) { mutableStateOf(request) }
  var metadata by remember { mutableStateOf<PlaybackVideoMetadata?>(null) }
  // YouTube 视频详情(简介 Tab 数据源;YouTube 无 B 站 view 元数据,单独拉 /player videoDetails)
  var youtubeDetail by remember { mutableStateOf<YoutubeVideoDetail?>(null) }
  var youtubeDetailLoading by remember { mutableStateOf(false) }
  var selectedQualityId by remember { mutableStateOf<Int?>(null) }
  var playbackSpeed by remember { mutableFloatStateOf(1f) }
  var settingsSheet by remember { mutableStateOf(false) }
  // 底栏画质下拉菜单(挂在 HD 图标按钮上)
  var showQualityMenu by remember { mutableStateOf(false) }
  // 发送弹幕:底栏内联输入栏开关 / 文本 / 发送中。发在当前播放位置(progress 毫秒)。
  var danmakuInputActive by remember { mutableStateOf(false) }
  var danmakuInputText by remember { mutableStateOf("") }
  var danmakuSending by remember { mutableStateOf(false) }
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
  // 从后台返回(ON_RESUME)时自增,作为手势 pointerInput 的 key,使手势协程在每次回前台时重新启动。
  // 避免后台/前台切换导致 SurfaceView/DanmakuView 重建后手势协程在 awaitFirstDown 处卡死、点击无响应。
  var resumeTick by remember { mutableIntStateOf(0) }

  // 布局三态:
  //  fullscreen → 沉浸式铺满,无简介
  //  !fullscreen && !userPaused(播放中) → 视频居中(上下留黑)+ 底栏完整简介(无Tab/无控制栏/无顶栏)
  //  !fullscreen && userPaused(暂停)  → 16:9 视频 + 简介/评论 Tab 分栏
  // 用 !userPaused 而非 isPlaying 判定播放态:缓冲/播放结束 userPaused 仍 false,保持 PLAYING 布局不抖。
  val isPlayingInline = !fullscreen && !userPaused
  val isPausedSplit = !fullscreen && userPaused
  val playerFillsScreen = fullscreen   // 仅手动全屏铺满
  // 全屏切换(仅手动 fullscreen):跟随设备方向 + 隐藏系统栏(沉浸);
  // 居中播放(非全屏)不动方向/系统栏,保持竖屏 + 系统栏可见。退出/关播放器恢复,避免主页卡横/竖屏。
  // SENSOR 跟随设备传感器切横/竖屏,不依赖系统"自动旋转"开关——用户调转手机必生效;
  // configChanges 已声明 orientation,旋转不重建 Activity,ExoPlayer 与 fullscreen 状态均存活。
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

  val player = remember {
    ExoPlayer.Builder(context)
      .setLoadControl(createTvPlaybackLoadControl())
      // 后台播放优化:别的应用抢音频焦点→自动暂停,焦点回来→自动续播;
      // 耳机/蓝牙音频设备断开(AUDIO_BECOMING_NOISY)→自动暂停。Media3 内部管理焦点
      // 请求/放弃与 becoming-noisy receiver 的注册/反注册(随 player release 自动清理)。
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
          .build(),
        /* handleAudioFocus = */ true,
      )
      .setHandleAudioBecomingNoisy(true)
      .build()
  }

  fun saveAndReportProgress(progressSecondsOverride: Int? = null) {
    val ready = playerState as? MobilePlayerState.Ready ?: return
    val info = ready.info
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    val durationMs = player.duration.takeIf { it > 0 } ?: info.durationMs
    scope.launch {
      // 本地进度保存保留（YouTube 按 videoId 也能续播）；B 站 heartbeat 仅 B 站视频上报。
      runCatching { playbackRepository.saveProgress(info.bvid, info.cid, positionMs, durationMs) }
      if (!activeRequest.isYoutube) {
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
  }

  // 播放/暂停切换:对齐 TV togglePlayback() 语义——暂停显控件 + 中央常驻暂停图标、
  // 播放隐控件 + 隐中央图标。isPlaying 异步回写,这里用调用前的值判断"即将进入"的状态。
  fun togglePlayback() {
    val willPlay = !isPlaying
    if (willPlay) player.play() else player.pause()
    // 非全屏:栏常驻,不随播放/暂停变;全屏:对齐沉浸式,播放隐/暂停显。
    if (fullscreen) controlsVisible = !willPlay
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

  /**
   * alpha.52:SABR 源中段 seek 路由——progressive 直链 SABR 源 LENGTH_UNSET,Media3 原生 seekTo
   * 会重开 DataSource 喂双 init 致 MatroskaExtractor "Multiple Segment elements not supported" 崩。
   * 故 SABR seek 改走「重新起播」:记 pendingSABRSeekMs + bump sabrSeekReloadKey 重跑 loadRequest
   * (fresh MediaSource → fresh extractor → 单 init;目标经 startMs 透传进 sabr:// URL,resolver 按
   * 窗口锚定,窗口内复用会话/跨窗口新 harvest)。非 SABR(B站等)保持直接 player.seekTo。
   */
  fun routeSeek(targetMs: Long) {
    val maxMs = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
    val clamped = targetMs.coerceIn(0L, maxMs)
    if ((playerState as? MobilePlayerState.Ready)?.info?.isSabrProgressive() == true) {
      pendingSABRSeekMs = clamped
      sabrSeekReloadKey++
    } else {
      player.seekTo(clamped)
    }
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
      routeSeek(targetPositionMs)
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

  // 计算自动连播的下一集:播放列表(playQueue)有下一项则取下一项,否则按 metadata.pages 取下一分P
  // (镜像 TV PlayerCompletionPlanner),都没有则返回 null(不连播)。
  fun computeNextRequest(): PlaybackRequest? {
    val queueNext = if (playQueue.size > 1) {
      val cur = playQueue.indexOfFirst { it.bvid == activeRequest.bvid }
      if (cur in 0 until playQueue.lastIndex) playQueue[cur + 1] else null
    } else null
    return queueNext?.toPlaybackRequest()
      ?: activeRequest.nextEpisodeCompletion(metadata, selectedQualityId)?.request
  }

  // 加载(镜像 TV PlayerScreen 的 load 序列)。抽成独立 suspend 函数,使自动连播/用户切集/切画质
  // 都能在"不依赖 Compose 重组"的协程作用域里直接调用——后台播放时帧时钟暂停、重组被推迟,
  // 若仍靠 LaunchedEffect(activeRequest) 触发加载,后台播完当前视频后下一集永远不会加载。
  suspend fun loadRequest(request: PlaybackRequest) {
    playerState = MobilePlayerState.Loading
    completionReported = false
    userPaused = false
    seekPreviewMs = null
    playbackPositionState.longValue = 0L
    playbackDurationState.longValue = 0L
    danmakuEntries = emptyList()
    activeRequest = request
    player.clearMediaItems()
    try {
      // YouTube 无 B 站 view/metadata/cid，跳过 B 站元数据与 cid 解析。
      val isYoutube = request.isYoutube
      val videoMetadata = if (isYoutube) null else runCatching { playbackRepository.getVideoMetadata(request) }.getOrNull()
      metadata = videoMetadata
      // YouTube 简介 Tab 单独拉 /player videoDetails（view/metadata 走 B 站，YouTube 无）。
      youtubeDetailLoading = isYoutube
      youtubeDetail = if (isYoutube) runCatching { videoRepository.getYoutubeVideoDetail(request.bvid) }.getOrNull() else null
      youtubeDetailLoading = false
      val cid = if (isYoutube) {
        0L
      } else {
        request.cid.takeIf { it > 0L }
          ?: videoMetadata?.cid?.takeIf { it > 0L }
          ?: playbackRepository.resolveCid(request.bvid)
      }
      if (cid <= 0L && !isYoutube) {
        playerState = MobilePlayerState.Failed(context.getString(R.string.player_error_missing_cid))
        return
      }
      val resolvedRequest = request.withResolvedMetadata(metadata = videoMetadata, cid = cid)
      displayTitle = resolvedRequest.title.ifBlank { request.title }
      val info = playbackRepository.getPlaybackInfo(
        request = resolvedRequest,
        codecPreference = playbackCodecPreference,
        qualityPreference = playbackQualityPreference,
      )
      selectedQualityId = info.selectedQuality.id
      // 允许 audioTracks 为空：仅当视频轨是合并 progressive 流(如 YouTube itag 18/22,音视频一体)。
      if (info.videoTracks.isEmpty() || (info.audioTracks.isEmpty() && !info.videoTracks.first().isProgressive)) {
        playerState = MobilePlayerState.Failed(context.getString(R.string.player_error_empty_tracks))
        return
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
      // alpha.52:SABR 中段 seek 目标优先(重跑 loadRequest),绕过 saved progress 避免回退到更旧位置。
      val pendingSABRSeek = pendingSABRSeekMs
      pendingSABRSeekMs = null
      val startPositionMs = when {
        pendingSABRSeek != null -> pendingSABRSeek
        // stall 自动重试续播:优先用卡住时的当前位置,而非 saved progress(可能更旧)。
        autoResumePositionMs >= 0L -> autoResumePositionMs.also { autoResumePositionMs = -1L }
        else -> playbackRepository.getSavedProgress(info.bvid, info.cid)?.positionMs
          ?: request.startPositionMs
      }
      // alpha.34:SABR 源不可 seek(LENGTH_UNSET)。把续播点 startMs 透传进 sabr:// URL,
      // 让 DataSource 首段按 playerTimeMs=startMs 从续播点发段(协议层续播),并在下方跳过
      // ExoPlayer.seekTo(否则 seek 取消 fetch 重开 DataSource,init 喂两遍给 MatroskaExtractor
      // → "Multiple Segment elements not supported" 崩)。
      val sabrEffectiveInfo = if (startPositionMs > 0L && effectiveInfo.isSabrProgressive()) {
        effectiveInfo.copy(
          videoTracks = effectiveInfo.videoTracks.map { it.copy(baseUrl = appendSabrStartMs(it.baseUrl, startPositionMs)) },
          audioTracks = effectiveInfo.audioTracks.map { it.copy(baseUrl = appendSabrStartMs(it.baseUrl, startPositionMs)) },
        )
      } else effectiveInfo
      // 后台播放 MediaStyle 通知封面:下载 coverUrl bytes(IO),失败忽略。
      val coverBytes = request.coverUrl.takeIf { it.isNotEmpty() }?.let { url ->
        runCatching {
          withContext(Dispatchers.IO) {
            playbackHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute()
              .use { resp -> resp.body?.bytes() }
          }
        }.getOrNull()
      }
      val metadata = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(displayTitle)
        .setArtist(request.ownerName)
        .apply { if (coverBytes != null) setArtworkData(coverBytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        .build()
      // alpha.27:包一层 SabrAwareDataSourceFactory——sabr:// URI(YouTube SABR 流)交 SabrStreamingDataSource
      //(走 SabrStreamRegistry 查表 + SabrClient 驱动 init/seg),其余 http/https(B站 + YouTube 回退)走 OkHttp。
      val dataSourceFactory = DefaultDataSource.Factory(
        context,
        SabrAwareDataSourceFactory(
          BiliMediaDataSourceFactory(client = playbackHttpClient, headers = sabrEffectiveInfo.headers).create(),
        ),
      )
      // alpha.59(Phase 2 DASH):SABR 轨 isSabrDash=true(segmentBase 仍 null → isProgressive 为 true),
      // 须排除走 DASH 分支(SegmentTemplate MPD + SabrDashDataSource 逐段拉),而非 progressive MergingMediaSource。
      val mediaSource: MediaSource = if (resolvedRequest.isPgc || (sabrEffectiveInfo.videoTracks.first().isProgressive && !sabrEffectiveInfo.isSabrDash())) {
        val videoItem = androidx.media3.common.MediaItem.Builder()
          .setUri(sabrEffectiveInfo.videoTracks.first().baseUrl)
          .setMediaMetadata(metadata)
          .build()
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(videoItem)
        // audioTracks 为空(YouTube 无 PO token 时回退单个合并流)时直接单轨播放。
        if (sabrEffectiveInfo.audioTracks.isEmpty()) {
          videoSource
        } else {
          val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(androidx.media3.common.MediaItem.fromUri(sabrEffectiveInfo.audioTracks.first().baseUrl))
          MergingMediaSource(videoSource, audioSource)
        }
      } else {
        val dashItem = buildDashMediaItem(sabrEffectiveInfo, playbackCdnPreference)
          .buildUpon()
          .setMediaMetadata(metadata)
          .build()
        DashMediaSource.Factory(dataSourceFactory).createMediaSource(dashItem)
      }
      player.setMediaSource(mediaSource)
      player.prepare()
      player.setPlaybackSpeed(playbackSpeed)
      if (startPositionMs > 0L) {
        // alpha.34:SABR 源(LENGTH_UNSET 不可 seek)跳过 seekTo——续播已由 startMs 透传进
        // sabr:// URL 协议层完成;seekTo 会重开 DataSource 喂双 init 致 MatroskaExtractor 崩。
        if (!sabrEffectiveInfo.isSabrProgressive()) player.seekTo(startPositionMs)
        playbackPositionState.longValue = startPositionMs
        danmakuSyncToken += 1L
      }
      player.playWhenReady = true
      playerState = MobilePlayerState.Ready(sabrEffectiveInfo)
      // alpha.49:播放就绪,隐藏加载步骤提示。
      YoutubeLoadProgress.clear()

      // 弹幕
      if (danmakuSettings.enabled && cid > 0L) {
        danmakuEntries = runCatching { playbackRepository.getDanmaku(cid) }.getOrDefault(emptyList())
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      playerState = MobilePlayerState.Failed(error.message.orEmpty())
      // alpha.49:加载失败,隐藏加载步骤提示。
      YoutubeLoadProgress.clear()
    }
  }

  // ExoPlayer 监听 + 生命周期释放
  DisposableEffect(player) {
    val listener = object : Player.Listener {
      override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
        if (playing && autoRetryCount > 0) {
          autoRetryCount = 0
          Log.i(MobilePlayerLogTag, "stall auto-retry recovered, counter reset")
        }
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        // 暴露缓冲态为可观察 state:STATE_BUFFERING→true,READY/ENDED/IDLE→false。
        // 原本仅命令式读 player.playbackState 做 stall 检测,UI 无法据此显加载图标/控制栏。
        isBuffering = playbackState == Player.STATE_BUFFERING
        if (playbackState == Player.STATE_ENDED && playerState is MobilePlayerState.Ready && player.mediaItemCount > 0 && !completionReported) {
          completionReported = true
          saveAndReportProgress(CompletedProgressSeconds)
          context.stopService(Intent(context, PlaybackService::class.java))
          // 自动连播下一集:后台播放时帧时钟暂停、Compose 重组被推迟,LaunchedEffect(completionReported)
          // 不会重启,故在此用不依赖帧时钟的 scope 直接调度(镜像 TV scheduleCompletionAction)。
          scope.launch {
            delay(CompletionActionDelayMs)
            val next = computeNextRequest()
            if (next != null) {
              loadRequest(next)
              // 下一集加载成功并开始播放,重启后台保活服务(原 LaunchedEffect(isPlaying, displayTitle) 在后台被推迟)。
              if (playerState is MobilePlayerState.Ready) {
                PlayerHolder.title = displayTitle
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
              }
            }
          }
        }
      }

      override fun onPlayerErrorChanged(error: PlaybackException?) {
        if (error != null) {
          // alpha.41:SABR RELOAD_PLAYER_RESPONSE / init fetch 失败时 DataSource.open 抛 IOException →
          // ExoPlayer 上报 source error。此前直接 Failed,但 init 失败的会话已被 evict,重 resolve 会走新
          // harvest(可能拿到不同 itag/更新 poToken 的可用会话)。复用 stall-retry 计数(MaxStallAutoRetry)
          // bump retryKey → LaunchedEffect → loadRequest → getPlaybackInfo(cache miss → 新 harvest)。
          // 耗尽上限才 Failed,避免不可恢复错误无限重试(同 stall 语义)。
          if (autoRetryCount < MaxStallAutoRetry) {
            autoRetryCount += 1
            Log.w(
              MobilePlayerLogTag,
              "playback error, auto-retry #${autoRetryCount}: ${error.message}",
            )
            retryKey += 1L
          } else {
            playerState = MobilePlayerState.Failed(error.message.orEmpty())
            context.stopService(Intent(context, PlaybackService::class.java))
          }
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
        Lifecycle.Event.ON_RESUME -> {
          // 后台时系统会清除 FLAG_KEEP_SCREEN_ON,回前台重新挂载;
          // bump resumeTick 让手势 pointerInput 重新启动,避免 SurfaceView/DanmakuView 重建后
          // 手势协程在 awaitFirstDown 处卡死、点击事件被下层消费导致全屏播放控件点击无响应。
          window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          resumeTick++
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

  // 加载(镜像 TV PlayerScreen 的 load 序列)。key 不含 activeRequest:自动连播/用户切集/切画质
  // 由显式 loadRequest 调用触发(见 ExoPlayer 监听器与各 onClick),避免后台重组被推迟时无法加载;
  // 本 effect 只处理初始加载、新视频(request 变)、设置变更、stall 重试(retryKey 变)。
  LaunchedEffect(request, playbackCodecPreference, playbackQualityPreference, playbackCdnPreference, retryKey, sabrSeekReloadKey) {
    loadRequest(activeRequest)
  }

  // 进度轮询
  LaunchedEffect(player, playerState) {
    var stallBaselinePositionMs = 0L
    var stallSinceMs = 0L
    while (true) {
      delay(ProgressUpdateMs)
      val ready = playerState as? MobilePlayerState.Ready ?: continue
      val nowMs = SystemClock.elapsedRealtime()
      val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
      if (seekPreviewMs == null) {
        playbackPositionState.longValue = currentPositionMs
      }
      val dur = player.duration
      if (dur > 0L) playbackDurationState.longValue = dur
      // 空降助手:seekPreviewMs 期间 handleAirJumpPosition 内部早退,不与手动拖拽冲突
      handleAirJumpPosition(currentPositionMs)
      // stall 检测:STATE_BUFFERING 且用户想播(playWhenReady)、进度连续 N 秒不前进 → 自动重载续播。
      // 排除:已暂停(playWhenReady=false)、拖拽预览(seekPreviewMs!=null)、已结束、非 Ready 态。
      val isStallBuffering = player.playbackState == Player.STATE_BUFFERING &&
        player.playWhenReady &&
        !completionReported &&
        seekPreviewMs == null
      if (isStallBuffering) {
        if (currentPositionMs == stallBaselinePositionMs) {
          if (stallSinceMs == 0L) {
            stallSinceMs = nowMs
          } else if (nowMs - stallSinceMs >= StallThresholdMs && autoRetryCount < MaxStallAutoRetry) {
            autoResumePositionMs = currentPositionMs
            autoRetryCount += 1
            Log.w(
              MobilePlayerLogTag,
              "stall detected, auto-retry #${autoRetryCount} @pos=${currentPositionMs}ms buffered=${player.bufferedPercentage}%",
            )
            stallSinceMs = 0L
            stallBaselinePositionMs = 0L
            retryKey += 1L
          }
        } else {
          stallBaselinePositionMs = currentPositionMs
          stallSinceMs = 0L
        }
      } else {
        stallBaselinePositionMs = currentPositionMs
        stallSinceMs = 0L
      }
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

  // 相关视频:B站按 bvid 拉 related;YouTube 无该接口,用播放列表(playQueue)当前视频之后的项。
  LaunchedEffect(activeRequest.bvid) {
    relatedVideos = emptyList()
    if (activeRequest.bvid.isBlank()) return@LaunchedEffect
    if (activeRequest.isYoutube) {
      val curIndex = playQueue.indexOfFirst { it.bvid == activeRequest.bvid }
      if (curIndex >= 0) relatedVideos = playQueue.drop(curIndex + 1)
      return@LaunchedEffect
    }
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

  // 控件自动隐藏:仅手动全屏(沉浸式)下,播放中 4s 后自动隐(对齐 TV PlayerControlsAutoHideMs)。
  // 非全屏播放栏常驻,不自动隐;暂停 isPlaying=false 不触发,全屏控件保持可见。
  LaunchedEffect(controlsVisible, isPlaying, fullscreen) {
    if (controlsVisible && isPlaying && fullscreen) {
      delay(4000)
      controlsVisible = false
    }
  }

  // 缓冲(含 seek 后重载、网络抖动)强制显示控制栏,避免全屏黑屏"什么都控制不了"。
  // 缓冲期 isPlaying=false,上面自动隐藏 effect 不触发,栏保持可见;播放恢复后照常 4s 自动隐藏。
  // 非全屏栏本就常驻,强制 true 无副作用;初始 Loading 态栏受 Ready 守卫不渲染,亦无副作用。
  LaunchedEffect(isBuffering) {
    if (isBuffering) controlsVisible = true
  }

  // 切换全屏状态时重置控制栏可见:进入全屏先显示(播放中再由下方 4s 自动隐藏 effect 沉浸),
  // 退出全屏恢复常驻。避免非全屏手动隐栏后进全屏栏永远不回来的回归。
  // 初始组合 fullscreen=false 且 controlsVisible 已 true,无副作用。
  LaunchedEffect(fullscreen) {
    controlsVisible = true
  }

  val positionMs = seekPreviewMs ?: playbackPositionState.longValue
  val durationMs = playbackDurationState.longValue.coerceAtLeast(1L)

  // 播放列表内点相关/后续视频:就地切换 activeRequest(保留 playQueue 上下文,◀▶ 与相关视频持续可用);
  // 非列表视频回退外层 onPlayVideo(会清队列)。
  val playPlaylistVideo: (VideoSummary) -> Unit = { video ->
    val idx = playQueue.indexOfFirst { it.bvid == video.bvid }
    if (idx >= 0) {
      scope.launch { loadRequest(playQueue[idx].toPlaybackRequest().copy(preferredQualityId = selectedQualityId)) }
    } else {
      onPlayVideo(video)
    }
  }

  // 全屏:播放器铺满、简介不渲染。非全屏播放:视频区高 H(weight(1f)) 内视频 16:9 垂直居中(上下黑边等),
  // 底栏控制栏贴视频下方(上移),控制栏下留黑;底部简介小条(≤200dp,仅标题/UP/简介,无相关视频)。
  // 非全屏暂停:播放器按内容高(16:9+顶栏+底栏)、简介/评论 Tab 占剩余。
  // windowInsetsPadding(statusBars):竖屏 edge-to-edge 下最顶留系统状态栏高度;手动全屏 hide(systemBars) 时 inset=0 不留空。
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
  // playerAreaModifier:全屏/播放 fillMaxSize 占满外层(播放态简介移入视频区填底);暂停 fillMaxWidth(内容高,外层留分栏 Tab)。
  val playerAreaModifier = if (playerFillsScreen || isPlayingInline) Modifier.fillMaxSize()
    else Modifier.fillMaxWidth()
  // 视频播放区:顶栏 + 视频区 + 底栏(PLAYING 顶栏/底栏不渲染,只剩居中视频)。
  BoxWithConstraints(
    modifier = playerAreaModifier
      .windowInsetsPadding(WindowInsets.statusBars)
      .background(Color.Black),
  ) {
    // H=视频区高(maxHeight)、V=视频高(宽×9/16);播放态上黑 Spacer height(H/2-V) 视频底部居中(视频上移),底栏+Tab 占下半。
    val areaH = maxHeight
    val videoH = maxWidth * 9f / 16f
    // 顶栏高度(px→dp):用于非全屏上黑 Spacer 扣除顶栏占位,让视频底部精确对齐中线。
    val density = LocalDensity.current
    var topBarHeightDp by remember { mutableStateOf(0.dp) }
    // 暂停态用 fillMaxWidth(高 wrap 内容),只占视频+顶栏+底栏内容高,留剩余给外层 when 的简介/评论 Tab 分栏。
    // 全屏/播放态 fillMaxSize 占满(播放态简介 weight 填底、全屏沉浸均需铺满)。条件与 playerAreaModifier 同步。
    Column(modifier = if (playerFillsScreen || isPlayingInline) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
      // 视频区:全屏 fillMaxSize;否则 16:9(播放态 16:9 + 上黑居中 + 控制栏紧跟 + 简介填底;暂停 16:9 内容高)。
      // 全屏用 weight(1f):视频区占顶栏/底栏之间剩余空间,让底栏在 Column 流里有位渲染(让位模式)。
      // 控制栏隐藏时顶栏/底栏不渲染,weight(1f) 视频自动占满全屏(沉浸)。fillMaxSize 会把底栏挤出视口。
      val videoModifier = if (playerFillsScreen) Modifier.weight(1f).fillMaxWidth()
        else Modifier.aspectRatio(16f / 9f).fillMaxWidth()
      // 顶栏(两态都渲染,随 controlsVisible 显隐):返回/标题/UP/设置。onSizeChanged 测高供上黑 Spacer 扣顶栏。
      if (controlsVisible && playerState is MobilePlayerState.Ready) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .onSizeChanged { topBarHeightDp = with(density) { it.height.toDp() } },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = onBack) {
            Icon(
              painter = painterResource(R.drawable.ic_player_chevron_left),
              contentDescription = "返回",
              tint = Color.White,
              modifier = Modifier.size(32.dp),
            )
          }
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

      // 非全屏(播放/暂停):顶栏贴顶 + 上黑让视频底部对齐中线。上黑 = H/2 - V - 顶栏高(顶栏占视频上方);
      // 顶栏随 controlsVisible 显隐——显时扣 topBarHeightDp,隐时 topBarSpace=0 视频上移,视频底部始终中线。
      // topBarHeightDp 首帧 0(顶栏未测),重组后精确;coerceAtLeast(0.dp) 防负高。全屏两分支都不走(weight 让位)。
      val showTopBar = controlsVisible && playerState is MobilePlayerState.Ready
      val topBarSpace = if (showTopBar) topBarHeightDp else 0.dp
      if (isPlayingInline || isPausedSplit) Spacer(Modifier.height((areaH / 2 - videoH - topBarSpace).coerceAtLeast(0.dp)))

      // 视频区外层 Box:全屏 fillMaxSize;否则 16:9 aspectRatio。播放态视频 16:9 居中,控制栏紧跟,简介填底。
      // 手势层放末尾顶层透明 Box(z 序最顶=事件优先),避免弹幕层 AndroidView 消费 ACTION_DOWN。
      Box(
        modifier = videoModifier
          .background(Color.Black),
      ) {
        // 内层视频画面区:填满视频 Box(全屏/播放/暂停均 fillMaxSize)。视频 Box 已 16:9(非全屏),内层填满即视频画面区。
        val videoFrameModifier = Modifier.fillMaxSize()
        Box(modifier = videoFrameModifier) {
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

          // alpha.49:YouTube 加载步骤提示——小转圈 + 单行当前步骤,独立于 playerState 常显于底部
          //(初始加载、切画质/续播轮换都覆盖;resolver/harvester 经 YoutubeLoadProgress 写步骤,
          // 播放就绪/失败时置 null 隐藏)。委托属性不能 smart cast,先取局部变量。
          val step = youtubeLoadStep
          if (step != null) {
            Column(
              modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
              Spacer(Modifier.padding(top = 6.dp))
              Text(
                step.label,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
              )
            }
          }

          // 缓冲加载图标:seek 后/网络抖动进入 BUFFERING 时显示,区别于初始 Loading 态的 spinner。
          // 仅 Ready 且缓冲中才显;seekPreviewMs!=null(拖拽预览中)不显,避免与时间气泡争位。
          // 与中央暂停图标互斥:userPaused 时多为 STATE_IDLE,isBuffering=false,二者不并存。
          androidx.compose.animation.AnimatedVisibility(
            visible = playerState is MobilePlayerState.Ready && isBuffering && seekPreviewMs == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
          ) {
            CircularProgressIndicator(color = Color.White)
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
              .pointerInput(resumeTick) {
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
                      routeSeek(target)
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
      }

      // 底栏(进度条等;controlsVisible && Ready。播放态也显示,顶栏仍黑)
      if (controlsVisible && playerState is MobilePlayerState.Ready) {
        val readyInfo = (playerState as MobilePlayerState.Ready).info
        val keyboardController = LocalSoftwareKeyboardController.current
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
          // 发送弹幕:内联输入栏(TextField + 发送),发在当前播放位置 progress 毫秒。
          // YouTube 无弹幕,所有 YouTube 播放不显示。
          if (danmakuInputActive && !activeRequest.isYoutube) {
            DanmakuInputBar(
              text = danmakuInputText,
              onTextChange = { if (it.length <= 100) danmakuInputText = it },
              sending = danmakuSending,
              onSend = {
                val msg = danmakuInputText.trim()
                Log.i(DanmakuSendLogTag, "onSend triggered msg=${msg.length}c cid=${readyInfo.cid} bvid=${readyInfo.bvid}")
                if (msg.isBlank()) {
                  Toast.makeText(context, "弹幕内容不能为空", Toast.LENGTH_SHORT).show()
                } else {
                  val progressMs = playbackPositionState.longValue
                  danmakuSending = true
                  scope.launch {
                    val result = runCatching {
                      playbackRepository.sendDanmaku(
                        cid = readyInfo.cid,
                        bvid = readyInfo.bvid,
                        msg = msg,
                        progressMs = progressMs,
                      )
                    }
                    danmakuSending = false
                    result
                      .onSuccess { posted ->
                        if (posted != null) {
                          // 本地插入,立即在弹幕层渲染(白色滚动 + 粉色粗描边识别)。
                          // showAtMs 用响应回来时的实时播放头 + 前置偏移:发送网络请求耗时 1-3s,
                          // 期间视频在播,用发送时的 progressMs 会让 showAtMs 落后于实际播放头被引擎跳过
                          // (Bytedance 对 showAtTime < start(time) 不显示)。实时位置 + 1s 保证落在 set time 之后。
                          val livePos = player.currentPosition
                          val showAtMs = livePos + LocalDanmakuLeadMs
                          danmakuEntries = danmakuEntries + DanmakuEntry(
                            showAtMs = showAtMs,
                            text = msg,
                            mode = DanmakuMode.Scroll,
                            color = android.graphics.Color.WHITE,
                            isMine = true,
                          )
                          danmakuInputText = ""
                          danmakuInputActive = false
                          keyboardController?.hide()
                          Toast.makeText(context, "弹幕已发送", Toast.LENGTH_SHORT).show()
                        } else {
                          // sendDanmaku 对未登录/参数非法返回 null(未抛)。
                          Toast.makeText(context, "发送失败(未登录或参数异常)", Toast.LENGTH_LONG).show()
                        }
                      }
                      .onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.w(DanmakuSendLogTag, "send danmaku failed", error)
                        val message = when (error) {
                          is BiliApiCodeException -> "弹幕${error.code}:${error.biliMessage}"
                          is BiliNetworkException -> "网络错误 HTTP ${error.statusCode}"
                          else -> "发送失败:${error.localizedMessage ?: error::class.simpleName}"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                      }
                  }
                }
              },
            )
          }
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
                  routeSeek(target)
                  playbackPositionState.longValue = target
                  danmakuSyncToken += 1L
                }
                if (wasPlayingBeforeSeek) player.play()
                seekPreviewMs = null
              },
              modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatMs(durationMs), color = Color.White)
            // 播放列表内:上一个 / 下一个视频按钮(◀ ▶)。非播放列表(curQueueIndex==-1)不显示。
            val curQueueIndex = playQueue.indexOfFirst { it.bvid == activeRequest.bvid }
            if (curQueueIndex >= 0) {
              MobilePlayerIconButton(
                iconRes = R.drawable.ic_player_chevron_left,
                contentDescription = stringResource(R.string.player_previous),
                tint = if (curQueueIndex > 0) BiliColors.TextPrimary else BiliColors.TextTertiary,
                onClick = {
                  if (curQueueIndex > 0) {
                    scope.launch {
                      loadRequest(playQueue[curQueueIndex - 1].toPlaybackRequest()
                        .copy(preferredQualityId = selectedQualityId))
                    }
                  }
                },
              )
              MobilePlayerIconButton(
                iconRes = R.drawable.ic_player_chevron_right,
                contentDescription = stringResource(R.string.player_next),
                tint = if (curQueueIndex < playQueue.lastIndex) BiliColors.TextPrimary else BiliColors.TextTertiary,
                onClick = {
                  if (curQueueIndex < playQueue.lastIndex) {
                    scope.launch {
                      loadRequest(playQueue[curQueueIndex + 1].toPlaybackRequest()
                        .copy(preferredQualityId = selectedQualityId))
                    }
                  }
                },
              )
            }
            // 画质快捷入口:HD 图标按钮 + DropdownMenu,放在进度条与全屏按钮之间。
            // 切换逻辑与原设置弹窗 onQualitySelected 一致(改 selectedQualityId + activeRequest.preferredQualityId 重载)。
            // 播放器页面未包 MaterialTheme,DropdownMenu 显式深色 containerColor,否则默认白底。
            val qualities = readyInfo.qualities
            if (qualities.isNotEmpty()) {
              Box {
                MobilePlayerIconButton(
                  iconRes = R.drawable.ic_player_hd,
                  contentDescription = "画质",
                  tint = BiliColors.TextPrimary,
                  onClick = { showQualityMenu = true },
                )
                DropdownMenu(
                  expanded = showQualityMenu,
                  onDismissRequest = { showQualityMenu = false },
                  containerColor = Color(0xFF1A1A20),
                ) {
                  qualities.forEach { q ->
                    val selected = q.id == selectedQualityId
                    DropdownMenuItem(
                      text = {
                        Text(
                          text = q.description,
                          color = if (selected) Color(0xFFFB7299) else Color.White,
                        )
                      },
                      onClick = {
                        showQualityMenu = false
                        selectedQualityId = q.id
                        scope.launch {
                          loadRequest(activeRequest.copy(
                            startPositionMs = player.currentPosition.takeIf { it > 0L }
                              ?: playbackPositionState.longValue,
                            preferredQualityId = q.id,
                          ))
                        }
                      },
                    )
                  }
                }
              }
            }
            // 发送弹幕入口:点开底栏内联输入栏(DanmakuInputBar),发在当前播放位置。
            // 图标复用 ic_player_subtitles(TV 弹幕设置亦用此图标);激活态高亮 BiliPink。
            // YouTube 无弹幕,所有 YouTube 播放不显示此按钮。
            if (!activeRequest.isYoutube) {
              MobilePlayerIconButton(
                iconRes = R.drawable.ic_player_subtitles,
                contentDescription = "发送弹幕",
                tint = if (danmakuInputActive) BiliColors.BiliPink else BiliColors.TextPrimary,
                onClick = { danmakuInputActive = !danmakuInputActive },
              )
            }
            // 手动沉浸式全屏入口(强制方向 + 隐藏系统栏);所有视频都显示。居中播放由播放/暂停驱动,与此独立。
            MobilePlayerIconButton(
              iconRes = if (fullscreen) R.drawable.ic_player_fullscreen_exit else R.drawable.ic_player_fullscreen,
              contentDescription = if (fullscreen) "退出全屏" else "全屏",
              tint = BiliColors.TextPrimary,
              onClick = { fullscreen = !fullscreen },
            )
          }
        }
      }

      // 设置弹窗:倍速 / 弹幕 / 分享(画质已移至底栏 HD 按钮)
      if (settingsSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
          onDismissRequest = { settingsSheet = false },
          sheetState = sheetState,
          containerColor = Color(0xFF1A1A20),
        ) {
          PlayerSettingsSheet(
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
            onDanmakuCapacity = { c -> scope.launch { danmakuSettingsStore.setCapacity(c) } },
            onShare = {
              settingsSheet = false
              shareVideo()
            },
          )
        }
      }

      // 播放态:简介/评论 Tab 分栏填底(weight 1f,视频居中+底栏之后),复用暂停态同款 Tab。
      // 底栏因视频垂直居中而偏下(区别于暂停态视频贴顶底栏偏上)。
      if (isPlayingInline) {
        MobilePlayerIntroCommentTabs(
          metadata = metadata,
          youtubeDetail = youtubeDetail,
          youtubeDetailLoading = youtubeDetailLoading,
          isYoutube = activeRequest.isYoutube,
          request = activeRequest,
          relatedVideos = relatedVideos,
          isPgc = activeRequest.isPgc,
          videoRepository = videoRepository,
          youtubePlaylistStore = youtubePlaylistStore,
          onPlayVideo = playPlaylistVideo,
          onOpenUpSpace = onOpenUpSpace,
          onShare = { shareVideo() },
          onSelectPage = { ep ->
            scope.launch {
              loadRequest(activeRequest.copy(
                cid = ep.cid,
                epId = ep.epId,
                startPositionMs = 0L,
                preferredQualityId = selectedQualityId,
                forceStartPosition = true,
                historyPage = ep.page,
              ))
            }
          },
          modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        )
      }
    }
  }
  // 下半区(外层):播放态简介/评论 Tab 已移入视频区填底;暂停态同款 Tab 分栏;全屏不渲染。
  when {
    isPausedSplit -> MobilePlayerIntroCommentTabs(
      metadata = metadata,
      youtubeDetail = youtubeDetail,
      youtubeDetailLoading = youtubeDetailLoading,
      isYoutube = activeRequest.isYoutube,
      request = activeRequest,
      relatedVideos = relatedVideos,
      isPgc = activeRequest.isPgc,
      videoRepository = videoRepository,
      youtubePlaylistStore = youtubePlaylistStore,
      onPlayVideo = playPlaylistVideo,
      onOpenUpSpace = onOpenUpSpace,
      onShare = { shareVideo() },
      onSelectPage = { ep ->
        scope.launch {
          loadRequest(activeRequest.copy(
            cid = ep.cid,
            epId = ep.epId,
            startPositionMs = 0L,
            preferredQualityId = selectedQualityId,
            forceStartPosition = true,
            historyPage = ep.page,
          ))
        }
      },
    )
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
 * 播放器设置弹窗:倍速(列表)/ 弹幕(开关 + 4 滑块 + 顶底开关)/ 分享。
 * 画质已移至底栏 HD 按钮(进度条与全屏之间);倍速实时设 player.playbackSpeed;
 * 弹幕经 DanmakuSettingsStore 持久化。
 */
@Composable
private fun PlayerSettingsSheet(
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
  onDanmakuCapacity: (com.kirin.mt.core.player.DanmakuCapacity) -> Unit,
  onShare: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
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
    SettingRow("弹幕数量") {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.kirin.mt.core.player.DanmakuCapacity.entries.forEach { c ->
          val selected = c == danmakuSettings.capacity
          TextButton(onClick = { onDanmakuCapacity(c) }) {
            Text(
              text = stringResource(c.labelRes),
              color = if (selected) Color(0xFFFB7299) else Color.White,
            )
          }
        }
      }
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
 * 简介头部:标题 / UP 主行 / 数据行(播放·弹幕·发布时间)/ 简介 desc。纯渲染(无本地状态),
 * 供完整 简介 Tab(MobilePlayerIntroTab)复用(播放态/暂停态共用 MobilePlayerIntroCommentTabs)。
 */
@Composable
private fun MobilePlayerIntroHeader(
  metadata: PlaybackVideoMetadata,
  request: PlaybackRequest,
  onOpenUpSpace: (Long, String, String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  Column(modifier = modifier) {
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
  }
}

/**
 * YouTube 简介 Tab:标题 / 频道行(头像+名) / 数据行(观看·发布时间) / 简介 desc。
 * YouTube 无 B 站 view 元数据,由 /player videoDetails 填充;youtubeDetail 未就绪时居中加载圈。
 * 无 B 站互动(点赞/投币)行;简介可能很长,可纵向滚动。
 */
@Composable
private fun MobileYoutubeIntroTab(
  youtubeDetail: YoutubeVideoDetail?,
  loading: Boolean,
  request: PlaybackRequest,
  relatedVideos: List<VideoSummary>,
  youtubePlaylistStore: com.kirin.mt.core.youtube.YoutubePlaylistStore,
  onPlayVideo: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val detail = youtubeDetail
  // hooks 规则:collectAsState 等必须在早期 return 之前无条件调用。
  var showPlaylistPicker by remember { mutableStateOf(false) }
  if (detail == null) {
    // 加载中显示转圈;加载完仍未取到(受限视频/解析失败)显示占位,避免永远转圈。
    Box(
      modifier = modifier.background(Color.Black),
      contentAlignment = Alignment.Center,
    ) {
      if (loading) {
        CircularProgressIndicator()
      } else {
        Text(
          text = "简介暂不可用",
          color = BiliColors.TextSecondary,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
    return
  }
  Column(
    modifier = modifier
      .background(Color.Black)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    // 标题
    Text(
      text = detail.title.ifBlank { request.title },
      color = Color.White,
      style = MaterialTheme.typography.titleMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    // 频道行(头像 + 名)。/player 无频道头像字段,渲染占位圆;仅展示,不进频道。
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val avatarModifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
      if (detail.channelAvatarUrl.isBlank()) {
        Box(modifier = avatarModifier)
      } else {
        AsyncImage(
          model = detail.channelAvatarUrl,
          contentDescription = detail.channelName,
          contentScale = androidx.compose.ui.layout.ContentScale.Crop,
          modifier = avatarModifier,
        )
      }
      Spacer(Modifier.width(10.dp))
      Text(
        text = detail.channelName.ifBlank { "YouTube" },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    // 数据行:观看 · 发布时间(pubdate 为秒,转 yyyy-MM-dd)
    val pubdateText = detail.publishedAt?.let { t ->
      if (t <= 0L) "" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t * 1000L))
    }.orEmpty()
    // formatCount 只收 Int，YouTube 的 viewCount 是 Long，先收敛到 Int。
    val viewCountInt = (detail.viewCount ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val metaParts = buildList {
      if (viewCountInt > 0) add("播放 ${formatCount(viewCountInt)}")
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
    if (detail.description.isNotBlank()) {
      Text(
        text = detail.description,
        color = BiliColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 10.dp),
      )
    }
    // 加入播放列表:打开播放列表选择/新建弹窗(/player 无 channelId 字段,存卡缺少 channelId,不影响播放与删除)。
    TextButton(
      onClick = { showPlaylistPicker = true },
      modifier = Modifier.padding(top = 12.dp),
    ) {
      Text(stringResource(R.string.add_to_playlist))
    }

    // 相关视频:播放列表后续视频(播放列表起播时由外层注入),单列展示,点击切播。
    if (relatedVideos.isNotEmpty()) {
      Text(
        text = stringResource(R.string.playlist_section_related),
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
      )
      relatedVideos.forEach { v ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onPlayVideo(v) }
            .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          AsyncImage(
            model = v.pic,
            contentDescription = v.title,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
              .width(110.dp)
              .height(62.dp)
              .clip(RoundedCornerShape(8.dp)),
          )
          Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
              text = v.title,
              color = Color.White,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
              text = buildString {
                if (v.ownerName.isNotBlank()) append(v.ownerName)
                if (v.view > 0) {
                  if (isNotEmpty()) append(" · ")
                  append(formatCount(v.view))
                }
              },
              color = BiliColors.TextSecondary,
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }

  // 加入播放列表选择弹窗:选已有列表或新建。
  if (showPlaylistPicker) {
    val video = VideoSummary(
      bvid = request.bvid,
      title = detail.title.ifBlank { request.title },
      pic = "https://i.ytimg.com/vi/${request.bvid}/mqdefault.jpg",
      ownerName = detail.channelName,
      ownerFace = "",
      ownerMid = 0L,
      view = (detail.viewCount ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
      danmaku = 0,
      duration = 0,
      pubdate = 0L,
      badge = "",
      source = SourceYoutube,
    )
    MobilePlaylistPickerDialog(
      video = video,
      youtubePlaylistStore = youtubePlaylistStore,
      onDismiss = { showPlaylistPicker = false },
    )
  }
}

/**
 * 简介/评论 Tab 分栏:简介 Tab(MobilePlayerIntroTab,详情+相关视频+互动)+ 评论 Tab(MobileCommentList)。
 * 播放态在视频区 BoxWithConstraints 内层 Column 填底(weight 1f,视频居中+底栏之后);暂停态在外层
 * Column 填底(BoxWithConstraints 内容高之后)。ColumnScope 扩展让 weight(1f) 在两处调用方均有效。
 * 切视频(metadata.aid 变)清零评论数,首屏加载后经 onTotalCountChange 回调更新真实总数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.MobilePlayerIntroCommentTabs(
  metadata: PlaybackVideoMetadata?,
  youtubeDetail: YoutubeVideoDetail?,
  youtubeDetailLoading: Boolean,
  isYoutube: Boolean,
  request: PlaybackRequest,
  relatedVideos: List<VideoSummary>,
  isPgc: Boolean,
  videoRepository: VideoRepository,
  youtubePlaylistStore: com.kirin.mt.core.youtube.YoutubePlaylistStore,
  onPlayVideo: (VideoSummary) -> Unit,
  onOpenUpSpace: (Long, String, String) -> Unit,
  onShare: () -> Unit,
  onSelectPage: (PlaybackEpisode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val tabPagerState = rememberPagerState(pageCount = { 2 })
  var commentTotalCount by remember { mutableIntStateOf(0) }
  LaunchedEffect(metadata?.aid) { commentTotalCount = 0 }
  Column(
    modifier = modifier
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
        0 -> if (isYoutube) {
          MobileYoutubeIntroTab(
            youtubeDetail = youtubeDetail,
            loading = youtubeDetailLoading,
            request = request,
            relatedVideos = relatedVideos,
            youtubePlaylistStore = youtubePlaylistStore,
            onPlayVideo = onPlayVideo,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          MobilePlayerIntroTab(
            metadata = metadata,
            request = request,
            relatedVideos = relatedVideos,
            onPlayVideo = onPlayVideo,
            onOpenUpSpace = onOpenUpSpace,
            videoRepository = videoRepository,
            onShare = onShare,
            onSelectPage = onSelectPage,
            modifier = Modifier.fillMaxSize(),
          )
        }
        1 -> if (isYoutube) {
          MobileYoutubeCommentList(
            videoId = request.bvid,
            videoRepository = videoRepository,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          MobileCommentList(
            aid = metadata?.aid ?: 0L,
            isPgc = isPgc,
            videoRepository = videoRepository,
            modifier = Modifier.fillMaxSize(),
            onTotalCountChange = { commentTotalCount = it },
          )
        }
      }
    }
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
      // 简介 头部:标题 / UP 主行 / 数据行 / 简介 desc(抽成 MobilePlayerIntroHeader 复用)。
      MobilePlayerIntroHeader(metadata, request, onOpenUpSpace)

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
internal fun MobilePlayerIconButton(
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

/**
 * 发送弹幕内联输入栏:OutlinedTextField + 发送按钮,挂在底栏顶部,发在当前播放位置。
 * 字数上限 100(对齐 B站 36702),由调用方 onTextChange 拦截。
 * 播放器页面未包 MaterialTheme,OutlinedTextField 显式深色 colors,否则默认白底不可读。
 */
@Composable
private fun DanmakuInputBar(
  text: String,
  onTextChange: (String) -> Unit,
  sending: Boolean,
  onSend: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedTextField(
      value = text,
      onValueChange = onTextChange,
      modifier = Modifier.weight(1f),
      singleLine = true,
      placeholder = { Text("发个弹幕…", color = Color(0xFF8A8A95)) },
      textStyle = TextStyle(color = Color.White),
      trailingIcon = {
        Text("${text.length}/100", color = Color(0xFF8A8A95))
      },
      // 接键盘发送键:手机用户打完字习惯点键盘右下角发送,默认 Done 不触发 onSend。
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { onSend() }),
      colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color(0xFF1A1A20),
        unfocusedContainerColor = Color(0xFF1A1A20),
        focusedBorderColor = BiliColors.BiliPink,
        unfocusedBorderColor = Color(0xFF3A3A45),
        cursorColor = BiliColors.BiliPink,
      ),
    )
    Spacer(Modifier.width(8.dp))
    TextButton(
      onClick = onSend,
      enabled = !sending && text.isNotBlank(),
    ) {
      Text(
        if (sending) "发送中" else "发送",
        color = if (sending || text.isBlank()) Color(0xFF8A8A95) else BiliColors.BiliPink,
      )
    }
  }
}