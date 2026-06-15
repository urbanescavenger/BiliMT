package com.kirin.mt.core.update

import android.os.Build
import com.kirin.mt.core.network.BiliApiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class UpdateRepository(
  private val apiClient: BiliApiClient,
  private val repoOwner: String,
  private val repoName: String,
) {
  suspend fun checkLatest(): UpdateInfo {
    val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
    val element = apiClient.getJsonWithHeaders(
      url = url,
      headers = mapOf(
        "Accept" to "application/vnd.github+json",
        "User-Agent" to "BiliMT-Android",
      ),
    )
    val obj = element.jsonObject
    val tagName = obj.stringOrNull("tag_name").orEmpty()
    val releaseUrl = obj.stringOrNull("html_url").orEmpty()
    val releaseNotes = obj.stringOrNull("body").orEmpty()
    val (versionName, versionCode) = parseTagVersion(tagName)
    val assets = obj["assets"]?.jsonArray?.toAssets().orEmpty()
    val matchingAsset = pickAssetForDevice(assets)
    return UpdateInfo(
      tagName = tagName,
      versionName = versionName,
      versionCode = versionCode,
      releaseUrl = releaseUrl,
      releaseNotes = releaseNotes,
      assets = assets,
      matchingAsset = matchingAsset,
    )
  }

  private fun pickAssetForDevice(assets: List<UpdateAsset>): UpdateAsset? {
    if (assets.isEmpty()) return null
    val supported = Build.SUPPORTED_ABIS.toSet()
    val abiPriority = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    for (abi in abiPriority) {
      if (abi in supported) {
        assets.firstOrNull { asset ->
          asset.contentType == APK_CONTENT_TYPE && asset.name.contains(abi, ignoreCase = true)
        }?.let { return it }
      }
    }
    return assets.firstOrNull { it.contentType == APK_CONTENT_TYPE }
      ?: assets.firstOrNull()
  }

  private fun JsonArray.toAssets(): List<UpdateAsset> = mapNotNull { element ->
    val o = element as? JsonObject ?: return@mapNotNull null
    val name = o.stringOrNull("name") ?: return@mapNotNull null
    val url = o.stringOrNull("browser_download_url") ?: return@mapNotNull null
    UpdateAsset(
      name = name,
      size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
      contentType = o.stringOrNull("content_type").orEmpty(),
      downloadUrl = url,
    )
  }

  private fun parseTagVersion(tag: String): Pair<String, Long> {
    val cleaned = tag.trim().removePrefix("v").removePrefix("V")
    val match = VERSION_REGEX.matchEntire(cleaned) ?: return cleaned to 0L
    val (major, minor, patch) = match.destructured
    val name = "$major.$minor.$patch"
    val code = major.toLongOrNull()?.coerceAtLeast(0L)?.times(10_000L) ?: 0L
    val code2 = code + (minor.toLongOrNull()?.coerceAtLeast(0L) ?: 0L).times(100L)
    val code3 = code2 + (patch.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
    return name to code3
  }

  private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

  private companion object {
    private const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"
    private val VERSION_REGEX = Regex("""(\d+)\.(\d+)\.(\d+)""")
  }
}
