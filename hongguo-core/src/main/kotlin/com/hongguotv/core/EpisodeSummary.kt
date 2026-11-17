// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** A checkpoint is a resume position, never proof that all preceding episodes were watched. */
object EpisodeSummary {
    fun total(badge: String): Int =
        Regex("(?:全|共|更新至)\\s*(\\d+)\\s*集")
            .find(badge)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 } ?: 0

    fun label(
        total: Int,
        episodeIndex: Int? = null,
        account: Boolean = false,
        ready: Boolean = true,
        loading: Boolean = false,
        markedWatched: Boolean = false,
        pending: Boolean = false,
        added: Int = 0,
    ): String {
        if (pending) return "待上线\n暂不可观看"
        val count = if (total > 0) "共 $total 集" + (if (added > 0) "（+$added）" else "") else "总集数待更新"
        val progress =
            when {
                markedWatched && !account -> "本机已标记看完"
                episodeIndex != null && episodeIndex >= 0 -> "看到第 ${episodeIndex+1} 集"
                !account -> "暂无本机进度"
                loading -> "账户进度读取中"
                !ready -> "账户进度待刷新"
                else -> "近50条无观看记录"
            }
        return "$count\n$progress"
    }
}
