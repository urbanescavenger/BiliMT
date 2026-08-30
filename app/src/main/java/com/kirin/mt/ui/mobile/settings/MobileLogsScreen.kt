package com.kirin.mt.ui.mobile.settings

import android.widget.Toast
import androidx.compose.foundation.background
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.util.FirebaseLogSender
import com.kirin.mt.core.util.LogCatcherUtil
import java.io.File
import kotlinx.coroutines.launch

/**
 * 移动端日志屏：列出日志文件（实时/崩溃/手动），可查看内容、分享导出、手动开始/停止录制。
 * 触屏友好，复用共享的 [LogCatcherUtil]（TV 端 SettingsLogsColumn 的移动版，不照搬 D-pad 焦点逻辑）。
 */
@Composable
fun MobileLogsScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var files by remember { mutableStateOf(LogCatcherUtil.allLogFiles()) }
  var isRecording by remember { mutableStateOf(LogCatcherUtil.isRecording) }
  var viewingFile by remember { mutableStateOf<File?>(null) }

  fun refresh() {
    files = LogCatcherUtil.allLogFiles()
    isRecording = LogCatcherUtil.isRecording
  }

  val current = viewingFile
  if (current != null) {
    MobileLogContentScreen(
      file = current,
      onBack = { viewingFile = null },
      modifier = modifier,
    )
  } else {
    Column(
      modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // 顶部标题 + 返回
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = onBack) {
          Text(stringResource(R.string.mobile_back))
        }
        Text(
          text = stringResource(R.string.settings_logs_entry_title),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
        )
      }

      // 手动录制开关
      Card(
        modifier = Modifier.fillMaxWidth().clickable {
          scope.launch {
            if (LogCatcherUtil.isRecording) {
              val file = LogCatcherUtil.stopManualRecording()
              refresh()
              Toast.makeText(
                context,
                context.getString(R.string.settings_logs_recording_stopped, file?.name ?: ""),
                Toast.LENGTH_SHORT,
              ).show()
            } else {
              val started = LogCatcherUtil.startManualRecording()
              refresh()
              val message = if (started) {
                R.string.settings_logs_recording_started
              } else {
                R.string.settings_logs_recording_failed
              }
              Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
          }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium,
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
          Text(
            text = stringResource(
              if (isRecording) R.string.settings_logs_stop_recording
              else R.string.settings_logs_start_recording
            ),
            style = MaterialTheme.typography.titleMedium,
            color = if (isRecording) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = stringResource(
              if (isRecording) R.string.settings_logs_recording_hint
              else R.string.settings_logs_description
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }

      // 日志文件列表
      LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(files, key = { it.file.name }) { info ->
          MobileLogFileRow(
            info = info,
            onView = { viewingFile = info.file },
            onShare = { LogCatcherUtil.shareLogFile(context, info.file) },
          )
        }
      }
    }
  }
}

/** 日志文件行：文件名 + 类型标签 + 大小，整行点击查看，右侧分享按钮。 */
@Composable
private fun MobileLogFileRow(
  info: LogCatcherUtil.LogFileInfo,
  onView: () -> Unit,
  onShare: () -> Unit,
) {
  val typeLabel = when (info.type) {
    LogCatcherUtil.LogType.Crash -> stringResource(R.string.settings_logs_type_crash)
    LogCatcherUtil.LogType.Manual -> stringResource(R.string.settings_logs_type_manual)
    LogCatcherUtil.LogType.Live -> stringResource(R.string.settings_logs_type_live)
  }
  Card(
    modifier = Modifier.fillMaxWidth().clickable { onView() },
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    shape = MaterialTheme.shapes.medium,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = info.file.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Text(
            text = typeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
          )
        }
        Text(
          text = stringResource(
            R.string.settings_logs_file_meta,
            LogCatcherUtil.formatFileSize(info.file.length()),
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 2.dp),
        )
      }
      TextButton(onClick = onShare) {
        Text(stringResource(R.string.settings_logs_share))
      }
    }
  }
}

/** 日志内容查看：可滚动等宽文本 + 返回/刷新，顶部「上报」把日志尾部送到 Crashlytics。 */
@Composable
private fun MobileLogContentScreen(
  file: File,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var refreshKey by remember { mutableStateOf(0L) }
  val content = remember(file, refreshKey) { LogCatcherUtil.readLogContent(file) }
  val lines = remember(content) { content.lines() }

  fun sendToCrashlytics() {
    scope.launch {
      // sendUnsentReports 无完成回调,入队成功只提示「已入队:上传中」,不假显示成功
      val result = withContext(Dispatchers.IO) {
        FirebaseLogSender.sendLogFile(context, file)
      }
      val message = when {
        result.isSuccess -> context.getString(R.string.settings_logs_send_queued)
        !FirebaseLogSender.isAvailable(context) ->
          context.getString(R.string.settings_logs_send_unavailable)
        else -> context.getString(
          R.string.settings_logs_send_failed,
          result.exceptionOrNull()?.message.orEmpty(),
        )
      }
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
  }

  Column(
    modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) {
        Text(stringResource(R.string.settings_logs_back))
      }
      Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
        Text(
          text = file.name,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = stringResource(
            R.string.settings_log_viewer_size,
            LogCatcherUtil.formatFileSize(file.length()),
            lines.size,
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = { refreshKey += 1L }) {
        Text(stringResource(R.string.settings_logs_refresh))
      }
      TextButton(onClick = ::sendToCrashlytics) {
        Text(stringResource(R.string.settings_logs_send))
      }
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(12.dp),
    ) {
      LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        items(lines.size) { index ->
          Text(
            text = lines[index],
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
