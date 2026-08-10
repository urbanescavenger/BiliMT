package com.kirin.mt.ui.home

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.ui.common.VideoThumbnailPrefetcher
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliMotion
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// 视频退出后把焦点拉回原卡片的最多重试帧数。长视频后主线程繁忙(图片缓存被挤占、
// 播放器 teardown、GC)时目标卡片首帧布局可能延迟,盲目 requestFocus 会连续失败,
// 帧用完后 onRestoreFocusHandled 清掉 destination → suppress 关闭 → 焦点留在头像。
// 调大到 90 帧(60fps≈1.5s、30fps≈3s)覆盖慢布局;短视频首帧即就绪,重试随即 break 不会等满。
// 配合 AppShell 的 PlaybackFocusRestoreCleanupFrameCount(必须 > 本值 + TvGridRestoreFocusWaitLayoutFrames)。
private const val TvGridRestoreFocusRetryCount = 90

// 退出恢复时先等目标行真的进入 LazyList 视口布局再开始 requestFocus。退出卡顿
// (ExoPlayer teardown + 首页重组 + 弹幕 draw 挤主线程)时目标行首帧可能晚若干帧才组合,
// 此时 itemFocusRequester 尚未挂上任何节点,requestFocus 必失败——先等 visibleItemsInfo
// 里出现目标行,再抢焦点,把"按帧数盲重试"改成"等布局就位再抢"。
private const val TvGridRestoreFocusWaitLayoutFrames = 90

internal const val TvFocusLogTag = "BiliMT:Focus"

// Keys that confirm a card selection; holding one for this long opens the card's long-press action menu.
private val VideoCardOwnerConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
private const val VideoCardOwnerLongPressMs = 500L

/**
 * 网格尾部状态:展示加载更多进度/到底/失败重试。None 时不渲染 footer。
 */
internal sealed interface GridFooterState {
  data object None : GridFooterState
  data object Loading : GridFooterState
  data object EndReached : GridFooterState
  data class Error(val message: String) : GridFooterState
}

private val TvGridBringIntoViewSpec = object : BringIntoViewSpec {
  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    // D-pad row scrolling is handled below. Returning 0 prevents Compose's
    // default focus relocation from doing an instant pre-scroll first.
    return 0f
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvVideoGrid(
  videos: List<VideoSummary>,
  firstItemFocusRequester: FocusRequester,
  restoredFocusIndex: Int,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onFocusedIndexChange: (Int, VideoSummary) -> Unit,
  onLoadMore: () -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit = {},
  onCardLongPress: (VideoSummary) -> Unit = {},
  modifier: Modifier = Modifier,
  cardMode: VideoCardMode = VideoCardMode.Standard,
  footer: GridFooterState = GridFooterState.None,
  requestInitialFocus: Boolean = false,
  onInitialFocusRequested: () -> Unit = {},
  focusFirstItemKey: Int = 0,
  focusRestoredItemKey: Int = 0,
  // 当前 section/分区标识。变化时(切已缓存分区,网格被复用而非重组)主动滚回第 0 行,
  // 避免「列表内容更新但视口停在旧位置 / 从 tab 按 Down 跳过整版」。
  // 网格被重组(侧栏 nav / 视频返回)时由 rememberLazyListState 的 initial 行处理,本 effect 不触发。
  sectionKey: Any? = null,
  onMoveUpFromFirstRow: () -> Boolean = { true },
  onBackKey: (() -> Boolean)? = null,
  horizontalPadding: Dp = BiliSizing.VideoGridHorizontalPadding,
  topPadding: Dp = BiliFocus.ScrollInset,
  topBleed: Dp = 0.dp,
  keyFactory: (Int, VideoSummary) -> Any = { _, video -> video.bvid },
) {
  val columns = BiliSizing.VideoGridColumns
  val rowCount = (videos.size + columns - 1) / columns
  val restoreTargetIndex = restoredFocusIndex.coerceIn(0, (videos.size - 1).coerceAtLeast(0))
  val restoreTargetRow = if (videos.isEmpty()) {
    0
  } else {
    restoreTargetIndex / columns
  }
  // 仅在「视频退出恢复」(restoreFocusRequestKey > 0)时用 restoreTargetRow 起始;其它重组场景
  // (侧栏 nav 切目的地 / 首次进入 / 切子 tab)从 0 开始,不再停在持久化 UiState 里的旧位置。
  val listState = rememberLazyListState(
    initialFirstVisibleItemIndex = if (restoreFocusRequestKey > 0) restoreTargetRow else 0,
  )
  val coroutineScope = rememberCoroutineScope()
  var centerDownMs by remember { mutableLongStateOf(0L) }
  val performancePolicy = LocalBiliPerformancePolicy.current
  val density = LocalDensity.current
  val topBleedPx = with(density) { topBleed.roundToPx() }
  val focusScrollInsetPx = with(density) { topPadding.roundToPx() }
  val focusedRowTopPaddingPx = with(density) { BiliFocus.FocusedRowTopPadding.roundToPx() }
  val videoCardFallbackHeightPx = with(density) { BiliSizing.VideoCardMinHeight.roundToPx() }
  val restoredItemFocusRequester = remember { FocusRequester() }
  val itemFocusRequesters = remember(videos.size, firstItemFocusRequester, restoredItemFocusRequester, restoreTargetIndex) {
    List(videos.size) { index ->
      when (index) {
        0 -> firstItemFocusRequester
        restoreTargetIndex -> restoredItemFocusRequester
        else -> FocusRequester()
      }
    }
  }
  var focusScrollJob by remember { mutableStateOf<Job?>(null) }
  var focusedIndex by remember { mutableIntStateOf(-1) }
  var rowScrollActive by remember { mutableStateOf(false) }
  var rowScrollGeneration by remember { mutableIntStateOf(0) }
  val focusScale = when {
    !performancePolicy.motionEnabled -> 1f
    performancePolicy.cinematicVisualEffectsEnabled -> BiliFocus.CinematicCardScale
    else -> BiliFocus.CardScale
  }

  VideoThumbnailPrefetcher(
    videos = videos,
    focusedIndex = if (focusedIndex >= 0) focusedIndex else restoredFocusIndex,
    enabled = !rowScrollActive,
  )

  suspend fun scrollRow(row: Int, smoothScroll: Boolean) {
    listState.scrollRowIntoStablePosition(
      row = row,
      totalRows = rowCount,
      fallbackItemHeightPx = videoCardFallbackHeightPx,
      scrollInsetPx = focusScrollInsetPx,
      focusedRowTopPaddingPx = focusedRowTopPaddingPx,
      focusScale = focusScale,
      smoothScroll = smoothScroll,
    )
  }

  LaunchedEffect(restoreFocusRequestKey, restoredFocusIndex, videos.size) {
    if (restoreFocusRequestKey <= 0 || videos.isEmpty()) {
      if (restoreFocusRequestKey > 0) {
        Log.d(
          TvFocusLogTag,
          "restore skipped: key=$restoreFocusRequestKey videos=${videos.size} restoredIndex=$restoredFocusIndex",
        )
      }
      return@LaunchedEffect
    }
    val targetIndex = restoredFocusIndex.coerceIn(0, videos.lastIndex)
    val targetRow = targetIndex / columns
    Log.d(
      TvFocusLogTag,
      "restore start: key=$restoreFocusRequestKey targetIndex=$targetIndex targetRow=$targetRow videos=${videos.size}",
    )
    scrollRow(targetRow, smoothScroll = false)
    // 先等目标行进入视口布局(itemFocusRequester 才会挂上节点),再开始抢焦点。
    // 退出卡顿时目标行首帧晚若干帧才组合,在此之前 requestFocus 必失败、白耗预算。
    var waitedFrames = 0
    while (
      listState.layoutInfo.visibleItemsInfo.none { it.index == targetRow } &&
      waitedFrames < TvGridRestoreFocusWaitLayoutFrames
    ) {
      withFrameNanos { }
      waitedFrames += 1
    }
    val rowVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == targetRow }
    Log.d(
      TvFocusLogTag,
      "restore layout: rowVisible=$rowVisible waitedFrames=$waitedFrames/$TvGridRestoreFocusWaitLayoutFrames",
    )
    repeat(TvGridRestoreFocusRetryCount) { attempt ->
      withFrameNanos { }
      val focused = runCatching {
        itemFocusRequesters[targetIndex].requestFocus()
      }.getOrDefault(false)
      if (focused) {
        Log.d(
          TvFocusLogTag,
          "restore success: key=$restoreFocusRequestKey attempt=$attempt rowVisible=$rowVisible",
        )
        onRestoreFocusHandled(restoreFocusRequestKey)
        return@LaunchedEffect
      }
    }
    Log.w(
      TvFocusLogTag,
      "restore failed: key=$restoreFocusRequestKey targetIndex=$targetIndex rowVisible=$rowVisible " +
        "(focus likely stayed on avatar)",
    )
    onRestoreFocusHandled(restoreFocusRequestKey)
  }

  LaunchedEffect(videos.size, requestInitialFocus) {
    if (requestInitialFocus && videos.isNotEmpty()) {
      withFrameNanos { }
      runCatching {
        firstItemFocusRequester.requestFocus()
      }
      onInitialFocusRequested()
    }
  }

  // 只在显式触发（tab 按下键）时滚回并聚焦首项；不要监听 videos.size，
  // 否则刷新分区/加载更多后焦点会自动从 tab 跳回网格第一项。
  // 另：LaunchedEffect 首次组合必定执行一次，而切 tab 会让本 Composable 被销毁后
  // 全新重组——此时 focusFirstItemKey 是 RecommendUiState 里持久化的旧值（上次按
  // Down 进网格后自增过）。若不加 guard，从动态切回首页就会用这个旧值抢首项焦点。
  // 用 lastHandledFirstItemKey 记录已处理过的 key，首次组合（含切 tab 重组）跳过，
  // 只在 key 真正自增时执行。
  val lastHandledFirstItemKey = remember { mutableIntStateOf(focusFirstItemKey) }
  LaunchedEffect(focusFirstItemKey) {
    if (focusFirstItemKey == lastHandledFirstItemKey.intValue) {
      return@LaunchedEffect
    }
    lastHandledFirstItemKey.intValue = focusFirstItemKey
    if (focusFirstItemKey <= 0 || videos.isEmpty()) {
      return@LaunchedEffect
    }
    listState.scrollToItem(0, scrollOffset = -focusedRowTopPaddingPx)
    repeat(TvGridRestoreFocusRetryCount) {
      withFrameNanos { }
      val focused = runCatching {
        firstItemFocusRequester.requestFocus()
      }.getOrDefault(false)
      if (focused) {
        return@LaunchedEffect
      }
    }
  }

  // 切已缓存分区时网格被复用(不经过 Loading 销毁),listState 实例保留旧滚动位置。
  // 这里在 sectionKey 真正变化时主动滚回第 0 行(只滚不抢焦点,焦点留在 tab 上),
  // 让「切 tab → 列表回顶 → 按 Down 落第一行(不跳版面)」。
  // 首次组合 guard:网格被重组(侧栏 nav / 视频返回)时 remember 重新初始化 lastSectionKey,
  // 与当前 sectionKey 相等 → 不触发(由 rememberLazyListState 的 initial 行处理)。
  val lastSectionKey = remember { mutableStateOf(sectionKey) }
  LaunchedEffect(sectionKey) {
    if (sectionKey == lastSectionKey.value) {
      return@LaunchedEffect
    }
    lastSectionKey.value = sectionKey
    if (videos.isNotEmpty()) {
      listState.scrollToItem(0, scrollOffset = -focusedRowTopPaddingPx)
    }
  }

  // 只在显式触发（tab 按下键）时滚回并聚焦上次所在项；不监听 videos.size，
  // 避免刷新/加载更多后焦点从 tab 误跳。与 focusFirstItemKey 同理，但落点是
  // restoreTargetIndex（上次聚焦的卡片），保留下滑位置而非滚回顶部。
  // 同样需要首次组合 guard：切 tab 重组时 focusRestoredItemKey 是 UserFeedUiState
  // 里的持久旧值，不抢焦点；只在 key 真正自增时执行。
  val lastHandledRestoredItemKey = remember { mutableIntStateOf(focusRestoredItemKey) }
  LaunchedEffect(focusRestoredItemKey) {
    if (focusRestoredItemKey == lastHandledRestoredItemKey.intValue) {
      return@LaunchedEffect
    }
    lastHandledRestoredItemKey.intValue = focusRestoredItemKey
    if (focusRestoredItemKey <= 0 || videos.isEmpty()) {
      return@LaunchedEffect
    }
    val targetIndex = restoreTargetIndex
    scrollRow(targetIndex / columns, smoothScroll = false)
    repeat(TvGridRestoreFocusRetryCount) {
      withFrameNanos { }
      val focused = runCatching {
        itemFocusRequesters[targetIndex].requestFocus()
      }.getOrDefault(false)
      if (focused) {
        return@LaunchedEffect
      }
    }
  }

  fun focusItem(index: Int): Boolean {
    return runCatching {
      itemFocusRequesters[index].requestFocus()
    }.getOrDefault(false)
  }

  fun commitFocusedItem(index: Int) {
    videos.getOrNull(index)?.let { video ->
      onFocusedIndexChange(index, video)
    }
  }

  fun scrollThenFocusItem(index: Int, row: Int) {
    focusScrollJob?.cancel()
    val scrollGeneration = ++rowScrollGeneration
    rowScrollActive = true
    focusScrollJob = coroutineScope.launch {
      val smoothScroll = performancePolicy.smoothScrollingEnabled
      try {
        if (smoothScroll) {
          val scrollJob = launch {
            scrollRow(row, smoothScroll = true)
          }
          delay(BiliMotion.FocusScrollDelayMs)
          focusItem(index)
          scrollJob.join()
          delay(BiliMotion.FocusScrollSettleMs)
        } else {
          scrollRow(row, smoothScroll = false)
          withFrameNanos { }
          focusItem(index)
        }
      } finally {
        if (rowScrollGeneration == scrollGeneration) {
          rowScrollActive = false
        }
      }
    }
  }

  fun moveFocus(fromIndex: Int, direction: Key): Boolean {
    val currentRow = fromIndex / columns
    val currentColumn = fromIndex % columns
    val lastIndex = videos.lastIndex
    val lastRow = lastIndex / columns

    if (direction == Key.DirectionUp && currentRow == 0) {
      commitFocusedItem(fromIndex)
      return onMoveUpFromFirstRow()
    }
    if (direction == Key.DirectionLeft && currentColumn == 0) {
      commitFocusedItem(fromIndex)
      return onMoveLeftToNav()
    }

    val targetIndex = when (direction) {
      Key.DirectionUp -> ((currentRow - 1) * columns + currentColumn).coerceAtMost(lastIndex).takeIf { currentRow > 0 }
      Key.DirectionDown -> ((currentRow + 1) * columns + currentColumn).coerceAtMost(lastIndex).takeIf { currentRow < lastRow }
      Key.DirectionLeft -> (fromIndex - 1).takeIf { currentColumn > 0 }
      Key.DirectionRight -> (fromIndex + 1).takeIf { currentColumn < columns - 1 && it <= lastIndex && it / columns == currentRow }
      else -> null
    } ?: return direction == Key.DirectionRight
    // Down on the last row falls through (returns false) so default focus traversal can
    // reach a focusable footer below (e.g. the load-more retry button). When there is no
    // focusable footer, traversal finds nothing below and focus stays — same as before.

    if (direction == Key.DirectionLeft || direction == Key.DirectionRight) {
      return focusItem(targetIndex)
    }

    scrollThenFocusItem(targetIndex, targetIndex / columns)
    return true
  }

  CompositionLocalProvider(LocalBringIntoViewSpec provides TvGridBringIntoViewSpec) {
    LazyColumn(
      state = listState,
      modifier = modifier
        .fillMaxSize()
        .layout { measurable, constraints ->
          if (topBleedPx <= 0) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
              placeable.place(0, 0)
            }
          } else {
            val expandedMaxHeight = if (constraints.maxHeight == Constraints.Infinity) {
              Constraints.Infinity
            } else {
              constraints.maxHeight + topBleedPx
            }
            val placeable = measurable.measure(
              constraints.copy(maxHeight = expandedMaxHeight),
            )
            val layoutHeight = if (constraints.maxHeight == Constraints.Infinity) {
              placeable.height
            } else {
              constraints.maxHeight
            }
            layout(placeable.width, layoutHeight) {
              placeable.place(0, -topBleedPx)
            }
          }
        },
      contentPadding = PaddingValues(
        start = horizontalPadding,
        top = topPadding,
        end = horizontalPadding,
        bottom = BiliSizing.VideoGridBottomPadding,
      ),
      verticalArrangement = Arrangement.spacedBy(BiliSizing.VideoGridSpacing),
    ) {
      items(
        count = rowCount,
        key = { row ->
          val firstIndex = row * columns
          "row-$row-${keyFactory(firstIndex, videos[firstIndex])}"
        },
        contentType = { "video-row" },
      ) { row ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .zIndex(
              if (focusedIndex >= 0 && focusedIndex / columns == row) {
                BiliFocus.FocusedZIndex
              } else {
                0f
              },
            ),
          horizontalArrangement = Arrangement.spacedBy(BiliSizing.VideoGridSpacing),
        ) {
          repeat(columns) { column ->
            val index = row * columns + column
            if (index < videos.size) {
              val video = videos[index]
              VideoCard(
                video = video,
                mode = cardMode,
                interactionPaused = rowScrollActive,
                modifier = Modifier
                  .weight(1f)
                  .focusRequester(itemFocusRequesters[index])
                  .onPreviewKeyEvent { event ->
                    if (event.key in VideoCardOwnerConfirmKeys) {
                      // Long-press of the OK/confirm key opens this card's action menu
                      // (点赞/稍后再看/去 UP 主主页); a short tap falls through (returns false)
                      // so the card onClick plays the video.
                      when (event.type) {
                        KeyEventType.KeyDown -> {
                          if (centerDownMs == 0L) {
                            centerDownMs = SystemClock.uptimeMillis()
                          }
                          false
                        }
                        KeyEventType.KeyUp -> {
                          val held = if (centerDownMs > 0L) SystemClock.uptimeMillis() - centerDownMs else 0L
                          centerDownMs = 0L
                          if (held >= VideoCardOwnerLongPressMs && video.ownerMid > 0L) {
                            onCardLongPress(video)
                            true
                          } else {
                            false
                          }
                        }
                        else -> false
                      }
                    } else if (event.type != KeyEventType.KeyDown) {
                      false
                    } else {
                      when (event.key) {
                        Key.Back -> onBackKey?.invoke() ?: false
                        Key.DirectionUp,
                        Key.DirectionDown,
                        Key.DirectionLeft,
                        Key.DirectionRight -> moveFocus(index, event.key)
                        else -> false
                      }
                    }
                },
                onFocused = {
                  focusedIndex = index
                  centerDownMs = 0L
                  commitFocusedItem(index)
                  if (index.shouldLoadMore(
                      totalItems = videos.size,
                      threshold = performancePolicy.loadMoreFocusThreshold,
                    )
                  ) {
                    onLoadMore()
                  }
                },
                onClick = {
                  commitFocusedItem(index)
                  onVideoSelected(video)
                },
                onOwnerTap = { onOwnerSelected(video) },
              )
            } else {
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      }
      if (footer != GridFooterState.None) {
        item(key = "grid-footer", contentType = "grid-footer") {
          TvGridFooter(footer = footer, onRetry = onLoadMore)
        }
      }
    }
  }
}

@Composable
private fun TvGridFooter(
  footer: GridFooterState,
  onRetry: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = BiliSizing.VideoGridSpacing),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    when (footer) {
      GridFooterState.None -> Unit
      GridFooterState.Loading -> FooterText(text = stringResource(R.string.feed_footer_loading))
      GridFooterState.EndReached -> FooterText(text = stringResource(R.string.feed_footer_end))
      is GridFooterState.Error -> {
        FooterText(text = stringResource(R.string.feed_footer_failed))
        Spacer(modifier = Modifier.width(BiliSizing.VideoGridSpacing))
        FooterRetryButton(onRetry = onRetry)
      }
    }
  }
}

@Composable
private fun FooterText(text: String) {
  val homeColors = LocalHomeColors.current
  Text(
    text = text,
    color = homeColors.textTertiary,
    fontSize = BiliTypography.CardMeta,
    maxLines = 1,
  )
}

@Composable
private fun FooterRetryButton(onRetry: () -> Unit) {
  val homeColors = LocalHomeColors.current
  var focused by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(BiliRadius.Pill)
  Box(
    modifier = Modifier
      .clip(shape)
      .background(if (focused) homeColors.accent.copy(alpha = 0.18f) else BiliColors.Transparent)
      .border(
        width = if (focused) BiliFocus.BorderWidth else BiliFocus.RestingBorderWidth,
        color = if (focused) homeColors.accent else homeColors.textPrimary.copy(alpha = 0.25f),
        shape = shape,
      )
      .onFocusChanged { focused = it.isFocused }
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp && event.key.isFooterConfirmKey()) {
          onRetry()
          true
        } else {
          false
        }
      }
      .focusable()
      .padding(horizontal = BiliSpacing.Sm, vertical = BiliSpacing.Xs),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = stringResource(R.string.action_retry),
      color = if (focused) homeColors.accent else homeColors.textSecondary,
      fontSize = BiliTypography.CardMeta,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
    )
  }
}

private fun Key.isFooterConfirmKey(): Boolean {
  return this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
}

private suspend fun LazyListState.scrollRowIntoStablePosition(
  row: Int,
  totalRows: Int,
  fallbackItemHeightPx: Int,
  scrollInsetPx: Int,
  focusedRowTopPaddingPx: Int,
  focusScale: Float,
  smoothScroll: Boolean,
) {
  val safeRow = row.coerceIn(0, (totalRows - 1).coerceAtLeast(0))
  val layout = layoutInfo
  val viewportTop = layout.viewportStartOffset
  val viewportBottom = layout.viewportEndOffset
  val itemHeightPx = layout.visibleItemsInfo.firstOrNull { item -> item.index == safeRow }?.size
    ?: layout.visibleItemsInfo.firstOrNull()?.size
    ?: fallbackItemHeightPx
  val focusOverflowPx = ((itemHeightPx * (focusScale - 1f)) / 2f).roundToInt()
  val edgeInsetPx = scrollInsetPx + focusOverflowPx
  val focusedRow = layout.visibleItemsInfo.firstOrNull { item -> item.index == safeRow }

  if (focusedRow != null) {
    val targetTop = (viewportTop + focusedRowTopPaddingPx.coerceAtLeast(edgeInsetPx))
      .coerceAtMost(viewportBottom - edgeInsetPx - focusedRow.size)
      .coerceAtLeast(viewportTop + edgeInsetPx)
    val scrollDelta = focusedRow.offset - targetTop
    if (abs(scrollDelta) <= BiliMotion.FocusScrollMinDeltaPx) {
      return
    }
    if (smoothScroll) {
      animateScrollBy(
        value = scrollDelta.toFloat(),
        animationSpec = tween(
          durationMillis = BiliMotion.FocusScrollMs,
          easing = BiliMotion.FocusScrollEasing,
        ),
      )
    } else {
      scroll {
        scrollBy(scrollDelta.toFloat())
      }
    }
    return
  }

  if (smoothScroll) {
    animateScrollToItem(safeRow, scrollOffset = -focusedRowTopPaddingPx)
  } else {
    scrollToItem(safeRow, scrollOffset = -focusedRowTopPaddingPx)
  }
}

private fun Int.shouldLoadMore(totalItems: Int, threshold: Int): Boolean {
  return this >= totalItems - threshold
}
