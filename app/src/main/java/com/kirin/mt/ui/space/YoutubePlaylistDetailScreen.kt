package com.kirin.mt.ui.space

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubeHistoryStore
import com.kirin.mt.core.youtube.YoutubeParsers
import com.kirin.mt.core.youtube.YoutubePlaylistHeader
import com.kirin.mt.core.youtube.YoutubeRepository
import com.kirin.mt.ui.focus.focusDiag
import com.kirin.mt.ui.glass.LocalLiquidGlassBackdrop
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 距末尾 2s 内视为已看完(对齐播放器「播到结尾」判定裕量)。 */
private const val CompletedThresholdMs = 2_000L

/** 初焦重试上限(帧):覆盖层初焦单发在低端盒子上会撞「FocusRequester is not initialized」,失败即整页无焦点。 */
private const val InitialFocusMaxFrames = 30

/** 聚焦高亮底色透明度(纯粉直画,用户选定样例 A:比玻璃面 PlayerPanelFocused 的 30% 再实一档)。 */
private const val FocusHighlightAlpha = 0.35f

/**
 * 详情页聚焦高亮底:纯粉半透明直画 background,**不走 playerFocusedLiquidGlassSurface 玻璃链路**。
 * P11-52 的玻璃高亮在覆盖层环境真机不可见(焦点已确认进屏 GAINED [playlist-detail]、OK 可播,
 * 用户仍报整页无高亮),改为不依赖玻璃管线/性能策略的硬渲染(P11-72,样例 A:粉底+粉框);
 * 粉边框仍由调用处 .border(3dp) 叠加。
 */
private fun Modifier.playlistFocusFill(focused: Boolean, shape: Shape): Modifier =
  if (focused) {
    background(BiliColors.BiliPink.copy(alpha = FocusHighlightAlpha), shape)
  } else {
    this
  }

/**
 * TV 版 YouTube 播放列表详情页(频道页"播放列表" tab 点卡片进入)。镜像移动端
 * MobileYoutubePlaylistDetailScreen 的数据流(getPlaylistVideos 首屏 header 元数据 + 滚动
 * continuation 翻页),布局 D-pad 化:顶栏返回 + 封面/标题/作者·视频数 + 「播放全部」 +
 * 可展开简介 + 带序号视频行列表。
 *
 * 连播:点视频行/「播放全部」均把当前已加载的整份 videos 快照为播放队列传出
 * (onStartSelected),播放器播完按队列下一项连播(对齐移动端 playQueue)。
 * 缩略图底部观看进度条 + 右下角「已看完」角标:数据取本地 YouTube 播放历史
 * (YoutubeHistoryStore.positionMs/durationMs,播放器写入;TV 播完写 ≈duration,
 * 移动端播完写 0,两种都算已看完)。
 * 「正在播放」标记(P11-69,用户反馈列表内看不出选过的视频):播放历史 lastPlayedAtMs
 * 最新的那条视频行,序号换成粉色 ▶ + 标题变粉(全列表唯一;从未播过无标记)。
 */
@Composable
internal fun YoutubePlaylistDetailScreen(
  youtubeRepository: YoutubeRepository,
  youtubeHistoryStore: YoutubeHistoryStore,
  playlist: YoutubeParsers.YoutubePlaylist,
  onStartSelected: (video: VideoSummary, queue: List<VideoSummary>) -> Unit,
  onBack: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  val coroutineScope = rememberCoroutineScope()
  var videos by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
  var header by remember { mutableStateOf<YoutubePlaylistHeader?>(null) }
  var continuation by remember { mutableStateOf<String?>(null) }
  var loading by remember { mutableStateOf(true) }
  var loadingMore by remember { mutableStateOf(false) }
  var endReached by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf<String?>(null) }
  var descExpanded by remember { mutableStateOf(false) }
  var playAllFocused by remember { mutableStateOf(false) }
  var backFocused by remember { mutableStateOf(false) }
  val playAllFocusRequester = remember { FocusRequester() }
  val backFocusRequester = remember { FocusRequester() }
  // 列表首行落点:「播放全部」按 ↓ 显式聚焦到它(覆盖层默认焦点搜索不可靠,P11-50/51 教训)。
  val firstRowFocusRequester = remember { FocusRequester() }
  var firstFocusDone by remember { mutableStateOf(false) }
  // 本屏(含子节点)是否持有焦点:初焦重试的成功判据。焦点在遮挡层后面(频道页卡片)时为 false。
  var screenHasFocus by remember { mutableStateOf(false) }

  BackHandler { onBack() }

  fun loadFirst() {
    coroutineScope.launch {
      loading = true
      failed = null
      continuation = null
      loadingMore = false
      endReached = false
      try {
        val page = youtubeRepository.getPlaylistVideos(playlist.browseId)
        videos = page.items.distinctBy { it.bvid }
        header = page.playlistHeader
        continuation = page.continuation
        endReached = page.continuation == null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failed = e.message.orEmpty().ifBlank { "加载失败" }
        videos = emptyList()
        continuation = null
        endReached = true
      }
      loading = false
    }
  }

  fun loadNext() {
    val token = continuation
    if (token == null || loadingMore || endReached) return
    loadingMore = true
    coroutineScope.launch {
      try {
        val page = youtubeRepository.getPlaylistVideos(playlist.browseId, token)
        val old = videos
        val merged = (old + page.items).distinctBy { it.bvid }
        videos = merged
        continuation = page.continuation
        endReached = page.continuation == null || merged.size == old.size
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 翻页失败保留已加载内容
      }
      loadingMore = false
    }
  }

  LaunchedEffect(playlist.browseId) { loadFirst() }

  // 本地播放历史按 videoId 索引:视频行缩略图底部进度条 + 「已看完」角标的数据源。
  // collectAsState 持续订阅:播放器写入进度返回本页即刷新,无需手动刷新。
  val history by youtubeHistoryStore.history.collectAsState(initial = emptyList())
  val historyByVideoId = remember(history) { history.associateBy { it.videoId } }
  // 「正在播放」标记:播放历史里 lastPlayedAtMs 最新的那条(全列表唯一)。
  // 播放器播这条时写入进度,lastPlayedAtMs 最大即最近在播;从未播过的列表无标记。
  val playingVideoId = remember(videos, history) {
    videos.asSequence()
      .mapNotNull { historyByVideoId[it.bvid] }
      .filter { it.lastPlayedAtMs > 0L && it.positionMs >= 0L }
      .maxByOrNull { it.lastPlayedAtMs }
      ?.videoId
  }

  // 首屏到达后聚焦「播放全部」(空列表/加载失败聚焦返回 chip)。
  // 单发 requestFocus 在覆盖层环境不可靠:等一帧仍可能撞「FocusRequester is not initialized」
  // (真机 logs_live 23:58:32/00:04:42 同款异常实锤),被 runCatching 吞掉后整页永远无焦点,
  // 且焦点留在被盖住的频道页卡片后面、按键全落空。改为验证+重试:拉焦点后等一帧确认
  // screenHasFocus,未确认继续拉,至多 InitialFocusMaxFrames 帧;焦点已在屏上(用户手动
  // 移动过)立即停,不抢焦点。
  LaunchedEffect(loading, failed) {
    if (loading || firstFocusDone) return@LaunchedEffect
    var attempt = 0
    var confirmed = false
    while (attempt < InitialFocusMaxFrames) {
      withFrameNanos { }
      if (screenHasFocus) {
        confirmed = true
        break
      }
      runCatching {
        if (videos.isNotEmpty()) playAllFocusRequester.requestFocus() else backFocusRequester.requestFocus()
      }
      attempt++
      withFrameNanos { }
      if (screenHasFocus) {
        confirmed = true
        break
      }
    }
    Log.i(
      "BiliMT:FocusDiag",
      "playlist-focus initial done attempts=$attempt confirmed=$confirmed " +
        "target=${if (videos.isNotEmpty()) "playall" else "back"} screenHasFocus=$screenHasFocus",
    )
    firstFocusDone = true
  }

  val cover = header?.cover?.takeIf { it.isNotBlank() } ?: playlist.thumbnail
  val countText = header?.videoCountText?.takeIf { it.isNotBlank() } ?: playlist.videoCount
  val owner = header?.owner?.takeIf { it.isNotBlank() }
  val desc = header?.description

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(BiliColors.VideoBlack)
      .focusDiag("playlist-detail")
      .onFocusChanged { screenHasFocus = it.hasFocus },
  ) {
    // 顶栏:返回 + 播放列表名。
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = BiliSizing.VideoGridHorizontalPadding, vertical = BiliSpacing.Md),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
    ) {
      YoutubePlaylistBackChip(
        focusRequester = backFocusRequester,
        onActivate = onBack,
      )
      Text(
        text = playlist.title,
        color = BiliColors.TextPrimary,
        fontSize = BiliTypography.PlayerTitle,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(
        horizontal = BiliSizing.VideoGridHorizontalPadding,
        vertical = BiliSpacing.Md,
      ),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
    ) {
      if (!loading || failed != null) {
        item {
          Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg)) {
            Box(
              modifier = Modifier
                .width(320.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(BiliRadius.Card))
                .background(BiliColors.SurfaceElevated),
            ) {
              if (cover.isNotBlank()) {
                AsyncImage(
                  model = cover,
                  contentDescription = playlist.title,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxWidth(),
                )
              } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                  Text("▶", color = BiliColors.TextSecondary, fontSize = BiliTypography.ScreenTitle)
                }
              }
            }
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
            ) {
              Text(
                text = playlist.title,
                color = BiliColors.TextPrimary,
                fontSize = BiliTypography.PlayerTitle,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
              val meta = listOfNotNull(owner, countText.takeIf { it.isNotBlank() }).joinToString(" · ")
              if (meta.isNotBlank()) {
                Text(
                  text = meta,
                  color = BiliColors.TextSecondary,
                  fontSize = BiliTypography.PlayerMeta,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              if (!desc.isNullOrBlank()) {
                Text(
                  text = if (descExpanded) desc else desc.take(120),
                  color = BiliColors.TextSecondary,
                  fontSize = BiliTypography.BodySmall,
                  maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.clickable { descExpanded = !descExpanded },
                )
              }
              // 「播放全部」:第一条起播,整份已加载列表作连播队列。
              // 聚焦高亮 = 实心粉底 + 粉边框(硬渲染,不再走玻璃链路,见 playlistFocusFill 注释)。
              val shape = RoundedCornerShape(BiliRadius.Pill)
              Box(
                modifier = Modifier
                  .clip(shape)
                  .playlistFocusFill(focused = playAllFocused, shape = shape)
                  .border(
                    androidx.compose.foundation.BorderStroke(
                      BiliFocus.BorderWidth,
                      if (playAllFocused) BiliColors.BiliPink else Color.Transparent,
                    ),
                    shape,
                  )
                  .focusRequester(playAllFocusRequester)
                  .focusable()
                  .onFocusChanged {
                    if (it.isFocused != playAllFocused) {
                      Log.i("BiliMT:FocusDiag", "playlist-playall focused=${it.isFocused}")
                    }
                    playAllFocused = it.isFocused
                  }
                  .onPreviewKeyEvent { event ->
                    val confirm = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                      event.key == Key.DirectionCenter
                    when {
                      // ↓ 显式落列表首行:覆盖层默认焦点搜索曾证不可靠(P11-50/51),不交给搜索。
                      event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown &&
                        videos.isNotEmpty() -> {
                        runCatching { firstRowFocusRequester.requestFocus() }.isSuccess
                      }
                      event.type == KeyEventType.KeyUp && confirm && videos.isNotEmpty() -> {
                        onStartSelected(videos.first(), videos)
                        true
                      }
                      else -> false
                    }
                  }
                  .padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Sm),
              ) {
                Text(
                  text = "播放全部",
                  color = BiliColors.BiliPink,
                  fontSize = BiliTypography.Body,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          }
        }
      }
      when {
        failed != null -> item {
          Text(
            text = failed ?: "",
            color = BiliColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
              .fillMaxWidth()
              .padding(BiliSpacing.Lg),
          )
        }
        loading -> item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(BiliSpacing.Xl),
            contentAlignment = Alignment.Center,
          ) { CircularProgressIndicator() }
        }
        videos.isEmpty() -> item {
          Text(
            text = "暂无视频",
            color = BiliColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
              .fillMaxWidth()
              .padding(BiliSpacing.Xl),
          )
        }
        else -> {
          itemsIndexed(videos) { index, video ->
            val entry = historyByVideoId[video.bvid]
            val completed = entry != null && entry.durationMs > 0 &&
              (entry.positionMs == 0L || entry.positionMs >= entry.durationMs - CompletedThresholdMs)
            val ratio = when {
              completed -> 1f
              entry != null && entry.durationMs > 0 && entry.positionMs > 0 ->
                (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
              else -> 0f
            }
            YoutubePlaylistVideoRow(
              video = video,
              index = index,
              progressRatio = ratio,
              completed = completed,
              playing = video.bvid == playingVideoId,
              // 首行挂 requester 供「播放全部」↓ 显式落点;首行 ↑ 显式回「播放全部」。
              firstRow = index == 0,
              firstRowFocusRequester = firstRowFocusRequester,
              onMoveUpFromFirstRow = {
                runCatching { playAllFocusRequester.requestFocus() }.isSuccess
              },
              onFocused = {
                if (index >= videos.size - 6) loadNext()
              },
              onActivate = { onStartSelected(video, videos) },
            )
          }
          if (loadingMore) {
            item {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(BiliSpacing.Md),
                contentAlignment = Alignment.Center,
              ) { CircularProgressIndicator() }
            }
          }
        }
      }
    }
  }
}

/** 详情页顶栏返回 chip(聚焦高亮,OK 返回;focusRequester 供空列表/加载失败时初焦落点)。 */
@Composable
private fun YoutubePlaylistBackChip(
  focusRequester: FocusRequester,
  onActivate: () -> Boolean,
) {
  var focused by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(BiliRadius.Pill)
  Box(
    modifier = Modifier
      .clip(shape)
      .focusRequester(focusRequester)
      .onFocusChanged {
        if (it.isFocused != focused) {
          Log.i("BiliMT:FocusDiag", "playlist-back focused=${it.isFocused}")
        }
        focused = it.isFocused
      }
      .border(
        androidx.compose.foundation.BorderStroke(
          BiliFocus.BorderWidth,
          if (focused) BiliColors.TextPrimary else Color.Transparent,
        ),
        shape,
      )
      .focusable()
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp && event.key.let {
            it == Key.Enter || it == Key.NumPadEnter || it == Key.DirectionCenter
          }
        ) {
          onActivate()
          true
        } else {
          false
        }
      }
      .padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Xs),
  ) {
    Text("‹", color = BiliColors.TextPrimary, fontSize = BiliTypography.ScreenTitle)
  }
}

/** 详情页一条视频行:序号(正在播放换成粉色 ▶) + 封面(右下角「已看完」角标、底部观看进度条) + 标题(正在播放变粉)/作者。聚焦近底触发翻页;首行 ↑ 显式回「播放全部」。 */
@Composable
private fun YoutubePlaylistVideoRow(
  video: VideoSummary,
  index: Int,
  progressRatio: Float,
  completed: Boolean,
  playing: Boolean,
  firstRow: Boolean,
  firstRowFocusRequester: FocusRequester,
  onMoveUpFromFirstRow: () -> Boolean,
  onFocused: () -> Unit,
  onActivate: () -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(BiliRadius.Card)
  // 玻璃高亮渲染链路诊断:一次真机日志分辨「焦点回调没触发」vs「玻璃路径没画出来」。
  val performancePolicy = LocalBiliPerformancePolicy.current
  val liquidGlassEnabled =
    performancePolicy.cinematicVisualEffectsEnabled && performancePolicy.liquidGlassCardsEnabled
  val backdropPresent = LocalLiquidGlassBackdrop.current != null
  Row(
    modifier = (if (firstRow) Modifier.focusRequester(firstRowFocusRequester) else Modifier)
      .fillMaxWidth()
      .clip(shape)
      // 聚焦高亮 = 实心粉底 + 粉边框(硬渲染,不再走玻璃链路,见 playlistFocusFill 注释)。
      .playlistFocusFill(focused = focused, shape = shape)
      .border(
        androidx.compose.foundation.BorderStroke(
          BiliFocus.BorderWidth,
          if (focused) BiliColors.BiliPink else Color.Transparent,
        ),
        shape,
      )
      .focusable()
      .onFocusChanged {
        if (it.isFocused != focused) {
          Log.i(
            "BiliMT:FocusDiag",
            "playlist-row index=$index focused=${it.isFocused} " +
              "glass=$liquidGlassEnabled backdrop=$backdropPresent",
          )
        }
        focused = it.isFocused
        if (it.isFocused) onFocused()
      }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyUp && event.key.let {
            it == Key.Enter || it == Key.NumPadEnter || it == Key.DirectionCenter
          } -> {
            onActivate()
            true
          }
          // 首行 ↑ 显式回「播放全部」(与它的 ↓ 显式落首行成对,防覆盖层默认搜索丢焦)。
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && firstRow ->
            onMoveUpFromFirstRow()
          else -> false
        }
      }
      .padding(BiliSpacing.Sm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
  ) {
    // 正在播放:序号换成粉色 ▶(对齐用户确认样例),标题同步变粉。
    Text(
      text = if (playing) "▶" else "${index + 1}",
      color = if (playing) BiliColors.BiliPink else BiliColors.TextSecondary,
      fontSize = BiliTypography.Body,
      textAlign = TextAlign.Center,
      modifier = Modifier.width(28.dp),
    )
    Box(
      modifier = Modifier
        .width(200.dp)
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(8.dp))
        .background(BiliColors.SurfaceElevated),
    ) {
      AsyncImage(
        model = video.pic,
        contentDescription = video.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth(),
      )
      // 观看进度:底部细条(样式对齐 TV VideoCard 的 VideoWatchProgressBar:轨道+粉色填充)。
      if (progressRatio > 0f) {
        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(BiliSizing.VideoProgressBarHeight)
            .background(BiliColors.ProgressTrack),
        ) {
          Box(
            modifier = Modifier
              .fillMaxHeight()
              .fillMaxWidth(progressRatio)
              .background(BiliColors.BiliPink),
          )
        }
      }
      // 已看完角标:贴缩略图右下,深色半透明 pill(样式对齐移动端 CompletedBadge)。
      if (completed) {
        Text(
          text = "已看完",
          color = Color.White,
          fontSize = 10.sp,
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        )
      }
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs)) {
      Text(
        text = video.title,
        color = if (playing) BiliColors.BiliPink else BiliColors.TextPrimary,
        fontSize = BiliTypography.Body,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (video.ownerName.isNotBlank()) {
        Text(
          text = video.ownerName,
          color = BiliColors.TextSecondary,
          fontSize = BiliTypography.BodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
