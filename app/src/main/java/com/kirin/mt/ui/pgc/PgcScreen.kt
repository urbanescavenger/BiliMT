package com.kirin.mt.ui.pgc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.PgcSummary
import com.kirin.mt.core.model.PgcType
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.common.BiliCapsuleTabRow
import com.kirin.mt.ui.common.BiliPillTab
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Stable
internal class PgcTabState {
  val items = mutableStateListOf<PgcSummary>()
  var cursor by mutableIntStateOf(0)
  var hasNext by mutableStateOf(true)
  var loading by mutableStateOf(false)
  var initialized by mutableStateOf(false)
  var failed by mutableStateOf(false)
}

@Stable
internal class PgcUiState {
  var selectedTab by mutableStateOf(PgcType.Anime)
  private val tabStates = mutableStateMapOf<PgcType, PgcTabState>()
  fun stateFor(type: PgcType): PgcTabState = tabStates.getOrPut(type) { PgcTabState() }
}

@Composable
internal fun PgcScreen(
  videoRepository: VideoRepository,
  uiState: PgcUiState,
  firstItemFocusRequester: FocusRequester,
  tabFocusRequester: FocusRequester,
  onMoveDownFromTab: () -> Boolean,
  onMoveLeftToNav: () -> Boolean,
  onSeasonSelected: (PgcSummary) -> Unit,
  onOpenIndex: (PgcType) -> Unit,
  requestInitialFocus: Boolean,
  onInitialFocusRequested: () -> Unit,
) {
  val selectedTab = uiState.selectedTab
  val tabState = uiState.stateFor(selectedTab)
  val gridState = rememberLazyGridState()
  var initialFocusHandled by remember { mutableStateOf(false) }

  LaunchedEffect(selectedTab) {
    if (!tabState.initialized && !tabState.loading) {
      loadPgcFeed(videoRepository, tabState, selectedTab, reset = true)
    }
  }

  // 首屏卡片就绪后聚焦第一项
  LaunchedEffect(tabState.items.isNotEmpty(), requestInitialFocus) {
    if (requestInitialFocus && tabState.items.isNotEmpty()) {
      runCatching { firstItemFocusRequester.requestFocus() }
      onInitialFocusRequested()
    }
  }

  // 焦点接近底部时加载下一页
  LaunchedEffect(tabState) {
    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
      .filter { it != null }
      .distinctUntilChanged()
      .collect { lastIndex ->
        if (lastIndex != null && lastIndex >= tabState.items.size - 6 && tabState.hasNext && !tabState.loading) {
          loadPgcFeed(videoRepository, tabState, selectedTab, reset = false)
        }
      }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    PgcTabRow(
      selectedTab = selectedTab,
      onSelect = {
        if (it != selectedTab) uiState.selectedTab = it
      },
      onOpenIndex = { onOpenIndex(selectedTab) },
      tabFocusRequester = tabFocusRequester,
      onMoveDownToGrid = onMoveDownFromTab,
      onMoveLeftToNav = onMoveLeftToNav,
    )
    PgcGrid(
      state = tabState,
      gridState = gridState,
      firstItemFocusRequester = firstItemFocusRequester,
      onMoveLeftToNav = onMoveLeftToNav,
      onMoveUpToTab = {
        runCatching { tabFocusRequester.requestFocus() }.isSuccess
      },
      onSeasonSelected = onSeasonSelected,
      requestInitialFocus = requestInitialFocus && !initialFocusHandled,
      onInitialFocusRequested = {
        initialFocusHandled = true
        onInitialFocusRequested()
      },
    )
  }
}

private suspend fun loadPgcFeed(
  videoRepository: VideoRepository,
  state: PgcTabState,
  type: PgcType,
  reset: Boolean,
) {
  state.loading = true
  state.failed = false
  if (reset) {
    state.items.clear()
    state.cursor = 0
    state.hasNext = true
  }
  runCatching { videoRepository.getPgcFeed(type, state.cursor) }
    .onSuccess { page ->
      state.items.addAll(page.items)
      state.cursor = page.nextCursor
      state.hasNext = page.hasNext
      state.initialized = true
    }
    .onFailure { state.failed = true }
  state.loading = false
}

@Composable
private fun PgcTabRow(
  selectedTab: PgcType,
  onSelect: (PgcType) -> Unit,
  onOpenIndex: () -> Unit,
  tabFocusRequester: FocusRequester,
  onMoveDownToGrid: () -> Boolean,
  onMoveLeftToNav: () -> Boolean,
) {
  BiliCapsuleTabRow(itemCount = PgcType.entries.size + 1) {
    PgcType.entries.forEach { type ->
      val selected = type == selectedTab
      BiliPillTab(
        text = stringResource(type.titleRes()),
        selected = selected,
        modifier = if (selected) Modifier.focusRequester(tabFocusRequester) else Modifier,
        onMoveUpToNav = onMoveLeftToNav,
        onMoveDownToGrid = onMoveDownToGrid,
        onClick = { onSelect(type) },
        onFocused = { onSelect(type) }, // 焦点即选中:对齐 UGC onSectionFocused / BV TopNav onFocus
      )
    }
    BiliPillTab(
      text = stringResource(R.string.pgc_index_entry),
      selected = false,
      onMoveUpToNav = onMoveLeftToNav,
      onMoveDownToGrid = onMoveDownToGrid,
      onClick = onOpenIndex,
    )
  }
}

@Composable
private fun PgcGrid(
  state: PgcTabState,
  gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
  firstItemFocusRequester: FocusRequester,
  onMoveLeftToNav: () -> Boolean,
  onMoveUpToTab: () -> Boolean,
  onSeasonSelected: (PgcSummary) -> Unit,
  requestInitialFocus: Boolean,
  onInitialFocusRequested: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val columns = BiliSizing.PgcGridColumns

  Box(modifier = Modifier.fillMaxSize()) {
    when {
      state.items.isEmpty() && state.loading -> Text(
        text = stringResource(R.string.pgc_loading),
        color = homeColors.textSecondary,
        modifier = Modifier
          .fillMaxSize()
          .padding(BiliSpacing.Xl),
      )
      state.items.isEmpty() && state.failed -> Text(
        text = stringResource(R.string.pgc_failed),
        color = homeColors.textSecondary,
        modifier = Modifier
          .fillMaxSize()
          .padding(BiliSpacing.Xl),
      )
      state.items.isEmpty() -> Text(
        text = stringResource(R.string.pgc_empty),
        color = homeColors.textSecondary,
        modifier = Modifier
          .fillMaxSize()
          .padding(BiliSpacing.Xl),
      )
      else -> LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
          horizontal = BiliSpacing.Xl,
          vertical = BiliSpacing.Md,
        ),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      ) {
        items(state.items, key = { it.seasonId }) { summary ->
          PgcCard(
            summary = summary,
            isFirst = state.items.firstOrNull()?.seasonId == summary.seasonId,
            firstItemFocusRequester = firstItemFocusRequester,
            requestInitialFocus = requestInitialFocus,
            onInitialFocusRequested = onInitialFocusRequested,
            onMoveLeftToNav = onMoveLeftToNav,
            onMoveUpToTab = onMoveUpToTab,
            onClick = { onSeasonSelected(summary) },
          )
        }
      }
    }
  }
}

@Composable
internal fun PgcCard(
  summary: PgcSummary,
  isFirst: Boolean,
  firstItemFocusRequester: FocusRequester,
  requestInitialFocus: Boolean,
  onInitialFocusRequested: () -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onMoveUpToTab: () -> Boolean,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val cardShape = RoundedCornerShape(BiliRadius.Card)
  val modifier = Modifier
    .fillMaxWidth()
    .let { base ->
      if (isFirst) base.focusRequester(firstItemFocusRequester) else base
    }
    .onPreviewKeyEvent { event ->
      if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
      when (event.key) {
        Key.DirectionLeft -> onMoveLeftToNav()
        Key.DirectionUp -> onMoveUpToTab()
        else -> false
      }
    }

  BiliFocusableSurface(
    scaleOnFocus = true,
    shadowOnFocus = true,
    shape = cardShape,
    onClick = onClick,
    onFocused = {
      if (isFirst && requestInitialFocus) {
        onInitialFocusRequested()
      }
    },
    modifier = modifier,
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(BiliSizing.PgcPosterAspect)
          .clip(cardShape),
      ) {
        AsyncImage(
          model = summary.cover,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
        if (summary.rating.isNotBlank()) {
          Text(
            text = summary.rating,
            color = Color.White,
            fontSize = BiliTypography.CardBadge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(BiliSpacing.Xs),
          )
        }
      }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(BiliSpacing.Sm),
      ) {
        Text(
          text = summary.title,
          color = homeColors.textPrimary,
          fontSize = BiliTypography.BodySmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (summary.subTitle.isNotBlank()) {
          Text(
            text = summary.subTitle,
            color = homeColors.textSecondary,
            fontSize = BiliTypography.CardMeta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = BiliSpacing.Xs),
          )
        }
      }
    }
  }
}

internal fun PgcType.titleRes(): Int = when (this) {
  PgcType.Anime -> R.string.pgc_type_anime
  PgcType.GuoChuang -> R.string.pgc_type_guochuang
  PgcType.Movie -> R.string.pgc_type_movie
  PgcType.Documentary -> R.string.pgc_type_documentary
  PgcType.Tv -> R.string.pgc_type_tv
  PgcType.Variety -> R.string.pgc_type_variety
}
