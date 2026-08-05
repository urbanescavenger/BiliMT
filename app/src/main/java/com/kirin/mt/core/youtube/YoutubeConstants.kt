package com.kirin.mt.core.youtube

/**
 * YouTube InnerTube 私有 API 常量。
 *
 * 这是对 FreeTube / youtubei.js（MIT）所调用的 InnerTube 协议的独立 Kotlin 实现——
 * 只复用请求/响应的"形状"，不复用其代码（FreeTube 本体是 AGPL，biliMT 是 MIT，不能并入）。
 *
 * 关键来源（已核对其 GitHub 源码）：
 *  - base URL:    https://www.youtube.com/youtubei/v1/{endpoint}?key={API_KEY}
 *  - API key:     youtubei.js Constants.CLIENTS.WEB.API_KEY（WEB 客户端公开 key）
 *  - client:      WEB 客户端，guest 认证，无需登录/用户 key
 *  - 热门/频道 tab 的 browseId + params（protobuf 参数串）来自 FreeTube local.js
 */
object YoutubeConstants {
  /** 数据接口 base（不含 version，endpoint 以 / 开头拼在后面）。 */
  const val InnerTubeBase = "https://www.youtube.com/youtubei"

  /** WEB 客户端公开 InnerTube API key（youtubei.js 同款）。 */
  const val ApiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

  /** InnerTube API 版本。 */
  const val ApiVersion = "v1"

  /** WEB 客户端版本（youtubei.js Constants.CLIENTS.WEB.VERSION）。 */
  const val ClientVersion = "2.20260623.01.00"

  /** 客户端名（InnerTube client.clientName）。 */
  const val ClientName = "WEB"

  /** X-Youtube-Client-Name 数值 id（WEB=1）。 */
  const val ClientNameId = "1"

  /** 桌面 Chrome UA（FreeTube getRandomUserAgent('desktop') 同源）。 */
  const val UserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

  /** Referer 必须是 youtube，否则 InnerTube 拒收。 */
  const val Referer = "https://www.youtube.com"

  /**
   * 界面语言 / 地区。注意:实测 zh-CN/CN 会触发 YouTube 反爬(搜索返回
   * backgroundPromoRenderer「出了点问题」);en/US 正常返回 videoRenderer。
   * 中文内容少但稳定,先保证能拉取。
   */
  const val Hl = "en"
  const val Gl = "US"

  /** 频道"视频"tab 的 protobuf 参数（YouTube 实际使用的值，去掉会失效）。 */
  const val ChannelVideosParams = "EgZ2aWRlb3PyBgQKAjoA"

  /** 频道"直播"tab 的 protobuf 参数。 */
  const val ChannelLiveParams = "EgdzdHJlYW1z8gYECgJ6AA%3D%3D"

  /**
   * 热门页各子 tab 的 browseId + 参数。
   *
   * 注意:通用 `FEtrending` 已于 2025-03 被 YouTube 废弃(400 invalid argument,
   * /feed/trending 已移除),现仅 topic 热门(gaming/sports/podcasts)可用。
   * 默认用"游戏"热门(实测 200 返回 gridVideoRenderer)。
   */
  val TrendingTabs: Map<String, TrendingTab> = mapOf(
    "游戏" to TrendingTab("UCOpNcN46UbXVtpKMrmU4Abg", "Egh0cmVuZGluZ7gBAJIDAPIGBAoCMgA"),
    "体育" to TrendingTab("UCEgdi0XIXXZ-qJOFPf4JSKw", "EglzcG9ydHN0YWK4AQCSAwDyBgQKAjIA"),
    "播客" to TrendingTab("FEpodcasts_destination", "qgcCCAM%3D"),
  )

  /** 单个热门 tab 的定义。 */
  data class TrendingTab(
    val browseId: String,
    val params: String?,
  )
}
