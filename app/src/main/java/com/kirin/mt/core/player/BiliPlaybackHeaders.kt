package com.kirin.mt.core.player

import com.kirin.mt.core.network.BiliHeaders

data class BiliPlaybackHeaders(
  val sessData: String?,
  val biliJct: String?,
  /** 部分 PGC 接口（如 /pgc/player/web/playurl）要求 Cookie 同时携带 DedeUserID。 */
  val mid: Long? = null,
  /** 流媒体 CDN 取流用的 Referer。点播默认 www.bilibili.com;直播流 CDN 要 live.bilibili.com。 */
  val referer: String = BiliHeaders.Referer,
  val origin: String = BiliHeaders.Origin,
) {
  val cookie: String?
    get() = BiliHeaders.cookie(sessData, biliJct, dedeUserId = mid)

  fun asMap(includeCookie: Boolean = true): Map<String, String> {
    return buildMap {
      put("User-Agent", BiliHeaders.UserAgent)
      put("Referer", referer)
      put("Origin", origin)
      if (includeCookie) {
        cookie?.let { value -> put("Cookie", value) }
      }
    }
  }
}
