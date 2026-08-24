package com.kirin.mt.ui.settings

import android.widget.Toast
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

/**
 * WebDAV 编辑弹窗(居中叠层):URL/账号/密码三个字段,走系统输入法(OutlinedTextField 唤起 IME)。
 * 保存前校验连通(https 优先、http 兜底),成功才落库并关闭;失败在弹窗内提示。备份/还原在设置页单独按钮行。
 */
@Composable
internal fun SettingsWebDavDialog(
  config: WebDavConfig,
  onSave: suspend (WebDavConfig) -> Result<WebDavConfig>,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val panelShape = RoundedCornerShape(BiliRadius.Panel)
  val performancePolicy = LocalBiliPerformancePolicy.current
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

  var url by remember { mutableStateOf(config.url) }
  var username by remember { mutableStateOf(config.username) }
  var password by remember { mutableStateOf(config.password) }
  var saving by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  val urlFocusRequester = remember { FocusRequester() }
  val usernameFocusRequester = remember { FocusRequester() }
  val passwordFocusRequester = remember { FocusRequester() }
  val saveFocusRequester = remember { FocusRequester() }
  // D-pad 把焦点移到「保存」时,若按钮在滚动区下方不可见,滚动把它带进可视区。
  val saveBringIntoViewRequester = remember { BringIntoViewRequester() }

  BackHandler { if (!saving) onDismiss() }

  // 弹窗打开时焦点先落到 URL 字段(仅高亮不弹 IME),按确认键才唤起系统输入法。
  LaunchedEffect(Unit) {
    runCatching { urlFocusRequester.requestFocus() }
  }

  fun save() {
    if (saving) return
    saving = true
    error = null
    coroutineScope.launch {
      val result = onSave(WebDavConfig(url, username, password))
      saving = false
      result.fold(
        onSuccess = {
          Toast.makeText(context, R.string.settings_webdav_connect_success, Toast.LENGTH_SHORT).show()
          onDismiss()
        },
        onFailure = { error = it.message },
      )
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
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

      // 三字段参照搜索框:聚焦只高亮,按确认键才唤起 IME。
      OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.settings_webdav_url_label)) },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .then(confirmImeModifier(urlFocusRequester)),
      )
      OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text(stringResource(R.string.settings_webdav_username_label)) },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .then(confirmImeModifier(usernameFocusRequester)),
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
          .then(confirmImeModifier(passwordFocusRequester)),
      )

      // 校验失败提示(保持弹窗打开,不落库)。
      error?.let { msg ->
        Text(
          text = msg,
          color = homeColors.accent,
          fontSize = BiliTypography.BodySmall,
          fontWeight = FontWeight.Bold,
        )
      }

      // 保存 / 取消 行。
      Row(
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
        modifier = Modifier.fillMaxWidth(),
      ) {
        WebDavActionButton(
          label = if (saving) {
            stringResource(R.string.settings_webdav_validating)
          } else {
            stringResource(R.string.settings_webdav_save)
          },
          enabled = !saving,
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
        WebDavActionButton(
          label = stringResource(R.string.mobile_dialog_cancel),
          enabled = !saving,
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
