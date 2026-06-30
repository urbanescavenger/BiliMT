package com.kirin.mt.ui.pgc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.PgcIndexFilters
import com.kirin.mt.core.model.PgcIndexPage
import com.kirin.mt.core.model.PgcSummary
import com.kirin.mt.core.model.PgcType
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Stable
internal class PgcIndexUiState {
  var pgcType by mutableStateOf(PgcType.Anime)
  var order by mutableStateOf(IndexOrder.UpdateTime)
  var orderType by mutableStateOf(IndexOrderType.Desc)
  var seasonVersion by mutableStateOf(SeasonVersion.All)
  var spokenLanguage by mutableStateOf(SpokenLanguage.All)
  var area by mutableStateOf(Area.All)
  var isFinish by mutableStateOf(IsFinish.All)
  var copyright by mutableStateOf(Copyright.All)
  var seasonStatus by mutableStateOf(SeasonStatus.All)
  var seasonMonth by mutableStateOf(SeasonMonth.All)
  var producer by mutableStateOf(Producer.All)
  var year by mutableStateOf(Year.All)
  var releaseDate by mutableStateOf(ReleaseDate.All)
  var style by mutableStateOf(Style.All)

  val items = mutableStateListOf<PgcSummary>()
  var page by mutableStateOf(PgcIndexPage())
  var loading by mutableStateOf(false)
  var initialized by mutableStateOf(false)
  var failed by mutableStateOf(false)

  fun toFilters(): PgcIndexFilters = PgcIndexFilters(
    order = order.id,
    sort = orderType.id,
    seasonVersion = seasonVersion.id,
    spokenLanguage = spokenLanguage.id,
    area = area.id,
    isFinish = isFinish.id,
    copyright = copyright.id,
    seasonStatus = seasonStatus.id,
    seasonMonth = seasonMonth.id,
    producer = producer.id,
    year = year.str,
    releaseDate = releaseDate.str,
    style = style.id,
  )

  fun resetForType(type: PgcType) {
    pgcType = type
    order = IndexOrder.getList(type).firstOrNull() ?: IndexOrder.UpdateTime
    orderType = IndexOrderType.Desc
    seasonVersion = SeasonVersion.All
    spokenLanguage = SpokenLanguage.All
    area = Area.All
    isFinish = IsFinish.All
    copyright = Copyright.All
    seasonStatus = SeasonStatus.All
    seasonMonth = SeasonMonth.All
    producer = Producer.All
    year = Year.All
    releaseDate = ReleaseDate.All
    style = Style.getList(type).firstOrNull() ?: Style.All
    items.clear()
    page = PgcIndexPage()
    initialized = false
    loading = false
    failed = false
  }

  fun clearResults() {
    items.clear()
    page = PgcIndexPage()
    initialized = false
    failed = false
  }
}

@Composable
internal fun PgcIndexScreen(
  videoRepository: VideoRepository,
  pgcType: PgcType,
  firstItemFocusRequester: FocusRequester,
  onBack: () -> Boolean,
  onSeasonSelected: (PgcSummary) -> Unit,
) {
  val uiState = remember { PgcIndexUiState() }
  val gridState = rememberLazyGridState()
  var showFilter by remember { mutableStateOf(false) }
  var initialFocusDone by remember { mutableStateOf(false) }

  // 全屏 overlay:Back 滤镜开→关滤镜,滤镜关→关 index 页回带侧栏基页(对齐 UpSpaceScreen)。
  // PgcIndexFilterDialog 是内嵌 Box 非独立 window,故此处需带 showFilter 分支。
  BackHandler {
    if (showFilter) showFilter = false else onBack()
  }

  LaunchedEffect(pgcType) {
    uiState.resetForType(pgcType)
  }

  val filters = remember(
    uiState.pgcType, uiState.order, uiState.orderType, uiState.seasonVersion,
    uiState.spokenLanguage, uiState.area, uiState.isFinish, uiState.copyright,
    uiState.seasonStatus, uiState.seasonMonth, uiState.producer, uiState.year,
    uiState.releaseDate, uiState.style,
  ) { uiState.toFilters() }

  LaunchedEffect(filters) {
    uiState.clearResults()
    loadPgcIndex(videoRepository, uiState, filters)
  }

  LaunchedEffect(uiState) {
    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
      .filter { it != null }
      .distinctUntilChanged()
      .collect { lastIndex ->
        val size = uiState.items.size
        if (lastIndex != null && lastIndex >= size - 8 && uiState.page.hasNext && !uiState.loading) {
          loadPgcIndex(videoRepository, uiState, filters)
        }
      }
  }

  LaunchedEffect(uiState.items.isNotEmpty()) {
    if (uiState.items.isNotEmpty() && !initialFocusDone) {
      initialFocusDone = true
      runCatching { firstItemFocusRequester.requestFocus() }
    }
  }

  val homeColors = LocalHomeColors.current
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(top = BiliSpacing.Md),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = BiliSpacing.Xl, vertical = BiliSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      ) {
        Text(
          text = stringResource(R.string.pgc_index_entry) + " · " + stringResource(pgcType.titleRes()),
          color = homeColors.textPrimary,
          fontSize = BiliTypography.SectionTitle,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f),
        )
        BiliFocusableSurface(
          scaleOnFocus = false,
          shadowOnFocus = false,
          shape = RoundedCornerShape(BiliRadius.Pill),
          onClick = { showFilter = true },
          restingBorderColor = homeColors.glassBorder,
          focusedBorderColor = homeColors.accent,
          modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) onBack() else false
          },
        ) {
          Text(
            text = stringResource(R.string.pgc_index_filter),
            color = homeColors.textPrimary,
            fontSize = BiliTypography.BodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Xs),
          )
        }
      }

      when {
        uiState.items.isEmpty() && uiState.loading -> Text(
          text = stringResource(R.string.pgc_loading),
          color = homeColors.textSecondary,
          modifier = Modifier.fillMaxSize().padding(BiliSpacing.Xl),
        )
        uiState.items.isEmpty() && uiState.failed -> Text(
          text = stringResource(R.string.pgc_failed),
          color = homeColors.textSecondary,
          modifier = Modifier.fillMaxSize().padding(BiliSpacing.Xl),
        )
        uiState.items.isEmpty() -> Text(
          text = stringResource(R.string.pgc_empty),
          color = homeColors.textSecondary,
          modifier = Modifier.fillMaxSize().padding(BiliSpacing.Xl),
        )
        else -> LazyVerticalGrid(
          state = gridState,
          columns = GridCells.Fixed(BiliSizing.PgcGridColumns),
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(
            horizontal = BiliSpacing.Xl,
            vertical = BiliSpacing.Md,
          ),
          verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
          horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
        ) {
          itemsIndexed(uiState.items, key = { _, it -> it.seasonId }) { index, summary ->
            PgcCard(
              summary = summary,
              isFirst = index == 0,
              isFirstColumn = index % BiliSizing.PgcGridColumns == 0,
              isFirstRow = index < BiliSizing.PgcGridColumns,
              firstItemFocusRequester = firstItemFocusRequester,
              requestInitialFocus = false,
              onInitialFocusRequested = {},
              onMoveLeftToNav = onBack,
              onMoveUpToTab = { false },
              onClick = { onSeasonSelected(summary) },
            )
          }
        }
      }
    }

    if (showFilter) {
      PgcIndexFilterDialog(
        uiState = uiState,
        onDismiss = { showFilter = false },
      )
    }
  }
}

private suspend fun loadPgcIndex(
  videoRepository: VideoRepository,
  state: PgcIndexUiState,
  filters: PgcIndexFilters,
) {
  state.loading = true
  state.failed = false
  runCatching { videoRepository.getPgcIndex(state.pgcType, filters, state.page) }
    .onSuccess { result ->
      state.items.addAll(result.items)
      state.page = result.nextPage
      state.initialized = true
    }
    .onFailure { state.failed = true }
  state.loading = false
}

@Composable
private fun PgcIndexFilterDialog(
  uiState: PgcIndexUiState,
  onDismiss: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val type = uiState.pgcType
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(BiliSpacing.Xl),
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(BiliSpacing.Xl),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
    ) {
      Text(
        text = stringResource(R.string.pgc_index_filter) + " · " + stringResource(type.titleRes()),
        color = homeColors.textPrimary,
        fontSize = BiliTypography.SectionTitle,
        fontWeight = FontWeight.Bold,
      )
      LazyColumn(
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
        modifier = Modifier.fillMaxSize(),
      ) {
        item {
          PgcFilterChipRow(
            title = stringResource(R.string.pgc_filter_order),
            options = IndexOrder.getList(type),
            selected = uiState.order,
            onSelect = { uiState.order = it },
          )
        }
        item {
          PgcFilterChipRow(
            title = stringResource(R.string.pgc_filter_sort),
            options = IndexOrderType.entries,
            selected = uiState.orderType,
            onSelect = { uiState.orderType = it },
          )
        }
        val seasonVersions = SeasonVersion.getList(type)
        if (seasonVersions.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_season_version), seasonVersions, uiState.seasonVersion) { uiState.seasonVersion = it }
        }
        val spoken = SpokenLanguage.getList(type)
        if (spoken.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_spoken_language), spoken, uiState.spokenLanguage) { uiState.spokenLanguage = it }
        }
        val areas = Area.getList(type)
        if (areas.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_area), areas, uiState.area) { uiState.area = it }
        }
        val finishes = IsFinish.getList(type)
        if (finishes.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_is_finish), finishes, uiState.isFinish) { uiState.isFinish = it }
        }
        val copyrights = Copyright.getList(type)
        if (copyrights.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_copyright), copyrights, uiState.copyright) { uiState.copyright = it }
        }
        val statuses = SeasonStatus.getList(type)
        if (statuses.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_season_status), statuses, uiState.seasonStatus) { uiState.seasonStatus = it }
        }
        val months = SeasonMonth.getList(type)
        if (months.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_season_month), months, uiState.seasonMonth) { uiState.seasonMonth = it }
        }
        val producers = Producer.getList(type)
        if (producers.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_producer), producers, uiState.producer) { uiState.producer = it }
        }
        val years = Year.getList(type)
        if (years.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_year), years, uiState.year) { uiState.year = it }
        }
        val releases = ReleaseDate.getList(type)
        if (releases.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_release_date), releases, uiState.releaseDate) { uiState.releaseDate = it }
        }
        val styles = Style.getList(type)
        if (styles.isNotEmpty()) item {
          PgcFilterChipRow(stringResource(R.string.pgc_filter_style), styles, uiState.style) { uiState.style = it }
        }
      }
    }
    BiliFocusableSurface(
      scaleOnFocus = false,
      shadowOnFocus = false,
      shape = RoundedCornerShape(BiliRadius.Pill),
      onClick = onDismiss,
      restingBorderColor = homeColors.glassBorder,
      focusedBorderColor = homeColors.accent,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
            onDismiss(); true
          } else false
        },
    ) {
      Text(
        text = stringResource(R.string.pgc_index_filter_close),
        color = homeColors.textPrimary,
        fontSize = BiliTypography.BodySmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Xs),
      )
    }
  }
}

@Composable
private fun <T : PgcIndexOption> PgcFilterChipRow(
  title: String,
  options: List<T>,
  selected: T,
  onSelect: (T) -> Unit,
) {
  val homeColors = LocalHomeColors.current
  Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs)) {
    Text(
      text = title,
      color = homeColors.textSecondary,
      fontSize = BiliTypography.BodySmall,
      fontWeight = FontWeight.Bold,
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
      modifier = Modifier.fillMaxWidth(),
    ) {
      options.forEach { option ->
        val isSelected = option == selected
        BiliFocusableSurface(
          scaleOnFocus = false,
          shadowOnFocus = false,
          shape = RoundedCornerShape(BiliRadius.Pill),
          onClick = { onSelect(option) },
          restingBorderColor = if (isSelected) homeColors.accent else homeColors.glassBorder,
          focusedBorderColor = homeColors.accent,
        ) {
          Text(
            text = option.display,
            color = if (isSelected) homeColors.accent else homeColors.textSecondary,
            fontSize = BiliTypography.CardMeta,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Xs),
          )
        }
      }
    }
  }
}
