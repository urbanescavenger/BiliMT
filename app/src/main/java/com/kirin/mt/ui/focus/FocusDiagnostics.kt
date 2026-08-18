package com.kirin.mt.ui.focus

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/**
 * 全局焦点诊断日志 tag。真机 logcat / 实时日志目录过滤此 tag,即可看到焦点在
 * 各可聚焦区域(AppShell 根 / 视频网格 / 侧栏 / 覆盖层)间的每次进出轨迹。
 */
internal const val TvFocusDiagTag = "BiliMT:FocusDiag"

/**
 * 焦点诊断总开关。true 时输出区域级焦点日志。诊断期间置 true,定位完可关掉避免刷屏。
 */
internal var tvFocusDiagEnabled: Boolean = true

/**
 * 区域级焦点诊断。给每个可聚焦区域(网格 / 侧栏 / 覆盖层 / AppShell 根)的根节点加一行,
 * 按 hasFocus 的跳变打日志:
 *  - 焦点进入该区域(该区域或其子节点拿到焦点) → `GAINED [label]`
 *  - 焦点离开该区域(该区域及子节点都无焦点)   → `LOST   [label]`
 *
 * 日志序列 = 焦点落点轨迹。丢焦点后最后一条 `GAINED` 就是焦点去向;
 * 若 AppShell 根打出 `LOST [root]` 且其后没有新的 `GAINED`,证明整个焦点树彻底无焦点
 * (最严重的丢失形态,区别于焦点被别处抢占)。
 *
 * 注意:焦点在区域内部移动(卡片间、菜单项间)不触发 hasFocus 跳变,日志安静;
 * 只有跨区域跳转/丢失时才打,因此不会刷屏,但能完整还原区域级焦点移动。
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.focusDiag(label: String): Modifier = composed {
  var hadFocus by remember { mutableStateOf(false) }
  onFocusChanged { state ->
    if (!tvFocusDiagEnabled) return@onFocusChanged
    val nowFocused = state.hasFocus
    if (nowFocused != hadFocus) {
      hadFocus = nowFocused
      Log.d(TvFocusDiagTag, if (nowFocused) "GAINED [$label]" else "LOST   [$label]")
    }
  }
}
