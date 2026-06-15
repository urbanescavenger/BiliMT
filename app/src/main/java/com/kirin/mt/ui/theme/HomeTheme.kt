package com.kirin.mt.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.kirin.mt.core.settings.HomeThemeVariant

data class HomeColorScheme(
  val accent: Color,
  val backgroundTop: Color,
  val backgroundBottom: Color,
  val ambientA: Color,
  val ambientB: Color,
  val glassSurface: Color,
  val glassSurfaceStrong: Color,
  val sidebarSurface: Color,
  val glassBorder: Color,
  val cardSurface: Color,
  val cardInfoSurface: Color,
  val cardFocusedSurface: Color,
  val textPrimary: Color,
  val textSecondary: Color,
  val textTertiary: Color,
  val shineColor: Color,
)

val LocalHomeColors = staticCompositionLocalOf { HomeThemes.Pink }

object HomeThemes {
  val Pink = HomeColorScheme(
    accent = BiliColors.BiliPink,
    backgroundTop = BiliHomeThemeColors.PinkBackgroundTop,
    backgroundBottom = BiliHomeThemeColors.PinkBackgroundBottom,
    ambientA = BiliHomeThemeColors.PinkAmbientA,
    ambientB = BiliHomeThemeColors.PinkAmbientB,
    glassSurface = BiliHomeThemeColors.PinkGlassSurface,
    glassSurfaceStrong = BiliHomeThemeColors.PinkGlassSurfaceStrong,
    sidebarSurface = BiliHomeThemeColors.PinkSidebarSurface,
    glassBorder = BiliHomeThemeColors.PinkGlassBorder,
    cardSurface = BiliHomeThemeColors.PinkCardSurface,
    cardInfoSurface = BiliHomeThemeColors.PinkCardInfoSurface,
    cardFocusedSurface = BiliHomeThemeColors.PinkCardFocusedSurface,
    textPrimary = BiliColors.TextPrimary,
    textSecondary = BiliColors.TextSecondary,
    textTertiary = BiliColors.TextTertiary,
    shineColor = BiliColors.BiliPink,
  )

  val Black = HomeColorScheme(
    accent = BiliColors.BiliPink,
    backgroundTop = BiliHomeThemeColors.BlackBackgroundTop,
    backgroundBottom = BiliHomeThemeColors.BlackBackgroundBottom,
    ambientA = BiliHomeThemeColors.BlackAmbientA,
    ambientB = BiliHomeThemeColors.BlackAmbientB,
    glassSurface = BiliHomeThemeColors.BlackGlassSurface,
    glassSurfaceStrong = BiliHomeThemeColors.BlackGlassSurfaceStrong,
    sidebarSurface = BiliHomeThemeColors.BlackSidebarSurface,
    glassBorder = BiliHomeThemeColors.BlackGlassBorder,
    cardSurface = BiliHomeThemeColors.BlackCardSurface,
    cardInfoSurface = BiliHomeThemeColors.BlackCardInfoSurface,
    cardFocusedSurface = BiliHomeThemeColors.BlackCardFocusedSurface,
    textPrimary = BiliHomeThemeColors.BlackTextPrimary,
    textSecondary = BiliHomeThemeColors.BlackTextSecondary,
    textTertiary = BiliHomeThemeColors.BlackTextTertiary,
    shineColor = BiliHomeThemeColors.BlackShine,
  )

  val Gray = HomeColorScheme(
    accent = BiliHomeThemeColors.GrayAccent,
    backgroundTop = BiliHomeThemeColors.GrayBackgroundTop,
    backgroundBottom = BiliHomeThemeColors.GrayBackgroundBottom,
    ambientA = BiliHomeThemeColors.GrayAmbientA,
    ambientB = BiliHomeThemeColors.GrayAmbientB,
    glassSurface = BiliHomeThemeColors.GrayGlassSurface,
    glassSurfaceStrong = BiliHomeThemeColors.GrayGlassSurfaceStrong,
    sidebarSurface = BiliHomeThemeColors.GraySidebarSurface,
    glassBorder = BiliHomeThemeColors.GrayGlassBorder,
    cardSurface = BiliHomeThemeColors.GrayCardSurface,
    cardInfoSurface = BiliHomeThemeColors.GrayCardInfoSurface,
    cardFocusedSurface = BiliHomeThemeColors.GrayCardFocusedSurface,
    textPrimary = BiliHomeThemeColors.GrayTextPrimary,
    textSecondary = BiliHomeThemeColors.GrayTextSecondary,
    textTertiary = BiliHomeThemeColors.GrayTextTertiary,
    shineColor = BiliHomeThemeColors.GrayShine,
  )

  val BlueGray = HomeColorScheme(
    accent = BiliHomeThemeColors.BlueGrayAccent,
    backgroundTop = BiliHomeThemeColors.BlueGrayBackgroundTop,
    backgroundBottom = BiliHomeThemeColors.BlueGrayBackgroundBottom,
    ambientA = BiliHomeThemeColors.BlueGrayAmbientA,
    ambientB = BiliHomeThemeColors.BlueGrayAmbientB,
    glassSurface = BiliHomeThemeColors.BlueGrayGlassSurface,
    glassSurfaceStrong = BiliHomeThemeColors.BlueGrayGlassSurfaceStrong,
    sidebarSurface = BiliHomeThemeColors.BlueGraySidebarSurface,
    glassBorder = BiliHomeThemeColors.BlueGrayGlassBorder,
    cardSurface = BiliHomeThemeColors.BlueGrayCardSurface,
    cardInfoSurface = BiliHomeThemeColors.BlueGrayCardInfoSurface,
    cardFocusedSurface = BiliHomeThemeColors.BlueGrayCardFocusedSurface,
    textPrimary = BiliHomeThemeColors.BlueGrayTextPrimary,
    textSecondary = BiliHomeThemeColors.BlueGrayTextSecondary,
    textTertiary = BiliHomeThemeColors.BlueGrayTextTertiary,
    shineColor = BiliHomeThemeColors.BlueGrayShine,
  )

  fun fromVariant(variant: HomeThemeVariant): HomeColorScheme {
    return when (variant) {
      HomeThemeVariant.Pink -> Pink
      HomeThemeVariant.Black -> Black
      HomeThemeVariant.Gray -> Gray
      HomeThemeVariant.BlueGray -> BlueGray
    }
  }
}
