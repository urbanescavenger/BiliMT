package com.kirin.mt.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.kirin.mt.R
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.settings.AppSettings
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.glass.biliLiquidGlassSurface
import com.kirin.mt.ui.home.titleRes
import com.kirin.mt.ui.login.createQrCodeBitmap
import com.kirin.mt.ui.theme.BiliFocus
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

@Composable
internal fun SettingsHomeSectionsColumn(
  settings: AppSettings,
  onMoveLeftToSettings: () -> Boolean,
  onHomeSectionEnabledChange: (HomeSection, Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
  ) {
    Text(
      text = stringResource(R.string.settings_home_sections_section),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.SectionTitle,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = stringResource(R.string.settings_home_sections_description),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.BodySmall,
    )
    LazyVerticalGrid(
      columns = GridCells.Fixed(BiliSizing.SettingsHomeSectionColumns),
      modifier = Modifier
        .fillMaxWidth()
        .height(BiliSizing.SettingsHomeSectionGridHeight),
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      userScrollEnabled = false,
    ) {
      itemsIndexed(HomeSection.DefaultOrder, key = { _, section -> section.key }) { index, section ->
        HomeSectionChip(
          title = stringResource(section.titleRes()),
          selected = section in settings.enabledHomeSections,
          modifier = Modifier.settingsSectionChipBoundaryKeys(
            index = index,
            columns = BiliSizing.SettingsHomeSectionColumns,
            onMoveLeftToSettings = onMoveLeftToSettings,
          ),
          onClick = {
            onHomeSectionEnabledChange(section, section !in settings.enabledHomeSections)
          },
        )
      }
    }
  }
}

@Composable
internal fun SettingsAboutColumn(
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val performancePolicy = LocalBiliPerformancePolicy.current
  val panelShape = RoundedCornerShape(BiliRadius.Panel)
  val projectUrl = SettingsAboutProjectUrl
  val qrBitmap = remember(projectUrl) {
    createQrCodeBitmap(projectUrl, SettingsAboutQrSizePx)
  }
  val libraryColumns = remember {
    SettingsAboutLibraries.chunked(
      (SettingsAboutLibraries.size + SettingsAboutLibraryColumnCount - 1) / SettingsAboutLibraryColumnCount,
    )
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .biliLiquidGlassSurface(
        enabled = performancePolicy.cinematicVisualEffectsEnabled &&
          performancePolicy.liquidGlassCardsEnabled,
        shape = panelShape,
        surfaceColor = homeColors.cardSurface,
        borderColor = homeColors.glassBorder,
        borderWidth = BiliFocus.RestingBorderWidth,
      ),
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(BiliSpacing.Lg),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
    ) {
      Text(
        text = stringResource(R.string.settings_about_title),
        color = homeColors.textSecondary,
        fontSize = BiliTypography.SectionTitle,
        fontWeight = FontWeight.Bold,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Xl),
        verticalAlignment = Alignment.Top,
      ) {
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
        ) {
          Text(
            text = stringResource(R.string.settings_about_project_name),
            color = homeColors.textPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          AboutTextBlock(
            title = stringResource(R.string.settings_about_project_intro_title),
            body = stringResource(R.string.settings_about_project_intro),
          )
        }
        Box(
          modifier = Modifier
            .width(BiliSizing.LoginQrImageSize),
          contentAlignment = Alignment.TopStart,
        ) {
          Box(
            modifier = Modifier
              .size(BiliSizing.LoginQrImageSize - BiliSpacing.Md)
              .clip(RoundedCornerShape(BiliRadius.Card))
              .background(Color.White)
              .padding(BiliSpacing.Sm),
            contentAlignment = Alignment.Center,
          ) {
            Image(
              bitmap = qrBitmap.asImageBitmap(),
              contentDescription = stringResource(R.string.settings_about_project_url_qr_content_description),
              modifier = Modifier.fillMaxSize(),
            )
          }
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Xl),
        verticalAlignment = Alignment.Top,
      ) {
        AboutTextBlock(
          title = stringResource(R.string.settings_about_project_url_title),
          body = projectUrl,
          bodyMaxLines = 2,
          modifier = Modifier.weight(1f),
        )
        AboutTextBlock(
          title = stringResource(R.string.settings_about_license_title),
          body = stringResource(R.string.settings_about_license_value),
          bodyMaxLines = 1,
          modifier = Modifier.width(BiliSizing.LoginQrImageSize),
        )
      }
      Text(
        text = stringResource(R.string.settings_about_libraries_title),
        color = homeColors.textPrimary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Xl),
      ) {
        libraryColumns.forEach { libraries ->
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
          ) {
            libraries.forEach { library ->
              AboutLibraryLine(library = library)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AboutTextBlock(
  title: String,
  body: String,
  bodyMaxLines: Int = Int.MAX_VALUE,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
  ) {
    Text(
      text = title,
      color = homeColors.textPrimary,
      fontSize = BiliTypography.Body,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = body,
      color = homeColors.textSecondary,
      fontSize = BiliTypography.BodySmall,
      maxLines = bodyMaxLines,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun AboutLibraryLine(
  library: SettingsAboutLibrary,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
  ) {
    Text(
      text = library.name,
      color = homeColors.textPrimary,
      fontSize = BiliTypography.BodySmall,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = stringResource(library.descriptionRes),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.CardMeta,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = library.url,
      color = homeColors.textTertiary,
      fontSize = BiliTypography.CardBadge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun HomeSectionChip(
  title: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val chipShape = RoundedCornerShape(BiliRadius.Pill)
  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = false,
    shape = chipShape,
    onClick = onClick,
    modifier = modifier
      .fillMaxWidth()
      .height(BiliSizing.SettingsChipHeight),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .clip(chipShape)
        .background(
          color = if (selected) {
            homeColors.accent.copy(alpha = BiliFocus.SettingsChipSelectedBackgroundAlpha)
          } else {
            Color.Transparent
          },
          shape = chipShape,
        ),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = title,
        color = if (selected) homeColors.textPrimary else homeColors.textTertiary,
        fontSize = BiliTypography.BodySmall,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      )
    }
  }
}

private fun Modifier.settingsSectionChipBoundaryKeys(
  index: Int,
  columns: Int,
  onMoveLeftToSettings: () -> Boolean,
): Modifier {
  return onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) {
      return@onPreviewKeyEvent false
    }
    when (event.key) {
      Key.DirectionUp -> index < columns
      Key.DirectionLeft -> if (index % columns == 0) onMoveLeftToSettings() else false
      else -> false
    }
  }
}

private data class SettingsAboutLibrary(
  val name: String,
  val descriptionRes: Int,
  val url: String,
)

private const val SettingsAboutProjectUrl = "https://github.com/urbanescavenger/BiliMT"
private const val SettingsAboutQrSizePx = 320
private const val SettingsAboutLibraryColumnCount = 2

private val SettingsAboutLibraries = listOf(
  SettingsAboutLibrary("OkHttp", R.string.settings_about_library_okhttp, "https://square.github.io/okhttp/"),
  SettingsAboutLibrary("Coil", R.string.settings_about_library_coil, "https://coil-kt.github.io/coil/"),
  SettingsAboutLibrary(
    "DanmakuRenderEngine",
    R.string.settings_about_library_danmaku_render_engine,
    "https://github.com/bytedance/DanmakuRenderEngine",
  ),
  SettingsAboutLibrary("OpenCC4J", R.string.settings_about_library_opencc4j, "https://github.com/houbb/opencc4j"),
  SettingsAboutLibrary(
    "AndroidLiquidGlass / Backdrop",
    R.string.settings_about_library_liquid_glass,
    "https://github.com/Kyant0/AndroidLiquidGlass",
  ),
  SettingsAboutLibrary("ZXing", R.string.settings_about_library_zxing, "https://github.com/zxing/zxing"),
)
