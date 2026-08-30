package com.kirin.mt.ui.mobile.space

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubeConstants
import com.kirin.mt.core.youtube.YoutubeParsers

/**
 * 移动端 YouTube 频道主页状态持有器:在 shell 层(MobileApp.kt)remember,离开组合仍存活,
 * 从频道起播后退出播放器回到频道时不重载(镜像 MobileUpSpaceUiState)。
 * 守卫:loadedChannelId 管频道名解析;loadedOrder 管排序(最新/最热,切排序重拉)。
 */
@Stable
internal class MobileYoutubeChannelUiState {
  var name by mutableStateOf("")
  var avatar by mutableStateOf("")
  /** 订阅数；未知为 null。 */
  var subscriberCount by mutableStateOf<Long?>(null)
  /** 频道简介；无则空串。 */
  var description by mutableStateOf("")
  /** banner 图 URL；无则空串。 */
  var bannerUrl by mutableStateOf("")
  /** 服务端提供的内容 Tab params(稳定标识小写 → params),对齐 LibreTube 从 header 取。 */
  var serverTabParams by mutableStateOf<Map<String, String>>(emptyMap())
  var followLoading by mutableStateOf(false)
  var items by mutableStateOf<List<VideoSummary>>(emptyList())
  var continuation by mutableStateOf<String?>(null)
  /** 播放列表 Tab 的播放列表卡列表(与 items 互斥,依 tab 而定)。 */
  var playlists by mutableStateOf<List<YoutubeParsers.YoutubePlaylist>>(emptyList())
  var playlistContinuation by mutableStateOf<String?>(null)
  var loading by mutableStateOf(true)
  var loadingMore by mutableStateOf(false)
  var endReached by mutableStateOf(false)
  var failed by mutableStateOf<String?>(null)
  val gridState = LazyGridState()
  var loadedChannelId by mutableStateOf("")
  var order by mutableStateOf(YoutubeConstants.ChannelVideoOrder.Latest)
  var loadedOrder by mutableStateOf(YoutubeConstants.ChannelVideoOrder.Latest)
  /** 当前内容 Tab(视频/Shorts/直播)。 */
  var tab by mutableStateOf(YoutubeConstants.ChannelContentTab.Videos)
  /** 已加载的 Tab 守卫:切 Tab 强制重拉。 */
  var loadedTab by mutableStateOf(YoutubeConstants.ChannelContentTab.Videos)
}
