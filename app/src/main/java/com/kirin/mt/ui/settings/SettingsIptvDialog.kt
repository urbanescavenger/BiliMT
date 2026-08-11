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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.glass.biliLiquidGlassSurface
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

/**
 * IPTV 源地址编辑弹窗(居中叠层):单个 URL 字段,走系统输入法(OutlinedTextField 唤起 IME)。
 * 镜像 [SettingsWebDavDialog] 的 stateless 模式 —— 保存时把 URL 通过 [onSave] 回调上抛。
 */
@Composable
internal fun SettingsIptvDialog(
  url: String,
  onSave: (String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val panelShape = RoundedCornerShape(BiliRadius.Panel)
  val performancePolicy = LocalBiliPerformancePolicy.current

  var urlValue by remember { mutableStateOf(url) }
  val urlFocusRequester = remember { FocusRequester() }
  val saveFocusRequester = remember { FocusRequester() }

  BackHandler { onDismiss() }

  // 弹窗打开时把焦点落到 URL 字段,自动唤起系统 IME。
  LaunchedEffect(Unit) {
    runCatching { urlFocusRequester.requestFocus() }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .imePadding()
      .background(Color.Black.copy(alpha = 0.55f)),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .width(720.dp)
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
        text = stringResource(R.string.settings_iptv_title),
        color = homeColors.textSecondary,
        fontSize = BiliTypography.SectionTitle,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.settings_iptv_description),
        color = homeColors.textSecondary,
        fontSize = BiliTypography.BodySmall,
      )

      OutlinedTextField(
        value = urlValue,
        onValueChange = { urlValue = it },
        label = { Text(stringResource(R.string.settings_iptv_url_label)) },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(urlFocusRequester),
      )

      Row(
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
        modifier = Modifier.fillMaxWidth(),
      ) {
        IptvActionButton(
          label = stringResource(R.string.settings_webdav_save),
          modifier = Modifier
            .weight(1f)
            .focusRequester(saveFocusRequester),
          onClick = { onSave(urlValue.trim()) },
        )
        IptvActionButton(
          label = stringResource(R.string.mobile_dialog_cancel),
          modifier = Modifier.weight(1f),
          onClick = onDismiss,
        )
      }
    }
  }
}

@Composable
private fun IptvActionButton(
  label: String,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Card),
    onClick = onClick,
    modifier = modifier.height(BiliSizing.SearchKeyboardButtonHeight),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .clip(RoundedCornerShape(BiliRadius.Card))
        .background(homeColors.accent.copy(alpha = BiliFocus.SettingsChipSelectedBackgroundAlpha)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        color = homeColors.textPrimary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}
