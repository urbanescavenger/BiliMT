package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.kirin.mt.core.image.buildFullImageRequest
import com.kirin.mt.core.model.DynamicImage
import com.kirin.mt.ui.settings.LocalBiliPerformancePolicy
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliFocus

/**
 * 动态大图查看器(P11-184):点图文图片打开,全屏黑底 + 左右翻页 + 双指缩放 / 双击放大 / 放大后拖动。
 *
 * 自研最小集(不引第三方看图库):**不做** BV 那套超大图渐进解码、下拉关闭、卡片↔大图共享元素过渡。
 * 用全屏 [Dialog] 承载,和 TV 的评论页同一范式 —— 不必把状态提到 shell 层就能盖住底栏。
 *
 * 交互:单击关闭(最容易退出)、双击在 1x/[BiliFocus.ImageViewerDoubleTapScale] 间切换、
 * 双指缩放到 [BiliFocus.ImageViewerMaxScale];图片按屏宽拼 CDN 尺寸后缀且**不裁切**。
 */
@Composable
internal fun DynamicImageViewer(
  images: List<DynamicImage>,
  initialIndex: Int,
  onDismiss: () -> Unit,
) {
  if (images.isEmpty()) return
  val pagerState = rememberPagerState(
    initialPage = initialIndex.coerceIn(0, images.lastIndex),
    pageCount = { images.size },
  )
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
    ),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black),
    ) {
      HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        ZoomableDynamicImage(image = images[page], onTap = onDismiss)
      }
      if (images.size > 1) {
        Text(
          text = "${pagerState.currentPage + 1}/${images.size}",
          style = MaterialTheme.typography.labelMedium,
          color = BiliColors.TextPrimary,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp),
        )
      }
    }
  }
}

@Composable
private fun ZoomableDynamicImage(image: DynamicImage, onTap: () -> Unit) {
  var scale by remember(image.url) { mutableFloatStateOf(1f) }
  var offset by remember(image.url) { mutableStateOf(Offset.Zero) }
  val context = LocalContext.current
  val policy = LocalBiliPerformancePolicy.current
  // 大图按屏宽取(px):整图不裁切,再靠 ContentScale.Fit 铺进屏幕。
  val screenWidthDp = LocalConfiguration.current.screenWidthDp
  val screenWidthPx = with(LocalDensity.current) { screenWidthDp.dp.roundToPx() }

  val transformState = rememberTransformableState { zoomChange, panChange, _ ->
    val next = (scale * zoomChange).coerceIn(1f, BiliFocus.ImageViewerMaxScale)
    scale = next
    // 回到 1x 时把位移清零,否则缩放回原状后图会停在偏位。
    offset = if (next <= 1f) Offset.Zero else offset + panChange
  }

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    AsyncImage(
      model = remember(image.url, screenWidthPx, policy.imageMemoryCacheEnabled) {
        buildFullImageRequest(
          context = context,
          url = image.url,
          widthPx = screenWidthPx,
          memoryCacheEnabled = policy.imageMemoryCacheEnabled,
        )
      },
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
          translationX = offset.x
          translationY = offset.y
        }
        // canPan 门控:1x 时不接管单指拖动,否则会把 Pager 的左右翻页手势一起吃掉;
        // 放大后才允许拖动看细节。
        .transformable(state = transformState, canPan = { scale > 1f })
        .pointerInput(image.url) {
          detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = {
              if (scale > 1f) {
                scale = 1f
                offset = Offset.Zero
              } else {
                scale = BiliFocus.ImageViewerDoubleTapScale
              }
            },
          )
        },
    )
  }
}
