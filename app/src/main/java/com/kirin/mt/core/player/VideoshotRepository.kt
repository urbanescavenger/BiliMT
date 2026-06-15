package com.kirin.mt.core.player

import com.kirin.mt.core.network.BiliApiClient
import com.kirin.mt.core.network.BiliApiEndpoints
import com.kirin.mt.core.network.BiliHeaders
import com.kirin.mt.core.network.int
import com.kirin.mt.core.network.obj
import com.kirin.mt.core.network.requireBiliCodeOk
import com.kirin.mt.core.network.rootObject
import com.kirin.mt.core.network.string
import com.kirin.mt.core.storage.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class VideoshotRepository(
  private val apiClient: BiliApiClient,
  private val sessionStore: SessionStore,
) {
  suspend fun getVideoshot(bvid: String, cid: Long): VideoshotData? {
    if (bvid.isBlank()) return null
    val root = apiClient.getJson(
      url = BiliApiEndpoints.PlayerVideoshot,
      params = buildMap {
        put("bvid", bvid)
        if (cid > 0L) {
          put("cid", cid.toString())
        }
      },
    ).rootObject()
    root.requireBiliCodeOk("player videoshot")

    val data = root.obj("data") ?: return null
    val images = (data["image"] as? JsonArray)
      ?.map { element -> element.asString().fixResourceUrl() }
      ?.filter(String::isNotBlank)
      .orEmpty()
    if (images.isEmpty()) return null

    val pvdataUrl = data.string("pvdata").fixResourceUrl().takeIf(String::isNotBlank)
    val timestamps = pvdataUrl
      ?.let { url -> runCatching { VideoshotData.parsePvdata(apiClient.getBytes(url)) }.getOrDefault(emptyList()) }
      .orEmpty()

    return VideoshotData(
      images = images,
      imgXLen = data.int("img_x_len").takeIf { it > 0 } ?: DefaultVideoshotColumns,
      imgYLen = data.int("img_y_len").takeIf { it > 0 } ?: DefaultVideoshotRows,
      imgXSize = data.int("img_x_size").takeIf { it > 0 } ?: DefaultVideoshotWidth,
      imgYSize = data.int("img_y_size").takeIf { it > 0 } ?: DefaultVideoshotHeight,
      pvdataUrl = pvdataUrl,
      frameTimestamps = timestamps,
    )
  }

  suspend fun getVideoshotImageBytes(url: String): ByteArray? {
    if (url.isBlank()) return null
    val sessData = sessionStore.sessData.first()
    val biliJct = sessionStore.biliJct.first()
    return apiClient.getBytes(
      url = url,
      headers = buildMap {
        put("User-Agent", BiliHeaders.UserAgent)
        put("Referer", BiliHeaders.Referer)
        put("Origin", BiliHeaders.Origin)
        put("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        BiliHeaders.cookie(sessData, biliJct)?.let { cookie -> put("Cookie", cookie) }
      },
    ).takeIf { bytes -> bytes.isNotEmpty() }
  }

  private fun JsonElement.asString(): String {
    return (this as? JsonPrimitive)?.contentOrNull ?: toString().trim('"')
  }

  private fun String.fixResourceUrl(): String {
    return when {
      startsWith("//") -> "https:$this"
      startsWith("http://") -> "https://${removePrefix("http://")}"
      else -> this
    }
  }

  private companion object {
    const val DefaultVideoshotColumns = 10
    const val DefaultVideoshotRows = 10
    const val DefaultVideoshotWidth = 160
    const val DefaultVideoshotHeight = 90
  }
}
