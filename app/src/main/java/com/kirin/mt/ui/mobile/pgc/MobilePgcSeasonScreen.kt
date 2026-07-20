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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.animateScrollToItem
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kirin.mt.R
import com.kirin.mt.core.model.PgcEpisode
import com.kirin.mt.core.model.PgcSeason
import com.kirin.mt.core.model.formatDurationSeconds
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
internal fun MobilePgcSeasonScreen(
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
  val listState = rememberLazyListState()

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

  // 进入季详情 / 切换同系列其它季后,自动滚到上次看到的那一集(对齐 TV 端 progress.lastEpId 初始焦点)。
  // targetIndex 仅在 season 加载完成或换季时变化,LaunchedEffect 不会因手动滚动重触发。
  val targetIndex = remember(season?.seasonId, season?.progress?.lastEpId) {
    val s = season ?: return@remember -1
    lastPlayedItemIndex(s, s.progress?.lastEpId ?: 0)
  }
  LaunchedEffect(targetIndex) {
    if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
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
          state = listState,
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
              val isLast = s.progress?.lastEpId == ep.id
              PgcEpisodeRowItem(
                episode = ep,
                isLastPlayed = isLast,
                lastTime = if (isLast) s.progress?.lastTime ?: 0 else 0,
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
              val isLast = s.progress?.lastEpId == ep.id
              PgcEpisodeRowItem(
                episode = ep,
                isLastPlayed = isLast,
                lastTime = if (isLast) s.progress?.lastTime ?: 0 else 0,
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
  lastTime: Int,
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
      // 上次那集叠进度条(样式参照 TV VideoCard:track + fillMaxWidth(ratio) 填充)。
      if (isLastPlayed && episode.duration > 0 && lastTime > 0) {
        val ratio = (lastTime.toFloat() / episode.duration).coerceIn(0f, 1f)
        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.Black.copy(alpha = 0.4f)),
        ) {
          Box(
            modifier = Modifier
              .fillMaxHeight()
              .fillMaxWidth(ratio)
              .background(MaterialTheme.colorScheme.primary),
          )
        }
      }
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
          text = if (lastTime > 0) "上次看到 ${lastTime.formatDurationSeconds()}" else "上次观看",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

/**
 * 按 MobilePgcSeasonScreen 的 LazyColumn item 顺序,算上次观看集(lastEpId)的线性索引。
 * header(1) → season-selector(可选) → main-title(可选)+ 正片 → 各 section-title + 该 section 集。
 * 与上方 LazyColumn 的 item 顺序一一对应;找不到或 lastEpId==0 返回 -1(不滚动)。
 */
private fun lastPlayedItemIndex(season: PgcSeason, lastEpId: Int): Int {
  if (lastEpId == 0) return -1
  var idx = 1 // header
  if (season.seasons.size > 1) idx++ // season-selector
  if (season.episodes.isNotEmpty()) {
    idx++ // main-title
    val i = season.episodes.indexOfFirst { it.id == lastEpId }
    if (i >= 0) return idx + i
    idx += season.episodes.size
  }
  for (section in season.sections) {
    idx++ // section-title-{id}
    val i = section.episodes.indexOfFirst { it.id == lastEpId }
    if (i >= 0) return idx + i
    idx += section.episodes.size
  }
  return -1
}