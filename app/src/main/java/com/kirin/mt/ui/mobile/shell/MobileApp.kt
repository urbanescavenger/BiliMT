package com.kirin.mt.ui.mobile.shell

import android.Manifest
import android.content.Intent
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.kirin.mt.R
import com.kirin.mt.core.i18n.ChineseTextConverters
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.ui.i18n.LocalChineseTextConverter
import com.kirin.mt.ui.i18n.localizedContext
import com.kirin.mt.core.model.SourceIptv
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.UserSummary
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.IptvRepository
import com.kirin.mt.core.network.LiveRepository
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackQuality
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.CdnSelector
import com.kirin.mt.core.player.DanmakuSettingsStore
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.core.storage.SearchHistoryStore
import com.kirin.mt.core.storage.SessionStore
import com.kirin.mt.core.storage.UserSession
import com.kirin.mt.core.update.ApkInstaller
import com.kirin.mt.core.update.UpdateManager
import com.kirin.mt.core.auth.AuthRepository
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeContentLocale
import com.kirin.mt.core.youtube.YoutubeParsers
import com.kirin.mt.core.youtube.YoutubePlaylistStore
import com.kirin.mt.core.youtube.YoutubeRepository
import com.kirin.mt.ui.mobile.LoginActivity
import com.kirin.mt.ui.mobile.SettingsActivity
import com.kirin.mt.ui.mobile.common.DevelopingTipContent
import com.kirin.mt.ui.mobile.downloads.MobileDownloadQualityDialog
import com.kirin.mt.ui.mobile.feed.MobileFeedScreen
import com.kirin.mt.ui.mobile.feed.MobileYoutubePlaylistDetailScreen
import com.kirin.mt.ui.mobile.feed.MobilePlaylistPickerDialog
import com.kirin.mt.ui.mobile.feed.MobileYoutubeLongPressSheet
import com.kirin.mt.ui.mobile.space.MobileYoutubeChannelScreen
import com.kirin.mt.ui.mobile.feed.MobileLiveScreen
import com.kirin.mt.ui.mobile.home.MobileHomeScreen
import com.kirin.mt.ui.mobile.pgc.MobilePgcSeasonScreen
import com.kirin.mt.ui.mobile.player.MobilePlayerScreen
import com.kirin.mt.ui.mobile.search.MobileSearchScreen
import com.kirin.mt.ui.pgc.PgcSeasonRequest
import com.kirin.mt.ui.player.LivePlayerScreen
import com.kirin.mt.ui.player.toPlaybackRequest
import com.kirin.mt.ui.shell.AppDestination
import com.kirin.mt.ui.theme.BiliMobileTheme
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 移动端应用壳:NavigationSuiteScaffold 自适应——窄屏底部 NavigationBar,宽屏侧边 NavigationRail,
 * 由 NavigationSuiteScaffold 根据 WindowAdaptiveInfo 自动决定。底栏项复用 [AppDestination]。
 * 点首页视频 → 全屏触屏播放器(MobilePlayerScreen)覆盖在上层。
 */
@Composable
fun BiliMobileApp(
  videoRepository: VideoRepository,
  liveRepository: LiveRepository,
  iptvRepository: IptvRepository,
  playbackRepository: PlaybackRepository,
  danmakuSettingsStore: DanmakuSettingsStore,
  liveQualityPreferenceStore: com.kirin.mt.core.player.LiveQualityPreferenceStore,
  playbackHttpClient: OkHttpClient,
  cdnSelector: CdnSelector,
  authRepository: AuthRepository,
  appSettingsStore: AppSettingsStore,
  sessionStore: SessionStore,
  searchHistoryStore: SearchHistoryStore,
  youtubeChannelStore: com.kirin.mt.core.youtube.YoutubeChannelStore,
  youtubeRepository: YoutubeRepository,
  youtubePlaylistStore: YoutubePlaylistStore,
  youtubeFeedCacheStore: com.kirin.mt.core.youtube.YoutubeFeedCacheStore,
  youtubeHistoryStore: com.kirin.mt.core.youtube.YoutubeHistoryStore,
  watchedStore: com.kirin.mt.core.storage.WatchedStore,
  updateManager: UpdateManager,
  apkInstaller: ApkInstaller,
  downloadManager: com.kirin.mt.core.download.DownloadManager,
) {
  val context = LocalContext.current
  val settings by appSettingsStore.settings.collectAsState(initial = AppSettings())
  // 已看完的视频 id 集合:经 CompositionLocal 下发,卡片右下角据此标「已看完」。
  val watchedIds by watchedStore.watched.collectAsState(initial = emptySet())
  // 语言设置:与 TV AppShell 一致,把 LocalContext 整体换成所选 locale 的 context,
  // 使 stringResource(R.string.*) 命中对应 values-<locale> 资源。
  val localizedContext = remember(context, settings.chineseTextVariant) {
    context.localizedContext(settings.chineseTextVariant)
  }
  val textConverter = remember(settings.chineseTextVariant) {
    ChineseTextConverters.forVariant(settings.chineseTextVariant)
  }
  var selected by rememberSaveable { mutableStateOf(AppDestination.Recommend) }
  var recommendRefreshKey by rememberSaveable { mutableStateOf(0) }
  // 动态 tab 手动刷新键:每次点击底栏"动态"(含重复点击)自增,驱动 DynamicScreen
  // 同时刷新 B 站动态 + YouTube 关注(镜像 recommendRefreshKey 与 TV dynamicManualRefreshKey)。
  var dynamicRefreshKey by rememberSaveable { mutableStateOf(0) }

  // YouTube 内容地区(gl/hl)写进进程级 holder,InnerTubeClient.buildContext 每次请求读它,
  // 让 gl/hl 跟随设置运行时变化(browse/search/player/SABR 全自动一致,免逐层透传)。
  LaunchedEffect(settings.youtubeContentRegion) {
    YoutubeContentLocale.current = settings.youtubeContentRegion
  }
  val session by sessionStore.session.collectAsState(initial = UserSession())
  var playbackRequest by remember { mutableStateOf<PlaybackRequest?>(null) }
  var spaceRequest by remember { mutableStateOf<com.kirin.mt.ui.space.UpSpaceRequest?>(null) }
  // 空间是否压在播放器之上:true=刚从播放器进空间(空间在上、播放器藏后),
  // false=从空间起了播(播放器在上)。配合空间叠层显示门控与 BackHandler enabled,
  // 让"空间→视频→返回→空间"成立(不销毁 spaceRequest),镜像 TV AppShell spacePlaybackBehind。
  var spacePlaybackBehind by remember { mutableStateOf(false) }
  // 追番点季 -> 季详情外壳;选集后起播,player 盖在季详情之上。z 序同 space。
  var pgcSeasonRequest by remember { mutableStateOf<PgcSeasonRequest?>(null) }
  var pgcPlaybackBehind by remember { mutableStateOf(false) }
  // YouTube 频道主页(channelId + 名),镜像 space 的覆盖层范式。
  var youtubeChannelRequest by remember { mutableStateOf<YoutubeChannel?>(null) }
  var channelPlaybackBehind by remember { mutableStateOf(false) }
  // YouTube 播放列表详情页:点频道页播放列表卡进入(覆盖在频道页之上),先列视频再选播。
  var youtubePlaylistRequest by remember { mutableStateOf<YoutubeParsers.YoutubePlaylist?>(null) }
  var playlistPlaybackBehind by remember { mutableStateOf(false) }
  // 空间/频道页状态提升到 shell:从空间/频道起播后退出播放器回到页面时不重载(镜像 TV UpSpaceUiState)。
  val upSpaceUiState = remember { com.kirin.mt.ui.mobile.space.MobileUpSpaceUiState() }
  val youtubeChannelUiState = remember { com.kirin.mt.ui.mobile.space.MobileYoutubeChannelUiState() }
  // 播放列表连播队列:播放列表 tab 起播时快照当前播放列表;其它入口起播时置空。
  var playQueue by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
  // 长按视频卡片弹出操作菜单的视频;再点「下载」/「加入播放列表」切换到对应弹窗。
  var longPressVideo by remember { mutableStateOf<VideoSummary?>(null) }
  var showPlaylistPicker by remember { mutableStateOf(false) }
  // 长按菜单里点「下载」→ 弹清晰度选择对话框;确认后入队下载。
  var showDownloadDialog by remember { mutableStateOf(false) }
  // 确认清晰度后入队下载期间弹加载动画(遮罩转圈),入队完成/失败即收起。
  var downloadEnqueueing by remember { mutableStateOf(false) }
  // B站下载弹窗的清晰度列表:经 getPlaybackInfo 拉取后缓存,再开对话框。
  var biliDownloadQualities by remember { mutableStateOf<List<PlaybackQuality>>(emptyList()) }
  // B站下载确认入队用的请求:拉清晰度时已解析 cid,enqueue 复用避免 playurl 用 cid=0 返 -400
  // (对齐播放器 activeRequest 回写 resolvedRequest,见 MobilePlayerScreen loadRequest)。
  var biliDownloadRequest by remember { mutableStateOf<PlaybackRequest?>(null) }
  val scope = rememberCoroutineScope()

  // 卡片长按:B站/YouTube 弹操作菜单(下载/加入播放列表),不再直接 toggle。
  // IPTV 直播卡除外:直播无下载、加播放列表无意义。
  val onLongPress: (VideoSummary) -> Unit = { video ->
    if (video.source != SourceIptv) {
      longPressVideo = video
      showPlaylistPicker = false
      showDownloadDialog = false
    }
  }

  // 打开 UP 主页:按来源分流——YouTube 带 channelId 进频道主页,否则 B 站空间。
  // 卡片身份数据缺失(首页/动态特殊卡无 owner、搜索 YouTube 无 channelId)时,点击按需解析补齐。
  fun openOwner(video: VideoSummary) {
    if (video.source == SourceYoutube) {
      if (video.channelId.isNotBlank()) {
        youtubeChannelRequest = YoutubeChannel(video.channelId, video.ownerName)
        channelPlaybackBehind = false
      } else if (video.ownerName.isNotBlank()) {
        // channelId 缺失:按频道名解析出 channelId 再进频道主页。
        scope.launch {
          val resolved = runCatching { youtubeRepository.resolveChannel(video.ownerName) }.getOrNull()
          if (resolved != null && resolved.channelId.isNotBlank()) {
            youtubeChannelRequest = YoutubeChannel(resolved.channelId, resolved.name.ifBlank { video.ownerName })
            channelPlaybackBehind = false
          }
        }
      }
    } else {
      if (video.ownerMid > 0L) {
        spaceRequest = com.kirin.mt.ui.space.UpSpaceRequest(video.ownerMid, video.ownerName, video.ownerFace)
        spacePlaybackBehind = false
      } else if (video.bvid.isNotBlank()) {
        // ownerMid 缺失:按 bvid 解析出 UP 主身份再进空间。
        scope.launch {
          val owner = runCatching { videoRepository.resolveBiliOwner(video.bvid) }.getOrNull()
          if (owner != null) {
            spaceRequest = com.kirin.mt.ui.space.UpSpaceRequest(
              owner.first,
              owner.second.ifBlank { video.ownerName },
              owner.third.ifBlank { video.ownerFace },
            )
            spacePlaybackBehind = false
          }
        }
      }
    }
  }

  // Android 13+ 需运行时请求 POST_NOTIFICATIONS,否则后台播放通知(及控件)不显示。
  val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  val effectiveCodecPreference =
    if (settings.lowSpecMode) PlaybackCodecPreference.H264 else settings.playbackCodecPreference

  val bottomNav = listOf(
    AppDestination.Recommend,
    AppDestination.Live,
    AppDestination.Dynamic,
    AppDestination.Search,
    AppDestination.Settings,
  )

  CompositionLocalProvider(
    LocalContext provides localizedContext,
    LocalResources provides localizedContext.resources,
    LocalChineseTextConverter provides textConverter,
    com.kirin.mt.ui.mobile.home.LocalWatchedIds provides watchedIds,
  ) {
    BiliMobileTheme(settings.homeThemeVariant, settings.appearanceMode) {
    Box(modifier = Modifier.fillMaxSize()) {
    NavigationSuiteScaffold(
      modifier = Modifier.statusBarsPadding(),
      navigationSuiteItems = {
        // 底部导航始终渲染。播放器是全屏不透明层(见下),盖住底栏并吞噬其下所有指针事件,
        // 播放时底栏不可达,无需在此隐藏——将来底下新增任何内容也一样被盖住。
        bottomNav.forEach { dest ->
          item(
            selected = selected == dest,
            onClick = {
              if (dest == AppDestination.Settings) {
                context.startActivity(Intent(context, SettingsActivity::class.java))
              } else {
                // 重复点击当前已选中的"推荐"tab -> 触发首页刷新(滚顶 + 重载)
                if (dest == AppDestination.Recommend && selected == dest) {
                  recommendRefreshKey++
                }
                // 点击"动态"tab(含重复点击) -> 触发动态刷新(B站 + YouTube 关注)
                if (dest == AppDestination.Dynamic) {
                  dynamicRefreshKey++
                }
                selected = dest
              }
            },
            icon = { Icon(painterResource(dest.iconRes), contentDescription = null) },
            label = { Text(stringResource(dest.titleRes)) },
          )
        }
      },
    ) {
      when (selected) {
        AppDestination.Recommend -> MobileHomeScreen(
          videoRepository = videoRepository,
          youtubeChannelStore = youtubeChannelStore,
          youtubeFeedCacheStore = youtubeFeedCacheStore,
          // 与 TV 共享同一份配置(AppSettings.homeSectionsOrder + enabledHomeSections):
          // 按用户排序筛掉隐藏分区,空兜底 Recommend。TV 端改顺序/显隐移动端即时同步。
          enabledSections = settings.homeSectionsOrder
            .filter { it in settings.enabledHomeSections }
            .ifEmpty { listOf(HomeSection.Recommend) },
          refreshKey = recommendRefreshKey,
          onVideoSelected = { video ->
            playQueue = emptyList()
            playbackRequest = video.toPlaybackRequest()
          },
          onOpenOwner = { video -> openOwner(video) },
          onLongPress = onLongPress,
          modifier = Modifier.fillMaxSize(),
        )
        AppDestination.Live -> MobileLiveScreen(
          liveRepository = liveRepository,
          iptvRepository = iptvRepository,
          onVideoSelected = { video ->
            playQueue = emptyList()
            playbackRequest = video.toPlaybackRequest()
          },
          onOpenOwner = { video -> openOwner(video) },
          modifier = Modifier.fillMaxSize(),
        )
        AppDestination.Dynamic -> MobileFeedScreen(
          videoRepository = videoRepository,
          youtubeChannelStore = youtubeChannelStore,
          youtubePlaylistStore = youtubePlaylistStore,
          youtubeFeedCacheStore = youtubeFeedCacheStore,
          youtubeHistoryStore = youtubeHistoryStore,
          isLoggedIn = session.isLoggedIn,
          dynamicRefreshKey = dynamicRefreshKey,
          onVideoSelected = { video ->
            playQueue = emptyList()
            playbackRequest = video.toPlaybackRequest()
          },
          onOpenOwner = { video -> openOwner(video) },
          onSeasonSelected = { season ->
            pgcSeasonRequest = PgcSeasonRequest(seasonId = season.seasonId, epId = season.firstEpId)
            pgcPlaybackBehind = false
          },
          onLongPress = onLongPress,
          onStartPlaylist = { queue -> playQueue = queue },
          onLogin = { context.startActivity(Intent(context, LoginActivity::class.java)) },
          modifier = Modifier.fillMaxSize(),
        )
        AppDestination.Pgc -> DevelopingTipContent()
        AppDestination.Search -> MobileSearchScreen(
          videoRepository = videoRepository,
          searchHistoryStore = searchHistoryStore,
          onVideoSelected = { video ->
            playQueue = emptyList()
            playbackRequest = video.toPlaybackRequest()
          },
          onOpenOwner = { video -> openOwner(video) },
          onLongPress = onLongPress,
          onUserSelected = { user ->
            if (user.source == SourceYoutube && user.channelId.isNotBlank()) {
              youtubeChannelRequest = YoutubeChannel(user.channelId, user.name)
              channelPlaybackBehind = false
            } else {
              spaceRequest = com.kirin.mt.ui.space.UpSpaceRequest(user.mid, user.name, user.face)
              spacePlaybackBehind = false
            }
          },
          modifier = Modifier.fillMaxSize(),
        )
        AppDestination.Settings -> DevelopingTipContent()
      }
    }

    val request = playbackRequest
    if (request != null) {
      // 组合在 NavigationSuiteScaffold 内容(含搜索页 BackHandler)之后,
      // OnBackPressedDispatcher 栈中更靠顶,系统返回优先关播放器而非退 app / 回搜索输入态。
      // 空间压在播放器之上时(spacePlaybackBehind),播放器让出返回键由空间响应。
      BackHandler(enabled = !(spaceRequest != null && spacePlaybackBehind)) {
        playbackRequest = null
      }
      Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 全屏消费层:吞噬整个播放器区域的所有指针事件,盖住并屏蔽其下任何层(底部导航/页面/空间等)。
        // background 只画不吞事件——缓存视频里"清晰度位置"是无 onClick 的静态 Text,点击会落穿到
        // 背后的"设置"tab 打开设置页。此层让播放器无点击目标处也吞掉指针,播放器控件叠在其上仍正常响应;
        // 将来底下新增别的内容也一样被盖住。
        Box(Modifier.fillMaxSize().pointerInput(Unit) { consumeAllGestures() })
        if (request.isLive || request.isIptv) {
          LivePlayerScreen(
            request = request,
            playbackRepository = playbackRepository,
            playbackHttpClient = playbackHttpClient,
            liveQualityPreferenceStore = liveQualityPreferenceStore,
            onBack = { playbackRequest = null },
            isMobile = true,
          )
        } else {
        MobilePlayerScreen(
          request = request,
          playbackRepository = playbackRepository,
          youtubeHistoryStore = youtubeHistoryStore,
          watchedStore = watchedStore,
          autoDeleteWatchedCache = settings.autoDeleteWatchedCache,
          danmakuSettingsStore = danmakuSettingsStore,
          playbackHttpClient = playbackHttpClient,
          cdnSelector = cdnSelector,
          playbackCodecPreference = effectiveCodecPreference,
          playbackQualityPreference = settings.playbackQualityPreference,
          playbackCdnPreference = settings.playbackCdnPreference,
          youtubeDefaultQuality = settings.youtubeDefaultQuality,
          youtubeStartQuality = settings.youtubeStartQuality,
          bufferMaxMs = settings.bufferMax.ms,
          airJumpAssistantEnabled = settings.airJumpAssistantEnabled,
          videoRepository = videoRepository,
          youtubePlaylistStore = youtubePlaylistStore,
          downloadManager = downloadManager,
          playQueue = playQueue,
          onPlayVideo = { video ->
            playQueue = emptyList()
            playbackRequest = video.toPlaybackRequest()
          },
          onBack = {
            playQueue = emptyList()
            playbackRequest = null
          },
          onOpenUpSpace = { mid, name, face ->
            spaceRequest = com.kirin.mt.ui.space.UpSpaceRequest(mid, name, face)
            spacePlaybackBehind = true
          },
          // 简介频道行点头像/名进 YouTube 频道主页(垫在播放器后面,对齐 B 站空间行为)。
          // channelId 缺省(搜索直进等存卡无 channelId)时按频道名解析,镜像 openOwner 的 YouTube 分支。
          onOpenYoutubeChannel = { channelId, name ->
            if (channelId.isNotBlank()) {
              youtubeChannelRequest = YoutubeChannel(channelId, name)
              channelPlaybackBehind = true
            } else if (name.isNotBlank()) {
              scope.launch {
                val resolved = runCatching { youtubeRepository.resolveChannel(name) }.getOrNull()
                if (resolved != null && resolved.channelId.isNotBlank()) {
                  youtubeChannelRequest = YoutubeChannel(resolved.channelId, resolved.name.ifBlank { name })
                  channelPlaybackBehind = true
                }
              }
            }
          },
          modifier = Modifier.fillMaxSize(),
        )
        }
      }
    }

    val space = spaceRequest
    if (space != null) {
      // 空间在顶层(无播放器或刚从播放器进空间)时才接管返回键;播放器在上时让播放器响应。
      BackHandler(enabled = playbackRequest == null || spacePlaybackBehind) {
        spaceRequest = null
        spacePlaybackBehind = false
      }
      // 显示门控:仅在空间处于顶层时渲染,避免从空间起播后空间仍盖在播放器之上。
      if (playbackRequest == null || spacePlaybackBehind) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        ) {
          com.kirin.mt.ui.mobile.space.MobileUserSpaceScreen(
            videoRepository = videoRepository,
            uiState = upSpaceUiState,
            mid = space.mid,
            ownerName = space.ownerName,
            ownerFace = space.ownerFace,
            // 不销毁 spaceRequest:播放器返回时由门控重新露出空间,而非落回首页。
            onVideoSelected = { video ->
              playQueue = emptyList()
              spacePlaybackBehind = false
              playbackRequest = video.toPlaybackRequest()
            },
            // 点空间内卡片的 UP 头像 -> 切到该 UP 空间/YouTube 频道(LaunchedEffect(mid/channelId) 自动重载)。
            // 不动 spacePlaybackBehind:保留来源栈(从播放器进来的返回回播放器,从 tab 进来的返回回 tab)。
            onOpenOwner = { video -> openOwner(video) },
            onLongPress = onLongPress,
            onPlayAll = { queue ->
              if (queue.isNotEmpty()) {
                playQueue = queue
                spacePlaybackBehind = false
                playbackRequest = queue.first().toPlaybackRequest()
              }
            },
            onBack = {
              spaceRequest = null
              spacePlaybackBehind = false
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }

    val channel = youtubeChannelRequest
    if (channel != null) {
      // 频道主页覆盖层:镜像 space 的返回键接管 + 显示门控。
      BackHandler(enabled = playbackRequest == null || channelPlaybackBehind) {
        youtubeChannelRequest = null
        channelPlaybackBehind = false
      }
      if (playbackRequest == null || channelPlaybackBehind) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        ) {
          MobileYoutubeChannelScreen(
            youtubeRepository = youtubeRepository,
            youtubeChannelStore = youtubeChannelStore,
            uiState = youtubeChannelUiState,
            channelId = channel.channelId,
            channelName = channel.name,
            onVideoSelected = { video ->
              playQueue = emptyList()
              channelPlaybackBehind = false
              playbackRequest = video.toPlaybackRequest()
            },
            onLongPress = onLongPress,
            onOpenPlaylist = { playlist ->
              youtubePlaylistRequest = playlist
              playlistPlaybackBehind = false
            },
            onPlayAll = { queue ->
              if (queue.isNotEmpty()) {
                playQueue = queue
                channelPlaybackBehind = false
                playbackRequest = queue.first().toPlaybackRequest()
              }
            },
            onBack = {
              youtubeChannelRequest = null
              channelPlaybackBehind = false
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }

    // YouTube 播放列表详情页:点频道页播放列表卡进入,覆盖在频道页之上,先列视频再选播。
    val playlistRequest = youtubePlaylistRequest
    if (playlistRequest != null) {
      BackHandler(enabled = playbackRequest == null || playlistPlaybackBehind) {
        youtubePlaylistRequest = null
        playlistPlaybackBehind = false
      }
      if (playbackRequest == null || playlistPlaybackBehind) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        ) {
          MobileYoutubePlaylistDetailScreen(
            youtubeRepository = youtubeRepository,
            playlist = playlistRequest,
            onStartSelected = { video, queue ->
              playQueue = queue
              playlistPlaybackBehind = false
              playbackRequest = video.toPlaybackRequest()
            },
            onLongPress = onLongPress,
            onBack = {
              youtubePlaylistRequest = null
              playlistPlaybackBehind = false
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }

    val seasonReq = pgcSeasonRequest
    if (seasonReq != null) {
      // 季详情在顶层(无播放器)时才接管返回键;播放器在上时让播放器响应。
      BackHandler(enabled = playbackRequest == null || pgcPlaybackBehind) {
        pgcSeasonRequest = null
        pgcPlaybackBehind = false
      }
      // 显示门控:仅在季详情处于顶层时渲染,选集起播后让播放器盖在上面。
      if (playbackRequest == null || pgcPlaybackBehind) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        ) {
          MobilePgcSeasonScreen(
            videoRepository = videoRepository,
            request = seasonReq,
            // 选集 -> PGC PlaybackRequest,照 AppShell PgcSeasonScreen onPlayEpisode 范式。
            onPlayEpisode = { season, ep ->
              val startMs = season.progress
                ?.takeIf { it.lastEpId == ep.id }
                ?.lastTime
                ?.let { it * 1000L }
                ?: 0L
              pgcPlaybackBehind = false
              playbackRequest = PlaybackRequest(
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
            onBack = {
              pgcSeasonRequest = null
              pgcPlaybackBehind = false
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }

    // 长按 B站/YouTube 卡片:底部操作菜单 →「下载」弹清晰度对话框 /「加入播放列表」选列表。
    longPressVideo?.let { video ->
      when {
        // 清晰度选择对话框(模态,盖在操作菜单上)。取消只关对话框回到菜单;
        // 确认后入队下载并收起整套菜单。B站走可播清晰度列表,YouTube 走简单/高清分档。
        showDownloadDialog -> {
          MobileDownloadQualityDialog(
            isYoutube = video.source == SourceYoutube,
            biliQualities = biliDownloadQualities,
            onDismiss = { showDownloadDialog = false; biliDownloadRequest = null },
            onConfirm = { choice ->
              // 确认下载即收起整套长按菜单(不必等入队结果),避免 dialog 关闭后长按弹窗又冒出来/残留。
              showDownloadDialog = false
              longPressVideo = null
              // B站用拉清晰度时已解析 cid 的 request(卡片 cid=0 直接入队会 -400);YouTube 走卡片原请求。
              val request = biliDownloadRequest ?: video.toPlaybackRequest()
              biliDownloadRequest = null
              // 入队期间弹加载动画给反馈,完成/失败再收起。
              downloadEnqueueing = true
              scope.launch {
                downloadManager.enqueue(request, choice)
                  .onSuccess {
                    downloadEnqueueing = false
                    Toast.makeText(context, context.getString(R.string.downloads_enqueued), Toast.LENGTH_SHORT).show()
                  }
                  .onFailure { e ->
                    downloadEnqueueing = false
                    Toast.makeText(context, e.message ?: context.getString(R.string.downloads_enqueue_failed), Toast.LENGTH_LONG).show()
                  }
              }
            },
          )
        }
        showPlaylistPicker -> {
          MobilePlaylistPickerDialog(
            video = video,
            youtubePlaylistStore = youtubePlaylistStore,
            onDismiss = {
              showPlaylistPicker = false
              longPressVideo = null
            },
          )
        }
        else -> {
          // 已下载(可播)的视频隐藏「下载」入口,避免重复下载。
          val downloads by downloadManager.downloads.collectAsState(initial = emptyList())
          val isDownloaded = downloads.any {
            it.download.videoId == video.bvid && it.isPlayable &&
              (it.videoPart != null || it.muxedPart != null)
          }
          MobileYoutubeLongPressSheet(
            video = video,
            isDownloaded = isDownloaded,
            onDownload = {
              // YouTube 直接弹分档对话框;B站先经 playurl 拉可播清晰度列表再弹。
              // 复用播放器同款 getPlaybackInfo,清晰度与在线播放一致。
              if (video.source == SourceYoutube) {
                // YouTube 无 cid 依赖,直接用卡片请求;清掉上次 B站解析残留的 biliDownloadRequest,
                // 避免 enqueue 误复用 B站 request。
                biliDownloadRequest = null
                showDownloadDialog = true
              } else {
                scope.launch {
                  runCatching {
                    // 卡片 cid 常为 0(列表接口只给 bvid),播放路径会先 resolveCid;下载同样先解析,
                    // 否则 getPlaybackInfo 用 cid=0 拼 playurl 返回 -400(对齐播放器 loadRequest)。
                    val req = video.toPlaybackRequest()
                    val cid = req.cid.takeIf { it > 0L }
                      ?: playbackRepository.resolveCid(req.bvid)
                    if (cid <= 0L) error(context.getString(R.string.downloads_enqueue_failed))
                    val resolved = req.copy(cid = cid)
                    biliDownloadRequest = resolved
                    playbackRepository.getPlaybackInfo(
                      resolved,
                      effectiveCodecPreference,
                      settings.playbackQualityPreference,
                    ).qualities
                  }
                    .onSuccess { qs ->
                      biliDownloadQualities = qs
                      showDownloadDialog = true
                    }
                    .onFailure { e ->
                      longPressVideo = null
                      biliDownloadRequest = null
                      Toast.makeText(context, e.message ?: context.getString(R.string.downloads_enqueue_failed), Toast.LENGTH_LONG).show()
                    }
                }
              }
            },
            onPickPlaylist = { showPlaylistPicker = true },
            onDismiss = {
              showPlaylistPicker = false
              showDownloadDialog = false
              biliDownloadRequest = null
              longPressVideo = null
            },
          )
        }
      }
    }

    // 入队下载期间:模态加载动画(转圈),给"正在入队"的过程反馈;完成/失败由 onConfirm 收起。
    if (downloadEnqueueing) {
      MobileEnqueueingDialog()
    }
  }
  }
  }
}

/** 入队下载期间的加载动画:模态遮罩 + 转圈 + 文案。入队通常很快,仅作过程反馈,不可手动取消。 */
/**
 * 全屏吞噬所有指针事件:使该层成为不透明遮挡,吞掉点/滑动,阻止事件落到它之下的任何组件
 * (底部导航、页面、空间等)。播放器控件叠于其上,事件先由顶层控件消费,不受影响。
 */
private suspend fun PointerInputScope.consumeAllGestures() {
  awaitPointerEventScope {
    while (true) {
      val event = awaitPointerEvent()
      event.changes.forEach { it.consume() }
    }
  }
}

@Composable
private fun MobileEnqueueingDialog() {
  AlertDialog(
    onDismissRequest = { /* 不可取消,入队完成/失败自动收起 */ },
    confirmButton = {},
    title = { Text(stringResource(R.string.downloads_enqueueing)) },
    text = {
      Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }
    },
  )
}