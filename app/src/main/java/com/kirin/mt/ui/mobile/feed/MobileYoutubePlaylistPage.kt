package com.kirin.mt.ui.mobile.feed

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubePlaylistStore
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.launch

/**
 * 移动端"播放列表"子 tab:展示本地 YouTube 播放列表(DataStore 持久化)。
 * 普通模式点击卡片起播、长按移除;点"编辑"进入编辑模式,每张卡片显示红色"✕ 移除"叠层,
 * 点击整卡即移除。空态提示长按加入。播放入口把整列表作为连播队列交给外层播放器。
 */
@Composable
fun MobileYoutubePlaylistPage(
  youtubePlaylistStore: YoutubePlaylistStore,
  onVideoSelected: (VideoSummary) -> Unit,
  onLongPress: ((VideoSummary) -> Unit)? = null,
  onStartPlaylist: (List<VideoSummary>) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val videos by youtubePlaylistStore.videos.collectAsState(initial = emptyList())
  var editMode by remember { mutableStateOf(false) }

  fun toast(msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
  }

  fun remove(video: VideoSummary) {
    scope.launch {
      youtubePlaylistStore.remove(video.bvid)
      toast(context.getString(R.string.playlist_removed))
    }
  }

  Column(modifier = modifier.fillMaxSize()) {
    // 头部:标题 + 编辑/完成切换
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.feed_tab_playlist),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f),
      )
      OutlinedButton(onClick = { editMode = !editMode }) {
        Text(stringResource(if (editMode) R.string.playlist_done else R.string.playlist_edit))
      }
    }

    if (videos.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = stringResource(R.string.playlist_empty),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(24.dp),
        )
      }
      return@Column
    }

    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 160.dp),
      contentPadding = PaddingValues(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      items(videos, key = { it.bvid }) { video ->
        if (editMode) {
          // 编辑模式:整卡点击 = 移除,卡片右上角红 ✕ 提示。
          Box {
            MobileVideoCard(
              video = video,
              onClick = { remove(video) },
              onLongPress = onLongPress,
            )
            Text(
              text = "✕ ${stringResource(R.string.playlist_remove)}",
              color = MaterialTheme.colorScheme.onError,
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        } else {
          MobileVideoCard(
            video = video,
            // 先起播(外层 onVideoSelected 会清空连播队列),再设置队列快照,保证播放器用播放列表连播。
            onClick = { video -> onVideoSelected(video); onStartPlaylist(videos) },
            onLongPress = onLongPress,
          )
        }
      }
    }
  }
}
