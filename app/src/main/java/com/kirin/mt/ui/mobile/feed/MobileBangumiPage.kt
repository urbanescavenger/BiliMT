package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.FollowingSeason
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val BangumiPageSize = 30

private enum class BangumiType(val id: Int, val labelRes: Int) {
  Bangumi(1, R.string.bangumi_type_bangumi),
  Cinema(2, R.string.bangumi_type_cinema),
}

private enum class BangumiStatus(val id: Int, val labelRes: Int) {
  All(0, R.string.bangumi_status_all),
  Want(1, R.string.bangumi_status_want),
  Watching(2, R.string.bangumi_status_watching),
  Watched(3, R.string.bangumi_status_watched),
}

private sealed interface BangumiState {
  data object Loading : BangumiState
  data object Empty : BangumiState
  data class Failed(val message: String) : BangumiState
  data class Success(
    val seasons: List<FollowingSeason>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : BangumiState
}

/** FollowingSeason -> VideoSummary,仅用于 MobileVideoCard 渲染;点击反查回真实 season。 */
private fun FollowingSeason.toCardVideo(): VideoSummary = VideoSummary(
  bvid = "season-$seasonId",
  title = title,
  pic = cover,
  ownerName = seasonTypeName,
  ownerFace = "",
  ownerMid = 0L,
  view = 0,
  danmaku = 0,
  duration = 0,
  pubdate = 0L,
  badge = badge,
)

/**
 * 移动端"追番"子 tab:番剧/影视 + 全部/想看/在看/看过 两组筛选 + 季网格 + page 分页。
 * 复用 getFollowingSeasons。季非 UGC,点击走 onSeasonSelected(进季详情选集)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileBangumiPage(
  videoRepository: VideoRepository,
  onSeasonSelected: (FollowingSeason) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  var type by remember { mutableStateOf(BangumiType.Bangumi) }
  var status by remember { mutableStateOf(BangumiStatus.All) }
  var state by remember { mutableStateOf<BangumiState>(BangumiState.Loading) }

  suspend fun loadFirstBody() {
    state = BangumiState.Loading
    state = try {
      val page = videoRepository.getFollowingSeasons(
        page = 1,
        pageSize = BangumiPageSize,
        type = type.id,
        status = status.id,
      )
      if (page.seasons.isEmpty()) {
        BangumiState.Empty
      } else {
        BangumiState.Success(
          seasons = page.seasons,
          nextPage = 2,
          loadingMore = false,
          endReached = !page.hasMore,
        )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      BangumiState.Failed(e.message.orEmpty().ifBlank { "加载失败" })
    }
  }

  // 筛选切换或首次进入都重载首页。
  LaunchedEffect(type, status) { loadFirstBody() }

  fun reloadFirst() {
    scope.launch { loadFirstBody() }
  }

  val gridState = rememberLazyGridState()

  fun loadNextPage() {
    val current = state as? BangumiState.Success ?: return
    if (current.loadingMore || current.endReached) return
    val pageToLoad = current.nextPage
    state = current.copy(loadingMore = true)
    scope.launch {
      val next = try {
        val page = videoRepository.getFollowingSeasons(
          page = pageToLoad,
          pageSize = BangumiPageSize,
          type = type.id,
          status = status.id,
        )
        val merged = (current.seasons + page.seasons).distinctBy { it.seasonId }
        current.copy(
          seasons = merged,
          nextPage = pageToLoad + 1,
          loadingMore = false,
          endReached = !page.hasMore || merged.size == current.seasons.size,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        current.copy(loadingMore = false)
      }
      state = next
    }
  }

  LaunchedEffect(type, status) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) loadNextPage() }
  }

  Box(modifier = modifier.fillMaxSize()) {
    PullToRefreshLayout(
      isRefreshing = state is BangumiState.Loading,
      onRefresh = { reloadFirst() },
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
        // 类型筛选行 + 状态筛选行:全宽 header item。
        item(span = { GridItemSpan(maxLineSpan) }) {
          androidx.compose.foundation.layout.Column {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              lazyItems(BangumiType.entries.toList()) { t ->
                FilterChip(
                  selected = type == t,
                  onClick = { type = t },
                  label = { Text(stringResource(t.labelRes)) },
                  colors = FilterChipDefaults.filterChipColors(),
                )
              }
            }
            LazyRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.padding(top = 4.dp),
            ) {
              lazyItems(BangumiStatus.entries.toList()) { st ->
                FilterChip(
                  selected = status == st,
                  onClick = { status = st },
                  label = { Text(stringResource(st.labelRes)) },
                  colors = FilterChipDefaults.filterChipColors(),
                )
              }
            }
          }
        }

        when (val s = state) {
          BangumiState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
          }
          BangumiState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.bangumi_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
          is BangumiState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.bangumi_failed_with_message, s.message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
              )
            }
          }
          is BangumiState.Success -> {
            val seasonByKey = s.seasons.associateBy { "season-${it.seasonId}" }
            items(s.seasons.map { it.toCardVideo() }, key = { it.bvid }) { video ->
              MobileVideoCard(
                video = video,
                onClick = { v ->
                  seasonByKey[v.bvid]?.let { season -> onSeasonSelected(season) }
                },
                onOpenOwner = null,
              )
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