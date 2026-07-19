plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

val supportedAbis = setOf("armeabi-v7a", "arm64-v8a")
val targetAbi = providers.gradleProperty("targetAbi").orNull?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Compute versionName / versionCode from an optional Gradle property.
 *
 * For release builds the CI passes the Git tag, e.g.
 *   -PbilitvVersionName=v1.0.5-alpha.11
 * and we derive versionName / versionCode from the semantic version.
 *
 * Local / debug builds use a fallback "dev" version.
 */
val bilitvVersionName = providers.gradleProperty("bilitvVersionName")
  .orNull
  ?.removePrefix("v")
  ?: "dev"

fun computeVersionCode(versionName: String): Int {
  val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([a-zA-Z]+)\.(\d+))?""")
    .matchEntire(versionName.removePrefix("v").removePrefix("V"))
    ?: return 1000000
  val (major, minor, patch, label, index) = match.destructured
  val m = major.toIntOrNull() ?: 0
  val n = minor.toIntOrNull() ?: 0
  val p = patch.toIntOrNull() ?: 0
  val labelOrder = when (label?.lowercase()) {
    "alpha" -> 1
    "beta" -> 2
    "rc" -> 3
    else -> 0
  }
  val pre = index.toIntOrNull() ?: 0
  // minor 权重 1e5 > patch 最大 99*1e3,避免 patch≥10 时 minor bump 反而降级
  // (如 v1.0.13=1,013,000 < v1.1.0=1,100,000)。
  return m * 1000000 + n * 100000 + p * 1000 + labelOrder * 100 + pre
}

val bilitvVersionCode = computeVersionCode(bilitvVersionName)

require(targetAbi == null || targetAbi in supportedAbis) {
  "Unsupported targetAbi=$targetAbi. Supported values: ${supportedAbis.joinToString()}"
}

android {
  namespace = "com.kirin.mt"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.kirin.mt"
    minSdk = 23
    targetSdk = 36
    versionCode = bilitvVersionCode
    versionName = bilitvVersionName

    ndk {
      abiFilters.clear()
      abiFilters += targetAbi?.let(::listOf) ?: supportedAbis.toList()
    }

  }

  buildTypes {
    debug {
      isMinifyEnabled = false
      isShrinkResources = false
    }
    release {
      signingConfig = if (project.hasProperty("key.store")) {
        signingConfigs.create("release") {
          storeFile = file(project.property("key.store") as String)
          storePassword = project.property("key.store.password") as String
          keyAlias = project.property("key.alias") as String
          keyPassword = project.property("key.key.password") as String
        }
      } else {
        signingConfigs.getByName("debug")
      }
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  androidResources {
    localeFilters += listOf("zh", "zh-rHK", "zh-rTW")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources {
      excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/**/LICENSE.txt",
      )
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(platform(libs.compose.bom))

  implementation(libs.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.backdrop)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.adaptive.navigation.suite)
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.coil.compose)
  implementation(libs.coroutines.android)
  implementation(libs.danmaku.render.engine)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.logging)
  implementation(libs.media3.datasource.okhttp)
  implementation(libs.media3.exoplayer)
  implementation(libs.media3.exoplayer.dash)
  implementation(libs.media3.ui)
  implementation(libs.okhttp)
  implementation(libs.okhttp.brotli)
  implementation(libs.opencc4j)
  implementation(libs.slf4j.api)
  implementation(libs.slf4j.android.mvysny)
  implementation(libs.tv.material)
  implementation(libs.zxing.core)

  debugImplementation(libs.compose.ui.tooling)
}
