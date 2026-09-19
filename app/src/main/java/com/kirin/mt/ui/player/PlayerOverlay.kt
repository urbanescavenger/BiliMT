package com.kirin.mt.ui.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.image.buildOwnerAvatarRequest
import com.kirin.mt.core.image.buildVideoThumbnailRequest
import com.kirin.mt.core.model.SourceBili
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.FavoriteFolder
import com.kirin.mt.core.player.AirJumpSegment
import com.kirin.mt.core.player.DanmakuSettings
import com.kirin.mt.core.player.PlaybackInfo
import com.kirin.mt.core.player.PlaybackEpisode
import com.kirin.mt.core.player.PlaybackQuality
import com.kirin.mt.core.player.PlaybackTrack
import com.kirin.mt.core.player.PlaybackRequest
import com.kirin.mt.core.player.SubtitleTracks
import com.kirin.mt.core.player.PlaybackVideoMetadata
import com.kirin.mt.core.player.VideoshotData
import com.kirin.mt.core.player.VideoshotFrame
import com.kirin.mt.ui.common.ClockOverlay
import com.kirin.mt.ui.glass.biliLiquidGlassSurface
import com.kirin.mt.ui.i18n.convertChineseText
import com.kirin.mt.ui.i18n.currentUiLocale
import com.kirin.mt.ui.i18n.formatCompactCount
import com.kirin.mt.ui.mobile.home.formatCount
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class PlayerControl {
  Episodes,
  Up,
  Related,

  /**
   * P11-123:画质/字幕直连入口——底栏一键直达对应面板,不再走「设置 → Main 面板 → 子面板」两步。
   * 这两个按钮是**带当前值**的形态([isValueBearing]):画质按钮显示当前档(+编码),字幕按钮显示
   * 当前轨(未开字幕显示「关闭」)——即把原本只在右下状态区显示的画质文案移进按钮里(占原显示位)。
   * 枚举位置决定 **YouTube** 控制行顺序(availableControls 按 entries 过滤),故紧跟 Related;
   * B站 版式改用显式顺序 [BiliPlayerControls],与这里的声明顺序无关。
   */
  Quality,
  Subtitle,

  Like,
  Coin,
  Favorite,
  ToView,
  Comment,
  Settings,

  // ── P11-124:B站 版式控制行的三个新入口(点击即生效,不开子面板)。只在 [BiliPlayerControls]
  // (!isYoutube 路径)里出现;YouTube 由 availableControls() 显式过滤为 false,版式/行为一字不变。
  /** 倍速文案按钮(值 = 现有 playbackSpeed.speedText())→ Speed 面板。 */
  Speed,

  /** 刷新:重新解析并重载当前视频,位置保持当前播放点(见 PlayerScreen.refreshPlayback)。 */
  Refresh,

  /** 弹幕开关:直接翻转 danmakuSettings.enabled(与设置面板里那一项同源)。 */
  DanmakuToggle,

  /**
   * TODO(P11-124):官方控制行还有「画面旋转」与「播放序列」,本轮**刻意不做**(宁缺勿假):
   * 旋转要改 AndroidView(PlayerView) 的渲染层(旋转角需与宽高比/弹幕层联动),播放序列要先加
   * 两态播放模式(列表连播 / 单视频循环)。两者各自的 vector drawable 已按设计稿建好备用
   * (ic_player_rotate / ic_player_sequence),下一轮补行为时再把枚举项与 [BiliPlayerControls] 补上。
   */
}

/**
 * P11-124:B站 版式控制行的项与**顺序**(对齐官方客户端底栏:倍速文案 → 入口图标… → 画质值按钮 → 设置)。
 *
 * 与 YouTube(沿用 [PlayerControl.entries] 过滤出的 availableControls)分开维护:两边项集与顺序都不同
 * (B站:无「点赞/投币/收藏/稍后再看/评论」——它们搬到了顶部动作行;多了倍速/刷新/弹幕开关,且字幕恒显示)。
 */
internal val BiliPlayerControls: List<PlayerControl> = listOf(
  PlayerControl.Speed,
  PlayerControl.Up,
  // TODO(P11-124):此处应插「画面旋转」(ic_player_rotate 已建),待 PlayerView 渲染层支持旋转后补。
  PlayerControl.Refresh,
  PlayerControl.Subtitle,
  PlayerControl.DanmakuToggle,
  // TODO(P11-124):此处应插「播放序列」(ic_player_sequence 已建),待两态播放模式落地后补。
  PlayerControl.Episodes,
  PlayerControl.Related,
  PlayerControl.Quality,
  PlayerControl.Settings,
)

/**
 * P11-124:B站 版式**顶部动作行**的项与顺序(官方在标题下方:点赞 / 收藏 / 投币 / 稍后再看 / 评论)。
 * 与 YouTube 底栏那三个带计数按钮不同:动作行只有图标 + 文字、不带计数(计数在元信息行),
 * 显隐仍按既有判据过滤(见 PlayerScreen.biliActionControls)。
 */
internal val BiliPlayerActions: List<PlayerControl> = listOf(
  PlayerControl.Like,
  PlayerControl.Favorite,
  PlayerControl.Coin,
  PlayerControl.ToView,
  PlayerControl.Comment,
)

internal enum class PlayerPanel {
  None,
  Main,
  Quality,
  Audio,
  Subtitle,
  Danmaku,
  Speed,
  Episodes,
  UpVideos,
  RelatedVideos,
  Favorite,
}

/**
 * P11-119/120:Main 面板末尾两个**条件项**的可见性与行号。
 *
 * Main 面板前 3 项固定(0 清晰度 / 1 弹幕 / 2 倍速),之后按「音轨 → 字幕」顺序追加,
 * 各自是否存在由视频本身决定(多音轨视频才有音轨项;有字幕的视频才有字幕项)。
 * 行号必须与 [PlayerScreen.activateFocusedPanelItem] 的分支严格一致,故集中在这里算一次。
 */
internal fun hasAudioTrackChoice(info: PlaybackInfo?): Boolean = (info?.availableAudioTracks?.size ?: 0) > 1

internal fun hasSubtitleChoice(info: PlaybackInfo?): Boolean = !info?.subtitleTracks.isNullOrEmpty()

/** Main 面板「音轨」项行号(紧跟固定 3 项)。 */
internal const val MainAudioRowIndex = 3

/** Main 面板「字幕」项行号(紧跟音轨项之后)。 */
internal fun mainSubtitleRowIndex(info: PlaybackInfo?): Int =
  MainAudioRowIndex + if (hasAudioTrackChoice(info)) 1 else 0

/** 字幕面板项数 = 「关闭」+ 每条字幕轨。 */
internal fun subtitlePanelItemCount(info: PlaybackInfo?): Int = 1 + (info?.subtitleTracks?.size ?: 0)

@Composable
internal fun BoxScope.PlayerOverlay(
  request: PlaybackRequest,
  info: PlaybackInfo,
  actualQuality: PlaybackQuality?,
  /** P11-119:当前音轨 id(activeRequest.preferredAudioTrackId,未选则服务器声明默认轨)——供音轨面板打勾。 */
  currentAudioTrackId: String?,
  /** P11-120:当前选中的字幕轨 id(null=关闭)——供 Main 面板字幕项显示与字幕面板打勾。 */
  currentSubtitleTrackId: Int?,
  metadata: PlaybackVideoMetadata?,
  sidePanelVideos: List<VideoSummary>,
  sidePanelLoading: Boolean,
  upVideoOrder: String,
  upFollowed: Boolean,
  upFollowLoading: Boolean,
  playbackPaused: Boolean,
  showPauseIndicator: Boolean,
  seekPreviewSpritesEnabled: Boolean,
  videoshotData: VideoshotData?,
  videoshotSprites: Map<String, ImageBitmap>,
  onlineCountText: String,
  currentCodecText: String,
  showUnfollowConfirm: Boolean,
  unfollowConfirmFocusedConfirm: Boolean,
  controlsVisible: Boolean,
  focusedControl: PlayerControl,
  availableControls: List<PlayerControl>,
  progressFocused: Boolean,
  /** P11-124:B站 版式顶部动作行的项(点赞/收藏/投币/稍后再看/评论,已按可交互性过滤;YouTube 恒空)。 */
  actionControls: List<PlayerControl>,
  /** P11-124:动作行当前焦点项下标(仅 [actionFocused] 为真时有意义)。 */
  focusedActionIndex: Int,
  /** P11-124:焦点是否在动作行——三级焦点 动作行 → 进度条 → 控制行,焦点唯一。 */
  actionFocused: Boolean,
  activePanel: PlayerPanel,
  focusedPanelIndex: Int,
  playbackSpeed: Float,
  danmakuSettings: DanmakuSettings,
  positionState: State<Long>,
  durationState: State<Long>,
  bufferedPercentageState: State<Long>,
  airJumpSegments: List<AirJumpSegment>,
  previewPositionMs: Long?,
  showClock: Boolean,
  clockText: String,
  showMiniProgressBar: Boolean,
  likeCount: Int,
  liked: Boolean,
  coinCount: Int,
  coined: Boolean,
  favCount: Int,
  faved: Boolean,
  favFolders: List<FavoriteFolder>,
  favLoading: Boolean,
  favSelectedIds: Set<Long>,
  showCoinDialog: Boolean,
  coinDialogFocusedIndex: Int,
) {
  if (controlsVisible) {
    // P11-124:按源分版式——只有 B站 源走官方版式(B站=SourceBili,含 UGC 与 PGC/番剧)。
    // YouTube 与影视库(TVBox)/IPTV/红果 一律保持老版式:它们没有 B站 的三连/UP主/弹幕数,
    // 套官方底栏反而会出现「弹幕开关」这种无意义入口。
    if (request.source != SourceBili) {
      PlayerTopOverlay(
        request = request,
        title = info.title,
        showClock = showClock,
        clockText = clockText,
        modifier = Modifier.align(Alignment.TopCenter),
      )
      PlayerBottomOverlay(
        request = request,
        info = info,
        actualQuality = actualQuality,
        currentSubtitleTrackId = currentSubtitleTrackId,
        availableControls = availableControls,
        focusedControl = focusedControl,
        progressFocused = progressFocused,
        positionState = positionState,
        durationState = durationState,
        bufferedPercentageState = bufferedPercentageState,
        airJumpSegments = airJumpSegments,
        previewPositionMs = previewPositionMs,
        danmakuSettings = danmakuSettings,
        onlineCountText = onlineCountText,
        currentCodecText = currentCodecText,
        likeCount = likeCount,
        liked = liked,
        coinCount = coinCount,
        coined = coined,
        favCount = favCount,
        faved = faved,
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    } else {
      BiliTopInfoOverlay(
        request = request,
        title = info.title,
        actionControls = actionControls,
        focusedActionIndex = focusedActionIndex,
        actionFocused = actionFocused,
        liked = liked,
        coined = coined,
        faved = faved,
        likeCount = likeCount,
        coinCount = coinCount,
        favCount = favCount,
        showClock = showClock,
        clockText = clockText,
        modifier = Modifier.align(Alignment.TopCenter),
      )
      BiliControlBarOverlay(
        info = info,
        actualQuality = actualQuality,
        availableControls = availableControls,
        focusedControl = focusedControl,
        progressFocused = progressFocused,
        actionFocused = actionFocused,
        playbackSpeed = playbackSpeed,
        currentCodecText = currentCodecText,
        // P11-124:字幕槽的压暗判据(无选中轨 = 关闭 → TextTertiary),与控制行同一份状态。
        currentSubtitleTrackId = currentSubtitleTrackId,
        danmakuEnabled = danmakuSettings.enabled,
        positionState = positionState,
        durationState = durationState,
        bufferedPercentageState = bufferedPercentageState,
        airJumpSegments = airJumpSegments,
        previewPositionMs = previewPositionMs,
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  } else {
    if (showClock) {
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
    if (showMiniProgressBar) {
      MiniProgressBar(
        positionState = positionState,
        durationState = durationState,
        airJumpSegments = airJumpSegments,
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }

  if (previewPositionMs != null) {
    SeekPreviewOverlay(
      previewPositionMs = previewPositionMs,
      durationMs = durationState.value,
      videoshotData = if (seekPreviewSpritesEnabled) videoshotData else null,
      videoshotSprites = videoshotSprites,
      modifier = Modifier.align(Alignment.Center),
    )
  } else if (playbackPaused && showPauseIndicator) {
    PauseIndicatorOverlay(modifier = Modifier.align(Alignment.Center))
  }

  if (activePanel != PlayerPanel.None) {
    when (activePanel) {
      PlayerPanel.Main,
      PlayerPanel.Quality,
      PlayerPanel.Audio,
      PlayerPanel.Subtitle,
      PlayerPanel.Danmaku,
      PlayerPanel.Speed -> PlayerSettingsPanel(
        activePanel = activePanel,
        focusedIndex = focusedPanelIndex,
        info = info,
        actualQuality = actualQuality,
        currentAudioTrackId = currentAudioTrackId,
        currentSubtitleTrackId = currentSubtitleTrackId,
        currentCodecText = currentCodecText,
        playbackSpeed = playbackSpeed,
        danmakuSettings = danmakuSettings,
        modifier = Modifier.align(Alignment.CenterEnd),
      )
      PlayerPanel.Episodes -> PlayerEpisodesPanel(
        episodes = metadata?.pages.orEmpty(),
        currentCid = request.cid,
        // 影视库:当前选集按集索引高亮(分P 页号=选集索引)。
        currentEpisodePage = request.tvboxEpisodeIndex.takeIf { request.isTvbox },
        focusedIndex = focusedPanelIndex,
        modifier = Modifier.align(Alignment.CenterEnd),
      )
      PlayerPanel.UpVideos -> PlayerVideoListPanel(
        titleRes = R.string.player_panel_up_videos,
        request = request,
        videos = sidePanelVideos,
        loading = sidePanelLoading,
        focusedIndex = focusedPanelIndex,
        showUploaderHeader = true,
        upVideoOrder = upVideoOrder,
        upFollowed = upFollowed,
        upFollowLoading = upFollowLoading,
        modifier = Modifier.align(Alignment.CenterEnd),
      )
      PlayerPanel.RelatedVideos -> PlayerVideoListPanel(
        titleRes = R.string.player_panel_related_videos,
        request = request,
        videos = sidePanelVideos,
        loading = sidePanelLoading,
        focusedIndex = focusedPanelIndex,
        showUploaderHeader = false,
        upVideoOrder = upVideoOrder,
        upFollowed = upFollowed,
        upFollowLoading = upFollowLoading,
        modifier = Modifier.align(Alignment.CenterEnd),
      )
      PlayerPanel.Favorite -> PlayerFavoritePanel(
        folders = favFolders,
        loading = favLoading,
        selectedIds = favSelectedIds,
        focusedIndex = focusedPanelIndex,
        modifier = Modifier.align(Alignment.CenterEnd),
      )
      PlayerPanel.None -> Unit
    }
  }

  if (showUnfollowConfirm) {
    UnfollowConfirmDialog(
      focusedConfirm = unfollowConfirmFocusedConfirm,
      modifier = Modifier.align(Alignment.Center),
    )
  }

  if (showCoinDialog) {
    CoinConfirmDialog(
      focusedIndex = coinDialogFocusedIndex,
      modifier = Modifier.align(Alignment.Center),
    )
  }
}

@Composable
internal fun PauseIndicatorOverlay(modifier: Modifier = Modifier) {
  val shape = CircleShape
  Box(
    modifier = modifier
      .size(BiliSizing.PlayerPauseIndicatorSize)
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = true,
        surfaceColor = BiliColors.OverlayStrong,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val barWidth = size.width * 0.16f
      val barHeight = size.height * 0.48f
      val gap = size.width * 0.14f
      val left = (size.width - barWidth * 2f - gap) / 2f
      val top = (size.height - barHeight) / 2f
      val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
      drawRoundRect(
        color = BiliColors.TextPrimary,
        topLeft = Offset(left, top),
        size = Size(barWidth, barHeight),
        cornerRadius = radius,
      )
      drawRoundRect(
        color = BiliColors.TextPrimary,
        topLeft = Offset(left + barWidth + gap, top),
        size = Size(barWidth, barHeight),
        cornerRadius = radius,
      )
    }
  }
}

@Composable
private fun PlayerTopOverlay(
  request: PlaybackRequest,
  title: String,
  showClock: Boolean,
  clockText: String,
  modifier: Modifier = Modifier,
) {
  val displayTitle = convertChineseText(title.ifBlank { request.title })
  val ownerName = convertChineseText(request.ownerName)
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
    Column(
      modifier = Modifier
        .align(Alignment.TopStart)
        .fillMaxWidth()
        .padding(
          start = BiliSizing.PlayerOverlayHorizontalPadding,
          top = BiliSizing.PlayerTopPadding,
          end = if (showClock) BiliSizing.PlayerTopTimeReservedWidth else BiliSizing.PlayerOverlayHorizontalPadding,
        ),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
    ) {
      Text(
        text = displayTitle,
        color = BiliColors.TextPrimary,
        fontSize = BiliTypography.PlayerTitle,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      val pubdate = request.formatPubdate()
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
        if (pubdate != null) {
          PlayerMetaItem(
            iconRes = R.drawable.ic_player_calendar,
            text = stringResource(R.string.player_meta_pubdate, pubdate),
          )
        }
        if (request.viewCount > 0) {
          PlayerMetaItem(
            iconRes = R.drawable.ic_video_play_count,
            text = stringResource(R.string.player_meta_view_count, request.viewCount.formatCompactCountText()),
          )
        }
      }
    }
    if (showClock) {
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
}

@Composable
internal fun PlayerMetaItem(
  @DrawableRes iconRes: Int,
  text: String,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = null,
      tint = BiliColors.TextSecondary,
      modifier = Modifier.size(BiliSizing.VideoOverlayIconSize),
    )
    Spacer(modifier = Modifier.width(BiliSpacing.Xs))
    Text(
      text = text,
      color = BiliColors.TextSecondary,
      fontSize = BiliTypography.PlayerMeta,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * P11-124:B站 版式顶部信息块(左上)——标题(2 行)/ 元信息(单行,超长直接裁掉)/ 动作行(点赞·收藏·投币·稍后再看·评论)。
 *
 * 对齐官方客户端:三连不在底栏而在标题下方,且**不带计数**(计数进元信息行);无图标元信息(官方是纯文字单行)。
 * YouTube 仍走 [PlayerTopOverlay](图标 + 省略号那套),两者互不影响。
 */
@Composable
private fun BiliTopInfoOverlay(
  request: PlaybackRequest,
  title: String,
  actionControls: List<PlayerControl>,
  focusedActionIndex: Int,
  actionFocused: Boolean,
  liked: Boolean,
  coined: Boolean,
  faved: Boolean,
  likeCount: Int,
  coinCount: Int,
  favCount: Int,
  showClock: Boolean,
  clockText: String,
  modifier: Modifier = Modifier,
) {
  val displayTitle = convertChineseText(title.ifBlank { request.title })
  val metaText = biliMetaText(
    request = request,
    likeCount = likeCount,
    coinCount = coinCount,
    favCount = favCount,
  )
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerOfficialTopGradientHeight)
      .background(
        Brush.verticalGradient(
          colors = listOf(BiliColors.OverlayStrong, BiliColors.OverlayTransparent),
        ),
      ),
  ) {
    Column(
      modifier = Modifier
        .align(Alignment.TopStart)
        .fillMaxWidth()
        .padding(
          start = BiliSizing.PlayerOverlayHorizontalPadding,
          top = BiliSizing.PlayerOfficialTopPadding,
          end = if (showClock) BiliSizing.PlayerTopTimeReservedWidth else BiliSizing.PlayerOverlayHorizontalPadding,
        ),
    ) {
      Text(
        text = displayTitle,
        color = BiliColors.TextPrimary,
        fontSize = BiliTypography.PlayerOfficialTitle,
        fontWeight = FontWeight.Bold,
        // 官方:最多两行,超出省略(不设 minLines——短标题只占一行)。
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (metaText.isNotBlank()) {
        Spacer(modifier = Modifier.height(BiliSizing.PlayerOfficialTitleMetaGap))
        Text(
          text = metaText,
          color = BiliColors.TextSecondary,
          fontSize = BiliTypography.PlayerOfficialMeta,
          // 官方:单行不换行、超长直接裁掉(不用省略号)。
          maxLines = 1,
          overflow = TextOverflow.Clip,
        )
      }
      if (actionControls.isNotEmpty()) {
        Spacer(modifier = Modifier.height(BiliSizing.PlayerOfficialMetaActionsGap))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(BiliSizing.PlayerOfficialActionSpacing),
        ) {
          actionControls.forEachIndexed { index, control ->
            BiliActionItem(
              iconRes = control.iconRes,
              label = stringResource(control.labelRes),
              active = when (control) {
                PlayerControl.Like -> liked
                PlayerControl.Coin -> coined
                PlayerControl.Favorite -> faved
                else -> false
              },
              focused = actionFocused && index == focusedActionIndex,
            )
          }
        }
      }
    }
    if (showClock) {
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
}

/**
 * P11-124:B站 版式元信息行:`UP主 · 1.3万播放 · 13弹幕 · 544点赞 · 231收藏 · 63投币 · 发布于 …`。
 *
 * 用现有 `player_meta_separator` 串联;**值为 0 的项整段跳过**。计数口径按官方:播放量/弹幕数走
 * [formatCompactCountText](1.3万 / 13),三连(点赞/收藏/投币)直接用原数字(官方不缩写)。
 */
@Composable
private fun biliMetaText(
  request: PlaybackRequest,
  likeCount: Int,
  coinCount: Int,
  favCount: Int,
): String {
  val separator = stringResource(R.string.player_meta_separator)
  val pubdate = request.formatPubdate()
  return listOf(
    convertChineseText(request.ownerName).takeIf { it.isNotBlank() }.orEmpty(),
    if (request.viewCount > 0) {
      stringResource(R.string.player_meta_bili_view_count, request.viewCount.formatCompactCountText())
    } else {
      ""
    },
    if (request.danmakuCount > 0) {
      stringResource(R.string.player_meta_bili_danmaku_count, request.danmakuCount.formatCompactCountText())
    } else {
      ""
    },
    if (likeCount > 0) stringResource(R.string.player_meta_bili_like_count, likeCount.toString()) else "",
    if (favCount > 0) stringResource(R.string.player_meta_bili_favorite_count, favCount.toString()) else "",
    if (coinCount > 0) stringResource(R.string.player_meta_bili_coin_count, coinCount.toString()) else "",
    pubdate?.let { stringResource(R.string.player_meta_pubdate, it) }.orEmpty(),
  )
    .filter(String::isNotBlank)
    .joinToString(separator)
}

/** P11-124:动作行单项——图标(23dp)+ 7dp 间隔 + 文字(15sp),激活态整项 BiliPink 着色。 */
@Composable
private fun BiliActionItem(
  @DrawableRes iconRes: Int,
  label: String,
  active: Boolean,
  focused: Boolean,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  val tint = if (active) BiliColors.BiliPink else BiliColors.TextPrimary
  Row(
    modifier = Modifier
      // 焦点态与控制行同一套:获焦铺 PlayerControlFocused 粉色玻璃,未获焦透明。
      .playerFocusedLiquidGlassSurface(
        shape = shape,
        focused = focused,
        surfaceColor = BiliColors.PlayerControlFocused,
      )
      .padding(horizontal = BiliSpacing.Sm, vertical = BiliSpacing.Xs)
      .height(BiliSizing.PlayerOfficialActionIconSize)
      .semantics { contentDescription = label },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = null,
      tint = tint,
      modifier = Modifier.size(BiliSizing.PlayerOfficialActionIconSize),
    )
    Spacer(modifier = Modifier.width(BiliSizing.PlayerOfficialActionIconGap))
    Text(
      text = label,
      color = tint,
      fontSize = BiliTypography.PlayerOfficialAction,
      fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
      maxLines = 1,
    )
  }
}

/**
 * P11-124:B站 版式底部——细进度条行(结构沿用 [PlayerBottomOverlay]:bar + Spacer + 时间)+ 控制行。
 *
 * 控制行 = 倍速文案 → 图标×7 → 画质文案 → 设置(顺序见 [BiliPlayerControls]),每项都是同一固定宽度的
 * 等宽槽(见 [BiliControlSlot]),故整行等宽等距。原右下角状态文案(在看/弹幕/画质)官方没有,
 * 故 B站 路径整块不渲染(画质档位改由画质槽的 contentDescription 承载,屏上只显示「HD」)。
 */
@Composable
private fun BiliControlBarOverlay(
  info: PlaybackInfo,
  actualQuality: PlaybackQuality?,
  availableControls: List<PlayerControl>,
  focusedControl: PlayerControl,
  progressFocused: Boolean,
  actionFocused: Boolean,
  playbackSpeed: Float,
  currentCodecText: String,
  /** P11-123/P11-124:当前字幕轨 id(null=关闭)——控制行字幕槽据此压暗,与弹幕开关同一个灰。 */
  currentSubtitleTrackId: Int?,
  /** P11-124:弹幕开关的状态(关掉时控制行那枚图标压暗)。 */
  danmakuEnabled: Boolean,
  positionState: State<Long>,
  durationState: State<Long>,
  bufferedPercentageState: State<Long>,
  airJumpSegments: List<AirJumpSegment>,
  previewPositionMs: Long?,
  modifier: Modifier = Modifier,
) {
  val speedLabel = playbackSpeed.speedTextBadge()
  // 真实档位仍算出来,只是不再上屏 —— 改由画质槽的 contentDescription 播报。
  val qualityText = (actualQuality ?: info.selectedQuality).description.withCodecLabel(currentCodecText)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerBottomGradientHeight)
      .background(
        Brush.verticalGradient(
          colors = listOf(BiliColors.OverlayTransparent, BiliColors.OverlayStrong),
        ),
      )
      .padding(
        start = BiliSizing.PlayerOverlayHorizontalPadding,
        end = BiliSizing.PlayerOverlayHorizontalPadding,
        bottom = BiliSizing.PlayerOfficialBottomPadding,
      ),
    verticalArrangement = Arrangement.Bottom,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BiliProgressBar(
        positionState = positionState,
        durationState = durationState,
        bufferedPercentageState = bufferedPercentageState,
        airJumpSegments = airJumpSegments,
        isFocused = progressFocused,
        previewPositionMs = previewPositionMs,
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.width(BiliSpacing.Xl))
      PlayerTimeText(
        positionState = positionState,
        durationState = durationState,
        previewPositionMs = previewPositionMs,
      )
    }
    Spacer(modifier = Modifier.height(BiliSizing.PlayerOfficialRowsGap))
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(BiliSizing.PlayerOfficialControlSpacing),
    ) {
      availableControls.forEachIndexed { index, control ->
        // P11-124:焦点唯一——动作行或进度条夺焦时控制行不高亮。
        val focused = !progressFocused && !actionFocused && focusedControl == control
        when (control) {
          // 倍速 = 纯文案槽(官方底栏第一项),点击出倍速列表(PlayerPanel.Speed)。
          PlayerControl.Speed -> BiliTextControl(
            text = speedLabel,
            contentDescription = "${stringResource(control.labelRes)} $speedLabel",
            focused = focused,
          )
          // 画质 = 纯文字「HD」(与倍速同款等宽槽)。用户要求不再显示 `1080P60(H.264)` 长文案,
          // 真实档位信息改由 contentDescription 承载(焦点/无障碍播报仍能听到当前档)。
          PlayerControl.Quality -> BiliTextControl(
            text = stringResource(R.string.player_quality_badge),
            contentDescription = "${stringResource(R.string.player_settings_quality)} $qualityText",
            focused = focused,
          )
          // 其余全是同宽槽内居中的裸图标(官方无底块、无玻璃)。
          else -> BiliIconControl(
            iconRes = control.iconRes,
            contentDescription = stringResource(control.labelRes),
            tint = when {
              // 弹幕开关带开关态——关掉时整枚图标压暗,一眼能看出当前是开还是关。
              control == PlayerControl.DanmakuToggle && !danmakuEnabled -> BiliColors.TextTertiary
              // 字幕同理:未选中任何轨(= 关闭)时压暗,与弹幕开关用同一个灰(TextTertiary)。
              control == PlayerControl.Subtitle && currentSubtitleTrackId == null -> BiliColors.TextTertiary
              else -> BiliColors.TextPrimary
            },
            focused = focused,
          )
        }
      }
    }
  }
}

/**
 * P11-124:B站 控制行的**等宽槽**——每项(图标或文案)都放进同尺寸槽并居中,整行因此等宽等距。
 *
 * 焦点态用我们原来的着色:获焦槽铺 [BiliColors.PlayerControlFocused] 粉色玻璃(圆角 [BiliRadius.Card]);
 * 未获焦**保持透明**(官方是裸图标,不加 idle 底块;获焦才铺粉底)。槽内图标/文字的 tint 不随焦点变粉(靠底色区分),
 * 只按语义压暗(弹幕关 / 字幕关 → [BiliColors.TextTertiary])。
 */
@Composable
private fun BiliControlSlot(
  contentDescription: String,
  focused: Boolean,
  content: @Composable () -> Unit,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  Box(
    modifier = Modifier
      // P11-124:按内容宽度排列(用户二轮反馈「不需要分散,靠左紧挨」)——撤掉原先的 56dp 等宽槽:
      // 等宽是为了让长文案不挤行,但真机看着每项之间空太多。现在图标/文案各按自身宽度,靠小间距(8dp)
      // 紧挨着排,整行仍靠左;左右各留 4dp 内边距,让获焦的粉底比内容略宽一圈。
      .height(BiliSizing.PlayerOfficialControlBoxSize)
      .playerFocusedLiquidGlassSurface(
        shape = shape,
        focused = focused,
        surfaceColor = BiliColors.PlayerControlFocused,
      )
      .padding(horizontal = BiliSpacing.Xs)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

/** P11-124:B站 控制行的图标项(按内容宽排列;官方无底块,焦点态见 [BiliControlSlot])。 */
@Composable
private fun BiliIconControl(
  @DrawableRes iconRes: Int,
  contentDescription: String,
  focused: Boolean,
  tint: Color = BiliColors.TextPrimary,
) {
  BiliControlSlot(contentDescription = contentDescription, focused = focused) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = null,
      tint = tint,
      modifier = Modifier.size(BiliSizing.PlayerOfficialControlIconSize),
    )
  }
}

/** P11-124:B站 控制行的文案项(倍速 `1.0x` / 画质 `HD`),19sp 粗体白,按内容宽排布。 */
@Composable
private fun BiliTextControl(
  text: String,
  contentDescription: String,
  focused: Boolean,
) {
  BiliControlSlot(contentDescription = contentDescription, focused = focused) {
    Text(
      text = text,
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerOfficialControlValue,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

/**
 * P11-124:B站 版式进度条:几何照官方(高 5dp / 圆角 3dp / 滑块 13dp),**配色回到我们原来的**
 * ——轨道 [BiliColors.ProgressTrack]、缓冲 [BiliColors.ProgressBuffered]、已播段与滑块 [BiliColors.BiliPink]。
 *
 * 与 [TvProgressBar](老版式路径)分开:这里不随焦点放大条/滑块(几何固定),获焦只在滑块外补一圈
 * [BiliColors.PlayerFocusGlow] 白色光晕(我们原来表示「进度条获焦」的做法),保证焦点肉眼可辨。
 */
@Composable
private fun BiliProgressBar(
  positionState: State<Long>,
  durationState: State<Long>,
  bufferedPercentageState: State<Long>,
  airJumpSegments: List<AirJumpSegment>,
  isFocused: Boolean,
  previewPositionMs: Long?,
  modifier: Modifier = Modifier,
) {
  Canvas(
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerOfficialProgressRowHeight),
  ) {
    val durationMs = durationState.value
    val progress = progressFraction(previewPositionMs ?: positionState.value, durationMs)
    val buffered = (bufferedPercentageState.value / 100f).coerceIn(0f, 1f)
    val centerY = size.height / 2f
    val barHeight = BiliSizing.PlayerOfficialProgressHeight.toPx()
    val radius = BiliSizing.PlayerOfficialProgressRadius.toPx()

    drawRoundBar(1f, centerY, barHeight, radius, BiliColors.ProgressTrack)
    if (buffered > 0f) {
      drawRoundBar(buffered, centerY, barHeight, radius, BiliColors.ProgressBuffered)
    }
    drawRoundBar(progress, centerY, barHeight, radius, BiliColors.BiliPink)
    drawAirJumpSegments(airJumpSegments, durationMs, centerY, barHeight, radius)

    val knobRadius = BiliSizing.PlayerOfficialProgressKnobSize.toPx() / 2f
    val knobCenterX = (size.width * progress).coerceIn(knobRadius, (size.width - knobRadius).coerceAtLeast(knobRadius))
    if (isFocused) {
      // 焦点可见性:几何不变(滑块仍 13dp),只在滑块外补一圈白色光晕(我们原来表示「进度条获焦」的做法)。
      // 半径 1.6× 保证在 21dp 行高内够醒目(10.4dp < 行半高 10.5dp),一眼能看出焦点在进度条上。
      drawCircle(
        color = BiliColors.PlayerFocusGlow,
        radius = knobRadius * 1.6f,
        center = Offset(knobCenterX, centerY),
      )
    }
    drawCircle(
      color = BiliColors.BiliPink,
      radius = knobRadius,
      center = Offset(knobCenterX, centerY),
    )
  }
}

@Composable
private fun PlayerBottomOverlay(
  request: PlaybackRequest,
  info: PlaybackInfo,
  actualQuality: PlaybackQuality?,
  /** P11-123:当前字幕轨 id(null=关闭)——供「字幕」值按钮显示当前轨名。 */
  currentSubtitleTrackId: Int?,
  availableControls: List<PlayerControl>,
  focusedControl: PlayerControl,
  progressFocused: Boolean,
  positionState: State<Long>,
  durationState: State<Long>,
  bufferedPercentageState: State<Long>,
  airJumpSegments: List<AirJumpSegment>,
  previewPositionMs: Long?,
  danmakuSettings: DanmakuSettings,
  onlineCountText: String,
  currentCodecText: String,
  likeCount: Int,
  liked: Boolean,
  coinCount: Int,
  coined: Boolean,
  favCount: Int,
  faved: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerBottomGradientHeight)
      .background(
        Brush.verticalGradient(
          colors = listOf(BiliColors.OverlayTransparent, BiliColors.OverlayStrong),
        ),
      )
      .padding(
        start = BiliSizing.PlayerOverlayHorizontalPadding,
        end = BiliSizing.PlayerOverlayHorizontalPadding,
        bottom = BiliSizing.PlayerBottomPadding,
      ),
    verticalArrangement = Arrangement.Bottom,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TvProgressBar(
        positionState = positionState,
        durationState = durationState,
        bufferedPercentageState = bufferedPercentageState,
        airJumpSegments = airJumpSegments,
        isFocused = progressFocused,
        previewPositionMs = previewPositionMs,
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.width(BiliSpacing.Xl))
      PlayerTimeText(
        positionState = positionState,
        durationState = durationState,
        previewPositionMs = previewPositionMs,
      )
    }
    Spacer(modifier = Modifier.height(BiliSpacing.Lg))
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      availableControls.forEachIndexed { index, control ->
        val focused = !progressFocused && focusedControl == control
        if (control.isAction) {
          val count: Int
          val active: Boolean
          when (control) {
            PlayerControl.Like -> {
              count = likeCount
              active = liked
            }
            PlayerControl.Coin -> {
              count = coinCount
              active = coined
            }
            PlayerControl.Favorite -> {
              count = favCount
              active = faved
            }
            else -> {
              count = 0
              active = false
            }
          }
          PlayerActionButton(
            iconRes = control.iconRes,
            contentDescription = stringResource(control.labelRes),
            count = count,
            active = active,
            focused = focused,
          )
        } else if (control.isValueBearing) {
          // P11-123:画质/字幕 = 「当前值」形态,点击直达对应面板。
          val valueText = when (control) {
            // 画质:当前实际播放档 + 编码标签,与右下状态区原先那条文案同源同形(文案搬进按钮)。
            PlayerControl.Quality -> (actualQuality ?: info.selectedQuality)
              .description
              .withCodecLabel(currentCodecText)
            // 字幕:当前选中轨名(自动生成轨带「自动」标);未开字幕显示「关闭」——与字幕面板 index 0 同词。
            PlayerControl.Subtitle -> info.subtitleTracks
              .firstOrNull { it.id == currentSubtitleTrackId }
              ?.let { subtitleRowTitle(it) }
              ?: stringResource(R.string.player_subtitle_off)
            else -> ""
          }
          PlayerValueButton(
            iconRes = control.valueIconRes,
            // 无障碍播报「标签 + 当前值」:画质按钮无图标,标签不能只靠图标承载。
            contentDescription = "${stringResource(control.labelRes)} $valueText",
            value = valueText,
            focused = focused,
          )
        } else {
          PlayerIconButton(
            iconRes = control.iconRes,
            contentDescription = stringResource(control.labelRes),
            focused = focused,
          )
        }
        if (index != availableControls.lastIndex) {
          Spacer(modifier = Modifier.width(BiliSpacing.Xl))
        }
      }
      Spacer(modifier = Modifier.weight(1f))
      PlayerStatusTexts(
        request = request,
        info = info,
        actualQuality = actualQuality,
        danmakuSettings = danmakuSettings,
        onlineCountText = onlineCountText,
        currentCodecText = currentCodecText,
        // P11-123:底栏有「画质」值按钮时,当前档文案已经搬进按钮(占原显示位),状态区不再重复一遍。
        showQualityText = PlayerControl.Quality !in availableControls,
      )
    }
  }
}

@Composable
private fun PlayerIconButton(
  @DrawableRes iconRes: Int,
  contentDescription: String,
  focused: Boolean,
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
      ),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = contentDescription,
      tint = BiliColors.TextPrimary,
      modifier = Modifier.size(BiliSizing.PlayerControlIconSize),
    )
  }
}

/**
 * P11-123:「当前值」控制按钮(画质/字幕)。
 *
 * 形态与 [PlayerIconButton] 同一套液态玻璃/焦点光晕,但**宽度自适应**(可带图标 + 文本),因为它承载的是
 * 原本只在右下状态区显示的当前值(如 `1080p60(AV1)`),固定 60dp 方块放不下。高度与图标按钮一致(60dp),
 * 故与同排按钮基线对齐。文本样式对齐被它接管的那条状态文案(PlayerStatus/次级字号 + 粗体)。
 * [iconRes] 为 null 时是纯文字按钮(画质按钮即如此,用户要求去掉 HD 标志)。
 */
@Composable
private fun PlayerValueButton(
  @DrawableRes iconRes: Int?,
  contentDescription: String,
  value: String,
  focused: Boolean,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  Row(
    modifier = Modifier
      .height(BiliSizing.PlayerControlIconButtonSize)
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = focused,
        surfaceColor = if (focused) BiliColors.PlayerControlFocused else BiliColors.PlayerControlIdle,
      )
      .semantics { this.contentDescription = contentDescription }
      .padding(horizontal = BiliSpacing.Lg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
  ) {
    if (iconRes != null) {
      Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = BiliColors.TextPrimary,
        modifier = Modifier.size(BiliSizing.PlayerControlIconSize),
      )
    }
    Text(
      text = value,
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerStatus,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * 互动按钮(点赞/投币/收藏):图标 + 下方计数,激活时 BiliPink 着色。
 * 尺寸与 PlayerIconButton 等宽,高度略增以容纳计数文本。
 */
@Composable
private fun PlayerActionButton(
  @DrawableRes iconRes: Int,
  contentDescription: String,
  count: Int,
  active: Boolean,
  focused: Boolean,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  val context = LocalContext.current
  val tint = if (active) BiliColors.BiliPink else BiliColors.TextPrimary
  Column(
    modifier = Modifier
      .width(BiliSizing.PlayerControlIconButtonSize)
      .height(BiliSizing.PlayerControlIconButtonSize)
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = focused,
        surfaceColor = if (focused) BiliColors.PlayerControlFocused else BiliColors.PlayerControlIdle,
      )
      .padding(vertical = BiliSpacing.Xs),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(BiliSizing.PlayerControlIconSize - 6.dp),
    )
    Spacer(modifier = Modifier.height(BiliSpacing.Xxs))
    Text(
      text = formatCount(count, context.resources),
      color = tint,
      fontSize = 11.sp,
      fontWeight = if (active || focused) FontWeight.Bold else FontWeight.Normal,
      maxLines = 1,
    )
  }
}

@Composable
internal fun Modifier.playerLiquidGlassSurface(
  shape: Shape,
  focused: Boolean,
  surfaceColor: Color,
): Modifier {
  val performancePolicy = LocalBiliPerformancePolicy.current
  val liquidGlassEnabled = performancePolicy.cinematicVisualEffectsEnabled && performancePolicy.liquidGlassCardsEnabled
  return biliLiquidGlassSurface(
    enabled = liquidGlassEnabled,
    shape = shape,
    surfaceColor = surfaceColor,
    borderColor = BiliColors.TextPrimary.copy(
      alpha = if (focused) {
        BiliFocus.LiquidGlassFocusedBorderAlpha
      } else {
        BiliFocus.LiquidGlassRestingBorderAlpha
      },
    ),
    borderWidth = BiliFocus.RestingBorderWidth,
  )
}

@Composable
internal fun Modifier.playerFocusedLiquidGlassSurface(
  shape: Shape,
  focused: Boolean,
  surfaceColor: Color = BiliColors.PlayerPanelFocused,
): Modifier {
  return if (focused) {
    clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = true,
        surfaceColor = surfaceColor,
      )
  } else {
    background(BiliColors.Transparent)
  }
}

@Composable
private fun PlayerStatusTexts(
  request: PlaybackRequest,
  info: PlaybackInfo,
  actualQuality: PlaybackQuality?,
  danmakuSettings: DanmakuSettings,
  onlineCountText: String,
  currentCodecText: String,
  /** P11-123:是否显示当前档文案。底栏有「画质」值按钮时置 false(文案已搬进按钮)。 */
  showQualityText: Boolean,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onlineCountText.isNotBlank()) {
      Text(
        text = stringResource(R.string.player_online_status, onlineCountText),
        color = BiliColors.TextSecondary,
        fontSize = BiliTypography.PlayerStatus,
        maxLines = 1,
      )
    }
    val danmakuText = when {
      !danmakuSettings.enabled -> stringResource(R.string.player_danmaku_off)
      request.danmakuCount > 0 -> stringResource(
        R.string.player_danmaku_count_status,
        request.danmakuCount.formatCompactCountText(),
      )
      else -> stringResource(R.string.player_danmaku_on)
    }
    Text(
      text = danmakuText,
      color = if (danmakuSettings.enabled) BiliColors.TextSecondary else BiliColors.TextTertiary,
      fontSize = BiliTypography.PlayerStatus,
      maxLines = 1,
    )
    if (showQualityText) {
      Text(
        text = (actualQuality ?: info.selectedQuality).description.withCodecLabel(currentCodecText),
        color = BiliColors.TextSecondary,
        fontSize = BiliTypography.PlayerStatus,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun PlayerTimeText(
  positionState: State<Long>,
  durationState: State<Long>,
  previewPositionMs: Long?,
) {
  Text(
    text = "${(previewPositionMs ?: positionState.value).toPlayerTime()} / ${durationState.value.toPlayerTime()}",
    color = BiliColors.TextPrimary,
    fontSize = BiliTypography.PlayerTime,
    fontWeight = FontWeight.Bold,
  )
}

@Composable
private fun TvProgressBar(
  positionState: State<Long>,
  durationState: State<Long>,
  bufferedPercentageState: State<Long>,
  airJumpSegments: List<AirJumpSegment>,
  isFocused: Boolean,
  previewPositionMs: Long?,
  modifier: Modifier = Modifier,
) {
  Canvas(
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerProgressTouchHeight),
  ) {
    val positionMs = positionState.value
    val durationMs = durationState.value
    val bufferedPercentage = bufferedPercentageState.value
    val progress = progressFraction(positionMs, durationMs)
    val buffered = (bufferedPercentage / 100f).coerceIn(0f, 1f)
    val preview = previewPositionMs?.let { progressFraction(it, durationMs) }
    val barHeight = if (isFocused) {
      BiliSizing.PlayerProgressFocusedHeight.toPx()
    } else {
      BiliSizing.PlayerProgressHeight.toPx()
    }
    val centerY = size.height / 2f
    val radius = barHeight / 2f
    val knobSize = if (isFocused) {
      BiliSizing.PlayerProgressFocusedKnobSize.toPx()
    } else {
      BiliSizing.PlayerProgressKnobSize.toPx()
    }

    drawRoundBar(1f, centerY, barHeight, radius, BiliColors.ProgressTrack)
    drawRoundBar(buffered, centerY, barHeight, radius, BiliColors.ProgressBuffered)
    drawRoundBar(progress, centerY, barHeight, radius, BiliColors.BiliPink)
    drawAirJumpSegments(airJumpSegments, durationMs, centerY, barHeight, radius)

    if (preview != null) {
      val previewX = preview * size.width
      val previewKnobRadius = knobSize * 0.95f
      val previewKnobCenterX = previewX.coerceIn(previewKnobRadius, size.width - previewKnobRadius)
      drawCircle(
        color = BiliColors.PlayerFocusGlow,
        radius = previewKnobRadius,
        center = Offset(previewKnobCenterX, centerY),
      )
      drawCircle(
        color = BiliColors.TextPrimary,
        radius = knobSize * 0.48f,
        center = Offset(previewKnobCenterX, centerY),
      )
      drawCircle(
        color = BiliColors.BiliPink,
        radius = knobSize * 0.22f,
        center = Offset(previewKnobCenterX, centerY),
      )
    } else {
      val x = progress * size.width
      val knobRadius = if (isFocused) knobSize * 0.62f else knobSize * 0.5f
      val knobCenterX = x.coerceIn(knobRadius, size.width - knobRadius)
      drawCircle(
        color = if (isFocused) BiliColors.PlayerFocusGlow else BiliColors.BiliPink,
        radius = knobRadius,
        center = Offset(knobCenterX, centerY),
      )
      drawCircle(
        color = BiliColors.BiliPink,
        radius = knobSize * 0.38f,
        center = Offset(knobCenterX, centerY),
      )
    }
  }
}

@Composable
private fun MiniProgressBar(
  positionState: State<Long>,
  durationState: State<Long>,
  airJumpSegments: List<AirJumpSegment>,
  modifier: Modifier = Modifier,
) {
  Canvas(
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerMiniProgressHeight),
  ) {
    val positionMs = positionState.value
    val durationMs = durationState.value
    val centerY = size.height / 2f
    val barHeight = size.height
    val radius = barHeight / 2f
    drawRoundBar(1f, centerY, barHeight, radius, BiliColors.ProgressTrack)
    drawRoundBar(progressFraction(positionMs, durationMs), centerY, barHeight, radius, BiliColors.BiliPink)
    drawAirJumpSegments(airJumpSegments, durationMs, centerY, barHeight, radius)
  }
}

@Composable
private fun SeekPreviewOverlay(
  previewPositionMs: Long,
  durationMs: Long,
  videoshotData: VideoshotData?,
  videoshotSprites: Map<String, ImageBitmap>,
  modifier: Modifier = Modifier,
) {
  val frame = videoshotData?.frameAt(previewPositionMs, durationMs)
  val spriteBitmap = frame?.let { videoshotSprites[it.imageUrl] }
  if (frame != null && spriteBitmap != null) {
    Column(
      modifier = modifier,
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      VideoshotFramePreview(
        image = spriteBitmap,
        frame = frame,
      )
      Spacer(modifier = Modifier.height(BiliSpacing.Md))
      Text(
        text = "${previewPositionMs.toPlayerTime()} / ${durationMs.toPlayerTime()}",
        color = BiliColors.TextPrimary,
        fontSize = BiliTypography.PlayerStatus,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
          .clip(RoundedCornerShape(BiliRadius.Pill))
          .background(BiliColors.OverlayStrong)
          .padding(horizontal = BiliSpacing.Lg, vertical = BiliSpacing.Sm),
      )
    }
    return
  }

  val shape = RoundedCornerShape(BiliRadius.Panel)
  Column(
    modifier = modifier
      .width(BiliSizing.PlayerSeekPreviewWidth)
      .height(BiliSizing.PlayerSeekPreviewHeight)
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = true,
        surfaceColor = BiliColors.OverlayStrong,
      )
      .padding(BiliSpacing.Lg),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = stringResource(R.string.player_seek_preview),
      color = BiliColors.TextSecondary,
      fontSize = BiliTypography.PlayerStatus,
      maxLines = 1,
    )
    Text(
      text = "${previewPositionMs.toPlayerTime()} / ${durationMs.toPlayerTime()}",
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerSeekPreview,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

@Composable
private fun VideoshotFramePreview(
  image: ImageBitmap,
  frame: VideoshotFrame,
) {
  val rawDisplayWidth = frame.width * SeekPreviewSpriteScale
  val rawDisplayHeight = frame.height * SeekPreviewSpriteScale
  val fitRatio = min(
    1f,
    min(
      SeekPreviewSpriteMaxWidth / rawDisplayWidth,
      SeekPreviewSpriteMaxHeight / rawDisplayHeight,
    ),
  )
  val renderScale = SeekPreviewSpriteScale * fitRatio
  val displayWidth = (frame.width * renderScale).dp
  val displayHeight = (frame.height * renderScale).dp

  Box(
    modifier = Modifier
      .width(displayWidth)
      .height(displayHeight)
      .clip(RoundedCornerShape(BiliRadius.Card))
      .background(BiliColors.SurfaceElevated),
    contentAlignment = Alignment.Center,
  ) {
    VideoshotCroppedCanvas(
      image = image,
      frame = frame,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

@Composable
private fun VideoshotCroppedCanvas(
  image: ImageBitmap,
  frame: VideoshotFrame,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val metadataSpriteWidth = frame.spriteWidth.coerceAtLeast(frame.width).coerceAtLeast(1)
    val metadataSpriteHeight = frame.spriteHeight.coerceAtLeast(frame.height).coerceAtLeast(1)
    val sourceScaleX = image.width.toFloat() / metadataSpriteWidth.toFloat()
    val sourceScaleY = image.height.toFloat() / metadataSpriteHeight.toFloat()
    val srcX = (frame.x * sourceScaleX).roundToInt().coerceIn(0, (image.width - 1).coerceAtLeast(0))
    val srcY = (frame.y * sourceScaleY).roundToInt().coerceIn(0, (image.height - 1).coerceAtLeast(0))
    val srcRight = ((frame.x + frame.width) * sourceScaleX).roundToInt().coerceIn(srcX + 1, image.width)
    val srcBottom = ((frame.y + frame.height) * sourceScaleY).roundToInt().coerceIn(srcY + 1, image.height)
    drawImage(
      image = image,
      srcOffset = IntOffset(srcX, srcY),
      srcSize = IntSize(srcRight - srcX, srcBottom - srcY),
      dstOffset = IntOffset.Zero,
      dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
  }
}

@Composable
private fun PlayerEpisodesPanel(
  episodes: List<PlaybackEpisode>,
  currentCid: Long,
  currentEpisodePage: Int?,
  focusedIndex: Int,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val performancePolicy = LocalBiliPerformancePolicy.current
  val shape = RoundedCornerShape(topStart = BiliRadius.Panel, bottomStart = BiliRadius.Panel)
  LaunchedEffect(focusedIndex, episodes.size) {
    if (episodes.isNotEmpty()) {
      val target = focusedIndex.coerceIn(0, episodes.lastIndex)
      if (performancePolicy.smoothScrollingEnabled) {
        listState.animateScrollToItem(target)
      } else {
        listState.scrollToItem(target)
      }
    }
  }

  Column(
    modifier = modifier
      .width(BiliSizing.PlayerSettingsPanelWidth)
      .fillMaxHeight()
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = false,
        surfaceColor = BiliColors.PlayerPanel,
      ),
  ) {
    PlayerPanelTitleRow(titleRes = R.string.player_panel_episodes)
    PlayerPanelDivider()
    if (episodes.isEmpty()) {
      PlayerPanelLoadingOrEmpty(loading = false)
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
      ) {
        itemsIndexed(episodes) { index, episode ->
          EpisodeRow(
            title = convertChineseText(episode.panelTitle(index)),
            focused = focusedIndex == index,
            // 影视库:分P 页号=选集索引高亮(cid 恒 0 无从比对);B站/PGC 按 cid 匹配。
            selected = if (currentEpisodePage != null) {
              episode.page == currentEpisodePage
            } else {
              episode.cid == currentCid
            },
          )
        }
      }
    }
  }
}

@Composable
private fun PlayerVideoListPanel(
  titleRes: Int,
  request: PlaybackRequest,
  videos: List<VideoSummary>,
  loading: Boolean,
  focusedIndex: Int,
  showUploaderHeader: Boolean,
  upVideoOrder: String,
  upFollowed: Boolean,
  upFollowLoading: Boolean,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val performancePolicy = LocalBiliPerformancePolicy.current
  val shape = RoundedCornerShape(topStart = BiliRadius.Panel, bottomStart = BiliRadius.Panel)
  val focusedVideoIndex = if (showUploaderHeader) focusedIndex - UpPanelHeaderItemCount else focusedIndex
  val scrollRevealPaddingPx = with(LocalDensity.current) { BiliSpacing.Sm.roundToPx() }
  LaunchedEffect(focusedVideoIndex, videos.size, scrollRevealPaddingPx) {
    if (videos.isNotEmpty() && focusedVideoIndex >= 0) {
      val target = focusedVideoIndex.coerceIn(0, videos.lastIndex)
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
            listState.scroll {
              scrollBy(scrollDelta.toFloat())
            }
          }
        }
      }
    }
  }

  Column(
    modifier = modifier
      .width(BiliSizing.PlayerContentPanelWidth)
      .fillMaxHeight()
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = false,
        surfaceColor = BiliColors.PlayerPanel,
      ),
  ) {
    if (showUploaderHeader) {
      UploaderPanelHeader(
        request = request,
        order = upVideoOrder,
        followed = upFollowed,
        followLoading = upFollowLoading,
        focusedIndex = focusedIndex,
      )
    } else {
      PlayerPanelTitleRow(titleRes = titleRes)
    }
    PlayerPanelDivider()
    when {
      loading || videos.isEmpty() -> PlayerPanelLoadingOrEmpty(loading = loading)
      else -> LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
      ) {
        itemsIndexed(
          items = videos,
          key = { _, video -> video.bvid },
        ) { index, video ->
          VideoPanelRow(
            video = video,
            focused = if (showUploaderHeader) focusedIndex == index + UpPanelHeaderItemCount else focusedIndex == index,
            showOwnerName = !showUploaderHeader,
          )
        }
      }
    }
  }
}

@Composable
private fun PlayerPanelTitleRow(titleRes: Int) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerSettingsHeaderHeight)
      .padding(horizontal = BiliSpacing.Xl),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(titleRes),
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerPanelTitle,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun UploaderPanelHeader(
  request: PlaybackRequest,
  order: String,
  followed: Boolean,
  followLoading: Boolean,
  focusedIndex: Int,
) {
  val context = LocalContext.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val ownerName = convertChineseText(request.ownerName)
  val fallbackPainter = ColorPainter(BiliColors.SurfaceElevated)
  val avatarRequest = remember(
    context,
    request.ownerFace,
    performancePolicy.ownerAvatarSizePx,
    performancePolicy.ownerAvatarRgb565Enabled,
    performancePolicy.imageMemoryCacheEnabled,
  ) {
    buildOwnerAvatarRequest(
      context = context,
      url = request.ownerFace,
      sizePx = performancePolicy.ownerAvatarSizePx,
      allowRgb565 = performancePolicy.ownerAvatarRgb565Enabled,
      memoryCacheEnabled = performancePolicy.imageMemoryCacheEnabled,
    )
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerUpPanelHeaderHeight)
      .padding(horizontal = BiliSpacing.Lg, vertical = BiliSpacing.Md),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (request.ownerFace.isNotBlank()) {
      AsyncImage(
        model = avatarRequest,
        contentDescription = ownerName,
        contentScale = ContentScale.Crop,
        placeholder = fallbackPainter,
        error = fallbackPainter,
        modifier = Modifier
          .size(BiliSizing.PlayerPanelAvatarSize)
          .clip(CircleShape)
          .background(BiliColors.SurfaceElevated),
      )
    } else {
      Box(
        modifier = Modifier
          .size(BiliSizing.PlayerPanelAvatarSize)
          .clip(CircleShape)
          .background(BiliColors.SurfaceElevated),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          painter = painterResource(R.drawable.ic_nav_account),
          contentDescription = null,
          tint = BiliColors.TextSecondary,
          modifier = Modifier.size(BiliSizing.PlayerSettingsIconSize),
        )
      }
    }
    Spacer(modifier = Modifier.width(BiliSpacing.Md))
    Text(
      text = ownerName.ifBlank { stringResource(R.string.player_panel_unknown_up) },
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerPanelTitle,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(modifier = Modifier.width(BiliSpacing.Sm))
    UpPanelChip(
      text = stringResource(if (order == UpVideoOrderLatest) R.string.player_up_sort_latest else R.string.player_up_sort_hot),
      focused = focusedIndex == UpFocusSort,
      selected = true,
    )
    Spacer(modifier = Modifier.width(BiliSpacing.Sm))
    UpPanelChip(
      text = when {
        followLoading -> stringResource(R.string.player_up_follow_loading)
        followed -> stringResource(R.string.player_up_followed)
        else -> stringResource(R.string.player_up_follow)
      },
      focused = focusedIndex == UpFocusFollow,
      selected = followed,
    )
    Spacer(modifier = Modifier.width(BiliSpacing.Sm))
    UpPanelChip(
      text = stringResource(R.string.player_up_view_home),
      focused = focusedIndex == UpFocusHome,
      selected = false,
    )
  }
}

@Composable
private fun UpPanelChip(
  text: String,
  focused: Boolean,
  selected: Boolean,
) {
  val shape = RoundedCornerShape(BiliRadius.Pill)
  val performancePolicy = LocalBiliPerformancePolicy.current
  val liquidGlassEnabled = performancePolicy.cinematicVisualEffectsEnabled && performancePolicy.liquidGlassCardsEnabled
  val surfaceColor = when {
    focused -> BiliColors.PlayerPanelFocused
    selected -> BiliColors.BiliPink.copy(alpha = UpPanelChipSelectedSurfaceAlpha)
    else -> BiliColors.PlayerControlIdle
  }
  val borderColor = when {
    focused -> BiliColors.TextPrimary.copy(alpha = UpPanelChipFocusedBorderAlpha)
    selected -> BiliColors.BiliPink.copy(alpha = UpPanelChipSelectedBorderAlpha)
    else -> BiliColors.TextPrimary.copy(alpha = UpPanelChipRestingBorderAlpha)
  }
  Box(
    modifier = Modifier
      .height(BiliSizing.PlayerPanelChipHeight)
      .clip(shape)
      .biliLiquidGlassSurface(
        enabled = liquidGlassEnabled,
        shape = shape,
        surfaceColor = surfaceColor,
        borderColor = borderColor,
        borderWidth = if (focused) BiliFocus.BorderWidth else BiliFocus.RestingBorderWidth,
      )
      .padding(horizontal = BiliSpacing.Md),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = if (selected && !focused) {
        BiliColors.BiliPink
      } else {
        BiliColors.TextPrimary
      },
      fontSize = BiliTypography.PlayerSettingValue,
      fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun UnfollowConfirmDialog(
  focusedConfirm: Boolean,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(BiliRadius.Panel)
  Column(
    modifier = modifier
      .width(BiliSizing.PlayerUnfollowDialogWidth)
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = false,
        surfaceColor = BiliColors.OverlayScrim,
      )
      .padding(BiliSpacing.Xl),
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
  ) {
    Text(
      text = stringResource(R.string.player_unfollow_confirm_title),
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerPanelTitle,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
    Text(
      text = stringResource(R.string.player_unfollow_confirm_message),
      color = BiliColors.TextSecondary,
      fontSize = BiliTypography.BodySmall,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      modifier = Modifier.fillMaxWidth(),
    ) {
      ConfirmDialogButton(
        text = stringResource(R.string.player_unfollow_confirm_cancel),
        focused = !focusedConfirm,
        destructive = false,
        modifier = Modifier.weight(1f),
      )
      ConfirmDialogButton(
        text = stringResource(R.string.player_unfollow_confirm_action),
        focused = focusedConfirm,
        destructive = true,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun ConfirmDialogButton(
  text: String,
  focused: Boolean,
  destructive: Boolean,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  val surfaceColor = when {
    focused && destructive -> BiliColors.BiliPink
    focused -> BiliColors.PlayerPanelFocused
    else -> BiliColors.PlayerControlIdle
  }
  Box(
    modifier = modifier
      .height(BiliSizing.PlayerUnfollowDialogButtonHeight)
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = focused,
        surfaceColor = surfaceColor,
      )
      .padding(horizontal = BiliSpacing.Md),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerSettingTitle,
      fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun CoinConfirmDialog(
  focusedIndex: Int,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(BiliRadius.Panel)
  Column(
    modifier = modifier
      .width(BiliSizing.PlayerUnfollowDialogWidth)
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = false,
        surfaceColor = BiliColors.OverlayScrim,
      )
      .padding(BiliSpacing.Xl),
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
  ) {
    Text(
      text = stringResource(R.string.player_coin_title),
      color = BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerPanelTitle,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      modifier = Modifier.fillMaxWidth(),
    ) {
      ConfirmDialogButton(
        text = stringResource(R.string.player_coin_one),
        focused = focusedIndex == 0,
        destructive = true,
        modifier = Modifier.weight(1f),
      )
      ConfirmDialogButton(
        text = stringResource(R.string.player_coin_two),
        focused = focusedIndex == 1,
        destructive = true,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun PlayerFavoritePanel(
  folders: List<FavoriteFolder>,
  loading: Boolean,
  selectedIds: Set<Long>,
  focusedIndex: Int,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val performancePolicy = LocalBiliPerformancePolicy.current
  val shape = RoundedCornerShape(topStart = BiliRadius.Panel, bottomStart = BiliRadius.Panel)
  val itemCount = 1 + folders.size
  LaunchedEffect(focusedIndex, folders.size) {
    if (itemCount > 0) {
      val target = focusedIndex.coerceIn(0, itemCount - 1)
      if (performancePolicy.smoothScrollingEnabled) {
        listState.animateScrollToItem(target)
      } else {
        listState.scrollToItem(target)
      }
    }
  }

  Column(
    modifier = modifier
      .width(BiliSizing.PlayerSettingsPanelWidth)
      .fillMaxHeight()
      .clip(shape)
      .playerLiquidGlassSurface(
        shape = shape,
        focused = false,
        surfaceColor = BiliColors.PlayerPanel,
      ),
  ) {
    PlayerPanelTitleRow(titleRes = R.string.player_panel_favorite)
    PlayerPanelDivider()
    if (loading) {
      PlayerPanelLoadingOrEmpty(loading = true)
    } else if (folders.isEmpty()) {
      PlayerPanelLoadingOrEmpty(loading = false)
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
      ) {
        item {
          FavoriteConfirmRow(
            focused = focusedIndex == 0,
            hasSelection = selectedIds.isNotEmpty(),
          )
        }
        itemsIndexed(folders) { index, folder ->
          FavoriteFolderRow(
            title = convertChineseText(folder.title),
            mediaCount = folder.mediaCount,
            selected = folder.mediaId in selectedIds,
            focused = focusedIndex == index + 1,
          )
        }
      }
    }
  }
}

@Composable
private fun FavoriteConfirmRow(
  focused: Boolean,
  hasSelection: Boolean,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  val tint = if (hasSelection) BiliColors.BiliPink else BiliColors.TextSecondary
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerEpisodeRowHeight)
      .playerFocusedLiquidGlassSurface(shape = shape, focused = focused),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (hasSelection) {
      Icon(
        painter = painterResource(R.drawable.ic_player_check),
        contentDescription = null,
        tint = BiliColors.BiliPink,
        modifier = Modifier
          .padding(start = BiliSpacing.Lg)
          .size(BiliSizing.VideoOverlayIconSize + 2.dp),
      )
    } else {
      Spacer(modifier = Modifier.width(BiliSpacing.Lg + BiliSizing.VideoOverlayIconSize + 2.dp))
    }
    Text(
      text = stringResource(R.string.player_favorite_confirm),
      color = tint,
      fontSize = BiliTypography.PlayerSettingTitle,
      fontWeight = if (hasSelection || focused) FontWeight.Bold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun FavoriteFolderRow(
  title: String,
  mediaCount: Int,
  selected: Boolean,
  focused: Boolean,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerEpisodeRowHeight)
      .playerFocusedLiquidGlassSurface(shape = shape, focused = focused),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .padding(start = BiliSpacing.Lg)
        .size(BiliSizing.VideoOverlayIconSize + 2.dp)
        .clip(RoundedCornerShape(BiliRadius.Card))
        .border(
          width = BiliFocus.RestingBorderWidth,
          color = if (selected) BiliColors.BiliPink else BiliColors.TextTertiary,
          shape = RoundedCornerShape(BiliRadius.Card),
        ),
      contentAlignment = Alignment.Center,
    ) {
      if (selected) {
        Icon(
          painter = painterResource(R.drawable.ic_player_check),
          contentDescription = null,
          tint = BiliColors.BiliPink,
          modifier = Modifier.size(BiliSizing.VideoOverlayIconSize),
        )
      }
    }
    Text(
      text = title,
      color = if (selected) BiliColors.BiliPink else BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerSettingTitle,
      fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .padding(start = BiliSpacing.Md)
        .weight(1f),
    )
    Text(
      text = stringResource(R.string.player_favorite_count, mediaCount),
      color = BiliColors.TextTertiary,
      fontSize = BiliTypography.PlayerSettingValue,
      maxLines = 1,
      modifier = Modifier.padding(end = BiliSpacing.Lg),
    )
  }
}

@Composable
private fun PlayerPanelDivider() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerSettingsDividerHeight)
      .background(BiliColors.PlayerPanelDivider),
  )
}

@Composable
private fun PlayerPanelLoadingOrEmpty(loading: Boolean) {
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
    ) {
      if (loading) {
        CircularProgressIndicator(color = BiliColors.BiliPink)
      }
      Text(
        text = stringResource(if (loading) R.string.player_panel_loading else R.string.player_panel_empty),
        color = BiliColors.TextSecondary,
        fontSize = BiliTypography.PlayerStatus,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun EpisodeRow(
  title: String,
  focused: Boolean,
  selected: Boolean,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerEpisodeRowHeight)
      .playerFocusedLiquidGlassSurface(shape = shape, focused = focused),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      color = if (selected) BiliColors.BiliPink else BiliColors.TextPrimary,
      fontSize = BiliTypography.PlayerSettingTitle,
      fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = BiliSpacing.Lg),
    )
  }
}

@Composable
private fun VideoPanelRow(
  video: VideoSummary,
  focused: Boolean,
  showOwnerName: Boolean,
) {
  val context = LocalContext.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val fallbackPainter = ColorPainter(BiliColors.SurfaceElevated)
  val title = convertChineseText(video.title)
  val ownerName = convertChineseText(video.ownerName)
  val imageRequest = remember(
    context,
    video.pic,
    performancePolicy.videoThumbnailWidthPx,
    performancePolicy.videoThumbnailHeightPx,
    performancePolicy.videoThumbnailRgb565Enabled,
    performancePolicy.imageMemoryCacheEnabled,
  ) {
    buildVideoThumbnailRequest(
      context = context,
      url = video.pic,
      widthPx = performancePolicy.videoThumbnailWidthPx,
      heightPx = performancePolicy.videoThumbnailHeightPx,
      allowRgb565 = performancePolicy.videoThumbnailRgb565Enabled,
      memoryCacheEnabled = performancePolicy.imageMemoryCacheEnabled,
    )
  }
  val separator = stringResource(R.string.player_meta_separator)
  val viewText = if (video.view > 0) video.view.formatCompactCountText() else ""
  val danmakuText = if (video.danmaku > 0) video.danmaku.formatCompactCountText() else ""
  val pubdateText = video.panelPubdateText()
  val metaText = listOf(
    ownerName.takeIf { showOwnerName }.orEmpty(),
    pubdateText,
  )
    .filter(String::isNotBlank)
    .joinToString(separator)

  val shape = RoundedCornerShape(BiliRadius.Card)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = BiliSpacing.Xs, vertical = BiliSpacing.Xxs)
      .height(BiliSizing.PlayerPanelVideoRowHeight)
      .playerFocusedLiquidGlassSurface(shape = shape, focused = focused)
      .padding(BiliSpacing.Sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .width(BiliSizing.PlayerPanelVideoThumbnailWidth)
        .height(BiliSizing.PlayerPanelVideoThumbnailHeight)
        .clip(RoundedCornerShape(BiliRadius.Card))
        .background(BiliColors.SurfaceElevated),
    ) {
      AsyncImage(
        model = imageRequest,
        contentDescription = title,
        contentScale = ContentScale.Crop,
        placeholder = fallbackPainter,
        error = fallbackPainter,
          modifier = Modifier.fillMaxSize(),
      )
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .height(BiliSizing.VideoCoverGradientHeight)
          .background(
            Brush.verticalGradient(
              colors = listOf(
                BiliColors.OverlayTransparent,
                BiliColors.OverlayScrim,
              ),
            ),
          ),
      )
      if (viewText.isNotBlank() || danmakuText.isNotBlank() || video.duration > 0) {
        Row(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = BiliSpacing.Sm, vertical = BiliSpacing.Sm),
          horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (viewText.isNotBlank()) {
              VideoPanelMetric(
                iconRes = R.drawable.ic_video_play_count,
                contentDescription = stringResource(R.string.video_play_count_content_description),
                text = viewText,
              )
            }
            if (danmakuText.isNotBlank()) {
              if (viewText.isNotBlank()) {
                Spacer(modifier = Modifier.width(BiliSpacing.Sm))
              }
              VideoPanelMetric(
                iconRes = R.drawable.ic_video_danmaku_count,
                contentDescription = stringResource(R.string.video_danmaku_count_content_description),
                text = danmakuText,
              )
            }
          }
          if (video.duration > 0) {
            Text(
              text = (video.duration.toLong() * 1000L).toPlayerTime(),
              color = BiliColors.TextPrimary,
              fontSize = BiliTypography.CardOverlay,
              maxLines = 1,
            )
          }
        }
      }
    }
    Spacer(modifier = Modifier.width(BiliSpacing.Md))
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = title,
        color = BiliColors.TextPrimary,
        fontSize = BiliTypography.PlayerSettingTitle,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (metaText.isNotBlank()) {
        Spacer(modifier = Modifier.height(BiliSpacing.Xs))
        Text(
          text = metaText,
          color = BiliColors.TextTertiary,
          fontSize = BiliTypography.PlayerSettingValue,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun VideoPanelMetric(
  @DrawableRes iconRes: Int,
  contentDescription: String,
  text: String,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = contentDescription,
      tint = BiliColors.TextSecondary,
      modifier = Modifier.size(BiliSizing.VideoOverlayIconSize),
    )
    Spacer(modifier = Modifier.width(BiliSpacing.Xs))
    Text(
      text = text,
      color = BiliColors.TextSecondary,
      fontSize = BiliTypography.CardOverlay,
      maxLines = 1,
    )
  }
}

@Composable
private fun PlayerSettingsPanel(
  activePanel: PlayerPanel,
  focusedIndex: Int,
  info: PlaybackInfo,
  actualQuality: PlaybackQuality?,
  currentAudioTrackId: String?,
  currentSubtitleTrackId: Int?,
  currentCodecText: String,
  playbackSpeed: Float,
  danmakuSettings: DanmakuSettings,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(topStart = BiliRadius.Panel, bottomStart = BiliRadius.Panel)
  val listState = rememberLazyListState()
  val performancePolicy = LocalBiliPerformancePolicy.current
  val rowCount = activePanel.settingsRowCount(info)
  LaunchedEffect(activePanel, focusedIndex, rowCount) {
    if (rowCount > 0) {
      val target = focusedIndex.coerceIn(0, rowCount - 1)
      if (performancePolicy.smoothScrollingEnabled) {
        listState.animateScrollToItem(target)
      } else {
        listState.scrollToItem(target)
      }
    }
  }
  Column(
    modifier = modifier
      .width(BiliSizing.PlayerSettingsPanelWidth)
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
        text = stringResource(activePanel.titleRes),
        color = BiliColors.TextPrimary,
        fontSize = BiliTypography.PlayerPanelTitle,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
      Spacer(modifier = Modifier.weight(1f))
      if (activePanel == PlayerPanel.Main) {
        Icon(
          painter = painterResource(R.drawable.ic_nav_settings),
          contentDescription = null,
          tint = BiliColors.TextTertiary,
          modifier = Modifier.size(BiliSizing.PlayerSettingsIconSize),
        )
      }
    }
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(BiliSizing.PlayerSettingsDividerHeight)
        .background(BiliColors.PlayerPanelDivider),
    )
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
    ) {
      when (activePanel) {
        PlayerPanel.Main -> {
          // alpha.9X(恢复清晰度选择):Main 面板 index 0 加回「清晰度」项 → Quality 面板选档。
          item(key = "quality") {
            SettingsRow(
              iconRes = R.drawable.ic_player_hd,
              title = stringResource(R.string.player_settings_quality),
              value = (actualQuality ?: info.selectedQuality).description.withCodecLabel(currentCodecText),
              focused = focusedIndex == 0,
              trailingChevron = true,
            )
          }
          item(key = "danmaku") {
            SettingsRow(
              iconRes = R.drawable.ic_player_subtitles,
              title = stringResource(R.string.player_settings_danmaku),
              value = if (danmakuSettings.enabled) stringResource(R.string.player_value_on) else stringResource(R.string.player_value_off),
              focused = focusedIndex == 1,
              trailingChevron = true,
            )
          }
          item(key = "speed") {
            SettingsRow(
              iconRes = R.drawable.ic_player_speed,
              title = stringResource(R.string.player_settings_speed),
              value = playbackSpeed.speedText(),
              focused = focusedIndex == 2,
              trailingChevron = true,
            )
          }
          // P11-119:音轨入口——仅多音轨(多语言配音)视频出现,追加在固定 3 项之后,
          // 不打乱既有 0/1/2(清晰度/弹幕/倍速)的索引语义。行号见 MainAudioRowIndex。
          if (hasAudioTrackChoice(info)) {
            val currentAudio = info.availableAudioTracks.firstOrNull { it.id == currentAudioTrackId }
            item(key = "audio") {
              SettingsRow(
                iconRes = R.drawable.ic_player_audio_track,
                title = stringResource(R.string.player_audio_track),
                value = currentAudio?.displayName ?: currentAudio?.languageCode ?: "",
                focused = focusedIndex == MainAudioRowIndex,
                trailingChevron = true,
              )
            }
          }
          // P11-120:字幕入口——仅有字幕的视频出现,排在音轨项之后(行号见 mainSubtitleRowIndex)。
          if (hasSubtitleChoice(info)) {
            val currentSubtitle = info.subtitleTracks.firstOrNull { it.id == currentSubtitleTrackId }
            item(key = "subtitle") {
              SettingsRow(
                iconRes = R.drawable.ic_player_subtitles,
                title = stringResource(R.string.player_subtitle),
                value = currentSubtitle?.let { subtitleRowTitle(it) } ?: stringResource(R.string.player_value_off),
                focused = focusedIndex == mainSubtitleRowIndex(info),
                trailingChevron = true,
              )
            }
          }
        }
        PlayerPanel.Quality -> {
          val qualities = info.qualities.ifEmpty { listOf(info.selectedQuality) }
          // 高亮/「当前」标实际播放档(actualQuality):Auto 自适应时随 onVideoSizeChanged 更新,
          // 与请求档(info.selectedQuality)分离——修「显示2160实际低」。actualQuality 为空(未首帧)回落请求档。
          val currentQuality = actualQuality ?: info.selectedQuality
          itemsIndexed(qualities, key = { _, quality -> quality.id }) { index, quality ->
            SettingsRow(
              iconRes = R.drawable.ic_player_hd,
              title = convertChineseText(quality.description),
              value = if (quality.id == currentQuality.id) stringResource(R.string.player_value_current) else "",
              focused = focusedIndex == index,
              trailingCheck = quality.id == currentQuality.id,
            )
          }
        }
        PlayerPanel.Audio -> {
          // P11-119:音轨列表(镜像 Quality 面板)。打勾 = 当前轨(preferredAudioTrackId,
          // 未选时由 PlayerScreen 回落服务器声明默认轨)。
          itemsIndexed(info.availableAudioTracks, key = { _, track -> track.id }) { index, track ->
            SettingsRow(
              iconRes = R.drawable.ic_player_audio_track,
              title = track.displayName ?: track.languageCode ?: track.id,
              value = if (track.id == currentAudioTrackId) stringResource(R.string.player_value_current) else "",
              focused = focusedIndex == index,
              trailingCheck = track.id == currentAudioTrackId,
            )
          }
        }
        PlayerPanel.Subtitle -> {
          // P11-120:index 0 = 「关闭」,其后每条字幕轨一项(镜像 Audio 面板的列表形态)。
          // 切换是纯客户端轨道选择(懒加载轨选中后才拉 WebVTT),故不重开会话、位置不变。
          // 注意:LazyColumn 的 content 是 LazyListScope.() -> Unit(非 @Composable),
          // 每行都必须包在 item{} 里,不能裸调 SettingsRow。
          item(key = "subtitle-off") {
            SettingsRow(
              iconRes = R.drawable.ic_player_subtitles,
              title = stringResource(R.string.player_subtitle_off),
              value = if (currentSubtitleTrackId == null) stringResource(R.string.player_value_current) else "",
              focused = focusedIndex == 0,
              trailingCheck = currentSubtitleTrackId == null,
            )
          }
          itemsIndexed(info.subtitleTracks, key = { _, track -> track.id }) { index, track ->
            val selected = track.id == currentSubtitleTrackId
            SettingsRow(
              iconRes = R.drawable.ic_player_subtitles,
              title = subtitleRowTitle(track),
              value = if (selected) stringResource(R.string.player_value_current) else "",
              focused = focusedIndex == index + 1,
              trailingCheck = selected,
            )
          }
        }
        PlayerPanel.Danmaku -> {
          val rows = danmakuSettingRows(danmakuSettings)
          itemsIndexed(rows) { index, row ->
            SettingsRow(
              iconRes = row.iconRes,
              title = stringResource(row.titleRes),
              value = row.valueRes?.let { valueRes -> stringResource(valueRes) } ?: row.value,
              focused = focusedIndex == index,
              adjustable = row.adjustable,
            )
          }
        }
        PlayerPanel.Speed -> {
          itemsIndexed(PlayerSpeedOptions) { index, speed ->
            val selected = speed == playbackSpeed
            SettingsRow(
              iconRes = R.drawable.ic_player_speed,
              title = speed.speedText(),
              value = if (selected) stringResource(R.string.player_value_current) else "",
              focused = focusedIndex == index,
              trailingCheck = selected,
            )
          }
        }
        PlayerPanel.Episodes,
        PlayerPanel.UpVideos,
        PlayerPanel.RelatedVideos,
        PlayerPanel.Favorite,
        PlayerPanel.None -> Unit
      }
    }
  }
}

private fun PlayerPanel.settingsRowCount(info: PlaybackInfo): Int {
  return when (this) {
    PlayerPanel.Main -> 3 + (if (hasAudioTrackChoice(info)) 1 else 0) + (if (hasSubtitleChoice(info)) 1 else 0)
    PlayerPanel.Quality -> info.qualities.size.coerceAtLeast(1)
    PlayerPanel.Audio -> info.availableAudioTracks.size.coerceAtLeast(1)
    PlayerPanel.Subtitle -> subtitlePanelItemCount(info)
    PlayerPanel.Danmaku -> DanmakuSettingsRowCount
    PlayerPanel.Speed -> PlayerSpeedOptions.size
    PlayerPanel.Episodes,
    PlayerPanel.UpVideos,
    PlayerPanel.RelatedVideos,
    PlayerPanel.Favorite,
    PlayerPanel.None -> 0
  }
}

@Composable
private fun MainSettingsRows(
  focusedIndex: Int,
  quality: String,
  playbackSpeed: Float,
  danmakuSettings: DanmakuSettings,
) {
  SettingsRow(
    iconRes = R.drawable.ic_player_hd,
    title = stringResource(R.string.player_settings_quality),
    value = quality,
    focused = focusedIndex == 0,
    trailingChevron = true,
  )
  SettingsRow(
    iconRes = R.drawable.ic_player_subtitles,
    title = stringResource(R.string.player_settings_danmaku),
    value = if (danmakuSettings.enabled) stringResource(R.string.player_value_on) else stringResource(R.string.player_value_off),
    focused = focusedIndex == 1,
    trailingChevron = true,
  )
  SettingsRow(
    iconRes = R.drawable.ic_player_speed,
    title = stringResource(R.string.player_settings_speed),
    value = playbackSpeed.speedText(),
    focused = focusedIndex == 2,
    trailingChevron = true,
  )
}

@Composable
private fun QualityRows(
  focusedIndex: Int,
  qualities: List<PlaybackQuality>,
  currentQuality: PlaybackQuality,
) {
  qualities.ifEmpty { listOf(currentQuality) }.forEachIndexed { index, quality ->
    SettingsRow(
      iconRes = R.drawable.ic_player_hd,
      title = convertChineseText(quality.description),
      value = if (quality.id == currentQuality.id) stringResource(R.string.player_value_current) else "",
      focused = focusedIndex == index,
      trailingCheck = quality.id == currentQuality.id,
    )
  }
}

private fun danmakuSettingRows(settings: DanmakuSettings): List<DanmakuSettingRow> {
  return listOf(
    DanmakuSettingRow(
      titleRes = R.string.player_settings_danmaku_toggle,
      iconRes = R.drawable.ic_player_subtitles,
      valueRes = if (settings.enabled) R.string.player_value_on else R.string.player_value_off,
    ),
    DanmakuSettingRow(
      titleRes = R.string.player_settings_danmaku_opacity,
      iconRes = R.drawable.ic_player_subtitles,
      value = "%.1f".format(Locale.US, settings.opacity),
      adjustable = true,
    ),
    DanmakuSettingRow(
      titleRes = R.string.player_settings_danmaku_font_size,
      iconRes = R.drawable.ic_player_subtitles,
      value = settings.fontSize.toString(),
      adjustable = true,
    ),
    DanmakuSettingRow(
      titleRes = R.string.player_settings_danmaku_area,
      iconRes = R.drawable.ic_player_subtitles,
      valueRes = settings.area.areaTextRes(),
      adjustable = true,
    ),
    DanmakuSettingRow(
      titleRes = R.string.player_settings_danmaku_speed,
      iconRes = R.drawable.ic_player_speed,
      value = settings.speed.toString(),
      adjustable = true,
    ),
    DanmakuSettingRow(
      titleRes = R.string.player_settings_danmaku_top,
      iconRes = R.drawable.ic_player_subtitles,
      valueRes = if (settings.allowTop) R.string.player_value_on else R.string.player_value_off,
    ),
    DanmakuSettingRow(
      titleRes = R.string.player_settings_danmaku_bottom,
      iconRes = R.drawable.ic_player_subtitles,
      valueRes = if (settings.allowBottom) R.string.player_value_on else R.string.player_value_off,
    ),
    DanmakuSettingRow(
      titleRes = R.string.player_settings_danmaku_capacity,
      iconRes = R.drawable.ic_player_subtitles,
      valueRes = settings.capacity.labelRes,
      adjustable = true,
    ),
  )
}

private data class DanmakuSettingRow(
  @param:StringRes val titleRes: Int,
  @param:DrawableRes val iconRes: Int,
  val value: String = "",
  @param:StringRes val valueRes: Int? = null,
  val adjustable: Boolean = false,
)

@Composable
private fun SpeedRows(
  focusedIndex: Int,
  playbackSpeed: Float,
) {
  PlayerSpeedOptions.forEachIndexed { index, speed ->
    val selected = speed == playbackSpeed
    SettingsRow(
      iconRes = R.drawable.ic_player_speed,
      title = speed.speedText(),
      value = if (selected) stringResource(R.string.player_value_current) else "",
      focused = focusedIndex == index,
      trailingCheck = selected,
    )
  }
}

@Composable
internal fun SettingsRow(
  @DrawableRes iconRes: Int,
  title: String,
  value: String,
  focused: Boolean,
  trailingChevron: Boolean = false,
  trailingCheck: Boolean = false,
  adjustable: Boolean = false,
) {
  val shape = RoundedCornerShape(BiliRadius.Card)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.PlayerSettingsRowHeight)
      .playerFocusedLiquidGlassSurface(shape = shape, focused = focused)
      .padding(horizontal = BiliSpacing.Lg),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(BiliSizing.PlayerSettingsIconSize)
        .clip(CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = if (focused) BiliColors.BiliPink else BiliColors.TextSecondary,
        modifier = Modifier.size(BiliSizing.PlayerSettingsIconSize),
      )
    }
    Spacer(modifier = Modifier.width(BiliSpacing.Md))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        color = if (trailingCheck) BiliColors.BiliPink else BiliColors.TextPrimary,
        fontSize = BiliTypography.PlayerSettingTitle,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (value.isNotBlank() && !adjustable) {
        Text(
          text = value,
          color = if (trailingCheck) BiliColors.BiliPink else BiliColors.TextTertiary,
          fontSize = BiliTypography.PlayerSettingValue,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (adjustable && value.isNotBlank()) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          painter = painterResource(R.drawable.ic_player_chevron_left),
          contentDescription = null,
          tint = if (focused) BiliColors.BiliPink else BiliColors.TextTertiary,
          modifier = Modifier.size(BiliSizing.PlayerSettingsChevronSize),
        )
        Spacer(modifier = Modifier.width(BiliSpacing.Sm))
        Text(
          text = value,
          color = if (focused) BiliColors.BiliPink else BiliColors.TextTertiary,
          fontSize = BiliTypography.PlayerSettingValue,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(BiliSpacing.Sm))
        Icon(
          painter = painterResource(R.drawable.ic_player_chevron_right),
          contentDescription = null,
          tint = if (focused) BiliColors.BiliPink else BiliColors.TextTertiary,
          modifier = Modifier.size(BiliSizing.PlayerSettingsChevronSize),
        )
      }
    }
    when {
      trailingCheck -> Icon(
        painter = painterResource(R.drawable.ic_player_check),
        contentDescription = null,
        tint = BiliColors.BiliPink,
        modifier = Modifier.size(BiliSizing.PlayerSettingsChevronSize),
      )
      trailingChevron -> Icon(
        painter = painterResource(R.drawable.ic_player_chevron_right),
        contentDescription = null,
        tint = BiliColors.TextTertiary,
        modifier = Modifier.size(BiliSizing.PlayerSettingsChevronSize),
      )
    }
  }
}

private fun DrawScope.drawRoundBar(
  fraction: Float,
  centerY: Float,
  height: Float,
  radius: Float,
  color: Color,
) {
  drawRoundRect(
    color = color,
    topLeft = Offset(0f, centerY - height / 2f),
    size = Size(size.width * fraction.coerceIn(0f, 1f), height),
    cornerRadius = CornerRadius(radius, radius),
  )
}

private fun DrawScope.drawAirJumpSegments(
  segments: List<AirJumpSegment>,
  durationMs: Long,
  centerY: Float,
  height: Float,
  radius: Float,
) {
  if (durationMs <= 0L || segments.isEmpty()) return
  segments.forEach { segment ->
    val startFraction = progressFraction(segment.startMs, durationMs)
    val endFraction = progressFraction(segment.endMs, durationMs)
    if (endFraction <= startFraction) return@forEach
    val left = (size.width * startFraction).coerceIn(0f, size.width)
    val right = (size.width * endFraction).coerceIn(left, size.width)
    if (right <= left) return@forEach
    drawRoundRect(
      color = BiliColors.AirJumpGreen,
      topLeft = Offset(left, centerY - height / 2f),
      size = Size(right - left, height),
      cornerRadius = CornerRadius(radius, radius),
    )
  }
}

private fun progressFraction(positionMs: Long, durationMs: Long): Float {
  return if (durationMs > 0L) {
    (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
  } else {
    0f
  }
}

private val PlayerControl.iconRes: Int
  @DrawableRes
  get() = when (this) {
    PlayerControl.Episodes -> R.drawable.ic_player_playlist
    PlayerControl.Up -> R.drawable.ic_nav_account
    PlayerControl.Related -> R.drawable.ic_player_related
    PlayerControl.Quality -> R.drawable.ic_player_hd
    // P11-124:字幕入口改用 CC 形状图标(原 ic_player_subtitles 是三横线=字幕文本形)。
    // iconRes 全源共用,故 YouTube 的「字幕」值按钮会一并换成 CC —— 用户已确认这次允许。
    PlayerControl.Subtitle -> R.drawable.ic_player_cc
    PlayerControl.Like -> R.drawable.ic_player_like
    PlayerControl.Coin -> R.drawable.ic_player_coin
    PlayerControl.Favorite -> R.drawable.ic_player_favorite
    PlayerControl.ToView -> R.drawable.ic_player_toview
    PlayerControl.Comment -> R.drawable.ic_player_comment
    PlayerControl.Settings -> R.drawable.ic_nav_settings
    // P11-124:B站 控制行的三个新入口。
    PlayerControl.Speed -> R.drawable.ic_player_speed
    PlayerControl.Refresh -> R.drawable.ic_player_refresh
    PlayerControl.DanmakuToggle -> R.drawable.ic_player_danmaku_toggle
  }

private val PlayerControl.labelRes: Int
  get() = when (this) {
    PlayerControl.Episodes -> R.string.player_control_episodes
    PlayerControl.Up -> R.string.player_control_up
    PlayerControl.Related -> R.string.player_control_related
    PlayerControl.Quality -> R.string.player_settings_quality
    PlayerControl.Subtitle -> R.string.player_subtitle
    PlayerControl.Like -> R.string.player_control_like
    PlayerControl.Coin -> R.string.player_control_coin
    PlayerControl.Favorite -> R.string.player_control_favorite
    PlayerControl.ToView -> R.string.player_control_toview
    PlayerControl.Comment -> R.string.player_control_comment
    PlayerControl.Settings -> R.string.player_control_settings
    // P11-124:倍速/弹幕开关复用设置面板里的既有文案,「刷新」为本轮新增。
    PlayerControl.Speed -> R.string.player_settings_speed
    PlayerControl.DanmakuToggle -> R.string.player_settings_danmaku_toggle
    PlayerControl.Refresh -> R.string.player_control_refresh
  }

/** 是否带计数 + 激活态的互动按钮(点赞/投币/收藏),用 PlayerActionButton 渲染。 */
internal val PlayerControl.isAction: Boolean
  get() = this == PlayerControl.Like || this == PlayerControl.Coin || this == PlayerControl.Favorite

/**
 * P11-123:是否「图标 + 当前值」形态(画质/字幕),用 [PlayerValueButton] 渲染。
 * 宽度自适应(不是固定 60dp 方块),故仅在底栏有余量时启用(当前仅 YouTube)。
 */
internal val PlayerControl.isValueBearing: Boolean
  get() = this == PlayerControl.Quality || this == PlayerControl.Subtitle

/**
 * P11-123:值按钮左侧图标(null = 纯文字按钮)。
 *
 * 画质**不带图标**(用户反馈「不需要画质 HD 标志」):当前档文字本身就是信息,再加一个 HD 方块
 * 既占宽又冗余(面板里每行已有 HD 图标)。字幕保留 CC 图标——它是「这是字幕入口」的唯一形状提示。
 */
internal val PlayerControl.valueIconRes: Int?
  get() = when (this) {
    // P11-124:与 iconRes 的「字幕」项同形(CC)——两处保持一致,不出现「底栏 CC / 值按钮三横线」。
    PlayerControl.Subtitle -> R.drawable.ic_player_cc
    else -> null
  }

private val PlayerPanel.titleRes: Int
  get() = when (this) {
    PlayerPanel.Main -> R.string.player_settings_title
    PlayerPanel.Quality -> R.string.player_settings_quality
    PlayerPanel.Audio -> R.string.player_audio_track
    PlayerPanel.Subtitle -> R.string.player_subtitle
    PlayerPanel.Danmaku -> R.string.player_settings_danmaku
    PlayerPanel.Speed -> R.string.player_settings_speed
    PlayerPanel.Episodes -> R.string.player_panel_episodes
    PlayerPanel.UpVideos -> R.string.player_panel_up_videos
    PlayerPanel.RelatedVideos -> R.string.player_panel_related_videos
    PlayerPanel.Favorite -> R.string.player_panel_favorite
    PlayerPanel.None -> R.string.player_settings_title
  }

private fun String.withCodecLabel(codec: String): String {
  return if (codec.isBlank()) this else "$this($codec)"
}

/**
 * P11-120:字幕轨列表标题。显示名缺省回落语言码;自动生成(asr)轨加本地化后缀
 * ——YouTube 同一语言常同时给人工轨与 asr 轨(如 en 与 en-asr),不标就分不清。
 */
@Composable
private fun subtitleRowTitle(track: PlaybackTrack): String {
  val label = convertChineseText(SubtitleTracks.labelOf(track))
  return if (track.isAutoGenerated) {
    stringResource(R.string.player_subtitle_auto, label)
  } else {
    label
  }
}

private fun PlaybackEpisode.panelTitle(index: Int): String {
  val pageIndex = page.takeIf { it > 0 } ?: index + 1
  return if (title.isBlank()) "P$pageIndex" else "P$pageIndex $title"
}

private fun VideoSummary.panelPubdateText(): String {
  if (pubdate <= 0L) return ""
  val date = Date(pubdate * 1000L)
  return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
}

@Composable
private fun PlaybackRequest.formatPubdate(): String? {
  if (pubdate <= 0L) return null
  val date = Date(pubdate * 1000L)
  return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
}

@Composable
internal fun Int.formatCompactCountText(): String {
  return formatCompactCount(this, currentUiLocale())
}

@Composable
private fun Float.areaText(): String {
  return stringResource(areaTextRes())
}

@StringRes
private fun Float.areaTextRes(): Int {
  return when {
    this >= 1f -> R.string.player_area_full
    this >= 0.75f -> R.string.player_area_three_quarters
    this >= 0.5f -> R.string.player_area_half
    else -> R.string.player_area_quarter
  }
}

/**
 * P11-124:官方底栏的倍速文案形态(`1.0x` / `1.25x` / `0.5x`)。
 *
 * 与 [speedText](设置面板里那句「1.0 倍」)分开:官方播放器底栏就写 `1.0x`,而设置面板沿用中文「倍」
 * 的既有措辞,两处不互相牵连(改一个不会动到另一个)。
 */
private fun Float.speedTextBadge(): String {
  val number = if (this % 1f == 0f) {
    String.format(java.util.Locale.US, "%.1f", this)
  } else {
    String.format(java.util.Locale.US, "%.2f", this).trimEnd('0')
  }
  return "${number}x"
}

@Composable
private fun Float.speedText(): String {
  return when (this) {
    0.5f -> stringResource(R.string.player_speed_050)
    0.75f -> stringResource(R.string.player_speed_075)
    1.0f -> stringResource(R.string.player_speed_100)
    1.25f -> stringResource(R.string.player_speed_125)
    1.5f -> stringResource(R.string.player_speed_150)
    2.0f -> stringResource(R.string.player_speed_200)
    else -> stringResource(R.string.player_speed_value, this)
  }
}

internal fun Long.toPlayerTime(): String {
  val totalSeconds = (this / 1000L).coerceAtLeast(0L)
  val hours = totalSeconds / 3600L
  val minutes = (totalSeconds % 3600L) / 60L
  val seconds = totalSeconds % 60L
  return if (hours > 0L) {
    "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
  } else {
    "%02d:%02d".format(Locale.US, minutes, seconds)
  }
}

internal val PlayerSpeedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

internal const val UpVideoOrderLatest = "pubdate"
internal const val UpVideoOrderHot = "click"
internal const val UpFocusSort = 0
internal const val UpFocusFollow = 1
internal const val UpFocusHome = 2
internal const val UpPanelHeaderItemCount = 3

private const val UpPanelChipSelectedSurfaceAlpha = 0.16f
private const val UpPanelChipFocusedBorderAlpha = 0.82f
private const val UpPanelChipSelectedBorderAlpha = 0.54f
private const val UpPanelChipRestingBorderAlpha = 0.16f
private const val DanmakuSettingsRowCount = 8
private const val SeekPreviewSpriteScale = 2f
private const val SeekPreviewSpriteMaxWidth = 360f
private const val SeekPreviewSpriteMaxHeight = 220f
