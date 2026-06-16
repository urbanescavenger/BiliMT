package com.kirin.mt.core.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

sealed class InstallResult {
  data class Started(val intent: Intent) : InstallResult()
  data object NeedsUnknownSourcesPermission : InstallResult()
  data class Failed(val message: String) : InstallResult()
}

class ApkInstaller(
  private val appContext: Context,
) {
  fun buildInstallIntent(file: File): Intent {
    val authority = "${appContext.packageName}.fileprovider"
    val uri: Uri = FileProvider.getUriForFile(appContext, authority, file)
    return Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
  }

  fun canInstallPackages(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      appContext.packageManager.canRequestPackageInstalls()
    } else {
      true
    }
  }

  fun buildUnknownSourcesIntent(): Intent {
    return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
      data = Uri.parse("package:${appContext.packageName}")
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
  }

  fun startInstall(activity: Activity, file: File): InstallResult {
    return try {
      if (!canInstallPackages()) {
        return InstallResult.NeedsUnknownSourcesPermission
      }
      val intent = buildInstallIntent(file)
      activity.startActivity(intent)
      InstallResult.Started(intent)
    } catch (e: Exception) {
      InstallResult.Failed(e.message ?: e.javaClass.simpleName)
    }
  }
}
