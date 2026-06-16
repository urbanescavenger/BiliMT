package com.kirin.mt.core.update

data class UpdateAsset(
  val name: String,
  val size: Long,
  val contentType: String,
  val downloadUrl: String,
)

data class UpdateInfo(
  val tagName: String,
  val versionName: String,
  val versionCode: Long,
  val releaseUrl: String,
  val releaseNotes: String,
  val assets: List<UpdateAsset>,
  val matchingAsset: UpdateAsset?,
) {
  val isNewer: Boolean get() = versionCode > 0
}
