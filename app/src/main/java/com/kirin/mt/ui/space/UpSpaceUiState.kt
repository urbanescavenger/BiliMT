package com.kirin.mt.ui.space

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kirin.mt.core.model.SpaceUserProfile
import com.kirin.mt.core.model.VideoSummary

internal const val UpSpaceOrderLatest = "pubdate"
internal const val UpSpaceOrderHot = "click"
internal const val UpSpacePageSize = 25

internal sealed interface SpaceProfileState {
  data object Loading : SpaceProfileState
  data class Loaded(val profile: SpaceUserProfile) : SpaceProfileState
  data class Failed(val message: String) : SpaceProfileState
}

internal sealed interface SpaceVideoState {
  data object Loading : SpaceVideoState
  data object Empty : SpaceVideoState
  data class Failed(val message: String) : SpaceVideoState
  data class Success(
    val videos: List<VideoSummary>,
    val nextPage: Int,
    val loadingMore: Boolean,
    val endReached: Boolean,
    val loadMoreError: String,
  ) : SpaceVideoState
}

@Stable
internal class UpSpaceUiState {
  var order by mutableStateOf(UpSpaceOrderLatest)
  var profileState by mutableStateOf<SpaceProfileState>(SpaceProfileState.Loading)
  var videoState by mutableStateOf<SpaceVideoState>(SpaceVideoState.Loading)
  var followed by mutableStateOf(false)
  var followLoading by mutableStateOf(false)
  var showUnfollowConfirm by mutableStateOf(false)
  var unfollowConfirmFocusedConfirm by mutableStateOf(false)
  var focusedVideoIndex by mutableIntStateOf(0)
  var focusedVideoKey by mutableStateOf("")
  var retryKey by mutableIntStateOf(0)
  var focusFirstVideo by mutableStateOf(true)

  var profileLoadedMid by mutableLongStateOf(0L)
  var profileLoadedRetryKey by mutableIntStateOf(-1)
  var videoLoadedMid by mutableLongStateOf(0L)
  var videoLoadedOrder by mutableStateOf("")
  var videoLoadedRetryKey by mutableIntStateOf(-1)

  fun reset() {
    order = UpSpaceOrderLatest
    profileState = SpaceProfileState.Loading
    videoState = SpaceVideoState.Loading
    followed = false
    followLoading = false
    showUnfollowConfirm = false
    unfollowConfirmFocusedConfirm = false
    focusedVideoIndex = 0
    focusedVideoKey = ""
    focusFirstVideo = true
    profileLoadedMid = 0L
    profileLoadedRetryKey = -1
    videoLoadedMid = 0L
    videoLoadedOrder = ""
    videoLoadedRetryKey = -1
  }

  fun selectOrder(newOrder: String) {
    if (order == newOrder) return
    order = newOrder
    focusFirstVideo = false
    focusedVideoIndex = 0
    focusedVideoKey = ""
    videoState = SpaceVideoState.Loading
    videoLoadedOrder = ""
    videoLoadedRetryKey = -1
  }
}