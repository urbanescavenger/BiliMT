package com.kirin.mt.ui.player

import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.kirin.mt.core.network.IptvChannel
import com.kirin.mt.core.network.IptvRepository
import com.kirin.mt.core.player.IptvSourceProbeStore
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.kirin.mt.R
import com.kirin.mt.core.player.BiliMediaDataSourceFactory
import com.kirin.mt.core.player.BiliPlaybackHeaders
import com.kirin.mt.core.player.IptvDataSourceFactory
import com.kirin.mt.core.player.LiveLoadErrorHandlingPolicy
import com.kirin.mt.core.player.LivePlayInfo
import com.kirin.mt.core.player.LiveQuality
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.ui.common.ClockOverlay
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.common.currentClockText
import com.kirin.mt.ui.i18n.convertChineseText
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliMotion
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import android.app.Activity
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kirin.mt.core.player.LiveQualityPreferenceStore
import com.kirin.mt.ui.mobile.player.MobilePlayerIconButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 直播默认请求清晰度:原画。服务端不可用时自动降级。 */
private const val LiveDefaultQn = 10000

/** 顶栏控件索引:0=返回,1=清晰度。 */
private const val ControlIndexBack = 0
private const val ControlIndexQuality = 1
private const val ControlCount = 2

/** BUFFERING 且进度不前进超过此阈值判定为 stall,触发自动重载源。 */
private const val LiveStallThresholdMs = 8_000L
/** 单次直播会话内自动重试上限,超过后交用户手动重试,避免死循环刷 CDN。 */
private const val MaxLiveAutoRetry = 3
private const val LivePlaybackLogTag = "BiliMT:LivePlayback"

private fun livePlaybackStateName(state: Int): String = when (state) {
  Player.STATE_IDLE -> "IDLE"
  Player.STATE_BUFFERING -> "BUFFERING"
  Player.STATE_READY -> "READY"
  Player.STATE_ENDED -> "ENDED"
  else -> "UNKNOWN($state)"
}

/** 直播播放器加载状态。 */
private sealed interface LiveLoadState {
  data object Loading : LiveLoadState
  data class Ready(val info: LivePlayInfo) : LiveLoadState
  data class Failed(val message: String) : LiveLoadState
}

/**
 * 直播播放器(独立于点播 [PlayerScreen])。直播不走 DASH/合成 MPD/弹幕/进度/分集,
 * 直接把 [LivePlayInfo.streamUrl] 喂给 HlsMediaSource 或 ProgressiveMediaSource(FLV)。
 *
 * UI/交互对齐点播 [PlayerScreen]/[PlayerOverlay]:玻璃质感顶栏(渐变 + 主播名/人气 +
 * 清晰度入口)、Canvas 暂停指示器、玻璃清晰度面板、统一 D-pad 按键路由与层级关闭、
 * [BiliMotion.PlayerControlsAutoHideMs] 自动隐藏。TV(D-pad)与移动端(触屏)共用。
 */
@Composable
fun LivePlayerScreen(
  request: PlaybackRequest,
  playbackRepository: PlaybackRepository,
  playbackHttpClient: OkHttpClient,
  liveQualityPreferenceStore: LiveQualityPreferenceStore,
  onBack: () -> Unit,
  isMobile: Boolean = false,
  iptvRepository: IptvRepository? = null,
  // TV 端传 app 级判活结果(活源前置重排);移动端不传(默认 null),行为与旧版一致。
  iptvProbeStore: IptvSourceProbeStore? = null,
) {
  val context = LocalContext.current
  val roomId = request.liveRoomId
  val scope = rememberCoroutineScope()
  // IPTV:selectedQn 当源索引,初值 0(第一个镜像源);B站直播才是清晰度 qn。
  var selectedQn by remember(roomId) {
    mutableIntStateOf(if (request.isIptv) 0 else (request.preferredQualityId ?: LiveDefaultQn))
  }
  var loadState by remember(roomId) { mutableStateOf<LiveLoadState>(LiveLoadState.Loading) }
  var liveInfo by remember(roomId) { mutableStateOf<LivePlayInfo?>(null) }
  val isPlayingState = remember { mutableStateOf(false) }
  val playerErrorMsg = remember { mutableStateOf<String?>(null) }
  var controlsVisible by remember { mutableStateOf(true) }
  var showQualityPanel by remember { mutableStateOf(false) }
  var showQualityMenu by remember { mutableStateOf(false) }
  var focusedControlIndex by remember { mutableIntStateOf(ControlIndexQuality) }
  var focusedQualityIndex by remember { mutableIntStateOf(0) }
  var retryKey by remember { mutableIntStateOf(0) }
  var clockText by remember { mutableStateOf(currentClockText()) }
  // 直播画质持久化:首次进入从 store 读上次选择的 qn(若无显式 preferredQualityId)。
  // initialResolved 门控主加载,避免默认画质先加载一次再切存储值造成双加载/闪切。
  // IPTV 无 store 画质可读,直接解锁主加载;B站直播才需先读 store。
  var initialResolved by remember { mutableStateOf(request.isIptv || request.preferredQualityId != null) }
  var fullscreen by rememberSaveable { mutableStateOf(false) }
  // stall 自动恢复:缓冲卡住且进度不前进时自动重载源。
  var autoRetryCount by remember { mutableIntStateOf(0) }
  var autoResumePositionMs by remember { mutableLongStateOf(-1L) }

  // IPTV 频道列表侧栏状态(TV only)。selectedChannelIndex=-1=列表未就绪(仍按原始 request 播)。
  var selectedChannelIndex by remember(roomId) { mutableIntStateOf(-1) }
  var focusedChannelIndex by remember(roomId) { mutableIntStateOf(0) }
  var iptvChannels by remember(roomId) { mutableStateOf<List<IptvChannel>>(emptyList()) }
  var iptvChannelLoading by remember { mutableStateOf(false) }
  var showChannelPanel by remember { mutableStateOf(false) }

  val player = remember(roomId) {
    ExoPlayer.Builder(context).build()
  }
  val lifecycleOwner = LocalLifecycleOwner.current
  val controlsFocusRequester = remember { FocusRequester() }
  val liveLoadErrorPolicy = remember { LiveLoadErrorHandlingPolicy() }
  // IPTV 数据源:强制 IPv4(源 m3u8 302 重定向按客户端 IP 族选节点,IPv6 不可路由的真机会连不上 → 黑屏)。
  // 复用同一 factory,避免每次重载源都新建 OkHttpClient。
  val iptvDataSourceFactory = remember { IptvDataSourceFactory().create() }

  // 当前播放频道(列表就绪后解析)与派生请求:顶栏标题/分组/封面/镜像源跟随当前频道。
  // 列表未就绪时 activeIptvChannel=null → effectiveRequest==request,原台照播。
  val activeIptvChannel = remember(iptvChannels, selectedChannelIndex) {
    iptvChannels.getOrNull(selectedChannelIndex.coerceIn(0, iptvChannels.lastIndex.coerceAtLeast(0)))
  }
  val activeChannelUrls = remember(activeIptvChannel) { activeIptvChannel?.urls ?: request.iptvUrls }
  val effectiveRequest = if (request.isIptv && activeIptvChannel != null) {
    request.copy(
      title = activeIptvChannel.name,
      ownerName = activeIptvChannel.group,
      coverUrl = activeIptvChannel.logo,
      iptvUrls = activeChannelUrls,
    )
  } else request
  // IPTV 专属 TV 路径:确认键开关频道面板,左/右切台,上/下切源。mobile 与 B 站直播不受影响。
  val isIptvTv = request.isIptv && !isMobile

  val qualities = liveInfo?.qualities.orEmpty()
  val qualityLabel = stringResource(R.string.live_quality)

  DisposableEffect(player) {
    player.addListener(object : Player.Listener {
      override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
        // 用 MutableState 对象而非委托的局部 var:匿名对象里不能改捕获的局部 var,
        // 但可以改对象属性(.value)。
        isPlayingState.value = isPlayingChanged
        if (isPlayingChanged && autoRetryCount > 0) {
          autoRetryCount = 0
          android.util.Log.i(LivePlaybackLogTag, "live stall auto-retry recovered, counter reset")
        }
      }

      override fun onPlayerError(error: PlaybackException) {
        // 取流失败(如 CDN 403/404/超时)不再静默卡住,记到 MutableState 让叠层显示。
        android.util.Log.e(
          LivePlaybackLogTag,
          "live player error code=${error.errorCode} codeName=${error.errorCodeName} " +
            "cause=${error.cause?.javaClass?.simpleName} message=${error.message}",
          error,
        )
        // IPTV:断流时自动切下一个镜像源(selectedQn 当源索引),切完即重载。
        // 与下方 retryKey 同机制:改 selectedQn 会触发主加载 LaunchedEffect 重跑。
        // 用 State 背书的当前频道 urls(监听器捕获的 request 是旧的),只在当前频道内切源,不跨台。
        if (request.isIptv) {
          val urls = iptvChannels
            .getOrNull(selectedChannelIndex.coerceIn(0, iptvChannels.lastIndex.coerceAtLeast(0)))
            ?.urls ?: request.iptvUrls
          if (selectedQn < urls.lastIndex) {
            selectedQn += 1
            android.util.Log.w(
              LivePlaybackLogTag,
              "iptv source error, switch to source #${selectedQn + 1}/${urls.size}",
            )
            return
          }
        }
        // 自动重试一次;耗尽后展示错误 overlay 等用户手动重试。
        if (autoRetryCount < MaxLiveAutoRetry) {
          autoRetryCount += 1
          autoResumePositionMs = player.currentPosition.coerceAtLeast(0L)
          android.util.Log.w(
            LivePlaybackLogTag,
            "live auto-retry #${autoRetryCount} @pos=${autoResumePositionMs}ms",
          )
          retryKey += 1
        } else {
          playerErrorMsg.value = error.message.orEmpty().ifBlank { context.getString(R.string.live_play_error) }
        }
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        android.util.Log.d(
          LivePlaybackLogTag,
          "live player state=${livePlaybackStateName(playbackState)} " +
            "tracks=${player.currentTracks.groups.size} " +
            "video=${player.videoFormat?.codecs} audio=${player.audioFormat?.codecs}",
        )
      }
    })
    onDispose { player.release() }
  }

  // 退到桌面(Home/切走)必须暂停:TV 不启 PlaybackService,直播不 pause 会一直在后台出声。
  // 对齐 PlayerScreen 的 ON_PAUSE/ON_RESUME 处理;仅恢复"按 Home 前本来在播"的台,
  // 用户手动暂停过的不被 ON_RESUME 拉起。
  DisposableEffect(lifecycleOwner, player) {
    var resumeWhenBack = false
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> {
          resumeWhenBack = player.isPlaying
          player.pause()
        }
        Lifecycle.Event.ON_RESUME -> {
          if (resumeWhenBack) {
            resumeWhenBack = false
            player.play()
          }
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // 首次进入:若无显式 preferredQualityId,从 store 读上次直播画质选择,再解锁主加载。
  // IPTV 源索引是每频道的,不读全局 store,直接跳过。
  LaunchedEffect(roomId) {
    if (!request.isIptv && !initialResolved) {
      val stored = runCatching { liveQualityPreferenceStore.quality.first() }.getOrDefault(LiveDefaultQn)
      if (stored > 0 && stored != selectedQn) selectedQn = stored
      initialResolved = true
    }
  }

  // TV-only IPTV:拉取 m3u 频道列表一次,并解析进入时所在频道(优先名匹配,回退 URL 匹配,再回退 0)。
  // 匹配台与原始 request 同源时 activeChannelUrls 结构相等 → 不触发多余重载(见主加载键)。
  // 判活结果(启动扫描/列表页截帧回写)活源前置重排:频道侧栏切源、断流自动 selectedQn++
  // 的顺序都变成活源优先,首开(selectedQn=0)直接播活源。名匹配为主,URL 匹配仅兜底
  // (重排后 urls 结构可能与 request.iptvUrls 不同,靠名匹配不受影响)。
  LaunchedEffect(roomId, request.isIptv) {
    if (!request.isIptv || isMobile || iptvRepository == null) return@LaunchedEffect
    iptvChannelLoading = true
    val result = runCatching { iptvRepository.getChannels() }.getOrDefault(emptyList())
    val reordered = iptvProbeStore?.let { store ->
      result.map { channel -> channel.copy(urls = store.reorderUrls(channel.urls)) }
    } ?: result
    iptvChannels = reordered
    iptvChannelLoading = false
    if (reordered.isNotEmpty() && selectedChannelIndex < 0) {
      selectedChannelIndex = reordered.indexOfFirst { it.name == request.title }
        .takeIf { it >= 0 }
        ?: reordered.indexOfFirst { it.urls == request.iptvUrls }
        .takeIf { it >= 0 }
        ?: 0
      focusedChannelIndex = selectedChannelIndex
    }
  }

  LaunchedEffect(roomId, activeChannelUrls, selectedQn, retryKey, initialResolved) {
    if (!initialResolved) return@LaunchedEffect
    loadState = LiveLoadState.Loading
    playerErrorMsg.value = null
    player.clearMediaItems()
    try {
      // IPTV:直链 m3u8,selectedQn 当源索引,qualities 合成 [线路1, 线路2, ...] 供源切换面板。
      // 不走 B站 getRoomPlayInfo,headers 置空(裸数据源,不套 B站 UA/头)。
      val info = if (request.isIptv) {
        val urls = activeChannelUrls
        val idx = selectedQn.coerceIn(0, urls.lastIndex)
        LivePlayInfo(
          roomId = 0,
          streamUrl = urls[idx],
          isHls = true,
          currentQn = idx,
          qualities = urls.mapIndexed { i, _ -> LiveQuality(i, context.getString(R.string.live_line, i + 1)) },
          headers = BiliPlaybackHeaders(sessData = null, biliJct = null),
        )
      } else {
        playbackRepository.getLivePlayInfo(roomId, selectedQn)
      }
      liveInfo = info
      android.util.Log.i(LivePlaybackLogTag, "live playurl resolved room=$roomId qn=${info.currentQn} hls=${info.isHls}")
      // IPTV 用独立数据源:强制 IPv4(见 IptvDataSourceFactory),不套 B站 UA/头;
      // B站直播才走 BiliMediaDataSourceFactory。
      val dataSourceFactory = if (request.isIptv) {
        iptvDataSourceFactory
      } else {
        DefaultDataSource.Factory(
          context,
          BiliMediaDataSourceFactory(playbackHttpClient, info.headers).create(),
        )
      }
      val mediaSource = if (info.isHls) {
        HlsMediaSource.Factory(dataSourceFactory)
          .setLoadErrorHandlingPolicy(liveLoadErrorPolicy)
          .createMediaSource(MediaItem.fromUri(info.streamUrl))
      } else {
        ProgressiveMediaSource.Factory(dataSourceFactory)
          .setLoadErrorHandlingPolicy(liveLoadErrorPolicy)
          .createMediaSource(MediaItem.fromUri(info.streamUrl))
      }
      player.setMediaSource(mediaSource)
      player.prepare()
      player.playWhenReady = true
      val resumeMs = autoResumePositionMs
      if (resumeMs >= 0L) {
        player.seekTo(resumeMs)
        autoResumePositionMs = -1L
      }
      loadState = LiveLoadState.Ready(info)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      loadState = LiveLoadState.Failed(error.message.orEmpty().ifBlank { context.getString(R.string.live_load_failed) })
    }
  }

  // 时钟:每 30s 刷新一次,供顶栏右上角显示。
  LaunchedEffect(Unit) {
    while (isActive) {
      clockText = currentClockText()
      delay(30_000L)
    }
  }

  // stall 检测:STATE_BUFFERING 且用户想播、进度连续 N 秒不前进 → 自动重载源。
  LaunchedEffect(loadState, player) {
    var stallBaselinePositionMs = 0L
    var stallSinceMs = 0L
    while (isActive) {
      if (loadState is LiveLoadState.Ready) {
        val currentPositionMs = player.currentPosition
        val nowMs = System.currentTimeMillis()
        val isStallBuffering =
          player.playbackState == Player.STATE_BUFFERING &&
            player.playWhenReady &&
            currentPositionMs == stallBaselinePositionMs
        if (isStallBuffering) {
          if (stallSinceMs == 0L) {
            stallSinceMs = nowMs
          } else if (nowMs - stallSinceMs >= LiveStallThresholdMs && autoRetryCount < MaxLiveAutoRetry) {
            autoRetryCount += 1
            autoResumePositionMs = currentPositionMs.coerceAtLeast(0L)
            android.util.Log.w(
              LivePlaybackLogTag,
              "live stall detected, auto-retry #${autoRetryCount} @pos=${currentPositionMs}ms buffered=${player.bufferedPercentage}%",
            )
            stallBaselinePositionMs = 0L
            stallSinceMs = 0L
            retryKey += 1
          }
        } else {
          stallBaselinePositionMs = currentPositionMs
          stallSinceMs = 0L
        }
      }
      delay(BiliMotion.PlayerProgressUpdateMs)
    }
  }

  // 控制条自动隐藏:播放中、无清晰度面板/下拉时 [PlayerControlsAutoHideMs] 后隐藏。
  // 暂停时不隐藏(与点播一致),面板/下拉打开时不隐藏。
  LaunchedEffect(controlsVisible, isPlayingState.value, showQualityPanel, showQualityMenu) {
    if (controlsVisible && isPlayingState.value && !showQualityPanel && !showQualityMenu) {
      delay(BiliMotion.PlayerControlsAutoHideMs)
      if (isActive && controlsVisible && isPlayingState.value && !showQualityPanel && !showQualityMenu) {
        controlsVisible = false
      }
    }
  }

  // TV:进入时把焦点收到根容器,统一由 onPreviewKeyEvent 路由 D-pad 按键。
  LaunchedEffect(Unit) {
    runCatching { controlsFocusRequester.requestFocus() }
  }

  // 移动端全屏:强制横屏(直播恒横屏)+ 隐藏系统栏;退出/离开恢复,避免主页卡横屏。
  if (isMobile) {
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
  }

  fun persistQuality(qn: Int) {
    // IPTV 的 selectedQn 是源索引(每频道),不能全局持久化,跳过。
    if (request.isIptv) return
    scope.launch { runCatching { liveQualityPreferenceStore.setQuality(qn) } }
  }

  fun togglePlayback() {
    if (player.isPlaying) player.pause() else player.play()
    controlsVisible = true
  }

  fun showControls() {
    controlsVisible = true
    runCatching { controlsFocusRequester.requestFocus() }
  }

  fun openQualityPanel() {
    if (qualities.isEmpty()) return
    showQualityPanel = true
    focusedQualityIndex = qualities.indexOfFirst { it.qn == selectedQn }.coerceAtLeast(0)
    controlsVisible = true
  }

  fun moveControl(delta: Int) {
    focusedControlIndex = (focusedControlIndex + delta).coerceIn(0, ControlCount - 1)
  }

  fun changeQualityFocus(delta: Int) {
    if (qualities.isEmpty()) return
    focusedQualityIndex = (focusedQualityIndex + delta).coerceIn(0, qualities.lastIndex)
  }

  fun activateControl() {
    when (focusedControlIndex) {
      ControlIndexBack -> onBack()
      ControlIndexQuality -> openQualityPanel()
    }
  }

  fun openChannelPanel() {
    if (iptvChannels.isEmpty()) return
    focusedChannelIndex = selectedChannelIndex.coerceIn(0, iptvChannels.lastIndex)
    showChannelPanel = true
    controlsVisible = true
  }

  fun selectChannel(index: Int) {
    if (index !in iptvChannels.indices) return
    // 重选当前台:仅关闭面板,不重载。
    if (index == selectedChannelIndex) { showChannelPanel = false; return }
    selectedChannelIndex = index
    selectedQn = 0 // 切台复位镜像源
    showChannelPanel = false
    showQualityPanel = false
  }

  fun zapChannel(delta: Int) { // 面板关闭:左/右切台
    if (iptvChannels.isEmpty()) return
    val size = iptvChannels.size
    val current = selectedChannelIndex.coerceIn(0, iptvChannels.lastIndex)
    val next = ((current + delta) % size + size) % size // 首尾环绕(tvbox 风格)
    if (next != current) {
      selectedChannelIndex = next
      selectedQn = 0
    }
  }

  fun switchSource(delta: Int) { // 面板关闭:上/下切线路
    val urls = iptvChannels
      .getOrNull(selectedChannelIndex.coerceIn(0, iptvChannels.lastIndex.coerceAtLeast(0)))
      ?.urls ?: request.iptvUrls
    if (urls.size <= 1) return // 单源台忽略
    selectedQn = ((selectedQn + delta) % urls.size + urls.size) % urls.size
    showQualityPanel = false
  }

  fun moveChannelFocus(delta: Int) { // 面板打开:上/下移焦点
    if (iptvChannels.isEmpty()) return
    focusedChannelIndex = ((focusedChannelIndex + delta) % iptvChannels.size + iptvChannels.size) % iptvChannels.size
  }

  // 层级关闭:频道面板→清晰度面板→控件→退出。TV 遥控器返回键与移动端系统返回共用此函数。
  fun closePanelOrControls() {
    when {
      showChannelPanel -> showChannelPanel = false
      showQualityPanel -> showQualityPanel = false
      controlsVisible -> controlsVisible = false
      else -> onBack()
    }
  }

  BackHandler { closePanelOrControls() }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
      .focusRequester(controlsFocusRequester)
      .focusable()
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
        when (event.key) {
          Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            when {
              // IPTV TV:确认键开关频道面板 / 面板内确认选中切台。
              isIptvTv && showChannelPanel && iptvChannels.isNotEmpty() -> selectChannel(focusedChannelIndex)
              isIptvTv -> openChannelPanel()
              showQualityPanel && qualities.isNotEmpty() -> {
                val qn = qualities[focusedQualityIndex].qn
                selectedQn = qn
                showQualityPanel = false
                persistQuality(qn)
              }
              controlsVisible -> activateControl()
              else -> togglePlayback()
            }
            true
          }
          Key.DirectionLeft -> {
            when {
              isIptvTv && showChannelPanel -> { showChannelPanel = false; true } // 面板打开:左键关闭不切换
              isIptvTv -> { zapChannel(-1); true } // 面板关闭:左键上一个台
              showQualityPanel -> true // 面板纵向滚动,左右消费避免原生焦点移入面板行
              controlsVisible -> { moveControl(-1); true }
              else -> { showControls(); true }
            }
          }
          Key.DirectionRight -> {
            when {
              isIptvTv && showChannelPanel -> { showChannelPanel = false; true }
              isIptvTv -> { zapChannel(1); true } // 面板关闭:右键下一个台
              showQualityPanel -> true
              controlsVisible -> { moveControl(1); true }
              else -> { showControls(); true }
            }
          }
          Key.DirectionUp -> {
            when {
              isIptvTv && showChannelPanel -> { moveChannelFocus(-1); true } // 面板打开:上移焦点
              isIptvTv -> { switchSource(-1); true } // 面板关闭:上键上一个线路
              showQualityPanel -> { changeQualityFocus(-1); true }
              else -> { showControls(); true }
            }
          }
          Key.DirectionDown -> {
            when {
              isIptvTv && showChannelPanel -> { moveChannelFocus(1); true } // 面板打开:下移焦点
              isIptvTv -> { switchSource(1); true } // 面板关闭:下键下一个线路
              showQualityPanel -> { changeQualityFocus(1); true }
              controlsVisible -> { controlsVisible = false; true }
              else -> { showControls(); true }
            }
          }
          Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { togglePlayback(); true }
          else -> false
        }
      },
  ) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        PlayerView(ctx).apply {
          useController = false
          this.player = player
          setShutterBackgroundColor(android.graphics.Color.BLACK)
          keepScreenOn = true
          resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
      },
      update = { it.player = player },
    )

    // 中心触屏区:点按在"显示控件/切换播放"间分派。不获取焦点,键盘由根容器统一路由。
    Box(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
          detectTapGestures(onTap = {
            when {
              showQualityPanel -> showQualityPanel = false
              controlsVisible -> togglePlayback()
              else -> showControls()
            }
          })
        },
    )

    // 暂停指示器(仅 Ready 且未在播时):复用点播 Canvas 双竖杠,替换旧文字图标。
    if (loadState is LiveLoadState.Ready && !isPlayingState.value) {
      PauseIndicatorOverlay(
        modifier = Modifier.align(Alignment.Center),
      )
    }

    val errorMsg = playerErrorMsg.value
    when {
      errorMsg != null -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        // onPlayerError(取流 403 等)或加载失败都走这里,重试清错误 + 重载。
        FeedStatusScreen(
          message = errorMsg,
          actionLabel = stringResource(R.string.action_retry),
          onAction = {
            playerErrorMsg.value = null
            retryKey++
          },
        )
      }
      loadState is LiveLoadState.Loading -> LoadingOverlay()
      loadState is LiveLoadState.Failed -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        FeedStatusScreen(
          message = (loadState as LiveLoadState.Failed).message,
          actionLabel = stringResource(R.string.action_retry),
          onAction = { retryKey++ },
        )
      }
      else -> Unit
    }

    if (controlsVisible && loadState is LiveLoadState.Ready) {
      LiveTopOverlay(
        request = effectiveRequest,
        currentQualityDesc = qualities
          .firstOrNull { it.qn == selectedQn }?.description
          ?: qualities.firstOrNull()?.description
          ?: qualityLabel,
        focusedControlIndex = focusedControlIndex,
        clockText = clockText,
        // IPTV TV 的线路切换已由上/下键承担,隐藏画质 chip 避免死控件。
        showQuality = !isMobile && !request.isIptv,
        onBack = onBack,
        onOpenQuality = { openQualityPanel() },
      )
    }

    // 移动端底栏控制条:直播中红点 + 画质下拉 + 预留弹幕按钮 + 全屏。TV 不渲染。
    if (isMobile && controlsVisible && loadState is LiveLoadState.Ready) {
      LiveBottomBar(
        qualities = qualities,
        selectedQn = selectedQn,
        showQualityMenu = showQualityMenu,
        onToggleQualityMenu = { showQualityMenu = it },
        onPickQuality = { qn ->
          selectedQn = qn
          showQualityMenu = false
          persistQuality(qn)
        },
        onDanmaku = {
          Toast.makeText(context, context.getString(R.string.live_danmaku_unavailable), Toast.LENGTH_SHORT).show()
        },
        fullscreen = fullscreen,
        onToggleFullscreen = { fullscreen = !fullscreen },
        modifier = Modifier.align(Alignment.BottomStart),
      )
    }

    if (showQualityPanel && qualities.isNotEmpty()) {
      LiveQualityPanel(
        qualities = qualities,
        selectedQn = selectedQn,
        focusedQualityIndex = focusedQualityIndex,
        onPick = { qn ->
          selectedQn = qn
          showQualityPanel = false
          persistQuality(qn)
        },
        onDismiss = { showQualityPanel = false },
      )
    }

    // IPTV TV:确认键弹出/隐藏的频道列表侧栏。独立于 controlsVisible,自动隐藏时不消失。
    if (showChannelPanel) {
      IptvChannelListPanel(
        channels = iptvChannels,
        selectedChannelIndex = selectedChannelIndex,
        focusedChannelIndex = focusedChannelIndex,
        loading = iptvChannelLoading,
        onSelect = { selectChannel(it) },
        onDismiss = { showChannelPanel = false },
        modifier = Modifier.align(Alignment.CenterStart),
      )
    }
  }
}

@Composable
private fun LiveTopOverlay(
  request: PlaybackRequest,
  currentQualityDesc: String,
  focusedControlIndex: Int,
  clockText: String,
  showQuality: Boolean,
  onBack: () -> Unit,
  onOpenQuality: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerTopGradientHeight)
      .background(
        Brush.verticalGradient(
          colors = listOf(BiliColors.OverlayStrong, BiliColors.OverlayTransparent),
        ),
      ),
  ) {
    Row(
      modifier = Modifier
        .align(Alignment.TopStart)
        .fillMaxWidth()
        .padding(
          start = BiliSizing.PlayerOverlayHorizontalPadding,
          top = BiliSizing.PlayerTopPadding,
          end = BiliSizing.PlayerOverlayHorizontalPadding,
        ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      LiveControlButton(
        iconRes = R.drawable.ic_player_chevron_left,
        focused = focusedControlIndex == ControlIndexBack,
        onClick = onBack,
      )
      Spacer(modifier = Modifier.width(BiliSpacing.Sm))
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
      ) {
        val displayTitle = convertChineseText(request.title)
        if (displayTitle.isNotBlank()) {
          Text(
            text = displayTitle,
            color = BiliColors.TextPrimary,
            fontSize = BiliTypography.PlayerTitle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        val ownerName = convertChineseText(request.ownerName)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (ownerName.isNotBlank()) {
            PlayerMetaItem(
              iconRes = R.drawable.ic_nav_account,
              text = ownerName,
              modifier = Modifier.weight(1f, fill = false),
            )
          }
          if (request.viewCount > 0) {
            PlayerMetaItem(
              iconRes = R.drawable.ic_video_play_count,
              text = stringResource(R.string.live_meta_online, request.viewCount.formatCompactCountText()),
            )
          }
        }
      }
      if (showQuality) {
        Spacer(modifier = Modifier.width(BiliSpacing.Sm))
        LiveQualityChip(
          description = currentQualityDesc,
          focused = focusedControlIndex == ControlIndexQuality,
          onClick = onOpenQuality,
        )
      }
    }
    ClockOverlay(
      clockText = clockText,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(
          top = BiliSizing.ClockOverlayTopPadding,
          end = BiliSizing.ClockOverlayEndPadding,
        ),
    )
  }
}

@Composable
private fun LiveControlButton(
  iconRes: Int,
  focused: Boolean,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  Box(
    modifier = Modifier
      .size(BiliSizing.PlayerControlIconButtonSize)
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = focused,
        surfaceColor = if (focused) BiliColors.PlayerControlFocused else BiliColors.PlayerControlIdle,
      )
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = null,
      tint = BiliColors.TextPrimary,
      modifier = Modifier.size(BiliSizing.PlayerControlIconSize),
    )
  }
}

@Composable
private fun LiveQualityChip(
  description: String,
  focused: Boolean,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  Row(
    modifier = Modifier
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = focused,
        surfaceColor = if (focused) BiliColors.PlayerControlFocused else BiliColors.PlayerControlIdle,
      )
      .clickable(onClick = onClick)
      .padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painter = painterResource(R.drawable.ic_player_hd),
      contentDescription = null,
      tint = BiliColors.TextPrimary,
      modifier = Modifier.size(BiliSizing.PlayerControlIconSize),
    )
    Spacer(modifier = Modifier.width(BiliSpacing.Sm))
    Text(
      text = convertChineseText(description),
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerMeta,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun LiveQualityPanel(
  qualities: List<LiveQuality>,
  selectedQn: Int,
  focusedQualityIndex: Int,
  onPick: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(BiliColors.OverlayScrim)
      .clickable(onClick = onDismiss),
    contentAlignment = Alignment.Center,
  ) {
    val shape = RoundedCornerShape(BiliRadius.Panel)
    Column(
      modifier = Modifier
        .widthIn(max = BiliSizing.PlayerSettingsPanelWidth)
        .clip(shape)
        .playerLiquidGlassSurface(
          shape = shape,
          focused = false,
          surfaceColor = BiliColors.PlayerPanel,
        ),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(BiliSizing.PlayerSettingsHeaderHeight)
          .padding(horizontal = BiliSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.live_quality),
          color = BiliColors.TextPrimary,
          fontSize = BiliTypography.PlayerPanelTitle,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(BiliSizing.PlayerSettingsDividerHeight)
          .background(BiliColors.PlayerPanelDivider),
      )
      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 420.dp)
          .padding(vertical = BiliSpacing.Xs),
      ) {
        itemsIndexed(qualities, key = { _, quality -> quality.qn }) { index, quality ->
          val selected = quality.qn == selectedQn
          Box(modifier = Modifier.clickable { onPick(quality.qn) }) {
            SettingsRow(
              iconRes = R.drawable.ic_player_hd,
              title = convertChineseText(quality.description.ifBlank { "qn ${quality.qn}" }),
              value = if (selected) stringResource(R.string.player_value_current) else "",
              focused = index == focusedQualityIndex,
              trailingCheck = selected,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LoadingOverlay() {
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator(color = BiliColors.BiliPink)
  }
}

/**
 * IPTV 频道列表侧栏(TVBox 风格,左侧)。确认键弹出/隐藏,上/下移焦点,确认选中切台。
 * 左锚定玻璃面板,复用 [com.kirin.mt.ui.player.PlayerVideoListPanel] 的 scroll-to-focused 机制。
 */
@Composable
private fun IptvChannelListPanel(
  channels: List<IptvChannel>,
  selectedChannelIndex: Int,
  focusedChannelIndex: Int,
  loading: Boolean,
  onSelect: (Int) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val performancePolicy = LocalBiliPerformancePolicy.current
  val shape = RoundedCornerShape(topEnd = BiliRadius.Panel, bottomEnd = BiliRadius.Panel)
  val scrollRevealPaddingPx = with(LocalDensity.current) { BiliSpacing.Sm.roundToPx() }
  LaunchedEffect(focusedChannelIndex, channels.size, scrollRevealPaddingPx) {
    if (channels.isNotEmpty() && focusedChannelIndex >= 0) {
      val target = focusedChannelIndex.coerceIn(0, channels.lastIndex)
      val layoutInfo = listState.layoutInfo
      val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == target }
      if (targetItem == null) {
        if (performancePolicy.smoothScrollingEnabled) {
          listState.animateScrollToItem(target)
        } else {
          listState.scrollToItem(target)
        }
      } else {
        val viewportStart = layoutInfo.viewportStartOffset + scrollRevealPaddingPx
        val viewportEnd = layoutInfo.viewportEndOffset - scrollRevealPaddingPx
        val itemStart = targetItem.offset
        val itemEnd = targetItem.offset + targetItem.size
        val scrollDelta = when {
          itemStart < viewportStart -> itemStart - viewportStart
          itemEnd > viewportEnd -> itemEnd - viewportEnd
          else -> 0
        }
        if (scrollDelta != 0) {
          if (performancePolicy.smoothScrollingEnabled) {
            listState.animateScrollBy(scrollDelta.toFloat())
          } else {
            listState.scroll { scrollBy(scrollDelta.toFloat()) }
          }
        }
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(BiliColors.OverlayScrim)
      .clickable(onClick = onDismiss),
    contentAlignment = Alignment.CenterStart,
  ) {
    Column(
      modifier = Modifier
        .width(BiliSizing.PlayerContentPanelWidth)
        .fillMaxHeight()
        .clip(shape)
        .playerLiquidGlassSurface(
          shape = shape,
          focused = false,
          surfaceColor = BiliColors.PlayerPanel,
        ),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(BiliSizing.PlayerSettingsHeaderHeight)
          .padding(horizontal = BiliSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.live_channel_list),
          color = BiliColors.TextPrimary,
          fontSize = BiliTypography.PlayerPanelTitle,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (!loading && channels.isNotEmpty()) {
          Text(
            text = "${channels.size}",
            color = BiliColors.TextSecondary,
            fontSize = BiliTypography.PlayerMeta,
          )
        }
      }
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(BiliSizing.PlayerSettingsDividerHeight)
          .background(BiliColors.PlayerPanelDivider),
      )
      when {
        loading && channels.isEmpty() -> Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(color = BiliColors.BiliPink)
        }
        channels.isEmpty() -> Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.live_no_channels),
            color = BiliColors.TextSecondary,
            fontSize = BiliTypography.PlayerSettingValue,
          )
        }
        else -> LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        ) {
          itemsIndexed(channels, key = { _, channel -> channel.name }) { index, channel ->
            val focused = index == focusedChannelIndex
            val isCurrent = index == selectedChannelIndex
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .playerLiquidGlassSurface(
                  shape = shape,
                  focused = focused,
                  surfaceColor = if (focused) BiliColors.PlayerControlFocused else BiliColors.PlayerPanel,
                )
                .clickable { onSelect(index) }
                .padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Sm),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                  .size(36.dp)
                  .clip(RoundedCornerShape(BiliRadius.Card)),
                error = ColorPainter(BiliColors.PlayerPanel),
                placeholder = ColorPainter(BiliColors.PlayerPanel),
              )
              Spacer(modifier = Modifier.width(BiliSpacing.Md))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = convertChineseText(channel.name),
                  color = if (isCurrent) BiliColors.BiliPink else BiliColors.TextPrimary,
                  fontSize = BiliTypography.PlayerSettingTitle,
                  fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                if (channel.group.isNotBlank()) {
                  Text(
                    text = convertChineseText(channel.group),
                    color = BiliColors.TextTertiary,
                    fontSize = BiliTypography.PlayerMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
              if (isCurrent) {
                Spacer(modifier = Modifier.width(BiliSpacing.Sm))
                Icon(
                  painter = painterResource(R.drawable.ic_player_check),
                  contentDescription = null,
                  tint = BiliColors.BiliPink,
                  modifier = Modifier.size(20.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * 移动端直播底栏控制条(参考 [com.kirin.mt.ui.mobile.player.MobilePlayerScreen] 底栏):
 * 左侧"直播中"红点指示,右侧画质下拉 + 预留弹幕按钮 + 全屏。直播无进度条/时间,故底栏仅单行控件。
 */
@Composable
private fun LiveBottomBar(
  qualities: List<LiveQuality>,
  selectedQn: Int,
  showQualityMenu: Boolean,
  onToggleQualityMenu: (Boolean) -> Unit,
  onPickQuality: (Int) -> Unit,
  onDanmaku: () -> Unit,
  fullscreen: Boolean,
  onToggleFullscreen: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(Color.Black)
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 直播中红点指示(替代视频底栏的时间/进度条区域)。
      Box(
        modifier = Modifier
          .size(8.dp)
          .clip(CircleShape)
          .background(BiliColors.BiliPink),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = stringResource(R.string.live_indicator),
        color = BiliColors.TextPrimary,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.weight(1f))
      // 画质下拉:HD 图标按钮 + DropdownMenu 列直播可用清晰度。
      if (qualities.isNotEmpty()) {
        Box {
          MobilePlayerIconButton(
            iconRes = R.drawable.ic_player_hd,
            contentDescription = stringResource(R.string.live_quality),
            tint = BiliColors.TextPrimary,
            onClick = { onToggleQualityMenu(!showQualityMenu) },
          )
          DropdownMenu(
            expanded = showQualityMenu,
            onDismissRequest = { onToggleQualityMenu(false) },
            containerColor = Color(0xFF1A1A20),
          ) {
            qualities.forEach { quality ->
              val selected = quality.qn == selectedQn
              DropdownMenuItem(
                text = {
                  Text(
                    text = convertChineseText(quality.description.ifBlank { "qn ${quality.qn}" }),
                    color = if (selected) BiliColors.BiliPink else Color.White,
                  )
                },
                onClick = { onPickQuality(quality.qn) },
              )
            }
          }
        }
        Spacer(modifier = Modifier.width(8.dp))
      }
      // 预留弹幕输入按钮:直播 WebSocket 弹幕未实现,点击提示"暂未开放"占位。
      MobilePlayerIconButton(
        iconRes = R.drawable.ic_player_subtitles,
        contentDescription = stringResource(R.string.player_settings_danmaku),
        tint = BiliColors.TextPrimary,
        onClick = onDanmaku,
      )
      Spacer(modifier = Modifier.width(8.dp))
      // 全屏:强制横屏 + 沉浸(由 LivePlayerScreen 的 DisposableEffect 处理),此按钮只 toggle 状态。
      MobilePlayerIconButton(
        iconRes = if (fullscreen) R.drawable.ic_player_fullscreen_exit else R.drawable.ic_player_fullscreen,
        contentDescription = if (fullscreen) stringResource(R.string.player_fullscreen_exit) else stringResource(R.string.player_fullscreen),
        tint = BiliColors.TextPrimary,
        onClick = onToggleFullscreen,
      )
    }
  }
}

/** 从 Compose Context 逐层解包出 Activity(全屏方向/系统栏控制需要)。镜像 PlayerScreen 同名工具。 */
private tailrec fun android.content.Context.findActivity(): Activity? {
  val ctx = this as? Activity ?: (this as? android.content.ContextWrapper)?.baseContext
  return when (ctx) {
    is Activity -> ctx
    else -> ctx?.findActivity()
  }
}