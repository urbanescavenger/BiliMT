package com.kirin.mt.ui.shell

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
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
import com.kirin.mt.core.i18n.ChineseTextConverters
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.player.CdnSelector
import com.kirin.mt.core.player.CdnSpeedTester
import com.kirin.mt.core.player.CodecCapabilityProbe
import com.kirin.mt.core.player.LastPlayedStore
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.SpeedTestUiState
import com.kirin.mt.core.player.DanmakuSettingsStore
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.isWatchCompleted
import com.kirin.mt.core.model.shouldAdvanceToNextHistoryEpisode
import com.kirin.mt.core.settings.AppPerformancePolicy
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.settings.supportsLiquidGlassCards
import com.kirin.mt.core.storage.SearchHistoryStore
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.storage.UserSession
import com.kirin.mt.core.update.ApkInstaller
import com.kirin.mt.core.update.UpdateManager
import com.kirin.mt.ui.feed.DynamicFeedScreen
import com.kirin.mt.ui.feed.DynamicFeedUiState
import com.kirin.mt.ui.feed.HistoryFeedScreen
import com.kirin.mt.ui.feed.HistoryFeedUiState
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
import okhttp3.OkHttpClient

private const val PlaybackFocusRestoreRetryCount = 8
private const val PlaybackFocusRestoreCleanupFrameCount = 30
private const val ExitConfirmWindowMs = 3_000L

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
  playbackRepository: PlaybackRepository,
  danmakuSettingsStore: DanmakuSettingsStore,
  playbackHttpClient: OkHttpClient,
  codecCapabilityProbe: CodecCapabilityProbe,
  cdnSelector: CdnSelector,
  authRepository: AuthRepository,
  appSettingsStore: AppSettingsStore,
  appCacheManager: AppCacheManager,
  searchHistoryStore: SearchHistoryStore,
  sessionStore: SessionStore,
  updateManager: UpdateManager,
  apkInstaller: ApkInstaller,
) {
  val settings by appSettingsStore.settings.collectAsState(initial = AppSettings())
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
  val searchFocusRequester = remember { FocusRequester() }
  val dynamicFocusRequester = remember { FocusRequester() }
  val historyFocusRequester = remember { FocusRequester() }
  val settingsFocusRequester = remember { FocusRequester() }
  val recommendUiState = remember { RecommendUiState() }
  val dynamicFeedState = remember { DynamicFeedUiState() }
  val historyFeedState = remember { HistoryFeedUiState() }
  val searchUiState = remember { SearchUiState() }
  var initialHomeFocusPending by remember { mutableStateOf(true) }
  var recommendManualRefreshKey by rememberSaveable { mutableStateOf(0) }
  var dynamicManualRefreshKey by rememberSaveable { mutableStateOf(0) }
  var historyManualRefreshKey by rememberSaveable { mutableStateOf(0) }
  var playbackRequest by remember { mutableStateOf<PlaybackRequest?>(null) }
  var playbackFocusRestoreDestination by remember { mutableStateOf<AppDestination?>(null) }
  var playbackFocusRestoreRequestKey by remember { mutableIntStateOf(0) }
  var contentFocusRestoreDestination by remember { mutableStateOf<AppDestination?>(null) }
  var contentFocusRestoreRequestKey by remember { mutableIntStateOf(0) }
  var lastAppExitBackPressMs by remember { mutableStateOf(0L) }
  var appExitConfirmToast by remember { mutableStateOf<Toast?>(null) }
  var pendingContentFocusDestination by remember { mutableStateOf<AppDestination?>(null) }
  var cacheSizeBytes by remember { mutableStateOf<Long?>(null) }
  var spaceRequest by remember { mutableStateOf<UpSpaceRequest?>(null) }
  var spaceOrigin by remember { mutableStateOf<SpaceOrigin?>(null) }
  var spacePlaybackBehind by remember { mutableStateOf(false) }
  var spaceFocusRestoreRequestKey by remember { mutableIntStateOf(0) }
  val upSpaceUiState = remember { UpSpaceUiState() }
  val spaceFocusRequester = remember { FocusRequester() }

  LaunchedEffect(performancePolicy.imageMemoryCacheEnabled) {
    if (!performancePolicy.imageMemoryCacheEnabled) {
      context.imageLoader.memoryCache?.clear()
    }
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

  fun requestDestinationFocus(destination: AppDestination): Boolean {
    return runCatching {
      when (destination) {
        AppDestination.Recommend -> contentFocusRequester.requestFocus()
        AppDestination.Search -> searchFocusRequester.requestFocus()
        AppDestination.Dynamic -> dynamicFocusRequester.requestFocus()
        AppDestination.History -> historyFocusRequester.requestFocus()
        AppDestination.Settings -> settingsFocusRequester.requestFocus()
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
    }
    if (contentFocusRestoreDestination == destination && key == contentFocusRestoreRequestKey) {
      contentFocusRestoreDestination = null
      pendingContentFocusDestination = null
    }
  }

  fun AppDestination.usesGridFocusRestore(): Boolean {
    return this == AppDestination.Recommend || this == AppDestination.Dynamic || this == AppDestination.History
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
      AppDestination.History -> historyManualRefreshKey += 1
      else -> Unit
    }
  }

  fun selectDestination(destination: AppDestination) {
    if (selectedDestination == AppDestination.Search && destination != AppDestination.Search) {
      searchUiState.clear()
    }
    accountSelected = false
    val destinationChanged = selectedDestination != destination
    if (!destinationChanged) {
      requestManualRefresh(destination)
    }
    selectedDestination = destination
    visitedDestinationNames = visitedDestinationNames + destination.name
  }

  fun moveIntoDestination(destination: AppDestination): Boolean {
    if (accountSelected) {
      return false
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
    )
  }

  LaunchedEffect(userSession.isLoggedIn) {
    if (userSession.isLoggedIn && accountSelected) {
      selectDestination(AppDestination.Recommend)
      runCatching {
        contentFocusRequester.requestFocus()
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

  LaunchedEffect(selectedDestination) {
    if (selectedDestination == AppDestination.Settings) {
      refreshCacheSize()
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
          AppSidebar(
            selectedDestination = selectedDestination,
            accountSelected = accountSelected,
            userSession = userSession,
            autoConfirmOnFocus = autoConfirmOnFocus,
            accountFocusRequester = accountFocusRequester,
            navFocusRequesters = navFocusRequesters,
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
            if (accountSelected) {
              AccountScreen(
                userSession = userSession,
                authRepository = authRepository,
              )
            } else {
              when (selectedDestination) {
                AppDestination.Recommend -> RecommendScreen(
                  videoRepository = videoRepository,
                  uiState = recommendUiState,
                  firstItemFocusRequester = contentFocusRequester,
                  enabledHomeSections = settings.enabledHomeSections,
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
                    upSpaceUiState.reset()
                    spaceOrigin = SpaceOrigin.Content
                    spacePlaybackBehind = false
                    spaceRequest = UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
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
                    upSpaceUiState.reset()
                    spaceOrigin = SpaceOrigin.Content
                    spacePlaybackBehind = false
                    spaceRequest = UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
                  },
                )
                AppDestination.History -> HistoryFeedScreen(
                  videoRepository = videoRepository,
                  isLoggedIn = userSession.isLoggedIn,
                  feedState = historyFeedState,
                  autoRefreshOnSwitch = autoRefreshOnSwitch,
                  manualRefreshKey = historyManualRefreshKey,
                  firstItemFocusRequester = historyFocusRequester,
                  restoreFocusRequestKey = restoreFocusRequestKeyFor(AppDestination.History),
                  onRestoreFocusHandled = { key -> clearFocusRestoreRequest(AppDestination.History, key) },
                  onMoveLeftToNav = {
                    runCatching {
                      navFocusRequesters.getValue(selectedDestination).requestFocus()
                    }.isSuccess
                  },
                  onVideoSelected = { video ->
                    playbackRequest = video.toPlaybackRequest(forceStartPosition = true)
                  },
                  onOwnerSelected = { video ->
                    upSpaceUiState.reset()
                    spaceOrigin = SpaceOrigin.Content
                    spacePlaybackBehind = false
                    spaceRequest = UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
                  },
                )
                AppDestination.Dynamic -> DynamicFeedScreen(
                  videoRepository = videoRepository,
                  isLoggedIn = userSession.isLoggedIn,
                  feedState = dynamicFeedState,
                  autoRefreshOnSwitch = autoRefreshOnSwitch,
                  manualRefreshKey = dynamicManualRefreshKey,
                  firstItemFocusRequester = dynamicFocusRequester,
                  restoreFocusRequestKey = restoreFocusRequestKeyFor(AppDestination.Dynamic),
                  onRestoreFocusHandled = { key -> clearFocusRestoreRequest(AppDestination.Dynamic, key) },
                  onMoveLeftToNav = {
                    runCatching {
                      navFocusRequesters.getValue(selectedDestination).requestFocus()
                    }.isSuccess
                  },
                  onVideoSelected = { video ->
                    playbackRequest = video.toPlaybackRequest()
                  },
                  onOwnerSelected = { video ->
                    upSpaceUiState.reset()
                    spaceOrigin = SpaceOrigin.Content
                    spacePlaybackBehind = false
                    spaceRequest = UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
                  },
                )
                AppDestination.Settings -> SettingsScreen(
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
                      val info = runCatching {
                        playbackRepository.getPlaybackInfo(
                          request = PlaybackRequest(bvid = last.bvid, cid = last.cid, title = ""),
                          codecPreference = effectivePlaybackCodecPreference,
                          qualityPreference = settings.playbackQualityPreference,
                        )
                      }.getOrNull()
                      if (info == null || info.videoTracks.isEmpty()) {
                        speedTestState = SpeedTestUiState.Failed
                        return@launch
                      }
                      val candidates = info.videoTracks
                        .flatMap { listOf(it.baseUrl) + it.backupUrls }
                        .filter { it.startsWith("http://") || it.startsWith("https://") }
                        .distinct()
                      val results = cdnSpeedTester.measure(candidates)
                      speedTestState = if (results.isEmpty()) {
                        SpeedTestUiState.Failed
                      } else {
                        SpeedTestUiState.Succeeded(
                          results = results,
                          sourceLabel = info.title.takeIf { it.isNotBlank() } ?: last.bvid,
                        )
                      }
                    }
                  },
                  onDismissSpeedTest = {
                    speedTestState = SpeedTestUiState.Idle
                  },
                )
              }
            }
          }
        }
      }
      }
      val displayedPlaybackRequest = visiblePlaybackRequest
      if (displayedPlaybackRequest != null) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(BiliColors.VideoBlack),
        ) {
          PlayerScreen(
            request = displayedPlaybackRequest,
            videoRepository = videoRepository,
            playbackRepository = playbackRepository,
            danmakuSettingsStore = danmakuSettingsStore,
            playbackHttpClient = playbackHttpClient,
            cdnSelector = cdnSelector,
            playbackCodecPreference = effectivePlaybackCodecPreference,
            playbackQualityPreference = settings.playbackQualityPreference,
            playbackCdnPreference = settings.playbackCdnPreference,
            seekPreviewSpritesEnabled = settings.seekPreviewSpritesEnabled,
            airJumpAssistantEnabled = settings.airJumpAssistantEnabled,
            confirmPlaybackExit = settings.confirmPlaybackExit,
            autoPlayNextEpisode = settings.autoPlayNextEpisode,
            autoPlayRelatedVideo = settings.autoPlayRelatedVideo,
            autoReturnHomeOnCompletion = settings.autoReturnHomeOnCompletion,
            showClock = settings.showClock,
            showMiniProgressBar = settings.showMiniProgressBar,
            onBack = {
              playbackFocusRestoreDestination = selectedDestination
              playbackRequest = null
              playbackFocusRestoreRequestKey += 1
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
      if (transitionScrimAlpha > 0.01f) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(BiliColors.VideoBlack.copy(alpha = transitionScrimAlpha)),
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

private fun formatCacheSize(bytes: Long): String {
  val safeBytes = bytes.coerceAtLeast(0L)
  val mb = safeBytes / (1024.0 * 1024.0)
  return if (mb >= 1.0) {
    String.format(Locale.US, "%.1f MB", mb)
  } else {
    String.format(Locale.US, "%.0f KB", safeBytes / 1024.0)
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
