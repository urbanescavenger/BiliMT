package com.kirin.mt.ui.mobile.space

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import com.kirin.mt.ui.mobile.home.formatCount
import com.kirin.mt.ui.theme.BiliColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FirstPage = 1
private const val PageSize = 30

internal sealed interface SpaceState {
  data object Loading : SpaceState
  data class Failed(val message: String) : SpaceState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : SpaceState
}

/**
 * 移动端 UP 主空间页:头像/名/签名/关注 + 投稿网格(最新/最热排序,page 分页)。
 * 复用 VideoRepository.getSpaceVideos/getSpaceUserProfile/checkFollowStatus/setFollowStatus,
 * 视频卡复用 MobileVideoCard。点卡片走 onVideoSelected。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileUserSpaceScreen(
  videoRepository: VideoRepository,
  uiState: MobileUpSpaceUiState,
  mid: Long,
  ownerName: String,
  ownerFace: String,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  onLongPress: ((VideoSummary) -> Unit)? = null,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val order = uiState.order
  val state = uiState.state
  val gridState = uiState.gridState

  fun loadFirst(orderKey: String) {
    uiState.state = SpaceState.Loading
    scope.launch {
      val s = try {
        val videos = videoRepository.getSpaceVideos(mid = mid, page = FirstPage, order = orderKey)
        if (videos.isEmpty()) {
          SpaceState.Success(emptyList(), FirstPage + 1, false, true)
        } else {
          SpaceState.Success(videos, FirstPage + 1, false, videos.size < PageSize)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        SpaceState.Failed(e.message.orEmpty().ifBlank { "加载失败" })
      }
      uiState.state = s
    }
  }

  fun loadNextPage() {
    val current = uiState.state as? SpaceState.Success ?: return
    if (current.loadingMore || current.endReached) return
    uiState.state = current.copy(loadingMore = true)
    scope.launch {
      val next = try {
        val more = videoRepository.getSpaceVideos(mid = mid, page = current.nextPage, order = order)
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
      uiState.state = next
    }
  }

  // 资料 + 关注状态:仅 mid 变化时拉取(从播放器返回同 mid 跳过)。
  LaunchedEffect(mid) {
    if (uiState.profileLoadedMid != mid) {
      uiState.profile = runCatching { videoRepository.getSpaceUserProfile(mid) }.getOrNull()
      uiState.followed = runCatching { videoRepository.checkFollowStatus(mid) }.getOrDefault(false)
      uiState.profileLoadedMid = mid
    }
  }

  // 投稿列表:mid 或 order 变化时拉取(从播放器返回同 mid+order 跳过)。
  LaunchedEffect(mid, order) {
    if (uiState.videoLoadedMid != mid || uiState.videoLoadedOrder != order) {
      loadFirst(order)
      uiState.videoLoadedMid = mid
      uiState.videoLoadedOrder = order
    } else {
      // 从播放器返回同 mid+order:清除可能卡住的翻页 loading 标志(scope 已随离开组合取消)。
      val cur = uiState.state as? SpaceState.Success
      if (cur != null && cur.loadingMore) {
        uiState.state = cur.copy(loadingMore = false)
      }
    }
  }

  // 滚到底自动翻页
  LaunchedEffect(order) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) loadNextPage() }
  }

  PullToRefreshLayout(
    isRefreshing = state is SpaceState.Loading,
    onRefresh = { loadFirst(order) },
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 160.dp),
      state = gridState,
      contentPadding = PaddingValues(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
    // 顶栏 + 资料头 + 排序(跨整行)
    item(span = { GridItemSpan(maxLineSpan) }) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedButton(onClick = onBack) { Text("‹") }
          Text(
            text = uiState.profile?.name ?: ownerName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // 正直播的 UP:头像套红环 + 底部"直播"pill,且可点头像切到该直播间(onVideoSelected →
          // toPlaybackRequest 的 liveRoomId>0 分支 → LivePlayerScreen;返回键回到主页)。
          val upLive = uiState.profile?.liveStatus == 1 && (uiState.profile?.liveRoomId ?: 0L) > 0L
          Box(
            modifier = Modifier
              .size(56.dp)
              .then(if (upLive) Modifier.border(2.dp, BiliColors.BiliPink, CircleShape) else Modifier)
              .then(
                if (upLive) Modifier.clickable {
                  val liveSummary = VideoSummary(
                    bvid = "",
                    title = uiState.profile?.liveTitle?.ifBlank { uiState.profile?.name ?: ownerName } ?: (uiState.profile?.name ?: ownerName),
                    pic = uiState.profile?.liveCover?.ifBlank { uiState.profile?.face ?: ownerFace } ?: (uiState.profile?.face ?: ownerFace),
                    ownerName = uiState.profile?.name ?: ownerName,
                    ownerFace = uiState.profile?.face ?: ownerFace,
                    ownerMid = mid,
                    view = 0,
                    danmaku = 0,
                    duration = 0,
                    pubdate = 0L,
                    badge = "直播",
                    isLive = true,
                    liveRoomId = uiState.profile?.liveRoomId ?: 0L,
                  )
                  onVideoSelected(liveSummary)
                } else Modifier,
              ),
            contentAlignment = Alignment.Center,
          ) {
            AsyncImage(
              model = uiState.profile?.face ?: ownerFace,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.size(56.dp).clip(CircleShape),
            )
            if (upLive) {
              Row(
                modifier = Modifier
                  .align(Alignment.BottomCenter)
                  .clip(RoundedCornerShape(3.dp))
                  .background(BiliColors.BiliPink)
                  .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Box(
                  modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                  text = "直播",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                )
              }
            }
          }
          Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(uiState.profile?.name ?: ownerName, style = MaterialTheme.typography.titleSmall)
            val sign = uiState.profile?.sign.orEmpty()
            if (sign.isNotEmpty()) {
              Text(
                text = sign,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
            val fans = uiState.profile?.fans ?: 0L
            Text(
              text = "粉丝 ${formatCount(fans.toInt())}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Button(
            onClick = {
              if (uiState.followLoading) return@Button
              uiState.followLoading = true
              scope.launch {
                runCatching { videoRepository.setFollowStatus(mid, !uiState.followed) }
                  .getOrNull()?.let { uiState.followed = it }
                uiState.followLoading = false
              }
            },
            enabled = !uiState.followLoading,
          ) {
            Text(if (uiState.followed) "已关注" else "关注")
          }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
          OutlinedButton(onClick = { uiState.order = "pubdate" }) {
            Text(
              "最新",
              color = if (order == "pubdate") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
          }
          Spacer(Modifier.padding(start = 8.dp))
          OutlinedButton(onClick = { uiState.order = "click" }) {
            Text(
              "最热",
              color = if (order == "click") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }

    when (val s = state) {
      SpaceState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      }
      is SpaceState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
        Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
      }
      is SpaceState.Success -> {
        if (s.videos.isEmpty()) {
          item(span = { GridItemSpan(maxLineSpan) }) {
            Text("暂无投稿", modifier = Modifier.padding(16.dp))
          }
        } else {
          items(s.videos, key = { it.bvid }) { video ->
            MobileVideoCard(video = video, onClick = onVideoSelected, onOpenOwner = onOpenOwner, onLongPress = onLongPress)
          }
        }
        if (s.loadingMore) {
          item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
        }
      }
    }
    }
  }
}