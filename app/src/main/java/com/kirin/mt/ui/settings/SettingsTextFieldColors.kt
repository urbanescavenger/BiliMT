package com.kirin.mt.ui.settings

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import com.kirin.mt.ui.theme.LocalHomeColors

/**
 * TV 端设置弹窗里 OutlinedTextField 的统一配色。
 *
 * TV 壳(BiliTvTheme)不包 MaterialTheme,M3 组件会回落到默认 lightColorScheme:
 * 文字/标签/边框全是近黑色,而弹窗面板是深色玻璃、输入框容器又默认透明 ——
 * 结果就是「暗底黑字」完全看不清(WebDAV / IPTV / TVBox / Piped 弹窗同病)。
 *
 * 这里按 [LocalHomeColors] 显式给一套深色配色:
 * - 文字/光标用 textPrimary / accent;
 * - 容器参照 TV 搜索框(focused=accent 12%,resting=glassSurfaceStrong);
 * - 边框 focused 用 accent、未聚焦用 glassBorder。
 *
 * 注意:OutlinedTextFieldDefaults.colors() 本身是 @Composable,不能包在 remember 里。
 */
@Composable
internal fun settingsTextFieldColors(): TextFieldColors {
  val homeColors = LocalHomeColors.current
  return OutlinedTextFieldDefaults.colors(
    focusedTextColor = homeColors.textPrimary,
    unfocusedTextColor = homeColors.textPrimary,
    disabledTextColor = homeColors.textTertiary,
    focusedContainerColor = homeColors.accent.copy(alpha = 0.12f),
    unfocusedContainerColor = homeColors.glassSurfaceStrong,
    disabledContainerColor = homeColors.glassSurfaceStrong,
    cursorColor = homeColors.accent,
    focusedBorderColor = homeColors.accent,
    unfocusedBorderColor = homeColors.glassBorder,
    disabledBorderColor = homeColors.glassBorder,
    focusedLabelColor = homeColors.accent,
    unfocusedLabelColor = homeColors.textSecondary,
    disabledLabelColor = homeColors.textTertiary,
    focusedPlaceholderColor = homeColors.textTertiary,
    unfocusedPlaceholderColor = homeColors.textTertiary,
    disabledPlaceholderColor = homeColors.textTertiary,
    focusedSupportingTextColor = homeColors.textTertiary,
    unfocusedSupportingTextColor = homeColors.textTertiary,
  )
}
