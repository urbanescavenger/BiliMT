package com.kirin.mt.ui.mobile.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FirstPage = 1
private const val PageSize = 30

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

@Composable
fun MobileHomeScreen(
  videoRepository: VideoRepository,
  enabledSections: List<HomeSection>,
  onVideoSelected: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
  refreshKey: Int = 0,
) {
  val sections = remember(enabledSections) { enabledSections.ifEmpty { listOf(HomeSection.Recommend) } }
  var selectedKey by remember { mutableStateOf(sections.first().key) }
  val uiState = remember { MobileHomeUiState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  val selectedSection = sections.firstOrNull { it.key == selectedKey } ?: sections.first()

  fun loadSection(section: HomeSection, forceRefresh: Boolean) {
    val key = section.key
    val hasLoaded = key in uiState.loadedKeys
    if (!forceRefresh && hasLoaded) {
      selectedKey = key
      return
    }
    if (forceRefresh || key !in uiState.loadedKeys) {
      if (forceRefresh) uiState.nextRefreshKey(key)
      else uiState.refreshKey(key)
    }
    selectedKey = key
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

  // 首次进入加载选中分区
  LaunchedEffect(sections) {
    if (uiState.sectionStates[selectedSection.key] == null) {
      loadSection(selectedSection, forceRefresh = false)
    }
  }

  val gridState = rememberLazyGridState()
  // 底栏重复点击"推荐":bump refreshKey 触发当前分区强制刷新并滚顶
  LaunchedEffect(refreshKey) {
    if (refreshKey > 0) {
      loadSection(selectedSection, forceRefresh = true)
      gridState.scrollToItem(0)
    }
  }
  // 滑动接近底部自动加载下一页
  LaunchedEffect(selectedKey) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd ->
        if (nearEnd) loadNextPage(selectedSection)
      }
  }

  Column(modifier = modifier.fillMaxSize()) {
    PrimaryScrollableTabRow(
      selectedTabIndex = sections.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0),
      edgePadding = 0.dp,
    ) {
      sections.forEach { section ->
        Tab(
          selected = section.key == selectedKey,
          onClick = { loadSection(section, forceRefresh = true) },
          text = { Text(homeSectionTitle(context, section)) },
        )
      }
    }
    Box(modifier = Modifier.fillMaxSize()) {
      when (val state = uiState.sectionStates[selectedSection.key]) {
        null, MobileSectionState.Loading -> CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
        )
        is MobileSectionState.Failed -> DevelopingTipContent() // 复用占位,后续替换为可重试状态
        is MobileSectionState.Success -> LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 160.dp),
          state = gridState,
          contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          items(state.videos, key = { it.bvid }) { video ->
            MobileVideoCard(video = video, onClick = onVideoSelected)
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