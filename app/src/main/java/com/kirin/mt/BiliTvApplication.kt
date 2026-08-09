package com.kirin.mt

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kirin.mt.core.app.AppContainer
import com.kirin.mt.core.util.LogCatcherUtil
import com.kirin.mt.core.youtube.newpipe.NewPipeHolder
import org.slf4j.impl.HandroidLoggerAdapter

class BiliTvApplication : Application(), ImageLoaderFactory {
  lateinit var appContainer: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG
    LogCatcherUtil.install(this)
    appContainer = AppContainer(this)
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
  }
}
