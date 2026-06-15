package com.kirin.mt.core.app

import android.content.Context
import android.os.Build

data class AppVersion(val versionName: String, val versionCode: Long)

class AppInfo(context: Context) {
  private val appContext: Context = context.applicationContext

  fun current(): AppVersion {
    val packageName = appContext.packageName
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      appContext.packageManager.getPackageInfo(
        packageName,
        android.content.pm.PackageManager.PackageInfoFlags.of(0),
      )
    } else {
      @Suppress("DEPRECATION")
      appContext.packageManager.getPackageInfo(packageName, 0)
    }
    val name = info.versionName.orEmpty()
    val code: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      info.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      info.versionCode.toLong()
    }
    return AppVersion(name, code)
  }
}
