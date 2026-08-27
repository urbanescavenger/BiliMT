package com.kirin.mt.ui.space

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.youtube.YoutubeConstants
import com.kirin.mt.core.youtube.YoutubeParsers

/**
 * YouTube 频道主页视频态。镜像 [SpaceVideoState]，但用 InnerTube continuation token 翻页，
 * 而非 B 站整数页码。Shorts/直播 tab 与主页 tab 共用本态（数据各自一份在 UI state）。
 */
internal sealed interface ChannelVideoState {
  data object Loading : ChannelVideoState
  data object Empty : ChannelVideoState
  data class Failed(val message: String) : ChannelVideoState
  data class Success(
    val videos: List<VideoSummary>,
    val continuation: String?,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: String,
  ) : ChannelVideoState
}

/** 频道"播放列表" tab 的数据态（卡片列表 + continuation，与 [ChannelVideoState] 平行）。 */
internal sealed interface ChannelPlaylistState {
  data object Loading : ChannelPlaylistState
  data object Empty : ChannelPlaylistState
  data class Failed(val message: String) : ChannelPlaylistState
  data class Success(
    val playlists: List<YoutubeParsers.YoutubePlaylist>,
    val continuation: String?,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: String,
  ) : ChannelPlaylistState
}

/**
 * TV 版 YouTube 频道主页状态持有器。合并 [UpSpaceUiState] 的 TV 焦点恢复守卫字段与
 * 移动端 [com.kirin.mt.ui.mobile.space.MobileYoutubeChannelUiState] 的 continuation 分页模型。
 *
 * 2026-08-27 对齐移动端加内容 tab（主页/Shorts/直播/播放列表，TV 视频保留网格）：
 * 主页/Shorts/直播走 [ChannelVideoState]（各自独立 state），播放列表走 [ChannelPlaylistState]；
 * 服务端 tab params 来自 getChannelHeader 的 header.tabs（对齐移动端 serverTabParams）。
 *
 * 与 [UpSpaceUiState] 的差异：排序为"最新发布/最多播放"双档（[YoutubeConstants.ChannelVideoOrder]，
 * 对齐 B站 UP 空间；非 B站整数 order 维度）、无取关二次确认（本地 DataStore 写入无副作用）、
 * `continuation: String?` 替代 `nextPage: Int`。
 */
@Stable
internal class YoutubeChannelUiState {
  var name by mutableStateOf("")
  var avatar by mutableStateOf("")
  var tab by mutableStateOf(YoutubeConstants.ChannelContentTab.Videos)
  var videoState by mutableStateOf<ChannelVideoState>(ChannelVideoState.Loading)
  var shortsState by mutableStateOf<ChannelVideoState>(ChannelVideoState.Loading)
  var liveState by mutableStateOf<ChannelVideoState>(ChannelVideoState.Loading)
  var playlistState by mutableStateOf<ChannelPlaylistState>(ChannelPlaylistState.Loading)
  var order by mutableStateOf(YoutubeConstants.ChannelVideoOrder.Latest)
  var followed by mutableStateOf(false)
  var followLoading by mutableStateOf(false)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
  var focusedPlaylistIndex by mutableIntStateOf(0)
  var focusedPlaylistKey by mutableStateOf("")
  var retryKey by mutableIntStateOf(0)
  var focusFirstVideo by mutableStateOf(true)

  /** 服务端内容 tab params(小写标识 → params),切 Shorts/直播/播放列表时优先用(对齐移动端)。 */
  var serverTabParams by mutableStateOf<Map<String, String>>(emptyMap())

  // 守卫：已加载的频道 id + retryKey + 排序 + tab，防止同组合重复解析/重载
  //（从播放器返回复用列表；切排序/切 tab 强制重拉）。
  var loadedChannelId by mutableStateOf("")
  var loadedRetryKey by mutableIntStateOf(-1)
  var loadedOrder by mutableStateOf(YoutubeConstants.ChannelVideoOrder.Latest)
  var loadedTab by mutableStateOf(YoutubeConstants.ChannelContentTab.Videos)

  fun reset() {
    name = ""
    avatar = ""
    tab = YoutubeConstants.ChannelContentTab.Videos
    videoState = ChannelVideoState.Loading
    shortsState = ChannelVideoState.Loading
    liveState = ChannelVideoState.Loading
    playlistState = ChannelPlaylistState.Loading
    followed = false
    followLoading = false
    focusedVideoIndex = 0
    focusedVideoKey = ""
    focusedPlaylistIndex = 0
    focusedPlaylistKey = ""
    focusFirstVideo = true
    serverTabParams = emptyMap()
    loadedChannelId = ""
    loadedRetryKey = -1
    loadedOrder = YoutubeConstants.ChannelVideoOrder.Latest
    loadedTab = YoutubeConstants.ChannelContentTab.Videos
    order = YoutubeConstants.ChannelVideoOrder.Latest
  }
}
