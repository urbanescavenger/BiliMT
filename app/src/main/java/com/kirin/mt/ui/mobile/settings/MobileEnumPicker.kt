package com.kirin.mt.ui.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kirin.mt.R

/** 可点选择行:点击弹出单选对话框,选项来自 [options](label + 选定回调)。 */
@Composable
fun <T> MobileEnumPickerRow(
  title: String,
  selected: T,
  options: List<Pair<T, String>>,
  onSelected: (T) -> Unit,
  modifier: Modifier = Modifier,
  description: String? = null,
  selectedLabel: String,
) {
  var showDialog by remember { mutableStateOf(false) }
  MobileSettingsRow(
    title = title,
    description = description,
    modifier = modifier,
    onClick = { showDialog = true },
    trailing = {
      Text(
        text = selectedLabel,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
  )
  if (showDialog) {
    AlertDialog(
      onDismissRequest = { showDialog = false },
      title = { Text(title) },
      text = {
        Column(modifier = Modifier.fillMaxWidth()) {
          options.forEach { (value, label) ->
            val isSel = value == selected
            Text(
              text = label,
              style = if (isSel) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
              color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  onSelected(value)
                  showDialog = false
                }
                .padding(vertical = 12.dp),
            )
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showDialog = false }) {
          Text(stringResource(R.string.mobile_dialog_cancel))
        }
      },
    )
  }
}