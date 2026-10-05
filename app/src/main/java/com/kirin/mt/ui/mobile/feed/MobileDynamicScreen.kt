package com.kirin.mt.ui.mobile.feed

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kirin.mt.R
import com.kirin.mt.core.model.DynamicImage
import com.kirin.mt.core.model.DynamicKindDraw
import com.kirin.mt.core.model.VideoSummary
import com.kirin.mt.core.model.feedKey
import com.kirin.mt.core.network.VideoRepository
import com.kirin.mt.core.network.mergeByPubdate

import com.kirin.mt.core.youtube.YoutubeChannel
import com.kirin.mt.core.youtube.YoutubeFeedCacheStore
import com.kirin.mt.core.youtube.YoutubeSubscriptionsPage
import com.kirin.mt.ui.mobile.common.PullToRefreshLayout
import com.kirin.mt.ui.mobile.home.MobileVideoCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 关注动态取数口径:只有 `all` 会带回图文(图文占比约 1/3,见 docs/bilibili-dynamic-draw-feasibility.md)。 */
private const val DynamicFeedTypeAll = "all"

/** 与 UserFeedRepository 同一个 tag,方便一次 grep 出「取数 + 渲染列表」两段。 */
private const val LogTag = "BiliDynamicFeed"


private sealed interface DynamicState {
  data object Loading : DynamicState
  data object Empty : DynamicState
  data class Failed(val message: String) : DynamicState
  data class Success(
    val videos: List<VideoSummary>,
    val loadingMore: Boolean,
    val endReached: Boolean,
    /** 本次拉到的 YouTube 关注流:翻页时要用它**重新合并**(边界随 B 站页后移),不能只存合并结果。 */
    val youtubeVideos: List<VideoSummary> = emptyList(),
    /**
     * YouTube 关注流每频道续页 token(首屏拿到)。翻页时与 B 站页**同步推进一页**,否则 YouTube 侧
     * 永远只有首屏那一次快照(P11-186:每频道只取最新 N 条),第二屏起就再也合不出新的 YouTube 项。
     */
    val youtubeContinuation: Map<String, String?>? = null,
  ) : DynamicState
}

/**
 * 移动端动态 tab:关注动态 + offset 分页。复用 VideoRepository.getDynamicFeed、MobileVideoCard
 * (视频/直播)与 MobileDynamicDrawCard(图文)。未登录时显示登录入口。
 *
 * 取数用 `type=all` + `includeDraw=true`:服务端 `type=video` 时根本不会返回图文动态。
 * **列表 key/去重一律用 `VideoSummary.feedKey`(图文没有 bvid,回退 dynId)**,否则 key 冲突。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileDynamicScreen(
  videoRepository: VideoRepository,
  youtubeFeedCacheStore: YoutubeFeedCacheStore,
  isLoggedIn: Boolean,
  dynamicRefreshKey: Int = 0,
  youtubeChannels: List<YoutubeChannel>,
  /** store 是否已发出频道首值(含空)。false 时首帧组合频道尚未确认,先不刷新,等确认后 B 站 + YouTube 一起拉。 */
  channelsReady: Boolean = true,
  onVideoSelected: (VideoSummary) -> Unit,
  onOpenOwner: (VideoSummary) -> Unit,
  onLogin: () -> Unit,
  modifier: Modifier = Modifier,
  onLongPress: ((VideoSummary) -> Unit)? = null,
) {
  if (!isLoggedIn) {
    Column(
      modifier = modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.mobile_account_signed_out),
        style = MaterialTheme.typography.titleMedium,
      )
      Button(onClick = onLogin, modifier = Modifier.padding(top = 16.dp)) {
        Text(stringResource(R.string.mobile_login))
      }
    }
    return
  }

  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<DynamicState>(DynamicState.Loading) }
  var nextOffset by remember { mutableStateOf("") }
  // YouTube 关注拉取超时/失败提示:true 时网格顶部显示提示条(区别于静默空)。
  var youtubeTimeoutNotice by remember { mutableStateOf(false) }
  // 请求去重:已有拉取在进行则跳过,避免重复点击底栏/下拉并发重拉全量频道。
  var feedJob by remember { mutableStateOf<Job?>(null) }
  // 保留旧数据刷新时驱动下拉指示器(区别于初始 Loading 的网格内 spinner)。
  var isRefreshing by remember { mutableStateOf(false) }
  // 大图查看器:非空时以全屏 Dialog 盖在最上层(点图文图片打开)。
  var viewerTarget by remember { mutableStateOf<DynamicViewerTarget?>(null) }
  // 动态详情页:非空时全屏盖住(点图文卡正文/计数区打开)。
  var detailVideo by remember { mutableStateOf<VideoSummary?>(null) }

  /**
   * 拉一页 YouTube 关注流:`previous == null` 即首屏(每频道最新若干条),否则用留存 token 拉更早一页。
   * 失败返回 null(首屏额外置超时提示 + 用缓存快照兜底;续页静默保留现有列表,不打断本次合并)。
   * 走 [VideoRepository.youtubeHomeFeedPage] 而不是 youtubeSubscriptionsFeed:只有前者回传每频道
   * 续页 token,翻页才能继续推进 YouTube 侧。
   */
  suspend fun fetchYoutubePage(previous: Map<String, String?>?): YoutubeSubscriptionsPage? {
    val currentIds = youtubeChannels.map { it.channelId }
    return try {
      val page = videoRepository.youtubeHomeFeedPage(previousContinuation = previous)
      if (page.videos.isNotEmpty()) {
        youtubeTimeoutNotice = false
        // 只有首屏需要写缓存(续页是更早的页,写进去会把缓存快照变成残缺集)。
        if (previous == null) youtubeFeedCacheStore.write(currentIds, page.videos)
      }
      page
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (previous != null) return null
      youtubeTimeoutNotice = true
      val cached = youtubeFeedCacheStore.read()
      if (cached != null && cached.channelIds == currentIds && cached.videos.isNotEmpty()) {
        // 缓存兜底:空 continuation map ⇒ endReached,不会拿旧快照去续页。
        YoutubeSubscriptionsPage(videos = cached.videos, perChannelContinuation = emptyMap())
      } else {
        null
      }
    }
  }

  suspend fun loadFirstBody() {
    // 保留旧数据后台刷新:有旧 Success 时不闪 Loading,由 isRefreshing 驱动下拉指示器;
    // 无旧数据(首次进入)才显示网格内 Loading spinner。
    val prev = state as? DynamicState.Success
    if (prev == null) {
      state = DynamicState.Loading
    } else {
      isRefreshing = true
    }
    nextOffset = ""
    youtubeTimeoutNotice = false
    try {
      // 1. 拉 B 站动态
      var biliEndReached = true
      var biliError: String? = null
      val biliVideos = try {
        val page = videoRepository.getDynamicFeed(type = DynamicFeedTypeAll, includeDraw = true)
        nextOffset = page.offset
        biliEndReached = !page.hasMore
        page.videos
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        biliError = e.message.orEmpty().ifBlank { "加载失败" }
        null
      }

      // 2. 拉 YouTube 关注首屏(全量,等查完再一次性合并,不再分批增量叠加);记下每频道续页 token。
      // 拉取失败(youtubePage==null)时沿用上一次的 YouTube 集,不让卡片在刷新瞬间凭空消失。
      val youtubePage = if (youtubeChannels.isNotEmpty()) {
        fetchYoutubePage(previous = null)
      } else {
        null
      }
      val youtubeVideos = youtubePage?.videos ?: prev?.youtubeVideos.orEmpty()

      // 3. 合并一次
      val merged = mergeByPubdate(biliVideos.orEmpty(), youtubeVideos)
      state = when {
        merged.isEmpty() && biliError != null -> prev ?: DynamicState.Failed(biliError)
        merged.isEmpty() -> prev ?: DynamicState.Empty
        else -> DynamicState.Success(
          videos = merged,
          loadingMore = false,
          endReached = biliEndReached && youtubePage?.endReached != false,
          youtubeVideos = youtubeVideos,
          youtubeContinuation = youtubePage?.perChannelContinuation ?: prev?.youtubeContinuation,
        )
      }
    } finally {
      isRefreshing = false
    }
  }

  /** 去重入口:feedJob 活跃则跳过,否则启动一次完整刷新。 */
  fun refreshFeed() {
    if (feedJob?.isActive == true) return
    feedJob = scope.launch { loadFirstBody() }
  }

  // 首次进入 + 每次点击底栏"动态"tab(dynamicRefreshKey 自增)都刷新 B 站 + YouTube 关注。
  // key 加稳定 channelId 列表 + channelsReady:首帧组合时频道未确认(channelsReady=false)先不拉,
  // 等 store 发出首值后 channelsReady 置 true → key 变化 → 重跑 loadFirstBody,把 B 站 + YouTube
  // 一次合并(不再先空频道只拉 B 站、频道到位再重拉;头像回填不改 ID,不触发重启)。
  val youtubeChannelIds = youtubeChannels.map { it.channelId }
  LaunchedEffect(isLoggedIn, dynamicRefreshKey, youtubeChannelIds, channelsReady) {
    if (!isLoggedIn || !channelsReady) return@LaunchedEffect
    refreshFeed()
  }

  fun reloadFirst() {
    refreshFeed()
  }

  val gridState = rememberLazyGridState()

  // 列表侧打点(P11-181):真机报告「看不到图文」时用来区分「图文卡在列表里但没翻到」与
  // 「压根没进渲染列表」——记下图文在第几张、正文长度、图片数与发布时间。
  LaunchedEffect(state) {
    val success = state as? DynamicState.Success ?: return@LaunchedEffect
    val draws = success.videos.withIndex().filter { it.value.dynamicKind == DynamicKindDraw }
    Log.i(
      LogTag,
      "dynamic list total=${success.videos.size} draws=${draws.size} indexes=${draws.map { it.index }}",
    )
    draws.forEach { (index, video) ->
      Log.i(
        LogTag,
        "dynamic draw at index=$index textLen=${video.dynamicText.length} " +
          "images=${video.dynamicImages.size} pubdate=${video.pubdate} hasMore=${video.dynamicTextHasMore}",
      )
    }
  }

  fun loadNextPage() {
    val current = state as? DynamicState.Success ?: return
    if (current.loadingMore || current.endReached) return
    val offsetToLoad = nextOffset
    state = current.copy(loadingMore = true)
    scope.launch {
      val next = try {
        val page = videoRepository.getDynamicFeed(offset = offsetToLoad, type = DynamicFeedTypeAll, includeDraw = true)
        nextOffset = page.offset
        // YouTube 关注流同步推进一页:否则第二屏起只有 B 站条目(YouTube 侧只有首屏快照,合不出新项)。
        // 失败静默(null)保留现有 YouTube 集,不拖累本次 B 站页落地。
        val ytContinuation = current.youtubeContinuation
        val ytPage = if (youtubeChannels.isNotEmpty() && ytContinuation?.values?.any { it != null } == true) {
          fetchYoutubePage(previous = ytContinuation)
        } else {
          null
        }
        val youtubeVideos = if (ytPage != null) {
          (current.youtubeVideos + ytPage.videos).distinctBy { it.feedKey }
        } else {
          current.youtubeVideos
        }
        // 只把「B 站条目」累加,再用 YouTube 集**整体重合并** —— B 站拉到更早的页后,
        // 边界随之后移,更旧的 YouTube 项这时才进入列表(P11-186)。
        val biliItems = current.videos.filter { it.dynId.isNotBlank() } + page.videos
        val merged = mergeByPubdate(biliItems, youtubeVideos)
        val ytContinuationNext = ytPage?.perChannelContinuation ?: ytContinuation
        val ytHasMore = ytContinuationNext?.values?.any { it != null } == true
        current.copy(
          videos = merged,
          loadingMore = false,
          // 两侧都到底才算到底:YouTube 侧还能续页时,列表仍可继续向下长。
          endReached = (!page.hasMore && !ytHasMore) || merged.size == current.videos.size,
          youtubeVideos = youtubeVideos,
          youtubeContinuation = ytContinuationNext,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        current.copy(loadingMore = false)
      }
      state = next
    }
  }

  LaunchedEffect(isLoggedIn) {
    snapshotFlow {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      val total = gridState.layoutInfo.totalItemsCount
      total > 0 && last >= total - 6
    }
      .distinctUntilChanged()
      .collect { nearEnd -> if (nearEnd) loadNextPage() }
  }

  Box(modifier = modifier.fillMaxSize()) {
    viewerTarget?.let { target ->
      DynamicImageViewer(
        images = target.images,
        initialIndex = target.index,
        onDismiss = { viewerTarget = null },
      )
    }
    detailVideo?.let { detail ->
      MobileDynamicDetailScreen(
        video = detail,
        videoRepository = videoRepository,
        onDismiss = { detailVideo = null },
      )
    }
    // PullToRefreshLayout 提到 when 外,isRefreshing 顶层求值真值;刷新时 state→Loading 不再卸载容器,
    // 列表滚动位置与指示器保留,各状态内联为 grid item(照 MobileUserSpaceScreen 范式)。
    PullToRefreshLayout(
      isRefreshing = isRefreshing,
      onRefresh = { reloadFirst() },
      modifier = Modifier.fillMaxSize(),
    ) {
      LazyVerticalGrid(
        // 动态 feed 单列:卡片占满整行,配 feedLayout 的 B 站动态样式(顶行作者块+缩略图+标题)。
        columns = GridCells.Fixed(1),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        when (val s = state) {
          DynamicState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
          }
          DynamicState.Empty -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.mobile_dynamic_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
          is DynamicState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = s.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
              )
            }
          }
          is DynamicState.Success -> {
            if (youtubeTimeoutNotice) {
              item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                  text = "YouTube 关注加载超时",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
              }
            }
            items(s.videos, key = { it.feedKey }) { video ->
              // 图文动态不可播放:走图文卡(本轮整卡与图片都不响应点击,详情页/大图在后续切片接)。
              if (video.dynamicKind == DynamicKindDraw) {
                MobileDynamicDrawCard(
                  video = video,
                  onOpenOwner = onOpenOwner,
                  onImageClick = { images, index -> viewerTarget = DynamicViewerTarget(images, index) },
                  onOpenDetail = { detailVideo = video },
                )
                return@items
              }
              MobileVideoCard(
                video = video,
                onClick = onVideoSelected,
                onOpenOwner = onOpenOwner,
                onLongPress = onLongPress,
                showYoutubeBorder = true,
                feedLayout = true,
              )
            }
            if (s.loadingMore) {
              item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                  modifier = Modifier.fillMaxWidth().padding(16.dp),
                  contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
              }
            }
          }
        }
      }
    }
  }
}