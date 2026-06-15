package com.kirin.bilitv.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kirin.bilitv.R
import com.kirin.bilitv.core.i18n.ChineseTextVariant
import com.kirin.bilitv.core.player.CodecCapability
import com.kirin.bilitv.core.player.PlaybackCdnPreference
import com.kirin.bilitv.core.player.PlaybackCodecPreference
import com.kirin.bilitv.core.player.PlaybackQualityPreference
import com.kirin.bilitv.core.settings.AppVisualPerformanceMode
import com.kirin.bilitv.core.settings.HomeThemeVariant
import com.kirin.bilitv.ui.focus.BiliFocusableSurface
import com.kirin.bilitv.ui.theme.BiliRadius
import com.kirin.bilitv.ui.theme.BiliSizing
import com.kirin.bilitv.ui.theme.BiliSpacing
import com.kirin.bilitv.ui.theme.BiliTypography
import com.kirin.bilitv.ui.theme.LocalHomeColors

@Composable
internal fun SettingsOptionRow(
  title: String,
  description: String,
  value: String,
  modifier: Modifier = Modifier,
  onFocused: () -> Unit = {},
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Panel),
    onClick = onClick,
    onFocused = onFocused,
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.SettingsRowHeight),
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(start = BiliSpacing.Lg, end = BiliSpacing.Xl),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = title,
          color = homeColors.textPrimary,
          fontSize = BiliTypography.Body,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = description,
          color = homeColors.textSecondary,
          fontSize = BiliTypography.BodySmall,
          modifier = Modifier.padding(top = BiliSpacing.Xs),
        )
      }
      Text(
        text = value,
        color = homeColors.accent,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = Modifier
          .padding(start = BiliSpacing.Lg)
          .width(BiliSizing.SettingsCodecValueWidth),
      )
    }
  }
}

@Composable
internal fun SettingsActionRow(
  title: String,
  description: String,
  value: String,
  modifier: Modifier = Modifier,
  onFocused: () -> Unit = {},
  onClick: () -> Unit,
) {
  SettingsOptionRow(
    title = title,
    description = description,
    value = value,
    modifier = modifier,
    onFocused = onFocused,
    onClick = onClick,
  )
}

@Composable
internal fun SettingsToggleRow(
  title: String,
  description: String,
  checked: Boolean,
  enabled: Boolean = true,
  modifier: Modifier = Modifier,
  onFocused: () -> Unit = {},
  onCheckedChange: (Boolean) -> Unit,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Panel),
    onClick = {
      if (enabled) {
        onCheckedChange(!checked)
      }
    },
    onFocused = onFocused,
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.SettingsRowHeight),
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = BiliSpacing.Lg),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = title,
          color = if (enabled) homeColors.textPrimary else homeColors.textTertiary,
          fontSize = BiliTypography.Body,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = description,
          color = if (enabled) homeColors.textSecondary else homeColors.textTertiary,
          fontSize = BiliTypography.BodySmall,
          modifier = Modifier.padding(top = BiliSpacing.Xs),
        )
      }
      Box(
        modifier = Modifier.padding(start = BiliSpacing.Lg),
      ) {
        Switch(
          checked = checked,
          enabled = enabled,
          onCheckedChange = {
            if (enabled) {
              onCheckedChange(it)
            }
          },
          colors = SwitchDefaults.colors(
            checkedTrackColor = homeColors.accent,
            checkedThumbColor = homeColors.textPrimary,
            checkedBorderColor = homeColors.accent,
            uncheckedTrackColor = homeColors.glassSurfaceStrong,
            uncheckedThumbColor = homeColors.textSecondary,
            uncheckedBorderColor = homeColors.glassBorder,
            disabledCheckedTrackColor = homeColors.glassSurfaceStrong,
            disabledCheckedThumbColor = homeColors.textTertiary,
            disabledCheckedBorderColor = homeColors.glassBorder,
            disabledUncheckedTrackColor = homeColors.cardSurface,
            disabledUncheckedThumbColor = homeColors.textTertiary,
            disabledUncheckedBorderColor = homeColors.glassBorder,
          ),
          modifier = Modifier.focusProperties {
            canFocus = false
          },
        )
      }
    }
  }
}

internal fun CodecCapability.playbackCodecOptions(): List<PlaybackCodecPreference> {
  return buildList {
    add(PlaybackCodecPreference.Auto)
    if (supportsAv1) add(PlaybackCodecPreference.Av1)
    if (supportsH265) add(PlaybackCodecPreference.H265)
    if (supportsH264) add(PlaybackCodecPreference.H264)
  }
}

@Composable
internal fun ChineseTextVariant.languageLabel(): String {
  return when (this) {
    ChineseTextVariant.Simplified -> stringResource(R.string.settings_language_simplified)
    ChineseTextVariant.HongKong -> stringResource(R.string.settings_language_hong_kong)
    ChineseTextVariant.Taiwan -> stringResource(R.string.settings_language_taiwan)
  }
}

@Composable
internal fun AppVisualPerformanceMode.visualPerformanceLabel(): String {
  return when (this) {
    AppVisualPerformanceMode.Smooth -> stringResource(R.string.settings_visual_performance_smooth)
    AppVisualPerformanceMode.Balanced -> stringResource(R.string.settings_visual_performance_balanced)
    AppVisualPerformanceMode.Refined -> stringResource(R.string.settings_visual_performance_refined)
  }
}

@Composable
internal fun HomeThemeVariant.homeThemeLabel(): String {
  return when (this) {
    HomeThemeVariant.Pink -> stringResource(R.string.settings_home_theme_pink)
    HomeThemeVariant.Black -> stringResource(R.string.settings_home_theme_black)
    HomeThemeVariant.Gray -> stringResource(R.string.settings_home_theme_gray)
    HomeThemeVariant.BlueGray -> stringResource(R.string.settings_home_theme_blue_gray)
  }
}

@Composable
internal fun PlaybackQualityPreference.qualityLabel(): String {
  return when (this) {
    PlaybackQualityPreference.Highest -> stringResource(R.string.settings_playback_quality_highest)
    PlaybackQualityPreference.Q1080 -> stringResource(R.string.settings_playback_quality_1080)
    PlaybackQualityPreference.Q720 -> stringResource(R.string.settings_playback_quality_720)
    PlaybackQualityPreference.Q480 -> stringResource(R.string.settings_playback_quality_480)
  }
}

@Composable
internal fun PlaybackCodecPreference.codecLabel(): String {
  return when (this) {
    PlaybackCodecPreference.Auto -> stringResource(R.string.settings_playback_codec_auto)
    PlaybackCodecPreference.H264 -> stringResource(R.string.settings_playback_codec_h264)
    PlaybackCodecPreference.H265 -> stringResource(R.string.settings_playback_codec_h265)
    PlaybackCodecPreference.Av1 -> stringResource(R.string.settings_playback_codec_av1)
  }
}

@Composable
internal fun PlaybackCdnPreference.cdnLabel(): String {
  return when (this) {
    PlaybackCdnPreference.Auto -> stringResource(R.string.cdn_option_auto)
    PlaybackCdnPreference.Official -> stringResource(R.string.cdn_option_official)
    PlaybackCdnPreference.Aliyun -> stringResource(R.string.cdn_option_aliyun)
    PlaybackCdnPreference.Akamai -> stringResource(R.string.cdn_option_akamai)
    PlaybackCdnPreference.Hw -> stringResource(R.string.cdn_option_hw)
  }
}
