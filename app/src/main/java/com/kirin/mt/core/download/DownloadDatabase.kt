package com.kirin.mt.core.download

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 下载元数据 Room 数据库(父 [DownloadEntity] + 分件 [DownloadItemEntity])。 */
@Database(
  entities = [DownloadEntity::class, DownloadItemEntity::class],
  version = 2,
  exportSchema = false,
)
abstract class DownloadDatabase : RoomDatabase() {
  abstract fun downloadDao(): DownloadDao

  companion object {
    /** v1→v2:新增 sortOrder 列(列表拖动排序)。旧行默认 0,仍按 createdAtMs 兜底排序,顺序不变。 */
    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
      }
    }
  }
}
