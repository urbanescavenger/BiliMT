package com.kirin.mt.ui.mobile.pgc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.PgcEpisode
import com.kirin.mt.core.model.PgcSeason
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.ui.pgc.PgcSeasonRequest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 移动端 PGC 季详情外壳(触屏):封面/简介 + 同系列其它季切换 + 正片与花絮分集列表,
 * 选集后回调 onPlayEpisode(season, ep)。数据复用 VideoRepository.getPgcSeasonInfo,
 * 选集 -> PGC PlaybackRequest 的构造由调用方(MobileApp)照 AppShell 范式完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobilePgcSeasonScreen(
  videoRepository: VideoRepository,
  request: PgcSeasonRequest,
  onPlayEpisode: (PgcSeason, PgcEpisode) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var currentRequest by remember { mutableStateOf(request) }
  var season by remember { mutableStateOf<PgcSeason?>(null) }
  var loading by remember { mutableStateOf(true) }
  var failed by remember { mutableStateOf(false) }

  LaunchedEffect(request) { currentRequest = request }

  LaunchedEffect(currentRequest) {
    if (currentRequest.seasonId <= 0) return@LaunchedEffect
    season = null
    failed = false
    loading = true
    val result = withTimeoutOrNull(20_000L) {
      runCatching { videoRepository.getPgcSeasonInfo(currentRequest.seasonId, currentRequest.epId) }
        .getOrNull()
    }
    loading = false
    when {
      result != null -> season = result
      else -> failed = true
    }
  }

  BackHandler { onBack() }

  Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // 顶栏:返回 + 季标题。
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.mobile_back),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
          .clickable { onBack() }
          .padding(horizontal = 8.dp, vertical = 6.dp),
      )
      Text(
        text = season?.title ?: "",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(end = 16.dp),
      )
    }

    when {
      season == null && loading -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) { CircularProgressIndicator() }
      season == null && failed -> Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.pgc_failed),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      season != null -> {
        val s = season!!
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item(key = "header") { PgcSeasonHeader(season = s) }
          if (s.seasons.size > 1) {
            item(key = "season-selector") {
              LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.seasons, key = { it.seasonId }) { ref ->
                  FilterChip(
                    selected = ref.seasonId == s.seasonId,
                    onClick = { currentRequest = PgcSeasonRequest(seasonId = ref.seasonId, epId = 0) },
                    label = { Text(ref.seasonTitle.ifBlank { ref.seasonId.toString() }) },
                    colors = FilterChipDefaults.filterChipColors(),
                  )
                }
              }
            }
          }
          if (s.episodes.isNotEmpty()) {
            item(key = "main-title") {
              Text(
                text = stringResource(R.string.pgc_season_main_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
              )
            }
            items(s.episodes, key = { it.id }) { ep ->
              PgcEpisodeRowItem(
                episode = ep,
                isLastPlayed = s.progress?.lastEpId == ep.id,
                onPlay = { onPlayEpisode(s, ep) },
              )
            }
          }
          s.sections.forEach { section ->
            item(key = "section-title-${section.id}") {
              Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
              )
            }
            items(section.episodes, key = { "${section.id}-${it.id}" }) { ep ->
              PgcEpisodeRowItem(
                episode = ep,
                isLastPlayed = s.progress?.lastEpId == ep.id,
                onPlay = { onPlayEpisode(s, ep) },
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
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      modifier = Modifier
        .width(120.dp)
        .aspectRatio(0.7f)
        .clip(RoundedCornerShape(8.dp)),
    ) {
      AsyncImage(
        model = season.cover,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
    Column(
      modifier = Modifier.padding(top = 2.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = season.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (season.evaluate.isNotBlank()) {
        Text(
          text = season.evaluate,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 4,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun PgcEpisodeRowItem(
  episode: PgcEpisode,
  isLastPlayed: Boolean,
  onPlay: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onPlay() }
      .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .width(120.dp)
        .aspectRatio(16f / 10f)
        .clip(RoundedCornerShape(6.dp)),
    ) {
      AsyncImage(
        model = episode.cover,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
    Column(modifier = Modifier.padding(end = 8.dp)) {
      Text(
        text = episode.longTitle.ifBlank { episode.title },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (isLastPlayed) FontWeight.Bold else FontWeight.Normal,
        color = if (isLastPlayed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (isLastPlayed) {
        Text(
          text = "上次观看",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}