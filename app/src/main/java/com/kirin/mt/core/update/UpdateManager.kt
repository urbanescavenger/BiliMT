package com.kirin.mt.core.update

import com.kirin.mt.core.app.AppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UpdateManager(
  private val appInfo: AppInfo,
  private val repository: UpdateRepository,
  private val downloader: UpdateDownloader,
) {
  private val _state = MutableStateFlow(initialState())
  val state: StateFlow<UpdateUiState> = _state.asStateFlow()

  private fun initialState(): UpdateUiState {
    val v = appInfo.current()
    return UpdateUiState(
      currentVersionName = v.versionName,
      currentVersionCode = v.versionCode,
      status = UpdateUiState.Status.Idle,
    )
  }

  suspend fun refresh() {
    _state.update { it.copy(status = UpdateUiState.Status.Checking) }
    val installed = appInfo.current()
    // 安装版本 versionName 含 '-'（如 1.1.1-alpha.1）= 预发布用户，允许收到更新的 alpha/稳定版；
    // 稳定版/dev 构建只在稳定版里挑，避免把 alpha 推给稳定用户。
    val includePrereleases = installed.versionName.contains("-")
    val info = try {
      repository.checkLatest(includePrereleases)
    } catch (e: Exception) {
      _state.update { it.copy(status = UpdateUiState.Status.Failed(e.message ?: e.javaClass.simpleName)) }
      return
    }
    if (info.versionCode <= installed.versionCode) {
      _state.update { it.copy(status = UpdateUiState.Status.UpToDate(info)) }
      return
    }
    val asset = info.matchingAsset
    if (asset == null) {
      _state.update { it.copy(status = UpdateUiState.Status.Available(info)) }
      return
    }
    if (downloader.isDownloaded(asset.name)) {
      _state.update { it.copy(status = UpdateUiState.Status.Downloaded(info)) }
    } else {
      _state.update { it.copy(status = UpdateUiState.Status.Available(info)) }
    }
  }

  suspend fun download(): File? = withContext(Dispatchers.IO) {
    val info = (_state.value.status as? UpdateUiState.Status.Available)?.info
    if (info == null) return@withContext null
    val asset = info.matchingAsset ?: return@withContext null
    _state.update { it.copy(status = UpdateUiState.Status.Downloading(info)) }
    try {
      val file = downloader.download(asset) { downloaded, total ->
        _state.update { current ->
          val currentStatus = current.status
          if (currentStatus is UpdateUiState.Status.Downloading && currentStatus.info.versionCode == info.versionCode) {
            current.copy(status = currentStatus.copy(downloadedBytes = downloaded, totalBytes = total))
          } else {
            current
          }
        }
      }
      _state.update { it.copy(status = UpdateUiState.Status.Downloaded(info)) }
      file
    } catch (e: Exception) {
      _state.update { it.copy(status = UpdateUiState.Status.Available(info)) }
      throw e
    }
  }

  fun downloadedFile(): File? {
    val current = _state.value
    val info = (current.status as? UpdateUiState.Status.Downloaded)?.info ?: return null
    val asset = info.matchingAsset ?: return null
    val f = downloader.downloadedFile(asset.name)
    return if (f.exists() && f.length() > 0) f else null
  }
}
