package com.kirin.mt.core.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.kirin.mt.MainActivity
import com.kirin.mt.R

/**
 * 移动端后台播放前台服务:播放器仍由 [com.kirin.mt.ui.mobile.player.MobilePlayerScreen] 持有,
 * 本服务建一个绑该 ExoPlayer(经 [PlayerHolder])的 [MediaSession],并**显式 startForeground**
 * 保证通知显示 —— 不用 MediaSessionService(无 MediaController 连接时它不保证及时前台化)。
 * 通知用 [MediaStyleNotificationHelper.MediaStyle](封面取自 MediaItem.artworkData、播放/暂停按钮 +
 * 锁屏媒体控件由 session token 提供),外加"停止"按钮。仅移动端使用,TV 不启动。
 */
@OptIn(UnstableApi::class)
class PlaybackService : Service() {
  companion object {
    const val ACTION_PLAY = "com.kirin.mt.action.PLAYBACK_PLAY"
    const val ACTION_PAUSE = "com.kirin.mt.action.PLAYBACK_PAUSE"
    const val ACTION_STOP = "com.kirin.mt.action.PLAYBACK_STOP"
    private const val CHANNEL_ID = "bili_playback"
    private const val NOTIF_ID = 1001
  }

  private var session: MediaSession? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    ensureChannel()
    ensureSession()
    when (intent?.action) {
      ACTION_PLAY -> PlayerHolder.player?.play()
      ACTION_PAUSE -> PlayerHolder.player?.pause()
      ACTION_STOP -> {
        PlayerHolder.player?.pause()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
    }
    startForeground(NOTIF_ID, buildNotification())
    return START_NOT_STICKY
  }

  private fun ensureSession() {
    if (session == null) {
      val player = PlayerHolder.player
      if (player != null) {
        session = MediaSession.Builder(this, player).build()
      }
    }
  }

  private fun buildNotification(): Notification {
    val title = PlayerHolder.title.ifEmpty { getString(R.string.app_name) }
    val contentPi = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_nav_search)
      .setContentTitle(title)
      .setContentText(getString(R.string.app_name))
      .setContentIntent(contentPi)
      .addAction(R.drawable.ic_nav_search, "停止", servicePendingIntent(ACTION_STOP))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
    session?.let { builder.setStyle(MediaStyleNotificationHelper.MediaStyle(it)) }
    return builder.build()
  }

  private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
    this,
    action.hashCode(),
    Intent(this, PlaybackService::class.java).setAction(action),
    PendingIntent.FLAG_IMMUTABLE,
  )

  private fun ensureChannel() {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "后台播放", NotificationManager.IMPORTANCE_LOW),
      )
    }
  }

  override fun onDestroy() {
    session?.release()
    session = null
    super.onDestroy()
  }
}