package com.kirin.mt.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
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
import com.kirin.mt.core.player.BiliMediaDataSourceFactory
import com.kirin.mt.core.player.LivePlayInfo
import com.kirin.mt.core.player.PlaybackRepository
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.theme.BiliColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient

/** 直播默认请求清晰度:原画。服务端不可用时自动降级。 */
private const val LiveDefaultQn = 10000

/** 直播播放器加载状态。 */
private sealed interface LiveLoadState {
  data object Loading : LiveLoadState
  data class Ready(val info: LivePlayInfo) : LiveLoadState
  data class Failed(val message: String) : LiveLoadState
}

/**
 * 直播播放器(独立于点播 [PlayerScreen])。直播不走 DASH/合成 MPD/弹幕/进度/分集,
 * 直接把 [LivePlayInfo.streamUrl] 喂给 HlsMediaSource 或 ProgressiveMediaSource(FLV)。
 * 支持清晰度切换(侧面板)。TV(D-pad)与移动端(触屏)共用:中心区域点按=播放/暂停,
 * 顶部条提供返回 + 清晰度入口。
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
  var controlsVisible by remember { mutableStateOf(true) }
  var showQualityPanel by remember { mutableStateOf(false) }
  var retryKey by remember { mutableIntStateOf(0) }

  val player = remember(roomId) {
    ExoPlayer.Builder(context).build()
  }
  val centerFocus = remember { FocusRequester() }
  val qualityButtonFocus = remember { FocusRequester() }

  DisposableEffect(player) {
    player.addListener(object : Player.Listener {
      override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
        // 用 MutableState 对象而非委托的局部 var:匿名对象里不能改捕获的局部 var,
        // 但可以改对象属性(.value)。
        isPlayingState.value = isPlayingChanged
      }
    })
    onDispose { player.release() }
  }

  LaunchedEffect(roomId, selectedQn, retryKey) {
    loadState = LiveLoadState.Loading
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

  // 控制条自动隐藏:播放中且无清晰度面板时 5s 后隐藏。
  LaunchedEffect(controlsVisible, isPlayingState.value, showQualityPanel) {
    if (controlsVisible && isPlayingState.value && !showQualityPanel) {
      delay(5000)
      if (isActive) controlsVisible = false
    }
  }

  // TV:进入时把焦点放到中心播放/暂停区,让 D-pad 中心键能直接控制播放。
  LaunchedEffect(Unit) {
    runCatching { centerFocus.requestFocus() }
  }

  // 返回:清晰度面板打开时先关面板,否则退出播放器。覆盖 TV 遥控器返回键与移动端系统返回。
  BackHandler {
    if (showQualityPanel) showQualityPanel = false else onBack()
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyUp && (event.key == Key.DirectionUp ||
              event.key == Key.DirectionDown ||
              event.key == Key.DirectionLeft ||
              event.key == Key.DirectionRight) -> {
            controlsVisible = true
            false // 不消费,让焦点在顶部条按钮间流转;中心键交给焦点按钮自身处理
          }
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
        }
      },
      update = { it.player = player },
    )

    // 中心播放/暂停区(触屏点按 + D-pad 中心键)。
    Box(
      modifier = Modifier
        .fillMaxSize()
        .focusRequester(centerFocus)
        .clickable {
          if (player.isPlaying) player.pause() else player.play()
          controlsVisible = true
        },
      contentAlignment = Alignment.Center,
    ) {
      if (loadState is LiveLoadState.Ready && !isPlayingState.value) {
        PlayPauseIcon(isPlaying = false)
      }
    }

    when (val state = loadState) {
      is LiveLoadState.Loading -> LoadingOverlay()
      is LiveLoadState.Failed -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        FeedStatusScreen(
          message = state.message,
          actionLabel = "重试",
          onAction = { retryKey++ }, // 触发 LaunchedEffect 重载
        )
      }
      else -> Unit
    }

    if (controlsVisible && loadState is LiveLoadState.Ready) {
      TopBar(
        title = request.title,
        currentQualityDesc = liveInfo?.qualities
          ?.firstOrNull { it.qn == selectedQn }?.description
          ?: liveInfo?.qualities?.firstOrNull()?.description
          ?: "清晰度",
        onBack = onBack,
        onOpenQuality = {
          showQualityPanel = true
          controlsVisible = true
        },
        qualityButtonFocus = qualityButtonFocus,
      )
    }

    if (showQualityPanel && liveInfo != null) {
      QualityPanel(
        qualities = liveInfo!!.qualities,
        selectedQn = selectedQn,
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
private fun TopBar(
  title: String,
  currentQualityDesc: String,
  onBack: () -> Unit,
  onOpenQuality: () -> Unit,
  qualityButtonFocus: FocusRequester,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(BiliColors.OverlayStrong)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "← 返回",
      color = BiliColors.TextPrimary,
      fontWeight = FontWeight.Bold,
      modifier = Modifier
        .clickable(onClick = onBack)
        .padding(horizontal = 8.dp, vertical = 6.dp),
    )
    Text(
      text = title,
      color = BiliColors.TextPrimary,
      maxLines = 1,
      textOverflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 12.dp),
    )
    Text(
      text = currentQualityDesc,
      color = BiliColors.BiliPink,
      fontWeight = FontWeight.Bold,
      modifier = Modifier
        .focusRequester(qualityButtonFocus)
        .clip(RoundedCornerShape(50))
        .background(BiliColors.SurfaceElevated)
        .clickable(onClick = onOpenQuality)
        .padding(horizontal = 12.dp, vertical = 6.dp),
    )
  }
}

@Composable
private fun QualityPanel(
  qualities: List<com.kirin.mt.core.player.LiveQuality>,
  selectedQn: Int,
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
    Column(
      modifier = Modifier
        .widthIn(max = 320.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(BiliColors.PlayerPanel)
        .padding(vertical = 8.dp),
    ) {
      Text(
        text = "清晰度",
        color = BiliColors.TextSecondary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
      )
      LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(qualities, key = { it.qn }) { quality ->
          val selected = quality.qn == selectedQn
          Text(
            text = quality.description.ifBlank { "qn ${quality.qn}" },
            color = if (selected) BiliColors.BiliPink else BiliColors.TextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onPick(quality.qn) }
              .padding(horizontal = 20.dp, vertical = 12.dp),
          )
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

@Composable
private fun BoxScope.PlayPauseIcon(isPlaying: Boolean) {
  Box(
    modifier = Modifier
      .size(88.dp)
      .clip(RoundedCornerShape(50))
      .background(BiliColors.OverlayStrong),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = if (isPlaying) "❚❚" else "▶",
      color = BiliColors.TextPrimary,
      fontWeight = FontWeight.Bold,
    )
  }
}