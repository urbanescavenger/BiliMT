package com.kirin.mt.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.kirin.mt.R
import com.kirin.mt.core.player.BiliMediaDataSourceFactory
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

/** 直播默认请求清晰度:原画。服务端不可用时自动降级。 */
private const val LiveDefaultQn = 10000

/** 顶栏控件索引:0=返回,1=清晰度。 */
private const val ControlIndexBack = 0
private const val ControlIndexQuality = 1
private const val ControlCount = 2

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
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val roomId = request.liveRoomId
  var selectedQn by remember(roomId) { mutableIntStateOf(request.preferredQualityId ?: LiveDefaultQn) }
  var loadState by remember(roomId) { mutableStateOf<LiveLoadState>(LiveLoadState.Loading) }
  var liveInfo by remember(roomId) { mutableStateOf<LivePlayInfo?>(null) }
  val isPlayingState = remember { mutableStateOf(false) }
  val playerErrorMsg = remember { mutableStateOf<String?>(null) }
  var controlsVisible by remember { mutableStateOf(true) }
  var showQualityPanel by remember { mutableStateOf(false) }
  var focusedControlIndex by remember { mutableIntStateOf(ControlIndexQuality) }
  var focusedQualityIndex by remember { mutableIntStateOf(0) }
  var retryKey by remember { mutableIntStateOf(0) }
  var clockText by remember { mutableStateOf(currentClockText()) }

  val player = remember(roomId) {
    ExoPlayer.Builder(context).build()
  }
  val controlsFocusRequester = remember { FocusRequester() }

  val qualities = liveInfo?.qualities.orEmpty()

  DisposableEffect(player) {
    player.addListener(object : Player.Listener {
      override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
        // 用 MutableState 对象而非委托的局部 var:匿名对象里不能改捕获的局部 var,
        // 但可以改对象属性(.value)。
        isPlayingState.value = isPlayingChanged
      }

      override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        // 取流失败(如 CDN 403)不再静默卡住,记到 MutableState 让叠层显示错误便于定位。
        // 不能在此改委托的局部 var loadState(匿名对象里禁改捕获 var),改 playerErrorMsg.value。
        playerErrorMsg.value = error.message.orEmpty().ifBlank { "播放出错" }
      }
    })
    onDispose { player.release() }
  }

  LaunchedEffect(roomId, selectedQn, retryKey) {
    loadState = LiveLoadState.Loading
    playerErrorMsg.value = null
    player.clearMediaItems()
    try {
      val info = playbackRepository.getLivePlayInfo(roomId, selectedQn)
      liveInfo = info
      val dataSourceFactory = DefaultDataSource.Factory(
        context,
        BiliMediaDataSourceFactory(playbackHttpClient, info.headers).create(),
      )
      val mediaSource = if (info.isHls) {
        HlsMediaSource.Factory(dataSourceFactory)
          .createMediaSource(MediaItem.fromUri(info.streamUrl))
      } else {
        ProgressiveMediaSource.Factory(dataSourceFactory)
          .createMediaSource(MediaItem.fromUri(info.streamUrl))
      }
      player.setMediaSource(mediaSource)
      player.prepare()
      player.playWhenReady = true
      loadState = LiveLoadState.Ready(info)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      loadState = LiveLoadState.Failed(error.message.orEmpty().ifBlank { "直播加载失败" })
    }
  }

  // 时钟:每 30s 刷新一次,供顶栏右上角显示。
  LaunchedEffect(Unit) {
    while (isActive) {
      clockText = currentClockText()
      delay(30_000L)
    }
  }

  // 控制条自动隐藏:播放中、无清晰度面板时 [PlayerControlsAutoHideMs] 后隐藏。
  // 暂停时不隐藏(与点播一致),面板打开时不隐藏。
  LaunchedEffect(controlsVisible, isPlayingState.value, showQualityPanel) {
    if (controlsVisible && isPlayingState.value && !showQualityPanel) {
      delay(BiliMotion.PlayerControlsAutoHideMs)
      if (isActive && controlsVisible && isPlayingState.value && !showQualityPanel) {
        controlsVisible = false
      }
    }
  }

  // TV:进入时把焦点收到根容器,统一由 onPreviewKeyEvent 路由 D-pad 按键。
  LaunchedEffect(Unit) {
    runCatching { controlsFocusRequester.requestFocus() }
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

  // 层级关闭:清晰度面板→控件→退出。TV 遥控器返回键与移动端系统返回共用此函数。
  fun closePanelOrControls() {
    when {
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
              showQualityPanel && qualities.isNotEmpty() -> {
                selectedQn = qualities[focusedQualityIndex].qn
                showQualityPanel = false
              }
              controlsVisible -> activateControl()
              else -> togglePlayback()
            }
            true
          }
          Key.DirectionLeft -> {
            when {
              showQualityPanel -> true // 面板纵向滚动,左右消费避免原生焦点移入面板行
              controlsVisible -> { moveControl(-1); true }
              else -> { showControls(); true }
            }
          }
          Key.DirectionRight -> {
            when {
              showQualityPanel -> true
              controlsVisible -> { moveControl(1); true }
              else -> { showControls(); true }
            }
          }
          Key.DirectionUp -> {
            when {
              showQualityPanel -> { changeQualityFocus(-1); true }
              else -> { showControls(); true }
            }
          }
          Key.DirectionDown -> {
            when {
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
          actionLabel = "重试",
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
          actionLabel = "重试",
          onAction = { retryKey++ },
        )
      }
      else -> Unit
    }

    if (controlsVisible && loadState is LiveLoadState.Ready) {
      LiveTopOverlay(
        request = request,
        currentQualityDesc = qualities
          .firstOrNull { it.qn == selectedQn }?.description
          ?: qualities.firstOrNull()?.description
          ?: "清晰度",
        focusedControlIndex = focusedControlIndex,
        clockText = clockText,
        onBack = onBack,
        onOpenQuality = { openQualityPanel() },
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
        },
        onDismiss = { showQualityPanel = false },
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
      Spacer(modifier = Modifier.width(BiliSpacing.Sm))
      LiveQualityChip(
        description = currentQualityDesc,
        focused = focusedControlIndex == ControlIndexQuality,
        onClick = onOpenQuality,
      )
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