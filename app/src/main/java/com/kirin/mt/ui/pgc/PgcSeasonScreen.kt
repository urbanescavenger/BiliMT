package com.kirin.mt.ui.pgc

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.PgcEpisode
import com.kirin.mt.core.model.PgcSeason
import com.kirin.mt.core.model.PgcSeasonRef
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliColors
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSizing
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

// 选集弹窗每页集数（对齐 BV SeasonEpisodesDialog 的本地切片分页，BV 为 20，这里放大到 100）。
private const val EPISODES_PER_PAGE = 100

@Stable
internal class PgcSeasonUiState {
  var season by mutableStateOf<PgcSeason?>(null)
  var loading by mutableStateOf(false)
  var failed by mutableStateOf(false)
  var error by mutableStateOf<String?>(null)
}

@Composable
internal fun PgcSeasonScreen(
  videoRepository: VideoRepository,
  request: PgcSeasonRequest,
  firstItemFocusRequester: FocusRequester,
  playerLogOverlayEnabled: Boolean,
  onBack: () -> Boolean,
  onPlayEpisode: (PgcSeason, PgcEpisode) -> Unit,
) {
  val uiState = remember { PgcSeasonUiState() }
  var currentRequest by remember { mutableStateOf(request) }

  // 全屏 overlay:按 Back 关掉详情页回到带侧栏的 PGC 基页(对齐 UpSpaceScreen)。
  // PgcEpisodesDialog 是独立 Dialog window,开时 Back 由对话框消费,此处不触发。
  BackHandler { onBack() }

  LaunchedEffect(request) {
    currentRequest = request
  }

  LaunchedEffect(currentRequest) {
    if (currentRequest.seasonId <= 0) return@LaunchedEffect
    uiState.season = null
    uiState.failed = false
    uiState.error = null
    uiState.loading = true
    var caught: String? = null
    var completed = false
    val result = withTimeoutOrNull(20_000L) {
      val r = runCatching { videoRepository.getPgcSeasonInfo(currentRequest.seasonId, currentRequest.epId) }
        .onFailure {
          caught = "${it.javaClass.simpleName}: ${it.message}"
          Log.e("BiliMT:Pgc", "season fetch failed (seasonId=${currentRequest.seasonId} epId=${currentRequest.epId})", it)
        }
        .getOrNull()
      completed = true
      r
    }
    uiState.loading = false
    when {
      result != null -> uiState.season = result
      caught != null -> { uiState.failed = true; uiState.error = caught }
      !completed -> { uiState.failed = true; uiState.error = "真超时(20s)——HTTP 挂死" }
      else -> { uiState.failed = true; uiState.error = "返回空(data=null)——看日志 raw 行" }
    }
  }

  // 上次播放集：用服务端 progress.lastEpId 定位初始焦点；无记录/找不到则回退第 1 集正片。
  // 焦点由持有目标集的 PgcEpisodeRow 在滚动到该集后请求，避免目标未进入视口时盲请求失败。
  val initialFocusEpId = remember(uiState.season?.seasonId, uiState.season?.progress?.lastEpId) {
    val s = uiState.season ?: return@remember 0
    val target = s.progress?.lastEpId ?: 0
    val inMain = s.episodes.any { it.id == target }
    val inSection = s.sections.any { sec -> sec.episodes.any { it.id == target } }
    if (target != 0 && (inMain || inSection)) target
    else s.episodes.firstOrNull()?.id ?: 0
  }

  val homeColors = LocalHomeColors.current
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(top = BiliSpacing.Md),
  ) {
    when {
      uiState.season == null && uiState.loading -> Text(
        text = stringResource(R.string.pgc_loading),
        color = homeColors.textSecondary,
        modifier = Modifier.fillMaxSize().padding(BiliSpacing.Xl),
      )
      uiState.season == null && uiState.failed -> Text(
        text = stringResource(R.string.pgc_failed) + (uiState.error?.let { "\n$it" } ?: ""),
        color = homeColors.textSecondary,
        modifier = Modifier.fillMaxSize().padding(BiliSpacing.Xl),
      )
      uiState.season != null -> {
        val season = uiState.season!!
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(
            horizontal = BiliSpacing.Xl,
            vertical = BiliSpacing.Md,
          ),
          verticalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
        ) {
          item(key = "header") {
            PgcSeasonHeader(season = season)
          }
          if (season.seasons.size > 1) {
            item(key = "season-selector") {
              PgcSeasonSelector(
                seasons = season.seasons,
                currentSeasonId = season.seasonId,
                onSelect = { currentRequest = PgcSeasonRequest(seasonId = it, epId = 0) },
              )
            }
          }
          if (season.episodes.isNotEmpty()) {
            item(key = "main") {
              PgcEpisodeRow(
                title = stringResource(R.string.pgc_season_main_section),
                episodes = season.episodes,
                firstItemFocusRequester = firstItemFocusRequester,
                initialFocusEpId = initialFocusEpId,
                firstItemHandled = false,
                onMoveLeftToNav = onBack,
                onPlay = { ep -> onPlayEpisode(season, ep) },
              )
            }
          }
          season.sections.forEach { section ->
            item(key = "section-${section.id}") {
              PgcEpisodeRow(
                title = section.title,
                episodes = section.episodes,
                firstItemFocusRequester = firstItemFocusRequester,
                initialFocusEpId = initialFocusEpId,
                firstItemHandled = season.episodes.isNotEmpty(),
                onMoveLeftToNav = onBack,
                onPlay = { ep -> onPlayEpisode(season, ep) },
              )
            }
          }
        }
      }
    }
    if (playerLogOverlayEnabled) {
      PgcSeasonLogOverlay(
        loading = uiState.loading,
        failed = uiState.failed,
        error = uiState.error,
        seasonId = currentRequest.seasonId,
        epId = currentRequest.epId,
      )
    }
  }
}

@Composable
private fun BoxScope.PgcSeasonLogOverlay(
  loading: Boolean,
  failed: Boolean,
  error: String?,
  seasonId: Int,
  epId: Int,
) {
  var lines by remember { mutableStateOf<List<String>>(emptyList()) }
  LaunchedEffect(Unit) {
    while (isActive) {
      lines = LogCatcherUtil.readLiveLogTailLines(30)
      delay(1000L)
    }
  }
  val stateText = when {
    loading -> "加载中（season fetch）"
    failed -> "失败/超时（20s）"
    else -> "已加载"
  }
  Box(
    modifier = Modifier
      .align(Alignment.TopStart)
      .fillMaxWidth()
      .background(Color(0xE6333333))
      .border(width = 2.dp, color = BiliColors.BiliPink)
      .padding(BiliSpacing.Sm),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Text(
        text = "● 季详情诊断 | state=$stateText | seasonId=$seasonId epId=$epId",
        color = BiliColors.BiliPink,
        fontSize = BiliTypography.Body,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
      )
      error?.let { err ->
        Text(
          text = "ERR: $err",
          color = BiliColors.BiliPink,
          fontSize = BiliTypography.CardMeta,
          fontFamily = FontFamily.Monospace,
        )
      }
      lines.forEach { line ->
        Text(
          text = line,
          color = BiliColors.TextSecondary,
          fontSize = BiliTypography.CardMeta,
          fontFamily = FontFamily.Monospace,
        )
      }
    }
  }
}

@Composable
private fun PgcSeasonHeader(season: PgcSeason) {
  val homeColors = LocalHomeColors.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Lg),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      modifier = Modifier
        .width(160.dp)
        .aspectRatio(BiliSizing.PgcPosterAspect)
        .clip(RoundedCornerShape(BiliRadius.Card)),
    ) {
      AsyncImage(
        model = season.cover,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
    ) {
      Text(
        text = season.title,
        color = homeColors.textPrimary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (season.typeName.isNotBlank() || season.newEpDesc.isNotBlank()) {
        Text(
          text = listOf(season.typeName, season.newEpDesc)
            .filter { it.isNotBlank() }
            .joinToString(" · "),
          color = homeColors.textSecondary,
          fontSize = BiliTypography.BodySmall,
        )
      }
      Text(
        text = season.evaluate,
        color = homeColors.textSecondary,
        fontSize = BiliTypography.BodySmall,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun PgcSeasonSelector(
  seasons: List<PgcSeasonRef>,
  currentSeasonId: Int,
  onSelect: (Int) -> Unit,
) {
  val homeColors = LocalHomeColors.current
  LazyRow(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm)) {
    items(seasons, key = { it.seasonId }) { ref ->
      val selected = ref.seasonId == currentSeasonId
      BiliFocusableSurface(
        scaleOnFocus = false,
        shadowOnFocus = false,
        shape = RoundedCornerShape(BiliRadius.Pill),
        onClick = { onSelect(ref.seasonId) },
        restingBorderColor = if (selected) homeColors.accent else homeColors.glassBorder,
        focusedBorderColor = homeColors.accent,
      ) {
        Text(
          text = ref.seasonTitle.ifBlank { ref.seasonId.toString() },
          color = if (selected) homeColors.accent else homeColors.textSecondary,
          fontSize = BiliTypography.CardMeta,
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
          modifier = Modifier.padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Xs),
        )
      }
    }
  }
}

@Composable
private fun PgcEpisodeRow(
  title: String,
  episodes: List<PgcEpisode>,
  firstItemFocusRequester: FocusRequester,
  initialFocusEpId: Int,
  firstItemHandled: Boolean,
  onMoveLeftToNav: () -> Boolean,
  onPlay: (PgcEpisode) -> Unit,
) {
  val homeColors = LocalHomeColors.current
  var showEpisodesDialog by remember { mutableStateOf(false) }
  val selectButtonFocusRequester = remember { FocusRequester() }
  val listState = rememberLazyListState()
  val focusIndex = remember(episodes, initialFocusEpId) {
    episodes.indexOfFirst { it.id == initialFocusEpId }
  }
  // 持有目标集的那一行滚动到该集后再请求焦点；focusIndex<0 的行不动作，行间不冲突。
  // +1 是因为行首还有「选集」按钮占 LazyRow 的 item 0；第 0 集不滚动以保留选集按钮可见。
  LaunchedEffect(episodes, initialFocusEpId) {
    if (focusIndex >= 0) {
      if (focusIndex > 0) listState.scrollToItem(focusIndex + 1)
      runCatching { firstItemFocusRequester.requestFocus() }
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm)) {
    Text(
      text = title,
      color = homeColors.textPrimary,
      fontSize = BiliTypography.Body,
      fontWeight = FontWeight.Bold,
    )
    LazyRow(
      state = listState,
      horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
    ) {
      item(key = "select") {
        PgcSelectButton(
          isFirst = !firstItemHandled,
          focusRequester = selectButtonFocusRequester,
          onMoveLeftToNav = onMoveLeftToNav,
          onClick = { showEpisodesDialog = true },
        )
      }
      items(episodes, key = { it.id }) { ep ->
        PgcEpisodeButton(
          episode = ep,
          isFirst = ep.id == initialFocusEpId,
          firstItemFocusRequester = firstItemFocusRequester,
          // 左键交由默认焦点移动跳到前面的「选集」按钮；nav 逃逸由选集按钮承担。
          onMoveLeftToNav = { false },
          onClick = { onPlay(ep) },
        )
      }
    }
  }
  if (showEpisodesDialog) {
    PgcEpisodesDialog(
      title = title,
      episodes = episodes,
      onPlay = { ep ->
        showEpisodesDialog = false
        onPlay(ep)
      },
      onDismiss = {
        showEpisodesDialog = false
        runCatching { selectButtonFocusRequester.requestFocus() }
      },
    )
  }
}

@Composable
private fun PgcEpisodeButton(
  episode: PgcEpisode,
  isFirst: Boolean,
  firstItemFocusRequester: FocusRequester,
  onMoveLeftToNav: () -> Boolean,
  onClick: () -> Unit,
  fillMaxWidth: Boolean = false,
) {
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Card)
  val modifier = Modifier
    .let { if (fillMaxWidth) it.fillMaxWidth() else it.width(200.dp) }
    .let { if (isFirst) it.focusRequester(firstItemFocusRequester) else it }
    .onPreviewKeyEvent { event ->
      if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && isFirst) {
        onMoveLeftToNav()
      } else {
        false
      }
    }

  BiliFocusableSurface(
    scaleOnFocus = true,
    shadowOnFocus = true,
    shape = shape,
    onClick = onClick,
    modifier = modifier,
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(16f / 9f)
          .clip(shape),
      ) {
        AsyncImage(
          model = episode.cover,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
        Text(
          text = episode.title,
          color = Color.White,
          fontSize = BiliTypography.CardBadge,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(BiliSpacing.Xs),
        )
      }
      Text(
        text = episode.longTitle.ifBlank { episode.title },
        color = homeColors.textPrimary,
        fontSize = BiliTypography.CardMeta,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .fillMaxWidth()
          .padding(BiliSpacing.Sm),
      )
    }
  }
}

// 选集入口按钮：挂在每个分集行 LazyRow 的最前面，点击弹出分页选集对话框，
// 对齐 BV SeasonEpisodeRow 头部的 ViewModule 按钮。
@Composable
private fun PgcSelectButton(
  isFirst: Boolean,
  focusRequester: FocusRequester,
  onMoveLeftToNav: () -> Boolean,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Card)
  BiliFocusableSurface(
    scaleOnFocus = true,
    shadowOnFocus = true,
    shape = shape,
    onClick = onClick,
    modifier = Modifier
      .width(200.dp)
      .height(114.dp)
      .focusRequester(focusRequester)
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && isFirst) {
          onMoveLeftToNav()
        } else {
          false
        }
      },
    restingBorderColor = homeColors.glassBorder,
    focusedBorderColor = homeColors.accent,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(BiliSpacing.Sm),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Xs),
      ) {
        Text(
          text = "☰",
          color = homeColors.textPrimary,
          fontSize = BiliTypography.Body,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = stringResource(R.string.pgc_season_select),
          color = homeColors.textPrimary,
          fontSize = BiliTypography.CardMeta,
          fontWeight = FontWeight.Medium,
        )
      }
    }
  }
}

// 分页选集对话框：每页 100 集，P1-100 / P101-200 / ... 标签焦点切换，4 列封面网格。
// 对齐 BV SeasonEpisodesDialog（SeasonInfoScreen.kt:663）。
@Composable
private fun PgcEpisodesDialog(
  title: String,
  episodes: List<PgcEpisode>,
  onPlay: (PgcEpisode) -> Unit,
  onDismiss: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  val tabCount by remember { mutableIntStateOf(ceil(episodes.size / EPISODES_PER_PAGE.toDouble()).toInt()) }
  val selectedEpisodes = remember { mutableStateListOf<PgcEpisode>() }

  val tabFocusRequesters = remember(tabCount) { List(tabCount) { FocusRequester() } }
  val gridFocusRequester = remember { FocusRequester() }
  val listState = rememberLazyGridState()
  val scrollState = rememberScrollState()

  LaunchedEffect(selectedTabIndex, episodes) {
    val fromIndex = selectedTabIndex * EPISODES_PER_PAGE
    var toIndex = (selectedTabIndex + 1) * EPISODES_PER_PAGE
    if (toIndex >= episodes.size) {
      toIndex = episodes.size
    }
    if (fromIndex <= toIndex) {
      selectedEpisodes.clear()
      selectedEpisodes.addAll(episodes.subList(fromIndex, toIndex))
    }
  }

  LaunchedEffect(Unit) {
    if (tabCount > 1) {
      runCatching { tabFocusRequesters[0].requestFocus() }
    } else {
      runCatching { gridFocusRequester.requestFocus() }
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Column(
      modifier = Modifier
        .size(960.dp, 540.dp)
        .clip(RoundedCornerShape(BiliRadius.Card))
        .background(homeColors.cardSurface)
        .padding(BiliSpacing.Lg),
      verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
    ) {
      Text(
        text = title,
        color = homeColors.textPrimary,
        fontSize = BiliTypography.Body,
        fontWeight = FontWeight.Bold,
      )
      // 分页标签：焦点驱动切换（对齐 BV onFocus = { selectedTabIndex = i }）
      if (tabCount > 1) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
          horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Sm),
        ) {
          for (i in 0 until tabCount) {
            PgcPageTab(
              label = "P${i * EPISODES_PER_PAGE + 1}-${(i + 1) * EPISODES_PER_PAGE}",
              selected = i == selectedTabIndex,
              focusRequester = tabFocusRequesters[i],
              onFocused = { selectedTabIndex = i },
            )
          }
        }
      }
      LazyVerticalGrid(
        modifier = Modifier
          .fillMaxWidth()
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
              if (tabCount > 1) {
                runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
              } else {
                onDismiss()
              }
              true
            } else {
              false
            }
          },
        state = listState,
        columns = GridCells.Fixed(BiliSizing.VideoGridColumns),
        contentPadding = PaddingValues(BiliSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md),
      ) {
        itemsIndexed(
          items = selectedEpisodes,
          key = { _, ep -> ep.id },
        ) { index, ep ->
          PgcEpisodeButton(
            episode = ep,
            isFirst = index == 0,
            firstItemFocusRequester = gridFocusRequester,
            onMoveLeftToNav = {
              if (tabCount > 1) {
                runCatching { tabFocusRequesters[selectedTabIndex].requestFocus() }
              } else {
                onDismiss()
              }
              true
            },
            onClick = { onPlay(ep) },
            fillMaxWidth = true,
          )
        }
      }
    }
  }
}

@Composable
private fun PgcPageTab(
  label: String,
  selected: Boolean,
  focusRequester: FocusRequester,
  onFocused: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  BiliFocusableSurface(
    scaleOnFocus = false,
    shadowOnFocus = false,
    shape = RoundedCornerShape(BiliRadius.Pill),
    onClick = onFocused,
    onFocused = onFocused,
    restingBorderColor = if (selected) homeColors.accent else homeColors.glassBorder,
    focusedBorderColor = homeColors.accent,
    modifier = Modifier
      .widthIn(min = 96.dp)
      .focusRequester(focusRequester),
  ) {
    Text(
      text = label,
      color = if (selected) homeColors.accent else homeColors.textSecondary,
      fontSize = BiliTypography.CardMeta,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      modifier = Modifier.padding(horizontal = BiliSpacing.Md, vertical = BiliSpacing.Xs),
    )
  }
}
