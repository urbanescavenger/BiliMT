package com.kirin.mt.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
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
    listOf(WebDavBackupItem.Channels, WebDavBackupItem.Piped, WebDavBackupItem.Watched)
  } else {
    WebDavBackupItem.entries
  }
  var selected by remember { mutableStateOf(items.toSet()) }
  val allFocusRequester = remember { FocusRequester() }
  // 中部选项列表滚动状态:随焦点移动,滚到底才进底部按钮。
  val listState = rememberLazyListState()

  @Composable
  fun itemLabel(item: WebDavBackupItem): String = when (item) {
    WebDavBackupItem.Channels -> stringResource(R.string.settings_webdav_item_channels)
    WebDavBackupItem.Piped -> stringResource(R.string.settings_webdav_item_piped)
    WebDavBackupItem.Watched -> stringResource(R.string.settings_webdav_item_watched)
    WebDavBackupItem.Logs -> stringResource(R.string.settings_webdav_item_logs)
  }

  // 打开时焦点先落到「全选」行,按确认切换全选,D-pad 下移逐项单选。
  LaunchedEffect(Unit) { runCatching { allFocusRequester.requestFocus() } }

  // 真 Dialog 窗口:独立 window 自带焦点根,D-pad 遍历不会逃到背后的设置页。
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = modifier
        .fillMaxSize()
        // 内缩留边,内容超高时滚动区也不会贴到屏幕上下边界。
        .padding(BiliSpacing.Xl),
      contentAlignment = Alignment.Center,
    ) {
      Column(
      modifier = Modifier
        .width(600.dp)
        .heightIn(max = 640.dp)
        .biliLiquidGlassSurface(
          enabled = true,
          shape = panelShape,
          surfaceColor = homeColors.cardSurface,
          borderColor = homeColors.glassBorder,
          borderWidth = BiliFocus.RestingBorderWidth,
        )
        .padding(BiliSpacing.Lg),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
    ) {
      // 顶部锁定:标题 + 全选(不随中间滚动)。
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

      // 中部可滚动选项:随焦点移动自动滚动,滚到底才进底部按钮。
      // weight(fill=false) —— 选项少时卡片自适应高度,多时占满剩余空间并滚动。
      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f, fill = false),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
      ) {
        items(items) { item ->
          CheckboxRow(
            label = itemLabel(item),
            checked = item in selected,
            onToggle = { selected = if (item in selected) selected - item else selected + item },
          )
        }
      }

      // 底部锁定:开始/取消(始终可见,不受中间滚动影响)。
      Row(
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
        modifier = Modifier.fillMaxWidth(),
      ) {
        PipedActionButton(
          label = stringResource(
            if (isRestore) R.string.settings_webdav_start_restore
            else R.string.settings_webdav_start_backup,
          ),
          modifier = Modifier.weight(1f),
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
      // 比设置页行(96dp)紧凑:备份 5 行+按钮才放得进 TV 窗口,否则底部按钮被裁剪。
      .height(72.dp)
      .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = BiliSpacing.Md),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CheckIndicator(checked = checked, accentColor = homeColors.accent, borderColor = homeColors.glassBorder)
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

/**
 * 手绘勾选指示:方形外框 + 选中时 accent 填充 + 白色对勾。
 * 刻意不用 material3 Checkbox——在 TV 的 BiliFocusableSurface+玻璃渲染路径下,
 * material3 Checkbox 会触发原生渲染崩溃(无 Java crash log),手绘绘制避开该路径。
 * 视觉对齐 material3 Checkbox(20dp 方形、圆角、对勾)。
 */
@Composable
private fun CheckIndicator(
  checked: Boolean,
  accentColor: Color,
  borderColor: Color,
) {
  val indicatorShape = RoundedCornerShape(4.dp)
  Box(
    modifier = Modifier
      .size(20.dp)
      .clip(indicatorShape)
      .background(if (checked) accentColor else Color.Transparent)
      .border(
        width = 1.dp,
        color = if (checked) accentColor else borderColor,
        shape = indicatorShape,
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (checked) {
      Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
        val path = Path().apply {
          moveTo(size.width * 0.20f, size.height * 0.52f)
          lineTo(size.width * 0.44f, size.height * 0.74f)
          lineTo(size.width * 0.82f, size.height * 0.26f)
        }
        drawPath(
          path = path,
          color = Color.White,
          style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
      }
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
