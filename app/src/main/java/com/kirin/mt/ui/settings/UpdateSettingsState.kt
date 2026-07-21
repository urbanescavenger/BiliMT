package com.kirin.mt.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kirin.mt.R
import com.kirin.mt.core.update.UpdateUiState

@Composable
fun currentVersionText(state: UpdateUiState): String {
  return if (state.currentVersionName.isEmpty()) {
    stringResource(R.string.settings_update_current_version_title)
  } else {
    stringResource(
      R.string.settings_update_current_version_value,
      state.currentVersionName,
      state.currentVersionCode,
    )
  }
}

@Composable
fun latestVersionText(state: UpdateUiState): String {
  return when (val s = state.status) {
    UpdateUiState.Status.Idle -> stringResource(R.string.settings_update_latest_version_value_unchecked)
    UpdateUiState.Status.Checking -> stringResource(R.string.settings_update_checking)
    is UpdateUiState.Status.UpToDate -> stringResource(R.string.settings_update_latest_version_value_up_to_date)
    is UpdateUiState.Status.Available -> stringResource(
      R.string.settings_update_latest_version_value_available,
      s.info.versionName,
      s.info.versionCode,
    )
    is UpdateUiState.Status.Downloading -> downloadProgressText(s.downloadedBytes, s.totalBytes)
    is UpdateUiState.Status.Downloaded -> stringResource(
      R.string.settings_update_latest_version_value_available,
      s.info.versionName,
      s.info.versionCode,
    )
    is UpdateUiState.Status.Failed -> s.message
  }
}

@Composable
private fun downloadProgressText(downloaded: Long, total: Long): String {
  if (total <= 0) return stringResource(R.string.settings_update_downloading)
  val percent = (downloaded * 100L / total).toInt().coerceIn(0, 100)
  return "${percent}%"
}

/** 下载进度比例(0..1),仅 Downloading 且 total>0 时非 null,供进度条使用。 */
fun downloadProgressFraction(state: UpdateUiState): Float? {
  val s = state.status as? UpdateUiState.Status.Downloading ?: return null
  if (s.totalBytes <= 0L) return null
  return (s.downloadedBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f)
}

@Composable
fun checkActionLabel(state: UpdateUiState): String = when (val s = state.status) {
  UpdateUiState.Status.Checking -> stringResource(R.string.settings_update_checking)
  else -> stringResource(R.string.settings_update_check_action)
}

fun isCheckActionEnabled(state: UpdateUiState): Boolean = when (state.status) {
  UpdateUiState.Status.Checking -> false
  is UpdateUiState.Status.Downloading -> false
  else -> true
}

@Composable
fun downloadOrInstallLabel(state: UpdateUiState): String? = when (val s = state.status) {
  is UpdateUiState.Status.Available -> stringResource(R.string.settings_update_download_action)
  is UpdateUiState.Status.Failed -> null
  is UpdateUiState.Status.Downloading -> stringResource(R.string.settings_update_downloading)
  is UpdateUiState.Status.Downloaded -> stringResource(R.string.settings_update_install_action)
  else -> null
}

fun isDownloadOrInstallActionEnabled(state: UpdateUiState): Boolean = when (state.status) {
  is UpdateUiState.Status.Available -> true
  is UpdateUiState.Status.Downloaded -> true
  else -> false
}

fun shouldShowDownloadOrInstallRow(state: UpdateUiState): Boolean = when (state.status) {
  is UpdateUiState.Status.Available,
  is UpdateUiState.Status.Downloading,
  is UpdateUiState.Status.Downloaded -> true
  else -> false
}

fun shouldShowReleaseNotesAction(state: UpdateUiState): Boolean = when (val s = state.status) {
  is UpdateUiState.Status.Available -> s.info.releaseUrl.isNotEmpty()
  is UpdateUiState.Status.Downloaded -> s.info.releaseUrl.isNotEmpty()
  else -> false
}
