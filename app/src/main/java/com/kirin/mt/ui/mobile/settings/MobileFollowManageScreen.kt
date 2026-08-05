package com.kirin.mt.ui.mobile.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.image.BiliImageSizing
import com.kirin.mt.core.image.buildOwnerAvatarRequest
import com.kirin.mt.core.network.FollowingUser
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.youtube.YoutubeChannelStore
import com.kirin.mt.core.youtube.YoutubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 关注管理子屏类型:设置账号头点击头像后二选一。 */
enum class FollowManageKind {
  BiliFollows,
  YoutubeFollows,
}

/**
 * 关注管理屏:B站关注列表(BiliFollows)逐条取消关注 + 分页加载;
 * YouTube 关注列表(YoutubeFollows)复用 [MobileYoutubeChannelsPanel](已含删除/取关/添加)。
 */
@Composable
fun MobileFollowManageScreen(
  kind: FollowManageKind,
  mid: Long,
  videoRepository: VideoRepository,
  youtubeChannelStore: YoutubeChannelStore,
  youtubeRepository: YoutubeRepository,
  modifier: Modifier = Modifier,
) {
  when (kind) {
    FollowManageKind.BiliFollows -> BiliFollowingUsersList(
      videoRepository = videoRepository,
      mid = mid,
      modifier = modifier,
    )
    FollowManageKind.YoutubeFollows -> Column(
      modifier = modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MobileYoutubeChannelsPanel(
        youtubeChannelStore = youtubeChannelStore,
        youtubeRepository = youtubeRepository,
      )
    }
  }
}

private sealed interface BiliFollowsState {
  data object Loading : BiliFollowsState
  data class Failed(val message: String) : BiliFollowsState
  data class Success(
    val users: List<FollowingUser>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
  ) : BiliFollowsState
}

@Composable
private fun BiliFollowingUsersList(
  videoRepository: VideoRepository,
  mid: Long,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val loadFailedText = stringResource(R.string.mobile_follows_load_failed)
  var state by remember { mutableStateOf<BiliFollowsState>(BiliFollowsState.Loading) }
  var unfollowing by remember { mutableStateOf(setOf<Long>()) }

  fun loadFirst() {
    state = BiliFollowsState.Loading
    scope.launch {
      val s = try {
        val page = videoRepository.getFollowingUsers(mid, page = 1)
        if (page.users.isEmpty()) {
          BiliFollowsState.Success(emptyList(), nextPage = 2, loadingMore = false, endReached = true)
        } else {
          BiliFollowsState.Success(page.users, nextPage = 2, loadingMore = false, endReached = !page.hasMore)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        BiliFollowsState.Failed(e.message.orEmpty().ifBlank { loadFailedText })
      }
      state = s
    }
  }

  fun loadNextPage() {
    val current = state as? BiliFollowsState.Success ?: return
    if (current.loadingMore || current.endReached) return
    state = current.copy(loadingMore = true)
    scope.launch {
      val next = try {
        val more = videoRepository.getFollowingUsers(mid, page = current.nextPage)
        val merged = (current.users + more.users).distinctBy { it.mid }
        current.copy(
          users = merged,
          nextPage = current.nextPage + 1,
          loadingMore = false,
          endReached = !more.hasMore || more.users.isEmpty(),
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        current.copy(loadingMore = false)
      }
      state = next
    }
  }

  fun unfollow(user: FollowingUser) {
    if (user.mid in unfollowing) return
    unfollowing = unfollowing + user.mid
    scope.launch {
      val ok = runCatching { videoRepository.setFollowStatus(user.mid, follow = false) }.getOrDefault(false)
      unfollowing = unfollowing - user.mid
      if (ok) {
        val current = state as? BiliFollowsState.Success
        if (current != null) {
          state = current.copy(users = current.users.filterNot { it.mid == user.mid })
        }
        Toast.makeText(context, R.string.mobile_follows_unfollowed, Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(context, R.string.mobile_follows_unfollow_failed, Toast.LENGTH_SHORT).show()
      }
    }
  }

  LaunchedEffect(mid) { loadFirst() }

  // 滚到底自动翻页
  LaunchedEffect(mid) {
    snapshotFlow {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = listState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 4
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) loadNextPage() }
  }

  when (val s = state) {
    is BiliFollowsState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    is BiliFollowsState.Failed -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        text = s.message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    is BiliFollowsState.Success -> {
      if (s.users.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            text = stringResource(R.string.mobile_follows_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        LazyColumn(
          state = listState,
          modifier = modifier.fillMaxSize(),
        ) {
          items(s.users, key = { it.mid }) { user ->
            BiliFollowingUserRow(
              user = user,
              unfollowing = user.mid in unfollowing,
              onUnfollow = { unfollow(user) },
            )
          }
          if (s.loadingMore) {
            item(key = "loading") {
              Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun BiliFollowingUserRow(
  user: FollowingUser,
  unfollowing: Boolean,
  onUnfollow: () -> Unit,
) {
  val context = LocalContext.current
  val avatarSizePx = BiliImageSizing.StandardOwnerAvatarSizePx
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (user.face.isNotBlank()) {
      val request = remember(context, user.face, avatarSizePx) {
        buildOwnerAvatarRequest(context = context, url = user.face, sizePx = avatarSizePx)
      }
      AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(40.dp).clip(CircleShape),
      )
    }
    Column(
      modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
    ) {
      Text(
        text = user.uname.ifBlank { "uid ${user.mid}" },
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
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
    TextButton(
      enabled = !unfollowing,
      onClick = onUnfollow,
    ) {
      Text(stringResource(R.string.mobile_follows_unfollow))
    }
  }
}
