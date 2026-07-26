package com.kirin.mt.core.player

import android.graphics.Color

data class DanmakuEntry(
  val showAtMs: Long,
  val text: String,
  val mode: DanmakuMode,
  val color: Int = Color.WHITE,
  /** 本地发送的弹幕标记:渲染时用粉色粗描边区别于普通弹幕,便于识别"我发的"。 */
  val isMine: Boolean = false,
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
