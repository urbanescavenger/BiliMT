package com.kirin.mt.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.core.model.UgcBannerItem
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliRadius
import kotlinx.coroutines.delay

/**
 * UGC 分区顶部轮播 banner（对齐 BV `Carousel`）：单张封面，自动轮播（获焦暂停），
 * 左右键循环切换，OK 起播对应视频（cid 由播放器经 `/x/web-interface/view` 解析）。
 * 整个轮播是一个可聚焦单元；↑ 回分区 Tab、↓ 进网格。
 */
@Composable
internal fun UgcBannerCarousel(
  items: List<UgcBannerItem>,
  focusRequester: FocusRequester,
  onMoveUp: () -> Boolean,
  onMoveDown: () -> Boolean,
  onSelected: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (items.isEmpty()) return
  var currentIndex by remember { mutableIntStateOf(0) }
  var hasFocus by remember { mutableStateOf(false) }
  val itemCount = items.size
  val safeIndex = currentIndex.coerceIn(0, itemCount - 1)
  if (safeIndex != currentIndex) currentIndex = safeIndex

  LaunchedEffect(currentIndex, itemCount, hasFocus) {
    if (!hasFocus && itemCount > 1) {
      delay(BannerAutoScrollIntervalMs)
      currentIndex = (currentIndex + 1) % itemCount
    }
  }

  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = true,
    shape = RoundedCornerShape(BiliRadius.Card),
    onClick = { onSelected(items[currentIndex.coerceIn(0, itemCount - 1)].toVideoSummary()) },
    onFocusChanged = { hasFocus = it },
    modifier = modifier
      .focusRequester(focusRequester)
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.DirectionLeft -> {
            if (itemCount > 1) currentIndex = (currentIndex - 1 + itemCount) % itemCount
            true
          }
          Key.DirectionRight -> {
            if (itemCount > 1) currentIndex = (currentIndex + 1) % itemCount
            true
          }
          Key.DirectionUp -> onMoveUp()
          Key.DirectionDown -> onMoveDown()
          else -> false
        }
      },
  ) {
    val item = items[currentIndex.coerceIn(0, itemCount - 1)]
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(BannerHeight)
        .clip(RoundedCornerShape(BiliRadius.Card)),
    ) {
      AsyncImage(
        model = item.cover,
        contentDescription = item.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

private fun UgcBannerItem.toVideoSummary(): VideoSummary {
  return VideoSummary(
    bvid = bvid,
    title = title,
    pic = cover,
    ownerName = "",
    ownerFace = "",
    ownerMid = 0L,
    view = 0,
    danmaku = 0,
    duration = 0,
    pubdate = 0L,
    badge = "",
  )
}

private val BannerHeight = 180.dp
private const val BannerAutoScrollIntervalMs = 6000L