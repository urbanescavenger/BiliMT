package com.kirin.mt.core.youtube

/**
 * YouTube 内容地区设置项。一个变体同时定 [gl](InnerTube `context.client.gl`,国家/内容地区)
 * 与 [hl](`context.client.hl`,界面/返回内容语言),选地区即联动语言,免用户分别配。
 *
 * 默认 [US] —— 与历史写死的 `YoutubeConstants.Gl="US"`/`Hl="en"` 一致,保证首次安装不触发反爬。
 *
 * **不放 CN**:实测 `gl=CN`/`hl=zh-CN` 会触发 YouTube 反爬(搜索返回 backgroundPromoRenderer
 * 「出了点问题」),见 [YoutubeConstants.kt] 的 Hl/Gl 注释。要看中文内容用 [JP]/[HK]/[TW] 节点。
 */
enum class YoutubeContentRegion(val key: String, val label: String, val gl: String, val hl: String) {
  US("us", "美国", "US", "en"),
  JP("jp", "日本", "JP", "ja"),
  HK("hk", "香港", "HK", "zh-Hant"),
  TW("tw", "台湾", "TW", "zh-Hant"),
  KR("kr", "韩国", "KR", "ko"),
  GB("gb", "英国", "GB", "en"),
  DE("de", "德国", "DE", "de");

  companion object {
    /** 按 DataStore 存的 [key] 解码;null/未知回 [US](对齐 [YoutubeDefaultQuality.fromKey] 模式)。 */
    fun fromKey(key: String?): YoutubeContentRegion =
      entries.firstOrNull { it.key == key } ?: US
  }
}