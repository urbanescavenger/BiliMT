package com.kirin.mt.ui.home

import android.os.SystemClock
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

private const val TvGridRestoreFocusRetryCount = 8

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
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreTargetRow)
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
      return@LaunchedEffect
    }
    val targetIndex = restoredFocusIndex.coerceIn(0, videos.lastIndex)
    scrollRow(targetIndex / columns, smoothScroll = false)
    repeat(TvGridRestoreFocusRetryCount) {
      withFrameNanos { }
      val focused = runCatching {
        itemFocusRequesters[targetIndex].requestFocus()
      }.getOrDefault(false)
      if (focused) {
        onRestoreFocusHandled(restoreFocusRequestKey)
        return@LaunchedEffect
      }
    }
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

  LaunchedEffect(focusFirstItemKey, videos.size) {
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
