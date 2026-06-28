package com.kirin.mt.core.model

/** UGC 分区轮播 banner 单项。`bvid` 由 banner `url` 解析而来；点击起播时 cid 由播放器经
 *  `/x/web-interface/view` 解析。 */
data class UgcBannerItem(
  val bvid: String,
  val title: String,
  val cover: String,
)