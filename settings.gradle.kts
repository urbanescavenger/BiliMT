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
