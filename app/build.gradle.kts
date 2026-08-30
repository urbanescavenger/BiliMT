plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  // Firebase 两个插件仅声明(apply false)进 classpath,真正 apply 在下方按
  // google-services.json 是否存在条件触发——JSON 没放进来时构建/CI 行为完全不变。
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.firebase.crashlytics) apply false
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

// CI 的 debug 构建传 -PbilitvDebugRunNumber=$GITHUB_RUN_NUMBER,让 debug 版本 versionCode
// 随构建号递增。否则每次 debug 云编译恒为 1000000(computeVersionCode("dev") 回退值),
// 与已装旧 debug 包 versionCode 相同,Android 拒绝覆盖安装(同一 applicationId 内新包
// versionCode 必须严格 > 已装)。release 不传此属性,为 0,不受影响。
val debugRunNumber = providers.gradleProperty("bilitvDebugRunNumber").orNull?.toIntOrNull() ?: 0

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
    versionCode = bilitvVersionCode + debugRunNumber
    versionName = if (debugRunNumber > 0) "$bilitvVersionName.r$debugRunNumber" else bilitvVersionName

    ndk {
      abiFilters.clear()
      abiFilters += targetAbi?.let(::listOf) ?: supportedAbis.toList()
    }

  }

  // 固定签名 key:CI 传 key.store 等属性时用 release keystore 给 release 和 debug 两个变体
  // 签名,确保 CI 每次产物签名一致、可覆盖升级。debug 与 release 包名不同(.debug 后缀),
  // 不会互相覆盖,各自独立升级链。本地无 key.store 属性时两者都回退默认 debug 签名。
  signingConfigs {
    if (project.hasProperty("key.store")) {
      create("release") {
        storeFile = file(project.property("key.store") as String)
        storePassword = project.property("key.store.password") as String
        keyAlias = project.property("key.alias") as String
        keyPassword = project.property("key.key.password") as String
      }
    }
  }

  buildTypes {
    debug {
      // 给 debug 变体独立的 applicationId 后缀,使其与 release (com.kirin.mt) 在系统层面
      // 完全分离,可在已安装 release 版本的设备上并存安装(签名不同也不会冲突覆盖)。
      // 所有依赖 applicationId 的地方(FileProvider authority、AppInfo.packageName 等)
      // 均通过 ${applicationId} 占位符或 context.packageName 动态获取,会自动跟随此后缀。
      applicationIdSuffix = ".debug"
      // CI 用固定 release keystore 签 debug,避免每次全新 runner 临时生成随机 debug.keystore
      // 导致跨次签名不一致、Android 拒绝覆盖安装。本地无 key.store 时回退默认 debug 签名。
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
      isMinifyEnabled = false
      isShrinkResources = false
    }
    release {
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    localeFilters += listOf("zh", "zh-rHK", "zh-rTW", "en", "es", "pt")
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
  // Crashlytics 经 firebase-bom 版本仲裁;无 google-services.json 时 FirebaseApp 不会初始化,
  // SDK 只是进包不工作,所有调用点都有 runCatching 兜底。
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.crashlytics)

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
  implementation(libs.media3.exoplayer.hls)
  implementation(libs.media3.session)
  implementation(libs.media3.ui)
  implementation(libs.newpipeextractor)
  implementation(libs.okhttp)
  implementation(libs.okhttp.brotli)
  implementation(libs.opencc4j)
  implementation(libs.slf4j.api)
  implementation(libs.slf4j.android.mvysny)
  implementation(libs.tv.material)
  implementation(libs.zxing.core)
  implementation(libs.room.runtime)
  ksp(libs.room.compiler)

  debugImplementation(libs.compose.ui.tooling)
}

ksp {
  arg("room.generateKotlin", "true")
}

// Firebase 按需启用:google-services / crashlytics 插件只在 app/google-services.json 存在时
// 才 apply——JSON 没放进来时构建与集成前完全一致(CI 当前阶段),JSON 放入后自动激活。
if (file("google-services.json").exists()) {
  apply(plugin = "com.google.gms.google-services")
  apply(plugin = "com.google.firebase.crashlytics")
}

// Firebase 按需启用:google-services / crashlytics 插件只在 JSON 就位时 apply。
// 没有 JSON 时整个构建与集成前完全一致(CI 当前阶段),JSON 放入 app/ 目录后自动激活。
if (file("google-services.json").exists()) {
  apply(plugin = "com.google.gms.google-services")
  apply(plugin = "com.google.firebase.crashlytics")
}
