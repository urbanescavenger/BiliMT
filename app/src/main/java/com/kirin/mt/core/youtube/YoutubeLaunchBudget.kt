package com.kirin.mt.core.youtube

import com.kirin.mt.core.player.YoutubeDeliveryPriority

/**
 * P11-126:YouTube 起播(launch)预算表——**单一真源**。
 *
 * 真机判读(`logs_live_20260919_214431.log`,WEB-SABR 优先档):`PlayerScreen` 原本把整条起播
 * (resolve + CDN 选源 + 建 source + prepare)包在一个写死的 30s 里,而 WEB-SABR 优先链的**固定开销**
 * 已达 ~21-28s(PO token 铸造 ~9s + player jsUrl/signatureTimestamp ~4s + harvest WebView 冷启首页
 * 4~11s),watch 页还没开始加载预算就没了 ⇒ 整条 launch 被取消,连已经成功建好的 NewPipe 兜底会话
 * 也一起被丢弃,用户黑屏 ~99s 直至手动退出。
 *
 * 故预算按「这条链实际需要多久」给,而不是所有源共用一个数:
 * - 其它源(B站/影视库/IPTV/红果)起播实测 1~3s,30s 已极宽松 → 保持 [DefaultMs] 不变;
 * - YouTube 两条 NewPipe 链要铸 token + 抓 player js,给 [SabrOrDashMs];
 * - WEB-SABR 优先档还要额外承担 harvest WebView 冷启 → 给 [WebSabrMs]。
 *
 * 上限只是上限:[YoutubePlaybackResolver] 会按 [deadlineMs] 算剩余预算,不够就不做注定失败的
 * WEB-SABR 优先、直接落 NewPipe 主链(见 `MinWebSabrFirstBudgetMs`),不让用户白等满上限。
 *
 * @see com.kirin.mt.core.player.YoutubeDeliveryPriority
 */
object YoutubeLaunchBudget {
  /** B站 / 影视库(TVBox) / IPTV / 红果 / 未知源——原本的 30s,不动。 */
  const val DefaultMs = 30_000L

  /** YouTube「SABR 优先」/「DASH 优先」档:铸 token + 抓 player js,留出余量。 */
  const val SabrOrDashMs = 45_000L

  /** YouTube「WEB-SABR 优先」档:额外承担 harvest WebView 冷启(建实例 + 载首页 4~11s)。 */
  const val WebSabrMs = 90_000L

  /** 按交付档取值;[YoutubeDeliveryPriority] 是唯一分档依据。 */
  fun forPriority(priority: YoutubeDeliveryPriority): Long = when (priority) {
    YoutubeDeliveryPriority.WebSabr -> WebSabrMs
    YoutubeDeliveryPriority.Sabr, YoutubeDeliveryPriority.Dash -> SabrOrDashMs
  }
}
