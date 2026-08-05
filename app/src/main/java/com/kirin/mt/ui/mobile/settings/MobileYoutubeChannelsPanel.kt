package com.kirin.mt.ui.mobile.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.youtube.YoutubeChannelStore
import com.kirin.mt.core.youtube.YoutubeRepository
import kotlinx.coroutines.launch

/**
 * 移动端「YouTube 频道」管理:输入 @handle / UC... 频道 ID,解析加入
 * [YoutubeChannelStore];已跟频道列表可删除。软键盘输入,无需内嵌键盘。
 */
@Composable
fun MobileYoutubeChannelsPanel(
  youtubeChannelStore: YoutubeChannelStore,
  youtubeRepository: YoutubeRepository,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val channels by youtubeChannelStore.channels.collectAsState(initial = emptyList())
  var input by remember { mutableStateOf("") }
  var adding by remember { mutableStateOf(false) }

  fun submit() {
    val query = input.trim()
    if (query.isBlank() || adding) return
    adding = true
    scope.launch {
      val ok = runCatching {
        val channel = youtubeRepository.resolveChannel(query)
        youtubeChannelStore.add(channel)
      }.isSuccess
      adding = false
      if (ok) {
        input = ""
        Toast.makeText(context, R.string.youtube_channel_added, Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(context, R.string.youtube_channel_parse_failed, Toast.LENGTH_SHORT).show()
      }
    }
  }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = stringResource(R.string.settings_youtube_channels_desc),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = input,
      onValueChange = { input = it },
      singleLine = true,
      placeholder = { Text(stringResource(R.string.youtube_channel_hint)) },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      modifier = Modifier.fillMaxWidth(),
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(
        enabled = input.isNotBlank() && !adding,
        onClick = ::submit,
        modifier = Modifier.weight(1f),
      ) {
        Text(if (adding) stringResource(R.string.youtube_channel_resolving) else stringResource(R.string.youtube_add_channel))
      }
      TextButton(
        enabled = input.isNotBlank() && !adding,
        onClick = { input = "" },
        modifier = Modifier.weight(1f),
      ) {
        Text(stringResource(R.string.youtube_clear_input))
      }
    }
    if (channels.isEmpty()) {
      Text(
        text = stringResource(R.string.youtube_channel_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        channels.forEach { channel ->
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.medium,
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = channel.name,
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
                )
                Text(
                  text = channel.channelId,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                )
              }
              TextButton(
                onClick = { scope.launch { youtubeChannelStore.remove(channel.channelId) } },
              ) {
                Text(stringResource(R.string.youtube_channel_remove))
              }
            }
          }
        }
      }
    }
  }
}
