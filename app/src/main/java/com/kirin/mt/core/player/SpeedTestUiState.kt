package com.kirin.mt.core.player

/**
 * UI state for the CDN speed test entry in settings.
 *
 * [Succeeded.results] is ordered best-first (the order returned by
 * [CdnSpeedTester.measure]), and [Succeeded.sourceLabel] identifies which video
 * the tested URLs came from (its title, or its bvid when the title is blank).
 */
sealed interface SpeedTestUiState {
  data object Idle : SpeedTestUiState
  data object Running : SpeedTestUiState
  data object NoLastVideo : SpeedTestUiState
  data object Failed : SpeedTestUiState
  data class Succeeded(
    val results: List<CdnSpeedTester.Measurement>,
    val sourceLabel: String,
    /**
     * Wall-clock time spent resolving the playurl API call (api.bilibili.com)
     * that produced the tested URLs — the part of "open" latency the CDN TTFB
     * does NOT cover. Shown alongside the CDN TTFB so the user sees where the
     * seconds actually go.
     */
    val playurlResolveMs: Long,
  ) : SpeedTestUiState
}