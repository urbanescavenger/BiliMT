package com.kirin.mt.ui.space

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kirin.mt.core.model.VideoSummary

/**
 * YouTube 频道主页视频态。镜像 [SpaceVideoState]，但用 InnerTube continuation token 翻页，
 * 而非 B 站整数页码。
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

/**
 * TV 版 YouTube 频道主页状态持有器。合并 [UpSpaceUiState] 的 TV 焦点恢复守卫字段与
 * 移动端 [com.kirin.mt.ui.mobile.space.MobileYoutubeChannelUiState] 的 continuation 分页模型。
 *
 * 与 [UpSpaceUiState] 的差异：无排序（YouTube 频道页无 order 维度）、无取关二次确认
 * （本地 DataStore 写入无副作用）、`continuation: String?` 替代 `nextPage: Int`。
 */
@Stable
internal class YoutubeChannelUiState {
  var name by mutableStateOf("")
  var avatar by mutableStateOf("")
  var videoState by mutableStateOf<ChannelVideoState>(ChannelVideoState.Loading)
  var followed by mutableStateOf(false)
  var followLoading by mutableStateOf(false)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
  var retryKey by mutableIntStateOf(0)
  var focusFirstVideo by mutableStateOf(true)

  // 守卫：已加载的频道 id + retryKey，防止同 channelId 重复解析/重载（从播放器返回复用列表）。
  var loadedChannelId by mutableStateOf("")
  var loadedRetryKey by mutableIntStateOf(-1)

  fun reset() {
    name = ""
    avatar = ""
    videoState = ChannelVideoState.Loading
    followed = false
    followLoading = false
    focusedVideoIndex = 0
    focusedVideoKey = ""
    focusFirstVideo = true
    loadedChannelId = ""
    loadedRetryKey = -1
  }
}