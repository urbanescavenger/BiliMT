package com.kirin.mt.core.settings

import com.kirin.mt.core.i18n.ChineseTextVariant
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.player.DefaultPlaybackSpeed
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.player.PlaybackBufferMax
import com.kirin.mt.core.player.YoutubeDefaultQuality
import com.kirin.mt.core.player.YoutubeDeliveryPriority
import com.kirin.mt.core.player.YoutubeStartQuality
import com.kirin.mt.core.youtube.YoutubeContentRegion

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
  /** YouTube 默认画质(按分辨率上限选档)。 */
  val youtubeDefaultQuality: YoutubeDefaultQuality = YoutubeDefaultQuality.Auto,
  /** YouTube SABR 自适应起播档(初始选轨分辨率)。Auto 自适应时由该档起播再逐档爬升。 */
  val youtubeStartQuality: YoutubeStartQuality = YoutubeStartQuality.Q480,
  /** YouTube 内容地区(gl/hl 联动;默认美国,不放中国因 gl=CN 实测触发反爬)。 */
  val youtubeContentRegion: YoutubeContentRegion = YoutubeContentRegion.US,
  /** 默认播放倍速(起播时初始化播放器 playbackSpeed)。 */
  val defaultPlaybackSpeed: DefaultPlaybackSpeed = DefaultPlaybackSpeed.X100,
  /** 播放缓冲时长上限(maxBuffer)。网络波动时缓冲池顶住不卡的时间;默认 50s 对齐 LibreTube。 */
  val bufferMax: PlaybackBufferMax = PlaybackBufferMax.Standard,
  val seekPreviewSpritesEnabled: Boolean = true,
  val airJumpAssistantEnabled: Boolean = true,
  val confirmPlaybackExit: Boolean = true,
  val autoPlayNextEpisode: Boolean = false,
  val autoPlayRelatedVideo: Boolean = false,
  val autoReturnHomeOnCompletion: Boolean = false,
  val showClock: Boolean = true,
  val showMiniProgressBar: Boolean = true,
  /** PGC 黑屏时把实时日志盖在画面上，便于直接排查。仅诊断用，默认关。 */
  val playerLogOverlayEnabled: Boolean = false,
  val autoConfirmOnFocus: Boolean = false,
  val autoRefreshOnSwitch: Boolean = false,
  val liquidGlassCardsEnabled: Boolean = false,
  val enabledHomeSections: Set<HomeSection> = HomeSection.DefaultOrder.toSet(),
  val homeSectionsOrder: List<HomeSection> = HomeSection.DefaultOrder,
  /** IPTV 源地址（远程 m3u 播放列表 URL）。空串表示未配置，Live 页 IPTV tab 显示引导。 */
  val iptvSourceUrl: String = "",
  /** IPTV 源账号（可选，Basic Auth）。 */
  val iptvSourceUsername: String = "",
  /** IPTV 源密码（可选，Basic Auth）。 */
  val iptvSourcePassword: String = "",
  /**
   * YouTube SABR 实验开关:走 Piped 后端 `/streams/{videoId}` 拿**已 attested 的 WEB-bound**
   * ustreamerConfig(对齐 LibreTube 默认 Piped 路径),修 NewPipe visionOS 路径拿未 attested config 致
   * RELOAD_PLAYER_RESPONSE 死循环。默认关——先走现有 NewPipe 路径,RELOAD 卡死时手动开作诊断/回退方案。
   * 见 [com.kirin.mt.core.youtube.piped.PipedClient] 与 docs/youtube-hd-playback.md「alpha.83 更正」段。
   */
  val youtubeUsePiped: Boolean = false,
  /** Piped 实例 URL(实验)。空串 = 用默认实例 [com.kirin.mt.core.youtube.YoutubePlaybackResolver.DEFAULT_PIPED_INSTANCE]。 */
  val pipedInstanceUrl: String = "",
  /**
   * SABR itag 无关诊断开关:强制视频轨用会话选中的 videoFormatId,跳过 selectFormat 按声明 itag 重选。
   * 证伪"某 itag(如 itag313)是 RELOAD 根因"——锁死后仍 RELOAD 则根因在 ustreamerConfig 来源。默认关。
   * Piped 路径默认开此项(配合 Piped 已 attested config 验证 itag 确实无关)。
   */
  val sabrForceSessionVideoItag: Boolean = false,
  /**
   * YouTube 播放路径优先级:主路径先走 SABR 还是 DASH 自合成兜底。默认 [com.kirin.mt.core.player.YoutubeDeliveryPriority.Sabr]
   * ——保持历史行为(NewPipe SABR 主路径 → DASH 兜底)。[com.kirin.mt.core.player.YoutubeDeliveryPriority.Dash]
   * 用于慢源/卡顿场景:慢 SABR 首段会被 8s stall 看门狗误杀触发完整重建,切 Dash 让 DASH 自合成优先
   * (NewPipe 已解密直链拼 MPD,实测能出 4K VP9)。见 docs/youtube-hd-playback.md。
   */
  val youtubeDeliveryPriority: YoutubeDeliveryPriority = YoutubeDeliveryPriority.Sabr,
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
