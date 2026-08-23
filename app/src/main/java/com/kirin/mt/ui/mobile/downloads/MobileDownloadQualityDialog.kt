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

/** B站 qn=480P。服务端 accept_quality 不一定含此档,下载对话框强制补一行,解析端按高度挑轨兜底。 */
private const val Qn480 = 32

/**
 * 下载清晰度选择对话框(选中 → 「开始下载」确认):
 * - B站:列出全部可播清晰度,默认选中最高,RadioButton 仅选中,「开始下载」提交。
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
  // 选项类型:服务端清晰度(qn) / 强制 480P / 仅音频。默认选中最高清晰度(列表首个)。
  // 服务端 accept_quality 不一定含 480P,故 480P 作为固定行单独列出;
  // 480 轨缺失时解析端挑「≤480 的最高轨」(通常 360P)兜底,不会失败。
  val qualityOptions = buildList {
    addAll(qualities)
    if (qualities.none { it.id == Qn480 }) add(PlaybackQuality(Qn480, "480P"))
  }
  var selectedQn by remember { mutableStateOf<Int?>(qualityOptions.firstOrNull()?.id) }
  var audioOnly by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = stringResource(R.string.downloads_quality_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        qualityOptions.forEach { q ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = !audioOnly && selectedQn == q.id,
              onClick = { audioOnly = false; selectedQn = q.id },
            )
            Text(
              text = q.description,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(start = 4.dp),
            )
          }
        }
        // 仅音频:只下载音频轨,不下载视频。
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = audioOnly,
            onClick = { audioOnly = true },
          )
          Text(
            text = stringResource(R.string.downloads_quality_audio),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp),
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          if (audioOnly) {
            onConfirm(
              DownloadQualityChoice(
                source = DownloadSource.BILI,
                biliAudioOnly = true,
                biliQualityLabel = stringResource(R.string.downloads_quality_audio),
              ),
            )
          } else {
            selectedQn?.let { qn ->
              val q = qualityOptions.firstOrNull { it.id == qn }
              onConfirm(
                DownloadQualityChoice(
                  source = DownloadSource.BILI,
                  biliQn = qn,
                  biliQualityLabel = q?.description ?: "",
                ),
              )
            }
          }
        },
      ) { Text(stringResource(R.string.downloads_confirm)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.mobile_dialog_cancel)) }
    },
  )
}

@Composable
private fun YoutubeQualityDialog(
  onDismiss: () -> Unit,
  onConfirm: (DownloadQualityChoice) -> Unit,
) {
  // 高清高度选择:null=简单下载(音视频一体,≤720p);非 null=高清该高度(视频+音频分件)。
  // 新增 480p/360p 低档,供低流量/低画质需求;解析端按「≤所选高度的最高轨」挑,无该档自动降级。
  val heights = listOf(2160, 1440, 1080, 720, 480, 360)
  var selectedHeight by remember { mutableStateOf<Int?>(null) }
  var audioOnly by remember { mutableStateOf(false) }
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
            selected = !audioOnly && selectedHeight == null,
            onClick = { audioOnly = false; selectedHeight = null },
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
              selected = !audioOnly && selectedHeight == h,
              onClick = { audioOnly = false; selectedHeight = h },
            )
            Text(
              text = "${h}p",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(start = 4.dp),
            )
          }
        }
        // 仅音频:只下载音频轨,不下载视频。
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = audioOnly,
            onClick = { audioOnly = true },
          )
          Text(
            text = stringResource(R.string.downloads_quality_audio),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp),
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          if (audioOnly) {
            onConfirm(
              DownloadQualityChoice(
                source = DownloadSource.YOUTUBE,
                youTubeAudioOnly = true,
              ),
            )
          } else {
            val maxHeight = selectedHeight
            onConfirm(
              DownloadQualityChoice(
                source = DownloadSource.YOUTUBE,
                youTubePreferMuxed = maxHeight == null,
                youTubeMaxHeight = maxHeight,
              ),
            )
          }
        },
      ) { Text(stringResource(R.string.downloads_confirm)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.mobile_dialog_cancel)) }
    },
  )
}
