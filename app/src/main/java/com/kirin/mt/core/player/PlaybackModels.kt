package com.kirin.mt.core.player

import com.kirin.mt.core.model.SourceBili
import com.kirin.mt.core.model.SourceIptv
import com.kirin.mt.core.model.SourceTvbox
import com.kirin.mt.core.model.SourceYoutube
import com.kirin.mt.core.model.TvboxLine
import com.kirin.mt.core.youtube.InnerTubeClient

data class PlaybackRequest(
  val bvid: String,
  val cid: Long,
  val title: String,
  val startPositionMs: Long = 0L,
  val aid: Long = 0L,
  val ownerName: String = "",
  val ownerFace: String = "",
  val ownerMid: Long = 0L,
  /** 视频封面 URL,用于后台播放 MediaStyle 通知封面。 */
  val coverUrl: String = "",
  val viewCount: Int = 0,
  val danmakuCount: Int = 0,
  val pubdate: Long = 0L,
  val preferredQualityId: Int? = null,
  /** YouTube 音轨切换:用户选中的音轨 id(audioTrack.id,如 "en.4")。null=默认(优先原声轨)。 */
  val preferredAudioTrackId: String? = null,
  val forceStartPosition: Boolean = false,
  val historyPage: Int = 0,
  val advanceToNextHistoryEpisode: Boolean = false,
  /** PGC 剧集 id；>0 表示这是 PGC 播放请求，走 /pgc/player/web/playurl。 */
  val epId: Long = 0L,
  /** PGC 季 id；>0 时 getVideoMetadata 走 /pgc/view/web/season 取分集列表。 */
  val seasonId: Long = 0L,
  /** PGC 季副类型（season.type：1番剧/2电影/3纪录/4国创/5电视剧/7综艺），heartbeat type=4 时作 sub_type。 */
  val subType: Int = 0,
  /** 直播间 id;>0 表示这是直播播放请求,走 xlive/web-room/v2/index/getRoomPlayInfo,跳过 DASH playurl。 */
  val liveRoomId: Long = 0L,
  /** 内容来源：[SourceBili]（默认）/ [SourceYoutube] / [SourceIptv]。YouTube 请求 bvid 字段承载 videoId。 */
  val source: String = SourceBili,
  /** IPTV 频道镜像源/TVBox 跨站线路 URL 列表（仅 [SourceIptv]/[SourceTvbox] 请求填充）。播放器里按 selectedQn 当源索引切换。 */
  val iptvUrls: List<String> = emptyList(),
  /** TVBox 线路表（仅 [SourceTvbox] 请求填充）：每线路=一个采集站+完整分集。清晰度面板=线路列表([preferredQualityId]=线路索引)。 */
  val tvboxLines: List<TvboxLine> = emptyList(),
  /** TVBox 当前选集索引（当前线路内；选集面板/自动连播切集用）。 */
  val tvboxEpisodeIndex: Int = 0,
  /** YouTube 频道 id（UC 开头）。仅 [SourceYoutube] 请求填充，用于播放历史进频道主页；B 站为空串。 */
  val channelId: String = "",
  /**
   * YouTube InnerTube /player 客户端偏好。null=默认 WEB(移动端行为)；
   * TV 端调用点传 [InnerTubeClient.Client.TVHTML5] 试验 TV 专用 client(失败自动回退 WEB)。
   * 仅 [SourceYoutube] 请求使用。参见 [YoutubePlaybackResolver.resolve] clients 列表构建。
   */
  val preferredYoutubeClient: InnerTubeClient.Client? = null,
) {
  val isPgc: Boolean
    get() = epId > 0L || seasonId > 0L

  val isLive: Boolean
    get() = liveRoomId > 0L

  /** 这是 IPTV/TVBox 直链 m3u8 播放请求：跳过 B 站 getRoomPlayInfo，共用 LivePlayerScreen 直链路径与线路切换。 */
  val isIptv: Boolean
    get() = source == SourceIptv || source == SourceTvbox

  /** 这是 IPTV 直播频道请求（IPTV 专属语义：m3u 台列表/频道面板/「断流即切源」，TVBox 点播不具备）。 */
  val isIptvChannel: Boolean
    get() = source == SourceIptv

  /** 这是 TVBox（影视库）点播请求：MacCMS 采集站直链/懒解析 m3u8,线路=清晰度档,线路内可切集。 */
  val isTvbox: Boolean
    get() = source == SourceTvbox

  /** TVBox 当前线路(线路索引=preferredQualityId);非 TVBox 或无线路表为 null。 */
  val tvboxCurrentLine: TvboxLine?
    get() = if (tvboxLines.isEmpty()) null
    else tvboxLines.getOrNull((preferredQualityId ?: 0).coerceIn(0, tvboxLines.lastIndex))

  /** 这是 YouTube 播放请求：走 InnerTube /player 解析 progressive 直链，跳过 B 站 DASH playurl。 */
  val isYoutube: Boolean
    get() = source == SourceYoutube
}

/** 直播清晰度(qn + 描述,如 10000/原画)。 */
data class LiveQuality(
  val qn: Int,
  val description: String,
)

/**
 * 直播播放信息:一条可播流 URL + 可选清晰度列表。
 * 直播不走 DASH/合成 MPD,直接把 [streamUrl] 喂给 HlsMediaSource([isHls]=true)或
 * ProgressiveMediaSource([isHls]=false,FLV)。
 */
data class LivePlayInfo(
  val roomId: Long,
  val streamUrl: String,
  /** true=HLS(m3u8)用 HlsMediaSource;false=FLV 用 ProgressiveMediaSource。 */
  val isHls: Boolean,
  /** 当前实际下发清晰度(服务端可能因不可用而降级)。 */
  val currentQn: Int,
  /** 该直播间可选清晰度(已按 accept_qn 过滤)。 */
  val qualities: List<LiveQuality>,
  /** 播放流所需的 HTTP 头(Cookie/Referer/UA)。 */
  val headers: BiliPlaybackHeaders,
)

data class PlaybackInfo(
  val bvid: String,
  val cid: Long,
  val title: String,
  val durationMs: Long,
  val qualities: List<PlaybackQuality>,
  val selectedQuality: PlaybackQuality,
  val videoTracks: List<PlaybackTrack>,
  val audioTracks: List<PlaybackTrack>,
  val headers: BiliPlaybackHeaders,
  /** YouTube 多语言配音:全部可选音轨(供播放器音轨切换菜单)。非 YouTube/单音轨为空。 */
  val availableAudioTracks: List<PlaybackAudioTrack> = emptyList(),
  /** YouTube 字幕轨(WebVTT URL,来自 NewPipe info.subtitles)。播放器用 MergingMediaSource 合并渲染。非 YouTube/无字幕为空。 */
  val subtitleTracks: List<PlaybackTrack> = emptyList(),
  /**
   * alpha.88:RELOAD 闭环兜底——远程 DASH manifest URL(NewPipe `StreamInfo.dashMpdUrl`,android streamingData)。
   * 非空时播放器 [buildDashMediaItem] 直接用此 URL 喂 DashMediaSource(ExoPlayer 拉远程 MPD + 分段),
   * 跳过合成 data: MPD。对齐 LibreTube SABR RELOAD 崩后落 `streams.dash`。RELOAD 闭环(WEB attested reload)
   * 失败时由 [YoutubePlaybackResolver.buildDashFallbackFromNewPipe] 填,≤1080p。
   * videoTracks 仅一条 dummy(segmentBase 非 null → isProgressive=false → 路由 DashMediaSource 分支),
   * 真实轨由远程 MPD 定义。非兜底场景为 null。
   */
  val remoteDashManifestUrl: String? = null,
  /**
   * alpha.90:HLS 兜底——远程 HLS manifest URL(NewPipe `StreamInfo.hlsUrl`,visionOS /player 的
   * `hls_variant` manifest)。alpha.88 的 [remoteDashManifestUrl] 依赖 android streamingData 的 dashMpdUrl,
   * 但 Phase 0 真机取证坐实 visionOS getInfo 的 **dashMpdUrl 恒空**(android 无 poToken 取不到 protected
   * manifest),故 alpha.88 DASH 兜底实际从不触发。visionOS 是 Apple 平台,YouTube 给 visionOS 的 hlsUrl 是
   * Apple 平台原生 HLS 交付(AVPlayer 级),Phase 0 取证 hlsUrl **非空**——作 dashMpdUrl 空时的次选兜底,
   * 喂 [androidx.media3.exoplayer.hls.HlsMediaSource](manifest 自带多码率 + A/V,无需 init/index range 拼接)。
   * 非空时播放器走 HlsMediaSource 分支(优先于 DashMediaSource),真实轨由远程 HLS playlist 定义。
   * 非兜底场景为 null。
   */
  val remoteHlsManifestUrl: String? = null,
)

/** YouTube 一条可选音轨(多语言配音)。id 为 audioTrack.id(如 "en.4"),非 itag。 */
data class PlaybackAudioTrack(
  val id: String,
  val languageCode: String?,
  val displayName: String?,
  val isDefault: Boolean,
)

data class PlaybackQuality(
  val id: Int,
  val description: String,
)

data class PlaybackVideoMetadata(
  val aid: Long,
  val bvid: String,
  val cid: Long,
  val title: String,
  val ownerName: String,
  val ownerFace: String,
  val ownerMid: Long,
  val viewCount: Int,
  val danmakuCount: Int,
  val pubdate: Long,
  val pages: List<PlaybackEpisode>,
  /** 视频简介(B 站 view 接口 data.desc / PGC season.desc),移动端播放页简介 Tab 展示。 */
  val desc: String = "",
  /** 互动计数(B 站 view 接口 data.stat)。PGC 无,默认 0。 */
  val likeCount: Int = 0,
  val coinCount: Int = 0,
  val favoriteCount: Int = 0,
  val shareCount: Int = 0,
  /** 当前用户对该视频的互动状态(B 站 view 接口 data.req_user,仅登录态返回)。 */
  val liked: Boolean = false,
  val coined: Boolean = false,
  val faved: Boolean = false,
)

data class PlaybackEpisode(
  val cid: Long,
  val page: Int,
  val title: String,
  val durationSeconds: Int,
  /** PGC 剧集 ep_id；UGC 多 P 为 0。 */
  val epId: Long = 0L,
)

data class PlaybackTrack(
  val id: Int,
  val baseUrl: String,
  val backupUrls: List<String>,
  val bandwidth: Int,
  val codecs: String,
  val width: Int,
  val height: Int,
  val mimeType: String,
  /**
   * alpha.64(端口 LibreTube Representation):视频帧率(fps,/player adaptiveFormats 的 fps 字段)。
   * media3 Format.setFrameRate 用;SABR 单流 Representation 建表需此(非 SABR 路径忽略)。
   */
  val fps: Int = 0,
  /**
   * DASH SegmentBase 信息（B 站 playurl 有；YouTube progressive 直链为 null）。
   * null 时播放器走 progressive [MergingMediaSource] 分支，不经 MPD 合成。
   */
  val segmentBase: PlaybackSegmentBase? = null,
  /**
   * alpha.59(Phase 2 DASH):SABR 合成 DASH 轨标记。SABR 无 indexRange/initRange(服务端按段发),
   * 播放器须走 DASH 分支 + MPD 用 SegmentTemplate(每段一个 sabr:// seg/init URL),而非 progressive
   * MergingMediaSource。segmentBase 仍为 null(无 indexRange),故 [isProgressive] 为 true——播放器分支
   * 判断须额外排除本标记(见 PlayerScreen/MobilePlayerScreen 的 `&& !isSabrDash`)。
   *
   * **alpha.64 起此路径退役**(改走 [isSabrSingle] 自定义 SabrMediaSource),但保留字段+合成 DASH 死代码,
   * 待真机验证单流跑通后在后续 alpha 删除。
   */
  val isSabrDash: Boolean = false,
  /**
   * alpha.64(单流移植):SABR 单流自定义 MediaSource 轨标记。播放器据此走 [SabrMediaSource] 分支
   *(A/V 两 ChunkSampleStream 共享一个 SabrMediaFetcher,单流 POST 修 60s 断崖 + A/V 同步 + 后台音频)。
   * 取代 [isSabrDash] 合成 DASH 双流路径。segmentBase 仍为 null → [isProgressive] 为 true,
   * 播放器分支判断须优先排除本标记(见 PlayerScreen/MobilePlayerScreen 的 `effectiveInfo.isSabrSingle()`)。
   */
  val isSabrSingle: Boolean = false,
  /** 字幕轨语言码(YouTube WebVTT 字幕用,如 "zh-Hans"/"en")。A/V 轨为 null。 */
  val languageCode: String? = null,
) {
  /** 是否为 progressive 直链（无 DASH SegmentBase），如 YouTube 流。 */
  val isProgressive: Boolean
    get() = segmentBase == null

  val isH264: Boolean
    get() = codecs.contains("avc", ignoreCase = true)

  /**
   * 杜比视界（dvhe/dvh1/dav1 等，codecs 以 "dv" 开头）。设备普遍无 DV 解码器，
   * CodecCapabilityProbe 也不探测，故单独识别以便 [isPlayable] 将其排除。
   */
  val isDolbyVision: Boolean
    get() = codecs.startsWith("dv", ignoreCase = true)

  val isH265: Boolean
    get() = (codecs.contains("hev", ignoreCase = true) || codecs.contains("hvc", ignoreCase = true)) &&
      !isDolbyVision

  val isAv1: Boolean
    get() = codecs.contains("av01", ignoreCase = true) && !isDolbyVision
}

data class PlaybackSegmentBase(
  val initializationRange: String,
  val indexRange: String,
)
