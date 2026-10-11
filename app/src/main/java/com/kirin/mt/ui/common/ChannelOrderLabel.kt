package com.kirin.mt.ui.common

import androidx.annotation.StringRes
import com.kirin.mt.R
import com.kirin.mt.core.youtube.YoutubeConstants

/**
 * 频道「视频」排序三档的展示名（最新发布 / 最多播放 / 最早发布）。
 *
 * TV 头部那颗排序 chip（[com.kirin.mt.ui.space.YoutubeChannelScreen]）与移动端频道页排序菜单
 * （[com.kirin.mt.ui.mobile.space.MobileYoutubeChannelScreen]）共用同一份映射，别在两端各写一遍 when。
 */
@get:StringRes
internal val YoutubeConstants.ChannelVideoOrder.sortLabelRes: Int
  get() = when (this) {
    YoutubeConstants.ChannelVideoOrder.Latest -> R.string.player_up_sort_latest
    YoutubeConstants.ChannelVideoOrder.Popular -> R.string.player_up_sort_hot
    YoutubeConstants.ChannelVideoOrder.Oldest -> R.string.player_up_sort_oldest
  }
