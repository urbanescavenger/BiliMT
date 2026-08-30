package com.kirin.mt

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kirin.mt.core.app.AppContainer
import com.kirin.mt.core.app.AppInfo
import com.kirin.mt.core.player.IptvCleartextPlatform
import com.kirin.mt.core.util.FirebaseLogSender
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.core.youtube.newpipe.NewPipeHolder
import okhttp3.internal.platform.Platform
import org.slf4j.impl.HandroidLoggerAdapter

class BiliTvApplication : Application(), ImageLoaderFactory {
  lateinit var appContainer: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG
    // 安装新 APK 后启动:比对上/上次 versionCode,不一致(更新了)则清空实时日志,
    // 让每版真机日志从干净状态开始。手动/崩溃日志保留。须在 install() 之前执行,
    // 之后 startLiveLogging() 会重建空文件并写全新日志头。
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val lastCode = prefs.getLong(KEY_LAST_VERSION_CODE, -1L)
    val curCode = AppInfo(this).current().versionCode
    if (lastCode != -1L && curCode != lastCode) {
      LogCatcherUtil.clearLiveLog(this)
    }
    prefs.edit().putLong(KEY_LAST_VERSION_CODE, curCode).apply()
    LogCatcherUtil.install(this)
    // Crashlytics 装好后设 custom keys(版本/机型/系统),JSON 未就位时内部 runCatching 静默跳过。
    FirebaseLogSender.install(this)
    // 动态放行 IPTV 源的明文 HTTP(http:// 流):自定义 Platform 只对 IptvCleartextHosts
    // 里注册的 host 放行明文,其余委托原始 AndroidPlatform(保留系统 NetworkSecurityPolicy)。
    // 必须在首个 OkHttp 请求前设置。resetForTests 是 OkHttp 4.12 唯一公开的设置 Platform 单例入口。
    Platform.resetForTests(IptvCleartextPlatform(Platform.get()))
    appContainer = AppContainer(this)
    // 订阅设置流同步「崩溃日志自动上报」开关到 Crashlytics 采集开关(默认关)
    FirebaseLogSender.bindAutoReport(appContainer.appSettingsStore)
    // path C:初始化 NewPipeExtractor fork(YouTube 服务 + PoTokenProvider)。必须在任何
    // StreamInfo.getInfo() 调用前完成(YoutubePlaybackResolver.resolve 内会调)。
    NewPipeHolder.init(appContainer.youtubeHttpClient, appContainer.biliTvPoTokenProvider)
    // 预热 api.bilibili.com 连接池,首开主页/播放省掉冷建连。fire-and-forget。
    appContainer.warmupApiConnection()
  }

  override fun newImageLoader(): ImageLoader {
    return ImageLoader.Builder(this)
      .memoryCache {
        MemoryCache.Builder(this)
          .maxSizePercent(ImageMemoryCachePercent)
          .build()
      }
      .diskCache {
        DiskCache.Builder()
          .directory(cacheDir.resolve(ImageDiskCacheDirectory))
          .maxSizeBytes(ImageDiskCacheMaxBytes)
          .build()
      }
      .crossfade(false)
      .build()
  }

  private companion object {
    const val ImageMemoryCachePercent = 0.20
    const val ImageDiskCacheDirectory = "image_cache"
    const val ImageDiskCacheMaxBytes = 128L * 1024L * 1024L
    const val PREFS_NAME = "app_meta"
    const val KEY_LAST_VERSION_CODE = "last_version_code"
  }
}
