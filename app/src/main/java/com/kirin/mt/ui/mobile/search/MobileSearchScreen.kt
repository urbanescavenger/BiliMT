package com.kirin.mt.ui.mobile.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.image.BiliImageSizing
import com.kirin.mt.core.image.buildOwnerAvatarRequest
import com.kirin.mt.core.model.SourceBili
import com.kirin.mt.core.model.SourceTvbox
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.UserSummary
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.youtube.YoutubeSearchParams
import com.kirin.mt.core.youtube.toUserSummary
import com.kirin.mt.core.storage.SearchHistoryStore
import com.kirin.mt.ui.common.appendUniqueByMid
import com.kirin.mt.ui.common.dedupKey
import com.kirin.mt.ui.i18n.currentUiLocale
import com.kirin.mt.ui.i18n.formatCompactCount
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FirstPage = 1
private const val PageSize = 20
private const val SearchSuggestionDebounceMs = 250L

/** 搜索类型:视频 / 番剧(仅 B 站) / UP主(频道)。 */
private const val SearchTypeVideo = "video"
private const val SearchTypeUser = "user"
private const val SearchTypeBangumi = "media_bangumi"

/** 搜索结果分页状态。 */
private sealed interface SearchResultState {
  data object Loading : SearchResultState
  data object Empty : SearchResultState
  data class Failed(val message: String) : SearchResultState
  data class Success(
    val videos: List<VideoSummary> = emptyList(),
    /** 用户(UP主/频道)搜索结果;type==user 时填充,否则恒空。 */
    val users: List<UserSummary> = emptyList(),
    val nextPage: Int,
    /** YouTube 来源的续页 token；B站来源恒为 null。 */
    val continuation: String? = null,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : SearchResultState
}

private data class SearchSortOption(
  val key: String,
  val titleRes: Int,
)

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

/** 按来源返回排序选项(B站/YouTube 各一套;影视库(TVBox)无排序,空集=隐藏排序 chip)。 */
private fun sortOptionsFor(source: String): List<SearchSortOption> =
  when {
    source == SourceTvbox -> emptyList()
    source == SourceYoutube -> YoutubeSearchSortOptions
    else -> BiliSearchSortOptions
  }

/** 各来源默认排序(综合)的 key,切换来源时用于重置选中项;影视库 无排序,空 key。 */
private fun defaultOrderKey(source: String): String = sortOptionsFor(source).firstOrNull()?.key.orEmpty()

/** 移动端结果页类型 tab(对齐官方 综合/番剧/UP主 并排):视频 tab 标「综合」。key 即类型 key。 */
private fun typeTabsFor(source: String): List<SearchSortOption> =
  if (source == SourceYoutube) {
    listOf(
      SearchSortOption(SearchTypeVideo, R.string.search_type_comprehensive),
      SearchSortOption(SearchTypeUser, R.string.search_type_user_youtube),
    )
  } else {
    listOf(
      SearchSortOption(SearchTypeVideo, R.string.search_type_comprehensive),
      SearchSortOption(SearchTypeBangumi, R.string.search_type_bangumi),
      SearchSortOption(SearchTypeUser, R.string.search_type_user_bili),
    )
  }

/** 筛选面板·发布时间回看秒数(0=不限),对齐官方筛选项。 */
private val SearchPubtimeOptions = listOf(
  0L to R.string.search_filter_any,
  86400L to R.string.search_filter_time_day,
  604800L to R.string.search_filter_time_week,
  15552000L to R.string.search_filter_time_half_year,
)

/** 筛选面板·内容时长档位(0=不限,1-4 即接口 duration 参数),对齐官方筛选项。 */
private val SearchDurationOptions = listOf(
  0 to R.string.search_filter_any,
  1 to R.string.search_filter_duration_10,
  2 to R.string.search_filter_duration_30,
  3 to R.string.search_filter_duration_60,
  4 to R.string.search_filter_duration_60p,
)

/** 空结果文案按类型区分:UP主/番剧/视频。 */
private fun emptyMessageResFor(searchType: String): Int = when (searchType) {
  SearchTypeUser -> R.string.search_empty_user
  SearchTypeBangumi -> R.string.search_empty_bangumi
  else -> R.string.search_empty
}

@Stable
private class MobileSearchUiState {
  var query by mutableStateOf("")
  var submittedQuery by mutableStateOf<String?>(null)
  var source by mutableStateOf(SourceBili)
  var searchType by mutableStateOf(SearchTypeVideo)
  var orderKey by mutableStateOf(BiliSearchSortOptions.first().key)
  // 筛选面板(仅 B 站视频搜索消费):时长档位 1-4(0=不限)、发布时间回看秒数(0=不限)。
  var filterDuration by mutableStateOf(0)
  var filterPubtimeSeconds by mutableStateOf(0L)
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

  /** 切换来源。若已有搜索结果,重置结果态以便重搜。 */
  fun selectSource(newSource: String) {
    if (source == newSource) return
    source = newSource
    // 番剧类型仅 B 站源提供,切到其它源回退视频类型(YouTube 类型循环只有 视频⇄频道)。
    if (searchType == SearchTypeBangumi) {
      searchType = SearchTypeVideo
    }
    // 排序 key 与来源耦合(B站 totalrank/click…,YouTube params 串),切源重置为该源默认「综合」。
    orderKey = defaultOrderKey(newSource)
    // 筛选(时长/发布时间)仅 B 站视频搜索有,切源一并重置。
    filterDuration = 0
    filterPubtimeSeconds = 0L
    resultState = SearchResultState.Loading
  }

  /** 切换搜索类型(视频/UP主)。若已有搜索结果,重置结果态以便重搜。 */
  fun selectType(newType: String) {
    if (searchType == newType) return
    searchType = newType
    resultState = SearchResultState.Loading
  }

  /** 筛选发布时间区间起点:回看 N 秒 → now-N(unix 秒);不限返回 0(不传参)。 */
  fun pubtimeBeginSeconds(): Long {
    if (filterPubtimeSeconds <= 0L) return 0L
    return System.currentTimeMillis() / 1000 - filterPubtimeSeconds
  }

  /** 筛选发布时间区间终点:now(unix 秒);不限返回 0(不传参)。 */
  fun pubtimeEndSeconds(): Long {
    return if (filterPubtimeSeconds > 0L) System.currentTimeMillis() / 1000 else 0L
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
  onLongPress: ((VideoSummary) -> Unit)? = null,
  onUserSelected: (UserSummary) -> Unit = {},
) {
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current
  val uiState = remember { MobileSearchUiState() }
  val history by searchHistoryStore.history.collectAsState(initial = emptyList())
  val gridState = rememberLazyGridState()
  val listState = rememberLazyListState()
  // 请求去重/取消:新首屏加载或翻页前取消在途请求,避免快速切 source/重复提交/重试
  // 并发重复请求竞态写 resultState(对齐 LibreTube mapLatest 取消旧请求)。
  var searchJob by remember { mutableStateOf<Job?>(null) }

  fun loadFirstPage(query: String, order: String) {
    searchJob?.cancel()
    uiState.resultState = SearchResultState.Loading
    searchJob = scope.launch {
      val state = try {
        if (uiState.source == SourceTvbox) {
          // 影视库:聚合搜索单发全量,无排序/无翻页;类型/排序 chip 均隐藏。
          val videos = videoRepository.tvboxSearch(keyword = query)
          if (videos.isEmpty()) SearchResultState.Empty
          else SearchResultState.Success(
            videos = videos,
            nextPage = FirstPage + 1,
            loadingMore = false,
            endReached = true,
          )
        } else if (uiState.searchType == SearchTypeUser) {
          // UP主/频道搜索:无排序,忽略 order。
          if (uiState.source == SourceYoutube) {
            val page = videoRepository.youtubeSearchChannels(query = query)
            if (page.items.isEmpty()) SearchResultState.Empty
            else SearchResultState.Success(
              users = page.items.map { it.toUserSummary() },
              nextPage = FirstPage + 1,
              continuation = page.continuation,
              loadingMore = false,
              endReached = page.continuation == null,
            )
          } else {
            val users = videoRepository.searchUsers(keyword = query, page = FirstPage)
            if (users.isEmpty()) SearchResultState.Empty
            else SearchResultState.Success(
              users = users,
              nextPage = FirstPage + 1,
              loadingMore = false,
              endReached = users.size < PageSize,
            )
          }
        } else if (uiState.searchType == SearchTypeBangumi) {
          // 番剧搜索:无排序,结果卡带 seasonId,点击进 PGC 季详情(MobileApp 拦截)。
          val seasons = videoRepository.searchBangumi(keyword = query, page = FirstPage)
          if (seasons.isEmpty()) SearchResultState.Empty
          else SearchResultState.Success(
            videos = seasons,
            nextPage = FirstPage + 1,
            loadingMore = false,
            endReached = seasons.size < PageSize,
          )
        } else if (uiState.source == SourceYoutube) {
          val page = videoRepository.youtubeSearch(query = query, params = order)
          if (page.items.isEmpty()) SearchResultState.Empty
          else SearchResultState.Success(
            videos = page.items,
            nextPage = FirstPage + 1,
            continuation = page.continuation,
            loadingMore = false,
            endReached = page.continuation == null,
          )
        } else {
          val videos = videoRepository.searchVideos(
            keyword = query,
            page = FirstPage,
            order = order,
            duration = uiState.filterDuration,
            pubtimeBeginSeconds = uiState.pubtimeBeginSeconds(),
            pubtimeEndSeconds = uiState.pubtimeEndSeconds(),
          )
          if (videos.isEmpty()) SearchResultState.Empty
          else SearchResultState.Success(
            videos = videos,
            nextPage = FirstPage + 1,
            loadingMore = false,
            endReached = videos.size < PageSize,
          )
        }
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

  /** 筛选面板选择(时长/发布时间,仅 B 站视频搜索消费):更新状态并重搜首页。 */
  fun selectFilter(newDuration: Int = uiState.filterDuration, newPubtimeSeconds: Long = uiState.filterPubtimeSeconds) {
    if (newDuration == uiState.filterDuration && newPubtimeSeconds == uiState.filterPubtimeSeconds) return
    uiState.filterDuration = newDuration
    uiState.filterPubtimeSeconds = newPubtimeSeconds
    val q = uiState.submittedQuery ?: return
    loadFirstPage(q, uiState.orderKey)
  }

  fun loadNextPage() {
    val current = uiState.resultState as? SearchResultState.Success ?: return
    if (current.loadingMore || current.endReached) return
    val q = uiState.submittedQuery ?: return
    uiState.resultState = current.copy(loadingMore = true)
    searchJob?.cancel()
    searchJob = scope.launch {
      val next = try {
        if (uiState.searchType == SearchTypeUser) {
          // UP主/频道搜索:无排序,忽略 order。
          val moreUsers: List<UserSummary>
          val nextContinuation: String?
          if (uiState.source == SourceYoutube) {
            val page = videoRepository.youtubeSearchChannels(query = q, continuation = current.continuation)
            moreUsers = page.items.map { it.toUserSummary() }
            nextContinuation = page.continuation
          } else {
            moreUsers = videoRepository.searchUsers(keyword = q, page = current.nextPage)
            nextContinuation = null
          }
          val mergedUsers = current.users.appendUniqueByMid(moreUsers)
          current.copy(
            users = mergedUsers,
            nextPage = current.nextPage + 1,
            continuation = nextContinuation,
            loadingMore = false,
            endReached = if (uiState.source == SourceYoutube) {
              nextContinuation == null || mergedUsers.size == current.users.size
            } else {
              moreUsers.size < PageSize || mergedUsers.size == current.users.size
            },
          )
        } else if (uiState.searchType == SearchTypeBangumi) {
          // 番剧搜索翻页:无排序。
          val more = videoRepository.searchBangumi(keyword = q, page = current.nextPage)
          val merged = (current.videos + more).distinctBy { it.bvid }
          current.copy(
            videos = merged,
            nextPage = current.nextPage + 1,
            continuation = null,
            loadingMore = false,
            endReached = more.size < PageSize || merged.size == current.videos.size,
          )
        } else {
          val more: List<VideoSummary>
          val nextContinuation: String?
          if (uiState.source == SourceYoutube) {
            val page = videoRepository.youtubeSearch(query = q, continuation = current.continuation)
            more = page.items
            nextContinuation = page.continuation
          } else {
            more = videoRepository.searchVideos(
              keyword = q,
              page = current.nextPage,
              order = uiState.orderKey,
              duration = uiState.filterDuration,
              pubtimeBeginSeconds = uiState.pubtimeBeginSeconds(),
              pubtimeEndSeconds = uiState.pubtimeEndSeconds(),
            )
            nextContinuation = null
          }
          val merged = (current.videos + more).distinctBy { it.bvid }
          current.copy(
            videos = merged,
            nextPage = current.nextPage + 1,
            continuation = nextContinuation,
            loadingMore = false,
            endReached = if (uiState.source == SourceYoutube) {
              nextContinuation == null || merged.size == current.videos.size
            } else {
              more.size < PageSize || merged.size == current.videos.size
            },
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        current.copy(loadingMore = false)
      }
      uiState.resultState = next
    }
  }

  // 输入态下随输入防抖拉联想;提交态不拉。影视库(TVBox)无联想(联想是 B站 sug 接口),恒空。
  LaunchedEffect(uiState.query, uiState.submittedQuery) {
    if (uiState.submittedQuery != null) return@LaunchedEffect
    if (uiState.source == SourceTvbox) {
      uiState.suggestions = emptyList()
      return@LaunchedEffect
    }
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

  // 结果态切搜索类型(视频/UP主)或来源时重搜。selectType/selectSource 只重置 resultState 为 Loading,
  // 由本 effect 在已有提交查询时重新拉首屏(对齐 TV 端 LaunchedEffect 含 searchType 的键)。
  LaunchedEffect(uiState.searchType, uiState.source) {
    val q = uiState.submittedQuery ?: return@LaunchedEffect
    loadFirstPage(q, uiState.orderKey)
  }

  // 结果态系统返回键回到输入态。播放器开着时,MobileApp 中播放器覆盖层的 BackHandler
  // 组合在更后位、dispatcher 优先级更高,会先关播放器,不会与本 handler 冲突。
  BackHandler(enabled = uiState.submittedQuery != null) {
    uiState.backToInput()
  }

  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      listOf(
        SourceBili to stringResource(R.string.search_source_bili),
        SourceYoutube to stringResource(R.string.search_source_youtube),
        SourceTvbox to stringResource(R.string.search_source_tvbox),
      ).forEach { (value, label) ->
        FilterChip(
          selected = uiState.source == value,
          onClick = { uiState.selectSource(value) },
          label = { Text(label) },
        )
      }
    }
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
      // 结果态:类型 tab 行(对齐官方:综合/番剧/UP主 并排,选中粉色+下划线) + 行尾筛选漏斗
      // (仅视频类型显示;底部弹层含 排序方式 + 发布时间/内容时长(仅 B 站))。
      // 影视库(TVBox)源 tab 行整行隐藏(无类型/无筛选概念)。
      if (uiState.source != SourceTvbox) {
        var showFilterSheet by remember { mutableStateOf(false) }
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(24.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          typeTabsFor(uiState.source).forEach { tab ->
            val selected = uiState.searchType == tab.key
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.clickable { uiState.selectType(tab.key) },
            ) {
              Text(
                text = stringResource(tab.titleRes),
                color = if (selected) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
              )
              Box(
                modifier = Modifier
                  .padding(top = 2.dp)
                  .width(20.dp)
                  .height(3.dp)
                  .clip(RoundedCornerShape(2.dp))
                  .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
              )
            }
          }
          Spacer(modifier = Modifier.weight(1f))
          if (uiState.searchType == SearchTypeVideo) {
            IconButton(onClick = { showFilterSheet = true }) {
              Icon(
                painter = painterResource(R.drawable.ic_search_filter),
                contentDescription = stringResource(R.string.search_filter),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        if (showFilterSheet) {
          SearchFilterSheet(
            source = uiState.source,
            orderKey = uiState.orderKey,
            pubtimeSeconds = uiState.filterPubtimeSeconds,
            duration = uiState.filterDuration,
            onDismiss = { showFilterSheet = false },
            onSortSelected = { key -> selectOrder(key) },
            onPubtimeSelected = { seconds -> selectFilter(newPubtimeSeconds = seconds) },
            onDurationSelected = { value -> selectFilter(newDuration = value) },
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
                    text = stringResource(emptyMessageResFor(uiState.searchType)),
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
                if (uiState.searchType == SearchTypeUser) {
                  s.users.forEach { user ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = user.dedupKey()) {
                      MobileUserResultRow(user = user, onClick = { onUserSelected(user) })
                    }
                  }
                } else {
                  gridItems(s.videos, key = { it.bvid }) { video ->
                    MobileVideoCard(video = video, onClick = onVideoSelected, onOpenOwner = onOpenOwner, onLongPress = onLongPress)
                  }
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

/**
 * 筛选底部弹层(对齐官方筛选项):排序方式(两源各一套)+ 发布时间/内容时长(仅 B 站,
 * YouTube 无对应参数)。选中即生效并重搜,弹层保持打开可连续调整。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchFilterSheet(
  source: String,
  orderKey: String,
  pubtimeSeconds: Long,
  duration: Int,
  onDismiss: () -> Unit,
  onSortSelected: (String) -> Unit,
  onPubtimeSelected: (Long) -> Unit,
  onDurationSelected: (Int) -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(bottom = 24.dp),
    ) {
      FilterSectionTitle(stringResource(R.string.search_filter_sort))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        sortOptionsFor(source).forEach { opt ->
          FilterChip(
            selected = orderKey == opt.key,
            onClick = { onSortSelected(opt.key) },
            label = { Text(stringResource(opt.titleRes)) },
          )
        }
      }
      if (source != SourceYoutube) {
        FilterSectionTitle(stringResource(R.string.search_filter_pubtime))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SearchPubtimeOptions.forEach { (seconds, labelRes) ->
            FilterChip(
              selected = pubtimeSeconds == seconds,
              onClick = { onPubtimeSelected(seconds) },
              label = { Text(stringResource(labelRes)) },
            )
          }
        }
        FilterSectionTitle(stringResource(R.string.search_filter_duration))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SearchDurationOptions.forEach { (value, labelRes) ->
            FilterChip(
              selected = duration == value,
              onClick = { onDurationSelected(value) },
              label = { Text(stringResource(labelRes)) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun FilterSectionTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
  )
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

/** 用户(UP主/频道)搜索结果行:头像 + 名字 + 等级/认证 + 粉丝/视频数 + 签名。整行可点。 */
@Composable
private fun MobileUserResultRow(
  user: UserSummary,
  onClick: () -> Unit,
) {
  val context = LocalContext.current
  val locale = currentUiLocale()
  val avatarSizePx = BiliImageSizing.StandardOwnerAvatarSizePx
  val fallbackPainter = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
  val avatarRequest = remember(context, user.face, avatarSizePx) {
    buildOwnerAvatarRequest(context = context, url = user.face, sizePx = avatarSizePx)
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      if (user.face.isNotBlank()) {
        AsyncImage(
          model = avatarRequest,
          contentDescription = user.name,
          contentScale = ContentScale.Crop,
          placeholder = fallbackPainter,
          error = fallbackPainter,
          modifier = Modifier.size(48.dp).clip(CircleShape),
        )
      } else {
        Icon(
          painter = painterResource(R.drawable.ic_nav_account),
          contentDescription = user.name,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(28.dp),
        )
      }
    }
    Column(
      modifier = Modifier.weight(1f).padding(start = 12.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = user.name.ifBlank { stringResource(R.string.player_panel_unknown_up) },
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (user.level > 0) {
          Text(
            text = stringResource(R.string.up_space_level, user.level),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        if (user.officialVerify.isNotBlank()) {
          Text(
            text = user.officialVerify,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (user.fans > 0) {
          Text(
            text = stringResource(R.string.search_user_fans, formatCompactCount(user.fans, locale)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (user.videos > 0) {
          Text(
            text = stringResource(R.string.search_user_videos, formatCompactCount(user.videos, locale)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (user.sign.isNotBlank()) {
        Text(
          text = user.sign,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}