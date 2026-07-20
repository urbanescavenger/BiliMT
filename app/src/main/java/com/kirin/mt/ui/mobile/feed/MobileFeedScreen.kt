package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.FollowingSeason
import com.kirin.mt.core.network.VideoRepository
import kotlinx.coroutines.launch

/** 子 tab:动态 / 历史 / 收藏 / 追番。 */
private val FeedTabs = listOf(
  R.string.nav_dynamic,
  R.string.nav_history,
  R.string.nav_favorite,
  R.string.nav_bangumi,
)

/**
 * 移动端"动态"底栏 tab 内容:4 个子 tab(动态/历史/收藏/追番)+ HorizontalPager 左右滑动切换,
 * 镜像 MobileHomeScreen 的 PrimaryScrollableTabRow + Pager 范式。未登录时整体显示登录入口。
 * 复用 MobileDynamicScreen(动态)与 MobileHistoryPage/MobileFavoritePage/MobileBangumiPage。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileFeedScreen(
  videoRepository: VideoRepository,
  isLoggedIn: Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  onSeasonSelected: (FollowingSeason) -> Unit,
  onLogin: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!isLoggedIn) {
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

  val scope = rememberCoroutineScope()
  val pagerState = rememberPagerState(pageCount = { FeedTabs.size }, initialPage = 0)

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
      }
    }
  }
}