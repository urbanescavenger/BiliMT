package com.kirin.mt.ui.mobile.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.core.image.buildOwnerAvatarRequest
import com.kirin.mt.core.model.VideoSummary

/** 移动端视频卡片:纯触屏(无焦点缩放),clickable 点击播放。点头像/UP 名区域进 UP 主页。 */
@Composable
fun MobileVideoCard(
  video: VideoSummary,
  onClick: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
  onOpenOwner: ((VideoSummary) -> Unit)? = null,
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
      // 头像 + UP 名:整行可点进 UP 主页(对齐 TV VideoCard owner 行)。
      Row(
        modifier = Modifier
          .weight(1f, fill = false)
          .clip(RoundedCornerShape(4.dp))
          .clickable(enabled = onOpenOwner != null && video.ownerMid > 0) {
            onOpenOwner?.invoke(video)
          },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OwnerAvatar(face = video.ownerFace)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = video.ownerName,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = formatCount(if (video.view > 0) video.view else video.likeCount),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** UP 主圆形头像:20dp,空 face 纯色占位;复用 buildOwnerAvatarRequest 带 Bili 头与 CDN 尺寸。 */
@Composable
private fun OwnerAvatar(face: String) {
  val modifier = Modifier
    .size(20.dp)
    .clip(CircleShape)
    .background(MaterialTheme.colorScheme.surfaceVariant)
  if (face.isBlank()) {
    Box(modifier = modifier)
    return
  }
  val context = LocalContext.current
  AsyncImage(
    model = remember(context, face) { buildOwnerAvatarRequest(context, face) },
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = modifier,
  )
}

/** 播放量/弹幕数等计数格式化:万以上用"万"。 */
fun formatCount(count: Int): String {
  if (count < 10_000) return count.toString()
  val wan = count / 10_000.0
  return if (wan >= 100) "${wan.toInt()}万" else "${"%.1f".format(wan)}万"
}