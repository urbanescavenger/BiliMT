package com.kirin.mt.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import com.kirin.mt.R
import com.kirin.mt.core.i18n.ChineseTextVariant
import com.kirin.mt.core.player.CodecCapability
import com.kirin.mt.core.player.PlaybackCdnPreference
import com.kirin.mt.core.player.PlaybackCodecPreference
import com.kirin.mt.core.player.PlaybackQualityPreference
import com.kirin.mt.core.settings.AppVisualPerformanceMode
import com.kirin.mt.core.settings.HomeThemeVariant
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

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

/**
 * 最新版本合并行(镜像移动端 MobileUpdateVersionRow):标题+描述 + 右侧动作文案
 * (下载更新/下载中.../安装并重启),Downloading 时下方进度条。整行可点(Available→下载,
 * Downloaded→安装,其它状态确认无效但仍可聚焦,保持 D-pad 焦点连续性)。
 * 不固定高度——进度条出现时行变高。
 */
@Composable
internal fun SettingsUpdateVersionRow(
  title: String,
  description: String,
  actionLabel: String?,
  actionEnabled: Boolean,
  progress: Float?,
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
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = BiliSpacing.Lg, end = BiliSpacing.Xl, top = BiliSpacing.Md, bottom = BiliSpacing.Md),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            color = homeColors.textPrimary,
            fontSize = BiliTypography.Body,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          if (description.isNotBlank()) {
            Text(
              text = description,
              color = homeColors.textSecondary,
              fontSize = BiliTypography.BodySmall,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = BiliSpacing.Xs),
            )
          }
        }
        if (actionLabel != null) {
          Text(
            text = actionLabel,
            color = if (actionEnabled) homeColors.accent else homeColors.textTertiary,
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
      if (progress != null) {
        Spacer(Modifier.height(BiliSpacing.Xs))
        LinearProgressIndicator(
          progress = { progress },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
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
