package com.kirin.mt.core.player

import com.kirin.mt.core.network.BiliHeaders

data class BiliPlaybackHeaders(
  val sessData: String?,
  val biliJct: String?,
  /** 部分 PGC 接口（如 /pgc/player/web/playurl）要求 Cookie 同时携带 DedeUserID。 */
  val mid: Long? = null,
) {
  val cookie: String?
    get() = BiliHeaders.cookie(sessData, biliJct, dedeUserId = mid)

  fun asMap(includeCookie: Boolean = true): Map<String, String> {
    return buildMap {
      put("User-Agent", BiliHeaders.UserAgent)
      put("Referer", BiliHeaders.Referer)
      put("Origin", BiliHeaders.Origin)
      if (includeCookie) {
        cookie?.let { value -> put("Cookie", value) }
      }
    }
  }
}
