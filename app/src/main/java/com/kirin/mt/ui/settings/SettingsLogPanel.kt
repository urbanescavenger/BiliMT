package com.kirin.mt.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun SettingsLogsColumn(
  files: List<LogCatcherUtil.LogFileInfo>,
  isRecording: Boolean,
  viewingFile: File?,
  onView: (LogCatcherUtil.LogFileInfo) -> Unit,
  onBackFromView: () -> Unit,
  onShare: (LogCatcherUtil.LogFileInfo) -> Unit,
  onToggleRecording: () -> Unit,
  onMoveLeftToSettings: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current

  Box(
    modifier = modifier,
  ) {
    if (viewingFile != null) {
      LogContentPanel(
        file = viewingFile,
        onBack = onBackFromView,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      LogListPanel(
        files = files,
        isRecording = isRecording,
        onView = onView,
        onShare = onShare,
        onToggleRecording = onToggleRecording,
        onMoveLeftToSettings = onMoveLeftToSettings,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun LogListPanel(
  files: List<LogCatcherUtil.LogFileInfo>,
  isRecording: Boolean,
  onView: (LogCatcherUtil.LogFileInfo) -> Unit,
  onShare: (LogCatcherUtil.LogFileInfo) -> Unit,
  onToggleRecording: () -> Unit,
  onMoveLeftToSettings: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val listState = rememberLazyListState()

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
  ) {
    Text(
      text = stringResource(R.string.settings_logs_section),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.SectionTitle,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = if (isRecording) {
        stringResource(R.string.settings_logs_recording_hint)
      } else {
        stringResource(R.string.settings_logs_description)
      },
      color = if (isRecording) homeColors.accent else homeColors.textSecondary,
      fontSize = BiliTypography.BodySmall,
    )
    BiliFocusableSurface(
      scaleOnFocus = false,
      shadowOnFocus = false,
      shape = RoundedCornerShape(BiliRadius.Pill),
      onClick = onToggleRecording,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = BiliSpacing.Sm),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = if (isRecording) {
            stringResource(R.string.settings_logs_stop_recording)
          } else {
            stringResource(R.string.settings_logs_start_recording)
          },
          color = if (isRecording) homeColors.accent else homeColors.textPrimary,
          fontSize = BiliTypography.Body,
          fontWeight = FontWeight.Bold,
        )
      }
    }
    LazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
    ) {
      itemsIndexed(files, key = { _, info -> info.file.name }) { _, info ->
        LogFileRow(
          info = info,
          onView = { onView(info) },
          onShare = { onShare(info) },
          onMoveLeftToSettings = onMoveLeftToSettings,
        )
      }
    }
  }
}

@Composable
private fun LogContentPanel(
  file: File,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  var refreshKey by remember { mutableStateOf(0L) }
  val content = remember(file, refreshKey) { LogCatcherUtil.readLogContent(file) }
  val lines = remember(content) { content.lines() }
  val listState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val logAreaFocusRequester = remember { FocusRequester() }
  val backFocusRequester = remember { FocusRequester() }

  val totalCount = lines.size
  val visibleRange by remember {
    derivedStateOf {
      listState.layoutInfo.visibleItemsInfo.let { info ->
        if (info.isEmpty()) 0..0 else info.first().index..info.last().index
      }
    }
  }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
      ) {
        Text(
          text = file.name,
          color = homeColors.textPrimary,
          fontSize = BiliTypography.SectionTitle,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = stringResource(
            R.string.settings_log_viewer_size,
            LogCatcherUtil.formatFileSize(file.length()),
            totalCount,
          ),
          color = homeColors.textTertiary,
          fontSize = BiliTypography.BodySmall,
        )
      }
      Text(
        text = stringResource(
          R.string.settings_log_viewer_position,
          visibleRange.first + 1,
          visibleRange.last + 1,
          totalCount,
        ),
        color = homeColors.textTertiary,
        fontSize = BiliTypography.CardMeta,
      )
    }
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .clip(RoundedCornerShape(BiliRadius.Card))
        .background(homeColors.glassSurfaceStrong.copy(alpha = 0.5f))
        .padding(BiliSpacing.Md)
        .focusRequester(logAreaFocusRequester)
        .focusTarget()
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
          when (event.key) {
            Key.DirectionUp -> {
              val target = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
              coroutineScope.launch { listState.scrollToItem(target) }
              true
            }
            Key.DirectionDown -> {
              val target = (listState.firstVisibleItemIndex + 1).coerceAtMost((totalCount - 1).coerceAtLeast(0))
              coroutineScope.launch { listState.scrollToItem(target) }
              true
            }
            Key.PageUp -> {
              val target = (listState.firstVisibleItemIndex - PAGE_JUMP_SIZE).coerceAtLeast(0)
              coroutineScope.launch { listState.scrollToItem(target) }
              true
            }
            Key.PageDown -> {
              val target = (listState.firstVisibleItemIndex + PAGE_JUMP_SIZE).coerceAtMost((totalCount - 1).coerceAtLeast(0))
              coroutineScope.launch { listState.scrollToItem(target) }
              true
            }
            Key.MoveHome -> {
              coroutineScope.launch { listState.scrollToItem(0) }
              true
            }
            Key.MoveEnd -> {
              coroutineScope.launch { listState.scrollToItem((totalCount - 1).coerceAtLeast(0)) }
              true
            }
            Key.DirectionLeft -> {
              backFocusRequester.requestFocus()
              true
            }
            else -> false
          }
        },
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
      ) {
        itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
          LogLineText(line = line, homeColors = homeColors)
        }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md, Alignment.CenterHorizontally),
    ) {
      BiliFocusableSurface(
        scaleOnFocus = false,
        shadowOnFocus = false,
        shape = RoundedCornerShape(BiliRadius.Pill),
        onClick = onBack,
        modifier = Modifier
          .weight(1f)
          .focusRequester(backFocusRequester),
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BiliSpacing.Sm),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.settings_logs_back),
            color = homeColors.textPrimary,
            fontSize = BiliTypography.Body,
            fontWeight = FontWeight.Bold,
          )
        }
      }
      BiliFocusableSurface(
        scaleOnFocus = false,
        shadowOnFocus = false,
        shape = RoundedCornerShape(BiliRadius.Pill),
        onClick = { refreshKey += 1L },
        modifier = Modifier.weight(1f),
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BiliSpacing.Sm),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.settings_logs_refresh),
            color = homeColors.textPrimary,
            fontSize = BiliTypography.Body,
            fontWeight = FontWeight.Bold,
          )
        }
      }
      BiliFocusableSurface(
        scaleOnFocus = false,
        shadowOnFocus = false,
        shape = RoundedCornerShape(BiliRadius.Pill),
        onClick = {
          coroutineScope.launch { listState.scrollToItem(0) }
        },
        modifier = Modifier.weight(1f),
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BiliSpacing.Sm),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.settings_logs_top),
            color = homeColors.textPrimary,
            fontSize = BiliTypography.Body,
            fontWeight = FontWeight.Bold,
          )
        }
      }
      BiliFocusableSurface(
        scaleOnFocus = false,
        shadowOnFocus = false,
        shape = RoundedCornerShape(BiliRadius.Pill),
        onClick = {
          coroutineScope.launch { listState.scrollToItem((totalCount - 1).coerceAtLeast(0)) }
        },
        modifier = Modifier.weight(1f),
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BiliSpacing.Sm),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.settings_logs_bottom),
            color = homeColors.textPrimary,
            fontSize = BiliTypography.Body,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    runCatching {
      logAreaFocusRequester.requestFocus()
    }
  }
}

@Composable
private fun LogFileRow(
  info: LogCatcherUtil.LogFileInfo,
  onView: () -> Unit,
  onShare: () -> Unit,
  onMoveLeftToSettings: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val label = when (info.type) {
    LogCatcherUtil.LogType.Crash -> stringResource(R.string.settings_logs_type_crash)
    LogCatcherUtil.LogType.Manual -> stringResource(R.string.settings_logs_type_manual)
    LogCatcherUtil.LogType.Live -> stringResource(R.string.settings_logs_type_live)
  }
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BiliFocusableSurface(
      scaleOnFocus = false,
      shadowOnFocus = false,
      shape = RoundedCornerShape(BiliRadius.Card),
      onClick = onView,
      modifier = Modifier
        .weight(1f)
        .onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
            onMoveLeftToSettings()
          } else {
            false
          }
        },
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = info.file.name,
            color = homeColors.textPrimary,
            fontSize = BiliTypography.Body,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Text(
            text = label,
            color = homeColors.accent,
            fontSize = BiliTypography.CardBadge,
            fontWeight = FontWeight.Bold,
          )
        }
        Text(
          text = stringResource(
            R.string.settings_logs_file_meta,
            LogCatcherUtil.formatFileSize(info.file.length()),
          ),
          color = homeColors.textTertiary,
          fontSize = BiliTypography.CardMeta,
        )
      }
    }
    BiliFocusableSurface(
      scaleOnFocus = false,
      shadowOnFocus = false,
      shape = RoundedCornerShape(BiliRadius.Pill),
      onClick = onShare,
      modifier = Modifier.width(BiliSizing.SettingsMoveButtonSize),
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Sm),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.settings_logs_share),
          color = homeColors.textPrimary,
          fontSize = BiliTypography.BodySmall,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

@Composable
private fun LogLineText(
  line: String,
  homeColors: com.kirin.mt.ui.theme.HomeColorScheme,
) {
  val color = when {
    ERROR_PATTERNS.any { line.contains(it, ignoreCase = true) } -> BiliColors.BiliPink
    WARN_PATTERNS.any { line.contains(it, ignoreCase = true) } -> BiliColors.AirJumpGreen
    DEBUG_PATTERNS.any { line.contains(it, ignoreCase = true) } -> homeColors.textTertiary
    else -> homeColors.textSecondary
  }
  Text(
    text = line,
    color = color,
    fontSize = BiliTypography.BodySmall,
    fontFamily = FontFamily.Monospace,
  )
}

private val ERROR_PATTERNS = listOf(" E ", "ERROR", "FATAL", "UncaughtException", "Exception:")
private val WARN_PATTERNS = listOf(" W ", "WARN")
private val DEBUG_PATTERNS = listOf(" D ", "DEBUG")
private const val PAGE_JUMP_SIZE = 20
