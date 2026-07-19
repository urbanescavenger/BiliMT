package com.kirin.mt.core.player

import androidx.media3.exoplayer.ExoPlayer

/**
 * 移动端播放器与后台 [PlaybackService] 共享当前 ExoPlayer 与标题,用于后台保活通知控件。
 * 由 [com.kirin.mt.ui.mobile.player.MobilePlayerScreen] 在播放器创建/释放时设置/清空。
 */
object PlayerHolder {
  @Volatile
  var player: ExoPlayer? = null

  @Volatile
  var title: String = ""
}