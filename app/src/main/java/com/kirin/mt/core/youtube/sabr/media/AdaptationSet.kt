package com.kirin.mt.core.youtube.sabr.media

import androidx.media3.common.C.TrackType
import androidx.media3.common.util.UnstableApi

/**
 * alpha.64(端口 LibreTube `manifest/AdaptationSet`):一组可互换编码版本的视频/音频内容组件。
 * 单视频 adaptationSet(含选中 itag 的 Representation)+ 单音频 adaptationSet(默认音频 itag)。
 *
 * 对齐 LibreTube `manifest/AdaptationSet.kt`(MIT)。
 */
@UnstableApi
internal data class AdaptationSet(
  /** 轨类型([C.TRACK_TYPE_VIDEO] / [C.TRACK_TYPE_AUDIO])。 */
  val type: @TrackType Int,
  /** 该组的 Representation 列表(我们每组单条——预选清晰度)。 */
  val representations: List<Representation>,
)
