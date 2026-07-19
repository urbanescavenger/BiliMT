package com.kirin.mt.ui.mobile.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.core.model.VideoSummary

/** 移动端视频卡片:纯触屏(无焦点缩放),clickable 点击播放。 */
@Composable
fun MobileVideoCard(
  video: VideoSummary,
  onClick: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clickable { onClick(video) },
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 10f)
        .clip(RoundedCornerShape(12.dp)),
    ) {
      AsyncImage(
        model = video.pic,
        contentDescription = video.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth(),
      )
      if (video.badge.isNotEmpty()) {
        Text(
          text = video.badge,
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp),
        )
      }
    }
    Text(
      text = video.title,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 6.dp),
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 2.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = video.ownerName,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Text(
        text = formatCount(if (video.view > 0) video.view else video.likeCount),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** 播放量/弹幕数等计数格式化:万以上用"万"。 */
fun formatCount(count: Int): String {
  if (count < 10_000) return count.toString()
  val wan = count / 10_000.0
  return if (wan >= 100) "${wan.toInt()}万" else "${"%.1f".format(wan)}万"
}