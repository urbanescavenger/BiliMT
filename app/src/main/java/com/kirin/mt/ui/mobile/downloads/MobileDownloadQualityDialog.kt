package com.kirin.mt.ui.mobile.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.download.DownloadQualityChoice
import com.kirin.mt.core.download.DownloadSource
import com.kirin.mt.core.player.PlaybackQuality

/**
 * 下载清晰度选择对话框。
 * - B站:列出全部可播清晰度,点选即确认。
 * - YouTube:「简单下载(音视频一体,≤720p)」vs「高清(视频+音频)」+ 高度选择器。
 */
@Composable
fun MobileDownloadQualityDialog(
  isYoutube: Boolean,
  biliQualities: List<PlaybackQuality>,
  onDismiss: () -> Unit,
  onConfirm: (DownloadQualityChoice) -> Unit,
) {
  if (isYoutube) {
    YoutubeQualityDialog(onDismiss = onDismiss, onConfirm = onConfirm)
  } else {
    BiliQualityDialog(qualities = biliQualities, onDismiss = onDismiss, onConfirm = onConfirm)
  }
}

@Composable
private fun BiliQualityDialog(
  qualities: List<PlaybackQuality>,
  onDismiss: () -> Unit,
  onConfirm: (DownloadQualityChoice) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.downloads_quality_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        qualities.forEach { q ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = false,
              onClick = {
                onConfirm(
                  DownloadQualityChoice(
                    source = DownloadSource.BILI,
                    biliQn = q.id,
                    biliQualityLabel = q.description,
                  ),
                )
              },
            )
            Text(
              text = q.description,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(start = 4.dp),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.mobile_dialog_cancel)) }
    },
  )
}

@Composable
private fun YoutubeQualityDialog(
  onDismiss: () -> Unit,
  onConfirm: (DownloadQualityChoice) -> Unit,
) {
  // 高清高度选择:null=最高(不限制)。
  val heights = listOf(2160, 1440, 1080, 720)
  var selectedHeight by remember { mutableStateOf<Int?>(null) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.downloads_quality_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // 简单下载:音视频一体单文件,≤720p。
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = selectedHeight == null,
            onClick = {
              selectedHeight = null
              onConfirm(
                DownloadQualityChoice(
                  source = DownloadSource.YOUTUBE,
                  youTubePreferMuxed = true,
                  youTubeMaxHeight = null,
                ),
              )
            },
          )
          Text(
            text = stringResource(R.string.downloads_quality_simple),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp),
          )
        }
        // 高清:视频+音频分文件,可选高度。
        Text(
          text = stringResource(R.string.downloads_quality_hd),
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.padding(top = 8.dp),
        )
        heights.forEach { h ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = selectedHeight == h,
              onClick = {
                selectedHeight = h
                onConfirm(
                  DownloadQualityChoice(
                    source = DownloadSource.YOUTUBE,
                    youTubePreferMuxed = false,
                    youTubeMaxHeight = h,
                  ),
                )
              },
            )
            Text(
              text = "${h}p",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(start = 4.dp),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.mobile_dialog_cancel)) }
    },
  )
}
