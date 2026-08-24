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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import kotlinx.coroutines.launch

/**
 * IPTV 源编辑弹窗(居中叠层):URL/账号/密码三个字段,走系统输入法(OutlinedTextField 唤起 IME)。
 * 镜像 [SettingsWebDavDialog] 的 stateless 模式 —— 保存时把 URL(自动补全 http/https)与账号密码
 * 通过 [onSave] 回调上抛。
 */
@Composable
internal fun SettingsIptvDialog(
  url: String,
  username: String,
  password: String,
  onSave: (url: String, username: String, password: String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val panelShape = RoundedCornerShape(BiliRadius.Panel)
  val performancePolicy = LocalBiliPerformancePolicy.current
  val coroutineScope = rememberCoroutineScope()

  var urlValue by remember { mutableStateOf(url) }
  var usernameValue by remember { mutableStateOf(username) }
  var passwordValue by remember { mutableStateOf(password) }

  val urlFocusRequester = remember { FocusRequester() }
  val usernameFocusRequester = remember { FocusRequester() }
  val passwordFocusRequester = remember { FocusRequester() }
  val saveFocusRequester = remember { FocusRequester() }
  // D-pad 把焦点移到「保存」时,若按钮在滚动区下方不可见,滚动把它带进可视区。
  val saveBringIntoViewRequester = remember { BringIntoViewRequester() }

  BackHandler { onDismiss() }

  // 弹窗打开时焦点先落到 URL 字段(仅高亮不弹 IME),按确认键才唤起系统输入法。
  LaunchedEffect(Unit) {
    runCatching { urlFocusRequester.requestFocus() }
  }

  fun save() = onSave(normalizeIptvUrl(urlValue), usernameValue.trim(), passwordValue)

  Box(
    modifier = modifier
      .fillMaxSize()
      // 焦点组:把 D-pad 遍历限定在弹窗内,顶部再按上键不会跑到背后设置行。
      .focusGroup()
      .imePadding()
      .background(Color.Black.copy(alpha = 0.55f))
      // 内缩留边,内容超高时滚动区也不会贴到屏幕上下边界。
      .padding(BiliSpacing.Xl),
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

      // 三字段参照搜索框:聚焦只高亮,按确认键才唤起 IME。
      OutlinedTextField(
        value = urlValue,
        onValueChange = { urlValue = it },
        label = { Text(stringResource(R.string.settings_iptv_url_label)) },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .then(confirmImeModifier(urlFocusRequester)),
      )
      OutlinedTextField(
        value = usernameValue,
        onValueChange = { usernameValue = it },
        label = { Text(stringResource(R.string.settings_iptv_username_label)) },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .then(confirmImeModifier(usernameFocusRequester)),
      )
      OutlinedTextField(
        value = passwordValue,
        onValueChange = { passwordValue = it },
        label = { Text(stringResource(R.string.settings_iptv_password_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier
          .fillMaxWidth()
          .then(confirmImeModifier(passwordFocusRequester)),
      )

      // 保存 / 取消 行(对照 WebDAV 弹窗)。
      Row(
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
        modifier = Modifier.fillMaxWidth(),
      ) {
        IptvActionButton(
          label = stringResource(R.string.settings_webdav_save),
          modifier = Modifier
            .weight(1f)
            .focusRequester(saveFocusRequester)
            .bringIntoViewRequester(saveBringIntoViewRequester)
            .onFocusChanged { focusState ->
              if (focusState.isFocused) {
                coroutineScope.launch { saveBringIntoViewRequester.bringIntoView() }
              }
            },
          onClick = ::save,
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

/**
 * 补全 IPTV 源 URL:不带 http:// 或 https:// 时自动补 https://(优先加密)。
 * 空串原样返回(未配置)。
 */
internal fun normalizeIptvUrl(raw: String): String {
  val trimmed = raw.trim()
  if (trimmed.isEmpty()) return trimmed
  return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
    trimmed
  } else {
    "https://$trimmed"
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
