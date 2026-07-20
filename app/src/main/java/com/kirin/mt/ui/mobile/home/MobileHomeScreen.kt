package com.kirin.mt.ui.mobile.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.mobile.common.DevelopingTipContent
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FirstPage = 1
// 与 API 实际每页量对齐(推荐/热门 ps=20、分区 request_cnt=20),且与 TV RecommendScreen 一致。
// 之前写 30 导致首屏 20 条后 endReached=20<30=true,loadNextPage 早退,下滑不翻页。
private const val PageSize = 20

/** 单分区加载状态。 */
private sealed interface MobileSectionState {
  data object Loading : MobileSectionState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : MobileSectionState
  data class Failed(val message: String) : MobileSectionState
}

@Stable
private class MobileHomeUiState {
  var sectionStates by mutableStateOf<Map<String, MobileSectionState>>(emptyMap())
    private set
  var loadedKeys by mutableStateOf<Set<String>>(emptySet())
    private set
  var refreshKeys by mutableStateOf<Map<String, Int>>(emptyMap())
    private set

  fun setState(key: String, state: MobileSectionState) {
    sectionStates = sectionStates + (key to state)
  }
  fun markLoaded(key: String) {
    loadedKeys = loadedKeys + key
  }
  fun nextRefreshKey(key: String): Int {
    val n = (refreshKeys[key] ?: 0) + 1
    refreshKeys = refreshKeys + (key to n)
    return n
  }
  fun refreshKey(key: String): Int = refreshKeys[key] ?: 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileHomeScreen(
  videoRepository: VideoRepository,
  enabledSections: List<HomeSection>,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
  refreshKey: Int = 0,
) {
  val sections = remember(enabledSections) { enabledSections.ifEmpty { listOf(HomeSection.Recommend) } }
  val uiState = remember { MobileHomeUiState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  // pagerState 是 tab 选择的唯一真相源:左右滑动内容区跟手平移到相邻分区,
  // tab 高亮跟随 currentPage,点击 tab 走 animateScrollToPage。
  val pagerState = rememberPagerState(pageCount = { sections.size }, initialPage = 0)

  // 每个分区一份独立滚动状态,切回某 tab 保留原滚动位置(替代原先单共享 gridState)。
  val gridStates = remember { mutableStateMapOf<String, LazyGridState>() }
  fun gridStateFor(key: String): LazyGridState = gridStates.getOrPut(key) { LazyGridState() }

  fun loadSection(section: HomeSection, forceRefresh: Boolean) {
    val key = section.key
    val hasLoaded = key in uiState.loadedKeys
    if (!forceRefresh && hasLoaded) {
      // 已加载且非强制刷新:直接复用,不重载(滑动预载 / 点已加载 tab 走此路径)。
      return
    }
    if (forceRefresh || key !in uiState.loadedKeys) {
      if (forceRefresh) uiState.nextRefreshKey(key)
      else uiState.refreshKey(key)
    }
    uiState.setState(key, MobileSectionState.Loading)
    scope.launch {
      val state = try {
        val idx = if (section == HomeSection.Recommend) uiState.refreshKey(key) else 0
        val videos = videoRepository.getHomeSectionVideos(
          section = section,
          page = FirstPage,
          idx = idx,
        )
        if (videos.isEmpty()) {
          MobileSectionState.Failed("暂无内容")
        } else {
          MobileSectionState.Success(
            videos = videos,
            nextPage = FirstPage + 1,
            loadingMore = false,
            endReached = videos.size < PageSize,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        MobileSectionState.Failed(e.message.orEmpty().ifEmpty { "加载失败" })
      }
      uiState.markLoaded(key)
      uiState.setState(key, state)
    }
  }

  fun loadNextPage(section: HomeSection) {
    val key = section.key
    val current = uiState.sectionStates[key] as? MobileSectionState.Success ?: return
    if (current.loadingMore || current.endReached) return
    uiState.setState(key, current.copy(loadingMore = true))
    scope.launch {
      val next = try {
        val idx = if (section == HomeSection.Recommend) {
          uiState.refreshKey(key) + current.nextPage - FirstPage
        } else 0
        val more = videoRepository.getHomeSectionVideos(
          section = section,
          page = current.nextPage,
          idx = idx,
        )
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
      uiState.setState(key, next)
    }
  }

  // 用 targetPage 驱动加载:拖动期间 targetPage 即切到目标页,开始预载,松手即就绪;
  // 静止时 targetPage==currentPage,首次组合发射 0 即覆盖首载。统一滑动/点击两条路径。
  LaunchedEffect(pagerState, sections) {
    snapshotFlow { pagerState.targetPage }
      .distinctUntilChanged()
      .collect { page -> if (page in sections.indices) loadSection(sections[page], forceRefresh = false) }
  }

  // 底栏重复点击"推荐":强制刷新当前页分区并把该页 grid 滚顶。
  LaunchedEffect(refreshKey) {
    if (refreshKey > 0) {
      val page = pagerState.currentPage
      if (page in sections.indices) {
        loadSection(sections[page], forceRefresh = true)
        gridStateFor(sections[page].key).scrollToItem(0)
      }
    }
  }

  Column(modifier = modifier.fillMaxSize()) {
    PrimaryScrollableTabRow(
      selectedTabIndex = pagerState.currentPage.coerceIn(0, sections.lastIndex),
      edgePadding = 0.dp,
    ) {
      sections.forEachIndexed { index, section ->
        Tab(
          selected = index == pagerState.currentPage,
          onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
          text = { Text(homeSectionTitle(context, section)) },
        )
      }
    }
    // 默认 pageNestedScrollConnection 已处理"垂直 LazyVerticalGrid 嵌在水平 pager"的滚动冲突,
    // 上下滑动翻列表、左右滑动切 tab 互不干扰;每页各自下拉刷新与近底翻页。
    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize(),
    ) { page ->
      val section = sections[page]
      HomeSectionPage(
        section = section,
        state = uiState.sectionStates[section.key],
        gridState = gridStateFor(section.key),
        onRefresh = { loadSection(section, forceRefresh = true) },
        onLoadNext = { loadNextPage(section) },
        onVideoSelected = onVideoSelected,
        onOpenOwner = onOpenOwner,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

/** 单个分区内容页:PullToRefreshLayout + LazyVerticalGrid,自带近底加载下一页。 */
@Composable
private fun HomeSectionPage(
  section: HomeSection,
  state: MobileSectionState?,
  gridState: LazyGridState,
  onRefresh: () -> Unit,
  onLoadNext: () -> Unit,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  // 滑动接近底部自动加载下一页(绑定本页 gridState)。
  LaunchedEffect(gridState) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd ->
        if (nearEnd) onLoadNext()
      }
  }

  // PullToRefreshLayout 提到 when 外,isRefreshing 顶层求值真值;刷新时 state→Loading 不再卸载容器,
  // 列表滚动位置与指示器保留,Loading/Failed 内联为 grid item(照 MobileUserSpaceScreen 范式)。
  PullToRefreshLayout(
    isRefreshing = state is MobileSectionState.Loading,
    onRefresh = onRefresh,
    modifier = modifier,
  ) {
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 160.dp),
      state = gridState,
      contentPadding = PaddingValues(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      when (state) {
        null, MobileSectionState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
          Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
          ) { CircularProgressIndicator() }
        }
        is MobileSectionState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
          DevelopingTipContent() // 复用占位,后续替换为可重试状态
        }
        is MobileSectionState.Success -> {
          items(state.videos, key = { it.bvid }) { video ->
            MobileVideoCard(video = video, onClick = onVideoSelected, onOpenOwner = onOpenOwner)
          }
          if (state.loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
                contentAlignment = Alignment.Center,
              ) { CircularProgressIndicator() }
            }
          }
        }
      }
    }
  }
}

/** 分区标题:复用已有 `home_section_<key>` 字符串资源。 */
private fun homeSectionTitle(context: android.content.Context, section: HomeSection): String {
  val resId = context.resources.getIdentifier(
    "home_section_${section.key}",
    "string",
    context.packageName,
  )
  return if (resId != 0) context.getString(resId) else section.key
}