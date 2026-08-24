package com.kirin.mt.core.update

data class UpdateUiState(
  val currentVersionName: String = "",
  val currentVersionCode: Long = 0L,
  val status: Status = Status.Idle,
) {
  sealed class Status {
    data object Idle : Status()
    data object Checking : Status()
    data class UpToDate(val info: UpdateInfo) : Status()
    /**
     * 发现可下载更新。rechecked 标记该更新是否已被用户「重新检查」确认过:
     * false = 刚检查到,点击应重新检查(可能已有更新版本);true = 已重新检查仍是最新,点击应下载。
     */
    data class Available(val info: UpdateInfo, val rechecked: Boolean = false) : Status()
    data class Downloading(
      val info: UpdateInfo,
      val downloadedBytes: Long = 0L,
      val totalBytes: Long = 0L,
    ) : Status()
    data class Downloaded(val info: UpdateInfo) : Status()
    data class Failed(val message: String) : Status()
  }
}
