package com.kirin.mt.ui.search

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.storage.SearchHistoryStore
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.common.VideoGridSkeleton
import com.kirin.mt.ui.common.appendUniqueByBvid
import com.kirin.mt.ui.common.focusRestoreKey
import com.kirin.mt.ui.common.resolveFocusIndex
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.home.TvVideoGrid
import com.kirin.mt.ui.home.VideoCard
import com.kirin.mt.ui.i18n.convertChineseText
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
internal class SearchUiState {
  var searchText by mutableStateOf("")
  var activeQuery by mutableStateOf<String?>(null)
  var selectedOrderKey by mutableStateOf(SearchSortOptions.first().key)
  var focusFirstResult by mutableStateOf(true)
  var focusedResultIndex by mutableIntStateOf(0)
  var focusedResultKey by mutableStateOf("")
  var retryKey by mutableIntStateOf(0)
  var resultState by mutableStateOf<SearchResultState>(SearchResultState.Loading)
  var loadedQuery by mutableStateOf("")
  var loadedOrderKey by mutableStateOf("")
  var loadedRetryKey by mutableIntStateOf(-1)

  fun startSearch(query: String) {
    searchText = query
    if (activeQuery != query) {
      resetResultsForQuery(query)
    }
    activeQuery = query
  }

  fun backToKeyboard() {
    activeQuery = null
  }

  fun clear() {
    searchText = ""
    activeQuery = null
    resetResultsForQuery("")
  }

  fun selectOrder(orderKey: String) {
    if (selectedOrderKey == orderKey) {
      return
    }
    selectedOrderKey = orderKey
    focusFirstResult = false
    focusedResultIndex = 0
    focusedResultKey = ""
    retryKey = 0
    resultState = SearchResultState.Loading
    loadedQuery = ""
    loadedOrderKey = ""
    loadedRetryKey = -1
  }

  private fun resetResultsForQuery(query: String) {
    selectedOrderKey = SearchSortOptions.first().key
    focusFirstResult = true
    focusedResultIndex = 0
    focusedResultKey = ""
    retryKey = 0
    resultState = SearchResultState.Loading
    loadedQuery = query.takeIf { it.isBlank() }.orEmpty()
    loadedOrderKey = ""
    loadedRetryKey = -1
  }
}

@Composable
internal fun SearchScreen(
  videoRepository: VideoRepository,
  searchHistoryStore: SearchHistoryStore,
  uiState: SearchUiState,
  firstItemFocusRequester: FocusRequester,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit = {},
) {
  val coroutineScope = rememberCoroutineScope()
  val searchHistory by searchHistoryStore.history.collectAsState(initial = emptyList())
  var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
  var returnFocusToKeyboard by remember { mutableStateOf(false) }
  val screenFocusRequester = remember { FocusRequester() }

  LaunchedEffect(uiState.searchText) {
    if (uiState.searchText.isBlank()) {
      suggestions = emptyList()
      return@LaunchedEffect
    }

    delay(SearchSuggestionDebounceMs)
    suggestions = runCatching {
      videoRepository.getSearchSuggestions(uiState.searchText.trim())
    }.getOrElse {
      emptyList()
    }
  }

  LaunchedEffect(uiState.activeQuery, returnFocusToKeyboard) {
    if (uiState.activeQuery == null && returnFocusToKeyboard) {
      withFrameNanos { }
      runCatching {
        firstItemFocusRequester.requestFocus()
      }
      returnFocusToKeyboard = false
    }
  }

  val query = uiState.activeQuery
  Box(
    modifier = Modifier
      .fillMaxSize()
      .focusRequester(screenFocusRequester)
      .focusable(),
  ) {
    if (query == null) {
      SearchKeyboardView(
        searchText = uiState.searchText,
        suggestions = suggestions,
        searchHistory = searchHistory,
        keyboardFocusRequester = firstItemFocusRequester,
        onMoveLeftToNav = onMoveLeftToNav,
        onTextChange = { nextText ->
          uiState.searchText = nextText
        },
        onClearSearchHistory = {
          runCatching {
            firstItemFocusRequester.requestFocus()
          }
          coroutineScope.launch {
            searchHistoryStore.clear()
          }
        },
        onSearch = { text ->
          val trimmed = text.trim()
          if (trimmed.isNotEmpty()) {
            runCatching {
              screenFocusRequester.requestFocus()
            }
            coroutineScope.launch {
              searchHistoryStore.add(trimmed)
            }
            uiState.startSearch(trimmed)
          }
        },
      )
    } else {
      SearchResultsView(
        query = query,
        videoRepository = videoRepository,
        uiState = uiState,
        firstResultFocusRequester = firstItemFocusRequester,
        restoreFocusRequestKey = restoreFocusRequestKey,
        onRestoreFocusHandled = onRestoreFocusHandled,
        onMoveLeftToNav = onMoveLeftToNav,
        onBackToKeyboard = {
          uiState.backToKeyboard()
          returnFocusToKeyboard = true
        },
        onVideoSelected = onVideoSelected,
      )
    }
  }
}

@Composable
private fun SearchKeyboardView(
  searchText: String,
  suggestions: List<String>,
  searchHistory: List<String>,
  keyboardFocusRequester: FocusRequester,
  onMoveLeftToNav: () -> Boolean,
  onTextChange: (String) -> Unit,
  onClearSearchHistory: () -> Unit,
  onSearch: (String) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(BiliSizing.ContentPadding),
  ) {
    Row(
      modifier = Modifier.fillMaxSize(),
      verticalAlignment = Alignment.Top,
    ) {
      Column(
        modifier = Modifier
          .width(BiliSizing.SearchKeyboardPanelWidth)
          .fillMaxHeight(),
        verticalArrangement = Arrangement.Top,
      ) {
        SearchInputText(searchText = searchText)
        Spacer(modifier = Modifier.height(BiliSpacing.Md))
        Row(
          horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
          modifier = Modifier
            .fillMaxWidth()
            .height(BiliSizing.SearchKeyboardButtonHeight),
        ) {
          SearchKeyboardButton(
            label = stringResource(R.string.search_action_clear),
            modifier = Modifier
              .weight(1f)
              .focusRequester(keyboardFocusRequester),
            onMoveLeft = onMoveLeftToNav,
            onClick = {
              onTextChange("")
            },
          )
          SearchKeyboardButton(
            label = stringResource(R.string.search_action_backspace),
            modifier = Modifier.weight(1f),
            onClick = {
              if (searchText.isNotEmpty()) {
                onTextChange(searchText.dropLast(1))
              }
            },
          )
        }
        Spacer(modifier = Modifier.height(BiliSpacing.Md))
        SearchKeyGrid(
          onKeyClick = { key ->
            onTextChange(searchText + key)
          },
          onMoveLeftToNav = onMoveLeftToNav,
        )
        Spacer(modifier = Modifier.height(BiliSpacing.Lg))
        SearchKeyboardButton(
          label = stringResource(R.string.search_action_search),
          action = true,
          modifier = Modifier
            .fillMaxWidth()
            .height(BiliSizing.SearchKeyboardButtonHeight),
          onMoveLeft = onMoveLeftToNav,
          onClick = {
            onSearch(searchText)
          },
        )
      }
      SearchSuggestionPanel(
        searchText = searchText,
        suggestions = suggestions,
        searchHistory = searchHistory,
        onSuggestionSelected = { suggestion ->
          onSearch(suggestion)
        },
        onClearSearchHistory = onClearSearchHistory,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun SearchInputText(searchText: String) {
  val homeColors = LocalHomeColors.current
  val placeholder = stringResource(R.string.search_input_placeholder)
  val displayText = if (searchText.isBlank()) placeholder else convertChineseText(searchText)

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.SearchInputHeight)
      .background(homeColors.glassSurfaceStrong, RoundedCornerShape(BiliRadius.Card))
      .padding(horizontal = BiliSpacing.Lg),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(
      text = displayText,
      color = if (searchText.isBlank()) homeColors.textTertiary else homeColors.textPrimary,
      fontSize = BiliTypography.SearchInput,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun SearchKeyGrid(
  onKeyClick: (String) -> Unit,
  onMoveLeftToNav: () -> Boolean,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
    modifier = Modifier.fillMaxWidth(),
  ) {
    SearchKeyboardRows.forEach { row ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
        modifier = Modifier.fillMaxWidth(),
      ) {
        row.forEachIndexed { columnIndex, key ->
          SearchKeyboardButton(
            label = key,
            modifier = Modifier
              .weight(1f)
              .height(BiliSizing.SearchKeyboardButtonHeight),
            onMoveLeft = if (columnIndex == 0) onMoveLeftToNav else null,
            onClick = {
              onKeyClick(key)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun SearchKeyboardButton(
  label: String,
  modifier: Modifier = Modifier,
  action: Boolean = false,
  onMoveLeft: (() -> Boolean)? = null,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Card),
    onClick = onClick,
    modifier = modifier
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && onMoveLeft != null) {
          onMoveLeft()
        } else {
          false
        }
      },
  ) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        color = if (action) homeColors.accent else homeColors.textSecondary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun SearchSuggestionPanel(
  searchText: String,
  suggestions: List<String>,
  searchHistory: List<String>,
  onSuggestionSelected: (String) -> Unit,
  onClearSearchHistory: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  Box(
    modifier = modifier
      .fillMaxHeight()
      .padding(start = BiliSpacing.Xl),
  ) {
    if (searchText.isBlank()) {
      if (searchHistory.isEmpty()) {
        SearchHintText(text = stringResource(R.string.search_empty_prompt))
      } else {
        SearchHistoryList(
          history = searchHistory,
          onHistorySelected = onSuggestionSelected,
          onClearSearchHistory = onClearSearchHistory,
        )
      }
    } else if (suggestions.isEmpty()) {
      SearchHintText(text = stringResource(R.string.search_no_suggestions))
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = BiliSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
      ) {
        item {
          Text(
            text = stringResource(R.string.search_suggestions_title),
            color = homeColors.textSecondary,
            fontSize = BiliTypography.SectionTitle,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = BiliSpacing.Sm),
          )
        }
        items(suggestions, key = { suggestion -> suggestion }) { suggestion ->
          SearchSuggestionItem(
            text = suggestion,
            displayText = convertChineseText(suggestion),
            onClick = {
              onSuggestionSelected(suggestion)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun SearchHistoryList(
  history: List<String>,
  onHistorySelected: (String) -> Unit,
  onClearSearchHistory: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(vertical = BiliSpacing.Md),
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
  ) {
    item {
      Text(
        text = stringResource(R.string.search_history_title),
        color = homeColors.textSecondary,
        fontSize = BiliTypography.SectionTitle,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = BiliSpacing.Sm),
      )
    }
    items(history, key = { item -> item }) { item ->
      SearchSuggestionItem(
        text = item,
        displayText = convertChineseText(item),
        onClick = {
          onHistorySelected(item)
        },
      )
    }
    item {
      SearchSuggestionItem(
        text = stringResource(R.string.search_history_clear),
        onClick = onClearSearchHistory,
      )
    }
  }
}

@Composable
private fun SearchHintText(text: String) {
  val homeColors = LocalHomeColors.current
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = homeColors.textTertiary,
      fontSize = BiliTypography.Body,
      fontWeight = FontWeight.Medium,
    )
  }
}

@Composable
private fun SearchSuggestionItem(
  text: String,
  displayText: String = text,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Card),
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.SearchKeyboardButtonHeight),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = BiliSpacing.Lg),
      contentAlignment = Alignment.CenterStart,
    ) {
      Text(
        text = displayText,
        color = homeColors.textSecondary,
        fontSize = BiliTypography.Body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun SearchResultsView(
  query: String,
  videoRepository: VideoRepository,
  uiState: SearchUiState,
  firstResultFocusRequester: FocusRequester,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onBackToKeyboard: () -> Unit,
  onVideoSelected: (VideoSummary) -> Unit,
) {
  val coroutineScope = rememberCoroutineScope()
  val sortFocusRequesters = remember {
    SearchSortOptions.associate { option -> option.key to FocusRequester() }
  }
  val selectedOrderKey = uiState.selectedOrderKey

  LaunchedEffect(videoRepository, query, selectedOrderKey, uiState.retryKey) {
    if (
      uiState.loadedQuery == query &&
      uiState.loadedOrderKey == selectedOrderKey &&
      uiState.loadedRetryKey == uiState.retryKey &&
      uiState.resultState !is SearchResultState.Loading
    ) {
      return@LaunchedEffect
    }

    uiState.resultState = SearchResultState.Loading
    uiState.focusedResultIndex = 0
    uiState.focusedResultKey = ""
    val nextState = try {
      val videos = videoRepository.searchVideos(
        keyword = query,
        page = FirstPage,
        order = selectedOrderKey,
      )
      if (videos.isEmpty()) {
        SearchResultState.Empty
      } else {
        SearchResultState.Success(
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
      SearchResultState.Failed(error.message.orEmpty())
    }
    uiState.loadedQuery = query
    uiState.loadedOrderKey = selectedOrderKey
    uiState.loadedRetryKey = uiState.retryKey
    uiState.resultState = nextState
  }

  fun loadNextPage() {
    val currentState = uiState.resultState as? SearchResultState.Success ?: return
    if (currentState.loadingMore || currentState.endReached) {
      return
    }

    val pageToLoad = currentState.nextPage
    val orderToLoad = selectedOrderKey
    uiState.resultState = currentState.copy(
      loadingMore = true,
      loadMoreError = "",
    )

    coroutineScope.launch {
      uiState.resultState = try {
        val nextVideos = videoRepository.searchVideos(
          keyword = query,
          page = pageToLoad,
          order = orderToLoad,
        )
        val latestState = uiState.resultState as? SearchResultState.Success ?: return@launch
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
        val latestState = uiState.resultState as? SearchResultState.Success ?: return@launch
        latestState.copy(
          loadingMore = false,
          loadMoreError = error.message.orEmpty(),
        )
      }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
          onBackToKeyboard()
          true
        } else {
          false
        }
      },
  ) {
    SearchResultsHeader(
      query = query,
      selectedOrderKey = selectedOrderKey,
      sortFocusRequesters = sortFocusRequesters,
      firstResultFocusRequester = firstResultFocusRequester,
      onMoveLeftToNav = onMoveLeftToNav,
      onOrderSelected = { orderKey ->
        uiState.selectOrder(orderKey)
      },
    )
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = BiliSpacing.Lg),
    ) {
      when (val currentState = uiState.resultState) {
        SearchResultState.Loading -> VideoGridSkeleton()
        SearchResultState.Empty -> FeedStatusScreen(message = stringResource(R.string.search_empty))
        is SearchResultState.Failed -> FeedStatusScreen(
          message = stringResource(R.string.search_failed_with_message, currentState.message),
          actionLabel = stringResource(R.string.action_retry),
          onAction = {
            uiState.retryKey += 1
          },
        )
        is SearchResultState.Success -> SearchResultGrid(
          videos = currentState.videos,
          firstResultFocusRequester = firstResultFocusRequester,
          selectedSortFocusRequester = sortFocusRequesters.getValue(selectedOrderKey),
          restoredFocusIndex = currentState.videos.resolveFocusIndex(
            focusKey = uiState.focusedResultKey,
            fallbackIndex = uiState.focusedResultIndex,
          ),
          restoreFocusRequestKey = restoreFocusRequestKey,
          onRestoreFocusHandled = onRestoreFocusHandled,
          focusFirstResult = uiState.focusFirstResult,
          onFirstResultFocused = {
            uiState.focusFirstResult = false
          },
          onFocusedIndexChange = { index, video ->
            uiState.focusedResultIndex = index
            uiState.focusedResultKey = video.focusRestoreKey()
          },
          onLoadMore = ::loadNextPage,
          onMoveLeftToNav = onMoveLeftToNav,
          onBackToKeyboard = onBackToKeyboard,
          onVideoSelected = onVideoSelected,
          onOwnerSelected = onOwnerSelected,
        )
      }
    }
  }
}

@Composable
private fun SearchResultsHeader(
  query: String,
  selectedOrderKey: String,
  sortFocusRequesters: Map<String, FocusRequester>,
  firstResultFocusRequester: FocusRequester,
  onMoveLeftToNav: () -> Boolean,
  onOrderSelected: (String) -> Unit,
) {
  val homeColors = LocalHomeColors.current
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
  ) {
    Text(
      text = stringResource(R.string.search_results_title, convertChineseText(query)),
      color = homeColors.textPrimary,
      fontSize = BiliTypography.SectionTitle,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = BiliSizing.SearchVideoGridHorizontalPadding),
    )
    LazyRow(
      modifier = Modifier
        .padding(horizontal = BiliSizing.SearchVideoGridHorizontalPadding)
        .fillMaxWidth()
        .height(BiliSizing.HomeSectionTabHeight + BiliSpacing.Xs)
        .padding(BiliSpacing.Xs),
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
      contentPadding = PaddingValues(horizontal = BiliSpacing.Xs),
    ) {
      itemsIndexed(SearchSortOptions, key = { _, option -> option.key }) { index, option ->
        val selected = selectedOrderKey == option.key
        SearchSortButton(
          option = option,
          selected = selected,
          modifier = Modifier.focusRequester(sortFocusRequesters.getValue(option.key)),
          onMoveLeftToNav = if (index == 0) onMoveLeftToNav else null,
          onMoveDownToResults = {
            runCatching {
              firstResultFocusRequester.requestFocus()
            }.isSuccess
          },
          onSelected = {
            onOrderSelected(option.key)
          },
        )
      }
    }
  }
}

@Composable
private fun SearchSortButton(
  option: SearchSortOption,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onMoveLeftToNav: (() -> Boolean)? = null,
  onMoveDownToResults: () -> Boolean,
  onSelected: () -> Unit,
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
      label = "searchSortBorderWidth",
    ).value
  } else {
    if (focused) BiliFocus.BorderWidth else BiliFocus.RestingBorderWidth
  }
  val borderColor = if (performancePolicy.motionEnabled) {
    animateColorAsState(
      targetValue = targetBorderColor,
      animationSpec = androidx.compose.animation.core.tween(BiliMotion.FocusMs, easing = BiliMotion.FocusEasing),
      label = "searchSortBorder",
    ).value
  } else {
    targetBorderColor
  }
  val textColor = if (performancePolicy.motionEnabled) {
    animateColorAsState(
      targetValue = targetTextColor,
      animationSpec = androidx.compose.animation.core.tween(BiliMotion.FocusMs, easing = BiliMotion.FocusEasing),
      label = "searchSortText",
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
      .border(BorderStroke(borderWidth, borderColor), shape)
      .onFocusChanged { focusState ->
        focused = focusState.isFocused
        if (focusState.isFocused && !selected) {
          onSelected()
        }
      }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft ->
            if (onMoveLeftToNav != null) onMoveLeftToNav() else false
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> onMoveDownToResults()
          event.type == KeyEventType.KeyUp && event.key.isConfirmKey() -> {
            onSelected()
            true
          }
          else -> false
        }
      }
      .focusable(interactionSource = interactionSource)
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onSelected,
      )
      .padding(horizontal = BiliSpacing.Sm),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = stringResource(option.titleRes),
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
private fun SearchResultGrid(
  videos: List<VideoSummary>,
  firstResultFocusRequester: FocusRequester,
  selectedSortFocusRequester: FocusRequester,
  restoredFocusIndex: Int,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  focusFirstResult: Boolean,
  onFirstResultFocused: () -> Unit,
  onFocusedIndexChange: (Int, VideoSummary) -> Unit,
  onLoadMore: () -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onBackToKeyboard: () -> Unit,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit = {},
) {
  LaunchedEffect(videos, focusFirstResult) {
    if (videos.isNotEmpty() && focusFirstResult) {
      withFrameNanos { }
      runCatching {
        firstResultFocusRequester.requestFocus()
      }
      onFirstResultFocused()
    }
  }

  TvVideoGrid(
    videos = videos,
    firstItemFocusRequester = firstResultFocusRequester,
    restoredFocusIndex = restoredFocusIndex,
    restoreFocusRequestKey = restoreFocusRequestKey,
    onRestoreFocusHandled = onRestoreFocusHandled,
    onFocusedIndexChange = onFocusedIndexChange,
    onLoadMore = onLoadMore,
    onMoveLeftToNav = onMoveLeftToNav,
    onMoveUpFromFirstRow = {
      runCatching {
        selectedSortFocusRequester.requestFocus()
      }.isSuccess
    },
    onBackKey = {
      onBackToKeyboard()
      true
    },
    onVideoSelected = onVideoSelected,
    onOwnerSelected = onOwnerSelected,
    horizontalPadding = BiliSizing.SearchVideoGridHorizontalPadding,
  )
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

private fun Int.shouldLoadMore(totalItems: Int, threshold: Int): Boolean {
  return totalItems - this <= threshold
}

internal sealed interface SearchResultState {
  data object Loading : SearchResultState
  data object Empty : SearchResultState
  data class Failed(val message: String) : SearchResultState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: String,
  ) : SearchResultState
}

private data class SearchSortOption(
  val key: String,
  val titleRes: Int,
)

private const val SearchSuggestionDebounceMs = 250L
private const val RestoreFocusRetryCount = 8
private const val FirstPage = 1
private const val PageSize = 20

private val SearchSortOptions = listOf(
  SearchSortOption("totalrank", R.string.search_sort_totalrank),
  SearchSortOption("click", R.string.search_sort_click),
  SearchSortOption("pubdate", R.string.search_sort_pubdate),
  SearchSortOption("dm", R.string.search_sort_dm),
)

private val SearchKeyboardRows = listOf(
  listOf("A", "B", "C", "D", "E", "F"),
  listOf("G", "H", "I", "J", "K", "L"),
  listOf("M", "N", "O", "P", "Q", "R"),
  listOf("S", "T", "U", "V", "W", "X"),
  listOf("Y", "Z", "1", "2", "3", "4"),
  listOf("5", "6", "7", "8", "9", "0"),
)
