package com.kirin.mt.ui.focus

import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import com.kirin.mt.ui.home.TvFocusLogTag

/** 记忆槽上限。超限按插入顺序淘汰最早的一项(网格数量远小于此值,只是防泄漏)。 */
private const val GridFocusMemoryMaxEntries = 8

/**
 * 网格标识。**必须显式传入**,不能复用 `TvVideoGrid` 的 `debugLabel`:后者只有动态/UP主页/频道三处
 * 传了值,推荐/搜索/直播都用默认的 `"video-grid"`,拿它当标识会互相串槽。
 */
internal object GridFocusIds {
  const val Recommend = "grid:recommend"
  const val Search = "grid:search"
  const val Dynamic = "grid:dynamic"
  const val Live = "grid:live"
  const val Space = "grid:space"
  const val Channel = "grid:channel"
}

/**
 * P11-202:网格「身份锚定」焦点记忆。
 *
 * 解决的问题:`TvVideoGrid` 内部那份 `focusedKey` 随网格 dispose 一起没了,而页面级 UiState 里的
 * `focusedVideoKey` 只有 AppShell 那串 bump 计数器被 +1 时才被使用 —— 卡片本身不参与身份记忆,
 * 每接一个新页面就得再穿一条恢复管线。
 *
 * 这份记忆挂在 AppShell(经 [LocalGridFocusMemory] 下发),按网格 id 存「最后聚焦的稳定 key」,
 * 网格冷重建时自己就能把焦点放回原来那张卡。
 *
 * **核心判据是 [Entry.expectReturn],不是「销毁前是否持焦」**:
 *
 *  - 不能用「焦点获得即 arm」——焦点会在**非点击**情况下滑出网格(末行 `DirectionDown` 返回 false
 *    漏给默认遍历、焦点落到 footer 重试按钮、Search 按 Back 回键盘、`onBackKey`),arm 残留就会误恢复。
 *  - 不能用 `onFocusChanged { hasFocus=false }` 做 disarm——`hasFocus=false` 有三种成因无法区分:
 *    ①网格被 dispose(应恢复);②焦点移到侧栏(用户主动,**应拒绝**恢复);③覆盖层/对话框抢焦
 *    (**网格仍组合、仍应恢复**)。②③语义相反,所以事件驱动判据不成立。
 *
 * 不变式:`expectReturn == true` ⇔ 网格里某张卡被点击后跳转出去了,且焦点再没回到任何卡片。
 * 网格被销毁时它必然还是 true ⇒ **不需要在 dispose 时读任何焦点状态**,这是本机制最稳的一环。
 *
 * 普通字段而非快照状态:焦点每次进出都写它,做成快照会让整个网格跟着重组(对齐
 * [com.kirin.mt.ui.home.TvVideoGrid] 里 `GridFocusHolder` 的做法)。
 */
internal class GridFocusMemory {

  /** [consumeIntent] 的返回值:一次待兑现的恢复意图。 */
  class RestoreIntent(
    val stableKey: String,
    val fallbackIndex: Int,
  )

  private class Entry {
    /** `VideoSummary.focusRestoreKey()`;空串 = 这张卡不可锚定(如 IPTV)。 */
    var stableKey: String = ""
    /** 诊断用:置位那一刻的下标。 */
    var fallbackIndex: Int = -1
    var expectReturn: Boolean = false
  }

  private val entries = LinkedHashMap<String, Entry>()

  private fun entry(id: String): Entry {
    entries[id]?.let { return it }
    if (entries.size >= GridFocusMemoryMaxEntries) {
      // 手写淘汰,不继承 LinkedHashMap 覆写 removeEldestEntry(省掉覆写/类型别名的编译坑)。
      // LinkedHashMap 迭代序 = 插入序,所以第一个就是最早的一项。
      entries.keys.firstOrNull()?.let { oldest -> entries.remove(oldest) }
    }
    return Entry().also { entries[id] = it }
  }

  /** 卡片获得焦点:记下身份并**兑现**意图(焦点回到卡片本身就是「回来了」)。 */
  fun onCardFocused(id: String, stableKey: String, index: Int) {
    val target = entry(id)
    target.stableKey = stableKey
    target.fallbackIndex = index
    target.expectReturn = false
  }

  /** 网格自己发起跳转(点卡起播 / 点 owner / 长按菜单)之前调用:置位待兑现意图。 */
  fun onGridNavigate(id: String) {
    entry(id).expectReturn = true
  }

  /**
   * 组合期调用一次:取出并**消费**意图。返回 null 表示本次冷组合不该恢复(没发起过跳转、key 为空、
   * 或已被消费过)。
   *
   * 必须只在组合期调用:冷组合的 `remember` 之后,页面级 [clear] 就再也影响不到这一次恢复了 ——
   * 这正是「点击 → 播放器 → 返回」主路径(key 此时 <= 0)不被切 tab / 刷新误杀的原因。
   */
  fun consumeIntent(id: String): RestoreIntent? {
    val source = entries[id] ?: return null
    val intent = if (source.expectReturn && source.stableKey.isNotBlank()) {
      RestoreIntent(source.stableKey, source.fallbackIndex)
    } else {
      null
    }
    source.expectReturn = false
    return intent
  }

  /**
   * 只看一眼意图,**不消费**(留待接力效果决定去留)。显式恢复管线在身时用这个:管线跑完之前
   * 不知道要不要补刀,过早消费会让「管线失败」时无牌可打。
   *
   * 只在组合期调用;调用方必须在**本次组合内**用 try/finally 兜底清掉(见 TvVideoGrid 接力 effect),
   * 绝不能让它漏到下一次冷组合 —— 那是 P11-172「key 挂死」的镜像 bug。
   */
  fun peekIntent(id: String): RestoreIntent? {
    val source = entries[id] ?: return null
    return if (source.expectReturn && source.stableKey.isNotBlank()) {
      RestoreIntent(source.stableKey, source.fallbackIndex)
    } else {
      null
    }
  }

  /** 页面级上下文变化(切 tab / 手动刷新 / 换搜索词)时丢弃意图,避免下次冷组合误恢复。 */
  fun clear(id: String, reason: String) {
    val target = entries[id] ?: return
    if (target.expectReturn) {
      Log.d(TvFocusLogTag, "grid-memory clear: id=$id reason=$reason key=${target.stableKey}")
    }
    target.expectReturn = false
    target.stableKey = ""
    target.fallbackIndex = -1
  }
}

/**
 * 网格焦点记忆(AppShell 单点 provide)。用 `staticCompositionLocalOf`:值一辈子不变,
 * 不需要读追踪,避免无谓失效。
 */
internal val LocalGridFocusMemory = staticCompositionLocalOf<GridFocusMemory> { GridFocusMemory() }
