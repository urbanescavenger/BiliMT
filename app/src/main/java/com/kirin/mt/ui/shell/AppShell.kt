package com.kirin.mt.ui.shell

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import coil.imageLoader
import com.kirin.mt.R
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.cache.AppCacheManager
import com.kirin.mt.core.cache.formatCacheSize
import com.kirin.mt.core.i18n.ChineseTextConverters
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.player.CdnSelector
import com.kirin.mt.core.player.CdnSpeedTester
import com.kirin.mt.core.player.DefaultPlaybackSpeed
import com.kirin.mt.core.player.CodecCapabilityProbe
import com.kirin.mt.core.player.LastPlayedStore
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.SpeedTestUiState
import com.kirin.mt.core.youtube.YoutubeContentLocale
import com.kirin.mt.core.player.DanmakuSettingsStore
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.isWatchCompleted
import com.kirin.mt.core.model.shouldAdvanceToNextHistoryEpisode
import com.kirin.mt.core.model.SourceIptv
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.settings.AppPerformancePolicy
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.settings.supportsLiquidGlassCards
import com.kirin.mt.core.storage.SearchHistoryStore
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.storage.UserSession
import com.kirin.mt.core.update.ApkInstaller
import com.kirin.mt.core.update.UpdateManager
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.ui.feed.UserFeedScreen
import com.kirin.mt.ui.feed.UserFeedUiState
import com.kirin.mt.ui.home.RecommendScreen
import com.kirin.mt.ui.home.RecommendUiState
import com.kirin.mt.ui.glass.LocalLiquidGlassBackdrop
import com.kirin.mt.ui.i18n.LocalChineseTextConverter
import com.kirin.mt.ui.i18n.localizedContext
import com.kirin.mt.ui.login.AccountScreen
import com.kirin.mt.ui.player.PlayerScreen
import com.kirin.mt.ui.search.SearchScreen
import com.kirin.mt.ui.search.SearchUiState
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import com.kirin.mt.ui.settings.SettingsScreen
import com.kirin.mt.ui.space.UpSpaceRequest
import com.kirin.mt.ui.space.UpSpaceScreen
import com.kirin.mt.ui.space.UpSpaceUiState
import com.kirin.mt.ui.space.YoutubeChannelRequest
import com.kirin.mt.ui.space.YoutubeChannelScreen
import com.kirin.mt.ui.space.YoutubeChannelUiState
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliMotion
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.HomeColorScheme
import com.kirin.mt.ui.theme.HomeThemes
import com.kirin.mt.ui.theme.LocalHomeColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

private const val PlaybackFocusRestoreRetryCount = 8
// 视频退出后焦点恢复的清理 backstop 帧数。必须 > TvGridRestoreFocusRetryCount(90)
// + TvGridRestoreFocusWaitLayoutFrames(90) = 180,否则会在 TvVideoGrid 恢复 effect
// 「等布局 + 抢焦点」途中提前清掉 playbackFocusRestoreDestination,令 restoreFocusRequestKeyFor
// 变 0 取消恢复 effect → 长视频后慢布局时焦点停在头像。正常路径恢复 effect 自己调
// onRestoreFocusHandled 清 destination,本 backstop 仅作兜底。
private const val PlaybackFocusRestoreCleanupFrameCount = 240
private const val ExitConfirmWindowMs = 3_000L
private const val FocusLogTag = "BiliMT:Focus"

private fun isConstrainedTvUiDevice(): Boolean {
  val buildValues = listOf(
    Build.HARDWARE,
    Build.BOARD,
    Build.DEVICE,
    Build.PRODUCT,
    Build.MODEL,
    Build.MANUFACTURER,
    buildStringField("SOC_MODEL"),
    buildStringField("SOC_MANUFACTURER"),
  )
  val normalizedValues = buildValues.map { value -> value.orEmpty().lowercase(Locale.ROOT) }
  return normalizedValues.any { value -> value.contains("mt9655") } ||
    (normalizedValues.any { value -> value.contains("xiaomi") } &&
      normalizedValues.any { value -> value.contains("mitv-mffu1") })
}

private fun buildStringField(name: String): String {
  return runCatching {
    Build::class.java.getField(name).get(null) as? String
  }.getOrDefault("").orEmpty()
}

@Composable
fun BiliTvApp(
  videoRepository: VideoRepository,
  liveRepository: com.kirin.mt.core.network.LiveRepository,
  playbackRepository: PlaybackRepository,
  danmakuSettingsStore: DanmakuSettingsStore,
  liveQualityPreferenceStore: com.kirin.mt.core.player.LiveQualityPreferenceStore,
  playbackHttpClient: OkHttpClient,
  codecCapabilityProbe: CodecCapabilityProbe,
  cdnSelector: CdnSelector,
  authRepository: AuthRepository,
  appSettingsStore: AppSettingsStore,
  appCacheManager: AppCacheManager,
  searchHistoryStore: SearchHistoryStore,
  sessionStore: SessionStore,
  youtubeChannelStore: com.kirin.mt.core.youtube.YoutubeChannelStore,
  youtubeHistoryStore: com.kirin.mt.core.youtube.YoutubeHistoryStore,
  youtubeRepository: com.kirin.mt.core.youtube.YoutubeRepository,
  updateManager: UpdateManager,
  apkInstaller: ApkInstaller,
  webdavConfigStore: com.kirin.mt.core.webdav.WebDavConfigStore,
  webdavBackupService: com.kirin.mt.core.webdav.WebDavBackupService,
  iptvRepository: com.kirin.mt.core.network.IptvRepository,
) {
  val settings by appSettingsStore.settings.collectAsState(initial = AppSettings())
  val youtubeChannels by youtubeChannelStore.channels.collectAsState(initial = emptyList())
  val webDavConfig by webdavConfigStore.config.collectAsState(initial = com.kirin.mt.core.webdav.WebDavConfig())
  val updateState by updateManager.state.collectAsState()
  val context = LocalContext.current
  val localizedContext = remember(context, settings.chineseTextVariant) {
    context.localizedContext(settings.chineseTextVariant)
  }
  val textConverter = remember(settings.chineseTextVariant) {
    ChineseTextConverters.forVariant(settings.chineseTextVariant)
  }
  val userSession by sessionStore.session.collectAsState(initial = UserSession())
  val codecCapability = remember(codecCapabilityProbe) { codecCapabilityProbe.probe() }
  val autoConfirmOnFocus = settings.autoConfirmOnFocus
  val autoRefreshOnSwitch = settings.autoConfirmOnFocus && settings.autoRefreshOnSwitch
  val liquidGlassCardsSupported = remember { supportsLiquidGlassCards() }
  val constrainedTvUiDevice = remember { isConstrainedTvUiDevice() }
  val performancePolicy = remember(settings.visualPerformanceMode, settings.liquidGlassCardsEnabled, constrainedTvUiDevice) {
    AppPerformancePolicy.fromSettings(
      settings = settings,
      constrainedTvUi = constrainedTvUiDevice,
    )
  }
  val homeColors = remember(settings.homeThemeVariant) {
    HomeThemes.fromVariant(settings.homeThemeVariant)
  }
  val liquidGlassBackdrop = rememberLayerBackdrop()
  val activeLiquidGlassBackdrop = liquidGlassBackdrop.takeIf {
    performancePolicy.liquidGlassCardsEnabled && liquidGlassCardsSupported
  }
  val effectivePlaybackCodecPreference = if (settings.lowSpecMode) {
    PlaybackCodecPreference.H264
  } else {
    settings.playbackCodecPreference
  }
  val coroutineScope = rememberCoroutineScope()
  val cdnSpeedTester = remember { CdnSpeedTester(playbackHttpClient) }
  val lastPlayedStore = remember { LastPlayedStore(context) }
  var speedTestState by remember { mutableStateOf<SpeedTestUiState>(SpeedTestUiState.Idle) }
  var selectedDestination by rememberSaveable { mutableStateOf(AppDestination.Recommend) }
  var visitedDestinationNames by rememberSaveable { mutableStateOf(setOf(AppDestination.Recommend.name)) }
  var accountSelected by rememberSaveable { mutableStateOf(false) }
  val accountFocusRequester = remember { FocusRequester() }
  val navFocusRequesters = remember {
    AppDestination.entries.associateWith { FocusRequester() }
  }
  val contentFocusRequester = remember { FocusRequester() }
  val recommendTabFocusRequester = remember { FocusRequester() }
  val searchFocusRequester = remember { FocusRequester() }
  val dynamicFocusRequester = remember { FocusRequester() }
  val feedTabFocusRequester = remember { FocusRequester() }
  val settingsFocusRequester = remember { FocusRequester() }
  val pgcFocusRequester = remember { FocusRequester() }
  val pgcTabFocusRequester = remember { FocusRequester() }
  val liveFocusRequester = remember { FocusRequester() }
  val liveTabFocusRequester = remember { FocusRequester() }
  val recommendUiState = remember { RecommendUiState() }
  val userFeedState = remember { UserFeedUiState() }
  val searchUiState = remember { SearchUiState() }
  val liveUiState = remember { com.kirin.mt.ui.live.LiveUiState() }
  var initialHomeFocusPending by remember { mutableStateOf(true) }
  var recommendManualRefreshKey by rememberSaveable { mutableStateOf(0) }
  var dynamicManualRefreshKey by rememberSaveable { mutableStateOf(0) }
  var liveManualRefreshKey by rememberSaveable { mutableStateOf(0) }
  var dynamicUnread by remember { mutableIntStateOf(0) }
  var playbackRequest by remember { mutableStateOf<PlaybackRequest?>(null) }
  var playbackFocusRestoreDestination by remember { mutableStateOf<AppDestination?>(null) }
  var playbackFocusRestoreRequestKey by remember { mutableIntStateOf(0) }
  var contentFocusRestoreDestination by remember { mutableStateOf<AppDestination?>(null) }
  var contentFocusRestoreRequestKey by remember { mutableIntStateOf(0) }
  var lastAppExitBackPressMs by remember { mutableStateOf(0L) }
  var appExitConfirmToast by remember { mutableStateOf<Toast?>(null) }
  var pendingContentFocusDestination by remember { mutableStateOf<AppDestination?>(null) }
  var cacheSizeBytes by remember { mutableStateOf<Long?>(null) }
  var logFiles by remember { mutableStateOf(LogCatcherUtil.allLogFiles()) }
  var isRecordingLog by remember { mutableStateOf(LogCatcherUtil.isRecording) }
  var viewingLogFile by remember { mutableStateOf<java.io.File?>(null) }
  var spaceRequest by remember { mutableStateOf<UpSpaceRequest?>(null) }
  var spaceOrigin by remember { mutableStateOf<SpaceOrigin?>(null) }
  var spacePlaybackBehind by remember { mutableStateOf(false) }
  var spaceFocusRestoreRequestKey by remember { mutableIntStateOf(0) }
  var commentRequest by remember { mutableStateOf<com.kirin.mt.ui.feed.CommentRequest?>(null) }
  val upSpaceUiState = remember { UpSpaceUiState() }
  val spaceFocusRequester = remember { FocusRequester() }
  var youtubeChannelRequest by remember { mutableStateOf<YoutubeChannelRequest?>(null) }
  var channelOrigin by remember { mutableStateOf<SpaceOrigin?>(null) }
  var channelPlaybackBehind by remember { mutableStateOf(false) }
  var channelFocusRestoreRequestKey by remember { mutableIntStateOf(0) }
  val youtubeChannelUiState = remember { YoutubeChannelUiState() }
  val channelFocusRequester = remember { FocusRequester() }
  val pgcUiState = remember { com.kirin.mt.ui.pgc.PgcUiState() }
  var pgcSeasonRequest by remember { mutableStateOf<com.kirin.mt.ui.pgc.PgcSeasonRequest?>(null) }
  var pgcIndexRequest by remember { mutableStateOf<com.kirin.mt.core.model.PgcType?>(null) }
  val pgcIndexFocusRequester = remember { FocusRequester() }
  val pgcSeasonFocusRequester = remember { FocusRequester() }

  LaunchedEffect(performancePolicy.imageMemoryCacheEnabled) {
    if (!performancePolicy.imageMemoryCacheEnabled) {
      context.imageLoader.memoryCache?.clear()
    }
  }

  // YouTube 内容地区(gl/hl)写进进程级 holder,InnerTubeClient.buildContext 在每次请求时读它,
  // 让 gl/hl 跟随设置运行时变化(browse/search/player/SABR 全自动一致,免逐层透传)。
  LaunchedEffect(settings.youtubeContentRegion) {
    YoutubeContentLocale.current = settings.youtubeContentRegion
  }

  // 一次性迁移:把本轮新增的 UGC 分区持久化为启用,之后显隐面板才能正常切换它们的开关。
  LaunchedEffect(Unit) {
    appSettingsStore.ensureHomeSectionsMigration()
  }

  // 每次重启检测排序:显示的(enabled)分区排前、隐藏的排后。
  LaunchedEffect(Unit) {
    appSettingsStore.ensureEnabledSectionsFirst()
  }

  DisposableEffect(Unit) {
    onDispose {
      appExitConfirmToast?.cancel()
    }
  }

  fun showAppExitConfirmToast() {
    appExitConfirmToast?.cancel()
    appExitConfirmToast = Toast.makeText(localizedContext, R.string.app_exit_confirm_toast, Toast.LENGTH_SHORT).also { toast ->
      toast.show()
    }
  }

  fun cancelAppExitConfirmToast() {
    appExitConfirmToast?.cancel()
    appExitConfirmToast = null
  }

  fun refreshCacheSize() {
    coroutineScope.launch {
      cacheSizeBytes = appCacheManager.cacheSizeBytes()
    }
  }

  fun refreshLogFiles() {
    logFiles = LogCatcherUtil.allLogFiles()
    isRecordingLog = LogCatcherUtil.isRecording
  }

  fun requestDestinationFocus(destination: AppDestination): Boolean {
    return runCatching {
      when (destination) {
        AppDestination.Recommend -> recommendTabFocusRequester.requestFocus()
        AppDestination.Search -> searchFocusRequester.requestFocus()
        AppDestination.Dynamic -> feedTabFocusRequester.requestFocus()
        AppDestination.Settings -> settingsFocusRequester.requestFocus()
        AppDestination.Pgc -> pgcTabFocusRequester.requestFocus()
        AppDestination.Live -> liveFocusRequester.requestFocus()
      }
    }.getOrDefault(false)
  }

  fun restoreFocusRequestKeyFor(destination: AppDestination): Int {
    return when {
      playbackFocusRestoreDestination == destination -> playbackFocusRestoreRequestKey
      contentFocusRestoreDestination == destination -> contentFocusRestoreRequestKey
      else -> 0
    }
  }

  fun clearFocusRestoreRequest(destination: AppDestination, key: Int) {
    if (playbackFocusRestoreDestination == destination && key == playbackFocusRestoreRequestKey) {
      playbackFocusRestoreDestination = null
      Log.d(FocusLogTag, "cleared by restore handled: dest=$destination key=$key suppress=off")
    }
    if (contentFocusRestoreDestination == destination && key == contentFocusRestoreRequestKey) {
      contentFocusRestoreDestination = null
      pendingContentFocusDestination = null
    }
  }

  fun AppDestination.usesGridFocusRestore(): Boolean {
    return false
  }

  fun requestContentFocusRestore(destination: AppDestination) {
    if (destination.usesGridFocusRestore()) {
      contentFocusRestoreDestination = destination
      contentFocusRestoreRequestKey += 1
      pendingContentFocusDestination = null
    } else {
      pendingContentFocusDestination = destination
    }
  }

  fun requestManualRefresh(destination: AppDestination) {
    when (destination) {
      AppDestination.Recommend -> recommendManualRefreshKey += 1
      AppDestination.Dynamic -> dynamicManualRefreshKey += 1
      AppDestination.Live -> liveManualRefreshKey += 1
      else -> Unit
    }
  }

  fun selectDestination(destination: AppDestination) {
    // 选中「搜索」总是重置到初始搜索界面(键盘输入视图、清空状态)。
    if (destination == AppDestination.Search) {
      searchUiState.clear()
    }
    accountSelected = false
    val destinationChanged = selectedDestination != destination
    if (destinationChanged) {
      // 用户已手动切换目的地，「启动初始焦点」期结束：避免从其它 tab 切回首页/PGC 时
      // 焦点被 TvVideoGrid 的初始焦点 effect 抢到第一个视频卡片，而非停在侧边栏/胶囊 tab。
      initialHomeFocusPending = false
    } else {
      requestManualRefresh(destination)
    }
    selectedDestination = destination
    visitedDestinationNames = visitedDestinationNames + destination.name
  }

  fun moveIntoDestination(destination: AppDestination): Boolean {
    if (accountSelected) {
      // 在「我的」页时,右移进入设置列表首项(头像已与设置合并)。
      runCatching {
        settingsFocusRequester.requestFocus()
      }
      return true
    }
    if (selectedDestination != destination) {
      selectDestination(destination)
      requestContentFocusRestore(destination)
      return true
    }
    val focused = requestDestinationFocus(destination)
    if (!focused) {
      requestContentFocusRestore(destination)
    }
    return true
  }

  fun VideoSummary.toPlaybackRequest(forceStartPosition: Boolean = false): PlaybackRequest {
    // IPTV 卡片:直链 m3u8,走直播播放器(LivePlayerScreen)的 IPTV 分支,带镜像源列表。
    if (source == SourceIptv) {
      return PlaybackRequest(
        bvid = "",
        cid = 0L,
        title = title,
        ownerName = ownerName,
        coverUrl = pic,
        source = SourceIptv,
        iptvUrls = iptvUrls,
      )
    }
    // 直播卡片:走直播播放(独立 LivePlayerScreen),不带点播字段。
    if (liveRoomId > 0L) {
      return PlaybackRequest(
        bvid = "",
        cid = 0L,
        title = title,
        ownerName = ownerName,
        ownerFace = ownerFace,
        ownerMid = ownerMid,
        coverUrl = pic,
        liveRoomId = liveRoomId,
      )
    }
    val advanceToNextEpisode = shouldAdvanceToNextHistoryEpisode()
    return PlaybackRequest(
      bvid = bvid,
      cid = cid,
      title = title,
      startPositionMs = progress
        .takeIf { progress -> progress > 0 && !isWatchCompleted() && !advanceToNextEpisode }
        ?.times(1000L) ?: 0L,
      ownerName = ownerName,
      ownerFace = ownerFace,
      ownerMid = ownerMid,
      viewCount = view,
      danmakuCount = danmaku,
      pubdate = pubdate,
      forceStartPosition = forceStartPosition,
      historyPage = historyPage,
      advanceToNextHistoryEpisode = advanceToNextEpisode,
      source = source,
    )
  }

  LaunchedEffect(userSession.isLoggedIn) {
    if (userSession.isLoggedIn && accountSelected) {
      // 登录成功后留在「我的」页(头像+设置合并页),把焦点交给设置列表首项。
      runCatching {
        settingsFocusRequester.requestFocus()
      }
    }
  }

  LaunchedEffect(userSession.isLoggedIn, userSession.face, userSession.uname) {
    if (userSession.isLoggedIn && (userSession.face.isNullOrBlank() || userSession.uname.isNullOrBlank())) {
      runCatching {
        authRepository.refreshUserProfile()
      }
    }
  }

  LaunchedEffect(selectedDestination, accountSelected) {
    if (selectedDestination == AppDestination.Settings || accountSelected) {
      refreshCacheSize()
      refreshLogFiles()
    }
  }

  // 拉取动态未读数:登录态变化、切 tab、手动刷新时各拉一次,驱动侧栏 Dynamic 红点。
  LaunchedEffect(userSession.isLoggedIn, selectedDestination, dynamicManualRefreshKey) {
    dynamicUnread = if (userSession.isLoggedIn) {
      runCatching { videoRepository.getDynamicUnread() }.getOrDefault(0)
    } else {
      0
    }
  }

  CompositionLocalProvider(
    LocalContext provides localizedContext,
    LocalBiliPerformancePolicy provides performancePolicy,
    LocalChineseTextConverter provides textConverter,
    LocalHomeColors provides homeColors,
    LocalLiquidGlassBackdrop provides activeLiquidGlassBackdrop,
  ) {
    val activePlaybackRequest = playbackRequest
    var visiblePlaybackRequest by remember { mutableStateOf<PlaybackRequest?>(null) }
    var transitionScrimVisible by remember { mutableStateOf(false) }
    val transitionScrimAlpha by animateFloatAsState(
      targetValue = if (transitionScrimVisible) 1f else 0f,
      animationSpec = tween(
        durationMillis = if (transitionScrimVisible) {
          BiliMotion.PlaybackTransitionScrimInMs
        } else {
          BiliMotion.PlaybackTransitionScrimOutMs
        },
        easing = BiliMotion.FocusEasing,
      ),
      label = "playbackTransitionScrim",
    )
    LaunchedEffect(activePlaybackRequest, pendingContentFocusDestination, selectedDestination, accountSelected) {
      if (activePlaybackRequest != null) {
        return@LaunchedEffect
      }
      val destination = pendingContentFocusDestination ?: return@LaunchedEffect
      if (accountSelected || selectedDestination != destination) {
        return@LaunchedEffect
      }
      repeat(PlaybackFocusRestoreRetryCount) {
        withFrameNanos { }
        if (requestDestinationFocus(destination)) {
          pendingContentFocusDestination = null
          return@LaunchedEffect
        }
      }
    }

    LaunchedEffect(activePlaybackRequest, playbackFocusRestoreDestination, playbackFocusRestoreRequestKey) {
      val restoreDestination = playbackFocusRestoreDestination
      val restoreRequestKey = playbackFocusRestoreRequestKey
      if (activePlaybackRequest == null && restoreDestination != null && restoreRequestKey > 0) {
        repeat(PlaybackFocusRestoreCleanupFrameCount) {
          withFrameNanos { }
        }
        if (playbackFocusRestoreDestination == restoreDestination && playbackFocusRestoreRequestKey == restoreRequestKey) {
          playbackFocusRestoreDestination = null
          Log.w(
            FocusLogTag,
            "backstop cleared restoreDest=$restoreDestination after $PlaybackFocusRestoreCleanupFrameCount frames " +
              "(restore effect never confirmed focus → focus likely stayed on avatar)",
          )
        }
      }
    }

    LaunchedEffect(activePlaybackRequest) {
      if (activePlaybackRequest == visiblePlaybackRequest) {
        transitionScrimVisible = false
        return@LaunchedEffect
      }
      transitionScrimVisible = true
      delay(BiliMotion.PlaybackTransitionScrimInMs.toLong())
      visiblePlaybackRequest = activePlaybackRequest
      delay(BiliMotion.PlaybackTransitionScrimHoldMs.toLong())
      if (playbackRequest == activePlaybackRequest) {
        transitionScrimVisible = false
      }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      if (visiblePlaybackRequest == null) {
        Box(modifier = Modifier.fillMaxSize()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .then(
              if (activeLiquidGlassBackdrop != null) {
                Modifier.layerBackdrop(liquidGlassBackdrop)
              } else {
                Modifier
              },
            ),
        ) {
          HomeAppBackground(
            colors = homeColors,
            refinedVisualsEnabled = performancePolicy.refinedVisualEffectsEnabled,
            cinematicVisualsEnabled = performancePolicy.cinematicVisualEffectsEnabled,
          )
        }
        BackHandler(enabled = activePlaybackRequest == null) {
          val now = SystemClock.elapsedRealtime()
          if (now - lastAppExitBackPressMs <= ExitConfirmWindowMs) {
            cancelAppExitConfirmToast()
            context.findActivity()?.finish()
          } else {
            lastAppExitBackPressMs = now
            showAppExitConfirmToast()
          }
        }
        Row(
          modifier = Modifier.fillMaxSize(),
        ) {
          // 视频退出后恢复窗口内抑制头像 autoConfirm,避免焦点被头像抢占并打开「我的」页,
          // 让 TvVideoGrid 的网格恢复 effect 有机会把焦点拉回原视频卡片。
          val suppressAccountAutoConfirm = playbackFocusRestoreDestination != null
          AppSidebar(
            selectedDestination = selectedDestination,
            accountSelected = accountSelected,
            userSession = userSession,
            autoConfirmOnFocus = autoConfirmOnFocus,
            suppressAccountAutoConfirm = suppressAccountAutoConfirm,
            accountFocusRequester = accountFocusRequester,
            navFocusRequesters = navFocusRequesters,
            dynamicUnread = dynamicUnread,
            onAccountSelected = {
              accountSelected = true
            },
            onDestinationSelected = { destination ->
              selectDestination(destination)
            },
            shouldAutoConfirmDestination = { destination ->
              autoConfirmOnFocus || destination.name !in visitedDestinationNames
            },
            onMoveRight = { destination ->
              moveIntoDestination(destination)
            },
          )
          Box(
            modifier = Modifier
              .fillMaxSize()
              .then(
                if (!accountSelected && selectedDestination == AppDestination.Search) {
                  Modifier
                } else {
                  Modifier.padding(BiliSizing.ContentPadding)
                },
              ),
          ) {
            // 「我的」页 = 账号区(置顶) + 设置区(下方),设置区复用 SettingsScreen 的焦点/滚动体系。
            // 此 lambda 同时供 accountSelected 的「我的」页与(不可达的)Settings 目的地分支使用,
            // onMoveLeftToNav 在 accountSelected 时回到侧栏头像、否则回到对应 nav 项。
            val settingsContent: @Composable (Modifier) -> Unit = { mod ->
              SettingsScreen(
                modifier = mod,
                settings = settings,
                cacheSizeText = cacheSizeBytes?.let(::formatCacheSize) ?: stringResource(R.string.settings_clear_cache_calculating),
                codecCapability = codecCapability,
                firstItemFocusRequester = settingsFocusRequester,
                onMoveLeftToNav = {
                  runCatching {
                    if (accountSelected) {
                      accountFocusRequester.requestFocus()
                    } else {
                      navFocusRequesters.getValue(selectedDestination).requestFocus()
                    }
                  }.isSuccess
                },
                onVisualPerformanceModeChange = { mode ->
                  coroutineScope.launch {
                    appSettingsStore.setVisualPerformanceMode(mode)
                  }
                },
                liquidGlassCardsSupported = liquidGlassCardsSupported,
                onLiquidGlassCardsEnabledChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setLiquidGlassCardsEnabled(enabled)
                  }
                },
                onHomeThemeVariantChange = { variant ->
                  coroutineScope.launch {
                    appSettingsStore.setHomeThemeVariant(variant)
                  }
                },
                onChineseTextVariantChange = { variant ->
                  coroutineScope.launch {
                    appSettingsStore.setChineseTextVariant(variant)
                  }
                },
                onClearCache = {
                  coroutineScope.launch {
                    val result = appCacheManager.clearCache()
                    cacheSizeBytes = appCacheManager.cacheSizeBytes()
                    refreshLogFiles()
                    Toast.makeText(
                      localizedContext,
                      localizedContext.getString(R.string.settings_clear_cache_done, formatCacheSize(result.clearedBytes)),
                      Toast.LENGTH_SHORT,
                    ).show()
                  }
                },
                onSeekPreviewSpritesEnabledChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setSeekPreviewSpritesEnabled(enabled)
                  }
                },
                onPlaybackQualityPreferenceChange = { preference ->
                  coroutineScope.launch {
                    appSettingsStore.setPlaybackQualityPreference(preference)
                  }
                },
                onYoutubeDefaultQualityChange = { quality ->
                  coroutineScope.launch {
                    appSettingsStore.setYoutubeDefaultQuality(quality)
                  }
                },
                onYoutubeContentRegionChange = { region ->
                  coroutineScope.launch {
                    appSettingsStore.setYoutubeContentRegion(region)
                  }
                },
                onDefaultPlaybackSpeedChange = { speed ->
                  coroutineScope.launch {
                    appSettingsStore.setDefaultPlaybackSpeed(speed)
                  }
                },
                onPlaybackCodecPreferenceChange = { preference ->
                  coroutineScope.launch {
                    appSettingsStore.setPlaybackCodecPreference(preference)
                  }
                },
                onPlaybackCdnPreferenceChange = { preference ->
                  coroutineScope.launch {
                    appSettingsStore.setPlaybackCdnPreference(preference)
                  }
                },
                onAirJumpAssistantEnabledChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setAirJumpAssistantEnabled(enabled)
                  }
                },
                onConfirmPlaybackExitChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setConfirmPlaybackExit(enabled)
                  }
                },
                onAutoPlayNextEpisodeChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setAutoPlayNextEpisode(enabled)
                  }
                },
                onAutoPlayRelatedVideoChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setAutoPlayRelatedVideo(enabled)
                  }
                },
                onAutoReturnHomeOnCompletionChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setAutoReturnHomeOnCompletion(enabled)
                  }
                },
                onShowClockChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setShowClock(enabled)
                  }
                },
                onShowMiniProgressBarChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setShowMiniProgressBar(enabled)
                  }
                },
                onPlayerLogOverlayEnabledChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setPlayerLogOverlayEnabled(enabled)
                  }
                },
                onAutoConfirmOnFocusChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setAutoConfirmOnFocus(enabled)
                  }
                },
                onAutoRefreshOnSwitchChange = { enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setAutoRefreshOnSwitch(enabled)
                  }
                },
                onHomeSectionEnabledChange = { section, enabled ->
                  coroutineScope.launch {
                    appSettingsStore.setHomeSectionEnabled(section, enabled)
                  }
                },
                onHomeSectionsOrderChange = { order ->
                  coroutineScope.launch {
                    appSettingsStore.setHomeSectionsOrder(order)
                  }
                },
                logFiles = logFiles,
                isRecordingLog = isRecordingLog,
                viewingLogFile = viewingLogFile,
                onViewLog = { info ->
                  viewingLogFile = info.file
                },
                onBackFromLogView = {
                  viewingLogFile = null
                },
                onShareLog = { info ->
                  LogCatcherUtil.shareLogFile(context, info.file)
                },
                onToggleLogRecording = {
                  coroutineScope.launch {
                    if (LogCatcherUtil.isRecording) {
                      val file = LogCatcherUtil.stopManualRecording()
                      refreshLogFiles()
                      Toast.makeText(
                        localizedContext,
                        localizedContext.getString(R.string.settings_logs_recording_stopped, file?.name ?: ""),
                        Toast.LENGTH_SHORT,
                      ).show()
                    } else {
                      val started = LogCatcherUtil.startManualRecording()
                      isRecordingLog = LogCatcherUtil.isRecording
                      val message = if (started) {
                        R.string.settings_logs_recording_started
                      } else {
                        R.string.settings_logs_recording_failed
                      }
                      Toast.makeText(localizedContext, message, Toast.LENGTH_SHORT).show()
                    }
                  }
                },
                updateState = updateState,
                onCheckUpdate = {
                  coroutineScope.launch {
                    updateManager.refresh()
                  }
                },
                onDownloadUpdate = {
                  coroutineScope.launch {
                    try {
                      updateManager.download()
                    } catch (e: Exception) {
                      Toast.makeText(
                        localizedContext,
                        localizedContext.getString(R.string.settings_update_download_failed_with_message, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                      ).show()
                    }
                  }
                },
                onInstallUpdate = {
                  val file = updateManager.downloadedFile()
                  val activity = context.findActivity()
                  if (file != null && activity != null) {
                    val result = apkInstaller.startInstall(activity, file)
                    when (result) {
                      is com.kirin.mt.core.update.InstallResult.NeedsUnknownSourcesPermission -> {
                        context.startActivity(apkInstaller.buildUnknownSourcesIntent())
                        Toast.makeText(
                          localizedContext,
                          R.string.settings_update_install_unknown_sources_required,
                          Toast.LENGTH_LONG,
                        ).show()
                      }
                      is com.kirin.mt.core.update.InstallResult.Failed -> {
                        Toast.makeText(
                          localizedContext,
                          localizedContext.getString(R.string.settings_update_failed_with_message, result.message),
                          Toast.LENGTH_SHORT,
                        ).show()
                      }
                      else -> Unit
                    }
                  }
                },
                onOpenReleaseNotes = {
                  val url = (updateState.status as? com.kirin.mt.core.update.UpdateUiState.Status.Available)?.info?.releaseUrl
                    ?: (updateState.status as? com.kirin.mt.core.update.UpdateUiState.Status.Downloaded)?.info?.releaseUrl
                  if (!url.isNullOrEmpty()) {
                    runCatching {
                      context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                          .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                      )
                    }
                  }
                },
                speedTestState = speedTestState,
                onRunSpeedTest = {
                  if (speedTestState is SpeedTestUiState.Running) {
                    return@SettingsScreen
                  }
                  speedTestState = SpeedTestUiState.Running
                  coroutineScope.launch {
                    val last = lastPlayedStore.load()
                    if (last == null) {
                      speedTestState = SpeedTestUiState.NoLastVideo
                      return@launch
                    }
                    val resolveStartNs = System.nanoTime()
                    val info = runCatching {
                      playbackRepository.getPlaybackInfo(
                        request = PlaybackRequest(bvid = last.bvid, cid = last.cid, title = ""),
                        codecPreference = effectivePlaybackCodecPreference,
                        qualityPreference = settings.playbackQualityPreference,
                      )
                    }.getOrNull()
                    val playurlResolveMs = (System.nanoTime() - resolveStartNs) / 1_000_000L
                    if (info == null || info.videoTracks.isEmpty()) {
                      speedTestState = SpeedTestUiState.Failed
                      return@launch
                    }
                    // Use the exact same candidate set the player would consider
                    // (CdnRewriter + isEligibleCandidate applied), so for a non-Auto
                    // preference we only measure the host the player will actually use.
                    val cdnPreference = settings.playbackCdnPreference
                    val candidates = (info.videoTracks + info.audioTracks)
                      .flatMap { cdnSelector.candidatesFor(it, cdnPreference) }
                      // De-dup by host: video/audio/multi-quality tracks often
                      // share a CDN host with different signed URLs; measuring
                      // the same host multiple times wastes probe slots and
                      // shows duplicate rows. Keep one representative URL
                      // per host.
                      .distinctBy { it.toHttpUrlOrNull()?.host ?: it }
                    val results = cdnSpeedTester.measure(candidates, CdnSpeedTester.MeasureOptions.Dialog)
                    // Pre-warm the CdnSelector cache per track so the next open of
                    // the same video hits "Using cached CDN selection" and skips the
                    // inline measurement on the live playback path.
                    if (results.isNotEmpty()) {
                      info.videoTracks.forEach { cdnSelector.applyMeasurements(it, cdnPreference, results) }
                      info.audioTracks.forEach { cdnSelector.applyMeasurements(it, cdnPreference, results) }
                    }
                    speedTestState = if (results.isEmpty()) {
                      SpeedTestUiState.Failed
                    } else {
                      SpeedTestUiState.Succeeded(
                        results = results,
                        sourceLabel = info.title.takeIf { it.isNotBlank() } ?: last.bvid,
                        playurlResolveMs = playurlResolveMs,
                      )
                    }
                  }
                },
                onDismissSpeedTest = {
                  speedTestState = SpeedTestUiState.Idle
                },
                channels = youtubeChannels,
                onAddYoutubeChannel = { input ->
                  runCatching {
                    val channel = youtubeRepository.resolveChannel(input)
                    youtubeChannelStore.add(channel)
                  }.isSuccess
                },
                onRemoveYoutubeChannel = { channelId ->
                  runCatching { youtubeChannelStore.remove(channelId) }.isSuccess
                },
                webDavConfig = webDavConfig,
                onWebDavConfigChange = { cfg ->
                  com.kirin.mt.core.webdav.validateAndSaveWebDavConfig(
                    store = webdavConfigStore,
                    ping = { url -> webdavBackupService.ping(url, cfg.username, cfg.password) },
                    config = cfg,
                  )
                },
                onWebDavBackup = { cfg -> webdavBackupService.backup(cfg) },
                onWebDavRestore = { cfg -> webdavBackupService.restore(cfg) },
                onIptvSourceConfigChange = { url, username, password ->
                  coroutineScope.launch {
                    appSettingsStore.setIptvSourceUrl(url)
                    appSettingsStore.setIptvSourceUsername(username)
                    appSettingsStore.setIptvSourcePassword(password)
                    // 保存后校验连通性,成功/失败都提示。
                    val reachable = iptvRepository.checkSourceReachable(url, username, password)
                    Toast.makeText(
                      localizedContext,
                      if (reachable) {
                        R.string.settings_iptv_connect_success
                      } else {
                        R.string.settings_iptv_connect_failed
                      },
                      Toast.LENGTH_SHORT,
                    ).show()
                  }
                },
              )
            }
            if (accountSelected) {
              Column(Modifier.fillMaxSize()) {
                AccountScreen(
                  userSession = userSession,
                  authRepository = authRepository,
                  modifier = Modifier.fillMaxWidth(),
                )
                settingsContent(Modifier.weight(1f))
              }
            } else {
              when (selectedDestination) {
                AppDestination.Recommend -> RecommendScreen(
                  videoRepository = videoRepository,
                  uiState = recommendUiState,
                  firstItemFocusRequester = contentFocusRequester,
                  tabFocusRequester = recommendTabFocusRequester,
                  enabledHomeSections = settings.enabledHomeSections,
                  homeSectionsOrder = settings.homeSectionsOrder,
                  autoConfirmOnFocus = autoConfirmOnFocus,
                  autoRefreshOnSwitch = autoRefreshOnSwitch,
                  manualRefreshKey = recommendManualRefreshKey,
                  restoreFocusRequestKey = restoreFocusRequestKeyFor(AppDestination.Recommend),
                  onRestoreFocusHandled = { key -> clearFocusRestoreRequest(AppDestination.Recommend, key) },
                  requestInitialFocus = initialHomeFocusPending,
                  onInitialFocusRequested = {
                    initialHomeFocusPending = false
                  },
                  onMoveLeftToNav = {
                    runCatching {
                      if (accountSelected) {
                        accountFocusRequester.requestFocus()
                      } else {
                        navFocusRequesters.getValue(selectedDestination).requestFocus()
                      }
                    }.isSuccess
                  },
                  onVideoSelected = { video ->
                    playbackRequest = video.toPlaybackRequest()
                  },
                  onOwnerSelected = { video ->
                    if (video.source == SourceYoutube && video.channelId.isNotBlank()) {
                      youtubeChannelUiState.reset()
                      channelOrigin = SpaceOrigin.Content
                      channelPlaybackBehind = false
                      youtubeChannelRequest = YoutubeChannelRequest(
                        channelId = video.channelId,
                        channelName = video.ownerName,
                        avatar = video.ownerFace,
                      )
                    } else {
                      upSpaceUiState.reset()
                      spaceOrigin = SpaceOrigin.Content
                      spacePlaybackBehind = false
                      spaceRequest = UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
                    }
                  },
                )
                AppDestination.Search -> SearchScreen(
                  videoRepository = videoRepository,
                  searchHistoryStore = searchHistoryStore,
                  uiState = searchUiState,
                  firstItemFocusRequester = searchFocusRequester,
                  restoreFocusRequestKey = restoreFocusRequestKeyFor(AppDestination.Search),
                  onRestoreFocusHandled = { key -> clearFocusRestoreRequest(AppDestination.Search, key) },
                  onMoveLeftToNav = {
                    runCatching {
                      if (accountSelected) {
                        accountFocusRequester.requestFocus()
                      } else {
                        navFocusRequesters.getValue(selectedDestination).requestFocus()
                      }
                    }.isSuccess
                  },
                  onVideoSelected = { video ->
                    playbackRequest = video.toPlaybackRequest()
                  },
                  onOwnerSelected = { video ->
                    if (video.source == SourceYoutube && video.channelId.isNotBlank()) {
                      youtubeChannelUiState.reset()
                      channelOrigin = SpaceOrigin.Content
                      channelPlaybackBehind = false
                      youtubeChannelRequest = YoutubeChannelRequest(
                        channelId = video.channelId,
                        channelName = video.ownerName,
                        avatar = video.ownerFace,
                      )
                    } else {
                      upSpaceUiState.reset()
                      spaceOrigin = SpaceOrigin.Content
                      spacePlaybackBehind = false
                      spaceRequest = UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
                    }
                  },
                )
                AppDestination.Dynamic -> UserFeedScreen(
                  videoRepository = videoRepository,
                  youtubeChannelStore = youtubeChannelStore,
                  youtubeHistoryStore = youtubeHistoryStore,
                  isLoggedIn = userSession.isLoggedIn,
                  feedState = userFeedState,
                  autoRefreshOnSwitch = autoRefreshOnSwitch,
                  manualRefreshKey = dynamicManualRefreshKey,
                  firstItemFocusRequester = dynamicFocusRequester,
                  tabFocusRequester = feedTabFocusRequester,
                  restoreFocusRequestKey = restoreFocusRequestKeyFor(AppDestination.Dynamic),
                  onRestoreFocusHandled = { key -> clearFocusRestoreRequest(AppDestination.Dynamic, key) },
                  onMoveLeftToNav = {
                    runCatching {
                      navFocusRequesters.getValue(selectedDestination).requestFocus()
                    }.isSuccess
                  },
                  onVideoSelected = { video, forceStart ->
                    playbackRequest = video.toPlaybackRequest(forceStartPosition = forceStart)
                  },
                  onOwnerSelected = { video ->
                    if (video.source == SourceYoutube && video.channelId.isNotBlank()) {
                      youtubeChannelUiState.reset()
                      channelOrigin = SpaceOrigin.Content
                      channelPlaybackBehind = false
                      youtubeChannelRequest = YoutubeChannelRequest(
                        channelId = video.channelId,
                        channelName = video.ownerName,
                        avatar = video.ownerFace,
                      )
                    } else {
                      upSpaceUiState.reset()
                      spaceOrigin = SpaceOrigin.Content
                      spacePlaybackBehind = false
                      spaceRequest = UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
                    }
                  },
                  onCommentSelected = { video ->
                    commentRequest = com.kirin.mt.ui.feed.CommentRequest(
                      aid = video.aid,
                      title = video.title,
                    )
                  },
                  onSeasonSelected = { season ->
                    pgcSeasonRequest = com.kirin.mt.ui.pgc.PgcSeasonRequest(
                      seasonId = season.seasonId,
                      epId = season.firstEpId,
                    )
                  },
                )
                AppDestination.Settings -> settingsContent(Modifier.fillMaxSize())
                AppDestination.Pgc -> com.kirin.mt.ui.pgc.PgcScreen(
                  videoRepository = videoRepository,
                  uiState = pgcUiState,
                  firstItemFocusRequester = pgcFocusRequester,
                  tabFocusRequester = pgcTabFocusRequester,
                  onMoveDownFromTab = {
                    runCatching { pgcFocusRequester.requestFocus() }.isSuccess
                  },
                  onMoveLeftToNav = {
                    runCatching {
                      navFocusRequesters.getValue(selectedDestination).requestFocus()
                    }.isSuccess
                  },
                  onSeasonSelected = { summary ->
                    pgcSeasonRequest = com.kirin.mt.ui.pgc.PgcSeasonRequest(
                      seasonId = summary.seasonId,
                      epId = summary.episodeId,
                    )
                  },
                  onOpenIndex = { type ->
                    pgcIndexRequest = type
                  },
                  requestInitialFocus = initialHomeFocusPending,
                  onInitialFocusRequested = {
                    initialHomeFocusPending = false
                  },
                )
                AppDestination.Live -> com.kirin.mt.ui.live.LiveScreen(
                  liveRepository = liveRepository,
                  iptvRepository = iptvRepository,
                  uiState = liveUiState,
                  firstItemFocusRequester = liveFocusRequester,
                  tabFocusRequester = liveTabFocusRequester,
                  manualRefreshKey = liveManualRefreshKey,
                  restoreFocusRequestKey = restoreFocusRequestKeyFor(AppDestination.Live),
                  onRestoreFocusHandled = { key -> clearFocusRestoreRequest(AppDestination.Live, key) },
                  requestInitialFocus = initialHomeFocusPending,
                  onInitialFocusRequested = {
                    initialHomeFocusPending = false
                  },
                  onMoveLeftToNav = {
                    runCatching {
                      if (accountSelected) {
                        accountFocusRequester.requestFocus()
                      } else {
                        navFocusRequesters.getValue(selectedDestination).requestFocus()
                      }
                    }.isSuccess
                  },
                  onVideoSelected = { video ->
                    playbackRequest = video.toPlaybackRequest()
                  },
                )
              }
            }
          }
        }
      }
      }
      val displayedPgcIndexType = pgcIndexRequest
      if (displayedPgcIndexType != null && visiblePlaybackRequest == null) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(BiliColors.VideoBlack),
        ) {
          com.kirin.mt.ui.pgc.PgcIndexScreen(
            videoRepository = videoRepository,
            pgcType = displayedPgcIndexType,
            firstItemFocusRequester = pgcIndexFocusRequester,
            onBack = {
              pgcIndexRequest = null
              true
            },
            onSeasonSelected = { summary ->
              pgcSeasonRequest = com.kirin.mt.ui.pgc.PgcSeasonRequest(
                seasonId = summary.seasonId,
                epId = summary.episodeId,
              )
            },
          )
        }
      }
      val displayedPgcSeasonRequest = pgcSeasonRequest
      if (displayedPgcSeasonRequest != null && visiblePlaybackRequest == null) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(BiliColors.VideoBlack),
        ) {
          com.kirin.mt.ui.pgc.PgcSeasonScreen(
            videoRepository = videoRepository,
            request = displayedPgcSeasonRequest,
            firstItemFocusRequester = pgcSeasonFocusRequester,
            playerLogOverlayEnabled = settings.playerLogOverlayEnabled,
            onBack = {
              pgcSeasonRequest = null
              true
            },
            onPlayEpisode = { season, ep ->
              val startMs = season.progress
                ?.takeIf { it.lastEpId == ep.id }
                ?.lastTime
                ?.let { it * 1000L }
                ?: 0L
              playbackRequest = com.kirin.mt.core.player.PlaybackRequest(
                bvid = ep.bvid,
                cid = ep.cid,
                aid = ep.aid,
                title = season.title,
                startPositionMs = startMs,
                epId = ep.id.toLong(),
                seasonId = season.seasonId.toLong(),
                subType = season.type,
                forceStartPosition = startMs > 0L,
              )
            },
          )
        }
      }
      val displayedPlaybackRequest = visiblePlaybackRequest
      if (displayedPlaybackRequest != null) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(BiliColors.VideoBlack),
        ) {
          if (displayedPlaybackRequest.isLive || displayedPlaybackRequest.isIptv) {
            com.kirin.mt.ui.player.LivePlayerScreen(
              request = displayedPlaybackRequest,
              playbackRepository = playbackRepository,
              playbackHttpClient = playbackHttpClient,
              liveQualityPreferenceStore = liveQualityPreferenceStore,
              iptvRepository = iptvRepository,
              onBack = {
                playbackFocusRestoreDestination = selectedDestination
                playbackRequest = null
                playbackFocusRestoreRequestKey += 1
                Log.d(FocusLogTag, "live exit: restoreDest=$selectedDestination suppress=on restoreKey=$playbackFocusRestoreRequestKey")
              },
            )
          } else {
            PlayerScreen(
              request = displayedPlaybackRequest,
              videoRepository = videoRepository,
              playbackRepository = playbackRepository,
              youtubeHistoryStore = youtubeHistoryStore,
              danmakuSettingsStore = danmakuSettingsStore,
              playbackHttpClient = playbackHttpClient,
              cdnSelector = cdnSelector,
              playbackCodecPreference = effectivePlaybackCodecPreference,
              playbackQualityPreference = settings.playbackQualityPreference,
              youtubeDefaultQuality = settings.youtubeDefaultQuality,
              defaultPlaybackSpeed = settings.defaultPlaybackSpeed,
              playbackCdnPreference = settings.playbackCdnPreference,
              seekPreviewSpritesEnabled = settings.seekPreviewSpritesEnabled,
              airJumpAssistantEnabled = settings.airJumpAssistantEnabled,
              confirmPlaybackExit = settings.confirmPlaybackExit,
              autoPlayNextEpisode = settings.autoPlayNextEpisode,
              autoPlayRelatedVideo = settings.autoPlayRelatedVideo,
              autoReturnHomeOnCompletion = settings.autoReturnHomeOnCompletion,
              showClock = settings.showClock,
              showMiniProgressBar = settings.showMiniProgressBar,
              playerLogOverlayEnabled = settings.playerLogOverlayEnabled,
              onBack = {
                if (spaceRequest != null && spaceOrigin == SpaceOrigin.Content) {
                  // 从 UP 主页(内容来源)起播:返回时可见层是 UpSpace 网格,arm 它的 restore
                  playbackRequest = null
                  spaceFocusRestoreRequestKey += 1
                  Log.d(FocusLogTag, "video exit via upSpace(content): spaceRestoreKey bumped, no playback restore")
                } else if (youtubeChannelRequest != null && channelOrigin == SpaceOrigin.Content) {
                  // 从 YouTube 频道页(内容来源)起播:返回时可见层是频道网格,arm 它的 restore
                  playbackRequest = null
                  channelFocusRestoreRequestKey += 1
                  Log.d(FocusLogTag, "video exit via youtubeChannel(content): channelRestoreKey bumped")
                } else {
                  playbackFocusRestoreDestination = selectedDestination
                  playbackRequest = null
                  playbackFocusRestoreRequestKey += 1
                  Log.d(FocusLogTag, "video exit: restoreDest=$selectedDestination suppress=on restoreKey=$playbackFocusRestoreRequestKey")
                }
              },
              onOpenUpSpace = { mid, ownerName, ownerFace ->
                upSpaceUiState.reset()
                spaceOrigin = SpaceOrigin.Player
                spacePlaybackBehind = true
                spaceRequest = UpSpaceRequest(mid, ownerName, ownerFace)
              },
              spaceReturnKey = spaceFocusRestoreRequestKey,
            )
          }
        }
      }
      val displayedSpaceRequest = spaceRequest
      if (displayedSpaceRequest != null &&
        (visiblePlaybackRequest == null || (spaceOrigin == SpaceOrigin.Player && spacePlaybackBehind))
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(BiliColors.VideoBlack),
        ) {
          UpSpaceScreen(
            request = displayedSpaceRequest,
            videoRepository = videoRepository,
            isLoggedIn = userSession.isLoggedIn,
            uiState = upSpaceUiState,
            firstItemFocusRequester = spaceFocusRequester,
            restoreFocusRequestKey = spaceFocusRestoreRequestKey,
            onRestoreFocusHandled = { key ->
              if (key == spaceFocusRestoreRequestKey) spaceFocusRestoreRequestKey = 0
            },
            onBack = {
              spaceRequest = null
              val origin = spaceOrigin
              spaceOrigin = null
              spacePlaybackBehind = false
              when (origin) {
                SpaceOrigin.Player -> spaceFocusRestoreRequestKey += 1
                SpaceOrigin.Content -> requestContentFocusRestore(selectedDestination)
                else -> Unit
              }
              true
            },
            onVideoSelected = { video ->
              spacePlaybackBehind = false
              playbackRequest = video.toPlaybackRequest()
            },
          )
        }
      }
      val displayedYoutubeChannelRequest = youtubeChannelRequest
      if (displayedYoutubeChannelRequest != null &&
        (visiblePlaybackRequest == null || (channelOrigin == SpaceOrigin.Player && channelPlaybackBehind))
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(BiliColors.VideoBlack),
        ) {
          YoutubeChannelScreen(
            request = displayedYoutubeChannelRequest,
            youtubeRepository = youtubeRepository,
            youtubeChannelStore = youtubeChannelStore,
            uiState = youtubeChannelUiState,
            firstItemFocusRequester = channelFocusRequester,
            restoreFocusRequestKey = channelFocusRestoreRequestKey,
            onRestoreFocusHandled = { key ->
              if (key == channelFocusRestoreRequestKey) channelFocusRestoreRequestKey = 0
            },
            onBack = {
              youtubeChannelRequest = null
              val origin = channelOrigin
              channelOrigin = null
              channelPlaybackBehind = false
              when (origin) {
                SpaceOrigin.Player -> channelFocusRestoreRequestKey += 1
                SpaceOrigin.Content -> requestContentFocusRestore(selectedDestination)
                else -> Unit
              }
              true
            },
            onVideoSelected = { video ->
              channelPlaybackBehind = false
              playbackRequest = video.toPlaybackRequest()
            },
          )
        }
      }
      if (transitionScrimAlpha > 0.01f) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(BiliColors.VideoBlack.copy(alpha = transitionScrimAlpha)),
        )
      }

      commentRequest?.let { request ->
        com.kirin.mt.ui.feed.CommentScreen(
          aid = request.aid,
          title = request.title,
          videoRepository = videoRepository,
          onDismiss = { commentRequest = null },
        )
      }
    }
  }
}

private enum class SpaceOrigin { Player, Content }

private tailrec fun Context.findActivity(): Activity? {
  return when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }
}

@Composable
private fun HomeAppBackground(
  colors: HomeColorScheme,
  refinedVisualsEnabled: Boolean,
  cinematicVisualsEnabled: Boolean,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(colors.backgroundTop, colors.backgroundBottom),
        ),
      ),
  ) {
    if (cinematicVisualsEnabled) {
      val drift = BiliFocus.HomeBackgroundCinematicDrift
      Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = maxOf(size.width, size.height)
        drawRect(
          brush = Brush.verticalGradient(
            colors = listOf(
              colors.backgroundTop,
              colors.backgroundBottom,
              colors.cardSurface.copy(alpha = BiliFocus.HomeBackgroundCinematicCardSurfaceAlpha),
            ),
          ),
        )
        drawRect(
          brush = Brush.radialGradient(
            colors = listOf(colors.ambientA.copy(alpha = BiliFocus.HomeBackgroundCinematicAmbientAAlpha), BiliColors.Transparent),
            center = Offset(
              x = size.width * (BiliFocus.HomeBackgroundCinematicAmbientAX + drift * BiliFocus.HomeBackgroundCinematicAmbientADriftX),
              y = size.height * (BiliFocus.HomeBackgroundCinematicAmbientAY + drift * BiliFocus.HomeBackgroundCinematicAmbientADriftY),
            ),
            radius = radius * BiliFocus.HomeBackgroundCinematicAmbientARadius,
          ),
        )
        drawRect(
          brush = Brush.radialGradient(
            colors = listOf(colors.ambientB.copy(alpha = BiliFocus.HomeBackgroundCinematicAmbientBAlpha), BiliColors.Transparent),
            center = Offset(
              x = size.width * (BiliFocus.HomeBackgroundCinematicAmbientBX - drift * BiliFocus.HomeBackgroundCinematicAmbientBDriftX),
              y = size.height * (BiliFocus.HomeBackgroundCinematicAmbientBY + drift * BiliFocus.HomeBackgroundCinematicAmbientBDriftY),
            ),
            radius = radius * BiliFocus.HomeBackgroundCinematicAmbientBRadius,
          ),
        )
        drawRect(
          brush = Brush.radialGradient(
            colors = listOf(colors.ambientA.copy(alpha = BiliFocus.HomeBackgroundCinematicAmbientCAlpha), BiliColors.Transparent),
            center = Offset(
              x = size.width * (BiliFocus.HomeBackgroundCinematicAmbientCX + drift * BiliFocus.HomeBackgroundCinematicAmbientCDriftX),
              y = size.height * (BiliFocus.HomeBackgroundCinematicAmbientCY - drift * BiliFocus.HomeBackgroundCinematicAmbientCDriftY),
            ),
            radius = radius * BiliFocus.HomeBackgroundCinematicAmbientCRadius,
          ),
        )
        val bokehColor = colors.textPrimary.copy(alpha = BiliFocus.HomeBackgroundCinematicBokehAlpha)
        BiliFocus.HomeBackgroundCinematicBokehDots.forEach { dot ->
          val center = Offset(
            x = size.width * dot.xFraction,
            y = size.height * dot.yFraction,
          )
          drawRect(
            brush = Brush.radialGradient(
              colors = listOf(bokehColor, BiliColors.Transparent),
              center = center + Offset(
                x = drift * BiliFocus.HomeBackgroundCinematicBokehDriftX,
                y = drift * BiliFocus.HomeBackgroundCinematicBokehDriftY,
              ),
              radius = dot.radius + drift * BiliFocus.HomeBackgroundCinematicBokehRadiusDrift,
            ),
          )
        }
      }
    } else if (refinedVisualsEnabled) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = maxOf(size.width, size.height)
        drawRect(
          brush = Brush.radialGradient(
            colors = listOf(colors.ambientA, BiliColors.Transparent),
            center = Offset(
              x = size.width * BiliFocus.HomeBackgroundRefinedAmbientAX,
              y = size.height * BiliFocus.HomeBackgroundRefinedAmbientAY,
            ),
            radius = radius * BiliFocus.HomeBackgroundRefinedAmbientARadius,
          ),
        )
        drawRect(
          brush = Brush.radialGradient(
            colors = listOf(colors.ambientB, BiliColors.Transparent),
            center = Offset(
              x = size.width * BiliFocus.HomeBackgroundRefinedAmbientBX,
              y = size.height * BiliFocus.HomeBackgroundRefinedAmbientBY,
            ),
            radius = radius * BiliFocus.HomeBackgroundRefinedAmbientBRadius,
          ),
        )
      }
    }
  }
}

@Composable
private fun ComingSoonScreen(
  @StringRes titleRes: Int,
  @StringRes messageRes: Int,
) {
  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = stringResource(titleRes),
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.ScreenTitle,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = stringResource(messageRes),
      color = BiliColors.TextSecondary,
      fontSize = BiliTypography.Body,
      modifier = Modifier.padding(top = BiliSpacing.Md),
    )
  }
}
