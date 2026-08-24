package com.kirin.mt.core.update

import com.kirin.mt.BuildConfig
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
    // 从 Available 状态发起的刷新 = 用户主动「重新检查」,确认后仍是最新则转可下载(rechecked=true);
    // 从 Idle/UpToDate/Failed 发起的 = 首次检查,rechecked=false。
    val wasAvailable = _state.value.status is UpdateUiState.Status.Available
    _state.update { it.copy(status = UpdateUiState.Status.Checking) }
    val installed = appInfo.current()
    val info = try {
      if (BuildConfig.DEBUG) {
        // debug 变体走固定 "debug" release 升级链,不查 v* releases(避免把 debug 用户
        // 引导去装 release 稳定版;debug 与 release 是独立升级链)。
        repository.checkDebugLatest()
      } else {
        // 安装版本 versionName 含 '-'（如 1.1.1-alpha.1）= 预发布用户，允许收到更新的 alpha/稳定版；
        // 稳定版构建只在稳定版里挑，避免把 alpha 推给稳定用户。
        repository.checkLatest(installed.versionName.contains("-"))
      }
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
      _state.update { it.copy(status = UpdateUiState.Status.Available(info, rechecked = wasAvailable)) }
      return
    }
    // 缓存文件必须与远端 asset 大小一致才算已下载：debug 的固定 asset 名（BiliMT-debug.apk）
    // 会残留旧包，只按存在性判断会让后续更新一直装旧缓存 APK 而版本号不变。大小不一致走
    // Available，download() 会因 size 不同重新拉新包。
    if (downloader.isDownloaded(asset.name) && downloader.downloadedFileSize(asset.name) == asset.size) {
      _state.update { it.copy(status = UpdateUiState.Status.Downloaded(info)) }
    } else {
      _state.update { it.copy(status = UpdateUiState.Status.Available(info, rechecked = wasAvailable)) }
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
      // 下载失败回到 Available 且 rechecked=true,让按钮保持「下载更新」,再点可重试下载而非重新检查。
      _state.update { it.copy(status = UpdateUiState.Status.Available(info, rechecked = true)) }
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
