package com.kirin.mt.core.youtube

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * YouTube 关注流逐频道缓存行(对齐 LibreTube LocalFeedRepository 的逐频道 DB 缓存)。
 *
 * 主键 channelId,一行存该频道最近一次拉取的视频列表 JSON + 时间戳。
 * 增量刷新时每批/每频道独立 [upsert],几百频道也只需写自身行,不重写全表。
 */
@Entity(tableName = "youtube_feed")
data class YoutubeFeedEntity(
  @PrimaryKey val channelId: String,
  /** [VideoSummary] 列表的 kotlinx-serialization JSON([VideoSummary] 已 @Serializable)。 */
  val videosJson: String,
  /** 该频道本次拉取时间(epoch millis),用于缓存 TTL 判断。 */
  val fetchedAt: Long,
)
