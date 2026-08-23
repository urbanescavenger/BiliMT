package com.kirin.mt.core.download

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 下载元数据 Room 数据库(父 [DownloadEntity] + 分件 [DownloadItemEntity])。 */
@Database(
  entities = [DownloadEntity::class, DownloadItemEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class DownloadDatabase : RoomDatabase() {
  abstract fun downloadDao(): DownloadDao

  companion object {
    // 曾有一次临时实验把 version 升到 2(加 sortOrder 列,后已撤销)。个别设备数据库已是 v2,
    // 代码回退到 v1 后启动直接撞「A migration from 2 to 1 was required but not found」崩溃——
    // 这里提供降级迁移删掉临时 sortOrder 列,让该设备干净回到 v1(保留下载数据)。
    val MIGRATION_2_1 = object : Migration(2, 1) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download DROP COLUMN sortOrder")
      }
    }
  }
}
