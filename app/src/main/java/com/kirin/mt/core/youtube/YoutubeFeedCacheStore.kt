package com.kirin.mt.core.youtube

import android.content.Context
import androidx.room.Room
import com.kirin.mt.core.model.VideoSummary
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * YouTube 关注流逐频道持久化缓存(Room 底层)。
 *
 * 原实现用 DataStore 单 key 存全量 videos JSON,几百频道 × 每频道 15 条 = 数千条,增量刷新时每批全量
 * 序列化进 prefs 会变慢。改为 Room(SQLite)逐频道一行([YoutubeFeedEntity]),增量刷新只写当前频道行。
 *
 * 对外接口保持兼容(现有引用方零改动):
 *  - [read]:读全部频道缓存行合并成 [CachedYoutubeFeed],channelIds=缓存行主键集合。
 *    (调用方用 `cached.channelIds == 当前频道集合` 判断频道是否变化 —— 写缓存时保证每个频道都有行,
 *    包括空结果频道,键集合才与当前列表一致。)
 *  - [write]:全量写(按 video.channelId 分组逐频道 upsert,空频道补空行保持键一致),home 用。
 *  - [writeChannel]:单频道增量写,增量刷新的核心路径。
 *  - [clear]:清空全部。
 */
@Serializable
data class CachedYoutubeFeed(
  /** 写缓存时的频道 id 集合,读缓存时对比当前频道列表判断频道是否变化。 */
  val channelIds: List<String>,
  val videos: List<VideoSummary>,
  /** 各频道拉取时间的最大值(epoch millis),用于 TTL 判断。 */
  val fetchedAt: Long,
)

class YoutubeFeedCacheStore(context: Context) {
  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val dao: YoutubeFeedDao by lazy {
    Room.databaseBuilder(appContext, FeedDatabase::class.java, "youtube_feed.db").build().youtubeFeedDao()
  }

  suspend fun read(): CachedYoutubeFeed? {
    val rows = dao.getAll()
    if (rows.isEmpty()) return null
    val videos = rows.flatMap { decode(it.videosJson).orEmpty() }
    val fetchedAt = rows.maxOfOrNull { it.fetchedAt } ?: 0L
    return CachedYoutubeFeed(
      channelIds = rows.map { it.channelId },
      videos = videos,
      fetchedAt = fetchedAt,
    )
  }

  /** 全量写:先清掉当前列表之外的频道行,再按 video.channelId 分组逐频道 upsert。 */
  suspend fun write(channelIds: List<String>, videos: List<VideoSummary>) {
    dao.deleteChannelsNotIn(channelIds)
    val now = System.currentTimeMillis()
    val byChannel = videos.groupBy { it.channelId }
    for (channelId in channelIds) {
      // 空频道也写空行,保证读出的 channelIds 集合与当前列表一致(频道变化判断依赖此)。
      val channelVideos = byChannel[channelId].orEmpty()
      dao.upsert(YoutubeFeedEntity(channelId, encode(channelVideos), now))
    }
  }

  /** 单频道增量写:增量刷新的核心路径,只写该频道行。 */
  suspend fun writeChannel(channelId: String, videos: List<VideoSummary>) {
    dao.upsert(YoutubeFeedEntity(channelId, encode(videos), System.currentTimeMillis()))
  }

  suspend fun clear() {
    dao.clear()
  }

  private fun encode(videos: List<VideoSummary>): String =
    json.encodeToString(ListSerializer(VideoSummary.serializer()), videos)

  private fun decode(raw: String): List<VideoSummary>? {
    return runCatching {
      json.decodeFromString(ListSerializer(VideoSummary.serializer()), raw)
    }.getOrNull()
  }
}
