package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.DynamicImage
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.pubdateText
import com.kirin.mt.ui.mobile.home.DynamicActionRow
import com.kirin.mt.ui.mobile.home.OwnerAvatar
import com.kirin.mt.ui.mobile.home.rememberVideoCardRelativeText
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy

/** 正文默认折叠到这个行数,超出给「展开」(B站 官方也是折叠到几行 + 展开)。 */
private const val DynamicTextCollapsedLines = 6

/**
 * 动态图文卡(P11-181):顶行作者块 → 正文(可折叠)→ 九宫格 → 点赞/评论/转发计数。
 *
 * 布局参照 B站 官方动态与 BV `DynamicItem`:1 图整宽 2:1;2 图并排 1:1;≥3 取前 3 张 1:1 + 右下「+N」。
 * 图片**一律显式拼 CDN 尺寸后缀**(九图动态否则会拉 9 张原图),并跟随性能档:低配/RGB_565 档
 * 用 RGB_565 且关内存缓存(与视频卡同一套 [LocalBiliPerformancePolicy])。
 *
 * 交互:点图 → 大图查看器;点正文/计数区 → 动态详情页;点作者块 → UP 主页。
 */
@Composable
internal fun MobileDynamicDrawCard(
  video: VideoSummary,
  modifier: Modifier = Modifier,
  onOpenOwner: ((VideoSummary) -> Unit)? = null,
  /** 点图片回调(图片列表 + 起始下标),由调用方打开大图查看器。 */
  onImageClick: ((List<DynamicImage>, Int) -> Unit)? = null,
  /** 点正文/计数区回调:打开动态详情页(点图片仍走查看器)。 */
  onOpenDetail: (() -> Unit)? = null,
) {
  val policy = LocalBiliPerformancePolicy.current
  val relativeText = rememberVideoCardRelativeText()
  val pubdate = video.pubdateText(relativeText)

  var expanded by remember(video.dynId) { mutableStateOf(false) }
  var textOverflowed by remember(video.dynId) { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 6.dp)
        .clip(RoundedCornerShape(4.dp))
        .clickable(enabled = onOpenOwner != null) { onOpenOwner?.invoke(video) },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OwnerAvatar(face = video.ownerFace, size = 40.dp)
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = video.ownerName,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (pubdate.isNotBlank()) {
          Text(
            text = pubdate,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
      }
    }

    if (video.dynamicText.isNotBlank()) {
      // 走富文本渲染:B站 正文里的表情必须内联成图,否则只显示 [保佑] 这类占位文字(P11-183)。
      DynamicRichText(
        video = video,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clickable(enabled = onOpenDetail != null) { onOpenDetail?.invoke() },
        maxLines = if (expanded) Int.MAX_VALUE else DynamicTextCollapsedLines,
        onTextLayout = { layout ->
          val overflowed = layout.hasVisualOverflow
          if (textOverflowed != overflowed) textOverflowed = overflowed
        },
      )
      // 折叠时被截断,或服务端明说还有更多(opus.summary.has_more)时才给开关。
      if (textOverflowed || video.dynamicTextHasMore) {
        Text(
          text = stringResource(
            if (expanded) R.string.mobile_dynamic_collapse else R.string.mobile_dynamic_expand,
          ),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable {
              expanded = !expanded
              // 收起后要重新量一次是否溢出,否则开关会因残留状态消失。
              textOverflowed = false
            }
            .padding(horizontal = 2.dp, vertical = 2.dp),
        )
      }
    }

    if (video.dynamicImages.isNotEmpty()) {
      DynamicDrawPictures(
        images = video.dynamicImages,
        allowRgb565 = policy.videoThumbnailRgb565Enabled,
        memoryCacheEnabled = policy.imageMemoryCacheEnabled,
        onImageClick = onImageClick,
        modifier = Modifier.padding(top = 6.dp),
      )
    }

    // 与视频动态卡共用同一计数行(转发/评论/点赞),保证两类卡片观感一致。
    // 整行可点进详情(点「评论 N」想进详情是最自然的直觉);图片区在上层,不受影响。
    Box(modifier = Modifier.clickable(enabled = onOpenDetail != null) { onOpenDetail?.invoke() }) {
      DynamicActionRow(video = video, modifier = Modifier.padding(top = 6.dp))
    }
  }
}
