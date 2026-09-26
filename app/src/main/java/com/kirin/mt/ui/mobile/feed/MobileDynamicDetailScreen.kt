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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.mobile.home.DynamicActionRow
import com.kirin.mt.ui.mobile.home.OwnerAvatar
import com.kirin.mt.ui.mobile.home.rememberVideoCardRelativeText
import com.kirin.mt.ui.mobile.player.CommentItem
import com.kirin.mt.ui.mobile.player.CommentListFooter
import com.kirin.mt.ui.mobile.player.CommentTarget
import com.kirin.mt.ui.mobile.player.MobileCommentListState
import com.kirin.mt.ui.mobile.player.SortChip
import com.kirin.mt.ui.mobile.player.loadCommentFirstPage
import com.kirin.mt.ui.mobile.player.loadCommentNextPage
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
 * 评论走 [CommentTarget.Dynamic](oid=dynId,type=11),复用播放器那套评论行/排序/分页。
 */
@Composable
internal fun MobileDynamicDetailScreen(
  video: VideoSummary,
  videoRepository: VideoRepository,
  onDismiss: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val policy = LocalBiliPerformancePolicy.current
  val listState = rememberLazyListState()
  var viewerTarget by remember { mutableStateOf<DynamicViewerTarget?>(null) }
  val commentState = remember { MobileCommentListState() }
  val commentTarget = remember(video.dynId) { CommentTarget.Dynamic(video.dynId) }
  val relativeText = rememberVideoCardRelativeText()
  val pubdate = video.pubdateText(relativeText)

  // 一级评论首页 + 切排序重载(与播放器评论同一套 loader)。
  LaunchedEffect(commentTarget, commentState.sort) {
    runCatching { loadCommentFirstPage(videoRepository, commentState, commentTarget) }
      .onFailure { error -> if (error is CancellationException) throw error }
  }
  // 滚到接近末尾时翻页。
  LaunchedEffect(commentTarget) {
    snapshotFlow {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = listState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 3
    }
      .distinctUntilChanged()
      .collect { nearEnd ->
        if (nearEnd) loadCommentNextPage(videoRepository, scope, commentState, commentTarget)
      }
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
                selected = commentState.sort == 1,
                onClick = { commentState.sort = 1 },
              )
              Spacer(modifier = Modifier.width(8.dp))
              SortChip(
                label = stringResource(R.string.comment_sort_latest),
                selected = commentState.sort == 0,
                onClick = { commentState.sort = 0 },
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
