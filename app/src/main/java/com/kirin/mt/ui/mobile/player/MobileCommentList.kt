package com.kirin.mt.ui.mobile.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.Comment
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.theme.BiliColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 移动端视频评论列表(触屏):嵌入 MobilePlayerScreen 竖屏分栏的下半区。
 * 数据复用 VideoRepository.getComments(/x/v2/reply, oid=aid, type=1)。
 * 滚动到末尾自动翻页;顶部热门/最新排序切换重载;楼中楼本期仅显示计数(对齐 TV CommentScreen)。
 * 局部套深色 MaterialTheme,避免内容页浅色默认主题导致评论不可读(全局主题统一属 P3,不在本期)。
 */
@Stable
internal class MobileCommentListState {
  var comments by mutableStateOf<List<Comment>>(emptyList())
  var loading by mutableStateOf(true)
  var loadingMore by mutableStateOf(false)
  var error by mutableStateOf("")
  var loadMoreError by mutableStateOf("")
  var endReached by mutableStateOf(false)
  // 1=按热度, 0=按时间(bilibili reply sort)
  var sort by mutableIntStateOf(1)
  var currentPage by mutableIntStateOf(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileCommentList(
  aid: Long,
  videoRepository: VideoRepository,
  modifier: Modifier = Modifier,
) {
  // 局部深色主题:背景/字色用 Bili token,primary 用 BiliPink,让评论在深色背景上可读。
  MaterialTheme(
    colorScheme = darkColorScheme(
      background = BiliColors.Background,
      surface = BiliColors.Surface,
      onSurface = BiliColors.TextPrimary,
      onSurfaceVariant = BiliColors.TextSecondary,
      primary = BiliColors.BiliPink,
      onPrimary = BiliColors.TextPrimary,
    ),
  ) {
    Box(
      modifier = modifier
        .fillMaxWidth()
        .background(BiliColors.Background),
    ) {
      if (aid <= 0L) {
        // PGC 等无 aid 的内容用 type=1 oid=aid 接口取不到评论,直接占位,不发请求。
        Text(
          text = stringResource(R.string.comment_empty),
          color = BiliColors.TextSecondary,
          modifier = Modifier.align(Alignment.Center),
        )
      } else {
        CommentListContent(aid = aid, videoRepository = videoRepository)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentListContent(
  aid: Long,
  videoRepository: VideoRepository,
) {
  val state = remember { MobileCommentListState() }
  val coroutineScope = rememberCoroutineScope()
  val listState = rememberLazyListState()

  LaunchedEffect(aid, state.sort) {
    loadCommentFirstPage(videoRepository, state, aid)
  }

  // 触屏翻页:可见末尾临近时触发下一页。
  val nearEnd by remember {
    derivedStateOf {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
      last >= 0 && last >= state.comments.size - 3
    }
  }
  LaunchedEffect(nearEnd) {
    if (nearEnd) loadCommentNextPage(videoRepository, coroutineScope, state, aid)
  }

  Column(modifier = Modifier.fillMaxSize()) {
    // 排序行:热门 / 最新
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(BiliColors.Surface)
        .padding(horizontal = 12.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SortChip(
        label = stringResource(R.string.comment_sort_hot),
        selected = state.sort == 1,
        onClick = { state.sort = 1 },
      )
      SortChip(
        label = stringResource(R.string.comment_sort_latest),
        selected = state.sort == 0,
        onClick = { state.sort = 0 },
      )
    }

    when {
      state.loading && state.comments.isEmpty() -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(color = BiliColors.BiliPink)
      }
      state.error.isNotBlank() && state.comments.isEmpty() -> Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.comment_failed_with_message, state.error),
          color = BiliColors.TextSecondary,
        )
      }
      state.comments.isEmpty() -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.comment_empty),
          color = BiliColors.TextSecondary,
        )
      }
      else -> LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(state.comments, key = { it.id }) { comment ->
          CommentItem(comment)
        }
        item {
          CommentListFooter(state = state)
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label) },
    colors = FilterChipDefaults.filterChipColors(
      selectedContainerColor = BiliColors.BiliPink,
      selectedLabelColor = BiliColors.TextPrimary,
      containerColor = BiliColors.SurfaceElevated,
      labelColor = BiliColors.TextSecondary,
    ),
  )
}

@Composable
private fun CommentListFooter(state: MobileCommentListState) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.Center,
  ) {
    when {
      state.loadingMore -> CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        color = BiliColors.BiliPink,
      )
      state.loadMoreError.isNotBlank() -> Text(
        text = state.loadMoreError,
        color = BiliColors.TextTertiary,
        style = MaterialTheme.typography.bodySmall,
      )
      state.endReached -> Text(
        text = "没有更多了",
        color = BiliColors.TextTertiary,
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun CommentItem(comment: Comment) {
  Row(modifier = Modifier.fillMaxWidth()) {
    AsyncImage(
      model = comment.avatar,
      contentDescription = null,
      modifier = Modifier
        .size(40.dp)
        .clip(CircleShape),
      contentScale = ContentScale.Crop,
    )
    Spacer(Modifier.size(10.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = comment.uname.ifBlank { stringResource(R.string.comment_anonymous) },
          color = BiliColors.BiliPink,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        Text(
          text = formatCommentRelativeTime(comment.ctime),
          color = BiliColors.TextTertiary,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Spacer(Modifier.size(4.dp))
      Text(
        text = comment.content.ifBlank { stringResource(R.string.comment_empty_content) },
        color = BiliColors.TextPrimary,
        style = MaterialTheme.typography.bodyMedium,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.size(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          text = stringResource(R.string.comment_like_count, comment.likeCount),
          color = BiliColors.TextSecondary,
          style = MaterialTheme.typography.bodySmall,
        )
        if (comment.replyCount > 0) {
          Text(
            text = stringResource(R.string.comment_reply_count, comment.replyCount),
            color = BiliColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

private fun formatCommentRelativeTime(ctimeSeconds: Long): String {
  if (ctimeSeconds <= 0L) return ""
  val nowSeconds = System.currentTimeMillis() / 1000L
  val diff = nowSeconds - ctimeSeconds
  return when {
    diff < 60 -> "刚刚"
    diff < 3600 -> "${diff / 60}分钟前"
    diff < 86_400 -> "${diff / 3600}小时前"
    diff < 2_592_000 -> "${diff / 86_400}天前"
    else -> "${diff / 2_592_000}个月前"
  }
}

private suspend fun loadCommentFirstPage(
  videoRepository: VideoRepository,
  state: MobileCommentListState,
  aid: Long,
) {
  state.loading = true
  state.error = ""
  state.loadMoreError = ""
  state.endReached = false
  state.currentPage = 0
  state.comments = emptyList()
  try {
    val page = videoRepository.getComments(aid = aid, page = 1, sort = state.sort)
    state.currentPage = 1
    state.comments = page.comments
    state.endReached = !page.hasMore
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    state.error = error.message.orEmpty()
  } finally {
    state.loading = false
  }
}

private fun loadCommentNextPage(
  videoRepository: VideoRepository,
  coroutineScope: CoroutineScope,
  state: MobileCommentListState,
  aid: Long,
) {
  if (state.loadingMore || state.endReached || state.loading) return
  val nextPage = state.currentPage + 1
  val sort = state.sort
  state.loadingMore = true
  state.loadMoreError = ""
  coroutineScope.launch {
    try {
      val page = videoRepository.getComments(aid = aid, page = nextPage, sort = sort)
      state.currentPage = nextPage
      val known = state.comments.mapTo(mutableSetOf()) { it.id }
      val fresh = page.comments.filter { known.add(it.id) }
      state.comments = state.comments + fresh
      state.endReached = !page.hasMore || fresh.isEmpty()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      state.loadMoreError = error.message.orEmpty()
    } finally {
      state.loadingMore = false
    }
  }
}