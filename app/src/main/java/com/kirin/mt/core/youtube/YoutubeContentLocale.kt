package com.kirin.mt.core.youtube

/**
 * 进程级 YouTube 内容地区 holder。`InnerTubeClient.buildContext` + `sabrClientInfo` 在每次
 * 请求时读 [gl]/[hl],让 gl/hl 跟随用户设置运行时变化,而无需把地区参数逐层透传过
 * `YoutubeRepository`(browse/search)/`YoutubePlaybackResolver`(player)/SABR 的所有调用点
 * (那 blast radius 过大,见 plan)。
 *
 * 默认 [YoutubeContentRegion.US] = 历史写死的 `YoutubeConstants.Gl="US"`/`Hl="en"`,首次启动
 * 行为不变。AppShell/MobileApp 收集到 settings 后写 [current](幂等,值相同)。
 */
object YoutubeContentLocale {
  @Volatile
  var current: YoutubeContentRegion = YoutubeContentRegion.US

  val gl: String get() = current.gl
  val hl: String get() = current.hl
}