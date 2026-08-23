package com.kirin.mt.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 参照搜索框的输入态策略:设置弹窗(WebDAV/IPTV/Piped)的字段聚焦只高亮、不自动弹系统 IME;
 * 按确认键才唤起 IME 进入输入态;焦点移开(去下一个字段或保存/取消按钮)时退出输入态并收起 IME。
 *
 * 用法:把该 Modifier 追加到 OutlinedTextField 的 modifier 上(内部自带 focusRequester):
 * ```
 * OutlinedTextField(
 *   ...,
 *   modifier = Modifier.fillMaxWidth().then(confirmImeModifier(focusRequester)),
 * )
 * ```
 */
@Composable
internal fun confirmImeModifier(focusRequester: FocusRequester): Modifier {
  val keyboardController = LocalSoftwareKeyboardController.current
  var focused by remember { mutableStateOf(false) }
  var imeActive by remember { mutableStateOf(false) }

  // 兜底:焦点帧后系统仍可能自动弹 IME,再压一次确保「仅聚焦不弹、确认才进输入态」。
  LaunchedEffect(focused, imeActive) {
    if (focused && !imeActive) {
      keyboardController?.hide()
    }
  }

  return Modifier
    .focusRequester(focusRequester)
    .onFocusChanged { focusState ->
      focused = focusState.isFocused
      if (!focusState.isFocused) {
        // 焦点移开(下一个字段/保存按钮):退出输入态、收起 IME。
        if (imeActive) {
          imeActive = false
        }
      } else if (!imeActive) {
        // 仅聚焦不自动弹系统 IME,等按确认键再进入输入态。
        keyboardController?.hide()
      }
    }
    .onPreviewKeyEvent { event ->
      if (event.type == KeyEventType.KeyDown && event.key.isConfirmKey() && !imeActive) {
        imeActive = true
        keyboardController?.show()
        true
      } else {
        false
      }
    }
}

private fun Key.isConfirmKey(): Boolean {
  return this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
}
