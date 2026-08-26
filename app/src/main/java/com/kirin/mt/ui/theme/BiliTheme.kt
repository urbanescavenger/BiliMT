package com.kirin.mt.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.kirin.mt.core.settings.HomeThemeVariant

@Composable
fun BiliTvTheme(content: @Composable () -> Unit) {
  content()
}

/** 错误/告警色(红),四套主题共用,保持错误语义稳定。 */
private val ThemeError = Color(0xFFCF6679)
private val ThemeOnError = Color(0xFF000000)

/**
 * 把一套 TV 主页暗色主题([HomeColorScheme])映射成移动端 Material3 深色 scheme。
 *
 * 移动端 UI 全部走 `MaterialTheme.colorScheme.*`(见 MobileApp / SettingsActivity / 各 Mobile*Screen),
 * 不读 TV 那套 `LocalHomeColors`。所以移动端切主题要在这一层把 HomeThemes 的字段
 * 转成 Material 语义字段:accent→primary、cardSurface→surface、cardInfoSurface→surfaceVariant 等。
 * 播放器显式黑底白字,不经过此 scheme(见 MobilePlayerScreen)。
 */
fun homeToMaterialScheme(colors: HomeColorScheme): ColorScheme = darkColorScheme(
  primary = colors.accent,
  onPrimary = colors.backgroundTop,
  primaryContainer = colors.cardFocusedSurface,
  onPrimaryContainer = colors.textPrimary,
  background = colors.backgroundTop,
  onBackground = colors.textPrimary,
  surface = colors.cardSurface,
  onSurface = colors.textPrimary,
  surfaceVariant = colors.cardInfoSurface,
  onSurfaceVariant = colors.textSecondary,
  outline = colors.textTertiary,
  outlineVariant = colors.glassBorder,
  error = ThemeError,
  onError = ThemeOnError,
)

/**
 * 移动端主题包装:按 [HomeThemeVariant] 选一套暗色 HomeColorScheme,映射成
 * MaterialTheme 下发,让移动端壳(BiliMobileApp)与独立设置页(SettingsActivity)
 * 跟随与 TV 同源的 4 套主题。variant 变化时重映射并触发整体重组。
 */
@Composable
fun BiliMobileTheme(variant: HomeThemeVariant, content: @Composable () -> Unit) {
  val colors = remember(variant) { HomeThemes.fromVariant(variant) }
  MaterialTheme(colorScheme = remember(colors) { homeToMaterialScheme(colors) }) {
    content()
  }
}
