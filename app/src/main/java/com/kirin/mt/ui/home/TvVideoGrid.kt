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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import com.kirin.mt.ui.focus.focusDiag
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
import com.kirin.mt.core.model.SourceYoutube
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
// 帧数要大:长视频返回时网格以 initialFirstVisibleItemIndex=目标行 全新创建,受限设备上
// 首帧要测量到该行的全部前置行,慢布局可能远超 90 帧(此前 90 帧到期仍没等到目标行就放弃,
// destination 被清 → suppress 关 → 焦点留在头像)。列表本就定位在目标行,正常首帧即出现、
// 循环即刻退出;360 帧(60fps≈6s、30fps≈12s)只在慢布局兜底,不拖累正常路径。
private const val TvGridRestoreFocusWaitLayoutFrames = 360

/** P11-98c:焦点转移(scrollThenFocusItem)的按帧重试上限——滚动后目标行慢组合时单发必败。 */
private const val FocusItemRetryFrames = 30

internal const val TvFocusLogTag = "BiliMT:Focus"

// Keys that confirm a card selection; holding one for this long opens the card's long-press action menu.
private val VideoCardOwnerConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
private const val VideoCardOwnerLongPressMs = 500L

/**
 * P11-191:网格「此刻是否持焦」的持有对象。
 *
 * 用普通字段而不是快照状态:焦点每次进出网格都会写它,做成快照状态会让整个 4 列 LazyColumn
 * 跟着重组一遍。调用方只在**组合期**取值(见下方 `LaunchedEffect(videos)` 的 `gridHadFocus`),
 * 那一刻本帧的行销毁还没发生,拿到的正是「变更前网格有没有焦点」。
 */
private class GridFocusHolder {
  var value: Boolean = false
}

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

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
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
  // 焦点诊断区域标识(见 Modifier.focusDiag)。UP主页/频道页/动态等复用本网格的调用方
  // 可传自己的 label,默认 "video-grid";诊断时可借此区分焦点落在哪个内容网格。
  debugLabel: String = "video-grid",
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
  // 封面覆盖图 map:key 用 video.iptvUrls.firstOrNull()(IPTV 频道 URL 唯一)。
  // 非 IPTV 视频 iptvUrls 空 → key="" 不命中,不影响。IPTV 无 tvg-logo 时用拉流截帧缩略图。
  coverOverrides: Map<String, Any?>? = null,
  // 可见范围变化回调(视频 index 范围,含两端)。IPTV 懒加载截帧用:只截当前显示的频道。
  onVisibleRangeChange: ((Int, Int) -> Unit)? = null,
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
  // 可见范围变化回调(懒加载截帧用):监听网格可见行 index 范围,distinctUntilChanged 后
  // 回调视频 index 范围(含两端)。IPTV 分支据此只截当前显示的频道。
  LaunchedEffect(listState, columns) {
    snapshotFlow {
      val info = listState.layoutInfo
      val firstRow = info.visibleItemsInfo.firstOrNull()?.index ?: 0
      val lastRow = info.visibleItemsInfo.lastOrNull()?.index ?: 0
      firstRow to lastRow
    }
      .distinctUntilChanged()
      .collect { (firstRow, lastRow) ->
        onVisibleRangeChange?.invoke(firstRow * columns, (lastRow + 1) * columns - 1)
      }
  }
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
  // 当前聚焦卡片的稳定标识(keyFactory(index, video) 输出,动态流里即 bvid/videoId)。
  // 供「异步增量合并重排」时把焦点拉回同一视频的新 index:只监听显式聚焦写入,
  // 不随 videos 变化自更新,否则重排后就不知道原本聚焦的是谁了。
  var focusedKey by remember { mutableStateOf<Any?>(null) }
  // P11-191:聚焦时那一行的行 key。重排后若这一行的行 key 变了,说明整行被 LazyColumn 重建,
  // 焦点一定被清掉了(行 key 由「行首项」决定,详见 rowKeyOf)。
  var focusedRowKey by remember { mutableStateOf("") }
  val gridFocus = remember { GridFocusHolder() }
  // 组合期快照:本帧的重排/行销毁还没发生,拿到的就是「这次 videos 变更前网格是否持焦」。
  val gridHadFocus = gridFocus.value
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

  /**
   * P11-191:LazyColumn 的行 key。整行身份由「行首项的 key」决定 —— 行首换人 ⇒ 整行被销毁重建
   * ⇒ 行内被聚焦的卡片连节点一起没了,Compose 只能把焦点交给布局里第一个可聚焦节点(侧栏头像)。
   * 与 items(key = ...) 共用同一份实现,免得两处公式走偏。
   */
  fun rowKeyOf(row: Int): String {
    val firstIndex = row * columns
    return "row-$row-${keyFactory(firstIndex, videos[firstIndex])}"
  }

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

  // 列表被外层换新(合并 YouTube 关注流 / 刷新首屏)时保焦点。
  // 行 key 是 `row-$row-<行首项 key>`:重排后**即使被聚焦的那张卡 index 没变**,它所在行的
  // 行首项也可能换人 ⇒ LazyColumn 销毁重建整行 ⇒ 焦点被清,掉到布局里第一个可聚焦节点
  // (侧栏头像),再被头像 autoConfirm 送进「我的」页。
  // P11-191 真机实锤(logs_live_20261001_173435):17:34:24.740 合并落地 → 24.748 焦点已到头像,
  // 全程无按键;旧实现只判「聚焦项 index 变没变」,这一类(index 没变、行重建)整个漏网。
  // 判据用 gridHadFocus:变更前焦点已在别处(用户在侧栏/设置里)时绝不许抢回来。
  // 与 focusFirstItemKey / focusRestoredItemKey 不同,这里正是要在「列表内容被外部刷新」时
  // 主动把焦点拉回,而不是等显式 key 触发。
  LaunchedEffect(videos) {
    if (!gridHadFocus) return@LaunchedEffect
    val key = focusedKey ?: return@LaunchedEffect
    if (videos.isEmpty()) return@LaunchedEffect
    val oldIndex = focusedIndex
    if (oldIndex < 0) return@LaunchedEffect
    // 用户正在做纵向翻页(滚到别行)时让位,避免跟用户的移动抢焦点;
    // 空闲时(YouTube 增量合并落地)才把焦点拉回。
    if (rowScrollActive) return@LaunchedEffect
    val newIndex = videos.indices.firstOrNull { i -> keyFactory(i, videos[i]) == key }
    if (newIndex == null) {
      // YouTube 条目的 feedKey 内含 index,重排后按旧 key 找不到;退回旧 index 就近落回,
      // 总之不能让焦点留在网格外面。
      Log.d(TvFocusLogTag, "merge refocus: key=$key 已不在 ${videos.size} 条里,退回 index=$oldIndex")
    }
    val targetIndex = (newIndex ?: oldIndex).coerceIn(0, videos.lastIndex)
    val targetRow = targetIndex / columns
    // 行 key 没变、且网格手里还攥着焦点 ⇒ 这一行根本没被重建,焦点好端端在原卡上,不必动。
    // (append 加载更多就是这种:行首没换人,别每次翻页都白抢一遍、白刷日志。)
    // 两个判据并列而非只取一个:行 key 判据不依赖焦点事件派发时机,gridFocus 判据则能兜住
    // 「行 key 没变但焦点确实被别的路径清掉」的情况。
    if (rowKeyOf(targetRow) == focusedRowKey && gridFocus.value) return@LaunchedEffect
    // 只有真的换了行才需要滚(index 没变时行位置不变,重建后的节点一帧后即可聚焦)。
    if (newIndex != null && newIndex != oldIndex) {
      scrollRow(targetRow, smoothScroll = false)
    }
    repeat(TvGridRestoreFocusRetryCount) { attempt ->
      withFrameNanos { }
      val focused = runCatching {
        itemFocusRequesters[targetIndex].requestFocus()
      }.getOrDefault(false)
      if (focused) {
        if (newIndex != oldIndex) {
          Log.d(
            TvFocusLogTag,
            "merge refocus: $oldIndex → $targetIndex attempt=$attempt videos=${videos.size}",
          )
        }
        return@LaunchedEffect
      }
    }
    Log.w(
      TvFocusLogTag,
      "merge refocus failed: key=$key target=$targetIndex videos=${videos.size} (focus likely on avatar)",
    )
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
        // P11-98c:焦点转移加按帧重试——此前单帧等待+单发 requestFocus,慢组合(滚动后目标行
        // 未及布局)时 requester 未挂节点必失败,焦点留在原卡而按键已被吞,表现为「按 ↑ 没反应、
        // 连按多次才挪一格」(21:35:26.292 not-initialized + index=8 连按 4 次实锤)。
        // 正常路径首帧即中,循环立即退出不拖慢。
        suspend fun focusWithRetry() {
          var tries = 0
          while (tries < FocusItemRetryFrames && !focusItem(index)) {
            withFrameNanos { }
            tries++
          }
        }
        if (smoothScroll) {
          val scrollJob = launch {
            scrollRow(row, smoothScroll = true)
          }
          delay(BiliMotion.FocusScrollDelayMs)
          focusWithRetry()
          scrollJob.join()
          delay(BiliMotion.FocusScrollSettleMs)
        } else {
          scrollRow(row, smoothScroll = false)
          withFrameNanos { }
          focusWithRetry()
        }
      } finally {
        if (rowScrollGeneration == scrollGeneration) {
          rowScrollActive = false
        }
      }
    }
  }

  fun moveFocus(fromIndex: Int, direction: Key): Boolean {
    // P11-98b:按键级取证(此前该层零日志,焦点逃逸只能靠 LOST/GAINED 时间轴倒推)。
    Log.d(TvFocusLogTag, "grid-key label=$debugLabel index=$fromIndex dir=$direction")
    val currentRow = fromIndex / columns
    val currentColumn = fromIndex % columns
    val lastIndex = videos.lastIndex
    val lastRow = lastIndex / columns

    if (direction == Key.DirectionUp && currentRow == 0) {
      commitFocusedItem(fromIndex)
      // P11-98b:顶行 ↑ 的边界回调若未处理(目标 requester 未挂载,如 tab 栏未组合),吞掉
      // 按键、焦点留在原卡——绝不能把 false 漏给默认焦点遍历:遍历按几何找 grid 外的
      // focusable,正是逃逸路径(21:11:09 连按 ↑ 4 连发 FocusRequester not initialized +
      // 焦点逃到 sidebar/avatar 实锤)。回调成功时焦点已去 tab 栏,同样返回 true。
      onMoveUpFromFirstRow()
      return true
    }
    if (direction == Key.DirectionLeft && currentColumn == 0) {
      commitFocusedItem(fromIndex)
      // P11-98b:首列 ← 同理——nav requester 未挂时吞掉,不漏给默认遍历逃逸。
      onMoveLeftToNav()
      return true
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
        .focusDiag(debugLabel)
        // P11-191:记录网格是否持焦(普通字段,不触发重组),供「列表被换新」时判断该不该抢回焦点。
        .onFocusChanged { gridFocus.value = it.hasFocus }
        // focusRestorer 兜底:恢复目标卡(restoredItemFocusRequester,即进入覆盖层/播放器前
        // 最后聚焦的那张卡)在焦点树重建后由 Compose 自动恢复焦点,比下方手写 restore effect
        // 更快更稳。仅当目标卡已组合(在视口内)时生效——焦点曾在它上面且节点被移除过才会触发,
        // 不干扰首次进入/切 tab(此时该 requester 从未被聚焦,无恢复动作)。
        // 目标卡不在视口(滚动到深处返回)时 focusRestorer 无节点可恢复,仍由手写 effect
        // scroll+等布局+requestFocus 兜底。两者目标一致,不冲突。
        .focusRestorer(restoredItemFocusRequester)
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
        key = { row -> rowKeyOf(row) },
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
                coverOverride = coverOverrides?.get(video.iptvUrls.firstOrNull().orEmpty()),
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
                          // 长按打开卡片操作菜单(去 UP 主主页)。B 站视频以 ownerMid 判定;
                          // YouTube 视频无 B 站 mid(ownerMid=0),改以 channelId 判定,否则长按永远不触发。
                          val hasOwner = video.ownerMid > 0L ||
                            (video.source == SourceYoutube && video.channelId.isNotBlank())
                          if (held >= VideoCardOwnerLongPressMs && hasOwner) {
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
                  focusedKey = keyFactory(index, video)
                  focusedRowKey = rowKeyOf(row)
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
