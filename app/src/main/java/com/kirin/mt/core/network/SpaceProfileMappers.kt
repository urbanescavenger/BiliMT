package com.kirin.mt.core.network

import com.kirin.mt.core.model.SpaceUserProfile
import kotlinx.serialization.json.JsonObject

internal object SpaceProfileMappers {
  /** Maps the `data` object of `x/space/acc/info`. Fans/following are filled later from relation/stat. */
  fun fromAccInfo(data: JsonObject, fallbackMid: Long): SpaceUserProfile {
    val vip = data.obj("vip")
    val official = data.obj("official")
    val levelInfo = data.obj("level_info")
    val level = data.int("level").takeIf { it > 0 } ?: levelInfo?.int("current_level") ?: 0
    val mid = data.long("mid").takeIf { it > 0L } ?: fallbackMid
    return SpaceUserProfile(
      mid = mid,
      name = data.string("name"),
      face = VideoSummaryMappers.fixPicUrl(data.string("face")),
      topPhoto = VideoSummaryMappers.fixPicUrl(data.string("top_photo")),
      sign = data.string("sign"),
      level = level,
      fans = 0L,
      following = 0L,
      isVip = vip?.int("status") == 1,
      vipLabel = vip?.obj("label")?.string("text").orEmpty(),
      officialRole = official?.int("role") ?: 0,
      officialTitle = official?.string("title").orEmpty(),
      officialDesc = official?.string("desc").orEmpty(),
    )
  }

  /** Fills fans/following from the `data` object of `x/relation/stat`. Null keeps the profile as-is. */
  fun mergeRelationStat(profile: SpaceUserProfile, data: JsonObject?): SpaceUserProfile {
    if (data == null) return profile
    return profile.copy(
      fans = data.long("follower"),
      following = data.long("following"),
    )
  }
}