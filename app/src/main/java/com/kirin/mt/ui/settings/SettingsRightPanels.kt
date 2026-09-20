package com.kirin.mt.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.kirin.mt.R
import com.kirin.mt.core.model.HomeSection
import com.kirin.mt.core.player.CodecCapability
import com.kirin.mt.core.player.DefaultPlaybackSpeed
import com.kirin.mt.core.player.YoutubeCodecPreference
import com.kirin.mt.core.player.YoutubeDefaultQuality
import com.kirin.mt.core.player.YoutubeDeliveryPriority
import com.kirin.mt.core.player.YoutubeStartQuality
import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeContentRegion
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsHomeSectionsColumn(
  settings: AppSettings,
  onMoveLeftToSettings: () -> Boolean,
  onHomeSectionEnabledChange: (HomeSection, Boolean) -> Unit,
  onHomeSectionsOrderChange: (List<HomeSection>) -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val order = settings.homeSectionsOrder
  val listState = rememberLazyListState()
  val lastIndex = order.lastIndex
  val coroutineScope = rememberCoroutineScope()
  val density = LocalDensity.current
  val rowFallbackHeightPx = with(density) {
    (BiliSizing.SettingsChipHeight + BiliSpacing.Sm).roundToPx()
  }
  val rowScrollInsetPx = with(density) {
    BiliSpacing.Sm.roundToPx()
  }
  // 按 HomeSection 枚举身份建 requester,reorder 后仍稳定(不随 index 变)。
  val rowFocusRequesters = remember {
    HomeSection.entries.associateWith { FocusRequester() }
  }
  var focusRowJob by remember { mutableStateOf<Job?>(null) }

  fun moveRowFocus(index: Int, direction: Int): Boolean {
    val targetIndex = (index + direction).coerceIn(0, lastIndex)
    focusRowJob?.cancel()
    focusRowJob = coroutineScope.launch {
      listState.scrollItemIntoComfortableView(
        index = targetIndex,
        direction = direction,
        fallbackItemHeightPx = rowFallbackHeightPx,
        edgeInsetPx = rowScrollInsetPx,
      )
      withFrameNanos { }
      rowFocusRequesters[order[targetIndex]]?.requestFocus()
    }
    return true
  }

  fun refocusAfterMove(newIndex: Int, section: HomeSection, direction: Int) {
    focusRowJob?.cancel()
    focusRowJob = coroutineScope.launch {
      withFrameNanos { } // 等 onHomeSectionsOrderChange 触发的重组
      listState.scrollItemIntoComfortableView(
        index = newIndex,
        direction = direction,
        fallbackItemHeightPx = rowFallbackHeightPx,
        edgeInsetPx = rowScrollInsetPx,
      )
      withFrameNanos { }
      rowFocusRequesters[section]?.requestFocus()
    }
  }

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
    CompositionLocalProvider(LocalBringIntoViewSpec provides SettingsBringIntoViewSpec) {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
      ) {
        itemsIndexed(order, key = { _, section -> section.key }) { index, section ->
          HomeSectionOrderRow(
            section = section,
            selected = section in settings.enabledHomeSections,
            canMoveUp = index > 0,
            canMoveDown = index < lastIndex,
            rowFocusRequester = rowFocusRequesters.getValue(section),
            onNavigateRow = { direction -> moveRowFocus(index, direction) },
            onMoveLeftToSettings = onMoveLeftToSettings,
            onToggle = {
              onHomeSectionEnabledChange(section, section !in settings.enabledHomeSections)
            },
            onMoveUp = {
              onHomeSectionsOrderChange(HomeSection.swapped(order, index, index - 1))
              refocusAfterMove(index - 1, section, -1)
            },
            onMoveDown = {
              onHomeSectionsOrderChange(HomeSection.swapped(order, index, index + 1))
              refocusAfterMove(index + 1, section, 1)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun HomeSectionOrderRow(
  section: HomeSection,
  selected: Boolean,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  rowFocusRequester: FocusRequester,
  onNavigateRow: (Int) -> Boolean,
  onMoveLeftToSettings: () -> Boolean,
  onToggle: () -> Unit,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .onPreviewKeyEvent { event ->
        // 捕获相:Row 先于子节点。只拦 Up/Down 做纵向导航,其余放行
        // (chip 的 Left 逃逸、▲/▼ 的 Left/Right 默认遍历不受影响)。
        if (event.type == KeyEventType.KeyDown) {
          when (event.key) {
            Key.DirectionUp -> onNavigateRow(-1)
            Key.DirectionDown -> onNavigateRow(1)
            else -> false
          }
        } else {
          false
        }
      },
    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    HomeSectionChip(
      title = stringResource(section.titleRes()),
      selected = selected,
      modifier = Modifier
        .weight(1f)
        .focusRequester(rowFocusRequester)
        .onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
            onMoveLeftToSettings()
          } else {
            false
          }
        },
      onClick = onToggle,
    )
    HomeSectionMoveButton(
      text = "▲",
      contentDescription = stringResource(R.string.settings_home_sections_move_up),
      enabled = canMoveUp,
      onClick = onMoveUp,
    )
    HomeSectionMoveButton(
      text = "▼",
      contentDescription = stringResource(R.string.settings_home_sections_move_down),
      enabled = canMoveDown,
      onClick = onMoveDown,
    )
  }
}

@Composable
private fun HomeSectionMoveButton(
  text: String,
  contentDescription: String,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val size = BiliSizing.SettingsMoveButtonSize
  val shape = RoundedCornerShape(BiliRadius.Pill)
  if (!enabled) {
    Box(
      modifier = modifier.size(size),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = text,
        color = homeColors.textTertiary.copy(alpha = BiliFocus.SettingsChipDisabledAlpha),
        fontSize = BiliTypography.Body,
      )
    }
  } else {
    BiliFocusableSurface(
      scaleOnFocus = false,
      shadowOnFocus = false,
      shape = shape,
      onClick = onClick,
      modifier = modifier
        .size(size)
        .semantics { this.contentDescription = contentDescription },
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .clip(shape),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = text,
          color = homeColors.textSecondary,
          fontSize = BiliTypography.Body,
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

internal data class SettingsAboutLibrary(
  val name: String,
  val descriptionRes: Int,
  val url: String,
)

// internal:移动端 MobileAboutSection 复用同一份项目地址/依赖库清单(2026-08-30)。
internal const val SettingsAboutProjectUrl = "https://github.com/urbanescavenger/BiliMT"
private const val SettingsAboutQrSizePx = 320
private const val SettingsAboutLibraryColumnCount = 2

internal val SettingsAboutLibraries = listOf(
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

/**
 * P11-148:「YouTube 设置」二级面板(取代 P11-131 的可折叠分组)。
 *
 * 为什么换形态:折叠组把 10 行塞在主列表中段,组头又常压在视口边缘,D-pad 在组两端一次跨 9 行
 * ——正是「目标行还在屏外、LazyColumn 没组合它 ⇒ 抢焦点落空 ⇒ 焦点树空、D-pad 全哑」的高发场景,
 * 用户实测「走不到」(列表深处的 WebDAV 备份行按不到)。改成本面板后:①主列表少 9 行,到深处行的
 * 跳转距离整体缩短;②面板是**独立焦点岛**,行间移动只在面板内滚,不再跨屏。
 *
 * 与「首页分区 / 日志 / 关于 / 频道管理」四个面板完全同一套机制:面板里的行**不调 onSettingFocused**
 * (那是主列表一级项的语义,调了会被判成「焦点移到非归属项」而把面板收起),Left 交给
 * [onMoveLeftToSettings] 回主列表入口行;进面板靠入口行按 Right 的默认遍历。
 *
 * 行顺序的唯一真源是 [SettingsYoutubePanelItems](也是常量守卫的核对依据,漏加即当场报错)。
 */
@Composable
internal fun SettingsYoutubeSettingsColumn(
  settings: AppSettings,
  codecCapability: CodecCapability,
  channels: List<YoutubeChannel>,
  onDefaultQualityChange: (YoutubeDefaultQuality) -> Unit,
  onStartQualityChange: (YoutubeStartQuality) -> Unit,
  onDefaultSpeedChange: (DefaultPlaybackSpeed) -> Unit,
  onCodecChange: (YoutubeCodecPreference) -> Unit,
  onContentRegionChange: (YoutubeContentRegion) -> Unit,
  onChannelsSelected: () -> Unit,
  onPipedSelected: () -> Unit,
  onUsePipedChange: (Boolean) -> Unit,
  onDeliveryPriorityChange: (YoutubeDeliveryPriority) -> Unit,
  onMoveLeftToSettings: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  val homeColors = LocalHomeColors.current
  val listState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val density = LocalDensity.current
  val rowFallbackHeightPx = with(density) {
    (BiliSizing.SettingsRowHeight + BiliSpacing.Md).roundToPx()
  }
  val rowScrollInsetPx = with(density) {
    BiliSpacing.Md.roundToPx()
  }
  // 按项 id 建 requester(与主列表同一套 id 空间,便于对照日志),行序变化也不失效。
  val rowFocusRequesters = remember {
    SettingsYoutubePanelItems.associateWith { FocusRequester() }
  }
  var focusRowJob by remember { mutableStateOf<Job?>(null) }

  fun moveRowFocus(itemIndex: Int, direction: Int): Boolean {
    val targetIndex = (SettingsYoutubePanelItems.indexOf(itemIndex) + direction)
      .coerceIn(0, SettingsYoutubePanelItems.lastIndex)
    val targetItem = SettingsYoutubePanelItems[targetIndex]
    focusRowJob?.cancel()
    focusRowJob = coroutineScope.launch {
      // 与主列表同一套防御:滚 → 等目标行进入布局 → 有界重试抢焦点(单发 requestFocus 会落空)。
      listState.focusItemWithLayoutWait(
        index = targetIndex,
        direction = direction,
        requester = rowFocusRequesters[targetItem],
        fallbackItemHeightPx = rowFallbackHeightPx,
        edgeInsetPx = rowScrollInsetPx,
        label = "youtube-panel item=$targetItem",
      )
    }
    return true
  }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
  ) {
    Text(
      text = stringResource(R.string.settings_youtube_group_title),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.SectionTitle,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = stringResource(R.string.settings_youtube_group_desc),
      color = homeColors.textSecondary,
      fontSize = BiliTypography.BodySmall,
    )
    CompositionLocalProvider(LocalBringIntoViewSpec provides SettingsBringIntoViewSpec) {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      ) {
        item(key = "panel-youtube-default-quality") {
          val qualityOptions = remember { YoutubeDefaultQuality.entries.toList() }
          val effectiveQuality = settings.youtubeDefaultQuality
          SettingsOptionRow(
            title = stringResource(R.string.settings_youtube_default_quality_title),
            description = stringResource(R.string.settings_youtube_default_quality_description),
            value = effectiveQuality.label,
            modifier = Modifier
              .focusRequester(rowFocusRequesters.getValue(SettingsItemYoutubeDefaultQuality))
              .settingsPanelKeys(
                itemIndex = SettingsItemYoutubeDefaultQuality,
                onMoveRowFocus = { index, dir -> moveRowFocus(index, dir) },
                onMoveLeftToSettings = onMoveLeftToSettings,
              ),
            onClick = {
              val currentIndex = qualityOptions.indexOf(effectiveQuality).takeIf { it >= 0 } ?: 0
              onDefaultQualityChange(qualityOptions[(currentIndex + 1) % qualityOptions.size])
            },
          )
        }
        item(key = "panel-youtube-start-quality") {
          val startQualityOptions = remember { YoutubeStartQuality.entries.toList() }
          val effectiveStartQuality = settings.youtubeStartQuality
          SettingsOptionRow(
            title = stringResource(R.string.settings_youtube_start_quality_title),
            description = stringResource(R.string.settings_youtube_start_quality_description),
            value = effectiveStartQuality.label,
            modifier = Modifier
              .focusRequester(rowFocusRequesters.getValue(SettingsItemYoutubeStartQuality))
              .settingsPanelKeys(
                itemIndex = SettingsItemYoutubeStartQuality,
                onMoveRowFocus = { index, dir -> moveRowFocus(index, dir) },
                onMoveLeftToSettings = onMoveLeftToSettings,
              ),
            onClick = {
              val currentIndex = startQualityOptions.indexOf(effectiveStartQuality).takeIf { it >= 0 } ?: 0
              onStartQualityChange(startQualityOptions[(currentIndex + 1) % startQualityOptions.size])
            },
          )
        }
        item(key = "panel-youtube-default-speed") {
          val speedOptions = remember { DefaultPlaybackSpeed.entries.toList() }
          val effectiveSpeed = settings.youtubeDefaultSpeed
          SettingsOptionRow(
            title = stringResource(R.string.settings_youtube_default_speed_title),
            description = stringResource(R.string.settings_youtube_default_speed_description),
            value = effectiveSpeed.label,
            modifier = Modifier
              .focusRequester(rowFocusRequesters.getValue(SettingsItemYoutubeDefaultSpeed))
              .settingsPanelKeys(
                itemIndex = SettingsItemYoutubeDefaultSpeed,
                onMoveRowFocus = { index, dir -> moveRowFocus(index, dir) },
                onMoveLeftToSettings = onMoveLeftToSettings,
              ),
            onClick = {
              val currentIndex = speedOptions.indexOf(effectiveSpeed).takeIf { it >= 0 } ?: 0
              onDefaultSpeedChange(speedOptions[(currentIndex + 1) % speedOptions.size])
            },
          )
        }
        item(key = "panel-youtube-codec") {
          // P11-133:YouTube 自己的值域(Auto/VP9/AV1/H.264/H.265)。设备解不了的族不进列表;
          // VP9 恒在(isSupportedBy 对它恒真)。
          val codecOptions = remember(codecCapability) {
            YoutubeCodecPreference.entries.filter { it.isSupportedBy(codecCapability) }
          }
          val configuredPreference = settings.youtubePlaybackCodecPreference.takeIf { preference ->
            preference in codecOptions
          } ?: YoutubeCodecPreference.Auto
          // 与 B站 那行同样受低配档强制显示 H264,否则行里显示用户选的值而播放强制 H264(「设置没用」)。
          val effectivePreference = if (settings.lowSpecMode) {
            YoutubeCodecPreference.H264
          } else {
            configuredPreference
          }
          SettingsOptionRow(
            title = stringResource(R.string.settings_youtube_codec_title),
            description = stringResource(R.string.settings_youtube_codec_description),
            value = effectivePreference.youtubeCodecLabel(),
            modifier = Modifier
              .focusRequester(rowFocusRequesters.getValue(SettingsItemYoutubeCodec))
              .settingsPanelKeys(
                itemIndex = SettingsItemYoutubeCodec,
                onMoveRowFocus = { index, dir -> moveRowFocus(index, dir) },
                onMoveLeftToSettings = onMoveLeftToSettings,
              ),
            onClick = {
              val currentIndex = codecOptions.indexOf(configuredPreference).takeIf { it >= 0 } ?: 0
              onCodecChange(codecOptions[(currentIndex + 1) % codecOptions.size])
            },
          )
        }
        item(key = "panel-youtube-channels") {
          SettingsActionRow(
            title = stringResource(R.string.settings_youtube_channels),
            description = stringResource(R.string.settings_youtube_channels_desc),
            value = "${channels.size}",
            modifier = Modifier
              .focusRequester(rowFocusRequesters.getValue(SettingsItemYoutubeChannels))
              .settingsPanelKeys(
                itemIndex = SettingsItemYoutubeChannels,
                onMoveRowFocus = { index, dir -> moveRowFocus(index, dir) },
                onMoveLeftToSettings = onMoveLeftToSettings,
              ),
            onClick = onChannelsSelected,
          )
        }
        item(key = "panel-youtube-content-region") {
          val regionOptions = remember { YoutubeContentRegion.entries.toList() }
          val effectiveRegion = settings.youtubeContentRegion
          SettingsOptionRow(
            title = stringResource(R.string.settings_youtube_content_region_title),
            description = stringResource(R.string.settings_youtube_content_region_description),
            value = effectiveRegion.label,
            modifier = Modifier
              .focusRequester(rowFocusRequesters.getValue(SettingsItemYoutubeContentRegion))
              .settingsPanelKeys(
                itemIndex = SettingsItemYoutubeContentRegion,
                onMoveRowFocus = { index, dir -> moveRowFocus(index, dir) },
                onMoveLeftToSettings = onMoveLeftToSettings,
              ),
            onClick = {
              val currentIndex = regionOptions.indexOf(effectiveRegion).takeIf { it >= 0 } ?: 0
              onContentRegionChange(regionOptions[(currentIndex + 1) % regionOptions.size])
            },
          )
        }
        // NOTE: sabrForceSessionVideoItag("锁定会话视频轨")诊断开关已隐藏(alpha.83 使命完成,证伪
        // itag 是 RELOAD 根因)。字段/逻辑保留,如需再作诊断可恢复此 item。
        item(key = "panel-youtube-piped") {
          SettingsActionRow(
            title = stringResource(R.string.settings_piped_title),
            description = stringResource(R.string.settings_piped_description),
            value = settings.pipedInstanceUrl.ifBlank {
              stringResource(R.string.settings_piped_default_hint)
            },
            modifier = Modifier
              .focusRequester(rowFocusRequesters.getValue(SettingsItemPiped))
              .settingsPanelKeys(
                itemIndex = SettingsItemPiped,
                onMoveRowFocus = { index, dir -> moveRowFocus(index, dir) },
                onMoveLeftToSettings = onMoveLeftToSettings,
              ),
            onClick = onPipedSelected,
          )
        }
        item(key = "panel-youtube-use-piped") {
          SettingsToggleRow(
            title = stringResource(R.string.settings_youtube_use_piped_title),
            description = stringResource(R.string.settings_youtube_use_piped_description),
            checked = settings.youtubeUsePiped,
            modifier = Modifier
              .focusRequester(rowFocusRequesters.getValue(SettingsItemYoutubeUsePiped))
              .settingsPanelKeys(
                itemIndex = SettingsItemYoutubeUsePiped,
                onMoveRowFocus = { index, dir -> moveRowFocus(index, dir) },
                onMoveLeftToSettings = onMoveLeftToSettings,
              ),
            onCheckedChange = onUsePipedChange,
          )
        }
        item(key = "panel-youtube-delivery-priority") {
          val priorityOptions = remember { YoutubeDeliveryPriority.entries.toList() }
          val effectivePriority = settings.youtubeDeliveryPriority
          SettingsOptionRow(
            title = stringResource(R.string.settings_youtube_delivery_priority_title),
            description = stringResource(R.string.settings_youtube_delivery_priority_description),
            value = effectivePriority.label,
            modifier = Modifier
              .focusRequester(rowFocusRequesters.getValue(SettingsItemYoutubeDeliveryPriority))
              .settingsPanelKeys(
                itemIndex = SettingsItemYoutubeDeliveryPriority,
                onMoveRowFocus = { index, dir -> moveRowFocus(index, dir) },
                onMoveLeftToSettings = onMoveLeftToSettings,
              ),
            onClick = {
              val currentIndex = priorityOptions.indexOf(effectivePriority).takeIf { it >= 0 } ?: 0
              onDeliveryPriorityChange(priorityOptions[(currentIndex + 1) % priorityOptions.size])
            },
          )
        }
      }
    }
  }
}

/**
 * 二级面板行的按键:Up/Down 在面板内移动(到两端停住,不环绕也不逃到主列表),Left 回一级列表入口行。
 *
 * 与 [settingsBoundaryKeys] 的两点区别都来自「面板是独立焦点岛」:①纵向不环绕(面板行少,环绕只会
 * 平白多滚一屏);②Left 的语义是「回一级列表」,不是「回侧栏」。
 */
private fun Modifier.settingsPanelKeys(
  itemIndex: Int,
  onMoveRowFocus: (Int, Int) -> Boolean,
  onMoveLeftToSettings: () -> Boolean,
): Modifier = onPreviewKeyEvent { event ->
  if (event.type != KeyEventType.KeyDown) {
    return@onPreviewKeyEvent false
  }
  when (event.key) {
    Key.DirectionUp -> onMoveRowFocus(itemIndex, -1)
    Key.DirectionDown -> onMoveRowFocus(itemIndex, 1)
    Key.DirectionLeft -> onMoveLeftToSettings()
    else -> false
  }
}
