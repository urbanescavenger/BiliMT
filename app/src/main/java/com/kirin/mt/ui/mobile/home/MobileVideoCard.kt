package com.kirin.mt.ui.mobile.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.image.buildOwnerAvatarRequest
import com.kirin.mt.core.model.SourceIptv
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoCardRelativeText
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.pubdateText
import com.kirin.mt.ui.i18n.formatCompactCount
import com.kirin.mt.ui.i18n.localeFromResources
import com.kirin.mt.ui.theme.BiliColors

/** YouTube 卡片绿框颜色(Material Green 600),动态页区分 YouTube 与 B 站内容。 */
private val YoutubeBorderColor = Color(0xFF00C853)

/**
 * 已看完的视频 id 集合(B站 bvid / YouTube videoId,统一用 VideoSummary.bvid)。
 * 由 BiliMobileApp 收集 WatchedStore 后经 CompositionLocal 下发,卡片据此在缩略图右下角
 * 渲染「已看完」角标;默认空集(未提供时不显示)。
 */
val LocalWatchedIds = staticCompositionLocalOf<Set<String>> { emptySet() }

/** 移动端视频卡片:纯触屏(无焦点缩放),点击播放;长按加入/移除播放列表(仅 YouTube)。点头像/UP 名区域进 UP 主页。
 *  [feedLayout]=true 用动态 feed 版布局(顶行作者块 + 缩略图 + 标题),默认紧凑布局(首页/搜索/空间等)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileVideoCard(
  video: VideoSummary,
  onClick: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
  onOpenOwner: ((VideoSummary) -> Unit)? = null,
  onLongPress: ((VideoSummary) -> Unit)? = null,
  showYoutubeBorder: Boolean = false,
  feedLayout: Boolean = false,
  // 封面覆盖图(Coil data,可传 Bitmap)。IPTV 频道用拉流截帧的缩略图覆盖 logo(镜像 TV VideoCard.coverOverride)。
  coverOverride: Any? = null,
) {
  val youtubeBorder = showYoutubeBorder && video.source == SourceYoutube
  // 已看完(播放到结尾):bvid/videoId 命中本地 watched 集合;直播/IPTV 不标。
  val completed = !video.isLive && video.bvid in LocalWatchedIds.current
  val baseModifier = modifier
    .fillMaxWidth()
    .then(if (youtubeBorder) Modifier.border(2.dp, YoutubeBorderColor, RoundedCornerShape(12.dp)) else Modifier)
    .combinedClickable(
      onClick = { onClick(video) },
      onLongClick = onLongPress?.let { { it(video) } },
    )
  if (feedLayout) {
    FeedStyleCardContent(video = video, modifier = baseModifier, onOpenOwner = onOpenOwner, coverOverride = coverOverride, completed = completed)
  } else {
    CompactStyleCardContent(video = video, modifier = baseModifier, onOpenOwner = onOpenOwner, coverOverride = coverOverride, completed = completed)
  }
}

/** 紧凑布局(默认):缩略图 → 标题 → 头像/UP名/播放量。首页/搜索/空间等使用。 */
@Composable
private fun CompactStyleCardContent(
  video: VideoSummary,
  modifier: Modifier,
  onOpenOwner: ((VideoSummary) -> Unit)?,
  coverOverride: Any? = null,
  completed: Boolean = false,
) {
  Column(modifier = modifier) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 10f)
        .clip(RoundedCornerShape(12.dp)),
    ) {
      AsyncImage(
        model = coverOverride ?: video.pic,
        contentDescription = video.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth(),
      )
      if (video.isLive) {
        LiveBadge(text = video.badge.ifBlank { stringResource(R.string.mobile_live) }, modifier = Modifier.align(Alignment.TopStart))
      } else if (video.badge.isNotEmpty() && video.source != SourceIptv) {
        Text(
          text = video.badge,
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp),
        )
      }
      if (completed) {
        CompletedBadge(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
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
          .clickable(enabled = onOpenOwner != null && ownerClickable(video)) {
            onOpenOwner?.invoke(video)
          },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OwnerAvatar(face = video.ownerFace, isLive = video.isLive)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = video.ownerName,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (video.isLive) {
        LiveOnlineCount(online = video.view, areaName = video.liveAreaName)
      } else {
        Text(
          text = formatCount(if (video.view > 0) video.view else video.likeCount, LocalContext.current.resources),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** 动态 feed 布局(参考 B 站原版动态):顶行作者块(头像跨两行 + UP名/发布时间·播放量) → 缩略图独占整行 → 标题。 */
@Composable
private fun FeedStyleCardContent(
  video: VideoSummary,
  modifier: Modifier,
  onOpenOwner: ((VideoSummary) -> Unit)?,
  coverOverride: Any? = null,
  completed: Boolean = false,
) {
  val relativeText = rememberVideoCardRelativeText()
  val count = formatCount(if (video.view > 0) video.view else video.likeCount, LocalContext.current.resources)
  val pubdate = video.pubdateText(relativeText)
  val meta = if (pubdate.isBlank()) count else "$pubdate · $count"
  Column(modifier = modifier) {
    // 顶行作者块:头像跨两行,右侧第一行 UP 名、第二行发布时间 + 播放量。整块可点进 UP 主页。
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 6.dp)
        .clip(RoundedCornerShape(4.dp))
        .clickable(enabled = onOpenOwner != null && ownerClickable(video)) {
          onOpenOwner?.invoke(video)
        },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OwnerAvatar(face = video.ownerFace, isLive = video.isLive, size = 40.dp)
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f, fill = false)) {
        Text(
          text = video.ownerName,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (video.isLive) {
          LiveOnlineCount(online = video.view, areaName = video.liveAreaName)
        } else {
          Text(
            text = meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    // 缩略图独占整行(16:10、直播角标、YouTube 绿框保留)。
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 10f)
        .clip(RoundedCornerShape(12.dp)),
    ) {
      AsyncImage(
        model = coverOverride ?: video.pic,
        contentDescription = video.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth(),
      )
      if (video.isLive) {
        LiveBadge(text = video.badge.ifBlank { stringResource(R.string.mobile_live) }, modifier = Modifier.align(Alignment.TopStart))
      } else if (video.badge.isNotEmpty() && video.source != SourceIptv) {
        Text(
          text = video.badge,
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp),
        )
      }
      if (completed) {
        CompletedBadge(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
      }
    }
    // 标题在底部。
    Text(
      text = video.title,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 6.dp),
    )
  }
}

/** 相对时间文本(3分钟前/昨天/5天前等),复用 TV 同款字符串资源。 */
@Composable
private fun rememberVideoCardRelativeText(): VideoCardRelativeText {
  val minutesAgoFormat = stringResource(R.string.video_relative_minutes_ago)
  val hoursAgoFormat = stringResource(R.string.video_relative_hours_ago)
  val yesterday = stringResource(R.string.video_relative_yesterday)
  val daysAgoFormat = stringResource(R.string.video_relative_days_ago)
  return remember(minutesAgoFormat, hoursAgoFormat, yesterday, daysAgoFormat) {
    VideoCardRelativeText(
      viewedSuffixFormat = "",
      minutesAgoFormat = minutesAgoFormat,
      hoursAgoFormat = hoursAgoFormat,
      yesterday = yesterday,
      daysAgoFormat = daysAgoFormat,
    )
  }
}

/** 直播角标:红色圆角 pill + 左侧白色小圆点 + "直播"文字,贴封面左上(调用处用 BoxScope.align 定位)。 */
@Composable
private fun LiveBadge(text: String, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .padding(6.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(BiliColors.BiliPink)
      .padding(horizontal = 5.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(4.dp)
        .clip(CircleShape)
        .background(Color.White),
    )
    Spacer(modifier = Modifier.width(3.dp))
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = Color.White,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

/** 「已看完」角标:深色半透明圆角 pill + 白色文字,贴缩略图右下(调用处用 BoxScope.align 定位)。 */
@Composable
private fun CompletedBadge(modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .clip(RoundedCornerShape(4.dp))
      .background(Color.Black.copy(alpha = 0.6f))
      .padding(horizontal = 5.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(R.string.video_watch_completed),
      style = MaterialTheme.typography.labelSmall,
      color = Color.White,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

/** 直播在线人数:红色小圆点 + 在线数(万用"万")+ 可选分区名。 */
@Composable
private fun LiveOnlineCount(online: Int, areaName: String, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(5.dp)
        .clip(CircleShape)
        .background(BiliColors.BiliPink),
    )
    Spacer(modifier = Modifier.width(3.dp))
    if (online > 0) {
      Text(
        text = formatCount(online, LocalContext.current.resources),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
    if (areaName.isNotEmpty()) {
      Text(
        text = "· $areaName",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 2.dp),
      )
    }
  }
}

/** UP 主圆形头像:默认 20dp(紧凑卡),动态 feed 用 40dp(跨两行);空 face 纯色占位。
 *  复用 buildOwnerAvatarRequest 带 Bili 头与 CDN 尺寸。 */
@Composable
private fun OwnerAvatar(face: String, isLive: Boolean = false, size: Dp = 20.dp) {
  // 正直播的 UP 头像套红色环(BiliPink),作为"直播头像"视觉信号;20dp 太小不放"直播"文字,
  // 封面已有 LiveBadge 文字,头像用红环标识即可。
  val modifier = Modifier
    .size(size)
    .clip(CircleShape)
    .background(MaterialTheme.colorScheme.surfaceVariant)
    .then(if (isLive) Modifier.border(2.dp, BiliColors.BiliPink, CircleShape) else Modifier)
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

/** 播放量/弹幕数等计数格式化:按当前界面 locale 用「万/亿」或「K/M/B」。 */
fun formatCount(count: Int, resources: android.content.res.Resources): String {
  return formatCompactCount(count.toLong(), localeFromResources(resources))
}

/** 是否可点 UP 头像进主页:B站 ownerMid>0,YouTube 需带 channelId。 */
private fun ownerClickable(video: VideoSummary): Boolean {
  return video.ownerMid > 0L || (video.source == SourceYoutube && video.channelId.isNotBlank())
}