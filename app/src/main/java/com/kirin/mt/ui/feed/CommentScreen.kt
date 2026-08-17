package com.kirin.mt.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.Comment
import com.kirin.mt.core.model.SourceBili
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.youtube.YoutubeComment
import com.kirin.mt.ui.common.BiliCapsuleTabRow
import com.kirin.mt.ui.common.BiliPillTab
import com.kirin.mt.ui.common.FeedStatusScreen
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
internal class CommentUiState {
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

internal data class CommentRequest(
  val aid: Long,
  val title: String,
  /** 内容来源：[SourceBili]（默认）/ [SourceYoutube]。 */
  val source: String = SourceBili,
  /** YouTube 视频 id（仅 [SourceYoutube] 填充，bvid 字段承载）。 */
  val videoId: String = "",
)

/**
 * 视频/动态评论页(TV)。从长按菜单「查看评论」进入。
 * B 站走 oid=aid、type=1;YouTube 走 /next + continuation 续页(对齐 LibreTube)。
 * Dialog 全屏:Back 关闭;顶部热门/最新排序 pill(B 站);列表焦点驱动翻页;楼中楼本期不展开。
 */
@Composable
internal fun CommentScreen(
  request: CommentRequest,
  videoRepository: VideoRepository,
  onDismiss: () -> Unit,
) {
  val isYoutube = request.source == SourceYoutube
  val state = remember { CommentUiState() }
  val youtubeState = remember { YoutubeCommentUiState() }
  val coroutineScope = rememberCoroutineScope()
  val sortFocusRequester = remember { FocusRequester() }

  LaunchedEffect(request.aid, state.sort) {
    if (!isYoutube) loadCommentsFirstPage(videoRepository, state, request.aid)
  }

  LaunchedEffect(request.videoId) {
    if (isYoutube) loadYoutubeCommentFirstPage(videoRepository, youtubeState, request.videoId)
  }

  LaunchedEffect(state.sort) {
    // 切换排序或首次打开后,焦点落到排序行选中项(B 站)。
    if (!isYoutube) runCatching { sortFocusRequester.requestFocus() }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
    ),
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(BiliColors.OverlayScrim.copy(alpha = 0.9f)),
    ) {
      if (isYoutube) {
        YoutubeCommentHeader(title = request.title)
      } else {
        CommentHeader(
          title = request.title,
          state = state,
          sortFocusRequester = sortFocusRequester,
          onSortChange = { sort ->
            if (sort != state.sort) {
              state.sort = sort
            }
          },
        )
      }
      when {
        isYoutube -> YoutubeCommentList(
          state = youtubeState,
          onRetry = {
            coroutineScope.launch {
              loadYoutubeCommentFirstPage(videoRepository, youtubeState, request.videoId)
            }
          },
          onLoadMore = {
            loadYoutubeCommentNextPage(videoRepository, coroutineScope, youtubeState, request.videoId)
          },
        )
        state.loading -> FeedStatusScreen(message = stringResource(R.string.comment_loading))
        state.error.isNotBlank() -> FeedStatusScreen(
          message = stringResource(R.string.comment_failed_with_message, state.error),
          actionLabel = stringResource(R.string.action_retry),
          onAction = {
            coroutineScope.launch {
              loadCommentsFirstPage(videoRepository, state, request.aid)
            }
          },
        )
        state.comments.isEmpty() -> FeedStatusScreen(message = stringResource(R.string.comment_empty))
        else -> CommentList(
          state = state,
          onLoadMore = { loadCommentsNextPage(videoRepository, coroutineScope, state, request.aid) },
        )
      }
    }
  }
}

@Composable
private fun CommentHeader(
  title: String,
  state: CommentUiState,
  sortFocusRequester: FocusRequester,
  onSortChange: (Int) -> Unit,
) {
  val homeColors = LocalHomeColors.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = BiliSpacing.Lg, vertical = BiliSpacing.Md),
  ) {
    Text(
      text = title.ifBlank { stringResource(R.string.nav_comment) },
      color = homeColors.textPrimary,
      fontSize = BiliTypography.SectionTitle,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(BiliSpacing.Sm))
    BiliCapsuleTabRow(itemCount = 2) {
      BiliPillTab(
        text = stringResource(R.string.comment_sort_hot),
        selected = state.sort == 1,
        modifier = if (state.sort == 1) Modifier.focusRequester(sortFocusRequester) else Modifier,
        onClick = { onSortChange(1) },
      )
      BiliPillTab(
        text = stringResource(R.string.comment_sort_latest),
        selected = state.sort == 0,
        onClick = { onSortChange(0) },
      )
    }
  }
}

@Composable
private fun CommentList(
  state: CommentUiState,
  onLoadMore: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = BiliSpacing.Lg),
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
  ) {
    itemsIndexed(state.comments, key = { _, c -> c.id }) { index, comment ->
      CommentItem(
        comment = comment,
        onFocused = {
          if (index >= state.comments.size - 3) {
            onLoadMore()
          }
        },
      )
    }
    item(key = "comment-footer") {
      CommentFooter(state = state, onRetry = onLoadMore)
    }
  }
}

@Composable
private fun CommentItem(
  comment: Comment,
  onFocused: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  var focused by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(BiliRadius.Card)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(if (focused) homeColors.textPrimary.copy(alpha = 0.08f) else Color.Transparent)
      .border(
        width = if (focused) BiliSpacing.Xxs else 0.dp,
        color = if (focused) homeColors.accent.copy(alpha = 0.4f) else Color.Transparent,
        shape = shape,
      )
      .onFocusChanged { focusState ->
        focused = focusState.isFocused
        if (focusState.isFocused) {
          onFocused()
        }
      }
      .onPreviewKeyEvent { event ->
        // 评论项只读:消费确认键避免触发任何默认行为。
        if (event.type == KeyEventType.KeyUp && event.key.isCommentConfirmKey()) {
          true
        } else {
          false
        }
      }
      .focusable()
      .padding(BiliSpacing.Md),
    verticalAlignment = Alignment.Top,
  ) {
    CommentAvatar(url = comment.avatar)
    Spacer(modifier = Modifier.width(BiliSpacing.Md))
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = comment.uname.ifBlank { stringResource(R.string.comment_anonymous) },
          color = homeColors.accent,
          fontSize = BiliTypography.CardMeta,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(BiliSpacing.Sm))
        Text(
          text = formatCommentRelativeTime(comment.ctime),
          color = homeColors.textTertiary,
          fontSize = BiliTypography.CardBadge,
          maxLines = 1,
        )
      }
      Spacer(modifier = Modifier.height(BiliSpacing.Xs))
      Text(
        text = comment.content.ifBlank { stringResource(R.string.comment_empty_content) },
        color = homeColors.textPrimary,
        fontSize = BiliTypography.BodySmall,
        overflow = TextOverflow.Visible,
      )
      Spacer(modifier = Modifier.height(BiliSpacing.Xs))
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (comment.likeCount > 0) {
          Text(
            text = stringResource(R.string.comment_like_count, comment.likeCount),
            color = homeColors.textSecondary,
            fontSize = BiliTypography.CardBadge,
          )
          Spacer(modifier = Modifier.width(BiliSpacing.Md))
        }
        if (comment.replyCount > 0) {
          Text(
            text = stringResource(R.string.comment_reply_count, comment.replyCount),
            color = homeColors.textSecondary,
            fontSize = BiliTypography.CardBadge,
          )
        }
      }
    }
  }
}

@Composable
private fun CommentAvatar(url: String) {
  val homeColors = LocalHomeColors.current
  val modifier = Modifier
    .size(BiliSpacing.Xxl)
    .clip(CircleShape)
    .background(homeColors.glassSurfaceStrong)
  if (url.isBlank()) {
    Box(modifier = modifier)
    return
  }
  AsyncImage(
    model = url,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = modifier,
  )
}

@Composable
private fun CommentFooter(state: CommentUiState, onRetry: () -> Unit) {
  val homeColors = LocalHomeColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = BiliSpacing.Md),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val text = when {
      state.loadMoreError.isNotBlank() -> stringResource(R.string.feed_footer_failed)
      state.loadingMore -> stringResource(R.string.feed_footer_loading)
      state.endReached -> stringResource(R.string.feed_footer_end)
      else -> ""
    }
    if (text.isNotBlank()) {
      Text(
        text = text,
        color = homeColors.textTertiary,
        fontSize = BiliTypography.CardMeta,
        maxLines = 1,
      )
    }
    if (state.loadMoreError.isNotBlank()) {
      Spacer(modifier = Modifier.width(BiliSpacing.Sm))
      CommentRetryButton(onRetry = onRetry)
    }
  }
}

@Composable
private fun CommentRetryButton(onRetry: () -> Unit) {
  val homeColors = LocalHomeColors.current
  var focused by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(BiliRadius.Pill)
  Box(
    modifier = Modifier
      .clip(shape)
      .background(if (focused) homeColors.accent.copy(alpha = 0.18f) else Color.Transparent)
      .border(
        width = if (focused) BiliSpacing.Xxs else BiliSpacing.Xxs,
        color = if (focused) homeColors.accent else homeColors.textPrimary.copy(alpha = 0.25f),
        shape = shape,
      )
      .onFocusChanged { focused = it.isFocused }
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp && event.key.isCommentConfirmKey()) {
          onRetry()
          true
        } else {
          false
        }
      }
      .focusable()
      .padding(horizontal = BiliSpacing.Sm, vertical = BiliSpacing.Xs),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = stringResource(R.string.action_retry),
      color = if (focused) homeColors.accent else homeColors.textSecondary,
      fontSize = BiliTypography.CardMeta,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
    )
  }
}

private fun Key.isCommentConfirmKey(): Boolean {
  return this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
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

private suspend fun loadCommentsFirstPage(
  videoRepository: VideoRepository,
  state: CommentUiState,
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

private fun loadCommentsNextPage(
  videoRepository: VideoRepository,
  coroutineScope: CoroutineScope,
  state: CommentUiState,
  aid: Long,
) {
  if (state.loadingMore || state.endReached || state.loading) {
    return
  }
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

// ---- YouTube 评论 ----

/** YouTube 评论列表状态（/next + continuation 续页）。 */
@Stable
internal class YoutubeCommentUiState {
  var comments by mutableStateOf<List<YoutubeComment>>(emptyList())
  var loading by mutableStateOf(true)
  var loadingMore by mutableStateOf(false)
  var error by mutableStateOf("")
  var loadMoreError by mutableStateOf("")
  var endReached by mutableStateOf(false)
  /** 下一页 continuation token；null 表示到底。 */
  var continuation: String? = null
}

@Composable
private fun YoutubeCommentHeader(title: String) {
  val homeColors = LocalHomeColors.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = BiliSpacing.Lg, vertical = BiliSpacing.Md),
  ) {
    Text(
      text = title.ifBlank { stringResource(R.string.nav_comment) },
      color = homeColors.textPrimary,
      fontSize = BiliTypography.SectionTitle,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun YoutubeCommentList(
  state: YoutubeCommentUiState,
  onRetry: () -> Unit,
  onLoadMore: () -> Unit,
) {
  when {
    state.loading && state.comments.isEmpty() -> FeedStatusScreen(
      message = stringResource(R.string.comment_loading),
    )
    state.error.isNotBlank() && state.comments.isEmpty() -> FeedStatusScreen(
      message = stringResource(R.string.comment_failed_with_message, state.error),
      actionLabel = stringResource(R.string.action_retry),
      onAction = onRetry,
    )
    state.comments.isEmpty() -> FeedStatusScreen(message = stringResource(R.string.comment_empty))
    else -> LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = BiliSpacing.Lg),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
    ) {
      itemsIndexed(state.comments, key = { _, c -> c.commentId }) { index, comment ->
        YoutubeCommentItem(
          comment = comment,
          onFocused = {
            if (index >= state.comments.size - 3) {
              onLoadMore()
            }
          },
        )
      }
      item(key = "youtube-comment-footer") {
        YoutubeCommentFooter(state = state, onRetry = onLoadMore)
      }
    }
  }
}

@Composable
private fun YoutubeCommentItem(
  comment: YoutubeComment,
  onFocused: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  var focused by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(BiliRadius.Card)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(if (focused) homeColors.textPrimary.copy(alpha = 0.08f) else Color.Transparent)
      .border(
        width = if (focused) BiliSpacing.Xxs else 0.dp,
        color = if (focused) homeColors.accent.copy(alpha = 0.4f) else Color.Transparent,
        shape = shape,
      )
      .onFocusChanged { focusState ->
        focused = focusState.isFocused
        if (focusState.isFocused) {
          onFocused()
        }
      }
      .onPreviewKeyEvent { event ->
        // 评论项只读:消费确认键避免触发任何默认行为。
        if (event.type == KeyEventType.KeyUp && event.key.isCommentConfirmKey()) {
          true
        } else {
          false
        }
      }
      .focusable()
      .padding(BiliSpacing.Md),
    verticalAlignment = Alignment.Top,
  ) {
    CommentAvatar(url = comment.authorAvatarUrl)
    Spacer(modifier = Modifier.width(BiliSpacing.Md))
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = comment.authorName.ifBlank { stringResource(R.string.comment_anonymous) },
          color = if (comment.channelOwner) homeColors.accent else homeColors.textPrimary,
          fontSize = BiliTypography.CardMeta,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (comment.verified) {
          Spacer(modifier = Modifier.width(BiliSpacing.Xs))
          Text("✓", color = homeColors.accent, fontSize = BiliTypography.CardBadge)
        }
        if (comment.pinned) {
          Spacer(modifier = Modifier.width(BiliSpacing.Xs))
          Text("📌", color = homeColors.textSecondary, fontSize = BiliTypography.CardBadge)
        }
        if (comment.hearted) {
          Spacer(modifier = Modifier.width(BiliSpacing.Xs))
          Text("❤", color = BiliColors.BiliPink, fontSize = BiliTypography.CardBadge)
        }
        Spacer(modifier = Modifier.width(BiliSpacing.Sm))
        Text(
          text = formatCommentRelativeTime(comment.publishedAt ?: 0L),
          color = homeColors.textTertiary,
          fontSize = BiliTypography.CardBadge,
          maxLines = 1,
        )
      }
      Spacer(modifier = Modifier.height(BiliSpacing.Xs))
      Text(
        text = comment.content.ifBlank { stringResource(R.string.comment_empty_content) },
        color = homeColors.textPrimary,
        fontSize = BiliTypography.BodySmall,
        overflow = TextOverflow.Visible,
      )
      Spacer(modifier = Modifier.height(BiliSpacing.Xs))
      Row(verticalAlignment = Alignment.CenterVertically) {
        if ((comment.likeCount ?: 0L) > 0L) {
          Text(
            text = stringResource(
              R.string.comment_like_count,
              (comment.likeCount ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ),
            color = homeColors.textSecondary,
            fontSize = BiliTypography.CardBadge,
          )
          Spacer(modifier = Modifier.width(BiliSpacing.Md))
        }
        if (comment.replyCount > 0) {
          Text(
            text = stringResource(R.string.comment_reply_count, comment.replyCount),
            color = homeColors.textSecondary,
            fontSize = BiliTypography.CardBadge,
          )
        }
      }
    }
  }
}

@Composable
private fun YoutubeCommentFooter(state: YoutubeCommentUiState, onRetry: () -> Unit) {
  val homeColors = LocalHomeColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = BiliSpacing.Md),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val text = when {
      state.loadMoreError.isNotBlank() -> stringResource(R.string.feed_footer_failed)
      state.loadingMore -> stringResource(R.string.feed_footer_loading)
      state.endReached -> stringResource(R.string.feed_footer_end)
      else -> ""
    }
    if (text.isNotBlank()) {
      Text(
        text = text,
        color = homeColors.textTertiary,
        fontSize = BiliTypography.CardMeta,
        maxLines = 1,
      )
    }
    if (state.loadMoreError.isNotBlank()) {
      Spacer(modifier = Modifier.width(BiliSpacing.Sm))
      CommentRetryButton(onRetry = onRetry)
    }
  }
}

private suspend fun loadYoutubeCommentFirstPage(
  videoRepository: VideoRepository,
  state: YoutubeCommentUiState,
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
  state: YoutubeCommentUiState,
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
