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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.kirin.mt.core.youtube.DEFAULT_PLAYLIST_NAME
import com.kirin.mt.core.youtube.YoutubePlaylistStore
import com.kirin.mt.ui.theme.BiliColors
import kotlinx.coroutines.launch

/**
 * 长按视频卡片弹出的底部操作菜单。当前放「下载」+「加入播放列表」两项；
 * 下载由 [onDownload] 交给外层弹清晰度选择框,加入播放列表由 [onPickPlaylist]
 * 交给外层弹出 [MobilePlaylistPickerDialog]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileYoutubeLongPressSheet(
  video: VideoSummary,
  onDownload: () -> Unit,
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
          .clickable(onClick = onDownload)
          .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          painter = painterResource(R.drawable.ic_player_download),
          contentDescription = null,
          tint = BiliColors.BiliPink,
          modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.downloads_menu_download), color = Color.White)
      }
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
 * 加入播放列表选择弹窗：列出全部命名列表，勾选表示"该视频在这列表"，点行/勾选仅改本地暂存；
 * 「确认」一次性应用差异（新增勾选=加入，取消勾选=移除），「取消」丢弃不写库。底部可新建列表。
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
  // 勾选态(仅本地,确认才写库):true=要加入该列表,false=要移除。初始对应当前归属。
  val pending = remember { mutableStateMapOf<String, Boolean>() }
  LaunchedEffect(playlists) {
    playlists.forEach { pl ->
      if (!pending.containsKey(pl.name)) {
        pending[pl.name] = pl.videos.any { it.bvid == video.bvid }
      }
    }
  }

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
            val checked = pending[pl.name] ?: false
            // 「默认」是下载自动存档列表:只能取消勾选移除存档,禁止手动勾选加入。
            val autoArchive = pl.name == DEFAULT_PLAYLIST_NAME
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  // 自动存档列表只允许取消(移除),点中仍勾着时给一次去掉机会;未勾则不可加入。
                  if (autoArchive) { if (checked) pending[pl.name] = false }
                  else pending[pl.name] = !checked
                }
                .padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(
                checked = checked,
                // 禁用手动加入(勾选);但已入列时点击行/勾选框仍可取消(移除)。
                enabled = !autoArchive,
                onCheckedChange = {
                  if (autoArchive) { if (!it) pending[pl.name] = false }
                  else pending[pl.name] = it
                },
              )
              Text(
                pl.name,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(Modifier.weight(1f))
              Text(
                text = if (autoArchive) stringResource(R.string.playlist_auto_archive)
                  else "${pl.videos.size} 个",
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
      confirmButton = {
        TextButton(onClick = {
          scope.launch {
            playlists.forEach { pl ->
              val want = pending[pl.name] ?: false
              val inList = pl.videos.any { it.bvid == video.bvid }
              if (want && !inList) youtubePlaylistStore.addVideo(pl.name, video)
              else if (!want && inList) youtubePlaylistStore.removeVideo(pl.name, video.bvid)
            }
            // 写库完成后再关弹窗:否则 rememberCoroutineScope 随弹窗销毁被取消,
            // DataStore 写未落地,确认键点了没反应。
            onDismiss()
          }
        }) { Text(stringResource(R.string.playlist_confirm), color = Color.White) }
      },
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
        // 新建列表已创建(createPlaylist),勾选它;视频在「确认」时统一加入。
        pending[name] = true
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
