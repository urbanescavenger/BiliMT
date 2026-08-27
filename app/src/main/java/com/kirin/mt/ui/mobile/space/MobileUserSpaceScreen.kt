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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.pubdateText
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.formatCount
import com.kirin.mt.ui.mobile.home.rememberVideoCardRelativeText
import com.kirin.mt.ui.theme.BiliColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val FirstPage = 1
private const val PageSize = 30

sealed interface SpaceState {
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
 * 移动端 UP 主空间页:头像/名/签名/关注 + 投稿列表(最新发布/最多播放排序,page 分页)。
 * 复用 VideoRepository.getSpaceVideos/getSpaceUserProfile/checkFollowStatus/setFollowStatus。
 * 投稿区对齐 B站官方(2026-08-27 用户截图定稿):「▶ 播放全部」+「≡ 排序」头部行 + 官方式
 * 纵向视频行(ChannelVideoRow,与 YouTube 频道页共用,带弹幕数)。点行走 onVideoSelected。
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
  onPlayAll: (List<VideoSummary>) -> Unit = {},
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val order = uiState.order
  val state = uiState.state
  val gridState = uiState.gridState
  // 投稿行相对时间文案(今天/昨天/N天前),与首页卡片同一实现。
  val relativeText = rememberVideoCardRelativeText()

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
        SpaceState.Failed(e.message.orEmpty().ifBlank { context.getString(R.string.mobile_load_failed) })
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
                  text = stringResource(R.string.mobile_live),
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
              text = stringResource(R.string.mobile_fans, formatCount(fans.toInt(), context.resources)),
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
            Text(
              if (uiState.followed) stringResource(R.string.youtube_channel_following)
              else stringResource(R.string.youtube_channel_follow),
            )
          }
        }
        // 内容区头部行(对齐 B站官方 投稿 tab):「▶ 播放全部」左 +「≡ 排序」右,替代原独立排序按钮行。
        val currentVideos = (state as? SpaceState.Success)?.videos.orEmpty()
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(
            onClick = { if (currentVideos.isNotEmpty()) onPlayAll(currentVideos) },
            enabled = currentVideos.isNotEmpty(),
          ) {
            Text(
              text = "▶ 播放全部",
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold,
            )
          }
          Spacer(Modifier.weight(1f))
          Box {
            var sortMenuOpen by remember { mutableStateOf(false) }
            TextButton(onClick = { sortMenuOpen = true }) {
              Text(
                text = "≡ " + stringResource(
                  if (order == "pubdate") R.string.player_up_sort_latest else R.string.player_up_sort_hot,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.player_up_sort_latest)) },
                trailingIcon = { if (order == "pubdate") Text("✓") },
                onClick = {
                  uiState.order = "pubdate"
                  sortMenuOpen = false
                },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.player_up_sort_hot)) },
                trailingIcon = { if (order == "click") Text("✓") },
                onClick = {
                  uiState.order = "click"
                  sortMenuOpen = false
                },
              )
            }
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
            Text(stringResource(R.string.mobile_no_videos), modifier = Modifier.padding(16.dp))
          }
        } else {
          // 官方式纵向投稿行(封面左+时长,右标题/日期/播放量·弹幕),行间分割线;替代网格卡片。
          itemsIndexed(s.videos, key = { _, v -> v.bvid }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { index, video ->
            ChannelVideoRow(
              video = video,
              relativeText = relativeText,
              onClick = { onVideoSelected(video) },
              onLongPress = onLongPress,
              showDanmaku = true,
            )
            if (index < s.videos.lastIndex) {
              HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 2.dp),
              )
            }
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