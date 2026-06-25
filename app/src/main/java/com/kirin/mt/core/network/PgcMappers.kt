package com.kirin.mt.core.network

import com.kirin.mt.core.model.PgcEpisode
import com.kirin.mt.core.model.PgcPlayProgress
import com.kirin.mt.core.model.PgcSeason
import com.kirin.mt.core.model.PgcSeasonRef
import com.kirin.mt.core.model.PgcSection
import com.kirin.mt.core.model.PgcSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object PgcMappers {
  fun fromFeedSubItem(json: JsonObject): PgcSummary? {
    val seasonId = json.int("season_id")
    if (seasonId == 0) return null
    return PgcSummary(
      seasonId = seasonId,
      episodeId = json.int("episode_id"),
      title = json.string("title"),
      subTitle = json.string("sub_title"),
      cover = VideoSummaryMappers.fixPicUrl(json.string("cover")),
      rating = json.string("rating"),
      seasonType = json.int("season_type"),
    )
  }

  fun fromIndexItem(json: JsonObject): PgcSummary? {
    val seasonId = json.int("season_id")
    if (seasonId == 0) return null
    val firstEp = json.obj("first_ep")
    return PgcSummary(
      seasonId = seasonId,
      episodeId = firstEp?.int("ep_id") ?: 0,
      title = json.string("title"),
      subTitle = json.string("sub_title"),
      cover = VideoSummaryMappers.fixPicUrl(json.string("cover")),
      rating = json.string("score"),
      seasonType = json.int("season_type"),
    )
  }

  fun fromSeasonData(data: JsonObject): PgcSeason {
    val userStatus = data.obj("user_status")
    val progress = userStatus?.obj("progress")
    val type = data.int("type")
    return PgcSeason(
      seasonId = data.int("season_id"),
      title = data.string("title"),
      cover = VideoSummaryMappers.fixPicUrl(data.string("cover")),
      evaluate = data.string("evaluate"),
      type = type,
      typeName = pgcTypeName(type),
      newEpDesc = data.obj("new_ep")?.string("desc").orEmpty(),
      episodes = data.array("episodes").mapNotNull { it.asObjectOrNull()?.let { e -> fromEpisode(e) } },
      sections = data.array("section")
        .mapNotNull { it.asObjectOrNull()?.let { s -> fromSection(s) } }
        .filter { it.episodes.isNotEmpty() },
      seasons = data.array("seasons")
        .mapNotNull { it.asObjectOrNull()?.let { o -> fromOtherSeason(o) } },
      progress = progress?.let {
        PgcPlayProgress(
          lastEpId = it.int("last_ep_id"),
          lastEpIndex = it.string("last_ep_index"),
          lastTime = it.int("last_time"),
        )
      },
    )
  }

  fun fromEpisode(json: JsonObject): PgcEpisode {
    return PgcEpisode(
      id = json.int("id"),
      aid = json.long("aid"),
      bvid = json.string("bvid"),
      cid = json.long("cid"),
      title = json.string("title"),
      longTitle = json.string("long_title"),
      cover = VideoSummaryMappers.fixPicUrl(json.string("cover")),
      duration = json.int("duration"),
    )
  }

  private fun fromSection(json: JsonObject): PgcSection {
    val episodes = json.array("episodes")
      .mapNotNull { it.asObjectOrNull()?.let { e -> fromEpisode(e) } }
      .filter { it.aid != 0L }
    return PgcSection(
      id = json.long("id"),
      title = json.string("title"),
      episodes = episodes,
    )
  }

  private fun fromOtherSeason(json: JsonObject): PgcSeasonRef {
    return PgcSeasonRef(
      seasonId = json.int("season_id"),
      seasonTitle = json.string("season_title"),
      cover = VideoSummaryMappers.fixPicUrl(json.string("cover")),
    )
  }

  private fun pgcTypeName(type: Int): String = when (type) {
    1 -> "番剧"
    2 -> "电影"
    3 -> "纪录片"
    4 -> "国创"
    5 -> "电视剧"
    7 -> "综艺"
    else -> ""
  }
}

private fun JsonObject.array(name: String): List<JsonElement> {
  return this[name] as? JsonArray ?: emptyList()
}
