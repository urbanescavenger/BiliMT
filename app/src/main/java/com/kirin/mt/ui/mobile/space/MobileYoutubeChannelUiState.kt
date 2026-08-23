package com.kirin.mt.ui.mobile.space

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubeConstants

/**
 * 移动端 YouTube 频道主页状态持有器:在 shell 层(MobileApp.kt)remember,离开组合仍存活,
 * 从频道起播后退出播放器回到频道时不重载(镜像 MobileUpSpaceUiState)。
 * 守卫:loadedChannelId 管频道名解析;loadedOrder 管排序(最新/最热,切排序重拉)。
 */
@Stable
class MobileYoutubeChannelUiState {
  var name by mutableStateOf("")
  var avatar by mutableStateOf("")
  var followLoading by mutableStateOf(false)
  var items by mutableStateOf<List<VideoSummary>>(emptyList())
  var continuation by mutableStateOf<String?>(null)
  var loading by mutableStateOf(true)
  var loadingMore by mutableStateOf(false)
  var endReached by mutableStateOf(false)
  var failed by mutableStateOf<String?>(null)
  val gridState = LazyGridState()
  var loadedChannelId by mutableStateOf("")
  var order by mutableStateOf(YoutubeConstants.ChannelVideoOrder.Latest)
  var loadedOrder by mutableStateOf(YoutubeConstants.ChannelVideoOrder.Latest)
}
