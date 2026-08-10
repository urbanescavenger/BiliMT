package com.kirin.mt.ui.mobile.space

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kirin.mt.core.model.SpaceUserProfile

/**
 * 移动端 B 站 UP 空间页状态持有器:在 shell 层(MobileApp.kt)remember,离开组合仍存活,
 * 从空间起播后退出播放器回到空间时不重载(镜像 TV UpSpaceUiState)。
 * 守卫:profileLoadedMid 管资料/关注,videoLoadedMid+videoLoadedOrder 管投稿列表。
 */
@Stable
class MobileUpSpaceUiState {
  var order by mutableStateOf("pubdate")
  var profile by mutableStateOf<SpaceUserProfile?>(null)
  var followed by mutableStateOf(false)
  var followLoading by mutableStateOf(false)
  var state by mutableStateOf<SpaceState>(SpaceState.Loading)
  val gridState = LazyGridState()
  var profileLoadedMid by mutableLongStateOf(0L)
  var videoLoadedMid by mutableLongStateOf(0L)
  var videoLoadedOrder by mutableStateOf("")
}
