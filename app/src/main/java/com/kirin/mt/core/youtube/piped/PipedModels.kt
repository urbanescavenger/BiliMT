package com.kirin.mt.core.youtube.piped

import kotlinx.serialization.Serializable

/**
 * Piped 后端 `/streams/{videoId}` 响应(对齐 LibreTube `api/obj/Streams.kt`,仅取 SABR 相关字段子集)。
 *
 * Piped 实例自带 poToken 请求 YouTube,回**已 attested 的 WEB-bound** `serverAbrStreamingUrl` +
 * `videoPlaybackUstreamerConfig`——这是修 YouTube SABR `RELOAD_PLAYER_RESPONSE` 死循环的关键
 * (见 [docs/youtube-hd-playback.md]「alpha.83 更正」段:NewPipe visionOS 拿未 attested 的
 * visionOS-bound ustreamerConfig → 对需 attestation 的视频服务端直接 RELOAD;Piped 路径拿到的
 * config 已 attested,首请求发空 poToken,服务端回 status=2 时才铸 WEB poToken 续命)。
 *
 * 用 kotlinx.serialization 解码(`AppContainer.json` 已配 `ignoreUnknownKeys=true`,Piped 响应里
 * 我们不用的字段(title/description/uploader/...)会被安全忽略)。所有字段可空——Piped 实例版本
 * 漂移时缺字段不崩,缺关键字段(sabrUrl/ustreamerConfig)由 [PipedClient.fetchStreams] 调用方判 null 回退。
 */
@Serializable
data class PipedStreams(
  /** SABR 网关端点(Piped 已带 poToken 拿到的 attested URL)。null/空 = 无 SABR,回退 NewPipe。 */
  val serverAbrStreamingUrl: String? = null,
  /** SABR ustreamerConfig(URL-safe base64,由 `SabrSession.fromSabrData` 解码)。null/空 = 无 SABR。 */
  val videoPlaybackUstreamerConfig: String? = null,
  /** 全部视频流(Piped 服务端已正确分离音视频,无 NewPipe itag248 误分类问题)。 */
  val videoStreams: List<PipedStream> = emptyList(),
  /** 全部音频流。 */
  val audioStreams: List<PipedStream> = emptyList(),
  /** 视频时长(秒)。 */
  val duration: Long = 0L,
  val title: String? = null,
  /** HLS 兜底(暂不用,保留)。 */
  val hls: String? = null,
)

/**
 * Piped 单条流(对齐 LibreTube `api/obj/PipedStream.kt`,仅取 SABR manifest/选轨所需字段)。
 * `itag`/`lastModified`/`xtags` 喂 SABR [com.kirin.mt.core.youtube.sabr.FormatId];
 * `mimeType`/`codec`/`width`/`height`/`fps`/`bitrate` 喂 [com.kirin.mt.core.player.PlaybackTrack];
 * `audioTrackId`/`audioTrackType`/`audioTrackLocale` 喂多音轨 [com.kirin.mt.core.youtube.sabr.SabrAudioTrack]。
 */
@Serializable
data class PipedStream(
  val url: String? = null,
  val mimeType: String? = null,
  val codec: String? = null,
  val bitrate: Int? = null,
  val width: Int? = null,
  val height: Int? = null,
  val fps: Int? = null,
  val itag: Int? = null,
  val lastModified: Long? = null,
  val audioTrackId: String? = null,
  val audioTrackType: String? = null,
  val audioTrackLocale: String? = null,
  val audioTrackName: String? = null,
  val xtags: String? = null,
)