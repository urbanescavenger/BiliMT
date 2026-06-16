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
    val m = major.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val n = minor.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val p = patch.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val base = m.times(1_000_000L) + n.times(10_000L) + p.times(1_000L)
    val pre = SEMVER_PRE_REGEX.find(cleaned)?.let { result ->
      val label = result.groupValues[1]
      val index = result.groupValues[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
      labelToOrder(label) * 100L + index
    } ?: 0L
    return name to (base + pre)
  }

  private fun labelToOrder(label: String): Long = when (label.lowercase()) {
    "alpha" -> 1L
    "beta" -> 2L
    "rc" -> 3L
    else -> 99L
  }

  private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

  private companion object {
    private const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"
    private val VERSION_REGEX = Regex("""(\d+)\.(\d+)\.(\d+)""")
    private val SEMVER_PRE_REGEX = Regex("""-([a-zA-Z]+)\.(\d+)""")
  }
}
