package com.kirin.mt.ui.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 移动端设置行:卡片式可点击行,标题 + 副标题 + 右侧 trailing 槽(放当前值文本或开关)。 */
@Composable
fun MobileSettingsRow(
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  enabled: Boolean = true,
  trailing: @Composable (() -> Unit)? = null,
  onClick: (() -> Unit)? = null,
) {
  val cardMod = if (onClick != null) {
    Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }
  } else {
    Modifier.fillMaxWidth()
  }
  Card(
    modifier = modifier.then(cardMod),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    shape = MaterialTheme.shapes.medium,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (!description.isNullOrBlank()) {
          Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }
      if (trailing != null) trailing()
    }
  }
}

/** 开关行:trailing 为 Material3 Switch。 */
@Composable
fun MobileSwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
) {
  MobileSettingsRow(
    title = title,
    description = description,
    modifier = modifier,
    onClick = { onCheckedChange(!checked) },
    trailing = {
      Switch(checked = checked, onCheckedChange = onCheckedChange)
    },
  )
}

/** 区段标题(不带卡片)。 */
@Composable
fun MobileSettingsSectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
  )
}