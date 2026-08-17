package com.kirin.mt.core.youtube

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** YouTube 关注流逐频道缓存的 DAO。 */
@Dao
interface YoutubeFeedDao {
  /** 单频道 upsert:增量刷新的核心写路径,只写该频道行。 */
  @Upsert suspend fun upsert(entity: YoutubeFeedEntity)

  /** 读全部频道缓存行。 */
  @Query("SELECT * FROM youtube_feed")
  suspend fun getAll(): List<YoutubeFeedEntity>

  /** 清空全部缓存(频道列表大改 / 手动清缓存)。 */
  @Query("DELETE FROM youtube_feed")
  suspend fun clear()

  /** 删除当前关注列表之外的频道缓存行(增删频道后自动失效)。 */
  @Query("DELETE FROM youtube_feed WHERE channelId NOT IN (:keepIds)")
  suspend fun deleteChannelsNotIn(keepIds: List<String>)
}
