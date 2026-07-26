package com.kirin.mt.core.player

import android.graphics.Color

data class DanmakuEntry(
  val showAtMs: Long,
  val text: String,
  val mode: DanmakuMode,
  val color: Int = Color.WHITE,
)

enum class DanmakuMode {
  Scroll,
  Top,
  Bottom,
}

/** 发送弹幕结果:B站 /x/v2/dm/post 成功时返回。dmid 为弹幕 id,visible=false 表示进审核不可见。 */
data class DanmakuPostResult(
  val dmid: String,
  val visible: Boolean,
)
