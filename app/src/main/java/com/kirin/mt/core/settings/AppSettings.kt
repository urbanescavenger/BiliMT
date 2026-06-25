package com.kirin.mt.core.settings

import com.kirin.mt.core.i18n.ChineseTextVariant
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackQualityPreference

enum class AppVisualPerformanceMode(val key: String) {
  Smooth("smooth"),
  Balanced("balanced"),
  Refined("refined");

  companion object {
    fun fromKey(key: String?): AppVisualPerformanceMode {
      return entries.firstOrNull { mode -> mode.key == key } ?: Balanced
    }
  }
}

enum class HomeThemeVariant(val key: String) {
  Pink("pink"),
  Black("black"),
  Gray("gray"),
  BlueGray("blue_gray");

  companion object {
    fun fromKey(key: String?): HomeThemeVariant {
      return entries.firstOrNull { theme -> theme.key == key } ?: Pink
    }
  }
}

data class AppSettings(
  val visualPerformanceMode: AppVisualPerformanceMode = AppVisualPerformanceMode.Balanced,
  val homeThemeVariant: HomeThemeVariant = HomeThemeVariant.Pink,
  val chineseTextVariant: ChineseTextVariant = ChineseTextVariant.Simplified,
  val playbackQualityPreference: PlaybackQualityPreference = PlaybackQualityPreference.Highest,
  val playbackCodecPreference: PlaybackCodecPreference = PlaybackCodecPreference.Auto,
  val playbackCdnPreference: PlaybackCdnPreference = PlaybackCdnPreference.Auto,
  val seekPreviewSpritesEnabled: Boolean = true,
  val airJumpAssistantEnabled: Boolean = true,
  val confirmPlaybackExit: Boolean = true,
  val autoPlayNextEpisode: Boolean = false,
  val autoPlayRelatedVideo: Boolean = false,
  val autoReturnHomeOnCompletion: Boolean = false,
  val showClock: Boolean = true,
  val showMiniProgressBar: Boolean = true,
  val autoConfirmOnFocus: Boolean = false,
  val autoRefreshOnSwitch: Boolean = false,
  val liquidGlassCardsEnabled: Boolean = false,
  val enabledHomeSections: Set<HomeSection> = HomeSection.DefaultOrder.toSet(),
  val homeSectionsOrder: List<HomeSection> = HomeSection.DefaultOrder,
) {
  val lowSpecMode: Boolean
    get() = visualPerformanceMode == AppVisualPerformanceMode.Smooth
}

data class AppPerformancePolicy(
  val lowSpecMode: Boolean,
  val visualPerformanceMode: AppVisualPerformanceMode,
  val motionEnabled: Boolean,
  val smoothScrollingEnabled: Boolean,
  val videoThumbnailWidthPx: Int,
  val videoThumbnailHeightPx: Int,
  val videoThumbnailRgb565Enabled: Boolean,
  val ownerAvatarSizePx: Int,
  val ownerAvatarRgb565Enabled: Boolean,
  val imageMemoryCacheEnabled: Boolean,
  val videoThumbnailPrefetchCount: Int,
  val focusShadowEnabled: Boolean,
  val loadMoreFocusThreshold: Int,
  val focusedCoverBlurEnabled: Boolean,
  val refinedVisualEffectsEnabled: Boolean,
  val cinematicVisualEffectsEnabled: Boolean,
  val liquidGlassCardsEnabled: Boolean,
) {
  companion object {
    val Balanced = AppPerformancePolicy(
      lowSpecMode = false,
      visualPerformanceMode = AppVisualPerformanceMode.Balanced,
      motionEnabled = true,
      smoothScrollingEnabled = true,
      videoThumbnailWidthPx = 640,
      videoThumbnailHeightPx = 360,
      videoThumbnailRgb565Enabled = false,
      ownerAvatarSizePx = 96,
      ownerAvatarRgb565Enabled = false,
      imageMemoryCacheEnabled = true,
      videoThumbnailPrefetchCount = 24,
      focusShadowEnabled = true,
      loadMoreFocusThreshold = 18,
      focusedCoverBlurEnabled = false,
      refinedVisualEffectsEnabled = true,
      cinematicVisualEffectsEnabled = false,
      liquidGlassCardsEnabled = false,
    )

    val Refined = AppPerformancePolicy(
      lowSpecMode = false,
      visualPerformanceMode = AppVisualPerformanceMode.Refined,
      motionEnabled = true,
      smoothScrollingEnabled = true,
      videoThumbnailWidthPx = 640,
      videoThumbnailHeightPx = 360,
      videoThumbnailRgb565Enabled = false,
      ownerAvatarSizePx = 96,
      ownerAvatarRgb565Enabled = false,
      imageMemoryCacheEnabled = true,
      videoThumbnailPrefetchCount = 24,
      focusShadowEnabled = true,
      loadMoreFocusThreshold = 18,
      focusedCoverBlurEnabled = true,
      refinedVisualEffectsEnabled = true,
      cinematicVisualEffectsEnabled = true,
      liquidGlassCardsEnabled = false,
    )

    val LowSpec = AppPerformancePolicy(
      lowSpecMode = true,
      visualPerformanceMode = AppVisualPerformanceMode.Smooth,
      motionEnabled = false,
      smoothScrollingEnabled = false,
      videoThumbnailWidthPx = 320,
      videoThumbnailHeightPx = 180,
      videoThumbnailRgb565Enabled = true,
      ownerAvatarSizePx = 48,
      ownerAvatarRgb565Enabled = true,
      imageMemoryCacheEnabled = false,
      videoThumbnailPrefetchCount = 0,
      focusShadowEnabled = false,
      loadMoreFocusThreshold = 6,
      focusedCoverBlurEnabled = false,
      refinedVisualEffectsEnabled = false,
      cinematicVisualEffectsEnabled = false,
      liquidGlassCardsEnabled = false,
    )

    private val ConstrainedTv = Balanced.copy(
      videoThumbnailWidthPx = 480,
      videoThumbnailHeightPx = 270,
      videoThumbnailRgb565Enabled = true,
      ownerAvatarSizePx = 72,
      ownerAvatarRgb565Enabled = true,
      videoThumbnailPrefetchCount = 8,
      focusShadowEnabled = false,
      loadMoreFocusThreshold = 8,
      focusedCoverBlurEnabled = false,
      refinedVisualEffectsEnabled = false,
      cinematicVisualEffectsEnabled = false,
    )

    val Standard = Balanced

    fun fromSettings(
      settings: AppSettings,
      constrainedTvUi: Boolean = false,
    ): AppPerformancePolicy {
      if (settings.visualPerformanceMode == AppVisualPerformanceMode.Smooth) {
        return LowSpec
      }
      if (constrainedTvUi) {
        return when (settings.visualPerformanceMode) {
          AppVisualPerformanceMode.Smooth -> LowSpec
          AppVisualPerformanceMode.Balanced -> ConstrainedTv.copy(visualPerformanceMode = AppVisualPerformanceMode.Balanced)
          AppVisualPerformanceMode.Refined -> Refined.copy(liquidGlassCardsEnabled = settings.liquidGlassCardsEnabled)
        }
      }
      return when (settings.visualPerformanceMode) {
        AppVisualPerformanceMode.Smooth -> LowSpec
        AppVisualPerformanceMode.Balanced -> Balanced
        AppVisualPerformanceMode.Refined -> Refined.copy(liquidGlassCardsEnabled = settings.liquidGlassCardsEnabled)
      }
    }
  }
}
