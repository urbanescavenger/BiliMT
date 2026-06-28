package com.kirin.mt.core.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.kirin.mt.core.i18n.ChineseTextVariant
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.storage.biliDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppSettingsStore(private val context: Context) {
  private val defaultVisualPerformanceMode by lazy(LazyThreadSafetyMode.NONE) {
    context.defaultVisualPerformanceMode()
  }

  val settings: Flow<AppSettings> = context.biliDataStore.data.map { preferences ->
    val persistedEnabledKeys = preferences[Keys.EnabledHomeSections]
    val enabledSections = if (persistedEnabledKeys.isNullOrEmpty()) {
      HomeSection.DefaultOrder.toSet()
    } else {
      val mapped = persistedEnabledKeys.mapNotNull(HomeSection::fromKey).toSet()
      if (preferences[Keys.HomeSectionsUgcMigrationV1] == true) {
        // 迁移已跑:持久化集合即真相,用户显式禁用的新分区 key 不再被补回。
        mapped
      } else {
        // 迁移未跑:临时前向兼容补启用本轮新增 UGC 分区(ensureHomeSectionsMigration 跑过后走 mapped 分支)。
        val missingNew = HomeSection.DefaultOrder.filter {
          it.key in newlyAddedHomeSectionKeys && it.key !in persistedEnabledKeys
        }
        (mapped + missingNew).toSet()
      }
    }

    val homeSectionsOrder = preferences[Keys.HomeSectionsOrder]
      ?.split(',')
      ?.mapNotNull(HomeSection::fromKey)
      ?.let { ordered ->
        // 追加未出现在持久化列表里的分区（前向兼容：新增分区时自动补到末尾）
        val missing = HomeSection.DefaultOrder.filter { it !in ordered }
        if (missing.isEmpty()) ordered else ordered + missing
      }
      ?: HomeSection.DefaultOrder

    val autoConfirmOnFocus = preferences[Keys.AutoConfirmOnFocus] ?: false
    val autoRefreshOnSwitch = autoConfirmOnFocus && (preferences[Keys.AutoRefreshOnSwitch] ?: false)
    val liquidGlassCardsEnabled = supportsLiquidGlassCards() && (preferences[Keys.LiquidGlassCardsEnabled] ?: false)

    val visualPerformanceMode = preferences[Keys.VisualPerformanceMode]
      ?.let(AppVisualPerformanceMode::fromKey)
      ?: if (preferences[Keys.LowSpecMode] == true) {
        AppVisualPerformanceMode.Smooth
      } else {
        defaultVisualPerformanceMode
      }

    AppSettings(
      visualPerformanceMode = visualPerformanceMode,
      homeThemeVariant = HomeThemeVariant.fromKey(preferences[Keys.HomeThemeVariant]),
      chineseTextVariant = ChineseTextVariant.fromKey(preferences[Keys.ChineseTextVariant]),
      playbackQualityPreference = PlaybackQualityPreference.fromKey(preferences[Keys.PlaybackQualityPreference]),
      playbackCodecPreference = PlaybackCodecPreference.fromKey(preferences[Keys.PlaybackCodecPreference]),
      playbackCdnPreference = PlaybackCdnPreference.fromKey(preferences[Keys.PlaybackCdnPreference]),
      seekPreviewSpritesEnabled = preferences[Keys.SeekPreviewSpritesEnabled] ?: true,
      airJumpAssistantEnabled = preferences[Keys.AirJumpAssistantEnabled] ?: true,
      confirmPlaybackExit = preferences[Keys.ConfirmPlaybackExit] ?: true,
      autoPlayNextEpisode = preferences[Keys.AutoPlayNextEpisode] ?: false,
      autoPlayRelatedVideo = preferences[Keys.AutoPlayRelatedVideo] ?: false,
      autoReturnHomeOnCompletion = preferences[Keys.AutoReturnHomeOnCompletion] ?: false,
      showClock = preferences[Keys.ShowClock] ?: true,
      showMiniProgressBar = preferences[Keys.ShowMiniProgressBar] ?: true,
      playerLogOverlayEnabled = preferences[Keys.PlayerLogOverlayEnabled] ?: false,
      autoConfirmOnFocus = autoConfirmOnFocus,
      autoRefreshOnSwitch = autoRefreshOnSwitch,
      liquidGlassCardsEnabled = liquidGlassCardsEnabled,
      enabledHomeSections = enabledSections,
      homeSectionsOrder = homeSectionsOrder,
    )
  }

  suspend fun setLowSpecMode(enabled: Boolean) {
    setVisualPerformanceMode(if (enabled) AppVisualPerformanceMode.Smooth else AppVisualPerformanceMode.Balanced)
  }

  suspend fun setVisualPerformanceMode(mode: AppVisualPerformanceMode) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.VisualPerformanceMode] = mode.key
      preferences[Keys.LowSpecMode] = mode == AppVisualPerformanceMode.Smooth
    }
  }

  suspend fun setHomeThemeVariant(variant: HomeThemeVariant) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.HomeThemeVariant] = variant.key
    }
  }

  suspend fun setChineseTextVariant(variant: ChineseTextVariant) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.ChineseTextVariant] = variant.key
    }
  }

  suspend fun setSeekPreviewSpritesEnabled(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.SeekPreviewSpritesEnabled] = enabled
    }
  }

  suspend fun setPlaybackCodecPreference(preference: PlaybackCodecPreference) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.PlaybackCodecPreference] = preference.key
    }
  }

  suspend fun setPlaybackQualityPreference(preference: PlaybackQualityPreference) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.PlaybackQualityPreference] = preference.key
    }
  }

  suspend fun setPlaybackCdnPreference(preference: PlaybackCdnPreference) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.PlaybackCdnPreference] = preference.key
    }
  }

  suspend fun setAirJumpAssistantEnabled(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.AirJumpAssistantEnabled] = enabled
    }
  }

  suspend fun setConfirmPlaybackExit(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.ConfirmPlaybackExit] = enabled
    }
  }

  suspend fun setAutoPlayNextEpisode(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.AutoPlayNextEpisode] = enabled
    }
  }

  suspend fun setAutoPlayRelatedVideo(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.AutoPlayRelatedVideo] = enabled
    }
  }

  suspend fun setAutoReturnHomeOnCompletion(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.AutoReturnHomeOnCompletion] = enabled
    }
  }

  suspend fun setShowClock(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.ShowClock] = enabled
    }
  }

  suspend fun setShowMiniProgressBar(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.ShowMiniProgressBar] = enabled
    }
  }

  suspend fun setPlayerLogOverlayEnabled(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.PlayerLogOverlayEnabled] = enabled
    }
  }

  suspend fun setAutoConfirmOnFocus(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.AutoConfirmOnFocus] = enabled
      if (!enabled) {
        preferences[Keys.AutoRefreshOnSwitch] = false
      }
    }
  }

  suspend fun setAutoRefreshOnSwitch(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.AutoRefreshOnSwitch] = enabled && (preferences[Keys.AutoConfirmOnFocus] ?: false)
    }
  }

  suspend fun setLiquidGlassCardsEnabled(enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      preferences[Keys.LiquidGlassCardsEnabled] = enabled && supportsLiquidGlassCards()
    }
  }

  suspend fun setHomeSectionEnabled(section: HomeSection, enabled: Boolean) {
    context.biliDataStore.edit { preferences ->
      val current = preferences[Keys.EnabledHomeSections]
        ?.mapNotNull(HomeSection::fromKey)
        ?.toMutableSet()
        ?: HomeSection.DefaultOrder.toMutableSet()

      if (enabled) {
        current.add(section)
      } else if (current.size > 1) {
        current.remove(section)
      }

      preferences[Keys.EnabledHomeSections] = current.map { item -> item.key }.toSet()
    }
  }

  /**
   * 一次性迁移:把本轮新增的 UGC 分区写进持久化启用集合并置标志。
   * 之前读路径每次都临时补启用这些 key,导致用户在显隐面板关掉它们时 remove 是 no-op、
   * 下次发射又被补回。迁移后持久化集合即真相,显式禁用才生效。首启由 AppShell 调一次。
   */
  suspend fun ensureHomeSectionsMigration() {
    context.biliDataStore.edit { preferences ->
      if (preferences[Keys.HomeSectionsUgcMigrationV1] == true) return@edit
      val current = preferences[Keys.EnabledHomeSections]
        ?.mapNotNull(HomeSection::fromKey)
        ?.toMutableSet()
        ?: HomeSection.DefaultOrder.toMutableSet()
      val missingNew = HomeSection.DefaultOrder.filter {
        it.key in newlyAddedHomeSectionKeys && it !in current
      }
      if (missingNew.isNotEmpty()) {
        current.addAll(missingNew)
        preferences[Keys.EnabledHomeSections] = current.map { it.key }.toSet()
      }
      preferences[Keys.HomeSectionsUgcMigrationV1] = true
    }
  }

  suspend fun setHomeSectionsOrder(order: List<HomeSection>) {
    context.biliDataStore.edit { preferences ->
      // 保留 order 的顺序，过滤已知分区，再补齐缺失的默认分区
      val known = order.filter { it in HomeSection.DefaultOrder }
      val missing = HomeSection.DefaultOrder.filter { it !in known }
      val normalized = known + missing
      preferences[Keys.HomeSectionsOrder] = normalized.joinToString(",") { it.key }
    }
  }

  private object Keys {
    val LowSpecMode = booleanPreferencesKey("low_spec_mode")
    val VisualPerformanceMode = stringPreferencesKey("visual_performance_mode")
    val HomeThemeVariant = stringPreferencesKey("home_theme_variant")
    val ChineseTextVariant = stringPreferencesKey("chinese_text_variant")
    val PlaybackQualityPreference = stringPreferencesKey("playback_quality_preference")
    val PlaybackCodecPreference = stringPreferencesKey("playback_codec_preference")
    val PlaybackCdnPreference = stringPreferencesKey("playback_cdn_preference")
    val SeekPreviewSpritesEnabled = booleanPreferencesKey("seek_preview_sprites_enabled")
    val AirJumpAssistantEnabled = booleanPreferencesKey("air_jump_assistant_enabled")
    val ConfirmPlaybackExit = booleanPreferencesKey("confirm_playback_exit")
    val AutoPlayNextEpisode = booleanPreferencesKey("auto_play_next_episode")
    val AutoPlayRelatedVideo = booleanPreferencesKey("auto_play_related_video")
    val AutoReturnHomeOnCompletion = booleanPreferencesKey("auto_return_home_on_completion")
    val ShowClock = booleanPreferencesKey("show_clock")
    val ShowMiniProgressBar = booleanPreferencesKey("show_mini_progress_bar")
    val PlayerLogOverlayEnabled = booleanPreferencesKey("player_log_overlay_enabled")
    val AutoConfirmOnFocus = booleanPreferencesKey("auto_confirm_on_focus")
    val AutoRefreshOnSwitch = booleanPreferencesKey("auto_refresh_on_switch")
    val LiquidGlassCardsEnabled = booleanPreferencesKey("liquid_glass_cards_enabled")
    val EnabledHomeSections = stringSetPreferencesKey("enabled_home_sections")
    val HomeSectionsOrder = stringPreferencesKey("home_sections_order")
    val HomeSectionsUgcMigrationV1 = booleanPreferencesKey("home_sections_ugc_migration_v1")
  }
}

/** 本轮对齐 BV 31 分区时新增的 UGC 分区 key（旧版不存在这些 key）。读取持久化启用集合时
 *  把新增分区默认补为启用，不影响用户此前显式禁用的旧分区。 */
private val newlyAddedHomeSectionKeys = setOf(
  "kichiku", "cinephile", "ent", "information", "shortplay", "car", "fashion", "sports",
  "animal", "vlog", "painting", "ai", "home", "outdoors", "gym", "handmake", "travel",
  "rural", "parenting", "health", "emotion", "life_joy", "life_experience", "mysticism",
)

fun supportsLiquidGlassCards(): Boolean {
  return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

private fun Context.defaultVisualPerformanceMode(): AppVisualPerformanceMode {
  val activityManager = getSystemService(ActivityManager::class.java) ?: return AppVisualPerformanceMode.Balanced
  val memoryInfo = ActivityManager.MemoryInfo()
  activityManager.getMemoryInfo(memoryInfo)
  return if (memoryInfo.totalMem > 0L && memoryInfo.totalMem < SmoothDefaultMemoryThresholdBytes) {
    AppVisualPerformanceMode.Smooth
  } else {
    AppVisualPerformanceMode.Balanced
  }
}

private const val SmoothDefaultMemoryThresholdBytes = 1024L * 1024L * 1024L
