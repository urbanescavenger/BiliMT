plugins {
  alias(libs.plugins.android.application) apply false
  // :hongguo-core 需要 library 变体；与 application 同属 AGP，必须在根声明以固定版本，
  // 否则子模块请求 library 时会报 "plugin is already on the classpath with an unknown version"。
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ksp) apply false
}

val externalBuildRoot = providers.gradleProperty("bilitvBuildRoot")
  .orElse("${System.getProperty("user.home")}/.gradle/bilitv-native-build")
  .get()

layout.buildDirectory.set(file("$externalBuildRoot/root"))

subprojects {
  val projectBuildName = path
    .removePrefix(":")
    .replace(':', '-')
    .ifBlank { "root" }
  layout.buildDirectory.set(file("$externalBuildRoot/$projectBuildName"))
}
