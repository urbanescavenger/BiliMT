package com.kirin.mt.ui.settings

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kirin.mt.R
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

@Composable
internal fun SettingsLogsColumn(
  files: List<LogCatcherUtil.LogFileInfo>,
  onView: (LogCatcherUtil.LogFileInfo) -> Unit,
  onShare: (LogCatcherUtil.LogFileInfo) -> Unit,
  onExport: () -> Unit,
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
      text = stringResource(R.string.settings_logs_description),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.BodySmall,
    )
    BiliFocusableSurface(
      scaleOnFocus = false,
      shadowOnFocus = false,
      shape = RoundedCornerShape(BiliRadius.Pill),
      onClick = onExport,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = BiliSpacing.Sm),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.settings_logs_export),
          color = homeColors.textPrimary,
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
