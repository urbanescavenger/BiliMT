plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

// GPL-3.0-only: 移植自 N3urda/hongguoTV 的 core 模块(见 NOTICE.md / LICENSE)。
// 独立成库以隔离 GPL 代码边界;引用此库的发布物整体按 GPL-3.0-only 分发。
android {
  namespace = "com.hongguotv.core"
  compileSdk = 36

  defaultConfig {
    minSdk = 23
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(libs.okhttp)
  implementation(libs.bouncycastle)
  implementation(libs.jsoup)
}
