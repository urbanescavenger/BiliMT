package com.kirin.mt.ui.mobile.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubePlaylistStore
import com.kirin.mt.ui.theme.BiliColors
import kotlinx.coroutines.launch

/**
 * 长按视频卡片弹出的底部操作菜单。当前只放「加入播放列表」一项；
 * 点击后由 [onPickPlaylist] 交给外层弹出 [MobilePlaylistPickerDialog]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileYoutubeLongPressSheet(
  video: VideoSummary,
  onPickPlaylist: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFF1A1A20),
  ) {
    MaterialTheme(colorScheme = darkColorScheme()) {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Text(
        text = video.title,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.padding(top = 8.dp))
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .clickable(onClick = onPickPlaylist)
          .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          painter = painterResource(R.drawable.ic_player_playlist),
          contentDescription = null,
          tint = BiliColors.BiliPink,
          modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.add_to_playlist), color = Color.White)
      }
      Spacer(Modifier.padding(bottom = 8.dp))
      }
    }
  }
}

/**
 * 加入播放列表选择弹窗：列出全部命名列表，勾选表示视频已在该列表，点击切换加入/移除；
 * 底部「新建列表」进入次级命名对话框。
 */
@Composable
fun MobilePlaylistPickerDialog(
  video: VideoSummary,
  youtubePlaylistStore: YoutubePlaylistStore,
  onDismiss: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val playlists by youtubePlaylistStore.playlists.collectAsState(initial = emptyList())
  var showCreate by remember { mutableStateOf(false) }

  MaterialTheme(colorScheme = darkColorScheme()) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.playlist_add_title), color = Color.White) },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 360.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        playlists.forEach { pl ->
          val inList = pl.videos.any { it.bvid == video.bvid }
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .clickable {
                scope.launch {
                  if (inList) youtubePlaylistStore.removeVideo(pl.name, video.bvid)
                  else youtubePlaylistStore.addVideo(pl.name, video)
                }
              }
              .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(
              checked = inList,
              onCheckedChange = { c ->
                scope.launch {
                  if (c) youtubePlaylistStore.addVideo(pl.name, video)
                  else youtubePlaylistStore.removeVideo(pl.name, video.bvid)
                }
              },
            )
            Text(pl.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(
              text = "${pl.videos.size} 个",
              color = BiliColors.TextSecondary,
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }
        Spacer(Modifier.padding(top = 8.dp))
        OutlinedButton(
          onClick = { showCreate = true },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.playlist_new_list), color = Color.White)
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.playlist_cancel), color = Color.White)
      }
    },
  )
  }

  if (showCreate) {
    CreatePlaylistDialog(
      youtubePlaylistStore = youtubePlaylistStore,
      onDismiss = { showCreate = false },
      onCreated = { name ->
        showCreate = false
        scope.launch { youtubePlaylistStore.addVideo(name, video) }
      },
    )
  }
}

/** 新建播放列表命名对话框。 */
@Composable
internal fun CreatePlaylistDialog(
  youtubePlaylistStore: YoutubePlaylistStore,
  onDismiss: () -> Unit,
  onCreated: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var name by remember { mutableStateOf("") }
  MaterialTheme(colorScheme = darkColorScheme()) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.playlist_new_list), color = Color.White) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { if (it.length <= 30) name = it },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.playlist_name_hint)) },
        label = { Text(stringResource(R.string.playlist_name_hint)) },
      )
    },
    confirmButton = {
      TextButton(
        enabled = name.isNotBlank(),
        onClick = {
          scope.launch {
            val created = youtubePlaylistStore.createPlaylist(name.trim())
            if (created) onCreated(name.trim()) else onDismiss()
          }
        },
      ) { Text(stringResource(R.string.playlist_confirm), color = Color.White) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.playlist_cancel), color = Color.White)
      }
    },
  )
  }
}
