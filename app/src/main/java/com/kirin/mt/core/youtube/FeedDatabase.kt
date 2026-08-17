package com.kirin.mt.core.youtube

import androidx.room.Database
import androidx.room.RoomDatabase

/** YouTube 关注流缓存的 Room 数据库(单表 [YoutubeFeedEntity])。 */
@Database(entities = [YoutubeFeedEntity::class], version = 1, exportSchema = false)
abstract class FeedDatabase : RoomDatabase() {
  abstract fun youtubeFeedDao(): YoutubeFeedDao
}
