package com.kirin.mt.core.download

import android.content.Context
import java.io.File

/** 下载文件存储:应用私有目录,免存储权限,不进系统图库/文件管理器。 */
class DownloadStorage(context: Context) {
  private val appContext = context.applicationContext

  /** root = getExternalFilesDir("downloads"),不可用回退 filesDir/downloads。 */
  val root: File
    get() = (appContext.getExternalFilesDir("downloads") ?: File(appContext.filesDir, "downloads"))
      .apply { mkdirs() }

  private fun dir(name: String): File = File(root, name).apply { mkdirs() }

  /** 视频轨文件(video-only 或 muxed)。 */
  fun videoFile(downloadId: Long): File = File(dir("video"), "v_$downloadId.mp4")

  /** 音频轨文件。 */
  fun audioFile(downloadId: Long): File = File(dir("audio"), "a_$downloadId.m4a")

  /** 封面文件。 */
  fun thumbFile(downloadId: Long): File = File(dir("thumb"), "t_$downloadId.jpg")

  /** 删除该下载任务的全部文件。 */
  fun deleteAll(downloadId: Long) {
    listOf(videoFile(downloadId), audioFile(downloadId), thumbFile(downloadId)).forEach { file ->
      if (file.exists()) file.delete()
    }
  }
}
