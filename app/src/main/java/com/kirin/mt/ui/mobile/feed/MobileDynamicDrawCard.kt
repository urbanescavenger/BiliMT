package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.image.BiliImageSizing
import com.kirin.mt.core.image.buildVideoThumbnailRequest
import com.kirin.mt.core.model.DynamicImage
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.pubdateText
import com.kirin.mt.ui.mobile.home.DynamicActionRow
import com.kirin.mt.ui.mobile.home.OwnerAvatar
import com.kirin.mt.ui.mobile.home.rememberVideoCardRelativeText
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy

/** 正文默认折叠到这个行数,超出给「展开」(B站 官方也是折叠到几行 + 展开)。 */
private const val DynamicTextCollapsedLines = 6

/** 九宫格最多平铺几张,其余收进「+N」角标(对齐 BV DynamicItem 的 1/2/3+ 布局)。 */
private const val DynamicGridVisibleCount = 3

/**
 * 动态图文卡(P11-181):顶行作者块 → 正文(可折叠)→ 九宫格 → 点赞/评论/转发计数。
 *
 * 布局参照 B站 官方动态与 BV `DynamicItem`:1 图整宽 2:1;2 图并排 1:1;≥3 取前 3 张 1:1 + 右下「+N」。
 * 图片**一律显式拼 CDN 尺寸后缀**(九图动态否则会拉 9 张原图),并跟随性能档:低配/RGB_565 档
 * 用 RGB_565 且关内存缓存(与视频卡同一套 [LocalBiliPerformancePolicy])。
 *
 * 本轮(V1)整卡与图片都不可点:点卡片进详情页、点图看大图分别在后续切片接入;作者块可点进 UP 主页。
 */
@Composable
internal fun MobileDynamicDrawCard(
  video: VideoSummary,
  modifier: Modifier = Modifier,
  onOpenOwner: ((VideoSummary) -> Unit)? = null,
  /** 点图片回调(图片列表 + 起始下标),由调用方打开大图查看器。 */
  onImageClick: ((List<DynamicImage>, Int) -> Unit)? = null,
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
    DynamicActionRow(video = video, modifier = Modifier.padding(top = 6.dp))
  }
}

/**
 * 九宫格:1 图整宽(2:1)、2 图并排方格、≥3 取前 3 张方格 + 「+N」角标。
 * 每张都按格子尺寸拼 CDN 后缀再请求。
 */
@Composable
private fun DynamicDrawPictures(
  images: List<DynamicImage>,
  allowRgb565: Boolean,
  memoryCacheEnabled: Boolean,
  onImageClick: ((List<DynamicImage>, Int) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(8.dp)
  Box(modifier = modifier.fillMaxWidth()) {
    when {
      images.size == 1 -> DynamicPicture(
        image = images.first(),
        widthPx = BiliImageSizing.DynamicDrawSingleWidthPx,
        heightPx = BiliImageSizing.DynamicDrawSingleHeightPx,
        allowRgb565 = allowRgb565,
        memoryCacheEnabled = memoryCacheEnabled,
        onClick = onImageClick?.let { click -> { click(images, 0) } },
        modifier = Modifier.fillMaxWidth().aspectRatio(2f).clip(shape),
      )

      else -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        images.take(DynamicGridVisibleCount).forEachIndexed { index, image ->
          DynamicPicture(
            image = image,
            widthPx = BiliImageSizing.DynamicDrawGridSizePx,
            heightPx = BiliImageSizing.DynamicDrawGridSizePx,
            allowRgb565 = allowRgb565,
            memoryCacheEnabled = memoryCacheEnabled,
            onClick = onImageClick?.let { click -> { click(images, index) } },
            modifier = Modifier.weight(1f).aspectRatio(1f).clip(shape),
          )
        }
      }
    }
    if (images.size > DynamicGridVisibleCount) {
      Text(
        text = "+${images.size - DynamicGridVisibleCount}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .clip(RoundedCornerShape(bottomEnd = 8.dp, topStart = 8.dp))
          .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
          // 点「+N」直接看第 4 张起(与点前 3 张同一个查看器)。
          .clickable(enabled = onImageClick != null) { onImageClick?.invoke(images, DynamicGridVisibleCount) }
          .padding(horizontal = 6.dp, vertical = 1.dp),
      )
    }
  }
}

@Composable
private fun DynamicPicture(
  image: DynamicImage,
  widthPx: Int,
  heightPx: Int,
  allowRgb565: Boolean,
  memoryCacheEnabled: Boolean,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  AsyncImage(
    model = remember(context, image.url, widthPx, heightPx, allowRgb565, memoryCacheEnabled) {
      buildVideoThumbnailRequest(
        context = context,
        url = image.url,
        widthPx = widthPx,
        heightPx = heightPx,
        allowRgb565 = allowRgb565,
        memoryCacheEnabled = memoryCacheEnabled,
      )
    },
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
  )
}
