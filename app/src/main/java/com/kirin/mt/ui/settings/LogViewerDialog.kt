package com.kirin.mt.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.glass.biliLiquidGlassSurface
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import java.io.File

@Composable
internal fun LogViewerDialog(
  file: File,
  onDismiss: () -> Unit,
  onShare: (File) -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val panelShape = RoundedCornerShape(BiliRadius.Panel)
  val content = remember(file) { LogCatcherUtil.readLogPreview(file) }
  val lines = remember(content) { content.lines() }
  val listState = rememberLazyListState()
  val closeFocusRequester = remember { FocusRequester() }
  val shareFocusRequester = remember { FocusRequester() }

  BackHandler { onDismiss() }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.55f)),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .width(960.dp)
        .heightIn(max = 720.dp)
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
          lines.size,
        ),
        color = homeColors.textTertiary,
        fontSize = BiliTypography.BodySmall,
      )
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .clip(RoundedCornerShape(BiliRadius.Card))
          .background(homeColors.glassSurfaceStrong.copy(alpha = 0.5f))
          .padding(BiliSpacing.Md),
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
        ) {
          itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
            Text(
              text = line,
              color = homeColors.textSecondary,
              fontSize = BiliTypography.BodySmall,
              fontFamily = FontFamily.Monospace,
            )
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
          onClick = { onShare(file) },
          modifier = Modifier
            .width(200.dp)
            .focusRequester(shareFocusRequester),
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = BiliSpacing.Sm),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = stringResource(R.string.settings_logs_share),
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
          onClick = onDismiss,
          modifier = Modifier
            .width(200.dp)
            .focusRequester(closeFocusRequester),
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = BiliSpacing.Sm),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = stringResource(R.string.settings_logs_close),
              color = homeColors.textPrimary,
              fontSize = BiliTypography.Body,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    runCatching { closeFocusRequester.requestFocus() }
  }
}
