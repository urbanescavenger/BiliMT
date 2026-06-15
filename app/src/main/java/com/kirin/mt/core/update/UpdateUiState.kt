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
    data class Available(val info: UpdateInfo) : Status()
    data class Downloading(val info: UpdateInfo) : Status()
    data class Downloaded(val info: UpdateInfo) : Status()
    data class Failed(val message: String) : Status()
  }
}
