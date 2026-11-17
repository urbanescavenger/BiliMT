pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    google()
    maven("https://artifact.bytedance.com/repository/releases/")
    // NewPipeExtractor fork (libre-tube) 仅发布在 jitpack
    maven("https://jitpack.io")
  }
}

rootProject.name = "BiliMT"
include(":app")
// GPL-3.0-only 红果 App 端 core(移植自 N3urda/hongguoTV),与 MIT 应用代码隔离成独立模块。
include(":hongguo-core")
