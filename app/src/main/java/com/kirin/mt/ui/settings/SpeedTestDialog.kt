package com.kirin.mt.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.player.CdnSpeedTester
import com.kirin.mt.core.player.SpeedTestUiState
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.glass.biliLiquidGlassSurface
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun SpeedTestDialog(
  state: SpeedTestUiState,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val panelShape = RoundedCornerShape(BiliRadius.Panel)
  val dividerColor = homeColors.glassBorder
  val dismissFocusRequester = remember { FocusRequester() }

  BackHandler(enabled = state !is SpeedTestUiState.Running) { onDismiss() }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.55f)),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .width(720.dp)
        .heightIn(max = 600.dp)
        .biliLiquidGlassSurface(
          enabled = performancePolicy.cinematicVisualEffectsEnabled &&
            performancePolicy.liquidGlassCardsEnabled,
          shape = panelShape,
          surfaceColor = homeColors.cardSurface,
          borderColor = homeColors.glassBorder,
          borderWidth = BiliFocus.RestingBorderWidth,
        )
        .padding(BiliSpacing.Xl),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
    ) {
      Text(
        text = stringResource(R.string.settings_speed_test_dialog_title),
        color = homeColors.textPrimary,
        fontSize = BiliTypography.SectionTitle,
        fontWeight = FontWeight.Bold,
      )
      SpeedTestDialogBody(
        state = state,
        dividerColor = dividerColor,
      )
      Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
      ) {
        BiliFocusableSurface(
          scaleOnFocus = false,
          shadowOnFocus = false,
          shape = RoundedCornerShape(BiliRadius.Pill),
          onClick = onDismiss,
          modifier = Modifier
            .width(200.dp)
            .focusRequester(dismissFocusRequester),
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = BiliSpacing.Sm),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = stringResource(R.string.settings_speed_test_dialog_close),
              color = homeColors.textPrimary,
              fontSize = BiliTypography.Body,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }

  LaunchedEffect(state) {
    if (state !is SpeedTestUiState.Running) {
      runCatching { dismissFocusRequester.requestFocus() }
    }
  }
}

@Composable
private fun SpeedTestDialogBody(
  state: SpeedTestUiState,
  dividerColor: Color,
) {
  val homeColors = LocalHomeColors.current
  when (state) {
    SpeedTestUiState.Running -> Text(
      text = stringResource(R.string.settings_speed_test_running),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.Body,
    )
    SpeedTestUiState.NoLastVideo -> Text(
      text = stringResource(R.string.settings_speed_test_no_last_video),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.Body,
    )
    SpeedTestUiState.Failed -> Text(
      text = stringResource(R.string.settings_speed_test_failed),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.Body,
    )
    is SpeedTestUiState.Succeeded -> {
      val bestTtfbMs = state.results.firstOrNull()?.firstByteMs ?: 0L
      Text(
        text = stringResource(
          R.string.settings_speed_test_summary,
          state.playurlResolveMs,
          bestTtfbMs,
        ),
        color = homeColors.textTertiary,
        fontSize = BiliTypography.CardMeta,
      )
      Text(
        text = stringResource(R.string.settings_speed_test_source, state.sourceLabel),
        color = homeColors.textSecondary,
        fontSize = BiliTypography.BodySmall,
      )
      SpeedTestColumnHeader()
      HorizontalDivider(color = dividerColor)
      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
      ) {
        itemsIndexed(state.results, key = { _, item -> item.url }) { index, measurement ->
          SpeedTestResultRow(
            rank = index + 1,
            measurement = measurement,
            highlight = index == 0,
          )
        }
      }
    }
    SpeedTestUiState.Idle -> Unit
  }
}

@Composable
private fun SpeedTestColumnHeader() {
  val homeColors = LocalHomeColors.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
  ) {
    Text(
      text = stringResource(R.string.settings_speed_test_column_rank),
      color = homeColors.textTertiary,
      fontSize = BiliTypography.CardMeta,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.width(40.dp),
    )
    Text(
      text = stringResource(R.string.settings_speed_test_column_host),
      color = homeColors.textTertiary,
      fontSize = BiliTypography.CardMeta,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = stringResource(R.string.settings_speed_test_column_speed),
      color = homeColors.textTertiary,
      fontSize = BiliTypography.CardMeta,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.width(120.dp),
      textAlign = TextAlign.End,
    )
  }
}

@Composable
private fun HorizontalDivider(color: Color) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(color),
  )
}

@Composable
private fun SpeedTestResultRow(
  rank: Int,
  measurement: CdnSpeedTester.Measurement,
  highlight: Boolean,
) {
  val homeColors = LocalHomeColors.current
  val host = remember(measurement.url) {
    measurement.url.toHttpUrlOrNull()?.host ?: measurement.url
  }
  val speedText = remember(measurement) { formatSpeed(measurement) }
  val downloadedKb = remember(measurement) { measurement.downloadedBytes / 1024L }
  val rowShape = RoundedCornerShape(BiliRadius.Card)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(rowShape)
      .background(
        color = if (highlight) homeColors.accent.copy(alpha = 0.14f) else Color.Transparent,
        shape = rowShape,
      )
      .padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Sm),
    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = rank.toString(),
      color = if (highlight) homeColors.accent else homeColors.textPrimary,
      fontSize = BiliTypography.Body,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.width(40.dp),
    )
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = host,
          color = if (highlight) homeColors.textPrimary else homeColors.textSecondary,
          fontSize = BiliTypography.BodySmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (highlight) {
          SpeedTestBadge()
        }
      }
      Text(
        text = stringResource(
          R.string.settings_speed_test_detail,
          measurement.firstByteMs,
          measurement.totalMs,
          downloadedKb,
        ),
        color = homeColors.textTertiary,
        fontSize = BiliTypography.CardMeta,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      text = speedText,
      color = if (highlight) homeColors.accent else homeColors.textSecondary,
      fontSize = BiliTypography.Body,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.End,
      modifier = Modifier.width(120.dp),
    )
  }
}

@Composable
private fun SpeedTestBadge() {
  val homeColors = LocalHomeColors.current
  val badgeShape = RoundedCornerShape(BiliRadius.Pill)
  Box(
    modifier = Modifier
      .clip(badgeShape)
      .background(homeColors.accent, badgeShape)
      .padding(horizontal = BiliSpacing.Sm, vertical = 2.dp),
  ) {
    Text(
      text = stringResource(R.string.settings_speed_test_best),
      color = homeColors.textPrimary,
      fontSize = BiliTypography.CardBadge,
      fontWeight = FontWeight.Bold,
    )
  }
}

private fun formatSpeed(measurement: CdnSpeedTester.Measurement): String {
  val seconds = measurement.totalMs.coerceAtLeast(1L) / 1000.0
  val kbps = measurement.downloadedBytes.toDouble() / 1024.0 / seconds
  return if (kbps >= 1024.0) {
    String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024.0)
  } else {
    String.format(java.util.Locale.US, "%.0f KB/s", kbps)
  }
}