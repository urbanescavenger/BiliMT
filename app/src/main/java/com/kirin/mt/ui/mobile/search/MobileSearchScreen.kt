package com.kirin.mt.ui.mobile.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.storage.SearchHistoryStore
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FirstPage = 1
private const val PageSize = 20
private const val SearchSuggestionDebounceMs = 250L

/** 搜索结果分页状态。 */
private sealed interface SearchResultState {
  data object Loading : SearchResultState
  data object Empty : SearchResultState
  data class Failed(val message: String) : SearchResultState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : SearchResultState
}

private data class SearchSortOption(
  val key: String,
  val titleRes: Int,
)

private val SearchSortOptions = listOf(
  SearchSortOption("totalrank", R.string.search_sort_totalrank),
  SearchSortOption("click", R.string.search_sort_click),
  SearchSortOption("pubdate", R.string.search_sort_pubdate),
  SearchSortOption("dm", R.string.search_sort_dm),
)

@Stable
private class MobileSearchUiState {
  var query by mutableStateOf("")
  var submittedQuery by mutableStateOf<String?>(null)
  var orderKey by mutableStateOf(SearchSortOptions.first().key)
  var suggestions by mutableStateOf<List<String>>(emptyList())
  var resultState by mutableStateOf<SearchResultState>(SearchResultState.Loading)

  /** 编辑输入框后回到输入态(退出结果视图)。 */
  fun backToInput() {
    if (submittedQuery != null) {
      submittedQuery = null
      resultState = SearchResultState.Loading
    }
  }

  /** 清空输入并回到输入态。 */
  fun clearQuery() {
    query = ""
    submittedQuery = null
    suggestions = emptyList()
    resultState = SearchResultState.Loading
  }
}

/**
 * 移动端搜索 tab:输入框 + 搜索历史/联想 + 结果网格。复用 VideoRepository.searchVideos /
 * getSearchSuggestions 与 SearchHistoryStore,结果卡片复用 MobileVideoCard。点卡片走
 * onVideoSelected → 触屏播放器。TV 端搜索不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSearchScreen(
  videoRepository: VideoRepository,
  searchHistoryStore: SearchHistoryStore,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current
  val uiState = remember { MobileSearchUiState() }
  val history by searchHistoryStore.history.collectAsState(initial = emptyList())
  val gridState = rememberLazyGridState()
  val listState = rememberLazyListState()

  fun loadFirstPage(query: String, order: String) {
    uiState.resultState = SearchResultState.Loading
    scope.launch {
      val state = try {
        val videos = videoRepository.searchVideos(keyword = query, page = FirstPage, order = order)
        if (videos.isEmpty()) SearchResultState.Empty
        else SearchResultState.Success(
          videos = videos,
          nextPage = FirstPage + 1,
          loadingMore = false,
          endReached = videos.size < PageSize,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        SearchResultState.Failed(e.message.orEmpty().ifBlank { "搜索失败" })
      }
      uiState.resultState = state
    }
  }

  fun submit() {
    val trimmed = uiState.query.trim()
    if (trimmed.isEmpty()) return
    uiState.query = trimmed
    uiState.submittedQuery = trimmed
    uiState.suggestions = emptyList()
    keyboard?.hide()
    scope.launch { searchHistoryStore.add(trimmed) }
    loadFirstPage(trimmed, uiState.orderKey)
  }

  fun selectOrder(key: String) {
    if (uiState.orderKey == key) return
    uiState.orderKey = key
    val q = uiState.submittedQuery ?: return
    loadFirstPage(q, key)
  }

  fun loadNextPage() {
    val current = uiState.resultState as? SearchResultState.Success ?: return
    if (current.loadingMore || current.endReached) return
    val q = uiState.submittedQuery ?: return
    uiState.resultState = current.copy(loadingMore = true)
    scope.launch {
      val next = try {
        val more = videoRepository.searchVideos(keyword = q, page = current.nextPage, order = uiState.orderKey)
        val merged = (current.videos + more).distinctBy { it.bvid }
        current.copy(
          videos = merged,
          nextPage = current.nextPage + 1,
          loadingMore = false,
          endReached = more.size < PageSize || merged.size == current.videos.size,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        current.copy(loadingMore = false)
      }
      uiState.resultState = next
    }
  }

  // 输入态下随输入防抖拉联想;提交态不拉。
  LaunchedEffect(uiState.query, uiState.submittedQuery) {
    if (uiState.submittedQuery != null) return@LaunchedEffect
    val q = uiState.query.trim()
    if (q.isEmpty()) {
      uiState.suggestions = emptyList()
      return@LaunchedEffect
    }
    delay(SearchSuggestionDebounceMs)
    uiState.suggestions = try {
      videoRepository.getSearchSuggestions(q)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptyList()
    }
  }

  // 结果态滚到底自动翻页。
  LaunchedEffect(uiState.submittedQuery, uiState.orderKey) {
    if (uiState.submittedQuery == null) return@LaunchedEffect
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) loadNextPage() }
  }

  // 结果态系统返回键回到输入态。播放器开着时,MobileApp 中播放器覆盖层的 BackHandler
  // 组合在更后位、dispatcher 优先级更高,会先关播放器,不会与本 handler 冲突。
  BackHandler(enabled = uiState.submittedQuery != null) {
    uiState.backToInput()
  }

  Column(modifier = modifier.fillMaxSize()) {
    OutlinedTextField(
      value = uiState.query,
      onValueChange = { text ->
        uiState.query = text
        if (uiState.submittedQuery != null) uiState.backToInput()
      },
      leadingIcon = {
        Icon(
          painter = painterResource(R.drawable.ic_nav_search),
          contentDescription = null,
        )
      },
      trailingIcon = {
        if (uiState.query.isNotEmpty()) {
          IconButton(onClick = { uiState.clearQuery() }) {
            Icon(
              painter = painterResource(R.drawable.ic_clear),
              contentDescription = stringResource(R.string.search_action_clear),
            )
          }
        }
      },
      placeholder = { Text(stringResource(R.string.search_input_placeholder)) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { submit() }),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )

    val submitted = uiState.submittedQuery
    if (submitted == null) {
      // 输入态:空文本显示历史,有文本显示联想。
      if (uiState.query.trim().isEmpty()) {
        SearchHistoryView(
          history = history,
          listState = listState,
          onTap = { item ->
            uiState.query = item
            submit()
          },
          onClear = { scope.launch { searchHistoryStore.clear() } },
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        SearchSuggestionView(
          suggestions = uiState.suggestions,
          listState = listState,
          onTap = { item ->
            uiState.query = item
            submit()
          },
          modifier = Modifier.fillMaxSize(),
        )
      }
    } else {
      // 结果态:排序 chip + 结果网格。
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SearchSortOptions.forEach { opt ->
          FilterChip(
            selected = uiState.orderKey == opt.key,
            onClick = { selectOrder(opt.key) },
            label = { Text(stringResource(opt.titleRes)) },
          )
        }
      }

      Box(modifier = Modifier.fillMaxSize()) {
        // PullToRefreshLayout 提到 when 外,isRefreshing 顶层求值真值;刷新时 resultState→Loading 不再卸载容器,
        // 列表滚动位置与指示器保留,各状态内联为 grid item(照 MobileUserSpaceScreen 范式)。
        PullToRefreshLayout(
          isRefreshing = uiState.resultState is SearchResultState.Loading,
          onRefresh = { loadFirstPage(submitted, uiState.orderKey) },
          modifier = Modifier.fillMaxSize(),
        ) {
          LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            when (val s = uiState.resultState) {
              SearchResultState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                  modifier = Modifier.fillMaxWidth().padding(32.dp),
                  contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
              }
              SearchResultState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                  modifier = Modifier.fillMaxWidth().padding(32.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    text = stringResource(R.string.search_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                  )
                }
              }
              is SearchResultState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                  modifier = Modifier.fillMaxWidth().padding(32.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    text = stringResource(R.string.search_failed_with_message, s.message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                  )
                }
              }
              is SearchResultState.Success -> {
                gridItems(s.videos, key = { it.bvid }) { video ->
                  MobileVideoCard(video = video, onClick = onVideoSelected, onOpenOwner = onOpenOwner)
                }
                if (s.loadingMore) {
                  item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                      modifier = Modifier.fillMaxWidth().padding(16.dp),
                      contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SearchHistoryView(
  history: List<String>,
  listState: androidx.compose.foundation.lazy.LazyListState,
  onTap: (String) -> Unit,
  onClear: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (history.isEmpty()) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
      Text(
        text = stringResource(R.string.search_empty_prompt),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    return
  }
  LazyColumn(
    state = listState,
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = modifier,
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.search_history_title),
          style = MaterialTheme.typography.titleSmall,
        )
        TextButton(onClick = onClear) {
          Text(stringResource(R.string.search_history_clear))
        }
      }
    }
    items(history, key = { it }) { item ->
      Text(
        text = item,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onTap(item) }
          .padding(vertical = 10.dp, horizontal = 4.dp),
      )
    }
  }
}

@Composable
private fun SearchSuggestionView(
  suggestions: List<String>,
  listState: androidx.compose.foundation.lazy.LazyListState,
  onTap: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    state = listState,
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = modifier,
  ) {
    item {
      Text(
        text = stringResource(R.string.search_suggestions_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(vertical = 4.dp),
      )
    }
    if (suggestions.isEmpty()) {
      item {
        Text(
          text = stringResource(R.string.search_no_suggestions),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 8.dp),
        )
      }
    } else {
      items(suggestions, key = { it }) { item ->
        Text(
          text = item,
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 1,
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap(item) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        )
      }
    }
  }
}