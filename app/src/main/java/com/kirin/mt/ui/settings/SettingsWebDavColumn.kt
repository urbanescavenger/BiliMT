package com.kirin.mt.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kirin.mt.R
import com.kirin.mt.core.webdav.WebDavConfig
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.launch

/** 编辑中的字段。 */
private enum class WebDavField { Url, Username, Password }

/**
 * 设置右侧「WebDAV 备份」面板。
 *
 * 内嵌轻量 D-pad 键盘(字母+数字+URL 符号),聚焦字段行切换当前编辑字段(URL/账号/密码),
 * 字段变更即持久化;「备份」「还原」按钮把当前配置传给 [WebDavBackupService]。左键统一回设置列。
 */
@Composable
internal fun SettingsWebDavColumn(
  config: WebDavConfig,
  onConfigChange: (WebDavConfig) -> Unit,
  onBackup: suspend (WebDavConfig) -> Result<Unit>,
  onRestore: suspend (WebDavConfig) -> Result<Int>,
  onMoveLeftToSettings: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

  var url by remember { mutableStateOf(config.url) }
  var username by remember { mutableStateOf(config.username) }
  var password by remember { mutableStateOf(config.password) }
  var selectedField by remember { mutableStateOf(WebDavField.Url) }
  var message by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }

  val urlFocusRequester = remember { FocusRequester() }
  val usernameFocusRequester = remember { FocusRequester() }
  val passwordFocusRequester = remember { FocusRequester() }

  // 面板打开时把焦点落到 URL 字段行,提供 D-pad 入口。
  LaunchedEffect(Unit) {
    runCatching { urlFocusRequester.requestFocus() }
  }

  fun currentValue(field: WebDavField): String = when (field) {
    WebDavField.Url -> url
    WebDavField.Username -> username
    WebDavField.Password -> password
  }

  fun setValue(field: WebDavField, value: String) {
    when (field) {
      WebDavField.Url -> url = value
      WebDavField.Username -> username = value
      WebDavField.Password -> password = value
    }
    onConfigChange(WebDavConfig(url, username, password))
    message = null
  }

  fun appendKey(key: String) {
    if (busy) return
    setValue(selectedField, currentValue(selectedField) + key)
  }

  fun backspace() {
    if (busy) return
    val value = currentValue(selectedField)
    if (value.isNotEmpty()) setValue(selectedField, value.dropLast(1))
  }

  fun runBackup() {
    if (busy) return
    busy = true
    message = null
    coroutineScope.launch {
      val result = onBackup(WebDavConfig(url, username, password))
      busy = false
      message = result.fold(
        onSuccess = { context.getString(R.string.settings_webdav_backup_success) },
        onFailure = { context.getString(R.string.settings_webdav_failed, it.message ?: "") },
      )
    }
  }

  fun runRestore() {
    if (busy) return
    busy = true
    message = null
    coroutineScope.launch {
      val result = onRestore(WebDavConfig(url, username, password))
      busy = false
      message = result.fold(
        onSuccess = { count -> context.getString(R.string.settings_webdav_restore_success, count) },
        onFailure = { context.getString(R.string.settings_webdav_failed, it.message ?: "") },
      )
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .onPreviewKeyEvent { event ->
        // 面板内任意位置按左键回设置列。
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
          onMoveLeftToSettings()
        } else {
          false
        }
      },
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

    // 字段选择行:聚焦某行即切换当前编辑字段。
    WebDavFieldRow(
      label = stringResource(R.string.settings_webdav_url_label),
      value = url,
      selected = selectedField == WebDavField.Url,
      modifier = Modifier
        .focusRequester(urlFocusRequester)
        .height(BiliSizing.SettingsRowHeight),
      onFocused = { selectedField = WebDavField.Url },
    )
    WebDavFieldRow(
      label = stringResource(R.string.settings_webdav_username_label),
      value = username,
      selected = selectedField == WebDavField.Username,
      modifier = Modifier
        .focusRequester(usernameFocusRequester)
        .height(BiliSizing.SettingsRowHeight),
      onFocused = { selectedField = WebDavField.Username },
    )
    WebDavFieldRow(
      label = stringResource(R.string.settings_webdav_password_label),
      value = password,
      selected = selectedField == WebDavField.Password,
      modifier = Modifier
        .focusRequester(passwordFocusRequester)
        .height(BiliSizing.SettingsRowHeight),
      onFocused = { selectedField = WebDavField.Password },
    )

    // 备份 / 还原 行。
    Row(
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      modifier = Modifier.fillMaxWidth(),
    ) {
      WebDavActionButton(
        label = stringResource(R.string.settings_webdav_backup),
        enabled = !busy,
        modifier = Modifier.weight(1f),
        onClick = ::runBackup,
      )
      WebDavActionButton(
        label = stringResource(R.string.settings_webdav_restore),
        enabled = !busy,
        modifier = Modifier.weight(1f),
        onClick = ::runRestore,
      )
    }

    // 键盘。
    WebDavKeyboard(
      onKey = ::appendKey,
      onBackspace = ::backspace,
    )

    // 状态提示(备份/还原成功/失败)。
    message?.let { msg ->
      Text(
        text = msg,
        color = homeColors.accent,
        fontSize = BiliTypography.BodySmall,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun WebDavFieldRow(
  label: String,
  value: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onFocused: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Panel),
    onFocused = onFocused,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = BiliSpacing.Lg),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        color = if (selected) homeColors.accent else homeColors.textSecondary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(end = BiliSpacing.Lg),
      )
      Text(
        text = if (value.isBlank()) stringResource(R.string.settings_webdav_field_hint) else value,
        color = if (value.isBlank()) homeColors.textTertiary else homeColors.textPrimary,
        fontSize = BiliTypography.Body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
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

/** 轻量 D-pad 键盘:字母 + 数字 + URL 符号,末行退格。 */
private val WebDavKeyboardRows = listOf(
  listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"),
  listOf("m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x"),
  listOf("y", "z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"),
  listOf("@", "-", "_", ".", "/", ":", "?", "=", "&", "#", "%", "+"),
)

@Composable
private fun WebDavKeyboard(
  onKey: (String) -> Unit,
  onBackspace: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm)) {
    WebDavKeyboardRows.forEach { row ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
        modifier = Modifier.fillMaxWidth(),
      ) {
        row.forEach { key ->
          WebDavKeyButton(
            label = key,
            modifier = Modifier
              .weight(1f)
              .height(BiliSizing.SearchKeyboardButtonHeight),
            onClick = { onKey(key) },
          )
        }
        // 末行(符号行)补一个等宽退格,其余行用透明占位保持行高一致。
        if (row.size == 12) {
          WebDavKeyButton(
            label = "⌫",
            modifier = Modifier
              .weight(1f)
              .height(BiliSizing.SearchKeyboardButtonHeight),
            onClick = onBackspace,
          )
        } else {
          Spacer(
            modifier = Modifier
              .weight(1f)
              .height(BiliSizing.SearchKeyboardButtonHeight),
          )
        }
      }
    }
  }
}

@Composable
private fun WebDavKeyButton(
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
    modifier = modifier,
  ) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        color = homeColors.textSecondary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}
