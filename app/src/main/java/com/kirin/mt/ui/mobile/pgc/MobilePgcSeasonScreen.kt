package com.kirin.mt.ui.mobile.pgc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
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
import com.kirin.mt.ui.i18n.currentUiLocale
import com.kirin.mt.ui.i18n.formatCompactCount
import com.kirin.mt.ui.pgc.PgcSeasonRequest
import kotlinx.coroutines.withTimeoutOrNull

/** 话数分组阈值/组大小:超过 50 集按 50 集一组出快捷跳转条(对齐官方选集面板)。 */
private const val EpisodeGroupSize = 50

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
        val episodeRows = s.episodes.chunked(2)
        val grouped = s.episodes.size > EpisodeGroupSize
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
            // 选集标题行:右侧显更新状态(连载中,更新至…,对齐官方选集面板状态行)。
            item(key = "main-title") {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = stringResource(R.string.pgc_season_main_section),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                )
                if (s.newEpDesc.isNotBlank()) {
                  Spacer(Modifier.weight(1f))
                  Text(
                    text = s.newEpDesc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                  )
                }
              }
            }
            // 长剧集话数分组快捷跳(>50 集,50/组,对齐官方 1051-1100 式分桶)。
            if (grouped) {
              item(key = "ep-group-bar") {
                val lastEpId = s.progress?.lastEpId ?: 0
                val lastOrdinal = s.episodes.indexOfFirst { it.id == lastEpId }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  items((s.episodes.indices step EpisodeGroupSize).toList(), key = { it }) { start ->
                    val end = (start + EpisodeGroupSize).coerceAtMost(s.episodes.size)
                    val active = lastOrdinal in start until end
                    FilterChip(
                      selected = active,
                      onClick = {
                        val row = start / 2
                        val base = episodeRowsBaseIndex(s)
                        // base + 行号(组首集所在行),animateScrollToItem 保证可见。
                        listState.animateScrollToItem(base + row)
                      },
                      label = { Text(episodeGroupLabel(s, start, end)) },
                      colors = FilterChipDefaults.filterChipColors(),
                    )
                  }
                }
              }
            }
            episodeRows.forEachIndexed { rowIdx, rowEps ->
              item(key = "ep-row-${rowEps.first().id}") {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  rowEps.forEach { ep ->
                    val isLast = s.progress?.lastEpId == ep.id
                    PgcEpisodeGridCard(
                      episode = ep,
                      isLastPlayed = isLast,
                      lastTime = if (isLast) s.progress?.lastTime ?: 0 else 0,
                      onPlay = { onPlayEpisode(s, ep) },
                      modifier = Modifier.weight(1f),
                    )
                  }
                  if (rowEps.size == 1) {
                    Spacer(Modifier.weight(1f))
                  }
                }
              }
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
            section.episodes.chunked(2).forEach { rowEps ->
              item(key = "section-${section.id}-row-${rowEps.first().id}") {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  rowEps.forEach { ep ->
                    val isLast = s.progress?.lastEpId == ep.id
                    PgcEpisodeGridCard(
                      episode = ep,
                      isLastPlayed = isLast,
                      lastTime = if (isLast) s.progress?.lastTime ?: 0 else 0,
                      onPlay = { onPlayEpisode(s, ep) },
                      modifier = Modifier.weight(1f),
                    )
                  }
                  if (rowEps.size == 1) {
                    Spacer(Modifier.weight(1f))
                  }
                }
              }
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
      // 标题 + 季级角标(会员等),对齐官方标题行。
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = season.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (season.badge.isNotBlank()) {
          Text(
            text = season.badge,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
              .padding(start = 6.dp)
              .clip(RoundedCornerShape(3.dp))
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
              .padding(horizontal = 4.dp, vertical = 1.dp),
          )
        }
      }
      // 数据行:播放/追番/弹幕(对齐官方 ▶15亿 ♡933.6万 弹幕x)。
      if (season.viewCount > 0 || season.followCount > 0 || season.danmakuCount > 0) {
        val locale = currentUiLocale()
        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (season.viewCount > 0) {
            StatChip(R.drawable.ic_video_play_count, formatCompactCount(season.viewCount, locale))
          }
          if (season.followCount > 0) {
            StatChip(R.drawable.ic_player_like, formatCompactCount(season.followCount, locale))
          }
          if (season.danmakuCount > 0) {
            StatChip(R.drawable.ic_video_danmaku_count, formatCompactCount(season.danmakuCount, locale))
          }
        }
      }
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
private fun StatChip(iconRes: Int, text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(
      painter = painterResource(iconRes),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(14.dp),
    )
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 3.dp),
    )
  }
}

/**
 * 选集两列紧凑卡(P11-80,对齐官方选集面板):序号(第 N 话)+ 集标题 + 角标(会员),
 * 当前集(上次观看)粉色高亮 + 顶部进度条;无封面(官方选集面板同款紧凑样式)。
 */
@Composable
private fun PgcEpisodeGridCard(
  episode: PgcEpisode,
  isLastPlayed: Boolean,
  lastTime: Int,
  onPlay: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val accent = MaterialTheme.colorScheme.primary
  Box(modifier = modifier) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(if (isLastPlayed) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        .clickable(onClick = onPlay)
        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
      // 序号行:短标题为纯数字(正片话数)→「第 N 话」;否则用短标题(花絮名),空回落长标题。
      val shortTitle = episode.title.trim()
      val indexText = when {
        shortTitle.matches(Regex("\\d+")) -> "第 $shortTitle 话"
        shortTitle.isNotBlank() -> shortTitle
        else -> episode.longTitle
      }
      Text(
        text = indexText,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (isLastPlayed) FontWeight.Bold else FontWeight.Normal,
        color = if (isLastPlayed) accent else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (episode.longTitle.isNotBlank() && episode.longTitle != indexText) {
        Text(
          text = episode.longTitle,
          style = MaterialTheme.typography.bodySmall,
          color = if (isLastPlayed) accent else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (isLastPlayed) {
        Text(
          text = if (lastTime > 0) "上次看到 ${lastTime.formatDurationSeconds()}" else "上次观看",
          style = MaterialTheme.typography.labelSmall,
          color = accent,
        )
      }
    }
    // 角标(会员等)叠右上角。
    if (episode.badge.isNotBlank()) {
      Text(
        text = episode.badge,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .clip(RoundedCornerShape(bottomStart = 6.dp, topEnd = 8.dp))
          .background(accent)
          .padding(horizontal = 5.dp, vertical = 1.dp),
      )
    }
    // 上次观看进度条:卡片顶部细条(track + 按比例填充)。
    if (isLastPlayed && episode.duration > 0 && lastTime > 0) {
      val ratio = (lastTime.toFloat() / episode.duration).coerceIn(0f, 1f)
      Box(
        modifier = Modifier
          .align(Alignment.TopStart)
          .fillMaxWidth()
          .height(2.dp)
          .background(accent.copy(alpha = 0.2f)),
      ) {
        Box(
          modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(ratio)
            .background(accent),
        )
      }
    }
  }
}

/** 话数分组条跳转目标:选集行在 LazyColumn 里的首个 item 索引(header/季切换/选集标题/分组条之后)。 */
private fun episodeRowsBaseIndex(season: PgcSeason): Int {
  var idx = 1 // header
  if (season.seasons.size > 1) idx++ // season-selector
  if (season.episodes.isNotEmpty()) idx++ // main-title(选集标题+更新状态行)
  if (season.episodes.size > EpisodeGroupSize) idx++ // ep-group-bar
  return idx
}

/** 分组条标签:组首末集短标题均为数字(话数)→「1051-1100」式;否则用序号区间「N-M」。 */
private fun episodeGroupLabel(season: PgcSeason, start: Int, end: Int): String {
  fun numberAt(index: Int): Int? {
    val title = season.episodes.getOrNull(index)?.title?.trim().orEmpty()
    return title.toIntOrNull()
  }
  val first = numberAt(start)
  val last = numberAt(end - 1)
  return if (first != null && last != null) {
    "$first-$last"
  } else {
    "${start + 1}-${end}"
  }
}

/**
 * 按 MobilePgcSeasonScreen 的 LazyColumn item 顺序,算上次观看集(lastEpId)的线性索引。
 * header(1) → season-selector(可选) → ep-group-bar(可选) → main-title(可选)
 * + 正片行(每行2卡) → 各 section-title + 该 section 行。
 * 与上方 LazyColumn 的 item 顺序一一对应;找不到或 lastEpId==0 返回 -1(不滚动)。
 */
private fun lastPlayedItemIndex(season: PgcSeason, lastEpId: Int): Int {
  if (lastEpId == 0) return -1
  var idx = 1 // header
  if (season.seasons.size > 1) idx++ // season-selector
  if (season.episodes.isNotEmpty()) {
    idx++ // main-title(选集标题+更新状态行)
    if (season.episodes.size > EpisodeGroupSize) idx++ // ep-group-bar
    val i = season.episodes.indexOfFirst { it.id == lastEpId }
    if (i >= 0) return idx + i / 2 // 行 = 集序 / 2(两列)
    idx += (season.episodes.size + 1) / 2 // 正片行数
  }
  for (section in season.sections) {
    idx++ // section-title-{id}
    val i = section.episodes.indexOfFirst { it.id == lastEpId }
    if (i >= 0) return idx + i / 2
    idx += (section.episodes.size + 1) / 2
  }
  return -1
}