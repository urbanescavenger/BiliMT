package com.kirin.mt.ui.pgc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.PgcEpisode
import com.kirin.mt.core.model.PgcSeason
import com.kirin.mt.core.model.PgcSeasonRef
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.focus.BiliFocusableSurface
import com.kirin.mt.ui.theme.BiliRadius
import com.kirin.mt.ui.theme.BiliSpacing
import com.kirin.mt.ui.theme.BiliTypography
import com.kirin.mt.ui.theme.LocalHomeColors

@Stable
internal class PgcSeasonUiState {
  var season by mutableStateOf<PgcSeason?>(null)
  var loading by mutableStateOf(false)
  var failed by mutableStateOf(false)
}

@Composable
internal fun PgcSeasonScreen(
  videoRepository: VideoRepository,
  request: PgcSeasonRequest,
  firstItemFocusRequester: FocusRequester,
  onBack: () -> Boolean,
  onPlayEpisode: (PgcSeason, PgcEpisode) -> Unit,
) {
  val uiState = remember { PgcSeasonUiState() }
  var currentRequest by remember { mutableStateOf(request) }

  LaunchedEffect(request) {
    currentRequest = request
  }

  LaunchedEffect(currentRequest) {
    if (currentRequest.seasonId <= 0) return@LaunchedEffect
    uiState.season = null
    uiState.failed = false
    uiState.loading = true
    runCatching { videoRepository.getPgcSeasonInfo(currentRequest.seasonId, currentRequest.epId) }
      .onSuccess {
        uiState.season = it
        uiState.loading = false
      }
      .onFailure {
        uiState.failed = true
        uiState.loading = false
      }
  }

  // 季详情加载完成后聚焦第一个分集
  LaunchedEffect(uiState.season?.seasonId) {
    if (uiState.season != null) {
      runCatching { firstItemFocusRequester.requestFocus() }
    }
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
        text = stringResource(R.string.pgc_failed),
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
                firstItemHandled = season.episodes.isNotEmpty(),
                onMoveLeftToNav = onBack,
                onPlay = { ep -> onPlayEpisode(season, ep) },
              )
            }
          }
        }
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
        .aspectRatio(0.7f)
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
  firstItemHandled: Boolean,
  onMoveLeftToNav: () -> Boolean,
  onPlay: (PgcEpisode) -> Unit,
) {
  val homeColors = LocalHomeColors.current
  Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.Sm)) {
    Text(
      text = title,
      color = homeColors.textPrimary,
      fontSize = BiliTypography.Body,
      fontWeight = FontWeight.Bold,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.Md)) {
      items(episodes, key = { it.id }) { ep ->
        PgcEpisodeButton(
          episode = ep,
          isFirst = !firstItemHandled && episodes.firstOrNull()?.id == ep.id,
          firstItemFocusRequester = firstItemFocusRequester,
          onMoveLeftToNav = onMoveLeftToNav,
          onClick = { onPlay(ep) },
        )
      }
    }
  }
}

@Composable
private fun PgcEpisodeButton(
  episode: PgcEpisode,
  isFirst: Boolean,
  firstItemFocusRequester: FocusRequester,
  onMoveLeftToNav: () -> Boolean,
  onClick: () -> Unit,
) {
  val homeColors = LocalHomeColors.current
  val shape = RoundedCornerShape(BiliRadius.Card)
  val modifier = Modifier
    .width(200.dp)
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
