package com.kirin.mt.core.update

import android.os.Build
import com.kirin.mt.core.network.BiliApiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
  /**
   * 拉取 GitHub Releases 列表，返回当前用户应升级到的目标 release。
   *
   * GitHub `/releases/latest` 只返回非 prerelease，alpha 用户永远收不到更新的 alpha。
   * 改用 `/releases`（含 prerelease）按 versionCode 取最大者：
   * - includePrereleases=false（稳定版/dev 用户）：只在非 prerelease 里挑，避免把 alpha 推给稳定用户。
   * - includePrereleases=true（alpha/beta/rc 用户）：全部 release 里挑 versionCode 最大者，
   *   既能收到更新的 alpha，也能在有更新稳定版时毕业到稳定版。
   */
  suspend fun checkLatest(includePrereleases: Boolean): UpdateInfo {
    val url = "https://api.github.com/repos/$repoOwner/$repoName/releases?per_page=100"
    val element = apiClient.getJsonWithHeaders(
      url = url,
      headers = mapOf(
        "Accept" to "application/vnd.github+json",
        "User-Agent" to "BiliMT-Android",
      ),
    )
    val releases = element.jsonArray
    var best: UpdateInfo? = null
    for (entry in releases) {
      val obj = entry as? JsonObject ?: continue
      if (obj["draft"]?.jsonPrimitive?.booleanOrNull == true) continue
      val isPrerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull == true
      if (!includePrereleases && isPrerelease) continue
      val tagName = obj.stringOrNull("tag_name").orEmpty()
      if (tagName.isBlank()) continue
      val (versionName, versionCode) = parseTagVersion(tagName)
      if (versionCode <= 0L) continue
      if (best != null && versionCode <= best.versionCode) continue
      val releaseUrl = obj.stringOrNull("html_url").orEmpty()
      val releaseNotes = obj.stringOrNull("body").orEmpty()
      val assets = obj["assets"]?.jsonArray?.toAssets().orEmpty()
      val matchingAsset = pickAssetForDevice(assets)
      best = UpdateInfo(
        tagName = tagName,
        versionName = versionName,
        versionCode = versionCode,
        releaseUrl = releaseUrl,
        releaseNotes = releaseNotes,
        assets = assets,
        matchingAsset = matchingAsset,
      )
    }
    return best ?: throw Exception("No eligible releases found")
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
    val (major, minor, patch, label, index) = match.destructured
    val name = if (label.isNullOrEmpty()) {
      "$major.$minor.$patch"
    } else {
      "$major.$minor.$patch-$label.${index ?: "0"}"
    }
    val m = major.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val n = minor.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val p = patch.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    // minor 权重 1e5 须与 build.gradle#computeVersionCode 一致，否则同一 tag 在本地(编译期)
    // 与远端(运行期)算出不同 versionCode，导致 alpha.x 这种 minor>0 的预发布被误判为"已是最新"。
    val base = m.times(1_000_000L) + n.times(100_000L) + p.times(1_000L)
    val labelOrder = labelToOrder(label ?: "")
    val preIndex = index?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val pre = labelOrder * 100L + preIndex
    return name to (base + pre)
  }

  // 与 build.gradle#computeVersionCode 的 when(label?.lowercase()) 完全一致：
  // 稳定版(label 为空)与未知 label 都映射为 0，避免稳定版虚高 99*100=9900 而误报"有更新"。
  private fun labelToOrder(label: String): Long = when (label.lowercase()) {
    "alpha" -> 1L
    "beta" -> 2L
    "rc" -> 3L
    else -> 0L
  }

  private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

  private companion object {
    private const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"
    private val VERSION_REGEX = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([a-zA-Z]+)\.(\d+))?""")
  }
}
