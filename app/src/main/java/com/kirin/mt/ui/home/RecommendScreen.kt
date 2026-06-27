package com.kirin.mt.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import com.kirin.mt.R
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.model.UgcSubPartition
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.common.VideoGridSkeleton
import com.kirin.mt.ui.glass.biliLiquidGlassSurface
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliMotion
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Stable
internal class RecommendUiState {
  var selectedSectionKey by mutableStateOf("")
  var activeSectionKey by mutableStateOf("")
  var activeSubTidBySection by mutableStateOf<Map<String, Int?>>(emptyMap())
  var sectionStates by mutableStateOf<Map<String, RecommendState>>(emptyMap())
  var loadedSectionKeys by mutableStateOf(emptySet<String>())
  var sectionRefreshKeys by mutableStateOf<Map<String, Int>>(emptyMap())
  var loadRequest by mutableStateOf<RecommendLoadRequest?>(null)
  var nextLoadRequestId by mutableIntStateOf(0)
  var handledManualRefreshKey by mutableIntStateOf(0)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
}

@Composable
internal fun RecommendScreen(
  videoRepository: VideoRepository,
  uiState: RecommendUiState,
  firstItemFocusRequester: FocusRequester,
  enabledHomeSections: Set<HomeSection>,
  homeSectionsOrder: List<HomeSection>,
  autoConfirmOnFocus: Boolean,
  autoRefreshOnSwitch: Boolean,
  manualRefreshKey: Int,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  requestInitialFocus: Boolean,
  onInitialFocusRequested: () -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit,
) {
  val coroutineScope = rememberCoroutineScope()
  val sections = remember(enabledHomeSections, homeSectionsOrder) {
    homeSectionsOrder.filter { section -> section in enabledHomeSections }
      .ifEmpty { listOf(HomeSection.Recommend) }
  }
  val selectedSectionKey = uiState.selectedSectionKey.takeIf { key -> sections.any { section -> section.key == key } }
    ?: sections.first().key
  val activeSectionKey = uiState.activeSectionKey.takeIf { key -> sections.any { section -> section.key == key } }
    ?: selectedSectionKey
  val selectedSection = sections.firstOrNull { section -> section.key == selectedSectionKey } ?: sections.first()
  val activeSection = sections.firstOrNull { section -> section.key == activeSectionKey } ?: selectedSection
  val activeSubTid: Int? = uiState.activeSubTidBySection[activeSection.key]
  val selectedSectionFocusRequester = remember { FocusRequester() }
  val subPartitionFocusRequester = remember { FocusRequester() }
  val state = uiState.sectionStates[activeSection.key] ?: RecommendState.Loading
  val activeRefreshKey = uiState.sectionRefreshKeys[activeSection.key] ?: 0

  fun requestSectionLoad(sectionKey: String, refreshKey: Int, subTid: Int?) {
    uiState.nextLoadRequestId += 1
    uiState.loadRequest = RecommendLoadRequest(
      id = uiState.nextLoadRequestId,
      sectionKey = sectionKey,
      refreshKey = refreshKey,
      subTid = subTid,
    )
  }

  LaunchedEffect(sections) {
    val sectionKeys = sections.mapTo(mutableSetOf()) { section -> section.key }
    uiState.loadedSectionKeys = uiState.loadedSectionKeys.filterTo(mutableSetOf()) { key -> key in sectionKeys }
    uiState.sectionStates = uiState.sectionStates.filterKeys { key -> key in sectionKeys }
    uiState.sectionRefreshKeys = uiState.sectionRefreshKeys.filterKeys { key -> key in sectionKeys }
    if (sections.none { section -> section.key == uiState.selectedSectionKey }) {
      uiState.selectedSectionKey = sections.first().key
    }
    if (sections.none { section -> section.key == uiState.activeSectionKey }) {
      uiState.activeSectionKey = sections.first().key
      uiState.focusedVideoIndex = 0
      uiState.focusedVideoKey = ""
    }
    if (uiState.loadRequest != null && sections.none { section -> section.key == uiState.loadRequest?.sectionKey }) {
      uiState.loadRequest = null
    }

    val sectionKeyToLoad = uiState.activeSectionKey
      .takeIf { key -> sections.any { section -> section.key == key } }
      ?: sections.first().key
    if (
      uiState.loadRequest == null &&
      uiState.sectionStates[sectionKeyToLoad] == null
    ) {
      requestSectionLoad(
        sectionKey = sectionKeyToLoad,
        refreshKey = uiState.sectionRefreshKeys[sectionKeyToLoad] ?: 0,
        subTid = uiState.activeSubTidBySection[sectionKeyToLoad],
      )
    }
  }

  LaunchedEffect(videoRepository, uiState.loadRequest) {
    val request = uiState.loadRequest ?: return@LaunchedEffect
    val sectionToLoad = sections.firstOrNull { section -> section.key == request.sectionKey }
      ?: return@LaunchedEffect
    uiState.sectionStates = uiState.sectionStates + (sectionToLoad.key to RecommendState.Loading)
    uiState.focusedVideoIndex = 0
    uiState.focusedVideoKey = ""
    val nextState = try {
      val videos = videoRepository.getHomeSectionVideos(
        section = sectionToLoad,
        page = FirstPage,
        idx = if (sectionToLoad == HomeSection.Recommend) request.refreshKey else 0,
        regionTidOverride = request.subTid,
      )
      if (videos.isEmpty()) {
        RecommendState.Empty
      } else {
        RecommendState.Success(
          videos = videos,
          nextPage = FirstPage + 1,
          loadingMore = false,
          endReached = videos.size < PageSize,
          loadMoreError = "",
        )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      RecommendState.Failed(error.message.orEmpty())
    }
    uiState.loadedSectionKeys = uiState.loadedSectionKeys + sectionToLoad.key
    uiState.sectionStates = uiState.sectionStates + (sectionToLoad.key to nextState)
    if (uiState.loadRequest?.id == request.id) {
      uiState.loadRequest = null
    }
  }

  LaunchedEffect(manualRefreshKey) {
    if (manualRefreshKey <= 0 || manualRefreshKey == uiState.handledManualRefreshKey) {
      return@LaunchedEffect
    }
    uiState.handledManualRefreshKey = manualRefreshKey
    val nextRefreshKey = (uiState.sectionRefreshKeys[activeSection.key] ?: 0) + 1
    uiState.sectionRefreshKeys = uiState.sectionRefreshKeys + (activeSection.key to nextRefreshKey)
    requestSectionLoad(
      sectionKey = activeSection.key,
      refreshKey = nextRefreshKey,
      subTid = uiState.activeSubTidBySection[activeSection.key],
    )
  }

  fun loadNextPage() {
    val currentState = uiState.sectionStates[activeSection.key] as? RecommendState.Success ?: return
    if (currentState.loadingMore || currentState.endReached) {
      return
    }

    val pageToLoad = currentState.nextPage
    val sectionToLoad = activeSection
    val sectionKeyToLoad = activeSection.key
    val refreshKeyToLoad = activeRefreshKey
    uiState.sectionStates = uiState.sectionStates + (
      sectionKeyToLoad to currentState.copy(
        loadingMore = true,
        loadMoreError = "",
      )
    )

    coroutineScope.launch {
      val nextState = try {
        val nextVideos = videoRepository.getHomeSectionVideos(
          section = sectionToLoad,
          page = pageToLoad,
          idx = if (sectionToLoad == HomeSection.Recommend) {
            refreshKeyToLoad + pageToLoad - FirstPage
          } else {
            0
          },
          regionTidOverride = uiState.activeSubTidBySection[sectionKeyToLoad],
        )
        val latestState = uiState.sectionStates[sectionKeyToLoad] as? RecommendState.Success ?: return@launch
        val mergedVideos = latestState.videos.appendUniqueByBvid(nextVideos)
        latestState.copy(
          videos = mergedVideos,
          nextPage = pageToLoad + 1,
          loadingMore = false,
          endReached = nextVideos.size < PageSize ||
            mergedVideos.size == latestState.videos.size,
          loadMoreError = "",
        )
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        val latestState = uiState.sectionStates[sectionKeyToLoad] as? RecommendState.Success ?: return@launch
        latestState.copy(
          loadingMore = false,
          loadMoreError = error.message.orEmpty(),
        )
      }
      uiState.sectionStates = uiState.sectionStates + (sectionKeyToLoad to nextState)
    }
  }

  fun selectSection(section: HomeSection, forceRefresh: Boolean) {
    uiState.selectedSectionKey = section.key
    uiState.activeSectionKey = section.key
    uiState.focusedVideoIndex = 0
    uiState.focusedVideoKey = ""
    val hasLoadedSection = section.key in uiState.loadedSectionKeys
    if (forceRefresh || !hasLoadedSection) {
      val nextRefreshKey = if (forceRefresh) {
        (uiState.sectionRefreshKeys[section.key] ?: 0) + 1
      } else {
        uiState.sectionRefreshKeys[section.key] ?: 0
      }
      uiState.sectionRefreshKeys = uiState.sectionRefreshKeys + (section.key to nextRefreshKey)
      requestSectionLoad(
        sectionKey = section.key,
        refreshKey = nextRefreshKey,
        subTid = uiState.activeSubTidBySection[section.key],
      )
    }
  }

  fun selectSubPartition(subTid: Int?) {
    val section = activeSection
    // 子分区胶囊点击始终强制刷新（含重复点击当前子分区），与主分区 onSectionSelected→selectSection(forceRefresh=true) 对齐
    uiState.activeSubTidBySection = uiState.activeSubTidBySection + (section.key to subTid)
    uiState.focusedVideoIndex = 0
    uiState.focusedVideoKey = ""
    // 子分区切换 = 不同的 rid，需重新加载该分区槽位
    uiState.loadedSectionKeys = uiState.loadedSectionKeys - section.key
    uiState.sectionStates = uiState.sectionStates - section.key
    val nextRefreshKey = (uiState.sectionRefreshKeys[section.key] ?: 0) + 1
    uiState.sectionRefreshKeys = uiState.sectionRefreshKeys + (section.key to nextRefreshKey)
    requestSectionLoad(sectionKey = section.key, refreshKey = nextRefreshKey, subTid = subTid)
  }

  val activeSubPartitions = activeSection.subPartitions()

  Column(
    modifier = Modifier.fillMaxSize(),
  ) {
    RecommendHeader(
      sections = sections,
      selectedSection = selectedSection,
      selectedSectionFocusRequester = selectedSectionFocusRequester,
      onMoveLeftToNav = onMoveLeftToNav,
      onSectionSelected = { section ->
        selectSection(section = section, forceRefresh = true)
      },
      onSectionFocused = { section ->
        uiState.selectedSectionKey = section.key
        val isAlreadyActive = uiState.activeSectionKey == section.key
        val shouldLoad = autoRefreshOnSwitch || section.key !in uiState.loadedSectionKeys
        if (shouldLoad) {
          selectSection(section = section, forceRefresh = autoRefreshOnSwitch && section.key in uiState.loadedSectionKeys)
        } else if (!isAlreadyActive) {
          // 已加载但显示不在该分区：焦点落上就切显示（对齐 BV onSelectedChanged），不重载。
          uiState.activeSectionKey = section.key
          uiState.focusedVideoIndex = 0
          uiState.focusedVideoKey = ""
        }
      },
    )
    if (activeSubPartitions.isNotEmpty()) {
      UgcSubPartitionBar(
        subPartitions = activeSubPartitions,
        selectedSubTid = activeSubTid,
        selectedFocusRequester = subPartitionFocusRequester,
        onMoveUp = { runCatching { selectedSectionFocusRequester.requestFocus() }.isSuccess },
        onMoveDown = { runCatching { firstItemFocusRequester.requestFocus() }.isSuccess },
        onSelected = { subTid -> selectSubPartition(subTid) },
      )
    }
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = BiliSpacing.Xs),
    ) {
      when (val currentState = state) {
        RecommendState.Loading -> VideoGridSkeleton()
        RecommendState.Empty -> FeedStatusScreen(message = stringResource(R.string.recommend_empty))
        is RecommendState.Failed -> FeedStatusScreen(
          message = stringResource(R.string.recommend_failed_with_message, currentState.message),
          actionLabel = stringResource(R.string.action_retry),
          onAction = {
            selectSection(section = activeSection, forceRefresh = true)
          },
        )
        is RecommendState.Success -> {
          val restoredFocusIndex = currentState.videos.resolveFocusIndex(
            focusKey = uiState.focusedVideoKey,
            fallbackIndex = uiState.focusedVideoIndex,
          )
          RecommendGrid(
            videos = currentState.videos,
            firstItemFocusRequester = firstItemFocusRequester,
            restoredFocusIndex = restoredFocusIndex,
            restoreFocusRequestKey = restoreFocusRequestKey,
            onRestoreFocusHandled = onRestoreFocusHandled,
            requestInitialFocus = requestInitialFocus,
            onInitialFocusRequested = onInitialFocusRequested,
            onFocusedIndexChange = { index, video ->
              uiState.focusedVideoIndex = index
              uiState.focusedVideoKey = video.focusRestoreKey()
            },
            onLoadMore = ::loadNextPage,
            onMoveLeftToNav = onMoveLeftToNav,
            onMoveUpFromFirstRow = {
              if (activeSubPartitions.isNotEmpty()) {
                runCatching { subPartitionFocusRequester.requestFocus() }.isSuccess
              } else {
                runCatching { selectedSectionFocusRequester.requestFocus() }.isSuccess
              }
            },
            onVideoSelected = onVideoSelected,
            onOwnerSelected = onOwnerSelected,
          )
        }
      }
    }
  }
}

@Composable
private fun RecommendHeader(
  sections: List<HomeSection>,
  selectedSection: HomeSection,
  selectedSectionFocusRequester: FocusRequester,
  onMoveLeftToNav: () -> Boolean,
  onSectionSelected: (HomeSection) -> Unit,
  onSectionFocused: (HomeSection) -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val capsuleShape = RoundedCornerShape(BiliRadius.Pill)
  val liquidGlassEnabled = performancePolicy.cinematicVisualEffectsEnabled && performancePolicy.liquidGlassCardsEnabled
  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.HomeSectionCapsuleHeight),
    contentAlignment = Alignment.Center,
  ) {
    val capsuleMaxWidth = maxWidth
    val capsuleMinWidth = capsuleMaxWidth * homeSectionCapsuleMinWidthFraction(sections.size)
    val capsuleArrangement = if (sections.size <= HomeSectionCapsuleSpreadMaxCount) {
      Arrangement.SpaceEvenly
    } else {
      Arrangement.spacedBy(BiliSizing.HomeSectionCapsuleItemSpacing)
    }
    Row(
      modifier = Modifier
        .align(Alignment.Center)
        .offset(y = -BiliSizing.HomeSectionCapsuleTopOffset)
        .widthIn(min = capsuleMinWidth, max = capsuleMaxWidth)
        .clip(capsuleShape)
        .biliLiquidGlassSurface(
          enabled = liquidGlassEnabled,
          shape = capsuleShape,
          surfaceColor = homeColors.glassSurface.copy(alpha = BiliFocus.HomeSectionCapsuleSurfaceAlpha),
          borderColor = homeColors.textPrimary.copy(alpha = BiliFocus.HomeSectionCapsuleBorderAlpha),
          borderWidth = BiliFocus.RestingBorderWidth,
        )
        .padding(
          horizontal = BiliSizing.HomeSectionCapsuleHorizontalPadding,
          vertical = BiliSizing.HomeSectionCapsuleVerticalPadding,
        ),
      horizontalArrangement = capsuleArrangement,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      sections.forEachIndexed { index, section ->
        HomeSectionTab(
          section = section,
          selected = section == selectedSection,
          modifier = if (section == selectedSection) {
            Modifier.focusRequester(selectedSectionFocusRequester)
          } else {
            Modifier
          },
          onMoveLeftToNav = if (index == 0) onMoveLeftToNav else null,
          onClick = {
            onSectionSelected(section)
          },
          onFocused = { onSectionFocused(section) },
        )
      }
    }
  }
}

@Composable
private fun HomeSectionTab(
  section: HomeSection,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onMoveLeftToNav: (() -> Boolean)?,
  onClick: () -> Unit,
  onFocused: () -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  val performancePolicy = LocalBiliPerformancePolicy.current
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Pill)
  val targetBorderColor = if (focused) homeColors.accent else BiliColors.Transparent
  val targetTextColor = when {
    selected -> homeColors.accent
    focused -> homeColors.textPrimary
    else -> homeColors.textSecondary
  }
  val borderWidth = if (performancePolicy.motionEnabled) {
    animateDpAsState(
      targetValue = if (focused) BiliFocus.BorderWidth else BiliFocus.RestingBorderWidth,
      animationSpec = androidx.compose.animation.core.tween(BiliMotion.FocusMs, easing = BiliMotion.FocusEasing),
      label = "homeSectionBorderWidth",
    ).value
  } else {
    if (focused) BiliFocus.BorderWidth else BiliFocus.RestingBorderWidth
  }
  val borderColor = if (performancePolicy.motionEnabled) {
    animateColorAsState(
      targetValue = targetBorderColor,
      animationSpec = androidx.compose.animation.core.tween(BiliMotion.FocusMs, easing = BiliMotion.FocusEasing),
      label = "homeSectionBorder",
    ).value
  } else {
    targetBorderColor
  }
  val textColor = if (performancePolicy.motionEnabled) {
    animateColorAsState(
      targetValue = targetTextColor,
      animationSpec = androidx.compose.animation.core.tween(BiliMotion.FocusMs, easing = BiliMotion.FocusEasing),
      label = "homeSectionText",
    ).value
  } else {
    targetTextColor
  }
  val interactionSource = remember { MutableInteractionSource() }

  Box(
    modifier = modifier
      .height(BiliSizing.HomeSectionTabHeight)
      .widthIn(min = BiliSizing.HomeSectionTabCompactMinWidth)
      .clip(shape)
      .background(
        if (focused) {
          homeColors.textPrimary.copy(alpha = BiliFocus.HomeSectionTabFocusedSurfaceAlpha)
        } else {
          BiliColors.Transparent
        },
      )
      .border(BorderStroke(borderWidth, borderColor), shape)
      .onFocusChanged { focusState ->
        focused = focusState.isFocused
        if (focusState.isFocused && !selected) {
          // 焦点落主分区就通知切显示（对齐 BV TopNav onSelectedChanged），不卡 autoConfirmOnFocus。
          onFocused()
        }
      }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown &&
            event.key == Key.DirectionLeft &&
            onMoveLeftToNav != null -> onMoveLeftToNav()
          event.type == KeyEventType.KeyUp && event.key.isConfirmKey() -> {
            onClick()
            true
          }
          else -> false
        }
      }
      .focusable(interactionSource = interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
      )
      .padding(horizontal = BiliSpacing.Sm),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = stringResource(section.titleRes()),
      color = textColor,
      fontSize = BiliTypography.HomeSectionTab,
      lineHeight = BiliTypography.HomeSectionTabLineHeight,
      fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
      textAlign = TextAlign.Center,
      maxLines = 1,
      style = TextStyle(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    )
  }
}

@Composable
private fun UgcSubPartitionBar(
  subPartitions: List<UgcSubPartition>,
  selectedSubTid: Int?,
  selectedFocusRequester: FocusRequester,
  onMoveUp: () -> Boolean,
  onMoveDown: () -> Boolean,
  onSelected: (Int?) -> Unit,
) {
  LazyRow(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.UgcSubPartitionBarHeight)
      .padding(horizontal = BiliSpacing.Lg),
    horizontalArrangement = Arrangement.spacedBy(BiliSizing.UgcSubPartitionItemSpacing),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    item {
      UgcSubPartitionChip(
        text = stringResource(R.string.ugc_sub_all),
        selected = selectedSubTid == null,
        focusRequester = if (selectedSubTid == null) selectedFocusRequester else null,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        onClick = { onSelected(null) },
      )
    }
    items(subPartitions) { sub ->
      UgcSubPartitionChip(
        text = stringResource(sub.titleRes),
        selected = selectedSubTid == sub.tid,
        focusRequester = if (selectedSubTid == sub.tid) selectedFocusRequester else null,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        onClick = { onSelected(sub.tid) },
      )
    }
  }
}

@Composable
private fun UgcSubPartitionChip(
  text: String,
  selected: Boolean,
  focusRequester: FocusRequester?,
  onMoveUp: () -> Boolean,
  onMoveDown: () -> Boolean,
  onClick: () -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  val performancePolicy = LocalBiliPerformancePolicy.current
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Pill)
  val targetBorderColor = if (focused) homeColors.accent else BiliColors.Transparent
  val targetTextColor = when {
    selected -> homeColors.accent
    focused -> homeColors.textPrimary
    else -> homeColors.textSecondary
  }
  val borderColor = if (performancePolicy.motionEnabled) {
    animateColorAsState(
      targetValue = targetBorderColor,
      animationSpec = androidx.compose.animation.core.tween(BiliMotion.FocusMs, easing = BiliMotion.FocusEasing),
      label = "ugcSubBorder",
    ).value
  } else {
    targetBorderColor
  }
  val textColor = if (performancePolicy.motionEnabled) {
    animateColorAsState(
      targetValue = targetTextColor,
      animationSpec = androidx.compose.animation.core.tween(BiliMotion.FocusMs, easing = BiliMotion.FocusEasing),
      label = "ugcSubText",
    ).value
  } else {
    targetTextColor
  }
  val interactionSource = remember { MutableInteractionSource() }

  Box(
    modifier = Modifier
      .height(BiliSizing.UgcSubPartitionTabHeight)
      .widthIn(min = BiliSizing.UgcSubPartitionTabMinWidth)
      .clip(shape)
      .background(
        if (focused) {
          homeColors.textPrimary.copy(alpha = BiliFocus.HomeSectionTabFocusedSurfaceAlpha)
        } else {
          BiliColors.Transparent
        },
      )
      .border(BorderStroke(BiliFocus.RestingBorderWidth, borderColor), shape)
      .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
      .onFocusChanged { focusState -> focused = focusState.isFocused }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> onMoveUp()
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> onMoveDown()
          event.type == KeyEventType.KeyUp && event.key.isConfirmKey() -> {
            onClick()
            true
          }
          else -> false
        }
      }
      .focusable(interactionSource = interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
      )
      .padding(horizontal = BiliSpacing.Md),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = textColor,
      fontSize = BiliTypography.UgcSubPartitionTab,
      lineHeight = BiliTypography.UgcSubPartitionTabLineHeight,
      fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
      textAlign = TextAlign.Center,
      maxLines = 1,
      style = TextStyle(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
      ),
    )
  }
}

@Composable
private fun RecommendGrid(
  videos: List<VideoSummary>,
  firstItemFocusRequester: FocusRequester,
  restoredFocusIndex: Int,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  requestInitialFocus: Boolean,
  onInitialFocusRequested: () -> Unit,
  onFocusedIndexChange: (Int, VideoSummary) -> Unit,
  onLoadMore: () -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onMoveUpFromFirstRow: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit,
) {
  TvVideoGrid(
    videos = videos,
    firstItemFocusRequester = firstItemFocusRequester,
    restoredFocusIndex = restoredFocusIndex,
    restoreFocusRequestKey = restoreFocusRequestKey,
    onRestoreFocusHandled = onRestoreFocusHandled,
    requestInitialFocus = requestInitialFocus,
    onInitialFocusRequested = onInitialFocusRequested,
    onFocusedIndexChange = onFocusedIndexChange,
    onLoadMore = onLoadMore,
    onMoveLeftToNav = onMoveLeftToNav,
    onMoveUpFromFirstRow = onMoveUpFromFirstRow,
    topPadding = BiliSizing.HomeVideoGridTopPadding + BiliSizing.HomeVideoGridTopBleed,
    topBleed = BiliSizing.HomeVideoGridTopBleed,
    onVideoSelected = onVideoSelected,
    onOwnerSelected = onOwnerSelected,
  )
}

private const val HomeSectionCapsuleSpreadMaxCount = 6

private fun homeSectionCapsuleMinWidthFraction(sectionCount: Int): Float = when (sectionCount) {
  0, 1 -> 0.24f
  2 -> 0.34f
  3 -> 0.44f
  4 -> 0.54f
  5 -> 0.60f
  6 -> 0.66f
  else -> 0f
}

private suspend fun LazyGridState.scrollItemIntoStablePosition(
  index: Int,
  totalItems: Int,
  fallbackItemHeightPx: Int,
  scrollInsetPx: Int,
  focusedRowTopPaddingPx: Int,
  focusScale: Float,
  smoothScroll: Boolean,
) {
  val layout = layoutInfo
  val columns = layout.estimatedColumnCount()
  val row = index / columns
  val lastRow = (totalItems - 1) / columns
  val rowStartIndex = row * columns
  val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
  val itemHeightPx = layout.visibleItemsInfo.firstOrNull { item -> item.index == index }?.size?.height
    ?: layout.visibleItemsInfo.firstOrNull()?.size?.height
    ?: fallbackItemHeightPx
  val focusOverflowPx = ((itemHeightPx * (focusScale - 1f)) / 2f).roundToInt()
  val edgeInsetPx = scrollInsetPx + focusOverflowPx
  val focusedItem = layout.visibleItemsInfo.firstOrNull { item -> item.index == index }
  if (focusedItem != null) {
    val itemTop = focusedItem.offset.y
    val viewportTop = layout.viewportStartOffset
    val viewportBottom = layout.viewportEndOffset - edgeInsetPx
    val targetTop = (layout.viewportStartOffset + focusedRowTopPaddingPx.coerceAtLeast(edgeInsetPx))
      .coerceAtMost(viewportBottom - focusedItem.size.height)
      .coerceAtLeast(viewportTop + edgeInsetPx)
    val scrollDelta = itemTop - targetTop
    if (kotlin.math.abs(scrollDelta) <= BiliMotion.FocusScrollMinDeltaPx) {
      return
    }
    if (smoothScroll) {
      animateScrollBy(scrollDelta.toFloat())
    } else {
      scroll {
        scrollBy(scrollDelta.toFloat())
      }
    }
    return
  }
  val maxTop = (viewportHeight - itemHeightPx - edgeInsetPx).coerceAtLeast(edgeInsetPx)
  val desiredTop = when (row) {
    0 -> edgeInsetPx
    lastRow -> maxTop
    else -> {
      ((viewportHeight - itemHeightPx) / 2).coerceIn(edgeInsetPx, maxTop)
    }
  }

  if (smoothScroll) {
    animateScrollToItem(
      index = rowStartIndex,
      scrollOffset = -focusedRowTopPaddingPx,
    )
  } else {
    scrollToItem(
      index = rowStartIndex,
      scrollOffset = -focusedRowTopPaddingPx,
    )
  }
}

private fun LazyGridState.targetIndexForDirection(
  fromIndex: Int,
  totalItems: Int,
  direction: Key,
): Int? {
  val columns = layoutInfo.estimatedColumnCount()
  val currentRow = fromIndex / columns
  val currentColumn = fromIndex % columns
  val lastIndex = totalItems - 1
  val lastRow = lastIndex / columns

  return when (direction) {
    Key.DirectionUp -> {
      if (currentRow == 0) {
        null
      } else {
        ((currentRow - 1) * columns + currentColumn).coerceAtMost(lastIndex)
      }
    }
    Key.DirectionDown -> {
      if (currentRow >= lastRow) {
        null
      } else {
        ((currentRow + 1) * columns + currentColumn).coerceAtMost(lastIndex)
      }
    }
    Key.DirectionLeft -> {
      if (currentColumn == 0) null else fromIndex - 1
    }
    Key.DirectionRight -> {
      val nextIndex = fromIndex + 1
      if (nextIndex > lastIndex || nextIndex / columns != currentRow) null else nextIndex
    }
    else -> null
  }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo.estimatedColumnCount(): Int {
  return visibleItemsInfo
    .map(LazyGridItemInfo::columnAnchor)
    .distinct()
    .count()
    .coerceAtLeast(1)
}

private val LazyGridItemInfo.columnAnchor: Int
  get() = offset.x

private fun Key.isConfirmKey(): Boolean {
  return this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
}

private fun List<VideoSummary>.appendUniqueByBvid(nextVideos: List<VideoSummary>): List<VideoSummary> {
  if (nextVideos.isEmpty()) {
    return this
  }
  val knownBvids = mapTo(mutableSetOf()) { video -> video.bvid }
  return this + nextVideos.filter { video -> knownBvids.add(video.bvid) }
}

private fun List<VideoSummary>.resolveFocusIndex(focusKey: String, fallbackIndex: Int): Int {
  val keyIndex = focusKey
    .takeIf { key -> key.isNotBlank() }
    ?.let { key -> indexOfFirst { video -> video.focusRestoreKey() == key } }
    ?.takeIf { index -> index >= 0 }
  return keyIndex ?: fallbackIndex.coerceIn(0, lastIndex)
}

private fun VideoSummary.focusRestoreKey(): String {
  return bvid.ifBlank {
    when {
      cid > 0L -> "cid-$cid"
      historyPage > 0 -> "p-$historyPage"
      else -> ""
    }
  }
}

private fun Int.shouldLoadMore(totalItems: Int, threshold: Int): Boolean {
  return totalItems - this <= threshold
}

internal data class RecommendLoadRequest(
  val id: Int,
  val sectionKey: String,
  val refreshKey: Int,
  val subTid: Int?,
)

@Composable
private fun InitialHomeCardFocusEffect(
  firstItemFocusRequester: FocusRequester,
  onInitialFocusRequested: () -> Unit,
) {
  LaunchedEffect(firstItemFocusRequester) {
    repeat(InitialFocusRetryCount) {
      withFrameNanos { }
      val focused = runCatching {
        firstItemFocusRequester.requestFocus()
      }.getOrDefault(false)
      if (focused) {
        onInitialFocusRequested()
        return@LaunchedEffect
      }
      delay(InitialFocusRetryDelayMs)
    }
  }
}

private const val InitialFocusRetryCount = 20
private const val InitialFocusRetryDelayMs = 50L
private const val RestoreFocusRetryCount = 8
private const val FirstPage = 1
private const val PageSize = 20

internal sealed interface RecommendState {
  data object Loading : RecommendState
  data object Empty : RecommendState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: String,
  ) : RecommendState
  data class Failed(val message: String) : RecommendState
}
