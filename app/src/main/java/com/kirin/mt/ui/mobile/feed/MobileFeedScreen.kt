package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.FollowingSeason
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.youtube.YoutubeChannelStore
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 子 tab:动态 / 历史 / 收藏 / 追番 / YouTube 关注。 */
private val FeedTabs = listOf(
  R.string.nav_dynamic,
  R.string.nav_history,
  R.string.nav_favorite,
  R.string.nav_bangumi,
  R.string.feed_tab_youtube,
)

/** YouTube 关注 tab 在 pager 中的下标(最后一个,免登录)。 */
private const val YoutubeTabIndex = 4

/**
 * 移动端"动态"底栏 tab 内容:4 个子 tab(动态/历史/收藏/追番)+ HorizontalPager 左右滑动切换,
 * 镜像 MobileHomeScreen 的 PrimaryScrollableTabRow + Pager 范式。未登录时整体显示登录入口。
 * 复用 MobileDynamicScreen(动态)与 MobileHistoryPage/MobileFavoritePage/MobileBangumiPage。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileFeedScreen(
  videoRepository: VideoRepository,
  youtubeChannelStore: com.kirin.mt.core.youtube.YoutubeChannelStore,
  isLoggedIn: Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  onSeasonSelected: (FollowingSeason) -> Unit,
  onLogin: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val pagerState = rememberPagerState(pageCount = { FeedTabs.size }, initialPage = 0)

  // YouTube 关注无需登录;其余 tab 未登录时显示登录入口。
  if (!isLoggedIn && pagerState.currentPage != YoutubeTabIndex) {
    Column(
      modifier = modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.mobile_account_signed_out),
        style = MaterialTheme.typography.titleMedium,
      )
      Button(onClick = onLogin, modifier = Modifier.padding(top = 16.dp)) {
        Text(stringResource(R.string.mobile_login))
      }
    }
    return
  }

  Column(modifier = modifier.fillMaxSize()) {
    PrimaryScrollableTabRow(
      selectedTabIndex = pagerState.currentPage.coerceIn(0, FeedTabs.lastIndex),
      edgePadding = 0.dp,
    ) {
      FeedTabs.forEachIndexed { index, labelRes ->
        Tab(
          selected = index == pagerState.currentPage,
          onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
          text = { Text(stringResource(labelRes)) },
        )
      }
    }
    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize(),
    ) { page ->
      when (page) {
        0 -> MobileDynamicScreen(
          videoRepository = videoRepository,
          isLoggedIn = true,
          onVideoSelected = onVideoSelected,
          onOpenOwner = onOpenOwner,
          onLogin = onLogin,
          modifier = Modifier.fillMaxSize(),
        )
        1 -> MobileHistoryPage(
          videoRepository = videoRepository,
          onVideoSelected = onVideoSelected,
          onOpenOwner = onOpenOwner,
          modifier = Modifier.fillMaxSize(),
        )
        2 -> MobileFavoritePage(
          videoRepository = videoRepository,
          onVideoSelected = onVideoSelected,
          onOpenOwner = onOpenOwner,
          modifier = Modifier.fillMaxSize(),
        )
        3 -> MobileBangumiPage(
          videoRepository = videoRepository,
          onSeasonSelected = onSeasonSelected,
          modifier = Modifier.fillMaxSize(),
        )
        4 -> MobileYoutubeSubscriptions(
          videoRepository = videoRepository,
          youtubeChannelStore = youtubeChannelStore,
          onVideoSelected = onVideoSelected,
          onOpenOwner = onOpenOwner,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}
/** 移动端"YouTube 关注"子页:手动配置频道的最新视频网格(单页,免登录)。 */
@Composable
private fun MobileYoutubeSubscriptions(
  videoRepository: VideoRepository,
  youtubeChannelStore: YoutubeChannelStore,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val channels by youtubeChannelStore.channels.collectAsState(initial = emptyList())
  var state by remember { mutableStateOf<YoutubeFeedState>(YoutubeFeedState.Loading) }

  LaunchedEffect(channels) {
    state = YoutubeFeedState.Loading
    state = try {
      val videos = videoRepository.youtubeSubscriptionsFeed(channels)
      if (videos.isEmpty()) YoutubeFeedState.Empty
      else YoutubeFeedState.Success(videos)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      YoutubeFeedState.Failed(e.message.orEmpty().ifBlank { "加载失败" })
    }
  }

  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 160.dp),
    contentPadding = PaddingValues(12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = modifier.fillMaxSize(),
  ) {
    when (val s = state) {
      YoutubeFeedState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }
      YoutubeFeedState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
          Text(
            text = stringResource(R.string.feed_youtube_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
      is YoutubeFeedState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
          Text(
            text = s.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
          )
        }
      }
      is YoutubeFeedState.Success -> {
        items(s.videos, key = { it.bvid }) { video ->
          MobileVideoCard(video = video, onClick = onVideoSelected, onOpenOwner = onOpenOwner)
        }
      }
    }
  }
}

private sealed interface YoutubeFeedState {
  data object Loading : YoutubeFeedState
  data object Empty : YoutubeFeedState
  data class Failed(val message: String) : YoutubeFeedState
  data class Success(val videos: List<VideoSummary>) : YoutubeFeedState
}
