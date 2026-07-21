package com.kirin.mt.ui.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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

/**
 * 最新版本行:卡片内标题 + 描述 + 右侧动作文案(下载更新/下载中.../安装并重启),
 * progress != null 时在下方显示进度条。整卡在 onClick != null && actionEnabled 时可点
 * (对齐 MobileSettingsRow 的 clickable 模式与「检查更新」row 的 trailing-Text 模式)。
 * 用于把下载/进度/安装内联进「最新版本」row,不再单开一栏。
 */
@Composable
fun MobileUpdateVersionRow(
  title: String,
  description: String,
  actionLabel: String?,
  actionEnabled: Boolean,
  progress: Float?,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val clickable = onClick != null && actionEnabled
  val cardMod = if (clickable) {
    Modifier.fillMaxWidth().clickable { onClick?.invoke() }
  } else {
    Modifier.fillMaxWidth()
  }
  Card(
    modifier = modifier.then(cardMod),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    shape = MaterialTheme.shapes.medium,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
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
          if (description.isNotBlank()) {
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
        if (actionLabel != null) {
          Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = if (actionEnabled) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (progress != null) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { progress },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}