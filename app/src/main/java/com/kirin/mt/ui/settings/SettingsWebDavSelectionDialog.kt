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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
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
import com.kirin.mt.core.webdav.WebDavBackupItem
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.glass.biliLiquidGlassSurface
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

/**
 * WebDAV 备份/还原选择弹窗(居中叠层):「全选」复选框行 + 各项复选框行,均用 [BiliFocusableSurface]
 * 支持 D-pad 聚焦切换;底部「开始/取消」按钮。还原时只列频道+Piped(日志只备份不还原)。
 * stateless——选中的项目子集经 [onConfirm] 上抛,由调用方执行备份/还原并恢复焦点。
 */
@Composable
internal fun SettingsWebDavSelectionDialog(
  isRestore: Boolean,
  onConfirm: (Set<WebDavBackupItem>) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val panelShape = RoundedCornerShape(BiliRadius.Panel)
  val items = if (isRestore) {
    listOf(WebDavBackupItem.Channels, WebDavBackupItem.Piped)
  } else {
    WebDavBackupItem.entries
  }
  var selected by remember { mutableStateOf(items.toSet()) }
  val allFocusRequester = remember { FocusRequester() }
  val startFocusRequester = remember { FocusRequester() }

  fun itemLabel(item: WebDavBackupItem): String = when (item) {
    WebDavBackupItem.Channels -> stringResource(R.string.settings_webdav_item_channels)
    WebDavBackupItem.Piped -> stringResource(R.string.settings_webdav_item_piped)
    WebDavBackupItem.Logs -> stringResource(R.string.settings_webdav_item_logs)
  }

  BackHandler { onDismiss() }

  // 打开时焦点先落到「全选」行,按确认切换全选,D-pad 下移逐项单选。
  LaunchedEffect(Unit) { runCatching { allFocusRequester.requestFocus() } }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.55f)),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .width(600.dp)
        .verticalScroll(rememberScrollState())
        .biliLiquidGlassSurface(
          enabled = true,
          shape = panelShape,
          surfaceColor = homeColors.cardSurface,
          borderColor = homeColors.glassBorder,
          borderWidth = BiliFocus.RestingBorderWidth,
        )
        .padding(BiliSpacing.Xl),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
    ) {
      Text(
        text = stringResource(
          if (isRestore) R.string.settings_webdav_restore_select_title
          else R.string.settings_webdav_select_title,
        ),
        color = homeColors.textSecondary,
        fontSize = BiliTypography.SectionTitle,
        fontWeight = FontWeight.Bold,
      )

      CheckboxRow(
        label = stringResource(R.string.settings_webdav_select_all),
        checked = selected.size == items.size,
        focusRequester = allFocusRequester,
        onToggle = {
          selected = if (selected.size == items.size) emptySet() else items.toSet()
        },
      )
      items.forEach { item ->
        CheckboxRow(
          label = itemLabel(item),
          checked = item in selected,
          onToggle = { selected = if (item in selected) selected - item else selected + item },
        )
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
        modifier = Modifier.fillMaxWidth(),
      ) {
        PipedActionButton(
          label = stringResource(
            if (isRestore) R.string.settings_webdav_start_restore
            else R.string.settings_webdav_start_backup,
          ),
          modifier = Modifier
            .weight(1f)
            .focusRequester(startFocusRequester),
          onClick = { if (selected.isNotEmpty()) onConfirm(selected) },
        )
        PipedActionButton(
          label = stringResource(R.string.mobile_dialog_cancel),
          modifier = Modifier.weight(1f),
          onClick = onDismiss,
        )
      }
    }
  }
}

/** 单个复选框行:整行可聚焦点击切换,左侧复选框 + 右侧文案。 */
@Composable
private fun CheckboxRow(
  label: String,
  checked: Boolean,
  onToggle: () -> Unit,
  focusRequester: FocusRequester? = null,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Card),
    onClick = onToggle,
    modifier = Modifier
      .fillMaxWidth()
      .height(BiliSizing.SettingsRowHeight)
      .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = BiliSpacing.Md),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(checked = checked, onCheckedChange = { onToggle() })
      Text(
        text = label,
        color = homeColors.textPrimary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = BiliSpacing.Sm),
      )
    }
  }
}

/** 底部「开始/取消」按钮(镜像 SettingsPipedDialog 的 PipedActionButton)。 */
@Composable
private fun PipedActionButton(
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
