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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.image.BiliImageSizing
import com.kirin.mt.core.image.buildOwnerAvatarRequest
import com.kirin.mt.core.model.SourceBili
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.UserSummary
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.storage.SearchHistoryStore
import com.kirin.mt.core.youtube.YoutubeSearchParams
import com.kirin.mt.core.youtube.toUserSummary
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.common.VideoGridSkeleton
import com.kirin.mt.ui.common.appendUniqueByBvid
import com.kirin.mt.ui.common.appendUniqueByMid
import com.kirin.mt.ui.common.dedupKey
import com.kirin.mt.ui.common.focusRestoreKey
import com.kirin.mt.ui.common.resolveFocusIndex
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.home.TvVideoGrid
import com.kirin.mt.ui.home.VideoCard
import com.kirin.mt.ui.i18n.convertChineseText
import com.kirin.mt.ui.i18n.currentUiLocale
import com.kirin.mt.ui.i18n.formatCompactCount
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Stable
internal class SearchUiState {
  var searchText by mutableStateOf("")
  var activeQuery by mutableStateOf<String?>(null)
  var source by mutableStateOf(SourceBili)
  var searchType by mutableStateOf(SearchTypeVideo)
  var selectedOrderKey by mutableStateOf(BiliSearchSortOptions.first().key)
  var focusFirstResult by mutableStateOf(true)
  var focusedResultIndex by mutableIntStateOf(0)
  var focusedResultKey by mutableStateOf("")
  var retryKey by mutableIntStateOf(0)
  var resultState by mutableStateOf<SearchResultState>(SearchResultState.Loading)
  var loadedQuery by mutableStateOf("")
  var loadedOrderKey by mutableStateOf("")
  var loadedSource by mutableStateOf("")
  var loadedType by mutableStateOf("")
  var loadedRetryKey by mutableIntStateOf(-1)

  fun startSearch(query: String) {
    searchText = query
    if (activeQuery != query) {
      resetResultsForQuery(query)
    }
    activeQuery = query
  }

  fun selectSource(newSource: String) {
    if (source == newSource) {
      return
    }
    source = newSource
    // 排序 key 与来源耦合(B站 totalrank/click…,YouTube params 串),切源重置为该源默认「综合」。
    selectedOrderKey = defaultOrderKey(newSource)
    focusFirstResult = true
    focusedResultIndex = 0
    focusedResultKey = ""
    retryKey = 0
    resultState = SearchResultState.Loading
    loadedQuery = ""
    loadedOrderKey = ""
    loadedSource = ""
    loadedType = ""
    loadedRetryKey = -1
  }

  /** 切换搜索类型（视频/UP主）。类型与排序解耦，切类型重置结果态以便重搜。 */
  fun selectType(newType: String) {
    if (searchType == newType) {
      return
    }
    searchType = newType
    focusFirstResult = true
    focusedResultIndex = 0
    focusedResultKey = ""
    retryKey = 0
    resultState = SearchResultState.Loading
    loadedQuery = ""
    loadedOrderKey = ""
    loadedSource = ""
    loadedType = ""
    loadedRetryKey = -1
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
    selectedOrderKey = defaultOrderKey(source)
    focusFirstResult = true
    focusedResultIndex = 0
    focusedResultKey = ""
    retryKey = 0
    resultState = SearchResultState.Loading
    loadedQuery = query.takeIf { it.isBlank() }.orEmpty()
    loadedOrderKey = ""
    loadedSource = ""
    loadedType = ""
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
  onUserSelected: (UserSummary) -> Unit = {},
) {
  val coroutineScope = rememberCoroutineScope()
  val searchHistory by searchHistoryStore.history.collectAsState(initial = emptyList())
  var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
  var returnFocusToKeyboard by remember { mutableStateOf(false) }
  val screenFocusRequester = remember { FocusRequester() }
  val sourceToggleFocusRequester = remember { FocusRequester() }
  val inputFocusRequester = remember { FocusRequester() }
  // 结果视图的标题「返回重新搜索」:结果模式下 SourceToggle 的 Down 落到这里(键盘模式的 Down 落输入框)。
  val resultsTitleFocusRequester = remember { FocusRequester() }

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
  Column(
    modifier = Modifier
      .fillMaxSize()
      .focusRequester(screenFocusRequester)
      .focusable(),
  ) {
    SearchSourceToggle(
      source = uiState.source,
      onSourceSelected = { newSource ->
        if (uiState.activeQuery != null) {
          uiState.selectSource(newSource)
        } else {
          uiState.source = newSource
        }
      },
      focusRequester = sourceToggleFocusRequester,
      onMoveDown = {
        // 键盘模式 Down→输入框;结果模式输入框未挂载,Down→结果标题(返回重新搜索),否则焦点卡在 pill 上。
        val target = if (uiState.activeQuery == null) {
          inputFocusRequester
        } else {
          resultsTitleFocusRequester
        }
        runCatching { target.requestFocus() }.isSuccess
      },
      modifier = Modifier.padding(horizontal = BiliSizing.ContentPadding),
    )
    Box(
      modifier = Modifier.fillMaxSize(),
    ) {
      if (query == null) {
        SearchKeyboardView(
          searchText = uiState.searchText,
          suggestions = suggestions,
          searchHistory = searchHistory,
          keyboardFocusRequester = firstItemFocusRequester,
          inputFocusRequester = inputFocusRequester,
          onMoveUpToSourceToggle = {
            runCatching { sourceToggleFocusRequester.requestFocus() }.isSuccess
          },
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
          titleFocusRequester = resultsTitleFocusRequester,
          restoreFocusRequestKey = restoreFocusRequestKey,
          onRestoreFocusHandled = onRestoreFocusHandled,
          onMoveLeftToNav = onMoveLeftToNav,
          onBackToKeyboard = {
            uiState.backToKeyboard()
            returnFocusToKeyboard = true
          },
          onVideoSelected = onVideoSelected,
          onOwnerSelected = onOwnerSelected,
          onUserSelected = onUserSelected,
        )
      }
    }
  }
}

@Composable
private fun SearchSourceToggle(
  source: String,
  onSourceSelected: (String) -> Unit,
  focusRequester: FocusRequester,
  onMoveDown: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  // 单个按钮占满整行居中,显示当前源;点击循环切换到另一个源。
  val label = if (source == SourceBili) "BILIBILI" else "YOUTUBE"
  val targetSource = if (source == SourceBili) SourceYoutube else SourceBili
  BiliFocusableSurface(
    scaleOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Pill),
    onClick = { onSourceSelected(targetSource) },
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.HomeSectionTabHeight)
      .focusRequester(focusRequester)
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
          onMoveDown()
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
        color = homeColors.accent,
        fontSize = BiliTypography.HomeSectionTab,
        fontWeight = FontWeight.Bold,
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
  inputFocusRequester: FocusRequester,
  onMoveUpToSourceToggle: () -> Boolean,
  onMoveLeftToNav: () -> Boolean,
  onTextChange: (String) -> Unit,
  onClearSearchHistory: () -> Unit,
  onSearch: (String) -> Unit,
) {
  // 输入框聚焦不自动弹 IME,按确认键才进入 IME 输入态(自绘键盘隐藏);焦点移开时退出输入态、自绘键盘恢复。
  var imeActive by remember { mutableStateOf(false) }
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(BiliSizing.ContentPadding)
      .imePadding(),
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
        SearchInputText(
          searchText = searchText,
          onTextChange = onTextChange,
          focusRequester = inputFocusRequester,
          imeActive = imeActive,
          onImeActiveChange = { imeActive = it },
          onMoveUp = onMoveUpToSourceToggle,
          onMoveDown = {
            // IME 激活、自绘键盘隐藏时,Down 交给默认焦点系统(移到右侧建议面板)。
            if (imeActive) {
              false
            } else {
              runCatching { keyboardFocusRequester.requestFocus() }.isSuccess
            }
          },
          onSearchSubmit = {
            onSearch(searchText)
          },
        )
        if (!imeActive) {
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
              onMoveUp = {
                runCatching { inputFocusRequester.requestFocus() }.isSuccess
              },
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
            modifier = Modifier.weight(1f),
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
private fun SearchInputText(
  searchText: String,
  onTextChange: (String) -> Unit,
  focusRequester: FocusRequester,
  imeActive: Boolean,
  onImeActiveChange: (Boolean) -> Unit,
  onMoveUp: () -> Boolean,
  onMoveDown: () -> Boolean,
  onSearchSubmit: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val placeholder = stringResource(R.string.search_input_placeholder)
  val keyboardController = LocalSoftwareKeyboardController.current
  var focused by remember { mutableStateOf(false) }
  val borderColor = if (focused) homeColors.accent else homeColors.glassBorder
  val borderWidth = if (focused) BiliFocus.BorderWidth else BiliFocus.RestingBorderWidth

  // 兜底:焦点帧后系统仍可能自动弹 IME,再压一次确保「仅聚焦不弹、确认才进输入态」。
  LaunchedEffect(focused, imeActive) {
    if (focused && !imeActive) {
      keyboardController?.hide()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.SearchInputHeight)
      .background(
        if (focused) homeColors.accent.copy(alpha = 0.12f) else homeColors.glassSurfaceStrong,
        RoundedCornerShape(BiliRadius.Card),
      )
      .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(BiliRadius.Card))
      .padding(horizontal = BiliSpacing.Lg),
    contentAlignment = Alignment.CenterStart,
  ) {
    BasicTextField(
      value = searchText,
      onValueChange = onTextChange,
      singleLine = true,
      textStyle = TextStyle(
        color = if (searchText.isBlank()) homeColors.textTertiary else homeColors.textPrimary,
        fontSize = BiliTypography.SearchInput,
        fontWeight = FontWeight.Bold,
      ),
      cursorBrush = SolidColor(homeColors.accent),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
      modifier = Modifier
        .fillMaxWidth()
        .focusRequester(focusRequester)
        .onFocusChanged { focusState ->
          focused = focusState.isFocused
          if (!focusState.isFocused) {
            // 焦点移开:退出 IME 输入态,自绘键盘恢复。
            if (imeActive) {
              onImeActiveChange(false)
            }
          } else if (!imeActive) {
            // 仅聚焦不自动弹系统 IME,等按确认键再进入输入态。
            keyboardController?.hide()
          }
        }
        .onPreviewKeyEvent { event ->
          when {
            event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> onMoveUp()
            event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> onMoveDown()
            event.type == KeyEventType.KeyDown && event.key.isConfirmKey() && !imeActive -> {
              // 未进入输入态时按确认键:唤起 IME、隐藏自绘键盘。
              onImeActiveChange(true)
              keyboardController?.show()
              true
            }
            else -> false
          }
        },
      decorationBox = { innerTextField ->
        if (searchText.isBlank()) {
          Text(
            text = placeholder,
            color = homeColors.textTertiary,
            fontSize = BiliTypography.SearchInput,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        innerTextField()
      },
    )
  }
}

@Composable
private fun SearchKeyGrid(
  onKeyClick: (String) -> Unit,
  onMoveLeftToNav: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  // 键盘区整体弹性:每行均分剩余高度,按键随屏幕高度自适应伸缩,避免底部搜索按钮被挤出可视区。
  Column(
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
    modifier = modifier.fillMaxWidth(),
  ) {
    SearchKeyboardRows.forEach { row ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
      ) {
        row.forEachIndexed { columnIndex, key ->
          SearchKeyboardButton(
            label = key,
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight(),
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
  onMoveUp: (() -> Boolean)? = null,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Card),
    onClick = onClick,
    modifier = modifier
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && onMoveLeft != null -> onMoveLeft()
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && onMoveUp != null -> onMoveUp()
          else -> false
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
  titleFocusRequester: FocusRequester,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onBackToKeyboard: () -> Unit,
  onVideoSelected: (VideoSummary) -> Unit,
  onOwnerSelected: (VideoSummary) -> Unit = {},
  onUserSelected: (UserSummary) -> Unit = {},
) {
  val coroutineScope = rememberCoroutineScope()
  val sortFocusRequesters = remember(uiState.source) {
    sortOptionsFor(uiState.source).associate { option -> option.key to FocusRequester() }
  }
  // 类型单开关按钮只有一个焦点落点(视频/UP主 两枚旧 chip 已去掉,见 SearchResultsHeader)。
  val typeToggleFocusRequester = remember { FocusRequester() }
  val selectedOrderKey = uiState.selectedOrderKey
  val source = uiState.source
  val searchType = uiState.searchType

  LaunchedEffect(videoRepository, query, selectedOrderKey, source, searchType, uiState.retryKey) {
    if (
      uiState.loadedQuery == query &&
      uiState.loadedOrderKey == selectedOrderKey &&
      uiState.loadedSource == source &&
      uiState.loadedType == searchType &&
      uiState.loadedRetryKey == uiState.retryKey &&
      uiState.resultState !is SearchResultState.Loading
    ) {
      return@LaunchedEffect
    }

    uiState.resultState = SearchResultState.Loading
    uiState.focusedResultIndex = 0
    uiState.focusedResultKey = ""
    val nextState = try {
      if (searchType == SearchTypeUser) {
        if (source == SourceYoutube) {
          val page = videoRepository.youtubeSearchChannels(query = query)
          if (page.items.isEmpty()) {
            SearchResultState.Empty
          } else {
            SearchResultState.Success(
              users = page.items.map { it.toUserSummary() },
              nextPage = FirstPage + 1,
              continuation = page.continuation,
              loadingMore = false,
              endReached = page.continuation == null,
              loadMoreError = "",
            )
          }
        } else {
          val users = videoRepository.searchUsers(keyword = query, page = FirstPage)
          if (users.isEmpty()) {
            SearchResultState.Empty
          } else {
            SearchResultState.Success(
              users = users,
              nextPage = FirstPage + 1,
              loadingMore = false,
              endReached = users.size < PageSize,
              loadMoreError = "",
            )
          }
        }
      } else if (source == SourceYoutube) {
        val page = videoRepository.youtubeSearch(query = query, params = selectedOrderKey)
        if (page.items.isEmpty()) {
          SearchResultState.Empty
        } else {
          SearchResultState.Success(
            videos = page.items,
            nextPage = FirstPage + 1,
            continuation = page.continuation,
            loadingMore = false,
            endReached = page.continuation == null,
            loadMoreError = "",
          )
        }
      } else {
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
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      SearchResultState.Failed(error.message.orEmpty())
    }
    uiState.loadedQuery = query
    uiState.loadedOrderKey = selectedOrderKey
    uiState.loadedSource = source
    uiState.loadedType = searchType
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
    val continuation = currentState.continuation
    uiState.resultState = currentState.copy(
      loadingMore = true,
      loadMoreError = "",
    )

    coroutineScope.launch {
      uiState.resultState = try {
        val latestState = uiState.resultState as? SearchResultState.Success ?: return@launch
        val nextContinuation: String?
        val endReached: Boolean
        if (searchType == SearchTypeUser) {
          val nextUsers: List<UserSummary>
          if (source == SourceYoutube) {
            val page = videoRepository.youtubeSearchChannels(
              query = query,
              continuation = continuation,
            )
            nextUsers = page.items.map { it.toUserSummary() }
            nextContinuation = page.continuation
          } else {
            nextUsers = videoRepository.searchUsers(keyword = query, page = pageToLoad)
            nextContinuation = null
          }
          val mergedUsers = latestState.users.appendUniqueByMid(nextUsers)
          endReached = if (source == SourceYoutube) {
            nextContinuation == null || mergedUsers.size == latestState.users.size
          } else {
            nextUsers.size < PageSize || mergedUsers.size == latestState.users.size
          }
          latestState.copy(
            users = mergedUsers,
            nextPage = pageToLoad + 1,
            continuation = nextContinuation,
            loadingMore = false,
            endReached = endReached,
            loadMoreError = "",
          )
        } else {
          val nextVideos: List<VideoSummary>
          if (source == SourceYoutube) {
            val page = videoRepository.youtubeSearch(
              query = query,
              continuation = continuation,
            )
            nextVideos = page.items
            nextContinuation = page.continuation
          } else {
            nextVideos = videoRepository.searchVideos(
              keyword = query,
              page = pageToLoad,
              order = orderToLoad,
            )
            nextContinuation = null
          }
          val mergedVideos = latestState.videos.appendUniqueByBvid(nextVideos)
          endReached = if (source == SourceYoutube) {
            nextContinuation == null || mergedVideos.size == latestState.videos.size
          } else {
            nextVideos.size < PageSize ||
              mergedVideos.size == latestState.videos.size
          }
          latestState.copy(
            videos = mergedVideos,
            nextPage = pageToLoad + 1,
            continuation = nextContinuation,
            loadingMore = false,
            endReached = endReached,
            loadMoreError = "",
          )
        }
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
      source = source,
      searchType = searchType,
      selectedOrderKey = selectedOrderKey,
      sortFocusRequesters = sortFocusRequesters,
      typeToggleFocusRequester = typeToggleFocusRequester,
      titleFocusRequester = titleFocusRequester,
      firstResultFocusRequester = firstResultFocusRequester,
      onMoveLeftToNav = onMoveLeftToNav,
      onBackToKeyboard = onBackToKeyboard,
      onOrderSelected = { orderKey ->
        uiState.selectOrder(orderKey)
      },
      onTypeSelected = { type ->
        uiState.selectType(type)
      },
    )
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = BiliSpacing.Lg),
    ) {
      when (val currentState = uiState.resultState) {
        SearchResultState.Loading -> VideoGridSkeleton()
        SearchResultState.Empty -> FeedStatusScreen(
          message = stringResource(
            if (searchType == SearchTypeUser) R.string.search_empty_user else R.string.search_empty
          )
        )
        is SearchResultState.Failed -> FeedStatusScreen(
          message = stringResource(R.string.search_failed_with_message, currentState.message),
          actionLabel = stringResource(R.string.action_retry),
          onAction = {
            uiState.retryKey += 1
          },
        )
        is SearchResultState.Success -> {
          if (searchType == SearchTypeUser) {
            UserResultList(
              users = currentState.users,
              firstResultFocusRequester = firstResultFocusRequester,
              selectedTypeFocusRequester = typeToggleFocusRequester,
              focusFirstResult = uiState.focusFirstResult,
              onFirstResultFocused = {
                uiState.focusFirstResult = false
              },
              onFocusedIndexChange = { index, user ->
                uiState.focusedResultIndex = index
                uiState.focusedResultKey = user.focusRestoreKey()
              },
              onLoadMore = ::loadNextPage,
              onMoveLeftToNav = onMoveLeftToNav,
              onBackToKeyboard = onBackToKeyboard,
              onUserSelected = onUserSelected,
            )
          } else {
            SearchResultGrid(
              videos = currentState.videos,
              firstResultFocusRequester = firstResultFocusRequester,
              selectedSortFocusRequester = sortFocusRequesters.getValue(selectedOrderKey),
              titleFocusRequester = titleFocusRequester,
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
  }
}

@Composable
private fun SearchResultsHeader(
  query: String,
  source: String,
  searchType: String,
  selectedOrderKey: String,
  sortFocusRequesters: Map<String, FocusRequester>,
  typeToggleFocusRequester: FocusRequester,
  titleFocusRequester: FocusRequester,
  firstResultFocusRequester: FocusRequester,
  onMoveLeftToNav: () -> Boolean,
  onBackToKeyboard: () -> Unit,
  onOrderSelected: (String) -> Unit,
  onTypeSelected: (String) -> Unit,
) {
  val homeColors = LocalHomeColors.current
  var titleFocused by remember { mutableStateOf(false) }
  // 视频类型才显示排序 chip;UP主 类型只剩类型 chip,标题 Down 需回退落类型 chip。
  val showSort = searchType == SearchTypeVideo
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
  ) {
    // 标题可聚焦:按确认键返回键盘重新搜索。
    BiliFocusableSurface(
      scaleOnFocus = false,
      shape = RoundedCornerShape(BiliRadius.Card),
      onClick = onBackToKeyboard,
      modifier = Modifier
        .fillMaxWidth()
        .focusRequester(titleFocusRequester)
        .onFocusChanged { titleFocused = it.isFocused }
        .onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
            // 排序 chip 在类型 chip 前（对齐移动端顺序）;落当前选中排序 chip（避免聚焦未选中 chip 触发焦点即选中重搜）。
            // UP主 类型排序 chip 不在组合树,回退落第一个类型 chip（视频）。
            val moved = if (showSort) {
              runCatching { sortFocusRequesters.getValue(selectedOrderKey).requestFocus() }.getOrDefault(false)
            } else {
              false
            }
            if (moved) true else runCatching { typeToggleFocusRequester.requestFocus() }.isSuccess
          } else {
            false
          }
        },
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = BiliSizing.SearchVideoGridHorizontalPadding)
          .height(BiliSizing.HomeSectionTabHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      ) {
        Text(
          text = stringResource(R.string.search_results_title, convertChineseText(query)),
          color = homeColors.textPrimary,
          fontSize = BiliTypography.SectionTitle,
          fontWeight = FontWeight.Bold,
        )
        if (titleFocused) {
          Text(
            text = stringResource(R.string.search_back_to_edit_hint),
            color = homeColors.textTertiary,
            fontSize = BiliTypography.BodySmall,
          )
        }
      }
    }
    val sortOptions = sortOptionsFor(source)
    LazyRow(
      modifier = Modifier
        .padding(horizontal = BiliSizing.SearchVideoGridHorizontalPadding)
        .fillMaxWidth()
        .height(BiliSizing.HomeSectionTabHeight + BiliSpacing.Xs)
        .padding(BiliSpacing.Xs),
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
      contentPadding = PaddingValues(horizontal = BiliSpacing.Xs),
    ) {
    // 排序 chip 在前（综合/最多播放/最新/最多弹幕，对齐移动端顺序），类型 chip（视频/UP主）在后；
    // UP主 类型时隐藏排序 chip，只剩类型 chip。行首 chip（视频类型时为第一个排序 chip）Left 移侧栏。
    if (showSort) {
      itemsIndexed(sortOptions, key = { _, option -> option.key }) { index, option ->
        val selected = selectedOrderKey == option.key
        SearchSortButton(
          option = option,
          selected = selected,
          modifier = Modifier.focusRequester(sortFocusRequesters.getValue(option.key)),
          onMoveLeftToNav = if (index == 0) onMoveLeftToNav else null,
          onMoveUpToTitle = {
            runCatching { titleFocusRequester.requestFocus() }.isSuccess
          },
          onMoveDownToResults = {
            runCatching { firstResultFocusRequester.requestFocus() }.isSuccess
          },
          onSelected = {
            onOrderSelected(option.key)
          },
        )
      }
    }
    // 类型单开关按钮:恒标「UP主(B站)/频道(YouTube)」,选中态=UP主搜索,OK 在 视频⇄UP主 间翻转。
    // 「视频」chip 已去掉——默认即视频,不会有误解(对齐移动端,2026-08-30 用户定稿)。
    // 聚焦只高亮不切类型(P11-53 教训:焦点扫过触发重搜);行尾 Right 消费防焦点逃逸(P11-51 教训)。
    // 显式 key:切类型时排序 chip 整组出入组合树,无 key 的 item 按位置挪位会被 LazyRow
    // 当作新 item 销毁重建 → 聚焦中的类型 chip 节点被 detach → 焦点逃出搜索屏落到侧栏头像,
    // autoConfirm 直接打开「我的」页(即「切频道退到头像」bug)。稳定 key 令节点跨重组存活,焦点不掉。
    item(key = "type_toggle") {
      SearchSortButton(
        option = typeOptionsFor(source).last(),
        selected = searchType == SearchTypeUser,
        selectOnFocus = false,
        consumeRight = true,
        modifier = Modifier.focusRequester(typeToggleFocusRequester),
        // 排序 chip 在前时该按钮 Left 交给默认焦点系统(移回排序行);UP主 类型(排序隐藏,按钮行首)Left 移侧栏。
        onMoveLeftToNav = if (!showSort) onMoveLeftToNav else null,
        onMoveUpToTitle = {
          runCatching { titleFocusRequester.requestFocus() }.isSuccess
        },
        onMoveDownToResults = {
          runCatching { firstResultFocusRequester.requestFocus() }.isSuccess
        },
        onSelected = {
          onTypeSelected(if (searchType == SearchTypeUser) SearchTypeVideo else SearchTypeUser)
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
  onMoveUpToTitle: () -> Boolean,
  onMoveDownToResults: () -> Boolean,
  onSelected: () -> Unit,
  // 排序 chip 沿用「焦点即选中」;类型开关按钮(单枚)传 false——聚焦只高亮,OK 才翻转,
  // 防焦点扫过未选中 chip 触发重搜(P11-53 教训)。
  selectOnFocus: Boolean = true,
  // 行尾按钮传 true:消费 Right 防焦点逃逸出 chip 行(P11-51 教训)。
  consumeRight: Boolean = false,
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
        if (selectOnFocus && focusState.isFocused && !selected) {
          onSelected()
        }
      }
      .onPreviewKeyEvent { event ->
        when {
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft ->
            if (onMoveLeftToNav != null) onMoveLeftToNav() else false
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> consumeRight
          event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> onMoveUpToTitle()
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
  titleFocusRequester: FocusRequester,
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
      // B站优先回排序选项;YouTube 无排序选项时回标题(重新搜索)。
      val moved = runCatching { selectedSortFocusRequester.requestFocus() }.getOrDefault(false)
      if (moved) true else runCatching { titleFocusRequester.requestFocus() }.getOrDefault(false)
    },
    onBackKey = {
      onBackToKeyboard()
      true
    },
    onVideoSelected = onVideoSelected,
    onOwnerSelected = onOwnerSelected,
    onCardLongPress = { video -> onOwnerSelected(video) },
    horizontalPadding = BiliSizing.SearchVideoGridHorizontalPadding,
  )
}

@Composable
private fun UserResultList(
  users: List<UserSummary>,
  firstResultFocusRequester: FocusRequester,
  selectedTypeFocusRequester: FocusRequester,
  focusFirstResult: Boolean,
  onFirstResultFocused: () -> Unit,
  onFocusedIndexChange: (Int, UserSummary) -> Unit,
  onLoadMore: () -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onBackToKeyboard: () -> Unit,
  onUserSelected: (UserSummary) -> Unit,
) {
  val listState = rememberLazyListState()
  LaunchedEffect(users, focusFirstResult) {
    if (users.isNotEmpty() && focusFirstResult) {
      withFrameNanos { }
      runCatching { firstResultFocusRequester.requestFocus() }
      onFirstResultFocused()
    }
  }
  // 滚到底自动翻页。
  LaunchedEffect(users.size) {
    snapshotFlow {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = listState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 3
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) onLoadMore() }
  }

  LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = BiliSizing.SearchVideoGridHorizontalPadding,
      end = BiliSizing.SearchVideoGridHorizontalPadding,
      top = BiliSpacing.Sm,
      bottom = BiliSpacing.Lg,
    ),
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
  ) {
    itemsIndexed(users, key = { _, user -> user.dedupKey() }) { index, user ->
      UserResultRow(
        user = user,
        focusRequester = if (index == 0) firstResultFocusRequester else null,
        onMoveUp = {
          if (index == 0) {
            runCatching { selectedTypeFocusRequester.requestFocus() }.isSuccess
          } else {
            false
          }
        },
        onMoveLeft = if (index == 0) onMoveLeftToNav else null,
        onBack = {
          onBackToKeyboard()
          true
        },
        onFocused = {
          onFocusedIndexChange(index, user)
        },
        onClick = {
          onUserSelected(user)
        },
      )
    }
  }
}

@Composable
private fun UserResultRow(
  user: UserSummary,
  focusRequester: FocusRequester?,
  onMoveUp: () -> Boolean,
  onMoveLeft: (() -> Boolean)?,
  onBack: () -> Boolean,
  onFocused: () -> Unit,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val context = LocalContext.current
  val locale = currentUiLocale()
  val performancePolicy = LocalBiliPerformancePolicy.current
  val requestSizePx = if (performancePolicy.lowSpecMode) {
    BiliImageSizing.AccountAvatarSizePx
  } else {
    BiliImageSizing.AccountProfileAvatarSizePx
  }
  val fallbackPainter = ColorPainter(BiliColors.Surface)
  val avatarRequest = remember(context, user.face, requestSizePx, performancePolicy.ownerAvatarRgb565Enabled) {
    buildOwnerAvatarRequest(
      context = context,
      url = user.face,
      sizePx = requestSizePx,
      allowRgb565 = performancePolicy.ownerAvatarRgb565Enabled,
      memoryCacheEnabled = performancePolicy.imageMemoryCacheEnabled,
    )
  }

  val baseModifier = Modifier
    .fillMaxWidth()
    .onFocusChanged { if (it.isFocused) onFocused() }
    .onPreviewKeyEvent { event ->
      when {
        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> onMoveUp()
        event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && onMoveLeft != null -> onMoveLeft()
        event.type == KeyEventType.KeyDown && event.key == Key.Back -> onBack()
        else -> false
      }
    }
  BiliFocusableSurface(
    scaleOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Card),
    onClick = onClick,
    modifier = if (focusRequester != null) baseModifier.focusRequester(focusRequester) else baseModifier,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = BiliSpacing.Lg, vertical = BiliSpacing.Md),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
    ) {
      Box(
        modifier = Modifier
          .size(BiliSizing.AccountProfileAvatarSize)
          .clip(CircleShape)
          .background(BiliColors.Surface),
        contentAlignment = Alignment.Center,
      ) {
        if (user.face.isNotBlank()) {
          AsyncImage(
            model = avatarRequest,
            contentDescription = user.name,
            contentScale = ContentScale.Crop,
            placeholder = fallbackPainter,
            error = fallbackPainter,
            modifier = Modifier
              .size(BiliSizing.AccountProfileAvatarSize)
              .clip(CircleShape),
          )
        } else {
          Icon(
            painter = painterResource(R.drawable.ic_nav_account),
            contentDescription = user.name,
            tint = BiliColors.BiliPink,
            modifier = Modifier.size(BiliSizing.AccountAvatarSize),
          )
        }
      }
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm)) {
          Text(
            text = convertChineseText(user.name).ifBlank { stringResource(R.string.player_panel_unknown_up) },
            color = homeColors.textPrimary,
            fontSize = BiliTypography.SectionTitle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (user.level > 0) {
            Text(
              text = stringResource(R.string.up_space_level, user.level),
              color = BiliColors.BiliPink,
              fontSize = BiliTypography.BodySmall,
              fontWeight = FontWeight.Bold,
            )
          }
          if (user.officialVerify.isNotBlank()) {
            Text(
              text = convertChineseText(user.officialVerify),
              color = BiliColors.TextSecondary,
              fontSize = BiliTypography.BodySmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg)) {
          if (user.fans > 0) {
            Text(
              text = stringResource(R.string.search_user_fans, formatCompactCount(user.fans, locale)),
              color = homeColors.textSecondary,
              fontSize = BiliTypography.Body,
            )
          }
          if (user.videos > 0) {
            Text(
              text = stringResource(R.string.search_user_videos, formatCompactCount(user.videos, locale)),
              color = homeColors.textSecondary,
              fontSize = BiliTypography.Body,
            )
          }
        }
        if (user.sign.isNotBlank()) {
          Text(
            text = convertChineseText(user.sign),
            color = homeColors.textTertiary,
            fontSize = BiliTypography.BodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
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
    val videos: List<VideoSummary> = emptyList(),
    /** UP主/频道搜索结果；视频类型恒空。 */
    val users: List<UserSummary> = emptyList(),
    val nextPage: Int,
    /** YouTube 来源的续页 token；B站来源恒为 null。 */
    val continuation: String? = null,
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

/** 搜索类型：视频。 */
private const val SearchTypeVideo = "video"

/** 搜索类型：UP主/频道。 */
private const val SearchTypeUser = "user"

private val BiliSearchSortOptions = listOf(
  SearchSortOption("totalrank", R.string.search_sort_totalrank),
  SearchSortOption("click", R.string.search_sort_click),
  SearchSortOption("pubdate", R.string.search_sort_pubdate),
  SearchSortOption("dm", R.string.search_sort_dm),
)

/** YouTube 排序:key 即 InnerTube search params(Relevance 为空串→默认综合)。对齐 B站 4 项。 */
private val YoutubeSearchSortOptions = listOf(
  SearchSortOption(YoutubeSearchParams.Relevance, R.string.search_sort_totalrank),
  SearchSortOption(YoutubeSearchParams.ViewCount, R.string.search_sort_click),
  SearchSortOption(YoutubeSearchParams.UploadDate, R.string.search_sort_pubdate),
  SearchSortOption(YoutubeSearchParams.Rating, R.string.search_sort_rating),
)

/** 按来源返回排序选项(两源都有一套,对齐 B站 4 项)。 */
private fun sortOptionsFor(source: String): List<SearchSortOption> =
  if (source == SourceYoutube) YoutubeSearchSortOptions else BiliSearchSortOptions

/** 各来源默认排序(综合)的 key,切换来源时用于重置选中项。 */
private fun defaultOrderKey(source: String): String = sortOptionsFor(source).first().key

/** 搜索类型选项：视频 + UP主（B站）/ 频道（YouTube）。key 即 [SearchTypeVideo]/[SearchTypeUser]。 */
private fun typeOptionsFor(source: String): List<SearchSortOption> =
  if (source == SourceYoutube) {
    listOf(
      SearchSortOption(SearchTypeVideo, R.string.search_type_video),
      SearchSortOption(SearchTypeUser, R.string.search_type_user_youtube),
    )
  } else {
    listOf(
      SearchSortOption(SearchTypeVideo, R.string.search_type_video),
      SearchSortOption(SearchTypeUser, R.string.search_type_user_bili),
    )
  }

private val SearchKeyboardRows = listOf(
  listOf("A", "B", "C", "D", "E", "F"),
  listOf("G", "H", "I", "J", "K", "L"),
  listOf("M", "N", "O", "P", "Q", "R"),
  listOf("S", "T", "U", "V", "W", "X"),
  listOf("Y", "Z", "1", "2", "3", "4"),
  listOf("5", "6", "7", "8", "9", "0"),
)
