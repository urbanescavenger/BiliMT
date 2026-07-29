package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.LiveArea
import com.kirin.mt.core.model.LiveAreaGroup
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.LiveRepository
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import com.kirin.mt.ui.player.toVideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FirstPage = 1
private const val SkeletonCount = 6
private const val RecommendTabKey = "recommend"

private sealed interface MobileLiveSectionState {
  data object Loading : MobileLiveSectionState
  data object Empty : MobileLiveSectionState
  data class Failed(val message: String) : MobileLiveSectionState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: String? = null,
  ) : MobileLiveSectionState
}

@Stable
private class MobileLiveUiState {
  /** 直播分区树。 */
  var areaGroups by mutableStateOf<List<LiveAreaGroup>>(emptyList())
  /** 分区列表加载状态:Loading 仅在首次;Failed 时展示全屏重试。 */
  var areaListState by mutableStateOf<MobileLiveSectionState>(MobileLiveSectionState.Loading)
  /** 每个 tab 的内容状态,key 为 "recommend" 或 "${parentId}-${areaId}"。 */
  var sectionStates by mutableStateOf<Map<String, MobileLiveSectionState>>(emptyMap())
  var loadedKeys by mutableStateOf<Set<String>>(emptySet())
  var refreshKeys by mutableStateOf<Map<String, Int>>(emptyMap())

  fun setState(key: String, state: MobileLiveSectionState) {
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

private fun LiveArea.tabKey(): String = "${parentId}-${id}"

/**
 * 移动端直播列表:顶部可滚动 tab 行("推荐" + 直播分区) + HorizontalPager 左右切换,
 * 镜像 [com.kirin.mt.ui.mobile.home.MobileHomeScreen] 的分区范式。
 * 每个 tab 独立 PullToRefreshLayout + LazyVerticalGrid + 近底翻页。
 * 卡片复用 [MobileVideoCard],点击走 [onVideoSelected] → 壳层挂载 [LivePlayerScreen]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileLiveScreen(
  liveRepository: LiveRepository,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState = remember { MobileLiveUiState() }
  val scope = rememberCoroutineScope()

  // 首次进入加载分区树;成功后默认显示"推荐"tab(仍在 pager 第 0 页)。
  LaunchedEffect(liveRepository) {
    if (uiState.areaGroups.isEmpty() && uiState.areaListState is MobileLiveSectionState.Loading) {
      val groups = try {
        liveRepository.getAreaList()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        uiState.areaListState = MobileLiveSectionState.Failed(
          error.message.orEmpty().ifBlank { "分区加载失败" },
        )
        return@LaunchedEffect
      }
      uiState.areaGroups = groups
      uiState.areaListState = if (groups.isEmpty()) MobileLiveSectionState.Empty else MobileLiveSectionState.Success(
        videos = emptyList(),
        nextPage = FirstPage,
        loadingMore = false,
        endReached = true,
      )
    }
  }

  val tabs = remember(uiState.areaGroups) {
    buildList {
      add(LiveTab.Recommend)
      uiState.areaGroups.forEach { group ->
        group.areas.forEach { area ->
          add(LiveTab.Area(groupName = group.name, area = area))
        }
      }
    }
  }

  val pagerState = rememberPagerState(pageCount = { tabs.size }, initialPage = 0)

  fun loadSection(key: String, forceRefresh: Boolean) {
    val hasLoaded = key in uiState.loadedKeys
    if (!forceRefresh && hasLoaded) return
    if (forceRefresh || key !in uiState.loadedKeys) {
      if (forceRefresh) uiState.nextRefreshKey(key)
    }
    uiState.setState(key, MobileLiveSectionState.Loading)
    scope.launch {
      val state = try {
        val page = if (key == RecommendTabKey) {
          liveRepository.getLiveList(FirstPage)
        } else {
          val area = tabs.find { it.key == key }?.areaOrNull()
          if (area == null) {
            MobileListPage.empty()
          } else {
            liveRepository.getLiveListByArea(area.parentId, area.id, FirstPage)
          }
        }
        when {
          page.items.isEmpty() -> MobileLiveSectionState.Empty
          else -> MobileLiveSectionState.Success(
            videos = page.items.map { it.toVideoSummary() },
            nextPage = page.nextPage,
            loadingMore = false,
            endReached = !page.hasMore,
          )
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        MobileLiveSectionState.Failed(error.message.orEmpty().ifBlank { "加载失败" })
      }
      uiState.markLoaded(key)
      uiState.setState(key, state)
    }
  }

  fun loadNextPage(key: String) {
    val current = uiState.sectionStates[key] as? MobileLiveSectionState.Success ?: return
    if (current.loadingMore || current.endReached) return
    uiState.setState(key, current.copy(loadingMore = true, loadMoreError = null))
    scope.launch {
      val next = try {
        val page = if (key == RecommendTabKey) {
          liveRepository.getLiveList(current.nextPage)
        } else {
          val area = tabs.find { it.key == key }?.areaOrNull()
          if (area == null) {
            MobileListPage.empty()
          } else {
            liveRepository.getLiveListByArea(area.parentId, area.id, current.nextPage)
          }
        }
        val known = current.videos.map { it.liveRoomId }.toMutableSet()
        val merged = current.videos + page.items
          .map { it.toVideoSummary() }
          .filter { it.liveRoomId > 0L && known.add(it.liveRoomId) }
        current.copy(
          videos = merged,
          nextPage = page.nextPage,
          loadingMore = false,
          endReached = !page.hasMore || merged.size == current.videos.size,
          loadMoreError = null,
        )
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        current.copy(loadingMore = false, loadMoreError = error.message.orEmpty().ifBlank { "加载更多失败" })
      }
      uiState.setState(key, next)
    }
  }

  // 切换 tab / 首次进入时预载当前页。
  LaunchedEffect(pagerState, tabs) {
    snapshotFlow { pagerState.targetPage }
      .distinctUntilChanged()
      .collect { page ->
        if (page in tabs.indices) {
          loadSection(tabs[page].key, forceRefresh = false)
        }
      }
  }

  when (val areaState = uiState.areaListState) {
    MobileLiveSectionState.Loading -> Box(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator() }

    MobileLiveSectionState.Empty -> Box(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) { Text(stringResource(R.string.live_empty)) }

    is MobileLiveSectionState.Failed -> Box(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(R.string.live_failed_with_message, areaState.message),
          color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = {
          uiState.areaListState = MobileLiveSectionState.Loading
          scope.launch {
            val groups = try {
              liveRepository.getAreaList()
            } catch (error: CancellationException) {
              throw error
            } catch (error: Exception) {
              uiState.areaListState = MobileLiveSectionState.Failed(
                error.message.orEmpty().ifBlank { "分区加载失败" },
              )
              return@launch
            }
            uiState.areaGroups = groups
            uiState.areaListState = if (groups.isEmpty()) {
              MobileLiveSectionState.Empty
            } else {
              MobileLiveSectionState.Success(
                videos = emptyList(),
                nextPage = FirstPage,
                loadingMore = false,
                endReached = true,
              )
            }
          }
        }) {
          Text(stringResource(R.string.live_retry))
        }
      }
    }

    else -> Column(modifier = modifier.fillMaxSize()) {
      PrimaryScrollableTabRow(
        selectedTabIndex = pagerState.currentPage.coerceIn(0, tabs.lastIndex),
        edgePadding = 0.dp,
      ) {
        tabs.forEachIndexed { index, tab ->
          Tab(
            selected = index == pagerState.currentPage,
            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
            text = {
              Text(
                text = tab.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
          )
        }
      }

      HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
      ) { page ->
        val tab = tabs[page]
        val gridStates = remember { mutableStateMapOf<String, LazyGridState>() }
        val gridState = gridStates.getOrPut(tab.key) { rememberLazyGridState() }
        MobileLiveSectionPage(
          title = tab.label,
          state = uiState.sectionStates[tab.key],
          gridState = gridState,
          onRefresh = { loadSection(tab.key, forceRefresh = true) },
          onLoadNext = { loadNextPage(tab.key) },
          onVideoSelected = onVideoSelected,
          onOpenOwner = onOpenOwner,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}

/** 单个直播 tab 内容页:PullToRefreshLayout + LazyVerticalGrid + 近底翻页。 */
@Composable
private fun MobileLiveSectionPage(
  title: String,
  state: MobileLiveSectionState?,
  gridState: LazyGridState,
  onRefresh: () -> Unit,
  onLoadNext: () -> Unit,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  LaunchedEffect(gridState) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) onLoadNext() }
  }

  PullToRefreshLayout(
    isRefreshing = state is MobileLiveSectionState.Loading,
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
      // 顶部标题条(失败态独占居中时除外)。
      if (state !is MobileLiveSectionState.Failed) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
          MobileLiveHeader(title = title)
        }
      }

      when (state) {
        null, MobileLiveSectionState.Loading -> {
          items(SkeletonCount) { LiveSkeletonCard() }
        }
        MobileLiveSectionState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
          Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
          ) { Text(stringResource(R.string.live_empty)) }
        }
        is MobileLiveSectionState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = stringResource(R.string.live_failed_with_message, state.message),
              color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRefresh) {
              Text(stringResource(R.string.live_retry))
            }
          }
        }
        is MobileLiveSectionState.Success -> {
          items(state.videos, key = { it.liveRoomId }) { video ->
            MobileVideoCard(video = video, onClick = onVideoSelected, onOpenOwner = onOpenOwner)
          }
          if (state.loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
              ) { CircularProgressIndicator() }
            }
          } else if (state.loadMoreError != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { onLoadNext() }
                  .padding(16.dp),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = stringResource(R.string.live_load_more_failed),
                  color = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MobileLiveHeader(title: String) {
  Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
    Text(
      text = stringResource(R.string.live_title),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = title,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 骨架占位卡片:圆角封面占位 + 两行文字占位。 */
@Composable
private fun LiveSkeletonCard() {
  val placeholder = MaterialTheme.colorScheme.surfaceVariant
  Column(modifier = Modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 10f)
        .clip(RoundedCornerShape(12.dp))
        .background(placeholder),
    )
    Spacer(modifier = Modifier.height(6.dp))
    Box(
      modifier = Modifier
        .fillMaxWidth(0.9f)
        .height(14.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(placeholder),
    )
    Spacer(modifier = Modifier.height(4.dp))
    Box(
      modifier = Modifier
        .fillMaxWidth(0.5f)
        .height(12.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(placeholder),
    )
  }
}

/** 单个 tab 的标识与显示文本。 */
private sealed interface LiveTab {
  val key: String
  val label: String

  data object Recommend : LiveTab {
    override val key = RecommendTabKey
    override val label = "推荐"
  }

  data class Area(
    val groupName: String,
    val area: LiveArea,
  ) : LiveTab {
    override val key: String = area.tabKey()
    override val label: String = area.name
  }
}

private fun LiveTab.areaOrNull(): LiveArea? = when (this) {
  is LiveTab.Area -> area
  else -> null
}

/** 空分页结果,用于找不到对应分区时的安全兜底。 */
private data object MobileListPage {
  fun empty() = com.kirin.mt.core.model.LiveListPage(
    items = emptyList(),
    nextPage = FirstPage,
    hasMore = false,
  )
}
