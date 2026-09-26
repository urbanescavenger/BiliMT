package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.core.image.BiliImageSizing
import com.kirin.mt.core.image.buildVideoThumbnailRequest
import com.kirin.mt.core.model.DynamicImage

/** 九宫格最多平铺几张,其余收进「+N」角标(对齐 BV DynamicItem 的 1/2/3+ 布局)。 */
private const val DynamicGridVisibleCount = 3

/**
 * 九宫格:1 图整宽(2:1)、2 图并排方格、≥3 取前 3 张方格 + 「+N」角标。
 * 每张都按格子尺寸拼 CDN 后缀再请求。
 */
@Composable
internal fun DynamicDrawPictures(
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
