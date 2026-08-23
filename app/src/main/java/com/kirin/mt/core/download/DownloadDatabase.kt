package com.kirin.mt.core.download

import androidx.room.Database
import androidx.room.RoomDatabase

/** 下载元数据 Room 数据库(父 [DownloadEntity] + 分件 [DownloadItemEntity])。 */
@Database(
  entities = [DownloadEntity::class, DownloadItemEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class DownloadDatabase : RoomDatabase() {
  abstract fun downloadDao(): DownloadDao
}
