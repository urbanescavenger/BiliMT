package com.kirin.mt.core.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 下载元数据 DAO。Room 为唯一事实源(进程死/界面重建后仍在)。 */
@Dao
interface DownloadDao {
  @Query("SELECT * FROM download ORDER BY createdAtMs DESC")
  @Transaction
  fun observeAll(): Flow<List<DownloadWithItems>>

  @Query("SELECT * FROM download WHERE id = :id")
  @Transaction
  suspend fun getById(id: Long): DownloadWithItems?

  /** 该视频是否已有非终态(进行中)下载,避免重复入队。 */
  @Query("SELECT COUNT(*) FROM download WHERE videoId = :videoId AND source = :source AND status IN ('queued','running','paused')")
  suspend fun existsActive(videoId: String, source: String): Boolean

  @Insert
  suspend fun insertDownload(download: DownloadEntity): Long

  @Insert
  suspend fun insertItem(item: DownloadItemEntity): Long

  @Query("UPDATE download SET status = :status, errorMessage = :error WHERE id = :id")
  suspend fun updateStatus(id: Long, status: String, error: String? = null)

  @Query("UPDATE download SET coverPath = :coverPath WHERE id = :id")
  suspend fun updateCoverPath(id: Long, coverPath: String?)

  @Query("UPDATE downloadItem SET status = :status, error = :error, initDone = :initDone WHERE id = :id")
  suspend fun updateItemStatus(id: Int, status: String, error: String? = null, initDone: Boolean = false)

  @Query("UPDATE downloadItem SET totalSize = :totalSize WHERE id = :id")
  suspend fun updateItemTotalSize(id: Int, totalSize: Long)

  @Query("SELECT * FROM downloadItem WHERE downloadId = :downloadId")
  suspend fun itemsFor(downloadId: Long): List<DownloadItemEntity>

  @Query("DELETE FROM download WHERE id = :id")
  suspend fun delete(id: Long)
}
