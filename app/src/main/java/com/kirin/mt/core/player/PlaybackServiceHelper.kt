package com.kirin.mt.core.player

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 移动端后台播放前台服务启动助手。在线播放器(MobilePlayerScreen)与离线播放器
 * (MobileOfflinePlayerScreen)共用:播放开始时安全启动 [PlaybackService] 做通知控件。
 *
 * 这里捕获异常避免崩溃;若服务已在运行,用普通 startService 刷新通知标题(已运行服务后台允许)。
 */
fun startPlaybackService(context: Context) {
  try {
    ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
  } catch (e: IllegalStateException) {
    Log.w("PlaybackService", "startForegroundService blocked in background: ${e.message}")
    // 不 fallback 到 startService——后台 startService 虽入队成功,但 onStartCommand 里
    // startForeground 仍会抛 ForegroundServiceStartNotAllowedException。
    // PlaybackService 侧已 catch 该异常优雅停止,这里直接放弃启动即可。
  }
}
