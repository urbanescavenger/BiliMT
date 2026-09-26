package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.pubdateText
import com.kirin.mt.core.network.DynamicCommentModeHot
import com.kirin.mt.core.network.DynamicCommentModeLatest
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.mobile.home.DynamicActionRow
import com.kirin.mt.ui.mobile.home.OwnerAvatar
import com.kirin.mt.ui.mobile.home.rememberVideoCardRelativeText
import com.kirin.mt.ui.mobile.player.CommentItem
import com.kirin.mt.ui.mobile.player.CommentListFooter
import com.kirin.mt.ui.mobile.player.MobileCommentListState
import com.kirin.mt.ui.mobile.player.SortChip
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 动态详情页(P11-185):点图文卡正文区打开。
 *
 * 全屏 [Dialog](与查看器同范式,不必把状态提到 shell 层),单条滚动列表:
 * 作者行 → **正文不折叠**(列表卡折叠 6 行)→ 九宫格(点图开大图查看器)→ 互动计数 → 评论区(一级评论)。
 *
 * 数据直接用列表里那条已解析的动态,**不再请求详情接口** —— 实测 `x/polymer/web-dynamic/v1/detail`
 * 离线回 `-352`(风控参数未摸清),而列表数据的正文足够;超长正文可能被服务端截断,此时给一行提示。
 * 评论走 `/x/v2/reply/wbi/main`(type=11):**游标分页**(`pagination_str` 的 offset + `cursor.is_end`),
 * 与视频评论的页码分页不是一套,故这里自带游标状态;评论行/排序片/页脚复用播放器那套渲染件。
 */
@Composable
internal fun MobileDynamicDetailScreen(
  video: VideoSummary,
  videoRepository: VideoRepository,
  onDismiss: () -> Unit,
) {
  val policy = LocalBiliPerformancePolicy.current
  val listState = rememberLazyListState()
  var viewerTarget by remember { mutableStateOf<DynamicViewerTarget?>(null) }
  // 评论:state 只用于渲染(行/页脚),分页由下面的游标状态驱动。
  val commentState = remember { MobileCommentListState() }
  var commentMode by remember(video.dynId) { mutableStateOf(DynamicCommentModeHot) }
  var commentOffset by remember(video.dynId) { mutableStateOf("") }
  var commentEnd by remember(video.dynId) { mutableStateOf(false) }
  val relativeText = rememberVideoCardRelativeText()
  val pubdate = video.pubdateText(relativeText)

  /** 拉一页评论:reset=true 换排序/首次进页时重来,否则按游标续拉。 */
  suspend fun loadComments(reset: Boolean) {
    if (commentState.loadingMore || commentState.loading) return
    if (!reset && commentEnd) return
    val mode = commentMode
    val offset = if (reset) "" else commentOffset
    if (reset) {
      commentState.loading = true
      commentState.error = ""
      commentState.comments = emptyList()
      commentState.totalCount = 0
    } else {
      commentState.loadingMore = true
    }
    commentState.loadMoreError = ""
    try {
      val page = videoRepository.getDynamicComments(dynId = video.dynId, mode = mode, offset = offset)
      commentState.comments = if (reset) {
        page.comments
      } else {
        val known = commentState.comments.mapTo(mutableSetOf()) { it.id }
        commentState.comments + page.comments.filter { known.add(it.id) }
      }
      commentOffset = page.nextOffset
      commentEnd = page.isEnd || page.comments.isEmpty()
      commentState.endReached = commentEnd
      commentState.totalCount = page.totalCount
      commentState.currentPage += 1
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      val brief = error.message.orEmpty()
      if (reset) commentState.error = brief else commentState.loadMoreError = brief
    } finally {
      commentState.loading = false
      commentState.loadingMore = false
    }
  }

  // 进页 / 切排序:重拉首页。
  LaunchedEffect(video.dynId, commentMode) {
    commentOffset = ""
    commentEnd = false
    loadComments(reset = true)
  }
  // 滚到接近末尾时按游标续拉。
  LaunchedEffect(video.dynId, commentMode) {
    snapshotFlow {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = listState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 3
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) loadComments(reset = false) }
  }

  viewerTarget?.let { target ->
    DynamicImageViewer(
      images = target.images,
      initialIndex = target.index,
      onDismiss = { viewerTarget = null },
    )
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
        .background(MaterialTheme.colorScheme.background),
    ) {
      // 顶栏:返回 + 标题。
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = onDismiss) { Text("‹") }
        Text(
          text = stringResource(R.string.mobile_dynamic_detail_title),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(start = 4.dp),
        )
      }
      HorizontalDivider()

      LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "detail-header") {
          Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              OwnerAvatar(face = video.ownerFace, size = 40.dp)
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text(
                  text = video.ownerName,
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                if (pubdate.isNotBlank()) {
                  Text(
                    text = pubdate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }

            if (video.dynamicText.isNotBlank()) {
              DynamicRichText(
                video = video,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 10.dp),
              )
              if (video.dynamicTextHasMore) {
                Text(
                  text = stringResource(R.string.mobile_dynamic_detail_truncated),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(top = 6.dp),
                )
              }
            }

            if (video.dynamicImages.isNotEmpty()) {
              DynamicDrawPictures(
                images = video.dynamicImages,
                allowRgb565 = policy.videoThumbnailRgb565Enabled,
                memoryCacheEnabled = policy.imageMemoryCacheEnabled,
                onImageClick = { images, index -> viewerTarget = DynamicViewerTarget(images, index) },
                modifier = Modifier.padding(top = 10.dp),
              )
            }

            DynamicActionRow(video = video, modifier = Modifier.padding(top = 10.dp))
          }
        }

        item(key = "comment-header") {
          Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider()
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = if (commentState.totalCount > 0) {
                  stringResource(R.string.player_comment_count_format, commentState.totalCount)
                } else {
                  stringResource(R.string.player_control_comment)
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Spacer(modifier = Modifier.weight(1f))
              SortChip(
                label = stringResource(R.string.comment_sort_hot),
                selected = commentMode == DynamicCommentModeHot,
                onClick = { commentMode = DynamicCommentModeHot },
              )
              Spacer(modifier = Modifier.width(8.dp))
              SortChip(
                label = stringResource(R.string.comment_sort_latest),
                selected = commentMode == DynamicCommentModeLatest,
                onClick = { commentMode = DynamicCommentModeLatest },
              )
            }
          }
        }

        if (commentState.loading && commentState.comments.isEmpty()) {
          item(key = "comment-loading") {
            Box(
              modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
              contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
          }
        } else if (commentState.error.isNotBlank() && commentState.comments.isEmpty()) {
          item(key = "comment-error") {
            Box(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.comment_failed_with_message, commentState.error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else if (commentState.comments.isEmpty()) {
          item(key = "comment-empty") {
            Box(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.comment_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          items(commentState.comments, key = { it.id }) { comment ->
            CommentItem(comment = comment)
          }
          item(key = "comment-footer") {
            CommentListFooter(state = commentState)
          }
        }
      }
    }
  }
}
