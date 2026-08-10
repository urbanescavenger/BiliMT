package com.kirin.mt.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.webdav.WebDavConfig
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.glass.biliLiquidGlassSurface
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

/**
 * WebDAV 编辑弹窗(居中叠层):URL/账号/密码三个字段,走系统输入法(OutlinedTextField 唤起 IME)。
 * 保存/关闭回设置列。仅编辑配置,备份/还原在设置页单独按钮行。
 */
@Composable
internal fun SettingsWebDavDialog(
  config: WebDavConfig,
  onSave: (WebDavConfig) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val panelShape = RoundedCornerShape(BiliRadius.Panel)
  val performancePolicy = LocalBiliPerformancePolicy.current

  var url by remember { mutableStateOf(config.url) }
  var username by remember { mutableStateOf(config.username) }
  var password by remember { mutableStateOf(config.password) }

  val urlFocusRequester = remember { FocusRequester() }
  val usernameFocusRequester = remember { FocusRequester() }
  val passwordFocusRequester = remember { FocusRequester() }
  val saveFocusRequester = remember { FocusRequester() }

  BackHandler { onDismiss() }

  // 弹窗打开时把焦点落到 URL 字段,自动唤起系统 IME。
  LaunchedEffect(Unit) {
    runCatching { urlFocusRequester.requestFocus() }
  }

  fun save() = onSave(WebDavConfig(url, username, password))

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
        .heightIn(max = 680.dp)
        .verticalScroll(rememberScrollState())
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
        text = stringResource(R.string.settings_webdav_title),
        color = homeColors.textSecondary,
        fontSize = BiliTypography.SectionTitle,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.settings_webdav_description),
        color = homeColors.textSecondary,
        fontSize = BiliTypography.BodySmall,
      )

      // 三字段走系统输入法:聚焦 OutlinedTextField 自动唤起 IME。
      OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.settings_webdav_url_label)) },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(urlFocusRequester),
      )
      OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text(stringResource(R.string.settings_webdav_username_label)) },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(usernameFocusRequester),
      )
      OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.settings_webdav_password_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(passwordFocusRequester),
      )

      // 保存 / 取消 行。
      Row(
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
        modifier = Modifier.fillMaxWidth(),
      ) {
        WebDavActionButton(
          label = stringResource(R.string.settings_webdav_save),
          enabled = true,
          modifier = Modifier
            .weight(1f)
            .focusRequester(saveFocusRequester),
          onClick = ::save,
        )
        WebDavActionButton(
          label = stringResource(R.string.mobile_dialog_cancel),
          enabled = true,
          modifier = Modifier.weight(1f),
          onClick = onDismiss,
        )
      }
    }
  }
}

@Composable
private fun WebDavActionButton(
  label: String,
  enabled: Boolean,
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
        .background(
          if (enabled) homeColors.accent.copy(alpha = BiliFocus.SettingsChipSelectedBackgroundAlpha)
          else homeColors.glassSurfaceStrong,
        ),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        color = if (enabled) homeColors.textPrimary else homeColors.textTertiary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}
