package com.kirin.mt.core.util

import java.math.BigInteger

/**
 * avid ↔ BV 号转换，移植自 BV `bili-api` 的 `AvBvConverter`（base58 + XOR 算法）。
 * 仅用 `av2bv`：UGC 分区轮播 banner 的 `url` 多为 `bilibili://video/{aid}`，需转成 bvid 才能起播。
 */
object BvId {
  private val XOR_CODE = 23442827791579L.toBigInteger()
  private val MAX_AID = BigInteger.ONE.shiftLeft(51)
  private val BASE = 58L.toBigInteger()
  private const val DATA = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

  fun av2bv(aid: Long): String {
    val bytes = "BV1000000000".toCharArray()
    var bvIndex = bytes.size - 1
    var tmp = MAX_AID.or(aid.toBigInteger()).xor(XOR_CODE)
    while (tmp > BigInteger.ZERO) {
      bytes[bvIndex] = DATA[(tmp.mod(BASE)).toInt()]
      tmp = tmp.div(BASE)
      bvIndex--
    }
    swap(bytes, 3, 9)
    swap(bytes, 4, 7)
    return String(bytes)
  }

  /**
   * 从 UGC 分区轮播 banner 的 `url` 解析 bvid：
   * - `bilibili://video/{aid}` → avid 转 bvid；
   * - `https://www.bilibili.com/video/BVxxx[/...]` → 直接取 BV 段；
   * 非视频 url 返回 null（对齐 BV `UrlUtil.isVideoUrl`，丢弃番剧/活动链接）。
   */
  fun bvidFromUrl(url: String): String? {
    return when {
      url.startsWith("bilibili://video/") ->
        url.removePrefix("bilibili://video/").substringBefore('/').toLongOrNull()?.let { av2bv(it) }
      url.startsWith("https://www.bilibili.com/video/") -> {
        val id = url.removePrefix("https://www.bilibili.com/video/").substringBefore('/')
        if (id.startsWith("BV")) id else null
      }
      else -> null
    }
  }

  private fun swap(arr: CharArray, i: Int, j: Int) {
    val tmp = arr[i]
    arr[i] = arr[j]
    arr[j] = tmp
  }
}