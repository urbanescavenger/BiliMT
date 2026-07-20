package com.kirin.mt.core.player

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 移动端后台播放媒体会话服务:播放器仍由 [com.kirin.mt.ui.mobile.player.MobilePlayerScreen] 持有,
 * 本服务建一个绑该 ExoPlayer(经 [PlayerHolder])的 [MediaSession];MediaSessionService 自动前台化 +
 * 默认 MediaStyle 通知(封面来自 MediaItem.artworkData、标题/艺人来自 MediaMetadata、播放/暂停按钮、
 * 锁屏媒体控件),随播放状态自动更新。仅移动端使用,TV 不启动。
 */
class PlaybackService : MediaSessionService() {
  private var session: MediaSession? = null

  override fun onCreate() {
    super.onCreate()
    // 服务在 isPlaying 时被 startForegroundService 启动,此时 player 已建并 setPlayerHolder。
    val player = PlayerHolder.player
    if (player != null) {
      session = MediaSession.Builder(this, player).build()
    }
  }

  override fun onGetSession(controller: MediaSession.ControllerInfo): MediaSession? = session

  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    val player = PlayerHolder.player
    if (player == null || !player.playWhenReady) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    session?.release()
    session = null
    super.onDestroy()
  }
}