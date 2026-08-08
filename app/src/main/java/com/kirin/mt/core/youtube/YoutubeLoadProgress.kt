package com.kirin.mt.core.youtube

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * YouTube 播放加载的步骤提示状态。
 *
 * [YoutubePlaybackResolver] / [YoutubeSabrHarvester] 在加载链路的各阶段写入当前步骤,
 * UI(如 MobilePlayerScreen)收集显示"转圈 + 单行当前步骤文字",让用户感知加载进度。
 *
 * 用全局单例而非穿透回调,是因其从 UI 到 repository → resolver → harvester 的调用链很深,
 * 回调解耦成本高;且 YouTube 单播放器场景下全局状态足够(无并发多实例冲突)。
 * 播放就绪/加载结束时置 null 隐藏提示。
 */
enum class YoutubeLoadStep(val label: String) {
  FetchPlayer("正在获取播放信息"),
  MintToken("正在铸造安全令牌"),
  ResolvePlayer("正在解析播放地址"),
  DecipherN("正在解密签名"),
  HarvestWatch("正在采集播放签名…"),
  BuildSession("正在建立视频会话"),
  Connect("正在连接视频流"),
}

/** 全局 YouTube 加载进度(步骤流,null=无进行中的加载)。 */
object YoutubeLoadProgress {
  val step = MutableStateFlow<YoutubeLoadStep?>(null)

  /** 非阻塞写当前步骤(不抛,避免诊断写入干扰播放主链路)。 */
  fun emit(s: YoutubeLoadStep) {
    runCatching { step.value = s }
  }

  /** 加载完成/失败时清除提示。 */
  fun clear() {
    runCatching { step.value = null }
  }
}
