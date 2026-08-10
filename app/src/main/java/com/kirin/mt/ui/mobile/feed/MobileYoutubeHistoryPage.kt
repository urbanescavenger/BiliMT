package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubeHistoryEntry
import com.kirin.mt.core.youtube.YoutubeHistoryStore
import com.kirin.mt.ui.mobile.home.MobileVideoCard

/**
 * 移动端"YouTube 历史"子 tab:本地最近播放的 YouTube 视频网格。数据来自
 * [YoutubeHistoryStore](DataStore 持久化,无需登录)。点击卡片经 progress 续播,
 * 点头像/UP 名进频道主页。镜像 MobileHistoryPage 的网格结构,但无分页(本地列表)。
 */
@Composable
fun MobileYoutubeHistoryPage(
  youtubeHistoryStore: YoutubeHistoryStore,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  val history by youtubeHistoryStore.history.collectAsState(initial = emptyList())

  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 160.dp),
    contentPadding = PaddingValues(12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = modifier.fillMaxSize(),
  ) {
    if (history.isEmpty()) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        Box(
          modifier = Modifier.fillMaxWidth().padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.youtube_history_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    } else {
      items(history, key = { it.videoId }) { entry ->
        MobileVideoCard(
          video = entry.toVideoSummary(),
          onClick = onVideoSelected,
          onOpenOwner = onOpenOwner,
          showYoutubeBorder = true,
        )
      }
    }
  }
}

/** 历史条目 → 卡片模型。progress 填秒数,toPlaybackRequest 据此续播;接近播完自动视为看完不续播。 */
private fun YoutubeHistoryEntry.toVideoSummary(): VideoSummary {
  return VideoSummary(
    bvid = videoId,
    title = title,
    pic = thumbnailUrl,
    ownerName = channelName,
    ownerFace = "",
    ownerMid = 0L,
    view = 0,
    danmaku = 0,
    duration = (durationMs / 1000L).toInt(),
    pubdate = 0L,
    badge = "",
    progress = (positionMs / 1000L).toInt(),
    source = SourceYoutube,
    channelId = channelId,
  )
}
