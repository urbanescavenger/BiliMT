package com.kirin.mt.ui.mobile

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * 判断当前设备是否应使用 TV(D-pad/遥控器) UI,否则使用触屏移动端 UI。
 *
 * 规则:Leanback TV 设备(FEATURE_LEANBACK)或当前 UI 模式为电视 → TV 壳;
 * 其余(手机/平板)→ 移动端壳。一个 APK 同时适配两种形态。
 */
fun isTvUi(context: Context): Boolean {
  val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
  if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
  return runCatching {
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
  }.getOrDefault(false)
}