package com.kirin.mt.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.kirin.mt.R
import com.kirin.mt.core.model.LiveAreaGroup
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.IptvRepository
import com.kirin.mt.core.network.LiveRepository
import com.kirin.mt.ui.common.BiliCapsuleTabRow
import com.kirin.mt.ui.common.BiliPillTab
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.common.VideoGridSkeleton
import com.kirin.mt.ui.common.focusRestoreKey
import com.kirin.mt.ui.common.resolveFocusIndex
import com.kirin.mt.ui.home.TvVideoGrid
import com.kirin.mt.ui.player.toVideoSummary
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val FirstPage = 1

/** 直播分区 tab:推荐 + 各一级分区(网游/手游/娱乐/电台/...)。镜像移动端父分区做 tab 的策略。 */
internal sealed interface LiveSection {
  val key: String
  val label: String

  data object Recommend : LiveSection {
    override val key = "recommend"
    override val label = "推荐"
  }

  /** IPTV 频道 tab:读配置的 m3u 源,无分页(一次拉全量)。 */
  data object Iptv : LiveSection {
    override val key = "iptv"
    override val label = "IPTV"
  }

  data class Area(val group: LiveAreaGroup) : LiveSection {
    override val key = "area-${group.id}"
    override val label = group.name
  }
}

/** 单个 tab 的加载状态。Success 内联分页字段。 */
internal sealed interface LiveState {
  data object Loading : LiveState
  data object Empty : LiveState
  data class Failed(val message: String) : LiveState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: String,
  ) : LiveState
}

@Stable
internal class LiveUiState {
  /** 直播分区树(构建 tab 用)。 */
  var areaGroups by mutableStateOf<List<LiveAreaGroup>>(emptyList())
  var selectedSectionKey by mutableStateOf("")
  var activeSectionKey by mutableStateOf("")
  var sectionStates by mutableStateOf<Map<String, LiveState>>(emptyMap())
  var loadedSectionKeys by mutableStateOf(emptySet<String>())
  var sectionRefreshKeys by mutableStateOf<Map<String, Int>>(emptyMap())
  var loadRequest by mutableStateOf<LiveLoadRequest?>(null)
  var nextLoadRequestId by mutableIntStateOf(0)
  var handledManualRefreshKey by mutableIntStateOf(0)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
  var focusFirstItemKey by mutableIntStateOf(0)
}

internal data class LiveLoadRequest(
  val id: Int,
  val sectionKey: String,
  val refreshKey: Int,
)

/**
 * TV 直播列表(分区 tab + 单网格)。镜像 [com.kirin.mt.ui.home.RecommendScreen] 的结构:
 * 顶部 capsule tab 行("推荐" + 一级直播分区) + 下方 [TvVideoGrid] 的 D-pad 焦点/分页机制。
 * tab = 父分区(而非 438 个叶子子分区,游戏类 388 个会霸屏);每个父分区 tab 按
 * `parent_area_id` + `area_id=0` 拉该大类下所有房间。卡片由 [LiveRoom.toVideoSummary] 映射,
 * 点击走 [onVideoSelected] → 壳层据 [VideoSummary.liveRoomId] 挂载 [com.kirin.mt.ui.player.LivePlayerScreen]。
 */
@Composable
internal fun LiveScreen(
  liveRepository: LiveRepository,
  iptvRepository: IptvRepository,
  uiState: LiveUiState,
  firstItemFocusRequester: FocusRequester,
  tabFocusRequester: FocusRequester,
  manualRefreshKey: Int,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  requestInitialFocus: Boolean,
  onInitialFocusRequested: () -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
) {
  val coroutineScope = rememberCoroutineScope()

  // 首次进入加载分区树(构建 tab);失败则仅保留"推荐"tab,推荐流仍可用。
  LaunchedEffect(liveRepository) {
    if (uiState.areaGroups.isEmpty()) {
      uiState.areaGroups = runCatching { liveRepository.getAreaList() }.getOrDefault(emptyList())
    }
  }

  val sections = remember(uiState.areaGroups) {
    buildList {
      add(LiveSection.Recommend)
      add(LiveSection.Iptv)
      uiState.areaGroups.forEach { add(LiveSection.Area(it)) }
    }
  }
  val selectedSectionKey = uiState.selectedSectionKey
    .takeIf { key -> sections.any { it.key == key } }
    ?: sections.first().key
  val activeSectionKey = uiState.activeSectionKey
    .takeIf { key -> sections.any { it.key == key } }
    ?: selectedSectionKey
  val activeSection = sections.firstOrNull { it.key == activeSectionKey }
    ?: sections.first()
  val selectedSection = sections.firstOrNull { it.key == selectedSectionKey }
    ?: sections.first()
  val selectedSectionFocusRequester = tabFocusRequester
  val state = uiState.sectionStates[activeSection.key] ?: LiveState.Loading

  fun requestSectionLoad(sectionKey: String, refreshKey: Int) {
    uiState.nextLoadRequestId += 1
    uiState.loadRequest = LiveLoadRequest(
      id = uiState.nextLoadRequestId,
      sectionKey = sectionKey,
      refreshKey = refreshKey,
    )
  }

  LaunchedEffect(sections) {
    val sectionKeys = sections.mapTo(mutableSetOf()) { it.key }
    uiState.loadedSectionKeys = uiState.loadedSectionKeys.filterTo(mutableSetOf()) { it in sectionKeys }
    uiState.sectionStates = uiState.sectionStates.filterKeys { it in sectionKeys }
    uiState.sectionRefreshKeys = uiState.sectionRefreshKeys.filterKeys { it in sectionKeys }
    if (sections.none { it.key == uiState.selectedSectionKey }) {
      uiState.selectedSectionKey = sections.first().key
    }
    if (sections.none { it.key == uiState.activeSectionKey }) {
      uiState.activeSectionKey = sections.first().key
      uiState.focusedVideoIndex = 0
      uiState.focusedVideoKey = ""
    }
    if (uiState.loadRequest != null && sections.none { it.key == uiState.loadRequest?.sectionKey }) {
      uiState.loadRequest = null
    }
    val sectionKeyToLoad = uiState.activeSectionKey
      .takeIf { key -> sections.any { it.key == key } }
      ?: sections.first().key
    if (uiState.loadRequest == null && uiState.sectionStates[sectionKeyToLoad] == null) {
      requestSectionLoad(
        sectionKey = sectionKeyToLoad,
        refreshKey = uiState.sectionRefreshKeys[sectionKeyToLoad] ?: 0,
      )
    }
  }

  LaunchedEffect(liveRepository, uiState.loadRequest) {
    val request = uiState.loadRequest ?: return@LaunchedEffect
    val sectionToLoad = sections.firstOrNull { it.key == request.sectionKey } ?: return@LaunchedEffect
    // 刷新时若已有 Success,保留旧 videos 不切骨架,避免网格销毁重建抢焦点。
    if (uiState.sectionStates[sectionToLoad.key] !is LiveState.Success) {
      uiState.sectionStates = uiState.sectionStates + (sectionToLoad.key to LiveState.Loading)
      uiState.focusedVideoIndex = 0
      uiState.focusedVideoKey = ""
    }
    val nextState = try {
      val page = when (sectionToLoad) {
        LiveSection.Recommend -> liveRepository.getLiveList(FirstPage)
        LiveSection.Iptv -> null
        is LiveSection.Area -> liveRepository.getLiveListByArea(
          parentAreaId = sectionToLoad.group.id,
          areaId = 0,
          page = FirstPage,
        )
      }
      if (page == null) {
        // IPTV:一次拉全量,无分页。未配置源时 getChannels 返回空 → 空态提示去设置。
        val channels = iptvRepository.getChannels()
        if (channels.isEmpty()) {
          LiveState.Empty
        } else {
          LiveState.Success(
            videos = channels.map { it.toVideoSummary() },
            nextPage = 0,
            loadingMore = false,
            endReached = true,
            loadMoreError = "",
          )
        }
      } else if (page.items.isEmpty()) {
        LiveState.Empty
      } else {
        LiveState.Success(
          videos = page.items.map { it.toVideoSummary() },
          nextPage = page.nextPage,
          loadingMore = false,
          endReached = !page.hasMore,
          loadMoreError = "",
        )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      LiveState.Failed(error.message.orEmpty())
    }
    uiState.loadedSectionKeys = uiState.loadedSectionKeys + sectionToLoad.key
    uiState.sectionStates = uiState.sectionStates + (sectionToLoad.key to nextState)
    if (uiState.loadRequest?.id == request.id) {
      uiState.loadRequest = null
    }
  }

  fun loadNextPage() {
    val currentState = uiState.sectionStates[activeSection.key] as? LiveState.Success ?: return
    if (currentState.loadingMore || currentState.endReached) return
    val pageToLoad = currentState.nextPage
    val sectionToLoad = activeSection
    val sectionKeyToLoad = activeSection.key
    uiState.sectionStates = uiState.sectionStates + (
      sectionKeyToLoad to currentState.copy(loadingMore = true, loadMoreError = "")
    )
    coroutineScope.launch {
      val nextState = try {
        val nextVideos = when (sectionToLoad) {
          LiveSection.Recommend -> liveRepository.getLiveList(pageToLoad)
          // IPTV 无分页(endReached=true),loadNextPage 不会触发;占位保持编译穷尽。
          LiveSection.Iptv -> return@launch
          is LiveSection.Area -> liveRepository.getLiveListByArea(
            parentAreaId = sectionToLoad.group.id,
            areaId = 0,
            page = pageToLoad,
          )
        }
        val latestState = uiState.sectionStates[sectionKeyToLoad] as? LiveState.Success ?: return@launch
        val known = latestState.videos.mapTo(mutableSetOf()) { it.liveRoomId }
        val merged = latestState.videos + nextVideos.items
          .map { it.toVideoSummary() }
          .filter { it.liveRoomId > 0L && known.add(it.liveRoomId) }
        latestState.copy(
          videos = merged,
          nextPage = nextVideos.nextPage,
          loadingMore = false,
          endReached = !nextVideos.hasMore || merged.size == latestState.videos.size,
          loadMoreError = "",
        )
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        val latestState = uiState.sectionStates[sectionKeyToLoad] as? LiveState.Success ?: return@launch
        latestState.copy(loadingMore = false, loadMoreError = error.message.orEmpty())
      }
      uiState.sectionStates = uiState.sectionStates + (sectionKeyToLoad to nextState)
    }
  }

  fun selectSection(section: LiveSection, forceRefresh: Boolean) {
    val isSameSection = uiState.activeSectionKey == section.key
    uiState.selectedSectionKey = section.key
    uiState.activeSectionKey = section.key
    // 切到不同分区回顶部;同一分区被显式 force-refresh(重点击当前顶栏 tab /
    // 侧栏当前目的地)也回顶,避免刷新后视口停旧位置、Down 落旧深位置。
    if (!isSameSection || forceRefresh) {
      uiState.focusedVideoIndex = 0
      uiState.focusedVideoKey = ""
    }
    val hasLoadedSection = section.key in uiState.loadedSectionKeys
    if (forceRefresh || !hasLoadedSection) {
      val nextRefreshKey = if (forceRefresh) {
        (uiState.sectionRefreshKeys[section.key] ?: 0) + 1
      } else {
        uiState.sectionRefreshKeys[section.key] ?: 0
      }
      uiState.sectionRefreshKeys = uiState.sectionRefreshKeys + (section.key to nextRefreshKey)
      requestSectionLoad(sectionKey = section.key, refreshKey = nextRefreshKey)
    }
  }

  LaunchedEffect(manualRefreshKey) {
    if (manualRefreshKey <= 0 || manualRefreshKey == uiState.handledManualRefreshKey) {
      return@LaunchedEffect
    }
    uiState.handledManualRefreshKey = manualRefreshKey
    // 侧栏重点击当前目的地 = 显式刷新当前分区:复用 selectSection(forceRefresh=true),
    // 一并 bump refreshKey + 重置 focusedVideoIndex,配合下方复合 sectionKey 让列表回顶。
    selectSection(section = activeSection, forceRefresh = true)
  }

  val onMoveDownFromTab: () -> Boolean = {
    uiState.focusFirstItemKey += 1
    true
  }

  Column(modifier = Modifier.fillMaxSize()) {
    LiveHeader(
      sections = sections,
      selectedSection = selectedSection,
      selectedSectionFocusRequester = selectedSectionFocusRequester,
      onMoveLeftToNav = onMoveLeftToNav,
      onMoveDownFromTab = onMoveDownFromTab,
      onSectionSelected = { section -> selectSection(section = section, forceRefresh = true) },
      onSectionFocused = { section ->
        uiState.selectedSectionKey = section.key
        val isAlreadyActive = uiState.activeSectionKey == section.key
        val shouldLoad = section.key !in uiState.loadedSectionKeys
        if (shouldLoad) {
          selectSection(section = section, forceRefresh = false)
        } else if (!isAlreadyActive) {
          uiState.activeSectionKey = section.key
          uiState.focusedVideoIndex = 0
          uiState.focusedVideoKey = ""
        }
      },
    )
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = BiliSpacing.Xs),
    ) {
      when (val currentState = state) {
        LiveState.Loading -> VideoGridSkeleton()
        LiveState.Empty -> FeedStatusScreen(
          message = stringResource(
            if (activeSection is LiveSection.Iptv) R.string.live_iptv_empty else R.string.live_empty,
          ),
        )
        is LiveState.Failed -> FeedStatusScreen(
          message = stringResource(R.string.live_failed_with_message, currentState.message),
          actionLabel = stringResource(R.string.action_retry),
          onAction = { selectSection(section = activeSection, forceRefresh = true) },
        )
        is LiveState.Success -> {
          val restoredFocusIndex = currentState.videos.resolveFocusIndex(
            focusKey = uiState.focusedVideoKey,
            fallbackIndex = uiState.focusedVideoIndex,
          )
          TvVideoGrid(
            videos = currentState.videos,
            firstItemFocusRequester = firstItemFocusRequester,
            restoredFocusIndex = restoredFocusIndex,
            restoreFocusRequestKey = restoreFocusRequestKey,
            onRestoreFocusHandled = onRestoreFocusHandled,
            requestInitialFocus = requestInitialFocus,
            onInitialFocusRequested = onInitialFocusRequested,
            focusFirstItemKey = uiState.focusFirstItemKey,
            // sectionKey 复合 refreshKey:重点击当前 tab/侧栏刷新时 refreshKey bump →
            // 复合 key 变 → TvVideoGrid 的 sectionKey effect 滚回第 0 行(只滚不抢焦点)。
            // loadMore 不 bump refreshKey,复合 key 不变 → 不抢回顶(保留下滑位置)。
            sectionKey = activeSection.key to (uiState.sectionRefreshKeys[activeSection.key] ?: 0),
            onFocusedIndexChange = { index, video ->
              uiState.focusedVideoIndex = index
              uiState.focusedVideoKey = video.focusRestoreKey()
            },
            onLoadMore = ::loadNextPage,
            onMoveLeftToNav = onMoveLeftToNav,
            onMoveUpFromFirstRow = {
              runCatching { selectedSectionFocusRequester.requestFocus() }.isSuccess
            },
            onVideoSelected = onVideoSelected,
            onOwnerSelected = { },
            onCardLongPress = { },
            keyFactory = { _, video -> video.liveRoomId },
            topPadding = BiliSizing.HomeVideoGridTopPadding + BiliSizing.HomeVideoGridTopBleed,
            topBleed = BiliSizing.HomeVideoGridTopBleed,
          )
        }
      }
    }
  }
}

@Composable
private fun LiveHeader(
  sections: List<LiveSection>,
  selectedSection: LiveSection,
  selectedSectionFocusRequester: FocusRequester,
  onMoveLeftToNav: () -> Boolean,
  onMoveDownFromTab: () -> Boolean,
  onSectionSelected: (LiveSection) -> Unit,
  onSectionFocused: (LiveSection) -> Unit,
) {
  BiliCapsuleTabRow(itemCount = sections.size) {
    sections.forEachIndexed { index, section ->
      BiliPillTab(
        text = section.label,
        selected = section == selectedSection,
        modifier = if (section == selectedSection) {
          Modifier.focusRequester(selectedSectionFocusRequester)
        } else {
          Modifier
        },
        onMoveLeftToNav = if (index == 0) onMoveLeftToNav else null,
        onMoveDownToGrid = onMoveDownFromTab,
        onClick = { onSectionSelected(section) },
        onFocused = { onSectionFocused(section) },
      )
    }
  }
}