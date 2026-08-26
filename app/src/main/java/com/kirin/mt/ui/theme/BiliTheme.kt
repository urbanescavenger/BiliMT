package com.kirin.mt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.kirin.mt.core.settings.AppAppearanceMode
import com.kirin.mt.core.settings.HomeThemeVariant

@Composable
fun BiliTvTheme(content: @Composable () -> Unit) {
  content()
}

/** 错误/告警色(红),四套主题共用,保持错误语义稳定。 */
private val ThemeError = Color(0xFFCF6679)
private val ThemeOnError = Color(0xFF000000)

/**
 * 把一套 [HomeColorScheme] 映射成移动端 Material3 scheme。
 *
 * 移动端 UI 全部走 `MaterialTheme.colorScheme.*`(见 MobileApp / SettingsActivity / 各 Mobile*Screen),
 * 不读 TV 那套 `LocalHomeColors`。所以移动端切主题要在这一层把 HomeThemes 的字段
 * 转成 Material 语义字段:accent→primary、cardSurface→surface、cardInfoSurface→surfaceVariant 等。
 * [isDark] 决定深浅:暗色用 darkColorScheme、亮色用 lightColorScheme 的默认值打底,再覆盖语义字段。
 * 播放器显式黑底白字,不经过此 scheme(见 MobilePlayerScreen)。
 */
fun homeToMaterialScheme(colors: HomeColorScheme, isDark: Boolean): ColorScheme {
  val base = if (isDark) darkColorScheme() else lightColorScheme()
  return base.copy(
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
}

/**
 * 移动端主题包装:按 [HomeThemeVariant] 选主题色、按 [AppAppearanceMode] 定深浅
 * (Dark/Light 手动指定,Auto 跟随系统),取对应 HomeColorScheme 映射成 MaterialTheme 下发。
 * 移动端壳(MobileApp)与独立设置页(SettingsActivity)均消费;TV 端走 LocalHomeColors 不消费 appearance。
 */
@Composable
fun BiliMobileTheme(
  variant: HomeThemeVariant,
  appearance: AppAppearanceMode,
  content: @Composable () -> Unit,
) {
  val isDark = when (appearance) {
    AppAppearanceMode.Dark -> true
    AppAppearanceMode.Light -> false
    AppAppearanceMode.Auto -> isSystemInDarkTheme()
  }
  val colors = remember(variant, isDark) {
    if (isDark) HomeThemes.fromVariant(variant) else HomeThemes.lightFromVariant(variant)
  }
  MaterialTheme(colorScheme = remember(colors, isDark) { homeToMaterialScheme(colors, isDark) }) {
    content()
  }
}
