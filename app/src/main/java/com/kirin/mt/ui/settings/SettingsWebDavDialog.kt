package com.kirin.mt.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** 编辑中的字段。 */
private enum class WebDavField { Url, Username, Password }

/**
 * WebDAV 编辑弹窗(居中叠层):URL/账号/密码三个字段 + D-pad 键盘,聚焦字段行切换当前编辑字段。
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
  var selectedField by remember { mutableStateOf(WebDavField.Url) }

  val urlFocusRequester = remember { FocusRequester() }
  val usernameFocusRequester = remember { FocusRequester() }
  val passwordFocusRequester = remember { FocusRequester() }
  val saveFocusRequester = remember { FocusRequester() }

  BackHandler { onDismiss() }

  // 弹窗打开时把焦点落到 URL 字段行,提供 D-pad 入口。
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
  }

  fun save() = onSave(WebDavConfig(url, username, password))

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.55f)),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .width(720.dp)
        .heightIn(max = 680.dp)
        .biliLiquidGlassSurface(
          enabled = performancePolicy.cinematicVisualEffectsEnabled &&
            performancePolicy.liquidGlassCardsEnabled,
          shape = panelShape,
          surfaceColor = homeColors.cardSurface,
          borderColor = homeColors.glassBorder,
          borderWidth = BiliFocus.RestingBorderWidth,
        )
        .padding(BiliSpacing.Xl)
        .onPreviewKeyEvent { event ->
          // 弹窗内任意位置按左键回设置列。
          if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
            onDismiss()
            true
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

      // 保存 / 关闭 行。
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
          label = stringResource(R.string.settings_webdav_close),
          enabled = true,
          modifier = Modifier.weight(1f),
          onClick = onDismiss,
        )
      }

      // 键盘。
      WebDavKeyboard(
        onKey = { key -> setValue(selectedField, currentValue(selectedField) + key) },
        onBackspace = {
          val value = currentValue(selectedField)
          if (value.isNotEmpty()) setValue(selectedField, value.dropLast(1))
        },
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
