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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.Comment
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.youtube.YoutubeComment
import com.kirin.mt.ui.theme.BiliColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 移动端视频评论列表(触屏):嵌入 MobilePlayerScreen 竖屏分栏的下半区。
 * 数据复用 VideoRepository.getComments(/x/v2/reply, oid=aid, type=1)。
 * 滚动到末尾自动翻页;顶部热门/最新排序切换重载;楼中楼本期仅显示计数(对齐 TV CommentScreen)。
 *
 * aid 来源:`toPlaybackRequest` 未带 aid、卡片除动态外也不带 aid,故 aid 取自
 * `PlaybackVideoMetadata.aid`(播放器加载后才就绪)。metadata 加载前 aid=0 显示加载圈;
 * PGC 无 aid 显示"暂无评论"占位(本期不接 PGC 评论接口)。白底深字。
 */
@Stable
internal class MobileCommentListState {
  var comments by mutableStateOf<List<Comment>>(emptyList())
  var loading by mutableStateOf(true)
  var loadingMore by mutableStateOf(false)
  var error by mutableStateOf("")
  var loadMoreError by mutableStateOf("")
  var endReached by mutableStateOf(false)
  // B 站 data.page.count:评论总数,经 onTotalCountChange 回调透到外层 Tab 标题显示。
  var totalCount by mutableIntStateOf(0)
  // 1=按热度, 0=按时间(bilibili reply sort)
  var sort by mutableIntStateOf(1)
  var currentPage by mutableIntStateOf(0)
}

/** 白底评论列表的浅色 token(全局内容页主题统一属 P3,这里自带浅色,不依赖 MaterialTheme)。 */
private object CommentColor {
  val Surface = Color(0xFFF2F2F4)
  val ChipContainer = Color(0xFFEAEAEA)
  val ChipLabel = Color(0xFF555555)
  val TextPrimary = Color(0xFF222222)
  val TextSecondary = Color(0xFF999999)
  val Divider = Color(0xFFEEEEEE)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileCommentList(
  aid: Long,
  isPgc: Boolean,
  videoRepository: VideoRepository,
  modifier: Modifier = Modifier,
  onTotalCountChange: ((Int) -> Unit)? = null,
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .background(Color.White),
  ) {
    when {
      // PGC 用 epId 无 aid,/x/v2/reply type=1 取不到,本期不接 PGC 评论,占位。
      isPgc -> Text(
        text = stringResource(R.string.comment_empty),
        color = CommentColor.TextSecondary,
        modifier = Modifier.align(Alignment.Center),
      )
      // metadata 未加载完(aid=0):显示加载圈,而非"暂无评论",避免误判。
      aid <= 0L -> CircularProgressIndicator(
        color = BiliColors.BiliPink,
        modifier = Modifier.align(Alignment.Center),
      )
      else -> CommentListContent(aid = aid, videoRepository = videoRepository, onTotalCountChange = onTotalCountChange)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentListContent(
  aid: Long,
  videoRepository: VideoRepository,
  onTotalCountChange: ((Int) -> Unit)? = null,
) {
  val state = remember { MobileCommentListState() }
  val coroutineScope = rememberCoroutineScope()
  val listState = rememberLazyListState()

  // 评论总数变化时回调外层(评论 Tab 标题显示)。分页/排序重载后 state.totalCount 更新即触发。
  LaunchedEffect(state.totalCount) {
    onTotalCountChange?.invoke(state.totalCount)
  }

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
        .background(CommentColor.Surface)
        .padding(horizontal = 12.dp, vertical = 4.dp),
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
          color = CommentColor.TextSecondary,
        )
      }
      state.comments.isEmpty() -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.comment_empty),
          color = CommentColor.TextSecondary,
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
      selectedLabelColor = Color.White,
      containerColor = CommentColor.ChipContainer,
      labelColor = CommentColor.ChipLabel,
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
        color = CommentColor.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
      )
      state.endReached -> Text(
        text = "没有更多了",
        color = CommentColor.TextSecondary,
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
          color = CommentColor.TextSecondary,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Spacer(Modifier.size(4.dp))
      Text(
        text = comment.content.ifBlank { stringResource(R.string.comment_empty_content) },
        color = CommentColor.TextPrimary,
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
          color = CommentColor.TextSecondary,
          style = MaterialTheme.typography.bodySmall,
        )
        if (comment.replyCount > 0) {
          Text(
            text = stringResource(R.string.comment_reply_count, comment.replyCount),
            color = CommentColor.TextSecondary,
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
    state.totalCount = page.totalCount
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
      state.totalCount = page.totalCount
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      state.loadMoreError = error.message.orEmpty()
    } finally {
      state.loadingMore = false
    }
  }
}

// ---- YouTube 评论 ----

/** YouTube 评论列表状态（/next + continuation 续页）。直接持有 [YoutubeComment] 渲染增强字段。 */
@Stable
internal class MobileYoutubeCommentListState {
  var comments by mutableStateOf<List<YoutubeComment>>(emptyList())
  var loading by mutableStateOf(true)
  var loadingMore by mutableStateOf(false)
  var error by mutableStateOf("")
  var loadMoreError by mutableStateOf("")
  var endReached by mutableStateOf(false)
  /** 下一页 continuation token；null 表示到底。 */
  var continuation: String? = null
}

/**
 * YouTube 视频评论列表(触屏)。数据走 VideoRepository.getYoutubeComments(/next)，
 * 滚动到末尾自动用 continuation 续页；把 YoutubeComment 映射成 B 站 [Comment] 复用 [CommentItem] 渲染。
 * YouTube 无 aid，故不接评论总数回调(评论 Tab 标题不带计数)；无排序切换(取默认排序)。
 */
@Composable
internal fun MobileYoutubeCommentList(
  videoId: String,
  videoRepository: VideoRepository,
  modifier: Modifier = Modifier,
) {
  val state = remember { MobileYoutubeCommentListState() }
  val coroutineScope = rememberCoroutineScope()
  val listState = rememberLazyListState()

  LaunchedEffect(videoId) {
    loadYoutubeCommentFirstPage(videoRepository, state, videoId)
  }

  // 触屏翻页:可见末尾临近时触发下一页。
  val nearEnd by remember {
    derivedStateOf {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
      last >= 0 && last >= state.comments.size - 3
    }
  }
  LaunchedEffect(nearEnd) {
    if (nearEnd) loadYoutubeCommentNextPage(videoRepository, coroutineScope, state, videoId)
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .background(Color.White),
  ) {
    when {
      state.loading && state.comments.isEmpty() -> CircularProgressIndicator(
        color = BiliColors.BiliPink,
        modifier = Modifier.align(Alignment.Center),
      )
      state.error.isNotBlank() && state.comments.isEmpty() -> Text(
        text = state.error,
        color = CommentColor.TextSecondary,
        modifier = Modifier.align(Alignment.Center),
      )
      state.comments.isEmpty() -> Text(
        text = stringResource(R.string.comment_empty),
        color = CommentColor.TextSecondary,
        modifier = Modifier.align(Alignment.Center),
      )
      else -> LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(state.comments, key = { it.commentId }) { comment ->
          YoutubeCommentItem(comment)
        }
        item {
          YoutubeCommentFooter(state = state)
        }
      }
    }
  }
}

@Composable
private fun YoutubeCommentFooter(state: MobileYoutubeCommentListState) {
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
        color = CommentColor.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
      )
      state.endReached -> Text(
        text = "没有更多了",
        color = CommentColor.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

/** 移动端 YouTube 评论项：直接渲染 [YoutubeComment]，显示认证✓/置顶📌/作者点赞❤/回复数。 */
@Composable
private fun YoutubeCommentItem(comment: YoutubeComment) {
  Row(modifier = Modifier.fillMaxWidth()) {
    AsyncImage(
      model = comment.authorAvatarUrl,
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
        Row(
          modifier = Modifier.weight(1f, fill = false),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = comment.authorName.ifBlank { stringResource(R.string.comment_anonymous) },
            color = if (comment.channelOwner) BiliColors.BiliPink else CommentColor.TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (comment.verified) {
            Spacer(Modifier.size(4.dp))
            Text("✓", color = BiliColors.BiliPink, style = MaterialTheme.typography.bodySmall)
          }
          if (comment.pinned) {
            Spacer(Modifier.size(4.dp))
            Text("📌", color = CommentColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
          }
          if (comment.hearted) {
            Spacer(Modifier.size(4.dp))
            Text("❤", color = BiliColors.BiliPink, style = MaterialTheme.typography.bodySmall)
          }
        }
        Text(
          text = formatCommentRelativeTime(comment.publishedAt ?: 0L),
          color = CommentColor.TextSecondary,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Spacer(Modifier.size(4.dp))
      Text(
        text = comment.content.ifBlank { stringResource(R.string.comment_empty_content) },
        color = CommentColor.TextPrimary,
        style = MaterialTheme.typography.bodyMedium,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.size(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if ((comment.likeCount ?: 0L) > 0L) {
          Text(
            text = stringResource(
              R.string.comment_like_count,
              (comment.likeCount ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ),
            color = CommentColor.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        if (comment.replyCount > 0) {
          Text(
            text = stringResource(R.string.comment_reply_count, comment.replyCount),
            color = CommentColor.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

private suspend fun loadYoutubeCommentFirstPage(
  videoRepository: VideoRepository,
  state: MobileYoutubeCommentListState,
  videoId: String,
) {
  state.loading = true
  state.error = ""
  state.loadMoreError = ""
  state.endReached = false
  state.continuation = null
  state.comments = emptyList()
  try {
    val page = videoRepository.getYoutubeComments(videoId, null)
    state.comments = page.items
    state.continuation = page.continuation
    state.endReached = page.continuation == null || page.items.isEmpty()
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    state.error = error.message.orEmpty()
  } finally {
    state.loading = false
  }
}

private fun loadYoutubeCommentNextPage(
  videoRepository: VideoRepository,
  coroutineScope: CoroutineScope,
  state: MobileYoutubeCommentListState,
  videoId: String,
) {
  if (state.loadingMore || state.endReached || state.loading) return
  val token = state.continuation ?: return
  state.loadingMore = true
  state.loadMoreError = ""
  coroutineScope.launch {
    try {
      val page = videoRepository.getYoutubeComments(videoId, token)
      state.continuation = page.continuation
      val known = state.comments.mapTo(mutableSetOf()) { it.commentId }
      val fresh = page.items.filter { known.add(it.commentId) }
      state.comments = state.comments + fresh
      state.endReached = page.continuation == null || fresh.isEmpty()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      state.loadMoreError = error.message.orEmpty()
    } finally {
      state.loadingMore = false
    }
  }
}