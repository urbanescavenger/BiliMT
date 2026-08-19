package com.kirin.mt.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kirin.mt.BiliTvApplication
import com.kirin.mt.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 下载前台服务:持有 [DownloadManager] 的队列循环,负责前台通知(进度 + 暂停/续传/取消)。
 * 仅移动端使用。startForeground 用 try/catch 兜底 Android 14 后台启动限制(对齐 [com.kirin.mt.core.player.PlaybackService])。
 */
class DownloadService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var queueJob: Job? = null
  private var currentTitle: String = ""
  private var currentFraction: Float = 0f
  private var lastNotifyAt = 0L

  private val manager: DownloadManager
    get() = (application as BiliTvApplication).appContainer.downloadManager

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    ensureChannel()
    val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
    when (intent?.action) {
      ACTION_ENQUEUE, ACTION_RESUME -> {
        startForegroundSafely()
        if (queueJob == null) {
          queueJob = scope.launch {
            while (true) {
              val hasMore = manager.runQueue { p -> onProgress(p) }
              if (!hasMore) break
              delay(500)
            }
            stopForeground(ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            queueJob = null
          }
        }
      }
      ACTION_PAUSE -> {
        if (id > 0L) scope.launch { manager.pause(id) }
      }
      ACTION_CANCEL -> {
        if (id > 0L) scope.launch { manager.cancel(id) }
      }
    }
    return START_NOT_STICKY
  }

  private fun onProgress(p: DownloadProgress) {
    manager.reportProgress(p)
    // 前台通知 + Room 查询只按限流间隔刷(引擎已聚合,但服务端再兜一层)。若逐事件 Room 查询 +
    // binder notify 会在共享 Dispatchers.IO 上跟实际网络读抢线程,拖慢下载(对齐 LibreTube 低频刷新)。
    val now = SystemClock.elapsedRealtime()
    if (now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
    lastNotifyAt = now
    scope.launch {
      val group = manager.downloads.first().firstOrNull { it.download.id == p.downloadId }
      currentTitle = group?.download?.title ?: currentTitle
      currentFraction = p.fraction
      updateNotification()
    }
  }

  private fun startForegroundSafely() {
    try {
      startForeground(NOTIF_ID, buildNotification())
    } catch (e: RuntimeException) {
      Log.w(Tag, "startForeground failed: ${e.message}")
    }
  }

  private fun updateNotification() {
    val nm = getSystemService(NotificationManager::class.java)
    try {
      nm.notify(NOTIF_ID, buildNotification())
    } catch (e: RuntimeException) {
      Log.w(Tag, "notify failed: ${e.message}")
    }
  }

  private fun buildNotification(): Notification {
    val contentPi = PendingIntent.getActivity(
      this, 0,
      Intent(this, com.kirin.mt.MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val title = currentTitle.ifEmpty { getString(R.string.app_name) }
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_nav_search)
      .setContentTitle(title)
      .setContentText(getString(R.string.downloads_notification_title))
      .setContentIntent(contentPi)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setProgress(100, (currentFraction * 100).toInt(), currentFraction <= 0f)
      .addAction(R.drawable.ic_nav_search, getString(R.string.downloads_action_pause), servicePendingIntent(ACTION_PAUSE))
      .addAction(R.drawable.ic_nav_search, getString(R.string.downloads_action_cancel), servicePendingIntent(ACTION_CANCEL))
    return builder.build()
  }

  private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
    this,
    action.hashCode(),
    Intent(this, DownloadService::class.java).setAction(action),
    PendingIntent.FLAG_IMMUTABLE,
  )

  private fun ensureChannel() {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, getString(R.string.downloads_notification_channel), NotificationManager.IMPORTANCE_LOW),
      )
    }
  }

  override fun onDestroy() {
    queueJob?.cancel()
    queueJob = null
    super.onDestroy()
  }

  companion object {
    const val ACTION_ENQUEUE = "com.kirin.mt.action.DOWNLOAD_ENQUEUE"
    const val ACTION_PAUSE = "com.kirin.mt.action.DOWNLOAD_PAUSE"
    const val ACTION_RESUME = "com.kirin.mt.action.DOWNLOAD_RESUME"
    const val ACTION_CANCEL = "com.kirin.mt.action.DOWNLOAD_CANCEL"
    const val EXTRA_ID = "download_id"
    private const val CHANNEL_ID = "bili_download"
    private const val NOTIF_ID = 2001
    private const val NOTIFY_INTERVAL_MS = 250L
    private const val Tag = "DownloadSvc"
  }
}
