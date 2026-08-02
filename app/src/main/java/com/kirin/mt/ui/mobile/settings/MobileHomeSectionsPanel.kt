package com.kirin.mt.ui.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.core.settings.AppSettingsStore
import com.kirin.mt.ui.home.titleRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 移动端「首页分区」排序+显示隐藏面板:触屏版镜像 TV `SettingsHomeSectionsColumn`
 * (SettingsRightPanels.kt),去掉 D-pad/FocusRequester 焦点逻辑,改为 Switch + ▲/▼ IconButton。
 *
 * 默认折叠:标题行点击才展开 33 行列表,避免一进设置就被占满。复用同一份
 * AppSettings(AppSettingsStore 持久化到共享 bili_settings DataStore),故此处改动与
 * TV 端完全同步:TV 改顺序/显隐移动端即时反映,反之亦然。
 */
@Composable
fun MobileHomeSectionsPanel(
  settings: AppSettings,
  appSettingsStore: AppSettingsStore,
  scope: CoroutineScope,
  modifier: Modifier = Modifier,
) {
  // 默认折叠:一进设置不占满 33 行;点标题行展开/收起。
  var expanded by remember { mutableStateOf(false) }
  val order = settings.homeSectionsOrder
  val enabled = settings.enabledHomeSections
  val lastIndex = order.lastIndex

  Column(modifier = modifier.fillMaxWidth()) {
    // 折叠头:着色与内边距对齐 MobileSettingsSectionHeader,右侧 ▼/▲ 指示态。
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }
        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.settings_home_sections_section),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = if (expanded) "▲" else "▼",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    if (expanded) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium,
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
          order.forEachIndexed { index, section ->
            if (index > 0) Spacer(Modifier.padding(top = 8.dp))
            MobileHomeSectionOrderRow(
              section = section,
              enabled = section in enabled,
              canMoveUp = index > 0,
              canMoveDown = index < lastIndex,
              onToggle = { checked ->
                scope.launch { appSettingsStore.setHomeSectionEnabled(section, checked) }
              },
              onMoveUp = {
                scope.launch { appSettingsStore.setHomeSectionsOrder(HomeSection.swapped(order, index, index - 1)) }
              },
              onMoveDown = {
                scope.launch { appSettingsStore.setHomeSectionsOrder(HomeSection.swapped(order, index, index + 1)) }
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MobileHomeSectionOrderRow(
  section: HomeSection,
  enabled: Boolean,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  onToggle: (Boolean) -> Unit,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = stringResource(section.titleRes()),
      style = MaterialTheme.typography.bodyLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
      // 隐藏的分区淡化,与开关状态视觉呼应。
      color = if (enabled) MaterialTheme.colorScheme.onSurface
      else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Switch(checked = enabled, onCheckedChange = onToggle)
    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
      Text(
        text = "▲",
        style = MaterialTheme.typography.titleMedium,
      )
    }
    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
      Text(
        text = "▼",
        style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}